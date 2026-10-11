import test from 'node:test';
import assert from 'node:assert/strict';
import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { createBotOutboxRepository, BOT_OUTBOX_POLICY as P } from '../cloudflare/bot-outbox-repository.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';

const NOW = Date.UTC(2026, 9, 12, 18);
const TEST_KEY = Buffer.alloc(32, 17); // Local test key only, never a deployment key.
const MESSAGE = 'TEST_ONLY_ACTIVATION_CODE_NOT_A_REAL_KEY';
const KIND = 'telegram_paid_key';
const digest = value => createHash('sha256').update(value).digest('hex');
const lease = n => `test_lease_${String(n).padStart(24, '0')}`;
const errorCode = code => error => error.code === code;
const aad = (id, kind, keyId) => Buffer.from(JSON.stringify([1, id, kind, keyId]));
function encrypted(id, text = MESSAGE, kind = KIND) {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', TEST_KEY, iv);
    cipher.setAAD(aad(id, kind, 'test-key-v1'));
    const bytes = Buffer.concat([cipher.update(JSON.stringify({ chat_id: '101', text })), cipher.final()]);
    return { version: 1, algorithm: 'AES-256-GCM', keyId: 'test-key-v1',
        iv: iv.toString('base64url'), ciphertext: bytes.toString('base64url'), tag: cipher.getAuthTag().toString('base64url') };
}
function decrypted(item) {
    const payload = item.encryptedPayload;
    const decipher = createDecipheriv('aes-256-gcm', TEST_KEY, Buffer.from(payload.iv, 'base64url'));
    decipher.setAAD(aad(item.id, item.kind, payload.keyId));
    decipher.setAuthTag(Buffer.from(payload.tag, 'base64url'));
    return JSON.parse(Buffer.concat([decipher.update(Buffer.from(payload.ciphertext, 'base64url')), decipher.final()]).toString());
}
function database(t) {
    const parent = resolve(tmpdir());
    const directory = mkdtempSync(join(parent, 'lunagram-bot-outbox-'));
    const file = join(directory, 'outbox.sqlite');
    const opened = new Set();
    const open = () => {
        const storage = openSqliteStorageForTests(file);
        opened.add(storage);
        return { storage, repository: createBotOutboxRepository(storage), close() { storage.close(); opened.delete(storage); } };
    };
    t.after(() => {
        for (const storage of opened) storage.close();
        assert.equal(dirname(resolve(directory)), parent);
        assert.ok(basename(directory).startsWith('lunagram-bot-outbox-'));
        rmSync(directory, { recursive: true, force: true });
    });
    return { open, file };
}
const readJob = (storage, id) => [...storage.sql.exec('SELECT * FROM billing_bot_outbox WHERE id=?', id)][0];

test('requires synchronous SQLite, dedicated schema only, and no free enqueueTx capability', t => {
    for (const invalid of [{}, { transaction() {}, prepare() {} }, { sql: { exec() {} } }]) {
        assert.throws(() => createBotOutboxRepository(invalid), errorCode('SQLITE_STORAGE_REQUIRED'));
    }
    const { repository, storage } = database(t).open();
    assert.equal(repository.durable, true);
    assert.equal(repository.enqueueTx, undefined);
    assert.deepEqual([...storage.sql.exec("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")].map(r => r.name),
        ['billing_bot_outbox', 'billing_bot_outbox_leases']);
});

test('exact idempotent enqueue survives restart, never resets state/deadline or overwrites ciphertext', async t => {
    const db = database(t);
    let connection = db.open();
    const id = digest('idempotent');
    const payload = encrypted(id);
    assert.equal(await connection.repository.enqueue(id, payload, KIND, NOW), 'enqueued');
    const before = readJob(connection.storage, id);
    connection.close(); connection = db.open();
    assert.equal(await connection.repository.enqueue(id, { ...payload }, KIND, NOW + 5000), 'existing');
    assert.deepEqual(readJob(connection.storage, id), before);
    for (const [otherPayload, kind] of [[encrypted(id), KIND], [payload, 'telegram_info']]) {
        await assert.rejects(connection.repository.enqueue(id, otherPayload, kind, NOW), errorCode('OUTBOX_IDEMPOTENCY_CONFLICT'));
        assert.deepEqual(readJob(connection.storage, id), before);
    }
});

