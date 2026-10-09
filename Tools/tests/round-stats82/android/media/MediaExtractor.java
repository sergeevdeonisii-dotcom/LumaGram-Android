package android.media;

import java.util.HashMap;
import java.io.IOException;

/** Controlled stand-in only: these tests do not pretend to parse a real MP4. */
public final class MediaExtractor {
    public static final HashMap<String, Source> sources = new HashMap<>();
    public static int instances, released;
    public static Runnable firstSampleHook;
    private Source source;
    private int selected = -1, position;

    public static final class Source {
        public final MediaFormat[] formats;
        public final long[] pts, sizes;
        public boolean failSamples, failRelease;
        public Source(MediaFormat[] formats, long[] pts, long[] sizes) {
            this.formats = formats; this.pts = pts; this.sizes = sizes;
        }
    }
    public MediaExtractor() { instances++; }
    public void setDataSource(String path) throws IOException {
        source = sources.get(path);
        if (source == null) throw new IOException("No fake source.");
    }
    public int getTrackCount() { return source.formats.length; }
    public MediaFormat getTrackFormat(int track) { return source.formats[track]; }
    public void selectTrack(int track) { selected = track; }
    public long getSampleTime() {
        if (firstSampleHook != null) {
            Runnable hook = firstSampleHook; firstSampleHook = null; hook.run();
        }
        if (source.failSamples) throw new IllegalStateException("Fake extraction failure.");
        if (selected < 0 || !source.formats[selected].getString(MediaFormat.KEY_MIME).startsWith("video/")) {
            throw new IllegalStateException("Wrong selected track.");
        }
        return position < source.pts.length ? source.pts[position] : -1;
    }
    public long getSampleSize() { return source.sizes[position]; }
    public boolean advance() { return ++position < source.pts.length; }
    public void release() {
        released++;
        if (source != null && source.failRelease) throw new IllegalStateException("Fake release failure.");
    }
}
