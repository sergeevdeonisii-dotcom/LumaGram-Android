import { randomBytes } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { addCalendarMonth, planById, requireBilling, telegramUserId } from './catalog.mjs';
import { activationCodeHash, generateActivationCode, normalizeActivationCode, verifyCryptoPayWebhook, verifyPrivateBotMessage } from './security.mjs';
import { CHECKOUT_SECONDS, checkCreatedCryptoInvoice, checkPaidCryptoInvoice, checkPaidTonTransfer, cryptoPayRequest, makeTonQuote } from './providers.mjs';
import { checkStarsPayment, checkStarsPreCheckout, starsInvoiceRequest, starsPreCheckout, starsPrice, starsSuccessfulPayment } from './stars.mjs';

const opaqueId = () => randomBytes(24).toString('base64url');
const RATE_WINDOW_MS = 60_000;
function orderView(order) {
    return { id: order.id, planId: order.planId, provider: order.provider, status: order.status,
        usdCents: order.usdCents, expiresAtMs: order.expiresAtMs, checkoutUrl: order.checkoutUrl || null };
}
function grantMonth(tx, userId, planId, nowMs) {
    const old = tx.getEntitlement(userId);
    const entitlement = { telegramUserId: userId, planId,
        expiresAtMs: addCalendarMonth(Math.max(nowMs, old?.expiresAtMs || 0)), featuresGated: false };
    tx.putEntitlement(entitlement);
    return entitlement;
}

/**
 * No HTTP listener, network client, demo credentials or production memory database.
 * Dependencies are SERVER-OWNED adapters, never request JSON. See README contract.
 */
