package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** Runs the production helpers, not a parallel implementation of their conversion/storage logic. */
public final class AnonymousNumber150Test {
    private static int checks;
    private static final String DATE = "luma_anonymous_number_purchase_date";
    private static final String PRICE = "luma_anonymous_number_price_ton";
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void equal(Object actual, Object expected, String message) {
        check(java.util.Objects.equals(actual, expected), message + ": " + actual + " != " + expected);
    }
    private static SharedPreferences named(String name) {
        return ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
    private static long now() { return System.currentTimeMillis() / 1000L; }

    private static void amounts() {
        String[][] valid = {
            {"0", "0"}, {"000000000", "0"}, {"94.300", "94.3"}, {" 94,30 ", "94.3"},
            {"0.000000001", "0.000000001"}, {"999999999.999999999", "999999999.999999999"},
            {"000000001.010000000", "1.01"}, {"42.000000000", "42"}
        };
        for (String[] pair : valid) equal(LumaAnonymousNumber.normalizePriceTon(pair[0]), pair[1], "ASCII TON normalization");
        for (String invalid : new String[] {null, "", " ", "1e3", "1E3", "NaN", "Infinity", "∞", "-0", "-1", "+1",
                "1.", ".1", "1,", ",1", "1.1.1", "1,2.3", "1000000000", "0000000000", "0.0000000001",
                "999999999.9999999999", "９４", "٩٤", "94\u00a030", "1 000", "1\n", "\t1", "1\u200b",
                "1 TON", "$10", " ".repeat(65) + "1"}) {
            equal(LumaAnonymousNumber.normalizePriceTon(invalid), null, "malformed TON rejected");
        }
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0d, -0d, -1d}) {
            equal(LumaAnonymousNumber.getUsdCents(1_000_000_000L, invalid), -1L, "invalid Telegram rate hidden");
        }
        equal(LumaAnonymousNumber.getUsdCents(-1, 1), -1L, "negative nano rejected");
        equal(LumaAnonymousNumber.getUsdCents(94_300_000_000L, 1.3613), 12837L, "native example USD from TON rate");
        equal(LumaAnonymousNumber.getUsdCents(5_000_000L, 1), 1L, "half cent rounds up");
        equal(LumaAnonymousNumber.getUsdCents(4_999_999L, 1), 0L, "less than half cent rounds down");
        equal(LumaAnonymousNumber.getUsdCents(1_000_000_000L, 1.005), 101L, "binary double converted using decimal value");
        equal(LumaAnonymousNumber.getUsdCents(0, Double.MAX_VALUE), 0L, "zero amount remains zero");
        equal(LumaAnonymousNumber.getUsdCents(1, Double.MIN_VALUE), 0L, "positive tiny rate remains valid zero cents");
        equal(LumaAnonymousNumber.getUsdCents(1, Double.MAX_VALUE), -1L, "conversion overflow hidden");
        equal(LumaAnonymousNumber.getUsdCents(Long.MAX_VALUE, 1), 922337203685L, "long nano conversion is exact");
        equal(LumaAnonymousNumber.getUsdCents(Long.MAX_VALUE, 10_000_000), Long.MAX_VALUE, "maximum output remains representable");
        equal(LumaAnonymousNumber.getUsdCents(Long.MAX_VALUE, Math.nextUp(10_000_000d)), -1L, "just-over-long boundary rejected");
        for (long cents = 0; cents < 100; cents++) {
            long nano = cents * 10_000_000L;
            equal(LumaAnonymousNumber.getUsdCents(nano, 1), cents, "exact cent");
            equal(LumaAnonymousNumber.getUsdCents(nano + 5_000_000L, 1), cents + 1, "HALF_UP boundary");
        }
    }

    private static void edition(boolean friends, boolean publicEdition, long owner) {
        BuildConfig.LUMA_FRIENDS_EDITION = friends;
        BuildConfig.LUMA_PUBLIC_EDITION = publicEdition;
        UserConfig.ids[0] = owner;
        UserConfig.ids[1] = owner + 1;
        UserConfig.selectedAccount = 1;
        equal(LumaAnonymousNumber.getPriceTon(0), "0", "no invented price");
        equal(LumaAnonymousNumber.getFormattedPriceTon(0), "0 TON", "TON-only format");
        int before = (int) now();
        int initial = LumaAnonymousNumber.getPurchaseDate(0);
        check(initial > 0 && initial <= now() && initial >= before - 60, "current minute default");
        check(!named("luma_user_" + owner).contains(DATE), "disabled read does not persist date");
        LumaAnonymousNumber.setEnabled(0, true);
        LumaAnonymousNumber.setDigits(0, "24673891");
        equal(LumaAnonymousNumber.isEnabled(0), !friends, "build capability cannot be bypassed");
        equal(LumaAnonymousNumber.setPriceTon(0, owner, "94.3"), !friends, "edition amount write gate");
        equal(LumaAnonymousNumber.setPurchaseDate(0, owner, 1_713_285_540L), !friends, "edition date write gate");
        if (friends) {
            check(named("luma_user_" + owner).getAll().isEmpty(), "restricted edition performs no profile writes");
            equal(LumaAnonymousNumber.getPriceNanoTon(0), 0L, "restricted amount zero");
            equal(LumaAnonymousNumber.getDisplayPhone(0, owner, "real"), "real", "restricted original phone");
            return;
        }
        equal(LumaAnonymousNumber.getDisplayPhone(0, owner, "real"), "88824673891", "own visual phone");
        equal(LumaAnonymousNumber.getDisplayPhone(0, owner + 1, "real"), "real", "other user never overridden");
        equal(LumaAnonymousNumber.getPriceNanoTon(0), 94_300_000_000L, "TON nanounit exact");
        equal(LumaAnonymousNumber.getFormattedPriceTon(0), "94.3 TON", "no dollar symbol or market estimate");
        equal(LumaAnonymousNumber.getPurchaseDate(0), 1_713_285_540, "explicit date retained");
        equal(LumaAnonymousNumber.getPriceTon(1), "0", "selected account does not redirect stored amount");
        check(!LumaAnonymousNumber.setPriceTon(0, owner + 1, "1"), "stale picker amount denied");
        check(!LumaAnonymousNumber.setPurchaseDate(0, owner + 1, 60), "stale picker date denied");
        check(!LumaAnonymousNumber.setPriceTon(0, 0, "1"), "zero owner denied");
        check(!LumaAnonymousNumber.setPriceTon(0, owner, "1e3"), "bad write denied");
        equal(LumaAnonymousNumber.getPriceTon(0), "94.3", "failed write retains prior price");
        for (long invalid : new long[] {-1, 0, Long.MIN_VALUE, Long.MAX_VALUE, (long) Integer.MAX_VALUE + 1, now() + 61}) {
            check(!LumaAnonymousNumber.setPurchaseDate(0, owner, invalid), "invalid or future date denied");
        }
        equal(LumaAnonymousNumber.getPurchaseDate(0), 1_713_285_540, "failed write retains prior date");
        check(LumaAnonymousNumber.setPurchaseDate(0, owner, 1), "Unix lower positive boundary");
        equal(LumaAnonymousNumber.getPurchaseDate(0), 1, "positive second preserved");
        check(LumaAnonymousNumber.setPurchaseDate(0, owner, now()), "current second accepted");
        if (now() < Integer.MAX_VALUE) check(!LumaAnonymousNumber.setPurchaseDate(0, owner, Integer.MAX_VALUE), "int boundary is future today");
        check(LumaAnonymousNumber.setPriceTon(0, owner, "999999999.999999999"), "max TON accepted");
        equal(LumaAnonymousNumber.getPriceNanoTon(0), 999_999_999_999_999_999L, "nine-nine precision exact");
        check(LumaAnonymousNumber.setPriceTon(0, owner, "0.000000001"), "one nano accepted");
        equal(LumaAnonymousNumber.getPriceNanoTon(0), 1L, "one nano survives storage");

        SharedPreferences own = named("luma_user_" + owner);
        own.edit().remove(DATE).apply();
        int defaultDate = LumaAnonymousNumber.getPurchaseDate(0);
        check(own.contains(DATE), "enabled valid identity persists default date");
        equal(LumaAnonymousNumber.getPurchaseDate(0), defaultDate, "default date stable across repeated reads");
        own.edit().putInt(DATE, Integer.MAX_VALUE).apply();
        if (now() < Integer.MAX_VALUE) check(LumaAnonymousNumber.getPurchaseDate(0) < Integer.MAX_VALUE, "future stored date repaired");
        own.edit().putString(PRICE, "1e99").apply();
        equal(LumaAnonymousNumber.getPriceNanoTon(0), 0L, "corrupt decimal fails to zero");
        LumaAnonymousNumber.setPriceTon(0, owner, "7.125");
        // A Telegram identity can move between slots; real LumaAccountData owns the namespace.
        UserConfig.ids[2] = owner;
        equal(LumaAnonymousNumber.getPriceTon(2), "7.125", "same identity in another slot retains model");
        UserConfig.ids[0] = owner + 2;
        equal(LumaAnonymousNumber.getPriceTon(0), "0", "reused slot does not inherit previous price");
        check(!LumaAnonymousNumber.setPriceTon(0, owner, "8"), "picker opened for old slot owner cannot write");
        equal(LumaAnonymousNumber.getPriceTon(2), "7.125", "stale save leaves old identity unchanged");
        UserConfig.ids[0] = 0;
        UserConfig.ids[2] = 0;
        LumaAccountData.clearOnLogout(0, owner);
        check(own.getAll().isEmpty(), "actual identity logout clears amount/date/digits");
        Map<String, ?> signedOutBefore = named("luma_logged_out_0").getAll();
        LumaAnonymousNumber.setEnabled(0, true);
        LumaAnonymousNumber.setDigits(0, "11111111");
        check(!LumaAnonymousNumber.setPriceTon(0, owner, "10"), "post-logout amount rejected");
        check(!LumaAnonymousNumber.setPurchaseDate(0, owner, 60), "post-logout date rejected");
        LumaAnonymousNumber.getPurchaseDate(0);
        equal(LumaAnonymousNumber.getPriceTon(0), "0", "logged-out amount default");
        check(!LumaAnonymousNumber.isEnabled(0), "logged-out feature disabled");
        equal(named("luma_logged_out_0").getAll(), signedOutBefore, "signed-out getters and setters do not persist model");
        UserConfig.ids[0] = owner;
        equal(LumaAnonymousNumber.getPriceTon(0), "0", "new login starts without cleared local model");
    }

    public static void main(String[] args) {
        amounts();
        edition(false, false, 11001);
        edition(false, true, 12001);
        edition(true, false, 13001);
        System.out.println("PASS: " + checks + " anonymous TON/date/conversion checks with actual model, identity preferences and three build policies.");
    }
}
