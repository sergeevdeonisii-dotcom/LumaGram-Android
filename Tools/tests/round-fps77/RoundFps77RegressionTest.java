package org.telegram.messenger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Tests the actual production helper, not Android's camera or media stack. */
public final class RoundFps77RegressionTest {
    private static int cases, assertions, failures;
    private interface TestBody { void run() throws Exception; }
    private static void test(String label, TestBody body) {
        cases++;
        MessagesController.values.clear();
        try {
            body.run();
            System.out.println("PASS: " + label);
        } catch (Throwable e) {
            failures++;
            System.out.println("FAIL: " + label + " — " + e);
        }
    }
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    private static List<int[]> ranges(int... endpoints) {
        if (endpoints.length % 2 != 0) throw new IllegalArgumentException("Endpoint pairs required");
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < endpoints.length; i += 2) result.add(new int[] {endpoints[i], endpoints[i + 1]});
        return result;
    }
    private static void range(int[] actual, int minimum, int maximum, String label) {
        check(actual != null && actual.length == 2 && actual[0] == minimum && actual[1] == maximum, label);
    }
    private static LumaRoundVideoQuality.Profile base() {
        return LumaRoundVideoQuality.baseline(384, 1000, 64);
    }
    private static LumaRoundVideoQuality.Profile hd60() {
        return LumaRoundVideoQuality.forCamera(base(), true, true);
    }
    private static void profile(LumaRoundVideoQuality.Profile p, int size, int rate, int video, int audio, boolean high, String label) {
        check(p != null && p.size == size && p.frameRate == rate && p.videoBitrate == video
            && p.audioBitrate == audio && p.highQuality == high, label);
    }
    private static void profiles() {
        test("HQ preference defaults enabled and owns the existing preference key", () -> {
            check(LumaRoundVideoQuality.isEnabled(), "HD default");
            check(LumaRoundVideoQuality.PREFERENCE_KEY.equals("luma_round_video_high_quality"), "Migration-safe key");
            LumaRoundVideoQuality.setEnabled(false);
            check(!LumaRoundVideoQuality.isEnabled(), "Disabled persists");
            LumaRoundVideoQuality.setEnabled(true);
            check(LumaRoundVideoQuality.isEnabled(), "Re-enabled persists");
        });
        test("640/60/12Mbps target requires common camera size and supported 60 range", () -> {
            LumaRoundVideoQuality.Profile b = base();
            profile(LumaRoundVideoQuality.forCamera(b, true, true), 640, 60, 12_000_000, 128_000, true, "Actual 60 profile");
            profile(LumaRoundVideoQuality.forCamera(b, true, false), 640, 30, 6_000_000, 128_000, true, "HD30 fallback");
            check(LumaRoundVideoQuality.forCamera(b, false, true) == b, "60 does not bypass absent native HD source");
            check(LumaRoundVideoQuality.forCamera(b, false, false) == b, "No camera support keeps exact baseline");
            profile(LumaRoundVideoQuality.forCamera(b, true), 640, 30, 6_000_000, 128_000, true, "Legacy overload does not fabricate 60");
        });
        test("Disabled HQ preserves custom baseline even with 60-capable cameras", () -> {
            LumaRoundVideoQuality.Profile b = LumaRoundVideoQuality.baseline(512, 1500, 96);
            LumaRoundVideoQuality.setEnabled(false);
            check(LumaRoundVideoQuality.forCamera(b, true, true) == b, "Disabled uses supplied baseline");
            profile(b, 512, 30, 1_536_000, 98_304, false, "Server-configurable baseline preserved");
        });
        test("Profile immutable across preferences, flips, and downgrade", () -> {
            LumaRoundVideoQuality.Profile initial = hd60();
            for (Field field : LumaRoundVideoQuality.Profile.class.getFields()) {
                check(Modifier.isFinal(field.getModifiers()), "Public profile field immutable: " + field.getName());
            }
            LumaRoundVideoQuality.setEnabled(false);
            profile(initial, 640, 60, 12_000_000, 128_000, true, "Preference mutation does not alter existing recording");
            LumaRoundVideoQuality.Profile lower = LumaRoundVideoQuality.fallback(initial, base());
            check(lower != initial, "Fallback creates a separate immutable profile");
            profile(initial, 640, 60, 12_000_000, 128_000, true, "Downgrade does not mutate 60 track");
        });
        test("Codec/camera failure ladder is HD60 then HD30 then exact baseline", () -> {
            LumaRoundVideoQuality.Profile b = base(), sixty = hd60();
            LumaRoundVideoQuality.Profile thirty = LumaRoundVideoQuality.fallback(sixty, b);
            profile(thirty, 640, 30, 6_000_000, 128_000, true, "First failure retains native HD crop");
            check(LumaRoundVideoQuality.fallback(thirty, b) == b, "Second failure chooses supplied baseline");
            check(LumaRoundVideoQuality.fallback(b, b) == b, "Baseline remains baseline");
            check(LumaRoundVideoQuality.MAX_DURATION_MS == 60_000L, "Sixty-second duration is not reduced");
        });
        test("Invalid app baseline and bitrate conversion are bounded", () -> {
            profile(LumaRoundVideoQuality.baseline(0, -1, 0), 384, 30, 1_024_000, 65_536, false, "Safe baseline defaults");
            check(LumaRoundVideoQuality.baseline(384, Integer.MAX_VALUE, Integer.MAX_VALUE).videoBitrate == Integer.MAX_VALUE, "Video bitrate saturation");
            check(LumaRoundVideoQuality.baseline(384, Integer.MAX_VALUE, Integer.MAX_VALUE).audioBitrate == Integer.MAX_VALUE, "Audio bitrate saturation");
        });
        test("Actual codec size/rate, surface, alignment, and bitrate gate both profiles", () -> {
            LumaRoundVideoQuality.Profile sixty = hd60(), thirty = LumaRoundVideoQuality.fallback(sixty, base());
            check(LumaRoundVideoQuality.supportsVideo(sixty, true, 16, 16, true, 12_000_000, 12_000_000), "Inclusive 60 bitrate range");
            check(!LumaRoundVideoQuality.supportsVideo(sixty, true, 16, 16, true, 1, 8_000_000), "12Mbps unsupported");
            check(LumaRoundVideoQuality.supportsVideo(thirty, true, 16, 16, true, 1, 8_000_000), "Same-size HD30 fits codec");
            check(!LumaRoundVideoQuality.supportsVideo(sixty, true, 16, 16, false, 1, 16_000_000), "Actual rate unsupported");
            check(!LumaRoundVideoQuality.supportsVideo(sixty, false, 16, 16, true, 1, 16_000_000), "Surface input required");
            check(!LumaRoundVideoQuality.supportsVideo(sixty, true, 0, 16, true, 1, 16_000_000), "Invalid alignment rejected");
            check(!LumaRoundVideoQuality.supportsVideo(sixty, true, 256, 16, true, 1, 16_000_000), "Dimensions never rounded/upscaled to fit alignment");
            check(!LumaRoundVideoQuality.hasSourceSize(1280, 480, 640), "Source crop cannot fabricate missing dimension");
        });
        test("Every profile yields consistent metadata without modifying trim or original path", () -> {
            LumaRoundVideoQuality.Profile b = base(), sixty = hd60(), thirty = LumaRoundVideoQuality.fallback(sixty, b);
            VideoEditedInfo info = new VideoEditedInfo();
            info.originalPath = "unedited-original.mp4";
            info.startTime = 2_000_000L; info.endTime = 7_000_000L;
            for (LumaRoundVideoQuality.Profile p : Arrays.asList(sixty, thirty, b)) {
                LumaRoundVideoQuality.applyMetadata(info, p);
                check(info.originalWidth == p.size && info.resultWidth == p.size
                    && info.originalHeight == p.size && info.resultHeight == p.size, "Actual geometry");
                check(info.framerate == p.frameRate && info.bitrate == p.videoBitrate && info.originalBitrate == p.videoBitrate, "Actual FPS and bitrate for every fallback");
                check(info.startTime == 2_000_000L && info.endTime == 7_000_000L
                    && info.originalPath.equals("unedited-original.mp4") && info.roundVideo, "Trim/path/round flag retained");
            }
        });
    }
    private static void selection() {
        test("Exact fixed 60 wins over variable ranges in every input ordering", () -> {
            List<int[]> options = ranges(15, 30, 30, 60, 60, 60, 15, 60, 60, 120);
            Random random = new Random(77);
            for (int i = 0; i < 150; i++) {
                Collections.shuffle(options, random);
                range(LumaRoundVideoQuality.chooseFpsRange(options, 60, 1), 60, 60, "Fixed 60 selection");
                check(LumaRoundVideoQuality.hasFixedFrameRate(options, 60, 1), "Fixed 60 capability");
            }
        });
        test("Variable-only 30-to-60 is advertised but does not prove fixed 60", () -> {
            List<int[]> options = ranges(30, 60);
            range(LumaRoundVideoQuality.chooseFpsRange(options, 60, 1), 30, 60, "Choose existing range, not synthesized fixed 60");
            check(!LumaRoundVideoQuality.hasFixedFrameRate(options, 60, 1), "Variable does not falsely report constant cadence");
            check(LumaRoundVideoQuality.supportsTargetFrameRate(options, 60, 1), "Owner policy accepts advertised 30-to-60 target");
            profile(LumaRoundVideoQuality.forCamera(base(), true,
                LumaRoundVideoQuality.supportsTargetFrameRate(options, 60, 1)), 640, 60, 12_000_000, 128_000, true, "60 is nominal recording target, not a low-light cadence guarantee");
        });
        test("Target 60 accepts only ranges capped at 60 with at least 30 FPS minimum", () -> {
            check(LumaRoundVideoQuality.supportsTargetFrameRate(ranges(60, 60), 60, 1), "Fixed 60 accepted");
            check(LumaRoundVideoQuality.supportsTargetFrameRate(ranges(30, 60), 60, 1), "Variable target 60 accepted");
            check(!LumaRoundVideoQuality.supportsTargetFrameRate(ranges(15, 60), 60, 1), "Too-low minimum rejected");
            check(!LumaRoundVideoQuality.supportsTargetFrameRate(ranges(30, 120), 60, 1), "Upper bound above requested target rejected");
            check(!LumaRoundVideoQuality.supportsTargetFrameRate(ranges(30, 30), 60, 1), "30 maximum cannot prove target 60");
            check(LumaRoundVideoQuality.supportsTargetFrameRate(ranges(30_000, 60_000), 60, 1000), "Legacy 30-to-60 target accepted in thousandths");
            check(!LumaRoundVideoQuality.supportsTargetFrameRate(ranges(15_000, 60_000), 60, 1000), "Legacy low minimum rejected");
            check(!LumaRoundVideoQuality.supportsTargetFrameRate(ranges(30_000, 120_000), 60, 1000), "Legacy too-high maximum rejected");
            check(!LumaRoundVideoQuality.supportsTargetFrameRate(null, 60, 1), "Null ranges cannot prove target");
            check(!LumaRoundVideoQuality.supportsTargetFrameRate(ranges(60, 60), 0, 1), "Zero target rejected");
            check(!LumaRoundVideoQuality.supportsTargetFrameRate(ranges(60, 60), 60, 0), "Zero units rejected");
        });
        test("Tightest advertised range containing target is selected", () -> {
            range(LumaRoundVideoQuality.chooseFpsRange(ranges(15, 60, 15, 30, 24, 30), 30, 1), 24, 30, "Highest lower bound of smallest upper");
            range(LumaRoundVideoQuality.chooseFpsRange(ranges(15, 30, 30, 30, 24, 30), 30, 1), 30, 30, "Fixed 30 preferred");
        });
        test("Camera1 thousandths and Camera2 plain FPS cannot be mixed", () -> {
            List<int[]> legacy = ranges(15_000, 30_000, 30_000, 60_000, 60_000, 60_000);
            range(LumaRoundVideoQuality.chooseFpsRange(legacy, 60, 1000), 60_000, 60_000, "Camera1 fixed 60");
            check(LumaRoundVideoQuality.hasFixedFrameRate(legacy, 60, 1000), "Legacy fixed proof");
            check(!LumaRoundVideoQuality.hasFixedFrameRate(legacy, 60, 1), "Plain FPS cannot match legacy endpoints");
            check(!LumaRoundVideoQuality.hasFixedFrameRate(ranges(60, 60), 60, 1000), "Legacy units cannot match Camera2 endpoints");
        });
        test("Unknown and malformed ranges never fabricate camera support", () -> {
            check(LumaRoundVideoQuality.chooseFpsRange(null, 60, 1) == null, "Null list");
            check(LumaRoundVideoQuality.chooseFpsRange(Collections.emptyList(), 60, 1) == null, "Empty list");
            List<int[]> invalid = Arrays.asList(null, new int[0], new int[] {60}, new int[] {0, 60}, new int[] {-60, 60}, new int[] {60, 30});
            check(LumaRoundVideoQuality.chooseFpsRange(invalid, 60, 1) == null, "Malformed-only list");
            check(!LumaRoundVideoQuality.hasFixedFrameRate(invalid, 60, 1), "Malformed cannot prove fixed 60");
            invalid = new ArrayList<>(invalid); invalid.add(new int[] {60, 60});
            range(LumaRoundVideoQuality.chooseFpsRange(invalid, 60, 1), 60, 60, "Malformed entries do not mask valid pair");
            check(LumaRoundVideoQuality.chooseFpsRange(invalid, 0, 1) == null, "Invalid zero FPS");
            check(LumaRoundVideoQuality.chooseFpsRange(invalid, -60, 1) == null, "Invalid negative FPS");
            check(LumaRoundVideoQuality.chooseFpsRange(invalid, 60, 0) == null, "Invalid zero units");
            check(LumaRoundVideoQuality.chooseFpsRange(invalid, 60, -1) == null, "Invalid negative units");
        });
        test("No-match FPS fallback is permitted only at baseline rates", () -> {
            List<int[]> options = ranges(10, 15, 15, 24, 24, 24, 60, 60);
            range(LumaRoundVideoQuality.chooseFpsRange(options, 30, 1), 24, 24, "Highest safe lower cadence");
            check(!LumaRoundVideoQuality.hasFixedFrameRate(options, 30, 1), "24 cannot be reported as fixed 30");
            check(LumaRoundVideoQuality.chooseFpsRange(ranges(15, 30), 60, 1) == null, "No blind 60 fallback");
            check(LumaRoundVideoQuality.chooseFpsRange(ranges(15, 30), 31, 1) == null, "No fallback above baseline rate");
            check(LumaRoundVideoQuality.chooseFpsRange(ranges(60, 60), 30, 1) == null, "Never select faster range when no target match");
        });
        test("Chosen endpoints are detached copies and do not mutate advertised ranges", () -> {
            int[] advertised = {60, 60}; List<int[]> options = new ArrayList<>(); options.add(advertised);
            int[] first = LumaRoundVideoQuality.chooseFpsRange(options, 60, 1);
            check(first != advertised, "Defensive copy");
            first[0] = 1;
            check(advertised[0] == 60, "Caller cannot corrupt shared camera capabilities");
            int[] second = LumaRoundVideoQuality.chooseFpsRange(options, 60, 1);
            advertised[1] = 120;
            range(second, 60, 60, "Future capability mutation cannot corrupt captured selection");
            check(options.size() == 1 && options.get(0) == advertised, "Input list identity/order preserved");
        });
        test("Target multiplication cannot wrap into an advertised endpoint", () -> {
            check(LumaRoundVideoQuality.chooseFpsRange(ranges(60, 60), Integer.MAX_VALUE, Integer.MAX_VALUE) == null, "Overflow-safe high target");
            check(!LumaRoundVideoQuality.hasFixedFrameRate(ranges(1, 1), Integer.MAX_VALUE, Integer.MAX_VALUE), "Overflow cannot invent fixed capability");
        });
        test("Randomized selections always come from valid advertised endpoints", () -> {
            Random random = new Random(6077);
            for (int trial = 0; trial < 1200; trial++) {
                List<int[]> options = new ArrayList<>();
                for (int i = 0; i < 12; i++) options.add(new int[] {random.nextInt(100) - 5, random.nextInt(125) - 5});
                int target = trial % 2 == 0 ? 30 : 60;
                int[] selected = LumaRoundVideoQuality.chooseFpsRange(options, target, 1);
                boolean containsTarget = false, existsLowFallback = false;
                for (int[] candidate : options) {
                    if (candidate[0] > 0 && candidate[0] <= candidate[1]) {
                        containsTarget |= candidate[0] <= target && candidate[1] >= target;
                        existsLowFallback |= target <= 30 && candidate[1] <= target;
                    }
                }
                check((selected != null) == (containsTarget || existsLowFallback), "Null selection iff no legal range");
                if (selected == null) continue;
                boolean advertised = false;
                for (int[] candidate : options) advertised |= candidate[0] == selected[0] && candidate[1] == selected[1];
                check(advertised && selected[0] > 0 && selected[0] <= selected[1], "Selection is a real valid pair");
                check(containsTarget ? selected[0] <= target && selected[1] >= target : selected[1] <= target, "Target or safe baseline fallback semantics");
            }
        });
    }
    private static void durations() {
        test("Stream timing accepts ceiling-rounded 60 and 30 FPS boundaries", () -> {
            check(LumaRoundVideoQuality.supportsFrameDuration(16_666_666L, 60), "Rounded-down 60 period");
            check(LumaRoundVideoQuality.supportsFrameDuration(16_666_667L, 60), "Rounded-up 60 period");
            check(!LumaRoundVideoQuality.supportsFrameDuration(16_666_668L, 60), "Too-slow 60 stream");
            check(LumaRoundVideoQuality.supportsFrameDuration(33_333_334L, 30), "Rounded-up 30 period");
            check(!LumaRoundVideoQuality.supportsFrameDuration(33_333_335L, 30), "Too-slow 30 stream");
        });
        test("Unknown timing, malformed FPS, and huge duration do not prove 60", () -> {
            check(!LumaRoundVideoQuality.supportsFrameDuration(0, 60), "Zero is unknown, not unlimited");
            check(!LumaRoundVideoQuality.supportsFrameDuration(-1, 60), "Negative timing");
            check(!LumaRoundVideoQuality.supportsFrameDuration(16_666_667L, 0), "Zero FPS");
            check(!LumaRoundVideoQuality.supportsFrameDuration(16_666_667L, -60), "Negative FPS");
            check(!LumaRoundVideoQuality.supportsFrameDuration(Long.MAX_VALUE, 60), "Huge duration remains unsupported");
        });
        test("Slowest configured stream and weakest flip camera must constrain target 60", () -> {
            boolean surface60 = LumaRoundVideoQuality.supportsFrameDuration(16_666_667L, 60);
            boolean jpeg60 = LumaRoundVideoQuality.supportsFrameDuration(33_333_333L, 60);
            check(surface60 && !jpeg60 && !(surface60 && jpeg60), "JPEG-enabled session cannot ignore slower output");
            boolean front60 = LumaRoundVideoQuality.supportsTargetFrameRate(ranges(30, 30, 30, 60), 60, 1);
            boolean back60 = LumaRoundVideoQuality.supportsTargetFrameRate(ranges(30, 30), 60, 1);
            profile(LumaRoundVideoQuality.forCamera(base(), true, front60 && back60), 640, 30, 6_000_000, 128_000, true, "Weakest flip lens fixes recording profile");
        });
    }
    private static void cadence() {
        test("60-frame real cadence survives without being capped at legacy 30", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(60);
            int accepted = 0;
            for (int i = 0; i < 600; i++) if (gate.accept(1_000_000_000L + i * 1_000_000_000L / 60, 0)) accepted++;
            check(accepted == 600, "All supplied 60fps frames pass for ten seconds");
        });
        test("30-frame profile limits a supplied 60-frame stream to 30", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(30);
            int accepted = 0;
            for (int i = 0; i < 600; i++) if (gate.accept(1_000_000_000L + i * 1_000_000_000L / 60, 0)) accepted++;
            check(accepted == 300, "Exactly alternate frames at real 60fps");
        });
        test("60-frame profile cannot fabricate frames from a slower camera", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(60);
            int accepted = 0;
            for (int i = 0; i < 300; i++) if (gate.accept(1_000_000_000L + i * 1_000_000_000L / 30, 0)) accepted++;
            check(accepted == 300, "One accepted frame per actual callback, not invented 600");
        });
        test("60-frame profile rejects repeats but retains every unique real sensor frame", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(60);
            check(gate.accept(1_000_000_000L, 0), "First actual frame");
            check(!gate.accept(1_000_000_000L, 0), "Duplicate camera timestamp");
            check(gate.accept(1_001_000_000L, 0), "Unique sensor frame is not pruned by an elapsed-time threshold");
            check(gate.accept(1_015_000_000L, 0), "Early real frame is preserved");
            check(gate.accept(1_016_666_667L, 0), "Next camera frame retains original timestamp");
        });
        test("Real cadence jitter tolerance does not stall every other frame", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(60);
            int accepted = 0;
            for (int i = 0; i < 120; i++) {
                long timestamp = i * 1_000_000_000L / 60 + (i % 2 == 0 ? 100_000L : -100_000L);
                if (gate.accept(timestamp, 0)) accepted++;
            }
            check(accepted == 120, "Small camera jitter is accepted within explicit tolerance");
        });
        test("Backward source clock resets cadence gate rather than blocking new stream", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(60);
            check(gate.accept(2_000_000_000L, 0), "Initial clock");
            check(gate.accept(1_000_000_000L, 0), "Driver clock reset accepted for timestamp repair downstream");
            check(!gate.accept(1_000_000_000L, 0), "Duplicate after reset rejected");
            check(gate.accept(1_016_666_667L, 0), "Cadence proceeds after reset");
        });
        test("Camera switch resets gate even at same or lower raw timestamp", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(60);
            check(gate.accept(10_000_000L, 0), "Original source");
            check(gate.accept(10_000_000L, 1), "Same-clock camera switch");
            check(!gate.accept(10_000_000L, 1), "Duplicate within new camera");
            check(!gate.accept(0, 0), "Unknown zero clock waits for timestamp repair upstream");
            check(gate.accept(1, 0), "New source positive clock can restart near zero");
            check(!gate.accept(1, 0), "Repeated near-zero is not fabricated frame");
        });
        test("Camera identifiers use value equality and support null safely", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(60);
            check(gate.accept(1, Integer.valueOf(300)), "First noncached camera ID");
            check(!gate.accept(1, Integer.valueOf(300)), "Equal ID value does not reset gate");
            check(gate.accept(1, null), "Camera ID null transition");
            check(!gate.accept(1, null), "Repeated null source timestamp");
        });
        test("Invalid zero or negative camera timestamps do not initialize cadence gate", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(60);
            check(!gate.accept(0, 0), "Zero camera timestamp");
            check(!gate.accept(-1, 0), "Negative camera timestamp");
            check(!gate.accept(Long.MIN_VALUE, 0), "Sentinel-like malformed timestamp");
            check(gate.accept(1_000_000_000L, 0), "First positive timestamp remains unblocked");
        });
        test("Pause gap and new recorder do not synthesize timeline frames", () -> {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(60);
            check(gate.accept(1_000_000_000L, 0), "Before pause");
            check(gate.accept(30_000_000_000L, 0), "One callback after long pause gives only one acceptance");
            check(!gate.accept(30_000_000_000L, 0), "No duplication after gap");
            gate = new LumaRoundVideoQuality.FrameGate(60);
            check(gate.accept(30_000_000_000L, 0), "Fresh resume gate accepts first supplied timestamp");
            check(!gate.accept(30_000_000_000L, 0), "Fresh gate also rejects repeats");
        });
        test("Invalid nonpositive gate rates have bounded safe cadence", () -> {
            for (int fps : new int[] {0, -60}) {
                LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(fps);
                check(gate.accept(1_000_000_000L, 0), "First frame at invalid rate");
                check(!gate.accept(1_500_000_000L, 0), "Invalid rate clamps to one fps");
                check(gate.accept(2_000_000_000L, 0), "Bounded cadence remains usable");
            }
        });
    }
    public static void main(String[] args) {
        profiles(); selection(); durations(); cadence();
        System.out.println("Round FPS .77: " + cases + " cases, " + assertions + " assertions, " + failures + " failures.");
        if (failures != 0) throw new AssertionError("Round FPS regressions failed: " + failures);
    }
}
