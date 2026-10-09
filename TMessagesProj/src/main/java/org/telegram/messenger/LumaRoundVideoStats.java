package org.telegram.messenger;

import android.content.SharedPreferences;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import java.io.File;

/** Optional local diagnostics of a finalized MP4. Never changes recording, uploads, or messages. */
public final class LumaRoundVideoStats {
    private static final String PREFIX = "lunagram_round_stats_";
    private static final Object RESULT_LOCK = new Object();
    private static long generation;

    private LumaRoundVideoStats() {}

    /**
     * Call only after the muxer has finalized a successful recording. On API 28+ the extractor
     * can read sample sizes without decoding video or allocating frame/sample buffers.
     * requestedFps must be captured at recording start, not reread from settings afterwards.
     */
    public static void inspectAsync(File finishedMp4, int requestedFps, String recorder) {
        if (finishedMp4 == null || Build.VERSION.SDK_INT < 28) return;
        final long token;
        synchronized (RESULT_LOCK) {
            token = ++generation;
        }
        final int requested = requestedFps > 0 && requestedFps <= 300 ? requestedFps : 0;
        final String recorderName = boundedRecorder(recorder);
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (RESULT_LOCK) {
                if (token != generation) return;
                // Do not display measurements from an older clip as this clip's result if
                // the new file is too short, missing, or unsupported by the extractor.
                clearResult(MessagesController.getGlobalMainSettings().edit()).apply();
            }
            Result result = inspect(finishedMp4, requested, recorderName, token);
            synchronized (RESULT_LOCK) {
                if (token != generation || result == null) return;
                MessagesController.getGlobalMainSettings().edit()
                    .putInt(PREFIX + "version", 1)
                    .putInt(PREFIX + "width", result.width)
                    .putInt(PREFIX + "height", result.height)
                    .putInt(PREFIX + "fps_milli", (int) Math.round(result.measuredFps * 1000))
                    .putLong(PREFIX + "video_bitrate", result.videoBitrate)
                    .putInt(PREFIX + "requested_fps", result.requestedFps)
                    .putString(PREFIX + "recorder", result.recorder)
                    .putLong(PREFIX + "measured_at", result.measuredAtMillis)
                    .apply();
            }
        });
    }

    /** Null means no usable measurement; it does not mean the recording had zero FPS. */
    public static Result lastResult() {
        synchronized (RESULT_LOCK) {
            SharedPreferences prefs = MessagesController.getGlobalMainSettings();
            try {
                if (prefs.getInt(PREFIX + "version", 0) != 1) return null;
                int width = prefs.getInt(PREFIX + "width", 0);
                int height = prefs.getInt(PREFIX + "height", 0);
                int fpsMilli = prefs.getInt(PREFIX + "fps_milli", 0);
                long bitrate = prefs.getLong(PREFIX + "video_bitrate", 0);
                int requested = prefs.getInt(PREFIX + "requested_fps", 0);
                long measuredAt = prefs.getLong(PREFIX + "measured_at", 0);
                if (!validDimensions(width, height) || fpsMilli <= 0 || fpsMilli > 300_000
                        || bitrate <= 0 || bitrate > 1_000_000_000L || requested < 0
                        || requested > 300 || measuredAt <= 0) return null;
                return new Result(width, height, fpsMilli / 1000.0, bitrate, requested,
                    boundedRecorder(prefs.getString(PREFIX + "recorder", "unknown")), measuredAt);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static Result inspect(File file, int requestedFps, String recorder, long token) {
        MediaExtractor extractor = null;
        try {
            if (!file.isFile() || file.length() == 0 || !isCurrent(token)) return null;
            extractor = new MediaExtractor();
            extractor.setDataSource(file.getAbsolutePath());
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                int width = format.getInteger(MediaFormat.KEY_WIDTH);
                int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                if (!validDimensions(width, height)) return null;
                extractor.selectTrack(track);
                LumaRoundVideoMetrics metrics = new LumaRoundVideoMetrics();
                int scanned = 0;
                while (extractor.getSampleTime() >= 0) {
                    // Cancellation is checked periodically without waiting for a scan of an
                    // obsolete file to complete. The bounded sample count also limits memory.
                    if ((scanned++ & 127) == 0 && !isCurrent(token)) return null;
                    if (!metrics.addSample(extractor.getSampleTime(), extractor.getSampleSize())) return null;
                    if (!extractor.advance()) break;
                }
                LumaRoundVideoMetrics.Measurement measurement = metrics.finish();
                if (measurement == null) return null;
                return new Result(width, height, measurement.measuredFps, measurement.videoBitrate,
                    requestedFps, recorder, System.currentTimeMillis());
            }
        } catch (Exception ignored) {
            // Diagnostics must not fail a successful recording or log a private local path.
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (RuntimeException ignored) {
                    // A broken extractor must not stop the shared background queue.
                }
            }
        }
        return null;
    }

    private static boolean isCurrent(long token) {
        synchronized (RESULT_LOCK) {
            return token == generation;
        }
    }

    private static boolean validDimensions(int width, int height) {
        return width > 0 && height > 0 && width <= 16_384 && height <= 16_384;
    }

    private static String boundedRecorder(String recorder) {
        return "legacy".equals(recorder) ? "legacy" : "camera2".equals(recorder) ? "camera2" : "unknown";
    }

    private static SharedPreferences.Editor clearResult(SharedPreferences.Editor editor) {
        return editor.remove(PREFIX + "version").remove(PREFIX + "width").remove(PREFIX + "height")
            .remove(PREFIX + "fps_milli").remove(PREFIX + "video_bitrate")
            .remove(PREFIX + "requested_fps").remove(PREFIX + "recorder").remove(PREFIX + "measured_at");
    }

    public static final class Result {
        public final int width;
        public final int height;
        public final double measuredFps;
        /** Video elementary stream bitrate in bits/second; excludes audio and MP4 overhead. */
        public final long videoBitrate;
        public final int requestedFps;
        public final String recorder;
        public final long measuredAtMillis;

        private Result(int width, int height, double measuredFps, long videoBitrate,
                       int requestedFps, String recorder, long measuredAtMillis) {
            this.width = width;
            this.height = height;
            this.measuredFps = measuredFps;
            this.videoBitrate = videoBitrate;
            this.requestedFps = requestedFps;
            this.recorder = recorder;
            this.measuredAtMillis = measuredAtMillis;
        }
    }
}
