import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { dispatchLiveUpdate } from '../cloudflare/live-bot.mjs';
import { createLiveStore } from '../cloudflare/live-store.mjs';
import { createLiveControl, TERMS_VERSION } from '../cloudflare/live-control.mjs';
import { createLiveAuth } from '../live-auth.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';

const NOW = Date.UTC(2026, 9, 12, 18);
const ALICE = '4012345678901';
const BOB = '4012345678902';
const OWNER = '4012345678903';
const CLAIM_TOKEN = Buffer.alloc(32, 33).toString('base64url');
const KEYS = { encryptionKey: Buffer.alloc(32, 34), keyPepper: Buffer.alloc(32, 35), authPepper: Buffer.alloc(32, 36) };
function fixture(t, { claimed = true, sales = true } = {}) {
    const parent = resolve(tmpdir());
    const directory = mkdtempSync(join(parent, 'lunagram-live-bot-'));
    const storage = openSqliteStorageForTests(join(directory, 'bot.sqlite'));
    t.after(() => {
        storage.close();
        assert.equal(dirname(resolve(directory)), parent);
        assert.ok(basename(directory).startsWith('lunagram-live-bot-'));
        rmSync(directory, { recursive: true, force: true });
    });
    const state = { at: NOW, updateId: 1, apiResult: { ok: true, result: true } };
    const env = { BILLING_PAYMENTS_ENABLED: sales ? 'true' : 'false',
        BILLING_OWNER_CLAIM_TOKEN: CLAIM_TOKEN, BILLING_OWNER_CLAIM_EXPIRES: String(NOW + 600000) };
    const control = createLiveControl(storage, KEYS, env);
    control.finishSetup();
    if (claimed) control.claimOwner(CLAIM_TOKEN, OWNER, NOW);
    const store = createLiveStore(storage, { ...KEYS, ownerUserId: OWNER, termsVersion: TERMS_VERSION, clock: () => state.at });
    const auth = createLiveAuth({ storage, authPepper: KEYS.authPepper });
    const calls = [];
    const api = { async call(method, args) { calls.push({ method, args: structuredClone(args) }); return state.apiResult; } };
    const dispatch = update => dispatchLiveUpdate(update, { store, auth, control, api, clock: () => state.at });
    const message = (userId, text, extra = {}) => ({ update_id: state.updateId++, message: {
        message_id: state.updateId, from: { id: Number(userId), is_bot: false },
        chat: { id: Number(userId), type: 'private' }, date: Math.floor(state.at / 1000), text, ...extra } });
    const callback = (userId, data) => ({ update_id: state.updateId++, callback_query: {
        id: `test-query-${state.updateId}`, data, from: { id: Number(userId), is_bot: false },
        message: { message_id: state.updateId, chat: { id: Number(userId), type: 'private' } } } });
    const deliveries = () => [...storage.sql.exec('SELECT id,kind,encrypted_payload FROM billing_bot_outbox ORDER BY rowid')]
        .map(row => ({ id: row.id, ...store.decryptOutbox({ id: row.id, kind: row.kind, encryptedPayload: JSON.parse(row.encrypted_payload) }) }));
    const replies = userId => deliveries().filter(item => item.method === 'sendMessage' && item.chat_id === userId);
    const count = table => [...storage.sql.exec(`SELECT COUNT(*) AS n FROM ${table}`)][0].n;
    return { storage, store, control, auth, state, calls, dispatch, message, callback, deliveries, replies, count };
}
async function purchase(f, plan = 'based', months = 1) {
    await f.dispatch(f.callback(ALICE, `accept3_${plan}_${months}`));
    const invoice = f.deliveries().filter(item => item.method === 'sendInvoice').at(-1);
    assert.ok(invoice);
    const pre = { update_id: f.state.updateId++, pre_checkout_query: { id: `pre-${f.state.updateId}`,
        from: { id: Number(ALICE), is_bot: false }, invoice_payload: invoice.payload,
        currency: 'XTR', total_amount: invoice.prices[0].amount } };
    await f.dispatch(pre);
    assert.equal(f.calls.at(-1).args.ok, true);
    const chargeId = `charge-${invoice.payload}`;
    const paid = f.message(ALICE, undefined, { successful_payment: { invoice_payload: invoice.payload,
        currency: 'XTR', total_amount: invoice.prices[0].amount, telegram_payment_charge_id: chargeId } });
    await f.dispatch(paid);
    const keyReply = f.replies(ALICE).find(reply => /\b[A-Z0-9]{16}\b/.test(reply.text));
    const code = keyReply.text.match(/\b[A-Z0-9]{16}\b/)[0];
    return { invoice, pre, paid, code, chargeId };
}
function activate(f, userId, code) {
    const preview = f.store.previewRedeemV2(userId, code);
    return f.store.confirmRedeemV2(userId, preview.quote.quoteId);
}

