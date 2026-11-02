param(
    [string]$JavaHome = 'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1',
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs/incoming-animation')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Method([string]$source,[string]$signature){
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal);if($start -lt 0){throw "Missing production method: $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++){if($source[$end] -eq '{'){$depth++};if($source[$end] -eq '}'){$depth--}}
    if($depth -ne 0){throw "Unbalanced method: $signature"};$source.Substring($start,$end-$start)
}
$queueFile=Join-Path $base 'org/telegram/messenger/LunagramIncomingAnimation.java'
$geometryFile=Join-Path $base 'org/telegram/messenger/LunagramSendEffectGeometry.java'
$helper=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/Components/LunagramIncomingMessageEffect.java'))
$helper=[regex]::Replace($helper,'(?m)^\s*(?:package|import) [^;]+;\s*','').Replace('public final class ','static final class ')
$cell=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/Cells/ChatMessageCell.java'))
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'incoming-animation/IncomingAnimationProbe.java.template')).Replace('@@HELPER@@',$helper)
foreach($pair in @(@('SET_EFFECT','public void setLunagramIncomingEffect('),@('HAS_EFFECT','public boolean hasLunagramIncomingEffect('),@('DRAW_INTERNAL','public void drawInternal(Canvas canvas)'),@('DRAW_OUTBOUND','public void drawOutboundsContent(Canvas canvas)'))){
    $template=$template.Replace('@@'+$pair[0]+'@@',(Method $cell $pair[1]).Replace('org.telegram.ui.Components.LunagramIncomingMessageEffect','LunagramIncomingMessageEffect'))
}
function Run-Probe([string]$name,[string]$source,[string]$queueSource,[string]$expected=''){
    $folder=Join-Path $run $name;New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $probe=Join-Path $folder 'IncomingAnimationProbe.java';[IO.File]::WriteAllText($probe,$source,[Text.UTF8Encoding]::new($false))
    $queue=Join-Path $folder 'LunagramIncomingAnimation.java';[IO.File]::WriteAllText($queue,$queueSource,[Text.UTF8Encoding]::new($false))
    $suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
    & (Join-Path $JavaHome ('bin/javac'+$suffix)) -J-Xmx128m -encoding UTF-8 -d $folder $queue $geometryFile $probe
    if($LASTEXITCODE -ne 0){throw "Incoming probe compilation failed: $name"}
    $result=& (Join-Path $JavaHome ('bin/java'+$suffix)) -Xmx128m -cp $folder IncomingAnimationProbe 2>&1
    $code=$LASTEXITCODE;$output=$result|Out-String
    if($expected){if($code -eq 0 -or !$output.Contains('AssertionError: '+$expected)){throw "Negative control missed intended defect: $name $output"};Write-Output "PASS negative control $name"}
    elseif($code -ne 0){throw "Incoming behavior failed: $output"}else{Write-Output $output.Trim()}
}
$queue=[IO.File]::ReadAllText($queueFile)
Run-Probe 'production' $template $queue
foreach($mutation in @(
    @('one-shot','return pending.remove(messageId) != null;','return pending.containsKey(messageId);','arrival consumed once'),
    @('ttl-boundary','age >= MAX_AGE_MS','age > MAX_AGE_MS','duplicate does not refresh TTL'),
    @('bounded-queue','pending.size() >= MAX_PENDING','false','pending queue evicts oldest')
)){
    if(!$queue.Contains($mutation[1])){throw "Missing queue mutation anchor: $($mutation[0])"}
    Run-Probe $mutation[0] $template ($queue.Replace($mutation[1],$mutation[2])) $mutation[3]
}
foreach($mutation in @(
    @('whole-text-reveal','Math.min(1, progress / .90f)','1f','letters use grapheme-safe shared reveal'),
    @('premature-plane-body','if (arrival == 0) return false;','if (false) return false;','plane body waits until arrival'),
    @('missing-canvas-restore','canvas.restoreToCount(save);','/* missing canvas restore */','caller can restore all helper layers')
)){
    if(!$template.Contains($mutation[1])){throw "Missing drawing mutation anchor: $($mutation[0])"}
    # The outer caller still restores helper layers, so target the wrapper assertion for a missing restore.
    $expected=if($mutation[0] -eq 'missing-canvas-restore'){'cell wrapper balances nested drawing'}else{$mutation[3]}
    Run-Probe $mutation[0] ($template.Replace($mutation[1],$mutation[2])) $queue $expected
}
$chat=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/ChatActivity.java'))
$animator=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/recyclerview/ChatListItemAnimator.java'))
if([regex]::Matches($chat,'prepareLunagramIncomingAnimations\(arr\);').Count -ne 1 -or !$chat.Contains("prepareLunagramIncomingAnimations(arr);"+"`r`n"+'                processNewMessages(arr);') -and !$chat.Contains("prepareLunagramIncomingAnimations(arr);"+"`n"+'                processNewMessages(arr);')){throw 'Incoming registration must occur only at the live-arrival path before processNewMessages'}
$newMessageStart=$chat.LastIndexOf('id == NotificationCenter.didReceiveNewMessages',$chat.IndexOf('prepareLunagramIncomingAnimations(arr);'))
if($newMessageStart -lt 0){throw 'Registration must live under didReceiveNewMessages'}
$prepare=Method $chat 'private void prepareLunagramIncomingAnimations('
foreach($token in @('canAnimateLunagramIncomingMessages()','!forwardEndReached[0]','findFirstVisibleItemPosition() != 0','RecyclerView.SCROLL_STATE_IDLE','!message.isOutOwner()','!message.isSponsored()','message.getGroupId() == 0','messagesDict[0].get(message.getId()) == null')){if(!$prepare.Contains($token)){throw "Live-arrival guard missing: $token"}}
foreach($signature in @('public void onPause()','public void onFragmentDestroy()')){if(!(Method $chat $signature).Contains('cancelLunagramIncomingAnimations()')){throw "Lifecycle cancel missing: $signature"}}
foreach($signature in @('public void animateAddImpl(final RecyclerView.ViewHolder holder, int addedItemsHeight)','public void animateAddImpl(RecyclerView.ViewHolder holder)')){if(!(Method $animator $signature).Contains('if (animateLunagramIncoming(')){throw "Incoming animation missing add overload: $signature"}}
$animate=Method $animator 'private boolean animateLunagramIncoming('
foreach($token in @('lunagramIncoming.consume(','LunagramExperienceSettings.incomingEffect(account)','!session.isCurrent()','cell.getMessageObject() != message','!cell.hasLunagramIncomingEffect(effect)','!cell.isAttachedToWindow()','lunagramIncomingHolders.size() >= 8','cell.setLunagramIncomingEffect(null)','mAddAnimations.remove(holder)','dispatchAddFinished(holder)')){if(!$animate.Contains($token)){throw "Animator lifecycle/ownership contract missing: $token"}}
$policy=Method $animator 'private boolean canAnimateLunagramIncoming()'
foreach($token in @('SharedConfig.animationsEnabled()','AndroidUtilities.getAnimatorDurationScale() > 0','!LiteMode.isPowerSaverApplied()','SharedConfig.PERFORMANCE_CLASS_LOW')){if(!$policy.Contains($token)){throw "Motion accessibility/budget guard missing: $token"}}
if(!(Method $animator 'public void endAnimations()').Contains('lunagramIncoming.clear()')){throw 'Global animator end must clear pending arrivals'}
if(!(Method $cell 'protected void onDetachedFromWindow()').Contains('setLunagramIncomingEffect(null)')){throw 'Detached cells must discard effect state'}
if([regex]::Matches($cell,'lunagramIncomingEffect\.clipText\(canvas, block\.textLayout\)').Count -lt 2){throw 'Text and animated-emoji draw paths must share letter clipping'}
Write-Output 'PASS incoming source integration guards: live path only, history exclusion, both add paths, session/identity/visibility, lifecycle cleanup, motion policies'
Write-Output "Results: $run"
