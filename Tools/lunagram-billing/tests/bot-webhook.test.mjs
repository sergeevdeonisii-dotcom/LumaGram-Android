import test from 'node:test';
import assert from 'node:assert/strict';
import { createBotWebhookHandler, handleBotWebhook, webhookAdmission, MAX_WEBHOOK_BODY_BYTES,
    WEBHOOK_BODY_TIMEOUT_MS } from '../cloudflare/bot-webhook.mjs';

const secret = 'TestOnly_NotARealWebhookSecret_123456789';
const nowMs = 1_800_000_000_000;
const botUsername = 'LunagramAppPayBot';
const baseHeaders = { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': secret };
function update(text = '/start', id = 42) {
    return { update_id: id, message: {
        message_id: 1, date: nowMs / 1000, text,
        from: { id: 12345, is_bot: false, first_name: 'INCOMING_PRIVATE_DATA' },
        chat: { id: 12345, type: 'private' },
        entities: [{ type: 'bot_command', offset: 0, length: text.split(' ')[0].length }],
    } };
}
function request(body = JSON.stringify(update()), changes = {}) {
    return new Request(changes.url ?? 'https://billing.example/telegram/webhook', {
        method: 'POST', headers: baseHeaders, body, duplex: 'half', ...changes,
    });
}
function repository() {
    // Deliberate unit-test fixture only. Production requires the actual durable
    // SQLite adapter; this module does not ship a memory-store fallback.
    const seen = new Set();
    const calls = [];
    return { durable: true, calls, async claim(...args) {
        calls.push(args);
        if (seen.has(args[0])) return 'duplicate';
        seen.add(args[0]);
        return 'claimed';
    } };
}
function options(store = repository()) { return { webhookSecret: secret, botUsername, repository: store, clock: () => nowMs }; }
async function ignored(result) { assert.equal(result.status, 200); assert.deepEqual(await result.json(), {}); }
function unreadRequest(headers = baseHeaders) {
    return { method: 'POST', url: 'https://billing.example/telegram/webhook', headers: new Headers(headers),
        get body() { assert.fail('body must not be read'); } };
}

test('header-only admission is synchronous and never touches body, clock or repository', () => {
    const opts = { webhookSecret: secret, botUsername };
    for (const key of ['repository', 'clock']) {
        Object.defineProperty(opts, key, { get() { assert.fail(`${key} must not be accessed`); } });
    }
    assert.equal(webhookAdmission(unreadRequest(), opts), null);
    const real = request();
    assert.equal(webhookAdmission(real, opts), null);
    assert.equal(real.bodyUsed, false);
});

test('admission returns only sanitized route/config/auth/media rejection Responses', async () => {
    const opts = { webhookSecret: secret, botUsername };
    for (const key of ['repository', 'clock']) {
        Object.defineProperty(opts, key, { get() { assert.fail(`${key} must not be accessed`); } });
    }
    const wrongPath = unreadRequest(); wrongPath.url += '?caller=PRIVATE_DATA';
    const wrongMethod = unreadRequest(); wrongMethod.method = 'GET';
    for (const [input, config, status, error] of [
        [wrongPath, opts, 404, 'NOT_FOUND'],
        [wrongMethod, opts, 404, 'NOT_FOUND'],
        [unreadRequest(), {}, 503, 'NOT_READY'],
        [unreadRequest({ ...baseHeaders, 'X-Telegram-Bot-Api-Secret-Token': 'wrong' }), opts, 403, 'FORBIDDEN'],
        [unreadRequest({ ...baseHeaders, 'Content-Type': 'text/plain' }), opts, 415, 'UNSUPPORTED_MEDIA_TYPE'],
        [unreadRequest({ ...baseHeaders, 'Content-Encoding': 'gzip' }), opts, 415, 'UNSUPPORTED_MEDIA_TYPE'],
    ]) {
        const result = webhookAdmission(input, config);
        assert.ok(result instanceof Response);
        assert.equal(result.status, status);
        assert.deepEqual(await result.json(), { error });
    }
});

test('receiver independently rechecks admission and does not consume a rejected request body', async () => {
    const opts = options();
    const forwarded = request();
    assert.equal(webhookAdmission(forwarded, opts), null);
    forwarded.headers.set('X-Telegram-Bot-Api-Secret-Token', 'replaced');
    assert.equal((await handleBotWebhook(forwarded, opts)).status, 403);
    assert.equal(forwarded.bodyUsed, false);
    assert.equal(opts.repository.calls.length, 0);
    const valid = request();
    assert.equal(webhookAdmission(valid, opts), null);
    assert.equal((await handleBotWebhook(valid, opts)).status, 200);
    assert.equal(opts.repository.calls.length, 1);
});

test('valid four commands return plain-text Telegram webhook sendMessage payload, never an invoice', async () => {
    for (const command of ['start', 'help', 'plans', 'paysupport']) {
        const store = repository();
        const result = await createBotWebhookHandler(options(store))(request(JSON.stringify(update('/' + command))));
        assert.equal(result.status, 200);
        const body = await result.json();
        assert.equal(body.method, 'sendMessage');
        assert.equal(body.chat_id, '12345');
        assert.match(body.text, /пока не запущены/);
        assert.equal(body.parse_mode, undefined);
        assert.equal(body.invoice_payload, undefined);
        assert.equal(body.provider_token, undefined);
        assert.equal(body.reply_markup, undefined);
        assert.ok(!JSON.stringify(body).includes(secret));
        assert.ok(!JSON.stringify(body).includes('INCOMING_PRIVATE_DATA'));
        assert.deepEqual(store.calls, [[42, '12345', nowMs]]);
    }
});

test('unknown methods, non-exact paths, query and fragments are rejected without body/store access', async () => {
    const opts = options();
    Object.defineProperty(opts, 'repository', { get() { assert.fail('store access'); } });
    for (const url of ['https://billing.example/telegram/webhook/', 'https://billing.example/telegram/webhook?x=1',
        'https://billing.example/telegram/webhook#fragment', 'https://billing.example/telegram/other']) {
        const value = unreadRequest(); value.url = url;
        assert.equal((await handleBotWebhook(value, opts)).status, 404);
    }
    for (const method of ['GET', 'PUT', 'OPTIONS', 'DELETE', 'HEAD']) {
        const value = unreadRequest(); value.method = method;
        assert.equal((await handleBotWebhook(value, opts)).status, 404);
    }
});

test('missing or invalid configuration fails closed without body/store access', async () => {
    for (const webhookSecret of [undefined, '', 'short', 'a'.repeat(257), 'a'.repeat(32) + '\n', ' '.repeat(32)]) {
        assert.equal((await handleBotWebhook(unreadRequest(), { webhookSecret, botUsername })).status, 503);
    }
    for (const name of [undefined, '', '@LunagramAppPayBot', 'bad name', 'a'.repeat(33), 'LunagramAppPayBot\n']) {
        assert.equal((await handleBotWebhook(unreadRequest(), { webhookSecret: secret, botUsername: name })).status, 503);
    }
});

test('bad or missing secret denies before body parsing or repository access', async () => {
    const opts = options();
    Object.defineProperty(opts, 'repository', { get() { assert.fail('store access'); } });
    for (const supplied of [undefined, '', 'wrong', secret + 'x', secret.slice(0, -1), 'b'.repeat(secret.length),
        'a'.repeat(257), 'a'.repeat(32) + '!', 'ä'.repeat(32)]) {
        const headers = { 'Content-Type': 'application/json' };
        if (supplied !== undefined) headers['X-Telegram-Bot-Api-Secret-Token'] = supplied;
        const result = await handleBotWebhook(unreadRequest(headers), opts);
        assert.equal(result.status, 403);
        assert.deepEqual(await result.json(), { error: 'FORBIDDEN' });
    }
});

test('secret length boundaries authenticate exactly, including 256-character secrets', async () => {
    for (const length of [32, 256]) {
        const boundedSecret = 'S'.repeat(length - 2) + '_-';
        const opts = { ...options(), webhookSecret: boundedSecret };
        const headers = { ...baseHeaders, 'X-Telegram-Bot-Api-Secret-Token': boundedSecret };
        assert.equal((await handleBotWebhook(request(undefined, { headers }), opts)).status, 200);
        headers['X-Telegram-Bot-Api-Secret-Token'] = boundedSecret.slice(0, -1);
        assert.equal((await handleBotWebhook(unreadRequest(headers), opts)).status, 403);
    }
});

test('JSON content type only; charset UTF-8 allowed; encoded/non-JSON bodies never read', async () => {
    for (const contentType of ['text/plain', 'application/jsonp', 'application/json; charset=utf-16', 'application/json; evil=1']) {
        const result = await handleBotWebhook(unreadRequest({ ...baseHeaders, 'Content-Type': contentType }), options());
        assert.equal(result.status, 415);
    }
    assert.equal((await handleBotWebhook(unreadRequest({ ...baseHeaders, 'Content-Encoding': 'gzip' }), options())).status, 415);
    for (const contentType of ['application/json; charset=utf-8', 'Application/JSON; charset="UTF-8"']) {
        assert.equal((await handleBotWebhook(request(undefined, { headers: { ...baseHeaders, 'Content-Type': contentType } }), options())).status, 200);
    }
});

test('malformed JSON, invalid UTF-8 and empty/erroring bodies never touch the repository', async () => {
    const store = repository();
    for (const body of ['{broken', '', new Uint8Array([0x7b, 0xff, 0x7d]), null,
        new ReadableStream({ start(controller) { controller.error(new Error('PRIVATE_ERROR_DATA')); } })]) {
        const result = await handleBotWebhook(request(body), options(store));
        assert.equal(result.status, 400);
        assert.deepEqual(await result.json(), { error: 'INVALID_BODY' });
    }
    assert.equal(store.calls.length, 0);
});

test('32 KiB actual byte bound works with omitted, overstated and understated Content-Length', async () => {
    const raw = JSON.stringify(update());
    const atLimit = raw + ' '.repeat(MAX_WEBHOOK_BODY_BYTES - Buffer.byteLength(raw));
    for (const declared of [undefined, '0', '1', '999999999999']) {
        const headers = { ...baseHeaders };
        if (declared !== undefined) headers['Content-Length'] = declared;
        const store = repository();
        assert.equal((await handleBotWebhook(request(atLimit, { headers }), options(store))).status, 200);
        assert.equal(store.calls.length, 1);
        const largeResult = await handleBotWebhook(request(atLimit + ' ', { headers }), options(store));
        assert.equal(largeResult.status, 413);
        assert.equal(store.calls.length, 1);
    }
});

test('body size counts UTF-8 bytes across stream chunks, cancels immediately on oversize', async () => {
    let cancelled = false;
    const chunks = [new TextEncoder().encode('ä'.repeat(9000)), new TextEncoder().encode('ä'.repeat(9000))];
    const stream = new ReadableStream({
        pull(controller) { if (chunks.length) controller.enqueue(chunks.shift()); },
        cancel() { cancelled = true; },
    });
    const store = repository();
    assert.equal((await handleBotWebhook(request(stream), options(store))).status, 413);
    assert.equal(cancelled, true);
    assert.equal(store.calls.length, 0);
});

test('body read deadline stops a stalled sender without awaiting its stalled cancel', async () => {
    let cancelled = false;
    const stream = new ReadableStream({ cancel() { cancelled = true; return new Promise(() => {}); } });
    const store = repository();
    const start = performance.now();
    const result = await handleBotWebhook(request(stream), options(store));
    assert.equal(result.status, 408);
    assert.ok(performance.now() - start < WEBHOOK_BODY_TIMEOUT_MS + 2000);
    assert.equal(cancelled, true);
    assert.equal(store.calls.length, 0);
});

test('repeated empty chunks cannot retain unbounded arrays or starve the body deadline', async () => {
    let cancelled = false;
    const stream = new ReadableStream({
        pull(controller) { controller.enqueue(new Uint8Array()); },
        cancel() { cancelled = true; },
    });
    const store = repository();
    assert.equal((await handleBotWebhook(request(stream), options(store))).status, 400);
    assert.equal(cancelled, true);
    assert.equal(store.calls.length, 0);
});

test('invalid shape, group, bot, edited, cross-chat and payment updates are ignored 200 without storage', async () => {
    const store = repository();
    const values = [null, [], {}, { update_id: 5, callback_query: {} }, { update_id: 6, pre_checkout_query: {} }];
    for (const change of [
        v => { v.message.chat.type = 'group'; },
        v => { v.message.from.is_bot = true; },
        v => { v.message.chat.id++; },
        v => { v.message.from.id = '12345'; },
        v => { v.edited_message = v.message; delete v.message; },
        v => { v.message.successful_payment = { currency: 'XTR', total_amount: 500 }; },
        v => { v.message.refunded_payment = {}; },
        v => { v.message.text = '/buy'; },
        v => { v.message.text = '/redeem KEY'; },
        v => { v.message.date -= 301; },
        v => { v.message.date += 31; },
    ]) { const value = update(); change(value); values.push(value); }
    for (const value of values) await ignored(await handleBotWebhook(request(JSON.stringify(value)), options(store)));
    assert.equal(store.calls.length, 0);
});

test('deep-link payload is never echoed/redeemed and bot suffix must match configured bot', async () => {
    const result = await handleBotWebhook(request(JSON.stringify(update('/start@LunagramAppPayBot PRIVATE_KEY'))), options());
    const body = await result.json();
    assert.equal(body.method, 'sendMessage');
    assert.ok(!body.text.includes('PRIVATE_KEY'));
    await ignored(await handleBotWebhook(request(JSON.stringify(update('/start@DifferentBot'))), options()));
});

test('replay and rate limiting yield ignored 200, including concurrent same-update delivery', async () => {
    const store = repository();
    const handler = createBotWebhookHandler(options(store));
    const responses = await Promise.all([handler(request()), handler(request())]);
    const bodies = await Promise.all(responses.map(result => result.json()));
    assert.equal(bodies.filter(body => body.method === 'sendMessage').length, 1);
    assert.equal(bodies.filter(body => Object.keys(body).length === 0).length, 1);
    for (const result of ['duplicate', 'rate_limited']) {
        await ignored(await handleBotWebhook(request(), options({ durable: true, claim: async () => result })));
    }
});

test('repository absence, nondurability, error or unknown outcome is 503 and never success', async () => {
    for (const store of [undefined, {}, { durable: false, claim() { assert.fail('claim'); } },
        { durable: true }, { durable: true, claim() { throw new Error('PRIVATE_STORE_DATA'); } },
        ...[undefined, true, false, {}, 'success'].map(result => ({ durable: true, claim: async () => result }))]) {
        const opts = options(); opts.repository = store;
        const result = await handleBotWebhook(request(), opts);
        assert.equal(result.status, 503);
        assert.deepEqual(await result.json(), { error: 'NOT_READY' });
    }
});

test('invalid clock fails closed before claiming and all responses omit CORS/secrets/debug fields', async () => {
    const store = repository();
    for (const clock of [() => NaN, () => -1, () => '1800000000000', () => { throw new Error('PRIVATE_CLOCK'); }]) {
        const result = await handleBotWebhook(request(), { ...options(store), clock });
        assert.equal(result.status, 503);
        assert.equal(result.headers.get('Access-Control-Allow-Origin'), null);
        assert.equal(result.headers.get('Cache-Control'), 'no-store');
        assert.equal(result.headers.get('X-Content-Type-Options'), 'nosniff');
        assert.deepEqual(await result.json(), { error: 'NOT_READY' });
    }
    assert.equal(store.calls.length, 0);
});
