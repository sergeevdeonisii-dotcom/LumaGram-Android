#requires -Version 7.0
<#
Validate the two throwaway-signed CI APKs, then sign local copies with the
existing release key. Never uploads a key, mutates downloaded APKs, publishes,
installs, or starts Gradle. Android tools: developer.android.com/tools/apksigner
and /tools/zipalign. DEX: source.android.com/docs/core/runtime/dex-format.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ArtifactRoot,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedSourceCommit,
    [string]$OutputRoot = 'C:\Users\denis228\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\TelegramCodex\personal\exports\759671544',
    [string]$SdkRoot = 'C:\Users\denis228\Documents\Codex\2026-07-23\new-chat-2\work\android-sdk',
    [string]$JavaHome,
    [string]$ReferenceApk = 'D:\CodexBuildCache\Lunagram-78-release\app.apk',
    [string]$StagingRoot = 'D:\CodexBuildCache\Lunagram-79',
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $JavaHome) { $JavaHome = Join-Path $repoRoot '../jdk17/jdk-17.0.20.1+1' }
$ArtifactRoot = [IO.Path]::GetFullPath($ArtifactRoot)
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$StagingRoot = [IO.Path]::GetFullPath($StagingRoot)
$toolsRoot = Join-Path $SdkRoot 'build-tools/36.0.0'
$java = Join-Path $JavaHome 'bin/java.exe'
$aapt = Join-Path $toolsRoot 'aapt.exe'
$zipalign = Join-Path $toolsRoot 'zipalign.exe'
$apksigner = Join-Path $toolsRoot 'lib/apksigner.jar'
foreach ($tool in @($java, $aapt, $zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw 'A required local Android signing tool is missing.' }
}

