function Assert-BlackHoleGramElfHeaders {
    [CmdletBinding()]
    param([string]$Headers, [string]$LibraryName, [switch]$RequireAlignedRelro)
    $loadSegments = @($Headers -split '\r?\n' | Where-Object { $_ -match '^\s*LOAD\s' })
    if ($loadSegments.Count -eq 0) { throw "No ELF load segments: $LibraryName" }
    foreach ($line in $loadSegments) {
        $fields = $line.Trim() -split '\s+'
        if ($fields.Count -lt 8 -or $fields[-1] -notmatch '^0x[0-9a-fA-F]+$') {
            throw "Unrecognized ELF load header: $LibraryName"
        }
        $alignment = [Convert]::ToUInt64($fields[-1].Substring(2), 16)
        $offset = [Convert]::ToUInt64($fields[1].Substring(2), 16)
        $address = [Convert]::ToUInt64($fields[2].Substring(2), 16)
        if ($alignment -lt 16384 -or ($alignment -band ($alignment - 1)) -ne 0 -or ($offset % 16384) -ne ($address % 16384)) {
            throw "ELF load segment is not 16 KiB aligned: $LibraryName"
        }
    }
    foreach ($line in @($Headers -split '\r?\n' | Where-Object { $_ -match '^\s*GNU_RELRO\s' })) {
        $fields = $line.Trim() -split '\s+'
        if ($fields.Count -lt 8) { throw "Unrecognized ELF RELRO header: $LibraryName" }
        $address = [Convert]::ToUInt64($fields[2].Substring(2), 16)
        $memorySize = [Convert]::ToUInt64($fields[5].Substring(2), 16)
        if (($address + $memorySize) % 16384 -ne 0) {
            $message = "ELF RELRO end is not 16 KiB aligned: $LibraryName"
            if ($RequireAlignedRelro) { throw $message }
            Write-Warning "$message. Vendor library needs a real 16 KiB-device check; LOAD alignment alone is not proof of full compatibility."
        }
    }
}

function Assert-BlackHoleGramNativeAlignment {
    [CmdletBinding()]
    param([string]$LibraryPath, [string]$NdkToolsPath, [switch]$RequireAlignedRelro)
    $headers = (& (Join-Path $NdkToolsPath 'llvm-readelf.exe') --program-headers --wide $LibraryPath) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect native ELF: $LibraryPath" }
    Assert-BlackHoleGramElfHeaders -Headers $headers -LibraryName $LibraryPath -RequireAlignedRelro:$RequireAlignedRelro
    Write-Output "PASS: native ELF LOAD alignment ($([System.IO.Path]::GetFileName($LibraryPath)))"
}
