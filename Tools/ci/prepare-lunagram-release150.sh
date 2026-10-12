#!/usr/bin/env bash
# SDK provisioning only: this step receives no API or signing credentials.
set +x
set -Eeuo pipefail
umask 077

fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }
[[ "${GITHUB_ACTIONS:-}" == true ]] || fail 'GitHub Actions only.'
[[ "${GITHUB_REPOSITORY:-}" == sergeevdeonisii-dotcom/LumaGram-Android ]] || fail 'Unexpected repository.'
[[ "${GITHUB_REF:-}" == refs/heads/agent/lunagram-release100 ]] || fail 'Unexpected source branch.'
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
[[ "$(git rev-parse HEAD)" == "${GITHUB_SHA:-}" ]] || fail 'Checkout does not match the workflow source commit.'
[[ "$(sed -n 's/^APP_VERSION_NAME=//p' gradle.properties | tr -d '\r')" == 1.5 ]] || fail 'Version 1.5 only.'
[[ "$(sed -n 's/^APP_VERSION_CODE=//p' gradle.properties | tr -d '\r')" == 7169 ]] || fail 'Unexpected monotonic Android version code.'

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
[[ -s "$sdk_root/platforms/android-36/android.jar" ]] || fail 'Android 36 platform is unavailable.'
for tool in aapt aapt2 apksigner; do
    [[ -x "$sdk_root/build-tools/36.0.0/$tool" ]] || fail 'Android build tools are unavailable.'
done
