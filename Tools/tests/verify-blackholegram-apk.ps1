param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$SdkPath,
    [Parameter(Mandatory = $true)][string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$buildTools = Join-Path $SdkPath 'build-tools/36.0.0'
$badging = (& (Join-Path $buildTools 'aapt2.exe') dump badging $apk) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'APK metadata inspection failed' }
foreach ($expected in @(
    "name='org.luma.liquid.web'",
    "versionCode='71459'",
    "versionName='12.10.6-bhg.66'",
    "application-label:'BlackHoleGram'",
    "targetSdkVersion:'36'",
    "native-code: 'arm64-v8a'"
)) {
    if (!$badging.Contains($expected)) { throw "Missing APK metadata: $expected" }
    Write-Output "PASS: $expected"
}
$signature = (& (Join-Path $JavaHome 'bin/java.exe') -jar (Join-Path $buildTools 'lib/apksigner.jar') verify --verbose --print-certs $apk) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'APK signature validation failed' }
if ($signature -notmatch 'Signer #1 certificate SHA-256 digest: 24a3777b3b0b2d353b0452aa166660f5ad39e0f50aa2124d95b85978880c7cd9') {
    throw 'APK is not signed with the existing LumaGram release certificate'
}
Write-Output 'PASS: existing release certificate and valid APK signature'
& (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'APK ZIP alignment validation failed' }
Write-Output 'PASS: APK ZIP alignment for 16 KiB pages'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $nativeFiles = @($archive.Entries | Where-Object FullName -Like 'lib/*/*.so')
    if (@($nativeFiles | Where-Object FullName -EQ 'lib/arm64-v8a/libtmessages.49.so').Count -ne 1) {
        throw 'New Telegram native library is missing'
    }
    if (@($nativeFiles | Where-Object { $_.FullName -NotLike 'lib/arm64-v8a/*' -or $_.FullName -Like '*tmessages.48*' }).Count -gt 0) {
        throw 'Unexpected ABI or stale Telegram native library'
    }
    Write-Output 'PASS: new native library, arm64 only, no stale tmessages.48'
} finally {
    $archive.Dispose()
}
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "SHA-256: $hash"
Write-Output 'Artifact checks passed. This does not establish Android UI, presence or push behavior on a real device.'
