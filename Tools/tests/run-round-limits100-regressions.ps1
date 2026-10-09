param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-100-limit-tests')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
function Method([string]$source, [string]$signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method: $signature" }
    $open = $source.IndexOf('{', $start); $depth = 1
    for ($i = $open + 1; $i -lt $source.Length; $i++) {
        if ($source[$i] -eq '{') { $depth++ }; if ($source[$i] -eq '}') { $depth-- }
        if (!$depth) { return $source.Substring($start, $i - $start + 1) }
    }
    throw "Unterminated method: $signature"
}
$edited = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/VideoEditedInfo.java'))
$controller = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java'))
$convert = Method $controller 'private boolean convertVideo('
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-limits100/VideoEditedInfo.java.template'))
$template = $template.Replace('@@GET_STRING@@', (Method $edited 'public String getString()')).Replace('@@PARSE_STRING@@', (Method $edited 'public boolean parseString(')).Replace('@@NEED_CONVERT@@', (Method $edited 'public boolean needConvert()'))
$template = $template.Replace('@@UPLOAD_CALLBACK@@', (Method $convert 'public void didWriteData(long availableSize, float progress)'))
$generated = Join-Path $run 'VideoEditedInfo.java'
[IO.File]::WriteAllText($generated, $template, [Text.UTF8Encoding]::new($false))
$sources = @($generated, (Join-Path $PSScriptRoot 'round-limits100/RoundLimits100Test.java'))
$sources += (Get-ChildItem (Join-Path $PSScriptRoot 'round-limits100/fixtures') -Recurse -Filter '*.java').FullName
$sources += @('SerializedData', 'AbstractSerializedData', 'InputSerializedData', 'OutputSerializedData', 'TLDataSourceType') | ForEach-Object { Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/tgnet/' + $_ + '.java') }
$sources += @('TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoLimits.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoQuality.java',
    'Tools/tests/typing-fixtures/org/telegram/messenger/MessagesController.java',
    'Tools/tests/fixtures/android/content/SharedPreferences.java') | ForEach-Object { Join-Path $repo $_ }
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Round size-limit regression compilation failed.' }
& $java -Xmx128m -cp $classes org.telegram.messenger.RoundLimits100Test
if ($LASTEXITCODE -ne 0) { throw 'Round size-limit regressions failed.' }
$controller = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java'))
$convert = Method $controller 'private boolean convertVideo('
foreach ($guard in @('if (constrainedRound) return;', 'error = !validatePreparedRoundVideo(', 'error || canceled', 'sameFile(videoPath, cacheFile)', 'LumaRoundVideoStats.inspectAsync(cacheFile,')) {
    if (!$convert.Contains($guard)) { throw "Missing upload/final-output safeguard: $guard" }
}
if ($convert.IndexOf('sameFile(videoPath, cacheFile)') -gt $convert.IndexOf('cacheFile.delete()')) { throw 'Source identity must be checked before output deletion.' }
if ($convert.IndexOf('validatePreparedRoundVideo(') -gt $convert.LastIndexOf('didWriteData(convertMessage,')) { throw 'Validate final output before releasing upload events.' }
$send = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java'))
$roundAttributeStart = $send.IndexOf('attributeVideo.round_message = isRound;', $send.IndexOf('final boolean constrainedRound ='))
if ($roundAttributeStart -lt 0) { throw 'Round video attribute construction missing.' }
$roundAttributeBlock = $send.Substring($roundAttributeStart, [Math]::Min(1800, $send.Length - $roundAttributeStart))
if ($roundAttributeBlock -notmatch 'if \(videoEditedInfo\.muted && !isRound\)\s*\{\s*document\.attributes\.add\(new TLRPC\.TL_documentAttributeAnimated\(\)\);') {
    throw 'Converted muted round messages must never acquire the GIF/Animated attribute.'
}
foreach ($guard in @('LumaRoundVideoLimits.prepareForSend(videoEditedInfo, temp,', 'cancelFileUpload(videoPath, false)', 'cancelFileUpload(videoPath, true)', 'if (!constrainedRound && !isEncrypted')) {
    if (!$send.Contains($guard)) { throw "Missing stale original upload/cache safeguard: $guard" }
}
$legacy = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java'))
$stop = Method $legacy 'private void handleStopRecording('
if ($stop.IndexOf('delegate.sendMedia(') -lt $stop.IndexOf('mediaMuxer.finishMovie()')) { throw 'Legacy recorder must finalize before creating a send request.' }
if ($stop.Contains('videoEditedInfo.notReadyYet = true;')) { throw 'Growing round cannot bypass size validation.' }
Write-Output 'PASS: original upload cancellation, muted-round metadata, full-minute preservation, fail-closed final validation and persistence integration guards.'
