$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Source([string]$path) { [System.IO.File]::ReadAllText((Join-Path $repo $path)) }
function Check([bool]$condition, [string]$description) {
    if (!$condition) { throw $description }
    Write-Output "PASS: $description"
}
$java = 'TMessagesProj/src/main/java/org/telegram/'
$chat = Source ($java + 'ui/ChatActivity.java')
$settings = Source ($java + 'ui/SettingsActivity.java')
$normal = Source ($java + 'ui/BlackHoleGramSettingsActivity.java')
$advanced = Source ($java + 'ui/ExperimentalFeaturesActivity.java')
$controller = Source ($java + 'messenger/MessagesController.java')
$sender = Source ($java + 'messenger/SendMessagesHelper.java')
$gifts = Source ($java + 'ui/Gifts/ProfileGiftsContainer.java')
$cell = Source ($java + 'ui/Cells/ChatMessageCell.java')
$standaloneLoader = Source 'TMessagesProj_AppStandalone/src/main/java/org/telegram/messenger/ApplicationLoaderImpl.java'
foreach ($resource in @('LumaUpdateVersion','LumaUpdateSecureInfo','LumaUpdatesTitle','LumaUpdateInstall','LumaUpdateDownloadInstall','AppUpdateRemindMeLater','LumaUpdateDownloading','OK','Cancel')) {
    Check ($standaloneLoader.Contains("org.telegram.messenger.R.string.$resource") -and $standaloneLoader -notmatch "(?<!\.)\bR\.string\.$resource\b") "Standalone updater uses the library resource namespace ($resource)"
}
Check ((Source 'gradle.properties') -match '(?m)^APP_PACKAGE=org\.luma\.liquid\r?$') 'Legacy Android package is unchanged'
Check ((Source 'gradle.properties') -match '(?m)^APP_VERSION_NAME=12\.10\.6-bhg\.66\r?$') 'Version identifies the new upstream base'
Check ((Source ($java + 'tgnet/TLRPC.java')) -match 'LAYER = 229;') 'Telegram layer 229 is present'
foreach ($locale in @('values', 'values-ru')) {
    $xml = [xml](Source "TMessagesProj/src/main/res/$locale/strings.xml")
    Check (($xml.resources.string | Where-Object name -CEQ 'AppName').'#text' -eq 'BlackHoleGram') "Displayed application name ($locale)"
    Check (@($xml.resources.string | Group-Object -CaseSensitive name | Where-Object Count -gt 1).Count -eq 0) "No duplicated string names ($locale)"
    foreach ($name in @('BlackHoleGramSettingsTitle','BlackHoleGramSettingsInfo','ExperimentalFeaturesTitle')) {
        Check (@($xml.resources.string | Where-Object name -CEQ $name).Count -eq 1) "Settings label $name ($locale)"
    }
}
Get-ChildItem (Join-Path $repo 'TMessagesProj/src/main/res') -Directory -Filter 'values*' | ForEach-Object {
    $stringsPath = Join-Path $_.FullName 'strings.xml'
    if (Test-Path $stringsPath) {
        $xml = [xml][System.IO.File]::ReadAllText($stringsPath)
        $appNames = @($xml.resources.string | Where-Object name -CEQ 'AppName')
        if ($appNames.Count -gt 0) {
            Check ($appNames.Count -eq 1 -and $appNames[0].'#text' -eq 'BlackHoleGram') "Consistent app brand ($($_.Name))"
        }
    }
}
Check ([regex]::Matches($settings, 'SettingCell\.Factory\.of\(BHG_SETTINGS_ROW,').Count -eq 1 -and [regex]::Matches($settings, 'SettingCell\.Factory\.of\(BHG_ADVANCED_ROW,').Count -eq 1) 'Exactly two custom rows in main settings'
Check ($settings.Contains('BHG_SETTINGS_ROW = 1001') -and $settings.Contains('BHG_ADVANCED_ROW = 1002') -and $settings.Contains('case 24:')) 'Custom settings do not collide with the new upstream round-video row'
Check ($settings -notmatch 'SettingCell\.Factory\.of\((25|26),') 'Updater and input rows are no longer duplicated at the top level'
Check ($normal.Contains('screen.setCurrentAccount(currentAccount);') -and $settings -match '(?s)case BHG_SETTINGS_ROW: \{.*?screen\.setCurrentAccount\(currentAccount\);' -and $settings -match '(?s)case BHG_ADVANCED_ROW: \{.*?screen\.setCurrentAccount\(currentAccount\);') 'Custom navigation preserves the originating account'
foreach ($screen in @('LiquidGlassSettingsActivity','TextAnimationSettingsActivity','LumaUpdateActivity')) {
    Check ($normal.Contains("new $screen()")) "Normal settings retains $screen"
}
foreach ($feature in @('LumaGhostMode','LumaDeletedMessages','LumaAnonymousNumber','LumaStarRating','LumaProfileVerification','LumaEmergencyMode','LumaDelayedSend','LumaTextAnimation','LumaAccountExportActivity')) {
    Check ($advanced.Contains($feature)) "Advanced settings retains $feature"
}
$options = @([regex]::Matches($chat, 'public (?:final static|static final) int (OPTION_\w+) = (\d+);'))
$read = @($options | Where-Object { $_.Groups[1].Value -eq 'OPTION_GHOST_READ' })
Check ($read.Count -eq 1 -and @($options | Where-Object { $_.Groups[2].Value -eq $read[0].Groups[2].Value }).Count -eq 1) 'Manual ghost read action has a unique menu ID'
Check ($chat.Contains('case OPTION_GHOST_READ:') -and $chat.Contains('LumaEditHistory.getVersions')) 'Manual read and local edit-history actions survive'
Check ($controller.Contains('setLumaGhostModeEnabled') -and $controller.Contains('suppressRead = dialogId > 0 && LumaGhostMode.isEnabled')) 'Presence and automatic read-receipt guards survive'
Check ([regex]::Matches($sender, 'LumaGhostMode\.getAutomaticScheduleDate').Count -ge 2 -and $sender.Contains('lumaGhostAutoScheduled')) 'Regular and forwarded ghost scheduling hooks survive'
Check ($sender.Contains('if (!isWelcomeMessageTemplate) {') -and $chat.Contains('chatMode != MODE_QUICK_REPLIES && chatMode != MODE_WELCOME_MESSAGES')) 'New welcome-message templates are not auto-scheduled or retained as deleted chats'
Check ($gifts.Contains('LumaAccountData.preferences') -and $gifts.Contains('LumaLocalPinGift') -and $gifts.Contains('ITEM_TOGGLE_ALL_GIFTS')) 'Local gift pins and hide/show-all survive'
Check ((Source ($java + 'ui/Gifts/GiftSheet.java')).Contains('item.object2')) 'Gift pin badge survives adapter rebinding'
Check ($cell.Contains('ColoredImageSpan.ALIGN_CENTER') -and $cell.Contains('saveLayerAlpha(0, 0, getMeasuredWidth(), getMeasuredHeight(), 185')) 'Aligned deleted-message marker and dimming survive'
Check ((Source ($java + 'ui/ActionBar/Theme.java')).Contains('themeInfo.assetName = "fullblack.attheme";') -and (Test-Path (Join-Path $repo 'TMessagesProj/src/main/assets/fullblack.attheme'))) 'FullBlack theme survives'
Check ((Source ($java + 'ui/Components/blur3/drawable/color/impl/BlurredBackgroundProviderImpl.java')).Contains('LumaAdaptiveGlassPalette')) 'Adaptive Liquid Glass colors survive'
Check ((Source ($java + 'messenger/voip/VoIPPreNotificationService.java')).Contains('VibrationAttributes.USAGE_RINGTONE')) 'Upstream background-call vibration fix is present'
Check ((Source 'TMessagesProj/jni/voip/webrtc/common_video/h265/h265_bitstream_parser.cc').Contains('kMaxLongTermReferencePictures')) 'Upstream H.265 bounds checks are present'
Write-Output 'Source integration checks passed. These are not Android UI or server-side presence tests.'
