param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot='D:/CodexBuildCache/Lunagram-public-updater-tests')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$java=Join-Path $JavaHome 'bin/java.exe'
$javac=Join-Path $JavaHome 'bin/javac.exe'
if (!(Test-Path -LiteralPath $javac)) { throw 'Pass JDK17 -JavaHome.' }
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path (Join-Path $run 'classes') -Force | Out-Null
$fixtureNames=@('android/app/Activity.java','android/content/Context.java','android/content/SharedPreferences.java','android/content/pm/PackageInfo.java','android/content/pm/Signature.java','android/os/Build.java','android/os/SystemClock.java','android/text/TextUtils.java','org/json/JSONObject.java','org/telegram/messenger/ApplicationLoader.java','org/telegram/messenger/BetaUpdate.java','org/telegram/messenger/FileLog.java','org/telegram/messenger/LocaleController.java','org/telegram/messenger/LumaEmergencyMode.java','org/telegram/messenger/NotificationCenter.java','org/telegram/messenger/R.java','org/telegram/messenger/UserConfig.java')
$sources=@($fixtureNames | ForEach-Object { Join-Path $PSScriptRoot ('fixtures/'+$_) })
$sources+=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixtures-audit73-updater') -Filter '*.java' -Recurse).FullName | Where-Object { [IO.Path]::GetFileName($_) -ne 'PackageManager.java' })
$sources+=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'updater76-fixtures') -Filter '*.java' -Recurse).FullName)
$sources+=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'public-updater150') -Filter '*.java').FullName)
foreach($name in @('LumaBuildPolicy','LumaUpdaterController','LumaUpdateFiles')) { $sources+=Join-Path $repo "TMessagesProj/src/main/java/org/telegram/messenger/$name.java" }
& $javac -J-Xmx128m -encoding UTF-8 -d (Join-Path $run 'classes') $sources
if ($LASTEXITCODE -ne 0) { throw 'Public updater compilation failed.' }
& $java -Xmx96m -cp (Join-Path $run 'classes') org.telegram.messenger.PublicUpdater150Test (Join-Path $run 'sandbox')
if ($LASTEXITCODE -ne 0) { throw 'Public updater contracts failed.' }
$manifest=[xml][IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/AndroidManifest.xml'))
$android='http://schemas.android.com/apk/res/android'
$entry=@($manifest.manifest.application.'meta-data' | Where-Object { $_.GetAttribute('name',$android) -eq 'org.lunagram.edition' })
if ($entry.Count -ne 1 -or $entry[0].GetAttribute('value',$android) -cne '${lumaEdition}') { throw 'Signed APK edition metadata is not connected.' }
$gradle=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/build.gradle'))
if (!$gradle.Contains("manifestPlaceholders = [lumaEdition: lumaFriendsEdition ? 'friends' : (lumaPublicEdition ? 'public' : 'personal')]")) { throw 'Common library must resolve edition for every app variant.' }
Write-Output 'PASS common manifest edition marker wiring (actual merged APK still requires packaging verification).'
Write-Output "Artifacts: $run"
