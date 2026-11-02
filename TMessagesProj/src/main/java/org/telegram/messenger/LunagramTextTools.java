package org.telegram.messenger;

import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** Bounded, offline text operations. Never reads the clipboard, persists input or sends messages. */
public final class LunagramTextTools {
    public static final int MAX_INPUT = 65536;
    private static final String EN = "`qwertyuiop[]asdfghjkl;'zxcvbnm,./";
    private static final String RU = "ёйцукенгшщзхъфывапролджэячсмитьбю.";
    private static final String EN_SHIFT = "~QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>?";
    private static final String RU_SHIFT = "ЁЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮ,";
    private LunagramTextTools() { }

    public static String requireInput(CharSequence text) {
        if (text == null) return "";
        if (text.length() > MAX_INPUT) throw new IllegalArgumentException("Text exceeds local tool limit");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode text");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode text");
        }
        return text.toString();
    }

    /** Deliberately opt-in with a preview: punctuation follows the physical keyboard too. */
    public static String keyboardLayout(CharSequence text, boolean toRussian) {
        String input = requireInput(text);
        String from = toRussian ? EN + EN_SHIFT : RU + RU_SHIFT;
        String to = toRussian ? RU + RU_SHIFT : EN + EN_SHIFT;
        StringBuilder result = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int at = from.indexOf(c);
            result.append(at < 0 ? c : to.charAt(at));
        }
        return result.toString();
    }

    /** Preserve paragraphs, emoji and direction marks; normalize only whitespace. */
    public static String normalizeWhitespace(CharSequence text) {
        String input = requireInput(text);
        StringBuilder out = new StringBuilder(input.length());
        boolean pendingSpace = false;
        int consecutiveNewlines = 0;
        for (int offset = 0; offset < input.length();) {
            int cp = input.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\r') {
                if (offset < input.length() && input.charAt(offset) == '\n') offset++;
                cp = '\n';
            }
            if (cp == '\n' || cp == 0x2028 || cp == 0x2029) {
                pendingSpace = false;
                if (out.length() > 0 && consecutiveNewlines < 2) out.append('\n');
                consecutiveNewlines++;
            } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0 && consecutiveNewlines == 0;
            } else {
                if (pendingSpace) out.append(' ');
                pendingSpace = false;
                consecutiveNewlines = 0;
                out.appendCodePoint(cp);
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
        return out.toString();
    }

    public static final class Replacement {
        public final String text;
        public final int matches, replaced;
        private Replacement(String text, int matches, int replaced) {
            this.text = text; this.matches = matches; this.replaced = replaced;
        }
    }

    /** Literal, case-sensitive, non-overlapping matches. No regex or quadratic replace loop. */
    public static Replacement replace(CharSequence text, String find, String replacement, boolean all) {
        String input = requireInput(text);
        String needle = requireInput(find), value = requireInput(replacement);
        if (needle.isEmpty()) return new Replacement(input, 0, 0);
        int matches = 0;
        for (int at = 0; (at = input.indexOf(needle, at)) >= 0; at += needle.length()) matches++;
        int changed = all ? matches : Math.min(1, matches);
        long length = (long) input.length() + (long) changed * (value.length() - needle.length());
        if (length > MAX_INPUT) throw new IllegalArgumentException("Replacement exceeds local tool limit");
        StringBuilder out = new StringBuilder((int) length);
        int previous = 0;
        for (int i = 0; i < changed; i++) {
            int at = input.indexOf(needle, previous);
            out.append(input, previous, at).append(value);
            previous = at + needle.length();
        }
        out.append(input, previous, input.length());
        return new Replacement(out.toString(), matches, changed);
    }

    public static final class Statistics {
        public final int codePoints, utf16Units, words, lines, utf8Bytes;
        private Statistics(int codePoints, int utf16Units, int words, int lines, int utf8Bytes) {
            this.codePoints = codePoints; this.utf16Units = utf16Units; this.words = words;
            this.lines = lines; this.utf8Bytes = utf8Bytes;
        }
    }

    /** Code points are not advertised as graphemes: a family emoji contains several. */
    public static Statistics statistics(CharSequence text) {
        String input = requireInput(text);
        BreakIterator iterator = BreakIterator.getWordInstance(Locale.ROOT);
        iterator.setText(input);
        int words = 0, start = iterator.first(), lines = input.isEmpty() ? 0 : 1;
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            for (int i = start; i < end;) {
                int cp = input.codePointAt(i);
                if (Character.isLetterOrDigit(cp)) { words++; break; }
                i += Character.charCount(cp);
            }
        }
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\r') {
                lines++;
                if (i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
            } else if (c == '\n' || c == 0x2028 || c == 0x2029) lines++;
        }
        return new Statistics(input.codePointCount(0, input.length()), input.length(), words, lines,
                input.getBytes(StandardCharsets.UTF_8).length);
    }

    /** A bounded contiguous change, split only at Unicode code-point boundaries. */
    public static final class Comparison {
        public final String prefix, removed, added, suffix;
        private Comparison(String prefix, String removed, String added, String suffix) {
            this.prefix = prefix; this.removed = removed; this.added = added; this.suffix = suffix;
        }
        public boolean identical() { return removed.isEmpty() && added.isEmpty(); }
    }
    public static Comparison compare(CharSequence before, CharSequence after) {
        String a = requireInput(before), b = requireInput(after);
        int start = 0, endA = a.length(), endB = b.length();
        while (start < endA && start < endB && a.codePointAt(start) == b.codePointAt(start))
            start += Character.charCount(a.codePointAt(start));
        while (endA > start && endB > start && a.codePointBefore(endA) == b.codePointBefore(endB)) {
            int count = Character.charCount(a.codePointBefore(endA)); endA -= count; endB -= count;
        }
        return new Comparison(a.substring(0, start), a.substring(start, endA), b.substring(start, endB), a.substring(endA));
    }

    public static final class SourceText {
        public final String source, text;
        public SourceText(CharSequence source, CharSequence text) {
            this.source = requireInput(source); this.text = requireInput(text);
            if (this.source.length() > 512) throw new IllegalArgumentException("Source label too long");
        }
    }
    /** Caller verifies source permissions and chronological order; this method never fetches messages. */
    public static String combine(List<SourceText> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > 100) throw new IllegalArgumentException("Invalid source count");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            SourceText item = sources.get(i);
            if (item == null) throw new IllegalArgumentException("Missing source");
            String header = "[" + (i + 1) + "] " + item.source.replace('\n', ' ').replace('\r', ' ').replace('\u2028', ' ').replace('\u2029', ' ') + "\n";
            String separator = i == 0 ? "" : "\n\n────────\n";
            if ((long) result.length() + separator.length() + header.length() + item.text.length() > MAX_INPUT)
                throw new IllegalArgumentException("Combined text exceeds local tool limit");
            result.append(separator).append(header).append(item.text);
        }
        return result.toString();
    }

    /** Case-insensitive literal search, capped without allocating a result for every character. */
    public static int[] find(CharSequence text, CharSequence query) {
        String input = requireInput(text), needle = requireInput(query);
        ArrayList<Integer> positions = new ArrayList<>();
        if (!needle.isEmpty()) for (int at = 0; at <= input.length() - needle.length() && positions.size() < 1000;) {
            if (input.regionMatches(true, at, needle, 0, needle.length())) { positions.add(at); at += needle.length(); }
            else at += Character.charCount(input.codePointAt(at));
        }
        return toArray(positions);
    }
    public static int[] paragraphStarts(CharSequence text) {
        String input = requireInput(text); ArrayList<Integer> starts = new ArrayList<>(); boolean fresh = true;
        for (int at = 0; at < input.length();) {
            int cp = input.codePointAt(at);
            if (cp == '\n' || cp == '\r' || cp == 0x2028 || cp == 0x2029) fresh = true;
            else if (fresh && !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) { starts.add(at); fresh = false; }
            at += Character.charCount(cp);
        }
        return toArray(starts);
    }
    private static int[] toArray(ArrayList<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }
}
