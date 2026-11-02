import org.telegram.messenger.LunagramSendEffectGeometry;
import java.util.Arrays;

public final class LunagramSendEffectGeometryTest {
    static int assertions;
    static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    static void clusters(String text, int... expected) { check(Arrays.equals(LunagramSendEffectGeometry.boundaries(text), expected), "cluster boundaries: " + text); }
    public static void main(String[] args) {
        clusters("", 0);
        clusters("abc", 0, 1, 2, 3);
        clusters("a\r\nb", 0, 1, 3, 4);
        clusters("e\u0301x", 0, 2, 3);
        clusters("\uD83D\uDE00x", 0, 2, 3);
        clusters("\uD83D\uDC4D\uD83C\uDFFDx", 0, 4, 5);
        clusters("\uD83C\uDDEC\uD83C\uDDE7\uD83C\uDDFA\uD83C\uDDF8", 0, 4, 8);
        clusters("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66x", 0, 11, 12);
        clusters("1\uFE0F\u20E3", 0, 3);
        clusters("مرحبا", 0, 1, 2, 3, 4, 5);
        int[] offsets = LunagramSendEffectGeometry.boundaries("A\uD83D\uDE00B");
        check(LunagramSendEffectGeometry.revealEnd(offsets, 0) == 0, "No text at start");
        check(LunagramSendEffectGeometry.revealEnd(offsets, 1) == 4, "Full text at end");
        check(LunagramSendEffectGeometry.revealEnd(offsets, .7f) == 3, "Cannot reveal half a surrogate pair");
        check(LunagramSendEffectGeometry.arrival(.45f) == 0, "Bubble hidden while plane flies");
        check(LunagramSendEffectGeometry.arrival(1) == 1, "Bubble complete at end");
        check(LunagramSendEffectGeometry.dropY(1) == 0 && LunagramSendEffectGeometry.dropScale(1) == 1, "Drop ends exactly at real cell geometry");
        int previous = 0;
        for (int i = 0; i <= 1000; i++) {
            float progress = i / 1000f;
            int end = LunagramSendEffectGeometry.revealEnd(offsets, progress);
            check(end >= previous && end <= 4, "Monotone bounded reveal"); previous = end;
            float scale = LunagramSendEffectGeometry.dropScale(progress), y = LunagramSendEffectGeometry.dropY(progress);
            check(Float.isFinite(scale) && scale > 0 && scale <= 1.1f && Float.isFinite(y), "Finite bounded animation geometry");
        }
        check(LunagramSendEffectGeometry.clamp(Float.NaN) == 0, "NaN guard");
        System.out.println("PASS send effect geometry: " + assertions + " assertions");
    }
}
