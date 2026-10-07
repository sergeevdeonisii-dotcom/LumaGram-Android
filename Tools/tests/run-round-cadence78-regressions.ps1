param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-78-cadence-tests'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath($OutputRoot)
$cacheRoot = [IO.Path]::GetFullPath('D:\CodexBuildCache')
if (-not $output.StartsWith($cacheRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Cadence regression artifacts must remain inside D:\CodexBuildCache.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'A JavaHome containing javac.exe is required.'
}
$production = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoQuality.java'
$recorder = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java'
$helperHash = (Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash
$recorderHash = (Get-FileHash -LiteralPath $recorder -Algorithm SHA256).Hash
$source = [IO.File]::ReadAllText($recorder)
$method = $source.IndexOf('private void handleVideoFrameAvailable(long timestampNanos, Integer cameraId)')
$begin = $source.IndexOf('long dt, alphaDt;', $method)
$end = $source.IndexOf('FloatBuffer textureBuffer = InstantCameraView.this.textureBuffer;', $begin)
if ($method -lt 0 -or $begin -lt 0 -or $end -le $begin) { throw 'Actual recorder PTS prefix not found.' }
$prefix = $source.Substring($begin, $end - $begin)
if (-not $prefix.Contains('currentTimestamp += dt;') -or -not $prefix.Contains('timestampNanos - lastTimestamp')) {
    throw 'Recorder PTS arithmetic changed; inspect and update the extraction fixture.'
}
if (-not $source.Contains('frameGate.accept(timestampNanos, cameraId)') -or -not $source.Contains('eglPresentationTimeANDROID(eglDisplay, eglSurface, currentTimestamp)')) {
    throw 'Actual frame gate / EGL presentation-time integration changed.'
}
$run = Join-Path $output ('round-cadence78-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-cadence78/RoundCadence78RegressionTest.java.template'))
$generated = Join-Path $run 'RoundCadence78RegressionTest.java'
[IO.File]::WriteAllText($generated, $template.Replace('@@PRODUCTION_PTS_PREFIX@@', $prefix), [Text.UTF8Encoding]::new($false))
$sources = @(
    $production,
    $generated,
    (Join-Path $PSScriptRoot 'typing-fixtures/org/telegram/messenger/MessagesController.java'),
    (Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java'),
    (Join-Path $PSScriptRoot 'round-video-fixtures/org/telegram/messenger/VideoEditedInfo.java')
)
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Round cadence .78 regression compilation failed.' }
if ((Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash -ne $helperHash -or
    (Get-FileHash -LiteralPath $recorder -Algorithm SHA256).Hash -ne $recorderHash) {
    throw 'Production source changed during compilation; rerun after source freeze.'
}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $classes org.telegram.messenger.RoundCadence78RegressionTest |
    Tee-Object -FilePath (Join-Path $run 'results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Round cadence .78 regressions failed.' }
Write-Output ('Production helper SHA-256: ' + $helperHash)
Write-Output ('Recorder source SHA-256: ' + $recorderHash)
Write-Output ('Verbatim PTS prefix characters: ' + $prefix.Length)
Write-Output ('Test artifacts: ' + $run)
Write-Output 'Coverage: actual helper and verbatim recorder timestamp arithmetic; not Android camera, codec, EGL, muxer, or physical device validation.'
