import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import { CATALOG, addCalendarMonth, planById, telegramUserId, usdAmount } from '../catalog.mjs';
import { activationCodeHash, generateActivationCode, normalizeActivationCode, verifyCryptoPayWebhook, verifyPrivateBotMessage } from '../security.mjs';
import { TON_WALLET, canonicalTonAddress, checkCreatedCryptoInvoice, checkPaidCryptoInvoice, checkPaidTonTransfer, makeTonQuote } from '../providers.mjs';
import { createBillingService } from '../service.mjs';
import { starsPrice } from '../stars.mjs';
import { memoryRepositoryForTests } from './test-repository.mjs';

const NOW = Date.UTC(2026, 9, 11, 12);
const PEPPER = Buffer.alloc(32, 1); // Public test fixture, not a deployable credential.
const API_TOKEN = 'test-only-api-token-never-use';
const SECRET = 'test_only_bot_webhook_secret_123456';
const err = code => error => error.code === code;
const sign = body => createHmac('sha256', createHash('sha256').update(API_TOKEN).digest()).update(body).digest('hex');

function fixture(overrides = {}) {
    const repository = memoryRepositoryForTests();
    const invoices = new Map();
    let nextId = 1, createCalls = 0;
    let now = NOW;
    const cryptoPay = {
        apiToken: API_TOKEN,
        checkoutPolicyApproved: true, // Fixture only: no claim of live policy approval.
        async createInvoice(request) {
            createCalls++;
            const invoice = { ...request, invoice_id: nextId++, status: 'active',
                created_at: new Date(now).toISOString(), expiration_date: new Date(now + 900_000).toISOString(),
                bot_invoice_url: `https://t.me/CryptoBot?start=invoice-test_${nextId}` };
            invoices.set(String(invoice.invoice_id), invoice);
            return structuredClone(invoice);
        },
        async getInvoice(id) { return structuredClone(invoices.get(id)); },
    };
    const service = createBillingService({ enabled: true, repository, testing: true, keyPepper: PEPPER,
        authenticate: async session => {
            if (session === 'alice-verified-server-session') return '101';
            if (session === 'bob-verified-server-session') return '202';
            throw Object.assign(new Error('UNAUTHENTICATED'), { code: 'UNAUTHENTICATED' });
        }, cryptoPay, clock: () => now, ...overrides });
    return { repository, service, invoices, cryptoPay, createCalls: () => createCalls,
        setNow: value => { now = value; },
        create: (extra = {}) => service.createAppPurchase({ session: 'alice-verified-server-session',
            provider: 'cryptopay', planId: 'based', idempotencyKey: 'client-retry-token-1234', ...extra }),
        paid(order) {
            const stored = repository.snapshot().orders[order.id];
            Object.assign(invoices.get(stored.invoiceId), { status: 'paid', paid_asset: 'USDT', paid_at: new Date(now).toISOString() });
        },
        reconcile: order => service.reconcilePurchase({ session: 'alice-verified-server-session', orderId: order.id }),
    };
}