test('only a bounded canonical AES-GCM envelope is accepted; plaintext/token/message fields are refused', async t => {
    const { repository, storage } = database(t).open();
    const id = digest('reject-envelope');
    const payload = encrypted(id);
    for (const invalid of [MESSAGE, {}, { ...payload, text: MESSAGE }, { ...payload, token: 'TEST_ONLY_TOKEN' },
        { ...payload, activationCode: MESSAGE }, { ...payload, version: 2 }, { ...payload, algorithm: 'none' },
        { ...payload, keyId: 'bad key' }, { ...payload, iv: Buffer.alloc(11).toString('base64url') },
        { ...payload, tag: Buffer.alloc(15).toString('base64url') }, { ...payload, ciphertext: '' },
        { ...payload, ciphertext: Buffer.alloc(P.maxCiphertextBytes + 1).toString('base64url') },
        { ...payload, iv: payload.iv + '=' }, { ...payload, tag: payload.tag + '\n' }]) {
        await assert.rejects(repository.enqueue(id, invalid, KIND, NOW), errorCode('INVALID_ENCRYPTED_PAYLOAD'));
    }
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_outbox')][0].count, 0);
});

test('durable data does not contain the plaintext key, message, token or encryption key', async t => {
    const db = database(t);
    const connection = db.open();
    const id = digest('encrypted-only');
    const payload = encrypted(id);
    await connection.repository.enqueue(id, payload, KIND, NOW);
    const item = await connection.repository.claimDue(lease(1), NOW);
    assert.deepEqual(decrypted(item), { chat_id: '101', text: MESSAGE });
    assert.equal(JSON.stringify(readJob(connection.storage, id)).includes(MESSAGE), false);
    connection.close();
    assert.equal(readFileSync(db.file).includes(Buffer.from(MESSAGE)), false);
    assert.equal(readFileSync(db.file).includes(TEST_KEY), false);
});

test('two SQLite connections racing for one job grant one lease; later same lease ID is never reused', async t => {
    const db = database(t);
    const a = db.open();
    const b = db.open();
    const id = digest('concurrent');
    await a.repository.enqueue(id, encrypted(id), KIND, NOW);
    const results = await Promise.all([a.repository.claimDue(lease(1), NOW), b.repository.claimDue(lease(2), NOW)]);
    assert.equal(results.filter(Boolean).length, 1);
    const claimed = results.find(Boolean);
    assert.equal(claimed.attempt, 1);
    assert.equal(claimed.leaseUntilMs, NOW + P.leaseMs);
    assert.ok(Object.isFrozen(claimed));
    assert.ok(Object.isFrozen(claimed.encryptedPayload));
    await assert.rejects(a.repository.claimDue(claimed.leaseId, NOW), errorCode('OUTBOX_LEASE_REUSED'));
    assert.equal(await a.repository.defer(id, claimed.leaseId, NOW + 1), 'deferred');
    await assert.rejects(b.repository.claimDue(claimed.leaseId, NOW + 10000), errorCode('OUTBOX_LEASE_REUSED'));
});

test('expired lease recovers after restart and redelivers exactly the same encrypted key, never rotates', async t => {
    const db = database(t);
    let connection = db.open();
    const id = digest('crash-lost-ack');
    const payload = encrypted(id);
    await connection.repository.enqueue(id, payload, KIND, NOW);
    const first = await connection.repository.claimDue(lease(1), NOW);
    // Simulate a Telegram send accepted before process crash: no finishAck call.
    connection.close(); connection = db.open();
    assert.equal(await connection.repository.recoverExpired(NOW + P.leaseMs), 1);
    assert.equal(await connection.repository.claimDue(lease(2), NOW + P.leaseMs + 4999), null);
    const again = await connection.repository.claimDue(lease(2), NOW + P.leaseMs + 5000);
    assert.equal(again.attempt, 2);
    assert.deepEqual(again.encryptedPayload, first.encryptedPayload);
    assert.deepEqual(decrypted(again), decrypted(first));
    assert.equal(await connection.repository.finishAck(id, lease(1), { messageId: 1 }, NOW + P.leaseMs + 5001), 'stale');
    assert.equal(await connection.repository.defer(id, lease(1), NOW + P.leaseMs + 5001), 'stale');
    assert.equal(readJob(connection.storage, id).lease_id, lease(2));
});

