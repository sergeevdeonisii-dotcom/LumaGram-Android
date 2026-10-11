import { Buffer } from 'node:buffer';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { requireBilling, telegramUserId } from './catalog.mjs';

export const PAIRING_TTL_MS = 10 * 60_000;
export const SESSION_TTL_MS = 30 * 24 * 60 * 60_000;
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const ENCODED_RANDOM = /^[A-Za-z0-9_-]{43}$/;
const randomToken = () => randomBytes(32).toString('base64url');

function canonical32(value) {
    return typeof value === 'string' && ENCODED_RANDOM.test(value)
        && Buffer.from(value, 'base64url').toString('base64url') === value;
}
function validTime(now) {
    requireBilling(Number.isSafeInteger(now) && now > 0
        && now <= Number.MAX_SAFE_INTEGER - SESSION_TTL_MS, 'INVALID_TIME');
}
function pairingId(value) {
    requireBilling(canonical32(value), 'PAIRING_NOT_AVAILABLE');
    return value;
}

/**
 * Private server adapter for ONE SQLite Durable Object. It performs no HTTP or
 * Bot API calls. expectedUserId is an untrusted app hint, never authentication.
 * pending/approve MUST receive a secret-verified private bot sender ID; approve
 * is called only after that user explicitly confirms the particular pairing.
 * The app keeps its verifier secret and serializes exchange polling. Tokens,
 * verifiers, and authorization headers must never be logged by its caller.
 */
