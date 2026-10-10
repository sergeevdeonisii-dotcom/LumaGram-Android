param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot='D:\CodexBuildCache\Lunagram-102-concurrent-tests')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$sources=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'round-concurrent102/fixtures') -Recurse -Filter '*.java').FullName)
$sources+=Join-Path $PSScriptRoot 'round-concurrent102/RoundConcurrent102Test.java'
$sources+=Join-Path $PSScriptRoot 'round-concurrent102/RoundConcurrentGl102Test.java'
$sources+=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoConcurrentCamera.java'
$sources+=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoGlProcessor.java'
& (Join-Path $JavaHome ('bin/javac'+$suffix)) -J-Xmx128m -encoding UTF-8 -d $run $sources
if($LASTEXITCODE -ne 0){throw 'Concurrent camera lifecycle compilation failed.'}
& (Join-Path $JavaHome ('bin/java'+$suffix)) -Xmx128m -cp $run org.telegram.utils.camera.roundvideo.RoundConcurrent102Test
if($LASTEXITCODE -ne 0){throw 'Concurrent camera lifecycle regression failed.'}
& (Join-Path $JavaHome ('bin/java'+$suffix)) -Xmx128m -cp $run org.telegram.utils.camera.roundvideo.RoundConcurrentGl102Test
if($LASTEXITCODE -ne 0){throw 'Concurrent GL regression failed.'}
$root=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram'
$camera=[IO.File]::ReadAllText((Join-Path $root 'utils/camera/roundvideo/RoundVideoCameraController.java'))
$gl=[IO.File]::ReadAllText((Join-Path $root 'utils/camera/roundvideo/RoundVideoGlProcessor.java'))
$view=[IO.File]::ReadAllText((Join-Path $root 'ui/Components/InstantCameraView2.java'))
foreach($guard in @('requestedFrameRate.getValue() != 30','Build.VERSION.SDK_INT < 30','candidate.isSupported()',
        'glProcessor.addConcurrentInput','processor.selectConcurrentInput','concurrentCamera.close(null)',
        'glProcessor.resetToPrimaryInput()')) {
    if(!$camera.Contains($guard)){throw "Missing concurrent safety guard: $guard"}
}
$cameraPermission=$camera.IndexOf('ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)')
$audioPermission=$camera.IndexOf('ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)')
$concurrentStart=$camera.IndexOf('if (tryStartConcurrentCameras()) return;')
if($cameraPermission -lt 0 -or $audioPermission -lt 0 -or $cameraPermission -gt $concurrentStart -or $audioPermission -gt $concurrentStart) {
    throw 'Both camera and audio permission checks must run before starting any warm camera.'
}
if(!$gl.Contains('input.texture.updateTexImage();') -or !$gl.Contains('if (input == activeInput) drawFrame(timestamp);')) {
    throw 'Standby frames must be drained, not encoded.'
}
if(!$gl.Contains('input == pendingInput') -or !$gl.Contains('postDelayed(selectionTimeout, 750L)')) {
    throw 'Switch must wait for a real target frame and time out safely.'
}
if(!$camera.Contains('activeFacing == null') -or !$camera.Contains('!concurrentCamera.isReady()') -or !$camera.Contains('recorder.isStarted()')) {
    throw 'Startup/stop must not race deferred camera switches or restart audio/encoder.'
}
if(!$gl.Contains('timestamp <= input.lastTimestamp') -or !$gl.Contains('target.lastTimestamp = target.texture.getTimestamp()')) {
    throw 'Queued old callbacks must not qualify as fresh camera frames.'
}
if($view -match 'rotationY\(90f\)|setRotationY\(-90f\)'){throw 'Preview still waits edge-on for camera startup.'}
foreach($locale in @('values','values-ru')) {
    $xml=[xml][IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    foreach($key in @('LumaRoundVideoFastSwitchTitle','LumaRoundVideoFastSwitchEnable','LumaRoundVideoFastSwitchInfo')) {
        if(@($xml.resources.string|Where-Object name -CEQ $key).Count -ne 1){throw "Missing/duplicate string: $locale/$key"}
    }
}
Write-Output 'PASS: dual-stream integration, unchanged 60 FPS, standby exclusion, bounded fresh-frame wait and full-size preview source guards.'
