# Luma regression tests

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

Gift partial-failure handling and the chat lifecycle integration are additionally checked by source review and Android compilation; the JVM tests exercise the detached sender, not the actual chat screen.

The local undo-send delay remains an in-process timer. Closing/swapping the chat no longer shortens it, but forcible process termination before the deadline is not a durable offline queue. The independent 20-second ghost sender schedules messages on Telegram's server.

For the BlackHoleGram 12.10.6 migration, also run `./Tools/tests/check-blackholegram-upstream.ps1`. It checks the two top-level settings entries and retained child screens, brand strings, unchanged package, unique menu IDs, welcome-template exclusions and key custom/upstream integration points. These assertions inspect source and resources, not rendered Android UI.

Before publishing version .66, run `./Tools/tests/verify-blackholegram-apk.ps1 -ApkPath <signed APK> -SdkPath <Android SDK> -JavaHome <JDK 17>`. It checks package/version/label, target SDK, arm64 native library version, the existing signing certificate, signature validity, ZIP alignment and every packaged native library's ELF LOAD alignment for 16 KiB pages. It requires aligned RELRO ends in our compiled Telegram library and reports unaligned RELRO ends in vendor libraries as compatibility warnings, not as proven runtime failures. The upstream ML Kit language-identification library in the previous .65 APK has such a warning; unchanged third-party code is not modified or represented as device-tested. The default NDK is `27.2.12479018`; pass `-NdkVersion` to override. Extracted native inspection artifacts are retained under ignored `Tools/tests/.runs/`. This does not replace device testing or prove complete 16 KiB-device compatibility. The alignment checks follow [Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes).

Run `./Tools/tests/test-native-alignment.ps1` to test the ELF-header parser against synthetic aligned, 4 KiB, bad RELRO, incongruent-offset, malformed and missing-header fixtures. These parser fixtures do not establish a real APK's alignment; that requires the artifact verifier above.

The source checks also require the optional non-premium Black Hole icon and all five previous custom launcher aliases. APK verification requires the new alias in the merged, packaged manifest, preventing publication of the earlier icon-less candidate. For visual review, `node Tools/tests/render-blackhole-icon.cjs <res directory> <preview.png> <node_modules directory>` renders the actual vector path geometry under rounded, circular and illustrative themed masks using Sharp. This is a vector preview, not an Android screenshot or a device UI test.
