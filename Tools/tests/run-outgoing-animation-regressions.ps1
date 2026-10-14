param(
    [string]$JavaHome='D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/outgoing-animation')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui'
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Method([string]$source,[string]$signature){
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal);if($start -lt 0){throw "Missing production lifecycle method: $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++){if($source[$end] -eq '{'){$depth++};if($source[$end] -eq '}'){$depth--}}
    if($depth -ne 0){throw "Unbalanced method $signature"};$source.Substring($start,$end-$start)
}
$text=[IO.File]::ReadAllText((Join-Path $base 'TextMessageEnterTransition.java'))
$voice=[IO.File]::ReadAllText((Join-Path $base 'VoiceMessageEnterTransition.java'))
$cell=[IO.File]::ReadAllText((Join-Path $base 'Cells/ChatMessageCell.java'))
$container=[IO.File]::ReadAllText((Join-Path $base 'MessageEnterTransitionContainer.java'))
$container=[regex]::Replace($container,'(?m)^\s*(?:package|import) [^;]+;\s*','')
$container=$container.Replace('@SuppressLint("ViewConstructor")','').Replace('public class MessageEnterTransitionContainer','static class MessageEnterTransitionContainer')
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'outgoing-animation/OutgoingLifecycleProbe.java.template')).Replace('@@CONTAINER@@',$container)
foreach($pair in @(@('CELL_SET','public void setEnterTransitionInProgress('),@('CELL_BEGIN','public void beginEnterTransition('),@('CELL_OWNS','public boolean ownsEnterTransition('),@('CELL_FINISH','public void finishEnterTransition('))){$template=$template.Replace('@@'+$pair[0]+'@@',(Method $cell $pair[1]))}
foreach($pair in @(@('TEXT',$text),@('VOICE',$voice))){
    foreach($method in @(@('START','public void start()'),@('VALID','private boolean isValid()'),@('FINISH','private void finish()'),@('CANCEL','public void cancel()'))){$template=$template.Replace('@@'+$pair[0]+'_'+$method[0]+'@@',(Method $pair[1] $method[1]))}
    $update=Method $pair[1] 'animator.addUpdateListener(valueAnimator ->'
    $body=$update.Substring($update.IndexOf('{')+1);$body=$body.Substring(0,$body.Length-1)
    $template=$template.Replace('@@'+$pair[0]+'_UPDATE@@',$body)
    $draw=Method $pair[1] 'public void onDraw(Canvas canvas)'
    $valid=$draw.IndexOf('if (!isValid())');if($valid -lt 0){throw "Missing draw cancellation guard: $($pair[0])"}
    $guard=Method $draw.Substring($valid) 'if (!isValid())'
    $template=$template.Replace('@@'+$pair[0]+'_DRAW_GUARD@@',$guard)
}
$template=$template.Replace('@@VOICE_RADIUS_GUARD@@',(Method $voice 'if (toRadius <= 0)'))
function Run-Probe([string]$name,[string]$source,[string]$expected=''){
    $folder=Join-Path $run $name;New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $path=Join-Path $folder 'OutgoingLifecycleProbe.java';[IO.File]::WriteAllText($path,$source,[Text.UTF8Encoding]::new($false))
    $suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
    & (Join-Path $JavaHome ('bin/javac'+$suffix)) -J-Xmx128m -encoding UTF-8 -d $folder $path
    if($LASTEXITCODE -ne 0){throw "Outgoing lifecycle compilation failed: $name"}
    $result=& (Join-Path $JavaHome ('bin/java'+$suffix)) -Xmx128m -cp $folder OutgoingLifecycleProbe 2>&1
    $code=$LASTEXITCODE;$output=$result|Out-String
    if($expected){if($code -eq 0 -or !$output.Contains('AssertionError: '+$expected)){throw "Negative control missed intended defect $name $output"};Write-Output "PASS negative control $name"}
    elseif($code -ne 0){throw "Outgoing lifecycle assertions failed: $output"}else{Write-Output $output.Trim()}
}
Run-Probe 'production' $template
foreach($mutation in @(
    @('stale-cell-owner','if (ownsEnterTransition(owner)) setEnterTransitionInProgress(false);','setEnterTransitionInProgress(false);','old transition cannot clear new cell owner'),
    @('older-composer-write','if (container.isTextTransitionOwner(this)) chatActivityEnterView.getEditField().setAlpha(progress);','chatActivityEnterView.getEditField().setAlpha(progress);','older update cannot change newer composer alpha'),
    @('older-composer-finish','if (container.isTextTransitionOwner(this)) {','if (true) {','older completion cannot reset newer composer or reply UI'),
    @('older-recording-circle','recordCircle != null && container.isVoiceTransitionOwner(this)','recordCircle != null','old voice finish cannot restore newer recording circle'),
    @('invalid-text-cell','messageView.getMessageObject() == currentMessageObject && messageView.isAttachedToWindow()','messageView.isAttachedToWindow()','text stale owner/session/lifecycle/motion is invalid'),
    @('changed-stable-id','&& currentMessageObject.stableId == messageId','','text stale owner/session/lifecycle/motion is invalid'),
    @('zero-voice-radius','if (toRadius <= 0)','if (toRadius < 0)','voice division finite after radius guard')
)){
    if(!$template.Contains($mutation[1])){throw "Missing outgoing negative-control anchor $($mutation[0])"}
    Run-Probe $mutation[0] ($template.Replace($mutation[1],$mutation[2])) $mutation[3]
}
$chat=[IO.File]::ReadAllText((Join-Path $base 'ChatActivity.java'))
foreach($signature in @('public void onPause()','public void onFragmentDestroy()')){if(!(Method $chat $signature).Contains('messageEnterTransitionContainer.cancelAll()')){throw "Outgoing cancellation missing: $signature"}}
foreach($source in @($text,$voice)){
    $constructor=Method $source $(if($source -eq $text){'public TextMessageEnterTransition('}else{'public VoiceMessageEnterTransition('})
    if(!$constructor.Contains('messageView.beginEnterTransition(this);') -or $constructor.Contains('messageView.setEnterTransitionInProgress(true)')){throw 'Constructor must bind explicit cell ownership'}
    if(!(Method $source 'public void onAnimationEnd(Animator animation)').Contains('finish();')){throw 'Actual end callback must use idempotent cleanup'}
}
if(!(Method $cell 'protected void onDetachedFromWindow()').Contains('setEnterTransitionInProgress(false)')){throw 'Detached cells must revoke old outgoing owner'}
if(!$cell.Contains('if (currentMessageObject != messageObject) setEnterTransitionInProgress(false);')){throw 'Rebound cells must revoke old outgoing state'}
# This legacy draw method contains commented-out braces; inspect its suffix rather than
# treating comment text as Java block delimiters in the small extraction helper.
$voiceDraw=$voice.Substring($voice.IndexOf('public void onDraw(Canvas canvas)'))
$radiusGuard=$voiceDraw.IndexOf('if (toRadius <= 0)');$radiusDivision=$voiceDraw.IndexOf('radius / toRadius')
if($radiusGuard -lt 0 -or $radiusDivision -lt 0 -or $radiusGuard -gt $radiusDivision){throw 'Voice radius guard must precede division'}
$sendButton=Method $text 'if (container.isTextTransitionOwner(this) && enterView.getSendButton().getVisibility() == View.VISIBLE && sendProgress < 1f)'
if([regex]::Matches($sendButton,'canvas\.save(?:LayerAlpha)?\(').Count -ne 2 -or [regex]::Matches($sendButton,'canvas\.restore\(').Count -ne 2){throw 'Outgoing send-button drawing must have exactly two matching saves/restores'}
Write-Output 'PASS outgoing lifecycle source integration contracts: explicit constructor ownership, end callbacks, pause/destroy/detach and cell rebind cleanup'
Write-Output "Results: $run"
