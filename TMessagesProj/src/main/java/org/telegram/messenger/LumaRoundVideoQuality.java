package org.telegram.messenger;

/** Recording quality only: unedited round videos still upload their original encoded MP4. */
public final class LumaRoundVideoQuality {
    public static final String PREFERENCE_KEY = "luma_round_video_high_quality";
    public static final int HIGH_QUALITY_SIZE = 640;
    public static final int HIGH_QUALITY_VIDEO_BITRATE = 6_000_000;
    public static final int HIGH_QUALITY_AUDIO_BITRATE = 128_000;
    public static final int FRAME_RATE = 30;
    public static final long MAX_DURATION_MS = 60_000L;

    private LumaRoundVideoQuality() {}

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREFERENCE_KEY, true);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREFERENCE_KEY, enabled).apply();
    }

    /** Immutable for a recording, including pause/resume and camera flips. */
    public static final class Profile {
        public final int size;
        public final int videoBitrate;
        public final int audioBitrate;
        public final int frameRate;
        public final boolean highQuality;

        private Profile(int size, int videoBitrate, int audioBitrate, boolean highQuality) {
            this.size = size;
            this.videoBitrate = videoBitrate;
            this.audioBitrate = audioBitrate;
            this.frameRate = FRAME_RATE;
            this.highQuality = highQuality;
        }
    }

    public static Profile baseline(int size, int videoKilobits, int audioKilobits) {
        return new Profile(size > 0 ? size : 384,
            fromKilobits(videoKilobits, 1000), fromKilobits(audioKilobits, 64), false);
    }

    public static Profile forCamera(Profile baseline, boolean commonCameraSupport) {
        return isEnabled() && commonCameraSupport
            ? new Profile(HIGH_QUALITY_SIZE, HIGH_QUALITY_VIDEO_BITRATE, HIGH_QUALITY_AUDIO_BITRATE, true)
            : baseline;
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
