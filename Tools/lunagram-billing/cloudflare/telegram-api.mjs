import { requireBilling } from '../catalog.mjs';

export const TELEGRAM_API_TIMEOUT_MS = 7000;
export const TELEGRAM_API_MAX_RESPONSE_BYTES = 64 * 1024;
export const TELEGRAM_WEBHOOK_URL = 'https://lunagram-billing.pages.dev/telegram/webhook';
const MAX_REQUEST_BYTES = 32 * 1024;
const METHODS = new Set(['sendMessage', 'sendInvoice', 'answerCallbackQuery', 'answerPreCheckoutQuery',
    'refundStarPayment', 'setWebhook', 'setMyCommands', 'getMe', 'getWebhookInfo']);
const BOOLEAN_METHODS = new Set(['answerCallbackQuery', 'answerPreCheckoutQuery', 'refundStarPayment', 'setWebhook', 'setMyCommands']);
const UPDATES = ['callback_query', 'message', 'pre_checkout_query'];
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const positiveId = value => Number.isSafeInteger(value) && value > 0 && value < 2 ** 52;
const privateId = value => positiveId(value) || typeof value === 'string'
    && /^[1-9][0-9]{0,15}$/.test(value) && BigInt(value) < 2n ** 52n;
const text = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max;
const opaque = value => text(value, 256) && !/[\u0000-\u0020\u007f]/.test(value);
const only = (value, keys) => Object.keys(value).every(key => keys.includes(key));
const fail = (error, retryable = false, retryAfterSeconds) => ({ ok: false, error, retryable,
    ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }) });

function requestBody(method, payload) {
    requireBilling(METHODS.has(method) && object(payload), 'INVALID_TELEGRAM_REQUEST');
    let json;
    try {
        json = JSON.stringify(payload, (_key, value) => {
            requireBilling(typeof value !== 'number' || Number.isFinite(value), 'INVALID_TELEGRAM_REQUEST');
            requireBilling(!['bigint', 'function', 'symbol', 'undefined'].includes(typeof value), 'INVALID_TELEGRAM_REQUEST');
            return value;
        });
    } catch { requireBilling(false, 'INVALID_TELEGRAM_REQUEST'); }
    requireBilling(typeof json === 'string' && new TextEncoder().encode(json).length <= MAX_REQUEST_BYTES, 'INVALID_TELEGRAM_REQUEST');
    // Validate the actual serialized data, not getters/toJSON supplied by a caller.
    const p = JSON.parse(json);
    requireBilling(object(p), 'INVALID_TELEGRAM_REQUEST');
    if (method === 'sendMessage' || method === 'sendInvoice') requireBilling(privateId(p.chat_id), 'INVALID_TELEGRAM_REQUEST');
    if (method === 'sendMessage') requireBilling(text(p.text, 4096), 'INVALID_TELEGRAM_REQUEST');
    if (method === 'sendInvoice') {
        requireBilling(text(p.title, 32) && text(p.description, 255) && text(p.payload, 128)
            && new TextEncoder().encode(p.payload).length <= 128 && p.currency === 'XTR'
            && (p.provider_token === undefined || p.provider_token === '') && p.subscription_period === undefined
            && Array.isArray(p.prices) && p.prices.length === 1 && object(p.prices[0])
            && text(p.prices[0].label, 256) && Number.isSafeInteger(p.prices[0].amount)
            && p.prices[0].amount > 0 && p.prices[0].amount <= 1_000_000, 'INVALID_TELEGRAM_REQUEST');
    }
    if (method === 'answerCallbackQuery') requireBilling(opaque(p.callback_query_id)
        && (p.text === undefined || text(p.text, 200)) && p.url === undefined, 'INVALID_TELEGRAM_REQUEST');
    if (method === 'answerPreCheckoutQuery') requireBilling(opaque(p.pre_checkout_query_id) && typeof p.ok === 'boolean'
        && (p.ok || text(p.error_message, 200)), 'INVALID_TELEGRAM_REQUEST');
    if (method === 'refundStarPayment') requireBilling(privateId(p.user_id) && opaque(p.telegram_payment_charge_id), 'INVALID_TELEGRAM_REQUEST');
    if (method === 'setWebhook') requireBilling(only(p, ['url', 'secret_token', 'max_connections', 'allowed_updates', 'ip_address'])
        && p.url === TELEGRAM_WEBHOOK_URL && typeof p.secret_token === 'string' && /^[A-Za-z0-9_-]{32,256}$/.test(p.secret_token)
        && (p.ip_address === undefined || ['172.66.47.192', '172.66.44.64'].includes(p.ip_address))
        && p.max_connections === 2 && Array.isArray(p.allowed_updates) && p.allowed_updates.length === 3
        && [...p.allowed_updates].sort().every((value, index) => value === UPDATES[index]), 'INVALID_TELEGRAM_REQUEST');
    if (method === 'setMyCommands') requireBilling(only(p, ['commands']) && Array.isArray(p.commands)
        && p.commands.length > 0 && p.commands.length <= 100
        && p.commands.every(command => object(command) && only(command, ['command', 'description'])
            && typeof command.command === 'string' && /^[a-z0-9_]{1,32}$/.test(command.command) && text(command.description, 256))
        && new Set(p.commands.map(command => command.command)).size === p.commands.length, 'INVALID_TELEGRAM_REQUEST');
    if (method === 'getMe' || method === 'getWebhookInfo') requireBilling(Object.keys(p).length === 0, 'INVALID_TELEGRAM_REQUEST');
    return { json, payload: p };
}

