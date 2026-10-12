package org.telegram.messenger;

import java.util.HashMap;
import java.util.Map;
import org.telegram.utils.settings.SharedSettings;
import org.telegram.utils.camera.roundvideo.RoundVideoSession;

/** Exercises actual preference getters; billing authority and physical camera are explicit test inputs. */
public final class RoundAccess150Test {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        UserConfig.selectedAccount = 0;
        SharedSettings.roundVideoOutputResolution.set(RoundVideoSession.OutputResolution.P360);
        SharedSettings.roundVideoCameraResolution.set(RoundVideoSession.CameraResolution.LOW);
        SharedSettings.roundVideoFrameRate.set(RoundVideoSession.FrameRate.FPS_60);
        SharedSettings.roundVideoVideoBitrate.set(8_000_000);
        SharedSettings.roundVideoCamera2Enabled.set(true);
        SharedSettings.roundVideoComposition.set(false);
        LumaRoundVideoQuality.setEnabled(true);
        LumaRoundVideoQuality.setFrameRateLevel(1);
        LumaRoundVideoStabilization.setMode(LumaRoundVideoStabilization.ENHANCED);
        LumaRoundVideoCamera.setFastSwitchEnabled(true);
        LumaRoundVideoCamera.setStartWithRearCameraEnabled(true);
        Map<String,Object> stored = new HashMap<>(MessagesController.values);
        for (int account = 0; account < 3; account++) {
            UserConfig.selectedAccount = account;
            LunagramSubscriptionAccess.allowed[account] = false;
            for (int repeat = 0; repeat < 3; repeat++) {
                check(SharedSettings.getRoundVideoOutputResolution() == RoundVideoSession.OutputResolution.P640, "denied keeps interoperable 640 output");
                check(SharedSettings.getRoundVideoCameraResolution() == RoundVideoSession.CameraResolution.HIGH, "denied source baseline");
                check(SharedSettings.getRoundVideoFrameRate() == RoundVideoSession.FrameRate.FPS_30, "denied native fps baseline");
                check(SharedSettings.getRoundVideoBitrate() == 4_000_000, "denied bitrate baseline");
                check(!LumaRoundVideoQuality.isEnabled(), "no custom legacy HQ override");
                check(LumaRoundVideoQuality.getPreferredFrameRate(60) == 30, "explicit fallback cannot bypass denied FPS");
                check(!LumaRoundVideoQuality.prefersHighFrameRateCapture(), "no denied high fps routing");
                check(LumaRoundVideoStabilization.getMode() == LumaRoundVideoStabilization.OFF, "no denied stabilization");
                check(!LumaRoundVideoCamera.isFastSwitchEnabled(), "no denied concurrency");
                check(LumaRoundVideoCamera.isStartWithRearCameraEnabled(), "initial facing stays free");
                check(SharedSettings.roundVideoCamera2Enabled.get() && !SharedSettings.roundVideoComposition.get(), "engine/composition unchanged");
                check(MessagesController.values.equals(stored), "denied getters never erase or migrate preferences");
            }
            LunagramSubscriptionAccess.allowed[account] = true;
            check(SharedSettings.getRoundVideoOutputResolution() == RoundVideoSession.OutputResolution.P360, "renewal restores chosen resolution");
            check(SharedSettings.getRoundVideoCameraResolution() == RoundVideoSession.CameraResolution.LOW, "renewal restores chosen source");
            check(SharedSettings.getRoundVideoFrameRate() == RoundVideoSession.FrameRate.FPS_60, "renewal restores native60");
            check(SharedSettings.getRoundVideoBitrate() == 8_000_000, "renewal restores bitrate");
            check(LumaRoundVideoQuality.getPreferredFrameRate() == 60 && LumaRoundVideoQuality.isEnabled(), "renewal restores custom legacy quality");
            check(LumaRoundVideoStabilization.getMode() == LumaRoundVideoStabilization.ENHANCED && LumaRoundVideoCamera.isFastSwitchEnabled(), "renewal restores stabilization/concurrency");
        }
        // Even malformed high-FPS preferences must remain untouched while denied.
        UserConfig.selectedAccount = 1;
        LunagramSubscriptionAccess.allowed[1] = false;
        MessagesController.values.put(LumaRoundVideoQuality.FRAME_RATE_PREFERENCE_KEY, "invalid");
        stored = new HashMap<>(MessagesController.values);
        check(LumaRoundVideoQuality.getPreferredFrameRate() == 30 && MessagesController.values.equals(stored), "denied no preference repair writes");
        UserConfig.selectedAccount = 0;
        check(SharedSettings.getRoundVideoBitrate() == 8_000_000, "switch back does not inherit other account denial");
        LumaRoundVideoQuality.Profile baseline = LumaRoundVideoQuality.baseline(384,1000,64);
        UserConfig.selectedAccount = 1;
        check(LumaRoundVideoQuality.forCamera(baseline,true,60) == baseline, "legacy retains exact existing compatible baseline");
        System.out.println("PASS: " + checks + " actual round-video access/getter/account/prefs checks; no hardware or billing authority execution.");
    }
}
