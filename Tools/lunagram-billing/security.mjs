import { createHash, createHmac, randomInt, timingSafeEqual } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { requireBilling, telegramUserId } from './catalog.mjs';

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
export const MAX_BODY_BYTES = 64 * 1024;

export function generateActivationCode() {
    // randomInt uses rejection sampling: no Math.random or modulo bias.
    return Array.from({ length: 16 }, () => ALPHABET[randomInt(ALPHABET.length)]).join('');
}
export function normalizeActivationCode(value) {
    requireBilling(typeof value === 'string' && value.length <= 64 && /^[A-Za-z0-9 -]*$/.test(value), 'INVALID_CODE');
    const code = value.replace(/[ -]/g, '').toUpperCase();
    requireBilling(/^[A-Z0-9]{16}$/.test(code), 'INVALID_CODE');
    return code;
}
export function activationCodeHash(code, pepper) {
    requireBilling(Buffer.isBuffer(pepper) && pepper.length >= 32, 'NOT_CONFIGURED');
    return createHmac('sha256', pepper).update(normalizeActivationCode(code), 'ascii').digest('hex');
}
function equalSecret(actual, expected) {
    if (typeof actual !== 'string' || typeof expected !== 'string' || actual.length > 512) return false;
    const a = Buffer.from(actual, 'utf8');
    const b = Buffer.from(expected, 'utf8');
    return a.length === b.length && timingSafeEqual(a, b);
}
export function parseBoundedJson(rawBody) {
    requireBilling(Buffer.isBuffer(rawBody) && rawBody.length <= MAX_BODY_BYTES, 'INVALID_BODY');
    let parsed;
    try { parsed = JSON.parse(rawBody.toString('utf8')); } catch { requireBilling(false, 'INVALID_BODY'); }
    requireBilling(parsed && typeof parsed === 'object' && !Array.isArray(parsed), 'INVALID_BODY');
    return parsed;
}
export function verifyCryptoPayWebhook(rawBody, signature, apiToken) {
    requireBilling(typeof apiToken === 'string' && apiToken.length >= 16, 'NOT_CONFIGURED');
    requireBilling(Buffer.isBuffer(rawBody) && rawBody.length <= MAX_BODY_BYTES, 'INVALID_BODY');
    requireBilling(typeof signature === 'string' && /^[0-9a-f]{64}$/i.test(signature), 'BAD_SIGNATURE');
    const key = createHash('sha256').update(apiToken).digest();
    const expected = createHmac('sha256', key).update(rawBody).digest();
    requireBilling(timingSafeEqual(Buffer.from(signature, 'hex'), expected), 'BAD_SIGNATURE');
    return parseBoundedJson(rawBody);
}

// This authenticates a Telegram update, not an arbitrary Android user_id.
// Consume updateId atomically in the durable repository before handling a command.
export function verifyPrivateBotMessage(rawBody, suppliedSecret, configuredSecret, nowMs) {
    const update = verifyBotUpdate(rawBody, suppliedSecret, configuredSecret);
    const message = update.message;
    const userId = privateBotSender(message);
    requireBilling(Number.isSafeInteger(message.date) && Number.isSafeInteger(nowMs)
        && message.date * 1000 <= nowMs + 30_000 && message.date * 1000 >= nowMs - 300_000, 'STALE_UPDATE');
    requireBilling(typeof message.text === 'string' && message.text.length <= 4096, 'INVALID_UPDATE');
    return Object.freeze({ updateId: update.update_id, telegramUserId: userId, text: message.text });
}
export function verifyBotUpdate(rawBody, suppliedSecret, configuredSecret) {
    requireBilling(typeof configuredSecret === 'string' && /^[A-Za-z0-9_-]{32,256}$/.test(configuredSecret), 'NOT_CONFIGURED');
    requireBilling(equalSecret(suppliedSecret, configuredSecret), 'BAD_SIGNATURE');
    const update = parseBoundedJson(rawBody);
    requireBilling(Number.isSafeInteger(update.update_id) && update.update_id >= 0, 'INVALID_UPDATE');
    return update;
}
export function privateBotSender(message) {
    requireBilling(message && message.chat?.type === 'private' && message.from?.is_bot === false,
        'PRIVATE_CHAT_REQUIRED');
    requireBilling(Number.isSafeInteger(message.from.id) && message.from.id > 0
        && message.chat.id === message.from.id, 'INVALID_UPDATE');
    return telegramUserId(String(message.from.id));
}
