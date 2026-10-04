# BlackHoleGram 12.10.6-bhg.66

Work in progress; not a published update until the signed APK and manifest have been verified.

- Upstream base: Telegram Android 12.10.6, commit `f2908b14133bbffbf7ab04f641ecb5bfaf533242` (30 September 2026), replacing 12.9.0 / `9bcf3d2769c6d3f07105a992e5d9493e33ac3348`.
- Displayed brand is BlackHoleGram. Package, signing key, preference keys and update URL remain the same as LumaGram, allowing an in-place update.
- Two main settings entries: BlackHoleGram Settings (Liquid Glass, Input and Messages, Updates) and BlackHoleGram Advanced (all previous experimental controls).
- Custom ghost, manual read, server-scheduled sends, local profile overrides, deleted/edit history, gift controls, export, typing, message formatting, notifications and Liquid Glass integrations are retained and reviewed against the changed upstream paths.
- SDK 36, Gradle 8.13, AGP 8.13.2 and the upstream pinned Media3/native submodules are used.

Validation to finish: full Android compilation, signed arm64 APK verification, source-preservation checks, regression tests and updater publication.

No Android phone is connected. Local JVM/source checks do not establish real-device UI, Firebase registration or Telegram server-side presence. Firebase/Google Cloud configuration is not changed by this migration.
