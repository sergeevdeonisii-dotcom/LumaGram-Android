param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-layout-diagnostics104b-tests')
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
$activity = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java'))
$actionBar = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java'))
$helper = Method $activity 'public static synchronized void logLumaLayoutFailure(Throwable error)'
if ($helper -match 'getMessage\(|toString\(\).*error|Log\.w\([^;]*,\s*error\)' -or !$helper.Contains('Log.w("LunagramLayout", details.toString())')) { throw 'Release layout logger must not expose messages or pass a Throwable to Android Log.' }
if (!$activity.Contains('logLumaLayoutFailure(e);') -or !$actionBar.Contains('ProfileActivity.logLumaLayoutFailure(e);')) { throw 'Both swallowed layout and tab-touch boundaries must use the same bounded diagnostic.' }
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'local-gifts-ui102/LayoutDiagnostics104bProbe.java.template'))
$template = $template.Replace('// PRODUCTION_LAYOUT_DIAGNOSTIC', $helper)
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$probe = Join-Path $run 'LayoutDiagnostics104bProbe.java'
[IO.File]::WriteAllText($probe, $template, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probe
if ($LASTEXITCODE -ne 0) { throw 'Production release diagnostic probe compilation failed.' }
& $java -Xmx128m -cp $run LayoutDiagnostics104bProbe
if ($LASTEXITCODE -ne 0) { throw 'Production release diagnostic privacy/bounds regression failed.' }
$negativeControls = @(
    @{ Name = 'missing-process-budget'; From = 'lumaLayoutFailuresLogged >= 3'; To = 'lumaLayoutFailuresLogged >= 30'; Expected = 'Release diagnostics are capped at three events per process' },
    @{ Name = 'message-exposure'; From = '.append(error.getClass().getName());'; To = '.append(error.getClass().getName()).append(error.getMessage());'; Expected = 'Exception messages, account/gift/content tokens and source filenames never appear' },
    @{ Name = 'unbounded-frames'; From = 'frameCount >= 6'; To = 'frameCount >= 60'; Expected = 'At most six app stack frames per throwable are logged' }
)
foreach ($control in $negativeControls) {
    $negative = $template.Replace($control.From, $control.To)
    if ($negative -eq $template) { throw "Missing source for diagnostic negative control: $($control.Name)" }
    $negativeRun = Join-Path $run $control.Name
    New-Item -ItemType Directory -Path $negativeRun -Force | Out-Null
    $negativeSource = Join-Path $negativeRun 'LayoutDiagnostics104bProbe.java'
    [IO.File]::WriteAllText($negativeSource, $negative, [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $negativeRun $negativeSource
    if ($LASTEXITCODE -ne 0) { throw "Diagnostic negative-control compilation failed: $($control.Name)" }
    $negativeOutput = (& $java -Xmx128m -cp $negativeRun LayoutDiagnostics104bProbe 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or !$negativeOutput.Contains($control.Expected)) { throw "Diagnostic negative control failed: $($control.Name)" }
}
Write-Output 'PASS: actual release logger negative controls detect missing process budget, exposed message and unbounded stack frames.'
Write-Output "Test artifacts: $run"
