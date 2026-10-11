# Lunagram billing — rollout status (2026-10-12)

The neutral service `https://lunagram-billing.pages.dev` now runs the durable bot, in-bot support, PKCE account pairing and authenticated subscription/code routes. **Live sales remain disabled** (`BILLING_PAYMENTS_ENABLED=false`) until the owner confirms their support role and the rollout is verified. No real payment or refund was made by the implementation tests. Crypto Pay and TON remain unconnected.

## Current connected implementation

- `cloudflare/live-bot.mjs`: private-chat commands, explicit purchase terms, approved Stars tariffs (40/70/100/140/360), invoice/pre-checkout/paid/refund routing, keys, status and support. Administrator commands are limited to the one explicitly bound owner; public support replies come from the bot, not the owner's personal profile.
- `cloudflare/live-store.mjs`: one SQLite transaction commits verified payment, code, ledger and encrypted delivery job. A different pre-checkout query must use a new invoice; paid receipts remain independently idempotent. A bearer key grants one calendar month only when redeemed. Repeat delivery uses the same key, never a replacement.
- `live-auth.mjs`: ten-minute PKCE pairing, explicit confirmation by the matching private Telegram account, revocable thirty-day sessions. Client-supplied user IDs are hints, never authentication. Authentication records contain Telegram IDs on the private server; financial identities, support messages and recoverable keys are encrypted, and lookup keys use HMAC.
- `cloudflare/billing-object.mjs`: durable alarm, bounded Bot API requests, lease/ACK, retry-after and restart recovery. Delivery attempts are at-least-once, not exactly-once. Terminal delivery records and financial history are retained for manual reconciliation; `/keys` provides a new authenticated delivery attempt.
- `cloudflare/live-control.mjs`: one-use, expiring owner capability with a separate explicit confirmation; no first-caller administrator. Bootstrap diagnostics require a separate server-held bearer secret and expose counts/status only, not user identities or messages.
- Android `LunagramSubscriptionClient` / `LunagramSubscriptionActivity`: fixed HTTPS endpoint, serialized PKCE polling, account-switch/cancellation checks, encrypted Android Keystore sessions (memory-only on API 21–22), activation and logout. Native screen and model tests do not replace full APK/device verification.

Keys exist only in Cloudflare secrets. Never commit them, rotate/drop encryption secrets without a tested data migration, or use test fixtures in production. No rollout flag may bypass owner binding or authentication. Keep the private core's public routes and preview URLs disabled.

Verification: 220 Node tests passed, plus a native workerd test using fake credentials and a fake Telegram transport: `/start`, owner confirmation, invoice → paid receipt → durable delivery → activation, and duplicate activation. It found and fixed a runtime incompatibility: workerd requires explicit bytes/encoding in `Cipheriv.update`; `tests/workerd-crypto.test.mjs` guards it. The owner's previously queued `/start` was subsequently acknowledged by Telegram after deployment. Full Android APK and real-money end-to-end verification remain separate release gates.

## Legacy isolated adapters and original scaffold notes

The sections below describe the original isolated `service.mjs`/provider adapters and informational-only rollout. They are retained as historical integration contracts, not a claim that the connected `live-*` paths are still absent. In particular, the connected Stars flow uses the encrypted transactional outbox and does **not** use the older plaintext-once `issuePurchasedKey()` recovery mechanism.

Initially this directory contained only an isolated core and informational bot. Those deployment notes are superseded by the connected implementation and rollout status above.

## Product contract

| Plan | USD reference per calendar month | Approved Stars tariff |
| --- | ---: | ---: |
| Based | $0.50 | 40 |
| Standard | $1.00 | 70 |
| Plus | $1.40 | 100 |
| Pro | $2.00 | 140 |
| Ultra | $5.00 | 360 |

The owner explicitly approved these Star amounts on 2026-10-12: reference 50 Stars = $0.70, rounded to the nearest ten (half up). `stars-tariffs.mjs` performs the calculation with integers. This is the owner's chosen pricing convention, not a guaranteed retail exchange rate, withdrawal rate or payout. Price configuration alone does not enable checkout. The owner declined a public support contact for now; no contact was invented or published.

