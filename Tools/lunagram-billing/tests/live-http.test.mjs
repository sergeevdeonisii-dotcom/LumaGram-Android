import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';
import { createLiveAuth } from '../live-auth.mjs';
import { createLiveStore } from '../cloudflare/live-store.mjs';
import { createLiveControl, secretMatches } from '../cloudflare/live-control.mjs';
import { handleAppRequest, proxyAppRequest, jsonResponse } from '../cloudflare/live-http.mjs';
import { publicResponse, readPublicReadiness } from '../cloudflare/public-http.mjs';
import core from '../cloudflare/core-worker.mjs';

const NOW = Date.UTC(2026, 9, 12, 20);
const keys = { encryptionKey: Buffer.alloc(32, 3), keyPepper: Buffer.alloc(32, 4), authPepper: Buffer.alloc(32, 5) };
const env = { BILLING_OWNER_CLAIM_TOKEN: 'A'.repeat(43), BILLING_OWNER_CLAIM_EXPIRES: String(NOW + 60000), BILLING_PAYMENTS_ENABLED: 'true' };
function fixture(t) {
    const storage = openSqliteStorageForTests(':memory:'); t.after(() => storage.close());
    return { storage, control: createLiveControl(storage, keys, env), auth: createLiveAuth({ storage, authPepper: keys.authPepper }),
        store: createLiveStore(storage, { ...keys, clock: () => NOW }), clock: () => NOW };
}
const request = (path, body = {}, token) => new Request(`https://billing.internal${path}`, { method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Lunagram-Client-IP': '127.0.0.1', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });

test('app pairing cannot authenticate by user ID; PKCE and private consent are required', async t => {
    const f = fixture(t); const verifier = randomBytes(32).toString('base64url');
    const challenge = createHash('sha256').update(verifier).digest('base64url');
    const start = await handleAppRequest(request('/v1/auth/start', { challenge, expectedUserId: '101' }), f);
    assert.equal(start.status, 200); const pair = await start.json();
    assert.equal(pair.botUrl, `https://t.me/LunagramAppPayBot?start=pair_${pair.pairId}`);
    const pending = await handleAppRequest(request('/v1/auth/exchange', { pairId: pair.pairId, verifier }), f);
    assert.equal(pending.status, 202); assert.equal((await pending.json()).status, 'pending');
    f.auth.approve(pair.pairId, '101', NOW);
    const exchange = await handleAppRequest(request('/v1/auth/exchange', { pairId: pair.pairId, verifier }), f);
    const session = await exchange.json(); assert.equal(session.telegramUserId, '101');
    const status = await handleAppRequest(request('/v1/subscription', {}, session.token), f);
    assert.deepEqual(await status.json(), { telegramUserId: '101', active: false, planId: null, expiresAtMs: 0, featuresGated: false });
    assert.equal((await handleAppRequest(request('/v1/subscription', { userId: '202' }, session.token), f)).status, 400);
    assert.equal((await handleAppRequest(request('/v1/auth/revoke', {}, session.token), f)).status, 200);
    assert.equal((await handleAppRequest(request('/v1/subscription', {}, session.token), f)).status, 401);
});
test('anonymous or cross-account fields never activate codes; malformed JSON and IP fail closed', async t => {
    const f = fixture(t);
    assert.equal((await handleAppRequest(request('/v1/redeem', { code: 'A'.repeat(16), userId: '101' }), f)).status, 401);
    const missing = request('/v1/auth/start'); missing.headers.delete('X-Lunagram-Client-IP');
    assert.equal((await handleAppRequest(missing, f)).status, 400);
    const bad = new Request('https://billing.internal/v1/auth/start', { method: 'POST', headers: request('/').headers, body: '{' });
    assert.equal((await handleAppRequest(bad, f)).status, 400);
});
test('durable rate admission counts malformed public requests and does not store raw IP', async t => {
    const f = fixture(t);
    for (let i = 0; i < 10; i++) await handleAppRequest(request('/v1/auth/start', {}), f);
    assert.equal((await handleAppRequest(request('/v1/auth/start', {}), f)).status, 429);
    assert.ok(!JSON.stringify([...f.storage.sql.exec('SELECT * FROM live_edge_limits')]).includes('127.0.0.1'));
});
test('owner needs exact one-use expiring capability and survives restart encrypted', t => {
    const f = fixture(t);
    assert.equal(f.control.paymentsReady(), false);
    assert.throws(() => f.control.claimOwner('B'.repeat(43), '101', NOW), { code: 'FORBIDDEN' });
    f.control.claimOwner(env.BILLING_OWNER_CLAIM_TOKEN, '101', NOW);
    assert.throws(() => f.control.claimOwner(env.BILLING_OWNER_CLAIM_TOKEN, '202', NOW), { code: 'FORBIDDEN' });
    assert.equal(f.control.isOwner('202'), false);
    f.control.finishSetup();
    assert.equal(createLiveControl(f.storage, keys, env).paymentsReady(), true);
    const saved = [...f.storage.sql.exec("SELECT value FROM live_control WHERE name='owner'")][0].value;
    assert.ok(!saved.includes('101')); assert.ok(!saved.includes(env.BILLING_OWNER_CLAIM_TOKEN));
    assert.equal(secretMatches('ю'.repeat(43), 'A'.repeat(43)), false);
});
test('Pages app proxy discards spoofed source-IP/cookies and only uses fixed service', async () => {
    const req = request('/v1/auth/start'); req.headers.set('X-Lunagram-Client-IP', '9.9.9.9');
    req.headers.set('CF-Connecting-IP', '1.2.3.4'); req.headers.set('Cookie', 'secret');
    const response = await proxyAppRequest(req, { BILLING_SERVICE: { async fetch(forwarded) {
        assert.equal(forwarded.url, 'https://billing.internal/v1/auth/start');
        assert.equal(forwarded.headers.get('X-Lunagram-Client-IP'), '1.2.3.4');
        assert.equal(forwarded.headers.get('Cookie'), null);
        return jsonResponse({ error: 'INVALID_REQUEST' }, 400);
    } } });
    assert.equal(response.status, 400); assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
    const blocked = await proxyAppRequest(request('/v1/auth/start'), { BILLING_SERVICE: { fetch: async () => jsonResponse({ stack: 'secret' }) } });
    assert.equal(blocked.status, 503); assert.ok(!(await blocked.text()).includes('secret'));
});
test('setup capability is denied before DO even for Unicode/noncanonical inputs', async () => {
    const config = { LIVE_BOT_ENABLED: 'true', BILLING_SETUP_TOKEN: 'A'.repeat(43),
        BILLING_ENCRYPTION_KEY: keys.encryptionKey.toString('base64url'), BILLING_KEY_PEPPER: keys.keyPepper.toString('base64url'),
        BILLING_AUTH_PEPPER: keys.authPepper.toString('base64url'), get BILLING_STORE() { throw new Error('must not touch'); } };
    for (const token of ['bad', 'A'.repeat(42), 'B'.repeat(43)]) {
        const response = await core.fetch(request('/v1/admin/setup', {}, token), config);
        assert.equal(response.status, 403);
    }
});
test('public status proxy accepts only explicit readiness states, never arbitrary metadata', async () => {
    assert.deepEqual(await readPublicReadiness(publicResponse('/health', { live: true, payments: false }), '/health'), { live: true, payments: false });
    assert.deepEqual(await readPublicReadiness(publicResponse('/v1/catalog', { live: true, payments: true }), '/v1/catalog'), { live: true, payments: true });
    assert.equal(await readPublicReadiness(jsonResponse({ service: 'lunagram-billing', secret: 'no' }), '/health'), null);
});