test('ack requires a live exact lease and minimal Telegram metadata; repeat exact ack is idempotent', async t => {
    const { repository, storage } = database(t).open();
    const id = digest('ack');
    await repository.enqueue(id, encrypted(id), KIND, NOW);
    await repository.claimDue(lease(1), NOW);
    for (const metadata of [{}, { messageId: 0 }, { messageId: '2' }, { messageId: 2, text: MESSAGE },
        { messageId: 2, token: 'TEST_ONLY_TOKEN' }, { messageId: 2, result: {} }]) {
        await assert.rejects(repository.finishAck(id, lease(1), metadata, NOW + 1), errorCode('INVALID_OUTBOX_ACK'));
    }
    assert.equal(await repository.finishAck(id, lease(2), { messageId: 2 }, NOW + 1), 'stale');
    assert.equal(await repository.finishAck(id, lease(1), { messageId: 2 }, NOW - 1), 'stale');
    assert.equal(await repository.finishAck(id, lease(1), { messageId: 2 }, NOW + P.leaseMs), 'stale');
    assert.equal(await repository.finishAck(id, lease(1), { messageId: 2 }, NOW + 1), 'acknowledged');
    const before = readJob(storage, id);
    assert.equal(await repository.finishAck(id, lease(1), { messageId: 2 }, NOW + 999999), 'acknowledged');
    assert.deepEqual(readJob(storage, id), before);
    await assert.rejects(repository.finishAck(id, lease(1), { messageId: 3 }, NOW + 2), errorCode('OUTBOX_ACK_CONFLICT'));
    assert.equal(await repository.defer(id, lease(1), NOW + 2), 'stale');
    assert.equal(await repository.claimDue(lease(2), NOW + P.lifetimeMs), null);
});

test('retry backoff and 12-attempt ceiling are bounded; exhausted job preserves ciphertext for manual review', async t => {
    const { repository, storage } = database(t).open();
    const id = digest('retry-bound');
    const payload = encrypted(id);
    await repository.enqueue(id, payload, KIND, NOW);
    let now = NOW;
    for (let attempt = 1; attempt <= P.maxAttempts; attempt++) {
        const item = await repository.claimDue(lease(attempt), now);
        assert.equal(item.attempt, attempt);
        assert.equal(await repository.defer(id, lease(attempt), now + 1), attempt === P.maxAttempts ? 'dead' : 'deferred');
        const saved = readJob(storage, id);
        const delay = Math.min(P.initialBackoffMs * 2 ** (attempt - 1), P.maxBackoffMs);
        assert.equal(saved.next_attempt_at_ms, now + 1 + delay);
        if (attempt < P.maxAttempts) assert.equal(await repository.claimDue(lease(attempt + 1), saved.next_attempt_at_ms - 1), null);
        now = saved.next_attempt_at_ms;
    }
    const final = readJob(storage, id);
    assert.equal(final.state, 'dead');
    assert.equal(final.terminal_reason, 'attempts_exhausted');
    assert.equal(JSON.stringify(JSON.parse(final.encrypted_payload)), JSON.stringify(payload));
    assert.equal(await repository.claimDue(lease(100), now + P.lifetimeMs), null);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_outbox_leases')][0].count, 12);
});

