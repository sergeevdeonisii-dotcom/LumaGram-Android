param([string]$JavaHome=$env:JAVA_HOME,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/subscription-screen150'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.runs'))+[IO.Path]::DirectorySeparatorChar
$output=[IO.Path]::GetFullPath($OutputRoot)
$inRunnerTemp=$env:RUNNER_TEMP -and $output.StartsWith([IO.Path]::GetFullPath($env:RUNNER_TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
if (!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -and !$inRunnerTemp) { throw 'Screen test output must stay in Tools/tests/.runs or configured runner temporary storage.' }
$screen=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramSubscriptionActivity.java'))
function Method([string]$signature) {
    $start=$screen.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing screen method: $signature"}
    $open=$screen.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $screen.Length -and $depth -gt 0;$end++){
        if($screen[$end] -eq '{'){$depth++};if($screen[$end] -eq '}'){$depth--}
    }
    if($depth -ne 0){throw "Unbalanced screen method: $signature"}
    return $screen.Substring($start,$end-$start)
}
$signatures=@('public void onResume()', 'public void onPause()', 'public void onFragmentDestroy()', 'private void onSubscriptionChanged()',
    'private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter)',
    'private void onItemClick(UItem item, View view, int position, float x, float y)',
    'private void showPeriodDialog()', 'private String periodPrice(Plan plan, Period period)',
    'private void openSubscriptionBot()', 'private void fillCryptoItems(ArrayList<UItem> items)',
    'private String cryptoStatusText(LunagramSubscriptionClient.CryptoState state)', 'private void openCryptoCheckout()', 'private void showCryptoTerms()',
    'private void showCryptoCheckoutError()',
    'private String connectionText()',
    'private String connectionBlockText()', 'private String periodTitle(Period period)',
    'private String totalPrice(Plan plan, Period period)', 'private String planTitle(String planId)',
    'private String formattedDate(long timestamp)', 'private String plannedFeaturesText(Plan plan)',
    'private int featureResource(Feature feature)', 'private int featureInfoResource(Feature feature)', 'private int featureSummaryResource(Feature feature)',
    'private int featureIcon(Feature feature)', 'private int featureColor(Feature feature)',
    'private String compactSubscriptionFooter()', 'private String currentPlanText()', 'private String currentPlanId()',
    'private void updateCurrentPlanLabelColor()', 'private static int currentPlanLabelColor(', 'private String subscriptionFooter()',
    'private void scheduleTermBoundary()', 'private String errorText()', 'private void updateActivationDialog()',
    'private static boolean hasVisibleLogoFrame(Bitmap frame)', 'private void verifyLogoFrame()', 'private void updateLogoState()')
$methods=($signatures|ForEach-Object{Method $_}) -join "`n"
$boundary=[regex]::Match($screen,'(?s)private final Runnable termBoundary = \(\) -> \{.*?\n    \};').Value
if(!$boundary){throw 'Missing boundary owner callback.'}
$fixture=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'subscriptions-ui150/SubscriptionScreen150Probe.java.template'))
$fixture=$fixture.Replace('/* ACTUAL_BOUNDARY */',$boundary).Replace('/* ACTUAL_METHODS */',$methods)
$run=Join-Path $output ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force|Out-Null
$probe=Join-Path $run 'SubscriptionScreen150Probe.java'
[IO.File]::WriteAllText($probe,$fixture,[Text.UTF8Encoding]::new($false))
$keys=@([regex]::Matches($screen,'R\.string\.(\w+)')|ForEach-Object{$_.Groups[1].Value}|Sort-Object -Unique)
$fields='';$cases='';$id=1
foreach($key in $keys){$fields+="static final int $key=$id;";$cases+="case ${id}:return `"$key`";";$id++}
$resources=Join-Path $run 'Resources.java'
$drawableFields=''
foreach($name in @([regex]::Matches($screen,'R\.drawable\.(\w+)')|ForEach-Object{$_.Groups[1].Value}|Sort-Object -Unique)){$drawableFields+='static final int '+$name+'='+($id++)+';'}
[IO.File]::WriteAllText($resources,'final class R {static final class string {'+$fields+'}static final class drawable {'+$drawableFields+'}} final class Resources {static String name(int id){switch(id){'+$cases+'default:throw new IllegalArgumentException();}}}',[Text.UTF8Encoding]::new($false))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix);$java=Join-Path $JavaHome ('bin/java'+$suffix)
$sources=@($probe,$resources,(Join-Path $PSScriptRoot 'subscriptions-colors201/ColorUtils.java'),(Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionCatalog.java'),(Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeatureCatalog.java'))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $sources
if($LASTEXITCODE -ne 0){throw 'Actual-extracted subscription screen probe compilation failed.'}
& $java -Xmx128m -cp $run SubscriptionScreen150Probe
if($LASTEXITCODE -ne 0){throw 'Actual-extracted subscription screen behavior failed.'}
# Only generated test copies are mutated. These controls prove that the tests
# distinguish transparent first-frame pausing, historical-price rewrites,
# and the real custom duration dialog's callback guards/selected value.
$mutations=@(
    @{Name='faded-logo';From='if (Color.alpha(pixel) < 240) continue;';To='if (false) continue;'},
    @{Name='early-reduced-motion-pause';From='!visible || (!motion && logoReady)';To='!visible || !motion'},
    @{Name='historical-quote-repriced';From='quote.starsAmount,';To='selectedPlan.totalStars(selectedPeriod),'},
    @{Name='duration-selection-ignored';From='selectedPeriod = period;';To='selectedPeriod = Period.MONTH_1;'},
    @{Name='duration-stale-callback';From="builder.getDismissRunnable().run();`n                if (client == null || !client.isAccountCurrent() || isPaused || isFinished) return;";To="builder.getDismissRunnable().run();`n                if (false) return;"},
    @{Name='loading-after-failure';From='statusLoading && client != null && client.isBusy() && client.isAccountCurrent()';To='statusLoading && client != null && client.isAccountCurrent()'},
    @{Name='generic-busy-as-status';From='statusLoading && client != null && client.isBusy()';To='client != null && client.isBusy()'},
    @{Name='missing-restore-loading';From='else { statusRequested = false; statusLoading = true; client.restore(); }';To='else { statusRequested = false; client.restore(); }'},
    @{Name='missing-round-date-row';From='case ROUND_VIDEO_DATE: return R.string.LunagramSubscriptionFeatureRoundDate;';To='case ROUND_VIDEO_DATE: return 0;'},
    @{Name='generic-round-date-info';From='case ROUND_VIDEO_DATE: return R.string.LunagramSubscriptionInfoRoundDate;';To='case ROUND_VIDEO_DATE: return R.string.LunagramSubscriptionInfoLocal;'},
    @{Name='duplicate-round-date-icon';From='case ROUND_VIDEO_DATE: return R.drawable.msg_calendar;';To='case ROUND_VIDEO_DATE: return R.drawable.msg_camera;'},
    @{Name='missing-text-tools-row';From='case TEXT_TOOLS: return R.string.LulPlanText;';To='case TEXT_TOOLS: return 0;'},
    @{Name='generic-reader-info';From='case READING_TOOLS: return R.string.LulPlanReadingInfo;';To='case READING_TOOLS: return R.string.LunagramSubscriptionInfoLocal;'},
    @{Name='unabridged-workspace-summary';From='case LOCAL_WORKSPACE: return R.string.LulPlanWorkspaceShort;';To='case LOCAL_WORKSPACE: return R.string.LulPlanWorkspaceInfo;'}
)
$fixture=$fixture.Replace("`r`n","`n")
foreach($mutation in $mutations){
    if(!$fixture.Contains($mutation.From)){throw "Negative control source missing: $($mutation.Name)"}
    $negative=Join-Path $run $mutation.Name
    New-Item -ItemType Directory -Path $negative -Force|Out-Null
    $negativeProbe=Join-Path $negative 'SubscriptionScreen150Probe.java'
    [IO.File]::WriteAllText($negativeProbe,$fixture.Replace($mutation.From,$mutation.To),[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -cp $run -d $negative $negativeProbe
    if($LASTEXITCODE -ne 0){throw "Negative control must compile: $($mutation.Name)"}
    $negativeOutput=& $java -Xmx128m -cp ($negative+[IO.Path]::PathSeparator+$run) SubscriptionScreen150Probe 2>&1
    if($LASTEXITCODE -eq 0){throw "Negative control unexpectedly passed: $($mutation.Name)"}
    [IO.File]::WriteAllLines((Join-Path $negative 'expected-failure.txt'),[string[]]$negativeOutput,[Text.UTF8Encoding]::new($false))
}
Write-Output 'PASS: fourteen negative controls reject logo/quote/duration regressions, incorrect loading states and omitted/generic/duplicate feature rows or verbose new summaries.'
$activation=Method 'private void showActivationDialog()'
foreach($required in @('client.previewCode(code);','client.confirmCode();','client.clearQuote();',
        'if (client != null && client.quote() != null) return true;','field.setSaveEnabled(false)',
        'IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS','IME_FLAG_NO_PERSONALIZED_LEARNING')){
    if(!$activation.Contains($required)){throw "Missing code UI safety seam: $required"}
}
if($activation -match '(?s)client\.previewCode\(code\);\s*field\.setText\(""\)' -or $activation.Contains('quote.validUntilMs')){
    throw 'Preview cannot clear input; UI cannot block client-owned idempotent confirmation expiry.'
}
if(!(Method 'public void onFragmentDestroy()').Contains('cancelRunOnUIThread(termBoundary)')){throw 'Destroy must cancel boundary timer.'}
$itemClick=Method 'private void onItemClick(UItem item, View view, int position, float x, float y)'
if(!$screen.Contains('private final Plan selectedPlan = Plan.ULTRA;') -or $screen.Contains('ROW_SELECT_PLAN')){throw 'Only Ultra may be offered for new purchases.'}
foreach($required in @('onSurfaceTextureUpdated(SurfaceTexture surface)', 'logoProbeFrames >= 32',
    'LunagramLogoMesh.create(part)', 'logoFallback.setVisibility(View.INVISIBLE)', 'UItem.asCustomShadow(subscriptionHeader)')) {
    if(!$screen.Contains($required)){throw "Missing visible-first-frame/fallback contract: $required"}
}
if($screen.Contains('whenReady(')){throw 'First EGL draw is not proof of a visible logo.'}
foreach($required in @('++logoProbeFrames % 4', 'if (frame == null) return false;', 'title.setTextSize(16)',
    'description.setTextSize(14)', 'LocaleController.isRTL ? 42 : start', 'FeatureCell.ChoiceFactory.of(ROW_SELECT_PERIOD')) {
    if(!$screen.Contains($required)){throw "Missing bounded readback or scalable localized cell: $required"}
}
$periodDialog=Method 'private void showPeriodDialog()'
if(!$itemClick.Contains('showPeriodDialog();') -or !$periodDialog.Contains('selectedPeriod = period;') -or !$itemClick.Contains('purchaseUrl(selectedPlan, selectedPeriod)')){throw 'Duration selection must reach only the client-owned trusted purchase URL.'}
$cryptoOpen=Method 'private void openCryptoCheckout()'
if(!$cryptoOpen.Contains('getUserNameResolver().resolve("CryptoBot", null, true, peerId ->') -or
    !$cryptoOpen.Contains('chat.setCurrentAccount(checkoutAccount)') -or
    !$cryptoOpen.Contains('navigation != cryptoBotNavigation') -or
    $cryptoOpen -match 'Browser\.|openInExternal|openUrlInSystemBrowser|openInTelegramBrowser') {
    throw 'Crypto invoice must use account-pinned native bot dispatch with lifecycle guards and no browser fallback.'
}
$featureBody=Method 'private String plannedFeaturesText(Plan plan)'
if(!$featureBody.Contains('LunagramFeatureCatalog.includes') -or !$featureBody.Contains('isBaselineFree')){throw 'Plan feature text must use cumulative pure catalog and exclude free baseline.'}
foreach($locale in @('values','values-ru')){
    $strings=[IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    foreach($dedicated in Get-ChildItem -LiteralPath (Join-Path $repo "TMessagesProj/src/main/res/$locale") -Filter 'lunagram_*.xml'){
        $strings += [IO.File]::ReadAllText($dedicated.FullName)
    }
    foreach($key in $keys|Where-Object{$_ -like 'LunagramSubscription*' -or $_ -like 'LulPlan*'}){
        if([regex]::Matches($strings,'name="'+[regex]::Escape($key)+'"').Count -ne 1){throw "Missing/duplicate $locale string: $key"}
    }
}
Write-Output 'PASS: duration purchase delegation, preview/explicit confirmation, preserved error input, lifecycle/boundary guards and EN/RU keys.'
Write-Output 'Scope: extracted actual methods with modeled native cells/client; no entitlement gates, network, APK or device operations.'
Write-Output "Test artifacts: $run"
