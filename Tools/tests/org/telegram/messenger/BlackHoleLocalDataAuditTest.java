package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Production local models against isolated in-memory platform/JSON fixtures.
 * Does not exercise Android Keystore, real accounts, server history or Android UI.
 */
public final class BlackHoleLocalDataAuditTest {
    private static final String JOURNAL = "bhg_notification_journal";

    public static void main(String[] args) throws Exception {
        ApplicationLoader.applicationContext.root = new File(args[0]);
        check(ApplicationLoader.applicationContext.root.mkdirs()
                || ApplicationLoader.applicationContext.root.isDirectory(), "isolated test root");
        exportOwnerAndLifecycle();
        archiveReservation();
        journalErrorLogging();
        journalRecoveryAndOwnerRace();
        concurrentNotes();
        System.out.println("PASS: local-data audit (owner-bound exports, immutable selections, one-shot starts, atomic archive reservation, journal corruption/identity race, concurrent note index).");
    }

    private static void journalErrorLogging() throws Exception {
        UserConfig.ids[0] = 91007;
        BlackHolePrivateData.write(0, JOURNAL, "private-decrypted-payload-sentinel");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream before = System.err;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            check(BlackHoleNotificationJournal.entries(0).isEmpty(), "corrupt journal remains recoverable");
        } finally {
            System.setErr(before);
        }
        String logged = output.toString(StandardCharsets.UTF_8);
        check(logged.contains("Invalid local notification journal data")
                && !logged.contains("private-decrypted-payload-sentinel"),
                "corrupt JSON diagnostics do not copy decrypted private text into plaintext logs");
    }

    private static void exportOwnerAndLifecycle() throws Exception {
        UserConfig.ids[0] = 0;
        LumaChatExportManager.queuedCompletion = null;
        Events loggedOut = new Events();
        LumaAccountExportManager rejected = new LumaAccountExportManager(config(), loggedOut);
        rejected.start();
        awaitWorker(rejected);
        check(loggedOut.errors.get() == 1 && loggedOut.total() == 1, "logged-out export rejected once");
        check(LumaChatExportManager.queuedCompletion == null, "logged-out export never starts history requests");

        UserConfig.ids[0] = 90001;
        Events beforeStart = new Events();
        LumaAccountExportManager cancelled = new LumaAccountExportManager(config(), beforeStart);
        cancelled.cancel();
        cancelled.start();
        awaitWorker(cancelled);
        check(beforeStart.cancelled.get() == 1 && beforeStart.total() == 1, "cancel-before-start stays terminal");
        check(LumaChatExportManager.queuedCompletion == null, "cancel-before-start creates no child");

        LumaAccountExportManager.Config original = config();
        Events immutableEvents = new Events();
        LumaAccountExportManager immutable = new LumaAccountExportManager(original, immutableEvents);
        original.account = 1;
        original.accountName = "replacement";
        original.chats.clear();
        immutable.start();
        LumaChatExportManager.Listener firstChild = LumaChatExportManager.queuedCompletion;
        check(firstChild != null, "confirmed selection survives UI config mutation");
        Field configField = LumaAccountExportManager.class.getDeclaredField("config");
        configField.setAccessible(true);
        LumaAccountExportManager.Config snapshot = (LumaAccountExportManager.Config) configField.get(immutable);
        check(snapshot.account == 0 && "fixture".equals(snapshot.accountName)
                && snapshot.chats.size() == 2, "account, name and dialogs are copied before asynchronous work");
        Field sessionField = LumaAccountExportManager.class.getDeclaredField("sessionDir");
        sessionField.setAccessible(true);
        File session = (File) sessionField.get(immutable);
        immutable.start();
        check(firstChild == LumaChatExportManager.queuedCompletion
                && session.equals(sessionField.get(immutable)), "repeated start creates no second export or orphan session");
        immutable.cancel();
        awaitWorker(immutable);
        immutable.start();
        check(immutableEvents.total() == 1 && !session.exists(), "terminal export cannot restart after cleanup");

        Events recycled = new Events();
        LumaAccountExportManager stale = new LumaAccountExportManager(config(), recycled);
        stale.start();
        LumaChatExportManager.Listener oldChild = LumaChatExportManager.queuedCompletion;
        UserConfig.ids[0] = 90002;
        File late = new File(ApplicationLoader.applicationContext.root, "late-owned-chat.zip");
        check(late.createNewFile(), "owned late fixture archive");
        oldChild.onComplete(late, new LumaChatExportManager.Progress());
        awaitWorker(stale);
        check(recycled.cancelled.get() == 1 && recycled.total() == 1 && !late.exists(), "old child result discarded on reused login slot");
        check(oldChild == LumaChatExportManager.queuedCompletion, "replacement identity receives no next-dialog request");

        Events errorEvents = new Events();
        LumaAccountExportManager oldError = new LumaAccountExportManager(config(), errorEvents);
        oldError.start();
        LumaChatExportManager.Listener errorChild = LumaChatExportManager.queuedCompletion;
        UserConfig.ids[0] = 90003;
        errorChild.onError("fixture history error", null);
        awaitWorker(oldError);
        check(errorEvents.cancelled.get() == 1 && errorEvents.total() == 1,
                "late error also cancels instead of exporting the replacement identity's next chat");
        check(errorChild == LumaChatExportManager.queuedCompletion, "old error cannot schedule a new child");

        Events constructorEvents = new Events();
        LumaChatExportManager.queuedCompletion = null;
        LumaAccountExportManager changedBeforeStart = new LumaAccountExportManager(config(), constructorEvents);
        UserConfig.ids[0] = 90004;
        changedBeforeStart.start();
        awaitWorker(changedBeforeStart);
        check(constructorEvents.cancelled.get() == 1 && LumaChatExportManager.queuedCompletion == null,
                "identity changed after selection but before start cannot export");
    }

    private static void archiveReservation() throws Exception {
        Method method = LumaAccountExportManager.class.getDeclaredMethod("uniqueFile", File.class, String.class);
        method.setAccessible(true);
        File folder = new File(ApplicationLoader.applicationContext.root, "reservations");
        check(folder.mkdir(), "reservation root");
        File first = (File) method.invoke(null, folder, "same.zip");
        File second = (File) method.invoke(null, folder, "same.zip");
        check(first.isFile() && second.isFile() && !first.equals(second),
                "archive path is atomically reserved before opening the output stream");
        ExecutorService threads = Executors.newFixedThreadPool(8);
        ArrayList<Future<File>> futures = new ArrayList<>();
        for (int n = 0; n < 32; n++) futures.add(threads.submit(() -> (File) method.invoke(null, folder, "parallel.zip")));
        Set<File> reserved = new HashSet<>();
        for (Future<File> future : futures) {
            File file = future.get(5, TimeUnit.SECONDS);
            check(file.isFile() && reserved.add(file), "parallel archives never share an output/cleanup target");
        }
        threads.shutdown();
        check(threads.awaitTermination(5, TimeUnit.SECONDS), "reservation threads stopped");
    }

    private static void journalRecoveryAndOwnerRace() throws Exception {
        UserConfig.ids[0] = 91001;
        BlackHoleNotificationJournal.setEnabled(0, true);
        BlackHolePrivateData.write(0, JOURNAL, "corrupt-authenticated-json-fixture");
        check(BlackHoleNotificationJournal.entries(0).isEmpty(), "malformed authenticated JSON has an empty recoverable view");
        ArrayList<BlackHoleNotificationJournal.Entry> batch = new ArrayList<>();
        batch.add(new BlackHoleNotificationJournal.Entry(42, 1, System.currentTimeMillis(), "fixture", "text"));
        BlackHoleNotificationJournal.record(0, batch);
        check(BlackHoleNotificationJournal.entries(0).size() == 1, "new notifications recover a malformed journal");
        batch.clear();
        long secretDialog = 0x4000000000000001L;
        batch.add(new BlackHoleNotificationJournal.Entry(secretDialog, -17, System.currentTimeMillis(), "Secret fixture", "notification text"));
        batch.add(new BlackHoleNotificationJournal.Entry(secretDialog, 0, System.currentTimeMillis(), "Zero ID", "invalid"));
        BlackHoleNotificationJournal.record(0, batch);
        check(BlackHoleNotificationJournal.entries(0).size() == 2
                && BlackHoleNotificationJournal.entries(0).stream().anyMatch(e -> e.dialogId == secretDialog && e.messageId == -17),
                "legitimate negative local secret-chat message IDs survive record/read; zero ID is ignored");

        JSONArray mixed = new JSONArray();
        mixed.put(entry(42, 2));
        mixed.put(entry(42, 3).put("message", "not-an-integer"));
        mixed.put(entry(0, 4));
        BlackHolePrivateData.write(0, JOURNAL, mixed.toString());
        check(BlackHoleNotificationJournal.entries(0).size() == 1
                && BlackHoleNotificationJournal.entries(0).get(0).messageId == 2,
                "one damaged entry cannot hide or permanently block valid notification history");
        check(new JSONArray(BlackHolePrivateData.read(0, JOURNAL, "[]")).length() == 1,
                "invalid and unusable entries pruned without discarding valid data");

        JSONArray identityRace = new JSONArray();
        JSONObject switching = new JSONObject() {
            @Override public long getLong(String key) {
                long value = super.getLong(key);
                if ("date".equals(key)) UserConfig.ids[0] = 91002;
                return value;
            }
        };
        switching.put("dialog", 42L).put("date", System.currentTimeMillis()).put("message", 9)
                .put("title", "old identity").put("text", "private old text");
        identityRace.put(switching);
        identityRace.put(entry(0, 10)); // Would trigger a rewrite into the new owner's store before the fix.
        BlackHolePrivateData.write(0, JOURNAL, identityRace.toString());
        expectFailure(() -> BlackHoleNotificationJournal.entries(0));
        check(UserConfig.ids[0] == 91002 && !LumaAccountData.preferences(0).contains(JOURNAL),
                "owner change during journal parsing exposes no old results and writes no old data into new account");
        expectFailure(() -> BlackHolePrivateData.write(0, 91001, "bhg_note_42", "stale text"));
        check(!LumaAccountData.preferences(0).contains("bhg_note_42"), "expected-owner write rejects a replaced slot");
        BlackHolePrivateData.write(0, "bhg_note_42", "new text");
        expectFailure(() -> BlackHolePrivateData.remove(0, 91001, "bhg_note_42"));
        check("new text".equals(BlackHolePrivateData.read(0, "bhg_note_42", "")),
                "expected-owner removal cannot delete the replacement owner's note");
    }

    private static void concurrentNotes() throws Exception {
        UserConfig.ids[0] = 92001;
        LumaAccountData.preferences(0); // Initialize one isolated preference store before concurrent calls.
        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), go = new CountDownLatch(1);
        ArrayList<Future<?>> futures = new ArrayList<>();
        for (int thread = 0; thread < 8; thread++) {
            final int base = thread * 8;
            futures.add(threads.submit(() -> {
                ready.countDown();
                go.await();
                for (int n = 1; n <= 8; n++) BlackHoleNotes.save(0, base + n, "fixture note " + (base + n));
                return null;
            }));
        }
        check(ready.await(5, TimeUnit.SECONDS), "all note writers ready");
        go.countDown();
        for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        threads.shutdown();
        check(threads.awaitTermination(5, TimeUnit.SECONDS), "note writers stopped");
        check(BlackHoleNotes.dialogs(0).size() == 64, "parallel note saves preserve all index entries");
        for (long id = 1; id <= 64; id++) check(BlackHoleNotes.get(0, id).equals("fixture note " + id), "indexed note text retained");
        LumaAccountData.preferences(0).edit().putStringSet("bhg_notes_index", Set.of("0", "bad", "42")).apply();
        check(BlackHoleNotes.dialogs(0).equals(Set.of(42L)), "corrupted zero/invalid dialog IDs do not create unusable note rows");
        UserConfig.ids[0] = 0;
        expectFailure(() -> BlackHoleNotes.save(0, 42, "logged-out text"));
        check(!LumaAccountData.preferences(0).contains("bhg_note_42"), "logged-out slot stores no private note");
    }

    private static JSONObject entry(long dialog, int message) {
        return new JSONObject().put("dialog", dialog).put("message", message).put("date", System.currentTimeMillis())
                .put("title", "fixture").put("text", "text");
    }

    private static LumaAccountExportManager.Config config() {
        LumaAccountExportManager.Config value = new LumaAccountExportManager.Config();
        value.accountName = "fixture";
        value.chats.add(new LumaAccountExportManager.ChatSpec(42, "fixture", "private", false));
        value.chats.add(new LumaAccountExportManager.ChatSpec(43, "fixture", "private", false));
        return value;
    }

    private static final class Events implements LumaAccountExportManager.Listener {
        final AtomicInteger complete = new AtomicInteger(), errors = new AtomicInteger(), cancelled = new AtomicInteger();
        public void onProgress(LumaAccountExportManager.Progress progress) { }
        public void onComplete(File archive, int a, int b, int c, int d) { complete.incrementAndGet(); }
        public void onError(String message, Throwable error) { errors.incrementAndGet(); }
        public void onCancelled() { cancelled.incrementAndGet(); }
        int total() { return complete.get() + errors.get() + cancelled.get(); }
    }

    private static void awaitWorker(LumaAccountExportManager manager) throws Exception {
        Field field = LumaAccountExportManager.class.getDeclaredField("worker");
        field.setAccessible(true);
        check(((ExecutorService) field.get(manager)).awaitTermination(10, TimeUnit.SECONDS), "export worker terminated");
    }
    private interface Attempt { void run() throws Exception; }
    private static void expectFailure(Attempt action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("expected rejection");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
