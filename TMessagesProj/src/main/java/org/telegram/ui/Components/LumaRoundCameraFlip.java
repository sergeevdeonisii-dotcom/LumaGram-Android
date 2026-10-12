package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.animation.LinearInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

/** A UI-only camera turn after the target has actually produced a preview frame. */
public final class LumaRoundCameraFlip {
    private static final long DURATION_MS = 140L;
    private static final long MAX_LIFETIME_MS = 220L;

    public interface Target {
        void setRotationY(float rotation);
        void hidePlaceholder();
    }

    private final Target target;
    private ValueAnimator animator;
    private Runnable deadline;
    private long generation;
    private boolean awaitingFirstFrame;

    public LumaRoundCameraFlip(Target target) {
        this.target = target;
    }

    public static boolean animationsEnabled() {
        try {
            return SharedConfig.animationsEnabled()
                    && AndroidUtilities.getAnimatorDurationScale() > 0f
                    && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Call before displaying the next full-size captured frame. HAL latency has no transform. */
    public void begin() {
        cancel();
        awaitingFirstFrame = true;
    }

    public void firstFrame(boolean animate) {
        if (!awaitingFirstFrame) return;
        awaitingFirstFrame = false;
        if (!animate) {
            cancel();
            return;
        }
        final long ticket = generation;
        // The target frame is already rendered. Do not cover it for another
        // outward half-turn: only turn the new face in, keeping the HAL wait
        // full-sized and preserving the familiar camera-flip feedback.
        target.hidePlaceholder();
        final ValueAnimator created = ValueAnimator.ofFloat(0f, 1f);
        animator = created;
        created.setDuration(DURATION_MS);
        created.setInterpolator(new LinearInterpolator());
        created.addUpdateListener(animation -> {
            if (ticket != generation || animation != animator) return;
            float progress = (float) animation.getAnimatedValue();
            float deceleration = 1f - (1f - progress) * (1f - progress);
            target.setRotationY(-90f * (1f - deceleration));
        });
        created.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finish(ticket, created);
            }
        });
        // A large developer animation scale must not leave the preview turned for seconds.
        deadline = () -> finish(ticket, created);
        AndroidUtilities.runOnUIThread(deadline, MAX_LIFETIME_MS);
        created.start();
    }

    /** New switches, pause/error, hidden UI and destruction invalidate every old callback. */
    public void cancel() {
        generation++;
        clearAnimation();
        awaitingFirstFrame = false;
        target.setRotationY(0f);
        target.hidePlaceholder();
    }

    private void finish(long ticket, ValueAnimator animation) {
        if (ticket != generation || animation != animator) return;
        clearAnimation();
        target.setRotationY(0f);
        target.hidePlaceholder();
    }

    private void clearAnimation() {
        ValueAnimator previous = animator;
        animator = null;
        if (deadline != null) {
            AndroidUtilities.cancelRunOnUIThread(deadline);
            deadline = null;
        }
        if (previous != null) {
            previous.removeAllUpdateListeners();
            previous.removeAllListeners();
            previous.cancel();
        }
    }
}
