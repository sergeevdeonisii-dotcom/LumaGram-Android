import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import core from '../cloudflare/core-worker.mjs';
import { billingStoreStub, BILLING_OBJECT_NAME, BillingStore } from '../cloudflare/billing-object.mjs';
import pages from '../cloudflare/pages/public/_worker.js';
import { publicPayload, publicResponse } from '../cloudflare/public-http.mjs';

const request = (path, method = 'GET') => new Request(`https://public.example${path}`, { method });
const readConfig = name => JSON.parse(readFileSync(new URL(name, import.meta.url), 'utf8'));

function noReadRequest(path, method) {
    return { method, url: `https://public.example${path}`,
        get body() { throw new Error('request body must not be read'); },
        get headers() { throw new Error('request headers must not be read'); },
        json() { throw new Error('no parsing'); }, text() { throw new Error('no parsing'); },
    };
}

test('disabled health and catalog inspect rollout flag only, never DO bindings or secrets', async () => {
    const forbiddenEnv = new Proxy({}, { get(_target, name) {
        if (name === 'LIVE_BOT_ENABLED') return 'false';
        throw new Error('no binding access for disabled public reads');
    } });
    for (const path of ['/health', '/v1/catalog']) {
        const response = await core.fetch(noReadRequest(path, 'GET'), forbiddenEnv);
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), publicPayload(path));
    }
});

test('health is liveness only and explicitly does not claim storage/payment readiness', async () => {
    const payload = await (await core.fetch(request('/health'))).json();
    assert.equal(payload.status, 'not_ready');
    assert.equal(payload.storageStatus, 'unverified');
    assert.equal(payload.paymentsAvailable, false);
    assert.equal(payload.activationAvailable, false);
});

test('catalog prices stay exact; every purchase method is unavailable and features ungated', async () => {
    const payload = await (await core.fetch(request('/v1/catalog'))).json();
    assert.deepEqual(payload.plans.map(p => p.usdCents), [50, 100, 140, 200, 500]);
    assert.equal(payload.featuresGated, false);
    assert.equal(payload.manualRenewal, true);
    assert.ok(payload.paymentMethods.every(p => p.available === false));
    assert.deepEqual(payload.plans.map(p => p.starsAmount), [40, 70, 100, 140, 360]);
    assert.equal(payload.paymentMethods.find(p => p.id === 'telegram_stars').priceStatus, 'configured');
    assert.ok(payload.paymentMethods.filter(p => p.id !== 'telegram_stars').every(p => p.priceStatus === 'not_configured'));
    assert.equal(JSON.stringify(payload).includes('UQDw'), false);
});

test('mutations, queries, unknown paths and all non-GET methods are 503 without parsing bodies', async () => {
    const env = { enabled: true, BILLING_ENABLED: true, PAYMENTS_ENABLED: true, BOT_TOKEN: 'secret-not-for-response' };
    const paths = ['/', '/v1/redeem', '/v1/pay', '/webhook', '/auth', '/internal/storage-probe', '/health?token=secret', '/v1/catalog/'];
    for (const path of paths) for (const method of ['GET', 'POST', 'HEAD', 'OPTIONS', 'DELETE']) {
        const response = await core.fetch(noReadRequest(path, method), env);
        assert.equal(response.status, 503, `${method} ${path}`);
        assert.equal((await response.json()).error, 'NOT_READY');
    }
    for (const method of ['POST', 'PUT', 'OPTIONS', 'HEAD']) assert.equal((await core.fetch(noReadRequest('/health', method), env)).status, 503);
});

test('Pages only invokes service binding with a fresh anonymous exact-path GET', async () => {
    let calls = 0;
    const env = { BILLING_SERVICE: { async fetch(upstreamRequest) {
        calls++;
        assert.equal(upstreamRequest.url, 'https://billing.internal/v1/catalog');
        assert.equal(upstreamRequest.method, 'GET');
        assert.equal(upstreamRequest.redirect, 'manual'); // workerd-compatible, never follow upstream redirects.
        assert.deepEqual([...upstreamRequest.headers], [['accept', 'application/json']]);
        assert.equal(upstreamRequest.body, null);
        return core.fetch(upstreamRequest);
    } } };
    const response = await pages.fetch(noReadRequest('/v1/catalog', 'GET'), env);
    assert.equal(response.status, 200);
    assert.equal(calls, 1);
    assert.deepEqual(await response.json(), publicPayload('/v1/catalog'));
});

test('Pages rejects unsupported requests before looking up any binding', async () => {
    const forbiddenEnv = new Proxy({}, { get() { throw new Error('must not touch binding'); } });
    for (const [path, method] of [['/auth','POST'], ['/health','POST'], ['/health?secret=anything','GET'], ['/v1/activate','POST']]) {
        const response = await pages.fetch(noReadRequest(path, method), forbiddenEnv);
        assert.equal(response.status, 503);
    }
});

