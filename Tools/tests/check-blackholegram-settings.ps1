$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Source([string]$path) { [System.IO.File]::ReadAllText((Join-Path $repo $path)) }
function Check([bool]$condition, [string]$description) {
    if (!$condition) { throw $description }
    Write-Output "PASS: $description"
}
function Method([string]$source, [string]$name, [string]$access = 'private') {
    $match = [regex]::Match($source, "(?s)    $access (?:void|UItem|String) $name\([^)]*\) \{\r?\n(.*?)\r?\n    \}")
    if (!$match.Success) { throw "Missing method: $name" }
    return $match.Groups[1].Value
}

$advanced = Source 'TMessagesProj/src/main/java/org/telegram/ui/ExperimentalFeaturesActivity.java'
$updates = Source 'TMessagesProj/src/main/java/org/telegram/ui/LumaUpdateActivity.java'
$updater = Source 'TMessagesProj/src/main/java/org/telegram/messenger/LumaUpdaterController.java'
$root = Method $advanced 'fillSections'
$dispatch = Method $advanced 'fillItems'
$click = Method $advanced 'onItemClick'
$rows = @([regex]::Matches($advanced, 'private static final int (ROW_\w+) = (\d+);'))
$sections = @([regex]::Matches($advanced, '(?m)^        (\w+)\((\d+), R\.string\.(\w+)\)[,;]'))
Check ($sections.Count -eq 6 -and $sections[0].Groups[1].Value -eq 'ROOT') 'Root plus exactly five advanced sections'
$allIds = @($rows | ForEach-Object { $_.Groups[2].Value }) + @($sections | ForEach-Object { $_.Groups[2].Value })
Check (@($allIds | Group-Object | Where-Object Count -gt 1).Count -eq 0) 'Category IDs never collide with feature or non-interactive IDs'
Check ([regex]::Matches($root, 'items\.add\(sectionItem\(').Count -eq 5 -and $root -notmatch 'asCheck|asSlideView|asIntSlideView|asButton') 'Advanced root is a compact five-category menu, not a toggle list'
Check ($advanced.Contains('this(Section.ROOT);') -and $advanced.Contains('actionBar.setTitle(getString(section.titleRes));')) 'Existing entry opens the category menu; child screens have their own titles'
Check ($advanced.Contains('section != ROOT && section.id == id') -and $click -match '(?s)if \(section == Section\.ROOT\).*?Section\.fromId\(item\.id\).*?screen\.setCurrentAccount\(currentAccount\);.*?presentFragment\(screen\);.*?return;') 'Root opens only valid child sections and preserves the current account'

