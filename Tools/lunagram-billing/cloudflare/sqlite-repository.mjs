import { requireBilling } from '../catalog.mjs';

const MAX_RECORD_BYTES = 16 * 1024;
const KEY_FIELDS = new Set(['hash', 'planId', 'orderId', 'revoked', 'redeemedBy', 'redeemedAtMs']);
const SCHEMA = [
    'CREATE TABLE IF NOT EXISTS billing_meta (name TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)',
    `CREATE TABLE IF NOT EXISTS billing_orders (
        id TEXT PRIMARY KEY NOT NULL, request_key TEXT NOT NULL UNIQUE, invoice_id TEXT UNIQUE,
        payload TEXT NOT NULL CHECK(json_valid(payload)))`,
    `CREATE TABLE IF NOT EXISTS billing_codes (
        hash TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL CHECK(json_valid(payload)))`,
    `CREATE TABLE IF NOT EXISTS billing_payments (
        event_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL CHECK(json_valid(payload)))`,
    `CREATE TABLE IF NOT EXISTS billing_entitlements (
        user_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL CHECK(json_valid(payload)))`,
    `CREATE TABLE IF NOT EXISTS billing_rate_limits (
        bucket TEXT PRIMARY KEY NOT NULL, attempts INTEGER NOT NULL CHECK(attempts > 0),
        expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms >= 0))`,
    'CREATE INDEX IF NOT EXISTS billing_rate_limits_expiry ON billing_rate_limits(expires_at_ms, bucket)',
];

function textKey(value, max = 512) {
    requireBilling(typeof value === 'string' && value.length > 0 && value.length <= max
        && !/[\u0000-\u001f\u007f]/.test(value), 'INVALID_RECORD');
    return value;
}
function integer(value, max = Number.MAX_SAFE_INTEGER) {
    requireBilling(Number.isSafeInteger(value) && value >= 0 && value <= max, 'INVALID_RECORD');
    return value;
}
function recordJson(value) {
    requireBilling(value && typeof value === 'object' && !Array.isArray(value), 'INVALID_RECORD');
    let json;
    try {
        json = JSON.stringify(value, (_key, item) => {
            requireBilling(typeof item !== 'number' || Number.isFinite(item), 'INVALID_RECORD');
            requireBilling(!['bigint', 'function', 'symbol'].includes(typeof item), 'INVALID_RECORD');
            return item;
        });
    } catch { requireBilling(false, 'INVALID_RECORD'); }
    requireBilling(typeof json === 'string' && new TextEncoder().encode(json).length <= MAX_RECORD_BYTES, 'INVALID_RECORD');
    return json;
}

/**
 * Pass ctx.storage from ONE SQLite-backed Durable Object per billing environment.
 * No network, async SQL, or arbitrary SQL is exposed by this repository.
 */
