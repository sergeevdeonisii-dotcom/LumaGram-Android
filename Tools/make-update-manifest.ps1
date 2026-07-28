param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [int]$VersionCode,

    [Parameter(Mandatory = $true)]
    [string]$ApkUrl,

    [Parameter(Mandatory = $true)]
    [string]$Changelog,

    [string]$OutputPath = "updates/latest.json"
)

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
if (-not $ApkUrl.StartsWith("https://", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "ApkUrl must use HTTPS."
}

$hash = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    version      = $Version
    version_code = $VersionCode
    file_url     = $ApkUrl
    sha256       = $hash
    changelog    = $Changelog
}

$outputFullPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputPath))
$outputDirectory = Split-Path -Parent $outputFullPath
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}
$json = $manifest | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText($outputFullPath, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
Write-Output "Manifest: $outputFullPath"
Write-Output "SHA-256: $hash"
