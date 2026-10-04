package org.telegram.messenger;

import java.io.File;
import org.telegram.tgnet.ConnectionsManager;

/** Scheduling/persistence checks; does not claim to test Telegram server presence. */
public final class BlackHoleGramGhostTest {
    public static void main(String[] args) {
        ApplicationLoader.applicationContext.root = new File(args[0]);
        ApplicationLoader.applicationContext.root.mkdirs();
        UserConfig.ids[0] = 601;
        UserConfig.ids[1] = 602;
        ConnectionsManager.serverTime[0] = 2000000000;
        check(LumaGhostMode.getAutomaticScheduleDate(0, 42, 0) == 0, "disabled by default");
        LumaGhostMode.setScheduledSendEnabled(0, true);
        check(LumaGhostMode.getAutomaticScheduleDate(0, 42, 0) == 0, "schedule toggle alone does not enable ghost");
        LumaGhostMode.setEnabled(0, true);
        check(MessagesController.ghostState[0], "presence controller receives the enabled state");
        check(LumaGhostMode.getAutomaticScheduleDate(0, 42, 0) == 2000000020, "private send uses server time plus twenty seconds");
        check(LumaGhostMode.getAutomaticScheduleDate(0, -42, 0) == 2000000020, "group IDs are not mistaken for secret chats");
        check(LumaGhostMode.getAutomaticScheduleDate(0, 42, 123456) == 123456, "explicit scheduling is preserved");
        check(LumaGhostMode.getAutomaticScheduleDate(0, 42, 0x7ffffffe) == 0x7ffffffe, "send-when-online sentinel is preserved");
        check(LumaGhostMode.getAutomaticScheduleDate(0, DialogObject.makeEncryptedDialogId(42), 0) == 0, "secret chat is not auto-scheduled");
        check(!LumaGhostMode.isEnabled(1), "other accounts are isolated");
        check(LumaGhostMode.isEnabled(0) && LumaGhostMode.isScheduledSendEnabled(0), "switches survive repeated preference reads");
        UserConfig.ids[0] = 603;
        check(!LumaGhostMode.isEnabled(0), "a reused login slot does not inherit ghost settings");
        UserConfig.ids[0] = 601;
        check(LumaGhostMode.isAutomaticScheduledSendEnabled(0), "settings remain attached to their owner");
        ConnectionsManager.serverTime[0] = 0;
        int before = (int)(System.currentTimeMillis() / 1000L) + 20;
        int fallback = LumaGhostMode.getAutomaticScheduleDate(0, 42, 0);
        int after = (int)(System.currentTimeMillis() / 1000L) + 20;
        check(fallback >= before && fallback <= after, "device time is a bounded fallback");
        LumaGhostMode.setEnabled(0, false);
        check(!MessagesController.ghostState[0], "presence controller receives the disabled state");
        check(LumaGhostMode.getAutomaticScheduleDate(0, 42, 0) == 0, "turning ghost off stops auto-scheduling");
        System.out.println("PASS: BlackHoleGram ghost scheduling and preference preservation.");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
