import { Buffer } from 'node:buffer';
import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes } from 'node:crypto';
import { addCalendarMonth, requireBilling, telegramUserId } from '../catalog.mjs';
import { generateActivationCode, normalizeActivationCode } from '../security.mjs';
import { createBotOutboxRepository } from './bot-outbox-repository.mjs';

export const LIVE_PLANS = Object.freeze([
    Object.freeze({ id: 'based', title: 'Based', starsAmount: 40 }),
    Object.freeze({ id: 'standard', title: 'Standard', starsAmount: 70 }),
    Object.freeze({ id: 'plus', title: 'Plus', starsAmount: 100 }),
    Object.freeze({ id: 'pro', title: 'Pro', starsAmount: 140 }),
    Object.freeze({ id: 'ultra', title: 'Ultra', starsAmount: 360 }),
]);
const INVOICE_MS = 15 * 60_000;
const RESERVATION_REPLY_MS = 10_000;
const KIND_KEY = 'telegram_paid_key';
const KIND_INFO = 'telegram_info';
const hash = text => createHash('sha256').update(text).digest('hex');
const opaque = () => randomBytes(24).toString('hex');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function text(value, max = 512) {
    requireBilling(typeof value === 'string' && value.length > 0 && value.length <= max
        && !/[\u0000-\u001f\u007f]/.test(value), 'INVALID_INPUT');
    return value;
}
function timestamp(value) {
    requireBilling(Number.isSafeInteger(value) && value >= 0 && value <= 8_000_000_000_000_000, 'INVALID_TIME');
    return value;
}
function messageText(value) {
    requireBilling(typeof value === 'string' && value.trim().length > 0 && value.length <= 2000
        && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value), 'INVALID_SUPPORT_TEXT');
    return value;
}
function buttons(value) {
    if (value === undefined) return undefined;
    requireBilling(object(value) && Object.keys(value).length === 1 && Array.isArray(value.inline_keyboard)
        && value.inline_keyboard.length > 0 && value.inline_keyboard.length <= 8, 'INVALID_REPLY_MARKUP');
    return { inline_keyboard: value.inline_keyboard.map(row => {
        requireBilling(Array.isArray(row) && row.length > 0 && row.length <= 4, 'INVALID_REPLY_MARKUP');
        return row.map(button => {
            requireBilling(object(button) && Object.keys(button).length === 2 && Object.hasOwn(button, 'text')
                && Object.hasOwn(button, 'callback_data'), 'INVALID_REPLY_MARKUP');
            text(button.text, 64); text(button.callback_data, 64);
            requireBilling(Buffer.byteLength(button.callback_data) <= 64 && !/[^\x20-\x7e]/.test(button.callback_data), 'INVALID_REPLY_MARKUP');
            return { text: button.text, callback_data: button.callback_data };
        });
    }) };
}
const SCHEMA = [
    `CREATE TABLE IF NOT EXISTS live_consents(owner_hash TEXT PRIMARY KEY,version TEXT NOT NULL,accepted_ms INTEGER NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS live_orders(id TEXT PRIMARY KEY,request_hash TEXT NOT NULL UNIQUE,owner_hash TEXT NOT NULL,
        plan_id TEXT NOT NULL,stars INTEGER NOT NULL,terms_version TEXT NOT NULL,status TEXT NOT NULL,
        created_ms INTEGER NOT NULL,expires_ms INTEGER NOT NULL,private_json TEXT NOT NULL,
        reservation_hash TEXT,reserved_ms INTEGER,primary_charge TEXT)`,
    `CREATE INDEX IF NOT EXISTS live_orders_owner ON live_orders(owner_hash,created_ms,id)`,
    `CREATE TABLE IF NOT EXISTS live_payments(charge_hash TEXT PRIMARY KEY,order_id TEXT NOT NULL,status TEXT NOT NULL,
        reason TEXT,created_ms INTEGER NOT NULL,private_json TEXT NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS live_codes(code_hash TEXT PRIMARY KEY,order_id TEXT NOT NULL UNIQUE,private_json TEXT NOT NULL,
        redeemed_hash TEXT,redeemed_ms INTEGER,revoked INTEGER NOT NULL DEFAULT 0)`,
    `CREATE TABLE IF NOT EXISTS live_grants(code_hash TEXT PRIMARY KEY,owner_hash TEXT NOT NULL,order_id TEXT NOT NULL,
        plan_id TEXT NOT NULL,created_ms INTEGER NOT NULL)`,
    `CREATE INDEX IF NOT EXISTS live_grants_owner ON live_grants(owner_hash,created_ms,code_hash)`,
    `CREATE TABLE IF NOT EXISTS live_ledger(id TEXT PRIMARY KEY,kind TEXT NOT NULL,order_id TEXT,owner_hash TEXT,
        created_ms INTEGER NOT NULL,private_json TEXT NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS live_refund_markers(charge_hash TEXT PRIMARY KEY,private_json TEXT NOT NULL,created_ms INTEGER NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS live_limits(bucket TEXT PRIMARY KEY,attempts INTEGER NOT NULL,expires_ms INTEGER NOT NULL)`,
    `CREATE INDEX IF NOT EXISTS live_limits_expiry ON live_limits(expires_ms)`,
    `CREATE TABLE IF NOT EXISTS live_tickets(id TEXT PRIMARY KEY,request_hash TEXT NOT NULL UNIQUE,owner_hash TEXT NOT NULL,
        status TEXT NOT NULL,created_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL,private_json TEXT NOT NULL)`,
    `CREATE INDEX IF NOT EXISTS live_tickets_updated ON live_tickets(updated_ms,id)`,
    `CREATE TABLE IF NOT EXISTS live_ticket_messages(id TEXT PRIMARY KEY,ticket_id TEXT NOT NULL,direction TEXT NOT NULL,
        created_ms INTEGER NOT NULL,private_json TEXT NOT NULL)`,
];

