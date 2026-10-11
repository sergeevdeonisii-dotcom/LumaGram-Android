param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-gift-geometry104-tests')
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
$profile = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Gifts/ProfileGiftsContainer.java'))
# Scope below Page's same-named getTabsHeight to the actual parent geometry.
$outer = $profile.Substring($profile.IndexOf('public void updateTabsShown(boolean animated)', [StringComparison]::Ordinal))
$height = Method $outer 'public float getTabsHeight()'
$geometry = @($height, (Method $outer 'private float hasTabs()'), (Method $outer 'public void updateTabsY()')) -join "`n"
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'local-gifts-ui102/GiftGeometry104Probe.java.template'))
$probe = $template.Replace('// PRODUCTION_GEOMETRY', $geometry)
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$source = Join-Path $run 'GiftGeometry104Probe.java'
[IO.File]::WriteAllText($source, $probe, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $source
if ($LASTEXITCODE -ne 0) { throw 'Production gift geometry compilation failed.' }
& $java -Xmx128m -cp $run GiftGeometry104Probe
if ($LASTEXITCODE -ne 0) { throw 'Production gift geometry regression failed.' }
# Verbatim pre-104 parent method: old behavior is the negative control, not a simulated assertion.
$legacyHeight = @'
public float getTabsHeight() {
    float h = 0;
    if (viewPager.getViewPages() != null) {
        final View[] views = viewPager.getViewPages();
        for (View view : views) {
            if (view instanceof Page) {
                h += (1.0f - (float) view.getTranslationX() / view.getWidth()) * ((Page) view).getTabsHeight();
            }
        }
    }
    return h;
}
'@
$negative = $probe.Replace($height, $legacyHeight)
if ($negative -eq $probe) { throw 'Legacy geometry negative control was not installed.' }
$negativeRun = Join-Path $run 'legacy-zero-width'
New-Item -ItemType Directory -Path $negativeRun -Force | Out-Null
$negativeSource = Join-Path $negativeRun 'GiftGeometry104Probe.java'
[IO.File]::WriteAllText($negativeSource, $negative, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $negativeRun $negativeSource
if ($LASTEXITCODE -ne 0) { throw 'Legacy gift geometry negative-control compilation failed.' }
$negativeOutput = (& $java -Xmx128m -cp $negativeRun GiftGeometry104Probe 2>&1 | Out-String)
if ($LASTEXITCODE -eq 0 -or !$negativeOutput.Contains('Unmeasured zero-width gift page must not return NaN')) {
    throw 'Legacy geometry did not reproduce the zero-width NaN defect.'
}
Write-Output 'PASS: exact pre-104 geometry fails on an unmeasured zero-width gift page.'
Write-Output "Test artifacts: $run"
