param(
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/gift-pager150')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix=if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
$java=Join-Path $JavaHome ('bin/java'+$suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
function Body([string]$source,[string]$signature) {
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production signature: $signature" }
    $open=$source.IndexOf('{',$start);$depth=1
    for ($i=$open+1;$i -lt $source.Length;$i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if (!$depth) { return $source.Substring($start,$i-$start+1) }
    }
    throw "Unterminated production signature: $signature"
}
$sheet=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Stars/StarGiftSheet.java'))
$parent=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Gifts/ProfileGiftsContainer.java'))
$preview=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Gifts/LumaLocalGiftPreview.java'))
$sheetSignatures=@(
    'private int getListPosition()', 'public StarGiftSheet setProfilePreviewPager(',
    'private TL_stars.SavedStarGift getProfilePreviewGift()', 'private int getProfilePreviewPosition()',
    'private int getProfilePreviewNeighbourIndex(', 'private void bindProfilePreview(',
    'private boolean isProfilePreviewRequestCurrent(', 'private void refreshProfilePreviewPager()',
    'private void restoreNativePreviewHeader()', 'public StarGiftSheet setLocalPreview(',
    'private TL_stars.SavedStarGift getNeighbourSavedGift(',
    'public boolean eq(TL_stars.TL_starGiftUnique a, TL_stars.TL_starGiftUnique b)',
    'public boolean eq(TL_stars.SavedStarGift a, TL_stars.SavedStarGift b)',
    'public TL_stars.StarGift getGift()',
    'public static TL_stars.InputSavedStarGift getInputStarGift(long dialogId, TL_stars.SavedStarGift g)',
    'private TL_stars.InputSavedStarGift getInputStarGift()'
)
$parentSignatures=@('private String getLocalGiftId(', 'private boolean isGiftPinnedLocally(',
    'private LumaLocalGiftPreview.PagerSource createPreviewPager(', 'private ArrayList<TL_stars.SavedStarGift> previewGiftOrder(',
    'private void notifyPreviewPagers()', 'private final class ProfilePreviewPager')
$probe=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'gift-pager150/GiftPager150Probe.java.template'))
$probe=$probe.Replace('/* ACTUAL_SHEET */',(($sheetSignatures | ForEach-Object {Body $sheet $_}) -join "`n"))
$probe=$probe.Replace('/* ACTUAL_PARENT */',(($parentSignatures | ForEach-Object {Body $parent $_}) -join "`n"))
$probe=$probe.Replace('/* ACTUAL_LOCAL_DISPLAY */',(Body $parent 'private ArrayList<LumaLocalGifts.LocalSavedGift> localDisplayGifts('))
$roller=Body ($sheet.Replace("`r",'')) 'private static class Roller'
$probe=$probe.Replace('/* ACTUAL_ROLLER_DETACH */',(Body $roller "public void detach() {`n            rolling = false;"))
$completion1=Body $roller "AndroidUtilities.runOnUIThread(() -> {`n                    rolling = false;"
$completion2=Body $roller "AndroidUtilities.runOnUIThread(() -> {`n                    if (whenDone2 != null)"
$probe=$probe.Replace('/* ACTUAL_ROLLER_COMPLETIONS */',($completion1+");`n"+$completion2+');'))
$probe=$probe.Replace('/* ACTUAL_METADATA */',(Body $preview 'private static void addStoredMetadataRows('))
$probe=$probe.Replace('/* ACTUAL_REMOVE */',(Body $preview 'public static void confirmRemove(BaseFragment fragment, LumaLocalGifts.LocalSavedGift gift, BooleanSupplier ownerCurrent)'))
$resource="final class R {`n"
foreach($kind in @('string','raw')) {
    $resource+="static final class $kind {`n"
    $keys=[regex]::Matches($probe,('R\.'+$kind+'\.(\w+)')) | ForEach-Object {$_.Groups[1].Value} | Sort-Object -Unique
    $id=1;foreach($key in $keys){$resource+="static final int $key=$id;`n";$id++}
    $resource+="}`n"
}
$resource+="}`n";$probe+="`n$resource"
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$path=Join-Path $run 'GiftPager150Probe.java';[IO.File]::WriteAllText($path,$probe,[Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $path
if($LASTEXITCODE -ne 0){throw 'Extracted production gift binder/pager probe did not compile.'}
& $java -Xmx128m -cp $run GiftPager150Probe
if($LASTEXITCODE -ne 0){throw 'Extracted gift binder/pager regression failed.'}

$local=Body $sheet 'public StarGiftSheet setLocalPreview('
if($local -match 'sendRequest\(|ConnectionsManager|TL_payments|toggleShow\(|onBuyPressed\('){throw 'Local binding must have no transaction side effects.'}
$nativeSaved=Body $sheet 'public StarGiftSheet set(TL_stars.SavedStarGift savedStarGift, StarsController.IGiftsList list)'
if($nativeSaved.IndexOf('instanceof LumaLocalGifts.LocalSavedGift') -lt 0 -or
    $nativeSaved.IndexOf('instanceof LumaLocalGifts.LocalSavedGift') -gt $nativeSaved.IndexOf('myProfile =')){throw 'Marker binding must branch before normal owned gift setup.'}
$menu=Body $sheet 'private void onMenuPressed('
$localMenu=$menu.Substring(0,$menu.IndexOf('final TL_stars.TL_starGiftUnique'))
if(!$localMenu.Contains('confirmRemove(') -or !$localMenu.Contains('return;') -or $localMenu -match 'sendRequest\(|toggleShow\(|onTransferClick\('){throw 'Local menu must remain remove-only.'}
$neighbor=Body $sheet 'private void setupNeighbour('
if(!$neighbor.Contains('sheet.setLocalPreview(') -or !$neighbor.Contains('sheet.setProfilePreviewPager(profilePreviewPager)')){throw 'Neighbor prebinding must preserve local isolation and the mixed source.'}
$indices=Body $sheet 'private int getListPosition()'
if($indices -match 'profilePreviewPosition|profilePreviewPager'){throw 'Server upgrade lookup must not use a mixed UI index.'}
foreach($method in @('private void openCrafting(', 'private void openCraftInfo(', 'private void showGiftOfferSheet(',
    'private boolean canSetAsTheme()', 'private void openSetAsTheme(', 'private void onWearPressed(',
    'public void toggleWear(boolean', 'public void onSharePressed(', 'public void onUpdatePriceClick(',
    'public void onResellPressed(', 'private void repostStory(', 'public boolean canTransfer()',
    'public void set(TL_stars.TL_starGiftUnique gift, boolean refunded, TLObject fromObject, TLRPC.TL_textWithEntities message)',
    'public boolean showCraft()', 'public boolean canCraft()', 'private boolean canSomeoneConvert()',
    'private boolean canConvert()', 'private void convert()', 'private void toggleShow()',
    'private void openUpgrade()', 'private void openUpgradeAfter()', 'private void openUpgradePrices()',
    'private void doUpgrade()', 'public void onTransferClick(', 'public void openTransfer()',
    'public void openTransferAlert(', 'private void initTONTransfer(', 'public void doTransfer(',
    'public void onBuyPressed(', 'private void performBuyPressed(')) {
    $body=Body $sheet $method
    if($body.Substring($body.IndexOf('{')+1).TrimStart() -notmatch '^if \(localPreviewGift != null\) return'){throw "Missing local capability guard: $method"}
}
$upgrade=Body $sheet 'private void openUpgrade()'
if(([regex]::Matches($upgrade,'isProfilePreviewRequestCurrent\(previewGeneration, previewGift\)')).Count -ne 2){throw 'Both upgrade preview and payment response must reject stale mixed-page state.'}
if(!(Body $sheet 'private void tickUpgradePrice()').Contains('isProfilePreviewRequestCurrent(previewGeneration, previewGift)')){throw 'Pending price-form response must reject a rebind.'}
if(([regex]::Matches((Body $sheet 'private void performBuyPressed('),'isProfilePreviewRequestCurrent\(previewGeneration, previewGift\)')).Count -ne 2){throw 'Resale form and confirmation must reject a rebind.'}
$add=Body $sheet 'private void addGiftLocally('
if(!$add.Contains('LunagramFeatureGate.require(') -or ([regex]::Matches($add,'LunagramSubscriptionAccess.canUse')).Count -ne 3){throw 'UI, worker mutation and late result must respect local-NFT access.'}
foreach($ownerSource in @($sheet,$parent)) {
    if(!$ownerSource.Contains('addObserver(this, NotificationCenter.lunagramSubscriptionChanged)') -or
            !$ownerSource.Contains('removeObserver(this, NotificationCenter.lunagramSubscriptionChanged)')){throw 'Gift expiry listeners must be lifecycle-balanced.'}
}
$swap=Body $sheet 'protected void swapViews('
foreach($guard in @('previewGeneration != profilePreviewGeneration','profilePreviewPager.indexOf(previewTarget) < 0','bindProfilePreview(previewTarget)')) {
    if(!$swap.Contains($guard)){throw "Queued swipe must reject stale/missing target: $guard"}
}

$negatives=@(
    @{Name='input-marker';Old='if (localPreviewGift != null || savedStarGift instanceof LumaLocalGifts.LocalSavedGift) return null;';New='';Expected='private/static input conversion fails closed'},
    @{Name='owner-epoch';Old='epoch == localPreviewEpoch && owner > 0';New='owner > 0';Expected='epoch invalidation closes owner view'},
    @{Name='uncleared-transaction';Old='savedStarGift = null;';New='';Expected='transaction identities cleared'},
    @{Name='native-roll-opacity';Old='nativeTopView.imageLayout.setAlpha(1.0f);';New='';Expected='roll artwork normalized'},
    @{Name='stale-upgrade-response';Old='generation == profilePreviewGeneration';New='generation >= 0';Expected='A request after local back to A rejected'},
    @{Name='queued-roll-after-detach';Old='whenDone = null;';New='';Expected='queued detached roller completion discarded'},
    @{Name='expiry-overlay';Old='if (!LunagramSubscriptionAccess.canUse(currentAccount, LunagramFeatureCatalog.Feature.LOCAL_NFT)) return new ArrayList<>();';New='';Expected='expiry removes overlay and selects real survivor'}
)
foreach($control in $negatives) {
    $mutant=$probe.Replace($control.Old,$control.New)
    if($mutant -eq $probe){throw "Negative control did not apply: $($control.Name)"}
    $folder=Join-Path $run $control.Name;New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $file=Join-Path $folder 'GiftPager150Probe.java';[IO.File]::WriteAllText($file,$mutant,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $folder $file
    if($LASTEXITCODE -ne 0){throw "Negative control failed to compile: $($control.Name)"}
    $preference=$ErrorActionPreference;$ErrorActionPreference='Continue'
    $result=(& $java -Xmx128m -cp $folder GiftPager150Probe 2>&1 | Out-String);$code=$LASTEXITCODE
    $ErrorActionPreference=$preference
    if($code -eq 0 -or !$result.Contains($control.Expected)){throw "Negative control missed defect: $($control.Name)"}
}
Write-Output 'PASS: mixed local/server UI order, server-only upgrade indices, local action guards, queued roll/swipe, expiry-preserved data, stored metadata and owner epoch; seven negative controls.'
Write-Output "Test artifacts: $run"
