param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-100-startup-tests',
    [string]$AndroidJar
)
$ErrorActionPreference = 'Stop'
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'A Java compiler is required.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram'
$run = Join-Path $OutputRoot ('run-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$production = @(
    (Join-Path $source 'messenger/LumaStartupRevealPolicy.java'),
    (Join-Path $source 'ui/LauncherIconController.java'),
    (Join-Path $source 'ui/Components/LumaStartupReveal.java'),
    (Join-Path $source 'ui/LaunchActivity.java')
)
$hashes = @($production | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash })
$sources = @($production[0], $production[1]) + @(
    (Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java'),
    (Join-Path $PSScriptRoot 'startup100/StartupPolicy100Test.java'),
    (Join-Path $PSScriptRoot 'startup100/LauncherIcon100Test.java'),
    (Join-Path $PSScriptRoot 'startup100/android/content/Context.java'),
    (Join-Path $PSScriptRoot 'startup100/android/content/ComponentName.java'),
    (Join-Path $PSScriptRoot 'startup100/android/content/pm/PackageManager.java'),
    (Join-Path $PSScriptRoot 'startup100/org/telegram/messenger/ApplicationLoader.java'),
    (Join-Path $PSScriptRoot 'startup100/org/telegram/messenger/FileLog.java'),
    (Join-Path $PSScriptRoot 'startup100/org/telegram/messenger/R.java')
)
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Startup policy/icon compilation failed.' }
& $java -Xmx128m -cp $classes org.telegram.messenger.StartupPolicy100Test
if ($LASTEXITCODE -ne 0) { throw 'Startup policy failed.' }
& $java -Xmx128m -cp $classes org.telegram.ui.LauncherIcon100Test
if ($LASTEXITCODE -ne 0) { throw 'Icon migration failed.' }

$activity = [IO.File]::ReadAllText($production[3])
$reveal = [IO.File]::ReadAllText($production[2])
function Check([bool]$Valid, [string]$Message) { if (!$Valid) { throw $Message } }
Check ($activity -match '(?s)onCreate\(Bundle savedInstanceState\).*?savedInstanceState == null.*?Intent.ACTION_MAIN.*?Intent.CATEGORY_LAUNCHER') 'Only a fresh normal launcher start may reveal.'
Check ($activity -match '(?s)maybeShowLumaStartupReveal\(\) \{.*?SharedConfig.appLocked.*?SharedConfig.isWaitingForPasscodeEnter.*?overlayPasscodeViews.*?voipLaunchedInBackground.*?isInPictureInPictureMode.*?isResumed && hasWindowFocus\(\)') 'Reveal must respect protected screens and foreground focus.'
foreach ($method in @('onPause\(\)', 'onDestroy\(\)', 'onUserInteraction\(\)', 'showPasscodeActivity\([^)]*\)', 'onNewIntent\(Intent intent\)', 'onNewIntent\(Intent intent, Browser.Progress progress\)')) {
    Check ($activity -match ('(?s)' + $method + '\s*\{.{0,140}?dismissLumaStartupReveal\(\)')) ('Reveal cleanup missing: ' + $method)
}
Check ($reveal.Contains('SharedConfig.animationsEnabled()') -and $reveal.Contains('ValueAnimator.areAnimatorsEnabled()') -and $reveal.Contains('Settings.Global.ANIMATOR_DURATION_SCALE') -and $reveal.Contains('LiteMode.isPowerSaverApplied()')) 'Reduced animation and power-saving controls must be honoured.'
Check ($reveal.Contains('setClickable(false)') -and $reveal.Contains('setFocusable(false)') -and $reveal.Contains('IMPORTANT_FOR_ACCESSIBILITY_NO')) 'Decorative reveal must not intercept input or accessibility focus.'
Check ($reveal.Contains('MAX_LIFETIME_MS = 900') -and $reveal.Contains('removeAllUpdateListeners()') -and $reveal.Contains('removeCallbacks(timeout)')) 'Reveal must have bounded lifetime and cleanup.'
Check (!$reveal.Contains('setKeepOnScreenCondition') -and !$reveal.Contains('Bitmap') -and !$reveal.Contains('ConnectionsManager')) 'Reveal must not delay startup, capture chats, or trigger network calls.'
$ns = 'http://schemas.android.com/apk/res/android'
$manifest = [xml][IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/AndroidManifest.xml'))
$defaultAlias = @($manifest.manifest.application.'activity-alias') | Where-Object { $_.GetAttribute('name', $ns) -eq 'org.telegram.messenger.DefaultIcon' }
Check ($defaultAlias.GetAttribute('icon', $ns) -eq '@mipmap/ic_luma_launcher' -and $defaultAlias.GetAttribute('roundIcon', $ns) -eq '@mipmap/ic_luma_launcher') 'Default alias must use the original gradient icon.'
$blackHole = @($manifest.manifest.application.'activity-alias') | Where-Object { $_.GetAttribute('name', $ns) -eq 'org.telegram.messenger.BlackHoleIcon' }
Check ($null -ne $blackHole) 'Black Hole must remain optional, not removed.'
foreach ($file in @('release/AndroidManifest.xml', 'release/AndroidManifest_SDK23.xml', 'release/AndroidManifest_standalone.xml', 'debug/AndroidManifest.xml', 'debug/AndroidManifest_SDK23.xml')) {
    $xml = [xml][IO.File]::ReadAllText((Join-Path $repo ('TMessagesProj/config/' + $file)))
    Check ($xml.manifest.application.GetAttribute('icon', $ns) -eq '@mipmap/ic_luma_launcher') ('Original application icon missing: ' + $file)
}
$adaptive = [xml][IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/res/mipmap-anydpi-v26/ic_luma_launcher.xml'))
Check ($null -eq $adaptive.'adaptive-icon'.monochrome) 'Original icon must not offer the palette-recoloured monochrome layer.'
Check ($adaptive.'adaptive-icon'.background.GetAttribute('drawable', $ns) -eq '@drawable/luma_launcher_background' -and $adaptive.'adaptive-icon'.foreground.GetAttribute('drawable', $ns) -eq '@drawable/icon_plane') 'Use the unchanged original gradient and white plane assets.'

if ($AndroidJar) {
    if (!(Test-Path -LiteralPath $AndroidJar)) { throw 'AndroidJar not found.' }
    $uiClasses = Join-Path $run 'android-classes'
    New-Item -ItemType Directory -Path $uiClasses -Force | Out-Null
    $uiSources = @($production[0], $production[2]) + @(
        'R.java', 'AndroidUtilities.java', 'LiteMode.java', 'MessagesController.java', 'SharedConfig.java'
    ) | ForEach-Object { if ([IO.Path]::IsPathRooted($_)) { $_ } else { Join-Path $PSScriptRoot ('startup100/org/telegram/messenger/' + $_) } }
    & $javac -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $uiClasses $uiSources
    if ($LASTEXITCODE -ne 0) { throw 'Actual reveal view failed Android SDK compilation.' }
    Write-Output 'Actual startup reveal compiles against the supplied Android SDK; application bridges are compile-only fixtures.'
}
for ($i = 0; $i -lt $production.Length; $i++) {
    Check ((Get-FileHash -LiteralPath $production[$i] -Algorithm SHA256).Hash -eq $hashes[$i]) 'Production files changed while tests ran; rerun.'
}
Write-Output 'Startup lifecycle/source/resource guards passed. This is not an on-device visual or launcher test.'
Write-Output ('Test output: ' + $run)
