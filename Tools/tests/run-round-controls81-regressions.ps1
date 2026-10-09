param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot='D:\CodexBuildCache\Lunagram-81-controls-tests')
$ErrorActionPreference='Stop'
$javaSuffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $javaSuffix)
$java = Join-Path $JavaHome ('bin/java' + $javaSuffix)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$classes=Join-Path $OutputRoot ('run-'+[guid]::NewGuid().ToString()+'/classes')
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@(
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoQuality.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoStabilization.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaBuildPolicy.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaHorizonState.java',
    'Tools/tests/round-controls81/RoundControls81Test.java',
    'Tools/tests/typing-fixtures/org/telegram/messenger/MessagesController.java',
    'Tools/tests/fixtures/org/telegram/messenger/BuildConfig.java',
    'Tools/tests/fixtures/android/content/SharedPreferences.java',
    'Tools/tests/round-video-fixtures/org/telegram/messenger/VideoEditedInfo.java'
) | ForEach-Object { Join-Path $repo $_ }
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Round controls compilation failed.'}
& $java -Xmx128m -cp $classes org.telegram.messenger.RoundControls81Test
if($LASTEXITCODE -ne 0){throw 'Round controls regressions failed.'}
$camera=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/camera/Camera2Session.java'))
$view=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java'))
function Method([string]$source,[string]$signature) {
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing method: $signature"}
    $open=$source.IndexOf('{',$start); $depth=1
    for($i=$open+1;$i -lt $source.Length;$i++){
        if($source[$i] -eq '{'){$depth++}; if($source[$i] -eq '}'){$depth--}
        if(!$depth){return $source.Substring($start,$i-$start+1)}
    }
    throw 'Unclosed method'
}
$methods=@('private static Range<Integer> chooseRecordingFpsRange(', 'private static Range<Integer> chooseHighSpeedFpsRange(', 'private static int chooseNormalFallbackFrameRate(', 'private static boolean contains(') |
    ForEach-Object { Method $camera $_ }
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-controls81/CameraRates81Test.java.template'))
$generated=Join-Path (Split-Path $classes) 'CameraRates81Test.java'
[IO.File]::WriteAllText($generated,$template.Replace('// PRODUCTION_RATE_METHODS',($methods -join "`n")),[Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -cp $classes -d $classes $generated
if($LASTEXITCODE -ne 0){throw 'Camera rate-selection compilation failed.'}
& $java -Xmx128m -cp $classes org.telegram.messenger.CameraRates81Test
if($LASTEXITCODE -ne 0){throw 'Camera rate-selection regressions failed.'}
$modern=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoCameraController.java'))
$session=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoSession.java'))
$methods=@('private FrameRatePlan resolveFrameRate(', 'private FrameRatePlan resolveLowerFrameRate(', 'private static boolean supportsFrameDuration(',
    'private static Range<Integer> findBestFpsRange(', 'private static Size[] filterSizesForFrameRate(',
    'private static OutputPair chooseOutputPair(', 'private static OutputPair chooseTierPair(',
    'private static Size chooseFallbackRecordingSize(', 'private static int getSourceCropSize(',
    'private static Size choosePreviewSize(', 'private static int comparePreviewSizes(', 'private static int compareOutputPairs(',
    'private static boolean hasSameAspectRatio(', 'private static boolean isValidTierSize(', 'private static boolean isWithinAbsoluteLimit(',
    'private static int shortSide(', 'private static long area(', 'private static boolean contains(',
    'private static final class FrameRatePlan', 'private static final class OutputPair') |
    ForEach-Object { Method $modern $_ }
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-controls81/ModernCamera81Test.java.template'))
$generated=Join-Path (Split-Path $classes) 'ModernCamera81Test.java'
$template=$template.Replace('// PRODUCTION_METHODS',($methods -join "`n")).Replace('// PRODUCTION_ENUM',(Method $session 'public enum FrameRate'))
[IO.File]::WriteAllText($generated,$template,[Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -cp $classes -d $classes $generated
if($LASTEXITCODE -ne 0){throw 'New-recorder camera policy compilation failed.'}
& $java -Xmx128m -cp $classes org.telegram.messenger.ModernCamera81Test
if($LASTEXITCODE -ne 0){throw 'New-recorder camera policy regressions failed.'}
foreach($guard in @('generation != captureGeneration', 'cameraDevice != expectedDevice',
    'createConstrainedHighSpeedCaptureSession(Collections.singletonList(recordingSurface)',
    'fast.setRepeatingBurst(fast.createHighSpeedRequestList(request)', 'glProcessor.updateInputConfiguration(',
    'LumaRoundVideoStabilization.videoMode(', 'horizonLock.stop();')) {
    if(!$modern.Contains($guard)){throw "Missing new recorder integration: $guard"}
}
$settings=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/RoundVideoSettingsActivity.java'))
$configured=Method $modern 'public void onConfigured('
if($configured.IndexOf('submitRepeatingRequest();') -gt $configured.IndexOf('notifySwitchCompletion = false;')) {
    throw 'A rejected capture request must not consume camera-switch completion.'
}
$gl=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoGlProcessor.java'))
foreach($guard in @('overlayRenderer.setHorizon(', 'horizonLock.correction(frontCamera, cameraTimestampRealtime ? timestamp : 0L)',
    'previewGate = new LumaRoundVideoQuality.FrameGate(targetFps, sourceFps)', 'gate.accept(timestamp, frontCamera ? 1 : 0)',
    'overlayRenderer.renderRawCameraToOutput(textureId, textureMatrix, outputSize, outputSize)',
    'EGL14.eglDestroySurface(eglDisplay, eglPreviewSurface)')) {
    if(!$gl.Contains($guard)){throw "Missing new recorder GL integration: $guard"}
}
foreach($guard in @('new SlideChooseView(context)', 'LumaRoundVideoQuality::setFrameRateLevel', 'LumaRoundVideoStabilization::setMode')) {
    if(!$settings.Contains($guard)){throw "Missing shared native slider: $guard"}
}
$navigation=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/BlackHoleGramSettingsActivity.java'))
if(!$navigation.Contains('openSettings(new RoundVideoSettingsActivity());')) { throw 'Round camera sliders must be accessible without hidden developer settings.' }
foreach($guard in @('getHighSpeedVideoFpsRangesFor(size)','getHighSpeedVideoSizes()','createConstrainedHighSpeedCaptureSession(',
    'setRepeatingBurst(fastSession.createHighSpeedRequestList(', 'generation != captureGeneration', 'fallbackHighSpeedSession()',
    'LumaRoundVideoStabilization.videoMode(', 'Build.VERSION.SDK_INT >= 33')) {
    if(!$camera.Contains($guard)){throw "Missing camera integration: $guard"}
}
foreach($guard in @('new RoundFrame(cameraId, matrix)','encoder.frameMatrix = frame.matrix;',
    'horizonLock.correction(isFrontface, poseTimestamp)', 'activeSession.hasRealtimeTimestamps()', 'getSupportedRecordingFrameRate()',
    'new LumaRoundVideoQuality.FrameGate(encodingProfile.frameRate, getCameraRecordingFrameRate())')) {
    if(!$view.Contains($guard)){throw "Missing recording integration: $guard"}
}
if([regex]::Matches($view,'glUniformMatrix4fv\(vertexMatrixHandle, 1, false, frameMatrix, 0\)').Count -ne 2){throw 'Preview and encoder must use the same frame transform.'}
if([regex]::Matches($view,'if \(horizonLock != null\) horizonLock.stop\(\);').Count -lt 2){throw 'Detach and camera teardown must unregister sensors.'}
foreach($locale in @('values','values-ru')) {
    $xml=[xml][IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    foreach($key in @('LumaRoundVideoFps','LumaRoundVideoFpsInfo','LumaRoundVideoStabilization','LumaRoundVideoStabilizationInfo','LumaRoundVideoHorizonUnavailable')) {
        if(@($xml.resources.string | Where-Object name -CEQ $key).Count -ne 1){throw "Missing/duplicated translation: $locale/$key"}
    }
}
Write-Output 'PASS: high-speed burst, sensor lifecycle, frame transform and settings translation source guards.'
