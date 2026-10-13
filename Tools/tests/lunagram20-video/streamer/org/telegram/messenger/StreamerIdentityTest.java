package org.telegram.messenger;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import org.telegram.tgnet.TLRPC;

public final class StreamerIdentityTest {
    private static int checks;
    private static void check(boolean actual, String description) {
        checks++; if (!actual) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        UserConfig.ids[0] = 101;
        UserConfig.ids[1] = 202;
        check(!LunagramStreamerMode.isEnabled(0), "new identity is off");
        LunagramStreamerMode.setEnabled(0, true);
        check(LunagramStreamerMode.isEnabled(0), "toggle is immediately visible");
        check(!LunagramStreamerMode.isEnabled(1), "other account remains off");
        check(NotificationCenter.updates == 1 && NotificationCenter.reloads == 1, "cached UI invalidation");
        TLRPC.User user = new TLRPC.User();
        user.first_name = "Original"; user.last_name = "Name"; user.username = "original_user"; user.phone = "123456";
        TLRPC.Chat chat = new TLRPC.Chat(); chat.title = "Original chat"; chat.username = "original_chat";
        check("Hidden contact".equals(LunagramStreamerMode.displayName(0, user)), "user rendering mask");
        check("Hidden chat".equals(LunagramStreamerMode.displayName(0, chat)), "chat rendering mask");
        check("Hidden contact".contentEquals(LunagramStreamerMode.displayName(0, "custom name", false)), "custom rendering mask");
        check("Original Name".equals(LunagramStreamerMode.displayName(1, user)), "other account original name");
        check("Original chat".equals(LunagramStreamerMode.displayName(1, chat)), "other account original title");
        check("Original".equals(user.first_name) && "Name".equals(user.last_name)
                && "original_user".equals(user.username) && "123456".equals(user.phone), "user protocol data untouched");
        check("Original chat".equals(chat.title) && "original_chat".equals(chat.username), "chat protocol data untouched");
        UserConfig.ids[0] = 303;
        check(!LunagramStreamerMode.isEnabled(0), "replacement identity cannot inherit slot cache");
        LunagramStreamerMode.setEnabled(0, true);
        UserConfig.ids[0] = 0;
        check(!LunagramStreamerMode.isEnabled(0), "logout drops identity cache");
        UserConfig.ids[0] = 101;
        check(LunagramStreamerMode.isEnabled(0), "same identity uses own preferences");
        LumaAccountData.clear(101);
        check(!LunagramStreamerMode.isEnabled(0), "logout clear observed without intermediate draw or different ID");
        UserConfig.ids[2] = 303;
        check(LunagramStreamerMode.isEnabled(2), "identity preference follows a different login slot");
        UserConfig.selectedAccount = 2;
        check(LunagramStreamerMode.isRenderingEnabled(), "selected-account rendering scope");
        LunagramStreamerMode.setEnabled(2, false);
        check(!LunagramStreamerMode.isEnabled(0) && !LunagramStreamerMode.isEnabled(2), "disable does not leak across identities");
        check(!LunagramStreamerMode.isEnabled(-1) && !LunagramStreamerMode.isEnabled(4), "invalid accounts are safe");
        CharSequence original = new StringBuilder("rich custom label");
        check(original == LunagramStreamerMode.displayName(2, original, false), "disabled custom label retains object/spans");
        check("".equals(LunagramStreamerMode.displayName(2, (TLRPC.User)null)), "disabled null user");
        LunagramStreamerMode.setEnabled(2,true);
        LunagramSubscriptionAccess.allowed=false;LunagramSubscriptionAccess.protect=true;
        check(LunagramStreamerMode.isEnabled(2),"unknown entitlement protects previously armed privacy");
        LunagramStreamerMode.setEnabled(0,true);check(!LunagramStreamerMode.isEnabled(0),"unknown privacy does not grant new paid toggle");
        LunagramSubscriptionAccess.protect=false;
        check(!LunagramStreamerMode.isEnabled(2),"confirmed expiry disables paid rendering");
        check(LumaAccountData.preferences(2).getBoolean("lunagram_streamer_mode",false),"expiry preserves saved mask setting");
        LunagramSubscriptionAccess.allowed=true;check(LunagramStreamerMode.isEnabled(2),"renewal restores saved mask");
        LunagramSubscriptionAccess.allowed=false;LunagramSubscriptionAccess.protect=true;LunagramStreamerMode.setEnabled(2,false);
        check(!LunagramStreamerMode.isEnabled(2),"explicit disable available without paid grant");
        System.out.println("PASS " + checks + " production streamer identity/render-only checks (Android collaborators modeled)");
    }
}
final class LunagramSubscriptionAccess {
    static boolean allowed=true,protect;
    static boolean canUse(int account,LunagramFeatureCatalog.Feature feature){return allowed;}
    static boolean shouldProtectExistingPrivacy(int account){return protect;}
}
final class UserConfig {
    static final int MAX_ACCOUNT_COUNT = 4;
    static final long[] ids = new long[4];
    static int selectedAccount;
    private final int account;
    private UserConfig(int account) { this.account = account; }
    static UserConfig getInstance(int account) { return new UserConfig(account); }
    long getClientUserId() { return ids[account]; }
}
final class LumaAccountData {
    private static final Map<String, Prefs> identities = new HashMap<>();
    static SharedPreferences preferences(int account) {
        long id = UserConfig.ids[account];
        return identities.computeIfAbsent(id > 0 ? "user:" + id : "logged-out:" + account, key -> new Prefs());
    }
    static void clear(long id) { Prefs prefs = identities.get("user:" + id); if (prefs != null) prefs.values.clear(); }
    static final class Prefs implements SharedPreferences, SharedPreferences.Editor {
        final Map<String, Boolean> values = new HashMap<>();
        public boolean getBoolean(String key, boolean fallback) { return values.getOrDefault(key, fallback); }
        public Editor edit() { return this; }
        public Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
        public void apply() {}
    }
}
final class AndroidUtilities { static void runOnUIThread(Runnable callback) { callback.run(); } }
final class MessagesController { static final int UPDATE_MASK_ALL = 0xffff; }
final class NotificationCenter {
    static final int updateInterfaces = 1, dialogsNeedReload = 2;
    static int updates, reloads;
    static NotificationCenter getInstance(int account) { return new NotificationCenter(); }
    void postNotificationName(int id, Object... args) { if (id == updateInterfaces) updates++; if (id == dialogsNeedReload) reloads++; }
}
final class R { static final class string { static final int LunagramHiddenChat = 1, LunagramHiddenContact = 2; } }
final class LocaleController { static String getString(int id) { return id == R.string.LunagramHiddenChat ? "Hidden chat" : "Hidden contact"; } }
final class UserObject { static String getUserName(TLRPC.User user) { return user.first_name + " " + user.last_name; } }
