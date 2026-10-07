param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $OutputRoot) { $OutputRoot = Join-Path $PSScriptRoot '.runs' }
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString() + '/bot-layout')
New-Item -ItemType Directory -Path (Join-Path $run 'classes') | Out-Null
$sources = @((Get-ChildItem (Join-Path $PSScriptRoot 'bot-layout-fixtures') -Recurse -Filter '*.java').FullName)
$sources += Join-Path $PSScriptRoot 'bot-layout/ChatActivityDraftMessageMeasureControllerTest.java'
$sources += Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/chat/ChatActivityDraftMessageMeasureController.java'
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d (Join-Path $run 'classes') $sources
if ($LASTEXITCODE -ne 0) { throw 'Bot layout test compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -cp (Join-Path $run 'classes') org.telegram.ui.Components.chat.ChatActivityDraftMessageMeasureControllerTest
if ($LASTEXITCODE -ne 0) { throw 'Bot layout height regressions failed.' }
$cell = [System.IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java'))
if ($cell -notmatch '(?s)final int currentLayoutHeight = getMeasuredHeight\(\).*?- additionalPaddingHeight;\s+if \(lastSize != currentSize \|\| layoutHeight != currentLayoutHeight.*?layoutHeight = currentLayoutHeight;') {
    throw 'Bubble/time layout cache must include content height, not just the padded row size.'
}
Write-Output 'PASS: bubble/time source cache guard accounts for changed scroll-only padding.'
Write-Output "Test artifacts: $run"