# A small bounded DEX reader avoids dumping hundreds of MB of disassembly or
# launching another JVM. It accepts only static boolean methods returning an
# immediate 0/1; unexpected code fails closed instead of guessing the policy.
if (-not ('LunagramCiApkAudit79' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Text;
public static class LunagramCiApkAudit79 {
    sealed class Dex {
        readonly byte[] b;
        readonly uint strings, stringsOff, types, typesOff, protos, protosOff, methods, methodsOff, classes, classesOff;
        public Dex(byte[] data) {
            b = data;
            if (b.Length < 112 || Encoding.ASCII.GetString(b,0,4) != "dex\n" || b[7] != 0 ||
                Encoding.ASCII.GetString(b,4,3).CompareTo("035") < 0 ||
                Encoding.ASCII.GetString(b,4,3).CompareTo("040") > 0 ||
                U32(32) != b.Length || U32(36) != 112 || U32(40) != 0x12345678)
                throw new InvalidOperationException("Unexpected DEX header.");
            strings=U32(56); stringsOff=U32(60); types=U32(64); typesOff=U32(68);
            protos=U32(72); protosOff=U32(76); methods=U32(88); methodsOff=U32(92);
            classes=U32(96); classesOff=U32(100);
            Range(stringsOff,(long)strings*4); Range(typesOff,(long)types*4);
            Range(protosOff,(long)protos*12); Range(methodsOff,(long)methods*8); Range(classesOff,(long)classes*32);
        }
        void Range(long p,long n) { if (p<0 || n<0 || p>b.Length-n) throw new InvalidOperationException("Malformed DEX range."); }
        ushort U16(long p) { Range(p,2); return BitConverter.ToUInt16(b,(int)p); }
        uint U32(long p) { Range(p,4); return BitConverter.ToUInt32(b,(int)p); }
        uint Leb(ref long p) {
            uint result=0;
            for (int i=0;i<5;i++) {
                Range(p,1); byte x=b[(int)p++];
                if (i==4 && (x&0xf0)!=0) throw new InvalidOperationException("Malformed DEX LEB128.");
                result|=(uint)(x&0x7f)<<(i*7); if ((x&0x80)==0) return result;
            }
            throw new InvalidOperationException("Malformed DEX LEB128.");
        }
        string Str(uint idx) {
            if (idx>=strings) throw new InvalidOperationException("Invalid DEX string index.");
            long p=U32(stringsOff+(long)idx*4); Leb(ref p); long start=p;
            while (true) { Range(p,1); if (b[(int)p++]==0) break; if (p-start>4096) throw new InvalidOperationException("DEX policy identifier too long."); }
            return Encoding.UTF8.GetString(b,(int)start,(int)(p-start-1));
        }
        string Type(uint idx) {
            if (idx>=types) throw new InvalidOperationException("Invalid DEX type index.");
            return Str(U32(typesOff+(long)idx*4));
        }
        bool ConstantBoolean(uint code) {
            if (code==0) throw new InvalidOperationException("Policy method has no DEX code.");
            Range(code,16); uint n=U32(code+12); long p=code+16;
            Range(p,(long)n*2);
            if (U16(code+2)!=0 || U16(code+6)!=0 || n<2 || n>3)
                throw new InvalidOperationException("Policy method is not an immediate boolean return.");
            ushort first=U16(p), ret; int reg,value;
            if ((first&255)==0x12 && n==2) { reg=(first>>8)&15; value=(first>>12)&15; ret=U16(p+2); }
            else if ((first&255)==0x13 && n==3) { reg=first>>8; value=U16(p+2); ret=U16(p+4); }
            else throw new InvalidOperationException("Unexpected DEX policy opcode.");
            if (reg>=U16(code) || (value!=0 && value!=1) || (ret&255)!=0x0f || (ret>>8)!=reg)
                throw new InvalidOperationException("Invalid DEX boolean return.");
            return value==1;
        }
        public Dictionary<string,bool> Policy() {
            Dictionary<string,bool> result=null;
            for (uint i=0;i<classes;i++) {
                long def=classesOff+(long)i*32; uint classIdx=U32(def);
                if (Type(classIdx)!="Lorg/telegram/messenger/LumaBuildPolicy;") continue;
                if (result!=null) throw new InvalidOperationException("Duplicate DEX policy class.");
                result=new Dictionary<string,bool>(StringComparer.Ordinal);
                long p=U32(def+24); if (p==0) throw new InvalidOperationException("Empty DEX policy class.");
                uint stat=Leb(ref p), inst=Leb(ref p), direct=Leb(ref p), virt=Leb(ref p);
                if ((long)stat+inst+direct+virt>methods+types+strings) throw new InvalidOperationException("Invalid DEX class counts.");
                for (long f=0;f<(long)stat+inst;f++) { Leb(ref p); Leb(ref p); }
                for (int kind=0;kind<2;kind++) {
                    uint idx=0, count=kind==0?direct:virt;
                    for (uint m=0;m<count;m++) {
                        uint diff=Leb(ref p); if ((long)idx+diff>=methods) throw new InvalidOperationException("Invalid DEX method index.");
                        idx+=diff; uint access=Leb(ref p), code=Leb(ref p); long id=methodsOff+(long)idx*8;
                        if (U16(id)!=classIdx) throw new InvalidOperationException("Unexpected DEX method owner.");
                        string name=Str(U32(id+4));
                        if (name!="isFriendsEdition" && name!="allowsPrivacyTools" && name!="allowsAnonymousNumber" &&
                            name!="allowsProfileVerification" && name!="allowsBuiltInUpdates") continue;
                        uint proto=U16(id+2); if (proto>=protos) throw new InvalidOperationException("Invalid DEX prototype.");
                        long protoOff=protosOff+(long)proto*12;
                        if ((access&9)!=9 || (access&0x500)!=0 || Type(U32(protoOff+4))!="Z" || U32(protoOff+8)!=0 || result.ContainsKey(name))
                            throw new InvalidOperationException("Invalid DEX policy method signature.");
                        result.Add(name,ConstantBoolean(code));
                    }
                }
                if (result.Count!=5) throw new InvalidOperationException("Missing DEX policy method.");
            }
            return result;
        }
    }
    public static Dictionary<string,bool> ReadDexPolicy(byte[] data) { return new Dex(data).Policy(); }
}
'@
}

function Run-Checked([string]$Tool, [string[]]$Arguments, [string]$Failure) {
    # Capture tool output, never echo signing command lines or credentials.
    $output = @(& $Tool @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw $Failure }
    return $output
}
function Get-SigningCertificate([string]$Apk) {
    $lines = Run-Checked $java @('-Xmx96m', '-XX:ActiveProcessorCount=1', '-jar', $apksigner,
        'verify', '--print-certs', $Apk) 'APK signature verification failed.'
    $certs = @($lines | ForEach-Object {
        if ([string]$_ -match '^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$') { $matches[1].ToLowerInvariant() }
    })
    if ($certs.Count -ne 1) { throw 'Expected one APK signer.' }
    return $certs[0]
}
function Get-ApkManifest([string]$Apk) {
    $lines = Run-Checked $aapt @('dump', 'badging', $Apk) 'APK manifest cannot be read.'
    $package = @($lines | Where-Object { [string]$_ -match '^package: ' })
    if ($package.Count -ne 1 -or [string]$package[0] -notmatch "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") {
        throw 'Unexpected APK package metadata.'
    }
    $manifest = @{ Package = $matches[1]; VersionCode = [int]$matches[2]; VersionName = $matches[3] }
    if (@($lines | Where-Object { [string]$_ -match '^application-debuggable' }).Count) { throw 'A debug APK is not deliverable.' }
    $native = @($lines | Where-Object { [string]$_ -match '^native-code: ' })
    if ($native.Count -ne 1 -or [string]$native[0] -notmatch "^native-code: 'arm64-v8a'\s*$") { throw 'Unexpected APK native architecture.' }
    return $manifest
}
function Assert-Apk([string]$Apk, [bool]$Friends, [string]$Version) {
    $manifest = Get-ApkManifest $Apk
    if ($manifest.Package -cne 'org.luma.liquid.web' -or $manifest.VersionCode -ne 71589 -or $manifest.VersionName -cne $Version) {
        throw 'APK package or version does not match the requested release.'
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        if ($zip.Entries.Count -gt 20000) { throw 'Unexpected APK entry count.' }
        # APK/Android paths are case-sensitive; resource shrinking legitimately
        # emits different files such as res/OUz.webp and res/ouz.webp. Nothing
        # here is extracted to a case-insensitive Windows directory.
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $dexEntries = @()
        $nativeCount = 0
        foreach ($entry in $zip.Entries) {
            if (-not $names.Add($entry.FullName) -or $entry.FullName -match '(^/|\\|(^|/)\.\.(/|$))') { throw 'Unsafe or duplicate APK entry.' }
            if ($entry.FullName -match '^classes\d*\.dex$') {
                if ($entry.Length -lt 112 -or $entry.Length -gt 64MB) { throw 'Unexpected DEX length.' }
                $dexEntries += $entry
            }
            if ($entry.FullName -match '^lib/([^/]+)/[^/]+\.so$') {
                if ($matches[1] -cne 'arm64-v8a') { throw 'APK contains an unexpected native ABI.' }
                $nativeCount++
            }
        }
        if (-not $names.Contains('AndroidManifest.xml') -or -not $names.Contains('resources.arsc') -or
            -not $names.Contains('classes.dex') -or $nativeCount -eq 0 -or $dexEntries.Count -gt 16) {
            throw 'APK is missing expected release contents.'
        }
        $found = 0
        foreach ($entry in $dexEntries) {
            $stream = $entry.Open()
            $reader = [IO.BinaryReader]::new($stream)
            try {
                $bytes = $reader.ReadBytes([int]$entry.Length)
                if ($bytes.Length -ne $entry.Length) { throw 'Truncated DEX data.' }
                $policy = [LunagramCiApkAudit79]::ReadDexPolicy($bytes)
                if ($null -ne $policy) {
                    $found++
                    if ($policy['isFriendsEdition'] -ne $Friends) { throw 'Actual DEX edition does not match the requested APK.' }
                    foreach ($capability in @('allowsPrivacyTools', 'allowsAnonymousNumber', 'allowsProfileVerification', 'allowsBuiltInUpdates')) {
                        if ($policy[$capability] -ne (-not $Friends)) { throw 'Actual DEX capabilities do not match the requested edition.' }
                    }
                }
            } finally { $bytes = $null; $reader.Dispose() }
        }
        if ($found -ne 1) { throw 'Expected exactly one actual DEX edition-policy class.' }
    } finally { $zip.Dispose() }
}
function Get-PayloadDigest([string]$Apk) {
    # apksigner is allowed to replace only signature metadata; all other ZIP
    # entries are compared by name, length and uncompressed SHA-256.
    $zip = [IO.Compression.ZipFile]::OpenRead($Apk)
    $combined = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        foreach ($entry in @($zip.Entries | Sort-Object -Property FullName -CaseSensitive)) {
            if ($entry.FullName -match '(?i)^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC)|SIG-[^/]+)$') { continue }
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = [Convert]::ToHexString($sha.ComputeHash($stream)) } finally { $sha.Dispose(); $stream.Dispose() }
            $row = [Text.Encoding]::UTF8.GetBytes($entry.FullName + "`n" + $entry.Length + "`n" + $hash + "`n")
            $combined.AppendData($row)
        }
        return [Convert]::ToHexString($combined.GetHashAndReset()).ToLowerInvariant()
    } finally { $combined.Dispose(); $zip.Dispose() }
}
function Read-JavaProperties([string]$Path) {
    # Standard Properties.load(InputStream) encoding and escapes, including
    # continued lines. Content and passwords are intentionally never printed.
    $result = @{}
    $pending = ''
    foreach ($raw in [IO.File]::ReadAllLines($Path, [Text.Encoding]::Latin1)) {
        $line = $pending + $raw.TrimStart()
        $slashes = 0
        for ($i = $line.Length - 1; $i -ge 0 -and $line[$i] -eq '\'; $i--) { $slashes++ }
        if (($slashes % 2) -eq 1) { $pending = $line.Substring(0, $line.Length - 1); continue }
        $pending = ''
        if (-not $line -or $line -match '^[#!]') { continue }
        if ($line -notmatch '^([^=:\s]+)\s*[=:]\s*(.*)$') { throw 'Unsupported signing properties syntax.' }
        $key = $matches[1]
        $value = [regex]::Replace($matches[2], '\\(u[0-9a-fA-F]{4}|.)', {
            param($m)
            $escaped = $m.Groups[1].Value
            if ($escaped.StartsWith('u')) { return [string][char][Convert]::ToInt32($escaped.Substring(1), 16) }
            switch -CaseSensitive ($escaped) { 't' { return "`t" }; 'r' { return "`r" }; 'n' { return "`n" }; 'f' { return [string][char]12 }; default { return $escaped } }
        })
        if ($result.ContainsKey($key)) { throw 'Duplicate signing property.' }
        $result[$key] = $value
    }
    if ($pending) { throw 'Incomplete signing properties continuation.' }
    foreach ($key in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
        if (-not $result.ContainsKey($key) -or [string]::IsNullOrEmpty($result[$key])) { throw 'Required signing property is missing.' }
    }
    return $result
}

$metadataPath = Join-Path $ArtifactRoot 'build-metadata.json'
if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf) -or (Get-Item -LiteralPath $metadataPath).Length -gt 64KB) {
    throw 'Missing or oversized CI build metadata.'
}
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
if ($metadata.schemaVersion -ne 1 -or $metadata.sourceCommit -ine $ExpectedSourceCommit -or
    $metadata.packageName -cne 'org.luma.liquid.web' -or $metadata.architecture -cne 'arm64-v8a' -or
    @($metadata.artifacts).Count -ne 2) { throw 'CI build provenance does not match the requested build.' }
