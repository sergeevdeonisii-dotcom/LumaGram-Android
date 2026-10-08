param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$javaBin = Join-Path $JavaHome 'bin'
if (-not $OutputRoot) { $OutputRoot = Join-Path $PSScriptRoot '.runs' }
$run = Join-Path $OutputRoot ('updater73-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path (Join-Path $run 'classes') | Out-Null
# Existing fixtures are read-only and selected explicitly; overrides live in a separate tree.
$fixtureNames = @('android/app/Activity.java', 'android/content/Context.java', 'android/content/SharedPreferences.java',
    'android/content/pm/PackageInfo.java', 'android/content/pm/Signature.java', 'android/os/Build.java',
    'android/os/SystemClock.java', 'android/text/TextUtils.java', 'org/json/JSONObject.java',
    'org/telegram/messenger/ApplicationLoader.java', 'org/telegram/messenger/AndroidUtilities.java',
    'org/telegram/messenger/BetaUpdate.java', 'org/telegram/messenger/BuildVars.java', 'org/telegram/messenger/BuildConfig.java',
    'org/telegram/messenger/FileLog.java', 'org/telegram/messenger/LocaleController.java',
    'org/telegram/messenger/LumaEmergencyMode.java', 'org/telegram/messenger/NotificationCenter.java',
    'org/telegram/messenger/R.java', 'org/telegram/messenger/UserConfig.java', 'org/telegram/ui/web/HttpGetTask.java')
$sources = @($fixtureNames | ForEach-Object { Join-Path $PSScriptRoot ('fixtures/' + $_) })
$sources += @((Get-ChildItem (Join-Path $PSScriptRoot 'fixtures-audit73-updater') -Recurse -Filter '*.java').FullName)
$sources += @((Get-ChildItem (Join-Path $PSScriptRoot 'audit73-updater') -Recurse -Filter '*.java').FullName)
$sources += Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaUpdaterController.java'
$sources += Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaUpdateFiles.java'
$sources += Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaBuildPolicy.java'
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -d (Join-Path $run 'classes') $sources
if ($LASTEXITCODE -ne 0) { throw 'Updater .73 regression compilation failed.' }
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $run 'classes') org.telegram.messenger.LumaUpdaterAudit73Test (Join-Path $run 'sandbox')
if ($LASTEXITCODE -ne 0) { throw 'Updater .73 regressions failed.' }
Write-Output "Test artifacts: $run"
