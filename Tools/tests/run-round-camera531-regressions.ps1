param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-531-camera-tests'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath($OutputRoot)
if (-not $output.StartsWith([IO.Path]::GetFullPath('D:\CodexBuildCache') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Regression artifacts must remain inside D:\CodexBuildCache.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'An existing JavaHome containing javac.exe is required.'
}
$production = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoCamera.java'
$initialHash = (Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash
$classes = Join-Path $output ('classes-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(
    $production,
    (Join-Path $PSScriptRoot 'round-camera531/RoundCamera531RegressionTest.java'),
    (Join-Path $PSScriptRoot 'typing-fixtures/org/telegram/messenger/MessagesController.java'),
    (Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java')
)
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx96m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Rear-camera preference test compilation failed.' }
if ((Get-FileHash -LiteralPath $production -Algorithm SHA256).Hash -ne $initialHash) {
    throw 'Camera helper changed during compilation; rerun after source freeze.'
}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx96m -cp $classes org.telegram.messenger.RoundCamera531RegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Rear-camera preference regressions failed.' }
$legacy = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java'))
$modern = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView2.java'))
if (-not $legacy.Contains('isFrontface = LumaRoundVideoCamera.initialFrontCamera(useCamera2 ? isFrontface : true, false);')) {
    throw 'Legacy new-recording camera policy is missing.'
}
if (-not $legacy.Contains('surfaceIndex = useCamera2 && bothCameras && !isFrontface ? 1 : 0;') -or
    $legacy.IndexOf('surfaceIndex = useCamera2 && bothCameras && !isFrontface ? 1 : 0;') -gt $legacy.IndexOf('textureView = new TextureView(getContext());')) {
    throw 'Rear-facing dual-camera recording must use the rear texture before GL starts.'
}
if (-not $legacy.Contains('if (isFrontface != selectedCamera.isFrontface())')) {
    throw 'Camera1 fallback must reflect the actual selected camera.'
}
if (-not $modern.Contains('.setInitialFacing(getInitialCameraFacing(fromPaused))') -or
    -not $modern.Contains('if (fromPaused || !LumaRoundVideoCamera.isStartWithRearCameraEnabled()) return previous;')) {
    throw 'New recording engine preference or pause preservation is missing.'
}
if (-not $modern.Contains('LumaRoundVideoCamera.availableFrontCamera(front, frontAvailable, backAvailable)')) {
    throw 'New recording engine must handle devices missing the requested lens.'
}
Write-Output ('Production helper SHA-256: ' + $initialHash)
Write-Output ('Test artifacts: ' + $classes)
Write-Output 'PASS: Camera1, Camera2 texture startup, pause policy, and new-engine facing source integration checks.'
