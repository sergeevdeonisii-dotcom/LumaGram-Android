param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-recording-header150-tests')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$sources = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'recording-header150') -Recurse -Filter '*.java').FullName)
foreach ($name in @('StatusDrawable', 'RoundStatusDrawable')) {
    $sources += Join-Path $repo "TMessagesProj/src/main/java/org/telegram/ui/Components/$name.java"
}
& (Join-Path $JavaHome ('bin/javac' + $suffix)) -J-Xmx128m -encoding UTF-8 -d $run $sources
if ($LASTEXITCODE -ne 0) { throw 'Production recording status drawable compilation failed.' }
& (Join-Path $JavaHome ('bin/java' + $suffix)) -Xmx128m -cp $run org.telegram.ui.Components.RecordingHeader150Test
if ($LASTEXITCODE -ne 0) { throw 'Recording header regression failed.' }
$header = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java'))
foreach ($required in @('statusDrawables[4] = new RoundStatusDrawable(true, true);',
        'statusDrawables[0] = new TypingDotsDrawable(true);', 'statusDrawables[1] = new RecordStatusDrawable(true);',
        'statusDrawables[2] = new SendingFileDrawable(true);', 'statusDrawables[3] = new PlayingGameDrawable(false, resourcesProvider);',
        'statusDrawables[5] = new ChoosingStickerStatusDrawable(true);',
        'Math.max(1, drawable.getIntrinsicHeight())', 'drawable.setBounds(previousBounds);')) {
    if (!$header.Contains($required)) { throw "Missing isolated header/snapshot contract: $required" }
}
foreach ($path in @('ui/ActionBar/Theme.java', 'ui/PopupNotificationActivity.java')) {
    $source = [IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/java/org/telegram/$path"))
    if ($source -match 'new RoundStatusDrawable\([^)]*,') { throw 'Unrelated status caller opted into header centering.' }
}
Write-Output 'PASS: only the chat header opts into bounds centering; typing/voice/upload/game/sticker and legacy caller construction unchanged.'
Write-Output 'Scope: real drawable implementation on canvas fixtures; no APK/device visual verification.'
Write-Output "Test artifacts: $run"
