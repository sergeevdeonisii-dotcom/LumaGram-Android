param([string]$JavaHome = $(if($env:JAVA_HOME){$env:JAVA_HOME}else{'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1'}),
    [string]$JsonJar='D:/CodexBuildCache/gradle-user-caches-20261006/modules-2/files-2.1/org.json/json/20240303/ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a/json-20240303.jar',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs'))
$ErrorActionPreference = 'Stop'
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskRun = Join-Path $OutputRoot ('link-tools-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskSuffix = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$taskNames=@('LunagramLinkTools','LunagramStickerList','LunagramFeedFilter','LunagramSpeechChunks','LunagramTextTools',
    'LunagramMessageAccess','LunagramLinkSettings','LunagramMediaAccess','LunagramFeatureCatalog')
$taskSources=@($taskNames | ForEach-Object {Join-Path $taskRepo "TMessagesProj/src/main/java/org/telegram/messenger/$_.java"})
$taskSources+=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'link-tools') -File -Filter '*.java' | ForEach-Object FullName)
if(!(Test-Path -LiteralPath $JsonJar -PathType Leaf)){throw 'Cached real JSON parser required; no network fallback.'}
& (Join-Path $JavaHome ('bin/javac' + $taskSuffix)) -J-Xmx128m -encoding UTF-8 -cp $JsonJar -d $taskRun @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Link tools compilation failed' }
foreach($test in @('LinkToolsTest','LocalToolsTest','org.telegram.messenger.AccessTest')){
    & (Join-Path $JavaHome ('bin/java' + $taskSuffix)) -Xmx128m -cp ($taskRun+[IO.Path]::PathSeparator+$JsonJar) $test
    if ($LASTEXITCODE -ne 0) { throw "Link tools regression failed: $test" }
}
function Extract-Method([string]$source,[string]$signature){
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal);if($start -lt 0){throw "Missing method: $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++){if($source[$end] -eq '{'){$depth++};if($source[$end] -eq '}'){$depth--}}
    if($depth -ne 0){throw 'Unbalanced method'};$source.Substring($start,$end-$start)
}
function Extract-Condition([string]$source,[string]$anchor){
    $at=$source.IndexOf($anchor,[StringComparison]::Ordinal);if($at -lt 0){throw "Missing guard anchor: $anchor"}
    $start=$source.IndexOf('if (',$at,[StringComparison]::Ordinal);$open=$source.IndexOf('(',$start);$depth=1
    for($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++){if($source[$end] -eq '('){$depth++};if($source[$end] -eq ')'){$depth--}}
    if($depth -ne 0){throw 'Unbalanced guard'};$source.Substring($open+1,$end-$open-2)
}
$chat=Get-Content -LiteralPath (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java') -Raw
$search=Extract-Method $chat 'public void searchLinks(final CharSequence charSequence, final boolean force)'
$worker=Extract-Condition $search 'Utilities.searchQueue.postRunnable(() -> {'
$dispatch=Extract-Condition $search 'synchronized (org.telegram.messenger.LunagramLinkSettings.class)'
$callback=Extract-Condition $search 'requestLinkPreviewCached(req,'
$links=Get-Content -LiteralPath (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/ui/Components/LunagramLinkToolsSheet.java') -Raw
$speech=Get-Content -LiteralPath (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/ui/Components/LunagramSpeechSheet.java') -Raw
$probe=(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'link-tools/CallbackProbe.java.template') -Raw).
    Replace('@@WORKER@@',$worker).Replace('@@DISPATCH@@',$dispatch).Replace('@@CALLBACK@@',$callback).
    Replace('@@LINK_VALID@@',(Extract-Method $links 'private boolean valid()')).
    Replace('@@SPEECH_VALID@@',(Extract-Method $speech 'private boolean valid()')).
    Replace('@@SPEECH_CLOSE@@',(Extract-Method $speech 'public void close()'))
$probePath=Join-Path $taskRun 'CallbackProbe.java'
[IO.File]::WriteAllText($probePath,$probe,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome ('bin/javac'+$taskSuffix)) -J-Xmx128m -encoding UTF-8 -cp ($taskRun+[IO.Path]::PathSeparator+$JsonJar) -d $taskRun $probePath
if($LASTEXITCODE -ne 0){throw 'Actual callback predicates did not compile'}
& (Join-Path $JavaHome ('bin/java'+$taskSuffix)) -Xmx128m -cp ($taskRun+[IO.Path]::PathSeparator+$JsonJar) org.telegram.messenger.CallbackProbe
if($LASTEXITCODE -ne 0){throw 'Actual callback regression failed'}
foreach($signature in @('public void onFragmentDestroy()', 'public void onPause()')){
    $method=Extract-Method $chat $signature
    if(!$method.Contains('lunagramSpeech.close();')){throw "Speech lifecycle shutdown absent: $signature"}
}
foreach($guard in @('host.showDialog(dialog, d -> close()) == null','if (!valid()) { close(); return; }',
    'if (!voice.isNetworkConnectionRequired()', 'previous.shutdown();','chunks.clear();')){
    if(!$speech.Contains($guard)){throw "Speech guard missing: $guard"}
}
if(!$search.Contains('if (SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter) { clearLunagramLinkPreview(); return; }')){throw 'Initial preview lock guard missing'}
if(!($links.Contains('if (valid()) AndroidUtilities.addToClipboard(result.url);') -and $links.Contains('if (valid()) AndroidUtilities.addToClipboard(text);'))){throw 'Explicit link/citation Copy guard missing'}
Write-Output 'PASS native callback/lifecycle source wiring'
$mutations=@(
    @{Name='message-lock';Class='LunagramMessageAccess';From='|| SharedConfig.appLocked';To='|| false';Test='org.telegram.messenger.AccessTest';Failure='Message guard 4'},
    @{Name='message-ttl';Class='LunagramMessageAccess';From='|| message.messageOwner.ttl > 0';To='|| false';Test='org.telegram.messenger.AccessTest';Failure='Message guard 14'},
    @{Name='message-forward';Class='LunagramMessageAccess';From='|| !message.canForwardMessage()';To='|| false';Test='org.telegram.messenger.AccessTest';Failure='Message guard 10'},
    @{Name='preference-owner';Class='LunagramLinkSettings';From='BlackHolePrivateData.write(account, value.owner, KEY, data.toString());';To='BlackHolePrivateData.write(account, KEY, data.toString());';Test='org.telegram.messenger.AccessTest';Failure='Old preference never written under replacement owner'},
    @{Name='preview-lock';Class='CallbackProbe';From='|| SharedConfig.appLocked';To='|| false';Test='org.telegram.messenger.CallbackProbe';Failure='Preview callback invalidated 2'},
    @{Name='preview-epoch';Class='CallbackProbe';From='!lunagramPreviewSession.isCurrent()';To='false';Test='org.telegram.messenger.CallbackProbe';Failure='Preview callback invalidated 0'},
    @{Name='preview-id';Class='CallbackProbe';From='waitingForWebpageId != myId';To='false';Test='org.telegram.messenger.CallbackProbe';Failure='Preview callback invalidated 7'},
    @{Name='tts-shutdown';Class='CallbackProbe';From='previous.shutdown();';To='';Test='org.telegram.messenger.CallbackProbe';Failure='Speech releases engine and dialog'},
    @{Name='source-context';Class='CallbackProbe';From='contextValid.getAsBoolean()';To='true';Test='org.telegram.messenger.CallbackProbe';Failure='Speech denies stale UI 4'}
)
foreach($mutation in $mutations){
    $original=if($mutation.Class -eq 'CallbackProbe'){$probe}else{Get-Content -LiteralPath (Join-Path $taskRepo "TMessagesProj/src/main/java/org/telegram/messenger/$($mutation.Class).java") -Raw}
    if(!$original.Contains($mutation.From)){throw "Missing mutation anchor: $($mutation.Name)"}
    $folder=Join-Path $taskRun $mutation.Name;New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $path=Join-Path $folder ($mutation.Class+'.java')
    [IO.File]::WriteAllText($path,$original.Replace($mutation.From,$mutation.To),[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome ('bin/javac'+$taskSuffix)) -J-Xmx128m -encoding UTF-8 -cp ($taskRun+[IO.Path]::PathSeparator+$JsonJar) -d $folder $path
    if($LASTEXITCODE -ne 0){throw "Negative must compile: $($mutation.Name)"}
    $result=& (Join-Path $JavaHome ('bin/java'+$taskSuffix)) -Xmx128m -cp ($folder+[IO.Path]::PathSeparator+$taskRun+[IO.Path]::PathSeparator+$JsonJar) $mutation.Test 2>&1
    if($LASTEXITCODE -eq 0 -or !($result|Out-String).Contains($mutation.Failure)){throw "Negative missed intended fault: $($mutation.Name): $result"}
    Write-Output "PASS negative: $($mutation.Name)"
}
Write-Output "Results: $taskRun"
