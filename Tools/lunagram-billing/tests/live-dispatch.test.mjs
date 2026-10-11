import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { BillingStore } from '../cloudflare/billing-object.mjs';
import { createLiveStore } from '../cloudflare/live-store.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';

const NOW = Date.UTC(2026, 9, 12, 20);
const USER = '4012345678901';
const keys = { encryptionKey: Buffer.alloc(32, 31), keyPepper: Buffer.alloc(32, 32), authPepper: Buffer.alloc(32, 33) };
const env = { LIVE_BOT_ENABLED: 'true', BILLING_ENCRYPTION_KEY: keys.encryptionKey.toString('base64url'),
    BILLING_KEY_PEPPER: keys.keyPepper.toString('base64url'), BILLING_AUTH_PEPPER: keys.authPepper.toString('base64url') };
const ok = (payload, messageId = 1001) => ({ ok: true, result: { message_id: messageId,
    chat: { id: Number(payload.chat_id), type: 'private' } } });

// File-backed SQL including the test alarm state: closing/reopening really loses
// the JS instance. Only the Bot API and Durable Object alarm scheduler are fake.
function fixture(t) {
    const parent = resolve(tmpdir());
    const directory = mkdtempSync(join(parent, 'lunagram-dispatch-'));
    const file = join(directory, 'dispatch.sqlite');
    const opened = new Set();
    const state = { at: NOW };
    t.mock.method(Date, 'now', () => state.at);
    function open(call = async (_method, payload) => ok(payload), overrides = {}) {
        const sqlStorage = openSqliteStorageForTests(file);
        opened.add(sqlStorage);
        sqlStorage.sql.exec('CREATE TABLE IF NOT EXISTS test_alarm (id INTEGER PRIMARY KEY CHECK(id=1),at_ms INTEGER NOT NULL)');
        const alarmWrites = [];
        const storage = { ...sqlStorage,
            async getAlarm() { return [...sqlStorage.sql.exec('SELECT at_ms FROM test_alarm WHERE id=1')][0]?.at_ms ?? null; },
            async setAlarm(at) {
                assert.ok(Number.isSafeInteger(at)); alarmWrites.push(at);
                sqlStorage.sql.exec('INSERT INTO test_alarm(id,at_ms) VALUES (1,?) ON CONFLICT(id) DO UPDATE SET at_ms=excluded.at_ms', at);
            },
        };
        const store = createLiveStore(storage, { ...keys, clock: () => state.at });
        const object = new BillingStore({ storage }, env);
        const calls = [];
        object.runtime = () => ({ store: { ...store, ...overrides }, api: { async call(method, payload) {
            calls.push({ method, payload }); return call(method, payload);
        } } });
        return { object, storage, store, calls, alarmWrites,
            row(id) { return [...storage.sql.exec('SELECT * FROM billing_bot_outbox WHERE id=?', id)][0]; },
            async fire(at = state.at) {
                state.at = at;
                // Cloudflare removes the current alarm when its handler starts;
                // any newly persisted alarm must be installed by the handler.
                storage.sql.exec('DELETE FROM test_alarm');
                await object.alarm();
            },
            close() { sqlStorage.close(); opened.delete(sqlStorage); },
        };
    }
    t.after(() => {
        for (const storage of opened) storage.close();
        assert.equal(dirname(resolve(directory)), parent);
        assert.ok(basename(directory).startsWith('lunagram-dispatch-'));
        rmSync(directory, { recursive: true, force: true });
    });
    return { state, open };
}

test('dispatcher persists recovery alarm and lease before API, then ACKs exact recipient', async t => {
    const db = fixture(t);
    let connection;
    let id;
    connection = db.open(async (method, payload) => {
        assert.equal(method, 'sendMessage');
        assert.equal(payload.chat_id, USER);
        assert.equal(connection.row(id).state, 'leased');
        assert.equal(connection.row(id).attempts, 1);
        assert.equal(await connection.storage.getAlarm(), NOW + 31_000);
        return ok(payload, 42);
    });
    id = connection.store.enqueueMessage(USER, 'Test delivery', 'dispatch-one').outboxId;
    await connection.fire();
    const row = connection.row(id);
    assert.equal(row.state, 'acknowledged');
    assert.deepEqual(JSON.parse(row.ack_json), { messageId: 42 });
    assert.equal(connection.calls.length, 1);
    assert.equal(connection.store.nextDueAt(), null);
    // One recovery wakeup may remain, but an empty run must not poll forever.
    await connection.fire(NOW + 31_000);
    assert.equal(await connection.storage.getAlarm(), null);
    assert.equal(connection.calls.length, 1);
    assert.deepEqual(connection.alarmWrites, [NOW + 31_000]);
});

