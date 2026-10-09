param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-100-edition-tests')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
$sources = @(
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaBuildPolicy.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoStabilization.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaHorizonLock.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaHorizonState.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoQuality.java',
    'Tools/tests/typing-fixtures/org/telegram/messenger/MessagesController.java',
    'Tools/tests/fixtures/android/content/SharedPreferences.java',
    'Tools/tests/fixtures/org/telegram/messenger/FileLog.java',
    'Tools/tests/round-video-fixtures/org/telegram/messenger/VideoEditedInfo.java',
    'Tools/tests/round-editions100/RoundEditions100Test.java'
) | ForEach-Object { Join-Path $repo $_ }
$sources += (Get-ChildItem (Join-Path $PSScriptRoot 'round-editions100/fixtures') -Recurse -Filter '*.java').FullName
foreach ($edition in @('full', 'friends')) {
    $classes = Join-Path $OutputRoot ($edition + '-' + [guid]::NewGuid().ToString('N') + '/classes')
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $editionSources = $sources + (Join-Path $PSScriptRoot ('friends531/fixtures-' + $edition + '/org/telegram/messenger/BuildConfig.java'))
    & $javac -J-Xmx128m -encoding UTF-8 -d $classes $editionSources
    if ($LASTEXITCODE -ne 0) { throw "$edition round-edition compilation failed." }
    & $java -Xmx128m -cp $classes org.telegram.messenger.RoundEditions100Test ($edition -eq 'friends').ToString().ToLowerInvariant()
    if ($LASTEXITCODE -ne 0) { throw "$edition round-edition tests failed." }
}
foreach ($path in @(
    'org/telegram/messenger/camera/CameraSession.java',
    'org/telegram/messenger/camera/Camera2Session.java',
    'org/telegram/ui/Components/InstantCameraView.java',
    'org/telegram/utils/camera/roundvideo/RoundVideoCameraController.java')) {
    $source = [IO.File]::ReadAllText((Join-Path $repo ('TMessagesProj/src/main/java/' + $path)))
    if (!$source.Contains('LumaRoundVideoStabilization.getMode()')) { throw "Recorder bypasses normalized stabilization policy: $path" }
    if ($source.Contains('getInt(LumaRoundVideoStabilization.PREFERENCE_KEY')) { throw "Recorder reads unrestricted stabilization preference: $path" }
}
Write-Output 'PASS: Camera1, legacy Camera2 and modern Camera2 consume the shared edition-aware stabilization mode.'
