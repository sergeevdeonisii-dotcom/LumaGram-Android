import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import core from '../cloudflare/core-worker.mjs';
import { BillingStore } from '../cloudflare/billing-object.mjs';
import { BOT_USERNAME } from '../cloudflare/bot-route.mjs';
import { proxyBotWebhook } from '../cloudflare/bot-proxy.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';

const SECRET = 'IntegrationFixtureOnly_NotARealWebhookSecret_123';
function update(id = 41, command = '/start', userId = 12345) {
    return { update_id: id, message: {
        message_id: id + 1, date: Math.floor(Date.now() / 1000), text: command,
        from: { id: userId, is_bot: false, first_name: 'UNTRUSTED_PRIVATE_NAME' },
        chat: { id: userId, type: 'private' },
        entities: [{ type: 'bot_command', offset: 0, length: command.split(' ')[0].length }],
    } };
}
function request(value = update(), { headers = {}, url = 'https://billing.example/telegram/webhook', method = 'POST' } = {}) {
    return new Request(url, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': SECRET, ...headers },
        ...(method !== 'GET' && method !== 'HEAD' ? { body: typeof value === 'string' ? value : JSON.stringify(value) } : {}),
    });
}
function boundEnv(target) {
    const calls = [];
    const env = {
        TELEGRAM_WEBHOOK_SECRET: SECRET,
        BILLING_STORE: {
            idFromName(name) { calls.push(['idFromName', name]); return `test-do:${name}`; },
            get(id) { calls.push(['get', id]); return { fetch: input => target(input) }; },
        },
    };
    return { env, calls };
}
function forbiddenStorage() {
    const calls = [];
    return { calls,
        sql: { exec() { calls.push('sql'); throw new Error('SQL must not be called'); } },
        transactionSync() { calls.push('transaction'); throw new Error('transaction must not be called'); },
    };
}
function database(t) {
    const parent = resolve(tmpdir());
    const directory = mkdtempSync(join(parent, 'lunagram-bot-integration-'));
    const file = join(directory, 'integration.sqlite');
    const opened = new Set();
    const open = () => {
        const storage = openSqliteStorageForTests(file);
        opened.add(storage);
        return { storage, close() { storage.close(); opened.delete(storage); } };
    };
    t.after(() => {
        for (const storage of opened) storage.close();
        assert.equal(dirname(resolve(directory)), parent);
        assert.ok(basename(directory).startsWith('lunagram-bot-integration-'));
        rmSync(directory, { recursive: true, force: true });
    });
    return { open };
}
async function ignored(result) { assert.equal(result.status, 200); assert.deepEqual(await result.json(), {}); }

test('core rejects absent/wrong secret or missing config without reading body or touching DO binding', async () => {
    let bindingReads = 0;
    const env = { TELEGRAM_WEBHOOK_SECRET: SECRET };
    Object.defineProperty(env, 'BILLING_STORE', { get() { bindingReads++; throw new Error('PRIVATE_BINDING_ERROR'); } });
    for (const supplied of [undefined, '', 'wrong', SECRET + 'x']) {
        const input = request();
        if (supplied === undefined) input.headers.delete('X-Telegram-Bot-Api-Secret-Token');
        else input.headers.set('X-Telegram-Bot-Api-Secret-Token', supplied);
        const result = await core.fetch(input, env);
        assert.equal(result.status, 403);
        assert.deepEqual(await result.json(), { error: 'FORBIDDEN' });
        assert.equal(input.bodyUsed, false);
    }
    delete env.TELEGRAM_WEBHOOK_SECRET;
    const input = request();
    assert.equal((await core.fetch(input, env)).status, 503);
    assert.equal(input.bodyUsed, false);
    assert.equal(bindingReads, 0);
});

test('core admits only exact route and JSON, before DO lookup', async () => {
    const { env, calls } = boundEnv(() => assert.fail('DO invocation forbidden'));
    for (const url of ['https://billing.example/telegram/webhook/', 'https://billing.example/telegram/webhook?user=12345',
        'https://billing.example/telegram/webhook#fragment', 'https://billing.example/auth']) {
        const input = request(update(), { url });
        assert.equal((await core.fetch(input, env)).status, 503);
        assert.equal(input.bodyUsed, false);
    }
    for (const method of ['GET', 'HEAD', 'PUT', 'OPTIONS']) {
        const input = request(update(), { method });
        assert.equal((await core.fetch(input, env)).status, 503);
        assert.equal(input.bodyUsed, false);
    }
    for (const headers of [{ 'Content-Type': 'text/plain' }, { 'Content-Encoding': 'gzip' }]) {
        const input = request(update(), { headers });
        assert.equal((await core.fetch(input, env)).status, 415);
        assert.equal(input.bodyUsed, false);
    }
    assert.deepEqual(calls, []);
});

