import { CATALOG } from './catalog.mjs';
import { STARS_TARIFFS } from './stars-tariffs.mjs';

// Server-only informational handler, not an HTTP endpoint or authentication layer.
// Integration order: verify the Telegram webhook secret and bounded JSON body,
// validateOnboardingUpdate(), then durable update-id deduplication / rate limiting
// and an outbound outbox before sending buildOnboardingReply() with the Bot API.
// Never expose this validator to client-claimed Telegram identities. This module
// neither sends requests nor accepts payments, issues codes, or creates sessions.
// Only its own validation results are accepted by the reply builder. This brand
// prevents accidental shape confusion; it does NOT replace webhook verification.
const validatedCommands = new WeakSet();
const COMMANDS = new Set(['start', 'help', 'plans', 'paysupport']);
const BOT_USERNAME = /^[A-Za-z][A-Za-z0-9_]{4,31}$/;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const positiveId = value => Number.isSafeInteger(value) && value > 0 && value < 2 ** 52;

/** Input must already come from a secret-verified Telegram webhook, not an app. */
export function validateOnboardingUpdate(update, { botUsername, nowMs = Date.now() } = {}) {
    if (!object(update) || !Number.isSafeInteger(update.update_id) || update.update_id < 0
        || Object.keys(update).some(key => key !== 'update_id' && key !== 'message')) return null;
    const message = update.message;
    if (!object(message) || !object(message.from) || !object(message.chat)
        || !positiveId(message.from.id) || message.from.is_bot !== false
        || message.chat.type !== 'private' || message.chat.id !== message.from.id
        || !Number.isSafeInteger(message.message_id) || message.message_id <= 0) return null;
    // Payment/service updates must be handled separately, never mistaken for a
    // command. Edited and business messages are intentionally unsupported here.
    for (const field of ['successful_payment', 'refunded_payment', 'invoice', 'edit_date',
        'business_connection_id', 'sender_business_bot', 'via_bot']) {
        if (Object.hasOwn(message, field)) return null;
    }
    if (!Number.isSafeInteger(nowMs) || nowMs < 0 || !Number.isSafeInteger(message.date)
        || message.date < 0 || !Number.isSafeInteger(message.date * 1000)
        || message.date * 1000 < nowMs - 300_000 || message.date * 1000 > nowMs + 30_000
        || typeof message.text !== 'string' || message.text.length > 128) return null;
    const match = /^\/([a-z]{1,32})(?:@([A-Za-z][A-Za-z0-9_]{4,31}))?(?: ([A-Za-z0-9_-]{1,64}))?$/.exec(message.text);
    if (!match || match[0] !== message.text || !COMMANDS.has(match[1]) || (match[3] && match[1] !== 'start')) return null;
    if (match[2] && (typeof botUsername !== 'string' || !BOT_USERNAME.test(botUsername)
        || match[2].toLowerCase() !== botUsername.toLowerCase())) return null;
    // Telegram normally provides this entity. Plain command text is also safe;
    // if entities are present, require exactly the matching initial command.
    if (message.entities !== undefined) {
        const commandLength = message.text.indexOf(' ') < 0 ? message.text.length : message.text.indexOf(' ');
        if (!Array.isArray(message.entities) || message.entities.length !== 1
            || message.entities[0]?.type !== 'bot_command' || message.entities[0]?.offset !== 0
            || message.entities[0]?.length !== commandLength) return null;
    }
    const validated = Object.freeze({
        updateId: update.update_id,
        telegramUserId: String(message.from.id),
        command: match[1],
    });
    // The optional /start deep-link payload is deliberately discarded, not
    // echoed, redeemed, or interpreted as authentication / a payment reference.
    validatedCommands.add(validated);
    return validated;
}

const STATUS = 'Подписки Lunagram пока не запущены. Все функции остаются доступны без подписки.';
const RENEWAL = 'Планируется ручное продление каждый месяц, без автоматических списаний.';
const HELP = 'Команды:\n/start — о боте\n/help — помощь\n/plans — тарифы\n/paysupport — информация об оплате';

function onboardingText(command) {
    let text;
    switch (command) {
        case 'start':
            text = `Lunagram\n\n${STATUS}\n\n${RENEWAL}\n\nТарифы можно посмотреть командой /plans. Помощь — /help.`;
            break;
        case 'help':
            text = `${STATUS}\n\n${HELP}\n\n${RENEWAL}`;
            break;
        case 'plans':
            text = `Тарифы Lunagram\n\n${CATALOG.map(plan => `${plan.title} — ${STARS_TARIFFS[plan.id]} ⭐ в месяц`).join('\n')}\n\n${STATUS}\n\n${RENEWAL}\n\nОплата пока недоступна: готовим активацию и выдачу ключей.`;
            break;
        case 'paysupport':
            text = 'Оплата подписок Lunagram пока недоступна. Этот бот не принимает платежи и не выдаёт ключи.\n\n' + STATUS;
            break;
        default:
            return null;
    }
    return text;
}

/** Returns a plain-text Bot API method payload or null; performs no I/O. */
export function buildOnboardingReply(command) {
    if (!object(command) || !validatedCommands.has(command)) return null;
    const text = onboardingText(command.command);
    if (!text) return null;
    return Object.freeze({
        method: 'sendMessage',
        chat_id: command.telegramUserId,
        text,
        link_preview_options: Object.freeze({ is_disabled: true }),
    });
}

const allowedReplyTexts = new Set([...COMMANDS].map(onboardingText));

/**
 * Response allowlist for the private-service proxy, NOT sender authentication.
 * The proxy must obtain the payload only from its fixed internal service binding.
 * Never use this helper to accept client-claimed commands or Telegram identities.
 */
export function validateOnboardingReplyPayload(value) {
    if (!object(value) || Object.keys(value).length !== 4
        || !['method', 'chat_id', 'text', 'link_preview_options'].every(key => Object.hasOwn(value, key))
        || value.method !== 'sendMessage' || typeof value.chat_id !== 'string'
        || !/^[1-9][0-9]{0,15}$/.test(value.chat_id) || BigInt(value.chat_id) >= 2n ** 52n
        || typeof value.text !== 'string' || !allowedReplyTexts.has(value.text)
        || !object(value.link_preview_options) || Object.keys(value.link_preview_options).length !== 1
        || !Object.hasOwn(value.link_preview_options, 'is_disabled') || value.link_preview_options.is_disabled !== true) return null;
    return Object.freeze({ method: 'sendMessage', chat_id: value.chat_id, text: value.text,
        link_preview_options: Object.freeze({ is_disabled: true }) });
}
