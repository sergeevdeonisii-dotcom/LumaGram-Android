import test from 'node:test';
import assert from 'node:assert/strict';
import { proxyBotWebhook, BOT_PROXY_RESPONSE_BYTES, BOT_PROXY_RESPONSE_TIMEOUT_MS } from '../cloudflare/bot-proxy.mjs';
import { validateOnboardingUpdate, buildOnboardingReply, validateOnboardingReplyPayload } from '../bot-onboarding.mjs';

const SECRET = 'test_proxy_webhook_secret_1234567890';
const HEADER = 'X-Telegram-Bot-Api-Secret-Token';
const NOW = Date.UTC(2026, 9, 12, 12);
const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers } });
const envFor = upstream => ({ BILLING_SERVICE: { fetch: async () => upstream } });
function request(extraHeaders = {}, changes = {}) {
    return new Request(changes.url ?? 'https://lunagram-billing.example/telegram/webhook', {
        method: changes.method ?? 'POST', headers: { [HEADER]: SECRET, 'Content-Type': 'application/json', ...extraHeaders },
        body: changes.body ?? '{}', duplex: 'half',
    });
}
function unreadRequest(changes = {}) {
    return { method: changes.method ?? 'POST', url: changes.url ?? 'https://public.example/telegram/webhook',
        headers: new Headers(changes.headers ?? { [HEADER]: SECRET, 'Content-Type': 'application/json' }),
        get body() { throw new Error('private incoming body must not be accessed'); },
        json() { throw new Error('must not parse incoming JSON'); },
        text() { throw new Error('must not read incoming text'); } };
}
function approvedReply(command = 'start') {
    return buildOnboardingReply(validateOnboardingUpdate({ update_id: 1, message: { message_id: 1, date: NOW / 1000,
        from: { id: 12345, is_bot: false }, chat: { id: 12345, type: 'private' }, text: `/${command}` } },
    { nowMs: NOW, botUsername: 'LunagramAppPayBot' }));
}

test('proxy accepts exactly the four existing plain-text onboarding replies and returns fresh sanitized payloads', async () => {
    for (const command of ['start', 'help', 'plans', 'paysupport']) {
        const original = approvedReply(command);
        const safe = validateOnboardingReplyPayload(original);
        assert.deepEqual(safe, original);
        assert.notEqual(safe, original);
        assert.notEqual(safe.link_preview_options, original.link_preview_options);
        const result = await proxyBotWebhook(request(), envFor(json(original)));
        assert.equal(result.status, 200);
        assert.deepEqual(await result.json(), original);
    }
    const ignored = await proxyBotWebhook(request(), envFor(json({})));
    assert.equal(ignored.status, 200);
    assert.deepEqual(await ignored.json(), {});
});

test('only exact POST webhook without query/fragment is forwarded; invalid paths do not access body or env', async () => {
    const noBinding = new Proxy({}, { get() { throw new Error('must not read env'); } });
    for (const change of [{ method: 'GET' }, { method: 'HEAD' }, { method: 'OPTIONS' },
        { url: 'https://public.example/telegram/webhook/' }, { url: 'https://public.example/telegram/webhook?secret=x' },
        { url: 'https://public.example/telegram/webhook#fragment' }, { url: 'https://public.example/health' }]) {
        const result = await proxyBotWebhook(unreadRequest(change), noBinding);
        assert.equal(result.status, 404);
        assert.deepEqual(await result.json(), { error: 'NOT_FOUND' });
    }
});

test('syntactically invalid secret and unsupported content fail before binding/body access', async () => {
    const noBinding = new Proxy({}, { get() { throw new Error('must not read env'); } });
    for (const secret of [undefined, '', 'short', 'a'.repeat(257), 'a'.repeat(32) + ',other', 'a'.repeat(31) + '!']) {
        const headers = { 'Content-Type': 'application/json' };
        if (secret !== undefined) headers[HEADER] = secret;
        assert.equal((await proxyBotWebhook(unreadRequest({ headers }), noBinding)).status, 403);
    }
    for (const type of ['', 'text/plain', 'application/jsonp', 'application/json; charset=latin1', 'application/json; extra=yes']) {
        assert.equal((await proxyBotWebhook(unreadRequest({ headers: { [HEADER]: SECRET, 'Content-Type': type } }), noBinding)).status, 415);
    }
    for (const encoding of ['gzip', 'identity', '']) {
        assert.equal((await proxyBotWebhook(unreadRequest({ headers: { [HEADER]: SECRET, 'Content-Type': 'application/json', 'Content-Encoding': encoding } }), noBinding)).status, 415);
    }
});

