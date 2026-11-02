param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [ValidateSet('public', 'personal', 'friends')][string]$ExpectedEdition = 'public',
    [ValidateSet('any', 'enabled', 'disabled')][string]$ExpectedBuiltInUpdates = 'any'
)
$ErrorActionPreference = 'Stop'

# Read-only packaged-code audit: no extraction, Java, signing configuration, or
# BuildConfig inspection. APK identity and cryptographic signature are separate
# checks. LumaBuildPolicy's public boolean methods are retained by ProGuard.
# The updater's actual constant is always reported. Its default expectation is
# 'any' so historical Public APKs with a disabled updater remain compatible;
# pass -ExpectedBuiltInUpdates enabled for the public-updater hotfix gate.
# Adapted from the bounded DEX reader in the legacy CI signing utility; that
# utility is never loaded or executed here.
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not ('LunagramEditionDexAudit150Updater' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Text;

public static class LunagramEditionDexAudit150Updater {
    sealed class Dex {
        readonly byte[] bytes;
        readonly uint strings, stringsOff, types, typesOff;
        readonly uint protos, protosOff, methods, methodsOff, classes, classesOff;

        public Dex(byte[] data) {
            bytes = data;
            if (bytes.Length < 112 || Encoding.ASCII.GetString(bytes, 0, 4) != "dex\n" || bytes[7] != 0 ||
                String.CompareOrdinal(Encoding.ASCII.GetString(bytes, 4, 3), "035") < 0 ||
                String.CompareOrdinal(Encoding.ASCII.GetString(bytes, 4, 3), "040") > 0 ||
                U32(32) != bytes.Length || U32(36) != 112 || U32(40) != 0x12345678)
                throw new InvalidOperationException("Unexpected DEX header.");
            strings = U32(56); stringsOff = U32(60);
            types = U32(64); typesOff = U32(68);
            protos = U32(72); protosOff = U32(76);
            methods = U32(88); methodsOff = U32(92);
            classes = U32(96); classesOff = U32(100);
            Range(stringsOff, (long)strings * 4); Range(typesOff, (long)types * 4);
            Range(protosOff, (long)protos * 12); Range(methodsOff, (long)methods * 8);
            Range(classesOff, (long)classes * 32);
        }

        void Range(long offset, long length) {
            if (offset < 0 || length < 0 || offset > bytes.Length - length)
                throw new InvalidOperationException("Malformed DEX range.");
        }
        ushort U16(long offset) { Range(offset, 2); return BitConverter.ToUInt16(bytes, (int)offset); }
        uint U32(long offset) { Range(offset, 4); return BitConverter.ToUInt32(bytes, (int)offset); }
        uint Leb(ref long offset) {
            uint result = 0;
            for (int i = 0; i < 5; i++) {
                Range(offset, 1);
                byte value = bytes[(int)offset++];
                if (i == 4 && (value & 0xf0) != 0)
                    throw new InvalidOperationException("Malformed DEX LEB128.");
                result |= (uint)(value & 0x7f) << (i * 7);
                if ((value & 0x80) == 0) return result;
            }
            throw new InvalidOperationException("Malformed DEX LEB128.");
        }
        string Str(uint index) {
            if (index >= strings) throw new InvalidOperationException("Invalid DEX string index.");
            long offset = U32(stringsOff + (long)index * 4);
            Leb(ref offset);
            long start = offset;
            while (true) {
                Range(offset, 1);
                if (bytes[(int)offset++] == 0) break;
                if (offset - start > 4096) throw new InvalidOperationException("DEX policy identifier too long.");
            }
            return Encoding.UTF8.GetString(bytes, (int)start, (int)(offset - start - 1));
        }
        string Type(uint index) {
            if (index >= types) throw new InvalidOperationException("Invalid DEX type index.");
            return Str(U32(typesOff + (long)index * 4));
        }
        bool ConstantBoolean(uint codeOffset) {
            if (codeOffset == 0) throw new InvalidOperationException("Policy method has no DEX code.");
            long code = codeOffset;
            Range(code, 16);
            uint count = U32(code + 12);
            long offset = code + 16;
            Range(offset, (long)count * 2);
            if (U16(code + 2) != 0 || U16(code + 4) != 0 || U16(code + 6) != 0 || count < 2 || count > 3)
                throw new InvalidOperationException("Policy method is not an immediate boolean return.");
            ushort first = U16(offset), ret;
            int register, value;
            if ((first & 255) == 0x12 && count == 2) {
                register = (first >> 8) & 15; value = (first >> 12) & 15; ret = U16(offset + 2);
            } else if ((first & 255) == 0x13 && count == 3) {
                register = first >> 8; value = U16(offset + 2); ret = U16(offset + 4);
            } else throw new InvalidOperationException("Unexpected DEX policy opcode.");
            if (register >= U16(code) || (value != 0 && value != 1) || (ret & 255) != 0x0f || (ret >> 8) != register)
                throw new InvalidOperationException("Invalid DEX boolean return.");
            return value == 1;
        }
        public Dictionary<string, bool> Policy() {
            Dictionary<string, bool> result = null;
            for (uint i = 0; i < classes; i++) {
                long def = classesOff + (long)i * 32;
                uint classIndex = U32(def);
                if (Type(classIndex) != "Lorg/telegram/messenger/LumaBuildPolicy;") continue;
                if (result != null) throw new InvalidOperationException("Duplicate DEX policy class.");
                result = new Dictionary<string, bool>(StringComparer.Ordinal);
                long offset = U32(def + 24);
                if (offset == 0) throw new InvalidOperationException("Empty DEX policy class.");
                uint stat = Leb(ref offset), inst = Leb(ref offset), direct = Leb(ref offset), virt = Leb(ref offset);
                if ((long)stat + inst + direct + virt > (long)methods + types + strings)
                    throw new InvalidOperationException("Invalid DEX class counts.");
                for (long field = 0; field < (long)stat + inst; field++) { Leb(ref offset); Leb(ref offset); }
                for (int kind = 0; kind < 2; kind++) {
                    uint index = 0, count = kind == 0 ? direct : virt;
                    for (uint method = 0; method < count; method++) {
                        uint diff = Leb(ref offset);
                        if ((long)index + diff >= methods) throw new InvalidOperationException("Invalid DEX method index.");
                        index += diff;
                        uint access = Leb(ref offset), code = Leb(ref offset);
                        long id = methodsOff + (long)index * 8;
                        if (U16(id) != classIndex) throw new InvalidOperationException("Unexpected DEX method owner.");
                        string name = Str(U32(id + 4));
                        if (name != "isPublicEdition" && name != "isFriendsEdition" && name != "isPersonalEdition" &&
                            name != "allowsBuiltInUpdates") continue;
                        uint proto = U16(id + 2);
                        if (proto >= protos) throw new InvalidOperationException("Invalid DEX prototype.");
                        long protoOff = protosOff + (long)proto * 12;
                        if ((access & 9) != 9 || (access & 0x500) != 0 || Type(U32(protoOff + 4)) != "Z" ||
                            U32(protoOff + 8) != 0 || result.ContainsKey(name))
                            throw new InvalidOperationException("Invalid DEX policy method signature.");
                        result.Add(name, ConstantBoolean(code));
                    }
                }
                if (result.Count != 4) throw new InvalidOperationException("Missing DEX edition/updater-policy method.");
            }
            return result;
        }
    }
    public static Dictionary<string, bool> ReadPolicy(byte[] data) { return new Dex(data).Policy(); }
}
'@
}

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$archive = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    if ($archive.Entries.Count -gt 20000) { throw 'Unexpected APK entry count.' }
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $dexEntries = @()
    foreach ($entry in $archive.Entries) {
        if (-not $names.Add($entry.FullName) -or $entry.FullName -match '(^/|\\|(^|/)\.\.(/|$))') {
            throw 'Unsafe or duplicate APK entry.'
        }
        if ($entry.FullName -cmatch '^classes(?:[2-9]|[1-9][0-9]+)?\.dex$') {
            if ($entry.Length -lt 112 -or $entry.Length -gt 64MB) { throw 'Unexpected DEX length.' }
            $dexEntries += $entry
        } elseif ($entry.FullName -cmatch '^classes.*\.dex$') {
            throw 'Unexpected APK DEX entry name.'
        }
    }
    if (-not $names.Contains('classes.dex') -or $dexEntries.Count -gt 16) { throw 'Unexpected APK DEX set.' }
    $found = 0
    $matchedPolicy = $null
    $matchedDex = $null
    foreach ($entry in $dexEntries) {
        $reader = [IO.BinaryReader]::new($entry.Open())
        try {
            $bytes = $reader.ReadBytes([int]$entry.Length)
            if ($bytes.Length -ne $entry.Length) { throw 'Truncated DEX data.' }
            $policy = [LunagramEditionDexAudit150Updater]::ReadPolicy($bytes)
            if ($null -ne $policy) {
                $found++
                $matchedPolicy = $policy
                $matchedDex = $entry.FullName
            }
        } finally {
            $bytes = $null
            $reader.Dispose()
        }
    }
    if ($found -ne 1) { throw 'Expected exactly one actual DEX edition-policy class.' }
    $expected = @{
        isPublicEdition = $ExpectedEdition -eq 'public'
        isFriendsEdition = $ExpectedEdition -eq 'friends'
        isPersonalEdition = $ExpectedEdition -eq 'personal'
    }
    foreach ($name in $expected.Keys) {
        if ($matchedPolicy[$name] -ne $expected[$name]) {
            throw "Actual DEX edition does not match expected '$ExpectedEdition': $name=$($matchedPolicy[$name])."
        }
    }
    $actualBuiltInUpdates = $matchedPolicy['allowsBuiltInUpdates']
    if ($ExpectedBuiltInUpdates -ne 'any' -and $actualBuiltInUpdates -ne ($ExpectedBuiltInUpdates -eq 'enabled')) {
        throw "Actual DEX updater does not match expected '$ExpectedBuiltInUpdates': allowsBuiltInUpdates=$actualBuiltInUpdates."
    }
    [pscustomobject]@{
        ApkPath = $apk
        ExpectedEdition = $ExpectedEdition
        PolicyDex = $matchedDex
        Public = $matchedPolicy['isPublicEdition']
        Friends = $matchedPolicy['isFriendsEdition']
        Personal = $matchedPolicy['isPersonalEdition']
        ExpectedBuiltInUpdates = $ExpectedBuiltInUpdates
        AllowsBuiltInUpdates = $actualBuiltInUpdates
        Passed = $true
    }
} finally {
    $archive.Dispose()
}
