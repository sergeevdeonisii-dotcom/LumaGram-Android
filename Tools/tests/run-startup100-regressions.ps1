param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs/startup100'),
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
    (Join-Path $source 'ui/LaunchActivity.java'),
    (Join-Path $source 'utils/localization/Localization.java'),
    (Join-Path $source 'messenger/LocaleController.java'),
    (Join-Path $repo 'buildSrc/src/main/kotlin/org/telegram/plugin/TelegramBuildAppPlugin.kt'),
    (Join-Path $repo 'TMessagesProj/src/main/res/values/strings.xml'),
    (Join-Path $repo 'TMessagesProj/src/main/res/values-ru/strings.xml')
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
function Method([string]$Source, [string]$Signature) {
    $start = $Source.IndexOf($Signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw ('Missing method: ' + $Signature) }
    $open = $Source.IndexOf('{', $start)
    $depth = 1
    for ($end = $open + 1; $end -lt $Source.Length -and $depth -gt 0; $end++) {
        if ($Source[$end] -eq '{') { $depth++ }
        if ($Source[$end] -eq '}') { $depth-- }
    }
    if ($depth -ne 0) { throw ('Unbalanced method: ' + $Signature) }
    return $Source.Substring($open + 1, $end - $open - 2)
}
$owner = Method $activity 'private boolean canShowLumaStartupNotes()'
foreach ($guard in @('SharedConfig.appLocked', 'SharedConfig.isWaitingForPasscodeEnter', 'overlayPasscodeViews',
        'voipLaunchedInBackground', 'isInPictureInPictureMode', 'VoIPService.getSharedInstance()',
        'VoIPFragment.getInstance()', 'GroupCallActivity.groupCallInstance', 'isClientActivated()',
        'switchingAccount', 'termsOfServiceView', 'blockingUpdateView', 'MainTabsActivity',
        'layersActionBarLayout', 'rightActionBarLayout', 'getVisibleDialog()', 'isTransitionAnimationInProgress()', 'PhotoViewer', 'SecretMediaViewer', 'ArticleViewer')) {
    Check ($owner.Contains($guard)) ('Release notes protection missing: ' + $guard)
}
foreach ($method in @('protected void onPause()', 'protected void onDestroy()', 'public void showPasscodeActivity(', 'public void onNewIntent(Intent intent)',
        'public void onNewIntent(Intent intent, Browser.Progress progress)')) {
    Check ((Method $activity $method).TrimStart().StartsWith('dismissLumaStartupReveal();')) ('Release notes cleanup missing: ' + $method)
}
$focus = Method $activity 'public void onWindowFocusChanged(boolean hasFocus)'
$interaction = Method $activity 'public void onUserInteraction()'
Check (!$focus.Contains('dismissLumaStartupReveal') -and !$interaction.Contains('dismissLumaStartupReveal')) 'Own dialog focus loss and reading touches must not dismiss notes.'
Check ($activity -match '(?s)NotificationCenter.appDidLogout\) \{\s*dismissLumaStartupReveal\(\);' -and
       $activity -match '(?s)NotificationCenter.didStartedCall\) \{\s*dismissLumaStartupReveal\(\);') 'Logout and incoming calls must immediately close notes.'
Check ($activity.Contains('.add(NotificationCenter.didStartedCall)')) 'Call cleanup must actually subscribe.'
Check ($activity.Contains('if (!ApplicationLoader.isScreenOn) dismissLumaStartupReveal();') -and
       $activity.Contains('if (SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter) dismissLumaStartupReveal();')) 'Screen-off and lock notifications must close notes.'
Check ((Method $activity 'private void onPowerSaver(boolean applied)').Contains('onMotionPreferenceChanged()')) 'A newly applied PowerSaver must settle motion while retaining notes.'
Check ((Method $activity 'public boolean onBackPressed(boolean invoked)').Contains('lumaStartupReveal.isShowing()')) 'Back must dismiss the sheet rather than navigate behind it.'
Check ((Method $activity 'private void dismissLumaStartupReveal()').Contains('dismissForLifecycle()')) 'Lifecycle cleanup must not leave a closing animation over protected UI.'
Check ($reveal.Contains('SharedConfig.animationsEnabled()') -and $reveal.Contains('ValueAnimator.areAnimatorsEnabled()') -and $reveal.Contains('Settings.Global.ANIMATOR_DURATION_SCALE') -and $reveal.Contains('LiteMode.isPowerSaverApplied()')) 'Reduced animation and power-saving controls must be honoured.'
Check ($reveal.Contains('extends BottomSheet') -and $reveal.Contains('new ScrollView(context)') -and $reveal.Contains('COMPLEX_UNIT_SP') -and $reveal.Contains('setMinimumHeight(AndroidUtilities.dp(48))') -and $reveal.Contains('done.setOnClickListener(view -> dismiss())')) 'Notes must retain native scroll/theme/font scaling and a usable Continue action.'
Check ((Method $reveal 'public void show()').Contains('containerView.setVisibility(View.VISIBLE)')) 'Native no-animation sheet container must actually become visible.'
Check ($reveal.Contains('continueButton.getMeasuredHeight()') -and $reveal.Contains('noteHeightLimit') -and
       $reveal.Contains('setCanDismissWithSwipe(false)')) 'Scrolling must reserve the font-scaled Continue action and must not become sheet-dismiss gestures.'
