param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/feed-chips'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Extract([string]$source,[string]$signature){
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal);if($start -lt 0){throw "Missing production method $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++){if($source[$end] -eq '{'){$depth++};if($source[$end] -eq '}'){$depth--}}
    if($depth -ne 0){throw 'Unbalanced production source'}
    return $source.Substring($start,$end-$start)
}
$screen=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/LunagramFeedActivity.java'))
$native=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/Components/Reactions/ReactionsLayoutInBubble.java'))
$cell=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/Cells/ChatMessageCell.java'))
$baseCell=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/Cells/BaseCell.java'))
$postView=Extract $screen 'private final class PostView extends LinearLayout'
foreach($removed in @('final TextView react, open','R.string.LunagramFeedReact','R.string.LunagramFeedOpenPost','LinearLayout actions')){
    if($postView.Contains($removed)){throw "Redundant post footer returned: $removed"}
}
if(!$postView.Contains('origin.setOnClickListener(v -> openPost(post))')){throw 'Original post is no longer reachable from origin'}
if(!$postView.Contains('((FeedMessageCell) message).cancelBodyLongPress();')){throw 'Rebinding must cancel body hold'}
if(!$screen.Contains('message.setDelegate(cellDelegate)')){throw 'Native message cell lost feed delegate'}
$touch=Extract $cell 'public boolean onTouchEvent(MotionEvent event)'
if($touch.IndexOf('!delegate.canPerformActions()') -lt 0 -or $touch.IndexOf('!delegate.canPerformActions()') -gt $touch.IndexOf('checkReactionsTouchEvent(event)')){throw 'Native touch gating contract changed: inspect new dispatch before updating probe'}
$probe=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'feed-chips/FeedChipTouchProbe.java.template'))
foreach($entry in @(@('/* ACTUAL_GATE */','@Override public boolean canPerformActions()'),
    @('/* ACTUAL_REPLY_GATE */','@Override public boolean canPerformReply()'),
    @('/* ACTUAL_REACTION */','@Override public void didPressReaction(ChatMessageCell cell, TLRPC.ReactionCount reaction, boolean longpress, float x, float y)'),
    @('/* ACTUAL_SEND */','private void react(MessageObject post, TLRPC.Reaction reaction)'))){
    $probe=$probe.Replace($entry[0],(Extract $screen $entry[1]))
}
$probe=$probe.Replace('/* ACTUAL_NATIVE_TOUCH */',(Extract $native 'public boolean checkTouchEvent(MotionEvent event)').Replace('org.telegram.ui.Cells.IMessageCell','IMessageCell'))
$body=Extract $screen 'private final class FeedMessageCell extends ChatMessageCell'
foreach($entry in @(@('/* ACTUAL_BODY_TOUCH */','@Override public boolean onTouchEvent(MotionEvent event)'),
    @('/* ACTUAL_BODY_CANCEL */','void cancelBodyLongPress()'),
    @('/* ACTUAL_BODY_LONG */','@Override protected boolean onLongPress()'),
    @('/* ACTUAL_BODY_DETACH */','@Override protected void onDetachedFromWindow()'))){$probe=$probe.Replace($entry[0],(Extract $body $entry[1]))}
foreach($entry in @(@('/* ACTUAL_BASE_TAP */','private final class CheckForTap implements Runnable'),
    @('/* ACTUAL_BASE_LONG */','class CheckForLongPress implements Runnable'),
    @('/* ACTUAL_BASE_START */','protected void startCheckLongPress()'),
    @('/* ACTUAL_BASE_CANCEL */','protected void cancelCheckLongPress()'))){$probe=$probe.Replace($entry[0],(Extract $baseCell $entry[1]))}
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix);$java=Join-Path $JavaHome ('bin/java'+$suffix)
function RunProbe([string]$name,[string]$source,[bool]$negative){
    $folder=Join-Path $run $name;New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $file=Join-Path $folder 'FeedChipTouchProbe.java';[IO.File]::WriteAllText($file,$source,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $folder $file
    if($LASTEXITCODE -ne 0){throw "Feed chip probe must compile: $name"}
    $result=& $java -Xmx128m -cp $folder FeedChipTouchProbe 2>&1
    if(!$negative -and $LASTEXITCODE -ne 0){throw ($result -join "`n")}
    if($negative -and $LASTEXITCODE -eq 0){throw "Executable negative control incorrectly passed: $name"}
    if(!$negative){Write-Output $result}else{Write-Output "PASS compiled negative control: $name"}
}
RunProbe 'actual' $probe $false
foreach($mutation in @(
    @('inert-default-delegate',$probe.Replace('return visible && feed != null && feed.isAvailable() && owner == getUserConfig().getClientUserId();','return false;')),
    @('hidden-feed-active',$probe.Replace('return visible && feed != null','return feed != null')),
    @('stale-feed-active',$probe.Replace('&& feed.isAvailable()','')),
    @('reply-enabled',$probe.Replace('public boolean canPerformReply() { return false; }','public boolean canPerformReply() { return true; }')),
    @('cancel-sends-reaction',$probe.Replace('event.getAction() == MotionEvent.ACTION_UP) {','(event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)) {')),
    @('paid-reaction-sent',$probe.Replace('if (reaction instanceof TLRPC.TL_reactionPaid)','if (false)')),
    @('no-first-reaction-entry',$probe.Replace('startCheckLongPress();','')),
    @('body-steals-native',$probe.Replace('if (handled || !cellDelegate.canPerformActions()','if (!cellDelegate.canPerformActions()')),
    @('body-scroll-longpress',$probe.Replace('Math.abs(event.getX() - bodyTouchX) > slop || Math.abs(event.getY() - bodyTouchY) > slop','false')),
    @('body-stale-message',$probe.Replace('|| bodyTouchPost != getMessageObject()',''))
)){
    if($mutation[1] -eq $probe){throw "Mutation did not change source: $($mutation[0])"}
    RunProbe $mutation[0] $mutation[1] $true
}
Write-Output "PASS feed footer/origin/delegate source contracts. Artifacts: $run"
Write-Output 'Scope: actual native reaction touch method and feed delegate methods on deterministic host fixtures; not Android pixels, device gestures, or live Telegram API.'
