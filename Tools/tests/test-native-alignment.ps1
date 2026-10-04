$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-alignment.ps1')
$aligned = @'
  LOAD 0x000000 0x0000000000000000 0x0000000000000000 0x004000 0x004000 R E 0x4000
  LOAD 0x004000 0x0000000000004000 0x0000000000004000 0x001000 0x004000 RW 0x4000
  GNU_RELRO 0x004000 0x0000000000004000 0x0000000000004000 0x001000 0x004000 R 0x1
'@
Assert-BlackHoleGramElfHeaders -Headers $aligned -LibraryName 'aligned fixture'
Assert-BlackHoleGramElfHeaders -Headers (($aligned -split '\r?\n' | Where-Object { $_ -notmatch 'GNU_RELRO' }) -join "`n") -LibraryName 'no RELRO fixture'
$compatibilityWarnings = @()
Assert-BlackHoleGramElfHeaders -Headers $aligned.Replace('0x004000 R 0x1', '0x001000 R 0x1') -LibraryName 'vendor RELRO fixture' -WarningVariable compatibilityWarnings -WarningAction SilentlyContinue
if ($compatibilityWarnings.Count -ne 1) { throw 'Vendor RELRO compatibility warning was not recorded' }
$invalid = @(
    $aligned.Replace('0x4000', '0x1000'),
    $aligned.Replace('0x004000 R 0x1', '0x001000 R 0x1'),
    $aligned.Replace('0x004000 0x0000000000004000', '0x004000 0x0000000000005000'),
    $aligned.Replace('0x4000', 'unknown'),
    'no program headers'
)
foreach ($headers in $invalid) {
    $rejected = $false
    try { Assert-BlackHoleGramElfHeaders -Headers $headers -LibraryName 'invalid fixture' -RequireAlignedRelro }
    catch { $rejected = $true }
    if (!$rejected) { throw 'Invalid ELF alignment fixture was accepted' }
}
Write-Output 'PASS: native alignment parser (synthetic aligned/unaligned/RELRO/malformed fixtures).'
