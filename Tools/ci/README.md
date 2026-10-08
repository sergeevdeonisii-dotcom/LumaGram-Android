# Temporary cloud compilation for Lunagram .79

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
