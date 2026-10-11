import { readJson } from './bot-webhook.mjs';
import { requireBilling } from '../catalog.mjs';

export const APP_PATHS = Object.freeze(['/v1/auth/start', '/v1/auth/exchange', '/v1/auth/revoke', '/v1/subscription', '/v1/redeem']);
export const SETUP_PATH = '/v1/admin/setup';
export const JSON_HEADERS = Object.freeze({ 'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'" });
export const jsonResponse = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
export function appPath(request) {
    const url = new URL(request.url);
    return request.method === 'POST' && !url.search && !url.hash
        && [...APP_PATHS, SETUP_PATH].includes(url.pathname) ? url.pathname : null;
}
export function jsonAdmission(request) {
    return /^application\/json(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?\s*$/i.test(request.headers.get('Content-Type') || '')
        && !request.headers.has('Content-Encoding');
}
const ERRORS = new Set(['INVALID_REQUEST', 'INVALID_BODY', 'RATE_LIMITED', 'UNAUTHENTICATED', 'PAIRING_NOT_AVAILABLE',
    'INVALID_CODE', 'CODE_ALREADY_USED', 'CODE_UNAVAILABLE', 'FORBIDDEN', 'NOT_READY']);
export function appError(error) {
    const code = ERRORS.has(error?.code) ? error.code : 'NOT_READY';
    const status = code === 'UNAUTHENTICATED' ? 401 : code === 'FORBIDDEN' ? 403 : code === 'RATE_LIMITED' ? 429
        : code === 'NOT_READY' ? 503 : 400;
    return jsonResponse({ error: code }, status);
}
function keysOnly(body, keys) {
    requireBilling(body && typeof body === 'object' && !Array.isArray(body)
        && Object.keys(body).every(key => keys.includes(key)), 'INVALID_REQUEST');
}
export async function handleAppRequest(request, { auth, store, control, clock = Date.now }) {
    try {
        const path = appPath(request);
        requireBilling(APP_PATHS.includes(path), 'INVALID_REQUEST');
        requireBilling(jsonAdmission(request), 'INVALID_BODY');
        const now = clock();
        // This header is overwritten by our Pages service, not copied from the caller.
        const source = request.headers.get('X-Lunagram-Client-IP');
        requireBilling(typeof source === 'string' && /^[0-9a-fA-F:.]{3,64}$/.test(source), 'INVALID_REQUEST');
        control.rate('all', 'app-global', 1000, now);
        control.rate(source, 'app-ip', 100, now);
        let body;
        try { body = await readJson(request); } catch { return jsonResponse({ error: 'INVALID_BODY' }, 400); }
        if (path === '/v1/auth/start') {
            control.rate(source, 'start-ip', 10, now, 3600_000);
            keysOnly(body, ['challenge', 'expectedUserId']);
            const pair = auth.start(body, now);
            return jsonResponse({ pairId: pair.pairId, botUrl: `https://t.me/LunagramAppPayBot?start=${pair.startParameter}`, expiresAtMs: pair.expiresAtMs });
        }
        if (path === '/v1/auth/exchange') {
            keysOnly(body, ['pairId', 'verifier']);
            const result = auth.exchange(body.pairId, body.verifier, now);
            return jsonResponse(result, result.status === 'pending' ? 202 : 200);
        }
        const authorization = request.headers.get('Authorization') || '';
        requireBilling(/^Bearer [A-Za-z0-9_-]{43}$/.test(authorization), 'UNAUTHENTICATED');
        const token = authorization.slice(7);
        const userId = auth.authenticate(token, now);
        control.rate(userId, 'app-user', 60, now);
        if (path === '/v1/auth/revoke') { keysOnly(body, []); return jsonResponse(auth.revoke(token, now)); }
        keysOnly(body, path === '/v1/redeem' ? ['code'] : []);
        const result = path === '/v1/redeem' ? store.redeem(userId, body.code) : store.getSubscription(userId, now);
        return jsonResponse({ telegramUserId: userId, ...result });
    } catch (error) { return appError(error); }
}

// Private-service proxy only; never follows redirects or reflects upstream headers.
export async function proxyAppRequest(request, env) {
    try {
        const path = appPath(request);
        if (!path || !jsonAdmission(request) || !env.BILLING_SERVICE?.fetch) return jsonResponse({ error: 'INVALID_REQUEST' }, 400);
        const headers = { 'Content-Type': 'application/json' };
        const authorization = request.headers.get('Authorization');
        if (authorization) headers.Authorization = authorization;
        // Cloudflare provides this at its public edge. User X-Lunagram-* is discarded.
        headers['X-Lunagram-Client-IP'] = request.headers.get('CF-Connecting-IP') || '';
        const upstream = await env.BILLING_SERVICE.fetch(new Request(`https://billing.internal${path}`, {
            method: 'POST', headers, body: request.body, duplex: 'half', redirect: 'manual' }));
        if (![200, 202, 400, 401, 403, 429, 503].includes(upstream.status) || !jsonAdmission(upstream)) {
            try { void upstream.body?.cancel().catch(() => {}); } catch { /* bounded */ }
            return jsonResponse({ error: 'NOT_READY' }, 503);
        }
        const body = await readJson(upstream);
        // The source is a private service binding, not a remote URL supplied by a caller.
        // Still reject unknown top-level fields (stack traces, diagnostics, identities).
        const permitted = ['pairId', 'botUrl', 'expiresAtMs', 'status', 'token', 'telegramUserId', 'planId', 'active', 'featuresGated', 'revoked', 'error', 'configured', ...(path === SETUP_PATH ? ['diagnostics'] : [])];
        keysOnly(body, permitted);
        return jsonResponse(body, upstream.status);
    } catch { return jsonResponse({ error: 'NOT_READY' }, 503); }
}
