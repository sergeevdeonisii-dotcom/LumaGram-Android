# Subscription activation extension

## Current tier extension (source candidate, not released)

The user requested actual paid public tiers and a warning followed by disabling paid functions on confirmed expiry. The screen now describes cumulative active tiers, and separately says that the personal edition is unrestricted. Runtime authority is outside the visual catalog: authenticated owner-bound server terms plus a bounded monotonic lease. A cached wall-clock timestamp alone never grants access. Reboot requires online verification; same-boot offline access is limited to 24 hours and the term boundary. A failed request does not itself mean expiry. Stored preferences/data are retained; protected vaults never automatically unlock. The earlier preparation-only notes below record the preceding UI pass, not the new runtime behavior.

Updated review is in progress: Android36 screen compilation, 1017 extracted screen checks and 224 auth contracts passed after the tier text/client extension. Authority, feature call-site and native gift checks are separate; no phone test or real-money purchase is claimed. The user will test the phone. No new raster or visual identity was introduced.

Mode: Operate. Extend the existing Telegram-native subscription screen; no new visual identity.

## Direction contract

THESIS: Show whether this Telegram account is connected and whether a server-confirmed optional subscription exists. Price selection is not payment or entitlement.

OWN-WORLD: Inherit the current ActionBar, UniversalRecyclerView, two-line radio cells, theme tokens, and wrapped native input. Preserve light/dark and account-aware settings navigation.

STORY: Read account state; connect through explicit approval in the trusted payment bot; return to a bounded status check; buy Stars in the bot or redeem a code on the authenticated server.

FIRST VIEWPORT: Access-distribution preparation note, one grouped native account section with status and connect/check actions, then duration selection and total-priced plans. Errors remain inside the account section. A bottom footer distinguishes current server-confirmed dates from historical records.

FORM: Local extension of the incumbent settings list. No concept seed is applicable under the local-extension rule. The signature interaction is a live pending-to-connected status change, not decorative motion.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

Verification constraint for this pass: the user has paused phone access; no device operations, purchases or heavy build were performed. Source/JVM checks establish contracts, not rendered phone/tablet/theme/font-scale quality. No raster assets introduced. Existing system documentation is not replaced.

## Implementation and inherited system

The extension uses the incumbent ActionBar, sectioned UniversalRecyclerView, two-line checked radio rows, `Theme.key_windowBackgroundGray`, and `LumaDialogInput`. Settings retains the current Telegram account. Screenshot 21988 exposed a header-only account pill: `asShadow(connectionText)` prematurely ended its section. The correction uses a normal native `TextInfoPrivacyCell` via `asCustom`, followed by actions and only then a shadow separator; theme/RTL/native text wrapping remain inherited. The correction is source/model verified, not yet captured on device.

Connection starts an expiring verifier/challenge pair and opens only the validated `@LunagramAppPayBot` approval link. The client verifies the captured Telegram owner. Foreground pairing checks run every three seconds; pause/disposal cancel work. A restored encrypted snapshot is historical evidence only and does not skip a live status refresh. Existing feature access remains unchanged; the cumulative pure feature catalog is used for honest planned-description text only, not authorization.

Duration radios select 1/3/6/12 months. Plan and duration rows use approved catalog totals, not multiplied monthly claims; dollar amounts are labeled references. Purchase delegates `Plan` and `Period` to the client's trusted bot URL. Crypto Bot and TON remain unavailable. The activation dialog validates bounded ASCII input, requests a non-consuming v2 preview, displays the server plan, period, Stars reference and start/end dates, then requires an explicit Activate button. IME Done can only preview. Error input is retained; confirmed success or explicit dismissal clears it. Quote cancellation, validity and idempotent retries belong to the client, not a duplicated UI expiry rule.

The bottom footer uses server-anchored time and freshness; an old `active` observation is never promoted to current entitlement. It shows at most three queued terms plus their remaining count, paid-until and server-check dates. A queued start that passed is labeled awaiting refresh until server confirmation. A single nearest term-boundary callback refreshes once and is canceled on pause/destroy; there is no per-second polling. Armed privacy preferences, vaults, existing data and active recordings are never changed here.

Billing is limited to production Telegram accounts; TestDC accounts cannot use the production client or purchase action. On API 21–22, the connection is memory-only for the fragment/client lifetime and the screen explains this. On API 23+, persistence uses the existing Android Keystore AES-GCM path, binding the owner and production role `lunagram_subscription_session_v1_prod` as authenticated data. Storage failures have no plaintext fallback.

## Evidence and handoff

The earlier handoff below remains historical v1 evidence. The current v2 UI pass adds actual-extracted duration/cumulative-feature/footer/confirmation/lifecycle model checks, real Android 36 API compilation, and matching uniquely named EN/RU subscription strings. The precise current results are reported with the source handoff; no full APK/device appearance or real purchase claim follows from these probes.

| Recorded check | Evidence scope |
| --- | --- |
| Actual billing client: PASS, 83 assertions | Fake transport/storage and controlled JSONObject fixture; account, pairing, redemption, cancellation and response contracts |
| Pure catalog/input: PASS, 55 assertions | Plan values/order and bounded code normalization |
| Screen/client compilation: PASS | Android 36 platform API with modeled Telegram UI collaborators |
| EN/RU resources: PASS | aapt2 compilation of the 49 subscription strings in each locale, separately from the full resource merge |

The independent finish review's verdict pass scored all four listed fixes resolved at source/model scope: actionable backend error mappings and pairing cleanup; a total 15-second monotonic TLS request deadline with disconnect watchdog; HTTP 401 cleanup before reading/parsing its body; and production-only billing with the TestDC guard. The transport source also retains standard TLS 1.2 trust, fixed host/paths, disabled redirects, and a 16 KiB response limit.

This evidence does not establish real purchases, bot messages or HTTP behavior, rendered phone/tablet/light/dark/font-scale quality, a full app build, the real JSON parser, or Android Keystore runtime behavior. No native design detector ran and no raster assets were introduced.

This is an ordinary extension of the established native system. Root `PRODUCT.md`, `DESIGN.md`, and `.impeccable/design.json` are absent in the incumbent checkout; this scoped handoff records the implemented extension without inventing a replacement system. The direction contract's generic FINISH line is applied through the ordinary-extension rule: scoped review and these evidence notes complete this documentation pass. Later shipping-source changes require the affected review/evidence notes to be checked again.
