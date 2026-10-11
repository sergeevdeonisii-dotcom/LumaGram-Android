import test from 'node:test';
import assert from 'node:assert/strict';
import { createTelegramApi, TELEGRAM_API_TIMEOUT_MS, TELEGRAM_API_MAX_RESPONSE_BYTES, TELEGRAM_WEBHOOK_URL } from '../cloudflare/telegram-api.mjs';

const TOKEN = '123456789:TEST_ONLY_TOKEN_NOT_A_REAL_CREDENTIAL_12345';
const WEBHOOK_SECRET = 'TEST_ONLY_WEBHOOK_SECRET_123456789';
const errorCode = code => error => error.code === code;
const json = (value, status = 200, headers = {}) => new Response(JSON.stringify(value),
    { status, headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers } });
const sent = () => ({ ok: true, result: { message_id: 123, chat: { id: 101, type: 'private', first_name: 'private name' },
    text: 'PRIVATE_KEY_SHOULD_NOT_BE_RETURNED', extra: TOKEN } });
const request = { chat_id: '101', text: 'test only' };
const setup = () => ({ url: TELEGRAM_WEBHOOK_URL, secret_token: WEBHOOK_SECRET, max_connections: 2,
    allowed_updates: ['message', 'callback_query', 'pre_checkout_query'] });
const invoice = () => ({ chat_id: '101', title: 'Lunagram Based', description: 'One month', payload: 'opaque-order',
    provider_token: '', currency: 'XTR', prices: [{ label: 'Based', amount: 40 }] });

test('fixed official origin, method allowlist, POST JSON, manual redirect and no extra credential headers', async () => {
    let observed;
    const api = createTelegramApi(TOKEN, { fetchImpl: async (url, options) => { observed = { url, options }; return json(sent()); } });
    assert.deepEqual(await api.call('sendMessage', request), { ok: true, result: { message_id: 123, chat: { id: 101, type: 'private' } } });
    assert.equal(observed.url, `https://api.telegram.org/bot${TOKEN}/sendMessage`);
    assert.equal(observed.options.method, 'POST');
    assert.equal(observed.options.redirect, 'manual');
    assert.deepEqual(observed.options.headers, { 'Content-Type': 'application/json', Accept: 'application/json' });
    assert.deepEqual(JSON.parse(observed.options.body), request);
    assert.ok(observed.options.signal instanceof AbortSignal);
    for (const method of ['getUpdates', 'deleteWebhook', '../getMe', 'sendMessage?token=other', 'https://evil.invalid']) {
        await assert.rejects(api.call(method, {}), errorCode('INVALID_TELEGRAM_REQUEST'));
    }
});

test('invalid token, config and payload are rejected locally with enum-only errors', async () => {
    for (const token of ['', null, 'short', TOKEN + '/anything', TOKEN + '?x=1', TOKEN + '\n', `https://evil.invalid/${TOKEN}`]) {
        assert.throws(() => createTelegramApi(token), errorCode('NOT_CONFIGURED'));
    }
    let calls = 0;
    const api = createTelegramApi(TOKEN, { fetchImpl: async () => { calls++; return json(sent()); } });
    const circular = {}; circular.self = circular;
    for (const payload of [null, [], 'text', circular, { ...request, text: 'x'.repeat(4097) },
        { ...request, chat_id: '@someone' }, { ...request, chat_id: '-10001' }, { ...request, extra: NaN },
        { ...request, unknown: 'x'.repeat(32768) }, { ...request, value: () => TOKEN }]) {
        await assert.rejects(api.call('sendMessage', payload), errorCode('INVALID_TELEGRAM_REQUEST'));
    }
    assert.equal(calls, 0);
});

test('Stars invoice, query answers and refunds return only safe approved result shapes', async () => {
    const api = createTelegramApi(TOKEN, { fetchImpl: async url => json(url.endsWith('/sendInvoice') ? sent() : { ok: true, result: true }) });
    assert.equal((await api.call('sendInvoice', invoice())).result.message_id, 123);
    for (const [method, payload] of [
        ['answerCallbackQuery', { callback_query_id: 'opaque-query', text: 'OK' }],
        ['answerPreCheckoutQuery', { pre_checkout_query_id: 'opaque-query', ok: true }],
        ['answerPreCheckoutQuery', { pre_checkout_query_id: 'opaque-query', ok: false, error_message: 'Unavailable' }],
        ['refundStarPayment', { user_id: '101', telegram_payment_charge_id: 'opaque:charge' }],
    ]) assert.deepEqual(await api.call(method, payload), { ok: true, result: true });
    for (const patch of [{ currency: 'USD' }, { subscription_period: 2592000 }, { provider_token: TOKEN },
        { prices: [{ label: 'x', amount: 0 }] }, { prices: [{ label: 'x', amount: 1.1 }] }]) {
        await assert.rejects(api.call('sendInvoice', { ...invoice(), ...patch }), errorCode('INVALID_TELEGRAM_REQUEST'));
    }
});

