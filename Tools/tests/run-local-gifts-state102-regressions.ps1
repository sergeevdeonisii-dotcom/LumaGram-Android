param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-local-gifts-state102-regressions')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
$java=Join-Path $JavaHome ('bin/java'+$suffix)
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Method([string]$source,[string]$signature) {
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing production method: $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($i=$open+1;$i -lt $source.Length;$i++){
        if($source[$i] -eq '{'){$depth++};if($source[$i] -eq '}'){$depth--}
        if(!$depth){return $source.Substring($start,$i-$start+1)}
    }
    throw "Unclosed production method: $signature"
}
function ProductionClass([string]$source,[string]$name){
    $match=[regex]::Match($source,'public static (?:final |abstract )?class '+[regex]::Escape($name)+'(?:\s|\{)')
    if(!$match.Success){throw "Missing production class: $name"}
    $start=$match.Index;$open=$source.IndexOf('{',$start);$depth=1
    for($i=$open+1;$i -lt $source.Length;$i++){
        if($source[$i] -eq '{'){$depth++};if($source[$i] -eq '}'){$depth--}
        if(!$depth){return $source.Substring($start,$i-$start+1)}
    }
    throw "Unclosed production class: $name"
}
function ExpectedFailure([string]$ClassRoot,[string]$MainClass){
    # Expected JVM assertion failures must not leave a failure LASTEXITCODE in the caller.
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$java;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in @('-Xmx128m','-cp',$ClassRoot,$MainClass)){$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($info)
    try{
        $outputTask=$process.StandardOutput.ReadToEndAsync();$errorTask=$process.StandardError.ReadToEndAsync()
        if(!$process.WaitForExit(30000)){throw "Negative-control JVM timed out: $MainClass"}
        return @{Code=$process.ExitCode;Output=$outputTask.GetAwaiter().GetResult()+$errorTask.GetAwaiter().GetResult()}
    }finally{$process.Dispose()}
}
$src=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram'
$shared=[IO.File]::ReadAllText((Join-Path $src 'ui/Components/SharedMediaLayout.java'))
$profile=[IO.File]::ReadAllText((Join-Path $src 'ui/ProfileActivity.java'))
$gifts=[IO.File]::ReadAllText((Join-Path $src 'ui/Gifts/ProfileGiftsContainer.java'))
$factory=[IO.File]::ReadAllText((Join-Path $src 'ui/Gifts/GiftSheet.java'))
$stars=[IO.File]::ReadAllText((Join-Path $src 'ui/Stars/StarsController.java'))
$utilities=[IO.File]::ReadAllText((Join-Path $src 'messenger/Utilities.java'))
$probe=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'local-gifts-ui102/LocalGiftsUi102Probe.java.template'))
$parent=@('private String getLocalPinsKey()', 'private String getLocalGiftId(', 'private boolean isGiftPinnedLocally(',
    'private boolean hasMissingLocalPins(', 'private void setGiftPinnedLocally(', 'private void reloadLocalGifts()',
    'private ArrayList<LumaLocalGifts.LocalSavedGift> localDisplayGifts(', 'public int getGiftsCount()')
