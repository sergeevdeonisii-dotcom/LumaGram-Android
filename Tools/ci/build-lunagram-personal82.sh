#!/usr/bin/env bash
# Temporary cloud build only. Real release signing happens on the owner's PC.
set +x
set -Eeuo pipefail
umask 077
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }
[[ "${GITHUB_ACTIONS:-}" == true ]] || fail 'GitHub Actions only.'
[[ "${GITHUB_REPOSITORY:-}" == sergeevdeonisii-dotcom/LumaGram-Android ]] || fail 'Unexpected repository.'
[[ "${GITHUB_REF:-}" == refs/heads/agent/lunagram-round-fps82 ]] || fail 'Unexpected source branch.'
[[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" ]] || fail 'Missing runner temp.'
[[ "${LUMA_API_ID:-}" =~ ^[1-9][0-9]{0,9}$ ]] || fail 'Missing or invalid API ID.'
(( LUMA_API_ID <= 2147483647 )) || fail 'API ID is outside integer range.'
[[ "${LUMA_API_HASH:-}" =~ ^[[:xdigit:]]{32}$ ]] || fail 'Missing or invalid API hash.'
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]] || fail 'JDK is unavailable.'
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
[[ "$(git rev-parse HEAD)" == "${GITHUB_SHA:-}" ]] || fail 'Checkout/commit mismatch.'
[[ "$(sed -n 's/^APP_VERSION_NAME=//p' gradle.properties | tr -d '\r')" == 12.10.6-lunagram.82 ]] || fail 'Version .82 only.'
[[ "$(sed -n 's/^APP_VERSION_CODE=//p' gradle.properties | tr -d '\r')" == 7161 ]] || fail 'Unexpected version code.'
submodule_status="$(git submodule status --recursive)"
if grep -q '^[+U-]' <<< "$submodule_status"; then fail 'Pinned submodules are missing or mismatched.'; fi
for archive in libavcodec libavformat libavutil libcrypto libssl libdav1d libiwasm \
        libopenh264 libopus libswresample libswscale libtde2e libtdutils libtlottie libvpx; do
    [[ -s "TMessagesProj/jni/prebuild/lib/arm64-v8a/$archive.a" ]] || fail 'Missing checked-in native archive.'
done
node --test Tools/ci/collect-lunagram-apk.test.mjs
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] || fail 'SDK unavailable.'
export ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root"
sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
[[ -x "$sdkmanager" ]] || sdkmanager="$(command -v sdkmanager || true)"
[[ -x "$sdkmanager" ]] || fail 'sdkmanager unavailable.'
(set +o pipefail; yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null)
"$sdkmanager" --sdk_root="$sdk_root" 'platform-tools' 'platforms;android-36' \
    'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
build_tools="$sdk_root/build-tools/36.0.0"
artifact_dir="$RUNNER_TEMP/lunagram82-artifacts"
[[ ! -e "$artifact_dir" ]] || fail 'Artifact directory already exists.'
mkdir -p "$artifact_dir"
[[ ! -e local-luma.properties && ! -e local-signing/luma-signing.properties ]] || fail 'Unexpected private configuration.'
git check-ignore -q local-luma.properties || fail 'API configuration must stay ignored.'
git check-ignore -q local-signing/luma-signing.properties || fail 'Signing configuration must stay ignored.'
signing_dir="$(mktemp -d "$RUNNER_TEMP/lunagram82-signing.XXXXXX")"
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
"$JAVA_HOME/bin/java" -Xms32m -Xmx128m -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
    ':TMessagesProj_AppStandalone:assembleAfatStandalone' \
    --no-daemon --no-parallel --max-workers=2 --no-watch-fs --console=plain \
    "-Dorg.gradle.jvmargs=$jvm_args" '-Pkotlin.compiler.execution.strategy=in-process' '-PlumaFriendsEdition=false'
node Tools/ci/collect-lunagram-apk.mjs --release 82 --edition full --repo-root "$repo_root" \
    --artifact-dir "$artifact_dir" --aapt "$build_tools/aapt"
"$build_tools/apksigner" verify "$artifact_dir/Lunagram-12.10.6-lunagram.82-full-temporary.apk"
printf 'Personal .82 temporary APK collected. Not published or signed with the production key.\n'
