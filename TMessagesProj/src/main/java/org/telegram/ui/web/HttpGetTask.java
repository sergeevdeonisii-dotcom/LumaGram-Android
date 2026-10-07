package org.telegram.ui.web;


import android.os.AsyncTask;

import org.telegram.messenger.Utilities;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpGetTask extends AsyncTask<String, Void, String> {

    private final HashMap<String, String> headers = new HashMap<>();
    private final Utilities.Callback<String> callback;
    private Exception exception;
    private int connectTimeoutMs;
    private int readTimeoutMs;
    private long maxResponseBytes = -1;
    private boolean strictResponse;
    private volatile HttpURLConnection activeHttpURLConnection;
    private volatile boolean cancelled;

    public HttpGetTask(Utilities.Callback<String> callback) {
        this.callback = callback;
    }

    public HttpGetTask setHeader(String key, String value) {
        headers.put(key, value);
        return this;
    }

    public HttpGetTask setTimeouts(int connectTimeoutMs, int readTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        return this;
    }

    public HttpGetTask setMaxResponseBytes(long maxResponseBytes) {
        if (maxResponseBytes <= 0 || maxResponseBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("HTTP response limit must be positive and fit in memory");
        }
        this.maxResponseBytes = maxResponseBytes;
        return this;
    }

    public HttpGetTask setStrictResponse(boolean strictResponse) {
        this.strictResponse = strictResponse;
        return this;
    }

    public HttpGetTask executeParallel(String url) {
        executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, url);
        return this;
    }

    public void cancelRequest() {
        cancelled = true;
        cancel(true);
        HttpURLConnection connection = activeHttpURLConnection;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    protected String doInBackground(String... params) {
        HttpURLConnection urlConnection = null;
        InputStream input = null;
        try {
            if (cancelled || isCancelled()) return null;
            URL url = new URL(params[0]);
            urlConnection = (HttpURLConnection) url.openConnection();
            activeHttpURLConnection = urlConnection;
            // Cancellation can race with creating/publishing the connection.
            if (cancelled || isCancelled()) return null;
            urlConnection.setConnectTimeout(connectTimeoutMs);
            urlConnection.setReadTimeout(readTimeoutMs);
            if (strictResponse) urlConnection.setUseCaches(false);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                urlConnection.setRequestProperty(e.getKey(), e.getValue());
            }
            urlConnection.setRequestMethod("GET");
            urlConnection.setDoInput(true);

            int statusCode = urlConnection.getResponseCode();
            if (strictResponse && (statusCode < 200 || statusCode >= 300)) {
                // Do not put a potentially secret custom URL/error body in logs.
                throw new IOException("HTTP " + statusCode);
            }
            if (statusCode >= 200 && statusCode < 300) {
                input = urlConnection.getInputStream();
            } else {
                // Legacy callers still receive an HTTP error body when present.
                input = urlConnection.getErrorStream();
            }
            if (input == null) return null;

            BufferedReader in;
            if (maxResponseBytes > 0) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long receivedBytes = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (cancelled || isCancelled()) return null;
                    if (read > maxResponseBytes - receivedBytes) {
                        throw new IOException("HTTP response exceeds size limit");
                    }
                    receivedBytes += read;
                    bytes.write(buffer, 0, read);
                }
                if (cancelled || isCancelled()) return null;
                // Count bytes before decoding: multibyte UTF-8 text must not
                // bypass the limit. This buffer is bounded by the opt-in cap.
                in = new BufferedReader(new StringReader(
                    new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
            } else {
                // Unrelated callers retain the original streaming memory path.
                in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
            // Keep the existing newline-joining response API in either mode.
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if (cancelled || isCancelled()) return null;
                response.append(line);
            }

            return response.toString();

        } catch (Exception e) {
            this.exception = e;
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignore) {
                }
            }
            if (urlConnection != null) {
                if (activeHttpURLConnection == urlConnection) activeHttpURLConnection = null;
                try {
                    urlConnection.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }

    @Override
    protected void onPostExecute(String result) {
        if (!cancelled && !isCancelled() && callback != null) {
            if (exception == null) {
                callback.run(result);
            } else {
                callback.run(null);
            }
        }
    }
}
