package org.telegram.messenger;

import android.content.Context;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.web.HttpGetTask;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real custom production models against controlled platform fixtures, not Android UI. */
public final class BlackHoleGramAudit70Test {
    public static void main(String[] args) {
        ApplicationLoader.applicationContext.root = new File(args[0]);
        UserConfig.ids[0] = 50001;
        localDeletion();
        ephemeralGhostPolicy();
        giftsCancellation();
        manualQuotePreserved();
        concurrentUpdateChecks();
        System.out.println("PASS: .70 audit regressions (explicit removal, ephemeral media, gift cancellation, quote preservation, update checks).");
    }

    private static void localDeletion() {
        check(!LumaDeletedMessages.shouldRetain(0, false, false, false), "retention disabled by default");
        LumaDeletedMessages.setEnabled(0, true);
        check(LumaDeletedMessages.shouldRetain(0, false, false, false), "remote ordinary deletion retained");
        check(!LumaDeletedMessages.shouldRetain(0, true, false, false), "explicit removal bypasses retention");
        check(!LumaDeletedMessages.shouldRetain(0, false, true, false), "scheduled deletion never retained");
        check(!LumaDeletedMessages.shouldRetain(0, false, false, true), "quick/welcome templates never retained");
        LumaDeletedMessages.rememberDeleted(0, 42, 10);
        LumaDeletedMessages.rememberDeleted(0, 42, 11);
        LumaDeletedMessages.rememberDeleted(0, 43, 10);
        LumaDeletedMessages.forgetDeleted(0, 42, Arrays.asList(10, null, 99));
        check(!LumaDeletedMessages.isDeleted(0, 42, 10), "explicit removal clears its old tombstone");
        check(LumaDeletedMessages.isDeleted(0, 42, 11) && LumaDeletedMessages.isDeleted(0, 43, 10), "unrelated messages and dialogs preserved");
        LumaDeletedMessages.setEnabled(0, false);
        LumaDeletedMessages.forgetDeleted(0, 42, Arrays.asList(11));
        check(!LumaDeletedMessages.isDeleted(0, 42, 11), "explicit removal clears tombstone even with retention off");
    }

    private static void ephemeralGhostPolicy() {
        check(!LumaGhostMode.shouldBlockEphemeralMedia(0, 42, false, true), "normal mode still opens timed media");
        LumaGhostMode.setEnabled(0, true);
        check(LumaGhostMode.shouldBlockEphemeralMedia(0, 42, false, true), "incoming timed cloud media blocked in ghost");
        check(!LumaGhostMode.shouldBlockEphemeralMedia(0, 42, false, false), "ordinary media remains available");
        check(!LumaGhostMode.shouldBlockEphemeralMedia(0, 42, true, true), "own outgoing timed media unaffected");
        check(!LumaGhostMode.shouldBlockEphemeralMedia(0, DialogObject.makeEncryptedDialogId(42), false, true), "secret-chat TTL protocol not intercepted");
        check(!LumaGhostMode.shouldBlockEphemeralMedia(0, -42, false, true), "group protocol unchanged");
        UserConfig.ids[1] = 50002;
        check(!LumaGhostMode.shouldBlockEphemeralMedia(1, 42, false, true), "other account unaffected");
        LumaGhostMode.setEnabled(0, false);
    }

    private static void giftsCancellation() {
        ConnectionsManager.cancelledRequests.clear();
        LumaGiftVisibilityOperation operation = new LumaGiftVisibilityOperation(0);
        operation.bindRequest(101);
        check(operation.isActive(), "operation starts active");
        operation.cancel();
        operation.cancel();
        check(!operation.isActive() && ConnectionsManager.cancelledRequests.size() == 1
                && ConnectionsManager.cancelledRequests.get(0) == 101, "cancel stops current request once");
        operation.bindRequest(102);
        check(ConnectionsManager.cancelledRequests.contains(102), "late request immediately cancelled");
        operation.requestFinished();
        check(!operation.isActive(), "late callback cannot continue the sequence");

        LumaGiftVisibilityOperation swapped = new LumaGiftVisibilityOperation(0);
        swapped.bindRequest(103);
        UserConfig.ids[0] = 50003;
        check(!swapped.isActive() && !swapped.isCurrentOwner(), "replacement login cannot inherit an operation");
        swapped.bindRequest(104);
        check(ConnectionsManager.cancelledRequests.contains(104), "new request for stale owner is cancelled");
        swapped.cancel();
        UserConfig.ids[0] = 50001;
        LumaGiftVisibilityOperation success = new LumaGiftVisibilityOperation(0);
        success.bindRequest(105);
        success.requestFinished();
        success.bindRequest(106);
        success.requestFinished();
        success.complete();
        success.cancel();
        check(!success.isActive() && !ConnectionsManager.cancelledRequests.contains(105)
                && !ConnectionsManager.cancelledRequests.contains(106), "normal completion does not cancel successful requests");
    }

