param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-ghost-profile150-tests')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$helperPath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaGhostProfileStatus.java'
$sources = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'ghost-profile150') -Recurse -Filter '*.java').FullName)
$sources += $helperPath
& (Join-Path $JavaHome ('bin/javac' + $suffix)) -J-Xmx128m -encoding UTF-8 -d $run $sources
if ($LASTEXITCODE -ne 0) { throw 'Ghost profile helper compilation failed.' }
& (Join-Path $JavaHome ('bin/java' + $suffix)) -Xmx128m -cp $run org.telegram.messenger.GhostProfile150Test
if ($LASTEXITCODE -ne 0) { throw 'Ghost profile behavior checks failed.' }
$helper = [IO.File]::ReadAllText($helperPath)
if ($helper -match '\.status\s*=|\.expires\s*=|\.by_me\s*=|sendRequest\(|setEnabled\(|getCurrentTime\(|currentTimeMillis\(|static\s+(?:final\s+)?(?:Map|HashMap|SparseArray|long\[|int\[)') {
    throw 'Ghost profile helper must not publish/mutate/cache server status or infer time from local activity.'
}
$profile = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java'))
foreach ($required in @('LumaGhostProfileStatus.format(currentAccount, user,', 'getConnectionsManager().getCurrentTime(), isOnline',
        'if (ghostStatus != null)', 'newString2 = ghostStatus;', 'boolean updatePresenceStyle = ghostStatus != null;',
        'LocaleController.formatUserStatus(currentAccount, user, isOnline', 'newString2 = LocaleController.getString(R.string.Online);')) {
    if (!$profile.Contains($required)) { throw "Missing ghost profile/native branch integration: $required" }
}
Write-Output 'PASS: production helper compiled; server status read-only and existing ordinary/other-user profile branches preserved.'
Write-Output 'Scope: helper behavior with Telegram/localization fixtures and source integration guards, not an APK/device or server-presence test.'
Write-Output "Test artifacts: $run"
