import { CATALOG, requireBilling } from './catalog.mjs';

// Owner-approved pricing reference, not a claim about users' retail Star cost
// or Telegram payouts. Confirmed 2026-10-12: 50 Stars / 70 US cents, nearest 10.
export const STARS_PRICING_REFERENCE = Object.freeze({
    usdCents: 70, stars: 50, quantum: 10, rounding: 'nearest_half_up',
});

export function roundedStarsForUsdCents(cents) {
    requireBilling(Number.isSafeInteger(cents) && cents > 0, 'INVALID_AMOUNT');
    const numerator = BigInt(cents) * 50n;
    const denominator = 70n * 10n;
    const amount = ((numerator + denominator / 2n) / denominator) * 10n;
    requireBilling(amount > 0n && amount <= 1_000_000n, 'INVALID_AMOUNT');
    return Number(amount);
}

export const STARS_TARIFFS = Object.freeze(Object.fromEntries(
    CATALOG.map(plan => [plan.id, roundedStarsForUsdCents(plan.usdCents)]),
));
