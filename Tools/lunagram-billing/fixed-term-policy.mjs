import { addCalendarMonths, durationByMonths, planById, requireBilling } from './catalog.mjs';

/**
 * Pure interval policy used by live-store's transactional v2 activation path.
 * It remains independent of HTTP, bot, Android, payment and transport concerns.
 * Records deliberately match existing identities: grantId is live_grants.code_hash
 * (64 lowercase hex), orderId is live_orders.id (48 hex), ownerHash is owner_hash.
 * No code, Telegram ID, charge credentials, clock, database or network lives here.
 *
 * The caller must provide an authenticated account's trusted persisted records
 * and a server-approved startsAtMs. Shape/calendar checks do NOT authenticate
 * client input or prove payment. This module does not pick a queue start, create
 * a payment, issue a key, migrate legacy grants, allocate an order or claim a code.
 * Integration still needs one durable transaction and uniqueness constraints.
 *
 * Intervals are [startsAtMs, expiresAtMs). Even refunded historical intervals
 * remain reserved: no automatic gap filling, rebasing, compaction or extension.
 * Refund authorization/primary-charge matching belongs to the payment store;
 * a confirmed review/duplicate charge must never be passed as a primary refund.
 */
const MAX_TIME_MS = 8_000_000_000_000_000; // Same strict upper bound as live-store.
const GRANT_INPUT = ['grantId', 'orderId', 'ownerHash', 'planId', 'months', 'startsAtMs'];
const GRANT_FIELDS = [...GRANT_INPUT, 'expiresAtMs'];

function record(value, keys, code) {
    requireBilling(value !== null && typeof value === 'object'
        && [Object.prototype, null].includes(Object.getPrototypeOf(value)), code);
    const fields = Reflect.ownKeys(value);
    requireBilling(fields.length === keys.length && fields.every(key => keys.includes(key)), code);
    requireBilling(fields.every(key => Object.hasOwn(Object.getOwnPropertyDescriptor(value, key), 'value')), code);
    return value;
}
function identity(value, length, code) {
    requireBilling(typeof value === 'string' && value.length === length && !/[^0-9a-f]/.test(value), code);
    return value;
}
function timestamp(value) {
    requireBilling(Number.isSafeInteger(value) && value >= 0 && value <= MAX_TIME_MS, 'INVALID_TIME');
    return value;
}
function list(value, code) {
    requireBilling(Array.isArray(value) && Object.getPrototypeOf(value) === Array.prototype
        && Reflect.ownKeys(value).length === value.length + 1
        && Object.keys(value).length === value.length, code);
    requireBilling(Object.keys(value).every((key, index) => key === String(index)
        && Object.hasOwn(Object.getOwnPropertyDescriptor(value, key), 'value')), code);
    return value;
}

/** Construct a term from an approved start; callers cannot supply or extend its end. */
export function createFixedTermGrant(input) {
    record(input, GRANT_INPUT, 'INVALID_GRANT');
    const grantId = identity(input.grantId, 64, 'INVALID_GRANT_ID');
    const orderId = identity(input.orderId, 48, 'INVALID_ORDER_ID');
    const ownerHash = identity(input.ownerHash, 64, 'INVALID_OWNER');
    const planId = planById(input.planId).id;
    const months = durationByMonths(input.months).months;
    const startsAtMs = timestamp(input.startsAtMs);
    const expiresAtMs = timestamp(addCalendarMonths(startsAtMs, months));
    requireBilling(expiresAtMs > startsAtMs, 'INVALID_INTERVAL');
    return Object.freeze({ grantId, orderId, ownerHash, planId, months, startsAtMs, expiresAtMs });
}

function persistedGrant(value) {
    record(value, GRANT_FIELDS, 'INVALID_GRANT');
    timestamp(value.expiresAtMs);
    const term = createFixedTermGrant(Object.fromEntries(GRANT_INPUT.map(key => [key, value[key]])));
    requireBilling(term.expiresAtMs === value.expiresAtMs, 'INVALID_INTERVAL');
    return term;
}

/** Validate/copy a single account's schedule; no live migration or database write. */
export function createFixedTermSchedule(ownerHash, grants, refunds = []) {
    identity(ownerHash, 64, 'INVALID_OWNER');
    const terms = list(grants, 'INVALID_SCHEDULE').map(persistedGrant);
    const grantIds = new Set(), orderIds = new Set();
    for (const term of terms) {
        requireBilling(term.ownerHash === ownerHash, 'OWNER_MISMATCH');
        requireBilling(!grantIds.has(term.grantId), 'DUPLICATE_GRANT');
        requireBilling(!orderIds.has(term.orderId), 'DUPLICATE_ORDER');
        grantIds.add(term.grantId); orderIds.add(term.orderId);
    }
    terms.sort((a, b) => a.startsAtMs - b.startsAtMs);
    for (let index = 1; index < terms.length; index++) {
        requireBilling(terms[index].startsAtMs >= terms[index - 1].expiresAtMs, 'OVERLAPPING_TERMS');
    }
    const refundedOrders = new Set();
    const states = list(refunds, 'INVALID_REFUNDS').map(value => {
        record(value, ['orderId', 'status'], 'INVALID_REFUND');
        const orderId = identity(value.orderId, 48, 'INVALID_ORDER_ID');
        requireBilling(value.status === 'refund_pending' || value.status === 'refunded', 'INVALID_REFUND_STATUS');
        requireBilling(orderIds.has(orderId), 'REFUND_ORDER_NOT_FOUND');
        requireBilling(!refundedOrders.has(orderId), 'DUPLICATE_REFUND');
        refundedOrders.add(orderId);
        return Object.freeze({ orderId, status: value.status });
    }).sort((a, b) => a.orderId.localeCompare(b.orderId));
    return Object.freeze({ ownerHash, grants: Object.freeze(terms), refunds: Object.freeze(states) });
}

function schedule(value) {
    record(value, ['ownerHash', 'grants', 'refunds'], 'INVALID_SCHEDULE');
    return createFixedTermSchedule(value.ownerHash, value.grants, value.refunds);
}

/** Pure projection of a trusted primary-refund event; never initiates a refund. */
export function withFixedTermRefund(value, orderId, status) {
    const current = schedule(value);
    identity(orderId, 48, 'INVALID_ORDER_ID');
    requireBilling(status === 'refund_pending' || status === 'refunded', 'INVALID_REFUND_STATUS');
    requireBilling(current.grants.some(term => term.orderId === orderId), 'REFUND_ORDER_NOT_FOUND');
    const previous = current.refunds.find(refund => refund.orderId === orderId);
    requireBilling(previous?.status !== 'refunded' || status === 'refunded', 'REFUND_STATE_REGRESSION');
    return createFixedTermSchedule(current.ownerHash, current.grants,
        [...current.refunds.filter(refund => refund.orderId !== orderId), { orderId, status }]);
}

/** Select the current term and future terms without replaying any grant durations. */
export function selectFixedTerms(value, nowMs) {
    const current = schedule(value);
    timestamp(nowMs);
    const revoked = new Set(current.refunds.filter(refund => refund.status === 'refunded').map(refund => refund.orderId));
    const available = current.grants.filter(term => !revoked.has(term.orderId));
    const activeTerm = available.find(term => term.startsAtMs <= nowMs && nowMs < term.expiresAtMs) ?? null;
    return Object.freeze({ asOfMs: nowMs, activeTerm,
        queued: Object.freeze(available.filter(term => term.startsAtMs > nowMs)) });
}
