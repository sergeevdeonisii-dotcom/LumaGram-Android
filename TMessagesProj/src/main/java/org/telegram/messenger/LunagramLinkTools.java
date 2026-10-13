package org.telegram.messenger;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local, lossless-by-default URL inspection. Never resolves, opens or fetches a URL. */
public final class LunagramLinkTools {
    public static final int MAX_URL = 8192;
    private LunagramLinkTools() {}

    public static final class Inspection {
        public final String scheme, host, asciiHost;
        public final boolean web, credentials, insecure, international, invisible;
        private Inspection(String scheme, String host, String ascii, boolean credentials, boolean invisible) {
            this.scheme = scheme; this.host = host; asciiHost = ascii;
            web = "https".equals(scheme) || "http".equals(scheme);
            this.credentials = credentials; insecure = !"https".equals(scheme);
            international = !host.equalsIgnoreCase(ascii) || ascii.toLowerCase(Locale.ROOT).contains("xn--");
            this.invisible = invisible;
        }
    }

    public static final class Cleaned {
        public final String url;
        public final int removed;
        public final boolean protectedQuery;
        private Cleaned(String url, int removed, boolean protect) {
            this.url = url; this.removed = removed; protectedQuery = protect;
        }
    }

    public static Inspection inspect(String input) {
        URI uri = parse(input);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String authority = uri.getRawAuthority();
        String host = uri.getHost();
        // java.net.URI#getHost is null for Unicode domains. Never mistake userinfo for host.
        if (host == null && authority != null) {
            host = authority.substring(authority.lastIndexOf('@') + 1);
            if (host.startsWith("[")) {
                int end = host.indexOf(']');
                if (end < 0) throw new IllegalArgumentException("Invalid host");
                host = host.substring(0, end + 1);
            } else {
                int colon = host.lastIndexOf(':');
                if (colon >= 0) host = host.substring(0, colon);
            }
        }
        if (host == null) host = "";
        String ascii;
        try { ascii = host.startsWith("[") ? host : IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid host"); }
        if ((scheme.equals("http") || scheme.equals("https")) && ascii.isEmpty()) throw new IllegalArgumentException("Missing host");
        boolean hidden = input.codePoints().anyMatch(c -> Character.getType(c) == Character.FORMAT || Character.isISOControl(c));
        return new Inspection(scheme, host, ascii, authority != null && authority.contains("@"), hidden);
    }

    public static Cleaned clean(String input) {
        Inspection info = inspect(input);
        if (!info.web || info.credentials || info.invisible) return new Cleaned(input, 0, true);
        URI uri = parse(input);
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null) return new Cleaned(input, 0, false);
        String[] pieces = rawQuery.split("&", -1);
        // An unknown signature may cover the whole URL. Never touch recognized auth/signed links.
        for (String piece : pieces) {
            String key = key(piece);
            if (key == null || protectedKey(key)) return new Cleaned(input, 0, true);
        }
        List<String> keep = new ArrayList<>();
        int removed = 0;
        for (String piece : pieces) {
            if (tracking(key(piece))) removed++; else keep.add(piece);
        }
        if (removed == 0) return new Cleaned(input, 0, false);
        int question = input.indexOf('?');
        int hash = input.indexOf('#', question);
        String query = String.join("&", keep);
        // Do not re-encode path, query values or fragment: +, %2F, ordering and duplicates survive.
        String result = input.substring(0, question) + (keep.isEmpty() ? "" : "?" + query)
                + (hash < 0 ? "" : input.substring(hash));
        return new Cleaned(result, removed, false);
    }

    public static boolean sameHost(String visible, String target) {
        try { Inspection a = inspect(visible), b = inspect(target); return a.web && b.web && a.asciiHost.equalsIgnoreCase(b.asciiHost); }
        catch (IllegalArgumentException ex) { return false; }
    }

    private static URI parse(String input) {
        if (input == null || input.isEmpty() || input.length() > MAX_URL) throw new IllegalArgumentException("URL length");
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= input.length() || !Character.isLowSurrogate(input.charAt(i))) throw new IllegalArgumentException("Invalid Unicode");
            } else if (Character.isLowSurrogate(c) || Character.isISOControl(c)) throw new IllegalArgumentException("Invalid Unicode");
        }
        try {
            URI uri = new URI(input);
            if (!uri.isAbsolute()) throw new IllegalArgumentException("Absolute URL required");
            return uri;
        } catch (URISyntaxException ex) { throw new IllegalArgumentException("Invalid URL"); }
    }

    private static String key(String piece) {
        int equal = piece.indexOf('=');
        try { return URLDecoder.decode(equal < 0 ? piece : piece.substring(0, equal), "UTF-8").toLowerCase(Locale.ROOT); }
        catch (Exception ex) { return null; }
    }

    private static boolean tracking(String key) {
        return key.startsWith("utm_") || key.equals("fbclid") || key.equals("gclid") || key.equals("dclid")
                || key.equals("msclkid") || key.equals("mc_cid") || key.equals("mc_eid");
    }

    private static boolean protectedKey(String key) {
        return key.contains("signature") || key.equals("sig") || key.equals("s") || key.equals("hmac")
                || key.contains("token") || key.contains("auth") || key.equals("key") || key.equals("code")
                || key.equals("expires") || key.equals("expiry") || key.startsWith("x-amz-") || key.startsWith("x-goog-")
                || key.equals("policy") || key.equals("hash") || key.equals("credential");
    }
}
