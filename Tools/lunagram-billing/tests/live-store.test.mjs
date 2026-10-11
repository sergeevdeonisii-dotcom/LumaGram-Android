import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { createLiveStore, LIVE_PLANS } from '../cloudflare/live-store.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';
import { addCalendarMonth } from '../catalog.mjs';

const NOW = Date.UTC(2026, 9, 12, 18);
const ALICE = '4012345678901';
const BOB = '4012345678902';
const OWNER = '4012345678903';
const KEY = Buffer.alloc(32, 41);
const PEPPER = Buffer.alloc(32, 51);
const TERMS = '2026-10-12-v1';
const codeError = code => error => error.code === code;
function database(t, overrides = {}) {
    const parent = resolve(tmpdir());
    const directory = mkdtempSync(join(parent, 'lunagram-live-store-'));
    const file = join(directory, 'live.sqlite');
    const opened = new Set();
    const state = { at: NOW };
    const open = () => {
        const storage = openSqliteStorageForTests(file);
        opened.add(storage);
        const store = createLiveStore(storage, { encryptionKey: KEY, keyPepper: PEPPER, termsVersion: TERMS,
            ownerUserId: OWNER, clock: () => state.at, ...overrides });
        return { storage, store, close() { storage.close(); opened.delete(storage); } };
    };
    t.after(() => {
        for (const storage of opened) storage.close();
        assert.equal(dirname(resolve(directory)), parent);
        assert.ok(basename(directory).startsWith('lunagram-live-store-'));
        rmSync(directory, { recursive: true, force: true });
    });
    return { open, state };
}
const count = (storage, table) => [...storage.sql.exec(`SELECT COUNT(*) AS n FROM ${table}`)][0].n;
function delivery(connection, outboxId) {
    const row = [...connection.storage.sql.exec('SELECT * FROM billing_bot_outbox WHERE id=?', outboxId)][0];
    return connection.store.decryptOutbox({ id: row.id, kind: row.kind, encryptedPayload: JSON.parse(row.encrypted_payload) });
}
function order(connection, requestKey = 'one', plan = 'based', userId = ALICE) {
    connection.store.acceptTerms(userId, TERMS);
    return connection.store.createOrder(userId, plan, requestKey);
}
function reserve(connection, value, queryId = 'query-one', userId = ALICE) {
    return connection.store.reservePreCheckout({ orderId: value.id, userId, currency: 'XTR', totalAmount: value.starsAmount, queryId });
}
function payment(connection, value, chargeId = 'test-charge-one', userId = ALICE) {
    return connection.store.recordPayment({ orderId: value.id, userId, currency: 'XTR', totalAmount: value.starsAmount, chargeId });
}
function paid(connection, requestKey = 'one', plan = 'based', userId = ALICE) {
    const value = order(connection, requestKey, plan, userId);
    assert.equal(reserve(connection, value, `query-${requestKey}`, userId).ok, true);
    const result = payment(connection, value, `charge-${requestKey}`, userId);
    return { ...value, result, code: delivery(connection, result.outboxId).text.match(/\b[A-Z0-9]{16}\b/)[0] };
}

test('stable 32-byte encryption key, pepper and valid user identity are mandatory', t => {
    const { storage } = database(t).open();
    for (const bad of [{}, { encryptionKey: Buffer.alloc(31), keyPepper: PEPPER }, { encryptionKey: KEY, keyPepper: Buffer.alloc(10) }]) {
        assert.throws(() => createLiveStore(storage, bad), codeError('LIVE_STORE_NOT_CONFIGURED'));
    }
    assert.throws(() => createLiveStore(storage, { encryptionKey: KEY, keyPepper: PEPPER, ownerUserId: 'not-user' }), codeError('UNAUTHENTICATED'));
});

