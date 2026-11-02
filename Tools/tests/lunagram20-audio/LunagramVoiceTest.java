import org.telegram.messenger.LunagramVoiceProcessor;
import org.telegram.messenger.LunagramVoiceSource;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

public final class LunagramVoiceTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static byte[] tone(int channels, double frequency) {
        ByteBuffer data = ByteBuffer.allocate(48000 * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < 48000; frame++) {
            data.putShort((short) (12000 * Math.sin(2 * Math.PI * frequency * frame / 48000)));
            if (channels == 2) data.putShort((short) 0);
        }
        return data.array();
    }
    static byte[] effect(int id, int channels, byte[] original, int block) {
        LunagramVoiceProcessor processor = new LunagramVoiceProcessor(id, 48000, channels);
        byte[] result = original.clone();
        for (int at = 0; at < result.length; at += block) {
            int length = Math.min(block, result.length - at);
            ByteBuffer buffer = ByteBuffer.wrap(Arrays.copyOfRange(result, at, at + length)).order(ByteOrder.LITTLE_ENDIAN);
            processor.process(buffer, length);
            System.arraycopy(buffer.array(), 0, result, at, length);
        }
        return result;
    }
    static double rms(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); double sum = 0;
        while (buffer.remaining() > 0) { double value = buffer.getShort(); sum += value * value; }
        return Math.sqrt(sum / (bytes.length / 2));
    }
    static double frequency(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int crossings = 0; short previous = 0;
        for (int frame = 12000; frame < 48000; frame++) {
            short value = buffer.getShort(frame * 2);
            if (previous < 0 && value >= 0) crossings++;
            previous = value;
        }
        return crossings / 0.75;
    }
    static byte[] read(File opus) throws Exception {
        try (LunagramVoiceSource source = LunagramVoiceSource.open(opus)) {
            byte[] data = new byte[(int) source.pcmBytes()]; source.rewind();
            check(source.read(data) == data.length, "Read original PCM"); return data;
        }
    }
    public static void main(String[] args) throws Exception {
        byte[] mono = tone(1, 440), stereo = tone(2, 440);
        check(Arrays.equals(mono, effect(0, 1, mono, 1920)), "Original is bit exact");
        for (int id = 1; id <= 5; id++) {
            byte[] changed = effect(id, 2, stereo, 3840);
            check(!Arrays.equals(stereo, changed), "Effect changes actual samples: " + id);
            check(Arrays.equals(changed, effect(id, 2, stereo, 640)), "Chunk boundaries preserve DSP state: " + id);
            check(rms(changed) > 100, "Effect remains audible: " + id);
            ByteBuffer buffer = ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN);
            for (int at = 2; at < changed.length; at += 4) check(buffer.getShort(at) == 0, "Stereo channel leakage: " + id);
        }
        double bass = frequency(effect(3, 1, mono, 1920)), cartoon = frequency(effect(4, 1, mono, 1920));
        check(bass < 380 && bass > 260, "Bass lowers pitch, measured " + bass);
        check(cartoon > 540 && cartoon < 720, "Cartoon raises pitch, measured " + cartoon);
        check(rms(effect(5, 1, tone(1, 40), 1920)) < rms(effect(5, 1, mono, 1920)) * 0.4, "Telephone rejects sub-bass");
        check(rms(effect(5, 1, tone(1, 18000), 1920)) < rms(effect(5, 1, mono, 1920)) * 0.4, "Telephone rejects high frequencies");
        File directory = Files.createTempDirectory("lunagram-voice-test-").toFile();
        File first = new File(directory, "first.ogg"), second = new File(directory, "second.ogg"), joined = new File(directory, "joined.ogg"), cropped = new File(directory, "crop.ogg");
        try {
            try (LunagramVoiceSource source = LunagramVoiceSource.create(first, 48000, 2)) { source.append(ByteBuffer.wrap(stereo), stereo.length); source.setEffect(1); }
            try (LunagramVoiceSource source = LunagramVoiceSource.create(second, 48000, 2)) { source.append(ByteBuffer.wrap(stereo), stereo.length); }
            check(Arrays.equals(stereo, read(first)), "Effect metadata does not alter original");
            try (LunagramVoiceSource source = LunagramVoiceSource.open(first)) { source.setEffect(4); }
            check(Arrays.equals(stereo, read(first)), "Switching effect retains original PCM");
            LunagramVoiceSource.join(first, second, joined);
            check(read(joined).length == stereo.length * 2, "Pause/resume joins all original stereo frames");
            try (LunagramVoiceSource source = LunagramVoiceSource.open(joined)) { check(source.channels == 2 && source.effect == 4, "Joined format/effect retained"); }
            LunagramVoiceSource.crop(joined, cropped, 250, 750);
            check(Arrays.equals(Arrays.copyOfRange(stereo, 48000, 144000), read(cropped)), "Crop uses frame count, preserving both channels");
            File legacy = new File(directory, "legacy.ogg");
            try { LunagramVoiceSource.open(legacy); throw new AssertionError("Missing original accepted"); } catch (java.io.IOException expected) {}
            check(!LunagramVoiceSource.path(legacy).exists(), "Reading legacy draft creates no empty companion");
        } finally {
            for (File file : directory.listFiles()) file.delete(); directory.delete();
        }
        System.out.println("Lunagram voice DSP/source checks passed; bass=" + bass + "Hz cartoon=" + cartoon + "Hz");
    }
}