test('valid core requests use one fixed billing-v1 identity and forward only necessary headers', async () => {
    const received = [];
    const { env, calls } = boundEnv(async input => {
        assert.ok(input instanceof Request);
        assert.equal(input.url, 'https://billing.internal/telegram/webhook');
        assert.equal(input.method, 'POST');
        assert.equal(input.redirect, 'manual');
        assert.deepEqual([...input.headers], [
            ['content-type', 'application/json'], ['x-telegram-bot-api-secret-token', SECRET],
        ]);
        received.push(await input.json());
        return new Response('{}', { headers: { 'Content-Type': 'application/json' } });
    });
    for (const userId of [12345, 67890]) {
        await ignored(await core.fetch(request(update(userId, '/start', userId), { headers: {
            Cookie: 'private-cookie', Authorization: 'Bearer caller-value', 'X-User-Id': '999',
        } }), env));
    }
    assert.equal(received.length, 2);
    assert.deepEqual(calls, [
        ['idFromName', 'billing-v1'], ['get', 'test-do:billing-v1'],
        ['idFromName', 'billing-v1'], ['get', 'test-do:billing-v1'],
    ]);
});

test('DO independently denies secret bypass and unrelated routes without any SQL initialization', async () => {
    const storage = forbiddenStorage();
    const object = new BillingStore({ storage }, { TELEGRAM_WEBHOOK_SECRET: SECRET });
    assert.deepEqual(storage.calls, []);
    for (const input of [
        request(update(), { headers: { 'X-Telegram-Bot-Api-Secret-Token': 'wrong' } }),
        request(update(), { url: 'https://billing.internal/v1/redeem' }),
    ]) {
        assert.ok((await object.fetch(input)).status >= 400);
        assert.equal(input.bodyUsed, false);
    }
    assert.deepEqual(storage.calls, []);
    assert.equal(object.botRepository, undefined);
});

test('authenticated invalid, group, edited, stale, cross-chat and payment updates perform zero SQL', async () => {
    const storage = forbiddenStorage();
    let object;
    const { env } = boundEnv(input => object.fetch(input));
    object = new BillingStore({ storage }, env);
    const values = [null, [], {}, { update_id: 1, pre_checkout_query: {} }];
    for (const change of [
        v => { v.message.chat.type = 'group'; },
        v => { v.message.from.is_bot = true; },
        v => { v.message.chat.id++; },
        v => { v.message.from.id = '12345'; },
        v => { v.edited_message = v.message; delete v.message; },
        v => { v.message.successful_payment = { currency: 'XTR', total_amount: 500 }; },
        v => { v.message.text = '/buy'; },
        v => { v.message.date -= 3600; },
        v => { v.message.date += 3600; },
    ]) { const value = update(); change(value); values.push(value); }
    for (const value of values) await ignored(await core.fetch(request(value), env));
    for (const [raw, status] of [['{invalid', 400], ['x'.repeat(32769), 413]]) {
        assert.equal((await core.fetch(request(raw), env)).status, status);
    }
    assert.deepEqual(storage.calls, []);
    assert.equal(object.botRepository, undefined);
});

test('only fresh private commands lazily initialize dedicated bot tables, never financial schema', async t => {
    const { storage } = database(t).open();
    let object;
    const { env } = boundEnv(input => object.fetch(input));
    object = new BillingStore({ storage }, env);
    const tables = () => [...storage.sql.exec("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")].map(row => row.name);
    assert.deepEqual(tables(), []);
    for (const [index, command] of ['start', 'help', 'plans', 'paysupport'].entries()) {
        const result = await core.fetch(request(update(index + 100, `/${command}@${BOT_USERNAME}`)), env);
        assert.equal(result.status, 200);
        const body = await result.json();
        assert.equal(body.method, 'sendMessage');
        assert.equal(body.chat_id, '12345');
        assert.match(body.text, /пока не запущены/);
        assert.ok(!JSON.stringify(body).includes(SECRET));
        assert.ok(!JSON.stringify(body).includes('UNTRUSTED_PRIVATE_NAME'));
    }
    assert.deepEqual(tables(), ['billing_bot_limits', 'billing_bot_updates']);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS total FROM billing_bot_updates')][0].total, 4);
    assert.ok(object.botRepository?.durable);
});

