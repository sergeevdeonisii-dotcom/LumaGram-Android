param(
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/round-startup-latency')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cameraRoot=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo'
function Method([string]$source,[string]$signature) {
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method $signature" }
    $open=$source.IndexOf('{',$start); $depth=1
    for ($end=$open+1;$end -lt $source.Length;$end++) {
        if ($source[$end] -eq '{') { $depth++ }
        if ($source[$end] -eq '}' -and --$depth -eq 0) { return $source.Substring($start,$end-$start+1) }
    }
    throw "Unclosed method $signature"
}
$controller=[IO.File]::ReadAllText((Join-Path $cameraRoot 'RoundVideoCameraController.java'))
$recorder=[IO.File]::ReadAllText((Join-Path $cameraRoot 'RoundVideoCodecRecorder.java'))
$gl=[IO.File]::ReadAllText((Join-Path $cameraRoot 'RoundVideoGlProcessor.java'))
$names=@('private void startRecordingWhenSessionReady(', 'private void startVideoAfterAudioReady(',
    'private void announceRecordingStarted(', 'private void postToCameraThread(', 'private void cancelCommonStartWait(',
    'private void createBokehSession(')
$methods=($names | ForEach-Object { (Method $controller $_).Replace('@NonNull ','') }) -join "`n"
$configured=Method $controller 'private CameraCaptureSession.StateCallback createSessionCallback('
if (!$configured.Contains('startRecordingWhenSessionReady();') -or $configured.Contains('hasFirstFrame()')) {
    throw 'Regular camera startup still waits for preview instead of starting audio at session readiness.'
}
$bokeh=Method $controller 'private void createBokehSession('
if (!$bokeh.Contains('if (glProcessor != null && glProcessor.hasFirstFrame()) startRecordingWhenSessionReady();')) {
    throw 'Bokeh without a first frame must retain its separate fallback deadline before the A/V start deadline.'
}
$policy=Method $controller 'private boolean tryStartConcurrentCameras('
if (!$policy.Contains('!RoundVideoConcurrentCamera.supportsLowLatencyStartup(targetFps)') -or
        $policy.IndexOf('supportsLowLatencyStartup(targetFps)') -gt $policy.IndexOf('snapshotCamera()')) {
    throw 'Cold high-FPS concurrent trial is not rejected before setup.'
}
$prepare=Method $recorder 'public synchronized Surface prepare('
$start=Method $recorder 'synchronized void startRecording('
if (!$prepare.Contains('audioCodec.start();') -or $prepare.Contains('audioRecord.startRecording()') -or
        $start.Contains('audioCodec.start();') -or !$start.Contains('audioRecord.startRecording();')) {
    throw 'Both codecs must be prepared before camera delivery; microphone starts only for an active recording.'
}
$draw=Method $gl 'private void drawFrame('
if ($draw.IndexOf('firstFrameReported = true;') -gt $draw.IndexOf('if (!recordingStarted)')) {
    throw 'An already-armed recording must still announce its first real camera input.'
}
foreach ($signature in @('boolean stopSegment(', 'void release(')) {
    if (!(Method $controller $signature).Contains('cancelCommonStartWait();')) { throw "Missing timer cleanup: $signature" }
}
foreach ($locale in @('values', 'values-ru')) {
    $xml=[xml][IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    $info=@($xml.resources.string | Where-Object name -CEQ 'LumaRoundVideoFastSwitchInfo')
    if ($info.Count -ne 1 -or $info[0].InnerText -match '30\s*/\s*60' -or
            !$info[0].InnerText.Contains('30 FPS') -or !$info[0].InnerText.Contains('60 FPS')) {
        throw "Fast-switch help must distinguish warm 30 FPS from single-camera high FPS: $locale"
    }
}
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-startup-latency/RoundStartupLatencyProbe.java.template'))
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$source=Join-Path $run 'RoundStartupLatencyProbe.java'
$suffix=if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac=Join-Path $JavaHome ('bin/javac'+$suffix); $java=Join-Path $JavaHome ('bin/java'+$suffix)
function RunProbe([string]$methodText,[bool]$expectPass) {
    [IO.File]::WriteAllText($source,$template.Replace('/* CONTROLLER_METHODS */',$methodText),[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $run $source
    if ($LASTEXITCODE -ne 0) { throw 'Extracted startup probe compilation failed.' }
    $output=& $java -Xmx64m -cp $run RoundStartupLatencyProbe 2>&1
    $passed=$LASTEXITCODE -eq 0
    if ($passed -ne $expectPass) { throw "Unexpected startup probe outcome: $output" }
    if ($expectPass) { Write-Output $output }
}
RunProbe $methods $true
$mutations=@(
    $methods.Replace('!active || stopping || !hasCaptureSession()', '!active || !hasCaptureSession()'),
    $methods.Replace('|| recorder.isStarted()', ''),
    $methods.Replace('|| recorder != expectedRecorder', ''),
    $methods.Replace('expectedRecorder.getTimeOriginNs()', '1L'),
    $methods.Replace('if (glProcessor != null && glProcessor.hasFirstFrame()) startRecordingWhenSessionReady();',
        'startRecordingWhenSessionReady();')
)
foreach ($mutation in $mutations) {
    if ($mutation -eq $methods) { throw 'Negative control failed to mutate production extraction.' }
    RunProbe $mutation $false
}
RunProbe $methods $true
Write-Output "PASS: 5 compiled negative controls and startup source integration guards. Artifacts: $run"
