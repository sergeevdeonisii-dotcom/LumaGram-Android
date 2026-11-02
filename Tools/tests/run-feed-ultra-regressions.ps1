param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/feed-ultra'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$controller=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/messenger/LunagramFeedController.java'))
$nested=[regex]::Replace($controller,'(?m)^package .*;\r?\n|^import .*;\r?\n','').Replace('public final class LunagramFeedController','static final class LunagramFeedController')
$probe=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'feed-ultra/FeedUltraProbe.java.template')).Replace('/* ACTUAL_CONTROLLER */',$nested)
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix);$java=Join-Path $JavaHome ('bin/java'+$suffix)
function RunProbe([string]$name,[string]$source,[bool]$negative){
    $folder=Join-Path $run $name;New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $file=Join-Path $folder 'FeedUltraProbe.java';[IO.File]::WriteAllText($file,$source,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $folder $file (Join-Path $base 'org/telegram/messenger/LunagramFeedMerger.java')
    if($LASTEXITCODE -ne 0){throw "Feed authority probe must compile: $name"}
    $result=& $java -Xmx128m -cp $folder FeedUltraProbe 2>&1
    if(!$negative -and $LASTEXITCODE -ne 0){throw ($result -join "`n")}
    if($negative -and $LASTEXITCODE -eq 0){throw "Executable negative control incorrectly passed: $name"}
    if(!$negative){Write-Output $result}else{Write-Output "PASS compiled negative control: $name"}
}
RunProbe 'actual' $probe $false
foreach($mutation in @(
    @('no-entitlement',$probe.Replace('LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.CHANNEL_FEED)','true')),
    @('no-epoch',$probe.Replace('epoch == LunagramSubscriptionClient.accountEpoch(account)','true')),
    @('no-owner',$probe.Replace('owner == UserConfig.getInstance(account).getClientUserId()','true')),
    @('no-selected-account',$probe.Replace('account == UserConfig.selectedAccount','true')),
    @('no-generation',$probe.Replace('token == generation','true')),
    @('late-callback',$probe.Replace('if (!valid(token)) return;','')),
    @('no-live-clear',$probe.Replace('posts.clear();','')),
    @('no-cancellation',$probe.Replace('ConnectionsManager.getInstance(account).cancelRequest(request, true);','{}')),
    @('source-bypass',$probe.Replace('if (access == null || !access.isAvailable()) return false;','')),
    @('post-account-bypass',$probe.Replace('post.currentAccount != account','false')),
    @('clone-instead-of-canonical',$probe.Replace('final MessageObject post = findPost(display);','final MessageObject post = display;')),
    @('identity-only-reload',$probe.Replace('posts.indexOf(findPost(post))','posts.indexOf(post)')),
    @('server-update-double-toggle',$probe.Replace('MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);','post.selectReaction(chosen, false, false); MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);'))
)){
    if($mutation[1] -eq $probe){throw "Mutation did not change source: $($mutation[0])"}
    RunProbe $mutation[0] $mutation[1] $true
}
$screen=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/LunagramFeedActivity.java'))
$sources=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/LunagramFeedSourcesActivity.java'))
foreach($required in @('if (!feed.checkAccess()) return;', 'if (!feed.isCurrent())', 'R.string.LunagramFeedUltraRequired','reactionDialog.dismiss(); reactionDialog = null;', 'screen.setCurrentAccount(currentAccount)', 'if (!feed.checkAccess() || position >= feed.posts().size())')) {
    if(!$screen.Contains($required)){throw "Feed locked UI contract missing: $required"}
}
foreach($required in @('new LunagramFeedController.AccessSession(currentAccount)','if (!LunagramFeedController.saveSelection(access, checked)) return;',
    'fragment.getCurrentAccount() != currentAccount || !access.isAvailable()', 'if (!access.isAvailable()) return;',
    'done.setVisibility(available ? View.VISIBLE : View.GONE)', 'sourcePicker.finishFragment()', 'NotificationCenter.lunagramSubscriptionChanged',
    'getNotificationCenter().removeObserver(this, NotificationCenter.lunagramSubscriptionChanged)', 'R.string.LunagramFeedUltraRequired')) {
    if(!$sources.Contains($required)){throw "Feed sources authority contract missing: $required"}
}
Write-Output "PASS feed UI integration source contracts. Artifacts: $run"
Write-Output 'Scope: actual controller + merger, deterministic owner/epoch/authority and async transport fixtures; UI source contracts only. No APK, device, live server, or pixel claims.'
