package org.telegram.messenger;
import java.io.File;
import java.util.Arrays;

public final class RoundLimits100Test {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static File size(long length) { return new File("not-a-real-recording") { @Override public long length() { return length; } }; }
    private static VideoEditedInfo round(int fps, long durationMs) {
        VideoEditedInfo info = new VideoEditedInfo();
        info.roundVideo = true;
        info.originalPath = "source_with_underscores.mp4";
        info.originalWidth = info.originalHeight = info.resultWidth = info.resultHeight = 640;
        info.estimatedDuration = durationMs;
        info.originalDuration = durationMs * 1000L;
        info.framerate = info.roundVideoRequestedFps = fps;
        info.bitrate = info.originalBitrate = 8_000_000;
        info.file = new Object(); info.encryptedFile = new Object(); info.key = new byte[] {1}; info.iv = new byte[] {2};
        return info;
    }
    public static void main(String[] args) {
        long limit = LumaRoundVideoLimits.DEFAULT_MAX_FILE_BYTES;
        check(limit == 12L * 1024 * 1024, "Telegram default is twelve MiB, not twelve MB");
        check(LumaRoundVideoLimits.normalizeLimit(-1) == limit && LumaRoundVideoLimits.normalizeLimit(Long.MAX_VALUE) == limit, "Invalid config uses bounded default");
        for (long duration = 800; duration <= 60_000; duration += 100) {
            int bitrate = LumaRoundVideoLimits.targetVideoBitrate(limit, duration);
            check(bitrate > 0, "Valid duration has a video budget");
            check((bitrate + 192_000L) * duration / 8_000L + 256L * 1024 < limit, "Audio and MP4 overhead reserved");
            for (int fps : new int[] {30,60}) {
                VideoEditedInfo info = round(fps, duration);
                check(!info.needConvert(), "Original unedited round bypasses conversion");
                check(LumaRoundVideoLimits.prepareForSend(info, size(limit + 1), limit), "Oversize source gets bounded export");
                check(info.needConvert(), "Persisted size flag forces round conversion");
                check(info.startTime == -1 && info.endTime == -1 && info.originalDuration == duration * 1000L, "No silent content truncation");
                check(info.framerate == fps && info.resultWidth == 640, "Keep FPS and compatible resolution");
                check(info.file == null && info.encryptedFile == null && info.key == null && info.iv == null, "Clear both stale upload handles and encryption material");
                check(info.estimatedSize <= limit && info.bitrate > 0, "Queue gets bounded estimate and positive bitrate");
                VideoEditedInfo retry = new VideoEditedInfo();
                check(retry.parseString(info.getString()), "Actual serialization parses for retry");
                retry.roundVideo = true; // MessageObject restores round type from the document attribute.
                check(retry.needConvert() && retry.roundVideoFileSizeLimit == limit, "Size restriction survives process restart");
                check(retry.bitrate == info.bitrate && retry.originalBitrate == info.originalBitrate, "Bitrate budget survives retry");
                check(retry.roundVideoRequestedFps == fps && retry.framerate == fps, "Requested and encoded FPS survive retry");
                check(retry.originalPath.equals(info.originalPath) && retry.originalDuration == info.originalDuration, "Input identity and full duration survive retry");
            }
        }
        VideoEditedInfo small = round(60, 8_000);
        check(!LumaRoundVideoLimits.prepareForSend(small, size(limit), limit) && small.file != null, "Small unedited file retains direct upload");
        VideoEditedInfo trimmed = round(60, 8_000); trimmed.startTime = 2_000_000; trimmed.endTime = 10_000_000; trimmed.originalDuration = 20_000_000;
        check(LumaRoundVideoLimits.prepareForSend(trimmed, size(100), limit), "Even edited export must pass final byte limit");
        check(trimmed.startTime == 2_000_000 && trimmed.endTime == 10_000_000 && trimmed.originalDuration == 20_000_000, "Explicit trim preserved exactly");
        VideoEditedInfo growing = round(60, 8_000); growing.notReadyYet = true;
        check(!LumaRoundVideoLimits.prepareForSend(growing, size(limit + 1), limit), "Growing-file decision deliberately forbidden");
        VideoEditedInfo ordinary = round(60, 8_000); ordinary.roundVideo = false;
        check(!LumaRoundVideoLimits.prepareForSend(ordinary, size(limit + 1), limit), "Ordinary videos unaffected");
        check(LumaRoundVideoLimits.targetVideoBitrate(limit, 0) == 0 && LumaRoundVideoLimits.targetVideoBitrate(limit, Long.MAX_VALUE) == 0, "Unknown/invalid duration cannot invent safe bitrate");
        check(LumaRoundVideoLimits.targetVideoBitrate(1, 60_000) == 0, "Impossible server budget fails closed");
        check(!LumaRoundVideoLimits.acceptsOutput(limit + 1, limit) && !LumaRoundVideoLimits.acceptsOutput(0, limit), "Oversize and empty converter output blocked");
        check(LumaRoundVideoLimits.acceptsOutput(limit, limit), "Exact byte boundary accepted");
        check(LumaRoundVideoLimits.acceptsVideo(640, 640, 60_000, 60_000), "Full minute remains permitted");
        check(!LumaRoundVideoLimits.acceptsVideo(640, 640, 7_000, 8_000), "Truncated output rejected");
        check(!LumaRoundVideoLimits.acceptsVideo(720, 720, 8_000, 8_000), "Oversized pixel output rejected");
        check(!LumaRoundVideoLimits.acceptsVideo(640, 480, 8_000, 8_000), "Non-square output rejected");
        VideoEditedInfo encoded = round(60, 8_000); LumaRoundVideoLimits.prepareForSend(encoded, size(limit + 1), limit);
        String[] fields = encoded.getString().split("_", 14);
        byte[] bytes = Utilities.hexToBytes(fields[12].substring(1));
        bytes[0] = 11;
        fields[12] = "-" + Utilities.bytesToHex(Arrays.copyOf(bytes, bytes.length - 12));
        VideoEditedInfo old = new VideoEditedInfo(); old.roundVideoFileSizeLimit = limit;
        check(old.parseString(String.join("_", fields)) && old.roundVideoFileSizeLimit == 0, "Version11 saved drafts remain readable and reset new state");
        MediaController export = new MediaController();
        export.constrainedRound = true;
        MediaController.Callback callback = export.new Callback();
        for (int i = 1; i <= 500; i++) callback.didWriteData(i * 1000L, i / 500f);
        check(export.writes == 0, "Actual converter callback never exposes unchecked round chunks");
        export.info.canceled = true;
        callback.didWriteData(limit, 1f);
        check(export.writes == 0, "Canceled export cannot expose a chunk");
        export.constrainedRound = false;
        callback.didWriteData(limit, 1f);
        check(export.writes == 0, "Normal canceled export also keeps existing behavior");
        export.info.canceled = false;
        callback.didWriteData(100, 0.5f);
        check(export.writes == 1, "Ordinary video keeps streaming-upload behavior");
        callback.didWriteData(100, 0.6f);
        check(export.writes == 1, "Ordinary repeated chunk remains deduplicated");
        System.out.println("PASS: " + checks + " actual round size-policy and getString/parseString persistence assertions.");
    }
}
