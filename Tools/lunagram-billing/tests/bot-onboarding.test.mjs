import test from 'node:test';
import assert from 'node:assert/strict';
import { CATALOG } from '../catalog.mjs';
import { validateOnboardingUpdate, buildOnboardingReply } from '../bot-onboarding.mjs';

const nowMs = 1_800_000_000_000;
const options = { nowMs, botUsername: 'LunagramTestBot' };
function update(text = '/start') {
    return {
        update_id: 42,
        message: {
            message_id: 11,
            date: nowMs / 1000,
            from: { id: 123456789, is_bot: false, first_name: '<untrusted>' },
            chat: { id: 123456789, type: 'private' },
            text,
            entities: [{ type: 'bot_command', offset: 0, length: text.split(' ')[0].length }],
        },
    };
}
function reply(text) {
    return buildOnboardingReply(validateOnboardingUpdate(update(text), options));
}
function reject(value, opts = options) {
    assert.equal(validateOnboardingUpdate(value, opts), null);
}

test('four private commands return only truthful plain-text sendMessage payloads', () => {
    for (const command of ['start', 'help', 'plans', 'paysupport']) {
        const result = reply(`/${command}`);
        assert.equal(result.method, 'sendMessage');
        assert.equal(result.chat_id, '123456789');
        assert.match(result.text, /пока не запущены/);
        assert.match(result.text, /Все функции остаются доступны без подписки/);
        assert.equal(result.parse_mode, undefined);
        assert.equal(result.entities, undefined);
        assert.equal(result.reply_markup, undefined);
        assert.deepEqual(result.link_preview_options, { is_disabled: true });
        assert.ok(!result.text.includes('<untrusted>'));
        assert.ok(Object.isFrozen(result));
        assert.ok(Object.isFrozen(result.link_preview_options));
    }
});

test('plans use owner-approved rounded Star tariffs with manual renewal and unavailable checkout', () => {
    const result = reply('/plans');
    for (const [index, expected] of [40, 70, 100, 140, 360].entries()) {
        assert.ok(result.text.includes(`${CATALOG[index].title} — ${expected} ⭐ в месяц`));
    }
    assert.match(result.text, /ручное продление каждый месяц, без автоматических списаний/);
    assert.match(result.text, /Оплата пока недоступна/);
    assert.doesNotMatch(result.text, /https?:|ton:|Crypto|USDT|кошел|Купить/);
});

test('support does not invent a contact or pretend to accept payments', () => {
    const result = reply('/paysupport');
    assert.match(result.text, /не принимает платежи и не выдаёт ключи/);
    assert.doesNotMatch(result.text, /@|https?:|ton:/);
});

test('start deep-link payload is bounded and discarded without activation or echo', () => {
    const plain = reply('/start');
    for (const payload of ['KEY_0123-abc', 'A'.repeat(64)]) {
        const validated = validateOnboardingUpdate(update(`/start ${payload}`), options);
        assert.deepEqual(Object.keys(validated), ['updateId', 'telegramUserId', 'command']);
        assert.deepEqual(buildOnboardingReply(validated), plain);
        assert.ok(Object.isFrozen(validated));
    }
    for (const text of ['/start ' + 'A'.repeat(65), '/start hello world', '/start ', '/start  key',
        '/start\nkey', '/start\tkey', '/start <key>', '/start ключ', '/plans payload']) reject(update(text));
});

test('optional bot suffix must match configured username, case-insensitively', () => {
    assert.deepEqual(reply('/help@LunagramTestBot'), reply('/help'));
    assert.deepEqual(reply('/help@lunagramtestbot'), reply('/help'));
    reject(update('/help@DifferentBot'));
    reject(update('/help@LunagramTestBot'), { nowMs });
    reject(update('/help@LunagramTestBot'), { nowMs, botUsername: '@LunagramTestBot' });
    assert.deepEqual(reply('/start@LunagramTestBot token'), reply('/start'));
});

