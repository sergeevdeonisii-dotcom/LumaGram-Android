#!/usr/bin/env bash
# Cloud compilation only. The real release key never belongs on this runner.
set +x
set -Eeuo pipefail
umask 077

fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

[[ "${GITHUB_ACTIONS:-}" == true ]] || fail 'This script is restricted to the approved GitHub Actions job.'
[[ "${GITHUB_REPOSITORY:-}" == sergeevdeonisii-dotcom/LumaGram-Android ]] || fail 'Unexpected repository.'
[[ "${GITHUB_REF:-}" == refs/heads/agent/lunagram79-friends-cloud ]] || fail 'Unexpected source branch.'
[[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" ]] || fail 'Runner temporary directory is unavailable.'
[[ "${LUMA_API_ID:-}" =~ ^[1-9][0-9]{0,9}$ ]] || fail 'LUMA_API_ID is missing or invalid.'
(( LUMA_API_ID <= 2147483647 )) || fail 'LUMA_API_ID is outside the Android integer range.'
[[ "${LUMA_API_HASH:-}" =~ ^[[:xdigit:]]{32}$ ]] || fail 'LUMA_API_HASH is missing or invalid.'
[[ "${JAVA_HOME:-}" != '' && -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/keytool" ]] || fail 'JDK 17 is unavailable.'
command -v node >/dev/null || fail 'Node.js is unavailable.'
command -v openssl >/dev/null || fail 'OpenSSL is unavailable.'

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
source_commit="$(git rev-parse HEAD)"
[[ "$source_commit" == "${GITHUB_SHA:-}" ]] || fail 'Checkout does not match the workflow source commit.'
[[ "$(sed -n 's/^APP_VERSION_NAME=//p' gradle.properties | tr -d '\r')" == 12.10.6-lunagram.79 ]] || fail 'This workflow builds version .79 only.'
submodule_status="$(git submodule status --recursive)"
if grep -q '^[+U-]' <<< "$submodule_status"; then
    fail 'A pinned submodule is missing or mismatched.'
fi
for archive in libavcodec libavformat libavutil libcrypto libssl libdav1d libiwasm \
        libopenh264 libopus libswresample libswscale libtde2e libtdutils libtlottie libvpx; do
    [[ -s "TMessagesProj/jni/prebuild/lib/arm64-v8a/$archive.a" ]] || fail 'A checked-in arm64 native archive is missing.'
done
# Deliberately do not invoke prebuild/build_all.sh or Dockerfile's all-variant CMD.

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] || fail 'The GitHub-hosted Android SDK is unavailable.'
export ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root"
sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
    sdkmanager="$(command -v sdkmanager || true)"
fi
[[ -x "$sdkmanager" ]] || fail 'Android sdkmanager is unavailable.'
# yes can receive SIGPIPE after sdkmanager finishes; retain sdkmanager's exit status.
(set +o pipefail; yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null)
"$sdkmanager" --sdk_root="$sdk_root" 'platform-tools' 'platforms;android-36' \
    'build-tools;36.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
build_tools="$sdk_root/build-tools/36.0.0"
[[ -x "$build_tools/aapt" && -x "$build_tools/apksigner" ]] || fail 'Required Android build tools are unavailable.'

artifact_dir="$RUNNER_TEMP/lunagram79-artifacts"
[[ ! -e "$artifact_dir" ]] || fail 'Artifact directory already exists; inspect the previous attempt.'
mkdir -p "$artifact_dir"
[[ ! -e local-luma.properties && ! -e local-signing/luma-signing.properties ]] || fail 'Private local configuration unexpectedly exists on the runner.'
git check-ignore -q local-luma.properties || fail 'API configuration must stay Git-ignored.'
git check-ignore -q local-signing/luma-signing.properties || fail 'Signing configuration must stay Git-ignored.'

signing_dir="$(mktemp -d "$RUNNER_TEMP/lunagram79-signing.XXXXXX")"
export LUMA_CI_SIGNING_PASSWORD="$(openssl rand -hex 24)"
"$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$signing_dir/temporary.jks" \
    -storetype JKS -alias lunagram-ci-temporary -keyalg RSA -keysize 2048 -validity 7 \
    -storepass:env LUMA_CI_SIGNING_PASSWORD -keypass:env LUMA_CI_SIGNING_PASSWORD \
    -dname 'CN=Lunagram disposable CI key, OU=Temporary build only' >/dev/null 2>&1
mkdir -p local-signing
printf 'API_ID=%s\nAPI_HASH=%s\n' "$LUMA_API_ID" "$LUMA_API_HASH" > local-luma.properties
printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=lunagram-ci-temporary\nkeyPassword=%s\n' \
    "$signing_dir/temporary.jks" "$LUMA_CI_SIGNING_PASSWORD" "$LUMA_CI_SIGNING_PASSWORD" > local-signing/luma-signing.properties
# Gradle needs the ignored files, not copies of credentials in its environment.
unset LUMA_API_ID LUMA_API_HASH LUMA_CI_SIGNING_PASSWORD

jvm_args='-Xmx4g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
for edition in full friends; do
    friends_flag=false
    [[ "$edition" == full ]] || friends_flag=true
    printf 'Building %s edition from %s with temporary signing.\n' "$edition" "$source_commit"
    "$JAVA_HOME/bin/java" -Xms32m -Xmx128m \
        -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
        ':TMessagesProj_AppStandalone:assembleAfatStandalone' \
        --no-daemon --no-parallel --max-workers=2 --no-watch-fs --console=plain \
        "-Dorg.gradle.jvmargs=$jvm_args" '-Pkotlin.compiler.execution.strategy=in-process' \
        "-PlumaFriendsEdition=$friends_flag"
    # Copy/verify Full before Friends reuses the same Gradle output path.
    node Tools/ci/collect-lunagram-apk.mjs --repo-root "$repo_root" --artifact-dir "$artifact_dir" \
        --edition "$edition" --aapt "$build_tools/aapt"
    "$build_tools/apksigner" verify "$artifact_dir/Lunagram-12.10.6-lunagram.79-$edition-temporary.apk"
done
node Tools/ci/collect-lunagram-apk.mjs --verify-pair --artifact-dir "$artifact_dir"
printf 'Both temporary-signed APKs are ready. No release or update feed was published.\n'
