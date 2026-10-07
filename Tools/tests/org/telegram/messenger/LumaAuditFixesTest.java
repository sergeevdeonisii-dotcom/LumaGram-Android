package org.telegram.messenger;

import android.content.SharedPreferences;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.web.HttpGetTask;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real Luma classes against small controlled platform/transport fixtures, not Android UI tests. */
public final class LumaAuditFixesTest {
    public static void main(String[] args) throws Exception {
        ApplicationLoader.applicationContext.root = new File(args[0]);
        ApplicationLoader.applicationContext.root.mkdirs();
        accountIsolationAndMigration();
        ratingWithoutServerObject();
        detachedSendKeepsDeadlineAndOwner();
        safeUpdateCleanup();
        updateSourceRace();
        exportCancellationAndSuccess();
        System.out.println("PASS: audit regressions (identity isolation, local level, delay, APK cleanup, update source, export).");
    }

    private static void accountIsolationAndMigration() {
        UserConfig.ids[2] = 12345;
        SharedPreferences legacy = MessagesController.getGlobalMainSettings();
        legacy.edit().putBoolean("luma_ghost_mode_enabled_2", true)
                .putBoolean("luma_ghost_mode_schedule_send_2", true)
                .putString("luma_edit_history_2_-123_55", "old private text")
                .putStringSet("luma_deleted_message_ids_2", Collections.singleton("-123:55"))
                .putString("luma_anonymous_number_digits_2", "87654321").apply();
        SharedPreferences first = LumaAccountData.preferences(2);
        check(first.getBoolean("luma_ghost_mode_enabled_", false), "protective ghost switch preserved");
        check(first.getBoolean("luma_ghost_mode_schedule_send_", false), "scheduled ghost switch preserved");
        check(!first.contains("luma_edit_history_-123_55"), "unattributed legacy history not imported");
        check(!LumaDeletedMessages.isDeleted(2, -123, 55), "legacy tombstone not imported");
        LumaAnonymousNumber.setEnabled(2, true);
        LumaAnonymousNumber.setDigits(2, "12345678");
        LumaStarRating.setEnabled(2, true);
        LumaStarRating.setLevel(2, 80);
        LumaDeletedMessages.setEnabled(2, true);
        LumaDeletedMessages.rememberDeleted(2, -123, 55);
        first.edit().putString("luma_edit_history_-123_55", "new private text").apply();

        UserConfig.ids[2] = 98765;
        check(!LumaAnonymousNumber.isEnabled(2), "new identity has no old number override");
        check(!LumaStarRating.isEnabled(2), "new identity has no old level");
        check(!LumaDeletedMessages.isDeleted(2, -123, 55), "new identity has no old tombstone");
        check(!LumaAccountData.preferences(2).contains("luma_edit_history_-123_55"), "new identity has no old history");
        check(!LumaAccountData.preferences(2).getBoolean("luma_ghost_mode_enabled_", false), "legacy migration not repeated for replacement identity");

        UserConfig.ids[3] = 12345;
        check(LumaAnonymousNumber.isEnabled(3), "same identity retains settings in a different slot");
        check(LumaStarRating.getLevel(3) == 80, "level follows identity rather than slot");
        LumaAccountData.clearOnLogout(3, 12345);
        check(!first.contains("luma_edit_history_-123_55"), "logout clears owned private history");
        check(!LumaAnonymousNumber.isEnabled(3), "logout clears owned profile override");
        LumaAccountData.clearOnLogout(2, 98765);
        check(!legacy.contains("luma_edit_history_2_-123_55"), "logout clears legacy slot history too");
        UserConfig.ids[2] = 0;
        check(!LumaStarRating.isEnabled(2), "signed-out slot has no active preferences");
    }