Prices use integer cents. All features remain available: `featuresGated` is always `false`. Renewal is manual, never an automatic debit. One month is added in UTC, clipping month-end to the last valid day. Renewal extends from the later of now and existing expiry. The most recently purchased plan becomes the displayed plan; no price proration or tier-dependent features are implemented. This behavior must be described in checkout terms before launch.

The core supports two fulfillment modes: direct activation for the authenticated account, or a 16-character uppercase Latin-letter/digit bearer code. Codes are generated with `crypto.randomInt`, persisted only as a peppered HMAC, and can be redeemed exactly once. No live codes have been generated. Whoever possesses a paid bearer code can redeem it; do not claim it is bound to its purchaser. There is no unauthenticated/admin promo-code issuance endpoint.

## Payments and Telegram platform restriction

- Crypto Pay preparation supports USD invoices accepting USDT only; it verifies the exact price, currency, asset, order payload and invoice ID using `getInvoices`. Raw-body webhook HMAC is checked before parsing. A webhook is a reconciliation hint, never client-side proof of payment.
- Native TON preparation fixes the recipient to the user-approved wallet `UQDwZfLQqfQakdFG642jd24xjILmNKfXoIbjsoEuGMoLX-7G`. A server-owned rate adapter must supply a fresh rational USD/TON quote; the amount is rounded upward to integer nanotons. Receipt verification requires mainnet, native TON (not a similarly named jetton), successful finalized non-bounced transfer, exact recipient, comment, amount and payment time.
- The app/bot must **not** accept a client `paid=true`, screenshot, wallet-return event or arbitrary transaction object as proof. Only server-owned provider adapters may call the receipt validators.
- Telegram requires **Stars (XTR)** for digital-goods purchases inside bots and Mini Apps. `createBotCryptoPurchase()` therefore always rejects. Calling checkout “external” is not sufficient: `bot_invoice_url` still opens Crypto Bot inside Telegram. Crypto Pay preparation additionally requires `checkoutPolicyApproved: true`; this flag must remain unset until the real distribution/checkout flow is reviewed for compliance. It is **not approved by this implementation**. Native TON must not be placed in a bot/Mini App as a Stars workaround either.
- The owner requested Stars bot purchases and approved the exact tariffs above. The offline Stars core is prepared, but authenticated activation, durable delivery, refunds/reconciliation and real support still need integration before live sales. Tests may use other fixture tariffs. Crypto Bot payment inside Telegram remains unapproved; it is not an alternative to Stars for these digital goods.

### Prepared Stars payment flow (not connected to the informational bot)