async function boundedJson(response, signal) {
    if (!response.body || !/^application\/json(?:\s*;.*)?$/i.test(response.headers.get('content-type') || '')) throw new Error('INVALID_RESPONSE');
    const length = response.headers.get('content-length');
    if (length !== null && (!/^[0-9]+$/.test(length) || Number(length) > TELEGRAM_API_MAX_RESPONSE_BYTES)) throw new Error('INVALID_RESPONSE');
    const reader = response.body.getReader();
    const cancel = () => { try { void reader.cancel().catch(() => {}); } catch { /* no reflected detail */ } };
    signal.addEventListener('abort', cancel, { once: true });
    let done = false;
    try {
        const chunks = [];
        let size = 0, empty = 0;
        for (;;) {
            if (signal.aborted) throw new Error('INVALID_RESPONSE');
            const part = await reader.read();
            if (part.done) break;
            if (!(part.value instanceof Uint8Array)) throw new Error('INVALID_RESPONSE');
            if (part.value.length === 0) {
                if (++empty > 64) throw new Error('INVALID_RESPONSE');
                continue;
            }
            size += part.value.length;
            if (size > TELEGRAM_API_MAX_RESPONSE_BYTES) throw new Error('INVALID_RESPONSE');
            chunks.push(part.value);
        }
        const bytes = new Uint8Array(size);
        let offset = 0;
        for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
        const result = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
        if (!object(result)) throw new Error('INVALID_RESPONSE');
        done = true;
        return result;
    } finally {
        signal.removeEventListener('abort', cancel);
        if (!done) cancel();
        try { reader.releaseLock(); } catch { /* aborted read */ }
    }
}

function safeSuccess(method, result, payload) {
    if (BOOLEAN_METHODS.has(method)) return result === true ? { ok: true, result: true } : fail('INVALID_RESPONSE');
    if (method === 'getMe') {
        return object(result) && result.is_bot === true && typeof result.username === 'string'
            && /^[A-Za-z][A-Za-z0-9_]{4,31}$/.test(result.username)
            ? { ok: true, result: { is_bot: true, username: result.username } } : fail('INVALID_RESPONSE');
    }
    if (method === 'getWebhookInfo') {
        if (!object(result) || typeof result.url !== 'string' || !Number.isSafeInteger(result.pending_update_count)
            || result.pending_update_count < 0) return fail('INVALID_RESPONSE');
        const output = { configured: result.url === TELEGRAM_WEBHOOK_URL,
            url: result.url === TELEGRAM_WEBHOOK_URL || result.url === '' ? result.url : null,
            pending_update_count: result.pending_update_count };
        if (Number.isSafeInteger(result.max_connections) && result.max_connections >= 1 && result.max_connections <= 100) {
            output.max_connections = result.max_connections;
        }
        if (Number.isSafeInteger(result.last_error_date)) {
            output.lastErrorAt = result.last_error_date;
            const message = typeof result.last_error_message === 'string' ? result.last_error_message.slice(0, 512) : '';
            const httpCode = /(?:response|HTTP).*?\b([45][0-9]{2})\b/i.exec(message)?.[1];
            output.deliveryError = httpCode ? `HTTP_${httpCode}` : /resolve|DNS/i.test(message) ? 'DNS'
                : /timeout|timed out/i.test(message) ? 'TIMEOUT' : /SSL|certificate/i.test(message) ? 'TLS' : 'UNKNOWN';
        }
        return { ok: true, result: output };
    }
    if (!object(result) || !positiveId(result.message_id) || !object(result.chat)
        || !positiveId(result.chat.id) || result.chat.type !== 'private'
        || String(result.chat.id) !== String(payload.chat_id)) return fail('INVALID_RESPONSE');
    // Never return echoed text/codes/invoice details, names, phone numbers, error
    // descriptions, response headers or arbitrary fields to the caller/log layer.
    return { ok: true, result: { message_id: result.message_id, chat: { id: result.chat.id, type: 'private' } } };
}

