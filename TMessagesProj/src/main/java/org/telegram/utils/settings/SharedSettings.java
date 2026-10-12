package org.telegram.utils.settings;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramSubscriptionAccess;
import org.telegram.messenger.UserConfig;
import org.telegram.utils.camera.roundvideo.RoundVideoSession;

public final class SharedSettings {

    public static final BooleanSetting experimentalSettingsAllowed =
        BooleanSetting.of("experimental_settings_allowed", BuildConfig.DEBUG_VERSION);

    public static final BooleanSetting roundVideoCamera2Enabled =
        BooleanSetting.of("round_video_camera2_enabled", BuildConfig.DEBUG_VERSION);

    public static final EnumSetting<RoundVideoSession.OutputResolution> roundVideoOutputResolution =
        EnumSetting.of("round_video_output_resolution", RoundVideoSession.OutputResolution.P640);

    public static final EnumSetting<RoundVideoSession.CameraResolution> roundVideoCameraResolution =
        EnumSetting.of("round_video_camera_resolution", RoundVideoSession.CameraResolution.HIGH);

    public static final EnumSetting<RoundVideoSession.FrameRate> roundVideoFrameRate =
        EnumSetting.of("round_video_frame_rate", RoundVideoSession.FrameRate.FPS_30);

    public static final IntSetting roundVideoVideoBitrate =
        IntSetting.of("round_video_video_bitrate", 4_000_000);

    public static final BooleanSetting roundVideoComposition =
        BooleanSetting.of("round_video_composition", true);

    public static final EnumSetting<RoundVideoSession.CameraFacing> roundVideoLastCamera =
        EnumSetting.of("round_video_last_camera", RoundVideoSession.CameraFacing.FRONT);

    /** Migrate .82's unsupported encoded sizes, retaining lower explicit choices. */
    public static RoundVideoSession.OutputResolution getRoundVideoOutputResolution() {
        if (!canUseRoundVideoControls()) return RoundVideoSession.OutputResolution.P640;
        RoundVideoSession.OutputResolution stored;
        try {
            stored = roundVideoOutputResolution.get();
        } catch (ClassCastException invalidRestoredValue) {
            stored = RoundVideoSession.OutputResolution.P640;
            roundVideoOutputResolution.set(stored);
        }
        RoundVideoSession.OutputResolution compatible = stored.forVideoNote();
        if (compatible != stored) roundVideoOutputResolution.set(compatible);
        return compatible;
    }

    public static RoundVideoSession.FrameRate getRoundVideoFrameRate() {
        if (!canUseRoundVideoControls()) return RoundVideoSession.FrameRate.FPS_30;
        RoundVideoSession.FrameRate stored;
        try {
            stored = roundVideoFrameRate.get();
        } catch (ClassCastException invalidRestoredValue) {
            stored = RoundVideoSession.FrameRate.FPS_30;
            roundVideoFrameRate.set(stored);
        }
        RoundVideoSession.FrameRate compatible = RoundVideoSession.FrameRate.fromValue(stored.getValue());
        if (compatible != stored) roundVideoFrameRate.set(compatible);
        return compatible;
    }

    /** Effective recording defaults; denied overrides remain stored for a later renewal. */
    public static RoundVideoSession.CameraResolution getRoundVideoCameraResolution() {
        return canUseRoundVideoControls() ? roundVideoCameraResolution.get() : RoundVideoSession.CameraResolution.HIGH;
    }

    public static int getRoundVideoBitrate() {
        return canUseRoundVideoControls() ? roundVideoVideoBitrate.get() : 4_000_000;
    }

    private static boolean canUseRoundVideoControls() {
        return LunagramSubscriptionAccess.canUse(UserConfig.selectedAccount, LunagramFeatureCatalog.Feature.ROUND_VIDEO_CONTROLS);
    }

    private SharedSettings() {
    }
}