test('explicit versioned consent precedes invoice; all five immutable Stars prices are exact', t => {
    const connection = database(t).open();
    assert.deepEqual(LIVE_PLANS.map(p => p.starsAmount), [40, 70, 100, 140, 360]);
    assert.equal(connection.store.getConsent(ALICE).accepted, false);
    assert.throws(() => connection.store.createOrder(ALICE, 'based', 'no-consent'), codeError('TERMS_REQUIRED'));
    assert.throws(() => connection.store.acceptTerms(ALICE, 'old'), codeError('TERMS_VERSION_MISMATCH'));
    connection.store.acceptTerms(ALICE, TERMS);
    for (const plan of LIVE_PLANS) {
        const value = connection.store.createOrder(ALICE, plan.id, plan.id);
        const invoice = delivery(connection, value.outboxId);
        assert.equal(invoice.method, 'sendInvoice');
        assert.equal(invoice.chat_id, ALICE);
        assert.equal(invoice.payload, value.id);
        assert.equal(invoice.currency, 'XTR');
        assert.equal(invoice.provider_token, '');
        assert.equal(invoice.start_parameter, `buy_${plan.id}`);
        assert.deepEqual(invoice.prices, [{ label: plan.title, amount: plan.starsAmount }]);
        assert.equal(invoice.subscription_period, undefined);
    }
    assert.equal(count(connection.storage, 'live_orders'), 5);
});

test('order retry is immutable and survives restart without duplicated invoice or different price', t => {
    const db = database(t);
    let connection = db.open();
    const first = order(connection);
    const original = delivery(connection, first.outboxId);
    connection.close(); connection = db.open();
    assert.deepEqual(connection.store.createOrder(ALICE, 'based', 'one'), first);
    assert.deepEqual(delivery(connection, first.outboxId), original);
    assert.equal(count(connection.storage, 'live_orders'), 1);
    assert.equal(count(connection.storage, 'billing_bot_outbox'), 1);
    assert.throws(() => connection.store.createOrder(ALICE, 'ultra', 'one'), codeError('IDEMPOTENCY_CONFLICT'));
});

test('precheckout validates buyer, amount and currency; approved invoice can never accept another query', t => {
    const db = database(t);
    const connection = db.open();
    const value = order(connection);
    for (const extra of [{ userId: BOB }, { currency: 'USD' }, { totalAmount: 1 }]) {
        assert.equal(connection.store.reservePreCheckout({ orderId: value.id, userId: ALICE, currency: 'XTR',
            totalAmount: value.starsAmount, queryId: 'first', ...extra }).ok, false);
    }
    assert.equal(reserve(connection, value, 'first').ok, true);
    db.state.at += 9000; assert.equal(reserve(connection, value, 'first').ok, true);
    db.state.at += 2000; assert.equal(reserve(connection, value, 'first').error, 'PRECHECKOUT_EXPIRED');
    assert.equal(reserve(connection, value, 'second').error, 'PAYMENT_IN_PROGRESS');
    db.state.at = NOW + 30_000; assert.equal(reserve(connection, value, 'second').error, 'PAYMENT_IN_PROGRESS');
    db.state.at = NOW + 10 * 60_000; assert.equal(reserve(connection, value, 'second').error, 'PAYMENT_IN_PROGRESS');
    db.state.at = NOW + 15 * 60_000; assert.equal(reserve(connection, value, 'third').error, 'ORDER_EXPIRED');
    // A genuine delayed paid receipt must not be dropped because its invoice expired.
    assert.equal(payment(connection, value).status, 'paid');
});

test('receipt + one key hash + encrypted key + ledger + outbox are one atomic commit', t => {
    const connection = database(t).open();
    const value = order(connection);
    reserve(connection, value);
    connection.storage.sql.exec(`CREATE TRIGGER fail_key_outbox BEFORE INSERT ON billing_bot_outbox
        WHEN NEW.kind='telegram_paid_key' BEGIN SELECT RAISE(ABORT,'TEST_OUTBOX_FAILURE'); END`);
    assert.throws(() => payment(connection, value), /TEST_OUTBOX_FAILURE/);
    assert.equal(connection.store.getOrder(value.id).status, 'pending');
    assert.equal(count(connection.storage, 'live_payments'), 0);
    assert.equal(count(connection.storage, 'live_codes'), 0);
    assert.equal([...connection.storage.sql.exec("SELECT COUNT(*) AS n FROM live_ledger WHERE kind='payment'")][0].n, 0);
    connection.storage.sql.exec('DROP TRIGGER fail_key_outbox');
    const first = payment(connection, value);
    const second = payment(connection, value);
    assert.equal(first.outboxId, second.outboxId);
    assert.equal(count(connection.storage, 'live_payments'), 1);
    assert.equal(count(connection.storage, 'live_codes'), 1);
    assert.equal(count(connection.storage, 'billing_bot_outbox'), 2);
    assert.equal(connection.store.getSubscription(ALICE).active, false);
});

