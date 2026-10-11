# Lunagram billing deployment — connected bot, sales disabled

## Current verified rollout — 2026-10-12

The neutral service is `https://lunagram-billing.pages.dev`; the bot is `@LunagramAppPayBot`. The connected runtime includes durable bot delivery, in-bot support, account pairing, authenticated code activation and the Stars payment state machine. **Real subscription sales are still disabled.** Crypto Pay and TON are not connected.

| Item | Current recorded state |
| --- | --- |
| Private core version | `ca7dfded-b0a0-41e9-b002-3058cdedcbda` — support/order lookup, 2026-10-12 |
| Pages deployment | `54f4ded9` |
| `LIVE_BOT_ENABLED` | `true` |
| `BILLING_PAYMENTS_ENABLED` | `false` |
| Owner support role bound | `true` — confirmed through private operator diagnostics on 2026-10-12; configured and bot identity match also confirmed |
| Production informational delivery | Encrypted outbox acknowledged: 2, pending: 0 at the owner-binding check; new `/paysupport` and `/keys` replies subsequently verified in Telegram Desktop |
| Node regression tests | 225 passed locally, including the deployed support/order-lookup patch |
| Native workerd check | Passed with fake credentials and fake Telegram transport |
| Actual paid purchase/refund | **Not performed or verified** |

The native workerd check exercised `/start`, explicit owner confirmation, invoice → simulated paid receipt → durable delivery → activation, and duplicate activation. It exposed an actual runtime incompatibility: `Cipheriv.update` needed explicit bytes/encoding instead of Node's implicit string handling. The implementation now passes UTF-8 `Buffer` data; `tests/workerd-crypto.test.mjs` guards this difference. The queued production `/start` was acknowledged by Telegram after deploying the fix.

These results demonstrate the tested local payment state machine, real informational delivery and a bound support owner, **not** real-money checkout, refund settlement, production crash recovery, disaster recovery, or the final Android APK. No real payment or refund was created by these tests. Owner binding is complete; it is no longer an activation blocker. Sales remain disabled pending the other gates below.

### Support/order-lookup patch — deployed, sales remain disabled

The source now includes the full opaque order reference in the buyer's original key receipt and `/keys` response. `/paysupport` tells the buyer to share this reference rather than the bearer key. Already queued encrypted messages are immutable and are not rewritten; after deployment a new `/keys` command supplies references for the latest 20 purchases.

The bound support owner can open `/ticket TICKET_ID` to see the last three paid orders belonging to that ticket's author. `/orders TICKET_ID [page]` returns five per page (pages 1–1000). Matching uses the stored ticket identity, never the buyer's supplied user ID or a different order mentioned in the message. Views expose only order reference, tariff, Stars amount, status and UTC creation date—not Telegram identity, keys, payment charge IDs or identity hashes. These commands are denied to ordinary users, including the author of the ticket. Lookup does not change payment or key state.

Refund execution is unchanged: the owner selects `/refund ORDER_ID` and must confirm it separately. An order reference is a lookup handle, not an authorization credential. The new local tests cover the buyer → support → owner lookup → explicit refund-confirmation flow, user isolation, pagination/reopening, wrong-order mentions and message-size limits. An isolated native workerd run additionally confirmed receipt order references, buyer-only lookup, reopening storage, and duplicate activation without a second grant. It used fake identities, keys and Telegram transport, never production financial records.

The reviewed patch was deployed to the existing private core with `--keep-vars --strict`. No namespace, migration, key, owner binding or Pages route changed. Fresh production checks confirmed `paymentsAvailable=false`, `ownerBound=true`, matching bot identity, zero pending updates and two acknowledged prior deliveries. Anonymous webhook/admin requests returned 403 and anonymous subscription access returned 401. Subsequent Telegram Desktop checks verified new `/paysupport` instructions asking for the order reference, not the secret code, and a `/keys` reply correctly reporting no key purchases. This does **not** verify a real paid order, paid-key delivery or refund; no real payment was made.

## Current architecture and routes

