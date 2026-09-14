package org.telegram.messenger;

import android.content.SharedPreferences;

/**
 * Local-only visual override for a Fragment-style anonymous Telegram number.
 * It never changes the account phone number or sends a request to Telegram.
 */
public final class LumaAnonymousNumber {

    private static final String KEY_ENABLED = "luma_anonymous_number_enabled_";
    private static final String KEY_DIGITS = "luma_anonymous_number_digits_";
    private static final int DIGITS_LENGTH = 8;
    private static final String DEFAULT_DIGITS = "00000000";

    private LumaAnonymousNumber() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled(int account) {
        return preferences().getBoolean(KEY_ENABLED + account, false);
    }

    public static void setEnabled(int account, boolean enabled) {
        preferences().edit().putBoolean(KEY_ENABLED + account, enabled).apply();
    }

    public static String getDigits(int account) {
        return normalize(preferences().getString(KEY_DIGITS + account, DEFAULT_DIGITS));
    }

    public static void setDigits(int account, String digits) {
        preferences().edit().putString(KEY_DIGITS + account, normalize(digits)).apply();
    }

    public static String getPhone(int account) {
        return "888" + getDigits(account);
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