test('send result requires exact private recipient and strips text, names, keys and arbitrary fields', async () => {
    for (const result of [{ ...sent().result, chat: { id: 202, type: 'private' } },
        { ...sent().result, chat: { id: 101, type: 'group' } }, { ...sent().result, message_id: 0 }, true]) {
        const api = createTelegramApi(TOKEN, { fetchImpl: async () => json({ ok: true, result }) });
        assert.deepEqual(await api.call('sendMessage', request), { ok: false, error: 'INVALID_RESPONSE', retryable: false });
    }
    const output = await createTelegramApi(TOKEN, { fetchImpl: async () => json(sent()) }).call('sendMessage', request);
    assert.equal(JSON.stringify(output).includes('PRIVATE_KEY'), false);
    assert.equal(JSON.stringify(output).includes(TOKEN), false);
});

test('API errors expose only enums, retryability and bounded integer retry_after', async () => {
    for (const [status, error_code, expected] of [[429, 429, 'TELEGRAM_RATE_LIMITED'], [200, 429, 'TELEGRAM_RATE_LIMITED'],
        [503, 503, 'TELEGRAM_UNAVAILABLE'], [200, 500, 'TELEGRAM_UNAVAILABLE'], [403, 403, 'TELEGRAM_REJECTED'], [200, 400, 'TELEGRAM_REJECTED']]) {
        const api = createTelegramApi(TOKEN, { fetchImpl: async () => json({ ok: false, error_code,
            description: `${TOKEN} ${WEBHOOK_SECRET} PRIVATE_KEY`, parameters: { retry_after: 12, migrate_to_chat_id: 999 } }, status) });
        const output = await api.call('sendMessage', request);
        assert.equal(output.error, expected);
        assert.equal(output.retryable, expected !== 'TELEGRAM_REJECTED');
        assert.equal(output.retryAfterSeconds, expected === 'TELEGRAM_RATE_LIMITED' ? 12 : undefined);
        assert.equal(JSON.stringify(output).includes(TOKEN), false);
        assert.equal(JSON.stringify(output).includes('PRIVATE_KEY'), false);
    }
    for (const delay of [0, -1, 1.5, '10', 86401, Number.MAX_SAFE_INTEGER]) {
        const api = createTelegramApi(TOKEN, { fetchImpl: async () => json({ ok: false, error_code: 429, parameters: { retry_after: delay } }, 429) });
        assert.equal((await api.call('sendMessage', request)).retryAfterSeconds, undefined);
    }
});

test('network errors and provider HTML errors cannot leak URLs, secrets or raw messages', async () => {
    let api = createTelegramApi(TOKEN, { fetchImpl: async () => { throw new Error(`https://api.telegram.org/bot${TOKEN} PRIVATE_KEY`); } });
    assert.deepEqual(await api.call('sendMessage', request), { ok: false, error: 'TELEGRAM_UNAVAILABLE', retryable: true });
    api = createTelegramApi(TOKEN, { fetchImpl: async () => new Response(`${TOKEN} PRIVATE_KEY`, { status: 502 }) });
    assert.deepEqual(await api.call('sendMessage', request), { ok: false, error: 'TELEGRAM_UNAVAILABLE', retryable: true });
});

test('redirects are never followed or reflected', async () => {
    let calls = 0;
    const api = createTelegramApi(TOKEN, { fetchImpl: async (_url, options) => {
        calls++; assert.equal(options.redirect, 'manual');
        return new Response('', { status: 302, headers: { location: `https://evil.invalid/${TOKEN}` } });
    } });
    assert.deepEqual(await api.call('sendMessage', request), { ok: false, error: 'INVALID_RESPONSE', retryable: false });
    assert.equal(calls, 1);
});

test('actual JSON response bytes, not only Content-Length, are limited to 64 KiB', async () => {
    for (const response of [json({ ...sent(), padding: 'x'.repeat(TELEGRAM_API_MAX_RESPONSE_BYTES) }),
        json(sent(), 200, { 'content-length': String(TELEGRAM_API_MAX_RESPONSE_BYTES + 1) }),
        new Response(new Uint8Array([0xff]), { headers: { 'Content-Type': 'application/json' } }),
        json([]), new Response('{broken', { headers: { 'Content-Type': 'application/json' } })]) {
        const api = createTelegramApi(TOKEN, { fetchImpl: async () => response });
        assert.equal((await api.call('sendMessage', request)).error, 'INVALID_RESPONSE');
    }
    const api = createTelegramApi(TOKEN, { fetchImpl: async () => json({ ...sent(), padding: '界'.repeat(22000) }) });
    assert.equal((await api.call('sendMessage', request)).error, 'INVALID_RESPONSE');
});