/**
 * Synchronous SQLite state machine. Network/Telegram authentication and admin
 * authorization are caller responsibilities: never expose these methods to raw
 * client user IDs. Refund/support-admin methods are SERVER-ONLY privileged calls.
 * No outgoing requests or automatic refunds occur here. A refund RPC happens
 * after prepareRefund; only confirmed Telegram success/verified refunded_payment
 * may call recordRefund. Failed/ambiguous RPC leaves refund_pending for review.
 *
 * Every paid primary receipt, code hash, encrypted bearer key, ledger and outbox
 * insert commits in ONE SQLite transaction. Outbox may duplicate delivery after
 * a lost ACK; it always repeats the same key, never generates a replacement.
 * Redeemed subscriptions stack calendar months; latest unrefunded redemption
 * supplies the displayed plan immediately. Features remain ungated. Refunding a
 * primary payment excludes its grant and recomputes from the append-only history.
 *
 * Stable encryptionKey/keyPepper required across restarts. This initial key-v1
 * store does not support dropping/rotating keys: retain secrets securely until a
 * separately tested migration is available. PII, codes and support text use GCM;
 * lookup IDs are HMAC, not usernames/phone numbers. No plaintext bot token exists.
 */
export function createLiveStore(storage, { encryptionKey, keyPepper, termsVersion = '2026-10-12-v1',
    ownerUserId = null, clock = Date.now } = {}) {
    requireBilling(Buffer.isBuffer(encryptionKey) && encryptionKey.length === 32
        && Buffer.isBuffer(keyPepper) && keyPepper.length >= 32 && typeof clock === 'function', 'LIVE_STORE_NOT_CONFIGURED');
    text(termsVersion, 64);
    if (ownerUserId !== null) telegramUserId(ownerUserId);
    const aesKey = Buffer.from(encryptionKey);
    const pepper = Buffer.from(keyPepper);
    const mac = (domain, value) => createHmac('sha256', pepper).update(`${domain}\0${value}`).digest('hex');
    const ownerHash = uid => mac('user', telegramUserId(uid));
    const now = () => timestamp(clock());
    const rows = (sql, ...args) => Array.from(storage.sql.exec(sql, ...args));
    const outbox = createBotOutboxRepository(storage);
    storage.transactionSync(() => { for (const sql of SCHEMA) rows(sql); });
    const atomic = callback => outbox.transactionSync(callback);
    const aad = (id, kind) => Buffer.from(JSON.stringify([1, id, kind, 'live-v1']));
    function encrypt(value, id, kind) {
        const iv = randomBytes(12);
        const cipher = createCipheriv('aes-256-gcm', aesKey, iv, { authTagLength: 16 });
        cipher.setAAD(aad(id, kind));
        const bytes = Buffer.concat([cipher.update(Buffer.from(JSON.stringify(value), 'utf8')), cipher.final()]);
        return { version: 1, algorithm: 'AES-256-GCM', keyId: 'live-v1', iv: iv.toString('base64url'),
            ciphertext: bytes.toString('base64url'), tag: cipher.getAuthTag().toString('base64url') };
    }
    function decrypt(value, id, kind) {
        const envelope = typeof value === 'string' ? JSON.parse(value) : value;
        requireBilling(object(envelope) && envelope.version === 1 && envelope.algorithm === 'AES-256-GCM'
            && envelope.keyId === 'live-v1', 'INVALID_ENCRYPTED_RECORD');
        const decipher = createDecipheriv('aes-256-gcm', aesKey, Buffer.from(envelope.iv, 'base64url'), { authTagLength: 16 });
        decipher.setAAD(aad(id, kind));
        decipher.setAuthTag(Buffer.from(envelope.tag, 'base64url'));
        return JSON.parse(Buffer.concat([decipher.update(Buffer.from(envelope.ciphertext, 'base64url')), decipher.final()]).toString('utf8'));
    }
    const privateJson = (value, id, kind) => JSON.stringify(encrypt(value, id, kind));
    const getOrderRow = id => rows('SELECT * FROM live_orders WHERE id=?', text(id, 128))[0];
    const orderView = row => row ? ({ id: row.id, planId: row.plan_id, starsAmount: row.stars,
        status: row.status, termsVersion: row.terms_version, createdAtMs: row.created_ms, expiresAtMs: row.expires_ms,
        outboxId: hash(`invoice:${row.id}`) }) : null;
    function ledger(id, kind, orderId, userHash, value, at) {
        rows('INSERT INTO live_ledger(id,kind,order_id,owner_hash,created_ms,private_json) VALUES (?,?,?,?,?,?)',
            id, kind, orderId, userHash, at, privateJson(value, id, 'ledger'));
    }
    function queue(tx, deliveryId, kind, payload, at) {
        requireBilling(Buffer.byteLength(JSON.stringify(payload)) <= 8192, 'DELIVERY_TOO_LARGE');
        // A deterministic logical delivery ID must not be re-encrypted on retry.
        const existing = rows('SELECT kind FROM billing_bot_outbox WHERE id=?', deliveryId)[0];
        if (existing) { requireBilling(existing.kind === kind, 'OUTBOX_IDEMPOTENCY_CONFLICT'); return deliveryId; }
        tx.enqueueTx(deliveryId, encrypt(payload, deliveryId, kind), kind, at);
        return deliveryId;
    }
    const sendText = (uid, value) => ({ method: 'sendMessage', chat_id: telegramUserId(uid), text: value,
        link_preview_options: { is_disabled: true } });
    function notifyOwner(tx, logicalId, value, at) {
        return ownerUserId ? queue(tx, hash(`owner:${logicalId}`), KIND_INFO, sendText(ownerUserId, value), at) : null;
    }
    function limit(uid, action, max = 5) {
        const at = now();
        const bucket = mac('limit', `${ownerHash(uid)}:${action}:${Math.floor(at / 60_000)}`);
        // Separate committed limiter: invalid code guesses cannot roll it back.
        const allowed = storage.transactionSync(() => {
            rows(`DELETE FROM live_limits WHERE bucket IN
                (SELECT bucket FROM live_limits WHERE expires_ms <= ? ORDER BY expires_ms LIMIT 100)`, at);
            const row = rows(`INSERT INTO live_limits(bucket,attempts,expires_ms) VALUES (?,1,?)
                ON CONFLICT(bucket) DO UPDATE SET attempts=MIN(live_limits.attempts+1,?) RETURNING attempts`, bucket, at + 120_000, max + 1)[0];
            return row.attempts <= max;
        });
        requireBilling(allowed, 'RATE_LIMITED');
    }
    function subscription(uid, at = now()) {
        timestamp(at);
        const userHash = ownerHash(uid);
        const grants = rows(`SELECT g.* FROM live_grants g JOIN live_orders o ON o.id=g.order_id
            WHERE g.owner_hash=? AND o.status!='refunded' ORDER BY g.created_ms,g.rowid`, userHash);
        let expiresAtMs = 0;
        let planId = null;
        for (const grant of grants) {
            expiresAtMs = addCalendarMonth(Math.max(expiresAtMs, grant.created_ms));
            planId = grant.plan_id;
        }
        return { active: expiresAtMs > at, planId: expiresAtMs > at ? planId : null, expiresAtMs, featuresGated: false };
    }
    function keyRows(uid, max = 50) {
        return rows(`SELECT c.*,o.plan_id,o.status AS order_status,o.created_ms FROM live_codes c
            JOIN live_orders o ON o.id=c.order_id WHERE o.owner_hash=? ORDER BY o.created_ms DESC,o.id LIMIT ?`, ownerHash(uid), max);
    }
    const keyView = row => ({ orderId: row.order_id, planId: row.plan_id, createdAtMs: row.created_ms,
        status: row.revoked || ['refunded', 'refund_pending'].includes(row.order_status) ? row.order_status
            : row.redeemed_hash ? 'redeemed' : 'available' });
    function refundInside(tx, payment, at) {
        const details = decrypt(payment.private_json, payment.charge_hash, 'payment');
        const order = getOrderRow(payment.order_id);
        rows("UPDATE live_payments SET status='refunded' WHERE charge_hash=?", payment.charge_hash);
        if (order?.primary_charge === payment.charge_hash) {
            rows("UPDATE live_orders SET status='refunded' WHERE id=?", order.id);
            rows('UPDATE live_codes SET revoked=1 WHERE order_id=?', order.id);
        }
        ledger(`refund:${payment.charge_hash}`, 'refund', payment.order_id, ownerHash(details.userId), {}, at);
        return { status: 'refunded', orderId: payment.order_id };
    }

    return Object.freeze({
        durable: true,
        plans: LIVE_PLANS,
        termsVersion,
        acceptTerms(userId, version) {
            const userHash = ownerHash(userId); requireBilling(version === termsVersion, 'TERMS_VERSION_MISMATCH');
            return atomic(() => {
                const existing = rows('SELECT * FROM live_consents WHERE owner_hash=?', userHash)[0];
                if (existing?.version === version) return { accepted: true, termsVersion: version, acceptedAtMs: existing.accepted_ms };
                const at = now();
                rows(`INSERT INTO live_consents(owner_hash,version,accepted_ms) VALUES (?,?,?)
                    ON CONFLICT(owner_hash) DO UPDATE SET version=excluded.version,accepted_ms=excluded.accepted_ms`, userHash, version, at);
                const eventId = `consent:${userHash}:${version}`;
                if (!rows('SELECT id FROM live_ledger WHERE id=?', eventId).length) ledger(eventId, 'consent', null, userHash, {}, at);
                return { accepted: true, termsVersion: version, acceptedAtMs: at };
            });
        },
        getConsent(userId) {
            const row = rows('SELECT * FROM live_consents WHERE owner_hash=?', ownerHash(userId))[0];
            return { accepted: row?.version === termsVersion, termsVersion: row?.version ?? null, acceptedAtMs: row?.accepted_ms ?? null };
        },
        createOrder(userId, planId, requestKey) {
            const userHash = ownerHash(userId); const plan = LIVE_PLANS.find(p => p.id === planId);
            requireBilling(plan, 'INVALID_PLAN'); text(requestKey, 128); limit(userId, 'purchase', 10);
            const requestHash = mac('purchase', `${userHash}:${requestKey}`);
            return atomic(tx => {
                requireBilling(rows('SELECT version FROM live_consents WHERE owner_hash=?', userHash)[0]?.version === termsVersion, 'TERMS_REQUIRED');
                const existing = rows('SELECT * FROM live_orders WHERE request_hash=?', requestHash)[0];
                if (existing) { requireBilling(existing.plan_id === planId, 'IDEMPOTENCY_CONFLICT'); return orderView(existing); }
                const id = opaque(); const at = now();
                rows(`INSERT INTO live_orders(id,request_hash,owner_hash,plan_id,stars,terms_version,status,
                    created_ms,expires_ms,private_json) VALUES (?,?,?,?,?,?,'pending',?,?,?)`, id, requestHash, userHash,
                plan.id, plan.starsAmount, termsVersion, at, at + INVOICE_MS, privateJson({ userId }, id, 'order'));
                queue(tx, hash(`invoice:${id}`), 'telegram_invoice', { method: 'sendInvoice', chat_id: userId,
                    title: `Lunagram ${plan.title}`, description: `Один месяц ${plan.title}. Ручное продление. Все функции пока доступны без подписки.`,
                    payload: id, provider_token: '', currency: 'XTR', start_parameter: `buy_${plan.id}`,
                    prices: [{ label: plan.title, amount: plan.starsAmount }] }, at);
                ledger(`order:${id}`, 'order_created', id, userHash, { termsVersion }, at);
                return orderView(getOrderRow(id));
            });
        },
        getOrder(orderId) { return orderView(getOrderRow(orderId)); },
        reservePreCheckout({ orderId, userId, currency, totalAmount, queryId }) {
            const userHash = ownerHash(userId); text(queryId, 256);
            return atomic(() => {
                const order = getOrderRow(orderId); const at = now();
                const deny = error => ({ ok: false, error });
                if (!order || order.owner_hash !== userHash || currency !== 'XTR' || totalAmount !== order.stars) return deny('PAYMENT_MISMATCH');
                if (order.status !== 'pending') return deny('ORDER_NOT_PENDING');
                if (at < order.created_ms || at >= order.expires_ms || order.terms_version !== termsVersion) return deny('ORDER_EXPIRED');
                const queryHash = mac('precheckout', queryId);
                if (order.reservation_hash === queryHash) return at >= order.reserved_ms && at - order.reserved_ms <= RESERVATION_REPLY_MS
                    ? { ok: true, orderId } : deny('PRECHECKOUT_EXPIRED');
                // Approval can lead to a delayed successful_payment at any time.
                // Never infer a failed payment from a timeout: create a NEW order
                // instead of approving a second query against this invoice.
                if (order.reservation_hash) return deny('PAYMENT_IN_PROGRESS');
                rows('UPDATE live_orders SET reservation_hash=?,reserved_ms=? WHERE id=?', queryHash, at, orderId);
                return { ok: true, orderId };
            });
        },
        recordPayment({ orderId, userId, currency, totalAmount, chargeId, recurring = false }) {
            const userHash = ownerHash(userId); text(orderId, 128); text(chargeId, 512);
            requireBilling(typeof currency === 'string' && currency.length <= 16 && Number.isSafeInteger(totalAmount) && totalAmount > 0,
                'INVALID_PAYMENT');
            const chargeHash = mac('charge', chargeId);
            return atomic(tx => {
                const previous = rows('SELECT * FROM live_payments WHERE charge_hash=?', chargeHash)[0];
                if (previous) {
                    const prior = decrypt(previous.private_json, chargeHash, 'payment');
                    requireBilling(previous.order_id === orderId && prior.userId === userId && prior.currency === currency
                        && prior.totalAmount === totalAmount, 'PAYMENT_REUSED');
                    return { status: previous.status, orderId, outboxId: hash(`key:${orderId}`) };
                }
                const order = getOrderRow(orderId); const at = now();
                const refundMarker = rows('SELECT private_json FROM live_refund_markers WHERE charge_hash=?', chargeHash)[0];
                const priorRefund = refundMarker ? decrypt(refundMarker.private_json, chargeHash, 'refund_marker') : null;
                const markerMismatch = priorRefund && (priorRefund.orderId !== orderId || priorRefund.userId !== userId
                    || priorRefund.currency !== currency || priorRefund.totalAmount !== totalAmount);
                const reason = markerMismatch ? 'refund_mismatch' : !order ? 'unknown_order' : order.owner_hash !== userHash || currency !== 'XTR'
                    || totalAmount !== order.stars || recurring !== false ? 'payment_mismatch'
                    : order.primary_charge ? 'duplicate_charge' : !order.reservation_hash ? 'missing_precheckout' : null;
                rows('INSERT INTO live_payments(charge_hash,order_id,status,reason,created_ms,private_json) VALUES (?,?,?,?,?,?)',
                    chargeHash, orderId, reason ? 'review_required' : 'paid', reason, at,
                    privateJson({ chargeId, userId, currency, totalAmount }, chargeHash, 'payment'));
                ledger(`payment:${chargeHash}`, reason ? 'payment_review' : 'payment', orderId, userHash, { reason }, at);
                if (reason) {
                    notifyOwner(tx, `payment:${chargeHash}`, `Платёж требует проверки. Заказ ${orderId}. Причина: ${reason}. Автовозврат не выполнялся.`, at);
                    return { status: 'review_required', reason, orderId };
                }
                rows("UPDATE live_orders SET status='paid',primary_charge=? WHERE id=?", chargeHash, orderId);
                if (refundMarker) {
                    return refundInside(tx, rows('SELECT * FROM live_payments WHERE charge_hash=?', chargeHash)[0], at);
                }
                let code, codeHash;
                for (let i = 0; i < 5; i++) {
                    code = generateActivationCode(); codeHash = mac('code', code);
                    if (!rows('SELECT code_hash FROM live_codes WHERE code_hash=?', codeHash).length) break;
                    code = null;
                }
                requireBilling(code, 'CODE_GENERATION_FAILED');
                rows('INSERT INTO live_codes(code_hash,order_id,private_json) VALUES (?,?,?)', codeHash, orderId,
                    privateJson({ code }, codeHash, 'code'));
                const outboxId = queue(tx, hash(`key:${orderId}`), KIND_KEY,
                    sendText(userId, `Lunagram ${order.plan_id}\nЗаказ: ${orderId}\nКлюч на один месяц:\n${code}\n\nАктивируйте его в Lunagram. Ключ одноразовый: не публикуйте его. Повторная выдача: /keys. Для поддержки укажите номер заказа, не ключ.`), at);
                return { status: 'paid', orderId, outboxId };
            });
        },
        listKeys(userId) { return keyRows(userId).map(keyView); },
        enqueueKeys(userId, requestKey) {
            const userHash = ownerHash(userId); text(requestKey, 128); limit(userId, 'keys', 5);
            return atomic(tx => {
                const at = now(); const keys = keyRows(userId, 20);
                const lines = keys.map(row => `Заказ: ${row.order_id}\n` + (keyView(row).status === 'available'
                    ? `${row.plan_id}: ${decrypt(row.private_json, row.code_hash, 'code').code}`
                    : `${row.plan_id}: ${keyView(row).status}`));
                const outboxId = queue(tx, mac('keys-delivery', `${userHash}:${requestKey}`), KIND_KEY,
                    sendText(userId, lines.length ? `Ваши ключи Lunagram (до 20 последних):\n${lines.join('\n')}\n\nНе передавайте неиспользованные ключи посторонним.` : 'Покупок с ключами пока нет.'), at);
                return { outboxId, count: keys.length };
            });
        },
        redeem(userId, value) {
            const userHash = ownerHash(userId); limit(userId, 'redeem', 5);
            const codeHash = mac('code', normalizeActivationCode(value));
            return atomic(() => {
                const row = rows('SELECT * FROM live_codes WHERE code_hash=?', codeHash)[0];
                requireBilling(row, 'INVALID_CODE');
                const order = getOrderRow(row.order_id);
                requireBilling(!row.revoked && order?.status === 'paid', 'CODE_UNAVAILABLE');
                if (row.redeemed_hash) {
                    requireBilling(row.redeemed_hash === userHash, 'CODE_ALREADY_USED');
                    return subscription(userId);
                }
                const at = now();
                rows('UPDATE live_codes SET redeemed_hash=?,redeemed_ms=? WHERE code_hash=?', userHash, at, codeHash);
                rows('INSERT INTO live_grants(code_hash,owner_hash,order_id,plan_id,created_ms) VALUES (?,?,?,?,?)', codeHash, userHash, order.id, order.plan_id, at);
                ledger(`redeem:${codeHash}`, 'redeem', order.id, userHash, {}, at);
                return subscription(userId, at);
            });
        },
        getSubscription: subscription,
        hasReply(userId, requestKey) {
            const userHash = ownerHash(userId); text(requestKey, 128);
            const key = `${userHash}:${requestKey}`;
            const order = rows('SELECT id FROM live_orders WHERE request_hash=?', mac('purchase', key))[0];
            // This is only a conversational retry guard. Financial receipts MUST
            // be authenticated and reconciled independently before this check.
            return rows('SELECT id FROM billing_bot_outbox WHERE id IN (?,?,?) LIMIT 1',
                mac('message', key), mac('keys-delivery', key), order ? hash(`invoice:${order.id}`) : '').length > 0;
        },
        enqueueMessage(userId, value, requestKey, replyMarkup) {
            const userHash = ownerHash(userId); messageText(value); text(requestKey, 128);
            const markup = buttons(replyMarkup);
            const payload = { ...sendText(userId, value), ...(markup ? { reply_markup: markup } : {}) };
            return atomic(tx => {
                const deliveryId = mac('message', `${userHash}:${requestKey}`);
                const previous = rows('SELECT * FROM billing_bot_outbox WHERE id=?', deliveryId)[0];
                if (previous) requireBilling(JSON.stringify(decrypt(previous.encrypted_payload, deliveryId, KIND_INFO)) === JSON.stringify(payload),
                    'IDEMPOTENCY_CONFLICT');
                return { outboxId: queue(tx, deliveryId, KIND_INFO, payload, now()) };
            });
        },
        nextDueAt(at = now()) {
            timestamp(at);
            const row = rows(`SELECT MIN(CASE WHEN state='leased' THEN lease_until_ms ELSE next_attempt_at_ms END) AS due
                FROM billing_bot_outbox WHERE state IN ('pending','leased')`)[0];
            return row?.due == null ? null : Math.max(at, row.due);
        },
        prepareRefund(orderId, chargeId = null) {
            return atomic(() => {
                const order = getOrderRow(orderId); requireBilling(order, 'ORDER_NOT_FOUND');
                const chargeHash = chargeId ? mac('charge', text(chargeId, 512)) : order.primary_charge;
                const payment = rows('SELECT * FROM live_payments WHERE charge_hash=? AND order_id=?', chargeHash, orderId)[0];
                requireBilling(payment, 'PAYMENT_NOT_FOUND');
                const details = decrypt(payment.private_json, chargeHash, 'payment');
                if (payment.status !== 'refunded') {
                    rows("UPDATE live_payments SET status='refund_pending' WHERE charge_hash=?", chargeHash);
                    if (order.primary_charge === chargeHash) {
                        rows("UPDATE live_orders SET status='refund_pending' WHERE id=?", orderId);
                        rows('UPDATE live_codes SET revoked=1 WHERE order_id=?', orderId);
                    }
                }
                return { orderId, userId: details.userId, chargeId: details.chargeId, status: payment.status === 'refunded' ? 'refunded' : 'refund_pending' };
            });
        },
        listPaymentReviews(limitCount = 20) {
            requireBilling(Number.isSafeInteger(limitCount) && limitCount > 0 && limitCount <= 50, 'INVALID_LIMIT');
            return rows(`SELECT charge_hash,order_id,status,reason,created_ms FROM live_payments
                WHERE reason IS NOT NULL AND status IN ('review_required','refund_pending')
                ORDER BY created_ms,charge_hash LIMIT ?`, limitCount)
                .map(row => ({ reviewId: row.charge_hash.slice(0, 48), orderId: row.order_id,
                    status: row.status, reason: row.reason, createdAtMs: row.created_ms }));
        },
        prepareReviewRefund(reviewId) {
            requireBilling(typeof reviewId === 'string' && reviewId.length === 48
                && !/[^0-9a-f]/.test(reviewId), 'INVALID_REVIEW_ID');
            return atomic(() => {
                // 192-bit opaque reference fits Telegram callback_data. In the
                // improbable prefix collision, fail closed rather than select
                // a different customer's charge. No client may call this API.
                const candidates = rows('SELECT * FROM live_payments WHERE substr(charge_hash,1,48)=? LIMIT 2', reviewId);
                requireBilling(candidates.length === 1 && candidates[0].reason !== null, 'PAYMENT_NOT_FOUND');
                const payment = candidates[0];
                const details = decrypt(payment.private_json, payment.charge_hash, 'payment');
                requireBilling(details.currency === 'XTR', 'REFUND_MISMATCH');
                if (payment.status !== 'refunded') rows("UPDATE live_payments SET status='refund_pending' WHERE charge_hash=?", payment.charge_hash);
                return { orderId: payment.order_id, userId: details.userId, chargeId: details.chargeId,
                    status: payment.status === 'refunded' ? 'refunded' : 'refund_pending' };
            });
        },
        recordRefund({ chargeId, orderId = null, userId = null, currency = null, totalAmount = null }) {
            const chargeHash = mac('charge', text(chargeId, 512));
            return atomic(tx => {
                const payment = rows('SELECT * FROM live_payments WHERE charge_hash=?', chargeHash)[0];
                if (!payment) {
                    const order = getOrderRow(orderId); requireBilling(order && ownerHash(userId) === order.owner_hash
                        && currency === 'XTR' && totalAmount === order.stars, 'REFUND_MISMATCH');
                    const previous = rows('SELECT private_json FROM live_refund_markers WHERE charge_hash=?', chargeHash)[0];
                    if (previous) {
                        const marker = decrypt(previous.private_json, chargeHash, 'refund_marker');
                        requireBilling(marker.orderId === orderId && marker.userId === userId && marker.currency === currency
                            && marker.totalAmount === totalAmount, 'REFUND_MISMATCH');
                    } else {
                        rows('INSERT INTO live_refund_markers(charge_hash,private_json,created_ms) VALUES (?,?,?)', chargeHash,
                            privateJson({ orderId, userId, currency, totalAmount }, chargeHash, 'refund_marker'), now());
                    }
                    return { status: 'awaiting_payment', orderId };
                }
                const details = decrypt(payment.private_json, chargeHash, 'payment');
                requireBilling((orderId === null || orderId === payment.order_id) && (userId === null || userId === details.userId)
                    && (currency === null || currency === details.currency) && (totalAmount === null || totalAmount === details.totalAmount), 'REFUND_MISMATCH');
                if (payment.status === 'refunded') return { status: 'refunded', orderId: payment.order_id };
                return refundInside(tx, payment, now());
            });
        },
        createSupportTicket(userId, value, requestKey) {
            const userHash = ownerHash(userId); messageText(value); text(requestKey, 128); limit(userId, 'support', 3);
            const requestHash = mac('support', `${userHash}:${requestKey}`);
            return atomic(tx => {
                const existing = rows('SELECT * FROM live_tickets WHERE request_hash=?', requestHash)[0];
                if (existing) {
                    const first = rows("SELECT * FROM live_ticket_messages WHERE ticket_id=? AND direction='user' ORDER BY created_ms,id LIMIT 1", existing.id)[0];
                    requireBilling(first && decrypt(first.private_json, first.id, 'ticket_message').text === value, 'IDEMPOTENCY_CONFLICT');
                    return { id: existing.id, status: existing.status };
                }
                const id = opaque(); const at = now(); const messageId = hash(`ticket:${id}`);
                rows(`INSERT INTO live_tickets(id,request_hash,owner_hash,status,created_ms,updated_ms,private_json)
                    VALUES (?,?,?,'open',?,?,?)`, id, requestHash, userHash, at, at, privateJson({ userId }, id, 'ticket'));
                rows('INSERT INTO live_ticket_messages(id,ticket_id,direction,created_ms,private_json) VALUES (?,?,?,?,?)',
                    messageId, id, 'user', at, privateJson({ text: value }, messageId, 'ticket_message'));
                notifyOwner(tx, `ticket:${id}`, `Новое обращение Lunagram #${id}\n${value}\n\nИмя пользователя не раскрывается.`, at);
                return { id, status: 'open' };
            });
        },
        listTickets(limitCount = 20) {
            requireBilling(Number.isSafeInteger(limitCount) && limitCount > 0 && limitCount <= 50, 'INVALID_LIMIT');
            return rows('SELECT id,status,created_ms,updated_ms FROM live_tickets ORDER BY updated_ms DESC,id LIMIT ?', limitCount)
                .map(r => ({ id: r.id, status: r.status, createdAtMs: r.created_ms, updatedAtMs: r.updated_ms }));
        },
        readTicket(ticketId) {
            const ticket = rows('SELECT * FROM live_tickets WHERE id=?', text(ticketId, 128))[0];
            requireBilling(ticket, 'TICKET_NOT_FOUND');
            return { id: ticket.id, status: ticket.status,
                messages: rows('SELECT * FROM live_ticket_messages WHERE ticket_id=? ORDER BY created_ms,id LIMIT 100', ticketId)
                    .map(row => ({ direction: row.direction, createdAtMs: row.created_ms, text: decrypt(row.private_json, row.id, 'ticket_message').text })) };
        },
        // SERVER-ONLY support-owner operation. Scope comes from the stored
        // ticket's identity, never a claimed user ID or order mentioned in text.
        // Views contain no bearer key, charge, Telegram identity or lookup HMAC.
        listTicketOrders(ticketId, page = 1) {
            requireBilling(Number.isSafeInteger(page) && page >= 1 && page <= 1000, 'INVALID_PAGE');
            const ticket = rows('SELECT owner_hash FROM live_tickets WHERE id=?', text(ticketId, 128))[0];
            requireBilling(ticket, 'TICKET_NOT_FOUND');
            const matches = rows(`SELECT id,plan_id,stars,status,created_ms FROM live_orders
                WHERE owner_hash=? AND primary_charge IS NOT NULL ORDER BY created_ms DESC,id DESC LIMIT 6 OFFSET ?`,
                ticket.owner_hash, (page - 1) * 5);
            return { ticketId, page, hasMore: matches.length > 5 && page < 1000,
                orders: matches.slice(0, 5).map(row => ({ id: row.id, planId: row.plan_id, starsAmount: row.stars,
                    status: row.status, createdAtMs: row.created_ms })) };
        },
        replyTicket(ticketId, value, requestKey) {
            text(ticketId, 128); messageText(value); text(requestKey, 128);
            return atomic(tx => {
                const ticket = rows('SELECT * FROM live_tickets WHERE id=?', ticketId)[0]; requireBilling(ticket, 'TICKET_NOT_FOUND');
                const messageId = mac('support-reply', `${ticketId}:${requestKey}`);
                const previous = rows('SELECT * FROM live_ticket_messages WHERE id=?', messageId)[0];
                if (previous) {
                    requireBilling(decrypt(previous.private_json, messageId, 'ticket_message').text === value, 'IDEMPOTENCY_CONFLICT');
                    return { outboxId: hash(`support-reply:${messageId}`) };
                }
                const at = now(); const { userId } = decrypt(ticket.private_json, ticketId, 'ticket');
                rows('INSERT INTO live_ticket_messages(id,ticket_id,direction,created_ms,private_json) VALUES (?,?,?,?,?)',
                    messageId, ticketId, 'author', at, privateJson({ text: value }, messageId, 'ticket_message'));
                rows("UPDATE live_tickets SET status='answered',updated_ms=? WHERE id=?", at, ticketId);
                return { outboxId: queue(tx, hash(`support-reply:${messageId}`), KIND_INFO,
                    sendText(userId, `Ответ поддержки Lunagram #${ticketId}\n\n${value}`), at) };
            });
        },
        decryptOutbox(item) {
            requireBilling(object(item) && ['telegram_invoice', KIND_KEY, KIND_INFO].includes(item.kind), 'INVALID_OUTBOX_ITEM');
            return decrypt(item.encryptedPayload, item.id, item.kind);
        },
        claimDue: outbox.claimDue,
        finishAck: outbox.finishAck,
        defer: outbox.defer,
        recoverExpired: outbox.recoverExpired,
    });
}