test('private start -> explicit consent -> immutable invoice -> checkout -> encrypted key -> redeem end to end', async t => {
    const f = fixture(t);
    await f.dispatch(f.message(ALICE, '/start'));
    assert.match(f.replies(ALICE).at(-1).text, /40 ⭐/);
    assert.equal(f.count('live_orders'), 0);
    await f.dispatch(f.callback(ALICE, 'buy_based'));
    assert.equal(f.store.getConsent(ALICE).accepted, false);
    assert.match(f.replies(ALICE).at(-1).text, /выберите срок/);
    await f.dispatch(f.callback(ALICE, 'buy_based_1'));
    assert.match(f.replies(ALICE).at(-1).text, /Условия Lunagram/);
    assert.equal(f.count('live_orders'), 0);
    const { invoice, pre, paid, code } = await purchase(f);
    assert.equal(invoice.start_parameter, 'buy_based_1');
    assert.equal(invoice.provider_token, '');
    assert.equal(invoice.subscription_period, undefined);
    assert.equal(f.store.getSubscription(ALICE).active, false);
    const before = f.count('billing_bot_outbox');
    await f.dispatch(paid);
    assert.equal(f.count('billing_bot_outbox'), before);
    assert.equal(f.count('live_codes'), 1);
    f.state.at += 100000;
    await f.dispatch({ ...pre, update_id: f.state.updateId++, pre_checkout_query: { ...pre.pre_checkout_query, id: 'different-pre' } });
    assert.equal(f.calls.at(-1).args.ok, false);
    await f.dispatch(f.message(ALICE, `/redeem ${code}`));
    assert.equal(f.store.getSubscription(ALICE).active, false);
    const confirmation = f.replies(ALICE).at(-1).reply_markup.inline_keyboard[0][0].callback_data;
    assert.match(confirmation, /^activate_[0-9a-f]{48}$/);
    await f.dispatch(f.callback(ALICE, confirmation));
    assert.equal(f.store.getSubscription(ALICE).active, true);
    await f.dispatch(f.message(BOB, `/redeem ${code}`));
    assert.match(f.replies(BOB).at(-1).text, /другим аккаунтом/);
    assert.equal(f.store.getSubscription(BOB).active, false);
    await f.dispatch(f.message(ALICE, '/keys'));
    assert.ok(!f.replies(ALICE).at(-1).text.includes(code)); // Already-redeemed bearer is not reissued.
    assert.equal(f.calls.some(c => c.method === 'sendMessage' || c.method === 'sendInvoice'), false); // Outbox, not immediate network.
});

test('repeated conversation update is a durable no-op even after mutable subscription state changes', async t => {
    const f = fixture(t);
    const status = f.message(ALICE, '/status');
    await f.dispatch(status);
    const beforeText = f.replies(ALICE).at(-1).text;
    const value = await purchase(f);
    activate(f, ALICE, value.code);
    const before = f.count('billing_bot_outbox');
    await f.dispatch(status);
    assert.equal(f.count('billing_bot_outbox'), before);
    assert.ok(f.replies(ALICE).some(row => row.text === beforeText));
    assert.equal(f.count('live_fixed_grants'), 1);
});

