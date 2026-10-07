$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Source([string]$path) { [IO.File]::ReadAllText((Join-Path $repo $path)) }
function Check([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
    Write-Output "PASS: $message"
}
foreach ($directory in Get-ChildItem (Join-Path $repo 'TMessagesProj/src/main/res') -Directory -Filter 'values*') {
    $path = Join-Path $directory.FullName 'strings.xml'
    if (-not (Test-Path -LiteralPath $path)) { continue }
    [xml]$xml = [IO.File]::ReadAllText($path)
    $app = @($xml.resources.string | Where-Object name -CEQ 'AppName')
    if ($app.Count -eq 0) { continue }
    Check ($app.Count -eq 1 -and $app[0].InnerText -eq 'Lunagram') "Lunagram launcher label ($($directory.Name))"
    Check (@($xml.resources.string | Where-Object { $_.InnerText -match 'BlackHoleGram|LumaGram' }).Count -eq 0) "No legacy display brand ($($directory.Name))"
}
$properties = Source 'gradle.properties'
Check ($properties -match '(?m)^APP_PACKAGE=org\.luma\.liquid\r?$') 'Android upgrade package is unchanged'
Check ($properties -match '(?m)^APP_VERSION_NAME=12\.10\.6-lunagram\.74\r?$') 'Lunagram release keeps Telegram 12.10.6 base'
$updater = Source 'TMessagesProj/src/main/java/org/telegram/messenger/LumaUpdaterController.java'
Check ($updater.Contains('"Lunagram-Android/"') -and $updater.Contains('"Lunagram.apk"')) 'Download metadata uses Lunagram'
Check ((Source 'TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java').Contains('https://raw.githubusercontent.com/sergeevdeonisii-dotcom/LumaGram-Android/main/updates/latest.json')) 'Existing installed update source is unchanged'
Check ((Source 'TMessagesProj/src/main/java/org/telegram/messenger/BlackHoleSettings.java').Contains('blackholegram-settings')) 'Legacy settings transfer remains compatible'
Check ((Source 'TMessagesProj/src/main/java/org/telegram/ui/ExperimentalFeaturesActivity.java').Contains('LumaGhostMode')) 'Mobile ghost mode remains available'
Check ((Source 'TMessagesProj/src/main/java/org/telegram/ui/BlackHoleToolsActivity.java').Contains('Lunagram-settings.json')) 'Settings download uses the new brand'
Write-Output 'Lunagram branding source checks passed; packaged APK verification is separate.'
