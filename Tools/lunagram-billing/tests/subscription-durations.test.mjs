import test from 'node:test';
import assert from 'node:assert/strict';
import {
    CATALOG, SUBSCRIPTION_DURATIONS, durationByMonths, addCalendarMonth, addCalendarMonths,
} from '../catalog.mjs';
import { STARS_TARIFFS, roundedStarsForDuration } from '../stars-tariffs.mjs';

const invalidDurations = [
    undefined, null, '', '1', '3', 0, -1, 2, 4, 5, 7, 9, 13, 1.5,
    NaN, Infinity, -Infinity, Number.MAX_SAFE_INTEGER, 1n, true, {}, [], new Number(1),
];

test('duration catalog is exact, deeply immutable and has strict approved selectors', () => {
    assert.deepEqual(SUBSCRIPTION_DURATIONS, [
        { months: 1, factorNumerator: 2, factorDenominator: 2 },
        { months: 3, factorNumerator: 5, factorDenominator: 2 },
        { months: 6, factorNumerator: 8, factorDenominator: 2 },
        { months: 12, factorNumerator: 14, factorDenominator: 2 },
    ]);
    assert.ok(Object.isFrozen(SUBSCRIPTION_DURATIONS));
    assert.throws(() => SUBSCRIPTION_DURATIONS.push({ months: 24 }), TypeError);
    for (const duration of SUBSCRIPTION_DURATIONS) {
        assert.equal(durationByMonths(duration.months), duration);
        assert.ok(Object.isFrozen(duration));
        assert.throws(() => { duration.factorNumerator = 99; }, TypeError);
    }
    for (const value of invalidDurations) {
        assert.throws(() => durationByMonths(value), error => error.code === 'INVALID_DURATION');
        assert.throws(() => roundedStarsForDuration(70, value), error => error.code === 'INVALID_DURATION');
        assert.throws(() => addCalendarMonths(0, value), error => error.code === 'INVALID_DURATION');
    }
});

const expectedStars = {
    based: [40, 100, 160, 280],
    standard: [70, 180, 280, 490],
    plus: [100, 250, 400, 700],
    pro: [140, 350, 560, 980],
    ultra: [360, 900, 1440, 2520],
};
for (const plan of CATALOG) {
    for (const [index, duration] of SUBSCRIPTION_DURATIONS.entries()) {
        test(`${plan.id}: ${duration.months} months uses approved monthly Stars times its factor`, () => {
            assert.equal(roundedStarsForDuration(STARS_TARIFFS[plan.id], duration.months), expectedStars[plan.id][index]);
        });
    }
}

test('Stars rounding is integer-exact half up, including non-multiple monthly inputs', () => {
    assert.equal(roundedStarsForDuration(70, 3), 180); // 175, not 170.
    for (const [monthly, expected] of [[5, 10], [14, 10], [15, 20], [16, 20]]) {
        assert.equal(roundedStarsForDuration(monthly, 1), expected);
    }
    assert.equal(roundedStarsForDuration(6, 3), 20); // 15 rounds once, not 6 -> 10 -> 25 -> 30.
    assert.equal(roundedStarsForDuration(7, 3), 20); // Exact 17.5 is supported by the rational factor.
});

test('Stars amount validation rejects coercion, unsafe inputs, zero rounding and result overflow', () => {
    for (const value of [undefined, null, '', '70', 0, -1, 1.5, NaN, Infinity, -Infinity,
        Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER + 1, 70n, true, {}, [], new Number(70)]) {
        assert.throws(() => roundedStarsForDuration(value, 3), error => error.code === 'INVALID_AMOUNT');
    }
    for (const value of [1, 2, 3, 4]) {
        assert.throws(() => roundedStarsForDuration(value, 1), error => error.code === 'INVALID_AMOUNT');
    }
    assert.equal(roundedStarsForDuration(1_000_000, 1), 1_000_000);
    assert.equal(roundedStarsForDuration(999_995, 1), 1_000_000);
    assert.equal(roundedStarsForDuration(1_000_004, 1), 1_000_000);
    assert.throws(() => roundedStarsForDuration(1_000_005, 1), error => error.code === 'INVALID_AMOUNT');
    assert.equal(roundedStarsForDuration(142_857, 12), 1_000_000);
    assert.throws(() => roundedStarsForDuration(142_858, 12), error => error.code === 'INVALID_AMOUNT');
});

const isoAfter = (start, months) => new Date(addCalendarMonths(Date.parse(start), months)).toISOString();

