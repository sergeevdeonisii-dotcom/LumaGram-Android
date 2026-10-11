import test from 'node:test';
import assert from 'node:assert/strict';
import { STARS_TARIFFS, STARS_PRICING_REFERENCE, roundedStarsForUsdCents } from '../stars-tariffs.mjs';

test('exact owner-approved prices use rational 50 Stars / 70 cents, rounded to tens', () => {
    assert.deepEqual(STARS_TARIFFS, { based: 40, standard: 70, plus: 100, pro: 140, ultra: 360 });
    assert.deepEqual(STARS_PRICING_REFERENCE, { usdCents: 70, stars: 50, quantum: 10, rounding: 'nearest_half_up' });
    assert.equal(roundedStarsForUsdCents(70), 50);
    assert.ok(Object.isFrozen(STARS_TARIFFS));
    assert.ok(Object.isFrozen(STARS_PRICING_REFERENCE));
});

test('half-up rounding is integer-exact, not per-Star rounding or unconditional ceiling', () => {
    assert.equal(roundedStarsForUsdCents(34), 20);
    assert.equal(roundedStarsForUsdCents(35), 30);
    assert.equal(roundedStarsForUsdCents(36), 30);
    assert.equal(roundedStarsForUsdCents(100), 70);
    assert.equal(roundedStarsForUsdCents(200), 140);
});

test('malformed, zero-rounded and out-of-bounds amounts fail closed', () => {
    for (const value of [undefined, null, '70', 0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER, 1, 6]) {
        assert.throws(() => roundedStarsForUsdCents(value), error => error.code === 'INVALID_AMOUNT');
    }
});
