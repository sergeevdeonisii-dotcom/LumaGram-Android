param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot='')
$ErrorActionPreference='Stop'
if (!$JavaHome -and $env:OS -eq 'Windows_NT') { $JavaHome='D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1' }
if (!$JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome.' }
$suffix=if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
$java=Join-Path $JavaHome ('bin/java'+$suffix)
if (!$OutputRoot) {
    $OutputRoot=if ($env:RUNNER_TEMP) { Join-Path $env:RUNNER_TEMP 'lunagram-media-access' } else { Join-Path $PSScriptRoot '.runs/media-access' }
}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram'
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$source=Join-Path $base 'messenger/LunagramMediaAccess.java'
$original=[IO.File]::ReadAllText($source)
$sources=@(
    (Join-Path $PSScriptRoot 'media-access/MediaAccessTest.java'),
    (Join-Path $PSScriptRoot 'local-gifts102/fixtures/android/content/Context.java'),
    (Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java'),
    (Join-Path $base 'messenger/LunagramVideoEffectsSettings.java'),
    (Join-Path $base 'messenger/LunagramAudioSettings.java'),
    (Join-Path $base 'messenger/LunagramFeatureCatalog.java')
)
$copy=Join-Path $run 'LunagramMediaAccess.java'
function Probe([string]$text,[bool]$expected) {
    [IO.File]::WriteAllText($copy,$text,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $run $copy $sources
    if ($LASTEXITCODE -ne 0) { throw 'Media access probe compilation failed.' }
    $result=& $java -Xmx64m -cp $run org.telegram.messenger.MediaAccessTest 2>&1
    if (($LASTEXITCODE -eq 0) -ne $expected) { throw "Media access probe unexpected result: $result" }
    if ($expected) { Write-Output $result }
}
Probe $original $true
foreach ($mutation in @(
    $original.Replace('&& UserConfig.getInstance(account).getClientUserId() == owner',''),
    $original.Replace('&& LunagramSubscriptionClient.accountEpoch(account) == epoch',''),
    $original.Replace('LunagramSubscriptionAccess.canUse(account, feature)','LunagramSubscriptionAccess.canUse(UserConfig.selectedAccount, feature)')
)) {
    if ($mutation -eq $original) { throw 'Missing media owner negative mutation.' }
    Probe $mutation $false
}
Probe $original $true
$media=[IO.File]::ReadAllText((Join-Path $base 'messenger/MediaController.java'))
function Extract-Method([string]$source,[string]$signature) {
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing method: $signature" }
    $open=$source.IndexOf('{',$start); $depth=1
    for ($end=$open+1; $end -lt $source.Length -and $depth -gt 0; $end++) {
        if ($source[$end] -eq '{') { $depth++ }; if ($source[$end] -eq '}') { $depth-- }
    }
    if ($depth -ne 0) { throw "Unbalanced method: $signature" }
    $source.Substring($start,$end-$start)
}
$factory=Extract-Method $media 'private AudioRecord createLunagramRecorder('
$factoryTemplate=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'media-access/MediaRecorderAccessProbe.java.template'))
$factoryPath=Join-Path $run 'MediaRecorderAccessProbe.java'
function Factory-Probe([string]$method,[bool]$expected) {
    [IO.File]::WriteAllText($factoryPath,$factoryTemplate.Replace('@@FACTORY@@',$method),[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $run $factoryPath
    if ($LASTEXITCODE -ne 0) { throw 'Actual recorder factory probe compilation failed.' }
    $result=& $java -Xmx64m -cp $run MediaRecorderAccessProbe 2>&1
    if (($LASTEXITCODE -eq 0) -ne $expected) { throw "Unexpected recorder factory probe result: $result" }
    if ($expected) { Write-Output $result }
}
Factory-Probe $factory $true
foreach ($mutation in @(
    $factory.Replace('if (preferStereo && (access == null || !access.canUse(LunagramFeatureCatalog.Feature.VOICE_STEREO)))', 'if (false)'),
    $factory.Replace('if (requested == 2 && (access == null || !access.canUse(LunagramFeatureCatalog.Feature.VOICE_STEREO)))', 'if (false)'),
    $factory.Replace('if (access != null && !access.isCurrent())', 'if (false)')
)) {
    if ($mutation -eq $factory) { throw 'Missing recorder boundary negative control.' }
    Factory-Probe $mutation $false
}
Factory-Probe $factory $true
$meme=[IO.File]::ReadAllText((Join-Path $base 'ui/Components/LunagramMemeEditor.java'))
$photo=[IO.File]::ReadAllText((Join-Path $base 'ui/PhotoViewer.java'))
$retro=[IO.File]::ReadAllText((Join-Path $base 'utils/camera/roundvideo/RoundVideoRetroRenderer.java'))
$bokeh=[IO.File]::ReadAllText((Join-Path $base 'utils/camera/roundvideo/RoundVideoBokeh.java'))
$controller=[IO.File]::ReadAllText((Join-Path $base 'utils/camera/roundvideo/RoundVideoCameraController.java'))
$gl=[IO.File]::ReadAllText((Join-Path $base 'utils/camera/roundvideo/RoundVideoGlProcessor.java'))
foreach ($guard in @('createLunagramRecorder(mediaAccess, LunagramAudioSettings.isStereoEnabled(currentAccount), true)',
    'createLunagramRecorder(lunagramRecordingAccess, lunagramRecordingChannels == 2, false)',
    'applyLunagramVoiceEffect(int currentAccount,', 'if (!allowFallback) throw new IOException("Stereo recording access expired")',
    'Stereo access changed during microphone startup', 'renderLunagramVoiceEffect(newFile, source.effect, lunagramRecordingAccess)')) {
    if (!$media.Contains($guard)) { throw "Missing media runtime boundary: $guard" }
}
if ([regex]::Matches($media,'effect != LunagramVoiceProcessor.ORIGINAL && .*access.canUse\(LunagramFeatureCatalog.Feature.VOICE_FILTERS\)').Count -lt 4) {
    throw 'Voice effects require request, queue, processing and pre-replacement gates, with free original restoration.'
}
foreach ($content in @($retro,$bokeh,$controller,$meme,$photo)) {
    if (!$content.Contains('.canUse(LunagramFeatureCatalog.Feature.') -and !$content.Contains('.canUse(org.telegram.messenger.LunagramFeatureCatalog.Feature.')) {
        throw 'A real media consumer lost its runtime entitlement boundary.'
    }
}
if (!$retro.Contains('if (activeEffect == 0 && !activeDate) return;') -or
        !$gl.Contains('setMediaAccess(int account, LunagramMediaAccess.Session access)') -or
        !$controller.Contains('glProcessor.setMediaAccess(currentAccount, mediaAccess);')) { throw 'Effects must be bound to the recording owner and rechecked on rendering.' }
if (!$bokeh.Contains('onCaptureStarted(') -or !$bokeh.Contains('if (!closed) listener.onReady();')) { throw 'Portrait expiry must leave the vendor session through safe normal fallback.' }
if ([regex]::Matches($meme,'access.canUse\(LunagramFeatureCatalog.Feature.VIDEO_MEMES\)').Count -lt 4) { throw 'Meme create, export queue and final share must all enforce original owner access.' }
Write-Output "PASS: 6 compiled owner/epoch/account/recorder negative controls plus media use-site integration guards. Artifacts: $run"
