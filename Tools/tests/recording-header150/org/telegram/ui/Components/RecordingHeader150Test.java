package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** Actual production draw() against a capturing canvas, not Android raster/visual evidence. */
public final class RecordingHeader150Test {
    private static int assertions;
    private static void check(boolean ok, String label) {
        assertions++;
        if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] arguments) throws Exception {
        java.lang.reflect.Field progress = RoundStatusDrawable.class.getDeclaredField("progress");
        progress.setAccessible(true);
        for (float density : new float[]{.75f, 1f, 1.25f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f}) {
            AndroidUtilities.density = density;
            for (boolean chat : new boolean[]{false, true}) for (boolean ownPaint : new boolean[]{false, true}) {
                RoundStatusDrawable header = new RoundStatusDrawable(ownPaint, true);
                RoundStatusDrawable legacy = new RoundStatusDrawable(ownPaint);
                header.setIsChat(chat); legacy.setIsChat(chat);
                int width = header.getIntrinsicWidth(), height = header.getIntrinsicHeight();
                check(width == AndroidUtilities.dp(12) && height == AndroidUtilities.dp(10), "intrinsic layout widths/heights unchanged");
                for (int[] origin : new int[][]{{0, 0}, {7, 13}, {-37, -21}, {17, -33}}) {
                    int left = origin[0], top = origin[1];
                    header.setBounds(left, top, left + width, top + height);
                    legacy.setBounds(left, top, left + width, top + height);
                    for (float phase : new float[]{0f, .25f, .5f, .75f, 1f}) {
                        progress.setFloat(header, phase); progress.setFloat(legacy, phase);
                        for (int color : new int[]{0xff88aaff, 0xff4455cc, 0xffdedede}) {
                            header.setColor(color); legacy.setColor(color); Theme.chat_statusPaint.setColor(color);
                            Canvas actual = new Canvas(), old = new Canvas();
                            header.draw(actual); legacy.draw(old);
                            check(actual.calls == 1 && actual.y == top + height / 2f, "header circle centered in its real drawable bounds");
                            check(actual.x == old.x && actual.radius == old.radius && actual.radius == AndroidUtilities.dp(4), "horizontal placement and dot radius unchanged");
                            check(old.y == top + AndroidUtilities.dp(chat ? 8 : 9), "legacy list/popup constructor preserves original private/group placement");
                            check(actual.alpha == old.alpha && actual.alpha == 55 + (int) (200 * phase), "animation phases keep original alpha envelope");
                            check(actual.color == color, "own/shared theme paint colors retained");
                            check(actual.y - actual.radius >= top && actual.y + actual.radius <= top + height,
                                    "dot fits intrinsic bitmap snapshots without bottom clipping");
                            check(header.getBounds().left == left && header.getBounds().top == top && header.getBounds().bottom == top + height,
                                    "draw does not mutate caller bounds");
                        }
                    }
                }
                // A host may supply taller/odd bounds; use the actual center, not a dp correction.
                header.setBounds(-8, -15, width - 8, height * 2 - 14);
                Canvas stretched = new Canvas(); header.draw(stretched);
                check(stretched.y == header.getBounds().exactCenterY(), "odd and resized bounds remain centered");
                final int[] invalidations = {0};
                Drawable.Callback callback = drawable -> invalidations[0]++;
                header.setCallback(callback);
                header.start(); header.draw(stretched);
                check(invalidations[0] >= 2 && header.getCallback() == callback, "start/draw preserve animation callback ownership");
                header.stop(); int stopped = invalidations[0]; header.draw(stretched);
                check(invalidations[0] == stopped, "stopped drawable does not schedule another frame");
            }
        }
        System.out.println("PASS: " + assertions + " production recording-dot geometry/paint/lifecycle assertions (capturing canvas fixtures, no Android raster).");
    }
}
