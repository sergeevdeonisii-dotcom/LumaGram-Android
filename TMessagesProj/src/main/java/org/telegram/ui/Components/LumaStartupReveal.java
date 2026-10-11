package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LumaBuildPolicy;
import org.telegram.messenger.LumaStartupRevealPolicy;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.function.BooleanSupplier;

/** Readable installed-release notes; only the brand masthead animates. */
public final class LumaStartupReveal extends BottomSheet {
    private static final String SEEN_VERSION = "luma_startup_notes_version";
    private static final long DURATION_MS = 640;
    private static final long MAX_MOTION_MS = 800;
    private final SharedPreferences preferences;
    private final int version;
    private final BooleanSupplier ownerReady;
    private final LinearLayout content;
    private final RevealHeader header;
    private Button continueButton;
    private int noteHeightLimit = AndroidUtilities.dp(520);
    private final Runnable finishMotion = this::stopAnimation;
    private ValueAnimator animator;
    private boolean closed;
    private boolean drawn;
    private boolean markedSeen;

    public static LumaStartupReveal maybeShow(FrameLayout parent, boolean launcherStart,
            boolean foreground, boolean protectedScreen, BooleanSupplier ownerReady) {
        if (parent == null || ownerReady == null) return null;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        int version = SharedConfig.buildVersion();
        boolean animate = animationsEnabled(parent.getContext());
        int decision = LumaStartupRevealPolicy.decide(version, preferences.getInt(SEEN_VERSION, 0),
                launcherStart, foreground && parent.isAttachedToWindow() && parent.getWidth() > 0,
                protectedScreen, animate);
        if (decision == LumaStartupRevealPolicy.DEFER || !ownerReady.getAsBoolean()) return null;
        LumaStartupReveal reveal = new LumaStartupReveal(parent.getContext(), preferences, version, ownerReady);
        reveal.setOnShowListener(dialog -> reveal.content.post(() -> {
            if (reveal.closed || !reveal.isShowing()) return;
            if (!ownerReady.getAsBoolean()) {
                reveal.dismissForLifecycle();
                return;
            }
            if (decision == LumaStartupRevealPolicy.SHOW) reveal.startAnimation();
            reveal.content.invalidate();
        }));
        try {
            reveal.show();
        } catch (RuntimeException error) {
            reveal.dismissForLifecycle();
            throw error;
        }
        return reveal.isShowing() ? reveal : null;
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

    private LumaStartupReveal(Context context, SharedPreferences preferences, int version,
            BooleanSupplier ownerReady) {
        super(context, false);
        this.preferences = preferences;
        this.version = version;
        this.ownerReady = ownerReady;
        setShowWithoutAnimation(true);
        skipDismissAnimation();
        setApplyTopPadding(false);
        setApplyBottomPadding(true);
        setCanDismissWithSwipe(false);
        setAllowNestedScroll(false);
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground));