test('upstream redirects are rejected without following or exposing their destination', async () => {
    for (const status of [301, 302, 303, 307, 308]) {
        let calls = 0;
        const response = await pages.fetch(request('/health'), { BILLING_SERVICE: { async fetch(bound) {
            calls++;
            assert.equal(bound.redirect, 'manual');
            return Response.redirect('https://untrusted.example/private-token', status);
        } } });
        assert.equal(calls, 1);
        assert.equal(response.status, 503);
        assert.equal(response.headers.get('Location'), null);
        assert.equal((await response.text()).includes('private-token'), false);
    }
});

test('missing or failing binding and upstream errors are sanitized, never reflected', async () => {
    const secret = 'private-account-identifier-that-must-not-leak';
    const envs = [{}, { BILLING_SERVICE: { fetch() { throw new Error(secret); } } },
        { BILLING_SERVICE: { fetch: async () => new Response(secret, { status: 500 }) } },
        { BILLING_SERVICE: { fetch: async () => new Response(secret, { status: 200, headers: { 'Content-Type': 'application/json' } }) } },
        { BILLING_SERVICE: { fetch: async () => Response.redirect('https://private.example/') } }];
    for (const env of envs) {
        const response = await pages.fetch(request('/health'), env);
        assert.equal(response.status, 503);
        assert.equal((await response.text()).includes(secret), false);
    }
});

test('Pages enforces response contract/size and strips upstream cookie/CORS headers', async () => {
    const oversized = new Response('x'.repeat(8193), { headers: { 'Content-Type': 'application/json' } });
    assert.equal((await pages.fetch(request('/health'), { BILLING_SERVICE: { fetch: async () => oversized } })).status, 503);
    const upstream = publicResponse('/health');
    upstream.headers.set('Set-Cookie', 'private=value');
    upstream.headers.set('Access-Control-Allow-Origin', '*');
    upstream.headers.set('X-Source-Account', 'private');
    const response = await pages.fetch(request('/health'), { BILLING_SERVICE: { fetch: async () => upstream } });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('Set-Cookie'), null);
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
    assert.equal(response.headers.get('X-Source-Account'), null);
});

test('all HTTP responses use no-store/no-sniff and no wildcard CORS', async () => {
    for (const handler of [core, pages]) for (const path of ['/health', '/v1/catalog', '/pay']) {
        const response = await handler.fetch(request(path), { BILLING_SERVICE: { fetch: req => core.fetch(req) } });
        assert.equal(response.headers.get('Cache-Control'), 'no-store');
        assert.equal(response.headers.get('X-Content-Type-Options'), 'nosniff');
        assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
        const body = await response.text();
        assert.equal(/github|account_id|sergeev|denis228|workers\.dev/i.test(body), false);
    }
});

test('stable DO helper never derives identity from a caller or per-user value', () => {
    const seen = [];
    const env = { BILLING_STORE: { idFromName(name) { seen.push(name); return `id:${name}`; }, get(id) { return { id }; } } };
    assert.equal(billingStoreStub(env).id, 'id:billing-v1');
    assert.equal(billingStoreStub(env).id, 'id:billing-v1');
    assert.deepEqual(seen, [BILLING_OBJECT_NAME, BILLING_OBJECT_NAME]);
});

test('DO fetch is also unavailable and does not accept request data', async () => {
    // Prototype call deliberately avoids constructor/schema initialization.
    const response = await BillingStore.prototype.fetch.call({}, noReadRequest('/pay', 'POST'));
    assert.equal(response.status, 503);
});

test('source-only configs keep private Worker, SQLite migration, neutral Pages name and no credentials', () => {
    const worker = readConfig('../cloudflare/wrangler.jsonc');
    const proxy = readConfig('../cloudflare/pages/wrangler.jsonc');
    assert.equal(worker.name, 'lunagram-billing-core');
    assert.equal(worker.workers_dev, false);
    assert.equal(worker.preview_urls, false);
    assert.deepEqual(worker.routes, []);
    assert.equal(worker.observability.enabled, false);
    assert.deepEqual(worker.migrations, [{ tag: 'v1', new_sqlite_classes: ['BillingStore'] }]);
    assert.deepEqual(worker.durable_objects.bindings, [{ name: 'BILLING_STORE', class_name: 'BillingStore' }]);
    assert.equal(proxy.name, 'lunagram-billing');
    assert.equal(proxy.pages_build_output_dir, './public');
    assert.deepEqual(proxy.services, [{ binding: 'BILLING_SERVICE', service: 'lunagram-billing-core' }]);
    for (const config of [worker, proxy]) for (const forbidden of ['account_id','secrets','triggers']) assert.equal(config[forbidden], undefined);
    assert.deepEqual(worker.vars, { LIVE_BOT_ENABLED: 'true', BILLING_PAYMENTS_ENABLED: 'false', TELEGRAM_WEBHOOK_IP: '172.66.44.64' });
    assert.equal(proxy.vars, undefined);
    assert.deepEqual(readConfig('../cloudflare/pages/public/_routes.json'), { version: 1, include: ['/*'], exclude: [] });
    assert.deepEqual(readdirSync(new URL('../cloudflare/pages/public/', import.meta.url)).sort(), ['_routes.json','_worker.js']);
});