`stars.salesEnabled` must be explicitly enabled and `stars.prices` must contain a positive integer amount for the selected plan (a local safety cap is 1,000,000 Stars, not a claim about Telegram's limit). Missing/malformed tariffs fail closed. `prepareBotStarsInvoice` verifies a private secret-protected `/buy based|standard|plus|pro|ultra` update and persists a key-delivery order; it returns a `sendInvoice` request with `XTR`, empty provider token and exactly one frozen price. It does not call Telegram. Replayed commands share one persisted order ID.

`prepareStarsPreCheckoutReply` verifies the secret, sender, order, currency and amount; it reserves one query ID atomically. It does **not** issue a key or grant a subscription. The transport must send `answerPreCheckoutQuery` within Telegram's 10-second deadline, including `ok: false` on rejected orders. An approved/reserved query that never completes needs an operator/reconciliation expiry path before another attempt; do not remove the reservation during an in-flight payment.

Only `handleStarsSuccessfulPayment` can mark a Stars order paid. It checks the secret-protected private sender, exact `XTR` amount/order and unique `telegram_payment_charge_id`. Delayed valid payment receipts are accepted without the text-command five-minute age limit. Recurring-payment fields are rejected because these purchases are manual monthly renewals. Duplicate receipts cannot issue a second grant; a different charge for an already-paid order needs support/refund handling. Paid key orders then use the same one-time code flow, with a reviewed authenticated bot-delivery adapter still required. Store charge IDs for future `refundStarPayment`; no live refund handler or admin tooling exists here.

## Code and delivery reliability

`issuePurchasedKey()` issues only after a verified paid key order, to its authenticated purchaser. Plaintext is returned once and must never enter logs, analytics, backups or general telemetry. Because only the hash is stored, a crash/lost response after commit means the original code cannot be recovered. An authenticated purchaser can explicitly request `replaceUndelivered: true`; in one transaction this revokes the old unredeemed code and issues a replacement. A redeemed key cannot be rotated. Do not silently rotate on an automatic HTTP retry.

An eventual bot sender must handle failed/ambiguous message delivery with a private authenticated recovery flow, or a reviewed encrypted transactional outbox with separately managed secrets and controlled retention. **`cloudflare/bot-outbox-repository.mjs` is an isolated, tested storage primitive, not a connected delivery system.** It accepts only a strict encrypted-envelope shape, with deduplication, lease/CAS and finite retries; encryption/authenticated AAD, the dispatcher and the shared financial transaction are still missing. Separate financial and outbox commits are not atomic. Terminal tasks remain for manual reconciliation; retention/cleanup must be implemented before launch. Paid order identity and proof must be retained for support and refunds. A bearer code copied before rotation becomes invalid; users must be told when replacement occurs.

## Required integration contracts

`createBillingService()` defaults to disabled. Enable only on a server, with all required dependencies. There is no production in-memory fallback. `testing: true` exists only for regression fixtures; never set it in production.

### Authentication and transport

`authenticate(session)` must validate a high-entropy, expiring, revocable server session tied to a Telegram ID proven through a real Telegram authentication flow. Returning a numeric ID supplied by the APK is **not authentication**. A future bot-assisted pairing flow needs a short-lived, single-use nonce, explicit account confirmation and an app-held secret (or another reviewed proof); never trust an app's claimed `user_id` alone. Implement replay prevention and session/account-switch behavior before app wiring.

`verifyPrivateBotMessage()` validates `X-Telegram-Bot-Api-Secret-Token`, sender identity, private chat and time bounds. The transport must consume `update_id` atomically once, with durable deduplication, before processing side-effecting commands; Stars invoice preparation already deduplicates its order by update ID. This helper is for text commands only. Separate Stars validators handle pre-checkout/payment updates without applying the text-message freshness rule to delayed receipts. Callback-query handling is not implemented. None of these helpers may be exposed as an endpoint that accepts the configured secret in a request body.

Production transport must provide HTTPS, request-body size/time limits, no credential-bearing query strings, IP/global abuse limits, bounded outbound requests/retries, CSRF protection where browser cookies are used, and redacted logs. Preserve the exact webhook bytes; no JSON reserialization. Error responses must not leak keys, payment secrets, account IDs or existence of another user's order. `getSubscription` must be authenticated. No endpoint may expose the internal `testing`, `authenticate`, provider or repository configuration.

### Durable transactional repository

Provide `durable: true` and `transaction(callback)`. Transactions must be serializable across **all processes**, not just a JavaScript mutex. Callback reads return isolated copies; all writes commit together or roll back. Implement the following transaction methods:

- Orders: `getOrder(id)`, `getOrderByRequest(requestKey)`, `getOrderByInvoice(invoiceId)`, `putOrder(order)`.
- Keys: `getCode(hash)`, `putCode(record)`; atomic compare-and-set/row lock for redemption and rotation.
- Payments: `getPayment(eventId)`, `putPayment(record)`; insert-only unique `eventId`.
- Entitlements: `getEntitlement(telegramUserId)`, `putEntitlement(record)`; row lock for extension.
- Rate limits: `consumeRateLimit(bucket, limit, expiresAtMs)`, atomically incrementing and returning whether allowed.

Enforce DB unique constraints on order ID, request key, provider invoice ID, payment event ID and key hash; one entitlement row per Telegram account. Payment insert, entitlement extension and marking order paid are one transaction. Code consumption and entitlement extension are one transaction. Duplicate delivery must not extend twice. Backups and migrations need restore tests before accepting money. Keep the HMAC pepper in server secret storage; losing/changing it without a migration invalidates unredeemed keys.

Failed-redemption rate limits are intentionally committed in a **separate transaction before** format validation/redemption. A rollback for a bad code must not undo the failed-attempt counter. The provided per-account fixed-window limits are only one layer: add shared per-IP/global controls, retention/cleanup and monitoring. Handle rate-limit database failures by denying requests, not allowing unlimited attempts. Multi-process, crash/restart and durable-storage tests are mandatory; `tests/test-repository.mjs` is a rollback/serialization fixture, **not production storage**.

### Provider adapters

Crypto Pay adapter: server-only token, reviewed checkout policy, `createInvoice(request)` and `getInvoice(invoiceId)` returning authoritative objects from your merchant account. Pin the official host and HTTPS, disallow redirects to untrusted origins, set tight response bounds/timeouts, check `ok` and choose the exact requested invoice. No transfer/spend API is needed; keep transfers disabled. Test environment and production must have separate keys, stores and fulfillment. The current URL validator intentionally permits only the mainnet Crypto Bot URL; a reviewed testnet adapter needs a separate allowlist, never a broadly relaxed one.

An ambiguous create-invoice failure goes to `review_required`; a retry of the same idempotency key must not create another invoice. Build an operator reconciliation job to recover by unique order payload before enabling checkout. A delayed webhook before the invoice is attached should be retried after reconciliation. The core validates and persists provider `created_at`/`expiration_date` instead of expiring a successfully created invoice by its earlier local request time; a bounded 30-second server/provider clock difference is accepted. Keep server time synchronized. Persist and investigate expired, late, underpaid or overpaid transfers for refund/support instead of silently discarding funds; the core does not fulfill mismatches. No live refund engine exists here.

TON adapter: `getRate()` returns `{centsNumerator: bigint, denominator: bigint, observedAtMs}` from a trusted rate source; `findPayment({recipient, comment, fromMs, toMs})` looks up the actual incoming native-TON mainnet transaction. Independently validate execution/aborted/bounce status, masterchain finality and decoded incoming message; normalize the destination to the raw address. Do not mistake wallet acknowledgement, outbound messages, fake jettons or an untrusted indexer flag for final payment. Choose and validate the rate/indexer providers and availability/finality policy before launch. Show the TON amount, expiry, recipient, native TON network and extra network fee before wallet confirmation; never estimate fees as zero.

## Still needed from the owner / deployment work

1. Choose explicit Stars tariffs or an agreed transparent conversion method, including fees/rounding and price refresh policy; approve terms/refund/support flow. The core accepts fixed integer amounts, not a currency conversion feed. Keep crypto unavailable inside the bot.
2. Create/choose the official bot and merchant account. Put secrets directly in the backend host's secret manager, **not in chat, Git or APK**. Never request a wallet seed/private key; incoming TON verification does not need one.
3. Cloudflare free hosting with a neutral Pages URL and private Worker/SQLite Durable Object namespace is prepared. The deployed scaffold does not run payment logic or claim production storage readiness. Complete Telegram account verification, session/pairing flow, TON rate/indexer selection and checkout-policy review. Qualify the actual storage/runtime, quotas and reliable delivery before connecting payment logic. Add reviewed webhook endpoints, durable outbox/recovery, reconciliation, refunds and abuse controls. No paid VPS is needed for this initial hosting stage.
4. Test sandbox/end-to-end account binding, concurrency, crashes, webhook retries, duplicate/late payments, no-payment and invalid-code paths. Then run small authorized real-payment/refund checks. No real payments were run for this change.
5. Wire Android and bot to the reviewed service only after successful tests. Until then the UI must clearly say activation/payment is not configured, not accept money or pretend success. All features stay ungated.

## Run tests

Node.js 22+; no packages or install step:

```sh
node --test Tools/lunagram-billing/tests/billing.test.mjs Tools/lunagram-billing/tests/sqlite-repository.test.mjs Tools/lunagram-billing/tests/cloudflare-scaffold.test.mjs
```

Primary references checked on 2026-10-11:

- [Crypto Pay API](https://help.send.tg/en/articles/10279948-crypto-pay-api)
- [Telegram digital-goods/Stars payments](https://core.telegram.org/bots/payments-stars)
- [Telegram webhook API](https://core.telegram.org/bots/api#setwebhook)
- [TON address formats](https://docs.ton.org/foundations/addresses/formats)
