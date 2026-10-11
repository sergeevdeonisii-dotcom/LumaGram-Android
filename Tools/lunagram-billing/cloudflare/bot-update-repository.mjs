import { requireBilling, telegramUserId } from '../catalog.mjs';

const UPDATE_TTL_MS = 24 * 60 * 60 * 1000;
const LIMIT_TTL_MS = 2 * 60 * 1000;
const WINDOW_MS = 60 * 1000;
const CLEANUP_LIMIT = 100;
const USER_LIMIT = 5;
const GLOBAL_LIMIT = 60;

/**
 * Information-bot delivery guard, not billing/fulfillment storage.
 * Call only AFTER verifying the webhook secret, private sender and freshness.
 * All users of one bot must share ONE SQLite Durable Object in each environment.
 * Stores only update IDs, expiring private rate buckets and integer counters.
 */
export function createBotUpdateRepository(storage) {
    requireBilling(storage && typeof storage.transactionSync === 'function'
        && typeof storage.sql?.exec === 'function', 'SQLITE_STORAGE_REQUIRED');
    const rows = (sql, ...bindings) => Array.from(storage.sql.exec(sql, ...bindings));
    storage.transactionSync(() => {
        rows(`CREATE TABLE IF NOT EXISTS billing_bot_updates (
            update_id INTEGER PRIMARY KEY NOT NULL CHECK(update_id >= 0),
            expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms >= 0))`);
        rows(`CREATE INDEX IF NOT EXISTS billing_bot_updates_expiry
            ON billing_bot_updates(expires_at_ms, update_id)`);
        rows(`CREATE TABLE IF NOT EXISTS billing_bot_limits (
            bucket TEXT PRIMARY KEY NOT NULL,
            attempts INTEGER NOT NULL CHECK(attempts > 0),
            expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms >= 0))`);
        rows(`CREATE INDEX IF NOT EXISTS billing_bot_limits_expiry
            ON billing_bot_limits(expires_at_ms, bucket)`);
    });

    return Object.freeze({
        durable: true,
        async claim(updateId, userId, nowMs) {
            requireBilling(Number.isSafeInteger(updateId) && updateId >= 0
                && Number.isSafeInteger(nowMs) && nowMs >= 0
                && nowMs <= Number.MAX_SAFE_INTEGER - UPDATE_TTL_MS, 'INVALID_UPDATE');
            const verifiedUserId = telegramUserId(userId);
            return storage.transactionSync(() => {
                // Duplicates never change counters or extend retention. The caller
                // must reject old webhook messages before reaching this repository.
                const previous = rows('SELECT expires_at_ms FROM billing_bot_updates WHERE update_id = ?', updateId)[0];
                if (previous && previous.expires_at_ms > nowMs) {
                    return 'duplicate';
                }

                // An old update ID can eventually be reused by Telegram after a
                // long idle period. Only a freshly verified message may reclaim it.
                const reclaimed = previous
                    ? rows('DELETE FROM billing_bot_updates WHERE update_id = ? AND expires_at_ms <= ? RETURNING update_id', updateId, nowMs).length
                    : 0;

                // At most 100 expired rows TOTAL per first-seen update, using only
                // these dedicated indexes/tables. Never touch financial history.
                const removed = reclaimed + rows(`DELETE FROM billing_bot_updates WHERE update_id IN
                    (SELECT update_id FROM billing_bot_updates WHERE expires_at_ms <= ?
                     ORDER BY expires_at_ms, update_id LIMIT ?) RETURNING update_id`, nowMs, CLEANUP_LIMIT - reclaimed).length;
                if (removed < CLEANUP_LIMIT) {
                    rows(`DELETE FROM billing_bot_limits WHERE bucket IN
                        (SELECT bucket FROM billing_bot_limits WHERE expires_at_ms <= ?
                         ORDER BY expires_at_ms, bucket LIMIT ?) RETURNING bucket`, nowMs, CLEANUP_LIMIT - removed);
                }

                const minute = Math.floor(nowMs / WINDOW_MS);
                const consume = (bucket, limit) => {
                    const result = rows(`INSERT INTO billing_bot_limits(bucket, attempts, expires_at_ms) VALUES (?, 1, ?)
                        ON CONFLICT(bucket) DO UPDATE SET
                        attempts=MIN(billing_bot_limits.attempts + 1, ?),
                        expires_at_ms=MAX(billing_bot_limits.expires_at_ms, excluded.expires_at_ms)
                        RETURNING attempts`, bucket, nowMs + LIMIT_TTL_MS, limit + 1);
                    requireBilling(result.length === 1 && Number.isSafeInteger(result[0].attempts)
                        && result[0].attempts > 0, 'INVALID_RECORD');
                    return result[0].attempts <= limit;
                };
                // Counters saturate at limit+1. Both counters and the update claim
                // commit even when denied; no throw/rollback on a policy denial.
                const userAllowed = consume(`user:${verifiedUserId}:${minute}`, USER_LIMIT);
                const globalAllowed = consume(`global:${minute}`, GLOBAL_LIMIT);
                rows('INSERT INTO billing_bot_updates(update_id, expires_at_ms) VALUES (?, ?)', updateId, nowMs + UPDATE_TTL_MS);
                return userAllowed && globalAllowed ? 'claimed' : 'rate_limited';
            });
        },
    });
}
