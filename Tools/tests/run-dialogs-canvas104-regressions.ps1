param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = 'D:/CodexBuildCache/Lunagram-dialogs-canvas104-regressions',
    [ValidatePattern('^[0-9a-f]{40}$')][string]$BaselineCommit = '8f60669bc4c257402d18bedd67e1ab71d0f59df8'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
foreach ($tool in @($javac, $java)) { if (!(Test-Path -LiteralPath $tool -PathType Leaf)) { throw 'JDK 17 is required.' } }
$relative = 'TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java'
$current = [IO.File]::ReadAllText((Join-Path $repo $relative))
$baselineLines = @(& git -C $repo show ($BaselineCommit + ':' + $relative))
if ($LASTEXITCODE -ne 0) { throw 'Exact audited baseline source is unavailable.' }
$baseline = $baselineLines -join "`n"
function DrawMethod([string]$Source) {
    $class = $Source.IndexOf('class DialogsRecyclerView', [StringComparison]::Ordinal)
    if ($class -lt 0) { throw 'Dialogs RecyclerView class not found.' }
    $signature = 'protected void dispatchDraw(Canvas canvas)'
    $start = $Source.IndexOf($signature, $class, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw 'Dialogs RecyclerView dispatchDraw not found.' }
    $open = $Source.IndexOf('{', $start); $depth = 1
    for ($end = $open + 1; $end -lt $Source.Length; $end++) {
        if ($Source[$end] -eq '{') { $depth++ }
        if ($Source[$end] -eq '}') { $depth-- }
        if ($depth -eq 0) { return $Source.Substring($start, $end - $start + 1) }
    }
    throw 'Unclosed dispatchDraw method.'
}
function Envelope([string]$Method) {
    $innerStart = $Method.IndexOf('if (rightFragmentOpenedProgress > 0)', [StringComparison]::Ordinal)
    $last = 'poller.checkList( this);'
    $innerEnd = $Method.LastIndexOf($last, [StringComparison]::Ordinal)
    if ($innerStart -lt 0 -or $innerEnd -le $innerStart) { throw 'Draw body landmarks changed; review the extraction.' }
    $innerEnd += $last.Length
    return @{
        Inner = $Method.Substring($innerStart, $innerEnd - $innerStart)
        # Only the outer production save/try/finally envelope is compiled. Android
        # drawing internals are replaced with controlled state/exception scenarios.
        Method = $Method.Substring(0, $innerStart) + 'if (mode == 3) return; drawScene(canvas);' + $Method.Substring($innerEnd)
    }
}
$fixed = Envelope (DrawMethod $current)
$old = Envelope (DrawMethod $baseline)
if ([regex]::Replace($fixed.Inner, '\s+', '') -cne [regex]::Replace($old.Inner, '\s+', '')) {
    throw 'The fix changed drawing semantics beyond the owned outer canvas scope.'
}
if ($fixed.Method -notmatch 'final int saveCount = canvas\.save\(\);\s*try\s*\{' -or
    $fixed.Method -notmatch '\}\s*finally\s*\{\s*canvas\.restoreToCount\(saveCount\);\s*\}') {
    throw 'dispatchDraw must unconditionally restore its own checkpoint in finally.'
}
if ($old.Method -match 'restoreToCount|finally' -or $old.Method -notmatch 'canvas\.save\(\);') {
    throw 'The exact baseline no longer represents the confirmed missing outer restore.'
}
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'dialogs-canvas104/CanvasScope104Probe.java.template'))
if ([regex]::Matches($template, '@@DISPATCH@@').Count -ne 1) { throw 'Invalid canvas fixture template.' }
$run = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $run
foreach ($entry in @(@{Name='fixed'; Value=$fixed}, @{Name='baseline'; Value=$old})) {
    $directory = Join-Path $run $entry.Name
    $null = New-Item -ItemType Directory -Path $directory
    $source = Join-Path $directory 'CanvasScope104Probe.java'
    [IO.File]::WriteAllText($source, $template.Replace('@@DISPATCH@@', $entry.Value.Method), [Text.UTF8Encoding]::new($false))
    & $javac '-J-Xmx128m' '-encoding' 'UTF-8' '-d' $directory $source
    if ($LASTEXITCODE -ne 0) { throw 'Extracted production canvas envelope did not compile.' }
}
& $java '-Xmx128m' '-cp' (Join-Path $run 'fixed') 'CanvasScope104Probe'
if ($LASTEXITCODE -ne 0) { throw 'Current canvas scope isolation regression failed.' }
foreach ($mode in 0..3) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $java; $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    foreach ($argument in @('-Xmx128m', '-cp', (Join-Path $run 'baseline'), 'CanvasScope104Probe', [string]$mode)) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($info)
    try {
        $out = $process.StandardOutput.ReadToEnd(); $err = $process.StandardError.ReadToEnd(); $process.WaitForExit()
        if ($process.ExitCode -eq 0 -or !$err.Contains('dispatch leaked its outer or nested save')) { throw 'Exact baseline negative control did not reproduce the canvas leak.' }
    } finally { $process.Dispose() }
}
Write-Output 'PASS: identical drawing body, finally/checkpoint source guards, and 4 exact-baseline negative controls.'
Write-Output 'Scope model only: not an Android GPU, blur or video reproduction test.'
Write-Output "Test artifacts: $run"