test('second distinct charge is durable manual-refund review, never a second key/grant', t => {
    const connection = database(t).open();
    const value = paid(connection);
    const extra = payment(connection, value, 'second-distinct-charge');
    assert.equal(extra.status, 'review_required');
    assert.equal(extra.reason, 'duplicate_charge');
    assert.equal(count(connection.storage, 'live_payments'), 2);
    assert.equal(count(connection.storage, 'live_codes'), 1);
    connection.store.redeem(ALICE, value.code);
    const before = connection.store.getSubscription(ALICE);
    assert.equal(connection.store.prepareRefund(value.id, 'second-distinct-charge').chargeId, 'second-distinct-charge');
    connection.store.recordRefund({ chargeId: 'second-distinct-charge' });
    assert.equal(connection.store.getOrder(value.id).status, 'paid');
    assert.deepEqual(connection.store.getSubscription(ALICE), before);
});

test('wrong amount, user, currency, recurring or unknown order produces stored review without keys', t => {
    const connection = database(t).open();
    const value = order(connection); reserve(connection, value);
    for (const [index, extra] of [{ totalAmount: 1 }, { userId: BOB }, { currency: 'USD' }, { recurring: true }, { orderId: 'unknown' }].entries()) {
        const result = connection.store.recordPayment({ orderId: value.id, userId: ALICE, currency: 'XTR', totalAmount: value.starsAmount,
            chargeId: `bad-${index}`, ...extra });
        assert.equal(result.status, 'review_required');
    }
    assert.equal(count(connection.storage, 'live_payments'), 5);
    assert.equal(count(connection.storage, 'live_codes'), 0);
    assert.equal(connection.store.getOrder(value.id).status, 'pending');
});

test('admin review exposes only opaque IDs and can refund unknown-order receipt without creating keys', t => {
    const connection = database(t).open();
    connection.store.recordPayment({ orderId: 'unknown', userId: ALICE, currency: 'XTR', totalAmount: 40, chargeId: 'unknown-charge' });
    const [review] = connection.store.listPaymentReviews();
    assert.match(review.reviewId, /^[0-9a-f]{48}$/);
    assert.equal(review.reason, 'unknown_order');
    assert.ok(!JSON.stringify(review).includes(ALICE));
    assert.ok(!JSON.stringify(review).includes('unknown-charge'));
    const pending = connection.store.prepareReviewRefund(review.reviewId);
    assert.deepEqual(pending, { orderId: 'unknown', userId: ALICE, chargeId: 'unknown-charge', status: 'refund_pending' });
    assert.equal(connection.store.recordRefund({ chargeId: pending.chargeId }).status, 'refunded');
    assert.equal(connection.store.prepareReviewRefund(review.reviewId).status, 'refunded');
    assert.deepEqual(connection.store.listPaymentReviews(), []);
    assert.equal(count(connection.storage, 'live_codes'), 0);
    assert.throws(() => connection.store.prepareReviewRefund('f'.repeat(48)), codeError('PAYMENT_NOT_FOUND'));
});

