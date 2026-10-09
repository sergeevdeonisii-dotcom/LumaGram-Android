package org.telegram.messenger;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.view.Display;
import android.view.WindowManager;

/** Exercises production policy/preferences, camera mode selection and horizon sensor lifecycle. */
public final class RoundEditions100Test {
    private static int checks;
    private static void check(boolean actual, String description) {
        checks++;
        if (!actual) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        boolean personal = !Boolean.parseBoolean(args[0]);
        int maximum = personal ? LumaRoundVideoStabilization.ENHANCED : LumaRoundVideoStabilization.STANDARD;
        check(LumaBuildPolicy.allowsEnhancedRoundVideoStabilization() == personal, "Compile-time horizon entitlement");
        check(LumaBuildPolicy.allowsPrivacyTools() == personal, "Unrelated privacy policy unchanged");
        check(LumaBuildPolicy.allowsAnonymousNumber() == personal, "Unrelated anonymous-number policy unchanged");
        check(LumaBuildPolicy.allowsProfileVerification() == personal, "Unrelated profile-verification policy unchanged");
        check(LumaBuildPolicy.allowsBuiltInUpdates() == personal, "Personal update feed remains isolated");
        MessagesController.values.clear();
        check(LumaRoundVideoStabilization.getMode() == LumaRoundVideoStabilization.OFF, "Stabilization remains opt-in");
        int[] modes = {Integer.MIN_VALUE, -1, 0, 1, 2, 3, Integer.MAX_VALUE};
        for (int requested : modes) {
            int effective = Math.max(0, Math.min(maximum, requested));
            // Simulates a restored preferences file, bypassing the settings setter.
            MessagesController.values.put(LumaRoundVideoStabilization.PREFERENCE_KEY, requested);
            check(LumaRoundVideoStabilization.getMode() == effective, "Imported mode is runtime restricted: " + requested);
            LumaRoundVideoStabilization.setMode(requested);
            check(LumaRoundVideoStabilization.getMode() == effective, "Setter mode is runtime restricted: " + requested);
            check(MessagesController.values.get(LumaRoundVideoStabilization.PREFERENCE_KEY).equals(effective), "Setter persists only entitled mode");
            for (int capabilities = 0; capabilities < 16; capabilities++) {
                boolean eis = (capabilities & 1) != 0;
                boolean preview = (capabilities & 2) != 0;
                boolean ois = (capabilities & 4) != 0;
                boolean fast = (capabilities & 8) != 0;
                int video = LumaRoundVideoStabilization.videoMode(requested, eis, preview, ois, fast);
                int expectedVideo = effective == 0 || fast ? 0
                    : effective == 2 && preview ? 2
                    : effective == 1 && ois ? 0
                    : eis ? 1 : 0;
                check(video == expectedVideo, "Raw camera mode respects entitlement and capabilities");
                check(personal || video != 2, "Public request never enables enhanced video mode");
                check(LumaRoundVideoStabilization.opticalMode(requested, video, ois)
                    == (effective > 0 && video == 0 && ois), "Ordinary optical stabilization remains available");
            }
        }
        MessagesController.values.put(LumaRoundVideoStabilization.PREFERENCE_KEY, "ENHANCED");
        check(LumaRoundVideoStabilization.getMode() == 0, "Wrong restored preference type safely disables stabilization");

        LumaRoundVideoQuality.Profile base = LumaRoundVideoQuality.baseline(384, 1000, 64);
        for (int level = 0; level < 2; level++) {
            LumaRoundVideoQuality.setEnabled(true);
            LumaRoundVideoQuality.setFrameRateLevel(level);
            int fps = (level + 1) * 30;
            LumaRoundVideoQuality.Profile profile = LumaRoundVideoQuality.forCamera(base, true, fps);
            check(LumaRoundVideoQuality.isEnabled(), "HQ available in either edition");
            check(profile.highQuality && profile.size == LumaRoundVideoQuality.HIGH_QUALITY_SIZE,
                "High-resolution compatible round profile available in either edition");
            check(profile.frameRate == fps, "All supported FPS levels available in either edition");
            check(profile.videoBitrate > base.videoBitrate, "Quality bitrate not edition restricted");
        }
        final SensorManager sensors = new SensorManager();
        final WindowManager windows = () -> new Display();
        Context context = new Context() {
            public Object getSystemService(String name) {
                return SENSOR_SERVICE.equals(name) ? sensors : windows;
            }
        };
        LumaHorizonLock horizon = new LumaHorizonLock(context);
        check(horizon.start() == personal, "Direct horizon start also enforces build policy");
        check(horizon.isRunning() == personal, "Public horizon never becomes active");
        check(sensors.registrations == (personal ? 1 : 0), "Public build never registers horizon sensors");
        SensorEvent event = new SensorEvent();
        event.sensor = new Sensor(Sensor.TYPE_GRAVITY);
        event.values = new float[] {1, 0, 0};
        event.timestamp = 1_000_000_000L;
        horizon.onSensorChanged(event);
        check(personal ? Math.abs(horizon.correction(false, event.timestamp)) == 90
            : horizon.correction(false, event.timestamp) == 0, "Only personal sensor data alters camera transform");
        horizon.stop();
        check(!horizon.isRunning() && horizon.correction(false, event.timestamp) == 0, "Stop clears active correction");
        check(horizon.start() == personal, "Restart cannot bypass entitlement");
        check(sensors.registrations == (personal ? 2 : 0), "No public sensor registration after restart");
        horizon.stop();
        System.out.println("PASS: " + checks + " round-video edition assertions (" + (personal ? "personal" : "public") + ").");
    }
}
