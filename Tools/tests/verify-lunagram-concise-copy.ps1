param(
    [string]$RepoRoot = (Join-Path $PSScriptRoot '../..'),
    [string]$ApkPath
)
$ErrorActionPreference = 'Stop'

# Read-only, dependency-free resource checks for the shortened settings copy and
# direct Crypto Bot actions. Do not pin full sentences or open payment services.
$keys = @(
    'LumaAutoBoldMessagesInfo', 'LumaAutomaticMessageStyleInfo',
    'LunagramSubscriptionAccessInfo', 'LunagramSubscriptionTonInfo',
    'LunagramSubscriptionRenewalInfo', 'LunagramSubscriptionUnavailableInfo',
    'LunagramSubscriptionCodeInfo', 'LunagramSubscriptionDisconnected',
    'LunagramSubscriptionPending', 'LunagramSubscriptionStarsCheckout',
    'LunagramSubscriptionConnectToActivate', 'LunagramSubscriptionEphemeral',
    'LunagramSubscriptionDistributionPending', 'LunagramSubscriptionLastConfirmed',
    'LunagramSubscriptionNetworkError', 'LunagramSubscriptionServerError',
    'ExperimentalGhostScheduledSendInfo', 'ExperimentalGhostInfo',
    'EmergencyConnectionInfo', 'ExperimentalTypingSwipeInfo', 'ExperimentalDelayedSendInfo',
    'LumaRoundVideoLegacyQualityInfo', 'LumaRoundVideoStartRearCameraInfo',
    'LumaRoundVideoQualityInfo', 'LumaRoundVideoFpsInfo',
    'LumaRoundVideoStabilizationPublicInfo', 'LumaRoundVideoStabilizationInfo',
    'LumaRoundVideoFastSwitchInfo', 'RoundVideoGeneralInfo',
    'LumaRoundVideoMeasuredNote', 'LumaRoundVideoMeasuredInfo',
    'BHGProfilesInfo', 'BHGNotesInfo', 'BHGJournalInfo', 'BHGTransferInfo',
    'BHGVaultLockInfo', 'BHGVaultInfo',
    'LunagramSubscriptionCryptoPay', 'LunagramSubscriptionCryptoOpen',
    'LunagramSubscriptionCryptoAccept', 'LunagramSubscriptionCryptoBotInfo',
    'LunagramSubscriptionCryptoOrder', 'LunagramSubscriptionCryptoConfirmDetails'
)
$formatPattern = '%(?:[1-9][0-9]*\$)?[-#+ 0,(<]*[0-9]*(?:\.[0-9]+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaAn%])'

function Get-Formats([string]$Text, [string]$Context) {
    $remaining = [regex]::Replace($Text, $formatPattern, '')
    if ($remaining.Contains('%')) { throw "Malformed format escape: $Context" }
    $formats = @([regex]::Matches($Text, $formatPattern) | ForEach-Object {
        if ($_.Value -notin @('%%', '%n')) {
            if ($_.Value -notmatch '^%[1-9][0-9]*\$') {
                throw "Unnumbered format argument: $Context"
            }
            $_.Value
        }
    })
    return (($formats | Sort-Object -CaseSensitive) -join '|')
}

