package org.telegram.messenger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class LumaNotificationsAudit74Test {
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        LumaNotificationUpdateState state = new LumaNotificationUpdateState();
        check(state.request(false, 1000) == 0, "first update has no extra delay");
        for (int i = 0; i < 100; ++i) {
            check(state.request(i == 73, 1000) == -1, "burst has one scheduled task");
        }
        check(Boolean.TRUE.equals(state.take(1000)), "a later alert survives silent refreshes");
        check(state.take(1000) == null, "one task cannot post twice");
        check(state.request(false, 1001) == 249, "minimum spacing applies after every post");
        check(state.request(false, 1100) == -1, "edits and reads merge into the same refresh");
        check(Boolean.FALSE.equals(state.take(1250)), "a quiet batch remains quiet");
        check(state.request(true, 1251) == 249, "new alert is bounded but not discarded");
        state.clear();
        check(state.take(1500) == null, "logout invalidates a pending refresh");
        check(state.request(false, 1501) == 0, "new session has no previous delay");
        check(Boolean.FALSE.equals(state.take(1501)), "new session does not inherit an old alert");
        check(state.request(true, 1) == 0, "clock reset cannot create an unbounded delay");
        check(Boolean.TRUE.equals(state.take(1)), "clock reset preserves the requested alert");
        check(LumaNotificationAccountGuard.allows(10, 10, 50, 50, false, false), "current account may show a normal conference invite");
        check(!LumaNotificationAccountGuard.allows(0, 0, 50, 50, false, false), "logged-out invite has no owner");
        check(!LumaNotificationAccountGuard.allows(10, 11, 50, 50, false, false), "old RPC cannot publish after login slot reuse");
        check(!LumaNotificationAccountGuard.allows(10, 10, 50, 51, false, false), "old RPC cannot replace a newer call");
        check(!LumaNotificationAccountGuard.allows(10, 10, 50, 50, true, false), "hiding the chat during RPC suppresses caller details");
        check(!LumaNotificationAccountGuard.allows(10, 10, 50, 50, false, true), "emergency exclusion is rechecked after RPC");
        LumaConferenceRequestState pending = new LumaConferenceRequestState();
        long first = pending.begin(0, 40, 12);
        check(pending.isCurrent(first), "fresh conference lookup is active");
        check(pending.cancelMessage(1, 12) == 0 && pending.isCurrent(first), "another account's matching message cannot cancel it");
        check(pending.cancelMessage(0, 13) == 0 && pending.isCurrent(first), "unrelated message cannot cancel it");
        check(pending.cancelMessage(0, 12) == 40 && !pending.isCurrent(first), "end event invalidates a lookup before its state exists");
        long second = pending.begin(0, 41, 13);
        pending.finish(first);
        check(pending.isCurrent(second), "late completion cannot consume a newer request");
        check(pending.cancelCall(1, 41) == 0 && pending.isCurrent(second), "call cancellation is account-scoped");
        check(pending.cancelCall(0, 41) == 41 && !pending.isCurrent(second), "matching call cancellation suppresses a late RPC");
        long third = pending.begin(0, 42, 14), fourth = pending.begin(0, 43, 15);
        check(!pending.isCurrent(third) && pending.isCurrent(fourth), "replacement lookup invalidates the previous token");
        pending.finish(fourth);
        check(!pending.isCurrent(fourth), "completed callback is one-shot");

        if (args.length > 0) {
            String controller = new String(Files.readAllBytes(Paths.get(args[0],
                    "TMessagesProj/src/main/java/org/telegram/messenger/NotificationsController.java")), StandardCharsets.UTF_8);
            check(controller.contains("private void showOrUpdateNotification(boolean notifyAboutLast) {\n")
                    || controller.contains("private void showOrUpdateNotification(boolean notifyAboutLast) {\r\n"),
                    "existing callers still reach the shared wrapper");
            int wrapper = controller.indexOf("private void showOrUpdateNotification(boolean notifyAboutLast)");
            int internal = controller.indexOf("private void showOrUpdateNotificationInternal(boolean notifyAboutLast)");
            check(wrapper >= 0 && internal > wrapper && controller.substring(wrapper, internal)
                    .contains("requestNotificationUpdate(notifyAboutLast);"), "all legacy paths enter the limiter");
            check(controller.contains("cancelRunnable(notificationUpdateRunnable);")
                    && controller.contains("notificationUpdateState.clear();"), "cleanup cancels the actual pending task");
            int batch = controller.indexOf("public void processNewMessages(");
            int conference = controller.indexOf("VoIPGroupNotification.request(", batch);
            int hidden = controller.indexOf("BlackHoleVault.contains(currentAccount, messageObject.getDialogId())", batch);
            check(hidden > batch && hidden < conference, "vault filtering precedes conference notification posting");
            check(controller.substring(hidden, conference).contains("VoIPGroupNotification.hide("),
                    "suppressed conference updates can dismiss their existing invite");
            check(controller.substring(batch, conference).contains("messageObjects == null || messageObjects.isEmpty()"),
                    "empty/null notification batches complete without dereferencing null");
            int scheduledUpdate = controller.indexOf("private final Runnable notificationUpdateRunnable");
            int actualPost = controller.indexOf("showOrUpdateNotificationInternal(notify);", scheduledUpdate);
            int wakeRelease = controller.indexOf("releaseNotificationDelayWakeLock();", actualPost);
            check(actualPost > scheduledUpdate && wakeRelease > actualPost
                    && controller.substring(actualPost, wakeRelease).contains("finally"),
                    "delivery wake lock releases after actual posting, including failures");
            String calls = new String(Files.readAllBytes(Paths.get(args[0],
                    "TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPGroupNotification.java")), StandardCharsets.UTF_8);
            int callback = calls.indexOf("sendRequest(req,");
            int ownerGuard = calls.indexOf("LumaNotificationAccountGuard.allows(expectedOwner", callback);
            check(callback > 0 && ownerGuard > callback && ownerGuard < calls.indexOf("putUsers(r.users, false)", callback),
                    "late conference result is guarded before account data or UI writes");
            int tokenGuard = calls.indexOf("pendingRequest.isCurrent(requestToken)", callback);
            check(tokenGuard > callback && tokenGuard < ownerGuard
                    && calls.contains("pendingRequest.cancelMessage(currentAccount, msg_id)")
                    && calls.contains("pendingRequest.cancelCall(currentAccount, call_id)"), "end events invalidate pending conference callbacks");
        }
        System.out.println("PASS: .74 notification merging models and source integration (not Android notification/device tests).");
    }
}
