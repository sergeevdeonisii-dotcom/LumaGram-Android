import test from 'node:test';
import assert from 'node:assert/strict';
import { BillingError, addCalendarMonths } from '../catalog.mjs';
import { APP_PATHS, appPath, appError, handleAppRequest, proxyAppRequest, jsonResponse } from '../cloudflare/live-http.mjs';

const NOW = Date.UTC(2026, 9, 12, 20);
const TOKEN = 'A'.repeat(43), OTHER_TOKEN = 'B'.repeat(43);
const id = n => n.toString(16).padStart(48, '0');
const paths = ['/v2/subscription', '/v2/redeem/preview', '/v2/redeem/confirm'];
const term = (n = 1, extra = {}) => {
    const value = { orderId: id(n), planId: 'standard', months: 3, startsAtMs: NOW, ...extra };
    return { ...value, expiresAtMs: addCalendarMonths(value.startsAtMs, value.months) };
};
const status = (extra = {}) => ({ schemaVersion: 2, telegramUserId: '101', serverNowMs: NOW,
    entitlementVersion: 0, queueVersion: 0, featuresGated: true, active: false, planId: null,
    expiresAtMs: 0, paidUntilMs: 0, current: null, queued: [], ...extra });
const active = (extra = {}) => {
    const current = term();
    return status({ active: true, planId: current.planId, expiresAtMs: current.expiresAtMs,
        paidUntilMs: current.expiresAtMs, current, ...extra });
};
const preview = (extra = {}) => status({ quote: { quoteId: id(100), planId: 'standard', months: 3,
    starsAmount: 180, startsAtMs: NOW, expiresAtMs: addCalendarMonths(NOW, 3), validUntilMs: NOW + 120_000, queueVersion: 0 }, ...extra });
const request = (path, body = {}, token = TOKEN) => new Request(`https://billing.internal${path}`, { method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Lunagram-Client-IP': '127.0.0.1', 'CF-Connecting-IP': '1.2.3.4',
        ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
const requestBody = path => path === '/v2/redeem/preview' ? { code: 'ABCD1234EFGH5678' }
    : path === '/v2/redeem/confirm' ? { quoteId: id(100) } : {};
function fixture(result = status()) {
    const calls = [], rates = [];
    return { calls, rates, clock: () => NOW,
        auth: { authenticate(token, now) {
            assert.equal(now, NOW);
            if (token === TOKEN) return '101';
            if (token === OTHER_TOKEN) return '202';
            throw new BillingError('UNAUTHENTICATED');
        } },
        control: { rate(...args) { rates.push(args); } },
        store: Object.fromEntries(['getSubscriptionV2', 'previewRedeemV2', 'confirmRedeemV2'].map(name => [name, (...args) => {
            calls.push([name, ...args]); return typeof result === 'function' ? result() : result;
        }])) };
}
async function proxy(body, path = '/v2/subscription', code = 200) {
    return proxyAppRequest(request(path, requestBody(path)), { BILLING_SERVICE: { fetch: async () => jsonResponse(body, code) } });
}
async function rejectedProxy(body, path = '/v2/subscription', code = 200) {
    const response = await proxy(body, path, code);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: 'NOT_READY' });
}

test('v2 routes are explicit POST-only additions; legacy route admission is retained', () => {
    for (const path of ['/v1/auth/start', '/v1/auth/exchange', '/v1/auth/revoke', '/v1/subscription', '/v1/redeem', ...paths]) {
        assert.ok(APP_PATHS.includes(path));
        assert.equal(appPath(request(path)), path);
        assert.equal(appPath(new Request(`https://billing.internal${path}`)), null);
        assert.equal(appPath(request(path + '?userId=202')), null);
        assert.equal(appPath(request(path + '#fragment')), null);
    }
    for (const path of ['/v2/redeem', '/v2/auth/start', '/v2/subscription/']) assert.equal(appPath(request(path)), null);
});

test('v2 dispatch is account-bound and accepts only the intended store operation', async () => {
    for (const [path, body, result, expected] of [
        ['/v2/subscription', {}, status(), ['getSubscriptionV2', '101', NOW]],
        ['/v2/redeem/preview', { code: 'abcd-1234-efgh-5678' }, preview(), ['previewRedeemV2', '101', 'abcd-1234-efgh-5678']],
        ['/v2/redeem/confirm', { quoteId: id(100) }, active({ activation: term() }), ['confirmRedeemV2', '101', id(100)]],
    ]) {
        const f = fixture(result), response = await handleAppRequest(request(path, body), f);
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), result);
        assert.deepEqual(f.calls, [expected]);
        assert.deepEqual(f.rates, [['all', 'app-global', 1000, NOW], ['127.0.0.1', 'app-ip', 100, NOW], ['101', 'app-user', 60, NOW]]);
    }
    const f = fixture(status({ telegramUserId: '101' }));
    const response = await handleAppRequest(request('/v2/subscription', {}, OTHER_TOKEN), f);
    assert.equal((await response.json()).telegramUserId, '202');
    assert.deepEqual(f.calls, [['getSubscriptionV2', '202', NOW]]);
});

