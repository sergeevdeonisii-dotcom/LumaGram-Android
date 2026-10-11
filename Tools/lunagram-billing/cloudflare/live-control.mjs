import { Buffer } from 'node:buffer';
import { createCipheriv, createDecipheriv, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { requireBilling, telegramUserId } from '../catalog.mjs';

export const TERMS_VERSION = '2026-10-12-v1';
export function secretMatches(actual, expected) {
    if (typeof actual !== 'string' || typeof expected !== 'string'
        || !/^[A-Za-z0-9_-]{43}$/.test(actual) || !/^[A-Za-z0-9_-]{43}$/.test(expected)) return false;
    return timingSafeEqual(Buffer.from(actual), Buffer.from(expected));
}
export function liveKeys(env) {
    if (env?.LIVE_BOT_ENABLED !== 'true') return null;
    const keys = {};
    for (const [name, variable] of [['encryptionKey', 'BILLING_ENCRYPTION_KEY'], ['keyPepper', 'BILLING_KEY_PEPPER'], ['authPepper', 'BILLING_AUTH_PEPPER']]) {
        const value = env[variable];
        if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(value)) return null;
        const bytes = Buffer.from(value, 'base64url');
        if (bytes.length !== 32 || bytes.toString('base64url') !== value) return null;
        keys[name] = bytes;
    }
    return keys;
}

// Owner identity is bound once by possession of an expiring, server-generated
// setup capability and explicit confirmation in a verified PRIVATE bot chat.
// No first-caller / username / app-supplied user ID can become an administrator.
export function createLiveControl(storage, keys, env) {
    const rows = (sql, ...args) => [...storage.sql.exec(sql, ...args)];
    storage.transactionSync(() => {
        rows('CREATE TABLE IF NOT EXISTS live_control (name TEXT PRIMARY KEY,value TEXT NOT NULL)');
        rows('CREATE TABLE IF NOT EXISTS live_edge_limits (bucket TEXT PRIMARY KEY,attempts INTEGER NOT NULL,expires_ms INTEGER NOT NULL)');
        rows('CREATE INDEX IF NOT EXISTS live_edge_expiry ON live_edge_limits(expires_ms)');
    });
    const get = name => rows('SELECT value FROM live_control WHERE name=?', name)[0]?.value;
    const set = (name, value) => rows('INSERT INTO live_control(name,value) VALUES (?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value', name, value);
    function owner() {
        const value = get('owner');
        if (!value) return null;
        const data = JSON.parse(value);
        const decipher = createDecipheriv('aes-256-gcm', keys.encryptionKey, Buffer.from(data.iv, 'base64url'), { authTagLength: 16 });
        decipher.setAAD(Buffer.from('lunagram-owner-v1'));
        decipher.setAuthTag(Buffer.from(data.tag, 'base64url'));
        return telegramUserId(Buffer.concat([decipher.update(Buffer.from(data.data, 'base64url')), decipher.final()]).toString());
    }
    function canClaim(token, now) {
        return !get('owner') && secretMatches(token, env.BILLING_OWNER_CLAIM_TOKEN)
            && Number.isSafeInteger(Number(env.BILLING_OWNER_CLAIM_EXPIRES)) && now < Number(env.BILLING_OWNER_CLAIM_EXPIRES);
    }
    return {
        owner,
        isOwner: userId => owner() === userId,
        canClaim,
        claimOwner(token, userId, now) {
            telegramUserId(userId);
            return storage.transactionSync(() => {
                requireBilling(canClaim(token, now), 'FORBIDDEN');
                const iv = randomBytes(12);
                const cipher = createCipheriv('aes-256-gcm', keys.encryptionKey, iv, { authTagLength: 16 });
                cipher.setAAD(Buffer.from('lunagram-owner-v1'));
                const data = Buffer.concat([cipher.update(Buffer.from(userId, 'utf8')), cipher.final()]);
                set('owner', JSON.stringify({ iv: iv.toString('base64url'), data: data.toString('base64url'), tag: cipher.getAuthTag().toString('base64url') }));
                return true;
            });
        },
        setupAllowed: token => !get('configured') && secretMatches(token, env.BILLING_SETUP_TOKEN),
        configured: () => get('configured') === '1',
        finishSetup: () => storage.transactionSync(() => set('configured', '1')),
        recordFailure(error) {
            const code = typeof error?.code === 'string' && /^[A-Z][A-Z0-9_]{1,63}$/.test(error.code) ? error.code : 'INTERNAL_ERROR';
            set('last_failure', code);
        },
        lastFailure: () => get('last_failure') ?? null,
        paymentsReady: () => env.BILLING_PAYMENTS_ENABLED === 'true' && get('configured') === '1' && owner() !== null,
        rate(subject, action, cap, now, period = 60_000) {
            const allowed = storage.transactionSync(() => {
                rows('DELETE FROM live_edge_limits WHERE bucket IN (SELECT bucket FROM live_edge_limits WHERE expires_ms<=? ORDER BY expires_ms LIMIT 50)', now);
                const slot = Math.floor(now / period);
                const key = createHmac('sha256', keys.authPepper).update(`${action}\0${subject}\0${slot}`).digest('hex');
                const row = rows(`INSERT INTO live_edge_limits(bucket,attempts,expires_ms) VALUES (?,1,?)
                    ON CONFLICT(bucket) DO UPDATE SET attempts=MIN(attempts+1,?) RETURNING attempts`, key, (slot + 2) * period, cap + 1)[0];
                return row.attempts <= cap;
            });
            requireBilling(allowed, 'RATE_LIMITED');
        },
    };
}
