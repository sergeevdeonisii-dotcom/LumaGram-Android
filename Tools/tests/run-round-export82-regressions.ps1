param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = $(if ($env:RUNNER_TEMP) { Join-Path $env:RUNNER_TEMP 'round-export82-tests' } else { 'D:\CodexBuildCache\Lunagram-82-export-tests' })
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath($OutputRoot)
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (-not $JavaHome -or -not (Test-Path -LiteralPath $javac)) { throw 'A JavaHome with javac is required.' }
$controllerPath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java'
$converterPath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/video/MediaCodecVideoConvertor.java'
$qualityPath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoQuality.java'
$controller = [IO.File]::ReadAllText($controllerPath)
$converter = [IO.File]::ReadAllText($converterPath)
$drawablePath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/AnimatedFileDrawable.java'
$drawable = [IO.File]::ReadAllText($drawablePath)
function Extract-Block([string]$Source, [string]$Declaration) {
    $start = $Source.IndexOf($Declaration)
    if ($start -lt 0) { throw ('Production block missing: ' + $Declaration) }
    $open = $Source.IndexOf('{', $start)
    $depth = 1
    for ($position = $open + 1; $position -lt $Source.Length; $position++) {
        if ($Source[$position] -eq '{') { $depth++ }
        elseif ($Source[$position] -eq '}') { $depth-- }
        if ($depth -eq 0) { return $Source.Substring($start, $position - $start + 1) }
    }
    throw ('Unterminated production block: ' + $Declaration)
}
$clock = Extract-Block $drawable 'private static final class RoundVideoPlaybackClock'
$update = Extract-Block $drawable 'private void updateCurrentFrameInternal(long now, boolean updateInBackground)'
$swap = Extract-Block $drawable 'private void swapBuffers(long now)'
foreach ($needle in @('roundVideo && fps > 60 && !precache',
    'Choreographer.getInstance().removeFrameCallback(roundVideoChoreographerCallback);',
    '&& (!roundVideo || !parents.isEmpty())',
    'if (roundVideo) checkChoreographer();',
    'if (roundVideo) AndroidUtilities.executeOnUIThread(roundVideoPlaybackClock::reset);',
    'drawable.roundVideo = roundVideo;',
    'Choreographer60FpsContent.getInstance().addFrameCallback(mUiThreadChoreographerCallback, fps);')) {
    if (-not $drawable.Contains($needle)) { throw ('Round-only playback lifecycle guard changed: ' + $needle) }
}
$method = $controller.IndexOf('private boolean convertVideo(final VideoConvertMessage convertMessage)')
$begin = $controller.IndexOf('final int maximumFrameRate = info.roundVideo', $method)
$end = $controller.IndexOf('boolean needCompress =', $begin)
if ($method -lt 0 -or $begin -lt 0 -or $end -le $begin) { throw 'Actual conversion FPS policy not found.' }
$ratePolicy = $controller.Substring($begin, $end - $begin)
$skipBegin = $converter.IndexOf('long frameDeltaFroSkipFrames;')
$skipEnd = $converter.IndexOf('extractor.selectTrack(videoIndex);', $skipBegin)
if ($skipBegin -lt 0 -or $skipEnd -le $skipBegin) { throw 'Actual converter skip threshold not found.' }
$skipPolicy = $converter.Substring($skipBegin, $skipEnd - $skipBegin)
foreach ($needle in @('outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);',
    'info.presentationTimeUs - lastFramePts < frameDeltaFroSkipFrames',
    'inputSurface.setPresentationTime(info.presentationTimeUs * 1000);')) {
    if (-not $converter.Contains($needle)) { throw ('Converter rate/PTS integration changed: ' + $needle) }
}
$run = Join-Path $output ('round-export82-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-export82/RoundExport82RegressionTest.java.template'))
$generated = Join-Path $run 'RoundExport82RegressionTest.java'
[IO.File]::WriteAllText($generated, $template.Replace('@@PRODUCTION_RATE_POLICY@@', $ratePolicy).Replace('@@PRODUCTION_SKIP_THRESHOLD@@', $skipPolicy), [Text.UTF8Encoding]::new($false))
$playbackTemplate = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-export82/RoundPlayback82RegressionTest.java.template'))
$playbackGenerated = Join-Path $run 'RoundPlayback82RegressionTest.java'
[IO.File]::WriteAllText($playbackGenerated, $playbackTemplate.Replace('@@PRODUCTION_CLOCK@@', $clock).Replace('@@PRODUCTION_UPDATE@@', $update).Replace('@@PRODUCTION_SWAP@@', $swap), [Text.UTF8Encoding]::new($false))
$sources = @(
    $generated,
    $playbackGenerated,
    $qualityPath,
    (Join-Path $PSScriptRoot 'typing-fixtures/org/telegram/messenger/MessagesController.java'),
    (Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java'),
    (Join-Path $PSScriptRoot 'round-video-fixtures/org/telegram/messenger/VideoEditedInfo.java')
)
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Round export .82 test compilation failed.' }
& $java -Xmx128m -cp $classes org.telegram.messenger.RoundExport82RegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Round export .82 regressions failed.' }
& $java -Xmx128m -cp $classes org.telegram.ui.Components.RoundPlayback82RegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Round playback .82 regressions failed.' }
Write-Output ('Test artifacts: ' + $run)
Write-Output 'Coverage: verbatim FPS/skip policy and round playback PTS clock/buffer scheduling. Not physical codec/device validation.'