test('v2 cannot accept caller identity, price, dates, tier, or a direct code on confirm', async () => {
    for (const path of paths) for (const field of ['userId', 'telegramUserId', 'ownerHash', 'planId', 'months',
        'starsAmount', 'startsAtMs', 'expiresAtMs', 'featuresGated']) {
        const f = fixture();
        const response = await handleAppRequest(request(path, { ...requestBody(path), [field]: 'forged' }), f);
        assert.equal(response.status, 400); assert.equal(f.calls.length, 0);
    }
    for (const [path, body] of [
        ['/v2/redeem/preview', {}], ['/v2/redeem/confirm', {}], ['/v2/redeem/confirm', { code: 'ABCD1234EFGH5678' }],
        ['/v2/subscription', null], ['/v2/subscription', []], ['/v2/subscription', 1],
    ]) {
        const f = fixture(), response = await handleAppRequest(request(path, body), f);
        assert.equal(response.status, 400); assert.equal(f.calls.length, 0);
    }
    for (const code of [null, 1, {}, '', 'A'.repeat(65)]) {
        const f = fixture(), response = await handleAppRequest(request('/v2/redeem/preview', { code }), f);
        assert.equal(response.status, 400); assert.deepEqual(await response.json(), { error: 'INVALID_CODE' }); assert.equal(f.calls.length, 0);
    }
    for (const quoteId of [null, 1, {}, '', 'A'.repeat(48), 'g'.repeat(48), '0'.repeat(47), '0'.repeat(49)]) {
        const f = fixture(), response = await handleAppRequest(request('/v2/redeem/confirm', { quoteId }), f);
        assert.equal(response.status, 400); assert.equal(f.calls.length, 0);
    }
});

test('v2 retains bearer, source-IP, content-type, JSON and rate admission checks', async () => {
    for (const path of paths) {
        const f = fixture(), unauthorized = await handleAppRequest(request(path, requestBody(path), null), f);
        assert.equal(unauthorized.status, 401); assert.equal(f.calls.length, 0);
        const badToken = await handleAppRequest(request(path, requestBody(path), 'C'.repeat(43)), f);
        assert.equal(badToken.status, 401); assert.equal(f.calls.length, 0);
        for (const mutate of [req => req.headers.delete('X-Lunagram-Client-IP'),
            req => req.headers.set('Content-Type', 'text/plain'), req => req.headers.set('Content-Encoding', 'gzip')]) {
            const req = request(path, requestBody(path)); mutate(req);
            assert.equal((await handleAppRequest(req, f)).status, 400); assert.equal(f.calls.length, 0);
        }
    }
    const f = fixture(); f.control.rate = () => { throw new BillingError('RATE_LIMITED'); };
    assert.equal((await handleAppRequest(request('/v2/subscription'), f)).status, 429);
    assert.equal(f.calls.length, 0);
    const malformed = new Request('https://billing.internal/v2/subscription', {
        method: 'POST', headers: request('/').headers, body: '{' });
    assert.equal((await handleAppRequest(malformed, fixture())).status, 400);
});

test('new actionable errors are sanitized, with only a validated legacy-expiry detail', async () => {
    for (const code of ['UPGRADE_REQUIRED', 'LEGACY_TERM_ACTIVE', 'QUOTE_EXPIRED', 'QUOTE_STALE', 'QUEUE_FULL']) {
        const error = Object.assign(new BillingError(code), { secret: 'must-not-leak', legacyExpiresAtMs: NOW + 1000 });
        const f = fixture(() => { throw error; });
        const response = await handleAppRequest(request('/v2/subscription'), f);
        assert.equal(response.status, 400);
        const expected = { error: code, ...(code === 'LEGACY_TERM_ACTIVE' ? { legacyExpiresAtMs: NOW + 1000 } : {}) };
        assert.deepEqual(await response.json(), expected);
        const forwarded = await proxy(expected, '/v2/subscription', 400);
        assert.equal(forwarded.status, 400); assert.deepEqual(await forwarded.json(), expected);
    }
    for (const legacyExpiresAtMs of [null, '123', -1, 0, 0.5, Infinity, Number.MAX_SAFE_INTEGER]) {
        assert.deepEqual(await appError({ code: 'LEGACY_TERM_ACTIVE', legacyExpiresAtMs }).json(), { error: 'LEGACY_TERM_ACTIVE' });
    }
    assert.deepEqual(await appError({ code: 'PRIVATE_DATABASE_ERROR', message: 'secret' }).json(), { error: 'NOT_READY' });
});