test('code and PII stay encrypted/HMAC at rest; buyer keys HTTP view contains no bearer secret', t => {
    const connection = database(t).open();
    const value = paid(connection);
    for (const table of ['live_orders', 'live_codes', 'live_payments', 'live_ledger', 'billing_bot_outbox']) {
        const serialized = JSON.stringify([...connection.storage.sql.exec(`SELECT * FROM ${table}`)]);
        assert.ok(!serialized.includes(value.code), table);
        assert.ok(!serialized.includes(ALICE), table);
        assert.ok(!serialized.includes('charge-one'), table);
    }
    assert.deepEqual(connection.store.listKeys(BOB), []);
    assert.equal(connection.store.listKeys(ALICE)[0].status, 'available');
    assert.ok(!JSON.stringify(connection.store.listKeys(ALICE)).includes(value.code));
    const resend = connection.store.enqueueKeys(ALICE, 'retry-command');
    assert.ok(delivery(connection, resend.outboxId).text.includes(value.code));
});

test('bearer redemption binds verified account once; same-account retry does not grant another month', async t => {
    const db = database(t);
    const a = db.open(); const b = db.open();
    const value = paid(a);
    const results = await Promise.allSettled([
        Promise.resolve().then(() => a.store.redeem(ALICE, value.code)),
        Promise.resolve().then(() => b.store.redeem(BOB, value.code)),
    ]);
    assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
    assert.equal(count(a.storage, 'live_grants'), 1);
    const winner = results[0].status === 'fulfilled' ? ALICE : BOB;
    const before = a.store.getSubscription(winner);
    assert.equal(before.expiresAtMs, addCalendarMonth(NOW));
    assert.deepEqual(a.store.redeem(winner, value.code), before);
});

test('invalid redemption attempts survive rollback/restart and are durably rate-limited', t => {
    const db = database(t);
    let connection = db.open();
    for (let i = 0; i < 5; i++) assert.throws(() => connection.store.redeem(ALICE, 'BADCODE0000000000'), codeError('INVALID_CODE'));
    connection.close(); connection = db.open();
    assert.throws(() => connection.store.redeem(ALICE, 'BADCODE0000000000'), codeError('RATE_LIMITED'));
    assert.equal(count(connection.storage, 'live_grants'), 0);
});

test('refund_pending blocks unused code immediately; confirmed refund recomputes stacked grants', t => {
    const connection = database(t).open();
    const first = paid(connection, 'first', 'based');
    const second = paid(connection, 'second', 'ultra');
    connection.store.redeem(ALICE, first.code);
    connection.store.redeem(ALICE, second.code);
    assert.equal(connection.store.getSubscription(ALICE).expiresAtMs, addCalendarMonth(addCalendarMonth(NOW)));
    assert.equal(connection.store.getSubscription(ALICE).planId, 'ultra'); // same-ms ordering follows insertion, not random code hash.
    const pending = connection.store.prepareRefund(first.id);
    assert.equal(pending.status, 'refund_pending');
    assert.equal(connection.store.getSubscription(ALICE).active, true); // Not yet confirmed refunded.
    assert.throws(() => connection.store.redeem(BOB, first.code), codeError('CODE_UNAVAILABLE'));
    connection.store.recordRefund({ chargeId: 'charge-first' });
    const remaining = connection.store.getSubscription(ALICE);
    assert.equal(remaining.expiresAtMs, addCalendarMonth(NOW));
    assert.equal(remaining.planId, 'ultra');
    assert.equal(connection.store.recordRefund({ chargeId: 'charge-first' }).status, 'refunded');
    assert.equal(count(connection.storage, 'live_grants'), 2); // Ledger is retained, not deleted.
    const third = paid(connection, 'third', 'plus');
    connection.store.prepareRefund(third.id);
    assert.throws(() => connection.store.redeem(ALICE, third.code), codeError('CODE_UNAVAILABLE'));
    assert.equal(connection.store.prepareRefund(third.id).status, 'refund_pending');
});

test('verified refund preceding successful_payment creates no usable key on late payment receipt', t => {
    const connection = database(t).open();
    const value = order(connection); reserve(connection, value);
    assert.equal(connection.store.recordRefund({ orderId: value.id, userId: ALICE, currency: 'XTR', totalAmount: value.starsAmount,
        chargeId: 'out-of-order-charge' }).status, 'awaiting_payment');
    assert.equal(payment(connection, value, 'out-of-order-charge').status, 'refunded');
    assert.equal(connection.store.getOrder(value.id).status, 'refunded');
    assert.equal(count(connection.storage, 'live_codes'), 0);
});