test('old consent buttons cannot accept revised terms; three-month Standard invoice freezes 180 Stars', async t => {
    const f = fixture(t);
    await f.dispatch(f.callback(ALICE, 'accept_standard'));
    assert.equal(f.store.getConsent(ALICE).accepted, false);
    assert.equal(f.count('live_orders'), 0);
    assert.equal(f.replies(ALICE).at(-1).reply_markup.inline_keyboard[0][0].callback_data, 'accept3_terms');
    await f.dispatch(f.callback(ALICE, 'accept2_standard_3'));
    assert.equal(f.store.getConsent(ALICE).accepted, false, 'pre-tier v2 button cannot accept paid restrictions');
    assert.equal(f.count('live_orders'), 0);
    await f.dispatch(f.message(ALICE, '/buy standard 3'));
    const terms = f.replies(ALICE).at(-1);
    assert.match(terms.text, /не сдвигая даты остальных/);
    assert.match(terms.text, /После окончания срока платные функции отключаются с предупреждением/);
    assert.match(terms.text, /раз в 24 часа/);
    assert.doesNotMatch(terms.text, /функции сейчас не ограничены|тарифы не дают разных возможностей/);
    assert.match(terms.text, /Поддержка Telegram не рассматривает покупки в этом боте; обращайтесь через \/paysupport\./);
    assert.equal(terms.reply_markup.inline_keyboard[0][0].callback_data, 'accept3_standard_3');
    const value = await purchase(f, 'standard', 3);
    assert.equal(value.invoice.prices[0].amount, 180);
    assert.equal(f.store.getOrder(value.invoice.payload).months, 3);
    await f.dispatch(f.message(ALICE, `/redeem ${value.code}`));
    const preview = f.replies(ALICE).at(-1);
    assert.match(preview.text, /на 3 мес/);assert.match(preview.text, /Начало:/);assert.match(preview.text, /Окончание:/);
    assert.equal(f.store.getSubscriptionV2(ALICE).active, false);
    await f.dispatch(f.callback(ALICE, preview.reply_markup.inline_keyboard[0][0].callback_data));
    assert.equal(f.store.getSubscriptionV2(ALICE).current.months, 3);
});

test('group, cross-chat, bot, edited and stale text updates cannot create invoices or keys', async t => {
    const f = fixture(t);
    const group = f.message(ALICE, '/buy based'); group.message.chat.type = 'supergroup';
    const cross = f.message(ALICE, '/buy based'); cross.message.chat.id = Number(BOB);
    const bot = f.message(ALICE, '/buy based'); bot.message.from.is_bot = true;
    const stale = f.message(ALICE, '/buy based'); stale.message.date -= 86401;
    const edited = f.message(ALICE, '/buy based'); edited.edited_message = edited.message; delete edited.message;
    for (const update of [group, cross, bot, stale, edited]) await f.dispatch(update);
    assert.equal(f.count('billing_bot_outbox'), 0);
    assert.equal(f.count('live_orders'), 0);
    assert.equal(f.calls.length, 0);
});

test('sale switch denies purchases/checkout while late genuine paid and refunded receipts still reconcile', async t => {
    const f = fixture(t, { sales: false });
    await f.dispatch(f.message(ALICE, '/buy based'));
    assert.match(f.replies(ALICE).at(-1).text, /ещё не открыта/);
    assert.equal(f.count('live_orders'), 0);
    f.store.acceptTerms(ALICE, TERMS_VERSION);
    const order = f.store.createOrder(ALICE, 'based', 'issued-before-stop');
    f.store.reservePreCheckout({ userId: ALICE, orderId: order.id, currency: 'XTR', totalAmount: 40, queryId: 'old-pre' });
    await f.dispatch({ update_id: f.state.updateId++, pre_checkout_query: { id: 'new-pre', from: { id: Number(ALICE), is_bot: false },
        invoice_payload: order.id, currency: 'XTR', total_amount: 40 } });
    assert.equal(f.calls.at(-1).args.ok, false);
    const metadata = { invoice_payload: order.id, currency: 'XTR', total_amount: 40, telegram_payment_charge_id: 'late-paid' };
    const receipt = f.message(ALICE, undefined, { date: 1, successful_payment: metadata });
    await f.dispatch(receipt);
    assert.equal(f.store.getOrder(order.id).status, 'paid');
    await f.dispatch(f.message(ALICE, undefined, { date: 1, refunded_payment: metadata }));
    assert.equal(f.store.getOrder(order.id).status, 'refunded');
    assert.equal(f.store.listKeys(ALICE)[0].status, 'refunded');
});