test('proxy preserves active/inactive/queued status and rejects malformed public fields', async () => {
    const current = term(), queued = term(2, { startsAtMs: current.expiresAtMs, planId: 'ultra', months: 12 });
    for (const body of [status(), active(), active({ queued: [queued], paidUntilMs: queued.expiresAtMs }),
        status({ queued: [queued], paidUntilMs: queued.expiresAtMs }), status({ paidUntilMs: NOW - 1 })]) {
        const response = await proxy(body);
        assert.equal(response.status, 200); assert.deepEqual(await response.json(), body);
    }
    for (const patch of [{ schemaVersion: 1 }, { featuresGated: false }, { telegramUserId: '00101' },
        { telegramUserId: '4503599627370496' }, { telegramUserId: 101 }, { serverNowMs: '1' }, { serverNowMs: -1 },
        { entitlementVersion: 1.5 }, { queueVersion: -1 }, { active: 1 }, { active: true }, { planId: 'pro' },
        { expiresAtMs: 1 }, { paidUntilMs: -1 }, { paidUntilMs: Number.MAX_SAFE_INTEGER }, { current: {} }, { queued: {} }]) {
        await rejectedProxy(status(patch));
    }
    const missing = status(); delete missing.queueVersion; await rejectedProxy(missing);
});

test('nested records reject secrets, malformed terms, overlap and mismatched active summaries', async () => {
    for (const patch of [{ secret: 'private' }, { token: TOKEN }, { ownerHash: 'a'.repeat(64) }]) await rejectedProxy(active(patch));
    for (const patch of [{ ownerHash: 'a'.repeat(64) }, { grantId: 'b'.repeat(64) }, { code: 'PRIVATEKEY' },
        { orderId: 'A'.repeat(48) }, { orderId: 'x' }, { planId: 'unknown' }, { months: '3' }, { months: 2 },
        { startsAtMs: NOW + 1 }, { expiresAtMs: NOW }, { startsAtMs: -1 }]) {
        const value = active(); value.current = { ...value.current, ...patch }; await rejectedProxy(value);
    }
    for (const patch of [{ active: false }, { planId: 'pro' }, { expiresAtMs: NOW + 1 }, { paidUntilMs: 0 }]) await rejectedProxy(active(patch));
    const first = term(), second = term(2, { startsAtMs: first.expiresAtMs });
    for (const queued of [[{ ...second, orderId: first.orderId }], [term(2, { startsAtMs: first.expiresAtMs - 1 })],
        [second, term(3, { startsAtMs: second.startsAtMs })], [null]]) {
        await rejectedProxy(active({ queued, paidUntilMs: addCalendarMonths(NOW, 12) }));
    }
    const f = fixture(active({ current: { ...first, code: 'must-not-leak' } }));
    const response = await handleAppRequest(request('/v2/subscription'), f);
    assert.equal(response.status, 503); assert.deepEqual(await response.json(), { error: 'NOT_READY' });
});

test('legacy active status has one exact representation and a bounded legacy error', async () => {
    const end = addCalendarMonths(NOW, 1);
    const legacy = status({ legacy: true, legacyExpiresAtMs: end, active: true, planId: 'plus', expiresAtMs: end, paidUntilMs: end });
    const response = await proxy(legacy);
    assert.equal(response.status, 200); assert.deepEqual(await response.json(), legacy);
    for (const patch of [{ legacy: false }, { legacyExpiresAtMs: 'x' }, { legacyExpiresAtMs: NOW }, { current: term() },
        { queued: [term(2, { startsAtMs: end })] }, { active: false }, { planId: 'bad' }, { paidUntilMs: end - 1 }, { expiresAtMs: end - 1 }]) {
        await rejectedProxy({ ...legacy, ...patch });
    }
    const missing = { ...legacy }; delete missing.legacy; await rejectedProxy(missing);
    await rejectedProxy({ ...legacy, quote: preview().quote }, '/v2/redeem/preview');
    await rejectedProxy({ error: 'QUOTE_EXPIRED', legacyExpiresAtMs: end }, '/v2/redeem/preview', 400);
    await rejectedProxy({ error: 'LEGACY_TERM_ACTIVE', legacyExpiresAtMs: '123' }, '/v2/redeem/preview', 400);
});

