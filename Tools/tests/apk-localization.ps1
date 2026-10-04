# Read the actual formats produced by TelegramStringsTask and GenerateStringResourceIdsAssetTask.
function Get-BlackHoleGramStringHash([string]$Name) {
    [uint64]$hash = 0
    foreach ($character in $Name.ToCharArray()) {
        $hash = ($hash * 31 + [uint16]$character) -band [uint64]4294967295
    }
    return [uint32]$hash
}

function Get-BlackHoleGramStringIds($Archive) {
    $entry = $Archive.GetEntry('assets/string_resource_ids.bin')
    if ($null -eq $entry) { throw 'Packaged string resource ID mapping is missing' }
    $reader = [System.IO.BinaryReader]::new($entry.Open())
    try {
        $ids = [System.Collections.Generic.Dictionary[uint32,uint32]]::new()
        $count = $reader.ReadUInt32()
        if ($count -gt 65536) { throw 'Invalid string ID mapping count' }
        for ($index = 0; $index -lt $count; $index++) {
            $resId = $reader.ReadUInt32()
            $hash = $reader.ReadUInt32()
            $ids.Add($hash, $resId)
        }
        if ($reader.BaseStream.ReadByte() -ne -1) { throw 'Unexpected trailing string ID data' }
        return $ids
    } finally {
        $reader.Dispose()
    }
}

function Get-BlackHoleGramLocalization($Archive, [string]$Tag) {
    $entry = $Archive.GetEntry("assets/localization_$Tag.bin")
    if ($null -eq $entry) { throw "Packaged localization is missing: $Tag" }
    $reader = [System.IO.BinaryReader]::new($entry.Open())
    try {
        $strings = [System.Collections.Generic.Dictionary[uint32,string]]::new()
        $count = $reader.ReadUInt32()
        if ($count -gt 65536) { throw "Invalid localization count: $Tag" }
        for ($index = 0; $index -lt $count; $index++) {
            $hash = $reader.ReadUInt32()
            $marker = $reader.ReadByte()
            if ($marker -eq 254) {
                $length = [int]$reader.ReadByte() -bor ([int]$reader.ReadByte() -shl 8) -bor ([int]$reader.ReadByte() -shl 16)
                $headerLength = 4
            } elseif ($marker -lt 254) {
                $length = [int]$marker
                $headerLength = 1
            } else {
                throw "Invalid TL string marker: $Tag"
            }
            $bytes = $reader.ReadBytes($length)
            if ($bytes.Length -ne $length) { throw "Truncated localization string: $Tag" }
            $paddingLength = (4 - (($length + $headerLength) % 4)) % 4
            $padding = $reader.ReadBytes($paddingLength)
            if ($padding.Length -ne $paddingLength -or @($padding | Where-Object { $_ -ne 0 }).Count -ne 0) {
                throw "Invalid localization padding: $Tag"
            }
            $strings.Add($hash, [System.Text.Encoding]::UTF8.GetString($bytes))
        }
        if ($reader.BaseStream.ReadByte() -ne -1) { throw "Unexpected trailing localization data: $Tag" }
        return $strings
    } finally {
        $reader.Dispose()
    }
}
