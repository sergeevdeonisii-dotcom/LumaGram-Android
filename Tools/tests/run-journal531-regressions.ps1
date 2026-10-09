param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-531-journal-tests'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath($OutputRoot)
if (-not $output.StartsWith([IO.Path]::GetFullPath('D:\CodexBuildCache') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Regression artifacts must remain inside D:\CodexBuildCache.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'An existing JavaHome containing javac.exe is required.'
}
$production = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/BlackHoleNotificationJournal.java'
$initialHash = (Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash
$classes = Join-Path $output ('classes-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sourcePaths = @(
    'TMessagesProj/src/main/java/org/telegram/messenger/BlackHoleNotificationJournal.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaAccountData.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/BlackHoleVault.java',
    'Tools/tests/journal531/Journal531RegressionTest.java',
    'Tools/tests/fixtures/org/telegram/messenger/ApplicationLoader.java',
    'Tools/tests/fixtures/org/telegram/messenger/UserConfig.java',
    'Tools/tests/fixtures/org/telegram/messenger/FileLog.java',
    'Tools/tests/fixtures/org/telegram/messenger/BlackHolePrivateData.java',
    'Tools/tests/fixtures/org/telegram/messenger/TelegramMediaSession.java',
    'Tools/tests/fixtures/org/json/JSONArray.java',
    'Tools/tests/fixtures/org/json/JSONObject.java',
    'Tools/tests/fixtures/android/content/Context.java',
    'Tools/tests/fixtures/android/content/SharedPreferences.java',
    'Tools/tests/fixtures/android/content/pm/PackageManager.java',
    'Tools/tests/fixtures/android/content/pm/PackageInfo.java',
    'Tools/tests/fixtures/android/content/pm/Signature.java',
    'Tools/tests/fixtures/android/app/Activity.java'
)
$sources = @($sourcePaths | ForEach-Object { Join-Path $repo $_ })
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx96m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Journal preference/content test compilation failed.' }
if ((Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash -ne $initialHash) {
    throw 'Journal changed during compilation; rerun after source freeze.'
}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx96m -cp $classes org.telegram.messenger.Journal531RegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Journal preference/content regressions failed.' }
Write-Output ('Production helper SHA-256: ' + $initialHash)
Write-Output ('Test artifacts: ' + $classes)
