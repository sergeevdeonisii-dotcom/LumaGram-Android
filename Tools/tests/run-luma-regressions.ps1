param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$javaBin = Join-Path $JavaHome 'bin'
$run = Join-Path $PSScriptRoot ('.runs/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path (Join-Path $run 'classes') | Out-Null
$sources = @((Get-ChildItem (Join-Path $PSScriptRoot 'fixtures') -Recurse -Filter '*.java').FullName)
$sources += @((Get-ChildItem (Join-Path $PSScriptRoot 'org') -Recurse -Filter '*.java').FullName)
$actual = @('LumaAccountData', 'LumaAnonymousNumber', 'LumaDeletedMessages', 'LumaStarRating',
    'LumaDelayedSend', 'LumaUpdateFiles', 'LumaUpdaterController', 'LumaAccountExportManager', 'LumaPresenceRequestState', 'LumaGhostMode',
    'LumaMessageFormatting', 'LumaGiftVisibilityOperation', 'LumaTextAnimation', 'LumaProfileVerification', 'LumaNotificationUpdateState', 'LumaNotificationAccountGuard', 'LumaConferenceRequestState', 'LumaExportSession',
    'BlackHoleSearch', 'BlackHoleSettings', 'BlackHoleVault', 'BlackHoleSealedData', 'BlackHoleNotes', 'BlackHoleNotificationJournal')
foreach ($name in $actual) {
    $sources += Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/messenger/' + $name + '.java')
}
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -d (Join-Path $run 'classes') $sources
if ($LASTEXITCODE -ne 0) { throw 'Regression test compilation failed.' }
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.LumaPresenceRequestStateTest
if ($LASTEXITCODE -ne 0) { throw 'Presence regressions failed.' }
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.LumaAuditFixesTest (Join-Path $run 'sandbox')
if ($LASTEXITCODE -ne 0) { throw 'Audit regressions failed.' }
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.BlackHoleGramGhostTest (Join-Path $run 'ghost-sandbox')
if ($LASTEXITCODE -ne 0) { throw 'Ghost scheduling regressions failed.' }
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.BlackHoleGramAudit70Test (Join-Path $run 'audit70-sandbox')
if ($LASTEXITCODE -ne 0) { throw '.70 audit regressions failed.' }
Write-Output "Test artifacts: $run"
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.BlackHoleGramTools71Test (Join-Path $run 'tools71-sandbox')
if ($LASTEXITCODE -ne 0) { throw '.71 tools regressions failed.' }
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.LumaNotificationsAudit74Test $repo
if ($LASTEXITCODE -ne 0) { throw '.74 notification merge regressions failed.' }
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.BlackHoleLocalDataAuditTest (Join-Path $run 'local74-sandbox')
if ($LASTEXITCODE -ne 0) { throw '.74 local data regressions failed.' }
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.LumaExportSessionTest (Join-Path $run 'export74-sandbox') $repo
if ($LASTEXITCODE -ne 0) { throw '.74 export session regressions failed.' }
& (Join-Path $PSScriptRoot 'run-bot-layout-regressions.ps1') -JavaHome $JavaHome
