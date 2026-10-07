param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot,[switch]$Baseline)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))){throw 'Pass -JavaHome pointing to JDK 17.'}
if(-not $OutputRoot){$OutputRoot=Join-Path $PSScriptRoot '.runs'}
$run=Join-Path $OutputRoot ('updater76-'+[guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path (Join-Path $run 'controller'),(Join-Path $run 'http') -Force|Out-Null
function Production([string]$path){if(-not $Baseline){return Join-Path $repo $path};$content=(& git -C $repo show "HEAD:$path") -join "`n";if($LASTEXITCODE -ne 0){throw "Baseline unavailable: $path"};$target=Join-Path $run ([IO.Path]::GetFileName($path));[IO.File]::WriteAllText($target,$content,[Text.UTF8Encoding]::new($false));return $target}
$fixtureNames=@('android/app/Activity.java','android/content/Context.java','android/content/SharedPreferences.java','android/content/pm/PackageInfo.java','android/content/pm/Signature.java','android/os/Build.java','android/os/SystemClock.java','android/text/TextUtils.java','org/json/JSONObject.java','org/telegram/messenger/ApplicationLoader.java','org/telegram/messenger/BetaUpdate.java','org/telegram/messenger/FileLog.java','org/telegram/messenger/LocaleController.java','org/telegram/messenger/LumaEmergencyMode.java','org/telegram/messenger/NotificationCenter.java','org/telegram/messenger/R.java','org/telegram/messenger/UserConfig.java')
$controllerSources=@($fixtureNames|ForEach-Object{Join-Path $PSScriptRoot ('fixtures/'+$_)})
$controllerSources+=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixtures-audit73-updater') -Filter '*.java' -Recurse).FullName)
$controllerSources+=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'updater76-fixtures') -Filter '*.java' -Recurse).FullName)
$controllerSources+=Join-Path $PSScriptRoot 'updater76/org/telegram/messenger/LumaUpdaterCheck76Test.java'
$controllerSources+=Production 'TMessagesProj/src/main/java/org/telegram/messenger/LumaUpdaterController.java'
$controllerSources+=Production 'TMessagesProj/src/main/java/org/telegram/messenger/LumaUpdateFiles.java'
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d (Join-Path $run 'controller') $controllerSources
if($LASTEXITCODE -ne 0){throw 'Updater controller regression compilation failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx96m -cp (Join-Path $run 'controller') org.telegram.messenger.LumaUpdaterCheck76Test (Join-Path $run 'sandbox')
$controllerExit=$LASTEXITCODE
$httpSources=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'updater76-http-fixtures') -Filter '*.java' -Recurse).FullName)
$httpSources+=Join-Path $PSScriptRoot 'updater76-http/org/telegram/ui/web/HttpGetTask76Test.java'
$httpSources+=Production 'TMessagesProj/src/main/java/org/telegram/ui/web/HttpGetTask.java'
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d (Join-Path $run 'http') $httpSources
if($LASTEXITCODE -ne 0){throw 'HTTP task regression compilation failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx96m -cp (Join-Path $run 'http') org.telegram.ui.web.HttpGetTask76Test
$httpExit=$LASTEXITCODE
Write-Output "Test artifacts: $run"
if($controllerExit -ne 0 -or $httpExit -ne 0){throw 'Updater .76 regressions failed (expected only with -Baseline).'}
