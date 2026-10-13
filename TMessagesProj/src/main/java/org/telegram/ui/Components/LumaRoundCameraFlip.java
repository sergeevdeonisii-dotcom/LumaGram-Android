package org.telegram.ui.Components;

/** Keeps the old preview visible until the target frame arrives, without distorting the circle. */
public final class LumaRoundCameraFlip {
    public interface Target {
        void setRotationY(float rotation);
        void hidePlaceholder();
    }

    private final Target target;
    private boolean awaitingFirstFrame;

    public LumaRoundCameraFlip(Target target) {
        this.target = target;
    }

    /** The switch button supplies tap feedback; camera pixels keep their natural geometry. */
    public void begin() {
        target.setRotationY(0f);
        awaitingFirstFrame = true;
    }

    public void firstFrame() {
        if (!awaitingFirstFrame) return;
        awaitingFirstFrame = false;
        target.hidePlaceholder();
    }

    /** Pause/error, hidden UI and destruction invalidate a pending arrival. */
    public void cancel() {
        awaitingFirstFrame = false;
        target.setRotationY(0f);
        target.hidePlaceholder();
    }
}
