param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-subscriptions-tests',
    [string]$Aapt2 = ''
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
$productionPath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionCatalog.java'
$screenPath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramSubscriptionActivity.java'
$settings = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java'))
$screen = [IO.File]::ReadAllText($screenPath)
$catalog = [IO.File]::ReadAllText($productionPath)
$advanced = $settings.IndexOf('items.add(SettingCell.Factory.of(BHG_ADVANCED_ROW,')
$subscription = $settings.IndexOf('items.add(SettingCell.Factory.of(LUNAGRAM_SUBSCRIPTION_ROW,')
$support = $settings.IndexOf('items.add(SettingCell.Factory.of(LUNAGRAM_SUPPORT_ROW,')
if ($advanced -lt 0 -or $subscription -le $advanced -or $support -le $subscription) {
    throw 'Subscription must follow Advanced and precede Author Support in main Settings.'
}
if ($settings -notmatch 'case LUNAGRAM_SUBSCRIPTION_ROW:\s*\{\s*BaseFragment screen = new LunagramSubscriptionActivity\(\);\s*screen.setCurrentAccount\(currentAccount\);\s*presentSettingFragment\(screen\)') {
    throw 'Subscription navigation must retain the selected Telegram account and native settings navigation.'
}
foreach ($required in @('UItem.asRadio2(', '.setChecked(plan == selectedPlan)',
        'PaymentMethod.TELEGRAM_STARS', 'PaymentMethod.CRYPTO_BOT_USDT', 'PaymentMethod.TON',
        'LunagramSubscriptionClient.purchaseUrl(selectedPlan, selectedPeriod)', 'client.isAuthenticated()',
        'LunagramSubscriptionAccessInfo', 'LunagramSubscriptionUnavailableInfo', 'LunagramSubscriptionConnectToActivate',
        'field.setSaveEnabled(false)', 'IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS',
        'IME_FLAG_NO_PERSONALIZED_LEARNING', 'field.setText("")', 'Theme.key_windowBackgroundGray')) {
    if (!$screen.Contains($required)) { throw "Missing UI safety/native/accessibility guard: $required" }
}
if (($screen + $catalog) -match '(?i)https?://|ton://|tg://|SharedPreferences|FileLog|System\.out|android\.util\.Log|sendRequest\(|startActivity\(|setPremium\(|\.setSubscription\(') {
    throw 'Subscription UI must delegate trusted links/private sessions to the client, never log codes or grant Telegram entitlements.'
}
if ($screen -match 'LumaGhostMode|BlackHoleVault|LunagramSubscriptionAccess\.canUse') {
    throw 'Selecting a plan must not itself mutate runtime access or privacy settings; the authority owns access.'
}
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
if ($Aapt2 -and !(Test-Path -LiteralPath $Aapt2)) { throw 'The provided Android resource compiler does not exist.' }
foreach ($locale in @('values', 'values-ru')) {
    $strings = [IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    foreach ($key in ([regex]::Matches($screen + $settings, 'R\.string\.(LunagramSubscription\w+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)) {
        if (!$strings.Contains('name="' + $key + '"')) { throw "Missing $locale subscription resource: $key" }
    }
    if ($Aapt2) {
        # Compile the new resources independently: upstream strings include legacy
        # formatting handled by the full Gradle resource merge, outside this probe.
        $selected = [regex]::Matches($strings, '<string name="LunagramSubscription\w+"[^>]*>.*?</string>', 'Singleline')
        $names = @($selected | ForEach-Object { [regex]::Match($_.Value, 'name="([^"]+)"').Groups[1].Value })
        if ($selected.Count -lt 48 -or (@($names | Sort-Object -Unique).Count -ne $names.Count)) { throw "Missing or duplicate subscription resources in $locale" }
        $resourceDir = Join-Path $run "res/$locale"
        $compiledDir = Join-Path $run 'compiled'
        New-Item -ItemType Directory -Path $resourceDir, $compiledDir -Force | Out-Null
        $resourcePath = Join-Path $resourceDir 'strings.xml'
        [IO.File]::WriteAllText($resourcePath, '<resources>' + [string]::Join("`n", @($selected | ForEach-Object { $_.Value })) + '</resources>', [Text.UTF8Encoding]::new($false))
        & $Aapt2 compile $resourcePath -o $compiledDir
        if ($LASTEXITCODE -ne 0) { throw "New $locale subscription resources failed Android resource compilation." }
    }
}
& $javac -J-Xmx128m -encoding UTF-8 -d $run $productionPath (Join-Path $PSScriptRoot 'subscriptions/LunagramSubscriptionCatalogTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Subscription catalog test compilation failed.' }
& $java -Xmx128m -cp $run org.telegram.messenger.LunagramSubscriptionCatalogTest
if ($LASTEXITCODE -ne 0) { throw 'Subscription catalog regression failed.' }
Write-Output 'PASS: main settings placement/account routing, native selection cells, translations and fail-closed UI guards.'
if ($Aapt2) { Write-Output 'PASS: new English and Russian subscription resources compile with Android aapt2.' }
Write-Output "Test artifacts: $run"
