package org.telegram.messenger;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Android-independent limits and decisions; none of these values grants access. */
public final class LunagramMediaToolsPolicy {
    private LunagramMediaToolsPolicy() {}
    public static final long MAX_HASH_BYTES = 128L * 1024 * 1024;
    public static final long MAX_CLIPBOARD_BYTES = 20L * 1024 * 1024;
    public static final int MAX_BOOKMARKS = 64;
    public static final long MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1000;

    public static boolean ownedFile(File file, File[] roots) throws IOException {
        if (file == null || roots == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".enc") || name.endsWith(".key") || name.endsWith(".temp")
                || name.endsWith(".part") || name.startsWith(".")) return false;
        String target = file.getCanonicalPath();
        for (File root : roots) {
            if (root != null && target.startsWith(root.getCanonicalPath() + File.separator)) return true;
        }
        return false;
    }

    public static String time(long millis) {
        long total = Math.max(0, millis / 1000);
        return total >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
                : String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    public static String sha256(File file, Runnable check) throws Exception {
        check.run();
        long size = file.length(), modified = file.lastModified();
        if (!file.isFile() || size <= 0 || size > MAX_HASH_BYTES) throw new IllegalArgumentException("Hash limit");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        long read = 0; byte[] buffer = new byte[32768];
        try (FileInputStream input = new FileInputStream(file)) {
            int n; while ((n = input.read(buffer)) >= 0) {
                check.run(); read += n;
                if (read > MAX_HASH_BYTES) throw new IllegalArgumentException("Hash limit");
                hash.update(buffer, 0, n);
            }
        }
        check.run();
        if (read != size || file.length() != size || file.lastModified() != modified) throw new IllegalStateException("File changed");
        StringBuilder text = new StringBuilder(64);
        for (byte b : hash.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    /** mm:ss or h:mm:ss. Strict input, no locale float parsing or overflow. */
    public static long parseTime(String value) {
        if (value == null || value.length() > 16 || !value.matches("[0-9]{1,4}:[0-5][0-9](:[0-5][0-9])?")) return -1;
        String[] parts = value.split(":");
        long seconds = 0;
        for (String part : parts) seconds = seconds * 60 + Long.parseLong(part);
        long ms = seconds * 1000;
        return ms <= MAX_DURATION_MS ? ms : -1;
    }

    public static final class Bookmark {
        public final int account, message;
        public final long owner, dialog, document, position;
        public Bookmark(int account, long owner, long dialog, int message, long document, long position) {
            if (account < 0 || owner <= 0 || dialog == 0 || message <= 0 || document <= 0
                    || position < 0 || position > MAX_DURATION_MS) throw new IllegalArgumentException("Invalid bookmark");
            this.account = account; this.owner = owner; this.dialog = dialog; this.message = message;
            this.document = document; this.position = position;
        }
        public boolean sameMedia(Bookmark other) {
            return other != null && account == other.account && owner == other.owner && dialog == other.dialog
                    && message == other.message && document == other.document;
        }
        public String encode() { return "1;" + account + ";" + owner + ";" + dialog + ";" + message + ";" + document + ";" + position; }
        public static Bookmark decode(String text) {
            if (text == null || text.length() > 180) return null;
            try {
                String[] p = text.split(";", -1);
                if (p.length != 7 || !"1".equals(p[0])) return null;
                return new Bookmark(Integer.parseInt(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]),
                        Integer.parseInt(p[4]), Long.parseLong(p[5]), Long.parseLong(p[6]));
            } catch (RuntimeException invalid) { return null; }
        }
    }

    /** One active player's transient controls. A return of -2 means pause; >=0 means seek. */
    public static final class Playback {
        public static final long NONE = -1, PAUSE = -2;
        private long from = -1, to = -1, deadline;
        private boolean stopAtEnd;
        private long lastSeek = -1000;
        public void setLoop(long start, long end, long duration) {
            if (duration <= 0 || duration > MAX_DURATION_MS || start < 0 || end > duration || end - start < 1000)
                throw new IllegalArgumentException("Invalid loop");
            from = start; to = end; lastSeek = -1000;
            if (stopAtEnd) clearTimer();
        }
        public void clearLoop() { from = to = -1; }
        public boolean hasLoop() { return from >= 0 && to > from; }
        public long loopStart() { return from; }
        public long loopEnd() { return to; }
        public void setTimer(int minutes, boolean atEnd, long now) {
            if (now < 0 || (!atEnd && (minutes < 1 || minutes > 120))) throw new IllegalArgumentException("Invalid timer");
            deadline = atEnd ? 0 : now + minutes * 60000L;
            stopAtEnd = atEnd;
            if (atEnd) clearLoop(); // End-of-track must remain reachable.
        }
        public void clearTimer() { deadline = 0; stopAtEnd = false; }
        public boolean hasTimer() { return deadline > 0 || stopAtEnd; }
        public long deadline() { return deadline; }
        public boolean stopsAtEnd() { return stopAtEnd; }
        public long evaluate(long position, long duration, long now, boolean ended) {
            if ((deadline > 0 && now >= deadline) || (stopAtEnd && ended)) {
                clearTimer(); clearLoop(); return PAUSE;
            }
            if (duration <= 0 || duration > MAX_DURATION_MS || position < 0) return NONE;
            if (hasLoop() && !stopAtEnd && (position >= to || ended) && now - lastSeek >= 250) {
                lastSeek = now; return from;
            }
            return NONE;
        }
    }
}