test('24-hour lifetime expires pending and leased jobs without deleting paid payloads', async t => {
    const { repository, storage } = database(t).open();
    const pending = digest('lifetime-pending');
    const leased = digest('lifetime-leased');
    await repository.enqueue(leased, encrypted(leased), KIND, NOW);
    await repository.claimDue(lease(1), NOW);
    await repository.enqueue(pending, encrypted(pending), KIND, NOW);
    assert.equal(await repository.recoverExpired(NOW + P.lifetimeMs), 2);
    for (const id of [pending, leased]) {
        const row = readJob(storage, id);
        assert.equal(row.state, 'dead');
        assert.equal(row.terminal_reason, 'expired');
        assert.ok(row.encrypted_payload.length > 0);
    }
    assert.equal(await repository.claimDue(lease(2), NOW + P.lifetimeMs), null);
});

test('Telegram retry_after survives restart, never shortens backoff, and retains expired payloads', async t => {
    const db = database(t);
    let connection = db.open();
    const id = digest('telegram-429');
    await connection.repository.enqueue(id, encrypted(id), KIND, NOW);
    await connection.repository.claimDue(lease(1), NOW);
    for (const invalid of [-1, 1.5, 86401, '60', NaN]) {
        await assert.rejects(connection.repository.defer(id, lease(1), NOW + 1, invalid), errorCode('INVALID_RETRY_AFTER'));
    }
    assert.equal(await connection.repository.defer(id, lease(1), NOW + 1, 60), 'deferred');
    assert.equal(readJob(connection.storage, id).next_attempt_at_ms, NOW + 60_001);
    connection.close(); connection = db.open();
    assert.equal(await connection.repository.claimDue(lease(2), NOW + 60_000), null);
    await connection.repository.claimDue(lease(2), NOW + 60_001);
    assert.equal(await connection.repository.defer(id, lease(2), NOW + 60_002, 1), 'deferred');
    assert.equal(readJob(connection.storage, id).next_attempt_at_ms, NOW + 70_002);
    await connection.repository.claimDue(lease(3), NOW + 70_002);
    assert.equal(await connection.repository.defer(id, lease(3), NOW + 70_003, 86400), 'dead');
    assert.equal(readJob(connection.storage, id).terminal_reason, 'expired');
    assert.ok(readJob(connection.storage, id).encrypted_payload.length > 0);
});

test('recovery touches at most requested bounded rows and no unrelated financial table', async t => {
    const { repository, storage } = database(t).open();
    storage.sql.exec('CREATE TABLE fixture_financial_history (id TEXT PRIMARY KEY)');
    storage.sql.exec("INSERT INTO fixture_financial_history VALUES ('keep')");
    for (let i = 0; i < 105; i++) {
        const id = digest(`expired-${i}`);
        await repository.enqueue(id, encrypted(id), KIND, NOW);
    }
    assert.equal(await repository.recoverExpired(NOW + P.lifetimeMs, 3), 3);
    assert.equal([...storage.sql.exec("SELECT COUNT(*) AS count FROM billing_bot_outbox WHERE state='pending'")][0].count, 102);
    assert.equal(await repository.recoverExpired(NOW + P.lifetimeMs), 100);
    assert.equal(await repository.recoverExpired(NOW + P.lifetimeMs), 2);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM fixture_financial_history')][0].count, 1);
    for (const limit of [0, 101, -1, 1.5]) await assert.rejects(repository.recoverExpired(NOW, limit), errorCode('INVALID_RECOVERY_LIMIT'));
});

