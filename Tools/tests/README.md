# Luma regression tests

The .71 tools model tests cover local search, strict portable-settings validation,
preset/custom profiles, identity-bound vault sessions with stale authentication rejection,
real AES-GCM encryption/tamper/AAD tests, note limits, and notification history limits/deduplication.
`check-blackholegram-tools71.ps1` checks navigation, document picker and privacy source wiring.
JSONObject/JSONArray and private storage are controlled JVM adapters, not real JSON parsing
or Android Keystore tests. The crypto codec uses real JDK cryptography. Native PIN/fingerprint,
Keystore and UI behaviour still need device testing. The vault is a UI privacy lock, not
an encryption layer for Telegram's existing message database.

The .72 bot-height regressions compile the production draft measurement controller
against isolated view/message adapters. They cover draft shrinking, final ID/group
replacement, reply-keyboard viewport changes, long final answers, ordinary messages,
disabled selection, and detached views. They require compact live "Thinking" drafts
and pending outgoing rows, including the outgoing server-ID acknowledgement, so the
old viewport filler cannot recreate the large bottom gap or up/down sending bounce.
The cell's content-height cache invalidation
is source-checked; these tests are not rendered Android UI tests and do not establish
that every bot's arbitrary trailing whitespace or animation issue is eliminated.
Run `run-bot-layout-regressions.ps1` separately or via the main runner below.

Run from the repository root with JDK 17:

```powershell
.\Tools\tests\run-luma-regressions.ps1 -JavaHome 'C:\path\to\jdk-17'
```

The runner compiles the production Luma sources listed in the script, together with small Android/Telegram transport fixtures. It writes isolated test files under ignored `Tools/tests/.runs/`; it never reads real account data, contacts Telegram/Firebase, or installs an APK.

Coverage:

- Presence request retries, stale responses, cancellation and rapid status changes.
- Ghost scheduling: per-account enablement, persisted switches, 20 seconds from server time, explicit dates and send-when-online preservation, secret-chat exclusion and device-time fallback.
- Per-user local preferences, reused login slots, same user in another slot, logout cleanup, one-time migration of protective switches, and refusal to import unattributed legacy history/deleted IDs.
- Levels 1–100 without a server rating, stable bounded progress, disabled overrides, and unchanged server/other-user objects.
- Detached local sends obeying their deadline and refusing to send under a replacement user identity.
- Installer cleanup restricted to direct versioned APK files; future, nested, outside and unrelated files remain untouched. Startup also cleans legacy orphan APKs.
- An old manifest response arriving before/after a replacement source, immediate new-source checking, single completion of the invalidated request, and rejection of HTTP sources.
- Late child completion after export cancellation, cleanup of the late archive, one terminal callback, and successful normal account export.

Fixtures deliberately control asynchronous ordering and manifest fields. They are not substitutes for real JSON parsing, APK-signature validation, Android lifecycle/UI tests, Firebase registration, or a server-side presence/push check.

The .70 audit regressions additionally compile the real `LumaMessageFormatting` and `LumaGiftVisibilityOperation`. They cover explicit local deletion decisions and tombstone removal, ephemeral-cloud-media ghost policy (without bypassing view-once/TTL semantics), cancellation and stale-owner handling for gift operations, preservation of manual collapsed quotes, joining concurrent update checks, and recovery of cleared/invalid update sources. Run `./Tools/tests/check-blackholegram-audit70.ps1` for the storage/UI propagation, album drawing, opening-entry guards, lifecycle cancellation and updater-screen integration checks. Those assertions inspect source; album rendering, actual SQLite deletion, real Telegram gift requests and media-viewing behavior still need device tests.

Gift partial-failure handling and the chat lifecycle integration are additionally checked by source review and Android compilation; the JVM tests exercise the detached sender, not the actual chat screen.

The local undo-send delay remains an in-process timer. Closing/swapping the chat no longer shortens it, but forcible process termination before the deadline is not a durable offline queue. The independent 20-second ghost sender schedules messages on Telegram's server.

For the BlackHoleGram 12.10.6 migration, also run `./Tools/tests/check-blackholegram-upstream.ps1`. It checks the two top-level settings entries and retained child screens, brand strings, unchanged package, unique menu IDs, welcome-template exclusions and key custom/upstream integration points. These assertions inspect source and resources, not rendered Android UI.

Before publishing version .66, run `./Tools/tests/verify-blackholegram-apk.ps1 -ApkPath <signed APK> -SdkPath <Android SDK> -JavaHome <JDK 17>`. It checks package/version/label, target SDK, arm64 native library version, the existing signing certificate, signature validity, ZIP alignment and every packaged native library's ELF LOAD alignment for 16 KiB pages. It requires aligned RELRO ends in our compiled Telegram library and reports unaligned RELRO ends in vendor libraries as compatibility warnings, not as proven runtime failures. The upstream ML Kit language-identification library in the previous .65 APK has such a warning; unchanged third-party code is not modified or represented as device-tested. The default NDK is `27.2.12479018`; pass `-NdkVersion` to override. Extracted native inspection artifacts are retained under ignored `Tools/tests/.runs/`. This does not replace device testing or prove complete 16 KiB-device compatibility. The alignment checks follow [Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes).

Run `./Tools/tests/test-native-alignment.ps1` to test the ELF-header parser against synthetic aligned, 4 KiB, bad RELRO, incongruent-offset, malformed and missing-header fixtures. These parser fixtures do not establish a real APK's alignment; that requires the artifact verifier above.

The source checks also require the optional non-premium Black Hole icon and all five previous custom launcher aliases. APK verification requires the new alias in the merged, packaged manifest, preventing publication of the earlier icon-less candidate. For visual review, `node Tools/tests/render-blackhole-icon.cjs <res directory> <preview.png> <node_modules directory>` renders the actual vector path geometry under rounded, circular and illustrative themed masks using Sharp. This is a vector preview, not an Android screenshot or a device UI test.