$page=@('public void fillItems(', 'private TL_stars.SavedStarGift getSavedGift(', 'private boolean canReorder(', 'public boolean onMove(')
$probe=$probe.Replace('// PRODUCTION_PARENT_METHODS',(($parent|ForEach-Object{Method $gifts $_})-join "`n"))
$probe=$probe.Replace('// PRODUCTION_PAGE_METHODS',(($page|ForEach-Object{Method $gifts $_})-join "`n"))
$probe=$probe.Replace('// PRODUCTION_REORDER',(Method $stars 'public void reorder(int fromPosition, int toPosition)'))
$probe=$probe.Replace('// PRODUCTION_FACTORY',(Method $factory 'public static UItem asStarGift(int tab, TL_stars.SavedStarGift gift, boolean noprice, boolean checkable, boolean inCollection)'))
$probe=$probe.Replace('// PRODUCTION_PROFILE_SELF_HELPER',(Method $profile 'private boolean hasLocalGiftsForProfile()'))
$probe=$probe.Replace('// PRODUCTION_SHARED_SELF_HELPER',(Method $shared 'private static boolean hasLocalGiftsForDialog('))
$probe=$probe.Replace('// PRODUCTION_CLAMP',(Method $utilities 'public static int clamp('))
$profileEvent=Method $profile 'else if (id == NotificationCenter.lumaLocalGiftsChanged)'
$profileEvent=$profileEvent.Replace('else if (id == NotificationCenter.lumaLocalGiftsChanged)','if (id == 1)')
$headerEvent="void localEvent(int id, int account, Object... args) { $profileEvent }"
$probe=$probe.Replace('static final class ProfileActivity extends BaseFragment {',"static final class ProfileActivity extends BaseFragment {`n boolean isSettings() { return false; }`n void updateRowsIds() {}`n ObjectListAdapter listAdapter;`n SharedStub sharedMediaLayout;`n $headerEvent")
$probe=$probe.Replace('static final class LaunchActivity',"static final class ObjectListAdapter { void notifyDataSetChanged() {} }`n static final class SharedStub { void updateTabs(boolean animated) {} }`n static final class LaunchActivity")
$pageUpdate=Method $gifts 'public void update(boolean animated)'
$probe=$probe.Replace('void update(boolean animated) {}',$pageUpdate)
$probe=$probe.Replace('void updateWithoutNotify() {}','void updateWithoutNotify() {} void update(boolean animated) {}')
$probe=$probe.Replace('int getSpanCount() { return spans; }','int getSpanCount() { return spans; } boolean canScrollVertically(int direction) { return false; } void scrollToPosition(int position) {}')
$start=$probe.IndexOf('    public static void main(String[] args) {')
$probe=$probe.Substring(0,$start)+@'
    public static void main(String[] args) {
        UserConfig.identity=1001;
        Parent p=new Parent(); Page page=new Page(p); p.list.totalCount=10;
        LumaLocalGifts.LocalSavedGift first=new LumaLocalGifts.LocalSavedGift(1001,1),second=new LumaLocalGifts.LocalSavedGift(1001,2);
        ProfileActivity header=new ProfileActivity();header.observed=p;p.fragment=header;
        LumaLocalGifts.entries.add(first);LumaLocalGifts.entries.add(second);p.reloadLocalGifts();
        header.updateSelectedMediaTabText();
        check(header.displayedCount==12,"initial actual combined gift count");
        Utilities.globalQueue.automatic=false;AndroidUtilities.ui.automatic=false;
        LumaLocalGifts.entries.remove(first);p.reloadLocalGifts();header.localEvent(1,0,1001L);
        check(header.displayedCount==12,"local event observes old loaded snapshot before worker");
        Utilities.globalQueue.drain();AndroidUtilities.ui.drain();
        check(p.getGiftsCount()==11,"actual asynchronous reload and Page.update apply new snapshot");
        check(header.displayedCount==11,"header callback after actual load completion/page update refreshes current count");
        LumaLocalGifts.entries.clear();p.reloadLocalGifts();header.localEvent(1,0,1001L);
        Utilities.globalQueue.drain();AndroidUtilities.ui.drain();
        check(p.getGiftsCount()==10 && header.displayedCount==10,"last local removal refreshes header while real gifts remain");
        System.out.println("PASS: actual reloadLocalGifts/Page.update/Profile local-event ordering refreshes header12->11->10 after each accepted snapshot.");
    }
}
'@
[IO.File]::WriteAllText((Join-Path $run 'LocalGiftsUi102Probe.java'),$probe,[Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run (Join-Path $run 'LocalGiftsUi102Probe.java')
if($LASTEXITCODE -ne 0){throw 'Header state proof compilation failed.'}
& $java -Xmx128m -cp $run LocalGiftsUi102Probe
if($LASTEXITCODE -ne 0){throw 'Header state regression failed.'}
$negativeHeader=[regex]::Replace($probe,'if \(fragment instanceof ProfileActivity\)\s*\{\s*\(\(ProfileActivity\) fragment\)\.updateSelectedMediaTabText\(\);\s*\}','')
if($negativeHeader -eq $probe){throw 'Missing production load-completion header callback for negative control.'}
$negativeHeaderRoot=Join-Path $run 'negative-header'
New-Item -ItemType Directory -Path $negativeHeaderRoot -Force | Out-Null
$negativeHeaderFile=Join-Path $negativeHeaderRoot 'LocalGiftsUi102Probe.java'
[IO.File]::WriteAllText($negativeHeaderFile,$negativeHeader,[Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $negativeHeaderRoot $negativeHeaderFile
if($LASTEXITCODE -ne 0){throw 'Header negative-control compilation failed.'}
$negativeHeaderOutput=ExpectedFailure $negativeHeaderRoot 'LocalGiftsUi102Probe'
if($negativeHeaderOutput.Code -eq 0 -or !$negativeHeaderOutput.Output.Contains('header callback after actual load completion')){throw 'Removing actual header callback did not reproduce stale count.'}
$late=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'local-gifts-ui102/LocalGiftState102Regression.java.template'))
$late=$late.Replace('// PRODUCTION_SELF_HELPER',(Method $shared 'private static boolean hasLocalGiftsForDialog('))
$late=$late.Replace('// PRODUCTION_CHECK_CURRENT_TAB',(Method $shared 'private void checkCurrentTabValid()'))
$predicate=[regex]::Match($shared,'boolean hasGifts = giftsContainer != null &&[^;]+;').Value
if(!$predicate){throw 'Missing production gift-tab predicate.'}
$late=$late.Replace('// PRODUCTION_HAS_GIFTS',$predicate)
[IO.File]::WriteAllText((Join-Path $run 'LocalGiftState102Regression.java'),$late,[Text.UTF8Encoding]::new($false))
$generated=@()
foreach($name in @('TL_stars','TLRPC')){
    $template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot ('local-gifts102/'+$name+'.java.template')))
    $path=if($name -eq 'TL_stars'){'tgnet/tl/TL_stars.java'}else{'tgnet/TLRPC.java'}
    $production=[IO.File]::ReadAllText((Join-Path $src $path))
    foreach($match in [regex]::Matches($template,'@@([A-Za-z0-9_]+)@@')){$template=$template.Replace($match.Value,(ProductionClass $production $match.Groups[1].Value))}
    $file=Join-Path $run ($name+'.java');[IO.File]::WriteAllText($file,$template,[Text.UTF8Encoding]::new($false));$generated+=$file
}
$sources=$generated+@((Join-Path $run 'LocalGiftState102Regression.java'),(Join-Path $PSScriptRoot 'local-gifts102/LocalGifts102Test.java'))
$sources+=(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'local-gifts102/fixtures') -Recurse -Filter '*.java').FullName
$sources+=@('SerializedData','AbstractSerializedData','InputSerializedData','OutputSerializedData','TLDataSourceType','TLObject','Vector')|ForEach-Object{Join-Path $src ('tgnet/'+$_+'.java')}
$sources+=@('messenger/LumaLocalGifts.java','messenger/LumaAccountData.java','messenger/BlackHoleSealedData.java')|ForEach-Object{Join-Path $src $_}
$sources+=Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java'
& $javac -J-Xmx128m -encoding UTF-8 -d $run $sources
if($LASTEXITCODE -ne 0){throw 'Availability/tab selection proof compilation failed.'}
& $java -Xmx128m -cp $run org.telegram.messenger.LocalGiftState102Regression
if($LASTEXITCODE -ne 0){throw 'Availability/tab selection regression failed.'}
$localSource=Join-Path $src 'messenger/LumaLocalGifts.java'
$local=[IO.File]::ReadAllText($localSource)
$writeThrough='uiAvailability.put(account, new UiAvailability(owner, encrypted, !written.isEmpty()));'
if(!$local.Contains($writeThrough)){throw 'Missing validated production write-through availability for negative control.'}
$negativeStoreRoot=Join-Path $run 'negative-store'
New-Item -ItemType Directory -Path $negativeStoreRoot -Force | Out-Null
$negativeLocalFile=Join-Path $negativeStoreRoot 'LumaLocalGifts.java'
[IO.File]::WriteAllText($negativeLocalFile,$local.Replace($writeThrough,''),[Text.UTF8Encoding]::new($false))
$negativeSources=@($sources|Where-Object {$_ -ne $localSource})+@($negativeLocalFile)
& $javac -J-Xmx128m -encoding UTF-8 -d $negativeStoreRoot $negativeSources
if($LASTEXITCODE -ne 0){throw 'Availability negative-control compilation failed.'}
$negativeStoreOutput=ExpectedFailure $negativeStoreRoot 'org.telegram.messenger.LocalGiftState102Regression'
if($negativeStoreOutput.Code -eq 0 -or !$negativeStoreOutput.Output.Contains('Non-empty validated write keeps actual local-only gift tab visible')){throw 'Removing actual write-through publication did not reproduce false tab removal.'}
Write-Output 'PASS: negative controls remove actual header callback / validated availability publication and reproduce stale count / false gift-tab removal.'
Write-Output "State regression artifacts: $run"
