package org.telegram.messenger;

import java.net.URI;
import java.util.regex.Pattern;

/** Display-only branding. Never use this output for transport or APK verification. */
public final class LumaUpdatePresentation {
    private static final String BRAND = "Lunagram";
    private static final Pattern GITHUB_LOCATION = Pattern.compile(
            "(?i)(?:https?://)?(?:[a-z0-9-]{1,63}\\.){0,4}(?:github\\.com|githubusercontent\\.com)"
                    + "(?=[:/\\s)\\]]|$)[^\\s<>\\\"'\\]\\)]*");

    private LumaUpdatePresentation() {}

    public static String forDisplay(String text) {
        return forDisplay(text, BuildVars.LUMA_UPDATE_MANIFEST_URL);
    }

    static String forDisplay(String text, String officialSource) {
        if (text == null || text.isEmpty()) return text;
        String result = GITHUB_LOCATION.matcher(text).replaceAll(BRAND);
        try {
            String path = new URI(officialSource).getPath();
            String[] segments = path == null ? new String[0] : path.split("/");
            // The official raw-content source begins with /owner/repository/.
            // Derive display redactions so a source migration cannot leave old
            // owner identifiers hardcoded in presentation code.
            for (int i = 1; i <= 2 && i < segments.length; i++) {
                if (segments[i].isEmpty()) continue;
                result = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_-])" + Pattern.quote(segments[i])
                                + "(?![\\p{L}\\p{N}_-])")
                        .matcher(result).replaceAll(BRAND);
            }
        } catch (Exception ignored) {
            // Source validity and availability are owned by the updater, not UI.
        }
        return result;
    }
}
