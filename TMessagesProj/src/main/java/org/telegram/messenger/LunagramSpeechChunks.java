package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;

/** No networking; preserves code points and all input between bounded speech chunks. */
public final class LunagramSpeechChunks {
    private LunagramSpeechChunks() {}
    public static List<String> split(CharSequence source, int limit) {
        String text = LunagramTextTools.requireInput(source);
        if (limit < 2 || limit > 4000) throw new IllegalArgumentException("Invalid chunk size");
        ArrayList<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + limit);
            if (end < text.length()) {
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                for (int i = end - 1; i > start + limit / 2; i--) {
                    if (Character.isWhitespace(text.charAt(i))) { end = i + 1; break; }
                }
            }
            chunks.add(text.substring(start, end)); start = end;
        }
        return chunks;
    }
}
