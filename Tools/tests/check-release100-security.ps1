param([string]$RepoRoot)
$ErrorActionPreference = 'Stop'
if (-not $RepoRoot) { $RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')) }
$android = 'http://schemas.android.com/apk/res/android'
$script:checks = 0
function Check([bool]$ok, [string]$label) {
    if (-not $ok) { throw $label }
    $script:checks++
    Write-Output "PASS: $label"
}
function Source([string]$relative) { [IO.File]::ReadAllText((Join-Path $RepoRoot $relative)) }

[xml]$manifest = Source 'TMessagesProj/src/main/AndroidManifest.xml'
$sms = @($manifest.manifest.application.receiver | Where-Object { $_.GetAttribute('name', $android) -eq '.SmsReceiver' })
Check ($sms.Count -eq 1) 'Exactly one SMS Retriever entry'
Check ($sms[0].GetAttribute('exported', $android) -eq 'true') 'SMS Retriever remains reachable by Play services'
Check ($sms[0].GetAttribute('permission', $android) -eq 'com.google.android.gms.auth.api.phone.permission.SEND') 'SMS Retriever rejects broadcasts from untrusted apps'
Check (@($manifest.manifest.'uses-permission' | Where-Object { $_.GetAttribute('name', $android) -eq 'com.google.android.gms.auth.api.phone.permission.SEND' }).Count -eq 0) 'GMS sender permission is not requested by the application'
Check (@($sms[0].'intent-filter'.action | Where-Object { $_.GetAttribute('name', $android) -eq 'com.google.android.gms.auth.api.phone.SMS_RETRIEVED' }).Count -eq 1) 'SMS Retriever action is preserved'

foreach ($relative in @('release/AndroidManifest.xml', 'release/AndroidManifest_SDK23.xml', 'release/AndroidManifest_standalone.xml', 'debug/AndroidManifest.xml', 'debug/AndroidManifest_SDK23.xml')) {
    [xml]$config = Source ('TMessagesProj/config/' + $relative)
    $fcm = @($config.manifest.application.service | Where-Object { $_.GetAttribute('name', $android) -eq 'org.telegram.messenger.GcmPushListenerService' })
    Check ($fcm.Count -eq 1 -and $fcm[0].GetAttribute('exported', $android) -eq 'false') "FCM implementation is internal ($relative)"
    Check (@($fcm[0].'intent-filter'.action | Where-Object { $_.GetAttribute('name', $android) -eq 'com.google.firebase.MESSAGING_EVENT' }).Count -eq 1) "FCM SDK routing action is preserved ($relative)"
}

$base = 'TMessagesProj/src/main/java/org/telegram/messenger/'
$push = Source ($base + 'PushListenerController.java')
$gcm = Source ($base + 'GcmPushListenerService.java')
$sensitiveLogs = '(?s)FileLog\.(?:d|e|w)\s*\((?:(?!;).)*\b(?:pushAuthKey|pushAuthKeyId|inAuthKeyId|jsonString|currentPushString)\b(?:(?!;).)*;'
Check (-not [regex]::IsMatch($push, $sensitiveLogs)) 'Push diagnostics never log authentication keys, full JSON or registration token'
Check (-not [regex]::IsMatch($gcm, '(?s)FileLog\.(?:d|e|w)\s*\((?:(?!;).)*\+\s*(?:token|data|from)\b(?:(?!;).)*;')) 'FCM callbacks never log token or message payload'
Check ($push.Contains('Unable to process push payload (') -and $push.Contains('e.getClass().getSimpleName()')) 'Untrusted parser failure retains an error category without exception message content'
Check ($gcm.Contains('sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, token)') -and $gcm.Contains('processRemoteMessage(PushListenerController.PUSH_TYPE_FIREBASE, data.get("p"), time)')) 'FCM registration and delivery still use their original values'

$updater = Source ($base + 'LumaUpdaterController.java')
Check ($updater.Contains('calculateSha256(file)') -and $updater.Contains('archive.packageName') -and $updater.Contains('archiveVersion != expectedVersion') -and $updater.Contains('archiveSignatures.retainAll(installedSignatures)')) 'Update verification still checks hash, package, version code and signer'
Check ($updater.Contains('setMaxResponseBytes(65536)') -and $updater.Contains('.setAllowedHosts("github.com", "githubusercontent.com")')) 'Update manifest is bounded and APK host restrictions remain active'
$private = Source ($base + 'BlackHolePrivateData.java')
Check ($private.Contains('AndroidKeyStore') -and $private.Contains('throw new IllegalStateException("Keystore unavailable")')) 'Private notes/journal encryption cannot silently fall back to plaintext'
$sealed = Source ($base + 'BlackHoleSealedData.java')
Check ($sealed.Contains('AES/GCM/NoPadding') -and $sealed.Contains('cipher.updateAAD(binding.getBytes')) 'Encrypted local data remains authenticated and identity-bound'
$officialUpdate = Source ($base + 'BetaUpdate.java')
Check ($officialUpdate.Contains('SharedConfig.versionBiggerOrEqual(version, update.version) && versionCode > update.versionCode')) 'Official upstream version-name policy is unchanged'
Write-Output "Release 1.0.0 security source guards passed: $script:checks checks. Not an Android runtime penetration test or account-security guarantee."
