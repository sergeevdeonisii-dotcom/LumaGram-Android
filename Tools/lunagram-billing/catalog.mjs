// Server-side prices. Client-supplied prices and durations must never be trusted.
export const CATALOG = Object.freeze([
    Object.freeze({ id: 'based', title: 'Based', usdCents: 50 }),
    Object.freeze({ id: 'standard', title: 'Standard', usdCents: 100 }),
    Object.freeze({ id: 'plus', title: 'Plus', usdCents: 140 }),
    Object.freeze({ id: 'pro', title: 'Pro', usdCents: 200 }),
    Object.freeze({ id: 'ultra', title: 'Ultra', usdCents: 500 }),
]);

export class BillingError extends Error {
    constructor(code) { super(code); this.name = 'BillingError'; this.code = code; }
}
export function requireBilling(condition, code) {
    if (!condition) throw new BillingError(code);
}
export function planById(id) {
    const plan = CATALOG.find(item => item.id === id);
    requireBilling(plan, 'INVALID_PLAN');
    return plan;
}
export function usdAmount(cents) {
    requireBilling(Number.isSafeInteger(cents) && cents > 0, 'INVALID_AMOUNT');
    return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}
export function telegramUserId(value) {
    requireBilling(typeof value === 'string' && /^[1-9][0-9]{0,15}$/.test(value)
        && BigInt(value) < 2n ** 52n, 'UNAUTHENTICATED');
    return value;
}
export function addCalendarMonth(startMs) {
    requireBilling(Number.isSafeInteger(startMs) && startMs >= 0, 'INVALID_TIME');
    const start = new Date(startMs);
    const end = new Date(startMs);
    end.setUTCDate(1);
    end.setUTCMonth(end.getUTCMonth() + 1);
    const lastDay = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth() + 1, 0)).getUTCDate();
    end.setUTCDate(Math.min(start.getUTCDate(), lastDay));
    requireBilling(Number.isSafeInteger(end.getTime()), 'INVALID_TIME');
    return end.getTime();
}
