param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$SdkPath,
    [ValidateSet('broken','fixed')][string]$ExpectedOutcome = 'fixed',
    [string]$OutputRoot = 'D:/CodexBuildCache/Lunagram-local-gifts-device103'
)
$ErrorActionPreference = 'Stop'
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
function CheckExit([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed." }
}
$paths = @(& $adb -s $Serial shell pm path org.luma.liquid.web)
CheckExit 'Installed APK lookup'
$basePaths = @($paths | Where-Object { $_ -match '^package:/data/app/[A-Za-z0-9._/=+~-]+/base\.apk\s*$' })
if ($basePaths.Count -ne 1) { throw 'Exactly one installed Lunagram base APK is required.' }
$apk = $basePaths[0].Trim().Substring(8)
$runId = [guid]::NewGuid().ToString('N')
$run = Join-Path $OutputRoot $runId
$classes = Join-Path $run 'classes'
$dex = Join-Path $run 'dex'
New-Item -ItemType Directory -Path $classes,$dex -Force | Out-Null
& $javac -J-Xmx128m -encoding UTF-8 --release 8 -classpath $android -d $classes (Join-Path $PSScriptRoot 'local-gifts103/LocalGiftDeviceProbe103.java')
CheckExit 'Probe compilation'
& $java -Xmx128m -cp $d8 com.android.tools.r8.D8 --min-api 26 --lib $android --output $dex (Join-Path $classes 'org/lunagram/tests/LocalGiftDeviceProbe103.class')
CheckExit 'Probe dex conversion'
$probe = Join-Path $run 'LocalGiftDeviceProbe103.jar'
& $jar --create --file $probe -C $dex classes.dex
CheckExit 'Probe jar creation'
$remote = '/data/local/tmp/lunagram-localgift-probe103-' + $runId
$remoteJar = $remote + '/LocalGiftDeviceProbe103.jar'
& $adb -s $Serial shell mkdir $remote
CheckExit 'Isolated shell scratch creation'
try {
    & $adb -s $Serial push $probe $remoteJar
    CheckExit 'Probe transfer'
    # All shell values are generated hexadecimal paths or the validated APK path.
    # This is a separate shell-UID process, never instrumentation of the logged-in app.
    & $adb -s $Serial shell "CLASSPATH=$remoteJar app_process /system/bin org.lunagram.tests.LocalGiftDeviceProbe103 $apk $remote $ExpectedOutcome"
    CheckExit 'Actual APK synthetic graph probe'
} finally {
    # Exact test-owned file only. No app data, keys or recursive deletion.
    & $adb -s $Serial shell rm -f $remoteJar
    & $adb -s $Serial shell rmdir $remote
}
Write-Output 'Scope: real installed APK classes + Android Path, synthetic graph only; no account, preference, key, network or camera access. Not an end-to-end market UI or encrypted-write test.'
Write-Output "Host probe artifacts preserved: $run"