test('callback-scoped enqueueTx rolls back with same-connection financial writes and closes escaped handles', async t => {
    const { repository, storage } = database(t).open();
    const id = digest('atomic');
    const payload = encrypted(id);
    storage.sql.exec('CREATE TABLE fixture_paid_orders (id TEXT PRIMARY KEY, code_hash TEXT)');
    let escaped;
    await assert.rejects(repository.transaction(tx => {
        escaped = tx;
        storage.sql.exec('INSERT INTO fixture_paid_orders VALUES (?,?)', 'order', digest(MESSAGE));
        tx.enqueueTx(id, payload, KIND, NOW);
        throw new Error('ABORT_BOTH');
    }), /ABORT_BOTH/);
    assert.equal(readJob(storage, id), undefined);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM fixture_paid_orders')][0].count, 0);
    assert.throws(() => escaped.enqueueTx(id, payload, KIND, NOW), errorCode('TRANSACTION_CLOSED'));
    await repository.transaction(tx => {
        storage.sql.exec('INSERT INTO fixture_paid_orders VALUES (?,?)', 'order', digest(MESSAGE));
        assert.equal(tx.enqueueTx(id, payload, KIND, NOW), 'enqueued');
    });
    assert.equal(readJob(storage, id).state, 'pending');
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM fixture_paid_orders')][0].count, 1);
});

test('async and nested transaction callbacks fail closed, including resumed escaped enqueue', async t => {
    const { repository, storage } = database(t).open();
    const id = digest('async');
    const payload = encrypted(id);
    let continuation;
    await assert.rejects(repository.transaction(async tx => {
        tx.enqueueTx(id, payload, KIND, NOW);
        await Promise.resolve();
        try { tx.enqueueTx(id, payload, KIND, NOW); } catch (error) { continuation = error.code; }
    }), errorCode('ASYNC_TRANSACTION_FORBIDDEN'));
    await Promise.resolve();
    assert.equal(continuation, 'TRANSACTION_CLOSED');
    assert.equal(readJob(storage, id), undefined);
    await assert.rejects(repository.transaction(() => repository.transaction(() => {})), errorCode('ASYNC_TRANSACTION_FORBIDDEN'));
});

test('claim and ack database failures roll back attempts, lease reservations and state', async t => {
    const connection = database(t).open();
    const { storage } = connection;
    let failOn;
    const wrapped = {
        transactionSync: callback => storage.transactionSync(callback),
        sql: { exec(sql, ...args) {
            if (failOn && sql.includes(failOn)) throw new Error('TEST_DB_FAILURE');
            return storage.sql.exec(sql, ...args);
        } },
    };
    const repository = createBotOutboxRepository(wrapped);
    const id = digest('db-failure');
    await repository.enqueue(id, encrypted(id), KIND, NOW);
    failOn = "SET state='leased'";
    await assert.rejects(repository.claimDue(lease(1), NOW), /TEST_DB_FAILURE/);
    assert.equal(readJob(storage, id).attempts, 0);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_outbox_leases')][0].count, 0);
    failOn = undefined;
    await repository.claimDue(lease(1), NOW);
    failOn = "SET state='acknowledged'";
    await assert.rejects(repository.finishAck(id, lease(1), { messageId: 9 }, NOW + 1), /TEST_DB_FAILURE/);
    assert.equal(readJob(storage, id).state, 'leased');
    failOn = undefined;
    assert.equal(await repository.finishAck(id, lease(1), { messageId: 9 }, NOW + 2), 'acknowledged');
});

test('invalid identifiers, lease tokens, kinds and times are rejected before row mutation', async t => {
    const { repository, storage } = database(t).open();
    const id = digest('invalid-contract');
    const payload = encrypted(id);
    for (const invalid of ['plaintext-key', id + '\n', id.toUpperCase(), '', null]) {
        await assert.rejects(repository.enqueue(invalid, payload, KIND, NOW), errorCode('INVALID_OUTBOX_ID'));
    }
    await assert.rejects(repository.enqueue(id, payload, 'send-anything', NOW), errorCode('INVALID_OUTBOX_KIND'));
    for (const invalid of [-1, 1.5, NaN, Number.MAX_SAFE_INTEGER, String(NOW)]) {
        await assert.rejects(repository.enqueue(id, payload, KIND, invalid), errorCode('INVALID_OUTBOX_TIME'));
    }
    for (const invalid of ['', 'short', lease(1) + '\n', 'a'.repeat(129)]) {
        await assert.rejects(repository.claimDue(invalid, NOW), errorCode('INVALID_OUTBOX_LEASE'));
    }
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_bot_outbox')][0].count, 0);
});
