package org.telegram.messenger;

import android.content.SharedPreferences;
import java.util.ArrayList;

/** Real journal helper against isolated preferences/JSON/Keystore models, not Android UI. */
public final class Journal531RegressionTest {
    private static final String ENABLED = "bhg_notification_journal_enabled";
    private static int assertions;

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        UserConfig.ids[0] = 53101;
        UserConfig.ids[1] = 53102;
        SharedPreferences prefs = LumaAccountData.preferences(0);
        check(!BlackHoleNotificationJournal.isEnabled(0), "Empty history preference remains disabled");
        BlackHoleNotificationJournal.setEnabled(0, true);
        check(BlackHoleNotificationJournal.isEnabled(0), "Explicit boolean true enabled");
        check(!BlackHoleNotificationJournal.isEnabled(1), "Preference is account-identity isolated");
        BlackHoleNotificationJournal.setEnabled(0, false);
        check(!BlackHoleNotificationJournal.isEnabled(0), "Explicit boolean false disabled");
        prefs.edit().putString(ENABLED, "true").apply();
        boolean typedGetterThrew = false;
        try { prefs.getBoolean(ENABLED, false); } catch (ClassCastException expected) { typedGetterThrew = true; }
        check(typedGetterThrew, "Previous typed-getter behavior reproduces UI-thread ClassCastException");
        check(!BlackHoleNotificationJournal.isEnabled(0), "String-valued flag does not crash or implicitly opt in");
        check("true".equals(prefs.getString(ENABLED, "")), "Read does not rewrite malformed preference");
        prefs.edit().putInt(ENABLED, 1).apply();
        check(!BlackHoleNotificationJournal.isEnabled(0), "Integer flag stays disabled without crashing");
        prefs.edit().putLong(ENABLED, 1L).apply();
        check(!BlackHoleNotificationJournal.isEnabled(0), "Long flag stays disabled without crashing");
        BlackHoleNotificationJournal.setEnabled(0, true);
        check(BlackHoleNotificationJournal.isEnabled(0), "Explicit toggle repairs flag with a boolean");

        check(BlackHoleNotificationJournal.canRecordPreview(true, true, false, false, false, false),
                "Visible ordinary full-metadata preview is eligible");
        check(!BlackHoleNotificationJournal.canRecordPreview(false, true, false, false, false, false),
                "Hidden or passcode-redacted previews are not retained");
        check(!BlackHoleNotificationJournal.canRecordPreview(true, false, false, false, false, false),
                "FCM-only content without complete privacy metadata is not retained");
        check(!BlackHoleNotificationJournal.canRecordPreview(true, true, true, false, false, false),
                "Vault and emergency-excluded chats are not retained");
        check(!BlackHoleNotificationJournal.canRecordPreview(true, true, false, true, false, false),
                "Message and peer no-forward protection prevents retention");
        check(!BlackHoleNotificationJournal.canRecordPreview(true, true, false, false, true, false),
                "TTL messages and disappearing media are not retained");
        check(!BlackHoleNotificationJournal.canRecordPreview(true, true, false, false, false, true),
                "Secret, login-code, service and spoiler content is not retained");

        BlackHoleNotificationJournal.Entry empty = new BlackHoleNotificationJournal.Entry(42, 1, 1, null, null);
        check(empty.title.isEmpty() && empty.text.isEmpty(), "Null title and text safe for rendering");
        String emoji = "\ud83d\ude80";
        BlackHoleNotificationJournal.Entry split = new BlackHoleNotificationJournal.Entry(42, 2, 1,
                "x".repeat(199) + emoji, "x".repeat(1999) + emoji);
        check(split.title.length() == 199 && split.text.length() == 1999, "Truncation does not split UTF-16 surrogate pair");
        BlackHoleNotificationJournal.Entry complete = new BlackHoleNotificationJournal.Entry(42, 3, 1,
                "x".repeat(198) + emoji, "x".repeat(1998) + emoji);
        check(complete.title.length() == 200 && complete.text.length() == 2000,
                "Whole emoji exactly at limit retained");
        check(complete.title.endsWith(emoji) && complete.text.endsWith(emoji), "Complete terminal emoji unchanged");
        BlackHoleNotificationJournal.Entry ordinary = new BlackHoleNotificationJournal.Entry(42, 4, 1,
                "title", "one\ntwo");
        check("title".equals(ordinary.title) && "one\ntwo".equals(ordinary.text), "Normal content and line breaks preserved");
        check(BlackHoleNotificationJournal.entries(0).isEmpty(), "Enabled but empty journal loads without a record");
        ArrayList<BlackHoleNotificationJournal.Entry> batch = new ArrayList<>();
        batch.add(null);
        batch.add(new BlackHoleNotificationJournal.Entry(42, 5, System.currentTimeMillis(), null, null));
        BlackHoleNotificationJournal.record(0, UserConfig.ids[0] + 1, batch);
        check(BlackHoleNotificationJournal.entries(0).isEmpty(), "Stale posted-notification owner cannot write into a replacement account");
        BlackHoleNotificationJournal.record(0, batch);
        check(BlackHoleNotificationJournal.entries(0).size() == 1, "Null batch entry skipped, normalized record loads");
        BlackHoleNotificationJournal.record(0, batch);
        check(BlackHoleNotificationJournal.entries(0).size() == 1, "Repeated posts remain deduplicated");
        UserConfig.ids[0] = 0;
        check(BlackHoleNotificationJournal.entries(0).isEmpty(), "Logged-out account does not display old entries");
        System.out.println("PASS: " + assertions + " notification-journal preference/content assertions. Not the owner's crash reproduction or Android UI/Keystore verification.");
    }
}
