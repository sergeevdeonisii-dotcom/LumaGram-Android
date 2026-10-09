package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LumaStartupRevealPolicy;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;

/** A brief, input-transparent welcome after installation/update; startup never waits for it. */
public final class LumaStartupReveal extends View {
    private static final String SEEN_VERSION = "luma_startup_reveal_version";
    private static final long DURATION_MS = 720;
    private static final long MAX_LIFETIME_MS = 900;
    private final Drawable icon;
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconClip = new Path();
    private final Runnable timeout = this::dismiss;
    private ValueAnimator animator;
    private float progress;
    private boolean dismissed;

    public static LumaStartupReveal maybeShow(FrameLayout parent, boolean launcherStart,
                                               boolean foreground, boolean protectedScreen) {
        if (parent == null) return null;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        int version = SharedConfig.buildVersion();
        int decision = LumaStartupRevealPolicy.decide(version, preferences.getInt(SEEN_VERSION, 0),
                launcherStart, foreground && parent.isAttachedToWindow() && parent.getWidth() > 0,
                protectedScreen, animationsEnabled(parent.getContext()));
        if (decision == LumaStartupRevealPolicy.DEFER) return null;
        // apply() updates the in-process preferences immediately, including configuration recreation.
        preferences.edit().putInt(SEEN_VERSION, version).apply();
        if (decision != LumaStartupRevealPolicy.SHOW) return null;
        LumaStartupReveal reveal = new LumaStartupReveal(parent.getContext());
        parent.addView(reveal, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        reveal.start();
        return reveal;
    }

    private static boolean animationsEnabled(Context context) {
        if (!SharedConfig.animationsEnabled() || LiteMode.isPowerSaverApplied()) return false;
        try {
            if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return false;
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception ignored) {
            return false;
        }
    }

    private LumaStartupReveal(Context context) {
        super(context);
        icon = context.getDrawable(R.mipmap.ic_luma_launcher).mutate();
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        haloPaint.setShader(new RadialGradient(0, 0, AndroidUtilities.dp(98),
                new int[]{0x604A7CFF, 0x2823D5F7, 0x00A855F7},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
    }

    private void start() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                dismiss();
            }
        });
        // Bound wall-clock time even when Android's developer animation scale is very large.
        postDelayed(timeout, MAX_LIFETIME_MS);
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float entrance = Math.min(1f, progress / .42f);
        entrance = 1f - (float) Math.pow(1f - entrance, 3);
        float exit = Math.max(0f, (progress - .48f) / .52f);
        float alpha = entrance * (1f - exit);
        float scale = .88f + .12f * entrance + .04f * exit;
        int save = canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(scale, scale);
        haloPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawCircle(0, 0, AndroidUtilities.dp(98), haloPaint);
        float radius = AndroidUtilities.dp(52);
        iconClip.rewind();
        iconClip.addCircle(0, 0, radius, Path.Direction.CW);
        canvas.clipPath(iconClip);
        icon.setAlpha(Math.round(255 * alpha));
        icon.setBounds(-(int) radius, -(int) radius, (int) radius, (int) radius);
        icon.draw(canvas);
        canvas.restoreToCount(save);
    }

    public void dismiss() {
        if (dismissed) return;
        dismissed = true;
        stopAnimation();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    private void stopAnimation() {
        removeCallbacks(timeout);
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        dismissed = true;
        stopAnimation();
        super.onDetachedFromWindow();
    }
}
