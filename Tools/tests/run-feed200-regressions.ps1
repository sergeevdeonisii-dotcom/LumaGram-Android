param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/feed200'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output=[IO.Path]::GetFullPath($OutputRoot)
$run=Join-Path $output ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
& (Join-Path $JavaHome ('bin/javac'+$suffix)) -J-Xmx128m -encoding UTF-8 -d $run (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeedMerger.java') (Join-Path $PSScriptRoot 'feed200/LunagramFeedMergerTest.java')
if($LASTEXITCODE -ne 0){throw 'Feed merger compilation failed'}
& (Join-Path $JavaHome ('bin/java'+$suffix)) -Xmx128m -cp $run LunagramFeedMergerTest
if($LASTEXITCODE -ne 0){throw 'Feed merger behavior failed'}
& (Join-Path $JavaHome ('bin/javac'+$suffix)) -J-Xmx128m -encoding UTF-8 -d $run (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSendEffectGeometry.java') (Join-Path $PSScriptRoot 'feed200/LunagramSendEffectGeometryTest.java')
if($LASTEXITCODE -ne 0){throw 'Send effect geometry compilation failed'}
& (Join-Path $JavaHome ('bin/java'+$suffix)) -Xmx128m -cp $run LunagramSendEffectGeometryTest
if($LASTEXITCODE -ne 0){throw 'Send effect geometry behavior failed'}
$transition=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/TextMessageEnterTransition.java'))
foreach($required in @('currentAccount = chatActivity.getCurrentAccount()', 'LunagramExperienceSettings.sendEffect(currentAccount)', 'SharedConfig.animationsEnabled()', 'ValueAnimator.areAnimatorsEnabled()', 'LiteMode.isPowerSaverApplied()', 'canvas.restoreToCount(save)', 'ReplacementSpan.class', 'target.getSelectionPath(', 'clipRevealedText(canvas, rtlLayout', 'drawFlightPlane(canvas, p)')) {
    if(!$transition.Contains($required)){throw "Send transition safety contract missing: $required"}
}
$controller=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeedController.java'))
$screen=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramFeedActivity.java'))
$sources=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramFeedSourcesActivity.java'))
$nav=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java'))
foreach($ui in @($screen,$sources)) {
    if($ui.Contains(': chat.title') -or !$ui.Contains('LunagramStreamerMode.displayName(currentAccount, chat)') -or !$ui.Contains('LunagramStreamerMode.hiddenName(true)')) {throw 'Feed title or fallback ID bypasses streamer privacy mask'}
}
foreach($forbidden in @('markDialogAsRead(', 'markMessageContentAsRead(', 'readHistory(', 'TL_messages_readHistory', 'TL_channels_readHistory', 'incrementViews', 'sendPaidReaction(', 'markMentionsAsRead(')) {
    if(($controller+$screen+$sources).Contains($forbidden)){throw "Feed preview performs prohibited side effect: $forbidden"}
}
foreach($required in @('LumaAccountData.preferences(account)', 'owner == UserConfig.getInstance(account).getClientUserId()', 'token == generation', 'cancelRequest(request, true)', 'TL_messages_getHistory', 'req.offset_id = source.offset', 'TL_messages_sendReaction', 'result.done(false)', 'reaction instanceof TLRPC.TL_reactionPaid', 'NotificationCenter.messagesDeleted', 'NotificationCenter.replaceMessagesObjects')) {
    if(!$controller.Contains($required)){throw "Feed lost production contract: $required"}
}
foreach($required in @('args.putBoolean("allowSwitchAccount", false)', 'args.putBoolean("checkCanWrite", false)', 'DIALOGS_TYPE_CHANNELS_ONLY', 'owner != getUserConfig().getClientUserId()', 'saveSelection(access, checked)')) {
    if(!$sources.Contains($required)){throw "Feed source picker lost contract: $required"}
}
foreach($required in @('TABS_COUNT = 5', 'POSITION_FEED = 1', 'INDEX_FEED = 1', 'new GlassTabView[6]', 'new LunagramFeedActivity(currentAccount)', 'index > INDEX_SETTINGS')) {
    if(!$nav.Contains($required)){throw "Feed tab mapping broken: $required"}
}
$en=[xml][IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/res/values/strings.xml'))
$ru=[xml][IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/res/values-ru/strings.xml'))
$enNames=@($en.resources.string | Where-Object {$_.name -match '^LunagramFeed'} | ForEach-Object name | Sort-Object)
$ruNames=@($ru.resources.string | Where-Object {$_.name -match '^LunagramFeed'} | ForEach-Object name | Sort-Object)
if(Compare-Object $enNames $ruNames){throw 'Feed locales differ'}
foreach($match in [regex]::Matches($screen+$sources+$nav,'R\.string\.(LunagramFeed\w*)')) {
    if($enNames -notcontains $match.Groups[1].Value){throw "Missing feed resource: $($match.Groups[1].Value)"}
}
Write-Output 'PASS feed integration contracts: account identity, cancellation, read/paid boundaries, navigation, EN/RU resources'
& (Join-Path $PSScriptRoot 'run-lunagram20-localization-regressions.ps1') -JavaHome $JavaHome -OutputRoot (Join-Path $OutputRoot 'localization')
