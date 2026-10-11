import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { Cipheriv, createCipheriv } from 'node:crypto';
import { createLiveStore } from '../cloudflare/live-store.mjs';
import { createLiveControl } from '../cloudflare/live-control.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';

const NOW = Date.UTC(2026, 9, 12, 20);
const keys = { encryptionKey: Buffer.alloc(32, 71), keyPepper: Buffer.alloc(32, 72), authPepper: Buffer.alloc(32, 73) };

function strictWorkerdCipherInput(t) {
    const update = Cipheriv.prototype.update;
    let checked = 0;
    // Verified against native workerd: unlike Node, its Cipheriv.update does not
    // default a string input to UTF-8 when inputEncoding is omitted. Keep this
    // guard even though all standard Node tests accept the formerly broken code.
    t.mock.method(Cipheriv.prototype, 'update', function(data, inputEncoding, ...rest) {
        if (typeof data === 'string' && inputEncoding === undefined) {
            const error = new TypeError('String cipher input needs explicit inputEncoding in workerd');
            error.code = 'ERR_INVALID_ARG_VALUE';
            throw error;
        }
        checked++;
        return update.call(this, data, inputEncoding, ...rest);
    });
    assert.throws(() => createCipheriv('aes-256-gcm', keys.encryptionKey, Buffer.alloc(12), { authTagLength: 16 }).update('guard self-test'),
        { code: 'ERR_INVALID_ARG_VALUE' });
    return () => checked;
}

test('owner encryption uses explicit bytes and decrypts under workerd-compatible cipher semantics', t => {
    const checked = strictWorkerdCipherInput(t);
    const storage = openSqliteStorageForTests(':memory:');
    t.after(() => storage.close());
    const env = { BILLING_OWNER_CLAIM_TOKEN: 'A'.repeat(43), BILLING_OWNER_CLAIM_EXPIRES: String(NOW + 60_000) };
    const control = createLiveControl(storage, keys, env);
    assert.equal(control.claimOwner(env.BILLING_OWNER_CLAIM_TOKEN, '101', NOW), true);
    assert.equal(control.owner(), '101');
    assert.equal(createLiveControl(storage, keys, env).owner(), '101');
    assert.ok(checked() >= 1);
    const encrypted = [...storage.sql.exec("SELECT value FROM live_control WHERE name='owner'")][0].value;
    assert.equal(Buffer.from(JSON.parse(encrypted).tag, 'base64url').length, 16);
});

test('messages, invoices, paid keys, grants and support encrypt as bytes, including Unicode', t => {
    const checked = strictWorkerdCipherInput(t);
    const storage = openSqliteStorageForTests(':memory:');
    t.after(() => storage.close());
    const store = createLiveStore(storage, { ...keys, ownerUserId: '101', clock: () => NOW });
    const readDelivery = outboxId => {
        const row = [...storage.sql.exec('SELECT * FROM billing_bot_outbox WHERE id=?', outboxId)][0];
        const envelope = JSON.parse(row.encrypted_payload);
        assert.equal(Buffer.from(envelope.tag, 'base64url').length, 16);
        return store.decryptOutbox({ id: row.id, kind: row.kind, encryptedPayload: envelope });
    };
    const reply = store.enqueueMessage('202', 'Луна 🌙 — проверка UTF-8', 'crypto-reply');
    assert.equal(readDelivery(reply.outboxId).text, 'Луна 🌙 — проверка UTF-8');
    store.acceptTerms('202', '2026-10-12-v1');
    const order = store.createOrder('202', 'based', 'crypto-order');
    assert.equal(readDelivery(order.outboxId).currency, 'XTR');
    assert.equal(store.reservePreCheckout({ orderId: order.id, userId: '202', currency: 'XTR',
        totalAmount: 40, queryId: 'crypto-query' }).ok, true);
    const payment = store.recordPayment({ orderId: order.id, userId: '202', currency: 'XTR',
        totalAmount: 40, chargeId: 'crypto-fake-charge' });
    const code = readDelivery(payment.outboxId).text.match(/\b[A-Z0-9]{16}\b/)[0];
    const subscription = store.redeem('202', code);
    assert.equal(subscription.active, true);
    assert.deepEqual(store.redeem('202', code), subscription);
    const ticket = store.createSupportTicket('202', 'Помощь с подпиской 🌙', 'crypto-support');
    assert.equal(store.readTicket(ticket.id).messages[0].text, 'Помощь с подпиской 🌙');
    const response = store.replyTicket(ticket.id, 'Всё проверено', 'crypto-support-reply');
    assert.ok(readDelivery(response.outboxId).text.includes('Всё проверено'));
    assert.ok(checked() >= 8, 'exercise multiple independent production encryption paths');
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS n FROM live_codes')][0].n, 1);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS n FROM live_grants')][0].n, 1);
});
