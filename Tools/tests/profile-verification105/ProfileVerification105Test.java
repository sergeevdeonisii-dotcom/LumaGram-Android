package org.telegram.messenger;

public final class ProfileVerification105Test {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static boolean prefix(int account, long shown, long owner, boolean encrypted, long icon) {
        return LumaProfileVerification.showsPrefix(account, shown, owner, encrypted, icon);
    }
    private static boolean footer(int account, long shown, long owner, boolean encrypted, boolean verified, boolean managed) {
        return LumaProfileVerification.showsFooter(account, shown, owner, encrypted, verified, managed);
    }
    public static void main(String[] args) {
        check(!prefix(0, 101, 101, false, 0), "disabled by default");
        check(!footer(0, 101, 101, false, false, false), "no disabled footer");
        LumaProfileVerification.setEnabled(0, true);
        check(prefix(0, 101, 101, false, 0), "own enabled prefix");
        check(footer(0, 101, 101, false, false, false), "own enabled footer");
        for (long shown : new long[] { 0, -101, 202, Long.MAX_VALUE }) {
            check(!prefix(0, shown, 101, false, 0), "other/invalid profile no prefix");
            check(!footer(0, shown, 101, false, false, false), "other/invalid profile no footer");
        }
        check(!prefix(0, 0, 0, false, 0), "logged-out zero ids");
        check(!footer(0, 0, 0, false, false, false), "logged-out footer");
        check(!prefix(0, 101, 101, true, 0), "secret lock wins");
        check(!footer(0, 101, 101, true, false, false), "secret profile no local notice");
        check(!prefix(0, 101, 101, false, 987), "real server emoji wins");
        check(!prefix(0, 101, 101, false, -987), "any nonzero server document wins");
        check(!footer(0, 101, 101, false, true, false), "real server note wins");
        check(!footer(0, 101, 101, false, false, true), "managed bot note wins");
        check(!prefix(1, 202, 202, false, 0), "preferences do not cross accounts");
        check(!footer(1, 202, 202, false, false, false), "footer account isolation");
        LumaProfileVerification.setEnabled(1, true);
        check(prefix(1, 202, 202, false, 0), "second account own enabled");
        check(!prefix(1, 101, 202, false, 0), "account switch cannot decorate old owner");
        check(!footer(1, 101, 202, false, false, false), "old owner footer gone");
        LumaProfileVerification.setEnabled(0, false);
        check(!prefix(0, 101, 101, false, 0), "toggle off prefix");
        check(!footer(0, 101, 101, false, false, false), "toggle off footer");
        check(prefix(1, 202, 202, false, 0), "toggle only current account");
        BuildConfig.LUMA_FRIENDS_EDITION = true;
        check(!prefix(1, 202, 202, false, 0), "existing friends capability respected");
        check(!footer(1, 202, 202, false, false, false), "friends footer hidden");
        LumaProfileVerification.setEnabled(1, false);
        BuildConfig.LUMA_FRIENDS_EDITION = false;
        check(prefix(1, 202, 202, false, 0), "restricted build cannot overwrite saved preference");
        BuildConfig.LUMA_PUBLIC_EDITION = true;
        check(prefix(1, 202, 202, false, 0), "existing public policy not silently changed");
        System.out.println("PASS: " + checks + " local profile eligibility, native priority, toggle and account checks.");
    }
}
