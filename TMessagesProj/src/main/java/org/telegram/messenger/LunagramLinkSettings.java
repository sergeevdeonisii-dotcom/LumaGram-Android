package org.telegram.messenger;

import org.json.JSONArray;
import java.util.HashSet;

/** Encrypted, per-owner privacy preference. Expiry never silently re-enables network previews. */
public final class LunagramLinkSettings {
    private static final String KEY = "lunagram_no_link_previews_v1";
    private static final State[] states = new State[UserConfig.MAX_ACCOUNT_COUNT];
    private static class State {
        final LunagramMediaAccess.Session session;
        final long owner;
        final HashSet<Long> disabled = new HashSet<>();
        boolean unreadable;
        State(int account) { owner = UserConfig.getInstance(account).getClientUserId(); session = LunagramMediaAccess.forAccount(account); }
    }
    private LunagramLinkSettings() {}
    private static State state(int account) throws Exception {
        if (account < 0 || account >= states.length) throw new IllegalArgumentException();
        State value = states[account];
        if (value != null && value.session.isCurrent()) return value;
        value = new State(account);
        if (!value.session.isCurrent() || value.owner != UserConfig.getInstance(account).getClientUserId()) throw new IllegalStateException();
        try {
            String raw = BlackHolePrivateData.read(account, KEY, "[]");
            if (raw.length() > 8192) throw new IllegalArgumentException();
            JSONArray data = new JSONArray(raw);
            if (data.length() > 256) throw new IllegalArgumentException();
            for (int i = 0; i < data.length(); i++) {
                long dialog = data.getLong(i);
                if (dialog == 0 || DialogObject.isEncryptedDialog(dialog)) throw new IllegalArgumentException();
                value.disabled.add(dialog);
            }
        } catch (Exception error) { value.unreadable = true; }
        if (!value.session.isCurrent()) throw new IllegalStateException();
        states[account] = value;
        return value;
    }
    public static synchronized boolean previewDisabled(int account, long dialog) {
        try { State value = state(account); return value.unreadable || value.disabled.contains(dialog); }
        catch (Exception error) { return true; } // Fail private: never disclose text because a preference could not be read.
    }
    public static synchronized boolean setPreviewDisabled(int account, long dialog, boolean disabled) {
        try {
            State value = state(account);
            if (value.unreadable || account != UserConfig.selectedAccount || !value.session.isCurrent()
                    || SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter
                    || dialog == 0 || DialogObject.isEncryptedDialog(dialog) || BlackHoleVault.blocks(account, dialog)
                    || disabled && !value.session.canUse(LunagramFeatureCatalog.Feature.LINK_TOOLS)) return false;
            HashSet<Long> next = new HashSet<>(value.disabled);
            if (disabled) next.add(dialog); else next.remove(dialog);
            if (next.size() > 256) return false;
            JSONArray data = new JSONArray(); for (long id : next) data.put(id);
            if (!value.session.isCurrent()) return false;
            BlackHolePrivateData.write(account, value.owner, KEY, data.toString());
            if (!value.session.isCurrent()) return false;
            value.disabled.clear(); value.disabled.addAll(next);
            return true;
        } catch (Exception error) { return false; }
    }
    public static synchronized void clearOnLogout(int account) {
        if (account >= 0 && account < states.length) states[account] = null;
    }
}
