package org.telegram.messenger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real portable export session helper + source wiring checks, not ChatExport server/UI tests. */
public final class LumaExportSessionTest {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        check(root.mkdirs() || root.isDirectory(), "isolated helper test root");
        oneShotAndIdentity();
        lateCallbacksAndShutdown();
        independentSessions(root);
        chatIntegration(new File(args[1]));
        System.out.println("PASS: real export session helper (concurrent one-shot start, owner reuse, terminal/shutdown enqueue rejection, unique session ownership) + ChatExport snapshot/queue/cleanup source wiring. Not Android/server runtime tests.");
    }

    private static void oneShotAndIdentity() throws Exception {
        UserConfig.ids[0] = 94001;
        LumaExportSession session = new LumaExportSession(0);
        AtomicBoolean cancelled = new AtomicBoolean(), terminal = new AtomicBoolean();
        ExecutorService threads = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        ArrayList<Future<Boolean>> starts = new ArrayList<>();
        for (int n = 0; n < 32; n++) starts.add(threads.submit(() -> { go.await(); return session.startOnce(cancelled, terminal); }));
        go.countDown();
        int count = 0;
        for (Future<Boolean> start : starts) if (start.get(5, TimeUnit.SECONDS)) count++;
        threads.shutdown();
        check(threads.awaitTermination(5, TimeUnit.SECONDS), "start test threads stopped");
        check(count == 1 && session.isCurrent(), "exactly one concurrent caller may start an export");
        UserConfig.ids[0] = 94002;
        check(session.hasOwner() && !session.isCurrent(), "reused login slot is not the export's confirmed owner");
        UserConfig.ids[0] = 0;
        check(!session.isCurrent(), "logout invalidates active export owner");
        check(!new LumaExportSession(0).hasOwner(), "logged-out session has no owner");
        UserConfig.ids[0] = 94003;
        LumaExportSession neverStarted = new LumaExportSession(0);
        check(!neverStarted.startOnce(new AtomicBoolean(true), new AtomicBoolean()), "cancel-before-start cannot start");
        check(!neverStarted.startOnce(new AtomicBoolean(), new AtomicBoolean(true)), "completed/failed export cannot start");
    }

    private static void lateCallbacksAndShutdown() throws Exception {
        UserConfig.ids[0] = 95001;
        LumaExportSession session = new LumaExportSession(0);
        AtomicBoolean cancelled = new AtomicBoolean(), terminal = new AtomicBoolean();
        AtomicInteger tasks = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        check(session.executeIfActive(worker, cancelled, terminal, tasks::incrementAndGet), "live callback enqueued");
        synchronized (worker) { terminal.set(true); worker.shutdown(); }
        for (int n = 0; n < 100; n++) check(!session.executeIfActive(worker, cancelled, terminal, tasks::incrementAndGet),
                "late terminal callback ignored without RejectedExecutionException");
        check(worker.awaitTermination(5, TimeUnit.SECONDS) && tasks.get() == 1, "only the pre-terminal task ran");

        ExecutorService alreadyClosed = Executors.newSingleThreadExecutor();
        alreadyClosed.shutdown();
        check(!session.executeIfActive(alreadyClosed, new AtomicBoolean(), new AtomicBoolean(), tasks::incrementAndGet),
                "shutdown executor rejects safely even if flags have not propagated");
        ExecutorService active = Executors.newSingleThreadExecutor();
        check(!session.executeIfActive(active, new AtomicBoolean(true), new AtomicBoolean(), tasks::incrementAndGet), "cancelled callback ignored");
        UserConfig.ids[0] = 95002;
        check(!session.executeIfActive(active, new AtomicBoolean(), new AtomicBoolean(), tasks::incrementAndGet), "old owner callback never reaches new account's worker tasks");
        active.shutdown();
        check(active.awaitTermination(5, TimeUnit.SECONDS), "unused active worker stopped");
    }

    private static void independentSessions(File root) throws Exception {
        File a = LumaExportSession.createDirectory(root), b = LumaExportSession.createDirectory(root);
        check(a.isDirectory() && b.isDirectory() && !a.equals(b), "simultaneous sessions never share a timestamp directory");
        File ownedA = LumaExportSession.reserveFile(a, "part.jsonl"), ownedB = LumaExportSession.reserveFile(b, "part.jsonl");
        Files.writeString(ownedA.toPath(), "first", StandardCharsets.UTF_8);
        Files.writeString(ownedB.toPath(), "second", StandardCharsets.UTF_8);
        check(ownedA.delete() && a.delete(), "first isolated session cleanup");
        check(ownedB.isFile() && "second".equals(Files.readString(ownedB.toPath())), "cleanup does not remove another session's identically named file");
        Set<File> names = new HashSet<>();
        for (int n = 0; n < 32; n++) check(names.add(LumaExportSession.createDirectory(root)), "unique private session root");
    }

    private static void chatIntegration(File repository) throws Exception {
        String source = Files.readString(new File(repository,
                "TMessagesProj/src/main/java/org/telegram/messenger/LumaChatExportManager.java").toPath()).replace("\r\n", "\n");
        String options = source.substring(source.indexOf("public static final class Options"), source.indexOf("public static final class Progress"));
        Matcher fields = Pattern.compile("public (?:int|long|String|boolean) ([a-zA-Z]+)(?:;| =)").matcher(options);
        check(source.contains("this.options = new Options(options.account, options.dialogId, options.title)"), "constructor creates detached identity/dialog/title snapshot");
        int count = 0;
        while (fields.find()) {
            String field = fields.group(1); count++;
            if (field.equals("account") || field.equals("dialogId") || field.equals("title")) continue;
            check(source.contains("this.options." + field + " = options." + field + ";"), "every mutable export option copied: " + field);
        }
        check(count == 17, "all current Options fields covered by snapshot check");
        check(source.contains("session.startOnce(cancelled, terminalCallbackSent)"), "production start uses one-shot helper");
        check(source.contains("executeIfActive(() -> processPage(page))")
                && source.contains("session.executeIfActive(worker, cancelled, terminalCallbackSent, task)"), "network callback uses guarded executor enqueue");
        check(source.contains("sessionDir = LumaExportSession.createDirectory(sessionRoot)")
                && source.contains("return LumaExportSession.reserveFile(directory, name)"), "production uses private unique sessions and atomically reserved outputs");
        check(source.contains("if (stoppedOrOwnerChanged() || !terminalCallbackSent.compareAndSet(false, true)) {\n                archive.delete();"), "terminal completion loser deletes its owned completed archive");
        check(source.contains("if (!session.isCurrent()) {\n                archive.delete();\n                listener.onCancelled();"), "queued completion rechecks the owner before exposing an archive");
        check(source.contains("while (!signaled && !stoppedOrOwnerChanged())"), "media download wait checks logout/replacement instead of holding stale export for ten minutes");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
