# BlackHoleGram 12.10.6-bhg.66

Work in progress; not a published update until the signed APK and manifest have been verified.

- Upstream base: Telegram Android 12.10.6, commit `f2908b14133bbffbf7ab04f641ecb5bfaf533242` (30 September 2026), replacing 12.9.0 / `9bcf3d2769c6d3f07105a992e5d9493e33ac3348`.
- Displayed brand is BlackHoleGram. Package, signing key, preference keys and update URL remain the same as LumaGram, allowing an in-place update.
- Two main settings entries: BlackHoleGram Settings (Liquid Glass, Input and Messages, Updates) and BlackHoleGram Advanced (all previous experimental controls).
- A free optional Black Hole launcher icon is added alongside the five existing Luma icons, with adaptive, legacy and themed monochrome resources. Existing launcher selection is not changed automatically.
- Custom ghost, manual read, server-scheduled sends, local profile overrides, deleted/edit history, gift controls, export, typing, message formatting, notifications and Liquid Glass integrations are retained and reviewed against the changed upstream paths.
- SDK 36, Gradle 8.13, AGP 8.13.2 and the upstream pinned Media3/native submodules are used.

Passed: local JVM regression tests (presence request state, account isolation, local rating, delay, updater source races, export and ghost scheduling); source/resource integration checks; `:TMessagesProj:compileStandaloneJavaWithJavac` with all Media3 modules (BUILD SUCCESSFUL, 137 tasks).

Also corrected integration collisions introduced by the base upgrade: separate manual-read/new welcome-revert menu IDs, separate custom/new round-video settings row IDs, welcome-template exclusions in automatic scheduling and deleted-message retention, Windows path normalization for Media3 module configuration, and explicit library resource references in the standalone updater for the new non-transitive R classes. New settings navigation explicitly retains the originating account.

The first signed arm64 candidate assembled successfully (332 tasks, 37m 47s) and passed package/version, release-certificate/signature, ZIP and native LOAD alignment checks. The user's additional Black Hole icon was added after that candidate, so final icon-inclusive APK assembly, artifact checks and updater publication are still pending. The artifact verifier now requires the new launcher alias in the packaged manifest and rejects the earlier icon-less candidate.

The upstream ML Kit language-identification library has a RELRO-end alignment warning, also present in the old .65 APK. Its LOAD segments pass the 16 KiB static check, but full runtime compatibility on a real 16 KiB device is not claimed; this unchanged vendor library is not patched.

No Android phone is connected. Local JVM/source checks do not establish real-device UI, Firebase registration or Telegram server-side presence. Firebase/Google Cloud configuration is not changed by this migration.
