param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-shared-media-geometry104b-tests')
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
$relative = 'TMessagesProj/src/main/java/org/telegram/ui/Components/SharedMediaLayout.java'
$source = [IO.File]::ReadAllText((Join-Path $repo $relative))
$helpers = @('private static float getFinitePageProgress(', 'private static float getFinitePageVisibility(')
$signatures = @('private void checkUi_topPanelLayoutY()', 'private boolean isTab(',
    'public float getBottomButtonStoriesVisibility()', 'public float getTabVisibility(',
    'public float getTabProgress()', 'private void checkStoriesTabsPosition()')
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'shared-media-geometry104b/SharedMediaGeometry104bProbe.java.template'))
$utilities = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/Utilities.java'))
$clamp = @((Method $utilities 'public static float clamp(float value,'), (Method $utilities 'public static float clamp01(')) -join "`n"
$template = $template.Replace('// PRODUCTION_CLAMP', $clamp)
$methods = (($helpers + $signatures | ForEach-Object { Method $source $_ }) -join "`n")
$probe = $template.Replace('// PRODUCTION_METHODS', $methods)
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$probePath = Join-Path $run 'SharedMediaGeometry104bProbe.java'
[IO.File]::WriteAllText($probePath, $probe, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probePath
if ($LASTEXITCODE -ne 0) { throw 'Actual shared media geometry compilation failed.' }
& $java -Xmx128m -cp $run SharedMediaGeometry104bProbe
if ($LASTEXITCODE -ne 0) { throw 'Actual shared media geometry regression failed.' }
$legacySource = (& git -C $repo show ('8f60669bc4c257402d18bedd67e1ab71d0f59df8:' + $relative)) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Exact pre-fix Git object is required for negative controls.' }
$legacyMethods = (($helpers | ForEach-Object { Method $source $_ }) + ($signatures | ForEach-Object { Method $legacySource $_ })) -join "`n"
$negative = $template.Replace('// PRODUCTION_METHODS', $legacyMethods)
$negativeRun = Join-Path $run 'exact-pre-fix'
New-Item -ItemType Directory -Path $negativeRun -Force | Out-Null
$negativePath = Join-Path $negativeRun 'SharedMediaGeometry104bProbe.java'
[IO.File]::WriteAllText($negativePath, $negative, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $negativeRun $negativePath
if ($LASTEXITCODE -ne 0) { throw 'Exact pre-fix geometry negative-control compilation failed.' }
$cases = @{
    visibility = 'Zero-width tab visibility must be finite'
    progress = 'Zero-width tab progress must be finite'
    bottom = 'Zero-width bottom button interpolation must be finite'
    stories = 'Zero-width story animation'
    panel = 'Zero-width top panel translation must be finite'
}
foreach ($name in $cases.Keys) {
    $negativeOutput = (& $java -Xmx128m -cp $negativeRun SharedMediaGeometry104bProbe $name 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or !$negativeOutput.Contains($cases[$name])) { throw "Exact pre-fix geometry did not reproduce $name NaN." }
}
foreach ($call in @('getFinitePageProgress(mediaPages[0].getTranslationX(), mediaPages[0].getMeasuredWidth())',
        'getFinitePageProgress(dx, mediaPages[0].getMeasuredWidth())')) {
    if (!$source.Contains($call)) { throw "Missing animation entry-point finite guard: $call" }
}
Write-Output 'PASS: five exact pinned production negative controls reproduce zero-width NaN; two animation entry points use finite progress.'
Write-Output "Test artifacts: $run"
