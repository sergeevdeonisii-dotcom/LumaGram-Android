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
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$probe = Join-Path $run 'LocalGiftsUi102Probe.java'
[IO.File]::WriteAllText($probe, $template, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probe
if ($LASTEXITCODE -ne 0) { throw 'Production-derived local gift UI probe compilation failed.' }
& $java -Xmx128m -cp $run LocalGiftsUi102Probe
if ($LASTEXITCODE -ne 0) { throw 'Production-derived local gift UI regression failed.' }
$negativeControls = @(
    @{ Name = 'cross-pin-groups'; Pattern = 'if \(parent\.isGiftPinnedLocally\(getSavedGift\(viewHolder\)\)\s*!= parent\.isGiftPinnedLocally\(getSavedGift\(target\)\)\) return false;'; Expected = 'Drag across local-pin display groups' },
    @{ Name = 'detached-holder'; Pattern = 'if \(fromPosition < 0 \|\| toPosition < 0\) return false;'; Expected = 'Detached holder NO_POSITION' },
    @{ Name = 'stale-owner-pin'; Pattern = 'localPinsOwner <= 0 \|\| localPinsOwner != UserConfig\.getInstance\(currentAccount\)\.getClientUserId\(\)\s*\|\|\s*'; Expected = 'Stale local pin callback' }
)
foreach ($control in $negativeControls) {
    $negative = [regex]::Replace($template, $control.Pattern, '')
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
Write-Output 'PASS: negative controls reproduce cross-pin-group reorder, detached holder and stale-owner pin defects when their actual production guards are removed.'
if ($preview -match 'ConnectionsManager|new\s+StarGiftSheet|sendRequest\(|TL_payments|saveStarGift|transferStarGift|resellStarGift') { throw 'Read-only local preview must not open server inventory/payment actions.' }
if ([regex]::Matches($preview, 'StarGiftSheet\.').Count -ne 1 -or !$preview.Contains('StarGiftSheet.getRarityName(')) { throw 'Only the pure static rarity formatter may be reused from the transaction sheet.' }
foreach ($guard in @('gift.ownerUserId != UserConfig.getInstance(account).getClientUserId()',
        '!(gift.gift instanceof TL_stars.TL_starGiftUnique)', 'getString(R.string.LumaLocalGiftPreviewInfo)',
        'TLRPC.User self = UserConfig.getInstance(account).getCurrentUser()', 'LumaLocalGifts.remove(account, gift)')) {
    if (!$preview.Contains($guard)) { throw "Missing local preview owner/type/identity guard: $guard" }
}
$click = Method $profile 'public void onItemClick('
$localBranch = $click.IndexOf('item.object instanceof LumaLocalGifts.LocalSavedGift')
$serverBranch = $click.IndexOf('item.object instanceof TL_stars.SavedStarGift')
if ($localBranch -lt 0 -or $localBranch -gt $serverBranch -or !$click.Contains('LumaLocalGiftPreview.show(')) { throw 'Local marker must be routed before the ordinary saved-gift transaction sheet.' }
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
& (Join-Path $PSScriptRoot 'run-local-gifts-state102-regressions.ps1') -JavaHome $JavaHome -OutputRoot (Join-Path $OutputRoot 'state')
