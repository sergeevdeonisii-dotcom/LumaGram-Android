#!/usr/bin/env bash
# Public test candidate only. Production re-signing and private delivery remain local.
set +x
set -Eeuo pipefail
umask 077

fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }
[[ $# == 1 && ( "$1" == --prepare-sdk || "$1" == --build ) ]] || fail 'Choose exactly --prepare-sdk or --build.'
[[ "${GITHUB_ACTIONS:-}" == true ]] || fail 'GitHub Actions only.'
[[ "${GITHUB_REPOSITORY:-}" == sergeevdeonisii-dotcom/LumaGram-Android ]] || fail 'Unexpected repository.'
[[ "${GITHUB_REF:-}" == refs/heads/agent/lunagram-300-test ]] || fail 'Unexpected source branch.'
[[ "${GITHUB_EVENT_NAME:-}" == push || "${GITHUB_EVENT_NAME:-}" == workflow_dispatch ]] || fail 'Unexpected workflow event.'
[[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" ]] || fail 'Runner temporary directory is unavailable.'
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
source_commit="$(git rev-parse HEAD)"
[[ "$source_commit" == "${GITHUB_SHA:-}" ]] || fail 'Checkout does not match the workflow source commit.'
git diff --quiet && git diff --cached --quiet || fail 'Tracked source has changed after checkout.'
submodule_status="$(git submodule status --recursive)"
if grep -q '^[+U-]' <<< "$submodule_status"; then fail 'Pinned submodules are missing or mismatched.'; fi

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] || fail 'The Android SDK is unavailable.'
export ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root"
if [[ "$1" == --prepare-sdk ]]; then
    [[ -z "${LUMA_API_ID:-}" && -z "${LUMA_API_HASH:-}" ]] || fail 'SDK provisioning must not receive API credentials.'
    sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
    [[ -x "$sdkmanager" ]] || sdkmanager="$(command -v sdkmanager || true)"
    [[ -x "$sdkmanager" ]] || fail 'sdkmanager is unavailable.'
    (set +o pipefail; yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null)
    "$sdkmanager" --sdk_root="$sdk_root" 'platform-tools' 'platforms;android-36' \
        'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
    exit 0
fi

[[ "${LUMA_API_ID:-}" =~ ^[1-9][0-9]{0,9}$ ]] || fail 'API ID is missing or invalid.'
(( LUMA_API_ID <= 2147483647 )) || fail 'API ID is outside the Android integer range.'
[[ "${LUMA_API_HASH:-}" =~ ^[[:xdigit:]]{32}$ ]] || fail 'API hash is missing or invalid.'
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/keytool" && -x "$JAVA_HOME/bin/javap" ]] || fail 'JDK is unavailable.'
command -v node >/dev/null || fail 'Node.js is unavailable.'
command -v openssl >/dev/null || fail 'OpenSSL is unavailable.'
[[ "${LUMA_BUILD_FRIENDS_EDITION:-false}" == false && "${LUMA_BUILD_PERSONAL_EDITION:-false}" == false ]] || fail 'Only the public test edition is allowed.'
for archive in libavcodec libavformat libavutil libcrypto libssl libdav1d libiwasm \
        libopenh264 libopus libswresample libswscale libtde2e libtdutils libtlottie libvpx; do
    [[ -s "TMessagesProj/jni/prebuild/lib/arm64-v8a/$archive.a" ]] || fail 'Missing checked-in arm64 native archive.'
done
build_tools="$sdk_root/build-tools/36.0.0"
for tool in aapt aapt2 apksigner; do
    [[ -x "$build_tools/$tool" ]] || fail 'Android build tools are unavailable.'
done
[[ -s "$sdk_root/platforms/android-36/android.jar" ]] || fail 'Android 36 platform is unavailable.'
[[ -d "$sdk_root/ndk/27.2.12479018" && -d "$sdk_root/cmake/3.22.1" ]] || fail 'Pinned native build tools are unavailable.'

artifact_dir="$RUNNER_TEMP/lunagram300-artifacts"
[[ ! -e "$artifact_dir" && ! -L "$artifact_dir" ]] || fail 'Artifact directory already exists; inspect the previous attempt.'
[[ ! -e local-luma.properties && ! -L local-luma.properties && ! -e local-signing && ! -L local-signing ]] || fail 'Private local configuration unexpectedly exists on the runner.'
git check-ignore -q local-luma.properties || fail 'API configuration must stay Git-ignored.'
git check-ignore -q local-signing/luma-signing.properties || fail 'Signing configuration must stay Git-ignored.'
mkdir -p "$artifact_dir"
signing_dir="$(mktemp -d "$RUNNER_TEMP/lunagram300-signing.XXXXXX")"
# Remove only this invocation's exact generated files, including on build failure/cancellation.
cleanup() {
    rm -f -- "$repo_root/local-luma.properties" "$repo_root/local-signing/luma-signing.properties" "$signing_dir/temporary.jks"
    rmdir -- "$signing_dir" "$repo_root/local-signing" 2>/dev/null || true
    unset LUMA_API_ID LUMA_API_HASH LUMA_CI_SIGNING_PASSWORD
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
export LUMA_CI_SIGNING_PASSWORD="$(openssl rand -hex 24)"
"$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$signing_dir/temporary.jks" \
    -storetype JKS -alias lunagram-ci-temporary -keyalg RSA -keysize 2048 -validity 7 \
    -storepass:env LUMA_CI_SIGNING_PASSWORD -keypass:env LUMA_CI_SIGNING_PASSWORD \
    -dname 'CN=Lunagram disposable CI key, OU=Temporary test build only' >/dev/null 2>&1
mkdir local-signing
printf 'API_ID=%s\nAPI_HASH=%s\n' "$LUMA_API_ID" "$LUMA_API_HASH" > local-luma.properties
printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=lunagram-ci-temporary\nkeyPassword=%s\n' \
    "$signing_dir/temporary.jks" "$LUMA_CI_SIGNING_PASSWORD" "$LUMA_CI_SIGNING_PASSWORD" > local-signing/luma-signing.properties
unset LUMA_API_ID LUMA_API_HASH LUMA_CI_SIGNING_PASSWORD

export CMAKE_BUILD_PARALLEL_LEVEL=2
jvm_args='-Xmx4g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
printf 'Building public test Lunagram 3.0.0 from %s.\n' "$source_commit"
"$JAVA_HOME/bin/java" -Xms32m -Xmx128m \
    -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
    ':TMessagesProj_AppStandalone:assembleAfatStandalone' \
    --no-daemon --no-parallel --max-workers=2 --no-watch-fs --console=plain \
    "-Dorg.gradle.jvmargs=$jvm_args" '-Pkotlin.compiler.execution.strategy=in-process' \
    '-PAPP_VERSION_NAME=3.0.0' '-PAPP_VERSION_CODE=7200' \
    '-PlumaFriendsEdition=false' '-PlumaPublicEdition=true'
git diff --quiet && git diff --cached --quiet || fail 'Tracked source changed during the build.'
node Tools/ci/collect-lunagram-apk.mjs --release 300 --repo-root "$repo_root" \
    --artifact-dir "$artifact_dir" --edition public --aapt "$build_tools/aapt" --javap "$JAVA_HOME/bin/javap"
"$build_tools/apksigner" verify "$artifact_dir/Lunagram-3.0.0-public-temporary.apk"
node Tools/ci/collect-lunagram-apk.mjs --release 300 --verify-pair --artifact-dir "$artifact_dir"
printf 'Public temporary-signed test APK verified. Re-sign locally before installation; no release or update feed was published.\n'
