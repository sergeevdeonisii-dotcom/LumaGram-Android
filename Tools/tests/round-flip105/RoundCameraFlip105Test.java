package org.telegram.ui.Components;

public final class RoundCameraFlip105Test {
    private static int checks;
    private static final class Target implements LumaRoundCameraFlip.Target {
        float rotation;
        boolean placeholder;
        int hides;
        public void setRotationY(float value) { rotation = value; }
        public void hidePlaceholder() { hides++; placeholder = false; }
    }
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        Target target = new Target();
        LumaRoundCameraFlip flip = new LumaRoundCameraFlip(target);
        target.rotation = 52f;
        flip.begin();
        target.placeholder = true;
        check(target.rotation == 0f, "A new switch restores circular preview geometry");
        check(target.hides == 0 && target.placeholder, "The old frame survives until an actual target frame");
        flip.firstFrame();
        check(!target.placeholder && target.hides == 1, "The actual frame is uncovered in the same callback without an animation deadline");
        check(target.rotation == 0f, "Frame arrival never warps or mirrors the preview");
        flip.firstFrame();
        check(target.hides == 1, "Duplicate arrival cannot affect a later overlay");
        flip.begin();
        target.placeholder = true;
        flip.cancel();
        check(!target.placeholder && target.rotation == 0f, "Pause, hide and destroy clear a pending switch");
        int hidesAfterCancel = target.hides;
        target.placeholder = true;
        flip.firstFrame();
        check(target.placeholder && target.hides == hidesAfterCancel, "Late arrival after cancellation cannot hide the next owner's placeholder");
        flip.begin();
        flip.firstFrame();
        check(!target.placeholder && target.hides == hidesAfterCancel + 1, "A new switch works after cancellation");
        System.out.println("PASS: " + checks + " production first-frame/placeholder lifecycle contracts; no hardware latency claim.");
    }
}
