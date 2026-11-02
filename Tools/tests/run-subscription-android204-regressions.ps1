param(
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$DependencyCache='D:/CodexBuildCache/gradle-user-caches-20261006/modules-2/files-2.1',
    [string]$FrameworkDirectory='C:/Users/denis228/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-12650502-i7',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/subscription-android204')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.runs'))+[IO.Path]::DirectorySeparatorChar
$output=[IO.Path]::GetFullPath($OutputRoot)
$inRunnerTemp=$env:RUNNER_TEMP -and $output.StartsWith([IO.Path]::GetFullPath($env:RUNNER_TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
if (!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -and !$inRunnerTemp) { throw 'Output must stay in test .runs or configured RUNNER_TEMP.' }
$java=Join-Path $JavaHome 'bin/java.exe';$javac=Join-Path $JavaHome 'bin/javac.exe'
if (!(Test-Path -LiteralPath $javac)) { throw 'An existing JDK17 is required; this runner never downloads it.' }
$framework=Join-Path $FrameworkDirectory 'android-all-instrumented-15-robolectric-12650502-i7.jar'
if (!(Test-Path -LiteralPath $framework)) { throw "Offline API35 Android framework absent: $framework" }
function Cached([string]$group,[string]$artifact,[string]$version,[string]$extension='jar') {
    $folder=Join-Path $DependencyCache "$group/$artifact/$version"
    $matches=@(Get-ChildItem -LiteralPath $folder -Recurse -Filter "$artifact-$version.$extension" -ErrorAction SilentlyContinue)
    if ($matches.Count -ne 1) { throw "Exactly one cached dependency required (no download): ${group}:${artifact}:${version}:$extension" }
    return $matches[0].FullName
}
$jars=@($framework)
foreach($artifact in @('annotations','junit','pluginapi','resources','sandbox','utils','utils-reflector','plugins-maven-dependency-resolver','shadows-framework','shadowapi','robolectric','nativeruntime')) {
    $jars+=Cached 'org.robolectric' $artifact '4.14.1'
}
$jars+=Cached 'org.robolectric' 'nativeruntime-dist-compat' '1.0.16'
foreach($coordinate in @(
    @('com.google.guava','guava','33.3.1-jre'),@('com.google.guava','failureaccess','1.0.2'),
    @('javax.inject','javax.inject','1'),@('javax.annotation','javax.annotation-api','1.3.2'),
    @('junit','junit','4.13.2'),@('org.hamcrest','hamcrest-core','1.3'),
    @('org.bouncycastle','bcprov-jdk18on','1.78.1'),@('org.conscrypt','conscrypt-openjdk-uber','2.5.2'),
    @('org.yaml','snakeyaml','2.3'),@('com.google.testparameterinjector','test-parameter-injector','1.18'),
    @('com.almworks.sqlite4java','sqlite4java','1.0.392'),@('com.ibm.icu','icu4j','75.1'),
    @('com.google.auto.value','auto-value-annotations','1.11.0'),@('com.google.errorprone','error_prone_annotation','2.34.0')
)) { $jars+=Cached $coordinate[0] $coordinate[1] $coordinate[2] }
foreach($artifact in @('asm','asm-commons','asm-tree','asm-util','asm-analysis')) { $jars+=Cached 'org.ow2.asm' $artifact '9.7.1' }
$runId=[guid]::NewGuid().ToString('N')
$run=Join-Path $output $runId
New-Item -ItemType Directory -Path $run -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach($aar in @((Cached 'androidx.test' 'monitor' '1.7.2' 'aar'),(Cached 'androidx.test.espresso' 'espresso-idling-resource' '3.6.1' 'aar'))) {
    $zip=[IO.Compression.ZipFile]::OpenRead($aar)
    try {
        $classes=$zip.GetEntry('classes.jar')
        if (!$classes) { throw "No classes.jar in cached AAR: $aar" }
        $target=Join-Path $run ([IO.Path]::GetFileNameWithoutExtension($aar)+'.jar')
        [IO.Compression.ZipFileExtensions]::ExtractToFile($classes,$target)
        $jars+=$target
    } finally { $zip.Dispose() }
}
$screen=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramSubscriptionActivity.java'))
function Extract-Class([string]$signature) {
    $start=$screen.IndexOf($signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Production class absent: $signature" }
    $open=$screen.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $screen.Length -and $depth -gt 0;$end++) {
        if($screen[$end] -eq '{'){$depth++};if($screen[$end] -eq '}'){$depth--}
    }
    if($depth -ne 0){throw "Unbalanced production class: $signature"}
    return $screen.Substring($start,$end-$start)
}
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'subscription-android204/SubscriptionCells.java.template'))
$featureSource=Extract-Class 'public static final class FeatureCell'
$periodSource=Extract-Class 'public static final class PeriodChoiceCell'
$headerSource=Extract-Class 'public static final class HeaderStatusCell'
function JavaString([string]$value) { return '"'+$value.Replace('\','\\').Replace('"','\"').Replace("`r",'\r').Replace("`n",'\n')+'"' }
$statusRows=@()
$featureRows=@()
$featureTitles=@([regex]::Matches((Extract-Class 'private int featureResource(Feature feature)'), 'case (\w+): return R\.string\.(\w+);'))
$featureInfo=@{}
foreach($entry in [regex]::Matches((Extract-Class 'private int featureInfoResource(Feature feature)'), 'case (\w+): return R\.string\.(\w+);')) {
    $featureInfo[$entry.Groups[1].Value]=$entry.Groups[2].Value
}
if($featureTitles.Count -ne 39 -or $featureInfo.Count -ne 39){throw 'Native copy probe requires all thirty-nine paid groups.'}
# New feature groups use concise rows; detailed capability lists remain in tap dialogs.
$featureSummaries=@{} + $featureInfo
foreach($entry in [regex]::Matches((Extract-Class 'private int featureSummaryResource(Feature feature)'), 'case (\w+): return R\.string\.(\w+);')) {
    $featureSummaries[$entry.Groups[1].Value]=$entry.Groups[2].Value
}
foreach($locale in @('values','values-ru')) {
    $strings=[IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    foreach($dedicated in Get-ChildItem -LiteralPath (Join-Path $repo "TMessagesProj/src/main/res/$locale") -Filter 'lunagram_*.xml') {
        $strings += [IO.File]::ReadAllText($dedicated.FullName)
    }
    $statusValues=@()
    foreach($key in @('LunagramSubscriptionCheckingStatus','LunagramSubscriptionUltraActive','LunagramSubscriptionFreeTier','LunagramSubscriptionPersonalTier','LunagramSubscriptionLastConfirmed')) {
        $matches=[regex]::Matches($strings,'<string name="'+$key+'">(.*?)</string>')
        if($matches.Count -ne 1){throw "Exactly one $locale resource required: $key"}
        $statusValues+=JavaString ([Net.WebUtility]::HtmlDecode($matches[0].Groups[1].Value))
    }
    $statusRows+='{'+($statusValues -join ',')+'}'
    $localizedFeatures=@()
    foreach($feature in $featureTitles) {
        $featureValues=@()
        foreach($key in @($feature.Groups[2].Value, $featureSummaries[$feature.Groups[1].Value])) {
            $matches=[regex]::Matches($strings,'<string name="'+$key+'">(.*?)</string>')
            if($matches.Count -ne 1){throw "Exactly one $locale feature resource required: $key"}
            $featureValues+=JavaString ([Net.WebUtility]::HtmlDecode($matches[0].Groups[1].Value))
        }
        $localizedFeatures+='{'+($featureValues -join ',')+'}'
        $detailKey=$featureInfo[$feature.Groups[1].Value]
        if([regex]::Matches($strings,'<string name="'+$detailKey+'">(.*?)</string>').Count -ne 1){throw "Exactly one $locale detail resource required: $detailKey"}
    }
    $featureRows+='{'+($localizedFeatures -join ',')+'}'
}
$template=$template.Replace('/* ACTUAL_HEADER_STATUS_CELL */',$headerSource).Replace('/* ACTUAL_HEADER_LABELS */',('static final String[][] VALUES={'+($statusRows -join ',')+'};'))
$template=$template.Replace('/* ACTUAL_FEATURE_LABELS */',('static final String[][][] VALUES={'+($featureRows -join ',')+'};'))
$source=$template.Replace('/* ACTUAL_FEATURE_CELL */',$featureSource)
$source=$source.Replace('/* ACTUAL_PERIOD_CELL */',$periodSource)
$probe=Join-Path $run 'SubscriptionCells.java'
[IO.File]::WriteAllText($probe,$source,[Text.UTF8Encoding]::new($false))
$classes=Join-Path $run 'classes';New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpath=($jars -join [IO.Path]::PathSeparator)
& $javac -J-Xmx256m -encoding UTF-8 -cp $classpath -d $classes $probe (Join-Path $PSScriptRoot 'subscription-android204/SubscriptionAndroid204Test.java')
if ($LASTEXITCODE -ne 0) { throw 'Actual-cell Android harness compilation failed.' }
$runtimeClasspath=$classes+[IO.Path]::PathSeparator+$classpath
$temp=Join-Path $run 'native-temp';New-Item -ItemType Directory -Path $temp -Force | Out-Null
$previewDirectory=Join-Path $PSScriptRoot ".runs/subscription-android204/$runId"
New-Item -ItemType Directory -Path $previewDirectory -Force | Out-Null
$preview=Join-Path $previewDirectory 'native-cell-geometry.png'
$statusPreview=Join-Path $previewDirectory 'native-header-states.png'
$runtimeOptions=@('-Xmx512m',"-Djava.io.tmpdir=$temp",'-Drobolectric.offline=true',"-Drobolectric.dependency.dir=$FrameworkDirectory",'-Drobolectric.enabledSdks=35')
& $java @runtimeOptions "-Dsubscription.geometry.preview=$preview" "-Dsubscription.status.preview=$statusPreview" -cp $runtimeClasspath org.junit.runner.JUnitCore org.telegram.ui.SubscriptionAndroid204Test | Tee-Object -FilePath (Join-Path $run 'measurement-pass.txt')
if ($LASTEXITCODE -ne 0) { throw 'Real Android cell measurement failed; do not treat as a modeled pass.' }
$periodHeight='MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)'
if (!$periodSource.Contains($periodHeight) -or !$featureSource.Contains('MeasureSpec.EXACTLY')) { throw 'Production negative-control target drifted.' }
$controls=@(
    @{Name='intrinsic-feature-width';Feature=$featureSource.Replace('MeasureSpec.EXACTLY','MeasureSpec.AT_MOST');Period=$periodSource;Failure='full row width'},
    @{Name='fixed-period-height';Feature=$featureSource;Period=$periodSource.Replace($periodHeight,'MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64), MeasureSpec.EXACTLY)');Failure='text fits vertically|no glyph cutoff'},
    @{Name='unreserved-header-states';Feature=$featureSource;Period=$periodSource;MutationFrom='reservation.setVisibility(View.INVISIBLE);';MutationTo='reservation.setVisibility(View.GONE);';Failure='reserved compact states|stable header'}
)
foreach($control in $controls) {
    $negative=Join-Path $run $control.Name;New-Item -ItemType Directory -Path $negative -Force | Out-Null
    $negativeSource=$template.Replace('/* ACTUAL_FEATURE_CELL */',$control.Feature).Replace('/* ACTUAL_PERIOD_CELL */',$control.Period)
    if ($control.MutationFrom) { $negativeSource=$negativeSource.Replace($control.MutationFrom,$control.MutationTo) }
    $negativeFile=Join-Path $negative 'SubscriptionCells.java'
    [IO.File]::WriteAllText($negativeFile,$negativeSource,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx256m -encoding UTF-8 -cp $runtimeClasspath -d $negative $negativeFile
    if ($LASTEXITCODE -ne 0) { throw "Negative control must compile: $($control.Name)" }
    $negativeClasspath=$negative+[IO.Path]::PathSeparator+$runtimeClasspath
    $negativeOutput=& $java @runtimeOptions -cp $negativeClasspath org.junit.runner.JUnitCore org.telegram.ui.SubscriptionAndroid204Test 2>&1
    $negativeExit=$LASTEXITCODE
    [IO.File]::WriteAllLines((Join-Path $negative 'expected-failure.txt'),[string[]]$negativeOutput,[Text.UTF8Encoding]::new($false))
    if ($negativeExit -eq 0 -or ($negativeOutput -join "`n") -notmatch $control.Failure) {
        throw "Negative control did not fail for its intended geometry reason: $($control.Name)"
    }
    Write-Output "PASS: real Android measurement rejects $($control.Name)."
}
Write-Output 'Scope: exact extracted production cells, Android API35 FrameLayout/LinearLayout/TextView and native glyph measurement. Telegram theme/dp/radio/list collaborators modeled; no full app or screenshot rendering.'
Write-Output "Geometry-only PNG: $preview"
Write-Output "Header-state-only PNG: $statusPreview"
Write-Output "Test artifacts: $run"