test('missing binding fails closed before body access; valid secret syntax alone does not authorize parsing', async () => {
    for (const env of [undefined, {}, { BILLING_SERVICE: {} }]) {
        const result = await proxyBotWebhook(unreadRequest(), env);
        assert.equal(result.status, 503);
        assert.deepEqual(await result.json(), { error: 'NOT_READY' });
    }
});

test('proxy streams body to only fixed internal service and forwards only secret plus JSON content type', async () => {
    const original = request({ Authorization: 'PRIVATE_AUTH', Cookie: 'PRIVATE_COOKIE',
        'X-Source-Account': 'PRIVATE_ACCOUNT', 'Content-Length': '999999999', 'Content-Type': 'Application/JSON; charset="UTF-8"' },
    { url: 'https://private-personal-host.example/telegram/webhook', body: 'UNPARSED_INBOUND_BODY' });
    let calls = 0;
    const result = await proxyBotWebhook(original, { BILLING_SERVICE: { async fetch(forwarded) {
        calls++;
        assert.equal(forwarded.url, 'https://billing.internal/telegram/webhook');
        assert.equal(forwarded.method, 'POST');
        assert.equal(forwarded.redirect, 'manual');
        assert.deepEqual([...forwarded.headers], [['content-type', 'application/json'], [HEADER.toLowerCase(), SECRET]]);
        assert.equal(original.bodyUsed, false);
        assert.equal(forwarded.body, original.body);
        // Private service would authenticate BEFORE this read. Deliberately
        // return a forbidden response without reading the inbound stream here.
        return json({ error: 'FORBIDDEN' }, 403);
    } } });
    assert.equal(calls, 1);
    assert.equal(result.status, 403);
    assert.equal(original.bodyUsed, false);
});

test('only known status/error pairs pass; arbitrary keys, stack details and mismatches never leak', async () => {
    for (const [status, error] of [[400, 'INVALID_BODY'], [403, 'FORBIDDEN'], [404, 'NOT_FOUND'],
        [408, 'INVALID_BODY'], [413, 'INVALID_BODY'], [415, 'UNSUPPORTED_MEDIA_TYPE'], [503, 'NOT_READY']]) {
        const result = await proxyBotWebhook(request(), envFor(json({ error }, status)));
        assert.equal(result.status, status);
        assert.deepEqual(await result.json(), { error });
    }
    for (const upstream of [json({ error: 'PRIVATE_STACK_ACCOUNT' }, 400),
        json({ error: 'FORBIDDEN', stack: 'PRIVATE_STACK_ACCOUNT' }, 403), json({ error: 'INVALID_BODY' }, 503),
        json({ error: 'FORBIDDEN' }, 200), json({ error: 'NOT_READY' }, 500), json({}, 429)]) {
        const result = await proxyBotWebhook(request(), envFor(upstream));
        assert.equal(result.status, 503);
        assert.deepEqual(await result.json(), { error: 'NOT_READY' });
    }
});

test('arbitrary Bot API methods, recipient shapes, text and extra fields are rejected', async () => {
    const good = approvedReply();
    const bad = [null, [], true, 'string', { ok: true }, { ...good, method: 'sendInvoice' },
        { ...good, text: 'PRIVATE_TEXT_OR_PAYMENT_URL' }, { ...good, text: good.text + '\n' },
        { ...good, reply_markup: { inline_keyboard: [] } }, { ...good, parse_mode: 'HTML' },
        { ...good, chat_id: 12345 }, { ...good, chat_id: '-12345' }, { ...good, chat_id: '@someone' },
        { ...good, chat_id: '012345' }, { ...good, chat_id: '4503599627370496' },
        { ...good, link_preview_options: { is_disabled: false } },
        { ...good, link_preview_options: { is_disabled: true, url: 'https://private.example' } }];
    for (const payload of bad) {
        assert.equal(validateOnboardingReplyPayload(payload), null);
        const result = await proxyBotWebhook(request(), envFor(json(payload)));
        assert.equal(result.status, 503);
        assert.deepEqual(await result.json(), { error: 'NOT_READY' });
    }
});