- Private Worker: `lunagram-billing-core`, `workers_dev: false`, `preview_urls: false`, no public routes; `nodejs_compat` enabled. It exports SQLite Durable Object `BillingStore`, migration `v1` / `new_sqlite_classes`.
- Public Pages project: `lunagram-billing`, using only service binding `BILLING_SERVICE` to the private Worker. Preserve **one fixed DO identity, `billing-v1`**, for all users in this environment: payment uniqueness and key redemption must not be split across per-user objects.
- Exact `GET /health` and `GET /v1/catalog` report the connected runtime and sales state. With the current flags, payment availability is false and authenticated activation availability is true. `status: ready` / `storageStatus: ready` describe configured runtime capability, not a financial certification or crash/restore guarantee. Unlike the original scaffold, the live route consults the DO and can initialize its schema; do not claim these requests never access storage.
- Exact `POST /telegram/webhook` authenticates the Telegram secret header at the core before invoking the DO and verifies it again in the live handler. JSON is bounded to 32 KiB and a two-second read deadline. Private commands/callbacks, pre-checkout queries, paid receipts and refund receipts have separate validation. Late genuine financial receipts are reconciled independently of conversational deduplication and freshness limits.
- App POST routes are `/v1/auth/start`, `/v1/auth/exchange`, `/v1/auth/revoke`, `/v1/subscription`, and `/v1/redeem`. Pairing requires an app-held PKCE verifier and explicit confirmation by the matching private Telegram account. Client user IDs are hints, not proof. Subscription/redemption/logout require a valid server session. Anonymous callers cannot activate a subscription by supplying an account ID.
- `/v1/admin/setup` is a separately authenticated bootstrap/operator route; no public command or first caller gains administrative access. Its bearer credential is never published. Owner binding additionally requires a distinct expiring, one-use server capability and explicit confirmation inside the private bot conversation. No capability value or owner-claim URL belongs in documentation, logs, source or APK.
- Unknown paths/methods and queried URLs fail closed. There is no wildcard CORS. Proxies use fixed service bindings, `redirect: manual`, bounded responses and sanitized security headers; redirects are not followed. Pages discards spoofed app-source headers and forwards only the necessary metadata.

The Pages upload directory is `pages/public`, containing `_worker.js` and `_routes.json`. Source, configuration, tests and this document remain outside the asset upload directory. Never deploy the repository root as Pages assets. The billing API root is not the Lunagram download website.

## Financial and delivery invariants

- Approved immutable tariffs are Based 40, Standard 70, Plus 100, Pro 140 and Ultra 360 Stars. The owner's USD reference uses 50 Stars per $0.70, rounded to the nearest ten; this is a pricing convention, not a guaranteed retail/exchange/payout rate. Renewal is manual. All application features remain ungated.
- Current terms version is `2026-10-12-v1`. Purchase requires explicit consent. Support is provided inside the bot; customers do not receive the author's personal account/contact. Only the explicitly bound owner can read support tickets, reply or initiate a confirmed refund.
- Creating an order atomically queues its encrypted invoice. Once a pre-checkout query is reserved, another query must use a new order; a timeout is never interpreted as proof that a payment failed. `start_parameter` sends forwarded invoice recipients through the bot's own purchase flow.
- A verified primary payment receipt, unique code HMAC, encrypted recoverable bearer code, ledger event and delivery job commit in **one synchronous SQLite transaction**. Duplicate receipts never mint another code or grant. A key grants one calendar month only after authenticated redemption; concurrent redemption has one winner.
- Unexpected/duplicate charges are retained for review. Owner-only review/refund methods can reconcile a captured unknown-order receipt without minting a key. Refund preparation blocks the relevant unused key; only confirmed provider success or a verified refund receipt revokes the corresponding grant. An ambiguous refund is retained as pending for reconciliation, not reported as successful.
- Durable alarms drive the encrypted outbox. Delivery uses bounded leases, ACK checks, exponential backoff, Telegram `retry_after`, at most 12 attempts and a 24-hour retry window. A send accepted before a lost ACK may be repeated; it repeats the **same key**, not a rotated replacement. This is at-least-once attempted delivery, not exactly-once or guaranteed delivery.
- Exhausted/expired jobs retain their ciphertext for manual reconciliation. `/keys` lets an authenticated buyer request another delivery attempt. Do not silently delete failed paid deliveries, financial history or lease/idempotency records; agree and test retention/backup/restore procedures before accepting money.

## Secrets and private data

Deployment secrets are supplied through the host's secret manager, not checked-in configuration, command arguments, chat, APK, Pages assets or logs. The runtime needs the bot token, webhook secret, encryption key and separate billing/auth peppers. Bootstrap and owner-binding capabilities are separate, expiring/one-use where specified by their handlers. No wallet seed or private wallet key is required.

Keep encryption keys and HMAC peppers stable across restarts. Losing or replacing them without a tested migration can make existing encrypted records unreadable or invalidate keys/sessions. This initial key-version implementation does not provide safe automatic key rotation. Test fixtures are never production credentials.

Financial identities, support content, keys and delivery payloads are encrypted with AES-GCM; lookup identifiers use domain-separated HMAC. Authentication tables contain Telegram IDs on the private server, so **not every account identifier is application-encrypted**. Application handlers do not intentionally log bodies, tokens or keys, but platform metadata/log retention remains subject to Cloudflare account settings; this is not a promise of zero provider-side logging.

