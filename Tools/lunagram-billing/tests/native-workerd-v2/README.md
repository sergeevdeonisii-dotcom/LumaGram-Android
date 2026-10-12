# Local-only native workerd v2 regression

Never deploy this fixture, bind production storage, load production secrets, or use real Telegram receipts. `worker.mjs` hardcodes clearly synthetic keys/identities and a fake-only sale flag. It replaces external `fetch` with a rejection and replaces the Telegram API with a bounded mock. It accepts test requests only on a loopback hostname. No actual Telegram payment or refund occurs.

The fixture imports the real `BillingStore`, bot dispatcher, PKCE authentication, Pages response validation, HTTP routes and SQLite transaction store. Its fixed clock, fake Bot API and controlled alarms are test substitutions, not a production recovery qualification.

With an existing Wrangler 4 installation, choose an unused loopback port and a **new** dedicated local persistence directory, then run:

```sh
wrangler dev --local --config Tools/lunagram-billing/tests/native-workerd-v2/wrangler.local.jsonc --ip 127.0.0.1 --port 8792 --inspector-port 0 --persist-to D:/CodexTemp/lunagram-v2-new-test-state
```

POST an empty JSON object to `http://127.0.0.1:8792/exercise`. Expect 200/`ok:true`. Stop that Wrangler process completely; restart the same command with the **same** persistence directory. POST an empty JSON object to `/resume`; expect 200/`ok:true`. Stop the test server afterward. Do not clear a shared or production directory to repeat the test; choose another new test directory.

Verified 2026-10-12 with Wrangler 4.149.0/native workerd and real local Durable Object SQLite: both phases passed on a clean database. Initial phase checks Standard/3 months = 180 Stars, Ultra/12 months = 2520 Stars, duplicate fake receipt handling, encrypted key/outbox delivery (10 mock ACKs), actual verified PKCE/Pages/DO routing, v1 incompatibility guard and genuine legacy compatibility, immutable mixed-tier queue, and SQL-trigger rollback before retry. After a full process stop/start, the expired quote retries without a second grant; a pending refund retains the active term, a confirmed refund preserves other dates and historical rows, and legacy expiry is unchanged.

The fixture persists its synthetic Telegram update counter across process restarts, as real Telegram update IDs do not reset when our service restarts. Only safe booleans/counts/prices are returned as evidence; keys, auth tokens and encrypted record contents are not reported.
