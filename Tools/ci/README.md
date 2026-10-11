# Temporary cloud compilation for Lunagram 1.0.4

Current workflow: `.github/workflows/lunagram-release100-apks.yml` (historical
filename retained), owner repository only, branch `agent/lunagram-release100`.
Version name is `1.0.4`, standalone arm64 version code is `71669`.

| Edition | `lumaFriendsEdition` | `lumaPublicEdition` | Delivery name | Privacy/local-number tools | Enhanced horizon / personal updater |
| --- | --- | --- | --- | --- | --- |
| Personal (`full`) | false | false | `Lunagram-1.0.4-personal-arm64.apk` | Yes | Yes |
| General release (`public`) | false | true | `Lunagram-1.0.4-public-arm64.apk` | Yes | No |
| Restricted Friends (`friends`) | true | false | `Lunagram-1.0.4-friends-arm64.apk` | No | No |

The general release is NOT the restricted Friends edition. The mistaken
historical `.100`/`.101` friends-to-public naming must never be applied to `.102` or later.
Both edition flags true is invalid. Default CI compiles `full` then `public`;
manual dispatch can add the third Friends APK with `build_friends=true`.
Each APK is copied and inspected before Gradle reuses the shared output path.

CI has no production signing key. Artifact `lunagram-104-temporary-signed`
contains the selected temporary APKs and schema-2 `build-metadata.json` with
explicit release `104`, exact workflow commit, identity, ABI, both edition flags
and hashes. Collector tests and camera/local-gift/security regressions run first.
Local signing must validate the exact DEX capability policy as well as metadata,
signature, manifest, hashes and unchanged application payload. Genuine public
must never receive the private updater feed or the personal enhanced horizon.
Neither signing nor CI compilation publishes a release or modifies an update feed.
The collector retains the immutable `.102`/`.103` metadata/version contracts for
existing artifacts; old APKs are never accepted or relabelled as the `.104` hotfix.
Collector and local signing/payload helpers now default to `.104`; inspecting
historical artifacts requires an explicit `--release 103` / `-Release 103` (or `102`).

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
