#!/usr/bin/env bash
# Compilation only: production signing and release publication happen separately on the owner's PC.
set +x
set -Eeuo pipefail
umask 077

fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }
[[ "${GITHUB_ACTIONS:-}" == true ]] || fail 'GitHub Actions only.'
[[ "${GITHUB_REPOSITORY:-}" == sergeevdeonisii-dotcom/LumaGram-Android ]] || fail 'Unexpected repository.'
[[ "${GITHUB_REF:-}" == refs/heads/agent/lunagram-release100 ]] || fail 'Unexpected source branch.'
[[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" ]] || fail 'Runner temporary directory is unavailable.'
[[ "${LUMA_API_ID:-}" =~ ^[1-9][0-9]{0,9}$ ]] || fail 'API ID is missing or invalid.'
(( LUMA_API_ID <= 2147483647 )) || fail 'API ID is outside the Android integer range.'
[[ "${LUMA_API_HASH:-}" =~ ^[[:xdigit:]]{32}$ ]] || fail 'API hash is missing or invalid.'
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/keytool" ]] || fail 'JDK is unavailable.'
command -v node >/dev/null || fail 'Node.js is unavailable.'
command -v openssl >/dev/null || fail 'OpenSSL is unavailable.'

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
source_commit="$(git rev-parse HEAD)"
[[ "$source_commit" == "${GITHUB_SHA:-}" ]] || fail 'Checkout does not match the workflow source commit.'
[[ "$(sed -n 's/^APP_VERSION_NAME=//p' gradle.properties | tr -d '\r')" == 1.0.0 ]] || fail 'Version 1.0.0 only.'
[[ "$(sed -n 's/^APP_VERSION_CODE=//p' gradle.properties | tr -d '\r')" == 7162 ]] || fail 'Unexpected monotonic Android version code.'
submodule_status="$(git submodule status --recursive)"
if grep -q '^[+U-]' <<< "$submodule_status"; then fail 'Pinned submodules are missing or mismatched.'; fi
for archive in libavcodec libavformat libavutil libcrypto libssl libdav1d libiwasm \
        libopenh264 libopus libswresample libswscale libtde2e libtdutils libtlottie libvpx; do
    [[ -s "TMessagesProj/jni/prebuild/lib/arm64-v8a/$archive.a" ]] || fail 'Missing checked-in arm64 native archive.'
done

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] || fail 'The Android SDK is unavailable.'
export ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root"
sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
[[ -x "$sdkmanager" ]] || sdkmanager="$(command -v sdkmanager || true)"
[[ -x "$sdkmanager" ]] || fail 'sdkmanager is unavailable.'
# Keep sdkmanager's status if the license responder receives SIGPIPE.
(set +o pipefail; yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null)
"$sdkmanager" --sdk_root="$sdk_root" 'platform-tools' 'platforms;android-36' \
    'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
build_tools="$sdk_root/build-tools/36.0.0"
[[ -x "$build_tools/aapt" && -x "$build_tools/apksigner" ]] || fail 'Android build tools are unavailable.'

artifact_dir="$RUNNER_TEMP/lunagram100-artifacts"
[[ ! -e "$artifact_dir" ]] || fail 'Artifact directory already exists; inspect the previous attempt.'
mkdir -p "$artifact_dir"
[[ ! -e local-luma.properties && ! -e local-signing/luma-signing.properties ]] || fail 'Private local configuration unexpectedly exists on the runner.'
git check-ignore -q local-luma.properties || fail 'API configuration must stay Git-ignored.'
git check-ignore -q local-signing/luma-signing.properties || fail 'Signing configuration must stay Git-ignored.'
signing_dir="$(mktemp -d "$RUNNER_TEMP/lunagram100-signing.XXXXXX")"
# Exact files created below only; no recursive removal or production key access.
cleanup() {
    rm -f -- "$repo_root/local-luma.properties" "$repo_root/local-signing/luma-signing.properties" "$signing_dir/temporary.jks"
}
trap cleanup EXIT
export LUMA_CI_SIGNING_PASSWORD="$(openssl rand -hex 24)"
"$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$signing_dir/temporary.jks" \
    -storetype JKS -alias lunagram-ci-temporary -keyalg RSA -keysize 2048 -validity 7 \
    -storepass:env LUMA_CI_SIGNING_PASSWORD -keypass:env LUMA_CI_SIGNING_PASSWORD \
    -dname 'CN=Lunagram disposable CI key, OU=Temporary build only' >/dev/null 2>&1
mkdir -p local-signing
printf 'API_ID=%s\nAPI_HASH=%s\n' "$LUMA_API_ID" "$LUMA_API_HASH" > local-luma.properties
printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=lunagram-ci-temporary\nkeyPassword=%s\n' \
    "$signing_dir/temporary.jks" "$LUMA_CI_SIGNING_PASSWORD" "$LUMA_CI_SIGNING_PASSWORD" > local-signing/luma-signing.properties
unset LUMA_API_ID LUMA_API_HASH LUMA_CI_SIGNING_PASSWORD

jvm_args='-Xmx4g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
for edition in full friends; do
    friends_flag=false
    [[ "$edition" == full ]] || friends_flag=true
    printf 'Building %s edition of Lunagram 1.0.0 from %s.\n' "$edition" "$source_commit"
    "$JAVA_HOME/bin/java" -Xms32m -Xmx128m \
        -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
        ':TMessagesProj_AppStandalone:assembleAfatStandalone' \
        --no-daemon --no-parallel --max-workers=2 --no-watch-fs --console=plain \
        "-Dorg.gradle.jvmargs=$jvm_args" '-Pkotlin.compiler.execution.strategy=in-process' \
        "-PlumaFriendsEdition=$friends_flag"
    # Preserve/verify personal bytes before public compilation reuses the same output path.
    node Tools/ci/collect-lunagram-apk.mjs --release 100 --repo-root "$repo_root" \
        --artifact-dir "$artifact_dir" --edition "$edition" --aapt "$build_tools/aapt"
    "$build_tools/apksigner" verify "$artifact_dir/Lunagram-1.0.0-$edition-temporary.apk"
done
node Tools/ci/collect-lunagram-apk.mjs --release 100 --verify-pair --artifact-dir "$artifact_dir"
printf 'Both temporary-signed 1.0.0 APKs are verified. No production release or update feed was published.\n'
