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