    private static void ratingWithoutServerObject() {
        UserConfig.ids[2] = 100001;
        LumaStarRating.setEnabled(2, true);
        TL_stars.Tl_starsRating original = new TL_stars.Tl_starsRating();
        original.flags = 32;
        original.stars = 123;
        for (int level = 1; level <= 100; level++) {
            LumaStarRating.setLevel(2, level);
            TL_stars.Tl_starsRating rating = LumaStarRating.getDisplayRating(2, 100001, null);
            check(rating != null && rating.level == level, "level exists without server object");
            check(rating.stars >= rating.current_level_stars, "progress above current threshold");
            if (level < 100) {
                check(rating.stars < rating.next_level_stars, "progress below next threshold");
                check(rating.stars == LumaStarRating.getDisplayRating(2, 100001, null).stars, "progress stable");
            } else check((rating.flags & 1) == 0, "max level has no next-level flag");
        }
        check(LumaStarRating.getDisplayRating(2, 42, original) == original, "other profile unchanged");
        LumaStarRating.getDisplayRating(2, 100001, original);
        check(original.flags == 32 && original.stars == 123, "server object never mutated");
        LumaStarRating.setEnabled(2, false);
        check(LumaStarRating.getDisplayRating(2, 100001, null) == null, "disabled override leaves absent rating absent");
    }

    private static void detachedSendKeepsDeadlineAndOwner() {
        UserConfig.ids[2] = 100001;
        android.os.SystemClock.now = 100;
        ArrayList<SendMessagesHelper.SendMessageParams> messages = new ArrayList<>();
        SendMessagesHelper.SendMessageParams message = new SendMessagesHelper.SendMessageParams();
        message.peer = 777;
        messages.add(message);
        LumaDelayedSend.sendDetached(2, 100001, messages, 5100);
        AndroidUtilities.advanceTo(5099);
        check(SendMessagesHelper.sent.isEmpty(), "detached send does not bypass remaining delay");
        AndroidUtilities.advanceTo(5100);
        check(SendMessagesHelper.sent.size() == 1 && SendMessagesHelper.sent.get(0).peer == 777, "correct dialog sent at deadline");
        LumaDelayedSend.sendDetached(2, 100001, messages, 10100);
        UserConfig.ids[2] = 100002;
        AndroidUtilities.advanceTo(10100);
        check(SendMessagesHelper.sent.size() == 1, "replacement account never sends old queued text");
        check(LumaDelayedSend.remainingDelay(50, 100) == 0, "elapsed deadline is clamped");
    }

    private static void safeUpdateCleanup() throws Exception {
        File root = new File(ApplicationLoader.applicationContext.root, "cleanup");
        root.mkdirs();
        File old = touch(root, "luma-update-71429.apk");
        File installed = touch(root, "luma-update-71439.apk");
        File future = touch(root, "luma-update-71449.apk");
        File foreign = touch(root, "photo.apk");
        File outside = touch(ApplicationLoader.applicationContext.root, "luma-update-71419.apk");
        File nested = new File(root, "nested");
        nested.mkdirs();
        File child = touch(nested, "luma-update-71419.apk");
        check(!LumaUpdateFiles.delete(root, outside), "outside file cannot be deleted");
        check(!LumaUpdateFiles.delete(root, child), "nested file cannot be deleted");
        LumaUpdateFiles.cleanupInstalled(root, 71439);
        check(!old.exists() && !installed.exists(), "old and installed APKs removed");
        check(future.exists() && foreign.exists() && outside.exists() && child.exists(), "future and unrelated files preserved");
    }