test('prices are exactly requested integer USD cents; all plans are immutable', () => {
    assert.deepEqual(CATALOG.map(p => [p.id, p.usdCents]), [['based',50],['standard',100],['plus',140],['pro',200],['ultra',500]]);
    assert.deepEqual(CATALOG.map(p => usdAmount(p.usdCents)), ['0.50','1.00','1.40','2.00','5.00']);
    assert.throws(() => { CATALOG[0].usdCents = 1; });
    assert.throws(() => planById('__proto__'), err('INVALID_PLAN'));
    assert.throws(() => usdAmount(0.5), err('INVALID_AMOUNT'));
});
test('calendar monthly expiry clamps month-end and preserves UTC time', () => {
    assert.equal(new Date(addCalendarMonth(Date.UTC(2024, 0, 31, 23, 4))).toISOString(), '2024-02-29T23:04:00.000Z');
    assert.equal(new Date(addCalendarMonth(Date.UTC(2026, 0, 31))).toISOString(), '2026-02-28T00:00:00.000Z');
});
test('Telegram numeric identity rejects floating point and client-like objects', () => {
    for (const id of [101, '0', '-1', '1.1', '9007199254740992', { id: '101' }]) assert.throws(() => telegramUserId(id));
});
test('unconfigured service is fail closed and memory repository is forbidden outside explicit tests', async () => {
    await assert.rejects(createBillingService().getSubscription({ session: '101' }), err('NOT_CONFIGURED'));
    const f = fixture({ testing: false });
    await assert.rejects(f.create(), err('NOT_CONFIGURED'));
    assert.equal(f.createCalls(), 0);
});
test('arbitrary client user_id cannot replace authenticated session', async () => {
    const f = fixture();
    await assert.rejects(f.create({ session: '101', user_id: '101' }), err('UNAUTHENTICATED'));
    assert.equal(f.createCalls(), 0);
});
test('request price override is ignored; provider and order payload server selected', async () => {
    const f = fixture();
    const order = await f.create({ usdCents: 1, amount: '0.01', planId: 'ultra' });
    assert.equal(order.usdCents, 500);
    const invoice = [...f.invoices.values()][0];
    assert.equal(invoice.amount, '5.00');
    assert.equal(invoice.payload, order.id);
    assert.equal(invoice.accepted_assets, 'USDT');
    assert.equal(invoice.allow_anonymous, false);
    await assert.rejects(f.create({ provider: 'https://evil.test' }), err('INVALID_REQUEST'));
});
test('concurrent identical checkout requests create exactly one invoice', async () => {
    const f = fixture();
    const results = await Promise.all([f.create(), f.create(), f.create()]);
    assert.equal(new Set(results.map(r => r.id)).size, 1);
    assert.equal(f.createCalls(), 1);
    await assert.rejects(f.create({ planId: 'pro' }), err('IDEMPOTENCY_CONFLICT'));
});
test('ambiguous invoice creation failure is not retried into a duplicate charge', async () => {
    let calls = 0;
    const f = fixture({ cryptoPay: { apiToken: API_TOKEN, checkoutPolicyApproved: true, getInvoice: async () => {}, createInvoice: async () => { calls++; throw new Error('timeout'); } } });
    await assert.rejects(f.create(), err('CHECKOUT_UNAVAILABLE'));
    assert.equal((await f.create()).status, 'review_required');
    assert.equal(calls, 1);
});
test('invoice redirect URL allowlist rejects host, credentials, path, scheme attacks', () => {
    const order = { id: 'order', usdCents: 50 };
    for (const url of ['http://t.me/CryptoBot?start=invoice-x', 'https://t.me.evil.test/CryptoBot?start=invoice-x',
        'https://user@t.me/CryptoBot?start=invoice-x', 'https://t.me/otherbot?start=invoice-x', 'javascript:alert(1)']) {
        assert.throws(() => checkCreatedCryptoInvoice({ ...order, createdAtMs: NOW }, { invoice_id: 1, status: 'active', currency_type: 'fiat',
            created_at: new Date(NOW).toISOString(), expiration_date: new Date(NOW + 900000).toISOString(),
            fiat: 'USD', amount: '0.50', payload: 'order', bot_invoice_url: url }, NOW), err('PAYMENT_MISMATCH'));
    }
});
test('provider invoice creation/expiry, opaque start token and clock skew are authoritative', () => {
    const local = { id: 'order', provider: 'cryptopay', usdCents: 50, createdAtMs: NOW, expiresAtMs: NOW + 900000 };
    const invoice = { invoice_id: 1, status: 'active', currency_type: 'fiat', fiat: 'USD', amount: '0.50',
        payload: 'order', bot_invoice_url: 'https://t.me/CryptoBot?start=IVopaque_provider_value',
        created_at: new Date(NOW + 20_000).toISOString(), expiration_date: new Date(NOW + 920_000).toISOString() };
    const attached = { ...local, ...checkCreatedCryptoInvoice(local, invoice, NOW + 21000) };
    assert.equal(attached.expiresAtMs, NOW + 920000);
    assert.equal(checkPaidCryptoInvoice(attached, { ...invoice, status: 'paid', paid_asset: 'USDT',
        paid_at: new Date(NOW + 910000).toISOString() }, NOW + 920000).eventId, 'cryptopay:1');
    const skewed = { ...invoice, created_at: new Date(NOW - 10000).toISOString(), expiration_date: new Date(NOW + 890000).toISOString() };
    const skewedOrder = { ...local, ...checkCreatedCryptoInvoice(local, skewed, NOW) };
    assert.equal(checkPaidCryptoInvoice(skewedOrder, { ...skewed, status: 'paid', paid_asset: 'USDT',
        paid_at: new Date(NOW - 9000).toISOString() }, NOW).eventId, 'cryptopay:1');
    for (const patch of [{ expiration_date: new Date(NOW).toISOString() }, { created_at: 'invalid' },
        { expiration_date: new Date(NOW + 7 * 86400000).toISOString() }]) {
        assert.throws(() => checkCreatedCryptoInvoice(local, { ...invoice, ...patch }, NOW + 21000), err('PAYMENT_MISMATCH'));
    }
});
test('direct payment grants one month exactly once across webhook and poll retries', async () => {
    const f = fixture();
    const order = await f.create();
    f.paid(order);
    const raw = Buffer.from(JSON.stringify({ update_type: 'invoice_paid', payload: { invoice_id: 1 } }));
    await Promise.all([f.reconcile(order), f.service.handleCryptoPayWebhook(raw, sign(raw)), f.reconcile(order)]);
    const state = f.repository.snapshot();
    assert.equal(Object.keys(state.payments).length, 1);
    assert.equal(state.entitlements['101'].expiresAtMs, addCalendarMonth(NOW));
    assert.equal(state.entitlements['101'].featuresGated, false);
});
test('authoritative getInvoice must be paid; signed webhook body cannot self-activate', async () => {
    const f = fixture();
    await f.create();
    const raw = Buffer.from(JSON.stringify({ update_type: 'invoice_paid', payload: { invoice_id: 1, status: 'paid' } }));
    await assert.rejects(f.service.handleCryptoPayWebhook(raw, sign(raw)), err('PAYMENT_MISMATCH'));
    assert.deepEqual(f.repository.snapshot().entitlements, {});
});
test('wrong fiat, amount, asset, invoice, payload, time and pending status never grant', () => {
    const order = { id: 'order', provider: 'cryptopay', usdCents: 50, invoiceId: '1', createdAtMs: NOW, expiresAtMs: NOW + 900_000 };
    const invoice = { invoice_id: 1, currency_type: 'fiat', fiat: 'USD', amount: '0.50', payload: 'order', status: 'paid', paid_asset: 'USDT', paid_at: new Date(NOW).toISOString() };
    for (const changes of [{ fiat: 'EUR' }, { amount: '0.49' }, { amount: '5e-1' }, { amount: 0.5 },
        { paid_asset: 'TON' }, { invoice_id: 2 }, { payload: 'other' }, { status: 'active' }, { currency_type: 'crypto' },
        { paid_at: new Date(NOW + 901_000).toISOString() }, { paid_at: 'invalid' }]) {
        assert.throws(() => checkPaidCryptoInvoice(order, { ...invoice, ...changes }, NOW), err('PAYMENT_MISMATCH'));
    }
    assert.equal(checkPaidCryptoInvoice(order, { ...invoice, amount: '0.5000' }, NOW).eventId, 'cryptopay:1');
});
test('another authenticated user cannot reconcile or issue the buyer order', async () => {
    const f = fixture();
    const order = await f.create({ delivery: 'key' });
    f.paid(order);
    await f.reconcile(order);
    await assert.rejects(f.service.reconcilePurchase({ session: 'bob-verified-server-session', orderId: order.id }), err('ORDER_NOT_FOUND'));
    await assert.rejects(f.service.issuePurchasedKey({ session: 'bob-verified-server-session', orderId: order.id }), err('KEY_NOT_AVAILABLE'));
});
test('16 alphanumeric random keys are normalized safely and stored only as HMAC', () => {
    const codes = new Set();
    for (let i = 0; i < 256; i++) { const code = generateActivationCode(); assert.match(code, /^[A-Z0-9]{16}$/); codes.add(code); }
    assert.equal(codes.size, 256);
    assert.equal(normalizeActivationCode(' abcd-1234-efgh-5678 '), 'ABCD1234EFGH5678');
    assert.notEqual(activationCodeHash('ABCD1234EFGH5678', PEPPER), activationCodeHash('ABCD1234EFGH5678', Buffer.alloc(32, 2)));
    assert.equal(normalizeActivationCode('abcd 1234 efgh 5678'), 'ABCD1234EFGH5678');
    for (const value of ['АBCD1234EFGH5678', 'ß'.repeat(8), 'ＡBCD1234EFGH5678', 'ABCD\t1234EFGH5678',
        'ABCD\u00a01234EFGH5678', 'ABC!', 'A'.repeat(1000), null]) assert.throws(() => normalizeActivationCode(value));
});
test('paid key does not grant until redeemed; racing redemptions have one winner', async () => {
    const f = fixture();
    const order = await f.create({ delivery: 'key' });
    f.paid(order); await f.reconcile(order);
    assert.deepEqual(f.repository.snapshot().entitlements, {});
    const { code } = await f.service.issuePurchasedKey({ session: 'alice-verified-server-session', orderId: order.id });
    assert.equal(JSON.stringify(f.repository.snapshot()).includes(code), false);
    const results = await Promise.allSettled(['alice-verified-server-session', 'bob-verified-server-session'].map(session => f.service.redeemCode({ session, code })));
    assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
    assert.equal(results.filter(r => r.status === 'rejected' && r.reason.code === 'INVALID_CODE').length, 1);
    assert.equal(Object.keys(f.repository.snapshot().entitlements).length, 1);
});
test('lost response recovery rotates only unredeemed code and revokes old key atomically', async () => {
    const f = fixture();
    const order = await f.create({ delivery: 'key' });
    f.paid(order); await f.reconcile(order);
    const args = { session: 'alice-verified-server-session', orderId: order.id };
    const first = await f.service.issuePurchasedKey(args);
    await assert.rejects(f.service.issuePurchasedKey(args), err('KEY_NOT_AVAILABLE'));
    const second = await f.service.issuePurchasedKey({ ...args, replaceUndelivered: true });
    await assert.rejects(f.service.redeemCode({ session: args.session, code: first.code }), err('INVALID_CODE'));
    await f.service.redeemCode({ session: args.session, code: second.code });
    await assert.rejects(f.service.issuePurchasedKey({ ...args, replaceUndelivered: true }), err('KEY_NOT_AVAILABLE'));
});
test('failed code guesses are limited including malformed inputs', async () => {
    const f = fixture();
    for (let i = 0; i < 5; i++) await assert.rejects(f.service.redeemCode({ session: 'alice-verified-server-session', code: '!' }), err('INVALID_CODE'));
    await assert.rejects(f.service.redeemCode({ session: 'alice-verified-server-session', code: '!' }), err('RATE_LIMITED'));
    f.setNow(NOW + 61_000);
    await assert.rejects(f.service.redeemCode({ session: 'alice-verified-server-session', code: '!' }), err('INVALID_CODE'));
});
test('renewal stacks calendar month; expired subscription does not gate any features', async () => {
    const f = fixture();
    for (const id of ['renewal-one-123456', 'renewal-two-123456']) {
        const order = await f.create({ idempotencyKey: id }); f.paid(order); await f.reconcile(order);
    }
    assert.equal((await f.service.getSubscription({ session: 'alice-verified-server-session' })).expiresAtMs, addCalendarMonth(addCalendarMonth(NOW)));
    f.setNow(Date.UTC(2027, 0, 1));
    const state = await f.service.getSubscription({ session: 'alice-verified-server-session' });
    assert.equal(state.active, false); assert.equal(state.featuresGated, false);
});
test('CryptoPay verifies exact raw body and rejects wrong/truncated/missing signatures', () => {
    const raw = Buffer.from('{"update_type":"invoice_paid"}');
    assert.equal(verifyCryptoPayWebhook(raw, sign(raw), API_TOKEN).update_type, 'invoice_paid');
    for (const signature of ['', null, '00', '0'.repeat(64), sign(raw) + 'x']) assert.throws(() => verifyCryptoPayWebhook(raw, signature, API_TOKEN));
    assert.throws(() => verifyCryptoPayWebhook(Buffer.concat([raw, Buffer.from(' ')]), sign(raw), API_TOKEN), err('BAD_SIGNATURE'));
    assert.throws(() => verifyCryptoPayWebhook(Buffer.alloc(65537), sign(raw), API_TOKEN), err('INVALID_BODY'));
});
test('bot identity comes from secret-protected private from.id, with replay age bounds', () => {
    const update = { update_id: 5, message: { date: NOW / 1000, chat: { id: 101, type: 'private' }, from: { id: 101, is_bot: false }, text: '/start' } };
    const raw = Buffer.from(JSON.stringify(update));
    assert.equal(verifyPrivateBotMessage(raw, SECRET, SECRET, NOW).telegramUserId, '101');
    assert.throws(() => verifyPrivateBotMessage(raw, 'wrong', SECRET, NOW), err('BAD_SIGNATURE'));
    assert.throws(() => verifyPrivateBotMessage(raw, SECRET, SECRET, NOW + 301_000), err('STALE_UPDATE'));
    for (const patch of [{ chat: { id: -101, type: 'group' } }, { chat: { id: 202, type: 'private' } }, { from: { id: 101, is_bot: true } }]) {
        assert.throws(() => verifyPrivateBotMessage(Buffer.from(JSON.stringify({ ...update, message: { ...update.message, ...patch } })), SECRET, SECRET, NOW));
    }
});
test('bot crypto sales fail closed instead of circumventing Telegram Stars requirement', () => {
    assert.throws(() => createBillingService().createBotCryptoPurchase(), err('BOT_REQUIRES_TELEGRAM_STARS'));
});
test('CryptoPay route requires explicit policy approval even with merchant credentials', async () => {
    const f = fixture();
    delete f.cryptoPay.checkoutPolicyApproved;
    await assert.rejects(f.create(), err('NOT_CONFIGURED'));
    assert.equal(f.createCalls(), 0);
});
test('TON address validates checksum and network; equivalent bounceable address normalizes', () => {
    const normalized = canonicalTonAddress(TON_WALLET);
    assert.match(normalized, /^0:[0-9a-f]{64}$/);
    assert.throws(() => canonicalTonAddress(TON_WALLET.slice(0, -1) + 'A'), err('INVALID_TON_ADDRESS'));
    assert.throws(() => canonicalTonAddress('0QDKbjIcfM6ezt8KjKJJLshZJJSqX7XOA4ff-W72r5gqPleK'), err('INVALID_TON_ADDRESS'));
    assert.equal(canonicalTonAddress('UQDKbjIcfM6ezt8KjKJJLshZJJSqX7XOA4ff-W72r5gqPuwA'), canonicalTonAddress('EQDKbjIcfM6ezt8KjKJJLshZJJSqX7XOA4ff-W72r5gqPrHF'));
});
test('TON amount uses integer ceil and a fresh server quote, no float exchange rates', () => {
    const order = { provider: 'ton', id: 'order', usdCents: 50 };
    const rate = { centsNumerator: 300n, denominator: 1n, observedAtMs: NOW };
    const quote = makeTonQuote(order, rate, NOW);
    assert.equal(quote.nanoTon, '166666667');
    assert.equal(quote.comment, 'lunagram:order');
    assert.ok(quote.checkoutUrl.startsWith(`ton://transfer/${TON_WALLET}?amount=166666667&text=lunagram%3Aorder`));
    for (const patch of [{ centsNumerator: 3 }, { denominator: 0n }, { observedAtMs: NOW - 60001 }, { observedAtMs: NOW + 1 }]) {
        assert.throws(() => makeTonQuote(order, { ...rate, ...patch }, NOW), err('INVALID_TON_QUOTE'));
    }
});
test('TON receipt requires native asset, exact account/comment/amount, finality and successful execution', () => {
    const order = { id: 'order', provider: 'ton', createdAtMs: NOW, expiresAtMs: NOW + 900000, usdCents: 50 };
    order.ton = makeTonQuote(order, { centsNumerator: 300n, denominator: 1n, observedAtMs: NOW }, NOW);
    const receipt = { network: 'mainnet', finalized: true, success: true, bounced: false, asset: 'TON',
        destinationRaw: order.ton.recipient, nanoTon: order.ton.nanoTon, comment: order.ton.comment,
        transactionHash: 'a'.repeat(64), paidAtMs: NOW };
    assert.equal(checkPaidTonTransfer(order, receipt, NOW).eventId, `ton:${'a'.repeat(64)}`);
    for (const patch of [{ network: 'testnet' }, { finalized: false }, { bounced: true }, { success: false },
        { asset: 'USDT' }, { destinationRaw: '0:' + '0'.repeat(64) }, { nanoTon: '1' }, { comment: 'other-order' },
        { transactionHash: 'garbage' }, { paidAtMs: NOW + 900001 }]) {
        assert.throws(() => checkPaidTonTransfer(order, { ...receipt, ...patch }, NOW), err('PAYMENT_MISMATCH'));
    }
});
test('TON service reconciles only server adapter receipt and never trusts client tx boolean', async () => {
    let receipt;
    const f = fixture({ ton: {
        getRate: async () => ({ centsNumerator: 300n, denominator: 1n, observedAtMs: NOW }),
        findPayment: async () => receipt,
    } });
    const order = await f.create({ provider: 'ton', paid: true, txHash: 'client-input' });
    await assert.rejects(f.reconcile(order), err('PAYMENT_MISMATCH'));
    const stored = f.repository.snapshot().orders[order.id];
    receipt = { network: 'mainnet', finalized: true, success: true, bounced: false, asset: 'TON',
        destinationRaw: stored.ton.recipient, nanoTon: stored.ton.nanoTon, comment: stored.ton.comment,
        transactionHash: 'b'.repeat(64), paidAtMs: NOW };
    await f.reconcile(order);
    assert.equal(f.repository.snapshot().entitlements['101'].planId, 'based');
});
test('repository transaction rolls back all writes on failure', async () => {
    const repository = memoryRepositoryForTests();
    await assert.rejects(repository.transaction(tx => {
        tx.putEntitlement({ telegramUserId: '101', expiresAtMs: NOW });
        throw new Error('crash');
    }));
    assert.deepEqual(repository.snapshot().entitlements, {});
});

