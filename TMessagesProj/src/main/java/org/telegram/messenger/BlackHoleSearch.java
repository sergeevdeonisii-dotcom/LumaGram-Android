package org.telegram.messenger;

import java.text.Normalizer;
import java.util.Locale;

public final class BlackHoleSearch {
    private BlackHoleSearch() {}
    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }
    public static boolean matches(String query, String text) {
        String haystack = normalize(text);
        for (String token : normalize(query).split("\\s+")) {
            if (token.length() >= 4 && token.matches("[а-я]+") && "аеёиоуыэюя".indexOf(token.charAt(token.length() - 1)) >= 0)
                token = token.substring(0, token.length() - 1);
            if (!haystack.contains(token)) return false;
        }
        return true;
    }
}
