param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$javaBin = Join-Path $JavaHome 'bin'
$run = Join-Path $PSScriptRoot ('.runs/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path (Join-Path $run 'classes') | Out-Null
$sources = @((Get-ChildItem (Join-Path $PSScriptRoot 'fixtures') -Recurse -Filter '*.java').FullName)
$sources += @((Get-ChildItem (Join-Path $PSScriptRoot 'org') -Recurse -Filter '*.java').FullName)
$actual = @('LumaAccountData', 'LumaAnonymousNumber', 'LumaDeletedMessages', 'LumaStarRating',
    'LumaDelayedSend', 'LumaUpdateFiles', 'LumaUpdaterController', 'LumaAccountExportManager', 'LumaPresenceRequestState', 'LumaGhostMode')
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
Write-Output "Test artifacts: $run"
