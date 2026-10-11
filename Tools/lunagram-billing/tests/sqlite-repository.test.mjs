import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { createSqliteRepository } from '../cloudflare/sqlite-repository.mjs';
import { createBillingService } from '../service.mjs';
import { activationCodeHash } from '../security.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';

const NOW = Date.UTC(2026, 9, 11, 12);
const PEPPER = Buffer.alloc(32, 71); // Test-only constant.
const CODE = 'TESTCODE12345678';
const HASH = activationCodeHash(CODE, PEPPER);
const errorCode = code => error => error.code === code;

function database(t) {
    const parent = resolve(tmpdir());
    const directory = mkdtempSync(join(parent, 'lunagram-billing-sqlite-'));
    const path = join(directory, 'billing.sqlite');
    const opened = new Set();
    const open = () => {
        const storage = openSqliteStorageForTests(path);
        opened.add(storage);
        return { storage, repository: createSqliteRepository(storage), close() { storage.close(); opened.delete(storage); } };
    };
    t.after(() => {
        for (const storage of opened) storage.close();
        // Delete only this test-created child, never an environment/workspace root.
        assert.equal(dirname(resolve(directory)), parent);
        assert.ok(basename(directory).startsWith('lunagram-billing-sqlite-'));
        rmSync(directory, { recursive: true, force: true });
    });
    return { open };
}
const sampleOrder = (id = 'order-one', extra = {}) => ({ id, requestKey: `request-${id}`, telegramUserId: '101',
    planId: 'based', usdCents: 50, provider: 'stars', delivery: 'key', status: 'paid',
    starsAmount: 7, createdAtMs: NOW, expiresAtMs: NOW + 900000, ...extra });
const sampleCode = () => ({ hash: HASH, planId: 'based', orderId: 'order-one', revoked: false, redeemedBy: null });
function service(repository, overrides = {}) {
    return createBillingService({ repository, enabled: true, keyPepper: PEPPER, clock: () => NOW,
        authenticate: async session => {
            if (session === 'server-alice') return '101';
            if (session === 'server-bob') return '202';
            throw new Error('unauthenticated');
        }, ...overrides });
}

test('adapter requires synchronous SQLite transaction API, not async D1 lookalike', () => {
    for (const invalid of [{}, { transaction() {}, prepare() {} }, { sql: { exec() {} } }]) {
        assert.throws(() => createSqliteRepository(invalid), errorCode('SQLITE_STORAGE_REQUIRED'));
    }
});

test('all repository records survive a file-backed database close/restart', async t => {
    const db = database(t);
    let connection = db.open();
    assert.equal(connection.repository.durable, true);
    await connection.repository.transaction(tx => {
        tx.putOrder(sampleOrder('order-one', { invoiceId: '42' }));
        tx.putCode(sampleCode());
        tx.putPayment({ eventId: 'stars:charge-one', orderId: 'order-one', paidAtMs: NOW });
        tx.putEntitlement({ telegramUserId: '101', planId: 'based', expiresAtMs: NOW + 1000, featuresGated: false });
        assert.equal(tx.consumeRateLimit('redeem:101:123', 2, NOW + 60000), true);
    });
    connection.close();
    connection = db.open();
    await connection.repository.transaction(tx => {
        assert.equal(tx.getOrder('order-one').invoiceId, '42');
        assert.equal(tx.getOrderByInvoice('42').id, 'order-one');
        assert.equal(tx.getOrderByRequest('request-order-one').id, 'order-one');
        assert.equal(tx.getCode(HASH).orderId, 'order-one');
        assert.equal(tx.getPayment('stars:charge-one').paidAtMs, NOW);
        assert.equal(tx.getEntitlement('101').featuresGated, false);
        assert.equal(tx.consumeRateLimit('redeem:101:123', 2, NOW + 60000), true);
        assert.equal(tx.consumeRateLimit('redeem:101:123', 2, NOW + 60000), false);
    });
});

test('request key and invoice unique constraints rollback preceding writes, never REPLACE old orders', async t => {
    const { repository } = database(t).open();
    await repository.transaction(tx => tx.putOrder(sampleOrder('original', { invoiceId: '99' })));
    for (const conflict of [sampleOrder('new', { requestKey: 'request-original' }), sampleOrder('new', { invoiceId: '99' })]) {
        await assert.rejects(repository.transaction(tx => {
            tx.putEntitlement({ telegramUserId: '202', planId: 'ultra', expiresAtMs: NOW });
            tx.putOrder(conflict);
        }), /UNIQUE/);
        await repository.transaction(tx => {
            assert.equal(tx.getEntitlement('202'), undefined);
            assert.equal(tx.getOrder('new'), undefined);
            assert.equal(tx.getOrder('original').invoiceId, '99');
        });
    }
});

