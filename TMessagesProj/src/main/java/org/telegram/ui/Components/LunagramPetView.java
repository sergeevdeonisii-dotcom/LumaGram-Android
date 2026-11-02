package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LunagramExperienceSettings;
import org.telegram.messenger.LunagramPetMotion;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;

/** Touch-through ginger cat, authored bitmap poses, display-synchronised motion and quiet idle. */
public final class LunagramPetView extends View {
    private static final int CELL_DP = 56;
    private static final int FLOOR_OFFSET_DP = 22;
    private final int account;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final float[] tailMesh = new float[(LunagramPetAtlas.MESH_SEGMENTS + 1) * (LunagramPetAtlas.MESH_SEGMENTS + 1) * 2];
    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LunagramPetMotion motion;
    private LunagramPetAtlas atlas;
    private EditText composer;
    private boolean recording, paused, disposed, explicitRail, tickPosted, frameTick;
    private long tickAt;
    private long motionPolicyAt = Long.MIN_VALUE;
    private boolean motionPolicyAllowed;
    private float railLeft, railRight;
    private final Runnable tick = this::onTick;
    private void onTick() {
        tickPosted = false;
        syncActivity();
        invalidate();
        schedule();
    }
    private final TextWatcher watcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            syncActivity();
            motion.onTyping(SystemClock.elapsedRealtime());
            invalidate(); schedule();
        }
        @Override public void afterTextChanged(Editable value) {}
    };
    public LunagramPetView(Context context, int account) {
        super(context); this.account = account;
        motion = new LunagramPetMotion(UserConfig.getInstance(account).getClientUserId());
        setClickable(false); setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        refresh();
    }
    /** The feet rest on the oval; the last 22dp are inside the overlay for its dangling tail. */
    public static int getFloorOffsetDp() { return FLOOR_OFFSET_DP; }
    public void setRailBounds(float leftPx, float rightPx) {
        float nextLeft = finite(leftPx) ? leftPx : 0;
        float nextRight = finite(rightPx) ? Math.max(nextLeft, rightPx) : nextLeft;
        if (explicitRail && Float.compare(nextLeft, railLeft) == 0 && Float.compare(nextRight, railRight) == 0) return;
        explicitRail = true; railLeft = nextLeft; railRight = nextRight;
        updateRail(); invalidate(); schedule();
    }
    public void bindComposer(EditText value) {
        if (composer == value) return;
        if (composer != null) composer.removeTextChangedListener(watcher);
        composer = disposed ? null : value;
        if (composer != null) composer.addTextChangedListener(watcher);
    }
    public void setRecording(boolean value) { if (recording == value) return; recording = value; refresh(); }
    public void setPaused(boolean value) { if (paused == value) return; paused = value; syncActivity(); invalidate(); schedule(); }
    public void refresh() {
        motionPolicyAt = Long.MIN_VALUE;
        setVisibility(!disposed && !recording && LunagramExperienceSettings.pet(account) != 0 ? VISIBLE : GONE);
        ensureAtlas(); syncActivity(); invalidate(); schedule();
    }
    public void onMessageSent() {
        motionPolicyAt = Long.MIN_VALUE;
        syncActivity(); motion.onMessageSent(SystemClock.elapsedRealtime()); invalidate(); schedule();
    }
    public void dispose() {
        disposed = true; bindComposer(null); cancelTick(); LunagramPetAtlas.cancel(this);
        motion.dispose(); atlas = null; setVisibility(GONE);
    }
    @Override public void setAlpha(float alpha) {
        if (Float.compare(alpha, getAlpha()) == 0) return;
        super.setAlpha(alpha);
        if (motion != null) { syncActivity(); schedule(); }
    }
    @Override public boolean onTouchEvent(MotionEvent event) { return false; }
    @Override protected void onAttachedToWindow() {
        motionPolicyAt = Long.MIN_VALUE;
        super.onAttachedToWindow(); ensureAtlas(); updateRail(); syncActivity(); schedule();
    }
    @Override protected void onDetachedFromWindow() {
        cancelTick(); LunagramPetAtlas.cancel(this);
        motion.setActive(false, SystemClock.elapsedRealtime()); super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        motionPolicyAt = Long.MIN_VALUE;
        super.onVisibilityChanged(changed, visibility);
        if (motion != null) { ensureAtlas(); syncActivity(); schedule(); }
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        motionPolicyAt = Long.MIN_VALUE;
        super.onWindowVisibilityChanged(visibility);
        if (motion != null) { syncActivity(); schedule(); }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh); updateRail(); schedule();
    }
    private void ensureAtlas() {
        if (atlas == null && !disposed && getVisibility() == VISIBLE && LunagramExperienceSettings.pet(account) != 0) {
            atlas = LunagramPetAtlas.request(this);
            if (atlas != null) updateRail();
        }
    }
    void onAtlasReady(LunagramPetAtlas decoded) {
        if (disposed) return;
        atlas = decoded; updateRail(); syncActivity(); invalidate(); schedule();
    }
    private void syncActivity() {
        if (motion == null) return;
        long now = SystemClock.elapsedRealtime();
        motion.setOwner(UserConfig.getInstance(account).getClientUserId(), now);
        motion.setMotionAllowed(animationsAllowed(), now);
        motion.setActive(!disposed && !recording && !paused && !ApplicationLoader.mainInterfacePaused
                && atlas != null && LunagramExperienceSettings.pet(account) != 0 && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && getAlpha() > 0, now);
    }
    private boolean animationsAllowed() {
        long now = SystemClock.elapsedRealtime();
        if (motionPolicyAt != Long.MIN_VALUE && now >= motionPolicyAt && now - motionPolicyAt < 1000) return motionPolicyAllowed;
        motionPolicyAt = now;
        try {
            PowerManager power = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            motionPolicyAllowed = SharedConfig.animationsEnabled() && AndroidUtilities.getAnimatorDurationScale() > 0
                    && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
                    && (Build.VERSION.SDK_INT < 21 || power == null || !power.isPowerSaveMode());
        } catch (RuntimeException ignored) { motionPolicyAllowed = false; }
        return motionPolicyAllowed;
    }
    private void cancelTick() {
        removeCallbacks(tick); tickPosted = false;
    }
    private void schedule() {
        if (motion == null) return;
        long now = SystemClock.elapsedRealtime(), delay = motion.nextDelayMillis(now);
        if (delay == LunagramPetMotion.NO_CALLBACK) { cancelTick(); return; }
        if (delay == LunagramPetMotion.NEXT_FRAME) {
            if (tickPosted && frameTick) return;
            cancelTick(); tickPosted = frameTick = true;
            postOnAnimation(tick);
        } else {
            long deadline = now + delay;
            if (tickPosted && !frameTick && tickAt == deadline) return;
            cancelTick(); tickPosted = true; frameTick = false; tickAt = deadline;
            postDelayed(tick, Math.max(1, delay));
        }
    }
    private float left() { return Math.max(0, explicitRail ? railLeft : 0); }
    private float right() { return Math.min(getWidth(), explicitRail ? railRight : getWidth()); }
    private float size() {
        if (atlas == null) return 0;
        return atlas.fittedSize(AndroidUtilities.dpf2(CELL_DP), Math.max(0, right() - left() - AndroidUtilities.dp(4)),
                Math.max(0, getHeight() - AndroidUtilities.dp(FLOOR_OFFSET_DP) - AndroidUtilities.dp(2)),
                AndroidUtilities.dp(FLOOR_OFFSET_DP));
    }
    private void updateRail() {
        if (motion != null) motion.setRailBounds(left(), Math.max(left(), right()),
                atlas == null ? 0 : atlas.footprint(size()) + AndroidUtilities.dp(4), size(), SystemClock.elapsedRealtime());
    }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    @Override protected void onDraw(Canvas canvas) {
        if (atlas == null || LunagramExperienceSettings.pet(account) == 0 || recording || paused || disposed
                || getWidth() <= 0 || getHeight() <= 0) return;
        LunagramPetMotion.Sample state = motion.sample(SystemClock.elapsedRealtime());
        float size = size(); if (size <= 0) return;
        canvas.save(); canvas.clipRect(left(), 0, Math.max(left(), right()), getHeight());
        final float floor = getHeight() - AndroidUtilities.dp(FLOOR_OFFSET_DP);
        canvas.save();
        canvas.translate(state.x, getHeight() - AndroidUtilities.dp(FLOOR_OFFSET_DP)
                - AndroidUtilities.dpf2(state.bounce));
        // Continuous subpixel translation runs at the display cadence.
        // Source silhouettes remain single, crisp poses; no alpha-blended double limbs.
        canvas.scale(state.facing, 1);
        atlas.draw(canvas, state.frame, size, paint, destination, state.tail, tailMesh);
        canvas.restore();
        // The released toy starts in front of the paws. Drawing it behind the
        // new no-ball sprite would hide it until it has already left the cat.
        if (state.ballVisible) drawBall(canvas, state, floor);
        canvas.restore();
        // Drawing never schedules work. Idle has one delayed wakeup, moving bouts use vsync.
    }
    private void drawBall(Canvas canvas, LunagramPetMotion.Sample state, float floor) {
        float radius = state.ballRadius;
        canvas.save();
        canvas.translate(state.ballX, floor - state.ballHeight - radius);
        canvas.rotate(state.ballTurn);
        ballPaint.setStyle(Paint.Style.FILL); ballPaint.setColor(0xff529bdb);
        canvas.drawCircle(0, 0, radius, ballPaint);
        ballPaint.setStyle(Paint.Style.STROKE); ballPaint.setStrokeWidth(Math.max(.7f, radius * .12f));
        ballPaint.setColor(0xff96c6ec);
        destination.set(-radius * .6f, -radius * .78f, radius * .6f, radius * .78f);
        canvas.drawOval(destination, ballPaint);
        canvas.rotate(65); canvas.drawOval(destination, ballPaint);
        ballPaint.setStyle(Paint.Style.FILL); ballPaint.setColor(0xffc3e0f6);
        canvas.drawCircle(-radius * .3f, -radius * .4f, radius * .2f, ballPaint);
        canvas.restore();
    }
}
