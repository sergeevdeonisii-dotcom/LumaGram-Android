import { createBotUpdateRepository } from './bot-update-repository.mjs';
import { handleBotWebhook, webhookAdmission } from './bot-webhook.mjs';
import { isBotWebhookRequest, botOptions } from './bot-route.mjs';
import { notReadyResponse, publicPath, publicResponse } from './public-http.mjs';
import { randomBytes } from 'node:crypto';
import { createLiveAuth } from '../live-auth.mjs';
import { createLiveStore } from './live-store.mjs';
import { createLiveControl, liveKeys, secretMatches } from './live-control.mjs';
import { createTelegramApi } from './telegram-api.mjs';
import { handleLiveWebhook } from './live-bot.mjs';
import { appPath, handleAppRequest, jsonResponse, SETUP_PATH } from './live-http.mjs';

export const BILLING_OBJECT_NAME = 'billing-v1';

// Fixed identity, never derived from an incoming user or account ID.
export function billingStoreStub(env) {
    const namespace = env.BILLING_STORE;
    return namespace.get(namespace.idFromName(BILLING_OBJECT_NAME));
}

export class BillingStore {
    constructor(ctx, env) {
        this.ctx = ctx;
        this.storage = ctx.storage;
        this.env = env;
    }
    async fetch(request) {
        const keys = liveKeys(this.env);
        if (keys) {
            if (isBotWebhookRequest(request)) {
                const rejected = webhookAdmission(request, botOptions(this.env));
                if (rejected) return rejected;
            }
            if (appPath(request) === SETUP_PATH && !secretMatches((request.headers.get('Authorization') || '').replace(/^Bearer /, ''), this.env.BILLING_SETUP_TOKEN)) {
                return jsonResponse({ error: 'FORBIDDEN' }, 403);
            }
            try { return await this.liveFetch(request, keys); }
            catch { return jsonResponse({ error: 'NOT_READY' }, 503); }
        }
        if (!isBotWebhookRequest(request)) return notReadyResponse();
        return handleBotWebhook(request, {
            ...botOptions(this.env),
            repository: {
                durable: true,
                claim: (updateId, userId, nowMs) => {
                    // Schema/storage access only after authenticated, bounded,
                    // fresh private command validation. No financial tables.
                    this.botRepository ??= createBotUpdateRepository(this.storage);
                    return this.botRepository.claim(updateId, userId, nowMs);
                },
            },
        });
    }

    runtime(keys) {
        const control = createLiveControl(this.storage, keys, this.env);
        return { control, store: createLiveStore(this.storage, { ...keys, ownerUserId: control.owner() }),
            auth: createLiveAuth({ storage: this.storage, authPepper: keys.authPepper }),
            api: createTelegramApi(this.env.TELEGRAM_BOT_TOKEN) };
    }

    async schedule(at) {
        const current = await this.storage.getAlarm();
        if (current === null || current > at) await this.storage.setAlarm(at);
    }

    async liveFetch(request, keys) {
        const runtime = this.runtime(keys);
        const path = publicPath(request);
        if (path) return publicResponse(path, { live: true, payments: runtime.control.paymentsReady() });
        if (appPath(request) === SETUP_PATH) {
            const token = (request.headers.get('Authorization') || '').replace(/^Bearer /, '');
            if (!secretMatches(token, this.env.BILLING_SETUP_TOKEN)) return jsonResponse({ error: 'FORBIDDEN' }, 403);
            if (runtime.control.configured()) {
                const hook = await runtime.api.call('getWebhookInfo', {});
                const identity = await runtime.api.call('getMe', {});
                return jsonResponse({ configured: true, diagnostics: {
                    webhook: hook.ok ? hook.result : { available: false },
                    botMatches: identity.ok && identity.result.username === 'LunagramAppPayBot',
                    ownerBound: runtime.control.owner() !== null,
                    lastFailure: runtime.control.lastFailure(),
                    queue: [...this.storage.sql.exec('SELECT state,COUNT(*) AS count FROM billing_bot_outbox GROUP BY state')],
                    nextAlarmAt: await this.storage.getAlarm(),
                } });
            }
            const result = await runtime.api.call('setWebhook', {
                url: 'https://lunagram-billing.pages.dev/telegram/webhook',
                secret_token: this.env.TELEGRAM_WEBHOOK_SECRET, max_connections: 2,
                allowed_updates: ['message', 'callback_query', 'pre_checkout_query'],
                ...(this.env.TELEGRAM_WEBHOOK_IP ? { ip_address: this.env.TELEGRAM_WEBHOOK_IP } : {}),
            });
            if (!result.ok) return jsonResponse({ error: 'NOT_READY' }, 503);
            const commands = await runtime.api.call('setMyCommands', { commands: [
                { command: 'start', description: 'Подписки Lunagram' },
                { command: 'plans', description: 'Тарифы и покупка ключа' },
                { command: 'keys', description: 'Мои ключи' },
                { command: 'status', description: 'Статус подписки' },
                { command: 'terms', description: 'Условия подписки' },
                { command: 'paysupport', description: 'Поддержка и возвраты' },
            ] });
            if (!commands.ok) return jsonResponse({ error: 'NOT_READY' }, 503);
            const hook = await runtime.api.call('getWebhookInfo', {});
            if (!hook.ok || !hook.result.configured) return jsonResponse({ error: 'NOT_READY' }, 503);
            runtime.control.finishSetup();
            return jsonResponse({ configured: true });
        }
        // Set a wakeup BEFORE any durable business writes. If execution crashes
        // after a commit, the queue still has a scheduled dispatcher.
        await this.schedule(Date.now() + 31_000);
        const result = isBotWebhookRequest(request)
            ? await handleLiveWebhook(request, { ...botOptions(this.env), ...runtime })
            : await handleAppRequest(request, runtime);
        const due = runtime.store.nextDueAt();
        if (due !== null) await this.schedule(Math.max(Date.now() + 100, due));
        return result;
    }

    async alarm() {
        const keys = liveKeys(this.env);
        if (!keys) return;
        const { store, api } = this.runtime(keys);
        await store.recoverExpired(Date.now());
        // Leave a recovery alarm BEFORE taking a lease or starting network I/O.
        if (store.nextDueAt() !== null) await this.schedule(Date.now() + 31_000);
        for (let count = 0; count < 3; count++) {
            const item = await store.claimDue(randomBytes(24).toString('base64url'), Date.now());
            if (!item) break;
            try {
                const { method, ...payload } = store.decryptOutbox(item);
                const result = await api.call(method, payload);
                if (result.ok && Number.isSafeInteger(result.result?.message_id)
                    && String(result.result.chat?.id) === String(payload.chat_id)) {
                    await store.finishAck(item.id, item.leaseId, { messageId: result.result.message_id }, Date.now());
                } else {
                    await store.defer(item.id, item.leaseId, Date.now(), result.retryAfterSeconds);
                }
            } catch { await store.defer(item.id, item.leaseId, Date.now()); }
        }
        const due = store.nextDueAt();
        if (due !== null) await this.schedule(Math.max(Date.now() + 1000, due));
        // A pre-existing future wakeup may remain after the last ACK. It runs
        // once, finds no jobs, and does not schedule another. No idle polling.
    }
}