test('wrong recipient is not ACKed and retries only after backoff', async t => {
    const db = fixture(t);
    const connection = db.open(async () => ({ ok: true, result: { message_id: 5,
        chat: { id: Number(USER) + 1, type: 'private' } } }));
    const id = connection.store.enqueueMessage(USER, 'Private reply', 'recipient').outboxId;
    await connection.fire();
    assert.equal(connection.row(id).state, 'pending');
    assert.equal(connection.row(id).ack_json, null);
    assert.equal(connection.row(id).next_attempt_at_ms, NOW + 5000);
    assert.equal(await connection.storage.getAlarm(), NOW + 5000);
    await connection.fire(NOW + 4999);
    assert.equal(connection.calls.length, 1);
    assert.equal(connection.row(id).attempts, 1);
    await connection.fire(NOW + 5000);
    assert.equal(connection.calls.length, 2);
    assert.equal(connection.row(id).attempts, 2);
    assert.equal(connection.row(id).next_attempt_at_ms, NOW + 15_000);
});

test('Telegram 429 retry_after survives reopen and an early recovery alarm cannot send', async t => {
    const db = fixture(t);
    let connection = db.open(async () => ({ ok: false, error: 'TELEGRAM_RATE_LIMITED', retryable: true, retryAfterSeconds: 120 }));
    const id = connection.store.enqueueMessage(USER, 'Rate limited reply', 'rate').outboxId;
    await connection.fire();
    assert.equal(connection.row(id).state, 'pending');
    assert.equal(connection.row(id).next_attempt_at_ms, NOW + 120_000);
    assert.equal(await connection.storage.getAlarm(), NOW + 31_000);
    connection.close(); connection = db.open();
    await connection.fire(NOW + 31_000);
    assert.equal(connection.calls.length, 0);
    assert.equal(connection.row(id).attempts, 1);
    await connection.fire(NOW + 120_000);
    assert.equal(connection.calls.length, 1);
    assert.equal(connection.row(id).state, 'acknowledged');
    assert.equal(connection.row(id).attempts, 2);
});

test('network exception is deferred; no untrusted error is persisted as ACK metadata', async t => {
    const db = fixture(t);
    const connection = db.open(async () => { throw new Error('test token must never reach SQL'); });
    const id = connection.store.enqueueMessage(USER, 'Reply', 'exception').outboxId;
    await connection.fire();
    const row = connection.row(id);
    assert.equal(row.state, 'pending');
    assert.equal(row.next_attempt_at_ms, NOW + 5000);
    assert.equal(row.ack_json, null);
    assert.ok(!JSON.stringify(row).includes('test token'));
});

test('crash after durable lease retains recovery alarm; reopen recovers and rejects stale ACK', async t => {
    const db = fixture(t);
    let connection = db.open();
    const id = connection.store.enqueueMessage(USER, 'Survives process restart', 'crash').outboxId;
    const originalStore = connection.store;
    connection.object.runtime = () => ({ store: { ...originalStore, async claimDue(...args) {
        const item = await originalStore.claimDue(...args);
        assert.ok(item); throw new Error('simulated process interruption after lease commit');
    } }, api: { call() { assert.fail('API must not run before simulated crash'); } } });
    await assert.rejects(connection.fire(), /simulated process interruption/);
    const leaseId = connection.row(id).lease_id;
    assert.equal(connection.row(id).state, 'leased');
    assert.equal(await connection.storage.getAlarm(), NOW + 31_000);
    connection.close(); connection = db.open();
    assert.equal(await connection.storage.getAlarm(), NOW + 31_000);
    await connection.fire(NOW + 31_000);
    assert.equal(connection.calls.length, 0);
    assert.equal(connection.row(id).state, 'pending');
    assert.equal(await connection.storage.getAlarm(), NOW + 36_000);
    assert.equal(await connection.store.finishAck(id, leaseId, { messageId: 123 }, NOW + 31_000), 'stale');
    await connection.fire(NOW + 36_000);
    assert.equal(connection.calls.length, 1);
    assert.equal(connection.row(id).state, 'acknowledged');
    assert.equal(connection.row(id).attempts, 2);
    assert.notEqual(connection.row(id).lease_id, leaseId);
});

