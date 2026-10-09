package org.telegram.messenger;

import java.util.HashSet;

/** Encoded-sample measurements, independent of requested FPS and container FPS metadata. */
final class LumaRoundVideoMetrics {
    static final int MAX_SAMPLES = 50_000;
    private static final long MIN_SPAN_US = 1_000_000L;
    private static final long MAX_SPAN_US = 300_000_000L;

    private final HashSet<Long> timestamps = new HashSet<>();
    private long minimumUs = Long.MAX_VALUE;
    private long maximumUs = Long.MIN_VALUE;
    private long bytes;
    private int sampleCount;
    private boolean valid = true;

    /** Duplicate PTS consume encoded bytes but are not additional displayed frames. */
    boolean addSample(long presentationTimeUs, long size) {
        if (!valid || ++sampleCount > MAX_SAMPLES || presentationTimeUs < 0 || size <= 0
                || bytes > Long.MAX_VALUE - size) {
            valid = false;
            return false;
        }
        bytes += size;
        if (timestamps.add(presentationTimeUs)) {
            minimumUs = Math.min(minimumUs, presentationTimeUs);
            maximumUs = Math.max(maximumUs, presentationTimeUs);
        }
        return true;
    }

    Measurement finish() {
        int uniqueFrames = timestamps.size();
        if (!valid || uniqueFrames < 2) return null;
        long spanUs = maximumUs - minimumUs;
        if (spanUs < MIN_SPAN_US || spanUs > MAX_SPAN_US) return null;
        // n frames have n - 1 measured intervals, including missing-frame gaps.
        double fps = (uniqueFrames - 1) * 1_000_000.0 / spanUs;
        if (!Double.isFinite(fps) || fps <= 0 || fps > 300) return null;
        // Estimate the last sample's duration from the observed mean interval.
        // Counting that duration avoids overstating bitrate for a short clip.
        double durationUs = (double) spanUs * uniqueFrames / (uniqueFrames - 1);
        double bitsPerSecond = bytes * 8_000_000.0 / durationUs;
        if (!Double.isFinite(bitsPerSecond) || bitsPerSecond <= 0
                || bitsPerSecond > 1_000_000_000L) return null;
        return new Measurement(fps, Math.round(bitsPerSecond), uniqueFrames, Math.round(durationUs));
    }

    static final class Measurement {
        final double measuredFps;
        final long videoBitrate;
        final int uniqueFrames;
        final long durationUs;

        Measurement(double measuredFps, long videoBitrate, int uniqueFrames, long durationUs) {
            this.measuredFps = measuredFps;
            this.videoBitrate = videoBitrate;
            this.uniqueFrames = uniqueFrames;
            this.durationUs = durationUs;
        }
    }
}