// Arbitrary TEST amounts below are not USD-to-Stars recommendations or production tariffs.
const starsConfig = () => ({ salesEnabled: true, webhookSecret: SECRET, prices: { based: 7, standard: 11, plus: 19, pro: 23, ultra: 41 } });
const botCommand = (updateId = 100, plan = 'based') => Buffer.from(JSON.stringify({ update_id: updateId,
    message: { from: { id: 101, is_bot: false }, chat: { id: 101, type: 'private' }, date: NOW / 1000, text: `/buy ${plan}` } }));
const starPreCheckout = (orderId, changes = {}) => Buffer.from(JSON.stringify({ update_id: 101,
    pre_checkout_query: { id: 'checkout-opaque-1', from: { id: 101, is_bot: false }, invoice_payload: orderId,
        currency: 'XTR', total_amount: 7, ...changes } }));
const starPaid = (orderId, changes = {}, sender = 101, date = NOW / 1000) => Buffer.from(JSON.stringify({ update_id: 102,
    message: { from: { id: sender, is_bot: false }, chat: { id: sender, type: 'private' }, date,
        successful_payment: { currency: 'XTR', total_amount: 7, invoice_payload: orderId,
            telegram_payment_charge_id: 'opaque.charge-id:1', provider_payment_charge_id: '', ...changes } } }));

