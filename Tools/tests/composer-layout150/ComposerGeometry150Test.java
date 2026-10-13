package org.telegram.ui.Components;

public final class ComposerGeometry150Test {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static boolean same(float a, float b) { return Math.abs(a - b) < .01f; }
    private static int dp(float value, float density) { return (int) Math.ceil(value * density); }

    private static void testLockedCompactGeometry() {
        for (float density : new float[]{1f, 1.5f, 2f, 2.625f, 3f, 4f}) {
            for (int configuredHeight : new int[]{1, 32, 34, 36, 38, 40, 42, 44, 71}) {
                int diameter = LumaComposerGeometry.recordDiameterDp(configuredHeight);
                int compactInset = (44 - diameter) / 2;
                int cameraHeight = LumaComposerGeometry.cameraControlsHeightDp(configuredHeight);
                check(cameraHeight == Math.max(48, diameter + 12), "capsule keeps minimum48 hit height");
                check(LumaComposerGeometry.cameraControlsLeftMarginDp(configuredHeight) + 6 == 7 + compactInset,
                        "camera visible-left matches compact input side island");
                check(cameraHeight >= 48 && cameraHeight - 12 >= diameter, "capsule drawable bleed cannot clip its circle");
                for (int width : new int[]{240, 320, 411, 600, 1024}) {
                    for (int viewport : new int[]{240, 360, 640, 800}) {
                        for (int keyboard : new int[]{0, 96, 240, 320}) {
                            int available = viewport - keyboard;
                            if (available < 180) continue; // A host cannot display the whole stack in a smaller area.
                            for (int windowOffset : new int[]{0, 24, 73}) {
                                int bottom = dp(available + windowOffset, density);
                                int controlLeft = dp(width - 51, density);
                                int controlTop = bottom - dp(7, density) - dp(44, density);
                                int controlSize = dp(44, density);
                                int circleTop = bottom - dp(7, density) - dp(194, density);
                                int controlsTop = bottom - dp(7, density) - dp(250, density);
                                int overlayLeft = -dp(3, density);
                                float sendWorldX = controlLeft + controlSize / 2f;
                                float sendWorldY = controlTop + controlSize / 2f;
                                float circleY = LumaComposerGeometry.localCenter(controlTop, controlSize, circleTop);
                                float controlsSendY = LumaComposerGeometry.localCenter(controlTop, controlSize, controlsTop);
                                float controlsX = LumaComposerGeometry.localCenter(controlLeft, controlSize, overlayLeft);
                                check(same(circleY + circleTop, sendWorldY) && same(controlsSendY + controlsTop, sendWorldY),
                                        "independent unequal overlay origins map to the same window send center");
                                check(Math.abs(controlsSendY - circleY - dp(250, density) + dp(194, density)) < .01f,
                                        "controls never reuse RecordCircle localY");
                                check(same(controlsX + overlayLeft, sendWorldX), "auxiliary stack shares measured send x");
                                float expectedPause = LumaComposerGeometry.recordAuxiliaryCenterY(controlsSendY,
                                        dp(diameter, density), dp(48, density), 0, 1);
                                float expectedOnce = LumaComposerGeometry.recordAuxiliaryCenterY(controlsSendY,
                                        dp(diameter, density), dp(48, density), 0, 2);
                                check(same(controlsSendY - expectedPause, dp(48, density)),
                                        "pause is one compact48-touch stride above send");
                                check(same(expectedPause - expectedOnce, dp(48, density)),
                                        "upper once circle is a second48dp touch stride");

                                // These are the actual onDraw stable-lock expressions, including Android ceil-dp
                                // for lockSize/insets and float-dp for the 36+12 upper-circle offset.
                                float pauseTop = expectedPause - dp(18, density);
                                float pauseCenter = pauseTop + dp(36, density) / 2f;
                                float onceCenter = pauseTop - 36 * density - 12 * density + 18 * density;
                                float pauseHalfY = dp(36, density) / 2f + dp(6, density);
                                float onceHalf = 18 * density + dp(6, density);
                                float sendCenter = Math.round(circleY) + circleTop - controlsTop;
                                float sendHalf = dp(24, density);
                                check(pauseHalfY * 2 >= 48 * density && onceHalf * 2 >= 48 * density,
                                        "pause/once native touch targets stay at least48dp at fractional density");
                                check(sendCenter - sendHalf - pauseCenter - pauseHalfY >= -2,
                                        "send and pause48 touch rectangles do not overlap (two-pixel dp rounding budget)");
                                check(pauseCenter - pauseHalfY - onceCenter - onceHalf >= -2,
                                        "pause and upper48 touch rectangles do not overlap at fractional density");
                                check(pauseCenter + pauseHalfY <= dp(250, density) - dp(44, density)
                                                && onceCenter - onceHalf >= 0,
                                        "stable locked controls are wholly inside the controls draw viewport");
                                check(onceCenter + controlsTop - onceHalf >= dp(windowOffset, density),
                                        "upper touch target remains inside available window above keyboard");
                                check(controlsX - onceHalf >= 0 && controlsX + onceHalf <= dp(width, density),
                                        "native48 auxiliary hit regions fit narrow and landscape widths");

                                float circleX = LumaComposerGeometry.localCenter(controlLeft, controlSize, overlayLeft);
                                float radius = LumaComposerGeometry.boundedRadius(dp(diameter, density),
                                        Math.round(circleX), Math.round(circleY), dp(width, density), dp(194, density));
                                check(radius > 0 && Math.round(circleX) - radius >= 0
                                                && Math.round(circleX) + radius <= dp(width, density)
                                                && Math.round(circleY) - radius >= 0
                                                && Math.round(circleY) + radius <= dp(194, density),
                                        "entire compact send circle fits actual overlay after keyboard movement");
                                float rightInset = dp(width, density) - sendWorldX - dp(diameter, density) / 2f;
                                check(Math.abs(rightInset - (7 + compactInset) * density) <= 1.5f,
                                        "left camera capsule and right send circle have symmetric outer insets");
                            }
                        }
                    }
                }
            }
            check(LumaComposerGeometry.recordTimerInsetDp(true) == 4 && LumaComposerGeometry.recordTimerInsetDp(false) == 0,
                    "timer is inset4 only for compact design");
            check(same((60 + LumaComposerGeometry.recordTimerInsetDp(true)) * density, 64 * density),
                    "timer uses ordinary64 text inset without changing dot slot");
            // Existing no-liquid expressions: controls height250, circle height194, cy170;
            // settled pause top60+(250-194)+14 and once36+12 above it.
            float stockPauseWorld = -250 + 60 + (250 - 194) + 14 + 18;
            float stockSendWorld = -194 + 170;
            float stockOnceWorld = stockPauseWorld - 36 - 12;
            check(same((stockSendWorld - stockPauseWorld) * density, 78 * density)
                            && same((stockPauseWorld - stockOnceWorld) * density, 48 * density),
                    "no-liquid legacy78/48 center spacing is retained");
        }
        check(same(LumaComposerGeometry.recordAuxiliaryCenterY(200, 60, 48, 8, 1), 138),
                "future larger send circle reserves its actual diameter");
        check(same(LumaComposerGeometry.recordAuxiliaryCenterY(200, 60, 48, 8, 2), 82),
                "second control uses native-touch stride even when send is larger");
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            check(LumaComposerGeometry.recordAuxiliaryCenterY(invalid, 44, 48, 8, 1) == 0,
                    "invalid window anchor has finite safe fallback");
            check(LumaComposerGeometry.recordAuxiliaryCenterY(200, invalid, 48, 8, 1) == 200,
                    "invalid send diameter leaves finite anchor unchanged");
            check(LumaComposerGeometry.recordAuxiliaryCenterY(200, 44, invalid, 8, 1) == 200,
                    "invalid touch size leaves finite anchor unchanged");
            check(LumaComposerGeometry.recordAuxiliaryCenterY(200, 44, 48, invalid, 1) == 200,
                    "invalid gap leaves finite anchor unchanged");
            check(LumaComposerGeometry.boundedRadius(44, invalid, 50, 100, 100) == 0,
                    "nonfinite circle center never draws outside bounds");
        }
        check(LumaComposerGeometry.recordAuxiliaryCenterY(200, -1, 48, 8, 1) == 200, "negative send diameter rejected");
        check(LumaComposerGeometry.recordAuxiliaryCenterY(200, 44, 0, 8, 1) == 200, "empty touch region rejected");
        check(LumaComposerGeometry.recordAuxiliaryCenterY(200, 44, -48, 8, 1) == 200, "negative touch region rejected");
        check(LumaComposerGeometry.recordAuxiliaryCenterY(200, 44, 48, -8, 1) == 200, "negative touch gap rejected");
        for (int position : new int[]{Integer.MIN_VALUE, -1, 0, 3, Integer.MAX_VALUE}) {
            check(LumaComposerGeometry.recordAuxiliaryCenterY(200, 44, 48, 8, position) == 200,
                    "invalid auxiliary slot rejected");
        }
        check(LumaComposerGeometry.boundedRadius(44, 50, 50, -1, 100) == 0
                        && LumaComposerGeometry.boundedRadius(44, 50, 50, 100, 0) == 0,
                "invalid or collapsed viewport cannot draw a circle");
    }
    public static void main(String[] args) {
        testLockedCompactGeometry();
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
