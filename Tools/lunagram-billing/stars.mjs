import { planById, requireBilling, telegramUserId } from './catalog.mjs';
import { privateBotSender, verifyBotUpdate } from './security.mjs';

export function starsPrice(prices, planId) {
    planById(planId);
    // Explicit owner-configured integer tariff; no USD/retail Stars conversion fiction.
    const amount = Object.hasOwn(prices || {}, planId) ? prices[planId] : undefined;
    requireBilling(Number.isSafeInteger(amount) && amount > 0 && amount <= 1_000_000, 'STARS_PRICE_NOT_CONFIGURED');
    return amount;
}
export function starsInvoiceRequest(order) {
    requireBilling(order.provider === 'stars' && Number.isSafeInteger(order.starsAmount)
        && order.starsAmount > 0, 'INVALID_REQUEST');
    const plan = planById(order.planId);
    return Object.freeze({ method: 'sendInvoice', chat_id: order.telegramUserId,
        title: `Lunagram ${plan.title}`, description: 'Activation code for one calendar month. Features are currently the same for every plan.',
        payload: order.id, provider_token: '', currency: 'XTR',
        prices: [{ label: plan.title, amount: order.starsAmount }],
        start_parameter: `order_${order.id}` });
}
export function starsPreCheckout(rawBody, suppliedSecret, configuredSecret) {
    const update = verifyBotUpdate(rawBody, suppliedSecret, configuredSecret);
    const query = update.pre_checkout_query;
    requireBilling(query && typeof query.id === 'string' && /^[A-Za-z0-9_-]{1,256}$/.test(query.id)
        && query.from?.is_bot === false && Number.isSafeInteger(query.from.id) && query.from.id > 0
        && typeof query.invoice_payload === 'string' && query.invoice_payload.length <= 128
        && query.currency === 'XTR' && Number.isSafeInteger(query.total_amount) && query.total_amount > 0, 'INVALID_UPDATE');
    return { id: query.id, telegramUserId: telegramUserId(String(query.from.id)),
        orderId: query.invoice_payload, starsAmount: query.total_amount };
}
export function checkStarsPreCheckout(order, query, nowMs) {
    requireBilling(order && order.provider === 'stars' && order.id === query.orderId
        && order.telegramUserId === query.telegramUserId && order.starsAmount === query.starsAmount
        && order.status === 'pending' && order.expiresAtMs > nowMs
        && (!order.preCheckoutId || order.preCheckoutId === query.id), 'CHECKOUT_REJECTED');
    return { method: 'answerPreCheckoutQuery', pre_checkout_query_id: query.id, ok: true };
}
export function starsSuccessfulPayment(rawBody, suppliedSecret, configuredSecret, nowMs) {
    const update = verifyBotUpdate(rawBody, suppliedSecret, configuredSecret);
    const message = update.message;
    const userId = privateBotSender(message);
    const paid = message.successful_payment;
    requireBilling(paid && paid.currency === 'XTR' && Number.isSafeInteger(paid.total_amount) && paid.total_amount > 0
        && typeof paid.invoice_payload === 'string' && paid.invoice_payload.length <= 128
        && typeof paid.telegram_payment_charge_id === 'string' && /^[\x21-\x7e]{1,256}$/.test(paid.telegram_payment_charge_id)
        && paid.is_recurring !== true && paid.is_first_recurring !== true && paid.subscription_expiration_date === undefined
        && Number.isSafeInteger(message.date) && message.date > 0 && message.date * 1000 <= nowMs + 30_000, 'INVALID_UPDATE');
    // No five-minute freshness check: successful payments can be redelivered days later.
    return { telegramUserId: userId, orderId: paid.invoice_payload, starsAmount: paid.total_amount,
        eventId: `stars:${paid.telegram_payment_charge_id}`, paidAtMs: message.date * 1000 };
}
export function checkStarsPayment(order, payment) {
    requireBilling(order && payment && order.provider === 'stars' && order.id === payment.orderId
        && order.telegramUserId === payment.telegramUserId && order.starsAmount === payment.starsAmount
        && Number.isSafeInteger(order.createdAtMs) && order.createdAtMs > 0
        && Number.isSafeInteger(payment.paidAtMs) && payment.paidAtMs > 0
        // Match the bounded Telegram/local clock tolerance used for receipt parsing.
        && payment.paidAtMs >= order.createdAtMs - 30_000, 'PAYMENT_MISMATCH');
    // Valid paid receipts are not discarded just because a delayed completion/retry
    // arrives after order expiry. Unique Telegram charge IDs prevent replay grants.
    return { eventId: payment.eventId, paidAtMs: payment.paidAtMs };
}