test('conflicting refund markers cannot hide a real payment; mismatch is retained for manual review', t => {
    const connection = database(t).open();
    const first = order(connection, 'first'); reserve(connection, first, 'first');
    const second = order(connection, 'second'); reserve(connection, second, 'second');
    connection.store.recordRefund({ chargeId: 'marker-charge', orderId: first.id, userId: ALICE, currency: 'XTR', totalAmount: 40 });
    assert.throws(() => connection.store.recordRefund({ chargeId: 'marker-charge', orderId: second.id,
        userId: ALICE, currency: 'XTR', totalAmount: 40 }), codeError('REFUND_MISMATCH'));
    const result = payment(connection, second, 'marker-charge');
    assert.equal(result.status, 'review_required');
    assert.equal(result.reason, 'refund_mismatch');
    assert.equal(count(connection.storage, 'live_payments'), 1);
    assert.equal(count(connection.storage, 'live_codes'), 0);
});

test('encrypted keys and subscriptions survive reopening without rotation', t => {
    const db = database(t);
    let connection = db.open();
    const value = paid(connection);
    connection.store.redeem(ALICE, value.code);
    const before = connection.store.getSubscription(ALICE);
    connection.close(); connection = db.open();
    assert.deepEqual(connection.store.getSubscription(ALICE), before);
    assert.ok(delivery(connection, value.result.outboxId).text.includes(value.code));
    assert.equal(count(connection.storage, 'live_codes'), 1);
});

test('support is encrypted, anonymous in admin views, durable and replies through outbox', t => {
    const connection = database(t).open();
    const privateText = 'TEST_PRIVATE_SUPPORT_MESSAGE';
    const ticket = connection.store.createSupportTicket(ALICE, privateText, 'support-one');
    assert.equal(connection.store.createSupportTicket(ALICE, privateText, 'support-one').id, ticket.id);
    assert.throws(() => connection.store.createSupportTicket(ALICE, 'Changed', 'support-one'), codeError('IDEMPOTENCY_CONFLICT'));
    assert.equal(connection.store.listTickets()[0].id, ticket.id);
    const view = connection.store.readTicket(ticket.id);
    assert.equal(view.messages[0].text, privateText);
    assert.ok(!JSON.stringify(view).includes(ALICE));
    for (const table of ['live_tickets', 'live_ticket_messages']) {
        const raw = JSON.stringify([...connection.storage.sql.exec(`SELECT * FROM ${table}`)]);
        assert.ok(!raw.includes(privateText)); assert.ok(!raw.includes(ALICE));
    }
    const reply = connection.store.replyTicket(ticket.id, 'Ответ поддержки', 'reply-one');
    assert.equal(delivery(connection, reply.outboxId).chat_id, ALICE);
    assert.equal(delivery(connection, reply.outboxId).parse_mode, undefined);
    assert.deepEqual(connection.store.replyTicket(ticket.id, 'Ответ поддержки', 'reply-one'), reply);
    assert.throws(() => connection.store.replyTicket(ticket.id, 'Другой ответ', 'reply-one'), codeError('IDEMPOTENCY_CONFLICT'));
    assert.equal(connection.store.readTicket(ticket.id).messages.length, 2);
});