/** Server-only bounded transport. Callers authorize business actions beforehand.
 * An ambiguous send failure may already have delivered: outbox retries must keep
 * the same paid key/order. No automatic retry, log, or mutable public base URL.
 */
export function createTelegramApi(token, { fetchImpl = globalThis.fetch } = {}) {
    requireBilling(typeof token === 'string' && /^[0-9]{5,20}:[A-Za-z0-9_-]{20,128}$/.test(token)
        && typeof fetchImpl === 'function', 'NOT_CONFIGURED');
    return Object.freeze({
        async call(method, payload = {}) {
            const prepared = requestBody(method, payload);
            const controller = new AbortController();
            let timer, upstream;
            const deadline = new Promise(resolve => {
                timer = setTimeout(() => { controller.abort(); resolve(fail('TELEGRAM_UNAVAILABLE', true)); }, TELEGRAM_API_TIMEOUT_MS);
            });
            const run = async () => {
                try {
                    upstream = await fetchImpl(`https://api.telegram.org/bot${token}/${method}`, {
                        method: 'POST', redirect: 'manual', signal: controller.signal,
                        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' }, body: prepared.json,
                    });
                    if (controller.signal.aborted) {
                        try { if (upstream?.body) void upstream.body.cancel().catch(() => {}); } catch { /* bounded cancellation */ }
                        return fail('TELEGRAM_UNAVAILABLE', true);
                    }
                    if (!upstream || !Number.isInteger(upstream.status) || upstream.redirected
                        || upstream.status >= 300 && upstream.status < 400) return fail('INVALID_RESPONSE');
                    let response;
                    try { response = await boundedJson(upstream, controller.signal); }
                    catch {
                        return upstream.status === 429 ? fail('TELEGRAM_RATE_LIMITED', true)
                            : upstream.status >= 500 ? fail('TELEGRAM_UNAVAILABLE', true)
                                : upstream.status >= 400 ? fail('TELEGRAM_REJECTED') : fail('INVALID_RESPONSE', true);
                    }
                    const code = response.ok === false && Number.isSafeInteger(response.error_code) ? response.error_code : upstream.status;
                    if (upstream.status === 429 || code === 429) {
                        const delay = response.parameters?.retry_after;
                        return fail('TELEGRAM_RATE_LIMITED', true,
                            Number.isSafeInteger(delay) && delay >= 1 && delay <= 86400 ? delay : undefined);
                    }
                    if (upstream.status >= 500 || code >= 500) return fail('TELEGRAM_UNAVAILABLE', true);
                    if (upstream.status >= 400 || response.ok === false) return fail('TELEGRAM_REJECTED');
                    if (upstream.status !== 200 || response.ok !== true) return fail('INVALID_RESPONSE', true);
                    return safeSuccess(method, response.result, prepared.payload);
                } catch { return fail('TELEGRAM_UNAVAILABLE', true); }
            };
            try { return await Promise.race([run(), deadline]); }
            finally {
                clearTimeout(timer);
                // Cancellation is best-effort; a hostile/failed stream must never
                // keep the deadline promise or caller waiting for cancel().
                try { if (upstream?.body) void upstream.body.cancel().catch(() => {}); } catch { /* reader may hold the lock */ }
            }
        },
    });
}
