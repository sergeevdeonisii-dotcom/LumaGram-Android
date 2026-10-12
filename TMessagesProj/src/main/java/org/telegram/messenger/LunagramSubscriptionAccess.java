package org.telegram.messenger;

import android.os.SystemClock;
import android.widget.Toast;

/** Public-edition authority. Preferences are never proof of purchase and are never erased on expiry. */
public final class LunagramSubscriptionAccess {
    private static final Object LOCK = new Object();
    private static final Entry[] ENTRIES = new Entry[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long REFRESH_MS = 15 * 60 * 1000L;
    private static final long RETRY_MS = 60 * 1000L;
    static { LunagramSubscriptionClient.setStateObserver(LunagramSubscriptionAccess::observe); }

    private static final class Entry {
        final int account;
        final long owner, epoch;
        LunagramEntitlementLease lease;
        LunagramSubscriptionClient client;
        LunagramFeatureCatalog.Tier lastTier = LunagramFeatureCatalog.Tier.FREE;
        long lastAttempt = -REFRESH_MS, pendingExpiry;
        boolean queued, restoring, privacyRevoked, authorizationRevoked;
        final Runnable tick;
        Entry(int account, long owner, long epoch) {
            this.account = account; this.owner = owner; this.epoch = epoch;
            tick = () -> tick(this);
        }
    }

    private LunagramSubscriptionAccess() {}

    public static boolean canUse(int account, LunagramFeatureCatalog.Feature feature) {
        if (feature == null) return false;
        if (LumaBuildPolicy.isPersonalEdition() || LunagramFeatureCatalog.isBaselineFree(feature)) return true;
        Entry entry = entry(account);
        if (entry == null) return false;
        LunagramFeatureCatalog.Tier tier;
        synchronized (LOCK) { tier = tier(entry); }
        request(entry, false);
        return LunagramFeatureCatalog.includes(tier, feature);
    }

    public static void refresh(int account) {
        if (LumaBuildPolicy.isPersonalEdition()) return;
        Entry entry = entry(account);
        if (entry != null) {
            request(entry, true);
            AndroidUtilities.runOnUIThread(() -> tick(entry));
        }
    }

    public static boolean isChecking(int account) {
        Entry entry = entry(account);
        if (entry == null || LumaBuildPolicy.isPersonalEdition()) return false;
        LunagramSubscriptionClient client;
        synchronized (LOCK) {
            if (entry.queued || entry.restoring) return true;
            client = entry.client;
        }
        return client != null && client.isBusy();
    }

    /**
     * A fail-closed privacy barrier, not paid access. Only callers with an already
     * armed privacy preference may use this to suppress outgoing activity while
     * authority is unknown. It must never enable a setting, scheduling or UI action.
     */
    public static boolean shouldProtectExistingPrivacy(int account) {
        if (LumaBuildPolicy.isPersonalEdition()) return false;
        Entry entry = entry(account);
        if (entry == null) return false;
        request(entry, false);
        synchronized (LOCK) {
            if (!current(entry) || entry.privacyRevoked) return false;
            if (entry.lease == null) return true;
            long now = SystemClock.elapsedRealtime();
            if (entry.lease.serverNow(now) < 0) return true;
            // Confirmed free, lower tier or known expiry releases the barrier.
            // A stale but still unexpired prior Ultra interval stays private,
            // without authorizing any paid action after its offline lease ends.
            return (entry.authorizationRevoked || entry.lease.tier(now) == LunagramFeatureCatalog.Tier.FREE)
                    && entry.lease.observedTier(now) == LunagramFeatureCatalog.Tier.ULTRA;
        }
    }

    private static Entry entry(int account) {
        if (account < 0 || account >= ENTRIES.length) return null;
        long owner = UserConfig.getInstance(account).getClientUserId();
        long epoch = LunagramSubscriptionClient.accountEpoch(account);
        synchronized (LOCK) {
            Entry old = ENTRIES[account];
            if (old != null && (old.owner != owner || old.epoch != epoch)) {
                ENTRIES[account] = null;
                AndroidUtilities.runOnUIThread(() -> {
                    AndroidUtilities.cancelRunOnUIThread(old.tick);
                    if (old.client != null) old.client.dispose();
                });
            }
            if (owner <= 0) return null;
            if (ENTRIES[account] == null) ENTRIES[account] = new Entry(account, owner, epoch);
            return ENTRIES[account];
        }
    }

    private static boolean current(Entry entry) {
        return ENTRIES[entry.account] == entry
                && UserConfig.getInstance(entry.account).getClientUserId() == entry.owner
                && LunagramSubscriptionClient.accountEpoch(entry.account) == entry.epoch;
    }

    private static LunagramFeatureCatalog.Tier tier(Entry entry) {
        return current(entry) && !entry.authorizationRevoked && entry.lease != null ? entry.lease.tier(SystemClock.elapsedRealtime())
                : LunagramFeatureCatalog.Tier.FREE;
    }

    private static void request(Entry entry, boolean force) {
        synchronized (LOCK) {
            long delay = force ? RETRY_MS : REFRESH_MS;
            if (!current(entry) || entry.queued || entry.restoring
                    || UserConfig.selectedAccount != entry.account
                    || SystemClock.elapsedRealtime() - entry.lastAttempt < delay) return;
            entry.queued = true;
        }
        // Never do I/O, Keystore reads, or create a network client in a rendering getter.
        AndroidUtilities.runOnUIThread(() -> startRefresh(entry), 1);
    }

    private static void startRefresh(Entry entry) {
        synchronized (LOCK) {
            entry.queued = false;
            if (!current(entry) || UserConfig.selectedAccount != entry.account) return;
            entry.lastAttempt = SystemClock.elapsedRealtime();
        }
        if (entry.client != null && !entry.client.isAccountCurrent()) {
            entry.client.dispose(); entry.client = null;
        }
        if (entry.client == null) {
            entry.client = new LunagramSubscriptionClient(entry.account, () -> restored(entry));
        }
        if (entry.client.isBusy()) return;
        if (!entry.client.isAuthenticated()) {
            synchronized (LOCK) { entry.restoring = true; }
            entry.client.restore();
        } else entry.client.refresh();
    }

    private static void restored(Entry entry) {
        boolean refresh;
        synchronized (LOCK) {
            if (!current(entry) || !entry.restoring || entry.client.isBusy()) return;
            entry.restoring = false;
            refresh = entry.client.isAuthenticated();
        }
        if (refresh) entry.client.refresh();
    }

    private static void observe(LunagramSubscriptionClient source, LunagramSubscriptionClient.AccessSnapshot state) {
        if (LumaBuildPolicy.isPersonalEdition()) return;
        Entry entry = entry(state.account);
        if (entry == null || entry.owner != state.owner || entry.epoch != state.epoch) return;
        synchronized (LOCK) {
            if (!current(entry)) return;
            if (entry.client != null && entry.client != source && !entry.client.isAccountCurrent()) {
                LunagramSubscriptionClient stale = entry.client;
                entry.client = null; entry.restoring = false; entry.queued = false;
                AndroidUtilities.runOnUIThread(stale::dispose);
            }
            if (state.revoked) {
                entry.authorizationRevoked = true;
                entry.privacyRevoked = state.explicitDisconnect;
                if (state.explicitDisconnect) entry.lease = null;
                if (entry.client != null && entry.client != source) {
                    LunagramSubscriptionClient stale = entry.client;
                    entry.client = null; entry.restoring = false;
                    AndroidUtilities.runOnUIThread(stale::dispose);
                }
            } else if (state.state != null) {
                LunagramEntitlementLease old = entry.lease;
                if (old != null && (state.state.entitlementVersion < old.state.entitlementVersion
                        || state.state.serverNowMs < old.state.serverNowMs)) return;
                if (old != null && old.ended(SystemClock.elapsedRealtime())) entry.pendingExpiry = old.state.paidUntilMs;
                entry.privacyRevoked = false;
                entry.authorizationRevoked = false;
                entry.lease = new LunagramEntitlementLease(state.state, state.receivedElapsed);
                if (state.confirmedEndedAt > 0) entry.pendingExpiry = state.confirmedEndedAt;
            }
            if (state.state == null && state.privacyHistory != null && entry.lease == null && !entry.privacyRevoked) {
                // Same-boot encrypted history without a session informs only privacy
                // and known expiry. It can never grant paid access on its own.
                entry.lease = new LunagramEntitlementLease(state.privacyHistory, state.receivedElapsed);
                entry.authorizationRevoked = true;
            }
            // NETWORK/BAD_RESPONSE with no trusted state never revokes an existing lease.
        }
        tick(entry);
    }

    private static void tick(Entry entry) {
        AndroidUtilities.cancelRunOnUIThread(entry.tick);
        boolean changed;
        long expiry, next = REFRESH_MS;
        synchronized (LOCK) {
            if (!current(entry)) return;
            long now = SystemClock.elapsedRealtime();
            LunagramFeatureCatalog.Tier tier = tier(entry);
            changed = tier != entry.lastTier;
            entry.lastTier = tier;
            if (entry.lease != null) {
                if (entry.lease.ended(now)) entry.pendingExpiry = entry.lease.state.paidUntilMs;
                long boundary = entry.lease.nextBoundary(now);
                if (boundary > now) next = Math.min(next, boundary - now);
            }
            expiry = tier == LunagramFeatureCatalog.Tier.FREE ? entry.pendingExpiry : 0;
        }
        if (expiry > 0 && !ApplicationLoader.mainInterfacePaused && UserConfig.selectedAccount == entry.account) {
            android.content.SharedPreferences settings = MessagesController.getMainSettings(entry.account);
            if (settings.getLong("lunagram_expiry_warning_owner", 0) != entry.owner
                    || settings.getLong("lunagram_expiry_warning_until", 0) != expiry) {
                Toast.makeText(ApplicationLoader.applicationContext,
                        LocaleController.getString(R.string.LunagramSubscriptionAccessExpired), Toast.LENGTH_LONG).show();
                settings.edit().putLong("lunagram_expiry_warning_owner", entry.owner)
                        .putLong("lunagram_expiry_warning_until", expiry).apply();
            }
        }
        if (changed) {
            MessagesController.getInstance(entry.account).setLumaGhostModeEnabled(LumaGhostMode.shouldSuppressActivity(entry.account));
            DownloadController.getInstance(entry.account).checkAutodownloadSettings();
            NotificationCenter.getInstance(entry.account).postNotificationName(NotificationCenter.lunagramSubscriptionChanged);
            NotificationCenter.getInstance(entry.account).postNotificationName(NotificationCenter.updateInterfaces,
                    MessagesController.UPDATE_MASK_ALL);
        }
        if (!ApplicationLoader.mainInterfacePaused) request(entry, false);
        AndroidUtilities.runOnUIThread(entry.tick, Math.max(1000, next));
    }
}