test('calendar terms clamp the original day once at the final UTC month', () => {
    assert.equal(isoAfter('2026-01-31T12:34:56.789Z', 1), '2026-02-28T12:34:56.789Z');
    assert.equal(isoAfter('2026-01-31T12:34:56.789Z', 3), '2026-04-30T12:34:56.789Z');
    assert.equal(isoAfter('2026-01-31T12:34:56.789Z', 6), '2026-07-31T12:34:56.789Z');
    assert.equal(isoAfter('2026-01-31T12:34:56.789Z', 12), '2027-01-31T12:34:56.789Z');
    assert.equal(isoAfter('2026-10-31T23:59:59.999Z', 3), '2027-01-31T23:59:59.999Z');
    assert.equal(isoAfter('2026-08-31T00:00:00.000Z', 6), '2027-02-28T00:00:00.000Z');
    const january = Date.parse('2026-01-31T12:34:56.789Z');
    assert.equal(new Date(addCalendarMonth(addCalendarMonth(addCalendarMonth(january)))).toISOString(),
        '2026-04-28T12:34:56.789Z'); // Existing one-month composition is intentionally unchanged.
});

test('calendar terms preserve UTC time across leap years and century boundaries', () => {
    for (const [start, months, expected] of [
        ['2024-01-31T01:02:03.004Z', 1, '2024-02-29T01:02:03.004Z'],
        ['2024-02-29T01:02:03.004Z', 12, '2025-02-28T01:02:03.004Z'],
        ['2023-11-30T01:02:03.004Z', 3, '2024-02-29T01:02:03.004Z'],
        ['2023-08-31T01:02:03.004Z', 6, '2024-02-29T01:02:03.004Z'],
        ['2000-01-31T01:02:03.004Z', 1, '2000-02-29T01:02:03.004Z'],
        ['2100-01-31T01:02:03.004Z', 1, '2100-02-28T01:02:03.004Z'],
        ['2026-03-29T00:30:00.123Z', 1, '2026-04-29T00:30:00.123Z'],
    ]) assert.equal(isoAfter(start, months), expected);
    assert.equal(new Date(addCalendarMonths(0, 1)).toISOString(), '1970-02-01T00:00:00.000Z');
});

test('calendar inputs and Date-range boundaries fail closed without overflow or mutation', () => {
    for (const value of [undefined, null, '', '0', -1, 0.5, NaN, Infinity, -Infinity,
        Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER + 1, 8_640_000_000_000_001, 0n, true, {}, [], new Date(0)]) {
        assert.throws(() => addCalendarMonths(value, 1), error => error.code === 'INVALID_TIME');
    }
    const maxDateMs = 8_640_000_000_000_000;
    assert.equal(addCalendarMonths(Date.parse('+275760-08-13T00:00:00.000Z'), 1), maxDateMs);
    assert.equal(addCalendarMonths(Date.parse('+275760-06-13T00:00:00.000Z'), 3), maxDateMs);
    assert.equal(addCalendarMonths(Date.parse('+275760-03-13T00:00:00.000Z'), 6), maxDateMs);
    assert.equal(addCalendarMonths(Date.parse('+275759-09-13T00:00:00.000Z'), 12), maxDateMs);
    for (const start of ['+275760-08-13T00:00:00.001Z', '+275760-08-14T00:00:00.000Z',
        '+275760-08-31T00:00:00.000Z', '+275760-09-01T00:00:00.000Z']) {
        assert.throws(() => addCalendarMonths(Date.parse(start), 1), error => error.code === 'INVALID_TIME');
    }
    for (const { months } of SUBSCRIPTION_DURATIONS) {
        assert.throws(() => addCalendarMonths(maxDateMs, months), error => error.code === 'INVALID_TIME');
    }
    const start = Date.parse('2026-01-31T12:34:56.789Z');
    assert.equal(addCalendarMonths(start, 3), addCalendarMonths(start, 3));
    assert.equal(start, Date.parse('2026-01-31T12:34:56.789Z'));
});

test('one-month helper compatibility holds for ordinary valid calendar dates', () => {
    for (let year = 1999; year <= 2101; year++) {
        for (let month = 0; month < 12; month++) {
            for (const day of [1, 15, 28, 29, 30, 31]) {
                const start = Date.UTC(year, month, day, 23, 59, 58, 123);
                if (new Date(start).getUTCMonth() !== month) continue;
                assert.equal(addCalendarMonths(start, 1), addCalendarMonth(start));
            }
        }
    }
});