test('Stars prices require explicit positive integers, with no guessed USD conversion', () => {
    for (const amount of [undefined, 0, -1, 1.5, '7', Infinity, 1000001]) {
        assert.throws(() => starsPrice({ based: amount }, 'based'), err('STARS_PRICE_NOT_CONFIGURED'));
    }
    assert.equal(starsPrice({ based: 7 }, 'based'), 7);
    assert.throws(() => starsPrice({}, 'pro'), err('STARS_PRICE_NOT_CONFIGURED'));
});
test('Stars checkout is unavailable without bot config or an explicit plan tariff', async () => {
    const f = fixture();
    const request = { rawBody: botCommand(), webhookSecret: SECRET };
    await assert.rejects(f.service.prepareBotStarsInvoice(request), err('NOT_CONFIGURED'));
    const g = fixture({ stars: { salesEnabled: true, webhookSecret: SECRET, prices: {} } });
    await assert.rejects(g.service.prepareBotStarsInvoice(request), err('STARS_PRICE_NOT_CONFIGURED'));
    assert.deepEqual(g.repository.snapshot().orders, {});
});
test('Stars bot invoice binds private verified buyer, deduplicates update and freezes amount', async () => {
    const config = starsConfig();
    const f = fixture({ stars: config });
    const request = { rawBody: botCommand(), webhookSecret: SECRET };
    const [a, b] = await Promise.all([f.service.prepareBotStarsInvoice(request), f.service.prepareBotStarsInvoice(request)]);
    assert.equal(a.order.id, b.order.id);
    assert.equal(a.invoice.chat_id, '101');
    assert.equal(a.invoice.currency, 'XTR');
    assert.equal(a.invoice.provider_token, '');
    assert.equal(a.invoice.prices[0].amount, 7);
    assert.equal(a.invoice.payload, a.order.id);
    config.prices.based = 99;
    assert.equal((await f.service.prepareBotStarsInvoice(request)).invoice.prices[0].amount, 7);
    await assert.rejects(f.service.prepareBotStarsInvoice({ ...request, webhookSecret: 'fake' }), err('BAD_SIGNATURE'));
});
test('Stars precheckout never grants and rejects mismatched user/amount/currency/duplicate purchase', async () => {
    const f = fixture({ stars: starsConfig() });
    const { order } = await f.service.prepareBotStarsInvoice({ rawBody: botCommand(), webhookSecret: SECRET });
    for (const changes of [{ total_amount: 1 }, { from: { id: 202, is_bot: false } }, { currency: 'USD' }, { invoice_payload: 'other' }]) {
        await assert.rejects(f.service.prepareStarsPreCheckoutReply({ rawBody: starPreCheckout(order.id, changes), webhookSecret: SECRET }));
    }
    const request = { rawBody: starPreCheckout(order.id), webhookSecret: SECRET };
    assert.equal((await f.service.prepareStarsPreCheckoutReply(request)).ok, true);
    assert.equal((await f.service.prepareStarsPreCheckoutReply(request)).ok, true);
    await assert.rejects(f.service.prepareStarsPreCheckoutReply({ rawBody: starPreCheckout(order.id, { id: 'another-checkout' }), webhookSecret: SECRET }), err('CHECKOUT_REJECTED'));
    assert.deepEqual(f.repository.snapshot().entitlements, {});
    assert.deepEqual(f.repository.snapshot().payments, {});
});
test('Stars successful payment grants key eligibility only, exactly once across delayed retries', async () => {
    const f = fixture({ stars: starsConfig() });
    const { order } = await f.service.prepareBotStarsInvoice({ rawBody: botCommand(), webhookSecret: SECRET });
    const payment = { rawBody: starPaid(order.id), webhookSecret: SECRET };
    f.setNow(NOW + 3 * 86400000); // Not rejected by text-command five-minute policy.
    await Promise.all([f.service.handleStarsSuccessfulPayment(payment), f.service.handleStarsSuccessfulPayment(payment)]);
    assert.equal(Object.keys(f.repository.snapshot().payments).length, 1);
    assert.deepEqual(f.repository.snapshot().entitlements, {});
    const { code } = await f.service.issuePurchasedKey({ session: 'alice-verified-server-session', orderId: order.id });
    const result = await f.service.redeemCode({ session: 'alice-verified-server-session', code });
    assert.equal(result.planId, 'based');
    assert.equal(result.featuresGated, false);
});
test('Stars forged, mismatched, recurring and cross-account receipts do not deliver keys', async () => {
    const f = fixture({ stars: starsConfig() });
    const { order } = await f.service.prepareBotStarsInvoice({ rawBody: botCommand(), webhookSecret: SECRET });
    for (const changes of [{ currency: 'USD' }, { total_amount: 1 }, { invoice_payload: 'other' },
        { telegram_payment_charge_id: '' }, { is_recurring: true }, { subscription_expiration_date: NOW / 1000 }]) {
        await assert.rejects(f.service.handleStarsSuccessfulPayment({ rawBody: starPaid(order.id, changes), webhookSecret: SECRET }));
    }
    await assert.rejects(f.service.handleStarsSuccessfulPayment({ rawBody: starPaid(order.id, {}, 202), webhookSecret: SECRET }), err('PAYMENT_MISMATCH'));
    await assert.rejects(f.service.handleStarsSuccessfulPayment({ rawBody: starPaid(order.id), webhookSecret: 'fake' }), err('BAD_SIGNATURE'));
    assert.deepEqual(f.repository.snapshot().payments, {});
});
test('one Telegram charge cannot fulfill two orders; already paid order cannot checkout again', async () => {
    const f = fixture({ stars: starsConfig() });
    const first = (await f.service.prepareBotStarsInvoice({ rawBody: botCommand(100), webhookSecret: SECRET })).order;
    const second = (await f.service.prepareBotStarsInvoice({ rawBody: botCommand(200), webhookSecret: SECRET })).order;
    await f.service.handleStarsSuccessfulPayment({ rawBody: starPaid(first.id), webhookSecret: SECRET });
    await assert.rejects(f.service.handleStarsSuccessfulPayment({ rawBody: starPaid(second.id), webhookSecret: SECRET }), err('PAYMENT_REUSED'));
    await assert.rejects(f.service.prepareStarsPreCheckoutReply({ rawBody: starPreCheckout(first.id), webhookSecret: SECRET }), err('CHECKOUT_REJECTED'));
    assert.equal(Object.keys(f.repository.snapshot().payments).length, 1);
});
