import { requireBilling, usdAmount } from './catalog.mjs';
import { Buffer } from 'node:buffer';

export const TON_WALLET = 'UQDwZfLQqfQakdFG642jd24xjILmNKfXoIbjsoEuGMoLX-7G';
export const CHECKOUT_SECONDS = 15 * 60;

export function cryptoPayRequest(order) {
    requireBilling(order.provider === 'cryptopay', 'INVALID_PROVIDER');
    return Object.freeze({
        currency_type: 'fiat', fiat: 'USD', accepted_assets: 'USDT',
        amount: usdAmount(order.usdCents), payload: order.id,
        description: `Lunagram ${order.planId}: one calendar month`,
        allow_comments: false, allow_anonymous: false, expires_in: CHECKOUT_SECONDS,
    });
}
function decimalCents(value) {
    requireBilling(typeof value === 'string' && /^(0|[1-9][0-9]{0,8})(\.[0-9]{1,2}0*)?$/.test(value)
        && value.length <= 64, 'PAYMENT_MISMATCH');
    const [whole, fraction = ''] = value.split('.');
    return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0').slice(0, 2));
}
function invoiceId(value) {
    requireBilling(Number.isSafeInteger(value) && value > 0, 'PAYMENT_MISMATCH');
    return String(value);
}
function checkInvoice(order, invoice) {
    requireBilling(invoice && invoice.currency_type === 'fiat' && invoice.fiat === 'USD'
        && decimalCents(invoice.amount) === BigInt(order.usdCents)
        && invoice.payload === order.id, 'PAYMENT_MISMATCH');
    return invoiceId(invoice.invoice_id);
}
export function checkCreatedCryptoInvoice(order, invoice, nowMs) {
    const id = checkInvoice(order, invoice);
    requireBilling(invoice.status === 'active', 'PAYMENT_MISMATCH');
    const createdAtMs = Date.parse(invoice.created_at);
    const expiresAtMs = Date.parse(invoice.expiration_date);
    // Provider expiry starts at invoice creation, not before our network request.
    requireBilling(Number.isSafeInteger(nowMs) && Number.isSafeInteger(createdAtMs) && Number.isSafeInteger(expiresAtMs)
        && createdAtMs >= order.createdAtMs - 30_000 && createdAtMs <= nowMs + 30_000
        && expiresAtMs > nowMs && expiresAtMs > createdAtMs
        && expiresAtMs <= createdAtMs + CHECKOUT_SECONDS * 1000 + 30_000, 'PAYMENT_MISMATCH');
    // A provider response cannot turn checkout into an arbitrary URL/open redirect.
    let url;
    try { url = new URL(invoice.bot_invoice_url); } catch { requireBilling(false, 'PAYMENT_MISMATCH'); }
    requireBilling(url.protocol === 'https:' && url.hostname === 't.me' && url.port === ''
        && url.username === '' && url.password === '' && /^\/CryptoBot$/i.test(url.pathname)
        && /^[A-Za-z0-9_-]{1,128}$/.test(url.searchParams.get('start') || '')
        && [...url.searchParams].length === 1 && url.hash === '', 'PAYMENT_MISMATCH');
    return { invoiceId: id, checkoutUrl: url.href, expiresAtMs, providerCreatedAtMs: createdAtMs };
}
export function checkPaidCryptoInvoice(order, invoice, nowMs) {
    const id = checkInvoice(order, invoice);
    const paidAtMs = Date.parse(invoice.paid_at);
    requireBilling(order.provider === 'cryptopay' && order.invoiceId === id && invoice.status === 'paid'
        && invoice.paid_asset === 'USDT' && Number.isSafeInteger(paidAtMs)
        && paidAtMs >= (order.providerCreatedAtMs ?? order.createdAtMs) - 1000 && paidAtMs <= order.expiresAtMs
        && paidAtMs <= nowMs + 30_000, 'PAYMENT_MISMATCH');
    return { eventId: `cryptopay:${id}`, paidAtMs };
}
function crc16(bytes) {
    let crc = 0;
    for (const byte of bytes) {
        crc ^= byte << 8;
        for (let bit = 0; bit < 8; bit++) crc = (crc & 0x8000) ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
    }
    return crc;
}
export function canonicalTonAddress(value) {
    requireBilling(typeof value === 'string' && /^[A-Za-z0-9_+/-]{48}$/.test(value), 'INVALID_TON_ADDRESS');
    const bytes = Buffer.from(value, 'base64url');
    requireBilling(bytes.length === 36 && [0x11, 0x51].includes(bytes[0])
        && crc16(bytes.subarray(0, 34)) === bytes.readUInt16BE(34), 'INVALID_TON_ADDRESS');
    return `${bytes.readInt8(1)}:${bytes.subarray(2, 34).toString('hex')}`;
}
export function makeTonQuote(order, rate, nowMs) {
    // Rate comes ONLY from a server-owned oracle adapter: USD cents per TON as a rational.
    requireBilling(order.provider === 'ton' && rate && typeof rate.centsNumerator === 'bigint'
        && typeof rate.denominator === 'bigint' && rate.centsNumerator > 0n && rate.denominator > 0n
        && rate.centsNumerator < 10n ** 18n && rate.denominator < 10n ** 18n
        && Number.isSafeInteger(rate.observedAtMs) && rate.observedAtMs <= nowMs
        && nowMs - rate.observedAtMs <= 60_000, 'INVALID_TON_QUOTE');
    canonicalTonAddress(TON_WALLET); // Fail closed on accidental wallet changes/checksum failure.
    const numerator = BigInt(order.usdCents) * rate.denominator * 1_000_000_000n;
    const nanoTon = (numerator + rate.centsNumerator - 1n) / rate.centsNumerator;
    requireBilling(nanoTon > 0n && nanoTon < (1n << 120n), 'INVALID_TON_QUOTE');
    const comment = `lunagram:${order.id}`;
    return Object.freeze({ nanoTon: nanoTon.toString(), recipient: canonicalTonAddress(TON_WALLET),
        comment, rateObservedAtMs: rate.observedAtMs,
        checkoutUrl: `ton://transfer/${TON_WALLET}?amount=${nanoTon}&text=${encodeURIComponent(comment)}` });
}
export function checkPaidTonTransfer(order, receipt, nowMs) {
    // A server-owned indexer adapter must validate the real mainnet transaction and decode
    // its incoming native-TON message. NEVER expose this normalized-object method as HTTP.
    requireBilling(order.provider === 'ton' && order.ton && receipt?.network === 'mainnet'
        && receipt.finalized === true && receipt.success === true && receipt.bounced === false
        && receipt.asset === 'TON' && receipt.destinationRaw === order.ton.recipient
        && receipt.nanoTon === order.ton.nanoTon && receipt.comment === order.ton.comment
        && typeof receipt.transactionHash === 'string' && /^[0-9a-f]{64}$/.test(receipt.transactionHash)
        && Number.isSafeInteger(receipt.paidAtMs) && receipt.paidAtMs >= order.createdAtMs
        && receipt.paidAtMs <= order.expiresAtMs && receipt.paidAtMs <= nowMs + 30_000, 'PAYMENT_MISMATCH');
    return { eventId: `ton:${receipt.transactionHash}`, paidAtMs: receipt.paidAtMs };
}
