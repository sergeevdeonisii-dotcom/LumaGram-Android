param([string]$JavaHome=$env:JAVA_HOME,
    [switch]$CheckCachedApi,
    [string]$AndroidJar='D:/CodexTools/Lunagram-Android-SDK/platforms/android-36/android.jar',
    [string]$DependencyCache='D:/CodexBuildCache/gradle-user-caches-20261006/8.13/transforms',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/subscription-colors201'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.runs'))+[IO.Path]::DirectorySeparatorChar
$output=[IO.Path]::GetFullPath($OutputRoot)
$inRunnerTemp=$env:RUNNER_TEMP -and $output.StartsWith([IO.Path]::GetFullPath($env:RUNNER_TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
if (!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -and !$inRunnerTemp) { throw 'Color test output must stay in Tools/tests/.runs or configured runner temporary storage.' }
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
$methods=(@('private String currentPlanText()', 'private String currentPlanId()',
    'private void updateCurrentPlanLabelColor()', 'private static int currentPlanLabelColor(',
    'public ArrayList<ThemeDescription> getThemeDescriptions()')|ForEach-Object{Method $_}) -join "`n"
if($methods -match 'selectedPlan|selectedPeriod|client\.(refresh|confirm|preview|disconnect)\('){throw 'Current label must only read the active subscription, not purchase/activation state.'}
$colorUpdate=Method 'private void updateCurrentPlanLabelColor()'
if(!$colorUpdate.Contains('Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider)') -or $colorUpdate.Contains('Theme.key_windowBackgroundWhite,')){throw 'Transparent hero contrast must use the outer window (Gray), not section White.'}
$fill=Method 'private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter)'
if(!$fill.Contains('currentPlanLabel.setText(currentPlanText());') -or !$fill.Contains('updateCurrentPlanLabelColor();')){throw 'Text and color must refresh together when subscription rows rebuild.'}
$changed=Method 'private void onSubscriptionChanged()'
if(!$changed.Contains('listView.adapter.update(false)')){throw 'Server refresh must rebuild header display.'}
$boundary=[regex]::Match($screen,'(?s)private final Runnable termBoundary = \(\) -> \{.*?\n    \};').Value
if(!$boundary.Contains('listView.adapter.update(false)')){throw 'Expiry boundary must rebuild header display.'}
$run=Join-Path $output ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force|Out-Null
$fixture=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'subscriptions-colors201/SubscriptionColors201Probe.java.template')).Replace('/* ACTUAL_METHODS */',$methods)
$probe=Join-Path $run 'SubscriptionColors201Probe.java'
[IO.File]::WriteAllText($probe,$fixture,[Text.UTF8Encoding]::new($false))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix);$java=Join-Path $JavaHome ('bin/java'+$suffix)
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probe (Join-Path $PSScriptRoot 'subscriptions-colors201/ColorUtils.java') (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionCatalog.java')
if($LASTEXITCODE -ne 0){throw 'Actual-extracted subscription color methods compilation failed.'}
& $java -Xmx128m -cp $run SubscriptionColors201Probe
if($LASTEXITCODE -ne 0){throw 'Subscription color behavior failed.'}
if($CheckCachedApi){
    $cached=Join-Path $repo 'TMessagesProj/build/intermediates/compile_library_classes_jar/standalone/bundleLibCompileToJarStandalone/classes.jar'
    if(!(Test-Path -LiteralPath $cached) -or !(Test-Path -LiteralPath $AndroidJar)){throw 'Cached production classes and Android36 are required.'}
    $api=Join-Path $run 'api'; New-Item -ItemType Directory -Path $api -Force|Out-Null
    $jars=@($cached,$AndroidJar)
    $jars+=@(Get-ChildItem -LiteralPath $DependencyCache -Recurse -File -Filter '*.jar'|ForEach-Object FullName)
    $jars+=@(Get-ChildItem -LiteralPath (Join-Path $repo 'TMessagesProj_Modules') -Recurse -File -Filter 'classes.jar'|Where-Object FullName -Match 'compile_library_classes_jar'|ForEach-Object FullName)
    # Resource identifiers are modeled here; real Android/AndroidX/Telegram method signatures are not.
    $r='package org.telegram.messenger; public final class R {'; $id=1
    foreach($group in ([regex]::Matches($screen,'R\.(\w+)\.(\w+)')|Group-Object {$_.Groups[1].Value})){
        $r+='public static final class '+$group.Name+' {'
        foreach($field in ($group.Group|ForEach-Object {$_.Groups[2].Value}|Sort-Object -Unique)){$r+='public static final int '+$field+'='+($id++)+';'}
        $r+='}'
    }
    $r+='}'; $resourceFile=Join-Path $api 'R.java'
    [IO.File]::WriteAllText($resourceFile,$r,[Text.UTF8Encoding]::new($false))
    $argsPath=Join-Path $api 'javac.args'
    $compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','20','-classpath',('"'+(($jars-join [IO.Path]::PathSeparator).Replace('\','/'))+'"'),'-d',('"'+$api.Replace('\','/')+'"'))
    $compilerArgs+='"'+(Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramSubscriptionActivity.java').Replace('\','/')+'"'
    foreach($source in @('messenger/LunagramSubscriptionCatalog.java','messenger/LunagramFeatureCatalog.java',
        'messenger/LunagramSubscriptionClient.java','ui/Components/Premium/GLIcon/LunagramLogoMesh.java')) {
        $compilerArgs+='"'+(Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/'+$source)).Replace('\','/')+'"'
    }
    $compilerArgs+='"'+$resourceFile.Replace('\','/')+'"'
    [IO.File]::WriteAllLines($argsPath,$compilerArgs,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx256m ('@'+$argsPath)
    if($LASTEXITCODE -ne 0){throw 'Subscription screen real Android/cached production API compilation failed.'}
    Write-Output 'PASS: full subscription screen compiled against Android36, real AndroidX color APIs and cached Telegram classes; resource IDs modeled, no APK built.'
}
Write-Output "Test artifacts: $run"
