param(
    [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1' }),
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs')
)
$ErrorActionPreference = 'Stop'
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskRun = Join-Path $OutputRoot ('productivity-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskSuffix = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$taskSources = @(
    (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramTextTools.java'),
    (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramTextHistory.java'),
    (Join-Path $PSScriptRoot 'productivity/TextToolsTest.java')
)
& (Join-Path $JavaHome ('bin/javac' + $taskSuffix)) -J-Xmx128m -encoding UTF-8 -d $taskRun @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Productivity source compilation failed' }
& (Join-Path $JavaHome ('bin/java' + $taskSuffix)) -Xmx128m -cp $taskRun TextToolsTest
if ($LASTEXITCODE -ne 0) { throw 'Productivity regression failed' }
function Extract-Method([string]$source,[string]$signature) {
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing composer method: $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++){
        if($source[$end] -eq '{'){$depth++};if($source[$end] -eq '}'){$depth--}
    }
    if($depth -ne 0){throw "Unbalanced composer method: $signature"}
    $source.Substring($start,$end-$start)
}
$taskComposer=Get-Content -LiteralPath (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java') -Raw
$taskMethods=(@(
    'private boolean isLunagramOrdinaryComposer()', 'private void clearLunagramTextHistory()',
    'private boolean ensureLunagramTextHistory()', 'private void recordLunagramTextSnapshot(CharSequence value)',
    'private void onLunagramTextSpansChanged()',
    'private String lunagramQuoteState()',
    'public LunagramTextToolsSheet.Apply createLunagramDraftApply()', 'public void showLunagramTextTools()',
    'public void setFieldText(CharSequence text, boolean ignoreChange, boolean fromDraft)'
) | ForEach-Object { Extract-Method $taskComposer $_ }) -join "`n"
$taskTemplate=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'productivity/ComposerProbe.java.template') -Raw
$taskProbe=Join-Path $taskRun 'ComposerProbe.java'
$taskComposerSources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'productivity/fixtures') -File -Filter '*.java' -Recurse | ForEach-Object FullName)
$taskComposerSources+=@($taskSources[0],$taskSources[1],
    (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramMediaAccess.java'),
    (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeatureCatalog.java'))
function Run-Composer([string]$methods,[string]$expectedFailure='') {
    [IO.File]::WriteAllText($taskProbe,$taskTemplate.Replace('@@METHODS@@',$methods),[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome ('bin/javac'+$taskSuffix)) -J-Xmx128m -encoding UTF-8 -d $taskRun $taskProbe @taskComposerSources
    if($LASTEXITCODE -ne 0){throw 'Extracted composer methods did not compile'}
    $result=& (Join-Path $JavaHome ('bin/java'+$taskSuffix)) -Xmx128m -cp $taskRun org.telegram.messenger.ComposerProbe 2>&1
    if($expectedFailure){
        if($LASTEXITCODE -eq 0 -or !($result|Out-String).Contains($expectedFailure)){throw "Composer negative did not catch intended fault: $result"}
        Write-Output "PASS negative: $expectedFailure"
    }else{if($LASTEXITCODE -ne 0){throw "Composer lifecycle regression: $result"};Write-Output $result}
}
Run-Composer $taskMethods
foreach($mutation in @(
    @('revision != lunagramDraftRevision','false','Revision rejects edit-and-revert while preview open'),
    @('!owner.canUse(org.telegram.messenger.LunagramFeatureCatalog.Feature.TEXT_TOOLS)','false','Stale scope/access cannot apply 1'),
    @('topic != parentFragment.getTopicId()','false','Stale scope/access cannot apply 4'),
    @('dialog != dialog_id','false','Stale scope/access cannot apply 3'),
    @('setFieldText(text, true, false);','setFieldText(text, false, false);','Applying newline never invokes send-by-enter'),
    @('&& !SharedConfig.appLocked','&& true','Stale scope/access cannot apply 11'),
    @('|| !quoteState.equals(lunagramQuoteState())','|| false','Mutable quote toggle invalidates Apply without span callback'),
    @('new android.text.SpannedString(value), value.length()','new android.text.SpannedString(value.toString()), value.length()','Undo preserves opaque span snapshot rather than String flattening')
)){
    if(!$taskMethods.Contains($mutation[0])){throw "Missing composer mutation: $($mutation[0])"}
    Run-Composer $taskMethods.Replace($mutation[0],$mutation[1]) $mutation[2]
}
Run-Composer $taskMethods
foreach($guard in @('if (!lunagramHistoryReplay && ensureLunagramTextHistory()) recordLunagramTextSnapshot(s);',
    'if (!lunagramHistoryReplay && ensureLunagramTextHistory()) recordLunagramTextSnapshot(text);',
    'if (dialog_id != id || currentAccount != account) clearLunagramTextHistory();',
    'if (rich != richDraftMessage) clearLunagramTextHistory();')){
    if(!$taskComposer.Contains($guard)){throw "Missing lifecycle wiring: $guard"}
}
if(!(Extract-Method $taskComposer 'public void onDestroy()').Contains('clearLunagramTextHistory();')){throw 'Destroy must clear in-memory history'}
if(!$taskComposer.Contains("messageEditText.setDelegate(() -> {`n            onLunagramTextSpansChanged();") -and !$taskComposer.Contains("messageEditText.setDelegate(() -> {`r`n            onLunagramTextSpansChanged();")){throw 'Format-only changes must invalidate pending preview'}
$taskSheet=Get-Content -LiteralPath (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/ui/Components/LunagramTextToolsSheet.java') -Raw
$taskReader=Get-Content -LiteralPath (Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramReaderActivity.java') -Raw
$taskSelection=Join-Path $taskRepo 'TMessagesProj/src/main/java/org/telegram/ui/Components/LunagramTextSelectionGuard.java'
$taskSelectionSource=Get-Content -LiteralPath $taskSelection -Raw
$taskAccessTemplate=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'productivity/AccessProbe.java.template') -Raw
$taskSheetValid=Extract-Method $taskSheet 'private boolean valid()'
$taskReaderAllowed=Extract-Method $taskReader 'private boolean allowed()'
function Run-Access([string]$sheet,[string]$reader,[string]$expectedFailure='', [string]$selection=$taskSelectionSource) {
    $source=$taskAccessTemplate.Replace('@@SHEET_VALID@@',$sheet).Replace('@@READER_ALLOWED@@',$reader)
    $probe=Join-Path $taskRun 'AccessProbe.java'
    [IO.File]::WriteAllText($probe,$source,[Text.UTF8Encoding]::new($false))
    $selectionProbe=Join-Path $taskRun 'LunagramTextSelectionGuard.java'
    [IO.File]::WriteAllText($selectionProbe,$selection,[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome ('bin/javac'+$taskSuffix)) -J-Xmx128m -encoding UTF-8 -cp $taskRun -d $taskRun $probe $selectionProbe
    if($LASTEXITCODE -ne 0){throw 'Extracted UI/access predicates did not compile'}
    $result=& (Join-Path $JavaHome ('bin/java'+$taskSuffix)) -Xmx128m -cp $taskRun org.telegram.messenger.AccessProbe 2>&1
    if($expectedFailure){
        if($LASTEXITCODE -eq 0 -or !($result|Out-String).Contains($expectedFailure)){throw "Access negative missed fault: $result"}
        Write-Output "PASS negative: $expectedFailure"
    }else{if($LASTEXITCODE -ne 0){throw "UI access regression: $result"};Write-Output $result}
}
Run-Access $taskSheetValid $taskReaderAllowed
$contextGuard='contextValid == null || contextValid.getAsBoolean()'
if(!$taskSheetValid.Contains($contextGuard) -or !$taskReaderAllowed.Contains($contextGuard)){throw 'Message context guard not present in both UI consumers'}
Run-Access $taskSheetValid.Replace($contextGuard,'true') $taskReaderAllowed 'Sheet rejects changed context 0'
Run-Access $taskSheetValid $taskReaderAllowed.Replace($contextGuard,'true') 'Reader rejects changed context 0'
if(!$taskSelectionSource.Contains('return !valid(mode);')){throw 'Native selection callback guard missing'}
Run-Access $taskSheetValid $taskReaderAllowed 'Revoked native action consumed' $taskSelectionSource.Replace('return !valid(mode);','return false;')
Run-Access $taskSheetValid $taskReaderAllowed
foreach($guard in @('if (!valid()) { dialog.dismiss(); return; }','if (apply == null) AndroidUtilities.addToClipboard(text);',
    'body.setCustomSelectionActionModeCallback(new LunagramTextSelectionGuard(this::valid',
    'view.setCustomSelectionActionModeCallback(new LunagramTextSelectionGuard(this::valid',
    'if (!valid()) { setText(""); return true; }')){
    if(!$taskSheet.Contains($guard)){throw "Missing tool action guard: $guard"}
}
foreach($guard in @('if (!allowed()) closeExpired();','new LunagramTextSelectionGuard(this::allowed, this::closeExpired)',
    'if (!allowed()) { closeExpired(); return true; }')){
    if(!$taskReader.Contains($guard)){throw "Missing reader action guard: $guard"}
}
$taskNames=@()
foreach($variant in @('values','values-ru')){
    [xml]$taskXml=Get-Content -LiteralPath (Join-Path $taskRepo "TMessagesProj/src/main/res/$variant/lunagram_text_tools.xml") -Raw
    $taskVariantNames=@($taskXml.resources.string | ForEach-Object name | Sort-Object)
    if($taskNames.Count -and (Compare-Object $taskNames $taskVariantNames)){throw 'Text tools EN/RU resource keys diverge'}
    $taskNames=$taskVariantNames
}
Write-Output "PASS lifecycle wiring and $($taskNames.Count) matching EN/RU resources"
Write-Output "Results: $taskRun"