test('duplicate payment proof is insert-only and rolls back entitlement changes', async t => {
    const { repository } = database(t).open();
    await repository.transaction(tx => tx.putPayment({ eventId: 'stars:one', orderId: 'old', paidAtMs: NOW }));
    await assert.rejects(repository.transaction(tx => {
        tx.putEntitlement({ telegramUserId: '101', expiresAtMs: NOW });
        tx.putPayment({ eventId: 'stars:one', orderId: 'new', paidAtMs: NOW });
    }), /UNIQUE/);
    await repository.transaction(tx => {
        assert.equal(tx.getEntitlement('101'), undefined);
        assert.equal(tx.getPayment('stars:one').orderId, 'old');
    });
});

test('key storage rejects plaintext/unrecognized fields and malformed hashes', async t => {
    const { repository, storage } = database(t).open();
    for (const extra of [{ code: CODE }, { plaintext: CODE }, { hash: CODE }]) {
        await assert.rejects(repository.transaction(tx => tx.putCode({ ...sampleCode(), ...extra })), errorCode('INVALID_RECORD'));
    }
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_codes')][0].count, 0);
    await repository.transaction(tx => tx.putCode(sampleCode()));
    assert.equal([...storage.sql.exec('SELECT payload FROM billing_codes')][0].payload.includes(CODE), false);
});

test('reads are isolated copies; bounded records and SQL-bound keys prevent accidental query injection', async t => {
    const { repository } = database(t).open();
    const id = "x'); DROP TABLE billing_orders; --";
    await repository.transaction(tx => tx.putOrder(sampleOrder(id)));
    await repository.transaction(tx => { tx.getOrder(id).status = 'changed-without-put'; });
    assert.equal((await repository.transaction(tx => tx.getOrder(id))).status, 'paid');
    await assert.rejects(repository.transaction(tx => tx.putOrder(sampleOrder('large', { extra: 'x'.repeat(17000) }))), errorCode('INVALID_RECORD'));
    await assert.rejects(repository.transaction(tx => tx.putEntitlement({ telegramUserId: '101', expiresAtMs: NaN })), errorCode('INVALID_RECORD'));
});

test('async transaction callback rolls back and cannot write after it resumes', async t => {
    const { repository } = database(t).open();
    let continued = false;
    await assert.rejects(repository.transaction(async tx => {
        tx.putOrder(sampleOrder('before-await'));
        await Promise.resolve();
        continued = true;
        tx.putOrder(sampleOrder('after-await'));
    }), errorCode('ASYNC_TRANSACTION_FORBIDDEN'));
    await Promise.resolve();
    assert.equal(continued, true);
    assert.equal(await repository.transaction(tx => tx.getOrder('before-await')), undefined);
    assert.equal(await repository.transaction(tx => tx.getOrder('after-await')), undefined);
    let escaped;
    await repository.transaction(tx => { escaped = tx; });
    assert.throws(() => escaped.getOrder('anything'), errorCode('TRANSACTION_CLOSED'));
});

test('two repository instances racing a bearer-code redemption have one durable winner', async t => {
    const db = database(t);
    const a = db.open();
    const b = db.open();
    await a.repository.transaction(tx => { tx.putOrder(sampleOrder()); tx.putCode(sampleCode()); });
    const results = await Promise.allSettled([
        service(a.repository).redeemCode({ session: 'server-alice', code: CODE }),
        service(b.repository).redeemCode({ session: 'server-bob', code: CODE }),
    ]);
    assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
    assert.equal(results.filter(r => r.status === 'rejected' && r.reason.code === 'INVALID_CODE').length, 1);
    a.close(); b.close();
    const restored = db.open();
    await restored.repository.transaction(tx => {
        const code = tx.getCode(HASH);
        assert.ok(['101', '202'].includes(code.redeemedBy));
        assert.ok(tx.getEntitlement(code.redeemedBy));
        assert.equal(tx.getEntitlement(code.redeemedBy === '101' ? '202' : '101'), undefined);
    });
});

