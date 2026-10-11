export const BOT_USERNAME = 'LunagramAppPayBot';

export function isBotWebhookRequest(request) {
    if (request.method !== 'POST') return false;
    try {
        const url = new URL(request.url);
        return url.pathname === '/telegram/webhook' && url.search === '' && url.hash === '';
    } catch { return false; }
}

export const botOptions = env => ({
    webhookSecret: env?.TELEGRAM_WEBHOOK_SECRET,
    botUsername: BOT_USERNAME,
});
