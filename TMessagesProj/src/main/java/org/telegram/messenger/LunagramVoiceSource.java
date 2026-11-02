package org.telegram.messenger;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Original, app-private PCM companion for a voice draft. Never uploaded. */
public final class LunagramVoiceSource implements Closeable {
    private static final int MAGIC = 0x4c565031, HEADER = 16;
    private static volatile File storageDirectory;
    private final RandomAccessFile file;
    public final int sampleRate, channels;
    public int effect;

    public static void setStorageDirectory(File directory) { storageDirectory = directory; }
    public static File path(File opus) {
        File directory = storageDirectory;
        if (directory == null) return new File(opus.getPath() + ".luna-pcm");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(opus.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(64);
            for (byte value : digest) { name.append(Character.forDigit((value >>> 4) & 15, 16)); name.append(Character.forDigit(value & 15, 16)); }
            return new File(directory, name + ".pcm");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static void delete(String opus) { if (opus != null) path(new File(opus)).delete(); }

    public static LunagramVoiceSource create(File opus, int sampleRate, int channels) throws IOException {
        if (sampleRate != 48000 || channels < 1 || channels > 2) throw new IOException("Unsupported original PCM format");
        File parent = path(opus).getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create original audio storage");
        RandomAccessFile file = new RandomAccessFile(path(opus), "rw");
        file.setLength(0); file.writeInt(MAGIC); file.writeInt(sampleRate); file.writeInt(channels); file.writeInt(0);
        return new LunagramVoiceSource(file, sampleRate, channels, 0);
    }

    public static LunagramVoiceSource open(File opus) throws IOException {
        if (!path(opus).isFile()) throw new IOException("Missing original PCM");
        RandomAccessFile file = new RandomAccessFile(path(opus), "rw");
        try {
            if (file.length() < HEADER || file.readInt() != MAGIC) throw new IOException("Missing original PCM");
            int rate = file.readInt(), channels = file.readInt(), effect = file.readInt();
            if (rate != 48000 || channels < 1 || channels > 2 || effect < 0 || effect > 5 ||
                (file.length() - HEADER) % (channels * 2) != 0) throw new IOException("Invalid original PCM");
            return new LunagramVoiceSource(file, rate, channels, effect);
        } catch (IOException error) { file.close(); throw error; }
    }

    private LunagramVoiceSource(RandomAccessFile file, int rate, int channels, int effect) {
        this.file = file; sampleRate = rate; this.channels = channels; this.effect = effect;
    }

    public void append(ByteBuffer pcm, int length) throws IOException {
        if (length % (2 * channels) != 0) throw new IOException("Unaligned PCM");
        byte[] bytes = new byte[length];
        ByteBuffer copy = pcm.duplicate(); copy.position(0); copy.limit(length); copy.get(bytes);
        file.seek(file.length()); file.write(bytes);
    }
    public long pcmBytes() throws IOException { return file.length() - HEADER; }
    public void rewind() throws IOException { file.seek(HEADER); }
    public int read(byte[] bytes) throws IOException {
        int count = (int) Math.min(bytes.length, file.length() - file.getFilePointer());
        if (count <= 0) return -1;
        file.readFully(bytes, 0, count);
        return count;
    }
    public void setEffect(int effect) throws IOException {
        long position = file.getFilePointer(); file.seek(12); file.writeInt(effect); file.seek(position); this.effect = effect;
    }
    @Override public void close() throws IOException { file.close(); }

    public static void join(File first, File second, File output) throws IOException {
        try (LunagramVoiceSource a = open(first); LunagramVoiceSource b = open(second);
             LunagramVoiceSource destination = create(output, a.sampleRate, a.channels)) {
            if (a.channels != b.channels || a.sampleRate != b.sampleRate) throw new IOException("PCM format changed");
            byte[] bytes = new byte[1920 * a.channels];
            for (LunagramVoiceSource source : new LunagramVoiceSource[]{a, b}) {
                source.rewind(); int read;
                while ((read = source.read(bytes)) > 0) destination.append(ByteBuffer.wrap(bytes), read);
            }
            destination.setEffect(a.effect);
        }
    }

    public static void crop(File input, File output, long startMs, long endMs) throws IOException {
        try (LunagramVoiceSource source = open(input); LunagramVoiceSource destination = create(output, source.sampleRate, source.channels)) {
            long frameBytes = source.channels * 2L;
            long start = Math.max(0, startMs * source.sampleRate / 1000) * frameBytes;
            long end = Math.min(source.pcmBytes(), endMs * source.sampleRate / 1000 * frameBytes);
            source.file.seek(HEADER + start);
            byte[] bytes = new byte[1920 * source.channels];
            while (start < end) {
                int read = (int) Math.min(bytes.length, end - start);
                source.file.readFully(bytes, 0, read);
                destination.append(ByteBuffer.wrap(bytes), read); start += read;
            }
            destination.setEffect(source.effect);
        }
    }
}