test('invalid redemption attempts remain committed across rollback and database restart', async t => {
    const db = database(t);
    let connection = db.open();
    let billing = service(connection.repository);
    for (let i = 0; i < 5; i++) await assert.rejects(billing.redeemCode({ session: 'server-alice', code: '!' }), errorCode('INVALID_CODE'));
    connection.close();
    connection = db.open();
    billing = service(connection.repository);
    await assert.rejects(billing.redeemCode({ session: 'server-alice', code: CODE }), errorCode('RATE_LIMITED'));
    assert.equal([...connection.storage.sql.exec('SELECT attempts FROM billing_rate_limits')][0].attempts, 5);
});

test('bounded indexed cleanup removes only expired limit buckets, not billing history', async t => {
    const { repository, storage } = database(t).open();
    await repository.transaction(tx => {
        tx.putOrder(sampleOrder()); tx.putCode(sampleCode());
        tx.putPayment({ eventId: 'stars:keep', orderId: 'order-one', paidAtMs: NOW });
        for (let i = 0; i < 7; i++) tx.consumeRateLimit(`expired-${i}`, 1, NOW - 1);
        tx.consumeRateLimit('active', 1, NOW + 60000);
    });
    assert.equal(await repository.cleanupExpiredRateLimits(NOW, 3), 3);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS count FROM billing_rate_limits')][0].count, 5);
    assert.equal(await repository.cleanupExpiredRateLimits(NOW, 3), 3);
    assert.equal(await repository.cleanupExpiredRateLimits(NOW, 3), 1);
    assert.equal(await repository.cleanupExpiredRateLimits(NOW, 3), 0);
    assert.equal([...storage.sql.exec('SELECT bucket FROM billing_rate_limits')][0].bucket, 'active');
    await repository.transaction(tx => {
        assert.ok(tx.getOrder('order-one')); assert.ok(tx.getCode(HASH)); assert.ok(tx.getPayment('stars:keep'));
    });
    for (const limit of [0, -1, 501, 1.5, '3']) await assert.rejects(repository.cleanupExpiredRateLimits(NOW, limit));
});

test('Stars paid receipt, key issue and renewal remain idempotent after restart', async t => {
    const db = database(t);
    let connection = db.open();
    const webhookSecret = 'test_stars_webhook_secret_1234567890';
    const options = { stars: { webhookSecret, salesEnabled: true, prices: { based: 7 } } };
    let billing = service(connection.repository, options);
    const command = Buffer.from(JSON.stringify({ update_id: 1, message: { date: NOW / 1000,
        from: { id: 101, is_bot: false }, chat: { id: 101, type: 'private' }, text: '/buy based' } }));
    const { order } = await billing.prepareBotStarsInvoice({ rawBody: command, webhookSecret });
    const rawBody = Buffer.from(JSON.stringify({ update_id: 2, message: { date: NOW / 1000,
        from: { id: 101, is_bot: false }, chat: { id: 101, type: 'private' }, successful_payment: {
            currency: 'XTR', total_amount: 7, invoice_payload: order.id, telegram_payment_charge_id: 'sqlite-proof-one' } } }));
    await Promise.all([billing.handleStarsSuccessfulPayment({ rawBody, webhookSecret }), billing.handleStarsSuccessfulPayment({ rawBody, webhookSecret })]);
    const issued = await billing.issuePurchasedKey({ session: 'server-alice', orderId: order.id });
    const first = await billing.redeemCode({ session: 'server-alice', code: issued.code });
    connection.close(); connection = db.open(); billing = service(connection.repository, options);
    await billing.handleStarsSuccessfulPayment({ rawBody, webhookSecret });
    await assert.rejects(billing.redeemCode({ session: 'server-alice', code: issued.code }), errorCode('INVALID_CODE'));
    const afterRestart = await billing.getSubscription({ session: 'server-alice' });
    assert.equal(afterRestart.expiresAtMs, first.expiresAtMs);
    assert.equal([...connection.storage.sql.exec('SELECT COUNT(*) AS count FROM billing_payments')][0].count, 1);
});

test('unknown schema version fails closed without rewriting persistent version', async t => {
    const { storage } = database(t).open();
    storage.sql.exec("UPDATE billing_meta SET value = 999 WHERE name = 'schema_version'");
    assert.throws(() => createSqliteRepository(storage), errorCode('UNSUPPORTED_SCHEMA'));
    assert.equal([...storage.sql.exec("SELECT value FROM billing_meta WHERE name = 'schema_version'")][0].value, 999);
});
