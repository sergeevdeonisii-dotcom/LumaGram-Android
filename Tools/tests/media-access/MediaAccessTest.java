package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

public final class MediaAccessTest {
    static int checks;
    static void check(boolean value, String text) { checks++; if (!value) throw new AssertionError(text); }
    public static void main(String[] args) {
        UserConfig.users[0].owner = 10;
        UserConfig.users[1].owner = 20;
        LunagramSubscriptionAccess.allowed[0] = true;
        LunagramSubscriptionAccess.allowed[1] = false;
        LunagramMediaAccess.Session access = LunagramMediaAccess.forAccount(0);
        LunagramFeatureCatalog.Feature[] features = {
            LunagramFeatureCatalog.Feature.ROUND_VIDEO_DATE, LunagramFeatureCatalog.Feature.ROUND_VIDEO_FILTERS,
            LunagramFeatureCatalog.Feature.ROUND_VIDEO_PORTRAIT, LunagramFeatureCatalog.Feature.VOICE_STEREO,
            LunagramFeatureCatalog.Feature.VOICE_FILTERS, LunagramFeatureCatalog.Feature.VIDEO_MEMES
        };
        for (LunagramFeatureCatalog.Feature feature : features) {
            check(access.canUse(feature), "paid owner authorized " + feature);
            UserConfig.selectedAccount = 1;
            check(access.canUse(feature), "queued media remains bound to original account " + feature);
            check(LunagramSubscriptionAccess.lastAccount == 0, "never borrow selected account authority " + feature);
            LunagramSubscriptionAccess.allowed[0] = false; LunagramSubscriptionAccess.allowed[1] = true;
            check(!access.canUse(feature), "expiry cannot borrow another account subscription " + feature);
            LunagramSubscriptionAccess.allowed[0] = true;
            UserConfig.users[0].owner = 11;
            check(!access.canUse(feature), "replacement owner cannot revive queued work " + feature);
            UserConfig.users[0].owner = 10;
            LunagramSubscriptionClient.epochs[0]++;
            check(!access.canUse(feature), "new account epoch cannot revive old work " + feature);
            access = LunagramMediaAccess.forAccount(0);
        }
        check(!LunagramMediaAccess.forAccount(-1).canUse(features[0]), "negative account fails closed");
        check(!LunagramMediaAccess.forAccount(UserConfig.MAX_ACCOUNT_COUNT).canUse(features[0]), "oversized account fails closed");
        UserConfig.users[0].owner = 0;
        check(!access.canUse(features[0]), "logout invalidates current operation");
        check(!LunagramMediaAccess.forAccount(0).canUse(features[0]), "logged-out owner cannot create paid work");
        UserConfig.users[0].owner = 10; UserConfig.selectedAccount = 0;
        LunagramVideoEffectsSettings.setEffect(LunagramVideoEffectsSettings.VHS);
        LunagramVideoEffectsSettings.setDateEnabled(true);
        LunagramVideoEffectsSettings.setBokehEnabled(true);
        LunagramAudioSettings.setStereoEnabled(0, true);
        check(LunagramVideoEffectsSettings.getEffect(0) == LunagramVideoEffectsSettings.VHS, "paid stored effect used");
        check(LunagramVideoEffectsSettings.isDateEnabled(0) && LunagramVideoEffectsSettings.isBokehEnabled(0), "paid date/portrait enabled");
        check(LunagramAudioSettings.isStereoEnabled(0), "paid stereo enabled");
        LunagramSubscriptionAccess.allowed[0] = false;
        check(LunagramVideoEffectsSettings.getEffect(0) == LunagramVideoEffectsSettings.NONE, "expired video falls back to original");
        check(!LunagramVideoEffectsSettings.isDateEnabled(0) && !LunagramVideoEffectsSettings.isBokehEnabled(0), "expired date/portrait disabled");
        check(!LunagramVideoEffectsSettings.needsProcessedPreview(0), "free recording does not request paid processing");
        check(!LunagramAudioSettings.isStereoEnabled(0), "new expired recording uses mono");
        SharedPreferences saved = ApplicationLoader.applicationContext.getSharedPreferences("lunagram_video_effects", 0);
        check(saved.getInt("effect", 0) == LunagramVideoEffectsSettings.VHS && saved.getBoolean("date", false)
                && saved.getBoolean("bokeh", false), "expiry preserves video preferences");
        check(MessagesController.getGlobalMainSettings().getBoolean("lunagramStereoVoice", false), "expiry preserves stereo preference");
        LunagramSubscriptionAccess.allowed[0] = true;
        check(LunagramVideoEffectsSettings.getEffect(0) == LunagramVideoEffectsSettings.VHS && LunagramAudioSettings.isStereoEnabled(0), "valid renewal restores preferences");
        LunagramSubscriptionAccess.allowed[0] = false;
        LunagramAudioSettings.setStereoEnabled(0, false);
        LunagramAudioSettings.setStereoEnabled(0, true);
        check(!MessagesController.getGlobalMainSettings().getBoolean("lunagramStereoVoice", false), "free UI cannot enable stereo, explicit disable stays available");
        LunagramSubscriptionAccess.allowed[0] = true;
        access = LunagramMediaAccess.forAccount(0);
        LunagramSubscriptionAccess.onRead = () -> UserConfig.users[0].owner = 90;
        check(!access.canUse(features[0]), "owner changed while authority was checked cannot reuse a different owner grant");
        System.out.println("PASS: " + checks + " real media-account/settings checks with modeled subscription authority, not billing/hardware proof.");
    }
}
final class UserConfig {
    static final int MAX_ACCOUNT_COUNT = 3;
    static int selectedAccount;
    static final UserConfig[] users = {new UserConfig(), new UserConfig(), new UserConfig()};
    long owner;
    static UserConfig getInstance(int account) { return users[account]; }
    long getClientUserId() { return owner; }
}
final class LunagramSubscriptionClient {
    static final long[] epochs = new long[UserConfig.MAX_ACCOUNT_COUNT];
    static long accountEpoch(int account) { return epochs[account]; }
}
final class LunagramSubscriptionAccess {
    static final boolean[] allowed = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    static int lastAccount;
    static Runnable onRead;
    static boolean canUse(int account, LunagramFeatureCatalog.Feature feature) {
        lastAccount = account;
        if (onRead != null) { Runnable task = onRead; onRead = null; task.run(); }
        return feature != null && allowed[account];
    }
}
final class ApplicationLoader { static final Context applicationContext = new Context(); }
final class MessagesController {
    static SharedPreferences getGlobalMainSettings() { return ApplicationLoader.applicationContext.getSharedPreferences("main", 0); }
}
