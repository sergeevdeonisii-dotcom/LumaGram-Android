param(
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/round-flip105'),
    [string]$AndroidJar=''
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.runs'))+[IO.Path]::DirectorySeparatorChar
$output=[IO.Path]::GetFullPath($OutputRoot)
$inRunnerTemp=$env:RUNNER_TEMP -and $output.StartsWith([IO.Path]::GetFullPath($env:RUNNER_TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
if (!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -and !$inRunnerTemp) { throw 'Flip test output must stay inside Tools/tests/.runs or configured runner temporary storage.' }
$suffix=if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
$java=Join-Path $JavaHome ('bin/java'+$suffix)
if (!$AndroidJar) {
    $sdk=if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'C:/Users/denis228/Documents/Codex/2026-07-23/new-chat-2/work/android-sdk' }
    $AndroidJar=Join-Path $sdk 'platforms/android-36/android.jar'
}
foreach ($tool in @($javac,$java,$AndroidJar)) { if (!(Test-Path -LiteralPath $tool -PathType Leaf)) { throw 'Existing JDK17 and Android36 platform jar are required.' } }
$helperPath=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/LumaRoundCameraFlip.java'
$view=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView2.java'))
$helper=[IO.File]::ReadAllText($helperPath)
$session=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoSession.java'))
$controller=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoCameraController.java'))
function Method([string]$Source,[string]$Signature) {
    $start=$Source.IndexOf($Signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing real integration method: $Signature" }
    $open=$Source.IndexOf('{',$start);$depth=1
    for ($end=$open+1;$end -lt $Source.Length;$end++) {
        if ($Source[$end] -eq '{') { $depth++ }
        if ($Source[$end] -eq '}' -and --$depth -eq 0) { return $Source.Substring($open+1,$end-$open-1) }
    }
    throw "Unclosed integration method: $Signature"
}
$started=Method $view 'public void onCameraSwitchStarted('
$configured=Method $view 'public void onCameraSwitchCompleted('
$first=Method $view 'public void onCameraSwitchFirstFrame('
if (!$started.Contains('cameraFlip.begin();') -or $started.IndexOf('cameraFlip.begin();') -gt $started.IndexOf('showCameraSwitchPlaceholder();')) { throw 'HAL wait must retain an unrotated full-size placeholder.' }
if ($configured -match 'cameraFlip\.|\.cancel\(|setRotationY\(') { throw 'HAL configuration must not cancel a valid first-frame flip.' }
if (!$first.Contains('cameraFlip.firstFrame();')) { throw 'Actual target first-frame hook must uncover the live preview.' }
$preview=Method $view 'public void onCameraPreviewFirstFrame('
if (!$preview.Contains('hideInitialPlaceholder();') -or !$preview.Contains('RoundVideoSession.State.STARTING') -or
        $preview.Contains('recordingStarted') -or $preview.Contains('recordStarted')) { throw 'Actual preview arrival must reveal startup pixels without starting the recording clock.' }
$surface=Method $controller 'public void onSurfaceTextureUpdated('
if (!$surface.Contains('cameraPreviewInputSeen') -or !$surface.Contains('surfaceTexture.getTimestamp() > 0L') -or
        !$surface.Contains('callback.onCameraPreviewFirstFrame();')) { throw 'Initial preview must be backed by actual camera input and a presented texture.' }
foreach ($body in @($started,$first)) {
    if (!$body.Contains('if (!canAnimateCameraSwitch())')) { throw 'Late callbacks must reject inactive/hidden UI owners.' }
}
$owner=Method $view 'private boolean canAnimateCameraSwitch('
foreach ($condition in @('session != null','isRecordingState()','isShown()','getWindowVisibility() == VISIBLE')) {
    if (!$owner.Contains($condition)) { throw "Missing switch owner guard: $condition" }
}
foreach ($signature in @('public void destroy(', 'private void pauseForPreview(', 'public void onError(')) {
    if (!(Method $view $signature).Contains('cameraFlip.cancel();')) { throw "Missing interruption cleanup: $signature" }
}
if (!(Method $view 'public void setVisibility(').Contains('visibility != VISIBLE && cameraFlip != null')) { throw 'Hidden UI must clear the turn.' }
foreach ($signature in @('protected void onWindowVisibilityChanged(', 'protected void onDetachedFromWindow(')) {
    if (!(Method $view $signature).Contains('cameraFlip.cancel();')) { throw "Missing view/window cleanup: $signature" }
}
$state=Method $view 'public void onStateChanged('
if (!$state.Contains('cameraFlip.cancel();')) { throw 'Lifecycle state transitions must clear the turn.' }
foreach ($terminal in @('PAUSING','PREVIEWING','FINISHING','COMPLETED','ERROR','RELEASED')) {
    if (!$state.Contains('RoundVideoSession.State.'+$terminal)) { throw "Missing state cleanup: $terminal" }
}
if (!(Method $view 'private void hideInitialPlaceholder(').Contains('if (switchPlaceholderVisible) return;')) { throw 'An initial placeholder fade must not hide a switch placeholder.' }
$hide=Method $view 'private void hideCameraSwitchPlaceholder('
if (!$hide.Contains('setAlpha(0f)') -or $hide.Contains('.alpha(0f)')) { throw 'The old overlay must uncover the actual first frame without another fade.' }
$snapshot=Method $view 'private void saveLastCameraBitmap('
if (!$snapshot.Contains('if (persist) Utilities.stackBlurBitmap(bitmap, 15);')) { throw 'Transient switch snapshots must not synchronously blur on the UI thread.' }
if (!$snapshot.Contains('if (!textureView.isAvailable()) return;') -or !$snapshot.Contains('if (bitmap == null) return;') -or $snapshot.Contains('lastBitmap = null')) { throw 'Unavailable snapshot must retain the last available overlay.' }
$show=Method $view 'private void showCameraSwitchPlaceholder('
if (!$show.Contains('saveLastCameraBitmap(false);') -or !$show.Contains('textureOverlayView.setImageBitmap(lastBitmap);')) { throw 'Switch overlay must retain captured-frame feedback without persistence work.' }
if (!$show.Contains('session.isFastSwitchActive()') -or $show.IndexOf('return;') -gt $show.IndexOf('saveLastCameraBitmap(false);')) { throw 'Warm GL switching must bypass synchronous bitmap readback and frozen overlay.' }
$button=Method $view 'private void switchCamera('
if (!$button.Contains('switchCameraDrawable.setCurrentFrame(0);') -or !$button.Contains('switchCameraDrawable.start();')) { throw 'Existing RLottie button response must remain.' }
if ($helper -match 'RoundVideoSession|RoundVideoGlProcessor|CameraDevice|SurfaceTexture|recorder|FileLog') { throw 'The flip helper must remain UI-only and cannot alter camera/video data.' }
if ($helper -match 'ValueAnimator|postDelayed|runOnUIThread|Math\.sin') { throw 'Camera preview must not pulse, distort or wait for animation on switching.' }
$firstMotion=Method $helper 'public void firstFrame('
if (!$firstMotion.Contains('target.hidePlaceholder();') -or !$firstMotion.Contains('awaitingFirstFrame = false;') -or
    $firstMotion.Contains('target.setRotationY(')) { throw 'A ready frame must be uncovered immediately without a rotation discontinuity or arrival animation.' }
$run=Join-Path $output ([guid]::NewGuid().ToString('N'))
$jvm=Join-Path $run 'jvm';$native=Join-Path $run 'android36'
New-Item -ItemType Directory -Path $jvm,$native -Force | Out-Null
$queueTemplate=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-flip105/RoundSwitchQueue105Probe.java.template'))
$queuePath=Join-Path $run 'RoundSwitchQueue105Probe.java'
$queueTemplate=$queueTemplate.Replace('/* ACTUAL_STARTED_BODY */',(Method $session 'public void onCameraSwitchStarted('))
$queueTemplate=$queueTemplate.Replace('/* ACTUAL_FRAME_BODY */',(Method $session 'public void onCameraSwitchFirstFrame('))
$queueTemplate=$queueTemplate.Replace('/* ACTUAL_PREVIEW_BODY */',(Method $session 'public void onCameraPreviewFirstFrame('))
[IO.File]::WriteAllText($queuePath,$queueTemplate,[Text.UTF8Encoding]::new($false))
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-flip105/AnimatorFixtures.java.template'))
$parts=[regex]::Matches($template,'(?m)^// FILE: ([^\r\n]+)\r?\n')
$sources=@();$collaborators=@()
for ($index=0;$index -lt $parts.Count;$index++) {
    $start=$parts[$index].Index+$parts[$index].Length
    $end=if ($index+1 -lt $parts.Count) { $parts[$index+1].Index } else { $template.Length }
    $relative=$parts[$index].Groups[1].Value
    $path=Join-Path $run $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path,$template.Substring($start,$end-$start),[Text.UTF8Encoding]::new($false))
    $sources+=$path
    if ($relative.StartsWith('org/telegram/')) { $collaborators+=$path }
}
& $javac -J-Xmx128m -encoding UTF-8 -d $jvm $sources $helperPath $queuePath (Join-Path $PSScriptRoot 'round-flip105/RoundCameraFlip105Test.java')
if ($LASTEXITCODE -ne 0) { throw 'Actual flip-helper model compilation failed.' }
& $java -Xmx128m -cp $jvm org.telegram.ui.Components.RoundCameraFlip105Test
if ($LASTEXITCODE -ne 0) { throw 'Flip-helper state/animator contracts failed.' }
& $java -Xmx128m -cp $jvm org.telegram.ui.Components.RoundSwitchQueue105Probe
if ($LASTEXITCODE -ne 0) { throw 'Actual Session posted-callback lifetime contracts failed.' }
& $javac -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $native $collaborators $helperPath
if ($LASTEXITCODE -ne 0) { throw 'Actual flip helper does not compile against Android36 APIs.' }
Write-Output 'PASS: actual helper Android36 compilation and native first-frame/lifecycle/motion-preference source hooks; no whole-app or hardware validation.'
Write-Output "Test artifacts: $run"
