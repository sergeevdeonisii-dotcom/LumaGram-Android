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
$signatures=@('public void onResume()', 'public void onPause()', 'private void onSubscriptionChanged()',
    'private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter)', 'private String connectionText()',
    'private String connectionBlockText()', 'private String periodTitle(Period period)',
    'private String totalPrice(Plan plan, Period period)', 'private String planTitle(String planId)',
    'private String formattedDate(long timestamp)', 'private String plannedFeaturesText(Plan plan)',
    'private int featureResource(Feature feature)', 'private String subscriptionFooter()',
    'private void scheduleTermBoundary()', 'private String errorText()', 'private void updateActivationDialog()')
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
[IO.File]::WriteAllText($resources,'final class R {static final class string {'+$fields+'}} final class Resources {static String name(int id){switch(id){'+$cases+'default:throw new IllegalArgumentException();}}}',[Text.UTF8Encoding]::new($false))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix);$java=Join-Path $JavaHome ('bin/java'+$suffix)
$sources=@($probe,$resources,(Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionCatalog.java'),(Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeatureCatalog.java'))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $sources
if($LASTEXITCODE -ne 0){throw 'Actual-extracted subscription screen probe compilation failed.'}
& $java -Xmx128m -cp $run SubscriptionScreen150Probe
if($LASTEXITCODE -ne 0){throw 'Actual-extracted subscription screen behavior failed.'}
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
if(!$itemClick.Contains('selectedPeriod = LunagramSubscriptionCatalog.PERIODS.get(') -or !$itemClick.Contains('purchaseUrl(selectedPlan, selectedPeriod)')){throw 'Duration selection must reach only the client-owned trusted purchase URL.'}
$featureBody=Method 'private String plannedFeaturesText(Plan plan)'
if(!$featureBody.Contains('LunagramFeatureCatalog.includes') -or !$featureBody.Contains('isBaselineFree')){throw 'Plan feature text must use cumulative pure catalog and exclude free baseline.'}
foreach($locale in @('values','values-ru')){
    $strings=[IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    foreach($key in $keys|Where-Object{$_ -like 'LunagramSubscription*'}){
        if([regex]::Matches($strings,'name="'+[regex]::Escape($key)+'"').Count -ne 1){throw "Missing/duplicate $locale string: $key"}
    }
}
Write-Output 'PASS: duration purchase delegation, preview/explicit confirmation, preserved error input, lifecycle/boundary guards and EN/RU keys.'
Write-Output 'Scope: extracted actual methods with modeled native cells/client; no entitlement gates, network, APK or device operations.'
Write-Output "Test artifacts: $run"
