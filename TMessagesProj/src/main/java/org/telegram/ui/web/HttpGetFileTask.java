package org.telegram.ui.web;


import android.content.ContentResolver;
import android.os.AsyncTask;
import android.os.Build;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import androidx.annotation.Keep;

import com.google.android.exoplayer2.util.MimeTypes;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Keep
public class HttpGetFileTask extends AsyncTask<String, Void, File> {

    private static final int MAX_REDIRECTS = 5;

    private File file;
    private File destinationFile;

    private Utilities.Callback<File> doneCallback;
    private Utilities.Callback<Float> progressCallback;

    private String overrideExt;

    private Exception exception;
    private long max_size = -1;
    private final Set<String> allowedHosts = new HashSet<>();

    public HttpGetFileTask(
        Utilities.Callback<File> doneCallback,
        Utilities.Callback<Float> progressCallback
    ) {
        this.doneCallback = doneCallback;
        this.progressCallback = progressCallback;
    }

    @Keep
    public HttpGetFileTask setOverrideExtension(String ext) {
        this.overrideExt = ext;
        return this;
    }

    @Keep
    public HttpGetFileTask setDestFile(File file) {
        this.file = file;
        this.destinationFile = file;
        return this;
    }

    @Keep
    public HttpGetFileTask setMaxSize(long max_size) {
        this.max_size = max_size;
        return this;
    }

    /**
     * Restricts a download to the supplied host names and their subdomains.
     * Redirect destinations are checked against the same list.
     */
    @Keep
    public HttpGetFileTask setAllowedHosts(String... hosts) {
        allowedHosts.clear();
        if (hosts != null) {
            for (String host : hosts) {
                String normalized = normalizeHost(host);
                if (!normalized.isEmpty()) {
                    allowedHosts.add(normalized);
                }
            }
        }
        return this;
    }

