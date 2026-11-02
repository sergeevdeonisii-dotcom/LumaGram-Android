param(
    [string]$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot,
    [switch]$ExpectKnownFailures
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (!$OutputRoot) { $OutputRoot = Join-Path ([IO.Path]::GetTempPath()) 'lunagram-status-drawable-tests' }
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$names = @('StatusDrawable','TypingDotsDrawable','RecordStatusDrawable','SendingFileDrawable','PlayingGameDrawable','RoundStatusDrawable','ChoosingStickerStatusDrawable')
$sources = [ordered]@{}
foreach ($name in $names) {
    $path = Join-Path $RepoRoot ('TMessagesProj/src/main/java/org/telegram/ui/Components/' + $name + '.java')
    $source = [IO.File]::ReadAllText($path)
    $source = [regex]::Replace($source, '(?m)^\s*(?:package|import)\s+[^;]+;\s*$', '')
    $declaration = 'public ' + $(if ($name -eq 'StatusDrawable') { 'abstract ' } else { '' }) + 'class ' + $name
    if (!$source.Contains($declaration)) { throw ('Missing production class: ' + $name) }
    $sources[$name] = $source.Replace($declaration, $declaration.Replace('public ', 'static '))
}
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'status-drawable-bounds/StatusDrawableBoundsProbe.java.template'))
function Assert-CenteredStatusGap([string]$Header) {
    if ($Header -notmatch 'else if \(externalAvatarMode && type != null && type >= 0 && type < statusDrawables\.length\)\s*\{[^}]*newSubtitle = TYPING_DRAWABLE_PLACEHOLDER \+ getTypingDrawableGap\(type\) \+ newSubtitle;' -or
        !$Header.Contains('return type == 4 ? "\u2009" : "\u2002";')) {
        throw 'Centered video dot must use a thin gap while moving statuses retain EN SPACE.'
    }
    if ($Header -notmatch 'if \(type != null && type == 5\)\s*\{\s*newSubtitle = Emoji\.replaceEmoji\(') {
        throw 'The native sticker-status emoji branch must remain separate.'
    }
}
function Run-Probe([string]$Name, [System.Collections.IDictionary]$Classes, [string[]]$Arguments) {
    $directory = Join-Path $run $Name
    New-Item -ItemType Directory -Path $directory | Out-Null
    $fixture = $template.Replace('// __PRODUCTION_CLASSES__', ($Classes.Values -join "`n"))
    $sourcePath = Join-Path $directory 'StatusDrawableBoundsProbe.java'
    [IO.File]::WriteAllText($sourcePath, $fixture, [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $directory $sourcePath
    if ($LASTEXITCODE -ne 0) { throw ('Actual status drawable compile failed: ' + $Name) }
    & $java -Xmx96m -XX:ActiveProcessorCount=1 -cp $directory StatusDrawableBoundsProbe @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Actual status drawable regression failed: ' + $Name) }
}
if ($ExpectKnownFailures) {
    Run-Probe 'known-broken-production' $sources @('--expect-failures=SendingFileDrawable,PlayingGameDrawable')
} else {
    $header = [IO.File]::ReadAllText((Join-Path $RepoRoot 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java'))
    Assert-CenteredStatusGap $header
    $oldGap = $header.Replace('return type == 4 ? "\u2009" : "\u2002";', 'return " ";')
    if ($oldGap -ceq $header) { throw 'Gap negative-control target not found.' }
    $gapRejected = $false
    try { Assert-CenteredStatusGap $oldGap } catch { $gapRejected = $true }
    if (!$gapRejected) { throw 'Old narrow status gap was accepted.' }
    Write-Output 'PASS: video-only thin gap, preserved EN SPACE for moving icons, unchanged native sticker branch, and narrow-all-statuses negative control.'
    Run-Probe 'actual-production' $sources @()
    foreach ($name in @('SendingFileDrawable','PlayingGameDrawable')) {
        $mutated = [ordered]@{}
        foreach ($key in $sources.Keys) { $mutated[$key] = $sources[$key] }
        if (!$mutated[$name].Contains('getBounds().left')) { throw ('Negative-control target not found: ' + $name) }
        $mutated[$name] = $mutated[$name].Replace('getBounds().left', '0')
        if ($name -eq 'SendingFileDrawable') { $mutated[$name] = $mutated[$name].Replace('getBounds().top', '0') }
        Run-Probe ('negative-' + $name) $mutated @(('--expect-failures=' + $name))
    }
}
Write-Output ('Artifacts: ' + $run)
Write-Output 'Scope: actual production draw() implementations with recording Canvas/platform fixtures; no APK, visual pixels, frame rate or device validation.'
