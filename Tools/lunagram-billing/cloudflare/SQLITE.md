# SQLite repository adapter

`createSqliteRepository(ctx.storage)` implements the billing core's repository contract for a **SQLite-backed Durable Object**. The adapter itself does not deploy, enable billing, authenticate users, send bot messages or read secrets.

```js
import { createSqliteRepository } from './sqlite-repository.mjs';

const repository = createSqliteRepository(ctx.storage);
// Pass this repository to createBillingService. Existing callbacks stay synchronous.
await repository.cleanupExpiredRateLimits(Date.now()); // Up to 100 expired buckets.
```

Route every account and payment within one billing environment to **one stable named Durable Object**. A per-user object would break global bearer-code and charge-ID uniqueness. Production/test environments must have separate object namespaces/identities. Do not change the stable identity on each deployment, and do not expose arbitrary object names to clients. Multi-object sharding would require a different transactional design.

## Why this adapter is not D1

The existing core reads an order/key, checks it and conditionally writes multiple records within a synchronous callback. Durable Object `transactionSync` plus `sql.exec` preserves those atomic decisions. Simply replacing these calls with asynchronous D1 operations would not satisfy that contract. D1 is not used or created by this adapter. SQLite-backed Durable Objects can be evaluated on Cloudflare's free plan; current account limits and production compatibility still need checking during deployment.

Transactions reject async/thenable callbacks, roll back on error and close their transaction handle before any continuation can use it. Never do network I/O inside a repository callback. All SQL cursors are consumed synchronously, all user-derived values are bound parameters, and the adapter never uses `INSERT OR REPLACE` for financial records. Unique constraints cover order/request/invoice IDs, key hashes and payment event IDs. Payment records are insert-only. Only allowlisted key fields can be stored, so accidentally passing a plaintext `code` field is rejected.

The rate-limit counter transaction remains separate from the later redemption transaction, as required by the core. Restarting the object does not reset failed attempts. Schedule `cleanupExpiredRateLimits(nowMs, limit)` on a bounded maintenance path; the default limit is 100 and maximum is 500 rows per call, indexed by expiry. Cleanup removes **only expired rate-limit buckets**. Orders, payment proofs, keys and entitlements are never deleted automatically. No financial-record retention policy or refund-state migration is introduced here.

Schema version 1 initializes through an atomic transaction and refuses unknown versions. Before production schema changes, add explicit migrations and backup/restore checks; do not drop tables or rewrite the schema version to bypass the guard. Restoring an old database backup can resurrect redeemed keys/payment state: production recovery must reconcile the ledger before accepting new transactions.

## Local verification

```sh
node --test Tools/lunagram-billing/tests/billing.test.mjs Tools/lunagram-billing/tests/sqlite-repository.test.mjs
```

The new tests use real file-backed `node:sqlite` databases through a minimal Cloudflare-shaped test wrapper. They cover close/reopen persistence, two connections racing redemption, unique-constraint rollback, replayed Stars receipts, committed failed-attempt limits, escaped/async transaction handles, record bounds and bounded cleanup. They do **not** substitute for Cloudflare runtime deployment tests, network/auth/outbox checks or real-payment tests. The Node SQLite wrapper is test-only and must not enter the Worker bundle.

Official reference: [Cloudflare SQLite-backed Durable Object storage](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/) documents `sql.exec`, synchronous cursor consumption and `transactionSync` rollback behavior. Checked on 2026-10-11.
