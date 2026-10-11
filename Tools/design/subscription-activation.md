# Subscription activation extension

Mode: Operate. Extend the existing Telegram-native subscription screen; no new visual identity.

## Direction contract

THESIS: Show whether this Telegram account is connected and whether a server-confirmed optional subscription exists. Price selection is not payment or entitlement.

OWN-WORLD: Inherit the current ActionBar, UniversalRecyclerView, two-line radio cells, theme tokens, and wrapped native input. Preserve light/dark and account-aware settings navigation.

STORY: Read account state; connect through explicit approval in the trusted payment bot; return to a bounded status check; buy Stars in the bot or redeem a code on the authenticated server.

FIRST VIEWPORT: Access-without-subscription note, account status and connect/check action, followed by monthly plans. Errors remain next to the account actions, never hidden in a transient toast.

FORM: Local extension of the incumbent settings list. No concept seed is applicable under the local-extension rule. The signature interaction is a live pending-to-connected status change, not decorative motion.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

Verification constraint: no device operations or heavy build authorized; source/JVM checks can establish contracts, not rendered phone/tablet/theme/font-scale quality. No raster assets introduced. Existing system documentation is not replaced.

## Implementation and inherited system

The completed extension uses the incumbent ActionBar, sectioned UniversalRecyclerView, two-line checked radio rows, `Theme.key_windowBackgroundGray`, and `LumaDialogInput` with the existing dialog theme tokens. Settings opens the fragment with its current Telegram account. Source inspection confirms the access note, account state/actions and inline errors precede the monthly plans; rendered first-viewport placement remains unverified.

Connection starts an expiring verifier/challenge pair and opens only the validated `@LunagramAppPayBot` approval link. The client accepts an authenticated session only when the server-reported Telegram user matches the captured account owner. Foreground pairing checks run every three seconds; pause/disposal cancel transport, and generation/account guards reject late results. A restored session does not supply a cached paid entitlement. Subscription status and code redemption require server confirmation, with `featuresGated: false`; existing app features remain available.

Stars purchase selection opens the trusted bot's plan link. Crypto Bot and TON remain visibly unavailable. The activation dialog checks the 16-character ASCII code, clears submitted/dismissed input, disables saved view state and supported autofill/personalized learning, and retains inline validation feedback.

Billing is limited to production Telegram accounts; TestDC accounts cannot use the production client or purchase action. On API 21–22, the connection is memory-only for the fragment/client lifetime and the screen explains this. On API 23+, persistence uses the existing Android Keystore AES-GCM path, binding the owner and production role `lunagram_subscription_session_v1_prod` as authenticated data. Storage failures have no plaintext fallback.

## Evidence and handoff

The documenter checked the actual client and screen, Settings routing, native input wrapper, private/sealed storage source, both direction-contract copies, EN/RU subscription blocks, and regression fixtures/scripts. EN and RU each contain 49 unique subscription keys with matching key sets. These are source observations; the following PASS results were recorded by the implementation handoff, without rerunning builds in the documentation pass.

| Recorded check | Evidence scope |
| --- | --- |
| Actual billing client: PASS, 83 assertions | Fake transport/storage and controlled JSONObject fixture; account, pairing, redemption, cancellation and response contracts |
| Pure catalog/input: PASS, 55 assertions | Plan values/order and bounded code normalization |
| Screen/client compilation: PASS | Android 36 platform API with modeled Telegram UI collaborators |
| EN/RU resources: PASS | aapt2 compilation of the 49 subscription strings in each locale, separately from the full resource merge |

The independent finish review's verdict pass scored all four listed fixes resolved at source/model scope: actionable backend error mappings and pairing cleanup; a total 15-second monotonic TLS request deadline with disconnect watchdog; HTTP 401 cleanup before reading/parsing its body; and production-only billing with the TestDC guard. The transport source also retains standard TLS 1.2 trust, fixed host/paths, disabled redirects, and a 16 KiB response limit.

This evidence does not establish real purchases, bot messages or HTTP behavior, rendered phone/tablet/light/dark/font-scale quality, a full app build, the real JSON parser, or Android Keystore runtime behavior. No native design detector ran and no raster assets were introduced.

This is an ordinary extension of the established native system. Root `PRODUCT.md`, `DESIGN.md`, and `.impeccable/design.json` are absent in the incumbent checkout; this scoped handoff records the implemented extension without inventing a replacement system. The direction contract's generic FINISH line is applied through the ordinary-extension rule: scoped review and these evidence notes complete this documentation pass. Later shipping-source changes require the affected review/evidence notes to be checked again.