Check ($reveal.Contains('DURATION_MS = 640') -and $reveal.Contains('MAX_MOTION_MS = 800') -and $reveal.Contains('removeAllUpdateListeners()') -and $reveal.Contains('removeCallbacks(finishMotion)')) 'Brand motion must be bounded and clean up.'
Check (!(Method $reveal 'private void startAnimation()').Contains('dismiss') -and !(Method $reveal 'private void stopAnimation()').Contains('dismiss')) 'Motion ending must not remove readable notes.'
Check ((Method $reveal 'public static LumaStartupReveal maybeShow(').IndexOf('preferences.edit()') -lt 0) 'Do not mark a version before construction/show succeeds.'
Check ($reveal.Contains('super.dispatchDraw(canvas);') -and $reveal.Contains('drawn = true;') -and
       $reveal.Contains('LumaStartupRevealPolicy.canMarkSeen(isShowing(), content.isAttachedToWindow(), drawn,') -and
       $reveal.Contains('getWindow().getDecorView().hasWindowFocus()') -and
       $reveal.Contains('Math.max(version, preferences.getInt(SEEN_VERSION, 0))')) 'Seen marker requires actual attached focused content and must not lower the installed-version marker.'
Check ($reveal.Contains('BuildConfig.BUILD_VERSION_STRING') -and $reveal.Contains('hasRelease15Notes(BuildConfig.BUILD_VERSION_STRING)') -and
       $reveal.Contains('LumaBuildPolicy.isPersonalEdition()') -and $reveal.Contains('LumaUpdateWelcomeFuture')) 'Display the actual installed version; edition-gate personal notes and avoid stale future claims.'
Check (!$reveal.Contains('setKeepOnScreenCondition') -and !$reveal.Contains('Bitmap') -and !$reveal.Contains('ConnectionsManager')) 'Notes must not delay startup, capture chats, or trigger network calls.'

