param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-anonymous-number150-tests', [string]$Aapt2)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$sources = @(
    'anonymous-number150/AnonymousNumber150Test.java',
    'fixtures/android/content/SharedPreferences.java',
    'fixtures/org/telegram/messenger/UserConfig.java',
    'logout102/fixtures/android/content/Context.java',
    'logout102/fixtures/org/telegram/messenger/BlackHoleVault.java',
    'local-gifts102/fixtures/org/telegram/messenger/ApplicationLoader.java',
    'profile-verification105/fixtures/org/telegram/messenger/BuildConfig.java'
) | ForEach-Object { Join-Path $PSScriptRoot $_ }
foreach ($name in @('LumaAnonymousNumber', 'LumaAccountData', 'LumaBuildPolicy')) {
    $sources += Join-Path $repo "TMessagesProj/src/main/java/org/telegram/messenger/$name.java"
}
. (Join-Path $PSScriptRoot 'feature-access150/add-fixtures.ps1')
$sources = @(Add-FeatureAccessFixtures $sources $repo)
& (Join-Path $JavaHome ('bin/javac' + $suffix)) -J-Xmx128m -encoding UTF-8 -d $run $sources
if ($LASTEXITCODE -ne 0) { throw 'Anonymous-number production model compilation failed.' }
& (Join-Path $JavaHome ('bin/java' + $suffix)) -Xmx128m -cp $run org.telegram.messenger.AnonymousNumber150Test
if ($LASTEXITCODE -ne 0) { throw 'Anonymous-number model regressions failed.' }
$source = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaAnonymousNumber.java'))
if ($source -match 'ConnectionsManager|sendRequest|Https?URLConnection|BlackHolePrivateData|TLRPC|normalizePriceUsd|getPriceUsd|DEFAULT_PRICE_USD') {
    throw 'The visual anonymous-number model must remain local and derive USD only from the supplied rate.'
}
$sheet = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/FragmentUsernameBottomSheet.java'))
$localStart = $sheet.IndexOf('public static void openLocalPhone(')
if ($localStart -lt 0) { throw 'Missing native local phone card entry point.' }
$local = $sheet.Substring($localStart)
foreach ($guard in @('owner.id != UserConfig.getInstance(currentAccount).getClientUserId()',
        '!LumaAnonymousNumber.isEnabled(currentAccount)', '!phone.equals(LumaAnonymousNumber.getPhone(currentAccount))',
        'new TL_fragment.TL_collectibleInfo()', 'LumaAnonymousNumber.getPurchaseDate(currentAccount)',
        'LumaAnonymousNumber.getPriceNanoTon(currentAccount)', 'LumaAnonymousNumber.getUsdCents(preview.crypto_amount,',
        'MessagesController.getInstance(currentAccount).config.tonUsdRate.get()',
        'preview.url = "https://fragment.com/numbers"',
        'open(context, TYPE_PHONE, phone, owner, preview, resourcesProvider, true, currentAccount)')) {
    if (!$local.Contains($guard)) { throw "Missing native phone-card source contract: $guard" }
}
if ($local -match 'sendRequest|ConnectionsManager|nextInt|Random|owner\.\w+\s*=|LumaAnonymousNumberSheetMessage') {
    throw 'Local phone preview must neither mutate the owner nor request/claim a real collectible.'
}
foreach ($guard in @('open(context, type, name, owner, info, resourcesProvider, false)',
        'localPhonePreview && info.amount < 0 ? ""', 'R.raw.fragment', 'R.string.FragmentPhoneMessage',
        'R.string.FragmentPhoneCopy', 'chipAvatar.setForUserOrChat(owner, avatarDrawable)')) {
    if (!$sheet.Contains($guard)) { throw "Native card behavior was replaced: $guard" }
}
$settings = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/ExperimentalFeaturesActivity.java'))
$start = $settings.IndexOf('private void showAnonymousNumberPriceDialog()')
$end = $settings.IndexOf('private void showProfileInputDialog(', $start)
if ($start -lt 0 -or $end -le $start) { throw 'Missing anonymous price/date settings handlers.' }
$dialogs = $settings.Substring($start, $end - $start)
foreach ($guard in @('final long ownerId = getUserConfig().getClientUserId()',
        'getUserConfig().getClientUserId() != ownerId || !LumaAnonymousNumber.isEnabled(currentAccount)',
        'DigitsKeyListener.getInstance("0123456789.,")', 'new InputFilter.LengthFilter(19)',
        'LumaAnonymousNumber.setPriceTon(currentAccount, ownerId,', 'AlertsCreator.createDatePickerDialog(',
        'new android.app.TimePickerDialog(', 'date.setLenient(false)', 'date.set(year, month, day, hour, minute, 0)',
        'LumaAnonymousNumber.setPurchaseDate(currentAccount, ownerId, seconds)')) {
    if (!$dialogs.Contains($guard)) { throw "Missing price/date owner or precision guard: $guard" }
}
Write-Output 'PASS: shared native card, Telegram-rate conversion, picker precision and owner-checked local-only source contracts.'
if ($Aapt2 -and !(Test-Path -LiteralPath $Aapt2)) { throw 'The supplied Android resource compiler does not exist.' }
$priceDateKeys = @('LumaAnonymousNumberPriceValue', 'LumaAnonymousNumberPriceTitle', 'LumaAnonymousNumberPriceInfo',
    'LumaAnonymousNumberPriceInvalid', 'LumaAnonymousNumberDateValue', 'LumaAnonymousNumberDateTitle',
    'LumaAnonymousNumberTimeTitle', 'LumaAnonymousNumberDateInvalid')
foreach ($locale in @('values', 'values-ru')) {
    # Telegram's asset generator consumes strings.xml; standalone values XML is
    # not sufficient for LocaleController. Compile only our resources because
    # upstream strings use the application's separate localization pipeline.
    $xml = [xml][IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    $selected = foreach ($key in $priceDateKeys) {
        $entries = @($xml.resources.string | Where-Object { $_.name -eq $key })
        if ($entries.Count -ne 1 -or [string]::IsNullOrWhiteSpace($entries[0].InnerText)) {
            throw "Missing or duplicate number setting resource: $locale/$key"
        }
        $entries[0].OuterXml
    }
    if ($Aapt2) {
        $resourceDir = Join-Path $run "res/$locale"
        $compiledDir = Join-Path $run 'compiled'
        New-Item -ItemType Directory -Path $resourceDir, $compiledDir -Force | Out-Null
        $resourcePath = Join-Path $resourceDir 'strings.xml'
        [IO.File]::WriteAllText($resourcePath, '<resources>' + [string]::Join("`n", $selected) + '</resources>', [Text.UTF8Encoding]::new($false))
        & $Aapt2 compile $resourcePath -o $compiledDir
        if ($LASTEXITCODE -ne 0) { throw "Number setting resources failed Android compilation: $locale" }
    }
}
if ($Aapt2) { Write-Output 'PASS: TON/date setting translations enter the localization pipeline and compile with Android aapt2.' }
Write-Output 'Scope: actual helpers with modeled Android preferences/account IDs; no network, no Android UI/device verification.'
Write-Output "Test artifacts: $run"
