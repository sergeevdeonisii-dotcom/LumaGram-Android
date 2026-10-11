param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-blur-capture104-tests')
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
$paths = @{
    Group = 'TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/ViewGroupPartRenderer.java'
    Relative = 'TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/utils/Blur3Utils.java'
    Suppressor = 'TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/DownscaleScrollableNoiseSuppressor.java'
    Drawable = 'TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/drawable/BlurredBackgroundDrawableRenderNode.java'
    Hash = 'TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/RenderNodeWithHash.java'
}
$sources = @{}
foreach ($key in $paths.Keys) { $sources[$key] = [IO.File]::ReadAllText((Join-Path $repo $paths[$key])) }
$base = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'blur-capture104/BlurCapture104Regression.java.template'))
function Make-Probe([hashtable]$inputSources) {
    $group = Method $inputSources.Group 'public class ViewGroupPartRenderer'
    $group = $group.Replace('public class ViewGroupPartRenderer', 'static class ViewGroupPartRenderer')
    $hash = (Method $inputSources.Hash 'public class RenderNodeWithHash').Replace('public class RenderNodeWithHash', 'static class RenderNodeWithHash')
    $endSignature = if ($inputSources.Suppressor.Contains('private void endRecordingRect(boolean complete)')) { 'private void endRecordingRect(boolean complete)' } else { 'private void endRecordingRect()' }
    return $base.Replace('// PRODUCTION_VIEWGROUP_CLASS', $group).
        Replace('// PRODUCTION_HASH_CLASS', $hash).
        Replace('// PRODUCTION_RELATIVE_METHOD', (Method $inputSources.Relative 'public static void captureRelativeParent(IBlur3Capture capture, Canvas canvas, RectF position, View view, ViewGroup parent, int alpha)')).
        Replace('// PRODUCTION_SUPPRESSOR_CAPTURE', (Method $inputSources.Suppressor 'public boolean invalidateResultRenderNodes(IBlur3Capture capture, int width, int height)')).
        Replace('// PRODUCTION_SUPPRESSOR_BEGIN', (Method $inputSources.Suppressor 'private RecordingCanvas beginRecordingRect(int index)')).
        Replace('// PRODUCTION_SUPPRESSOR_END', (Method $inputSources.Suppressor $endSignature))
}
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$probe = Join-Path $run 'BlurCapture104Regression.java'
[IO.File]::WriteAllText($probe, (Make-Probe $sources), [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probe
if ($LASTEXITCODE -ne 0) { throw 'Production-derived blur capture probe compilation failed.' }
& $java -Xmx128m -cp $run BlurCapture104Regression
if ($LASTEXITCODE -ne 0) { throw 'Production-derived blur capture regression failed.' }
$drawableUpdate = Method $sources.Drawable 'public void updateDisplayList()'
if ($drawableUpdate -notmatch 'source\.draw\(c, sL, sT, sR, sB\);\s*c\.restore\(\);\s*renderNodeFill\.endRecording\(\);') { throw 'Drawable source recording must restore, not save again, before ending.' }
Write-Output 'PASS: drawable fill-recording save/restore production guard.'
foreach ($control in @(
    @{ Mode='viewgroup'; Key='Group'; Expected='VIEWGROUP_' },
    @{ Mode='visibility'; Key='Group'; Expected='HIDDEN_PAGE' },
    @{ Mode='relative'; Key='Relative'; Expected='RELATIVE_' },
    @{ Mode='suppressor'; Key='Suppressor'; Expected='SUPPRESSOR_' },
    @{ Mode='hash'; Key='Hash'; Expected='HASH_' }
)) {
    $old = @(& git -C $repo show ('8f60669bc4c257402d18bedd67e1ab71d0f59df8:' + $paths[$control.Key])) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read pinned pre-fix production source for negative control.' }
    $negativeSources = $sources.Clone(); $negativeSources[$control.Key] = $old
    $negativeRun = Join-Path $run $control.Mode
    New-Item -ItemType Directory -Path $negativeRun -Force | Out-Null
    $negativeProbe = Join-Path $negativeRun 'BlurCapture104Regression.java'
    [IO.File]::WriteAllText($negativeProbe, (Make-Probe $negativeSources), [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $negativeRun $negativeProbe
    if ($LASTEXITCODE -ne 0) { throw "Original production negative-control compilation failed: $($control.Mode)" }
    $process = [Diagnostics.ProcessStartInfo]::new()
    $process.FileName = $java; $process.UseShellExecute = $false; $process.CreateNoWindow = $true
    $process.RedirectStandardError = $true; $process.RedirectStandardOutput = $true
    foreach ($arg in @('-Xmx128m', '-cp', $negativeRun, 'BlurCapture104Regression', $control.Mode)) { $process.ArgumentList.Add($arg) }
    $child = [Diagnostics.Process]::Start($process)
    $output = $child.StandardOutput.ReadToEnd() + $child.StandardError.ReadToEnd(); $child.WaitForExit()
    $exit = $child.ExitCode; $child.Dispose()
    if ($exit -eq 0 -or !$output.Contains($control.Expected)) { throw "Original production did not reproduce failure: $($control.Mode): $output" }
}
Write-Output 'PASS: five pinned pre-fix production negative controls reproduce canvas/rectangle/hidden-page/recording leaks.'
Write-Output "Test artifacts: $run"
