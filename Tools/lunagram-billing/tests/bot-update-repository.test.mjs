import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { createBotUpdateRepository } from '../cloudflare/bot-update-repository.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';

const NOW = Date.UTC(2026, 9, 12, 12);
const DAY = 24 * 60 * 60 * 1000;
const errorCode = code => error => error.code === code;

function database(t) {
    const parent = resolve(tmpdir());
    const directory = mkdtempSync(join(parent, 'lunagram-bot-guard-'));
    const file = join(directory, 'bot.sqlite');
    const opened = new Set();
    const open = () => {
        const storage = openSqliteStorageForTests(file);
        opened.add(storage);
        return { storage, repository: createBotUpdateRepository(storage), close() { storage.close(); opened.delete(storage); } };
    };
    t.after(() => {
        for (const storage of opened) storage.close();
        assert.equal(dirname(resolve(directory)), parent);
        assert.ok(basename(directory).startsWith('lunagram-bot-guard-'));
        rmSync(directory, { recursive: true, force: true });
    });
    return { open };
}

test('bot guard requires synchronous SQLite and advertises durable storage only with that API', t => {
    for (const storage of [{}, { transaction() {}, prepare() {} }, { sql: { exec() {} } }]) {
        assert.throws(() => createBotUpdateRepository(storage), errorCode('SQLITE_STORAGE_REQUIRED'));
    }
    assert.equal(database(t).open().repository.durable, true);
});

test('first update claims once across two instances and a file-backed reopen', async t => {
    const db = database(t);
    const a = db.open();
    const b = db.open();
    const results = await Promise.all([a.repository.claim(123, '101', NOW), b.repository.claim(123, '101', NOW)]);
    assert.deepEqual(results.sort(), ['claimed', 'duplicate']);
    a.close(); b.close();
    const restored = db.open();
    assert.equal(await restored.repository.claim(123, '101', NOW + 1000), 'duplicate');
    assert.equal([...restored.storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_updates')][0].count, 1);
});

test('duplicate update cannot change user/global limits or prolong its retention', async t => {
    const { repository, storage } = database(t).open();
    assert.equal(await repository.claim(1, '101', NOW), 'claimed');
    const beforeLimits = [...storage.sql.exec('SELECT * FROM billing_bot_limits ORDER BY bucket')];
    const beforeUpdates = [...storage.sql.exec('SELECT * FROM billing_bot_updates')];
    assert.equal(await repository.claim(1, '202', NOW + 30000), 'duplicate');
    assert.deepEqual([...storage.sql.exec('SELECT * FROM billing_bot_limits ORDER BY bucket')], beforeLimits);
    assert.deepEqual([...storage.sql.exec('SELECT * FROM billing_bot_updates')], beforeUpdates);
});

test('expired ID may be reused only as a new claim after the caller verifies fresh message identity', async t => {
    const { repository, storage } = database(t).open();
    assert.equal(await repository.claim(1, '101', NOW), 'claimed');
    assert.equal(await repository.claim(1, '101', NOW + DAY - 1), 'duplicate');
    assert.equal(await repository.claim(1, '202', NOW + DAY), 'claimed');
    assert.equal(await repository.claim(1, '202', NOW + DAY + 1), 'duplicate');
    assert.equal([...storage.sql.exec('SELECT expires_at_ms FROM billing_bot_updates WHERE update_id=1')][0].expires_at_ms, NOW + DAY * 2);
});

test('five per-user replies per minute; denied counters and update claims persist after restart', async t => {
    const db = database(t);
    let current = db.open();
    for (let id = 1; id <= 5; id++) assert.equal(await current.repository.claim(id, '101', NOW), 'claimed');
    assert.equal(await current.repository.claim(6, '101', NOW), 'rate_limited');
    current.close(); current = db.open();
    assert.equal(await current.repository.claim(6, '101', NOW), 'duplicate');
    assert.equal(await current.repository.claim(7, '101', NOW), 'rate_limited');
    const rows = [...current.storage.sql.exec('SELECT * FROM billing_bot_limits')];
    assert.equal(rows.find(row => row.bucket.startsWith('user:')).attempts, 6);
    assert.equal(rows.find(row => row.bucket.startsWith('global:')).attempts, 7);
    assert.equal(await current.repository.claim(8, '101', NOW + 60000), 'claimed');
});

test('global 60 per-minute cap survives unique users, denials and restart', async t => {
    const db = database(t);
    let current = db.open();
    for (let id = 1; id <= 60; id++) assert.equal(await current.repository.claim(id, String(1000 + id), NOW), 'claimed');
    assert.equal(await current.repository.claim(61, '9999', NOW), 'rate_limited');
    current.close(); current = db.open();
    assert.equal(await current.repository.claim(62, '8888', NOW), 'rate_limited');
    assert.equal([...current.storage.sql.exec("SELECT attempts FROM billing_bot_limits WHERE bucket LIKE 'global:%'")][0].attempts, 61);
    assert.equal(await current.repository.claim(63, '8888', NOW + 60000), 'claimed');
});

test('invalid update, identity, timestamp and SQL-like input fail closed before writes', async t => {
    const { repository, storage } = database(t).open();
    for (const id of [-1, 1.2, NaN, Infinity, '1', null, Number.MAX_SAFE_INTEGER + 1, "1); DROP TABLE billing_bot_updates; --"]) {
        await assert.rejects(repository.claim(id, '101', NOW));
    }
    for (const id of [101, '0', '-1', '1.1', '9007199254740992', {}, '1; DELETE FROM billing_orders']) {
        await assert.rejects(repository.claim(1, id, NOW));
    }
    for (const now of [-1, 0.5, NaN, Infinity, '1', Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER - DAY + 1]) {
        await assert.rejects(repository.claim(1, '101', now));
    }
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_updates')][0].count, 0);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_limits')][0].count, 0);
});

