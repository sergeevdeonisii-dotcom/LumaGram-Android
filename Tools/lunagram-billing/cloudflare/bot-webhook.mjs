import { Buffer } from 'node:buffer';
import { timingSafeEqual } from 'node:crypto';
import { validateOnboardingUpdate, buildOnboardingReply } from '../bot-onboarding.mjs';

// Informational-only integration; no payment routes or financial side effects.
// Requires nodejs_compat in workerd and a server-held Telegram webhook secret.
// repository must be durable and atomically implement:
//   claim(updateId, telegramUserId, nowMs)
//     -> 'claimed' | 'duplicate' | 'rate_limited'
// One transaction must deduplicate the update and consume durable per-user/global
// limits. Never use a per-request or in-memory repository in a deployed worker.
//
// Telegram supports method calls in the webhook response:
// https://core.telegram.org/bots/api#making-requests-when-getting-updates
// Telegram does NOT return their execution result. Claim-before-response gives
// only at-most-once informational reply attempts, NOT reliable delivery: a lost
// response is not replayed; the user can send the command again. Do not use this
// pattern for paid keys, entitlements, invoices, or other financial operations.
// There is no Bot API token, outgoing fetch, payment handler, session, or logging.
export const MAX_WEBHOOK_BODY_BYTES = 32 * 1024;
export const WEBHOOK_BODY_TIMEOUT_MS = 2000;
const SECRET_HEADER = 'X-Telegram-Bot-Api-Secret-Token';
const RESPONSE_HEADERS = Object.freeze({
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
});

const response = (status, body = {}) => new Response(JSON.stringify(body), { status, headers: RESPONSE_HEADERS });
const validSecret = secret => typeof secret === 'string' && secret.length >= 32 && secret.length <= 256
    && !/[^A-Za-z0-9_-]/.test(secret);
const validBotUsername = name => typeof name === 'string' && name.length >= 5 && name.length <= 32
    && /^[A-Za-z]/.test(name) && !/[^A-Za-z0-9_]/.test(name);

function matchesSecret(supplied, expected) {
    if (typeof supplied !== 'string' || supplied.length > 256 || /[^A-Za-z0-9_-]/.test(supplied)) return false;
    // Fixed-size comparison includes length, so no length-dependent early return
    // or silent zero-padding equivalence occurs for otherwise valid header text.
    const actualBytes = Buffer.alloc(258);
    const expectedBytes = Buffer.alloc(258);
    actualBytes.writeUInt16BE(supplied.length);
    expectedBytes.writeUInt16BE(expected.length);
    actualBytes.write(supplied, 2, 'ascii');
    expectedBytes.write(expected, 2, 'ascii');
    return timingSafeEqual(actualBytes, expectedBytes);
}

class BodyError extends Error {
    constructor(status) { super('Invalid webhook body'); this.status = status; }
}

export async function readJson(request) {
    // Content-Length is deliberately not used for admission or allocation.
    // The actual decoded HTTP body is counted while streaming, including when
    // the sender omits or lies about its Content-Length.
    if (!request.body) throw new BodyError(400);
    const reader = request.body.getReader();
    let timer;
    let succeeded = false;
    try {
        const read = async () => {
            const chunks = [];
            let length = 0;
            let emptyChunks = 0;
            for (;;) {
                const part = await reader.read();
                if (part.done) break;
                if (!(part.value instanceof Uint8Array)) throw new BodyError(400);
                if (part.value.byteLength === 0) {
                    if (++emptyChunks > 64) throw new BodyError(400);
                    continue;
                }
                length += part.value.byteLength;
                if (length > MAX_WEBHOOK_BODY_BYTES) throw new BodyError(413);
                chunks.push(part.value);
            }
            const bytes = new Uint8Array(length);
            let offset = 0;
            for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
            try {
                return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
            } catch { throw new BodyError(400); }
        };
        const deadline = new Promise((_, reject) => {
            timer = setTimeout(() => reject(new BodyError(408)), WEBHOOK_BODY_TIMEOUT_MS);
        });
        const result = await Promise.race([read(), deadline]);
        succeeded = true;
        return result;
    } finally {
        clearTimeout(timer);
        // Do not await an uncooperative sender's cancel promise. The reader's
        // pending read belongs to Promise.race and cannot become an unhandled
        // rejection after a timeout. releaseLock may reject that pending read.
        if (!succeeded) { try { void reader.cancel().catch(() => {}); } catch { /* no reflected error */ } }
        try { reader.releaseLock(); } catch { /* no reflected error */ }
    }
}

// Synchronous, header-only edge admission: null means this request may be
// forwarded, a Response means reject it. Never reads body/clock/repository.
// This does not validate a Telegram update or reserve a reply. The receiving DO
// must still call handleBotWebhook(), which repeats these checks independently.
export function webhookAdmission(request, options = {}) {
    try {
        const url = new URL(request.url);
        if (request.method !== 'POST' || url.pathname !== '/telegram/webhook'
            || url.search !== '' || url.hash !== '') return response(404, { error: 'NOT_FOUND' });
        const { webhookSecret, botUsername } = options;
        if (!validSecret(webhookSecret) || !validBotUsername(botUsername)) return response(503, { error: 'NOT_READY' });
        if (!matchesSecret(request.headers.get(SECRET_HEADER), webhookSecret)) return response(403, { error: 'FORBIDDEN' });
        // Nothing above has touched the body or repository. JSON only; compressed
        // bodies are intentionally unsupported, avoiding ambiguous size bounds.
        const type = request.headers.get('Content-Type');
        if (typeof type !== 'string'
            || !/^application\/json(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?\s*$/i.test(type)
            || request.headers.get('Content-Encoding') !== null) return response(415, { error: 'UNSUPPORTED_MEDIA_TYPE' });
        return null;
    } catch {
        return response(503, { error: 'NOT_READY' });
    }
}

export function createBotWebhookHandler(options) {
    return request => handleBotWebhook(request, options);
}

export async function handleBotWebhook(request, options = {}) {
    try {
        const admission = webhookAdmission(request, options);
        if (admission) return admission;
        let update;
        try { update = await readJson(request); }
        catch (error) { return response(error instanceof BodyError ? error.status : 400, { error: 'INVALID_BODY' }); }
        const nowMs = (options.clock ?? Date.now)();
        if (!Number.isSafeInteger(nowMs) || nowMs < 0) return response(503, { error: 'NOT_READY' });
        const command = validateOnboardingUpdate(update, { botUsername: options.botUsername, nowMs });
        if (!command) return response(200);
        const reply = buildOnboardingReply(command);
        if (!reply) return response(503, { error: 'NOT_READY' });
        const repository = options.repository;
        if (repository?.durable !== true || typeof repository.claim !== 'function') return response(503, { error: 'NOT_READY' });
        const result = await repository.claim(command.updateId, command.telegramUserId, nowMs);
        if (result === 'duplicate' || result === 'rate_limited') return response(200);
        if (result !== 'claimed') return response(503, { error: 'NOT_READY' });
        return response(200, reply);
    } catch {
        // No stack, secret, incoming text or storage/provider details escape.
        return response(503, { error: 'NOT_READY' });
    }
}
