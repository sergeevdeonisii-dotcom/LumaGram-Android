import { readPublicReadiness, notReadyResponse, publicPath, publicResponse } from '../../public-http.mjs';
import { isBotWebhookRequest } from '../../bot-route.mjs';
import { proxyBotWebhook } from '../../bot-proxy.mjs';
import { appPath, proxyAppRequest } from '../../live-http.mjs';

export default {
    async fetch(request, env) {
        if (isBotWebhookRequest(request)) return proxyBotWebhook(request, env);
        if (appPath(request)) return proxyAppRequest(request, env);
        const path = publicPath(request);
        if (!path) return notReadyResponse();
        try {
            if (typeof env.BILLING_SERVICE?.fetch !== 'function') return notReadyResponse();
            // Public GETs only: never use global fetch or forward user headers,
            // queries, cookies, authorization, request bodies or source hostnames.
            // 'manual' works on workerd variants that reject redirect:'error'.
            // The response validator below rejects every 3xx; none are followed.
            const boundRequest = new Request(`https://billing.internal${path}`,
                { method: 'GET', redirect: 'manual', headers: { Accept: 'application/json' } });
            const upstream = await env.BILLING_SERVICE.fetch(boundRequest);
            const readiness = await readPublicReadiness(upstream, path);
            return readiness === null ? notReadyResponse() : publicResponse(path, readiness);
        } catch {
            // No stack/body/secret logging or error reflection.
            return notReadyResponse();
        }
    },
};