test('same update is not delivered again after a real SQLite close and DO restart', async t => {
    const db = database(t);
    let current = db.open();
    let object;
    const { env, calls } = boundEnv(input => object.fetch(input));
    object = new BillingStore({ storage: current.storage }, env);
    const message = update(601, '/plans');
    assert.equal((await (await core.fetch(request(message), env)).json()).method, 'sendMessage');
    const limits = [...current.storage.sql.exec('SELECT * FROM billing_bot_limits ORDER BY bucket')];
    const claims = [...current.storage.sql.exec('SELECT * FROM billing_bot_updates')];
    current.close();
    current = db.open();
    object = new BillingStore({ storage: current.storage }, env);
    assert.equal(object.botRepository, undefined);
    await ignored(await core.fetch(request(message), env));
    assert.deepEqual([...current.storage.sql.exec('SELECT * FROM billing_bot_limits ORDER BY bucket')], limits);
    assert.deepEqual([...current.storage.sql.exec('SELECT * FROM billing_bot_updates')], claims);
    assert.deepEqual(calls.filter(call => call[0] === 'idFromName').map(call => call[1]), ['billing-v1', 'billing-v1']);
    assert.equal((await (await core.fetch(request(update(602, '/help')), env)).json()).method, 'sendMessage');
});

test('concurrent same-update core deliveries produce one informational reply through one DO', async t => {
    const { storage } = database(t).open();
    let object;
    const { env } = boundEnv(input => object.fetch(input));
    object = new BillingStore({ storage }, env);
    const incoming = update(777);
    const results = await Promise.all([core.fetch(request(incoming), env), core.fetch(request(incoming), env)]);
    const bodies = await Promise.all(results.map(result => result.json()));
    assert.equal(bodies.filter(body => body.method === 'sendMessage').length, 1);
    assert.equal(bodies.filter(body => Object.keys(body).length === 0).length, 1);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS total FROM billing_bot_updates')][0].total, 1);
    assert.ok([...storage.sql.exec('SELECT attempts FROM billing_bot_limits')].every(row => row.attempts === 1));
});

test('missing binding, DO failure and SQLite failure return sanitized 503, never a successful reply', async () => {
    for (const env of [
        { TELEGRAM_WEBHOOK_SECRET: SECRET },
        boundEnv(() => { throw new Error('PRIVATE_DO_DIAGNOSTIC'); }).env,
    ]) {
        const result = await core.fetch(request(), env);
        assert.equal(result.status, 503);
        const body = await result.json();
        assert.equal(body.error, 'NOT_READY');
        assert.equal(body.method, undefined);
        assert.ok(!JSON.stringify(body).includes('PRIVATE_'));
    }
    const storage = forbiddenStorage();
    let object;
    const { env } = boundEnv(input => object.fetch(input));
    object = new BillingStore({ storage }, env);
    const result = await core.fetch(request(), env);
    assert.equal(result.status, 503);
    assert.deepEqual(await result.json(), { error: 'NOT_READY' });
    assert.deepEqual(storage.calls, ['transaction']);
});

test('Pages proxy to core to SQLite DO preserves approved reply, retry dedupe and secret rejection', async t => {
    const { storage } = database(t).open();
    let object;
    const { env, calls } = boundEnv(input => object.fetch(input));
    object = new BillingStore({ storage }, env);
    let serviceCalls = 0;
    const pagesEnv = { BILLING_SERVICE: { fetch(input) {
        serviceCalls++;
        return core.fetch(input, env);
    } } };
    const incoming = update(888, '/plans');
    const first = await proxyBotWebhook(request(incoming), pagesEnv);
    assert.equal(first.status, 200);
    assert.equal(first.headers.get('Access-Control-Allow-Origin'), null);
    assert.equal(first.headers.get('Cache-Control'), 'no-store');
    const body = await first.json();
    assert.equal(body.method, 'sendMessage');
    assert.equal(body.chat_id, '12345');
    assert.match(body.text, /Based — 40 ⭐/);
    assert.match(body.text, /Оплата пока недоступна/);
    await ignored(await proxyBotWebhook(request(incoming), pagesEnv));
    const beforeReject = calls.length;
    const wrongSecret = request(update(889), { headers: { 'X-Telegram-Bot-Api-Secret-Token': 'W'.repeat(40) } });
    const denied = await proxyBotWebhook(wrongSecret, pagesEnv);
    assert.equal(denied.status, 403);
    assert.deepEqual(await denied.json(), { error: 'FORBIDDEN' });
    assert.equal(calls.length, beforeReject);
    assert.equal(serviceCalls, 3);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS total FROM billing_bot_updates')][0].total, 1);
});
