package org.telegram.messenger;

/** Recording quality only: unedited round videos still upload their original encoded MP4. */
public final class LumaRoundVideoQuality {
    public static final String PREFERENCE_KEY = "luma_round_video_high_quality";
    public static final int HIGH_QUALITY_SIZE = 640;
    public static final int HIGH_QUALITY_VIDEO_BITRATE = 6_000_000;
    public static final int HIGH_QUALITY_AUDIO_BITRATE = 128_000;
    public static final int HIGH_FRAME_RATE = 60;
    public static final int HIGH_FRAME_RATE_VIDEO_BITRATE = 12_000_000;
    public static final int FRAME_RATE = 30;
    public static final int MAX_FRAME_RATE = 120;
    public static final String FRAME_RATE_PREFERENCE_KEY = "lunagram_round_video_fps";
    public static final long MAX_DURATION_MS = 60_000L;

    private LumaRoundVideoQuality() {}

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREFERENCE_KEY, true);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREFERENCE_KEY, enabled).apply();
    }

    public static int normalizeFrameRate(int fps) {
        return Math.max(FRAME_RATE, Math.min(MAX_FRAME_RATE, fps)) / FRAME_RATE * FRAME_RATE;
    }

    public static int getPreferredFrameRate() {
        return getPreferredFrameRate(HIGH_FRAME_RATE);
    }

    /** Preserve each recorder's old default until the common slider is changed. */
    public static int getPreferredFrameRate(int fallback) {
        return normalizeFrameRate(MessagesController.getGlobalMainSettings()
            .getInt(FRAME_RATE_PREFERENCE_KEY, fallback));
    }

    public static int getFrameRateLevel() { return getPreferredFrameRate() / FRAME_RATE - 1; }

    /** An explicit high-FPS choice must not be silently routed through Camera1. */
    public static boolean prefersHighFrameRateCapture() {
        return MessagesController.getGlobalMainSettings().contains(FRAME_RATE_PREFERENCE_KEY)
            && getPreferredFrameRate() > FRAME_RATE;
    }

    public static void setFrameRateLevel(int level) {
        int fps = (Math.max(0, Math.min(3, level)) + 1) * FRAME_RATE;
        MessagesController.getGlobalMainSettings().edit().putInt(FRAME_RATE_PREFERENCE_KEY, fps).apply();
    }

    /** Immutable for a recording, including pause/resume and camera flips. */
    public static final class Profile {
        public final int size;
        public final int videoBitrate;
        public final int audioBitrate;
        public final int frameRate;
        public final boolean highQuality;

        private Profile(int size, int videoBitrate, int audioBitrate, boolean highQuality) {
            this(size, videoBitrate, audioBitrate, FRAME_RATE, highQuality);
        }

        private Profile(int size, int videoBitrate, int audioBitrate, int frameRate, boolean highQuality) {
            this.size = size;
            this.videoBitrate = videoBitrate;
            this.audioBitrate = audioBitrate;
            this.frameRate = frameRate;
            this.highQuality = highQuality;
        }
    }

    public static Profile baseline(int size, int videoKilobits, int audioKilobits) {
        return new Profile(size > 0 ? size : 384,
            fromKilobits(videoKilobits, 1000), fromKilobits(audioKilobits, 64), false);
    }

    public static Profile forCamera(Profile baseline, boolean commonCameraSupport) {
        return forCamera(baseline, commonCameraSupport, false);
    }

    public static Profile forCamera(Profile baseline, boolean commonCameraSupport, boolean common60Support) {
        return forCamera(baseline, commonCameraSupport, common60Support ? HIGH_FRAME_RATE : FRAME_RATE);
    }

    public static Profile forCamera(Profile baseline, boolean commonCameraSupport, int supportedFrameRate) {
        int fps = normalizeFrameRate(Math.min(getPreferredFrameRate(), supportedFrameRate));
        return isEnabled() && commonCameraSupport
            ? new Profile(HIGH_QUALITY_SIZE,
                HIGH_QUALITY_VIDEO_BITRATE * (fps / FRAME_RATE), HIGH_QUALITY_AUDIO_BITRATE, fps, true)
            : baseline;
    }

    public static Profile atFrameRate(Profile profile, int fps) {
        int rate = normalizeFrameRate(Math.min(profile.frameRate, fps));
        return new Profile(profile.size, profile.highQuality ? HIGH_QUALITY_VIDEO_BITRATE * (rate / FRAME_RATE)
            : profile.videoBitrate, profile.audioBitrate, rate, profile.highQuality);
    }

    public static Profile fallback(Profile profile, Profile baseline) {
        return profile.highQuality && profile.frameRate > FRAME_RATE
            ? atFrameRate(profile, profile.frameRate - FRAME_RATE)
            : baseline;
    }

    /** Return only a range the camera advertised, never synthesize (30,60). */
    public static int[] chooseFpsRange(java.util.List<int[]> ranges, int targetFps, int units) {
        if (ranges == null || targetFps <= 0 || units <= 0) return null;
        long target = (long) targetFps * units;
        int[] best = null;
        for (int[] range : ranges) {
            if (range == null || range.length < 2 || range[0] <= 0 || range[0] > range[1]) continue;
            if (range[0] <= target && range[1] >= target
                && (best == null || range[1] < best[1] || range[1] == best[1] && range[0] > best[0])) best = range;
        }
        if (best == null && targetFps <= FRAME_RATE) {
            for (int[] range : ranges) {
                if (range == null || range.length < 2 || range[0] <= 0 || range[0] > range[1] || range[1] > target) continue;
                if (best == null || range[1] > best[1] || range[1] == best[1] && range[0] > best[0]) best = range;
            }
        }
        return best == null ? null : new int[] {best[0], best[1]};
    }

    public static boolean hasFixedFrameRate(java.util.List<int[]> ranges, int fps, int units) {
        int[] range = chooseFpsRange(ranges, fps, units);
        return range != null && (long) fps * units == range[0] && range[0] == range[1];
    }

    public static boolean supportsTargetFrameRate(java.util.List<int[]> ranges, int fps, int units) {
        int[] range = chooseFpsRange(ranges, fps, units);
        // A genuine [15,60] AE range can deliver 60 in sufficient light. Its
        // lower bound is not a capability ceiling; never replace it with a
        // fabricated [60,60] range. Actual cadence is measured from the MP4.
        return range != null && (long) fps * units == range[1];
    }

    /** Unknown stream timing cannot prove normal-session high-FPS support. */
    public static boolean supportsFrameDuration(long durationNanos, int fps) {
        return fps > 0 && durationNanos > 0 && durationNanos <= (1_000_000_000L + fps - 1L) / fps;
    }

    /** Retain real timestamps; a validated 60fps source must not lose frames to sensor jitter. */
    public static final class FrameGate {
        private final long minimumInterval;
        private final long jitterAllowance;
        private final boolean fullCameraCadence;
        private long lastTimestamp = Long.MIN_VALUE;
        private long lastAcceptedTimestamp;
        private Integer lastCamera;
        private long phaseCredit;
        public FrameGate(int fps) {
            this(fps, fps);
        }
        public FrameGate(int fps, int sourceFps) {
            int target = Math.max(1, fps);
            minimumInterval = Math.max(1L, 1_000_000_000L / target);
            // Stay strictly inside the half-period window: an exact midpoint
            // in a regular 60-to-30 stream belongs to the next real frame.
            jitterAllowance = fps > 0 ? Math.max(0L, minimumInterval / 2L - 1L) : 0;
            fullCameraCadence = target >= HIGH_FRAME_RATE && sourceFps <= target;
        }
        public boolean accept(long timestamp, Integer camera) {
            if (timestamp <= 0) return false;
            boolean changed = lastCamera == null ? camera != null : !lastCamera.equals(camera);
            if (lastTimestamp == Long.MIN_VALUE || changed || timestamp < lastTimestamp) {
                lastCamera = camera;
                lastTimestamp = timestamp;
                lastAcceptedTimestamp = timestamp;
                phaseCredit = 0;
                return true;
            }
            if (timestamp == lastTimestamp) return false;
            long elapsed = timestamp - lastTimestamp;
            lastTimestamp = timestamp;
            if (fullCameraCadence) return true;

            // Accumulate from every source frame, not from the last accepted frame.
            // Select a real frame near each target phase, carrying early-frame
            // debt and late-frame overshoot. Normal 30fps jitter must not reset
            // that phase. Only a whole missing source period rebases the clock.
            if (elapsed >= 2L * minimumInterval) {
                phaseCredit = minimumInterval;
            } else {
                phaseCredit += elapsed; // bounded below 4 * minimumInterval
            }
            // This half-period spacing floor only prevents catch-up bursts. It
            // never replaces accumulated phase, and rejected frames keep credit.
            if (timestamp - lastAcceptedTimestamp < minimumInterval - jitterAllowance) return false;
            if (phaseCredit < minimumInterval - jitterAllowance) return false;
            phaseCredit -= minimumInterval;
            // Discard unserved whole periods instead of catching up with bursts;
            // the fractional phase still belongs to the original sensor clock.
            if (phaseCredit >= minimumInterval) phaseCredit %= minimumInterval;
            lastAcceptedTimestamp = timestamp;
            return true;
        }
    }

    private static int fromKilobits(int value, int fallback) {
        return (int) Math.min(Integer.MAX_VALUE, (long) (value > 0 ? value : fallback) * 1024L);
    }

    public static boolean hasSourceSize(int width, int height, int target) {
        return width >= target && height >= target;
    }

    /** Capability facts are obtained from the encoder that will actually be configured. */
    public static boolean supportsVideo(Profile profile, boolean surfaceInput,
                                        int widthAlignment, int heightAlignment,
                                        boolean sizeAndRateSupported, int minBitrate, int maxBitrate) {
        return surfaceInput && widthAlignment > 0 && heightAlignment > 0
            && profile.size % widthAlignment == 0 && profile.size % heightAlignment == 0
            && sizeAndRateSupported && profile.videoBitrate >= minBitrate
            && profile.videoBitrate <= maxBitrate;
    }

    public static void applyMetadata(VideoEditedInfo info, Profile profile) {
        info.framerate = profile.frameRate;
        info.resultWidth = info.originalWidth = profile.size;
        info.resultHeight = info.originalHeight = profile.size;
        info.bitrate = info.originalBitrate = profile.videoBitrate;
    }
}