    private static void updateSourceRace() throws Exception {
        File cache = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
        cache.mkdirs();
        File installed = touch(cache, "luma-update-71439.apk");
        File orphan = touch(cache, "luma-update-71429.apk");
        ApplicationLoader.applicationContext.getSharedPreferences("luma_updates", 0).edit()
                .putInt("version_code", 71439).putString("path", installed.getAbsolutePath()).apply();
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        check(!installed.exists() && !orphan.exists(), "controller startup cleans installed and orphan APKs");
        manifest("A", 71500); manifest("B", 71510); manifest("C", 71520);
        AtomicInteger completions = new AtomicInteger();
        controller.checkForUpdate(true, completions::incrementAndGet);
        HttpGetTask old = HttpGetTask.requests.get(HttpGetTask.requests.size() - 1);
        check(controller.setManifestUrl("https://b.example/latest.json"), "HTTPS source accepted");
        HttpGetTask current = HttpGetTask.requests.get(HttpGetTask.requests.size() - 1);
        check(current != old && current.url.startsWith("https://b.example/"), "replacement source checked immediately");
        check(completions.get() == 1, "invalidated spinner completion delivered once");
        old.deliver("A");
        check(controller.getUpdate() == null && controller.isChecking(), "old result cannot overwrite pending new source");
        current.deliver("B");
        check(controller.getUpdate().versionCode == 71510 && !controller.isChecking(), "new source result accepted");
        old.deliver("A");
        check(controller.getUpdate().versionCode == 71510 && completions.get() == 1, "late old result and callback ignored");
        check(!controller.setManifestUrl("http://c.example/latest.json"), "insecure source rejected");
        check(controller.getUpdate().versionCode == 71510, "rejected source does not clear valid result");
    }

    private static void manifest(String response, int version) {
        Map<String,Object> data = new HashMap<>();
        data.put("version", "test-" + version); data.put("version_code", version);
        data.put("file_url", "https://github.com/example/app.apk");
        data.put("sha256", String.join("", Collections.nCopies(64, "a")));
        JSONObject.responses.put(response, data);
    }

    private static void exportCancellationAndSuccess() throws Exception {
        UserConfig.ids[0] = 100003;
        AtomicInteger cancellations = new AtomicInteger();
        LumaAccountExportManager manager = new LumaAccountExportManager(exportConfig(), listener(cancellations, null));
        manager.start();
        manager.cancel(); manager.cancel();
        awaitWorker(manager);
        for (int i = 0; i < 10; i++) {
            File late = touch(ApplicationLoader.applicationContext.root, "late-" + i + ".zip");
            LumaChatExportManager.queuedCompletion.onComplete(late, new LumaChatExportManager.Progress());
            check(!late.exists(), "late child archive cleaned after cancellation");
        }
        check(cancellations.get() == 1, "one cancellation callback and no crash");

        final File[] result = {null};
        LumaAccountExportManager success = new LumaAccountExportManager(exportConfig(), listener(new AtomicInteger(), result));
        success.start();
        File child = new File(ApplicationLoader.applicationContext.root, "child.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(child.toPath()))) {
            zip.putNextEntry(new ZipEntry("messages.html"));
            zip.write("<!DOCTYPE html><html><head></head><body>fixture</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        LumaChatExportManager.queuedCompletion.onComplete(child, new LumaChatExportManager.Progress());
        awaitWorker(success);
        check(result[0] != null && result[0].isFile() && !child.exists(), "normal account export still completes");
    }

    private static LumaAccountExportManager.Config exportConfig() {
        LumaAccountExportManager.Config config = new LumaAccountExportManager.Config();
        config.accountName = "fixture";
        config.chats.add(new LumaAccountExportManager.ChatSpec(1, "fixture", "private", false));
        return config;
    }

    private static LumaAccountExportManager.Listener listener(AtomicInteger cancellations, File[] result) {
        return new LumaAccountExportManager.Listener() {
            public void onProgress(LumaAccountExportManager.Progress p) { }
            public void onComplete(File archive, int a, int b, int c, int d) { if (result != null) result[0] = archive; }
            public void onError(String message, Throwable error) { throw new AssertionError(message, error); }
            public void onCancelled() { cancellations.incrementAndGet(); }
        };
    }

    private static void awaitWorker(LumaAccountExportManager manager) throws Exception {
        Field field = LumaAccountExportManager.class.getDeclaredField("worker");
        field.setAccessible(true);
        check(((ExecutorService) field.get(manager)).awaitTermination(10, TimeUnit.SECONDS), "export worker terminates");
    }

    private static File touch(File parent, String name) throws Exception {
        File file = new File(parent, name);
        Files.write(file.toPath(), new byte[]{1});
        return file;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
