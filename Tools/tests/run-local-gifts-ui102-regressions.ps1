param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-local-gifts-ui102-tests')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
function Method([string]$source, [string]$signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method: $signature" }
    $open = $source.IndexOf('{', $start); $depth = 1
    for ($i = $open + 1; $i -lt $source.Length; $i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if (!$depth) { return $source.Substring($start, $i - $start + 1) }
    }
    throw "Unterminated production method: $signature"
}
function Source([string]$path) { [IO.File]::ReadAllText((Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/' + $path))) }
$profile = Source 'ui/Gifts/ProfileGiftsContainer.java'
$preview = Source 'ui/Gifts/LumaLocalGiftPreview.java'
$sheet = Source 'ui/Stars/StarGiftSheet.java'
$store = Source 'messenger/LumaLocalGifts.java'
$stars = Source 'ui/Stars/StarsController.java'
$factory = Source 'ui/Gifts/GiftSheet.java'
$activity = Source 'ui/ProfileActivity.java'
$shared = Source 'ui/Components/SharedMediaLayout.java'
$utilities = Source 'messenger/Utilities.java'
$universal = Source 'ui/Components/UniversalRecyclerView.java'
$adapter = Source 'ui/Components/UniversalAdapter.java'
$item = Source 'ui/Components/UItem.java'
$policy = Source 'messenger/LumaBuildPolicy.java'
$recycler = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/androidx/recyclerview/widget/RecyclerView.java'))
$grid = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/androidx/recyclerview/widget/GridLayoutManager.java'))
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'local-gifts-ui102/LocalGiftsUi102Probe.java.template'))
$parentSignatures = @('private String getLocalPinsKey()', 'private String getLocalGiftId(', 'private boolean isGiftPinnedLocally(',
    'private boolean hasMissingLocalPins(', 'private void setGiftPinnedLocally(', 'private void reloadLocalGifts()',
    'private ArrayList<LumaLocalGifts.LocalSavedGift> localDisplayGifts(', 'public int getGiftsCount()')
$pageSignatures = @('public void fillItems(', 'private TL_stars.SavedStarGift getSavedGift(', 'private boolean canReorder(', 'public boolean onMove(')
$template = $template.Replace('// PRODUCTION_PARENT_METHODS', (($parentSignatures | ForEach-Object { Method $profile $_ }) -join "`n"))
$template = $template.Replace('// PRODUCTION_PAGE_METHODS', (($pageSignatures | ForEach-Object { Method $profile $_ }) -join "`n"))
$template = $template.Replace('// PRODUCTION_REORDER', (Method $stars 'public void reorder(int fromPosition, int toPosition)'))
$template = $template.Replace('// PRODUCTION_FACTORY', (Method $factory 'public static UItem asStarGift(int tab, TL_stars.SavedStarGift gift, boolean noprice, boolean checkable, boolean inCollection)'))
$template = $template.Replace('// PRODUCTION_PROFILE_SELF_HELPER', (Method $activity 'private boolean hasLocalGiftsForProfile()'))
$template = $template.Replace('// PRODUCTION_SHARED_SELF_HELPER', (Method $shared 'private static boolean hasLocalGiftsForDialog('))
$template = $template.Replace('// PRODUCTION_CLAMP', (Method $utilities 'public static int clamp('))
$maxSpan = [regex]::Match($item, 'public static final int MAX_SPAN_COUNT\s*=\s*-?\d+;')
if (!$maxSpan.Success) { throw 'Missing production full-width span sentinel.' }
$template = $template.Replace('// PRODUCTION_MAX_SPAN_COUNT', $maxSpan.Value)
$template = $template.Replace('// PRODUCTION_SPAN_LOOKUP', (Method $universal 'public int getSpanSize(int position)'))
$template = $template.Replace('// PRODUCTION_DISPATCH_LAYOUT_STEP_2', (Method $recycler 'private void dispatchLayoutStep2()'))
$template = $template.Replace('// PRODUCTION_IS_COMPUTING_LAYOUT', (Method $recycler 'public boolean isComputingLayout()'))
$template = $template.Replace('// PRODUCTION_GRID_SPAN_CONSTRAINT', (Method $grid 'if (spanSize > mSpanCount)'))
$template = $template.Replace('// PRODUCTION_ADAPTER_UPDATE', (Method $adapter 'private void updateInternal(boolean animated)'))
$template = $template.Replace('// PRODUCTION_PERSONAL_EDITION', (Method $policy 'public static boolean isPersonalEdition()'))
$template = $template.Replace('// PRODUCTION_PREVIEW_LABELS', ((@('public static String getRemoveTitle()', 'public static String getRemoveInfo()', 'public static String getRemovedMessage()') | ForEach-Object { Method $preview $_ }) -join "`n"))
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$probe = Join-Path $run 'LocalGiftsUi102Probe.java'
[IO.File]::WriteAllText($probe, $template, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probe
if ($LASTEXITCODE -ne 0) { throw 'Production-derived local gift UI probe compilation failed.' }
& $java -Xmx128m -cp $run LocalGiftsUi102Probe
if ($LASTEXITCODE -ne 0) { throw 'Production-derived local gift UI regression failed.' }
$negativeControls = @(
    @{ Name = 'queued-grid-span'; Pattern = '\.setSpanCount\(UItem\.MAX_SPAN_COUNT\)'; Replacement = '.setSpanCount(spanCount)'; Expected = 'Queued span growth must not abort after SPACE or poison future layout' },
    @{ Name = 'personal-unified-gate'; Pattern = 'final boolean unifiedGifts = LumaBuildPolicy\.isPersonalEdition\(\);'; Replacement = 'final boolean unifiedGifts = false;'; Expected = 'Only personal edition hides section headers during queued layout' },
    @{ Name = 'personal-unified-pins'; Pattern = 'if \(parent\.isGiftPinnedLocally\(gift\) != \(localPinnedPass == 1\)\) continue;'; Replacement = 'if (localPinnedPass != 0) continue;'; Expected = 'Personal unified grid places all local pins ahead of all unpinned entries' },
    @{ Name = 'personal-loading-remainder'; Pattern = 'if \(--spanCountLeft == 0\) spanCountLeft = spanCount;'; Expected = 'Personal unified loading remainder accounts for both local and real tiles' },
    @{ Name = 'cross-pin-groups'; Pattern = 'if \(parent\.isGiftPinnedLocally\(getSavedGift\(viewHolder\)\)\s*!= parent\.isGiftPinnedLocally\(getSavedGift\(target\)\)\) return false;'; Expected = 'Drag across local-pin display groups' },
    @{ Name = 'detached-holder'; Pattern = 'if \(fromPosition < 0 \|\| toPosition < 0\) return false;'; Expected = 'Detached holder NO_POSITION' },
    @{ Name = 'stale-owner-pin'; Pattern = 'localPinsOwner <= 0 \|\| localPinsOwner != UserConfig\.getInstance\(currentAccount\)\.getClientUserId\(\)\s*\|\|\s*'; Expected = 'Stale local pin callback' }
)
foreach ($control in $negativeControls) {
    $replacement = if ($control.ContainsKey('Replacement')) { $control.Replacement } else { '' }
    $negative = [regex]::Replace($template, $control.Pattern, $replacement)
    if ($negative -eq $template) { throw "Cannot remove production guard for negative control: $($control.Name)" }
    $negativeRun = Join-Path $run $control.Name
    New-Item -ItemType Directory -Path $negativeRun -Force | Out-Null
    $negativeSource = Join-Path $negativeRun 'LocalGiftsUi102Probe.java'
    [IO.File]::WriteAllText($negativeSource, $negative, [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $negativeRun $negativeSource
    if ($LASTEXITCODE -ne 0) { throw "Negative-control compilation failed: $($control.Name)" }
    $negativeOutput = (& $java -Xmx128m -cp $negativeRun LocalGiftsUi102Probe 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or !$negativeOutput.Contains($control.Expected)) { throw "Negative control did not reproduce its defect: $($control.Name)" }
}
Write-Output 'PASS: negative controls reproduce queued-grid SPACE-only/stuck-layout, personal section/pin/loading defects, cross-pin-group reorder, detached holder and stale-owner pin defects when their actual production guards are removed.'
if ($preview -match 'ConnectionsManager|sendRequest\(|TL_payments|saveStarGift|transferStarGift|resellStarGift') { throw 'Read-only local preview must not open server inventory/payment actions.' }
if (!(Method $preview 'private static void showPersonal(').Contains('.setLocalPreview(fragment, gift)')) { throw 'Native local sheet construction must use its isolated local-first binder.' }
$pureGiftVisuals = @('TopView', 'addAttributeRow', 'getRarityName')
$giftSheetReferences = @([regex]::Matches($preview, 'StarGiftSheet\.(\w+)') | ForEach-Object { $_.Groups[1].Value })
if (@($giftSheetReferences | Where-Object { $_ -notin $pureGiftVisuals }).Count -gt 0) { throw 'Only pure NFT header and static attribute/rarity formatting may be reused from the transaction sheet.' }
foreach ($guard in @('gift.ownerUserId != UserConfig.getInstance(account).getClientUserId()',
        '!(gift.gift instanceof TL_stars.TL_starGiftUnique)', 'getString(R.string.LumaLocalGiftPreviewInfo)',
        'TLRPC.User self = UserConfig.getInstance(account).getCurrentUser()', 'LumaLocalGifts.remove(account, gift)')) {
    if (!$preview.Contains($guard)) { throw "Missing local preview owner/type/identity guard: $guard" }
}
$click = Method $profile 'public void onItemClick('
$localBranch = $click.IndexOf('item.object instanceof LumaLocalGifts.LocalSavedGift')
$serverBranch = $click.IndexOf('item.object instanceof TL_stars.SavedStarGift')
if ($localBranch -lt 0 -or $localBranch -gt $serverBranch -or !$click.Contains('LumaLocalGiftPreview.show(')) { throw 'Local marker must be routed before the ordinary saved-gift transaction sheet.' }
$longPress = Method $profile 'public boolean onItemLongPress('
$localMenu = Method $longPress 'if (item.object instanceof LumaLocalGifts.LocalSavedGift)'
if ($localMenu -match 'sendRequest\(|ConnectionsManager|TL_payments|saveStarGift|transferStarGift|new\s+StarGiftSheet' -or !$localMenu.Contains('LumaLocalGiftPreview.getRemoveTitle()') -or !$localMenu.Contains('LumaLocalGiftPreview.confirmRemove(') -or
        !$localMenu.Contains('gift.ownerUserId != UserConfig.getInstance(currentAccount).getClientUserId()') -or !$localMenu.Contains('return true;')) { throw 'Unified local tile menu must keep marker/owner routing, edition-aware label and no real transaction actions.' }
if (!$preview.Contains('if (!LumaBuildPolicy.isPersonalEdition())') -or !$preview.Contains('local.setText(getString(R.string.LumaLocalGiftPreviewInfo))')) { throw 'Personal preview disclaimer may be hidden without changing the public disclaimer.' }
foreach ($path in @('TMessagesProj/src/main/res/values/strings.xml', 'TMessagesProj/src/main/res/values-ru/strings.xml')) {
    [xml]$strings = [IO.File]::ReadAllText((Join-Path $repo $path))
    foreach ($name in @('LumaPersonalGiftRemove', 'LumaPersonalGiftRemoveInfo', 'LumaPersonalGiftRemoved')) {
        $entry = $strings.resources.string | Where-Object { $_.name -eq $name }
        if (!$entry -or $entry.InnerText -match '(?i)local|локал') { throw "Personal preview label must exist without a local-section label: $name" }
    }
}
$localAdd = Method $sheet 'private void addGiftLocally('
if ($localAdd -match 'sendRequest\(|ConnectionsManager|TL_payments|saveStarGift|transferStarGift') { throw 'Local add must not reach server/payment APIs.' }
foreach ($guard in @('final long owner = UserConfig.getInstance(currentAccount).getClientUserId()',
        'owner == UserConfig.getInstance(currentAccount).getClientUserId()', 'LumaLocalGifts.add(currentAccount, owner, gift)',
        'owner != UserConfig.getInstance(currentAccount).getClientUserId()')) {
    if (!$localAdd.Contains($guard)) { throw "Missing asynchronous local-add identity guard: $guard" }
}
if (!$store.Contains('throw new UnsupportedOperationException("Local gift is not a Telegram transaction")')) { throw 'Local saved marker serialization must fail closed.' }
foreach ($source in @($profile)) {
    if (!$source.Contains('addObserver(this, NotificationCenter.lumaLocalGiftsChanged)') -or !$source.Contains('removeObserver(this, NotificationCenter.lumaLocalGiftsChanged)')) { throw 'Gift UI local-change observer must be balanced.' }
}
if ([regex]::Matches($activity, 'hasLocalGiftsForProfile\(\)').Count -lt 3 -or !$shared.Contains('hasLocalGiftsForDialog(profileActivity.getCurrentAccount(), dialog_id)') -or [regex]::Matches($shared, 'hasLocalGiftsForDialog\(parent.getCurrentAccount\(\), did\)').Count -ne 2) { throw 'Profile and shared-media gift tabs must consume self-only availability helpers.' }
foreach ($helper in @((Method $activity 'private boolean hasLocalGiftsForProfile()'), (Method $shared 'private static boolean hasLocalGiftsForDialog('))) {
    if (!$helper.Contains('LumaLocalGifts.hasAnyForUi(') -or $helper -match 'LumaLocalGifts\.(hasAny|count)\(') { throw 'Gift-tab UI helper must use cheap asynchronous availability, never cold synchronous TL/Keystore parsing.' }
}
$profileEvent = Method $activity 'else if (id == NotificationCenter.lumaLocalGiftsChanged)'
$sharedEvent = Method $shared 'else if (id == NotificationCenter.lumaLocalGiftsChanged)'
if (!$profileEvent.Contains('account != currentAccount') -or !$sharedEvent.Contains('account != profileActivity.getCurrentAccount()')) { throw 'Gift-tab events must ignore mismatched account.' }
foreach ($source in @($profileEvent, $sharedEvent)) {
    foreach ($guard in @('args == null', 'args.length != 1', '!(args[0] instanceof Long)', 'owner <= 0')) {
        if (!$source.Contains($guard)) { throw "Gift-tab event missing malformed/owner guard: $guard" }
    }
}
$reload = Method $profile 'private void reloadLocalGifts()'
foreach ($guard in @('Utilities.globalQueue.postRunnable(', 'generation != localGiftsLoadGeneration',
        'owner != UserConfig.getInstance(currentAccount).getClientUserId()')) {
    if (!$reload.Contains($guard)) { throw "Missing asynchronous local gift reload guard: $guard" }
}
Write-Output 'PASS: local click routing, current owner/avatar/type, read-only preview, async identity, no network/payment and balanced local-change observers source guards.'
Write-Output "Test artifacts: $run"
& (Join-Path $PSScriptRoot 'run-local-gift-preview-ui105-regressions.ps1') -JavaHome $JavaHome -OutputRoot (Join-Path $OutputRoot 'preview')
& (Join-Path $PSScriptRoot 'run-local-gifts-state102-regressions.ps1') -JavaHome $JavaHome -OutputRoot (Join-Path $OutputRoot 'state')