    @Override
    protected File doInBackground(String... params) {
        String urlString = params[0];

        long totalSize = 0L;
        long downloadedSize = file != null && file.exists() ? file.length() : 0L;
        if (max_size > 0L && downloadedSize > max_size) {
            deletePartialFile();
            exception = new FileTooLargeException();
            return null;
        }
        for (int i = 0; i < 5; ++i) {
            boolean resuming = downloadedSize > 0L;
            HttpURLConnection urlConnection = null;
            try {
                urlConnection = openValidatedConnection(urlString, resuming, downloadedSize);

                int statusCode = urlConnection.getResponseCode();
                if (resuming && statusCode == 416 && file != null && file.exists()) {
                    // The previous request may have completed the file just before its callback
                    // was interrupted. Let the caller's hash/signature verification decide.
                    return file;
                }
                if (statusCode < 200 || statusCode >= 300) {
                    throw new IOException("HTTP " + statusCode);
                }
                InputStream in = urlConnection.getInputStream();

                if (resuming && statusCode != HttpURLConnection.HTTP_PARTIAL) {
                    FileLog.d("failed to resume, server doesn't support partial content. downloading from the beginning");
                    downloadedSize = 0L;
                    resuming = false;
                    if (file != null) {
                        try {
                            file.delete();
                        } catch (Exception ignore) {};
                    }
                    file = destinationFile;
                }
                long responseSize;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    responseSize = urlConnection.getContentLengthLong();
                } else {
                    responseSize = urlConnection.getContentLength();
                }
                totalSize = responseSize > 0L ? responseSize + (resuming ? downloadedSize : 0L) : 0L;
                if (max_size > 0 && totalSize > max_size) {
                    in.close();
                    deletePartialFile();
                    throw new FileTooLargeException();
                }

                if (file == null) {
                    final String ext = overrideExt != null ? overrideExt : MimeTypeMap.getSingleton().getExtensionFromMimeType(urlConnection.getContentType());
                    file = StoryEntry.makeCacheFile(UserConfig.selectedAccount, ext);
                }

                try (BufferedInputStream bis = new BufferedInputStream(in, 64 * 1024);
                     BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(file, resuming), 64 * 1024)) {

                    byte[] buffer = new byte[64 * 1024];
                    int bytesRead;

                    while ((bytesRead = bis.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        downloadedSize += bytesRead;

                        if (max_size > 0L && downloadedSize > max_size) {
                            deletePartialFile();
                            throw new FileTooLargeException();
                        }

                        if (isCancelled()) {
                            try {
                                file.delete();
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                            return null;
                        }

                        if (totalSize > 0) {
                            float progress = Utilities.clamp01((float) downloadedSize / totalSize);
                            if (progressCallback != null) {
                                AndroidUtilities.runOnUIThread(() -> progressCallback.run(progress));
                            }
                        }
                    }
                    output.flush();

                    if (totalSize > 0L && downloadedSize < totalSize) {
                        throw new IOException("Unexpected end of stream: " + downloadedSize + "/" + totalSize);
                    }

                    if (progressCallback != null) {
                        AndroidUtilities.runOnUIThread(() -> progressCallback.run(1.0f));
                    }
                }

                return isCancelled() ? null : file;
            } catch (Exception e) {
                downloadedSize = file != null && file.exists() ? file.length() : 0L;
                if (!isCancelled() && e instanceof IOException && !(e instanceof NonRetryableDownloadException) && i < 4) {
                    FileLog.d("download interrupted at " + downloadedSize + " bytes, retrying with Range");
                    try {
                        Thread.sleep(Math.min(2_000L, 350L * (i + 1L)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        this.exception = interrupted;
                        return null;
                    }
                    continue;
                }
                this.exception = e;
                FileLog.e(e);
                return null;
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        }
        this.exception = new RuntimeException("too many retries");
        return null;
    }

    private HttpURLConnection openValidatedConnection(String urlString, boolean resuming, long downloadedSize) throws IOException {
        URL current = new URL(urlString);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateRemoteUrl(current);
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            if (resuming) {
                connection.setRequestProperty("Range", "bytes=" + downloadedSize + "-");
            }
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);

            int statusCode = connection.getResponseCode();
            if (!isRedirect(statusCode)) {
                return connection;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.trim().isEmpty()) {
                throw new UnsafeUrlException("Redirect without a destination");
            }
            if (redirect == MAX_REDIRECTS) {
                throw new UnsafeUrlException("Too many redirects");
            }
            current = new URL(current, location);
        }
        throw new UnsafeUrlException("Too many redirects");
    }

    private void validateRemoteUrl(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new UnsafeUrlException("Only HTTPS downloads are allowed");
        }
        if (url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) {
            throw new UnsafeUrlException("Unsafe URL authority");
        }
        String host = normalizeHost(url.getHost());
        if (host.isEmpty() || !isAllowedHost(host)) {
            throw new UnsafeUrlException("Download host is not allowed");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new UnsafeUrlException("Download host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new UnsafeUrlException("Private or reserved network address is not allowed");
            }
        }
    }

    private boolean isAllowedHost(String host) {
        if (allowedHosts.isEmpty()) {
            return true;
        }
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.trim().toLowerCase(Locale.US);
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            return a != 0
                    && a != 10
                    && a != 127
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254)
                    && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && b == 0 && c == 0)
                    && !(a == 192 && b == 0 && c == 2)
                    && !(a == 192 && b == 168)
                    && !(a == 198 && (b == 18 || b == 19))
                    && !(a == 198 && b == 51 && c == 100)
                    && !(a == 203 && b == 0 && c == 113)
                    && a < 224;
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first & 0xfe) != 0xfc
                    && !(first == 0xfe && (second & 0xc0) == 0x80)
                    && !(first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8);
        }
        return false;
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == HttpURLConnection.HTTP_MOVED_PERM
                || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                || statusCode == HttpURLConnection.HTTP_SEE_OTHER
                || statusCode == 307
                || statusCode == 308;
    }

    private void deletePartialFile() {
        if (file != null && file.exists()) {
            try {
                file.delete();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static class NonRetryableDownloadException extends IOException {
        NonRetryableDownloadException(String message) {
            super(message);
        }
    }

    private static final class UnsafeUrlException extends NonRetryableDownloadException {
        UnsafeUrlException(String message) {
            super(message);
        }
    }

    private static final class FileTooLargeException extends NonRetryableDownloadException {
        FileTooLargeException() {
            super("Download exceeds the configured size limit");
        }
    }

    @Override
    protected void onPostExecute(File file) {
        if (doneCallback != null) {
            if (exception == null) {
                doneCallback.run(file);
            } else {
                doneCallback.run(null);
            }
        }
    }
}
