import { validateOnboardingReplyPayload } from '../bot-onboarding.mjs';

export const BOT_PROXY_RESPONSE_BYTES = 8192;
export const BOT_PROXY_RESPONSE_TIMEOUT_MS = 2000;
const SECRET_HEADER = 'X-Telegram-Bot-Api-Secret-Token';
const INTERNAL_URL = 'https://billing.internal/telegram/webhook';
const JSON_TYPE = /^application\/json(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?\s*$/i;
const HEADERS = Object.freeze({
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
});
const ERRORS = new Map([
    [400, 'INVALID_BODY'], [403, 'FORBIDDEN'], [404, 'NOT_FOUND'],
    [408, 'INVALID_BODY'], [413, 'INVALID_BODY'],
    [415, 'UNSUPPORTED_MEDIA_TYPE'], [503, 'NOT_READY'],
]);
const response = (status, payload) => new Response(JSON.stringify(payload), { status, headers: HEADERS });
const unavailable = () => response(503, { error: 'NOT_READY' });
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

function cancelBody(body) {
    try { if (body) void body.cancel().catch(() => {}); } catch { /* no upstream detail reflected */ }
}

async function boundedResponseJson(upstream) {
    const reader = upstream.body.getReader();
    let timer;
    let complete = false;
    try {
        const read = async () => {
            const chunks = [];
            let length = 0;
            let emptyChunks = 0;
            for (;;) {
                const part = await reader.read();
                if (part.done) break;
                if (!(part.value instanceof Uint8Array)) throw new Error('INVALID_RESPONSE');
                if (part.value.byteLength === 0) {
                    if (++emptyChunks > 64) throw new Error('INVALID_RESPONSE');
                    continue;
                }
                length += part.value.byteLength;
                if (length > BOT_PROXY_RESPONSE_BYTES) throw new Error('INVALID_RESPONSE');
                chunks.push(part.value);
            }
            const bytes = new Uint8Array(length);
            let offset = 0;
            for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
            return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
        };
        const deadline = new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error('RESPONSE_TIMEOUT')), BOT_PROXY_RESPONSE_TIMEOUT_MS);
        });
        const payload = await Promise.race([read(), deadline]);
        complete = true;
        return payload;
    } finally {
        clearTimeout(timer);
        if (!complete) { try { void reader.cancel().catch(() => {}); } catch { /* bounded failure */ } }
        try { reader.releaseLock(); } catch { /* pending read is already part of Promise.race */ }
    }
}

/**
 * Informational webhook transport only. The Pages edge cannot authenticate the
 * secret: the private service must verify it BEFORE reading the forwarded body.
 * No bot token, request parsing, public destination, payment method or database.
 */
export async function proxyBotWebhook(request, env) {
    try {
        const url = new URL(request.url);
        if (request.method !== 'POST' || url.pathname !== '/telegram/webhook' || url.search !== '' || url.hash !== '') {
            return response(404, { error: 'NOT_FOUND' });
        }
        const secret = request.headers.get(SECRET_HEADER);
        if (typeof secret !== 'string' || secret.length < 32 || secret.length > 256 || /[^A-Za-z0-9_-]/.test(secret)) {
            return response(403, { error: 'FORBIDDEN' });
        }
        const contentType = request.headers.get('Content-Type');
        if (typeof contentType !== 'string' || !JSON_TYPE.test(contentType) || request.headers.get('Content-Encoding') !== null) {
            return response(415, { error: 'UNSUPPORTED_MEDIA_TYPE' });
        }
        if (typeof env?.BILLING_SERVICE?.fetch !== 'function') return unavailable();
        // Do not buffer an unauthenticated body. Only a streaming body and the
        // two required headers reach the fixed private service. Incoming cookies,
        // authorization, content length, query and source host are discarded.
        const forwarded = new Request(INTERNAL_URL, { method: 'POST', redirect: 'manual',
            headers: { [SECRET_HEADER]: secret, 'Content-Type': 'application/json' },
            body: request.body, duplex: 'half' });
        const upstream = await env.BILLING_SERVICE.fetch(forwarded);
        if (!upstream || (upstream.status !== 200 && !ERRORS.has(upstream.status)) || !upstream.body
            || !JSON_TYPE.test(upstream.headers.get('Content-Type') || '')
            || upstream.headers.get('Content-Encoding') !== null) {
            cancelBody(upstream?.body);
            return unavailable();
        }
        const payload = await boundedResponseJson(upstream);
        if (upstream.status === 200) {
            if (object(payload) && Object.keys(payload).length === 0) return response(200, {});
            const safeReply = validateOnboardingReplyPayload(payload);
            return safeReply ? response(200, safeReply) : unavailable();
        }
        const expected = ERRORS.get(upstream.status);
        return object(payload) && Object.keys(payload).length === 1 && Object.hasOwn(payload, 'error') && payload.error === expected
            ? response(upstream.status, { error: expected }) : unavailable();
    } catch {
        return unavailable();
    }
}