    private static void manualQuotePreserved() {
        LumaMessageFormatting.setAutomaticStyle(LumaMessageFormatting.STYLE_QUOTE);
        TLRPC.TL_messageEntityBlockquote quote = new TLRPC.TL_messageEntityBlockquote();
        quote.offset = 0; quote.length = 5; quote.collapsed = true;
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        entities.add(quote);
        entities = LumaMessageFormatting.applyAutomaticStyle("hello world", entities);
        check(entities.contains(quote) && quote.collapsed && quote.offset == 0 && quote.length == 5, "manual collapsed quote keeps identity, state and range");
        check(entities.size() == 2 && entities.get(1).offset == 6 && entities.get(1).length == 5, "auto quote fills only uncovered text");
        entities = LumaMessageFormatting.applyAutomaticStyle("hello world", entities);
        check(entities.size() == 2 && quote.collapsed, "reapplication is idempotent");

        TLRPC.TL_messageEntityCode code = new TLRPC.TL_messageEntityCode();
        code.offset = 6; code.length = 5;
        entities = new ArrayList<>(); entities.add(quote); entities.add(code);
        entities = LumaMessageFormatting.applyAutomaticStyle("hello world", entities);
        check(entities.size() == 2 && entities.contains(code) && quote.collapsed, "code and manual quote do not gain overlapping quote entities");
        LumaMessageFormatting.setAutomaticStyle(LumaMessageFormatting.STYLE_BOLD);
        TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
        bold.offset = 0; bold.length = 3;
        entities = new ArrayList<>(); entities.add(bold); entities.add(code);
        entities = LumaMessageFormatting.applyAutomaticStyle("hello world", entities);
        check(entities.size() == 2 && entities.contains(code) && entities.get(0).length == 5, "existing auto bold and code protection unchanged");
        LumaMessageFormatting.setAutomaticStyle(LumaMessageFormatting.STYLE_NONE);
        check(LumaMessageFormatting.applyAutomaticStyle("hello world", entities) == entities, "disabled styling leaves entities untouched");
    }

    private static void concurrentUpdateChecks() {
        android.content.SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("luma_updates", Context.MODE_PRIVATE);
        prefs.edit().putString("manifest_url", "").apply();
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        check(controller.hasManifestUrl() && controller.getManifestUrl().equals(BuildVars.LUMA_UPDATE_MANIFEST_URL), "blank legacy source recovers built-in source");
        prefs.edit().putString("manifest_url", "http://bad.example/manifest.json").apply();
        check(controller.getManifestUrl().equals(BuildVars.LUMA_UPDATE_MANIFEST_URL), "insecure/corrupt stored source recovers default");
        AtomicInteger completions = new AtomicInteger();
        controller.checkForUpdate(false, null);
        HttpGetTask pending = lastRequest();
        int requests = HttpGetTask.requests.size();
        controller.checkForUpdate(true, () -> {
            check(!controller.isChecking() && controller.getUpdate() != null, "manual callback sees the actual newer response");
            completions.incrementAndGet();
        });
        controller.checkForUpdate(true, completions::incrementAndGet);
        check(completions.get() == 0 && HttpGetTask.requests.size() == requests, "manual checks join pending check without completing early or duplicating HTTP");
        manifest("new", 71500);
        pending.deliver("new");
        check(completions.get() == 2 && !controller.isChecking(), "joined callbacks finish once with the actual result");

        controller.checkForUpdate(true, completions::incrementAndGet);
        HttpGetTask old = lastRequest();
        controller.checkForUpdate(true, completions::incrementAndGet);
        controller.setManifestUrl("https://custom.example/latest.json");
        check(completions.get() == 4, "source change releases every invalidated waiter");
        HttpGetTask custom = lastRequest();
        old.deliver("new");
        check(completions.get() == 4 && controller.isChecking(), "stale source response cannot complete replacement check");
        manifest("custom", 71510);
        custom.deliver("custom");
        check(controller.getUpdate().versionCode == 71510, "valid custom source preserved");
        controller.setManifestUrl("");
        check(controller.getManifestUrl().equals(BuildVars.LUMA_UPDATE_MANIFEST_URL), "clearing custom source resets to default instead of disabling updates");
        controller.checkForUpdate(true, completions::incrementAndGet);
        HttpGetTask primary = lastRequest();
        int beforeFallback = HttpGetTask.requests.size();
        primary.deliver("");
        HttpGetTask fallback = lastRequest();
        check(fallback != primary && HttpGetTask.requests.size() == beforeFallback + 1
            && controller.isChecking() && completions.get() == 4,
            "official primary failure awaits its single fallback without completing joined callback early");
        fallback.deliver("");
        check(controller.getLastError() != null && !controller.isChecking() && completions.get() == 5,
            "failure of both official transports releases joined callback exactly once with an error");
        primary.deliver("new");
        fallback.deliver("new");
        check(controller.getLastError() != null && controller.getUpdate() == null && completions.get() == 5,
            "late primary and duplicate fallback cannot replace terminal error or finish callback twice");
    }

    private static HttpGetTask lastRequest() { return HttpGetTask.requests.get(HttpGetTask.requests.size() - 1); }
    private static void manifest(String response, int code) {
        Map<String, Object> values = new HashMap<>();
        values.put("version", "test-" + code); values.put("version_code", code);
        values.put("file_url", "https://example.org/app.apk"); values.put("sha256", "a".repeat(64));
        JSONObject.responses.put(response, values);
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
