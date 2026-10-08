package org.telegram.messenger;

import android.content.SharedPreferences;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.web.HttpGetFileTask;
import org.telegram.ui.web.HttpGetTask;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Compiles production capabilities twice; no Android UI or real transport is used. */
public final class LumaFriendsBuildPolicy531Test {
    private static int checks;
    private static final int ACCOUNT = 0;

    public static void main(String[] args) throws Exception {
        ApplicationLoader.applicationContext.root = new File(args[0]);
        UserConfig.ids[ACCOUNT] = 12345;
        boolean friends = Boolean.parseBoolean(args[1]);
        check(LumaBuildPolicy.isFriendsEdition() == friends, "correct compiled edition");
        check(LumaBuildPolicy.allowsPrivacyTools() == !friends, "privacy policy");
        check(LumaBuildPolicy.allowsAnonymousNumber() == !friends, "number policy");
        check(LumaBuildPolicy.allowsProfileVerification() == !friends, "verification policy");
        check(LumaBuildPolicy.allowsBuiltInUpdates() == !friends, "update policy");
        if (friends) checkRestrictedHelpers(); else checkFullHelpers();
        checkStarsRemainAvailable();
        checkProfiles(friends);
        checkUpdater(friends);
        System.out.println("PASS: " + (friends ? "Friends" : "Full") + " edition, " + checks + " production-helper assertions.");
    }

    private static void checkRestrictedHelpers() {
        SharedPreferences prefs = LumaAccountData.preferences(ACCOUNT);
        prefs.edit().putBoolean("luma_ghost_mode_enabled_", true)
                .putBoolean("luma_ghost_mode_schedule_send_", true)
                .putBoolean("luma_keep_deleted_messages_enabled_", true)
                .putBoolean("luma_anonymous_number_enabled_", true)
                .putBoolean("luma_profile_verification_enabled_", true)
                .putString("luma_anonymous_number_digits_", "12345678")
                .putStringSet("luma_deleted_message_ids_", new HashSet<>(Arrays.asList("777:55")))
                .putString("luma_edit_history_777_55", "old stored full-edition history").apply();
        SharedPreferences global = MessagesController.getGlobalMainSettings();
        global.edit().putBoolean("luma_delayed_send_enabled", true).putInt("luma_delayed_send_step", 25).apply();
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        Map<String, ?> globalBefore = new HashMap<>(global.getAll());
        check(!LumaGhostMode.isEnabled(ACCOUNT), "old true ghost preference denied");
        check(!LumaGhostMode.isScheduledSendEnabled(ACCOUNT), "old true scheduled ghost denied");
        check(!LumaGhostMode.isAutomaticScheduledSendEnabled(ACCOUNT), "no automatic scheduled sends");
        check(!LumaGhostMode.shouldBlockEphemeralMedia(ACCOUNT, 777, false, true), "normal ephemeral opening");
        check(LumaGhostMode.getAutomaticScheduleDate(ACCOUNT, 777, 0) == 0, "normal immediate send");
        check(LumaGhostMode.getAutomaticScheduleDate(ACCOUNT, 777, 999) == 999, "explicit Telegram schedule preserved");
        check(!LumaDeletedMessages.isEnabled(ACCOUNT), "old deletion-retention preference denied");
        check(!LumaDeletedMessages.shouldRetain(ACCOUNT, false, false, false), "normal server deletion");
        check(!LumaDeletedMessages.isDeleted(ACCOUNT, 777, 55), "old tombstone does not mark a message");
        check(LumaEditHistory.getVersions(ACCOUNT, 777, 55).length() == 0, "old edit history unavailable");
        check(!LumaDelayedSend.isEnabled() && LumaDelayedSend.getDelayMs() == 0, "old automatic delay denied");
        check(!LumaAnonymousNumber.isEnabled(ACCOUNT), "old fake-number preference denied");
        check(LumaAnonymousNumber.getDisplayPhone(ACCOUNT, 12345, "375000000000").equals("375000000000"), "real phone displayed");
        check(!LumaProfileVerification.isEnabled(ACCOUNT), "old fake-verification preference denied");
        // The same public setters used by setting imports/presets cannot bypass the policy.
        LumaGhostMode.setEnabled(ACCOUNT, true);
        LumaGhostMode.setEnabled(ACCOUNT, false);
        LumaGhostMode.setScheduledSendEnabled(ACCOUNT, true);
        LumaDeletedMessages.setEnabled(ACCOUNT, true);
        LumaDeletedMessages.rememberDeleted(ACCOUNT, 777, 56);
        LumaDeletedMessages.forgetDeleted(ACCOUNT, 777, Arrays.asList(55));
        LumaEditHistory.rememberEdit(ACCOUNT, 777, message(55, "before", 0), message(55, "after", 101));
        LumaDelayedSend.setEnabled(true);
        LumaDelayedSend.setDelayStep(5);
        LumaAnonymousNumber.setEnabled(ACCOUNT, true);
        LumaAnonymousNumber.setDigits(ACCOUNT, "87654321");
        LumaProfileVerification.setEnabled(ACCOUNT, true);
        check(before.equals(prefs.getAll()), "disabled setters/history writes leave previous account data untouched");
        check(globalBefore.equals(global.getAll()), "disabled delay setters leave previous global data untouched");
        check(!MessagesController.ghostState[ACCOUNT], "no offline-status mutation from disabled toggle");
        ArrayList<SendMessagesHelper.SendMessageParams> outgoing = new ArrayList<>();
        outgoing.add(new SendMessagesHelper.SendMessageParams());
        android.os.SystemClock.now = 100;
        LumaDelayedSend.sendDetached(ACCOUNT, 12345, outgoing, 5100);
        AndroidUtilities.advanceTo(100);
        check(SendMessagesHelper.sent.size() == 1, "direct detached path cannot impose automatic delay");
        LumaDelayedSend.sendDetached(ACCOUNT, 99999, outgoing, 5100);
        AndroidUtilities.advanceTo(100);
        check(SendMessagesHelper.sent.size() == 1, "wrong owner remains blocked");
    }