$editions = @(
    @{ Name = 'full'; Friends = $false; Version = '12.10.6-lunagram.79'; InputName = 'Lunagram-12.10.6-lunagram.79-full-temporary.apk'; OutputName = 'Lunagram-12.10.6-lunagram.79-arm64.apk' },
    @{ Name = 'friends'; Friends = $true; Version = '12.10.6-lunagram.79-friends'; InputName = 'Lunagram-12.10.6-lunagram.79-friends-temporary.apk'; OutputName = 'Lunagram-12.10.6-lunagram.79-friends-arm64.apk' }
)
foreach ($edition in $editions) {
    $record = @($metadata.artifacts | Where-Object { $_.edition -ceq $edition.Name })
    if ($record.Count -ne 1) { throw 'Missing or duplicate CI artifact edition.' }
    $record = $record[0]
    if ($record.fileName -cne $edition.InputName -or $record.versionName -cne $edition.Version -or
        $record.versionCode -ne 71589 -or $record.friendsEdition -isnot [bool] -or $record.friendsEdition -ne $edition.Friends -or
        $record.sha256 -notmatch '^[0-9a-fA-F]{64}$') { throw 'CI artifact metadata mismatch.' }
    $edition.Input = Join-Path $ArtifactRoot $edition.InputName
    if (-not (Test-Path -LiteralPath $edition.Input -PathType Leaf)) { throw 'A requested CI APK is missing.' }
    $edition.InputHash = (Get-FileHash -LiteralPath $edition.Input -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($edition.InputHash -ine $record.sha256) { throw 'Downloaded APK SHA-256 does not match CI metadata.' }
    Assert-Apk $edition.Input $edition.Friends $edition.Version
    $edition.PayloadHash = Get-PayloadDigest $edition.Input
    Write-Output ('Validated manifest, ABI, SHA-256 and actual DEX policy: ' + $edition.Name)
}
if ($ValidateOnly) { Write-Output 'Validation complete; no private key was read and no files were signed.'; return }

if (-not (Test-Path -LiteralPath $ReferenceApk -PathType Leaf)) { throw 'Local .78 reference APK is missing.' }
$os = Get-CimInstance Win32_OperatingSystem
if ($os.FreePhysicalMemory -lt (512MB / 1KB) -or $os.FreeVirtualMemory -lt (384MB / 1KB)) { throw 'Not enough free memory for bounded local signing.' }
if (@(Get-Process java, javac -ErrorAction SilentlyContinue).Count) { throw 'Wait for existing Java work before local signing.' }
foreach ($directory in @($OutputRoot, $StagingRoot)) {
    $drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($directory))
    if ($drive.AvailableFreeSpace -lt 160MB) { throw 'Not enough free disk space for signing/delivery.' }
}
$shaPath = Join-Path $OutputRoot 'Lunagram-79-SHA256SUMS.txt'
if (Test-Path -LiteralPath $shaPath) { throw 'Delivery checksum file already exists; inspect the previous result instead of overwriting it.' }
foreach ($edition in $editions) {
    $edition.Output = Join-Path $OutputRoot $edition.OutputName
    if (Test-Path -LiteralPath $edition.Output) { throw 'Delivery APK already exists; inspect it instead of overwriting it.' }
}
$referenceCert = Get-SigningCertificate $ReferenceApk
foreach ($edition in $editions) {
    if ((Get-SigningCertificate $edition.Input) -ceq $referenceCert) { throw 'CI input unexpectedly uses the real release certificate; stop and audit key handling.' }
}
$propertiesPath = Join-Path $repoRoot 'local-signing/luma-signing.properties'
if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) { throw 'Local release signing configuration is missing.' }
$signing = Read-JavaProperties $propertiesPath
$keyStore = if ([IO.Path]::IsPathRooted($signing.storeFile)) {
    [IO.Path]::GetFullPath($signing.storeFile)
} else { [IO.Path]::GetFullPath((Join-Path $repoRoot $signing.storeFile)) }
$privateRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'local-signing')) + [IO.Path]::DirectorySeparatorChar
if (-not $keyStore.StartsWith($privateRoot, [StringComparison]::OrdinalIgnoreCase) -or
    -not (Test-Path -LiteralPath $keyStore -PathType Leaf)) { throw 'Expected release keystore inside local-signing.' }
