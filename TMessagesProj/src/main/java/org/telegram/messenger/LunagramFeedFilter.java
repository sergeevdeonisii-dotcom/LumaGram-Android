package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;

/** Deliberately literal, bounded local filter. It folds ordinary feed posts, never deletes messages. */
public final class LunagramFeedFilter {
    public static final int MAX_RULES = 16, MAX_RULE = 80, MAX_INPUT = 2048;
    private LunagramFeedFilter() {}
    public static List<String> parse(String input) {
        if (input == null || input.length() > MAX_INPUT) throw new IllegalArgumentException("Filter limit");
        ArrayList<String> result = new ArrayList<>();
        for (String line : input.replace('\r', '\n').split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.length() > MAX_RULE || result.size() >= MAX_RULES) throw new IllegalArgumentException("Filter limit");
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == line.length() || !Character.isLowSurrogate(line.charAt(i))) throw new IllegalArgumentException("Invalid Unicode");
                } else if (Character.isLowSurrogate(c) || Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                    throw new IllegalArgumentException("Invisible rule");
            }
            if (!result.contains(line)) result.add(line);
        }
        return result;
    }
    public static boolean matches(List<String> rules, String text) {
        if (rules == null || rules.size() > MAX_RULES || text == null || text.length() > 65536) return false;
        for (String rule : rules) {
            if (rule == null || rule.isEmpty() || rule.length() > MAX_RULE) continue;
            for (int i = 0; i <= text.length() - rule.length(); i++)
                if (text.regionMatches(true, i, rule, 0, rule.length())) return true;
        }
        return false;
    }
}
