import { LIVE_PLANS } from './live-store.mjs';
import { readJson, webhookAdmission } from './bot-webhook.mjs';
import { jsonResponse } from './live-http.mjs';
import { TERMS_VERSION } from './live-control.mjs';

const id = value => Number.isSafeInteger(value) && value > 0 && value < 2 ** 52 ? String(value) : null;
const bounded = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max;
const keyboard = buttons => ({ inline_keyboard: buttons.map(([text, callback_data]) => [{ text, callback_data }]) });
const PLAN_BUTTONS = keyboard(LIVE_PLANS.map(p => [`${p.title} · ${p.starsAmount} ⭐`, `buy_${p.id}`]));
const orderLines = orders => orders.map(o => `${o.planId} · ${o.starsAmount} ⭐ · ${o.status} · ${new Date(o.createdAtMs).toISOString().slice(0, 10)} UTC\n/refund ${o.id}`).join('\n\n');
const PLAN_TEXT = 'Lunagram · подписки на месяц\n\n' + LIVE_PLANS.map(p => `${p.title} — ${p.starsAmount} ⭐`).join('\n')
    + '\n\nРучное продление, без автосписаний. Все функции пока доступны без подписки. После оплаты вы получите одноразовый ключ из 16 символов.';
export const TERMS_TEXT = 'Условия Lunagram\n\nПодписка добровольная: функции сейчас не ограничены и тарифы не дают разных возможностей. '
    + 'Оплата разовая, только Stars. Автосписаний нет. Оплата выдаёт один одноразовый ключ; месяц начинается после активации, '
    + 'при продлении добавляется к оставшемуся сроку. Ключ можно передать другому человеку до активации — храните его в секрете. '
    + 'Lunagram — независимый клиент, не Telegram Premium.\n\nВопросы по оплате и запросы на возврат: /paysupport в этом боте. '
    + 'Для обработки платежей храним идентификатор Telegram, сведения о покупке, согласие с условиями и обращения. '
    + 'Ключи и обращения зашифрованы на сервере. Личный аккаунт автора покупателям не раскрывается.';
const SAFE_ERRORS = new Map([
    ['RATE_LIMITED', 'Слишком много запросов. Подождите минуту и повторите.'],
    ['PAIRING_NOT_AVAILABLE', 'Ссылка подключения истекла или относится к другому аккаунту. Начните подключение заново в Lunagram.'],
    ['INVALID_CODE', 'Ключ не найден. Проверьте 16 букв и цифр.'],
    ['CODE_ALREADY_USED', 'Этот ключ уже активирован другим аккаунтом.'],
    ['CODE_UNAVAILABLE', 'Ключ недоступен. Напишите /paysupport.'],
    ['TERMS_REQUIRED', 'Перед покупкой примите условия: /plans.'],
    ['TICKET_NOT_FOUND', 'Обращение не найдено.'],
    ['ORDER_NOT_FOUND', 'Заказ не найден.'],
    ['PAYMENT_NOT_FOUND', 'Оплаченный заказ не найден.'],
    ['FORBIDDEN', 'Действие недоступно.'],
]);

