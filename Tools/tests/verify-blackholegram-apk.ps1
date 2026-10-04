param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$SdkPath,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$NdkVersion = '27.2.12479018',
    [string]$VersionName = '12.10.6-bhg.67',
    [int]$VersionCode = 71469
)
$ErrorActionPreference = 'Stop'
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$buildTools = Join-Path $SdkPath 'build-tools/36.0.0'
$ndkTools = Join-Path $SdkPath "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin"
. (Join-Path $PSScriptRoot 'native-alignment.ps1')
$badging = (& (Join-Path $buildTools 'aapt2.exe') dump badging $apk) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'APK metadata inspection failed' }
foreach ($expected in @(
    "name='org.luma.liquid.web'",
    "versionCode='$VersionCode'",
    "versionName='$VersionName'",
    "application-label:'BlackHoleGram'",
    "targetSdkVersion:'36'",
    "native-code: 'arm64-v8a'"
)) {
    if (!$badging.Contains($expected)) { throw "Missing APK metadata: $expected" }
    Write-Output "PASS: $expected"
}
$manifest = (& (Join-Path $buildTools 'aapt2.exe') dump xmltree --file AndroidManifest.xml $apk) -join "`n"
if ($LASTEXITCODE -ne 0 -or !$manifest.Contains('org.telegram.messenger.BlackHoleIcon')) {
    throw 'Black Hole launcher alias is missing from the final APK'
}
Write-Output 'PASS: Black Hole launcher alias in the packaged manifest'
$resources = (& (Join-Path $buildTools 'aapt2.exe') dump resources $apk) -join "`n"
if ($LASTEXITCODE -ne 0 -or !$resources.Contains('drawable/bhg_icon_blackhole_photo')) {
    throw 'The new Black Hole photo resource is missing from the final APK'
}
Write-Output 'PASS: new Black Hole photo resource in the packaged APK'
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
        throw 'Expected Telegram native library is missing'
    }
    if (@($nativeFiles | Where-Object { $_.FullName -NotLike 'lib/arm64-v8a/*' -or ($_.FullName -match '/libtmessages\.' -and $_.FullName -ne 'lib/arm64-v8a/libtmessages.49.so') }).Count -gt 0) {
        throw 'Unexpected ABI or stale Telegram native library'
    }
    Write-Output 'PASS: expected native library, arm64 only, no unexpected tmessages versions'
    $nativeCheckDir = Join-Path $PSScriptRoot ('.runs/' + [guid]::NewGuid().ToString() + '/apk-native')
    New-Item -ItemType Directory -Path $nativeCheckDir | Out-Null
    foreach ($entry in $nativeFiles) {
        if ($entry.FullName -cnotmatch '^lib/arm64-v8a/[A-Za-z0-9_.-]+\.so$') {
            throw "Unexpected native archive path: $($entry.FullName)"
        }
        $library = Join-Path $nativeCheckDir ([System.IO.Path]::GetFileName($entry.FullName))
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $library, $false)
        Assert-BlackHoleGramNativeAlignment -LibraryPath $library -NdkToolsPath $ndkTools -RequireAlignedRelro:($entry.FullName -eq 'lib/arm64-v8a/libtmessages.49.so')
    }
    Write-Output "Native inspection artifacts: $nativeCheckDir"
} finally {
    $archive.Dispose()
}
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "SHA-256: $hash"
Write-Output 'Artifact checks passed. This does not establish Android UI, presence or push behavior on a real device.'