test('all upstream headers are replaced including cookie, CORS, location and account data', async () => {
    const result = await proxyBotWebhook(request(), envFor(json(approvedReply(), 200, {
        'Set-Cookie': 'private=value', 'Access-Control-Allow-Origin': '*', Location: 'https://private.example',
        'X-Source-Account': 'PRIVATE_ACCOUNT', 'Cache-Control': 'public, max-age=3600',
    })));
    assert.equal(result.status, 200);
    for (const header of ['Set-Cookie', 'Access-Control-Allow-Origin', 'Location', 'X-Source-Account']) assert.equal(result.headers.get(header), null);
    assert.equal(result.headers.get('Cache-Control'), 'no-store');
    assert.equal(result.headers.get('X-Content-Type-Options'), 'nosniff');
    assert.equal(result.headers.get('Referrer-Policy'), 'no-referrer');
    assert.equal(result.headers.get('Content-Security-Policy'), "default-src 'none'; frame-ancestors 'none'");
});

test('redirect, missing body, non-JSON, compressed and throwing upstream responses fail closed', async () => {
    const upstreams = [Response.redirect('https://private.example/'), new Response(null, { status: 200 }),
        new Response('{}', { headers: { 'Content-Type': 'text/plain' } }),
        json({}, 200, { 'Content-Encoding': 'gzip' }), json({}, 200, { 'Content-Type': 'application/json; charset=latin1' })];
    for (const upstream of upstreams) assert.equal((await proxyBotWebhook(request(), envFor(upstream))).status, 503);
    const result = await proxyBotWebhook(request(), { BILLING_SERVICE: { fetch() { throw new Error('PRIVATE_STACK'); } } });
    assert.equal(result.status, 503);
    assert.deepEqual(await result.json(), { error: 'NOT_READY' });
});

test('upstream size is bounded by actual bytes, not content length; invalid JSON/UTF8 is never reflected', async () => {
    for (const body of ['x'.repeat(BOT_PROXY_RESPONSE_BYTES + 1), '"' + 'я'.repeat(4100) + '"',
        '{"invalid"', new Uint8Array([0xff])]) {
        const upstream = new Response(body, { headers: { 'Content-Type': 'application/json', 'Content-Length': '2' } });
        const result = await proxyBotWebhook(request(), envFor(upstream));
        assert.equal(result.status, 503);
        assert.deepEqual(await result.json(), { error: 'NOT_READY' });
    }
});

test('erroring and excessive empty-chunk response streams are bounded sanitized failures', async () => {
    for (const body of [new ReadableStream({ start(controller) { controller.error(new Error('PRIVATE_STREAM_ERROR')); } }),
        new ReadableStream({ pull(controller) { controller.enqueue(new Uint8Array()); } })]) {
        const result = await proxyBotWebhook(request(), envFor(new Response(body, { headers: { 'Content-Type': 'application/json' } })));
        assert.equal(result.status, 503);
        assert.deepEqual(await result.json(), { error: 'NOT_READY' });
    }
});

test('stalled upstream JSON expires within 2s without awaiting an uncooperative cancel', async () => {
    let cancelled = false;
    const body = new ReadableStream({ pull() {}, cancel() { cancelled = true; return new Promise(() => {}); } });
    const started = Date.now();
    const result = await proxyBotWebhook(request(), envFor(new Response(body, { headers: { 'Content-Type': 'application/json' } })));
    const elapsed = Date.now() - started;
    assert.equal(result.status, 503);
    assert.deepEqual(await result.json(), { error: 'NOT_READY' });
    assert.equal(cancelled, true);
    assert.ok(elapsed >= BOT_PROXY_RESPONSE_TIMEOUT_MS - 100 && elapsed < BOT_PROXY_RESPONSE_TIMEOUT_MS + 2500, `elapsed ${elapsed}`);
});