test('non-owner cannot inspect support or refund; owner gets anonymous tickets and explicit refund confirmation', async t => {
    const f = fixture(t);
    const value = await purchase(f);
    activate(f, ALICE, value.code);
    await f.dispatch(f.message(ALICE, '/support Проверка оплаты'));
    const ticket = f.store.listTickets()[0];
    await f.dispatch(f.message(BOB, `/ticket ${ticket.id}`));
    assert.match(f.replies(BOB).at(-1).text, /только владельцу/);
    await f.dispatch(f.message(BOB, `/refund ${value.invoice.payload}`));
    await f.dispatch(f.callback(BOB, `refund_${value.invoice.payload}`));
    assert.equal(f.calls.some(c => c.method === 'refundStarPayment'), false);
    await f.dispatch(f.message(OWNER, `/ticket ${ticket.id}`));
    assert.match(f.replies(OWNER).at(-1).text, /Проверка оплаты/);
    assert.ok(!f.replies(OWNER).at(-1).text.includes(ALICE));
    await f.dispatch(f.message(OWNER, `/reply ${ticket.id} Проверяем платёж`));
    assert.match(f.replies(ALICE).at(-1).text, /Проверяем платёж/);
    await f.dispatch(f.message(OWNER, `/refund ${value.invoice.payload}`));
    assert.equal(f.calls.some(c => c.method === 'refundStarPayment'), false);
    const confirm = f.callback(OWNER, `refund_${value.invoice.payload}`);
    await f.dispatch(confirm);
    const refundCalls = f.calls.filter(c => c.method === 'refundStarPayment');
    assert.equal(refundCalls.length, 1);
    assert.equal(refundCalls[0].args.user_id, Number(ALICE));
    assert.equal(refundCalls[0].args.telegram_payment_charge_id, value.chargeId);
    assert.equal(f.store.getSubscription(ALICE).active, false);
    await f.dispatch(confirm);
    assert.equal(f.calls.filter(c => c.method === 'refundStarPayment').length, 1);
});

test('ambiguous refund response retains blocked key and grants until authoritative confirmation', async t => {
    const f = fixture(t);
    const value = await purchase(f);
    activate(f, ALICE, value.code);
    f.state.apiResult = { ok: false, retryable: true, error: 'TEST_TIMEOUT' };
    await f.dispatch(f.callback(OWNER, `refund_${value.invoice.payload}`));
    assert.equal(f.store.getOrder(value.invoice.payload).status, 'refund_pending');
    assert.equal(f.store.getSubscription(ALICE).active, true);
    assert.throws(() => f.store.redeem(BOB, value.code), error => error.code === 'CODE_UNAVAILABLE');
    assert.match(f.replies(OWNER).at(-1).text, /не подтверждён/);
});

test('owner setup requires exact expiring server capability plus explicit callback, never start alone', async t => {
    const f = fixture(t, { claimed: false });
    await f.dispatch(f.message(OWNER, `/start owner_${CLAIM_TOKEN}`));
    assert.equal(f.control.owner(), null);
    assert.equal(f.control.paymentsReady(), false);
    await f.dispatch(f.callback(BOB, `owner_${Buffer.alloc(32, 1).toString('base64url')}`));
    assert.equal(f.control.owner(), null);
    await f.dispatch(f.callback(OWNER, `owner_${CLAIM_TOKEN}`));
    assert.equal(f.control.owner(), OWNER);
    assert.equal(f.control.paymentsReady(), true);
    await f.dispatch(f.callback(BOB, `owner_${CLAIM_TOKEN}`));
    assert.equal(f.control.owner(), OWNER);
});

test('app pairing start is preview only; explicit exact private account confirmation is required for PKCE exchange', async t => {
    const f = fixture(t);
    const verifier = Buffer.alloc(32, 75).toString('base64url');
    const challenge = createHash('sha256').update(verifier, 'ascii').digest('base64url');
    const pair = f.auth.start({ challenge, expectedUserId: ALICE }, NOW);
    await f.dispatch(f.message(ALICE, `/start ${pair.startParameter}`));
    assert.equal(f.auth.exchange(pair.pairId, verifier, NOW).status, 'pending');
    await f.dispatch(f.callback(BOB, pair.startParameter));
    assert.equal(f.auth.exchange(pair.pairId, verifier, NOW).status, 'pending');
    await f.dispatch(f.callback(ALICE, pair.startParameter));
    const session = f.auth.exchange(pair.pairId, verifier, NOW);
    assert.equal(session.status, 'authenticated');
    assert.equal(f.auth.authenticate(session.token, NOW), ALICE);
    assert.ok(!JSON.stringify(f.deliveries()).includes(session.token));
});