# Execute the actual Activity focus/touch bodies with a modeled owner, not an Android device.
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'startup100/StartupOwnerFocus15Probe.java.template'))
$probe = Join-Path $run 'StartupOwnerFocus15Probe.java'
$template = $template.Replace('/* ACTUAL_FOCUS_BODY */', $focus).Replace('/* ACTUAL_INTERACTION_BODY */', $interaction)
[IO.File]::WriteAllText($probe, $template, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $probe
if ($LASTEXITCODE -ne 0) { throw 'Extracted focus/touch probe compilation failed.' }
& $java -Xmx128m -cp $classes StartupOwnerFocus15Probe
if ($LASTEXITCODE -ne 0) { throw 'Extracted owner focus/touch behavior failed.' }

$expectedKeys = @('LumaUpdateWelcomeName', 'LumaUpdateWelcomeVersion', 'LumaUpdateWelcomeChanges',
    'LumaUpdateWelcomeContinue', 'LumaUpdateWelcomeRoundCamera', 'LumaUpdateWelcomeRoundSettings',
    'LumaUpdateWelcomeVerification', 'LumaUpdateWelcomeNumberValue', 'LumaUpdateWelcomePersonalGifts', 'LumaUpdateWelcomeFuture')
foreach ($locale in @('values', 'values-ru')) {
    # Telegram's localization generator only includes strings.xml. Merely having
    # R.string entries in another XML file is insufficient for LocaleController.
    $notesXml = [xml][IO.File]::ReadAllText((Join-Path $repo ('TMessagesProj/src/main/res/' + $locale + '/strings.xml')))
    $strings = @($notesXml.resources.string | Where-Object { $expectedKeys -contains $_.name })
    Check ($strings.Count -eq $expectedKeys.Count) 'Release notes must be present in the generated localization input.'
    foreach ($key in $expectedKeys) {
        $entry = @($strings | Where-Object { $_.name -eq $key })
        Check ($entry.Count -eq 1 -and ![string]::IsNullOrWhiteSpace($entry[0].InnerText)) ('Missing release notes copy: ' + $locale + '/' + $key)
    }
    Check ((@($strings | Where-Object { $_.name -eq 'LumaUpdateWelcomeVersion' })[0].InnerText).Contains('%1$s')) 'Installed version must be interpolated.'
    Check ((@($strings | Where-Object { $_.name -eq 'LumaUpdateWelcomeName' })[0].InnerText) -ceq 'Lunagram') 'Use the requested Latin Lunagram brand in every locale.'
    $numberCopy = (@($strings | Where-Object { $_.name -eq 'LumaUpdateWelcomeNumberValue' })[0].InnerText)
    Check ($numberCopy.Contains('TON') -and $numberCopy.Contains('USD') -and $numberCopy -match 'date|дат') 'Number-value notes must include TON, USD conversion and the date.'
    Check (!(Test-Path -LiteralPath (Join-Path $repo ('TMessagesProj/src/main/res/' + $locale + '/luma_startup_notes.xml')))) 'Do not reintroduce duplicate standalone startup strings.'
}

$plugin = [IO.File]::ReadAllText($production[6])
Check ($plugin -match '(?s)stringsXml\.from\(\s*telegramModule\.fileTree\("src/main/res/values"\)\s*\{\s*include\("strings.xml"\)' -and
       $plugin -match '(?s)localizationFiles\.from\(\s*telegramModule\.fileTree\("src/main/res"\)\s*\{\s*include\("values-\*/strings.xml"\)' -and
       $plugin.Contains('TelegramStringsTask::assetsOutputDir')) 'Startup resource test must follow the app plugin localization-asset input paths.'
# Run the real lookup bodies against the selected XML inputs. The negative case
# deliberately keeps Android resources/IDs but removes the strings from the
# generator input, reproducing the old standalone-file-only LOC_ERR failure.
$localization = [IO.File]::ReadAllText($production[4])
$localeController = [IO.File]::ReadAllText($production[5])
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'startup100/StartupLocalization15Probe.java.template'))
$template = $template.Replace('/* ACTUAL_RES_ID_BODY */', (Method $localization 'public String getByResId('))
$template = $template.Replace('/* ACTUAL_RES_NAME_BODY */', (Method $localization 'public String getByResName('))
$template = $template.Replace('/* ACTUAL_RES_NAME_OR_ID_BODY */', (Method $localization 'public String getByResNameOrResId('))
$template = $template.Replace('/* ACTUAL_GET_BODY */', (Method $localization 'public String get(int resNameHash)'))
$template = $template.Replace('/* ACTUAL_GET_STRING_BODY */', (Method $localeController 'private String getStringV2('))
$localizationProbe = Join-Path $run 'StartupLocalization15Probe.java'
[IO.File]::WriteAllText($localizationProbe, $template, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $localizationProbe
if ($LASTEXITCODE -ne 0) { throw 'Extracted startup localization lookup probe compilation failed.' }
& $java -Xmx128m -cp $classes StartupLocalization15Probe $production[7] $production[8]
if ($LASTEXITCODE -ne 0) { throw 'Startup localization lookup regression failed.' }

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
    $uiSources = @($production[0], $production[2], (Join-Path $source 'messenger/LumaBuildPolicy.java')) + @(
        'R.java', 'AndroidUtilities.java', 'LiteMode.java', 'MessagesController.java', 'SharedConfig.java',
        'BuildConfig.java', 'LocaleController.java'
    ) | ForEach-Object { if ([IO.Path]::IsPathRooted($_)) { $_ } else { Join-Path $PSScriptRoot ('startup100/org/telegram/messenger/' + $_) } }
    $uiSources += @('BottomSheet.java', 'Theme.java') | ForEach-Object { Join-Path $PSScriptRoot ('startup100/org/telegram/ui/ActionBar/' + $_) }
    & $javac -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $uiClasses $uiSources
    if ($LASTEXITCODE -ne 0) { throw 'Actual reveal view failed Android SDK compilation.' }
    Write-Output 'Actual startup reveal compiles against the supplied Android SDK; application bridges are compile-only fixtures.'
}
for ($i = 0; $i -lt $production.Length; $i++) {
    Check ((Get-FileHash -LiteralPath $production[$i] -Algorithm SHA256).Hash -eq $hashes[$i]) 'Production files changed while tests ran; rerun.'
}
Write-Output 'Startup lifecycle/source/resource guards passed. This is not an on-device visual or launcher test.'
Write-Output ('Test output: ' + $run)
