package org.telegram.messenger;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;

public final class RoundStats82Test {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double value, double target, double tolerance, String message) {
        check(Math.abs(value - target) <= tolerance, message + ": " + value + " != " + target);
    }
    private static long[] timestamps(int fps, int count) {
        long[] pts = new long[count];
        for (int i = 0; i < count; i++) pts[i] = Math.round(i * 1_000_000.0 / fps);
        return pts;
    }
    private static LumaRoundVideoMetrics.Measurement measure(long[] pts, long bytes) {
        LumaRoundVideoMetrics metrics = new LumaRoundVideoMetrics();
        for (long timestamp : pts) check(metrics.addSample(timestamp, bytes), "sample accepted");
        return metrics.finish();
    }
    private static void math() {
        for (int fps : new int[]{30, 60, 90, 120}) {
            LumaRoundVideoMetrics.Measurement result = measure(timestamps(fps, fps * 5), 20_000);
            check(result != null, "five second clip usable");
            near(result.measuredFps, fps, 0.0001, "actual FPS " + fps);
            near(result.videoBitrate, fps * 20_000.0 * 8, 2, "sample bitrate " + fps);
            near(result.durationUs, 5_000_000, 1, "last sample duration estimated");
        }
        long[] ordered = timestamps(60, 301);
        long[] reordered = ordered.clone();
        for (int i = 1; i < reordered.length - 1; i += 3) {
            long temp = reordered[i]; reordered[i] = reordered[i + 1]; reordered[i + 1] = temp;
        }
        near(measure(reordered, 1000).measuredFps, 60, 0.0001, "B-frame decode order does not lower FPS");
        long[] backwards = ordered.clone();
        for (int i = 0; i < backwards.length; i++) backwards[i] = ordered[ordered.length - 1 - i];
        near(measure(backwards, 1000).measuredFps, 60, 0.0001, "min/max PTS independent of sample order");
        long[] duplicates = new long[ordered.length * 2];
        for (int i = 0; i < ordered.length; i++) duplicates[2 * i] = duplicates[2 * i + 1] = ordered[i];
        LumaRoundVideoMetrics.Measurement duplicateResult = measure(duplicates, 1000);
        near(duplicateResult.measuredFps, 60, 0.0001, "duplicate PTS do not inflate FPS");
        check(duplicateResult.uniqueFrames == 301, "only unique presentation times count");
        near(duplicateResult.videoBitrate, measure(ordered, 1000).videoBitrate * 2.0, 1, "duplicate bytes remain real bitrate");
        long[] withGap = timestamps(60, 301);
        for (int i = 151; i < withGap.length; i++) withGap[i] += 5_000_000L;
        near(measure(withGap, 1000).measuredFps, 30, 0.0001, "missing-frame time is not compressed away");
        long[] jitter = timestamps(60, 601);
        for (int i = 1; i < jitter.length - 1; i++) jitter[i] += i % 2 == 0 ? 900 : -900;
        near(measure(jitter, 1000).measuredFps, 60, 0.0001, "jitter is not half-FPS");
        check(measure(timestamps(120, 120), 1000) == null, "less than one second span unavailable");
        check(measure(timestamps(120, 121), 1000) != null, "one second span usable");
        check(measure(new long[]{50, 50, 50}, 1000) == null, "same PTS unavailable not zero FPS");
        check(measure(new long[]{0, 301_000_000L}, 1000) == null, "implausibly long round-video span rejected");
        LumaRoundVideoMetrics invalid = new LumaRoundVideoMetrics();
        check(!invalid.addSample(-1, 1000) && invalid.finish() == null, "negative sample PTS invalid");
        invalid = new LumaRoundVideoMetrics();
        check(!invalid.addSample(0, 0) && invalid.finish() == null, "zero sample bytes invalid");
        invalid = new LumaRoundVideoMetrics();
        check(invalid.addSample(0, Long.MAX_VALUE), "large first bytes accepted for overflow check");
        check(!invalid.addSample(1_000_000, 1) && invalid.finish() == null, "byte sum overflow rejected");
        invalid = new LumaRoundVideoMetrics();
        for (int i = 0; i < LumaRoundVideoMetrics.MAX_SAMPLES; i++) check(invalid.addSample(i, 1), "bounded scan sample");
        check(!invalid.addSample(50_000, 1) && invalid.finish() == null, "scan bounded instead of partial result");
    }

    private static MediaExtractor.Source source(int fps, int width, int height) {
        MediaFormat audio = new MediaFormat(); audio.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
        MediaFormat video = new MediaFormat(); video.setString(MediaFormat.KEY_MIME, "video/avc");
        video.setInteger(MediaFormat.KEY_WIDTH, width); video.setInteger(MediaFormat.KEY_HEIGHT, height);
        video.setInteger(MediaFormat.KEY_FRAME_RATE, 120); video.setInteger(MediaFormat.KEY_BIT_RATE, 999_000_000);
        long[] pts = timestamps(fps, fps * 5);
        long[] sizes = new long[pts.length]; java.util.Arrays.fill(sizes, 1000);
        return new MediaExtractor.Source(new MediaFormat[]{audio, video}, pts, sizes);
    }
    private static void async(File root) throws Exception {
        File first = new File(root, "private-first-path.mp4"), second = new File(root, "private-second-path.mp4");
        Files.write(first.toPath(), new byte[]{1}); Files.write(second.toPath(), new byte[]{1});
        MediaExtractor.sources.put(first.getAbsolutePath(), source(30, 640, 640));
        MediaExtractor.sources.put(second.getAbsolutePath(), source(60, 1080, 1080));
        check(LumaRoundVideoStats.lastResult() == null, "fresh preferences have no result");
        LumaRoundVideoStats.inspectAsync(first, 120, "legacy");
        check(Utilities.globalQueue.size() == 1 && MediaExtractor.instances == 0, "no synchronous extractor work");
        Utilities.globalQueue.drain();
        LumaRoundVideoStats.Result result = LumaRoundVideoStats.lastResult();
        check(result != null && result.width == 640 && result.height == 640, "encoded dimensions measured");
        near(result.measuredFps, 30, 0.001, "ignore wrong declared 120 FPS");
        near(result.videoBitrate, 240_000, 1, "ignore wrong declared bitrate and audio");
        check(result.requestedFps == 120 && "legacy".equals(result.recorder), "separate request from measurement");
        check(result.measuredAtMillis > 0, "numeric measurement time available");
        LumaRoundVideoStats.inspectAsync(first, 120, "legacy");
        LumaRoundVideoStats.inspectAsync(second, 60, "camera2");
        Utilities.globalQueue.runLast(); Utilities.globalQueue.runFirst();
        result = LumaRoundVideoStats.lastResult();
        check(result.width == 1080 && result.requestedFps == 60, "older queued scan cannot overwrite newer result");
        LumaRoundVideoStats.inspectAsync(first, 120, "legacy");
        MediaExtractor.firstSampleHook = () -> {
            LumaRoundVideoStats.inspectAsync(second, 90, "camera2");
            Utilities.globalQueue.runLast();
        };
        Utilities.globalQueue.runFirst();
        result = LumaRoundVideoStats.lastResult();
        check(result.width == 1080 && result.requestedFps == 90, "in-flight old scan cannot overwrite new result");
        LumaRoundVideoStats.inspectAsync(first, -1, first.getAbsolutePath()); Utilities.globalQueue.drain();
        result = LumaRoundVideoStats.lastResult();
        check(result.requestedFps == 0 && "unknown".equals(result.recorder), "untrusted request/name bounded");
        for (Map.Entry<String, Object> entry : MessagesController.prefs.values.entrySet()) {
            check(entry.getKey().startsWith("lunagram_round_stats_"), "only namespaced diagnostic preferences");
            check(entry.getValue() instanceof Number || entry.getKey().endsWith("recorder")
                && "unknown".equals(entry.getValue()), "only numeric data and bounded enum persisted");
        }
        Build.VERSION.SDK_INT = 27; LumaRoundVideoStats.inspectAsync(first, 120, "legacy");
        check(Utilities.globalQueue.size() == 0, "old Android gracefully skips unavailable getSampleSize API");
        Build.VERSION.SDK_INT = 36; LumaRoundVideoStats.inspectAsync(null, 120, "legacy");
        check(Utilities.globalQueue.size() == 0, "null file safely ignored");
        MediaExtractor.sources.get(first.getAbsolutePath()).failSamples = true;
        LumaRoundVideoStats.inspectAsync(first, 120, "legacy"); Utilities.globalQueue.drain();
        check(LumaRoundVideoStats.lastResult() == null, "failed scan clears old result, not false zero FPS");
        MediaExtractor.sources.get(second.getAbsolutePath()).failRelease = true;
        LumaRoundVideoStats.inspectAsync(second, 60, "camera2"); Utilities.globalQueue.drain();
        check(LumaRoundVideoStats.lastResult() != null, "release error does not break diagnostics queue");
        check(MediaExtractor.instances == MediaExtractor.released, "all created extractors released");
        LumaRoundVideoStats.inspectAsync(new File(root, "missing.mp4"), 60, "legacy"); Utilities.globalQueue.drain();
        check(LumaRoundVideoStats.lastResult() == null, "missing file leaves unavailable result");
        LumaRoundVideoStats.inspectAsync(second, 60, "camera2"); Utilities.globalQueue.drain();
        MessagesController.prefs.values.put("lunagram_round_stats_width", "corrupt");
        check(LumaRoundVideoStats.lastResult() == null, "corrupt persisted type safely unavailable");
    }
    public static void main(String[] args) throws Exception {
        math(); async(new File(args[0]));
        System.out.println("PASS: round MP4 statistics " + assertions + " assertions (production math and async persistence).");
    }
}
