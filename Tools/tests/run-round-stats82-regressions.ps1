param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-82-stats-tests')
$ErrorActionPreference = 'Stop'
$javaSuffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $javaSuffix)
$java = Join-Path $JavaHome ('bin/java' + $javaSuffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'JavaHome must contain a Java compiler.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = Join-Path $OutputRoot ('run-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$production = @(
    (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoMetrics.java'),
    (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoStats.java')
)
$initialHashes = @($production | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash })
$sources = @($production) + @(
    (Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java'),
    (Join-Path $PSScriptRoot 'round-stats82/RoundStats82Test.java'),
    (Join-Path $PSScriptRoot 'round-stats82/android/media/MediaExtractor.java'),
    (Join-Path $PSScriptRoot 'round-stats82/android/media/MediaFormat.java'),
    (Join-Path $PSScriptRoot 'round-stats82/android/os/Build.java'),
    (Join-Path $PSScriptRoot 'round-stats82/org/telegram/messenger/MessagesController.java'),
    (Join-Path $PSScriptRoot 'round-stats82/org/telegram/messenger/Utilities.java')
)
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Round MP4 statistics regression compilation failed.' }
& $java -Xmx128m -cp $classes org.telegram.messenger.RoundStats82Test $run
if ($LASTEXITCODE -ne 0) { throw 'Round MP4 statistics regressions failed.' }
for ($i = 0; $i -lt $production.Length; $i++) {
    if ((Get-FileHash -LiteralPath $production[$i] -Algorithm SHA256).Hash -ne $initialHashes[$i]) {
        throw 'Production measurements changed during testing; rerun against frozen sources.'
    }
}
Write-Output 'Coverage: actual production PTS/bitrate math and asynchronous persistence, with a fake MediaExtractor. Not an Android MP4 parser, camera, display, or device verification.'
Write-Output ('Test artifacts: ' + $run)
