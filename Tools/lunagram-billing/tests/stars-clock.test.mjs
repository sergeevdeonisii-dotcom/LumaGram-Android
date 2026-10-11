import test from 'node:test';
import assert from 'node:assert/strict';
import { checkStarsPayment } from '../stars.mjs';

const NOW = Date.UTC(2026, 9, 12, 12);
const order = Object.freeze({ id: 'test-order', provider: 'stars', telegramUserId: '101',
    starsAmount: 40, createdAtMs: NOW, expiresAtMs: NOW + 900_000 });
const payment = Object.freeze({ orderId: order.id, telegramUserId: '101', starsAmount: 40,
    eventId: 'stars:test-clock-receipt', paidAtMs: NOW });
const mismatch = error => error.code === 'PAYMENT_MISMATCH';

test('Stars paid receipt accepts at most 30 seconds of negative clock skew', () => {
    for (const offset of [0, -10_000, -30_000]) {
        assert.deepEqual(checkStarsPayment(order, { ...payment, paidAtMs: NOW + offset }),
            { eventId: payment.eventId, paidAtMs: NOW + offset });
    }
    for (const offset of [-30_001, -31_000, -300_000]) {
        assert.throws(() => checkStarsPayment(order, { ...payment, paidAtMs: NOW + offset }), mismatch);
    }
});

test('Stars paid timestamps must be positive safe integers on both records', () => {
    for (const value of [undefined, null, false, '1791806400000', 0, -1, 0.5,
        NaN, Infinity, -Infinity, Number.MAX_SAFE_INTEGER + 1, {}, 1n]) {
        assert.throws(() => checkStarsPayment({ ...order, createdAtMs: value }, payment), mismatch);
        assert.throws(() => checkStarsPayment(order, { ...payment, paidAtMs: value }), mismatch);
    }
    assert.throws(() => checkStarsPayment(null, payment), mismatch);
    assert.throws(() => checkStarsPayment(order, null), mismatch);
});

test('clock tolerance never relaxes Stars amount, account, order or provider binding', () => {
    const slightlyEarlier = { ...payment, paidAtMs: NOW - 10_000 };
    for (const patch of [{ starsAmount: 39 }, { starsAmount: '40' }, { telegramUserId: '202' },
        { orderId: 'other-order' }]) {
        assert.throws(() => checkStarsPayment(order, { ...slightlyEarlier, ...patch }), mismatch);
    }
    assert.throws(() => checkStarsPayment({ ...order, provider: 'ton' }, slightlyEarlier), mismatch);
});

test('receipt matching retains delayed-completion support beyond order expiry', () => {
    const later = { ...payment, paidAtMs: NOW + 2 * 86_400_000 };
    // The secret-verified receipt parser separately bounds future timestamps against
    // its current clock; matching must not confuse order expiry with delivery age.
    assert.deepEqual(checkStarsPayment(order, later), { eventId: later.eventId, paidAtMs: later.paidAtMs });
});
