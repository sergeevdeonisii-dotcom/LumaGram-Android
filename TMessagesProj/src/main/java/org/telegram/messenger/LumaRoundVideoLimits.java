package org.telegram.messenger;

import java.io.File;

/** Telegram round-message size policy. Never truncate content to satisfy a byte limit. */
public final class LumaRoundVideoLimits {
    public static final long DEFAULT_MAX_FILE_BYTES = 12L * 1024L * 1024L;
    private static final int AUDIO_RESERVE_BITRATE = 192_000;
    private static final long CONTAINER_RESERVE_BYTES = 256L * 1024L;

    private LumaRoundVideoLimits() {}

    public static long normalizeLimit(long value) {
        return value > 0 && value <= Integer.MAX_VALUE ? value : DEFAULT_MAX_FILE_BYTES;
    }

    public static int targetVideoBitrate(long limit, long durationMs) {
        if (durationMs <= 0 || durationMs > LumaRoundVideoQuality.MAX_DURATION_MS + 2_000L) return 0;
        long payload = normalizeLimit(limit) * 4L / 5L - CONTAINER_RESERVE_BYTES;
        if (payload <= 0) return 0;
        long available = payload * 8_000L / durationMs - AUDIO_RESERVE_BITRATE;
        return available < 64_000L ? 0 : (int) Math.min(available, Integer.MAX_VALUE);
    }

    /** Called only after the recorder has closed its MP4, before any send request uses its upload. */
    public static boolean prepareForSend(VideoEditedInfo info, File source, long limit) {
        if (info == null || !info.roundVideo || info.notReadyYet) return false;
        long maximum = normalizeLimit(limit);
        if (source.length() <= maximum && !info.needConvert()) return false;
        info.roundVideoFileSizeLimit = maximum;
        int target = targetVideoBitrate(maximum, info.estimatedDuration);
        // Zero deliberately fails in MediaController rather than falling back to an arbitrary bitrate.
        if (info.bitrate > 0 && target > 0) target = Math.min(info.bitrate, target);
        info.bitrate = target;
        info.estimatedSize = Math.min(maximum, Math.max(1L,
            ((long) Math.max(0, target) + AUDIO_RESERVE_BITRATE) * Math.max(0L, info.estimatedDuration) / 8_000L
                + CONTAINER_RESERVE_BYTES));
        info.file = null;
        info.encryptedFile = null;
        info.key = null;
        info.iv = null;
        return true;
    }

    public static boolean acceptsOutput(long bytes, long limit) {
        return bytes > 0 && bytes <= normalizeLimit(limit);
    }

    public static boolean acceptsVideo(int width, int height, long durationMs, long expectedDurationMs) {
        return width > 0 && width == height && width <= 640
            && durationMs > 0 && durationMs <= LumaRoundVideoQuality.MAX_DURATION_MS + 500L
            && expectedDurationMs > 0 && Math.abs(durationMs - expectedDurationMs) <= 500L;
    }
}
