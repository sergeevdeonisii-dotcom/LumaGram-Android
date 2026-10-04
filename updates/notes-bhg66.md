# BlackHoleGram 12.10.6-bhg.66

The final signed arm64 APK is verified, downloaded back from GitHub and published in release `v12.10.6-bhg.66`. The updater manifest points to this verified release artifact; source changes alone do not deliver an Android update.

- Upstream base: Telegram Android 12.10.6, commit `f2908b14133bbffbf7ab04f641ecb5bfaf533242` (30 September 2026), replacing 12.9.0 / `9bcf3d2769c6d3f07105a992e5d9493e33ac3348`.
- Displayed brand is BlackHoleGram. Package, signing key, preference keys and update URL remain the same as LumaGram, allowing an in-place update.
- Two main settings entries: BlackHoleGram Settings (Liquid Glass, Input and Messages, Updates) and BlackHoleGram Advanced (all previous experimental controls).
- A free optional Black Hole launcher icon is added alongside the five existing Luma icons, with adaptive, legacy and themed monochrome resources. Existing launcher selection is not changed automatically.
- Custom ghost, manual read, server-scheduled sends, local profile overrides, deleted/edit history, gift controls, export, typing, message formatting, notifications and Liquid Glass integrations are retained and reviewed against the changed upstream paths.
- SDK 36, Gradle 8.13, AGP 8.13.2 and the upstream pinned Media3/native submodules are used.

Passed: local JVM regression tests (presence request state, account isolation, local rating, delay, updater source races, export and ghost scheduling); source/resource integration checks; `:TMessagesProj:compileStandaloneJavaWithJavac` with all Media3 modules (BUILD SUCCESSFUL, 137 tasks).

Also corrected integration collisions introduced by the base upgrade: separate manual-read/new welcome-revert menu IDs, separate custom/new round-video settings row IDs, welcome-template exclusions in automatic scheduling and deleted-message retention, Windows path normalization for Media3 module configuration, and explicit library resource references in the standalone updater for the new non-transitive R classes. New settings navigation explicitly retains the originating account.

The final icon-inclusive APK assembled successfully (332 tasks; 35 executed, 297 up-to-date; 23m 3s). It passed package/version, the Black Hole alias in the packaged manifest, existing release-certificate/signature, ZIP/native LOAD alignment and strict Telegram RELRO checks. The previously compiled native library was reused (`ninja: no work to do`). Final JVM/source/native-parser regressions and the production source's GitHub CodeQL checks passed. The artifact verifier rejects the earlier icon-less candidate.

APK: `org.luma.liquid.web`, version `12.10.6-bhg.66`, code `71459`, arm64 only, target SDK 36. Size: 36,936,967 bytes. SHA-256: `df3bbce4470a72f35ade31a58332477370aab464ede9d082af927e5a5b88d6df`. The release certificate SHA-256 remains `24a3777b3b0b2d353b0452aa166660f5ad39e0f50aa2124d95b85978880c7cd9`.

Production revision compiled: `8bdf219eff7a2918ce90622fcd1b3963883172d8`, merged into `main` via PR #62 (`b510f39e35374875b68f08dd14e192b79d700ad8`). Release metadata commits do not change application code.

The upstream ML Kit language-identification library has a RELRO-end alignment warning, also present in the old .65 APK. Its LOAD segments pass the 16 KiB static check, but full runtime compatibility on a real 16 KiB device is not claimed; this unchanged vendor library is not patched.

No Android phone is connected. Local JVM/source checks do not establish real-device UI, Firebase registration or Telegram server-side presence. Firebase/Google Cloud configuration is not changed by this migration.