export function createSqliteRepository(storage) {
    requireBilling(storage && typeof storage.transactionSync === 'function'
        && typeof storage.sql?.exec === 'function', 'SQLITE_STORAGE_REQUIRED');
    const rows = (sql, ...bindings) => Array.from(storage.sql.exec(sql, ...bindings));
    storage.transactionSync(() => {
        for (const statement of SCHEMA) rows(statement);
        rows("INSERT INTO billing_meta(name, value) VALUES ('schema_version', 1) ON CONFLICT(name) DO NOTHING");
        requireBilling(rows("SELECT value FROM billing_meta WHERE name = 'schema_version'")[0]?.value === 1,
            'UNSUPPORTED_SCHEMA');
    });
    let running = false;
    const transaction = async callback => {
        requireBilling(typeof callback === 'function' && !running, 'INVALID_TRANSACTION');
        return storage.transactionSync(() => {
            running = true;
            let active = true;
            const exec = (sql, ...bindings) => {
                requireBilling(active, 'TRANSACTION_CLOSED');
                return rows(sql, ...bindings);
            };
            const record = (sql, key) => {
                const result = exec(sql, textKey(key))[0];
                return result ? JSON.parse(result.payload) : undefined;
            };
            const tx = Object.freeze({
                getOrder: id => record('SELECT payload FROM billing_orders WHERE id = ?', id),
                getOrderByRequest: key => record('SELECT payload FROM billing_orders WHERE request_key = ?', key),
                getOrderByInvoice: id => record('SELECT payload FROM billing_orders WHERE invoice_id = ?', id),
                putOrder(order) {
                    const json = recordJson(order);
                    const id = textKey(order.id, 128);
                    const requestKey = textKey(order.requestKey);
                    const invoice = order.invoiceId == null ? null : textKey(order.invoiceId, 128);
                    // UPDATE-on-ID preserves all other UNIQUE constraints. Never REPLACE:
                    // REPLACE would silently delete the conflicting paid order.
                    exec(`INSERT INTO billing_orders(id, request_key, invoice_id, payload) VALUES (?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET request_key=excluded.request_key,
                        invoice_id=excluded.invoice_id, payload=excluded.payload`, id, requestKey, invoice, json);
                },
                getCode: hash => record('SELECT payload FROM billing_codes WHERE hash = ?', hash),
                putCode(code) {
                    const json = recordJson(code);
                    requireBilling(typeof code.hash === 'string' && /^[0-9a-f]{64}$/.test(code.hash)
                        && Object.keys(code).every(key => KEY_FIELDS.has(key)), 'INVALID_RECORD');
                    exec(`INSERT INTO billing_codes(hash, payload) VALUES (?, ?)
                        ON CONFLICT(hash) DO UPDATE SET payload=excluded.payload`, code.hash, json);
                },
                getPayment: id => record('SELECT payload FROM billing_payments WHERE event_id = ?', id),
                putPayment(payment) {
                    // Append-only proof: duplicate event IDs abort the entire transaction.
                    exec('INSERT INTO billing_payments(event_id, payload) VALUES (?, ?)',
                        textKey(payment.eventId), recordJson(payment));
                },
                getEntitlement: id => record('SELECT payload FROM billing_entitlements WHERE user_id = ?', id),
                putEntitlement(entitlement) {
                    exec(`INSERT INTO billing_entitlements(user_id, payload) VALUES (?, ?)
                        ON CONFLICT(user_id) DO UPDATE SET payload=excluded.payload`,
                    textKey(entitlement.telegramUserId, 32), recordJson(entitlement));
                },
                consumeRateLimit(bucket, limit, expiresAtMs) {
                    textKey(bucket);
                    requireBilling(integer(limit, 1_000_000) > 0, 'INVALID_RECORD');
                    integer(expiresAtMs);
                    // Denied attempts cannot reset or lower an existing counter/expiry.
                    return exec(`INSERT INTO billing_rate_limits(bucket, attempts, expires_at_ms) VALUES (?, 1, ?)
                        ON CONFLICT(bucket) DO UPDATE SET attempts=billing_rate_limits.attempts + 1,
                        expires_at_ms=MAX(billing_rate_limits.expires_at_ms, excluded.expires_at_ms)
                        WHERE billing_rate_limits.attempts < ? RETURNING attempts`, bucket, expiresAtMs, limit).length === 1;
                },
            });
            try {
                const result = callback(tx);
                if (result && typeof result.then === 'function') {
                    // Prevent an async continuation from writing after rollback and
                    // consume its later rejection without masking the immediate error.
                    Promise.resolve(result).catch(() => {});
                    requireBilling(false, 'ASYNC_TRANSACTION_FORBIDDEN');
                }
                return result;
            } finally {
                active = false;
                running = false;
            }
        });
    };
    return Object.freeze({
        durable: true,
        transaction,
        async cleanupExpiredRateLimits(nowMs, limit = 100) {
            integer(nowMs);
            requireBilling(integer(limit, 500) > 0 && !running, 'INVALID_TRANSACTION');
            return storage.transactionSync(() => rows(`DELETE FROM billing_rate_limits WHERE bucket IN
                (SELECT bucket FROM billing_rate_limits WHERE expires_at_ms <= ?
                 ORDER BY expires_at_ms, bucket LIMIT ?) RETURNING bucket`, nowMs, limit).length);
        },
    });
}
