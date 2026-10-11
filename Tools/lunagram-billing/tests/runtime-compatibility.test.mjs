import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { activationCodeHash, parseBoundedJson, verifyPrivateBotMessage } from '../security.mjs';
import { canonicalTonAddress, TON_WALLET } from '../providers.mjs';
import { createBillingService } from '../service.mjs';
import { memoryRepositoryForTests } from './test-repository.mjs';

test('billing helpers import Buffer explicitly instead of requiring a Node-only global', async () => {
    const previous = globalThis.Buffer;
    try {
        // workerd nodejs_compat provides importable node:buffer, not an assumption
        // that every Node global exists. This test does not emulate all workerd.
        globalThis.Buffer = undefined;
        assert.match(activationCodeHash('ABCD1234EFGH5678', Buffer.alloc(32, 8)), /^[0-9a-f]{64}$/);
        assert.deepEqual(parseBoundedJson(Buffer.from('{"ok":true}')), { ok: true });
        assert.ok(canonicalTonAddress(TON_WALLET));
        const now = Date.UTC(2026, 9, 12);
        const secret = 'test_only_webhook_secret_'.repeat(2);
        const rawBody = Buffer.from(JSON.stringify({ update_id: 1, message: {
            from: { id: 101, is_bot: false }, chat: { id: 101, type: 'private' }, date: now / 1000, text: '/start',
        } }));
        assert.equal(verifyPrivateBotMessage(rawBody, secret, secret, now).telegramUserId, '101');
        const service = createBillingService({ enabled: true, testing: true,
            repository: memoryRepositoryForTests(), authenticate: async () => '101',
            keyPepper: Buffer.alloc(32, 9), clock: () => now });
        assert.equal((await service.getSubscription({ session: 'test-only' })).active, false);
    } finally { globalThis.Buffer = previous; }
});