$expected = [ordered]@{
    PRIVACY = @{ Method = 'fillPrivacyItems'; Rows = @('ROW_GHOST_ENABLED', 'ROW_GHOST_SCHEDULE_SEND_ENABLED', 'ROW_DELETED_MESSAGES_ENABLED') }
    PROFILE = @{ Method = 'fillProfileItems'; Rows = @('ROW_STAR_RATING_ENABLED', 'ROW_STAR_RATING_LEVEL', 'ROW_ANONYMOUS_NUMBER_ENABLED', 'ROW_ANONYMOUS_NUMBER', 'ROW_PROFILE_VERIFICATION_ENABLED') }
    TYPING = @{ Method = 'fillTypingItems'; Rows = @('ROW_ENABLED', 'ROW_RESET') }
    SENDING = @{ Method = 'fillSendingItems'; Rows = @('ROW_DELAYED_SEND_ENABLED', 'ROW_ROUND_VIDEO_QUALITY') }
    CONNECTION = @{ Method = 'fillConnectionItems'; Rows = @('ROW_EMERGENCY_ENABLED', 'ROW_EMERGENCY_CHAT', 'ROW_ACCOUNT_EXPORT') }
}
$visibleRows = @()
foreach ($name in $expected.Keys) {
    $entry = $expected[$name]
    $body = Method $advanced $entry.Method
    Check ($dispatch -match "case ${name}:\s+$($entry.Method)\(items\);") "Section $name routes to its own content"
    Check ([regex]::Matches($root, "sectionItem\(Section\.${name},").Count -eq 1) "Section $name appears once in the menu"
    $actualRows = @([regex]::Matches($body, '\bROW_\w+\b') | ForEach-Object Value)
    Check (@(Compare-Object $entry.Rows $actualRows).Count -eq 0) "Section $name retains all expected controls"
    $visibleRows += $actualRows
}
Check ($visibleRows.Count -eq 15 -and $visibleRows.Count -eq $rows.Count -and @(Compare-Object @($rows | ForEach-Object { $_.Groups[1].Value }) $visibleRows).Count -eq 0) 'All fourteen existing controls and the new round-video switch are reachable once'
Check ((Method $advanced 'fillSendingItems').Contains('LumaRoundVideoQuality.isEnabled()') -and $click.Contains('LumaRoundVideoQuality.setEnabled(enabled);')) 'Round-video quality uses its persistent recording preference'
$typing = Method $advanced 'fillTypingItems'
foreach ($setting in @('SpeedLevel', 'BlurLevel', 'HeightLevel', 'SwipeMode')) {
    Check ($typing.Contains("LumaTextAnimation.get$setting()") -and $typing.Contains("LumaTextAnimation::set$setting")) "Typing slider $setting keeps its original preference"
}
Check ($click -match '(?s)ROW_ACCOUNT_EXPORT\).*?new LumaAccountExportActivity\(\);\s+screen\.setCurrentAccount\(currentAccount\);\s+presentFragment\(screen\);') 'Account export uses the account selected in settings'
Check ((Method $advanced 'fillPrivacyItems').Contains('ExperimentalGhostScheduledSendInfo') -and (Method $advanced 'fillPrivacyItems').Contains('ExperimentalGhostInfo')) 'Ghost limitations and server-scheduling explanation remain visible'
foreach ($warning in @('ExperimentalStarRatingInfo', 'ExperimentalAnonymousNumberInfo', 'ExperimentalProfileVerificationInfo')) {
    Check ((Method $advanced 'fillProfileItems').Contains($warning)) "Local-only explanation remains ($warning)"
}
Check ($updates -notmatch 'ROW_SOURCE|showSourceDialog|sourceLabel|R\.string\.LumaUpdate(?:AdvancedHeader|Source(?:Title|Info|Hint|Name)?)\b') 'Update source card and URL editor are absent from the user interface'
Check ($updates.Contains('UItem.asButton(ROW_REPAIR,') -and $updates.Contains('controller.setManifestUrl(null);') -and $updates.Contains('LumaUpdateRepairSourceInfo')) 'Explicit restore action repairs stale sources without exposing a URL editor'
Check ($updates.Contains('controller.checkForUpdate(true,') -and $updates.Contains('controller.setAutoCheckEnabled(enabled);') -and $updates.Contains('showCustomUpdateAppPopup')) 'Manual checks, automatic checks and update installation remain available'
Check ((Method $updater 'getManifestUrl' 'public').Contains('getString("manifest_url", null)') -and (Method $updater 'getManifestUrl' 'public').Contains('isHttps(saved) ? saved.trim() : BuildVars.LUMA_UPDATE_MANIFEST_URL')) 'Valid saved sources remain respected; empty/corrupt sources recover the built-in URL'
Check ($updater.Contains('LumaUpdateFiles') -and $updater.Contains('sha256')) 'Updater integrity validation is not removed'
foreach ($locale in @('values', 'values-ru')) {
    $xml = [xml](Source "TMessagesProj/src/main/res/$locale/strings.xml")
    $refs = @([regex]::Matches($advanced, 'R\.string\.(BlackHoleAdvanced\w+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    foreach ($name in $refs) {
        $strings = @($xml.resources.string | Where-Object name -CEQ $name)
        Check ($strings.Count -eq 1 -and ![string]::IsNullOrWhiteSpace($strings[0].'#text')) "Category label is translated ($locale / $name)"
    }
}
Write-Output 'Settings source regressions passed. Android compilation and on-device visual testing are separate checks.'
