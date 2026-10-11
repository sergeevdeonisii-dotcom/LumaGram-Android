package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

public final class GhostProfile150Test {
    private static int checks;
    private static final int NOW = 2_000_000_000;
    private static final String NEUTRAL = "text:" + R.string.ExperimentalGhostEnabled;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static TLRPC.User user(long id, TLRPC.UserStatus status, int expires) {
        TLRPC.User user = new TLRPC.User(); user.id = id; user.status = status;
        if (status != null) status.expires = expires;
        return user;
    }
    private static String format(int account, TLRPC.User user, boolean[] online) {
        return LumaGhostProfileStatus.format(account, user, NOW, online, null);
    }
    public static void main(String[] args) {
        TLRPC.User self = user(101, new TLRPC.TL_userStatusOffline(), NOW - 7200);
        boolean[] online = {true}, shortened = {true};
        check(format(0, self, online) == null && online[0], "ordinary self profile is unchanged");
        LumaGhostMode.enabled[0] = true;
        String result = LumaGhostProfileStatus.format(0, self, NOW, online, shortened);
        check(result.equals("last_seen:" + (NOW - 7200)), "real offline timestamp uses native formatter");
        check(!online[0] && shortened[0], "offline color and short native formatting");
        check(self.status.expires == NOW - 7200, "server offline timestamp is not mutated");
        check(format(0, user(202, new TLRPC.TL_userStatusOffline(), NOW - 10), online) == null,
                "other user unchanged even if ghost is on");
        check(format(1, self, online) == null, "different account does not reuse self status");
        LumaGhostMode.enabled[1] = true;
        check(format(1, user(202, new TLRPC.TL_userStatusOffline(), NOW - 20), online).equals("last_seen:" + (NOW - 20)),
                "second ghost account uses only its own status");
        UserConfig.ids[0] = 0;
        check(format(0, self, online) == null, "logout rejects old profile");
        UserConfig.ids[0] = 303;
        check(format(0, self, online) == null, "reused login slot rejects previous owner's profile");
        check(format(0, user(303, null, 0), online).equals(NEUTRAL), "new owner cannot inherit previous last seen");
        UserConfig.ids[0] = 101;
        check(format(-1, self, online) == null && format(4, self, online) == null,
                "invalid account is rejected before lookup");
        check(format(0, null, online) == null, "null user unchanged");
        check(format(0, user(0, null, 0), online) == null, "zero user not self");
        check(format(0, user(101, new TLRPC.TL_userStatusOnline(), NOW + 50), online).equals("text:1") && online[0],
                "genuine online from another session remains truthful");
        for (int expires : new int[]{0, -1, NOW - 1, NOW}) {
            check(format(0, user(101, new TLRPC.TL_userStatusOnline(), expires), online).equals(NEUTRAL) && !online[0],
                    "online expiry is never fabricated into last seen: " + expires);
        }
        for (int expires : new int[]{0, -1, -100, NOW + 1}) {
            check(format(0, user(101, new TLRPC.TL_userStatusOffline(), expires), online).equals(NEUTRAL),
                    "invalid or future offline timestamp remains unknown: " + expires);
        }
        check(format(0, user(101, new TLRPC.TL_userStatusOffline(), NOW), online).equals("last_seen:" + NOW),
                "offline timestamp at current time is valid");
        int calls = LocaleController.dateCalls;
        for (TLRPC.UserStatus status : new TLRPC.UserStatus[]{new TLRPC.TL_userStatusRecently(),
                new TLRPC.TL_userStatusLastWeek(), new TLRPC.TL_userStatusLastMonth(), new TLRPC.TL_userStatusHidden()}) {
            int label = status instanceof TLRPC.TL_userStatusRecently ? 2 : status instanceof TLRPC.TL_userStatusLastWeek ? 3
                    : status instanceof TLRPC.TL_userStatusLastMonth ? 4 : 5;
            for (int expires : new int[]{0, -100, -1000}) {
                status.expires = expires; status.by_me = true; online[0] = true; shortened[0] = true;
                check(LumaGhostProfileStatus.format(0, user(101, status, expires), NOW, online, shortened).equals("text:" + label),
                        "server privacy status retains its coarse meaning");
                check(!online[0] && !shortened[0] && status.expires == expires && status.by_me,
                        "privacy status and flags are read-only; output flags reset");
            }
        }
        check(LocaleController.dateCalls == calls, "privacy status never invents exact last seen");
        check(format(0, user(101, null, 0), online).equals(NEUTRAL), "unknown status has neutral fallback");
        check(format(0, user(101, new TLRPC.TL_userStatusEmpty(), 0), online).equals(NEUTRAL), "empty status has neutral fallback");
        check(format(0, user(101, new TLRPC.UserStatus(), NOW - 10), online).equals(NEUTRAL), "unknown subtype is not treated as offline");
        check(LumaGhostProfileStatus.format(0, self, 0, online, null).equals(NEUTRAL), "unknown server clock does not fabricate status");
        check(LumaGhostProfileStatus.format(0, self, NOW, new boolean[0], new boolean[0]).startsWith("last_seen:"),
                "optional output arrays may be empty");
        check(format(0, self, null).startsWith("last_seen:"), "optional online output may be absent");
        LumaGhostMode.enabled[0] = false;
        check(format(0, self, online) == null, "turning ghost off restores original branch immediately");
        System.out.println("PASS: " + checks + " ghost self-profile status checks; server formatter modeled, no network.");
    }
}