function ConvertTo-PackagedCopy([string]$Text) {
    # TelegramStringsTask.normalizeXmlString runs AFTER XML entity decoding.
    # Match its ordered literal replacements, not Regex.Unescape: the latter
    # interprets escapes (for example \t) that the real pipeline simply strips.
    return $Text.Replace('\n', "`n").Replace('\', '').Replace('&lt;', '<')
}

$copies = @{}
$formatsByLocale = @{}
foreach ($locale in @('values', 'values-ru')) {
    $path = Join-Path $RepoRoot "TMessagesProj/src/main/res/$locale/strings.xml"
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create((Resolve-Path -LiteralPath $path).Path, $settings)
    $xml = [Xml.XmlDocument]::new()
    $xml.XmlResolver = $null
    try { $xml.Load($reader) } finally { $reader.Dispose() }
    $copies[$locale] = @{}
    $formatsByLocale[$locale] = @{}
    foreach ($key in $keys) {
        $nodes = @($xml.SelectNodes("/resources/string[@name='$key']"))
        if ($nodes.Count -ne 1) { throw "Missing or duplicate resource: $locale/$key" }
        $text = $nodes[0].InnerText
        if ([string]::IsNullOrWhiteSpace($text)) { throw "Empty resource: $locale/$key" }
        if ($text.Contains([char]0xfffd)) { throw "Broken Unicode: $locale/$key" }
        if ($text.Length -gt 500) { throw "Settings helper is no longer concise: $locale/$key" }
        if ($text -match '\\(?![\\nrt"''@?u])') { throw "Unexpected resource escape: $locale/$key" }
        # Android resource apostrophes must be escaped (curly punctuation is fine).
        if ($text -match '(?<!\\)(?:\\\\)*''') { throw "Unescaped resource apostrophe: $locale/$key" }
        $copies[$locale][$key] = $text
        $formatsByLocale[$locale][$key] = Get-Formats $text "$locale/$key"
    }
}

foreach ($key in $keys) {
    if ($formatsByLocale['values'][$key] -cne $formatsByLocale['values-ru'][$key]) {
        throw "EN/RU format mismatch: $key"
    }
}
# Native callers depend on these argument counts/types, even if both locales
# accidentally drop the same placeholder during a future shortening pass.
$expectedFormats = @{
    ExperimentalGhostScheduledSendInfo = '%1$d'
    LumaRoundVideoMeasuredInfo = '%1$d|%2$d|%3$.1f|%4$.1f|%5$d'
    LunagramSubscriptionCryptoOrder = '%1$s|%2$s|%3$s'
    LunagramSubscriptionCryptoConfirmDetails = '%1$s|%2$s|%3$s|%4$s'
}
foreach ($key in $expectedFormats.Keys) {
    if ($formatsByLocale['values'][$key] -cne $expectedFormats[$key]) {
        throw "Native format contract changed: $key"
    }
}
foreach ($locale in @('values', 'values-ru')) {
    foreach ($key in @('LunagramSubscriptionCryptoPay', 'LunagramSubscriptionCryptoOpen', 'LunagramSubscriptionCryptoAccept')) {
        if ($copies[$locale][$key] -notmatch 'Crypto\s*Bot' -or $copies[$locale][$key].Length -gt 80) {
            throw "Direct Crypto Bot action lost its destination or became too long: $locale/$key"
        }
    }
    if ($copies[$locale]['LunagramSubscriptionCryptoBotInfo'] -notmatch '\bUSDT\b' -or
        $copies[$locale]['LunagramSubscriptionCryptoOrder'] -notmatch '\bUSD\b' -or
        $copies[$locale]['LunagramSubscriptionCryptoOrder'] -notmatch '\bUSDT\b') {
        throw "Crypto amount/asset terminology is missing: $locale"
    }
}

$packagedStringsChecked = 0
$resolvedApk = $null
if (-not [string]::IsNullOrWhiteSpace($ApkPath)) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    . (Join-Path $PSScriptRoot 'apk-localization.ps1')
    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $archive = [IO.Compression.ZipFile]::OpenRead($resolvedApk)
    try {
        # Only read bounded localization entries; never extract or modify APKs.
        foreach ($asset in @('assets/string_resource_ids.bin', 'assets/localization_en.bin', 'assets/localization_ru.bin')) {
            $entries = @($archive.Entries | Where-Object FullName -CEQ $asset)
            if ($entries.Count -ne 1 -or $entries[0].Length -lt 4 -or $entries[0].Length -gt 16MB) {
                throw "Missing, duplicated or oversized packaged localization asset: $asset"
            }
        }
        $ids = Get-BlackHoleGramStringIds -Archive $archive
        foreach ($locale in @(@{ Source = 'values'; Tag = 'en' }, @{ Source = 'values-ru'; Tag = 'ru' })) {
            $packaged = Get-BlackHoleGramLocalization -Archive $archive -Tag $locale.Tag
            foreach ($key in $keys) {
                $hash = Get-BlackHoleGramStringHash -Name $key
                if (-not $ids.ContainsKey($hash) -or $ids[$hash] -eq 0 -or -not $packaged.ContainsKey($hash)) {
                    throw "Packaged string or resource mapping missing: $($locale.Tag)/$key"
                }
                $expectedCopy = ConvertTo-PackagedCopy $copies[$locale.Source][$key]
                if ($packaged[$hash] -cne $expectedCopy) {
                    throw "Packaged copy is stale or different from current source: $($locale.Tag)/$key"
                }
                $packagedStringsChecked++
            }
        }
    } finally {
        $archive.Dispose()
    }
}

[pscustomobject]@{
    Passed = $true
    ResourceKeys = $keys.Count
    Locales = @('en', 'ru')
    Checked = 'XML, unique/nonempty copy, escaping, format parity/contracts, direct Crypto Bot terminology'
    ApkPath = $resolvedApk
    PackagedStringsChecked = $packagedStringsChecked
}
