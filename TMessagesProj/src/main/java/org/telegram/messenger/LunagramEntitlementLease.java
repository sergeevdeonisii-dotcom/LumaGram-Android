package org.telegram.messenger;

/** Pure bounded authorization from an authenticated server observation, never a selected plan. */
final class LunagramEntitlementLease {
    static final long OFFLINE_LIMIT_MS = 24 * 60 * 60 * 1000L;
    final LunagramSubscriptionClient.Subscription state;
    final long receivedElapsed;

    LunagramEntitlementLease(LunagramSubscriptionClient.Subscription state, long receivedElapsed) {
        this.state = state;
        this.receivedElapsed = receivedElapsed;
    }

    long serverNow(long elapsed) {
        if (elapsed < receivedElapsed) return -1;
        return state.serverNowMs + elapsed - receivedElapsed;
    }

    LunagramFeatureCatalog.Tier tier(long elapsed) {
        long now = serverNow(elapsed);
        if (!state.featuresGated || now < 0 || elapsed - receivedElapsed >= OFFLINE_LIMIT_MS)
            return LunagramFeatureCatalog.Tier.FREE;
        return observedTier(elapsed);
    }

    /** Historical interval only, never a grant beyond the bounded offline lease. */
    LunagramFeatureCatalog.Tier observedTier(long elapsed) {
        long now = serverNow(elapsed);
        if (!state.featuresGated || now < 0) return LunagramFeatureCatalog.Tier.FREE;
        if (state.legacy && now < state.legacyExpiresAtMs)
            return knownTier(state.planId);
        if (contains(state.current, now)) return knownTier(state.current.planId);
        // Fixed immutable intervals are signed-in server facts, not a local renewal.
        for (LunagramSubscriptionClient.Term term : state.queued)
            if (contains(term, now)) return knownTier(term.planId);
        return LunagramFeatureCatalog.Tier.FREE;
    }

    boolean ended(long elapsed) {
        long now = serverNow(elapsed);
        return now >= 0 && state.paidUntilMs > 0 && now >= state.paidUntilMs;
    }

    long nextBoundary(long elapsed) {
        long now = serverNow(elapsed);
        long cap = receivedElapsed + OFFLINE_LIMIT_MS;
        long next = cap > elapsed ? cap : Long.MAX_VALUE;
        if (now < 0) return elapsed;
        if (state.legacy && state.legacyExpiresAtMs > now)
            next = Math.min(next, elapsed + state.legacyExpiresAtMs - now);
        if (state.current != null && state.current.expiresAtMs > now)
            next = Math.min(next, elapsed + state.current.expiresAtMs - now);
        for (LunagramSubscriptionClient.Term term : state.queued) {
            if (term.startsAtMs > now) next = Math.min(next, elapsed + term.startsAtMs - now);
            if (term.expiresAtMs > now) next = Math.min(next, elapsed + term.expiresAtMs - now);
        }
        return next;
    }

    private static boolean contains(LunagramSubscriptionClient.Term term, long now) {
        return term != null && term.startsAtMs <= now && now < term.expiresAtMs;
    }

    private static LunagramFeatureCatalog.Tier knownTier(String id) {
        LunagramFeatureCatalog.Tier tier = LunagramFeatureCatalog.tierFromId(id);
        return tier == null ? LunagramFeatureCatalog.Tier.FREE : tier;
    }
}