test('only IDs and expiring numeric counters are persisted, no payload/token storage columns', async t => {
    const { repository, storage } = database(t).open();
    await repository.claim(1, '101', NOW);
    assert.deepEqual([...storage.sql.exec('PRAGMA table_info(billing_bot_updates)')].map(row => row.name), ['update_id', 'expires_at_ms']);
    assert.deepEqual([...storage.sql.exec('PRAGMA table_info(billing_bot_limits)')].map(row => row.name), ['bucket', 'attempts', 'expires_at_ms']);
    assert.ok([...storage.sql.exec('SELECT expires_at_ms FROM billing_bot_limits')].every(row => row.expires_at_ms === NOW + 120000));
    assert.equal([...storage.sql.exec('SELECT expires_at_ms FROM billing_bot_updates')][0].expires_at_ms, NOW + DAY);
});

test('cleanup removes at most 100 total expired rows and never financial history', async t => {
    const { repository, storage } = database(t).open();
    storage.sql.exec('CREATE TABLE billing_orders (id TEXT PRIMARY KEY, payload TEXT NOT NULL)');
    storage.sql.exec('INSERT INTO billing_orders(id,payload) VALUES (?,?)', 'keep', 'financial-record');
    storage.transactionSync(() => {
        for (let id = 1; id <= 70; id++) storage.sql.exec('INSERT INTO billing_bot_updates(update_id,expires_at_ms) VALUES (?,?)', id, NOW - 1);
        for (let id = 1; id <= 70; id++) storage.sql.exec('INSERT INTO billing_bot_limits(bucket,attempts,expires_at_ms) VALUES (?,1,?)', `old-${id}`, NOW - 1);
        storage.sql.exec('INSERT INTO billing_bot_updates(update_id,expires_at_ms) VALUES (?,?)', 900, NOW + 1);
    });
    assert.equal(await repository.claim(1000, '101', NOW), 'claimed');
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_updates WHERE expires_at_ms <= ?', NOW)][0].count, 0);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_limits WHERE expires_at_ms <= ?', NOW)][0].count, 40);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_updates WHERE update_id=900')][0].count, 1);
    assert.equal([...storage.sql.exec('SELECT payload FROM billing_orders WHERE id=?', 'keep')][0].payload, 'financial-record');
    assert.equal(await repository.claim(1001, '101', NOW), 'claimed');
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_limits WHERE expires_at_ms <= ?', NOW)][0].count, 0);
    assert.ok([...storage.sql.exec("SELECT name FROM sqlite_master WHERE type='index'")].some(row => row.name === 'billing_bot_updates_expiry'));
    assert.ok([...storage.sql.exec("SELECT name FROM sqlite_master WHERE type='index'")].some(row => row.name === 'billing_bot_limits_expiry'));
});

test('storage failure rolls back both limits and claim so webhook can safely retry', async t => {
    const { repository, storage } = database(t).open();
    storage.sql.exec(`CREATE TRIGGER fail_bot_claim BEFORE INSERT ON billing_bot_updates
        BEGIN SELECT RAISE(ABORT, 'test-write-failure'); END`);
    await assert.rejects(repository.claim(1, '101', NOW), /test-write-failure/);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_limits')][0].count, 0);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_updates')][0].count, 0);
    storage.sql.exec('DROP TRIGGER fail_bot_claim');
    assert.equal(await repository.claim(1, '101', NOW), 'claimed');
});