export async function dispatchLiveUpdate(update, { store, auth, control, api, clock = Date.now }) {
    if (!update || !Number.isSafeInteger(update.update_id) || update.update_id < 0) return;
    const now = clock();
    const requestKey = `update:${update.update_id}`;
    const pre = update.pre_checkout_query;
    if (pre) {
        const userId = id(pre.from?.id);
        if (!userId || pre.from?.is_bot !== false || !bounded(pre.id, 256) || !bounded(pre.invoice_payload, 128)) return;
        const result = control.paymentsReady() ? store.reservePreCheckout({ userId, orderId: pre.invoice_payload,
            currency: pre.currency, totalAmount: pre.total_amount, queryId: pre.id }) : { ok: false };
        const answer = await api.call('answerPreCheckoutQuery', { pre_checkout_query_id: pre.id, ok: result.ok,
            ...(!result.ok ? { error_message: 'Этот счёт сейчас недоступен. Откройте /plans и создайте новый. Списания не будет.' } : {}) });
        if (!answer.ok) throw new Error('PRECHECKOUT_DELIVERY_FAILED');
        return;
    }
    const message = update.message;
    const callback = update.callback_query;
    const sender = callback?.from ?? message?.from;
    const userId = id(sender?.id);
    const chat = callback?.message?.chat ?? message?.chat;
    if (!userId || sender?.is_bot !== false || chat?.type !== 'private' || id(chat.id) !== userId) return;
    // Financial receipts are allowed to arrive late after retries; commit first,
    // then webhook ACK. Never use conversational dedup claims for paid updates.
    const paid = message?.successful_payment;
    if (paid) {
        if (!bounded(paid.invoice_payload, 128) || !bounded(paid.telegram_payment_charge_id, 512)
            || !Number.isSafeInteger(paid.total_amount) || paid.total_amount <= 0) return;
        store.recordPayment({ orderId: paid.invoice_payload, userId, currency: paid.currency,
            totalAmount: paid.total_amount, chargeId: paid.telegram_payment_charge_id,
            recurring: paid.is_recurring === true || paid.is_first_recurring === true });
        return;
    }
    const refunded = message?.refunded_payment;
    if (refunded) {
        if (!bounded(refunded.invoice_payload, 128) || !bounded(refunded.telegram_payment_charge_id, 512)) return;
        store.recordRefund({ orderId: refunded.invoice_payload, userId, currency: refunded.currency,
            totalAmount: refunded.total_amount, chargeId: refunded.telegram_payment_charge_id });
        return;
    }
    if (!callback && (!Number.isSafeInteger(message?.date) || message.date * 1000 > now + 30_000
        || message.date * 1000 < now - 24 * 3600_000)) return;
    if (callback && (!bounded(callback.id, 256) || !bounded(callback.data, 64))) return;
    control.rate('all', 'bot-global', 300, now);
    control.rate(userId, 'bot-user', 30, now);
    if (store.hasReply(userId, requestKey)) return;
    const reply = (text, markup) => store.enqueueMessage(userId, text, requestKey, markup);
    const sendTerms = plan => reply(TERMS_TEXT, keyboard([['Принимаю условия', plan ? `accept_${plan}` : 'accept_terms']]));
    const buy = plan => {
        if (!LIVE_PLANS.some(p => p.id === plan)) return reply('Выберите тариф: /plans.');
        if (!control.paymentsReady()) return reply('Продажа подписок ещё не открыта. Средства не списываются.');
        if (!store.getConsent(userId).accepted) return sendTerms(plan);
        store.createOrder(userId, plan, requestKey);
    };
    try {
        if (callback) {
            // Stop the spinner best-effort. Business effects below are durable and
            // idempotent even if a Telegram callback acknowledgement gets lost.
            await api.call('answerCallbackQuery', { callback_query_id: callback.id }).catch(() => {});
            const data = callback.data;
            if (data.startsWith('buy_')) return buy(data.slice(4));
            if (data.startsWith('accept_')) {
                const plan = data.slice(7);
                if (plan !== 'terms' && !LIVE_PLANS.some(p => p.id === plan)) return;
                store.acceptTerms(userId, TERMS_VERSION);
                return plan === 'terms' ? reply('Условия приняты. Выберите тариф: /plans.') : buy(plan);
            }
            if (/^pair_[A-Za-z0-9_-]{43}$/.test(data)) {
                auth.approve(data.slice(5), userId, now);
                return reply('Аккаунт подключён. Вернитесь в Lunagram — подписка и активация ключей будут доступны там.');
            }
            if (/^owner_[A-Za-z0-9_-]{43}$/.test(data)) {
                control.claimOwner(data.slice(6), userId, now);
                return reply('Вы назначены владельцем поддержки Lunagram. /tickets — обращения, /ticket ID — просмотр, /reply ID текст — ответ. Личный аккаунт покупателям не раскрывается.');
            }
            if (/^refund_[a-f0-9]{48}$/.test(data) && control.isOwner(userId)) {
                const orderId = data.slice(7);
                const refund = store.prepareRefund(orderId);
                if (refund.status === 'refunded') return reply('Возврат уже выполнен.');
                const result = await api.call('refundStarPayment', { user_id: Number(refund.userId), telegram_payment_charge_id: refund.chargeId });
                if (result.ok) { store.recordRefund({ chargeId: refund.chargeId }); return reply('Stars возвращены. Ключ и соответствующий месяц подписки отозваны.'); }
                return reply('Результат возврата не подтверждён. Ключ заблокирован до сверки. Повторите /refund с тем же номером заказа; новый платёж не создаётся.');
            }
            if (/^review_[a-f0-9]{48}$/.test(data) && control.isOwner(userId)) {
                const refund = store.prepareReviewRefund(data.slice(7));
                if (refund.status === 'refunded') return reply('Возврат уже выполнен.');
                const result = await api.call('refundStarPayment', { user_id: Number(refund.userId), telegram_payment_charge_id: refund.chargeId });
                if (result.ok) { store.recordRefund({ chargeId: refund.chargeId }); return reply('Платёж на проверке возвращён покупателю.'); }
                return reply('Результат возврата не подтверждён. Запись сохранена для повторной сверки: /reviews.');
            }
            return;
        }
        const text = message.text;
        if (!bounded(text, 2500)) return;
        const match = /^\/([a-z]+)(?:@LunagramAppPayBot)?(?:\s+([\s\S]*))?$/i.exec(text);
        if (!match) return reply('Команды: /plans — тарифы, /keys — ключи, /status — подписка, /paysupport — помощь с оплатой.');
        const command = match[1].toLowerCase(); const arg = (match[2] || '').trim();
        if (command === 'start' && /^pair_[A-Za-z0-9_-]{43}$/.test(arg)) {
            auth.pending(arg.slice(5), userId, now);
            return reply('Подключить этот Telegram-аккаунт к подписке в Lunagram? Подтверждайте, только если сами нажали «Подключить» в приложении. Если не запрашивали — проигнорируйте.', keyboard([['Подключить мой аккаунт', arg]]));
        }
        if (command === 'start' && /^owner_[A-Za-z0-9_-]{43}$/.test(arg)) {
            if (!control.canClaim(arg.slice(6), now)) return reply('Ссылка администратора недоступна или истекла.');
            return reply('Эта одноразовая ссылка назначает ваш аккаунт владельцем поддержки Lunagram. Вы сможете читать обращения, отвечать и выполнять возвраты. Подтвердите только если вы владелец проекта.', keyboard([['Я владелец, подключить поддержку', arg]]));
        }
        if (command === 'start' && arg.startsWith('buy_')) return buy(arg.slice(4));
        if (command === 'buy') return buy(arg.toLowerCase());
        if (command === 'start' || command === 'plans') return reply(PLAN_TEXT, PLAN_BUTTONS);
        if (command === 'terms') return sendTerms();
        if (command === 'keys') return store.enqueueKeys(userId, requestKey);
        if (command === 'status' || command === 'redeem') {
            const status = command === 'redeem' ? store.redeem(userId, arg) : store.getSubscription(userId, now);
            return reply(status.active ? `Подписка ${status.planId} активна до ${new Date(status.expiresAtMs).toISOString().slice(0, 10)}. Все функции пока доступны без ограничений.`
                : 'Активной подписки нет. Ключ можно активировать в настройках Lunagram или командой /redeem КЛЮЧ.');
        }
        if (command === 'paysupport' || command === 'help') return reply('Поддержка работает прямо здесь. Отправьте /support и описание проблемы одним сообщением. Для возврата укажите номер заказа из сообщения с ключом или /keys; если не нашли — дату и тариф. Сам ключ, пароль, код входа, seed-фразу или банковские данные не присылайте.');
        if (command === 'support') {
            if (!arg || arg.length > 1500) return reply('Отправьте /support описание проблемы (до 1500 символов). Не отправляйте пароли и коды входа.');
            const ticket = store.createSupportTicket(userId, arg, requestKey);
            return reply(`Обращение #${ticket.id} сохранено. Ответ придёт в этот бот.`);
        }
        if (['tickets', 'ticket', 'orders', 'reply', 'refund', 'reviews', 'refundreview'].includes(command)) {
            if (!control.isOwner(userId)) return reply('Команда доступна только владельцу поддержки.');
            if (command === 'reviews') {
                const reviews = store.listPaymentReviews(10);
                return reply(reviews.length ? reviews.map(r => `${r.reviewId}\nЗаказ: ${r.orderId}\n${r.reason}: ${r.status}`).join('\n\n').slice(0, 1950) : 'Платежей на проверке нет.');
            }
            if (command === 'refundreview') {
                if (!/^[a-f0-9]{48}$/.test(arg)) return reply('/refundreview идентификатор_из_reviews');
                return reply(`Вернуть платёж на проверке ${arg}?`, keyboard([['Подтверждаю возврат Stars', `review_${arg}`]]));
            }
            if (command === 'tickets') {
                const tickets = store.listTickets(15);
                return reply(tickets.length ? tickets.map(t => `${t.id} — ${t.status}`).join('\n') : 'Обращений пока нет.');
            }
            if (command === 'ticket') {
                const ticket = store.readTicket(arg);
                const purchases = store.listTicketOrders(ticket.id);
                const conversation = ticket.messages.slice(-3).map(m => `${m.direction === 'user' ? 'Покупатель' : 'Поддержка'}: ${m.text}`).join('\n\n').slice(0, 950);
                return reply(`Обращение #${ticket.id}\n${conversation}\n\nОплаченные заказы автора обращения:\n${purchases.orders.length ? orderLines(purchases.orders.slice(0, 3)) : 'Не найдены.'}\n\nВсе страницы: /orders ${ticket.id}`);
            }
            if (command === 'orders') {
                const parsed = /^([a-f0-9]{48})(?:\s+([1-9][0-9]{0,3}))?$/.exec(arg);
                const page = parsed ? Number(parsed[2] || 1) : 0;
                if (!parsed || page > 1000) return reply('/orders номер_обращения [страница 1–1000]');
                const purchases = store.listTicketOrders(parsed[1], page);
                return reply(`Заказы автора обращения #${purchases.ticketId}\nСтраница ${page}\n\n${purchases.orders.length ? orderLines(purchases.orders) : 'Оплаченных заказов на этой странице нет.'}`
                    + (purchases.hasMore ? `\n\nДалее: /orders ${purchases.ticketId} ${page + 1}` : '')
                    + '\n\nКоманда /refund только открывает отдельное подтверждение возврата.');
            }
            if (command === 'reply') {
                const parsed = /^([a-f0-9]{48})\s+([\s\S]{1,1500})$/.exec(arg);
                if (!parsed) return reply('/reply номер_обращения текст_ответа');
                store.replyTicket(parsed[1], parsed[2], requestKey);
                return reply('Ответ сохранён и поставлен в очередь доставки.');
            }
            if (command === 'refund') {
                if (!/^[a-f0-9]{48}$/.test(arg)) return reply('/refund номер_заказа');
                const order = store.getOrder(arg);
                if (!order) return reply('Заказ не найден.');
                return reply(`Вернуть платёж за заказ ${arg}? Ключ и оплаченный им месяц будут отозваны.`, keyboard([['Подтверждаю возврат Stars', `refund_${arg}`]]));
            }
        }
        return reply('Команды: /plans, /keys, /status, /paysupport.');
    } catch (error) {
        if (SAFE_ERRORS.has(error?.code)) return reply(SAFE_ERRORS.get(error.code));
        throw error;
    }
}

export async function handleLiveWebhook(request, options) {
    const rejected = webhookAdmission(request, options);
    if (rejected) return rejected;
    let update;
    try { update = await readJson(request); }
    catch (error) { return jsonResponse({ error: 'INVALID_BODY' }, [400, 408, 413].includes(error?.status) ? error.status : 400); }
    try { await dispatchLiveUpdate(update, options); return jsonResponse({}); }
    catch (error) { options.control.recordFailure(error); return jsonResponse({ error: 'NOT_READY' }, 503); }
}
