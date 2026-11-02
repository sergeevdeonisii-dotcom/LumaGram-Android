param(
    [string]$JavaHome=$(if($env:JAVA_HOME){$env:JAVA_HOME}else{'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1'}),
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/media-tools')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix);$java=Join-Path $JavaHome ('bin/java'+$suffix)
$policy=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramMediaToolsPolicy.java'
& $javac -J-Xmx128m -encoding UTF-8 -d $run $policy (Join-Path $PSScriptRoot 'media-tools/MediaToolsPolicyTest.java')
if($LASTEXITCODE -ne 0){throw 'Media policy compile failed'}
& $java -Xmx128m -cp $run MediaToolsPolicyTest $run
if($LASTEXITCODE -ne 0){throw 'Media policy failed'}
$names=@()
foreach($variant in @('values','values-ru')){
    [xml]$xml=Get-Content -LiteralPath (Join-Path $repo "TMessagesProj/src/main/res/$variant/lunagram_media_tools.xml") -Raw
    $current=@($xml.resources.string | ForEach-Object name | Sort-Object)
    if($names.Count -and (Compare-Object $names $current)){throw 'Media EN/RU keys differ'}
    if(($current | Select-Object -Unique).Count -ne $current.Count){throw 'Duplicate media keys'}
    $names=$current
}
$controller=Get-Content -LiteralPath (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramMediaTools.java') -Raw
$player=Get-Content -LiteralPath (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java') -Raw
function Extract-Block([string]$source,[string]$signature){
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing actual media method: $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++){
        if($source[$end] -eq '{'){$depth++};if($source[$end] -eq '}'){$depth--}
    }
    if($depth -ne 0){throw "Unbalanced media method: $signature"}
    $source.Substring($start,$end-$start)
}
$methods=(@('public static boolean eligible(', 'public static boolean timedMedia(',
    'public static final class Session {', 'public static final class Job {',
    'private static ArrayList<LunagramMediaToolsPolicy.Bookmark> allBookmarks(',
    'public static synchronized ArrayList<Long> bookmarks(', 'private static void saveBookmarks(',
    'public static synchronized void addBookmark(', 'public static synchronized void removeBookmark(',
    'public static boolean seekBookmark(') | ForEach-Object {Extract-Block $controller $_}) -join "`n"
$start=$controller.IndexOf('private static Session playbackSession;',[StringComparison]::Ordinal)
$playback=$controller.Substring($start,$controller.LastIndexOf('}')-$start)
$fileLoader=Get-Content -LiteralPath (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java') -Raw
$busy=Extract-Block $fileLoader 'public static boolean isFileBusyForLunagramTools('
$template=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'media-tools/MediaToolsRuntimeProbe.java.template') -Raw
$probe=Join-Path $run 'MediaToolsRuntimeProbe.java'
$catalog=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeatureCatalog.java'
function Run-Probe([string]$text,[string]$negative=''){
    [IO.File]::WriteAllText($probe,$text,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $run $policy $catalog $probe
    if($LASTEXITCODE -ne 0){throw 'Extracted media runtime failed to compile'}
    $result=& $java -Xmx128m -cp $run MediaToolsRuntimeProbe $run 2>&1
    if($negative){
        if($LASTEXITCODE -eq 0 -or !($result|Out-String).Contains($negative)){throw "Media negative missed expected assertion: $negative / $result"}
        Write-Output "PASS negative: $negative"
    }else{if($LASTEXITCODE -ne 0){throw "Extracted media runtime failed: $result"};Write-Output $result}
}
$full=$template.Replace('@@METHODS@@',$methods).Replace('@@PLAYBACK@@',$playback).Replace('@@BUSY@@',$busy)
Run-Probe $full
foreach($mutation in @(
    @('UserConfig.selectedAccount == account','true','selected account rejects'),
    @('LunagramSubscriptionClient.accountEpoch(account) == epoch','true','epoch rejects'),
    @('session.require(true);','session.require(false);','cannot create after expiry'),
    @('if (!session.paid() || !sameMessage(session.message, message)','if (!sameMessage(session.message, message)','expired loop stops before effect'),
    @('if (playbackSession != captured) return;','// removed capture guard','late cancelled callback no effect'),
    @('if (sameMessage(effectMessage, message)) return true;','// removed reentrant guard','reentrant player never advances')
)){
    if(!$full.Contains($mutation[0])){throw "Missing media negative mutation: $($mutation[0])"}
    Run-Probe $full.Replace($mutation[0],$mutation[1]) $mutation[2]
}
Run-Probe $full
foreach($guard in @('UserConfig.selectedAccount == account','LunagramSubscriptionClient.accountEpoch(account) == epoch',
    'message.messageOwner.noforwards','media.ttl_seconds == 0','DialogObject.isEncryptedDialog(message.getDialogId())',
    'BlackHolePrivateData.write(session.account, session.owner, BOOKMARK_KEY','getFileLoaderQueue().postRunnable',
    'FileLoader.isFileBusyForLunagramTools(target)','if (session.message.isOut()',
    'context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)',
    'session.require(false);','if (cancelled()) throw','job.check(session);')){
    if(!$controller.Contains($guard)){throw "Missing media access/file guard: $guard"}
}
foreach($guard in @('LunagramMediaTools.onPlayback(currentPlayingMessageObject, progress, duration, false)',
    'LunagramMediaTools.onPlayback(messageObject, videoPlayer.getDuration(), videoPlayer.getDuration(), true)',
    'LunagramMediaTools.onPlayback(messageObject, audioPlayer.getDuration(), audioPlayer.getDuration(), true)',
    'LunagramMediaTools.resetPlayback();')){
    if(!$player.Contains($guard)){throw "Missing player hook: $guard"}
}
if($controller -match 'AlarmManager|startForegroundService|sendRequest\(|\.playMessage\(|\.startActivity\('){throw 'Media tools must not start playback/services/network'}
Write-Output "PASS source integration contracts and $($names.Count) matching EN/RU media keys. No runtime Android/device claim."
Write-Output "Artifacts: $run"
