import { readJson } from './bot-webhook.mjs';
import { CATALOG, SUBSCRIPTION_DURATIONS, requireBilling } from '../catalog.mjs';

export const APP_PATHS = Object.freeze(['/v1/auth/start', '/v1/auth/exchange', '/v1/auth/revoke', '/v1/subscription', '/v1/redeem',
    '/v2/subscription', '/v2/redeem/preview', '/v2/redeem/confirm']);
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
    'INVALID_CODE', 'CODE_ALREADY_USED', 'CODE_UNAVAILABLE', 'FORBIDDEN', 'NOT_READY',
    'UPGRADE_REQUIRED', 'LEGACY_TERM_ACTIVE', 'QUOTE_EXPIRED', 'QUOTE_STALE', 'QUEUE_FULL']);
export function appError(error) {
    const code = ERRORS.has(error?.code) ? error.code : 'NOT_READY';
    const status = code === 'UNAUTHENTICATED' ? 401 : code === 'FORBIDDEN' ? 403 : code === 'RATE_LIMITED' ? 429
        : code === 'NOT_READY' ? 503 : 400;
    return jsonResponse({ error: code, ...(code === 'LEGACY_TERM_ACTIVE' && validTime(error.legacyExpiresAtMs)
        && error.legacyExpiresAtMs > 0 ? { legacyExpiresAtMs: error.legacyExpiresAtMs } : {}) }, status);
}
function keysOnly(body, keys) {
    requireBilling(body && typeof body === 'object' && !Array.isArray(body)
        && Object.keys(body).every(key => keys.includes(key)), 'INVALID_REQUEST');
}
const MAX_TIME_MS = 8_000_000_000_000_000;
const TERM_FIELDS = ['orderId', 'planId', 'months', 'startsAtMs', 'expiresAtMs'];
const V2_FIELDS = ['schemaVersion', 'telegramUserId', 'serverNowMs', 'entitlementVersion', 'queueVersion',
    'featuresGated', 'active', 'planId', 'expiresAtMs', 'paidUntilMs', 'current', 'queued'];
