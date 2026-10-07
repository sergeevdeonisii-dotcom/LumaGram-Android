param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-77-fps-tests'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath($OutputRoot)
$cacheRoot = [IO.Path]::GetFullPath('D:\CodexBuildCache')
if (-not $output.StartsWith($cacheRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'FPS regression artifacts must remain inside D:\CodexBuildCache.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'A JavaHome containing javac.exe is required.'
}
$production = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoQuality.java'
$initialHash = (Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash
$run = Join-Path $output ('round-fps77-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(
    $production,
    (Join-Path $PSScriptRoot 'round-fps77/RoundFps77RegressionTest.java'),
    (Join-Path $PSScriptRoot 'typing-fixtures/org/telegram/messenger/MessagesController.java'),
    (Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java'),
    (Join-Path $PSScriptRoot 'round-video-fixtures/org/telegram/messenger/VideoEditedInfo.java')
)
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Round FPS .77 regression compilation failed.' }
if ((Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash -ne $initialHash) {
    throw 'Production helper changed during compilation; rerun after the source is frozen.'
}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $classes org.telegram.messenger.RoundFps77RegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Round FPS .77 regressions failed.' }
Write-Output ('Production helper SHA-256: ' + $initialHash)
Write-Output ('Test artifacts: ' + $run)
Write-Output 'Coverage: actual production FPS/profile/gate helper with preferences and metadata models; not Android camera, codec, muxer, or device verification.'