test('7-second deadline aborts even a fetch implementation that never settles', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    let signal;
    const api = createTelegramApi(TOKEN, { fetchImpl: (_url, options) => { signal = options.signal; return new Promise(() => {}); } });
    const waiting = api.call('sendMessage', request);
    t.mock.timers.tick(TELEGRAM_API_TIMEOUT_MS);
    assert.deepEqual(await waiting, { ok: false, error: 'TELEGRAM_UNAVAILABLE', retryable: true });
    assert.equal(signal.aborted, true);
});

test('same deadline bounds streaming JSON and actively cancels its reader', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    let canceled = false;
    const stream = new ReadableStream({ pull: () => new Promise(() => {}), cancel() { canceled = true; } });
    const api = createTelegramApi(TOKEN, { fetchImpl: async () => new Response(stream, { headers: { 'Content-Type': 'application/json' } }) });
    const waiting = api.call('sendMessage', request);
    await Promise.resolve(); await Promise.resolve();
    t.mock.timers.tick(TELEGRAM_API_TIMEOUT_MS);
    assert.equal((await waiting).error, 'TELEGRAM_UNAVAILABLE');
    assert.equal(canceled, true);
});

test('setup only permits exact neutral webhook, fixed updates, two connections and approved optional IPs', async () => {
    const api = createTelegramApi(TOKEN, { fetchImpl: async () => json({ ok: true, result: true }) });
    for (const patch of [{}, { ip_address: '172.66.47.192' }, { ip_address: '172.66.44.64' }]) {
        assert.deepEqual(await api.call('setWebhook', { ...setup(), ...patch }), { ok: true, result: true });
    }
    for (const patch of [{ url: TELEGRAM_WEBHOOK_URL + '?key=x' }, { url: 'https://evil.invalid' }, { max_connections: 40 },
        { drop_pending_updates: true }, { ip_address: '127.0.0.1' }, { allowed_updates: ['message'] },
        { allowed_updates: ['message', 'message', 'pre_checkout_query'] }, { secret_token: 'short' }]) {
        await assert.rejects(api.call('setWebhook', { ...setup(), ...patch }), errorCode('INVALID_TELEGRAM_REQUEST'));
    }
});

test('command setup validates the bounded list without accepting scopes or duplicate commands', async () => {
    const api = createTelegramApi(TOKEN, { fetchImpl: async () => json({ ok: true, result: true }) });
    const commands = [{ command: 'start', description: 'Начать' }, { command: 'paysupport', description: 'Помощь с оплатой' }];
    assert.deepEqual(await api.call('setMyCommands', { commands }), { ok: true, result: true });
    for (const payload of [{ commands: [] }, { commands, scope: { type: 'all_private_chats' } },
        { commands: [...commands, commands[0]] }, { commands: [{ command: '/start', description: 'Start' }] },
        { commands: [{ command: 'start', description: 'x'.repeat(257) }] }]) {
        await assert.rejects(api.call('setMyCommands', payload), errorCode('INVALID_TELEGRAM_REQUEST'));
    }
});

test('getMe and getWebhookInfo return sanitized identity/status, never remote URLs or raw errors', async () => {
    const api = createTelegramApi(TOKEN, { fetchImpl: async url => json({ ok: true, result: url.endsWith('/getMe')
        ? { id: 123456789, is_bot: true, username: 'LunagramAppPayBot', first_name: 'PRIVATE_OWNER_NAME', token: TOKEN }
        : { url: TELEGRAM_WEBHOOK_URL, pending_update_count: 4, max_connections: 2, last_error_message: TOKEN, ip_address: '1.2.3.4' } }) });
    assert.deepEqual(await api.call('getMe'), { ok: true, result: { is_bot: true, username: 'LunagramAppPayBot' } });
    assert.deepEqual(await api.call('getWebhookInfo'), { ok: true, result: { configured: true, url: TELEGRAM_WEBHOOK_URL, pending_update_count: 4, max_connections: 2 } });
    const other = createTelegramApi(TOKEN, { fetchImpl: async () => json({ ok: true, result: { url: `https://old.invalid/${TOKEN}`, pending_update_count: 3 } }) });
    assert.deepEqual(await other.call('getWebhookInfo'), { ok: true, result: { configured: false, url: null, pending_update_count: 3 } });
});