export function createLiveAuth({ storage, authPepper } = {}) {
    requireBilling(storage && typeof storage.transactionSync === 'function'
        && typeof storage.sql?.exec === 'function', 'SQLITE_STORAGE_REQUIRED');
    requireBilling(Buffer.isBuffer(authPepper) && authPepper.length >= 32, 'NOT_CONFIGURED');
    const pepper = Buffer.from(authPepper);
    const digest = (domain, value) => createHmac('sha256', pepper).update(domain).update('\0').update(value).digest('hex');
    const rows = (sql, ...bindings) => Array.from(storage.sql.exec(sql, ...bindings));
    storage.transactionSync(() => {
        rows('CREATE TABLE IF NOT EXISTS billing_auth_meta (name TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)');
        rows("INSERT INTO billing_auth_meta(name,value) VALUES ('schema_version',1) ON CONFLICT(name) DO NOTHING");
        requireBilling(rows("SELECT value FROM billing_auth_meta WHERE name='schema_version'")[0]?.value === 1, 'UNSUPPORTED_SCHEMA');
        rows(`CREATE TABLE IF NOT EXISTS billing_auth_pairs (
            pair_id TEXT PRIMARY KEY NOT NULL, challenge TEXT UNIQUE NOT NULL,
            expected_user_id TEXT NOT NULL, approved_user_id TEXT,
            created_at_ms INTEGER NOT NULL, expires_at_ms INTEGER NOT NULL,
            approved_at_ms INTEGER, session_hash TEXT, session_expires_at_ms INTEGER, revoked_at_ms INTEGER)`);
        rows('CREATE INDEX IF NOT EXISTS billing_auth_pairs_expiry ON billing_auth_pairs(expires_at_ms,pair_id)');
        rows(`CREATE TABLE IF NOT EXISTS billing_auth_sessions (
            token_hash TEXT PRIMARY KEY NOT NULL, user_id TEXT NOT NULL, pair_id TEXT UNIQUE NOT NULL,
            created_at_ms INTEGER NOT NULL, expires_at_ms INTEGER NOT NULL)`);
        rows('CREATE INDEX IF NOT EXISTS billing_auth_sessions_expiry ON billing_auth_sessions(expires_at_ms,token_hash)');
        rows(`CREATE TABLE IF NOT EXISTS billing_auth_limits (
            bucket TEXT PRIMARY KEY NOT NULL, attempts INTEGER NOT NULL CHECK(attempts>0), expires_at_ms INTEGER NOT NULL)`);
        rows('CREATE INDEX IF NOT EXISTS billing_auth_limits_expiry ON billing_auth_limits(expires_at_ms,bucket)');
    });

    function cleanup(now) {
        // Bounded indexed cleanup, never financial or bot delivery tables.
        rows(`DELETE FROM billing_auth_limits WHERE bucket IN
            (SELECT bucket FROM billing_auth_limits WHERE expires_at_ms<=? ORDER BY expires_at_ms,bucket LIMIT 50)`, now);
        rows(`DELETE FROM billing_auth_pairs WHERE pair_id IN
            (SELECT pair_id FROM billing_auth_pairs WHERE expires_at_ms<=? ORDER BY expires_at_ms,pair_id LIMIT 25)`, now);
        rows(`DELETE FROM billing_auth_sessions WHERE token_hash IN
            (SELECT token_hash FROM billing_auth_sessions WHERE expires_at_ms<=? ORDER BY expires_at_ms,token_hash LIMIT 25)`, now);
    }
    function limit(action, subject, count, window, globalCount, now) {
        // Commit denials before the operation transaction; failed guesses cannot
        // undo counters. Global admission precedes creation of attacker-selected
        // subject buckets. The public HTTP edge must additionally rate-limit IPs.
        const allowed = storage.transactionSync(() => {
            cleanup(now);
            const consume = (bucket, cap, duration) => rows(`INSERT INTO billing_auth_limits(bucket,attempts,expires_at_ms)
                VALUES (?,1,?) ON CONFLICT(bucket) DO UPDATE SET attempts=MIN(billing_auth_limits.attempts+1,?)
                RETURNING attempts`, bucket, (Math.floor(now / duration) + 2) * duration, cap + 1)[0].attempts <= cap;
            if (!consume(`global:${action}:${Math.floor(now / MINUTE)}`, globalCount, MINUTE)) return false;
            return consume(`${action}:${digest('rate-subject', subject)}:${Math.floor(now / window)}`, count, window);
        });
        requireBilling(allowed, 'RATE_LIMITED');
    }
    const getPair = id => rows('SELECT * FROM billing_auth_pairs WHERE pair_id=?', id)[0];
    function available(pair, now) {
        requireBilling(pair && pair.expires_at_ms > now && pair.revoked_at_ms === null, 'PAIRING_NOT_AVAILABLE');
        return pair;
    }
    function ownedPair(id, userId, now) {
        const pair = available(getPair(id), now);
        requireBilling(pair.expected_user_id === userId
            && (pair.approved_user_id === null || pair.approved_user_id === userId), 'PAIRING_NOT_AVAILABLE');
        return pair;
    }
    const preview = pair => ({ pairId: pair.pair_id,
        status: pair.session_hash !== null ? 'exchanged' : pair.approved_user_id !== null ? 'approved' : 'pending',
        expiresAtMs: pair.expires_at_ms });

    return Object.freeze({
        durable: true,
        start({ challenge, expectedUserId } = {}, now) {
            validTime(now);
            requireBilling(canonical32(challenge), 'INVALID_REQUEST');
            const userId = telegramUserId(expectedUserId);
            limit('start', userId, 5, HOUR, 60, now);
            return storage.transactionSync(() => {
                // Lost start responses can recover the same link, not extend its TTL.
                const old = rows('SELECT * FROM billing_auth_pairs WHERE challenge=?', challenge)[0];
                if (old) {
                    available(old, now);
                    requireBilling(old.expected_user_id === userId, 'PAIRING_NOT_AVAILABLE');
                    return { pairId: old.pair_id, expiresAtMs: old.expires_at_ms, startParameter: `pair_${old.pair_id}` };
                }
                const id = randomToken();
                const expiresAtMs = now + PAIRING_TTL_MS;
                rows(`INSERT INTO billing_auth_pairs(pair_id,challenge,expected_user_id,created_at_ms,expires_at_ms)
                    VALUES (?,?,?,?,?)`, id, challenge, userId, now, expiresAtMs);
                return { pairId: id, expiresAtMs, startParameter: `pair_${id}` };
            });
        },
        pending(id, verifiedUserId, now) {
            validTime(now); pairingId(id);
            const userId = telegramUserId(verifiedUserId);
            limit('preview', userId, 60, MINUTE, 300, now);
            return storage.transactionSync(() => preview(ownedPair(id, userId, now)));
        },
        approve(id, verifiedUserId, now) {
            validTime(now); pairingId(id);
            const userId = telegramUserId(verifiedUserId);
            limit('approve', userId, 30, MINUTE, 300, now);
            return storage.transactionSync(() => {
                const pair = ownedPair(id, userId, now);
                if (pair.approved_user_id === null) {
                    rows('UPDATE billing_auth_pairs SET approved_user_id=?,approved_at_ms=? WHERE pair_id=?', userId, now, id);
                    pair.approved_user_id = userId;
                }
                return preview(pair);
            });
        },
        exchange(id, verifier, now) {
            validTime(now); pairingId(id);
            requireBilling(canonical32(verifier), 'PAIRING_NOT_AVAILABLE');
            limit('exchange', id, 60, MINUTE, 600, now);
            const challenge = createHash('sha256').update(verifier, 'ascii').digest();
            return storage.transactionSync(() => {
                const pair = available(getPair(id), now);
                requireBilling(canonical32(pair.challenge)
                    && timingSafeEqual(challenge, Buffer.from(pair.challenge, 'base64url')), 'PAIRING_NOT_AVAILABLE');
                if (pair.approved_user_id === null) return { status: 'pending', expiresAtMs: pair.expires_at_ms };
                requireBilling(pair.approved_user_id === pair.expected_user_id, 'PAIRING_NOT_AVAILABLE');
                // Rotation is intentional recovery after an ambiguous/lost exchange
                // response. It revokes the old token atomically and never prolongs
                // pairing or the original session lifetime. No plaintext token is saved.
                const token = randomToken();
                const hash = digest('session-token', token);
                const expiresAtMs = pair.session_expires_at_ms ?? now + SESSION_TTL_MS;
                requireBilling(expiresAtMs > now, 'PAIRING_NOT_AVAILABLE');
                rows('DELETE FROM billing_auth_sessions WHERE pair_id=?', id);
                rows(`INSERT INTO billing_auth_sessions(token_hash,user_id,pair_id,created_at_ms,expires_at_ms)
                    VALUES (?,?,?,?,?)`, hash, pair.approved_user_id, id, now, expiresAtMs);
                rows('UPDATE billing_auth_pairs SET session_hash=?,session_expires_at_ms=? WHERE pair_id=?', hash, expiresAtMs, id);
                return { status: 'authenticated', token, telegramUserId: pair.approved_user_id, expiresAtMs };
            });
        },
        authenticate(token, now) {
            validTime(now);
            requireBilling(canonical32(token), 'UNAUTHENTICATED');
            const session = rows('SELECT user_id,expires_at_ms FROM billing_auth_sessions WHERE token_hash=?', digest('session-token', token))[0];
            requireBilling(session && session.expires_at_ms > now, 'UNAUTHENTICATED');
            return telegramUserId(session.user_id);
        },
        revoke(token, now) {
            validTime(now);
            requireBilling(canonical32(token), 'UNAUTHENTICATED');
            storage.transactionSync(() => {
                const hash = digest('session-token', token);
                const session = rows('SELECT pair_id FROM billing_auth_sessions WHERE token_hash=?', hash)[0];
                if (session) {
                    // Logout also ends any still-open pairing recovery window; a
                    // retained verifier must not silently resurrect the revoked login.
                    rows('UPDATE billing_auth_pairs SET revoked_at_ms=? WHERE pair_id=?', now, session.pair_id);
                    rows('DELETE FROM billing_auth_sessions WHERE token_hash=?', hash);
                }
            });
            return { revoked: true };
        },
    });
}