        content = new LinearLayout(context) {
            @Override protected void onMeasure(int width, int height) {
                int available = View.MeasureSpec.getSize(height);
                if (continueButton != null && available > 0) {
                    continueButton.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0,
                            View.MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight()), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    noteHeightLimit = Math.max(0, Math.min(AndroidUtilities.dp(520), available
                            - getPaddingTop() - getPaddingBottom() - continueButton.getMeasuredHeight() - AndroidUtilities.dp(32)));
                }
                super.onMeasure(width, height);
            }
            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                drawn = true;
                markShown();
            }
            @Override protected void onDetachedFromWindow() {
                stopAnimation();
                super.onDetachedFromWindow();
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(24), AndroidUtilities.dp(20), 0);
        LinearLayout notes = new LinearLayout(context);
        notes.setOrientation(LinearLayout.VERTICAL);
        header = new RevealHeader(context);
        notes.addView(header, new LinearLayout.LayoutParams(-1, -2));
        TextView installedVersion = text(LocaleController.formatString(R.string.LumaUpdateWelcomeVersion,
                BuildConfig.BUILD_VERSION_STRING), 14, false);
        installedVersion.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(24));
        notes.addView(installedVersion);
        TextView heading = text(LocaleController.getString(R.string.LumaUpdateWelcomeChanges), 20, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        notes.addView(heading);
        if (LumaStartupRevealPolicy.hasRelease15Notes(BuildConfig.BUILD_VERSION_STRING)) {
            addNote(notes, R.string.LumaUpdateWelcomeRoundCamera);
            addNote(notes, R.string.LumaUpdateWelcomeRoundSettings);
            if (LumaBuildPolicy.allowsProfileVerification()) addNote(notes, R.string.LumaUpdateWelcomeVerification);
            if (LumaBuildPolicy.allowsAnonymousNumber()) addNote(notes, R.string.LumaUpdateWelcomeNumberValue);
            if (LumaBuildPolicy.isPersonalEdition()) addNote(notes, R.string.LumaUpdateWelcomePersonalGifts);
        } else {
            addNote(notes, R.string.LumaUpdateWelcomeFuture);
        }
        ScrollView scroll = new ScrollView(context) {
            @Override protected void onMeasure(int width, int height) {
                int available = View.MeasureSpec.getSize(height);
                int limit = available > 0 ? Math.min(noteHeightLimit, available) : noteHeightLimit;
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.addView(notes);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        Button done = new Button(context);
        continueButton = done;
        done.setText(LocaleController.getString(R.string.LumaUpdateWelcomeContinue));
        done.setAllCaps(false);
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        done.setTypeface(AndroidUtilities.bold());
        done.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        done.setBackground(Theme.AdaptiveRipple.filledRect(Theme.getColor(Theme.key_featuredStickers_addButton), 8));
        done.setMinHeight(AndroidUtilities.dp(48));
        done.setMinimumHeight(AndroidUtilities.dp(48));
        done.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        done.setOnClickListener(view -> dismiss());
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-1, -2);
        action.topMargin = AndroidUtilities.dp(20);
        action.bottomMargin = AndroidUtilities.dp(12);
        content.addView(done, action);
        setCustomView(content);
    }

    private TextView text(CharSequence value, int sp, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private void addNote(LinearLayout notes, int resource) {
        TextView view = text(LocaleController.getString(resource), 16, false);
        view.setPadding(0, AndroidUtilities.dp(12), 0, 0);
        view.setLineSpacing(AndroidUtilities.dp(2), 1f);
        notes.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void markShown() {
        if (closed || markedSeen) return;
        if (!ownerReady.getAsBoolean()) {
            dismissForLifecycle();
            return;
        }
        if (!LumaStartupRevealPolicy.canMarkSeen(isShowing(), content.isAttachedToWindow(), drawn,
                getWindow() != null && getWindow().getDecorView().hasWindowFocus(), true)) return;
        markedSeen = true;
        // Write only after this dialog's content actually drew in its focused window.
        preferences.edit().putInt(SEEN_VERSION, Math.max(version, preferences.getInt(SEEN_VERSION, 0))).apply();
    }

    private void startAnimation() {
        if (closed || animator != null || !animationsEnabled(getContext())) return;
        header.progress = .12f;
        final ValueAnimator current = ValueAnimator.ofFloat(.12f, 1f);
        animator = current;
        current.setDuration(DURATION_MS);
        current.setInterpolator(new LinearInterpolator());
        current.addUpdateListener(value -> {
            if (closed || animator != current) return;
            float progress = (float) value.getAnimatedValue();
            header.progress = 1f - (float) Math.pow(1f - progress, 3);
            header.invalidate();
        });
        current.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator == current) stopAnimation();
            }
        });
        content.postDelayed(finishMotion, MAX_MOTION_MS);
        current.start();
    }

    private void stopAnimation() {
        content.removeCallbacks(finishMotion);
        ValueAnimator old = animator;
        animator = null;
        if (old != null) {
            old.removeAllListeners();
            old.removeAllUpdateListeners();
            old.cancel();
        }
        header.progress = 1f;
        header.invalidate();
    }

    @Override public void show() {
        super.show();
        // BottomSheet's no-animation branch does not reveal its initially invisible container.
        if (isShowing() && containerView != null) containerView.setVisibility(View.VISIBLE);
    }

    public void onMotionPreferenceChanged() {
        if (!animationsEnabled(getContext())) stopAnimation();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (content == null) return;
        if (!ownerReady.getAsBoolean()) {
            dismissForLifecycle();
            return;
        }
        if (hasFocus) content.invalidate();
        else stopAnimation();
    }

    @Override public void dismiss() {
        if (closed) return;
        closed = true;
        stopAnimation();
        super.dismiss();
    }

    public void dismissForLifecycle() {
        if (closed && !isShowing()) return;
        closed = true;
        stopAnimation();
        cancelSheetAnimation();
        content.setVisibility(View.GONE);
        // A lock/background transition must not leave an animated closing dialog over protected UI.
        dismissInternal();
    }

    private final class RevealHeader extends LinearLayout {
        private float progress = 1f;
        RevealHeader(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            setGravity(Gravity.CENTER_VERTICAL);
            ImageView logo = new ImageView(context);
            logo.setImageResource(R.mipmap.ic_luma_launcher);
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(logo, new LinearLayout.LayoutParams(AndroidUtilities.dp(64), AndroidUtilities.dp(64)));
            TextView name = text(LocaleController.getString(R.string.LumaUpdateWelcomeName), 28, true);
            name.setPaddingRelative(AndroidUtilities.dp(16), 0, 0, 0);
            if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
            addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        @Override protected void dispatchDraw(Canvas canvas) {
            int save = canvas.save();
            float width = getWidth() * progress;
            if (LocaleController.isRTL) canvas.clipRect(getWidth() - width, 0, getWidth(), getHeight());
            else canvas.clipRect(0, 0, width, getHeight());
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