test('info buttons are callback-only, account-bound, strict and idempotent; nextDueAt tracks work', async t => {
    const connection = database(t).open();
    assert.equal(connection.store.nextDueAt(NOW), null);
    const markup = { inline_keyboard: [[{ text: 'Согласен', callback_data: 'terms:2026-10-12-v1' }]] };
    const queued = connection.store.enqueueMessage(ALICE, 'Условия', 'terms-one', markup);
    assert.deepEqual(connection.store.enqueueMessage(ALICE, 'Условия', 'terms-one', markup), queued);
    assert.equal(connection.store.nextDueAt(NOW), NOW);
    assert.deepEqual(delivery(connection, queued.outboxId).reply_markup, markup);
    assert.throws(() => connection.store.enqueueMessage(ALICE, 'Другой текст', 'terms-one', markup), codeError('IDEMPOTENCY_CONFLICT'));
    for (const bad of [{ inline_keyboard: [[{ text: 'Site', url: 'https://example.com' }]] },
        { inline_keyboard: [[{ text: 'Bad', callback_data: 'x'.repeat(65) }]] }, { force_reply: true }]) {
        assert.throws(() => connection.store.enqueueMessage(ALICE, 'Текст', 'bad', bad));
    }
    const claim = await connection.store.claimDue('live_test_lease_01234567890123456789', NOW);
    assert.equal(claim.id, queued.outboxId);
    assert.equal(connection.store.nextDueAt(NOW), NOW + 30000);
    await connection.store.finishAck(claim.id, claim.leaseId, { messageId: 1 }, NOW + 1);
    assert.equal(connection.store.nextDueAt(NOW), null);
});

test('conversational hasReply guard is account-bound and durable for replies, key resend and invoices', t => {
    const db = database(t);
    let connection = db.open();
    assert.equal(connection.store.hasReply(ALICE, 'reply'), false);
    connection.store.enqueueMessage(ALICE, 'Text', 'reply');
    connection.store.enqueueKeys(ALICE, 'keys');
    order(connection, 'invoice');
    connection.close(); connection = db.open();
    for (const requestKey of ['reply', 'keys', 'invoice']) {
        assert.equal(connection.store.hasReply(ALICE, requestKey), true);
        assert.equal(connection.store.hasReply(BOB, requestKey), false);
    }
    assert.equal(connection.store.hasReply(ALICE, 'never'), false);
    assert.throws(() => connection.store.hasReply(ALICE, 'x'.repeat(129)), codeError('INVALID_INPUT'));
});

test('GCM authentication binds delivery ID and kind and rejects a wrong key', t => {
    const connection = database(t).open();
    const queued = connection.store.enqueueMessage(ALICE, 'Sensitive', 'cipher');
    const row = [...connection.storage.sql.exec('SELECT * FROM billing_bot_outbox WHERE id=?', queued.outboxId)][0];
    const item = { id: row.id, kind: row.kind, encryptedPayload: JSON.parse(row.encrypted_payload) };
    assert.throws(() => connection.store.decryptOutbox({ ...item, id: 'f'.repeat(64) }));
    assert.throws(() => connection.store.decryptOutbox({ ...item, kind: 'telegram_paid_key' }));
    const other = createLiveStore(connection.storage, { encryptionKey: Buffer.alloc(32, 99), keyPepper: PEPPER });
    assert.throws(() => other.decryptOutbox(item));
});

test('buyer receipt and key retry include the same order reference without exposing another buyer', t => {
    const connection = database(t).open();
    const alice = paid(connection, 'alice');
    const bob = paid(connection, 'bob', 'ultra', BOB);
    const receipt = delivery(connection, alice.result.outboxId);
    assert.equal(receipt.chat_id, ALICE);
    assert.ok(receipt.text.includes(`Заказ: ${alice.id}`));
    assert.ok(receipt.text.includes(alice.code));
    assert.ok(!receipt.text.includes(bob.id));
    const retry = delivery(connection, connection.store.enqueueKeys(ALICE, 'key-retry').outboxId);
    assert.ok(retry.text.includes(`Заказ: ${alice.id}`));
    assert.ok(retry.text.includes(alice.code));
    assert.ok(!retry.text.includes(bob.id) && !retry.text.includes(bob.code));
    connection.store.redeem(ALICE, alice.code);
    const redeemed = delivery(connection, connection.store.enqueueKeys(ALICE, 'key-after-use').outboxId);
    assert.ok(redeemed.text.includes(`Заказ: ${alice.id}`));
    assert.match(redeemed.text, /redeemed/);
    assert.ok(!redeemed.text.includes(alice.code));
    connection.store.prepareRefund(alice.id);
    connection.store.recordRefund({ chargeId: 'charge-alice' });
    const refunded = delivery(connection, connection.store.enqueueKeys(ALICE, 'key-after-refund').outboxId);
    assert.ok(refunded.text.includes(`Заказ: ${alice.id}`));
    assert.match(refunded.text, /refunded/);
    assert.ok(!refunded.text.includes(alice.code));
});