    private static void checkFullHelpers() {
        LumaGhostMode.setEnabled(ACCOUNT, true);
        LumaGhostMode.setScheduledSendEnabled(ACCOUNT, true);
        ConnectionsManager.serverTime[ACCOUNT] = 1000;
        check(LumaGhostMode.isEnabled(ACCOUNT) && MessagesController.ghostState[ACCOUNT], "full ghost retained");
        check(LumaGhostMode.getAutomaticScheduleDate(ACCOUNT, 777, 0) == 1020, "full ghost schedule retained");
        check(LumaGhostMode.shouldBlockEphemeralMedia(ACCOUNT, 777, false, true), "full ephemeral safeguard retained");
        LumaDeletedMessages.setEnabled(ACCOUNT, true);
        LumaDeletedMessages.rememberDeleted(ACCOUNT, 777, 55);
        check(LumaDeletedMessages.shouldRetain(ACCOUNT, false, false, false), "full retention retained");
        check(LumaDeletedMessages.isDeleted(ACCOUNT, 777, 55), "full tombstone retained");
        LumaDeletedMessages.forgetDeleted(ACCOUNT, 777, Arrays.asList(55));
        check(!LumaDeletedMessages.isDeleted(ACCOUNT, 777, 55), "full explicit removal retained");
        LumaEditHistory.rememberEdit(ACCOUNT, 777, message(55, "before", 0), message(55, "after", 101));
        check(LumaEditHistory.getVersions(ACCOUNT, 777, 55).length() == 2, "full edit history retained");
        LumaDelayedSend.setEnabled(true);
        LumaDelayedSend.setDelayStep(10);
        check(LumaDelayedSend.isEnabled() && LumaDelayedSend.getDelayMs() == 2000, "full delay retained");
        LumaAnonymousNumber.setEnabled(ACCOUNT, true);
        LumaAnonymousNumber.setDigits(ACCOUNT, "12345678");
        check(LumaAnonymousNumber.getDisplayPhone(ACCOUNT, 12345, "375000000000").equals("88812345678"), "full number retained");
        LumaProfileVerification.setEnabled(ACCOUNT, true);
        check(LumaProfileVerification.isEnabled(ACCOUNT), "full verification retained");
    }

