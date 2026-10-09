package org.telegram.messenger;

import java.net.MalformedURLException;
import java.net.URL;

/** Scheme boundary for remote file downloads; never opens a connection. */
final class LumaHttpUrlPolicy {
    private LumaHttpUrlPolicy() {
    }

    static URL parse(String location) throws MalformedURLException {
        requireLocation(location);
        try {
            return requireHttp(new URL(location));
        } catch (MalformedURLException e) {
            // Do not include a private URL or its query parameters in error logs.
            throw new MalformedURLException("Invalid HTTP download URL");
        }
    }

    static URL resolveRedirect(URL responseUrl, String location) throws MalformedURLException {
        requireHttp(responseUrl);
        requireLocation(location);
        try {
            // Resolve against the actual response URL, including prior automatic redirects.
            return requireHttp(new URL(responseUrl, location));
        } catch (MalformedURLException e) {
            throw new MalformedURLException("Invalid HTTP redirect URL");
        }
    }

    private static void requireLocation(String location) throws MalformedURLException {
        if (location == null || location.trim().isEmpty()) {
            throw new MalformedURLException("Missing HTTP download URL");
        }
    }

    private static URL requireHttp(URL url) throws MalformedURLException {
        if (url == null || !("http".equalsIgnoreCase(url.getProtocol())
                || "https".equalsIgnoreCase(url.getProtocol()))
                || url.getHost() == null || url.getHost().isEmpty()) {
            throw new MalformedURLException("Only HTTP and HTTPS downloads are allowed");
        }
        return url;
    }
}