test('ticket-scoped paid order lookup is bounded, persistent and follows ticket identity rather than user text', t => {
    const db = database(t);
    let connection = db.open();
    const bob = paid(connection, 'bob', 'ultra', BOB);
    const alice = [];
    for (let i = 0; i < 7; i++) {
        db.state.at += 60_000;
        alice.push(paid(connection, `alice-${i}`, i % 2 ? 'plus' : 'based'));
    }
    // A malicious mention of Bob's order must never change the query scope.
    const ticket = connection.store.createSupportTicket(ALICE, `Верните ${bob.id}`, 'alice-ticket');
    order(connection, 'unpaid');
    const tablesBefore = ['live_orders', 'live_payments', 'live_codes', 'live_grants', 'live_ledger', 'billing_bot_outbox']
        .map(table => count(connection.storage, table));
    const first = connection.store.listTicketOrders(ticket.id);
    assert.deepEqual(first.orders.map(o => o.id), alice.slice(2).reverse().map(o => o.id));
    assert.equal(first.page, 1); assert.equal(first.hasMore, true);
    for (const view of first.orders) assert.deepEqual(Object.keys(view).sort(), ['createdAtMs', 'id', 'planId', 'starsAmount', 'status']);
    const serialized = JSON.stringify(first);
    for (const forbidden of [ALICE, BOB, bob.id, bob.code, ...alice.map(a => a.code), 'charge-alice']) assert.ok(!serialized.includes(forbidden));
    assert.deepEqual(['live_orders', 'live_payments', 'live_codes', 'live_grants', 'live_ledger', 'billing_bot_outbox']
        .map(table => count(connection.storage, table)), tablesBefore, 'read-only lookup cannot trigger refunds or delivery');
    connection.close(); connection = db.open();
    assert.deepEqual(connection.store.listTicketOrders(ticket.id), first);
    const second = connection.store.listTicketOrders(ticket.id, 2);
    assert.deepEqual(second.orders.map(o => o.id), alice.slice(0, 2).reverse().map(o => o.id));
    assert.equal(second.hasMore, false);
    assert.deepEqual(connection.store.listTicketOrders(ticket.id, 3).orders, []);
    for (const page of [0, -1, 1.5, 1001, NaN, Infinity, '1']) {
        assert.throws(() => connection.store.listTicketOrders(ticket.id, page), codeError('INVALID_PAGE'));
    }
    assert.throws(() => connection.store.listTicketOrders('f'.repeat(48)), codeError('TICKET_NOT_FOUND'));
    connection.store.prepareRefund(alice[6].id);
    assert.equal(connection.store.listTicketOrders(ticket.id).orders[0].status, 'refund_pending');
    connection.store.recordRefund({ chargeId: 'charge-alice-6' });
    assert.equal(connection.store.listTicketOrders(ticket.id).orders[0].status, 'refunded');
    const noPurchases = connection.store.createSupportTicket(OWNER, 'Нет покупок', 'owner-ticket');
    assert.deepEqual(connection.store.listTicketOrders(noPurchases.id).orders, []);
});

test('twenty key references still fit the Telegram message and encrypted outbox limits', t => {
    const db = database(t);
    const connection = db.open();
    const ids = [];
    for (let i = 0; i < 21; i++) {
        db.state.at += 60_000;
        ids.push(paid(connection, `capacity-${i}`, 'standard').id);
    }
    const resend = delivery(connection, connection.store.enqueueKeys(ALICE, 'twenty-keys').outboxId);
    assert.equal((resend.text.match(/Заказ: /g) || []).length, 20);
    assert.ok(!resend.text.includes(ids[0]));
    assert.ok(resend.text.length <= 4096);
    assert.ok(Buffer.byteLength(JSON.stringify(resend)) <= 8192);
});
