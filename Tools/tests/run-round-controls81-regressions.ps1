param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot='D:\CodexBuildCache\Lunagram-81-controls-tests')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$classes=Join-Path $OutputRoot ('run-'+[guid]::NewGuid().ToString()+'/classes')
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@(
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoQuality.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoStabilization.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaHorizonState.java',
    'Tools/tests/round-controls81/RoundControls81Test.java',
    'Tools/tests/typing-fixtures/org/telegram/messenger/MessagesController.java',
    'Tools/tests/fixtures/android/content/SharedPreferences.java',
    'Tools/tests/round-video-fixtures/org/telegram/messenger/VideoEditedInfo.java'
) | ForEach-Object { Join-Path $repo $_ }
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Round controls compilation failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $classes org.telegram.messenger.RoundControls81Test
if($LASTEXITCODE -ne 0){throw 'Round controls regressions failed.'}
$camera=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/camera/Camera2Session.java'))
$view=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java'))
foreach($guard in @('getHighSpeedVideoFpsRangesFor(size)','getHighSpeedVideoSizes()','createConstrainedHighSpeedCaptureSession(',
    'setRepeatingBurst(fastSession.createHighSpeedRequestList(', 'generation != captureGeneration', 'fallbackHighSpeedSession()',
    'LumaRoundVideoStabilization.videoMode(', 'Build.VERSION.SDK_INT >= 33')) {
    if(!$camera.Contains($guard)){throw "Missing camera integration: $guard"}
}
foreach($guard in @('new RoundFrame(cameraId, matrix)','encoder.frameMatrix = frame.matrix;',
    'horizonLock.correction(isFrontface)', 'getSupportedRecordingFrameRate()',
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
