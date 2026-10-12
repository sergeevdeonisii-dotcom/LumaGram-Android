import { CATALOG } from '../catalog.mjs';
import { STARS_TARIFFS } from '../stars-tariffs.mjs';

const HEADERS = Object.freeze({
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
});

export function publicPath(request) {
    // No body, credentials, cookie, origin or caller-provided ID is read.
    if (request.method !== 'GET') return null;
    let url;
    try { url = new URL(request.url); } catch { return null; }
    if (url.search !== '' || url.hash !== '') return null;
    return ['/health', '/v1/catalog'].includes(url.pathname) ? url.pathname : null;
}

export function publicPayload(path, { live = false, payments = false } = {}) {
    if (path === '/health') return {
        service: 'lunagram-billing', status: live ? 'ready' : 'not_ready',
        paymentsAvailable: live && payments, activationAvailable: live, storageStatus: live ? 'ready' : 'unverified',
    };
    if (path === '/v1/catalog') return {
        currency: 'USD', period: 'calendar_month', manualRenewal: true, featuresGated: true,
        paymentsAvailable: live && payments, activationAvailable: live,
        plans: CATALOG.map(plan => ({ id: plan.id, title: plan.title, usdCents: plan.usdCents, starsAmount: STARS_TARIFFS[plan.id] })),
        paymentMethods: [
            { id: 'telegram_stars', available: live && payments, priceStatus: 'configured' },
            { id: 'cryptopay', available: false, priceStatus: 'not_configured' },
            { id: 'ton', available: false, priceStatus: 'not_configured' },
        ],
    };
    return null;
}

export function publicResponse(path, readiness) {
    const payload = publicPayload(path, readiness);
    return payload ? new Response(JSON.stringify(payload), { status: 200, headers: HEADERS }) : notReadyResponse();
}

export function notReadyResponse() {
    return new Response(JSON.stringify({ ok: false, error: 'NOT_READY', paymentsAvailable: false, activationAvailable: false }),
        { status: 503, headers: HEADERS });
}

export async function readPublicReadiness(response, path) {
    // The service proxy never reflects arbitrary upstream bodies/headers, even if
    // an accidental platform error includes source paths or account identifiers.
    if (!response || response.status !== 200 || !response.body
        || !response.headers.get('Content-Type')?.startsWith('application/json')) {
        try { void response?.body?.cancel().catch(() => {}); } catch { /* bounded */ }
        return null;
    }
    const reader = response.body.getReader();
    const chunks = [];
    let length = 0;
    let timer;
    const deadline = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('TIMEOUT')), 2000); });
    try {
        for (;;) {
            const item = await Promise.race([reader.read(), deadline]);
            if (item.done) break;
            length += item.value.byteLength;
            if (length > 8192 || chunks.length > 8192) { void reader.cancel().catch(() => {}); return null; }
            chunks.push(item.value);
        }
        const bytes = new Uint8Array(length);
        let offset = 0;
        for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
        const text = new TextDecoder().decode(bytes);
        return [{}, { live: true, payments: false }, { live: true, payments: true }]
            .find(readiness => text === JSON.stringify(publicPayload(path, readiness))) ?? null;
    } catch { try { void reader.cancel().catch(() => {}); } catch { /* bounded */ } return null; }
    finally { clearTimeout(timer); try { reader.releaseLock(); } catch { /* bounded */ } }
}

export async function matchesPublicResponse(response, path) {
    return await readPublicReadiness(response, path) !== null;
}
