package org.telegram.messenger;

import java.net.MalformedURLException;
import java.net.URL;

/** No connections, DNS requests, local-file reads, or Android runtime are used. */
public final class HttpUrlPolicy100Test {
    private static int assertions;

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static void same(String expected, URL actual) {
        assertions++;
        if (!expected.equals(actual.toExternalForm())) {
            throw new AssertionError("Unexpected URL resolution");
        }
    }

    private static void rejects(CheckedAction action) throws Exception {
        assertions++;
        try {
            action.run();
            throw new AssertionError("Unsafe URL was accepted");
        } catch (MalformedURLException expected) {
            if (expected.getMessage().contains("private-value")) {
                throw new AssertionError("URL was included in the rejection log message");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String[] valid = {
            "http://example.invalid/a.gif",
            "https://example.invalid/a.gif?x=1#part",
            "https://example.invalid:8443/a%20b.gif",
            "https://user:password@example.invalid/a.gif",
            "http://127.0.0.1/a.gif",
            "http://[::1]/a.gif"
        };
        for (String value : valid) {
            same(value, LumaHttpUrlPolicy.parse(value));
        }
        same("https://example.invalid/a.gif", LumaHttpUrlPolicy.parse("HTTPS://example.invalid/a.gif"));
        String[] unsafe = {
            null, "", "  ", "relative.gif", "//example.invalid/a.gif", "http:/a.gif",
            "file:///private-value", "FILE:///private-value", "content://private-value/a",
            "jar:file:///private-value!/a", "jar:https://example.invalid/private-value!/a",
            "ftp://example.invalid/private-value", "data:text/plain,private-value",
            "mailto:private-value@example.invalid", "https://example.invalid:private-value/a"
        };
        for (String value : unsafe) {
            rejects(() -> LumaHttpUrlPolicy.parse(value));
        }

        for (String scheme : new String[]{"http", "https"}) {
            URL base = LumaHttpUrlPolicy.parse(scheme + "://example.invalid/final/dir/old.gif");
            String[] blockedRedirects = {
                null, "", "  ", "file:///private-value", "FILE:///private-value",
                "content://private-value/a", "jar:file:///private-value!/a",
                "jar:https://example.invalid/private-value!/a", "ftp://example.invalid/private-value",
                "data:text/plain,private-value", "mailto:private-value@example.invalid"
            };
            for (String value : blockedRedirects) {
                rejects(() -> LumaHttpUrlPolicy.resolveRedirect(base, value));
            }
            same("http://cdn.invalid/a.gif", LumaHttpUrlPolicy.resolveRedirect(base, "http://cdn.invalid/a.gif"));
            same("https://cdn.invalid/a.gif", LumaHttpUrlPolicy.resolveRedirect(base, "https://cdn.invalid/a.gif"));
            same(scheme + "://example.invalid/final/dir/next.gif", LumaHttpUrlPolicy.resolveRedirect(base, "next.gif"));
            same(scheme + "://example.invalid/final/next.gif", LumaHttpUrlPolicy.resolveRedirect(base, "../next.gif"));
            same(scheme + "://example.invalid/next.gif", LumaHttpUrlPolicy.resolveRedirect(base, "/next.gif"));
            same(scheme + "://cdn.invalid/next.gif", LumaHttpUrlPolicy.resolveRedirect(base, "//cdn.invalid/next.gif"));
            same(scheme + "://example.invalid/final/dir/a%20b.gif?x=%2F", LumaHttpUrlPolicy.resolveRedirect(base, "a%20b.gif?x=%2F"));
            same(scheme + "://example.invalid/final/dir/old.gif#next", LumaHttpUrlPolicy.resolveRedirect(base, "#next"));
        }
        rejects(() -> LumaHttpUrlPolicy.resolveRedirect(null, "https://example.invalid/a"));
        rejects(() -> LumaHttpUrlPolicy.resolveRedirect(new URL("file:///private-value"), "https://example.invalid/a"));
        // A chain's Location is relative to the final response path, not the original request.
        URL afterAutomaticRedirect = LumaHttpUrlPolicy.parse("https://cdn.invalid/moved/final.gif");
        same("https://cdn.invalid/moved/next.gif", LumaHttpUrlPolicy.resolveRedirect(afterAutomaticRedirect, "next.gif"));
        System.out.println("PASS: " + assertions + " HTTP URL policy assertions (no I/O)");
    }
}
