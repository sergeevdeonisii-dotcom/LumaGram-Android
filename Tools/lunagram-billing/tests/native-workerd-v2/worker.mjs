// LOCAL-ONLY regression fixture. Never deploy this Worker or bind production storage.
// All identities, capabilities and payment receipts below are synthetic. Every
// Bot API operation is mocked; network fetch outside a local DO binding is denied.
import { Buffer } from 'node:buffer';
import { createHash, randomBytes } from 'node:crypto';
import { BillingStore } from '../../cloudflare/billing-object.mjs';
import { dispatchLiveUpdate } from '../../cloudflare/live-bot.mjs';
import { createLiveStore } from '../../cloudflare/live-store.mjs';
import { liveKeys, TERMS_VERSION } from '../../cloudflare/live-control.mjs';
import { proxyAppRequest } from '../../cloudflare/live-http.mjs';

globalThis.fetch = async () => { throw new Error('LOCAL_EXTERNAL_NETWORK_DISABLED'); };
const ALICE = '101000001', LEGACY = '101000002', ROLLBACK = '101000003', OWNER = '101000009';
const CLAIM = Buffer.alloc(32, 51).toString('base64url');
const FAKE_ENV = Object.freeze({
    LIVE_BOT_ENABLED: 'true', BILLING_PAYMENTS_ENABLED: 'true',
    BILLING_ENCRYPTION_KEY: Buffer.alloc(32, 52).toString('base64url'),
    BILLING_KEY_PEPPER: Buffer.alloc(32, 53).toString('base64url'),
    BILLING_AUTH_PEPPER: Buffer.alloc(32, 54).toString('base64url'),
    BILLING_OWNER_CLAIM_TOKEN: CLAIM, BILLING_OWNER_CLAIM_EXPIRES: '4000000000000',
    TELEGRAM_BOT_TOKEN: '123456789:' + 'f'.repeat(32),
    TELEGRAM_BOT_USERNAME: 'LunagramAppPayBot', TELEGRAM_WEBHOOK_SECRET: 'FAKE_LOCAL_ONLY_' + 'x'.repeat(40),
});
function check(value, name) { if (!value) throw new Error(`LOCAL_ASSERT_${name}`); }
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

