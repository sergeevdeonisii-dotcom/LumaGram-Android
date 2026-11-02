package org.telegram.messenger;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** A portable list of public pack references. No sticker contents, account IDs or install requests. */
public final class LunagramStickerList {
    public static final String HEADER = "Lunagram sticker list v1";
    public static final int MAX_PACKS = 500, MAX_TEXT = 65536;
    private LunagramStickerList() {}
    public static final class Pack {
        public final String name;
        public final boolean emoji;
        public Pack(String name, boolean emoji) {
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid pack name");
            this.name = name; this.emoji = emoji;
        }
        public String url() { return "https://t.me/" + (emoji ? "addemoji/" : "addstickers/") + name; }
    }
    public static List<Pack> parse(String text) {
        if (text == null || text.length() > MAX_TEXT) throw new IllegalArgumentException("List too long");
        LinkedHashMap<String, Pack> packs = new LinkedHashMap<>();
        int lines = 0;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.equals(HEADER) && lines++ == 0) continue;
            if (line.startsWith("Lunagram sticker list")) throw new IllegalArgumentException("Unsupported version");
            if (++lines > MAX_PACKS + 1) throw new IllegalArgumentException("Too many packs");
            try {
                URI uri = new URI(line);
                if (!"https".equals(uri.getScheme()) || !"t.me".equalsIgnoreCase(uri.getHost()) || uri.getRawUserInfo() != null
                        || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException("Invalid pack URL");
                String[] path = uri.getRawPath().split("/", -1);
                if (path.length != 3 || !path[1].equals("addstickers") && !path[1].equals("addemoji")) throw new IllegalArgumentException("Invalid pack URL");
                Pack pack = new Pack(path[2], path[1].equals("addemoji"));
                packs.put(pack.url().toLowerCase(Locale.ROOT), pack);
                if (packs.size() > MAX_PACKS) throw new IllegalArgumentException("Too many packs");
            } catch (java.net.URISyntaxException error) { throw new IllegalArgumentException("Invalid pack URL"); }
        }
        return new ArrayList<>(packs.values());
    }
    public static String format(List<Pack> packs) {
        if (packs == null || packs.size() > MAX_PACKS) throw new IllegalArgumentException("Too many packs");
        StringBuilder result = new StringBuilder(HEADER);
        for (Pack pack : packs) { if (pack == null) throw new IllegalArgumentException("Missing pack"); result.append('\n').append(pack.url()); }
        if (result.length() > MAX_TEXT) throw new IllegalArgumentException("List too long");
        return result.toString();
    }
}