test('order reference bridges buyer receipt -> support ticket -> owner-only lookup -> separate refund confirmation', async t => {
    const f = fixture(t);
    const value = await purchase(f);
    assert.ok(f.replies(ALICE).at(-1).text.includes(`Заказ: ${value.invoice.payload}`));
    await f.dispatch(f.message(ALICE, '/keys'));
    assert.ok(f.replies(ALICE).at(-1).text.includes(value.invoice.payload));
    await f.dispatch(f.message(ALICE, '/paysupport'));
    assert.match(f.replies(ALICE).at(-1).text, /\/keys/);
    assert.match(f.replies(ALICE).at(-1).text, /Сам ключ/);
    await f.dispatch(f.message(ALICE, '/support Не нахожу номер заказа, Based сегодня'));
    const ticket = f.store.listTickets()[0];
    // Knowledge of the opaque ticket reference grants no support-owner role.
    for (const user of [ALICE, BOB]) {
        await f.dispatch(f.message(user, `/orders ${ticket.id}`));
        const denied = f.replies(user).at(-1).text;
        assert.match(denied, /только владельцу/);
        assert.ok(!denied.includes(value.invoice.payload));
    }
    await f.dispatch(f.message(OWNER, `/ticket ${ticket.id}`));
    const detail = f.replies(OWNER).at(-1).text;
    assert.ok(detail.includes(`/refund ${value.invoice.payload}`));
    assert.ok(detail.includes(`/orders ${ticket.id}`));
    assert.ok(!detail.includes(ALICE) && !detail.includes(value.code) && !detail.includes(value.chargeId));
    await f.dispatch(f.message(OWNER, `/orders ${ticket.id}`));
    const overview = f.replies(OWNER).at(-1).text;
    assert.ok(overview.includes(value.invoice.payload));
    assert.ok(!overview.includes(ALICE) && !overview.includes(value.code) && !overview.includes(value.chargeId));
    assert.match(overview, /40 ⭐/);
    assert.match(overview, /отдельное подтверждение/);
    assert.equal(f.calls.some(c => c.method === 'refundStarPayment'), false);
    await f.dispatch(f.message(OWNER, `/refund ${value.invoice.payload}`));
    assert.equal(f.calls.some(c => c.method === 'refundStarPayment'), false);
    assert.equal(f.replies(OWNER).at(-1).reply_markup.inline_keyboard[0][0].callback_data, `refund_${value.invoice.payload}`);
    await f.dispatch(f.callback(OWNER, `refund_${value.invoice.payload}`));
    assert.equal(f.calls.filter(c => c.method === 'refundStarPayment').length, 1);
    assert.equal(f.store.getOrder(value.invoice.payload).status, 'refunded');
});

test('owner order pages ignore order IDs mentioned in ticket text and retain bounded readable replies', async t => {
    const f = fixture(t);
    const alice = await purchase(f);
    f.store.acceptTerms(BOB, TERMS_VERSION);
    const bob = f.store.createOrder(BOB, 'ultra', 'bob-purchase');
    f.store.reservePreCheckout({ userId: BOB, orderId: bob.id, currency: 'XTR', totalAmount: 360, queryId: 'bob-query' });
    f.store.recordPayment({ userId: BOB, orderId: bob.id, currency: 'XTR', totalAmount: 360, chargeId: 'bob-charge' });
    const ticket = f.store.createSupportTicket(ALICE, `Не мой заказ ${bob.id} ` + 'д'.repeat(1900), 'long-ticket');
    await f.dispatch(f.message(OWNER, `/ticket ${ticket.id}`));
    const text = f.replies(OWNER).at(-1).text;
    assert.ok(text.length <= 2000);
    assert.ok(text.includes(`/refund ${alice.invoice.payload}`));
    assert.ok(!text.includes(`/refund ${bob.id}`));
    const [expectedId] = text.match(/\/refund [a-f0-9]{48}/g);
    assert.equal(expectedId, `/refund ${alice.invoice.payload}`);
    await f.dispatch(f.message(OWNER, `/orders ${ticket.id} 2`));
    assert.match(f.replies(OWNER).at(-1).text, /на этой странице нет/);
    for (const arg of [`${ticket.id} 1001`, `${ticket.id} 0`, `${ticket.id} 1.5`, ALICE]) {
        await f.dispatch(f.message(OWNER, `/orders ${arg}`));
        assert.match(f.replies(OWNER).at(-1).text, /страница 1–1000/);
    }
    await f.dispatch(f.message(OWNER, `/orders ${'f'.repeat(48)}`));
    assert.match(f.replies(OWNER).at(-1).text, /Обращение не найдено/);
    assert.equal(f.calls.some(c => c.method === 'refundStarPayment'), false);
});
