param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-531-policy-tests')
$ErrorActionPreference = 'Stop'
$tests = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repo = [System.IO.Path]::GetFullPath((Join-Path $tests '../..'))
$javaBin = Join-Path $JavaHome 'bin'
$fixtureNames = @('android/app/Activity.java', 'android/content/Context.java', 'android/content/SharedPreferences.java',
    'android/content/pm/PackageInfo.java', 'android/content/pm/Signature.java', 'android/content/pm/PackageManager.java',
    'android/os/Build.java', 'android/os/SystemClock.java', 'android/text/TextUtils.java',
    'org/telegram/messenger/ApplicationLoader.java', 'org/telegram/messenger/AndroidUtilities.java',
    'org/telegram/messenger/BetaUpdate.java', 'org/telegram/messenger/BuildVars.java',
    'org/telegram/messenger/FileLog.java', 'org/telegram/messenger/LocaleController.java',
    'org/telegram/messenger/LumaEmergencyMode.java', 'org/telegram/messenger/NotificationCenter.java',
    'org/telegram/messenger/R.java', 'org/telegram/messenger/UserConfig.java', 'org/telegram/messenger/Utilities.java',
    'org/telegram/messenger/MessagesController.java', 'org/telegram/messenger/SendMessagesHelper.java',
    'org/telegram/messenger/DialogObject.java', 'org/telegram/messenger/LiteMode.java', 'org/telegram/messenger/LumaRoundVideoQuality.java', 'org/telegram/ui/web/HttpGetTask.java',
    'org/telegram/tgnet/ConnectionsManager.java', 'org/telegram/tgnet/tl/TL_stars.java')
$sources = @($fixtureNames | ForEach-Object { Join-Path $tests ('fixtures/' + $_) })
$sources += Join-Path $tests 'fixtures-audit73-updater/org/telegram/ui/web/HttpGetFileTask.java'
$sources += @((Get-ChildItem (Join-Path $PSScriptRoot 'fixtures') -Recurse -Filter '*.java').FullName)
$sources += Join-Path $PSScriptRoot 'org/telegram/messenger/LumaFriendsBuildPolicy531Test.java'
foreach ($name in @('LumaBuildPolicy', 'LumaGhostMode', 'LumaDeletedMessages', 'LumaEditHistory',
        'LumaDelayedSend', 'LumaAnonymousNumber', 'LumaProfileVerification', 'LumaStarRating',
        'LumaUpdaterController', 'LumaUpdateFiles', 'LumaTextAnimation', 'LumaMessageFormatting',
        'LumaRoundVideoCamera', 'BlackHoleSettings')) {
    $sources += Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/messenger/' + $name + '.java')
}
foreach ($edition in @('full', 'friends')) {
    $run = Join-Path $OutputRoot ($edition + '-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path (Join-Path $run 'classes') -Force | Out-Null
    $editionSources = $sources + (Join-Path $PSScriptRoot ('fixtures-' + $edition + '/org/telegram/messenger/BuildConfig.java'))
    & (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -d (Join-Path $run 'classes') $editionSources
    if ($LASTEXITCODE -ne 0) { throw "$edition policy-test compilation failed." }
    $friendsArgument = ($edition -eq 'friends').ToString().ToLowerInvariant()
    & (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.LumaFriendsBuildPolicy531Test (Join-Path $run 'sandbox') $friendsArgument
    if ($LASTEXITCODE -ne 0) { throw "$edition policy tests failed." }
    Write-Output "Test artifacts: $run"
}
