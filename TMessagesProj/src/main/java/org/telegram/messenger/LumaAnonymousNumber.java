package org.telegram.messenger;

import android.content.SharedPreferences;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Local-only visual override for a Fragment-style anonymous Telegram number.
 * It never changes the account phone number or sends a request to Telegram.
 */
public final class LumaAnonymousNumber {

    private static final String KEY_ENABLED = "luma_anonymous_number_enabled_";
    private static final String KEY_DIGITS = "luma_anonymous_number_digits_";
    private static final String KEY_PRICE_TON = "luma_anonymous_number_price_ton";
    private static final String KEY_PURCHASE_DATE = "luma_anonymous_number_purchase_date";
    private static final String DEFAULT_PRICE_TON = "0";
    private static final int DIGITS_LENGTH = 8;
    private static final String DEFAULT_DIGITS = "00000000";

    private LumaAnonymousNumber() {
    }

    private static SharedPreferences preferences(int account) {
        return LumaAccountData.preferences(account);
    }

    public static boolean isEnabled(int account) {
        return LumaBuildPolicy.allowsAnonymousNumber()
                && UserConfig.getInstance(account).getClientUserId() > 0
                && preferences(account).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(int account, boolean enabled) {
        if (!LumaBuildPolicy.allowsAnonymousNumber() || UserConfig.getInstance(account).getClientUserId() <= 0) return;
        preferences(account).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static String getDigits(int account) {
        return normalize(preferences(account).getString(KEY_DIGITS, DEFAULT_DIGITS));
    }

    public static void setDigits(int account, String digits) {
        if (!LumaBuildPolicy.allowsAnonymousNumber() || UserConfig.getInstance(account).getClientUserId() <= 0) return;
        preferences(account).edit().putString(KEY_DIGITS, normalize(digits)).apply();
    }

    public static String getPhone(int account) {
        return "888" + getDigits(account);
    }

    /** User-authored display amount only, never a market quote or payment parameter. */
    public static String normalizePriceTon(String value) {
        if (value == null) return null;
        // Only ASCII spaces may surround the value; all digits and punctuation are ASCII.
        if (value.length() > 64) return null;
        int start = 0, end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        value = value.substring(start, end);
        if (value.length() > 19 || !value.matches("[0-9]{1,9}([.,][0-9]{1,9})?")) return null;
        return new BigDecimal(value.replace(',', '.')).stripTrailingZeros().toPlainString();
    }

    private static SharedPreferences ownerPreferences(int account, long expectedOwnerId) {
        if (!LumaBuildPolicy.allowsAnonymousNumber() || expectedOwnerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != expectedOwnerId) return null;
        SharedPreferences result = preferences(account);
        return UserConfig.getInstance(account).getClientUserId() == expectedOwnerId ? result : null;
    }

    public static String getPriceTon(int account) {
        SharedPreferences prefs = ownerPreferences(account, UserConfig.getInstance(account).getClientUserId());
        if (prefs == null) return DEFAULT_PRICE_TON;
        String value = normalizePriceTon(prefs.getString(KEY_PRICE_TON, DEFAULT_PRICE_TON));
        return value == null ? DEFAULT_PRICE_TON : value;
    }

    public static long getPriceNanoTon(int account) {
        return new BigDecimal(getPriceTon(account)).movePointRight(9).longValueExact();
    }

    public static String getFormattedPriceTon(int account) {
        return getPriceTon(account) + " TON";
    }

    public static boolean setPriceTon(int account, long expectedOwnerId, String value) {
        String price = normalizePriceTon(value);
        if (price == null) return false;
        SharedPreferences prefs = ownerPreferences(account, expectedOwnerId);
        if (prefs == null) return false;
        prefs.edit().putString(KEY_PRICE_TON, price).apply();
        return true;
    }

    private static int currentDate() {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, System.currentTimeMillis() / 1000L));
    }

    /** The initial user-editable preview date is stable once this identity enables the number. */
    public static synchronized int getPurchaseDate(int account) {
        final int now = currentDate();
        final int initialDate = now < 60 ? now : now - now % 60;
        final long owner = UserConfig.getInstance(account).getClientUserId();
        SharedPreferences prefs = ownerPreferences(account, owner);
        if (prefs == null) return initialDate;
        int date = prefs.getInt(KEY_PURCHASE_DATE, 0);
        if (date > 0 && date <= now) return date;
        if (prefs.getBoolean(KEY_ENABLED, false) && UserConfig.getInstance(account).getClientUserId() == owner) {
            prefs.edit().putInt(KEY_PURCHASE_DATE, initialDate).apply();
        }
        return initialDate;
    }

    public static synchronized boolean setPurchaseDate(int account, long expectedOwnerId, long seconds) {
        if (seconds <= 0 || seconds > Integer.MAX_VALUE || seconds > currentDate()) return false;
        SharedPreferences prefs = ownerPreferences(account, expectedOwnerId);
        if (prefs == null) return false;
        prefs.edit().putInt(KEY_PURCHASE_DATE, (int) seconds).apply();
        return true;
    }

    /** Pure display conversion using Telegram's supplied rate, never a quote or a network request. */
    public static long getUsdCents(long nanoTon, double tonUsdRate) {
        if (nanoTon < 0 || Double.isNaN(tonUsdRate) || Double.isInfinite(tonUsdRate) || tonUsdRate <= 0) return -1;
        try {
            return BigDecimal.valueOf(nanoTon, 9).multiply(BigDecimal.valueOf(tonUsdRate))
                    .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException ignored) {
            return -1;
        }
    }

    public static String getDisplayPhone(int account, long userId, String actualPhone) {
        if (isEnabled(account) && userId == UserConfig.getInstance(account).getClientUserId()) {
            return getPhone(account);
        }
        return actualPhone;
    }

    private static String normalize(String digits) {
        StringBuilder result = new StringBuilder(DIGITS_LENGTH);
        if (digits != null) {
            for (int i = 0; i < digits.length() && result.length() < DIGITS_LENGTH; i++) {
                char c = digits.charAt(i);
                if (c >= '0' && c <= '9') {
                    result.append(c);
                }
            }
        }
        while (result.length() < DIGITS_LENGTH) {
            result.append('0');
        }
        return result.toString();
    }
}
