import org.telegram.messenger.LunagramMediaToolsPolicy;
import org.telegram.messenger.LunagramMediaToolsPolicy.Bookmark;
import org.telegram.messenger.LunagramMediaToolsPolicy.Playback;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicInteger;

public class MediaToolsPolicyTest {
    static int checks;
    static void ok(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    interface Task { void run() throws Exception; }
    static void rejects(Task task, String name) throws Exception {
        boolean failed = false; try { task.run(); } catch (Exception expected) { failed = true; }
        ok(failed, name);
    }
    public static void main(String[] args) throws Exception {
        ok(LunagramMediaToolsPolicy.parseTime("1:05") == 65000, "minutes");
        ok(LunagramMediaToolsPolicy.parseTime("1:02:03") == 3723000, "hours");
        for (String invalid : new String[]{"", "1", "-1:01", "1:60", "1:2", "1:02:60", "1:02:03:04", "1.5:00", " 1:00", "99999:00", "200:00:00", "0:00\n", "١:٠٠"})
            ok(LunagramMediaToolsPolicy.parseTime(invalid) == -1, "reject invalid time " + invalid);
        for (long n = 0; n <= LunagramMediaToolsPolicy.MAX_DURATION_MS; n += 61337)
            ok(LunagramMediaToolsPolicy.parseTime(LunagramMediaToolsPolicy.time(n)) == n / 1000 * 1000, "time roundtrip");
        Bookmark a = new Bookmark(0, 9, -10, 11, 12, 13000);
        Bookmark b = Bookmark.decode(a.encode());
        ok(b != null && b.sameMedia(a) && b.position == 13000, "bookmark roundtrip");
        ok(!a.sameMedia(new Bookmark(1, 9, -10, 11, 12, 13000)), "account isolation");
        ok(!a.sameMedia(new Bookmark(0, 8, -10, 11, 12, 13000)), "owner isolation");
        ok(!a.sameMedia(new Bookmark(0, 9, -20, 11, 12, 13000)), "dialog isolation");
        ok(!a.sameMedia(new Bookmark(0, 9, -10, 21, 12, 13000)), "message isolation");
        ok(!a.sameMedia(new Bookmark(0, 9, -10, 11, 22, 13000)), "edited-media isolation");
        for (String invalid : new String[]{"", "1;0;9;-10;11;12;-1", "1;-1;9;-10;11;12;0", "1;0;0;-10;11;12;0", "1;0;9;0;11;12;0", "1;0;9;-10;-1;12;0", "2;0;9;-10;11;12;0", "1;0;9;-10;11;0;0", "1;0;9;-10;11;12;604800001", "1;0;9;-10;11;12;0;x"})
            ok(Bookmark.decode(invalid) == null, "reject malformed bookmark");

        Playback p = new Playback();
        ok(p.evaluate(500, 10000, 1000, false) == Playback.NONE, "off by default");
        rejects(() -> p.setLoop(-1, 1000, 10000), "negative loop");
        rejects(() -> p.setLoop(1000, 1500, 10000), "loop minimum1s");
        rejects(() -> p.setLoop(1000, 12000, 10000), "duration bound");
        rejects(() -> p.setLoop(1000, 3000, 0), "unknown duration");
        p.setLoop(1000, 3000, 10000);
        ok(p.evaluate(2999, 10000, 1000, false) == Playback.NONE, "before B");
        ok(p.evaluate(3000, 10000, 1000, false) == 1000, "seek at B");
        ok(p.evaluate(3000, 10000, 1100, false) == Playback.NONE, "seek throttle");
        ok(p.evaluate(3000, 10000, 1300, false) == 1000, "next loop");
        p.clearLoop(); ok(!p.hasLoop(), "clear loop");
        p.setLoop(1000, 3000, 10000); p.setTimer(0, true, 5000);
        ok(!p.hasLoop() && p.stopsAtEnd(), "end timer clears loop");
        ok(p.evaluate(3000, 10000, 7000, false) == Playback.NONE, "timer awaits end");
        ok(p.evaluate(10000, 10000, 15000, true) == Playback.PAUSE, "end timer pauses");
        ok(!p.hasTimer(), "one shot end timer");
        p.setTimer(5, false, 1000);
        ok(p.evaluate(1000, 10000, 300999, false) == Playback.NONE, "deadline not early");
        ok(p.evaluate(2000, 10000, 301000, false) == Playback.PAUSE, "deadline pauses");
        ok(p.evaluate(10000, 10000, 400000, true) == Playback.NONE, "deadline one shot");
        p.setTimer(0, true, 0); p.setLoop(0, 1000, 10000);
        ok(!p.hasTimer() && p.hasLoop(), "explicit loop replaces unreachable end timer");
        for (int invalid : new int[]{-1, 0, 121, Integer.MAX_VALUE}) rejects(() -> p.setTimer(invalid, false, 0), "timer bounded");

        Path root = Path.of(args[0]).toAbsolutePath().resolve("owned"); Files.createDirectories(root);
        Path safe = root.resolve("download.bin"); Files.write(safe, new byte[]{97,98,99});
        File[] roots = {root.toFile()};
        ok(LunagramMediaToolsPolicy.ownedFile(safe.toFile(), roots), "owned file");
        ok(!LunagramMediaToolsPolicy.ownedFile(root.toFile(), roots), "never directory");
        Path outside = root.resolveSibling("original.bin"); Files.write(outside, new byte[]{1});
        ok(!LunagramMediaToolsPolicy.ownedFile(outside.toFile(), roots), "outside original");
        ok(!LunagramMediaToolsPolicy.ownedFile(root.resolve("../original.bin").toFile(), roots), "traversal");
        Path prefix = root.resolveSibling("owned-other"); Files.createDirectories(prefix); Path prefixFile=prefix.resolve("a.bin"); Files.write(prefixFile,new byte[]{1});
        ok(!LunagramMediaToolsPolicy.ownedFile(prefixFile.toFile(), roots), "root prefix confusion");
        for (String suffix : new String[]{".enc", ".ENC", ".key", ".temp", ".part"}) {
            Path secret=root.resolve("file"+suffix); Files.write(secret,new byte[]{1});
            ok(!LunagramMediaToolsPolicy.ownedFile(secret.toFile(), roots), "protected filename");
        }
        ok(LunagramMediaToolsPolicy.sha256(safe.toFile(), () -> {}).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "known SHA256");
        AtomicInteger polls=new AtomicInteger();
        rejects(() -> LunagramMediaToolsPolicy.sha256(safe.toFile(), () -> { if(polls.incrementAndGet()>=2) throw new IllegalStateException(); }), "hash cancellation");
        Path huge=root.resolve("huge.bin");
        try(RandomAccessFile sparse=new RandomAccessFile(huge.toFile(),"rw")){sparse.setLength(LunagramMediaToolsPolicy.MAX_HASH_BYTES+1);}
        rejects(() -> LunagramMediaToolsPolicy.sha256(huge.toFile(), () -> {}), "hash size bound");
        Files.delete(huge); // Only this test's exact sparse fixture.
        System.out.println("PASS media policy: "+checks+" assertions; parsing/ref/owned-root/hash/loop/timer");
    }
}
