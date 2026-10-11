import { publicPath, publicResponse, notReadyResponse } from './public-http.mjs';
import { billingStoreStub } from './billing-object.mjs';
import { webhookAdmission } from './bot-webhook.mjs';
import { isBotWebhookRequest, botOptions } from './bot-route.mjs';
import { liveKeys, secretMatches } from './live-control.mjs';
import { appPath, SETUP_PATH, jsonAdmission, jsonResponse } from './live-http.mjs';
export { BillingStore } from './billing-object.mjs';

export default {
    async fetch(request, env) {
        const path = publicPath(request);
        if (path) {
            if (!liveKeys(env)) return publicResponse(path);
            try { return await billingStoreStub(env).fetch(new Request(`https://billing.internal${path}`)); }
            catch { return notReadyResponse(); }
        }
        const app = appPath(request);
        if (app && liveKeys(env)) {
            if (!jsonAdmission(request)) return jsonResponse({ error: 'INVALID_BODY' }, 400);
            if (app === SETUP_PATH && !secretMatches((request.headers.get('Authorization') || '').replace(/^Bearer /, ''), env.BILLING_SETUP_TOKEN)) {
                return jsonResponse({ error: 'FORBIDDEN' }, 403);
            }
            try { return await billingStoreStub(env).fetch(request); }
            catch { return jsonResponse({ error: 'NOT_READY' }, 503); }
        }
        if (!isBotWebhookRequest(request)) return notReadyResponse();
        try {
            // Reject unauthenticated traffic before invoking/instantiating the DO.
            const rejected = webhookAdmission(request, botOptions(env));
            if (rejected) return rejected;
            const headers = {
                'Content-Type': request.headers.get('Content-Type'),
                'X-Telegram-Bot-Api-Secret-Token': request.headers.get('X-Telegram-Bot-Api-Secret-Token'),
            };
            return await billingStoreStub(env).fetch(new Request('https://billing.internal/telegram/webhook', {
                method: 'POST', headers, body: request.body, duplex: 'half', redirect: 'manual',
            }));
        } catch { return notReadyResponse(); }
    },
};