test('accepted send with lost ACK repeats same paid key after restart, not another grant', async t => {
    const db = fixture(t);
    let connection = db.open();
    connection.store.acceptTerms(USER, '2026-10-12-v1');
    const order = connection.store.createOrder(USER, 'based', 'paid-delivery');
    assert.equal(connection.store.reservePreCheckout({ orderId: order.id, userId: USER, currency: 'XTR',
        totalAmount: 40, queryId: 'dispatch-query' }).ok, true);
    // Deliver the invoice first so only the paid-key job is left for the crash.
    await connection.fire();
    const paid = connection.store.recordPayment({ orderId: order.id, userId: USER, currency: 'XTR',
        totalAmount: 40, chargeId: 'dispatch-charge' });
    const sent = [];
    const originalStore = connection.store;
    connection.object.runtime = () => ({ store: { ...originalStore,
        async finishAck() { throw new Error('storage unavailable after Telegram accepted send'); },
        async defer() { throw new Error('simulated process ended before recovery write'); },
    }, api: { async call(method, payload) { sent.push({ method, payload }); return ok(payload); } } });
    await assert.rejects(connection.fire(), /simulated process ended/);
    assert.equal(connection.row(paid.outboxId).state, 'leased');
    assert.equal(await connection.storage.getAlarm(), NOW + 31_000);
    connection.close(); connection = db.open();
    await connection.fire(NOW + 31_000);
    await connection.fire(NOW + 36_000);
    assert.deepEqual(connection.calls, sent);
    assert.match(connection.calls[0].payload.text, /\b[A-Z0-9]{16}\b/);
    assert.equal(connection.row(paid.outboxId).state, 'acknowledged');
    assert.equal([...connection.storage.sql.exec('SELECT COUNT(*) AS n FROM live_codes')][0].n, 1);
    assert.equal([...connection.storage.sql.exec('SELECT COUNT(*) AS n FROM live_payments')][0].n, 1);
    assert.equal([...connection.storage.sql.exec('SELECT COUNT(*) AS n FROM live_grants')][0].n, 0);
});

test('one alarm sends at most three jobs and schedules remaining work, then stops idle polling', async t => {
    const db = fixture(t);
    const connection = db.open();
    for (let i = 0; i < 5; i++) connection.store.enqueueMessage(USER, `Batch ${i}`, `batch-${i}`);
    await connection.fire();
    assert.equal(connection.calls.length, 3);
    assert.equal([...connection.storage.sql.exec("SELECT COUNT(*) AS n FROM billing_bot_outbox WHERE state='pending'")][0].n, 2);
    assert.equal(await connection.storage.getAlarm(), NOW + 1000);
    await connection.fire(NOW + 1000);
    assert.equal(connection.calls.length, 5);
    assert.equal(await connection.storage.getAlarm(), NOW + 32_000);
    await connection.fire(NOW + 32_000);
    assert.equal(await connection.storage.getAlarm(), null);
    assert.equal(connection.calls.length, 5);
});

test('schedule never replaces an earlier wakeup and disabled runtime never touches storage', async t => {
    const db = fixture(t);
    const connection = db.open();
    await connection.object.schedule(NOW + 5000);
    await connection.object.schedule(NOW + 8000);
    assert.equal(await connection.storage.getAlarm(), NOW + 5000);
    await connection.object.schedule(NOW + 1000);
    assert.deepEqual(connection.alarmWrites, [NOW + 5000, NOW + 1000]);
    const disabled = new BillingStore({ storage: {} }, { ...env, LIVE_BOT_ENABLED: 'false' });
    disabled.runtime = () => { assert.fail('disabled runtime must not initialize secrets/storage'); };
    await disabled.alarm();
});