test('preview quote is strictly bounded but may start months after its confirmation deadline', async () => {
    const startsAtMs = addCalendarMonths(NOW, 12);
    const value = preview(); value.quote.startsAtMs = startsAtMs; value.quote.expiresAtMs = addCalendarMonths(startsAtMs, 3);
    const response = await proxy(value, '/v2/redeem/preview');
    assert.equal(response.status, 200); assert.deepEqual(await response.json(), value);
    for (const patch of [{ code: 'PRIVATEKEY' }, { orderId: id(1) }, { quoteId: 'A'.repeat(48) },
        { planId: 'unknown' }, { months: 2 }, { starsAmount: '180' }, { starsAmount: 0 }, { starsAmount: 1_000_001 },
        { starsAmount: 1.5 }, { startsAtMs: NOW - 1 }, { expiresAtMs: NOW }, { validUntilMs: NOW },
        { validUntilMs: NOW + 130_001 }, { queueVersion: 1 }]) {
        await rejectedProxy(preview({ quote: { ...preview().quote, ...patch } }), '/v2/redeem/preview');
    }
    await rejectedProxy(status(), '/v2/redeem/preview');
    await rejectedProxy(preview(), '/v2/subscription');
});

test('confirm activation is a term only and supports an idempotent historical result', async () => {
    const historical = term(7, { startsAtMs: Date.UTC(2020, 0, 1), months: 1 });
    const body = status({ activation: historical, paidUntilMs: historical.expiresAtMs });
    const response = await proxy(body, '/v2/redeem/confirm');
    assert.equal(response.status, 200); assert.deepEqual(await response.json(), body);
    await rejectedProxy({ ...body, activation: { ...historical, token: TOKEN } }, '/v2/redeem/confirm');
    await rejectedProxy(status(), '/v2/redeem/confirm');
    await rejectedProxy({ ...body, quote: preview().quote }, '/v2/redeem/confirm');
});

test('100 queued terms fit the bounded 32 KiB proxy contract; 101 is rejected', async () => {
    const queued = []; let start = NOW + 1;
    for (let i = 0; i < 100; i++) {
        const next = term(i + 1, { startsAtMs: start, months: 1 }); queued.push(next); start = next.expiresAtMs;
    }
    const body = status({ queued, paidUntilMs: start });
    const bytes = Buffer.byteLength(JSON.stringify(body));
    assert.ok(bytes < 32 * 1024, 'Maximum queue fits the explicitly agreed v2 response bound');
    const response = await proxy(body); assert.equal(response.status, 200); assert.deepEqual(await response.json(), body);
    queued.push(term(101, { startsAtMs: start, months: 1 }));
    await rejectedProxy({ ...body, queued, paidUntilMs: queued.at(-1).expiresAtMs });
});

test('proxy v2 retains fixed service, authenticated header, real source IP and safe errors only', async () => {
    const req = request('/v2/subscription'); req.headers.set('X-Lunagram-Client-IP', '9.9.9.9'); req.headers.set('Cookie', 'secret');
    const response = await proxyAppRequest(req, { BILLING_SERVICE: { fetch: async forwarded => {
        assert.equal(forwarded.url, 'https://billing.internal/v2/subscription');
        assert.equal(forwarded.headers.get('Authorization'), `Bearer ${TOKEN}`);
        assert.equal(forwarded.headers.get('X-Lunagram-Client-IP'), '1.2.3.4');
        assert.equal(forwarded.headers.get('Cookie'), null); assert.equal(forwarded.redirect, 'manual');
        return jsonResponse(status());
    } } });
    assert.equal(response.status, 200); assert.equal(response.headers.get('Cache-Control'), 'no-store');
    await rejectedProxy({ error: 'PRIVATE_ERROR', stack: 'secret' }, '/v2/subscription', 400);
    await rejectedProxy({ error: 'UNAUTHENTICATED', token: TOKEN }, '/v2/subscription', 401);
    await rejectedProxy({ error: 'QUOTE_STALE' }, '/v2/subscription', 200);
    await rejectedProxy(status(), '/v2/subscription', 202);
});

test('legacy subscription and direct redemption keep their original DTO and dispatch', async () => {
    for (const [path, body, method, expectedArgs] of [
        ['/v1/subscription', {}, 'getSubscription', ['101', NOW]],
        ['/v1/redeem', { code: 'ABCD1234EFGH5678' }, 'redeem', ['101', 'ABCD1234EFGH5678']],
    ]) {
        const f = fixture(), old = { active: false, planId: null, expiresAtMs: 0, featuresGated: false };
        f.store[method] = (...args) => { assert.deepEqual(args, expectedArgs); return old; };
        const response = await handleAppRequest(request(path, body), f);
        assert.equal(response.status, 200); assert.deepEqual(await response.json(), { telegramUserId: '101', ...old });
        assert.equal(f.calls.length, 0);
    }
});
