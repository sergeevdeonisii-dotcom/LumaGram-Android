package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

/** Read-only presentation of the server status for this account's ghost profile. */
public final class LumaGhostProfileStatus {
    private LumaGhostProfileStatus() {
    }

    /** Null leaves the ordinary profile branch unchanged. No status or account data is cached. */
    public static String format(int account, TLRPC.User user, int serverTime,
                                boolean[] isOnline, boolean[] madeShorter) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || user == null
                || user.id <= 0 || user.id != UserConfig.getInstance(account).getClientUserId()
                || !LumaGhostMode.isEnabled(account)) {
            return null;
        }
        if (isOnline != null && isOnline.length > 0) isOnline[0] = false;
        if (madeShorter != null && madeShorter.length > 0) madeShorter[0] = false;
        final TLRPC.UserStatus status = user.status;
        // Do not use formatUserStatus(): onlinePrivacy may infer activity and it
        // mutates fuzzy status.expires. Only concrete server status is evidence.
        if (status instanceof TLRPC.TL_userStatusOnline) {
            if (serverTime > 0 && status.expires > serverTime) {
                if (isOnline != null && isOnline.length > 0) isOnline[0] = true;
                // Another signed-in device may legitimately publish online.
                return LocaleController.getString(R.string.Online);
            }
            // An online expiry is NOT a last-seen timestamp.
        } else if (status instanceof TLRPC.TL_userStatusOffline) {
            if (serverTime > 0 && status.expires > 0 && status.expires <= serverTime) {
                return LocaleController.formatDateOnline(status.expires,
                        madeShorter != null && madeShorter.length > 0 ? madeShorter : null);
            }
        } else if (status instanceof TLRPC.TL_userStatusRecently) {
            return LocaleController.getString(R.string.Lately);
        } else if (status instanceof TLRPC.TL_userStatusLastWeek) {
            return LocaleController.getString(R.string.WithinAWeek);
        } else if (status instanceof TLRPC.TL_userStatusLastMonth) {
            return LocaleController.getString(R.string.WithinAMonth);
        } else if (status instanceof TLRPC.TL_userStatusHidden) {
            return LocaleController.getString(R.string.Invisible);
        }
        // Missing/private/expired data must not invent "last seen 2 hours ago"
        // or claim offline forever. This describes only the local mode.
        return LocaleController.getString(R.string.ExperimentalGhostEnabled);
    }
}