export function createBillingService({ enabled = false, repository, authenticate,
    cryptoPay, ton, stars, keyPepper, clock = Date.now, testing = false } = {}) {
    function configured() {
        requireBilling(enabled && repository && typeof repository.transaction === 'function'
            && (testing === true || repository.durable === true)
            && typeof authenticate === 'function' && Buffer.isBuffer(keyPepper) && keyPepper.length >= 32, 'NOT_CONFIGURED');
    }
    async function userFor(session) {
        configured();
        // The adapter verifies an opaque, expiring server session, issued only after
        // Telegram proof. It must not just return a client-provided Telegram numeric ID.
        return telegramUserId(await authenticate(session));
    }
    async function rateLimit(userId, action, limit) {
        const now = clock();
        const bucket = `${action}:${userId}:${Math.floor(now / RATE_WINDOW_MS)}`;
        const allowed = await repository.transaction(tx => tx.consumeRateLimit(bucket, limit, now + RATE_WINDOW_MS));
        requireBilling(allowed, 'RATE_LIMITED');
    }
    async function ownedOrder(userId, orderId) {
        return repository.transaction(tx => {
            const order = tx.getOrder(orderId);
            requireBilling(order && order.telegramUserId === userId, 'ORDER_NOT_FOUND');
            return order;
        });
    }
    async function finalize(order, payment) {
        return repository.transaction(tx => {
            const current = tx.getOrder(order.id);
            requireBilling(current && current.invoiceId === order.invoiceId && current.provider === order.provider, 'PAYMENT_MISMATCH');
            const existing = tx.getPayment(payment.eventId);
            requireBilling(!existing || existing.orderId === current.id, 'PAYMENT_REUSED');
            if (existing) return orderView(current);
            requireBilling(current.status === 'pending', 'ORDER_NOT_PENDING');
            // These writes MUST commit/rollback together, with unique payment.eventId.
            tx.putPayment({ ...payment, orderId: current.id });
            if (current.delivery === 'direct') grantMonth(tx, current.telegramUserId, current.planId, clock());
            current.status = 'paid';
            tx.putOrder(current);
            return orderView(current);
        });
    }
    return Object.freeze({
        async prepareBotStarsInvoice({ rawBody, webhookSecret } = {}) {
            configured();
            requireBilling(stars?.salesEnabled === true, 'NOT_CONFIGURED');
            const command = verifyPrivateBotMessage(rawBody, webhookSecret, stars.webhookSecret, clock());
            const match = /^\/buy (based|standard|plus|pro|ultra)$/.exec(command.text);
            requireBilling(match, 'INVALID_REQUEST');
            const plan = planById(match[1]);
            const amount = starsPrice(stars.prices, plan.id);
            await rateLimit(command.telegramUserId, 'purchase', 10);
            const order = await repository.transaction(tx => {
                // Durable update deduplication: replayed bot command returns the same
                // pending invoice payload, never a second independent purchase.
                const requestKey = `bot-update:${command.updateId}`;
                const previous = tx.getOrderByRequest(requestKey);
                if (previous) {
                    requireBilling(previous.telegramUserId === command.telegramUserId && previous.planId === plan.id
                        && previous.status === 'pending', 'ORDER_NOT_PENDING');
                    return previous;
                }
                const now = clock();
                const created = { id: opaqueId(), requestKey, telegramUserId: command.telegramUserId, planId: plan.id,
                    usdCents: plan.usdCents, provider: 'stars', delivery: 'key', starsAmount: amount, status: 'pending',
                    createdAtMs: now, expiresAtMs: now + CHECKOUT_SECONDS * 1000 };
                tx.putOrder(created);
                return created;
            });
            // Return a request, do not send it. Production requires a durable bot outbox.
            return { order: orderView(order), invoice: starsInvoiceRequest(order) };
        },
        async prepareStarsPreCheckoutReply({ rawBody, webhookSecret } = {}) {
            configured();
            requireBilling(stars?.salesEnabled === true, 'NOT_CONFIGURED');
            const query = starsPreCheckout(rawBody, webhookSecret, stars.webhookSecret);
            return repository.transaction(tx => {
                const order = tx.getOrder(query.orderId);
                const reply = checkStarsPreCheckout(order, query, clock());
                order.preCheckoutId = query.id;
                tx.putOrder(order);
                return reply;
            });
        },
        async handleStarsSuccessfulPayment({ rawBody, webhookSecret } = {}) {
            configured();
            // Reconciliation remains possible after new Stars sales are disabled.
            const payment = starsSuccessfulPayment(rawBody, webhookSecret, stars?.webhookSecret, clock());
            const order = await repository.transaction(tx => tx.getOrder(payment.orderId));
            return finalize(order, checkStarsPayment(order, payment));
        },
        async createAppPurchase({ session, planId, provider, delivery = 'direct', idempotencyKey } = {}) {
            const userId = await userFor(session);
            const plan = planById(planId);
            requireBilling(['cryptopay', 'ton'].includes(provider) && ['direct', 'key'].includes(delivery), 'INVALID_REQUEST');
            requireBilling(typeof idempotencyKey === 'string' && /^[A-Za-z0-9_-]{16,64}$/.test(idempotencyKey), 'INVALID_REQUEST');
            if (provider === 'cryptopay') requireBilling(cryptoPay && typeof cryptoPay.createInvoice === 'function'
                && typeof cryptoPay.getInvoice === 'function' && typeof cryptoPay.apiToken === 'string'
                && cryptoPay.apiToken.length >= 16 && cryptoPay.checkoutPolicyApproved === true, 'NOT_CONFIGURED');
            if (provider === 'ton') requireBilling(ton && typeof ton.getRate === 'function'
                && typeof ton.findPayment === 'function', 'NOT_CONFIGURED');
            await rateLimit(userId, 'purchase', 10);
            const requestKey = `${userId}:${idempotencyKey}`;
            const result = await repository.transaction(tx => {
                const previous = tx.getOrderByRequest(requestKey);
                if (previous) {
                    requireBilling(previous.planId === planId && previous.provider === provider && previous.delivery === delivery, 'IDEMPOTENCY_CONFLICT');
                    return { fresh: false, order: previous };
                }
                const now = clock();
                const order = { id: opaqueId(), requestKey, telegramUserId: userId, planId,
                    usdCents: plan.usdCents, provider, delivery, status: 'creating',
                    createdAtMs: now, expiresAtMs: now + CHECKOUT_SECONDS * 1000 };
                tx.putOrder(order);
                return { fresh: true, order };
            });
            if (!result.fresh) return orderView(result.order);
            const order = result.order;
            try {
                if (provider === 'cryptopay') {
                    Object.assign(order, checkCreatedCryptoInvoice(order, await cryptoPay.createInvoice(cryptoPayRequest(order)), clock()));
                } else {
                    order.ton = makeTonQuote(order, await ton.getRate(), clock());
                    order.checkoutUrl = order.ton.checkoutUrl;
                }
                order.status = 'pending';
                await repository.transaction(tx => tx.putOrder(order));
                return orderView(order);
            } catch {
                // A network timeout may already have created a payable invoice. Do NOT
                // create another invoice on retry; reconcile this order administratively.
                await repository.transaction(tx => {
                    const current = tx.getOrder(order.id);
                    current.status = 'review_required';
                    tx.putOrder(current);
                });
                requireBilling(false, 'CHECKOUT_UNAVAILABLE');
            }
        },
        async reconcilePurchase({ session, orderId } = {}) {
            const userId = await userFor(session);
            await rateLimit(userId, 'reconcile', 20);
            const order = await ownedOrder(userId, orderId);
            if (order.status === 'paid') return orderView(order);
            requireBilling(order.status === 'pending', 'ORDER_NOT_PENDING');
            requireBilling(order.provider !== 'stars', 'AWAITING_TELEGRAM_PAYMENT');
            if (order.provider === 'cryptopay') {
                requireBilling(typeof cryptoPay?.getInvoice === 'function', 'NOT_CONFIGURED');
                const invoice = await cryptoPay.getInvoice(order.invoiceId);
                return finalize(order, checkPaidCryptoInvoice(order, invoice, clock()));
            }
            requireBilling(typeof ton?.findPayment === 'function', 'NOT_CONFIGURED');
            const receipt = await ton.findPayment({ recipient: order.ton.recipient, comment: order.ton.comment,
                fromMs: order.createdAtMs, toMs: order.expiresAtMs });
            return finalize(order, checkPaidTonTransfer(order, receipt, clock()));
        },
        async handleCryptoPayWebhook(rawBody, signature) {
            configured();
            requireBilling(typeof cryptoPay?.getInvoice === 'function', 'NOT_CONFIGURED');
            const update = verifyCryptoPayWebhook(rawBody, signature, cryptoPay.apiToken);
            requireBilling(update.update_type === 'invoice_paid'
                && Number.isSafeInteger(update.payload?.invoice_id) && update.payload.invoice_id > 0, 'INVALID_UPDATE');
            const order = await repository.transaction(tx => tx.getOrderByInvoice(String(update.payload.invoice_id)));
            requireBilling(order && order.provider === 'cryptopay', 'ORDER_NOT_FOUND');
            // Signed payload is only a hint: authoritative getInvoices reconciliation
            // also protects against wrong amounts/assets/orders and delayed callbacks.
            const invoice = await cryptoPay.getInvoice(order.invoiceId);
            return finalize(order, checkPaidCryptoInvoice(order, invoice, clock()));
        },
        async issuePurchasedKey({ session, orderId, replaceUndelivered = false } = {}) {
            const userId = await userFor(session);
            await rateLimit(userId, 'issue-key', 5);
            const code = generateActivationCode();
            const hash = activationCodeHash(code, keyPepper);
            await repository.transaction(tx => {
                const order = tx.getOrder(orderId);
                requireBilling(order && order.telegramUserId === userId && order.status === 'paid'
                    && order.delivery === 'key', 'KEY_NOT_AVAILABLE');
                if (order.keyHash) {
                    const previous = tx.getCode(order.keyHash);
                    requireBilling(replaceUndelivered === true && previous && !previous.redeemedBy, 'KEY_NOT_AVAILABLE');
                    previous.revoked = true;
                    tx.putCode(previous);
                }
                // Only the hash is persisted. Raw key is returned once over an
                // authenticated private channel; do not log request/response bodies.
                requireBilling(!tx.getCode(hash), 'RETRY_REQUIRED');
                tx.putCode({ hash, planId: order.planId, orderId, revoked: false, redeemedBy: null });
                order.keyHash = hash;
                tx.putOrder(order);
            });
            return { code, planId: (await ownedOrder(userId, orderId)).planId };
        },
        async redeemCode({ session, code } = {}) {
            const userId = await userFor(session);
            // Limit attempts BEFORE validating format, so malformed guesses also count.
            await rateLimit(userId, 'redeem', 5);
            const hash = activationCodeHash(normalizeActivationCode(code), keyPepper);
            return repository.transaction(tx => {
                const stored = tx.getCode(hash);
                requireBilling(stored && !stored.revoked && !stored.redeemedBy, 'INVALID_CODE');
                const entitlement = grantMonth(tx, userId, stored.planId, clock());
                stored.redeemedBy = userId;
                stored.redeemedAtMs = clock();
                tx.putCode(stored);
                return entitlement;
            });
        },
        async getSubscription({ session } = {}) {
            const userId = await userFor(session);
            const entitlement = await repository.transaction(tx => tx.getEntitlement(userId));
            return { ...(entitlement || { telegramUserId: userId, planId: null, expiresAtMs: null }),
                active: !!entitlement && entitlement.expiresAtMs > clock(), featuresGated: false };
        },
        createBotCryptoPurchase() {
            // Digital goods in Telegram bots/mini apps must use Stars (XTR).
            // No external-payment workaround or silent USD-to-Stars price assumption.
            requireBilling(false, 'BOT_REQUIRES_TELEGRAM_STARS');
        },
    });
}
