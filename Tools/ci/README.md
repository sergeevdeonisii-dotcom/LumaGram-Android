# Temporary cloud compilation for Lunagram 1.0.5

Current workflow: `.github/workflows/lunagram-release100-apks.yml` (historical
filename retained), owner repository only, branch `agent/lunagram-release100`.
Version name is `1.0.5`, base version code is `7167`, and standalone arm64 version
code is `71679` (the existing ABI suffix is `9`).

| Edition | `lumaFriendsEdition` | `lumaPublicEdition` | Delivery name | Privacy/local-number tools | Enhanced horizon / personal updater |
| --- | --- | --- | --- | --- | --- |
| Personal (`full`) | false | false | `Lunagram-1.0.5-personal-arm64.apk` | Yes | Yes |
| General release (`public`) | false | true | `Lunagram-1.0.5-public-arm64.apk` | Yes | No |

The general release is NOT the restricted Friends edition. The mistaken
historical `.100`/`.101` friends-to-public naming must never be applied to `.102` or later.
Both edition flags true is invalid. This release compiles only `full` then `public`;
Friends selection and release-105 Friends artifacts are rejected.
Each APK is copied and inspected before Gradle reuses the shared output path.

CI has no production signing key. Artifact `lunagram-105-temporary-signed`
contains exactly `Lunagram-1.0.5-full-temporary.apk`,
`Lunagram-1.0.5-public-temporary.apk` and schema-2 `build-metadata.json` with
explicit release `105`, exact workflow commit, identity, ABI, both edition flags
and hashes. No wildcard upload can include extra APKs or credential files.
Collector tests and camera/local-gift/security regressions run first. A separate
credential-free SDK step installs the pinned Android 36 tools, then three local
subscription probes check auth contracts, catalog/EN-RU resources with AAPT2,
and actual screen/client compilation against Android 36 with modeled Telegram UI
collaborators. All run before the secret-bearing disposable-key/Gradle step.
They do not contact the billing server, make purchases or validate device Keystore
and rendered UI behavior; the full app build remains necessary.
Local signing must validate the exact DEX capability policy as well as metadata,
signature, manifest, hashes and unchanged application payload. Genuine public
must never receive the private updater feed or the personal enhanced horizon.
Neither signing nor CI compilation publishes a release or modifies an update feed.
The collector retains the immutable `.102`/`.103`/`.104` metadata/version contracts
for existing artifacts, including their historical Friends validation; old APKs
are never accepted or relabelled as `.105`. The collector defaults to `.105`;
historical artifacts require an explicit `--release 104` (or `103`/`102`). Local
production signing is separate: any legacy `.79` signing helper must not be used
for this pair without a reviewed `.105` identity and full/public policy update.

The checkout stays at depth 1. Before the visual regression suites, a dedicated
helper fetches only the immutable pre-fix commit
`8f60669bc4c257402d18bedd67e1ab71d0f59df8` with `--no-tags --depth=1` when absent,
verifies its exact identity and checks that `HEAD` did not change. This provides
the negative-control source without downloading the whole repository history.
Dialogs canvas, blur-capture and gift-geometry regressions block APK compilation.

Collector/workflow tests (no Gradle, signing or external network; the baseline
proof creates a small temporary local Git repository and depth-1 clone):

```text
node --test Tools/ci/collect-lunagram-apk.test.mjs Tools/ci/release100-workflow.test.mjs
```

## Historical .79 workflow

The workflow runs only on `agent/lunagram79-friends-cloud` in the owner's repository.
Required Actions secret names: `LUMA_API_ID`, `LUMA_API_HASH`.

No release keystore or release-key password is needed or accepted by this workflow.
It creates a disposable signing key on the hosted runner. The APKs are not production
updates until the owner verifies their source, identity, edition policy and hashes,
then re-signs them locally with the original release key. Never install these
temporary-signed builds over the production client or publish them to its update feed.

The job checks out pinned submodules, installs JDK 17 / SDK 36 / Build Tools 36.0.0 /
NDK 27.2.12479018 / CMake 3.22.1, then builds only the standalone arm64 variant.
It uses checked-in native archives, not the destructive all-ABI prebuild script.
Full and Friends compile sequentially with two workers and a bounded 4 GiB heap.
Full is copied out before Friends overwrites Gradle's shared output path.

Artifact `lunagram-79-temporary-signed` contains exactly the two temporary APKs and
`build-metadata.json`, retained for three days. Metadata includes source commit,
actual package/version, architecture, edition flags and APK hashes, never credential
files or the temporary signing key. Uploading it does not create a release or modify
`updates/latest.json`.

Collector pure validation tests (no Gradle, network, signing or account data):

```text
node --test Tools/ci/collect-lunagram-apk.test.mjs
```
