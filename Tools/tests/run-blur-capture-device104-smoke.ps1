param(
    [string]$Serial,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$SdkPath,
    [ValidateSet('broken','fixed')][string]$ExpectedOutcome = 'fixed',
    [string]$ApkPath,
    [switch]$CompileOnly,
    [string]$OutputRoot = 'D:/CodexBuildCache/Lunagram-blur-capture-device104'
)
$ErrorActionPreference = 'Stop'
if (!$CompileOnly -and !$Serial) { throw 'Choose an explicit device Serial, or CompileOnly.' }
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$adb = Join-Path $SdkPath ('platform-tools/adb' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$jar = Join-Path $JavaHome ('bin/jar' + $suffix)
$android = Join-Path $SdkPath 'platforms/android-36/android.jar'
$d8 = Join-Path $SdkPath 'build-tools/36.0.0/lib/d8.jar'
foreach ($tool in @($adb,$java,$javac,$jar,$android,$d8)) {
    if (!(Test-Path -LiteralPath $tool)) { throw 'Existing JDK17 and Android36 SDK/build tools are required.' }
}
function CheckExit([string]$step) { if ($LASTEXITCODE -ne 0) { throw "$step failed." } }
$runId = [guid]::NewGuid().ToString('N')
$run = Join-Path $OutputRoot $runId
$classes = Join-Path $run 'classes'; $dex = Join-Path $run 'dex'
New-Item -ItemType Directory -Path $classes,$dex -Force | Out-Null
& $javac -J-Xmx128m -encoding UTF-8 --release 8 -classpath $android -d $classes (Join-Path $PSScriptRoot 'blur-capture104/BlurCaptureDeviceProbe104.java')
CheckExit 'Android probe compilation'
$classJar = Join-Path $run 'probe-classes.jar'
& $jar --create --file $classJar -C $classes .
CheckExit 'Probe class jar creation'
if ($ApkPath) {
    if (!(Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw 'Exact APK required for host symbol resolution.' }
    $classpath = $classJar + [IO.Path]::PathSeparator + $android
    & $java -Xmx128m -cp $classpath org.lunagram.tests.BlurCaptureDeviceProbe104 --resolve $ApkPath
    CheckExit 'Bounded host actual-APK symbol resolution'
}
& $java -Xmx128m -cp $d8 com.android.tools.r8.D8 --min-api 26 --lib $android --output $dex $classJar
CheckExit 'Probe dex conversion'
$probe = Join-Path $run 'BlurCaptureDeviceProbe104.jar'
& $jar --create --file $probe -C $dex classes.dex
CheckExit 'Probe dex jar creation'
if ($CompileOnly) { Write-Output "PASS: Android36 probe compiled/dexed only; no device accessed. Probe jar: $probe"; return }

if ($ApkPath) {
    $ApkPath = [IO.Path]::GetFullPath($ApkPath)
    if (!(Test-Path -LiteralPath $ApkPath -PathType Leaf) -or [IO.Path]::GetExtension($ApkPath) -cne '.apk') { throw 'ApkPath must name an existing exact APK file.' }
} else {
    $paths = @(& $adb -s $Serial shell pm path org.luma.liquid.web)
    CheckExit 'Installed APK lookup'
    $basePaths = @($paths | Where-Object { $_ -match '^package:/data/app/[A-Za-z0-9._/=+~-]+/base\.apk\s*$' })
    if ($basePaths.Count -ne 1) { throw 'Exactly one installed Lunagram base APK is required.' }
    $apk = $basePaths[0].Trim().Substring(8)
}
$remote = '/data/local/tmp/lunagram-blur-probe104-' + $runId
$remoteJar = $remote + '/BlurCaptureDeviceProbe104.jar'
$remoteApk = $remote + '/candidate.apk'
& $adb -s $Serial shell mkdir $remote
CheckExit 'Isolated shell scratch creation'
try {
    & $adb -s $Serial push $probe $remoteJar
    CheckExit 'Probe transfer'
    & $adb -s $Serial shell chmod 444 $remoteJar
    CheckExit 'Read-only probe permissions'
    if ($ApkPath) {
        & $adb -s $Serial push $ApkPath $remoteApk
        CheckExit 'Candidate APK transfer (no installation)'
        & $adb -s $Serial shell chmod 444 $remoteApk
        CheckExit 'Read-only candidate permissions'
        $apk = $remoteApk
    }
    # Generated hex paths + validated installed path only. Separate shell UID,
    # never app instrumentation; toybox bounds the remote process to 20 seconds.
    $command = "CLASSPATH=$remoteJar toybox timeout 20 app_process /system/bin org.lunagram.tests.BlurCaptureDeviceProbe104 $apk $remote $ExpectedOutcome"
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb; $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in @('-s',$Serial,'shell',$command)) { $start.ArgumentList.Add($argument) }
    $child = [Diagnostics.Process]::Start($start)
    try {
        if (!$child.WaitForExit(30000)) { $child.Kill($true); throw 'Bounded Android probe timed out.' }
        $output = $child.StandardOutput.ReadToEnd() + $child.StandardError.ReadToEnd()
        if ($child.ExitCode -ne 0 -or !$output.Contains("PASS: actual APK $ExpectedOutcome Android Canvas")) { throw "Actual APK Canvas probe failed: $output" }
        Write-Output $output.Trim()
    } finally { $child.Dispose() }
} finally {
    # Exact probe-owned files only. No app data or recursive deletion.
    & $adb -s $Serial shell rm -f $remoteJar $remoteApk
    & $adb -s $Serial shell rmdir $remote
}
Write-Output 'Scope: APK reflection + Android software Canvas/Matrix/clip + synthetic unattached Views and system resources only; no accounts/preferences/keys/network/camera/app UI. Not a hardware RenderNode or end-to-end gift-screen test.'
Write-Output "Host probe artifacts preserved: $run"
