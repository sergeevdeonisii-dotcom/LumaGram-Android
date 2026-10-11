package org.telegram.messenger;

final class UserConfig {
    static final int MAX_ACCOUNT_COUNT = 4;
    static final long[] ids = {101, 202, 0, 0};
    private final int account;
    private UserConfig(int account) { this.account = account; }
    static UserConfig getInstance(int account) { return new UserConfig(account); }
    long getClientUserId() { return ids[account]; }
}
final class LumaGhostMode {
    static final boolean[] enabled = new boolean[4];
    static boolean isEnabled(int account) { return enabled[account]; }
}
final class R {
    static final class string {
        static final int Online = 1, Lately = 2, WithinAWeek = 3, WithinAMonth = 4,
                Invisible = 5, ExperimentalGhostEnabled = 6;
    }
}
final class LocaleController {
    static int dateCalls;
    static String getString(int id) { return "text:" + id; }
    static String formatDateOnline(long date, boolean[] madeShorter) {
        dateCalls++;
        if (madeShorter != null) madeShorter[0] = true;
        return "last_seen:" + date;
    }
}
