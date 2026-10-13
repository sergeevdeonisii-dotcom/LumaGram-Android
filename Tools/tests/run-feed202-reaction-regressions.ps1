param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/feed202-reactions'))
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
$feed=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/LunagramFeedActivity.java'))
$backup=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/Components/BackupImageView.java'))
$native=(@('protected void onDetachedFromWindow()','protected void onAttachedToWindow()','protected void onDraw(Canvas canvas)','public void setAnimatedEmojiDrawable(AnimatedEmojiDrawable animatedEmojiDrawable)') | ForEach-Object{Extract $backup $_}) -join "`n"
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'feed202/FeedReactionPickerProbe.java.template'))
$grid=(Extract $feed 'private final class ReactionGrid extends RecyclerListView').Replace('private final int count;','private final int count; final ArrayList<View> holders = new ArrayList<>();')
$template=$template.Replace('/* NATIVE_BACKUP_METHODS */',$native).Replace('/* ACTUAL_GRID */',$grid)
foreach($entry in @(@('/* ACTUAL_REACT */','private void react(MessageObject post, TLRPC.Reaction reaction)'),
    @('/* ACTUAL_CHOOSE */','private void chooseReaction(MessageObject post)'),@('/* ACTUAL_OPTION */','private final class ReactionOption extends FrameLayout'),
    @('/* ACTUAL_PAUSE */','@Override public void onPause()'),@('/* ACTUAL_DESTROY */','@Override public void onFragmentDestroy()'))){$template=$template.Replace($entry[0],(Extract $feed $entry[1]))}
$javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
function RunProbe([string]$folder,[string]$source,[bool]$negative){
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $file=Join-Path $folder 'FeedReactionPickerProbe.java';[IO.File]::WriteAllText($file,$source,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $folder $file
    if($LASTEXITCODE -ne 0){throw 'Actual picker extraction/negative control must compile'}
    $result=& $java -Xmx128m -cp $folder FeedReactionPickerProbe 2>&1
    if(!$negative -and $LASTEXITCODE -ne 0){throw ($result -join "`n")}
    if($negative -and $LASTEXITCODE -eq 0){throw 'Executable negative control incorrectly passed'}
    if(!$negative){Write-Output $result}
}
RunProbe $run $template $false
foreach($mutation in @(@('wrong-account',$template.Replace('AnimatedEmojiDrawable.make(currentAccount,','AnimatedEmojiDrawable.make(0,')),
    @('empty-custom-renderer',$template.Replace('imageReceiver.draw(canvas);','')),
    @('no-detach-release',$template.Replace('animatedEmojiDrawable.removeView(this);','animatedEmojiDrawable.getImageReceiver();')),
    @('tall-list',$template.Replace('rows * slotSize','count * slotSize')),
    @('rounding-target',$template.Replace('LayoutHelper.createFrame(48, 48, Gravity.CENTER)','LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 4, 4, 4, 4)')),
    @('large-font-glyph',$template.Replace('emoji.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);','emoji.setTextSize(28);')),
    @('stale-owner',$template.Replace('if (!visible || owner != getUserConfig().getClientUserId()) return;','if (!visible) return;')))){
    if($mutation[1] -eq $template){throw ('Negative control did not mutate: '+$mutation[0])}
    RunProbe (Join-Path $run $mutation[0]) $mutation[1] $true
    Write-Output ('PASS executable negative control: '+$mutation[0])
}
Write-Output 'Scope: actual picker and native emoji draw/attach/detach methods on modeled host; no APK, device, server, or pixel claims.'