export class LocalTermsV2Store extends BillingStore {
    constructor(ctx, env) {
        super(ctx, { ...env, ...FAKE_ENV });
        this.rows = (sql, ...args) => [...this.storage.sql.exec(sql, ...args)];
        this.rows('CREATE TABLE IF NOT EXISTS local_v2_fixture(name TEXT PRIMARY KEY,value TEXT NOT NULL)');
        this.rows("INSERT OR IGNORE INTO local_v2_fixture(name,value) VALUES ('clock',?)", String(Date.now()));
        this.rows("INSERT OR IGNORE INTO local_v2_fixture(name,value) VALUES ('update_sequence','100000')");
        this.calls = [];
    }
    clock() { return Number(this.rows("SELECT value FROM local_v2_fixture WHERE name='clock'")[0].value); }
    save(name, value) { this.rows('INSERT OR REPLACE INTO local_v2_fixture(name,value) VALUES (?,?)', name, JSON.stringify(value)); }
    read(name) { const row = this.rows('SELECT value FROM local_v2_fixture WHERE name=?', name)[0]; return row ? JSON.parse(row.value) : null; }
    async alarm() { /* No background network. Tests explicitly invoke parent alarm with mocked API. */ }
    async schedule() { /* Local test controls lifetime; never register background alarms. */ }
    runtime(keys = liveKeys(this.env)) {
        const runtime = super.runtime(keys);
        return { ...runtime, clock: () => this.clock(),
            store: createLiveStore(this.storage, { ...keys, termsVersion: TERMS_VERSION,
                ownerUserId: runtime.control.owner(), clock: () => this.clock() }),
            api: { call: async (method, payload) => {
                this.calls.push(method);
                if (method === 'sendMessage' || method === 'sendInvoice') return { ok: true,
                    result: { message_id: 900 + this.calls.length, chat: { id: Number(payload.chat_id), type: 'private' } } };
                if (['answerCallbackQuery', 'answerPreCheckoutQuery', 'refundStarPayment'].includes(method)) return { ok: true, result: true };
                throw new Error('LOCAL_UNEXPECTED_API_METHOD');
            } },
        };
    }
    nextUpdateId() {
        // Telegram does not restart its update sequence when our DO restarts.
        return this.storage.transactionSync(() => Number(this.rows("UPDATE local_v2_fixture SET value=CAST(value AS INTEGER)+1 WHERE name='update_sequence' RETURNING value")[0].value));
    }
    callback(userId, data) { const updateId = this.nextUpdateId(); return { update_id: updateId, callback_query: { id: `local-${updateId}`, data,
        from: { id: Number(userId), is_bot: false }, message: { chat: { id: Number(userId), type: 'private' } } } }; }
    payment(userId, order, chargeId) { return { update_id: this.nextUpdateId(), message: {
        from: { id: Number(userId), is_bot: false }, chat: { id: Number(userId), type: 'private' }, date: Math.floor(this.clock() / 1000),
        successful_payment: { invoice_payload: order.id, currency: 'XTR', total_amount: order.starsAmount, telegram_payment_charge_id: chargeId },
    } }; }
    delivery(store, outboxId) {
        const row = this.rows('SELECT * FROM billing_bot_outbox WHERE id=?', outboxId)[0];
        return store.decryptOutbox({ id: row.id, kind: row.kind, encryptedPayload: JSON.parse(row.encrypted_payload) });
    }
    code(store, orderId) {
        for (const row of this.rows("SELECT * FROM billing_bot_outbox WHERE kind='telegram_paid_key'")) {
            const payload = store.decryptOutbox({ id: row.id, kind: row.kind, encryptedPayload: JSON.parse(row.encrypted_payload) });
            if (payload.text.includes(orderId)) return payload.text.match(/\b[A-Z0-9]{16}\b/)[0];
        }
        throw new Error('LOCAL_MISSING_KEY');
    }
    async app(path, body, token = null) {
        // Actual Pages DTO validation and actual DO HTTP/auth routing, without a
        // network hop or client-supplied account identity.
        const response = await proxyAppRequest(new Request(`http://127.0.0.1${path}`, { method: 'POST',
            headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': '127.0.0.1', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
            body: JSON.stringify(body) }), { BILLING_SERVICE: { fetch: request => super.fetch(request) } });
        return { status: response.status, body: await response.json() };
    }
    async session(userId) {
        const verifier = randomBytes(32).toString('base64url');
        const start = await this.app('/v1/auth/start', { expectedUserId: userId,
            challenge: createHash('sha256').update(verifier).digest('base64url') });
        check(start.status === 200, 'PAIR_START');
        await dispatchLiveUpdate(this.callback(userId, `pair_${start.body.pairId}`), this.runtime());
        const exchanged = await this.app('/v1/auth/exchange', { pairId: start.body.pairId, verifier });
        check(exchanged.status === 200 && exchanged.body.telegramUserId === userId, 'PAIR_EXCHANGE');
        return exchanged.body.token; // Synthetic session stays in memory, never in report or fixture table.
    }
    async purchase(userId, plan, months, suffix, legacy = false) {
        const runtime = this.runtime(), { store } = runtime;
        let order;
        if (legacy) {
            store.acceptTerms(userId, TERMS_VERSION); order = store.createOrder(userId, plan, `local-legacy-${suffix}`);
        } else {
            await dispatchLiveUpdate(this.callback(userId, `accept3_${plan}_${months}`), runtime);
            const rows = this.rows('SELECT id FROM live_orders ORDER BY rowid DESC LIMIT 1');
            order = store.getOrder(rows[0].id);
            check(order.months === months && order.schemaVersion === 2, 'FROZEN_ORDER');
        }
        await dispatchLiveUpdate({ update_id: this.nextUpdateId(), pre_checkout_query: { id: `local-pre-${suffix}`,
            from: { id: Number(userId), is_bot: false }, invoice_payload: order.id, currency: 'XTR', total_amount: order.starsAmount } }, runtime);
        const chargeId = `fake-local-charge-${suffix}`;
        const receipt = this.payment(userId, order, chargeId);
        await dispatchLiveUpdate(receipt, runtime); await dispatchLiveUpdate(receipt, runtime);
        check(store.getOrder(order.id).status === 'paid', 'PAID');
        return { order, chargeId, code: this.code(store, order.id) };
    }
    async exercise() {
        check(!this.read('evidence'), 'USE_FRESH_FIXTURE');
        const initial = this.runtime(); initial.control.finishSetup(); initial.control.claimOwner(CLAIM, OWNER, this.clock());
        const runtime = this.runtime(), { store } = runtime;
        const a = await this.purchase(ALICE, 'standard', 3, 'a'), b = await this.purchase(ALICE, 'ultra', 12, 'b');
        check(a.order.starsAmount === 180 && b.order.starsAmount === 2520, 'PRICES');
        const token = await this.session(ALICE);
        check((await this.app('/v1/redeem', { code: a.code }, token)).body.error === 'UPGRADE_REQUIRED', 'V1_SCHEMA_GUARD');
        const previewA = await this.app('/v2/redeem/preview', { code: a.code }, token);
        check(previewA.status === 200 && previewA.body.active === false, 'PREVIEW_NO_CLAIM');
        const confirmedA = await this.app('/v2/redeem/confirm', { quoteId: previewA.body.quote.quoteId }, token);
        check(confirmedA.status === 200 && confirmedA.body.active, 'CONFIRM_A');
        const previewB = await this.app('/v2/redeem/preview', { code: b.code }, token);
        const confirmedB = await this.app('/v2/redeem/confirm', { quoteId: previewB.body.quote.quoteId }, token);
        check(confirmedB.status === 200 && confirmedB.body.current.planId === 'standard'
            && confirmedB.body.queued[0].planId === 'ultra'
            && confirmedB.body.queued[0].startsAtMs === confirmedA.body.activation.expiresAtMs, 'IMMUTABLE_QUEUE');
        const legacy = await this.purchase(LEGACY, 'based', 1, 'legacy', true), legacyToken = await this.session(LEGACY);
        const old = await this.app('/v1/redeem', { code: legacy.code }, legacyToken);
        const oldV2 = await this.app('/v2/subscription', {}, legacyToken);
        check(old.status === 200 && old.body.active && oldV2.body.legacy === true && oldV2.body.current === null, 'V1_COMPAT');
        const rollback = await this.purchase(ROLLBACK, 'plus', 6, 'rollback');
        const q = store.previewRedeemV2(ROLLBACK, rollback.code);
        this.rows("CREATE TRIGGER local_fail_grant BEFORE INSERT ON live_ledger WHEN NEW.kind='redeem' BEGIN SELECT RAISE(ABORT,'LOCAL_ROLLBACK'); END");
        let failed = false;
        try { store.confirmRedeemV2(ROLLBACK, q.quote.quoteId); } catch { failed = true; }
        this.rows('DROP TRIGGER local_fail_grant');
        check(failed && !store.getSubscriptionV2(ROLLBACK).active
            && store.listKeys(ROLLBACK)[0].status === 'available', 'REAL_SQLITE_ROLLBACK');
        store.confirmRedeemV2(ROLLBACK, q.quote.quoteId);
        for (let i = 0; i < 10 && store.nextDueAt() !== null; i++) await BillingStore.prototype.alarm.call(this);
        const states = this.rows('SELECT state,COUNT(*) AS n FROM billing_bot_outbox GROUP BY state');
        check(states.length === 1 && states[0].state === 'acknowledged', 'MOCK_DELIVERY_ACK');
        this.save('evidence', { firstOrder: a.order.id, firstCharge: a.chargeId, firstQuote: previewA.body.quote.quoteId,
            secondOrder: b.order.id, firstTerm: confirmedA.body.activation, queued: confirmedB.body.queued,
            legacyExpiry: old.body.expiresAtMs, grantCount: this.rows('SELECT COUNT(*) AS n FROM live_fixed_grants')[0].n });
        return { ok: true, phase: 'initial', actualDurableObjectSqlite: true, prices: [180, 2520],
            verifiedPairingAndPagesProxy: true, immutableQueue: true, v1Compatible: true, nativeRollback: true,
            mockOutboxAcknowledged: states[0].n, externalNetwork: 'disabled', fakeFinancialFixturesOnly: true };
    }
    async resume() {
        const saved = this.read('evidence'); check(saved, 'INITIAL_REQUIRED');
        check(!this.read('resumed'), 'RESTART_CHECK_ALREADY_DONE');
        this.rows("UPDATE local_v2_fixture SET value=? WHERE name='clock'", String(this.clock() + 180_000));
        const { store } = this.runtime(), token = await this.session(ALICE);
        const retry = await this.app('/v2/redeem/confirm', { quoteId: saved.firstQuote }, token);
        check(retry.status === 200 && same(retry.body.activation, saved.firstTerm), 'RESTART_SAME_ACTIVATION');
        check(this.rows('SELECT COUNT(*) AS n FROM live_fixed_grants')[0].n === saved.grantCount, 'NO_DOUBLE_GRANT');
        const before = this.rows('SELECT * FROM live_fixed_grants ORDER BY grant_id');
        store.prepareRefund(saved.firstOrder);
        check(store.getSubscriptionV2(ALICE).current.orderId === saved.firstOrder, 'PENDING_KEEPS_TERM');
        store.recordRefund({ chargeId: saved.firstCharge });
        const result = await this.app('/v2/subscription', {}, token);
        check(result.status === 200 && !result.body.active && result.body.current === null
            && same(result.body.queued, saved.queued), 'REFUND_NO_REBASE');
        check(same(before, this.rows('SELECT * FROM live_fixed_grants ORDER BY grant_id')), 'HISTORY_IMMUTABLE');
        check(store.getSubscription(LEGACY).expiresAtMs === saved.legacyExpiry, 'LEGACY_RESTART');
        const revision = result.body.queueVersion; store.recordRefund({ chargeId: saved.firstCharge });
        check(store.getSubscriptionV2(ALICE).queueVersion === revision, 'REFUND_IDEMPOTENT');
        this.save('resumed', true);
        return { ok: true, phase: 'reopened-after-process-restart', expiredQuoteRetry: true, noDoubleGrant: true,
            pendingRefundPreservedTerm: true, confirmedRefundPreservedOtherDates: true, unchangedHistoricalIntervals: true,
            legacyExpiryPreserved: true, externalNetwork: 'disabled', fakeFinancialFixturesOnly: true };
    }
    async fetch(request) {
        try {
            await request.arrayBuffer();
            const path = new URL(request.url).pathname;
            if (path === '/exercise') return Response.json(await this.exercise());
            if (path === '/resume') return Response.json(await this.resume());
            return new Response(null, { status: 404 });
        } catch (error) {
            // Fake fixture diagnostics only; no record, token, code or request body.
            return Response.json({ ok: false, error: error.code || error.name, detail: String(error.message).slice(0, 200) }, { status: 500 });
        }
    }
}

export default {
    fetch(request, env) {
        const url = new URL(request.url);
        if (!['127.0.0.1', 'localhost'].includes(url.hostname) || request.method !== 'POST'
            || !['/exercise', '/resume'].includes(url.pathname) || url.search) return new Response(null, { status: 404 });
        return env.LOCAL_TERMS.get(env.LOCAL_TERMS.idFromName('local-v2-one-database')).fetch(request);
    },
};
