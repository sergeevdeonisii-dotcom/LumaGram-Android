param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs/camera-lifecycle'),
    [string]$SourceRevision = ''
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath($OutputRoot)
$allowed = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.runs')) + '\'
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Lifecycle regression artifacts must remain inside Tools/tests/.runs.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'An existing JavaHome containing javac.exe is required.'
}
function Method([string]$source, [string]$signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method $signature" }
    $open = $source.IndexOf('{', $start); $depth = 1
    for ($i = $open + 1; $i -lt $source.Length; $i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if ($depth -eq 0) { return $source.Substring($start, $i - $start + 1) }
    }
    throw "Unclosed production method $signature"
}
$path = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/camera/Camera2Session.java'
$hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
$source = [IO.File]::ReadAllText($path)
if ($SourceRevision) {
    $source = (& git -C $repo show ($SourceRevision + ':TMessagesProj/src/main/java/org/telegram/messenger/camera/Camera2Session.java')) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read requested comparison revision.' }
}
$start = $source.IndexOf('cameraStateCallback = new CameraDevice.StateCallback()', [StringComparison]::Ordinal)
$end = $source.IndexOf('this.isFront = isFront;', $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) { throw 'Cannot extract actual Camera2 callback implementations.' }
$callbacks = $source.Substring($start, $end - $start)
$signatures = @(
    'private void publishError()', 'public void whenError(Runnable callback)',
    'public void whenDone(Runnable doneCallback)', 'public void open(SurfaceTexture surfaceTexture)',
    'private void checkOpen()', 'public boolean isInitiated()',
    'public void destroy(boolean async, Runnable afterCallback)'
)
$methods = ($signatures | ForEach-Object { Method $source $_ }) -join "`n"
if ($source.Contains('private void closeCameraResources()')) {
    $methods += "`n" + (Method $source 'private void closeCameraResources()')
}
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'camera-lifecycle/Camera2LifecycleRegressionTest.java.template'))
$template = $template.Replace('// PRODUCTION_CALLBACKS', $callbacks).Replace('// PRODUCTION_METHODS', $methods)
$run = Join-Path $output ('run-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$harnessPath = Join-Path $run 'Camera2Session.java'
[IO.File]::WriteAllText($harnessPath, $template, [Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $classes $harnessPath
if ($LASTEXITCODE -ne 0) { throw 'Camera2 lifecycle harness compilation failed.' }
if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $hash) {
    throw 'Production source changed during lifecycle compilation; rerun after freeze.'
}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $classes Camera2Session
if ($LASTEXITCODE -ne 0) { throw 'Camera2 lifecycle regressions failed.' }
Write-Output ('Camera2Session SHA-256: ' + $hash)
Write-Output ('Test artifacts: ' + $run)
Write-Output 'Coverage uses verbatim lifecycle callbacks/methods with deterministic queues; no physical camera, capture request or Android runtime verification.'