    private static void checkStarsRemainAvailable() {
        LumaStarRating.setEnabled(ACCOUNT, true);
        LumaStarRating.setLevel(ACCOUNT, 84);
        TL_stars.Tl_starsRating rating = LumaStarRating.getDisplayRating(ACCOUNT, 12345, null);
        check(rating != null && rating.level == 84, "local Stars rating remains available in both editions");
    }

    private static void checkProfiles(boolean friends) throws Exception {
        LumaRoundVideoCamera.setStartWithRearCameraEnabled(true);
        Map<String, Object> work = BlackHoleSettings.profile(ACCOUNT, BlackHoleSettings.PROFILE_WORK);
        check(work.size() == 28, "profile contains all portable fields");
        check(Boolean.TRUE.equals(work.get("send.roundStartRear")), "new profile preserves initial camera choice");
        check(Boolean.FALSE.equals(work.get("typing.enabled")) && Boolean.FALSE.equals(work.get("glass.enabled")), "work profile retains quiet appearance");
        check(Boolean.valueOf(!friends).equals(work.get("send.delayed")), "work only enables delay where supported");
        if (!friends) check(((Number) work.get("send.step")).intValue() == 10, "full Work delay remains two seconds");
        BlackHoleSettings.apply(ACCOUNT, work);
        check(BlackHoleSettings.capture(ACCOUNT).equals(BlackHoleSettings.profile(ACCOUNT, BlackHoleSettings.PROFILE_WORK)), "Work selection matches effective settings after apply");
        if (!friends) return;

        Map<String, Object> oldFullProfile = new HashMap<>(BlackHoleSettings.capture(ACCOUNT));
        oldFullProfile.remove("send.roundStartRear");
        oldFullProfile.put("ghost.enabled", true);
        oldFullProfile.put("ghost.schedule", true);
        oldFullProfile.put("deleted.keep", true);
        oldFullProfile.put("send.delayed", true);
        oldFullProfile.put("send.step", 2);
        oldFullProfile.put("number.enabled", true);
        oldFullProfile.put("number.digits", "87654321");
        oldFullProfile.put("verification.enabled", true);
        String originalSaved = BlackHoleSettings.encode(oldFullProfile);
        SharedPreferences prefs = LumaAccountData.preferences(ACCOUNT);
        prefs.edit().putString("bhg_profile_2", originalSaved).apply();
        Map<String, Object> effective = BlackHoleSettings.profile(ACCOUNT, BlackHoleSettings.PROFILE_WORK);
        check(effective.size() == 28 && Boolean.TRUE.equals(effective.get("send.roundStartRear")), "legacy profile inherits the current rear camera");
        check(originalSaved.equals(prefs.getString("bhg_profile_2", null)), "loading an old profile never rewrites saved preferences");
        check(effective.equals(BlackHoleSettings.capture(ACCOUNT)), "blocked old settings cannot spoil profile selection");
        BlackHoleSettings.apply(ACCOUNT, oldFullProfile);
        check(effective.equals(BlackHoleSettings.capture(ACCOUNT)), "direct legacy import cannot activate blocked capabilities");
        check(!LumaGhostMode.isEnabled(ACCOUNT) && !LumaAnonymousNumber.isEnabled(ACCOUNT)
                && !LumaProfileVerification.isEnabled(ACCOUNT), "restricted toggles remain disabled after old-profile import");
        boolean rejected = false;
        try { BlackHoleSettings.profile(ACCOUNT, BlackHoleSettings.PROFILE_GHOST); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Ghost preset is unavailable in Friends edition");
    }

    private static void checkUpdater(boolean friends) throws Exception {
        File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
        check(directory.mkdirs() || directory.isDirectory(), "owned test cache created");
        File apk = new File(directory, "luma-update-9999999.apk");
        File part = new File(directory, "luma-update-9999999-123.apk.part");
        Files.write(apk.toPath(), new byte[]{1, 2, 3});
        Files.write(part.toPath(), new byte[]{4, 5, 6});
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("luma_updates", 0);
        prefs.edit().putString("version", "future-full-edition")
                .putInt("version_code", 9999999).putString("path", apk.getAbsolutePath())
                .putString("file_url", "https://github.com/owner/repo/apk")
                .putString("sha256", repeat('a', 64)).putString("manifest_url", "https://example.com/full.json")
                .putBoolean("auto_check", true).apply();
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        if (!friends) {
            check(controller.getUpdate() != null && controller.getDownloadedFile().equals(apk), "full cached updater retained");
            AtomicInteger done = new AtomicInteger();
            controller.checkForUpdate(true, done::incrementAndGet);
            check(HttpGetTask.requests.size() == 1, "full forced check still launches transport");
            HttpGetTask.requests.get(0).deliver(null);
            check(done.get() == 1 && !controller.isChecking(), "full failed check completes normally");
            return;
        }
        check(before.equals(prefs.getAll()), "Friends constructor leaves full updater preferences untouched");
        check(apk.isFile() && part.isFile(), "Friends constructor leaves pending APK and staging cache untouched");
        check(controller.getUpdate() == null && controller.getDownloadedFile() == null, "cached full-edition update inaccessible");
        // Even stale in-memory metadata cannot unlock another install route.
        set(controller, "version", "cached-full");
        set(controller, "versionCode", 9999999);
        set(controller, "path", apk.getAbsolutePath());
        check(controller.getUpdate() == null && controller.getDownloadedFile() == null, "in-memory cached full edition denied");
        check(!controller.install(new android.app.Activity()), "installer denied before permissions or file opening");
        check(!controller.hasManifestUrl() && controller.getManifestUrl().isEmpty(), "full manifest not exposed");
        check(!controller.isAutoCheckEnabled(), "automatic checks remain disabled");
        controller.setAutoCheckEnabled(true);
        check(!controller.setManifestUrl("https://example.net/other.json"), "source replacement cannot enable updater");
        AtomicInteger done = new AtomicInteger();
        controller.checkForUpdate(false, done::incrementAndGet);
        controller.checkForUpdate(true, done::incrementAndGet);
        check(done.get() == 2 && !controller.isChecking(), "forced and automatic checks release callbacks exactly once");
        controller.checkForUpdate(true, () -> { throw new IllegalStateException("completion fixture"); });
        check(!controller.isChecking(), "a failed disabled-check callback cannot strand or crash the updater");
        check(HttpGetTask.requests.isEmpty(), "restricted checks never create HTTP requests");
        AtomicInteger finished = new AtomicInteger();
        controller.addDownloadListener(new LumaUpdaterController.DownloadListener() {
            public void onProgress(float value) { throw new AssertionError("no progress expected"); }
            public void onFinished(File file, String error) { throw new IllegalStateException("listener fixture"); }
        });
        controller.downloadUpdate(new LumaUpdaterController.DownloadListener() {
            public void onProgress(float value) { throw new AssertionError("no progress expected"); }
            public void onFinished(File file, String error) {
                check(file == null && error != null, "download completion indicates denial");
                finished.incrementAndGet();
            }
        });
        controller.downloadUpdate();
        check(finished.get() == 1, "one failed listener does not block or repeat another listener");
        check(HttpGetFileTask.requests.isEmpty() && !controller.isDownloading(), "restricted download never starts or gets stuck");
        check(before.equals(prefs.getAll()) && apk.isFile() && part.isFile(), "all restricted updater paths leave existing data untouched");
    }

    private static TLRPC.Message message(int id, String text, int editDate) {
        TLRPC.Message message = new TLRPC.Message();
        message.id = id; message.message = text; message.date = 100; message.edit_date = editDate;
        message.flags = editDate == 0 ? 0 : TLRPC.MESSAGE_FLAG_EDITED;
        return message;
    }
    private static String repeat(char value, int count) {
        char[] data = new char[count]; Arrays.fill(data, value); return new String(data);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
