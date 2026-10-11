import { Buffer } from 'node:buffer';
import { requireBilling } from '../catalog.mjs';

export const BOT_OUTBOX_POLICY = Object.freeze({
    leaseMs: 30_000,
    maxAttempts: 12,
    lifetimeMs: 24 * 60 * 60 * 1000,
    initialBackoffMs: 5_000,
    maxBackoffMs: 15 * 60 * 1000,
    maxRecoveryRows: 100,
    maxCiphertextBytes: 8192,
});
const P = BOT_OUTBOX_POLICY;
const KINDS = new Set(['telegram_invoice', 'telegram_paid_key', 'telegram_info']);
const ENVELOPE_KEYS = ['version', 'algorithm', 'keyId', 'iv', 'ciphertext', 'tag'];
const SCHEMA = [
    `CREATE TABLE IF NOT EXISTS billing_bot_outbox (
        id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=64),
        kind TEXT NOT NULL CHECK(kind IN ('telegram_invoice','telegram_paid_key','telegram_info')),
        encrypted_payload TEXT NOT NULL CHECK(json_valid(encrypted_payload)),
        state TEXT NOT NULL CHECK(state IN ('pending','leased','acknowledged','dead')),
        created_at_ms INTEGER NOT NULL, expires_at_ms INTEGER NOT NULL,
        next_attempt_at_ms INTEGER NOT NULL, attempts INTEGER NOT NULL CHECK(attempts BETWEEN 0 AND 12),
        lease_id TEXT, lease_until_ms INTEGER, last_attempt_at_ms INTEGER,
        ack_json TEXT CHECK(ack_json IS NULL OR json_valid(ack_json)),
        updated_at_ms INTEGER NOT NULL,
        terminal_reason TEXT CHECK(terminal_reason IS NULL OR terminal_reason IN ('expired','attempts_exhausted')),
        CHECK(expires_at_ms > created_at_ms))`,
    `CREATE INDEX IF NOT EXISTS billing_bot_outbox_due ON billing_bot_outbox(state,next_attempt_at_ms,id)`,
    `CREATE INDEX IF NOT EXISTS billing_bot_outbox_leased ON billing_bot_outbox(state,lease_until_ms,id)`,
    `CREATE INDEX IF NOT EXISTS billing_bot_outbox_expiry ON billing_bot_outbox(state,expires_at_ms,id)`,
    // Persistent, insert-only nonce history prevents a reused lease ID from
    // authorizing an old worker after its earlier lease expired. At most 12/job.
    `CREATE TABLE IF NOT EXISTS billing_bot_outbox_leases (
        lease_id TEXT PRIMARY KEY NOT NULL,
        outbox_id TEXT NOT NULL, attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 12),
        UNIQUE(outbox_id,attempt))`,
];

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function id(value) {
    requireBilling(typeof value === 'string' && value.length === 64 && !/[^0-9a-f]/.test(value), 'INVALID_OUTBOX_ID');
    return value;
}
function lease(value) {
    requireBilling(typeof value === 'string' && value.length >= 22 && value.length <= 128
        && !/[^A-Za-z0-9_-]/.test(value), 'INVALID_OUTBOX_LEASE');
    return value;
}
function time(value) {
    requireBilling(Number.isSafeInteger(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER - P.lifetimeMs,
        'INVALID_OUTBOX_TIME');
    return value;
}
function canonicalBase64(value, minBytes, maxBytes) {
    requireBilling(typeof value === 'string' && value.length <= Math.ceil(maxBytes * 4 / 3)
        && value.length > 0 && !/[^A-Za-z0-9_-]/.test(value), 'INVALID_ENCRYPTED_PAYLOAD');
    const bytes = Buffer.from(value, 'base64url');
    requireBilling(bytes.length >= minBytes && bytes.length <= maxBytes
        && bytes.toString('base64url') === value, 'INVALID_ENCRYPTED_PAYLOAD');
    return value;
}
function envelopeJson(value) {
    requireBilling(object(value) && Object.keys(value).length === ENVELOPE_KEYS.length
        && ENVELOPE_KEYS.every(key => Object.hasOwn(value, key))
        && value.version === 1 && value.algorithm === 'AES-256-GCM'
        && typeof value.keyId === 'string' && value.keyId.length >= 1 && value.keyId.length <= 64
        && !/[^A-Za-z0-9_-]/.test(value.keyId), 'INVALID_ENCRYPTED_PAYLOAD');
    return JSON.stringify({ version: 1, algorithm: 'AES-256-GCM', keyId: value.keyId,
        iv: canonicalBase64(value.iv, 12, 12),
        ciphertext: canonicalBase64(value.ciphertext, 1, P.maxCiphertextBytes),
        tag: canonicalBase64(value.tag, 16, 16) });
}
function ackJson(value) {
    requireBilling(object(value) && Object.keys(value).length === 1 && Object.hasOwn(value, 'messageId')
        && Number.isSafeInteger(value.messageId) && value.messageId > 0, 'INVALID_OUTBOX_ACK');
    return JSON.stringify({ messageId: value.messageId });
}
const retryDelay = attempts => Math.min(P.initialBackoffMs * 2 ** (attempts - 1), P.maxBackoffMs);

/**
 * Storage primitive only: no dispatcher, network calls, bot token, rollout flag,
 * encryption key, or payment activation. Use ONE SQLite DO per billing system.
 *
 * A trusted crypto adapter must encrypt/verify AES-GCM with a fresh IV and AAD
 * binding at least version + outbox ID + kind + keyId before/after this adapter.
 * This adapter checks the envelope FORMAT, not whether bytes are really encrypted.
 * NEVER accept an envelope from an unauthenticated client. No token should be in
 * an encrypted message either: the dispatcher reads the bot token from secrets.
 * keyId is a public key-version label; IDs must be opaque SHA-256 hex digests.
 *
 * Delivery is AT-LEAST-ONCE attempts within finite retry/time bounds, not exactly
 * once and not guaranteed success. A Telegram send accepted before a crash/lost
 * ACK can be repeated. Dispatcher network timeout must be shorter than the lease;
 * it must authenticate/decrypt, verify recipient + returned Telegram message, and
 * call finishAck only after a successful Telegram response. Failed/expired jobs
 * become 'dead', retained for manual reconciliation, not silently deleted.
 *
 * Atomic payment/key + outbox requires one shared SQLite transaction. Only
 * transactionSync(callback)'s live tx.enqueueTx capability can join that transaction;
 * an escaped handle and async callback are rejected. Other synchronous writes on
 * THE SAME storage connection inside that callback roll back with it. The current
 * separate financial repository.transaction MUST NOT be nested or committed first.
 * live-store.mjs uses this capability for its shared receipt/key/outbox commit.
 * Calling financial transaction then enqueue() is NOT atomic. Prepare encryption
 * synchronously, without network/await, in the transaction. A retry reuses the saved
 * envelope: re-encryption with a new IV under the same ID is a conflict, not update.
 *
 * Ack/dead records and lease history are retained intentionally for idempotency.
 * No cleanup/GC is supplied; design retention/reconciliation before live rollout.
 */
export function createBotOutboxRepository(storage) {
    requireBilling(storage && typeof storage.transactionSync === 'function'
        && typeof storage.sql?.exec === 'function', 'SQLITE_STORAGE_REQUIRED');
    const rows = (sql, ...bindings) => Array.from(storage.sql.exec(sql, ...bindings));
    storage.transactionSync(() => { for (const sql of SCHEMA) rows(sql); });
    let running = false;
    function atomic(callback) {
        requireBilling(typeof callback === 'function' && !running, 'INVALID_TRANSACTION');
        return storage.transactionSync(() => {
            running = true;
            try { return callback(); } finally { running = false; }
        });
    }
    const get = value => rows('SELECT * FROM billing_bot_outbox WHERE id = ?', id(value))[0];
    function enqueueInside(value, encryptedPayload, kind, nowMs) {
        id(value); time(nowMs);
        requireBilling(KINDS.has(kind), 'INVALID_OUTBOX_KIND');
        const payload = envelopeJson(encryptedPayload);
        const previous = get(value);
        if (previous) {
            requireBilling(previous.kind === kind && previous.encrypted_payload === payload, 'OUTBOX_IDEMPOTENCY_CONFLICT');
            return 'existing';
        }
        rows(`INSERT INTO billing_bot_outbox(id,kind,encrypted_payload,state,created_at_ms,expires_at_ms,
            next_attempt_at_ms,attempts,updated_at_ms) VALUES (?,?,?,'pending',?,?,?,0,?)`,
        value, kind, payload, nowMs, nowMs + P.lifetimeMs, nowMs, nowMs);
        return 'enqueued';
    }
    function postpone(row, nowMs, retryAfterSeconds = 0) {
        const next = nowMs + Math.max(retryDelay(row.attempts), retryAfterSeconds * 1000);
        const reason = row.attempts >= P.maxAttempts ? 'attempts_exhausted'
            : next >= row.expires_at_ms ? 'expired' : null;
        rows(`UPDATE billing_bot_outbox SET state=?,next_attempt_at_ms=?,lease_id=NULL,lease_until_ms=NULL,
            updated_at_ms=?,terminal_reason=? WHERE id=?`, reason ? 'dead' : 'pending', next, nowMs, reason, row.id);
        return reason ? 'dead' : 'deferred';
    }
    function recoverInside(nowMs, limit) {
        const due = rows(`SELECT * FROM billing_bot_outbox WHERE
            (state='leased' AND lease_until_ms <= ?) OR (state='pending' AND expires_at_ms <= ?)
            ORDER BY expires_at_ms,id LIMIT ?`, nowMs, nowMs, limit);
        for (const row of due) {
            if (row.expires_at_ms <= nowMs) {
                rows(`UPDATE billing_bot_outbox SET state='dead',lease_id=NULL,lease_until_ms=NULL,
                    updated_at_ms=?,terminal_reason='expired' WHERE id=?`, nowMs, row.id);
            } else postpone(row, nowMs);
        }
        return due.length;
    }
    const activeLease = (row, leaseId, nowMs) => row?.state === 'leased' && row.lease_id === leaseId
        && nowMs >= row.last_attempt_at_ms && nowMs < row.lease_until_ms && nowMs < row.expires_at_ms;

    const transactionSync = callback => {
        requireBilling(typeof callback === 'function', 'INVALID_TRANSACTION');
        return atomic(() => {
            let active = true;
            const tx = Object.freeze({ enqueueTx(...args) {
                requireBilling(active, 'TRANSACTION_CLOSED');
                return enqueueInside(...args);
            } });
            try {
                const result = callback(tx);
                if (result && typeof result.then === 'function') {
                    Promise.resolve(result).catch(() => {});
                    requireBilling(false, 'ASYNC_TRANSACTION_FORBIDDEN');
                }
                return result;
            } finally { active = false; }
        });
    };
    const transaction = async callback => transactionSync(callback);

    return Object.freeze({
        durable: true,
        transaction,
        transactionSync,
        async enqueue(...args) { return transaction(tx => tx.enqueueTx(...args)); },
        async claimDue(leaseId, nowMs) {
            lease(leaseId); time(nowMs);
            return atomic(() => {
                requireBilling(rows('SELECT lease_id FROM billing_bot_outbox_leases WHERE lease_id=?', leaseId).length === 0,
                    'OUTBOX_LEASE_REUSED');
                recoverInside(nowMs, P.maxRecoveryRows);
                const row = rows(`SELECT * FROM billing_bot_outbox WHERE state='pending'
                    AND next_attempt_at_ms <= ? AND created_at_ms <= ? AND expires_at_ms > ? AND attempts < ?
                    ORDER BY next_attempt_at_ms,created_at_ms,id LIMIT 1`, nowMs, nowMs, nowMs, P.maxAttempts)[0];
                if (!row) return null;
                const attempt = row.attempts + 1;
                const leaseUntilMs = Math.min(nowMs + P.leaseMs, row.expires_at_ms);
                const encryptedPayload = Object.freeze(JSON.parse(envelopeJson(JSON.parse(row.encrypted_payload))));
                rows('INSERT INTO billing_bot_outbox_leases(lease_id,outbox_id,attempt) VALUES (?,?,?)', leaseId, row.id, attempt);
                rows(`UPDATE billing_bot_outbox SET state='leased',attempts=?,lease_id=?,lease_until_ms=?,
                    last_attempt_at_ms=?,updated_at_ms=? WHERE id=?`, attempt, leaseId, leaseUntilMs, nowMs, nowMs, row.id);
                return Object.freeze({ id: row.id, kind: row.kind, encryptedPayload, attempt, leaseId, leaseUntilMs,
                    createdAtMs: row.created_at_ms, expiresAtMs: row.expires_at_ms });
            });
        },
        async finishAck(value, leaseId, resultMetadata, nowMs) {
            id(value); lease(leaseId); time(nowMs);
            const json = ackJson(resultMetadata);
            return atomic(() => {
                const row = get(value);
                if (row?.state === 'acknowledged' && row.lease_id === leaseId) {
                    requireBilling(row.ack_json === json, 'OUTBOX_ACK_CONFLICT');
                    return 'acknowledged';
                }
                if (!activeLease(row, leaseId, nowMs)) return 'stale';
                rows(`UPDATE billing_bot_outbox SET state='acknowledged',ack_json=?,lease_until_ms=NULL,
                    updated_at_ms=? WHERE id=?`, json, nowMs, value);
                return 'acknowledged';
            });
        },
        async defer(value, leaseId, nowMs, retryAfterSeconds = 0) {
            id(value); lease(leaseId); time(nowMs);
            requireBilling(Number.isSafeInteger(retryAfterSeconds) && retryAfterSeconds >= 0
                && retryAfterSeconds <= 86400, 'INVALID_RETRY_AFTER');
            return atomic(() => {
                const row = get(value);
                return activeLease(row, leaseId, nowMs) ? postpone(row, nowMs, retryAfterSeconds) : 'stale';
            });
        },
        async recoverExpired(nowMs, limit = P.maxRecoveryRows) {
            time(nowMs);
            requireBilling(Number.isSafeInteger(limit) && limit > 0 && limit <= P.maxRecoveryRows, 'INVALID_RECOVERY_LIMIT');
            return atomic(() => recoverInside(nowMs, limit));
        },
    });
}
