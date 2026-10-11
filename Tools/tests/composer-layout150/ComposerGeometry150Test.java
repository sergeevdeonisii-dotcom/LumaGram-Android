package org.telegram.ui.Components;

public final class ComposerGeometry150Test {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static boolean same(float a, float b) { return Math.abs(a - b) < .01f; }
    public static void main(String[] args) {
        for (float density : new float[]{1f, 1.5f, 2f, 2.625f, 3f, 4f}) {
            for (int width : new int[]{240, 320, 360, 411, 600, 800, 1024}) {
                for (int height : new int[]{194, 280, 400}) {
                    for (int diameter : new int[]{40, 42, 44}) {
                        // Real control/overlay locations, including composer outer7 and overlay -3.
                        int controlLeft = Math.round((width - 51) * density);
                        int controlTop = Math.round((height - 44) * density);
                        int controlSize = Math.round(44 * density);
                        float cx = LumaComposerGeometry.localCenter(controlLeft, controlSize, Math.round(-3 * density));
                        float cy = LumaComposerGeometry.localCenter(controlTop, controlSize, 0);
                        float radius = LumaComposerGeometry.boundedRadius(diameter * density, cx, cy, width * density, height * density);
                        for (float scale : new float[]{0f, .1f, .5f, 1f}) {
                            // Android reports the transformed origin, not unscaled layout-left.
                            int transformedLeft = Math.round(controlLeft + controlSize / 2f * (1 - scale));
                            int transformedTop = Math.round(controlTop + controlSize / 2f * (1 - scale));
                            float scaledCx = LumaComposerGeometry.localCenter(transformedLeft, controlSize * scale, Math.round(-3 * density));
                            float scaledCy = LumaComposerGeometry.localCenter(transformedTop, controlSize * scale, 0);
                            check(Math.abs(scaledCx - cx) <= .51f && Math.abs(scaledCy - cy) <= .51f, "scaled hidden action does not shift record circle");
                        }
                        check(radius > 0 && radius <= 22 * density, "bounded recording diameter");
                        check(cx - radius >= 0 && cx + radius <= width * density, "not clipped horizontally");
                        check(cy - radius >= 0 && cy + radius <= height * density, "not clipped vertically/IME");
                        float screenCenter = cx - 3 * density;
                        float capsuleRight = (width - 59) * density;
                        check(screenCenter - radius - capsuleRight >= 7 * density, "right circle remains separate");
                    }
                }
            }
            check(same((7 + LumaComposerGeometry.sendAsTextInsetDp(true, 36) - 59) * density, 12 * density), "send-as inset matches ordinary text");
            check(same(LumaComposerGeometry.sendAsTextTranslation(true, -43 * density), 0), "avatar animation never translates right emoji/text");
            check(same(LumaComposerGeometry.sendAsTextTranslation(false, -43 * density), -43 * density), "stock sender animation preserved");
        }
        check(LumaComposerGeometry.replyTextInsetDp(true, false) == 64, "reply text one inset");
        check(LumaComposerGeometry.replyTextInsetDp(true, true) == 108, "reply image34 +gap10");
        check(LumaComposerGeometry.replyTextInsetDp(false, false) == 52, "stock reply retained");
        check(LumaComposerGeometry.replyTextInsetDp(false, true) == 96, "stock image reply retained");
        check(LumaComposerGeometry.sendAsTextInsetDp(false, 36) == 104, "stock send-as retained");
        check(LumaComposerGeometry.recordDiameterDp(71) == 44 && LumaComposerGeometry.recordDiameterDp(1) == 32, "configured radius bounded");
        check(LumaComposerGeometry.boundedRadius(Float.NaN, 10, 10, 20, 20) == 0, "invalid radius safe");
        check(LumaComposerGeometry.boundedRadius(44, -1, 10, 20, 20) == 0, "outside viewport safe");
        System.out.println("PASS: " + assertions + " geometry assertions, density/narrow/landscape/IME bounds; not device layout.");
    }
}
