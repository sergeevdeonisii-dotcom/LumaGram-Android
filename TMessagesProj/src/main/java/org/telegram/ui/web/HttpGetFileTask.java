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
import java.net.HttpURLConnection;
import java.net.URL;

@Keep
public class HttpGetFileTask extends AsyncTask<String, Void, File> {

    private File file;
    private File destinationFile;

    private Utilities.Callback<File> doneCallback;
    private Utilities.Callback<Float> progressCallback;

    private String overrideExt;

    private Exception exception;
    private long max_size = -1;

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

    @Override
    protected File doInBackground(String... params) {
        String urlString = params[0];

        long totalSize = 0L;
        long downloadedSize = file != null && file.exists() ? file.length() : 0L;
        for (int i = 0; i < 5; ++i) {
            boolean resuming = downloadedSize > 0L;
            HttpURLConnection urlConnection = null;
            try {
                URL url = new URL(urlString);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                if (resuming) {
                    urlConnection.setRequestProperty("Range", "bytes=" + downloadedSize + "-");
                }
                urlConnection.setDoInput(true);
                urlConnection.setUseCaches(false);
                urlConnection.setConnectTimeout(20_000);
                urlConnection.setReadTimeout(30_000);

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
                    return null;
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
                if (!isCancelled() && e instanceof IOException && i < 4) {
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