$runRoot = Join-Path $StagingRoot ('local-sign-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$storeEnvName = 'LUNAGRAM79_STORE_' + [guid]::NewGuid().ToString('N')
$keyEnvName = 'LUNAGRAM79_KEY_' + [guid]::NewGuid().ToString('N')
$checksums = @()
try {
    [Environment]::SetEnvironmentVariable($storeEnvName, $signing.storePassword, 'Process')
    [Environment]::SetEnvironmentVariable($keyEnvName, $signing.keyPassword, 'Process')
    foreach ($edition in $editions) {
        $aligned = Join-Path $runRoot ($edition.Name + '-aligned.apk')
        $signed = Join-Path $runRoot $edition.OutputName
        Run-Checked $zipalign @('-P', '16', '-f', '4', $edition.Input, $aligned) 'APK alignment failed.' | Out-Null
        Run-Checked $java @('-Xmx128m', '-XX:ActiveProcessorCount=1', '-jar', $apksigner,
            'sign', '--ks', $keyStore, '--ks-key-alias', $signing.keyAlias,
            '--ks-pass', ('env:' + $storeEnvName), '--key-pass', ('env:' + $keyEnvName),
            '--v4-signing-enabled', 'false', '--out', $signed, $aligned) 'Local APK signing failed.' | Out-Null
        if ((Get-SigningCertificate $signed) -cne $referenceCert) { throw 'Signed APK certificate differs from the existing release.' }
        Run-Checked $zipalign @('-c', '-P', '16', '4', $signed) 'Signed APK alignment verification failed.' | Out-Null
        if ((Get-PayloadDigest $signed) -cne $edition.PayloadHash) { throw 'Signing unexpectedly modified the APK application payload.' }
        Assert-Apk $signed $edition.Friends $edition.Version
        if ((Get-FileHash -LiteralPath $edition.Input -Algorithm SHA256).Hash -ine $edition.InputHash) { throw 'Downloaded input APK was modified unexpectedly.' }
        $edition.FinalHash = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::Copy($signed, $edition.Output, $false)
        if ((Get-FileHash -LiteralPath $edition.Output -Algorithm SHA256).Hash -ine $edition.FinalHash) { throw 'Delivery APK copy failed verification.' }
        $checksums += $edition.FinalHash + '  ' + $edition.OutputName
        # Remove only this run's individually named generated copies. Inputs,
        # key material, reference APK and other cached builds are preserved.
        Remove-Item -LiteralPath $aligned, $signed -Force
        Write-Output ('Signed and verified locally: ' + $edition.Output)
    }
    $shaStream = [IO.File]::Open($shaPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $shaWriter = [IO.StreamWriter]::new($shaStream, [Text.UTF8Encoding]::new($false))
    try { foreach ($line in $checksums) { $shaWriter.WriteLine($line) } } finally { $shaWriter.Dispose() }
    Write-Output ('Checksums: ' + $shaPath)
} finally {
    [Environment]::SetEnvironmentVariable($storeEnvName, $null, 'Process')
    [Environment]::SetEnvironmentVariable($keyEnvName, $null, 'Process')
    $signing = $null
}
