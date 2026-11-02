package org.telegram.messenger;

import java.util.ArrayList;

/** Bounded presentation math and Unicode-safe reveal boundaries, independent of Canvas/lifecycle. */
public final class LunagramSendEffectGeometry {
    private LunagramSendEffectGeometry() { }
    public static float clamp(float value) { return Float.isNaN(value) ? 0 : Math.max(0, Math.min(1, value)); }
    public static float arrival(float progress) { return progress >= 1 ? 1 : clamp((progress - .46f) / .54f); }
    public static float dropY(float progress) {
        float p = clamp(progress);
        return -64 * (1 - p) * (1 - p) * (1 - p) + (float) (10 * Math.sin(p * Math.PI * 3) * (1 - p) * (1 - p));
    }
    public static float dropScale(float progress) {
        float p = clamp(progress);
        return .84f + .16f * p + (float) (.06f * Math.sin(p * Math.PI * 2) * (1 - p));
    }
    public static int revealEnd(int[] boundaries, float progress) {
        return boundaries[Math.min(boundaries.length - 1, (int) Math.floor(clamp(progress) * (boundaries.length - 1)))];
    }

    // Keep surrogate pairs, combining marks, flags, modifiers and ZWJ emoji sequences together.
    // Android ReplacementSpans receive an additional indivisibility check at the Canvas boundary.
    public static int[] boundaries(CharSequence text) {
        ArrayList<Integer> result = new ArrayList<>();
        result.add(0);
        int end = text.length(), at = 0;
        while (at < end) {
            int first = Character.codePointAt(text, at);
            at += Character.charCount(first);
            if (first == '\r' && at < end && text.charAt(at) == '\n') at++;
            else if (regional(first) && at < end && regional(Character.codePointAt(text, at))) at += Character.charCount(Character.codePointAt(text, at));
            while (at < end) {
                int next = Character.codePointAt(text, at);
                int type = Character.getType(next);
                if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || next >= 0xFE00 && next <= 0xFE0F || next >= 0xE0100 && next <= 0xE01EF
                    || next >= 0x1F3FB && next <= 0x1F3FF || next >= 0xE0020 && next <= 0xE007F) {
                    at += Character.charCount(next);
                } else if (next == 0x200D) {
                    at += Character.charCount(next);
                    if (at < end) at += Character.charCount(Character.codePointAt(text, at));
                } else break;
            }
            result.add(at);
        }
        int[] array = new int[result.size()];
        for (int i = 0; i < result.size(); i++) array[i] = result.get(i);
        return array;
    }
    private static boolean regional(int cp) { return cp >= 0x1F1E6 && cp <= 0x1F1FF; }
}