## Remaining activation and release gates

1. Owner binding was confirmed on 2026-10-12 (`ownerBound=true`, configured and bot identity match). Preserve the bound role and verify it privately after updates; do not publish its capability or account ID and do not repeat bootstrap merely because sales are still disabled.
2. The support/order-lookup patch is reviewed and deployed. Keep `BILLING_PAYMENTS_ENABLED=false` until the remaining support/refund procedures and rollout checks are complete. A flag is not a substitute for authentication, terms or owner binding: `paymentsReady()` requires configuration, owner binding **and** the payment flag.
3. Check current Cloudflare free-plan resources/quotas through authorized tooling; stop rather than enabling a paid plan without approval. A paid VPS is not required for this deployment. Preserve the namespace, DO name, migrations and secrets on updates; keep private Worker public/preview URLs disabled.
4. Before upload, run the complete Node suite and a native workerd smoke test with fake credentials/transport, then dry-run/build both bundles. Node-only checks did not catch the cipher encoding incompatibility. Never run synthetic financial fixtures against production storage or Telegram.
5. Verify production health/catalog, rejected anonymous webhook/app requests, bounded private diagnostics and actual informational delivery. Qualify production restart/persistence, backup restoration, alarm recovery, duplicate/late receipts, failed delivery and operator refund/review recovery separately. Do not infer all of this from a green health response.
6. Only after these gates and explicit authorization, enable sales and perform a small controlled real purchase/activation/refund test. Record provider confirmation and duplicate/retry behavior without retaining credentials or bearer codes in reports. **This has not been done in the rollout recorded above.**
7. Build and verify the final Android APK/device integration separately: pairing cancellation/account switching, session storage, activation, logout, unavailable-payment messaging and subscription display. Source-level Android tests do not prove the installed APK is updated.

Typical local commands, run only with existing reviewed tools from the repository root:

```sh
node --test Tools/lunagram-billing/tests/*.test.mjs
wrangler deploy --dry-run --config Tools/lunagram-billing/cloudflare/wrangler.jsonc
```

For an authorized update, deploy the private Worker first, rebuild the Pages advanced-mode bundle, then deploy Pages using its configuration. These notes do not authorize changing accounts, creating paid resources, overwriting unrelated projects, dropping pending Telegram updates or bypassing the remaining sales gates. The bot currently allows `message`, `callback_query`, and `pre_checkout_query` updates with two webhook connections.

Telegram previously reported DNS resolution failure. Deployment uses the supported explicit webhook IP option with a validated public Cloudflare address while retaining the HTTPS hostname/certificate check. If that address changes, re-resolve and validate it before updating registration; remove the pin when Telegram's resolver is working. Do not replace the webhook hostname with an arbitrary IP URL.

## Historical rollout record — superseded, not deployment instructions

The following records explain earlier versions only. Their informational-only routing, missing financial schema and unavailable auth routes **do not describe the connected runtime above**.

- **2026-10-11 read-only scaffold:** private core `d1ec111f-b0cb-41f1-a9ae-b539b1cc81ea`, Pages `ccbb6bac`; 60 Node tests. Only exact health/catalog GETs were available and avoided DO initialization. Other routes returned 503. Local workerd required `redirect: manual` instead of Node-compatible `redirect: error`.
- **2026-10-12 informational bot:** core `d80195e3-d79e-4ac7-9069-1b80b369a641`, Pages `c5c831fe`; 122 Node tests. Four private commands used Telegram webhook-response replies with at-most-once attempts, dedicated update/rate tables and no financial fulfillment. Real `/start` and `/plans` replies verified informational delivery only. This response mechanism is not used for paid-key delivery.
- **2026-10-12 tariff preparation:** core `b91dd651-e9fb-48ea-b2be-195a052bd8f8`, Pages `7724e576`; 144 Node tests. The owner approved the fixed 40/70/100/140/360 Stars amounts. The outbox was then only an isolated primitive. Subsequent connected `live-*` implementation added the shared transaction, dispatcher, pairing, support and refund/review flows.

The original isolated Crypto Pay/TON/provider adapters remain offline and must not be mistaken for approved or connected bot payment options. Telegram digital-goods purchases in this bot use Stars, not a Crypto Bot workaround.

Configuration references consulted during the scaffold rollout (2026-10-11):

- [Worker Wrangler configuration](https://developers.cloudflare.com/workers/wrangler/configuration/)
- [Pages advanced-mode Worker](https://developers.cloudflare.com/pages/functions/advanced-mode/)
- [Pages service bindings](https://developers.cloudflare.com/pages/functions/bindings/#service-bindings)
- [Pages Wrangler configuration](https://developers.cloudflare.com/pages/functions/wrangler-configuration/)