test('invalid, unknown, non-command, extra-argument and overlong inputs are ignored', () => {
    for (const value of [null, [], {}, 1, 'data', { telegramUserId: '123456789', command: 'start' }]) reject(value);
    for (const text of ['', 'hello', ' /start', '/START', '/start\n', '/buy', '/redeem',
        '/help more', '/paysupport x', '/start ' + 'A'.repeat(10000)]) {
        const value = update(text);
        reject(value);
        delete value.message.entities;
        reject(value);
    }
    for (const text of [undefined, null, 10, {}]) {
        const value = update(); value.message.text = text; reject(value);
    }
});

test('groups, bot senders, mismatched IDs and client-claimed identities are ignored', () => {
    for (const change of [
        v => { v.message.chat.type = 'group'; },
        v => { v.message.chat.type = 'supergroup'; },
        v => { v.message.chat.type = 'channel'; },
        v => { v.message.from.is_bot = true; },
        v => { delete v.message.from.is_bot; },
        v => { v.message.chat.id++; },
        v => { v.message.from.id = '123456789'; },
        v => { v.message.from.id = v.message.chat.id = 2 ** 52; },
        v => { v.message.from.id = v.message.chat.id = -1; },
        v => { v.message.from.id = v.message.chat.id = 0; },
        v => { v.message.from.id = v.message.chat.id = 1.5; },
        v => { delete v.message.from; },
        v => { delete v.message.chat; },
    ]) {
        const value = update(); change(value); reject(value);
    }
    const value = update();
    value.message.telegramUserId = '666';
    value.message.from.username = 'misleading';
    assert.equal(buildOnboardingReply(validateOnboardingUpdate(value, options)).chat_id, '123456789');
});

test('edited, payment, callback, business and mixed updates are never commands', () => {
    for (const field of ['edited_message', 'channel_post', 'edited_channel_post', 'callback_query',
        'pre_checkout_query', 'shipping_query', 'business_message', 'deleted_business_messages']) {
        const value = update(); value[field] = {}; reject(value);
        delete value.message; reject(value);
    }
    for (const field of ['successful_payment', 'refunded_payment', 'invoice', 'edit_date',
        'business_connection_id', 'sender_business_bot', 'via_bot']) {
        const value = update(); value.message[field] = {}; reject(value);
    }
});

test('entity validation rejects forged command offsets/types/lengths and excessive entities', () => {
    for (const entities of [null, {}, [], [{ type: 'mention', offset: 0, length: 6 }],
        [{ type: 'bot_command', offset: 1, length: 6 }],
        [{ type: 'bot_command', offset: 0, length: 5 }],
        [{ type: 'bot_command', offset: 0, length: 6 }, {}]]) {
        const value = update(); value.message.entities = entities; reject(value);
    }
    const plain = update(); delete plain.message.entities;
    assert.deepEqual(buildOnboardingReply(validateOnboardingUpdate(plain, options)), reply('/start'));
});

test('metadata and stale/future command timestamps are bounded', () => {
    for (const id of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1, '42', undefined]) {
        const value = update(); value.update_id = id; reject(value);
    }
    for (const id of [0, -1, 1.5, '11', undefined]) {
        const value = update(); value.message.message_id = id; reject(value);
    }
    for (const date of [nowMs / 1000 - 301, nowMs / 1000 + 31, -1, 1.5, '1800000000', Number.MAX_SAFE_INTEGER]) {
        const value = update(); value.message.date = date; reject(value);
    }
    for (const invalidNow of [-1, NaN, Infinity, '1800000000000']) reject(update(), { ...options, nowMs: invalidNow });
    for (const offset of [-300, 30]) {
        const value = update(); value.message.date += offset;
        assert.ok(validateOnboardingUpdate(value, options));
    }
});

test('reply builder denies fabricated or cloned validation results', () => {
    for (const value of [null, {}, [], { updateId: 42, telegramUserId: '666', command: 'start' }]) {
        assert.equal(buildOnboardingReply(value), null);
    }
    const validated = validateOnboardingUpdate(update(), options);
    assert.equal(buildOnboardingReply({ ...validated }), null);
    assert.equal(buildOnboardingReply(JSON.parse(JSON.stringify(validated))), null);
    assert.throws(() => { validated.telegramUserId = '666'; }, TypeError);
    assert.equal(buildOnboardingReply(validated).chat_id, '123456789');
});