const validTime = value => Number.isSafeInteger(value) && value >= 0 && value <= MAX_TIME_MS;
const validVersion = value => Number.isSafeInteger(value) && value >= 0;
const validPlan = value => CATALOG.some(plan => plan.id === value);
const validMonths = value => SUBSCRIPTION_DURATIONS.some(duration => duration.months === value);
const validOpaque = value => typeof value === 'string' && /^[0-9a-f]{48}$/.test(value);
function exactKeys(body, required, optional = [], code = 'NOT_READY') {
    requireBilling(body && typeof body === 'object' && !Array.isArray(body)
        && required.every(key => Object.hasOwn(body, key))
        && Object.keys(body).every(key => required.includes(key) || optional.includes(key)), code);
}
function validateTerm(term) {
    exactKeys(term, TERM_FIELDS);
    requireBilling(validOpaque(term.orderId) && validPlan(term.planId) && validMonths(term.months)
        && validTime(term.startsAtMs) && validTime(term.expiresAtMs) && term.expiresAtMs > term.startsAtMs, 'NOT_READY');
}
// V2 is a bounded public DTO, not a pass-through for persisted grant/owner records.
// Keep the v1 allowlist unchanged for existing installed clients and auth pairing.
function validateV2Response(body, path, status) {
    if (status !== 200) {
        exactKeys(body, ['error'], ['legacyExpiresAtMs']);
        requireBilling(status >= 400 && ERRORS.has(body.error), 'NOT_READY');
        if (Object.hasOwn(body, 'legacyExpiresAtMs')) {
            requireBilling(body.error === 'LEGACY_TERM_ACTIVE' && validTime(body.legacyExpiresAtMs)
                && body.legacyExpiresAtMs > 0, 'NOT_READY');
        }
        return;
    }
    const extra = path === '/v2/redeem/preview' ? ['quote'] : path === '/v2/redeem/confirm' ? ['activation'] : [];
    exactKeys(body, [...V2_FIELDS, ...extra], ['legacy', 'legacyExpiresAtMs']);
    requireBilling(body.schemaVersion === 2 && typeof body.telegramUserId === 'string'
        && /^[1-9][0-9]{0,15}$/.test(body.telegramUserId) && BigInt(body.telegramUserId) < 2n ** 52n
        && validTime(body.serverNowMs) && validVersion(body.entitlementVersion) && validVersion(body.queueVersion)
        && body.featuresGated === true && typeof body.active === 'boolean'
        && validTime(body.expiresAtMs) && validTime(body.paidUntilMs)
        && Array.isArray(body.queued) && body.queued.length <= 100, 'NOT_READY');
    const orderIds = new Set();
    if (Object.hasOwn(body, 'legacy') || Object.hasOwn(body, 'legacyExpiresAtMs')) {
        requireBilling(path === '/v2/subscription' && body.legacy === true && body.active === true
            && validPlan(body.planId) && body.current === null && body.queued.length === 0
            && validTime(body.legacyExpiresAtMs) && body.legacyExpiresAtMs > body.serverNowMs
            && body.expiresAtMs === body.legacyExpiresAtMs && body.paidUntilMs === body.legacyExpiresAtMs, 'NOT_READY');
        return;
    }
    if (body.current !== null) {
        validateTerm(body.current);
        requireBilling(body.active === true && body.current.startsAtMs <= body.serverNowMs
            && body.serverNowMs < body.current.expiresAtMs && body.planId === body.current.planId
            && body.expiresAtMs === body.current.expiresAtMs, 'NOT_READY');
        orderIds.add(body.current.orderId);
    } else {
        requireBilling(body.active === false && body.planId === null && body.expiresAtMs === 0, 'NOT_READY');
    }
    let end = body.expiresAtMs;
    for (const term of body.queued) {
        validateTerm(term);
        requireBilling(term.startsAtMs > body.serverNowMs && term.startsAtMs >= end
            && !orderIds.has(term.orderId), 'NOT_READY');
        orderIds.add(term.orderId); end = term.expiresAtMs;
    }
    requireBilling(body.paidUntilMs >= end, 'NOT_READY');
    if (path === '/v2/redeem/preview') {
        const quote = body.quote;
        exactKeys(quote, ['quoteId', 'planId', 'months', 'starsAmount', 'startsAtMs', 'expiresAtMs', 'validUntilMs', 'queueVersion']);
        requireBilling(validOpaque(quote.quoteId) && validPlan(quote.planId) && validMonths(quote.months)
            && Number.isSafeInteger(quote.starsAmount) && quote.starsAmount > 0 && quote.starsAmount <= 1_000_000
            && validTime(quote.startsAtMs) && quote.startsAtMs >= body.serverNowMs
            && validTime(quote.expiresAtMs) && quote.expiresAtMs > quote.startsAtMs
            && validTime(quote.validUntilMs) && quote.validUntilMs > body.serverNowMs
            && quote.validUntilMs - body.serverNowMs <= 130_000 && quote.queueVersion === body.queueVersion, 'NOT_READY');
        // A queued term may start months after its two-minute confirmation quote expires.
    } else if (path === '/v2/redeem/confirm') {
        validateTerm(body.activation); // Idempotent confirmations may describe an already-expired term.
    }
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
        if (path.startsWith('/v2/')) {
            const fields = path === '/v2/redeem/preview' ? ['code'] : path === '/v2/redeem/confirm' ? ['quoteId'] : [];
            exactKeys(body, fields, [], 'INVALID_REQUEST');
            if (path === '/v2/redeem/preview') {
                requireBilling(typeof body.code === 'string' && body.code.length > 0 && body.code.length <= 64, 'INVALID_CODE');
            } else if (path === '/v2/redeem/confirm') {
                requireBilling(validOpaque(body.quoteId), 'INVALID_REQUEST');
            }
            const result = path === '/v2/subscription' ? store.getSubscriptionV2(userId, now)
                : path === '/v2/redeem/preview' ? store.previewRedeemV2(userId, body.code)
                    : store.confirmRedeemV2(userId, body.quoteId);
            const output = { ...result, telegramUserId: userId };
            validateV2Response(output, path, 200);
            return jsonResponse(output);
        }
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
        if (path.startsWith('/v2/')) {
            validateV2Response(body, path, upstream.status);
            return jsonResponse(body, upstream.status);
        }
        // The source is a private service binding, not a remote URL supplied by a caller.
        // Still reject unknown top-level fields (stack traces, diagnostics, identities).
        const permitted = ['pairId', 'botUrl', 'expiresAtMs', 'status', 'token', 'telegramUserId', 'planId', 'active', 'featuresGated', 'revoked', 'error', 'configured', ...(path === SETUP_PATH ? ['diagnostics'] : [])];
        keysOnly(body, permitted);
        return jsonResponse(body, upstream.status);
    } catch { return jsonResponse({ error: 'NOT_READY' }, 503); }
}
