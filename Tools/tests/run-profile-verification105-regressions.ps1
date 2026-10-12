param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-profile-verification105-tests', [string]$Aapt2 = '')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$sources = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'profile-verification105/fixtures') -Recurse -Filter '*.java').FullName)
$sources += Join-Path $PSScriptRoot 'profile-verification105/ProfileVerification105Test.java'
$sources += Join-Path $PSScriptRoot 'profile-verification105/ProfileVerificationVector105Test.java'
foreach ($name in @('LumaProfileVerification', 'LumaBuildPolicy')) {
    $sources += Join-Path $repo "TMessagesProj/src/main/java/org/telegram/messenger/$name.java"
}
. (Join-Path $PSScriptRoot 'feature-access150/add-fixtures.ps1')
$sources = @(Add-FeatureAccessFixtures $sources $repo)
& (Join-Path $JavaHome ('bin/javac' + $suffix)) -J-Xmx128m -encoding UTF-8 -d $run $sources
if ($LASTEXITCODE -ne 0) { throw 'Local verification policy compilation failed.' }
& (Join-Path $JavaHome ('bin/java' + $suffix)) -Xmx128m -cp $run org.telegram.messenger.ProfileVerification105Test
if ($LASTEXITCODE -ne 0) { throw 'Local verification policy regression failed.' }
$iconPath = Join-Path $repo 'TMessagesProj/src/main/res/drawable/luma_profile_verification.xml'
& (Join-Path $JavaHome ('bin/java' + $suffix)) '-Djava.awt.headless=true' -Xmx128m -cp $run org.telegram.messenger.ProfileVerificationVector105Test $iconPath (Join-Path $run 'vector-geometry-preview.png') (Join-Path $repo 'TMessagesProj/src/main/res/drawable/icon_plane.xml') (Join-Path $repo 'TMessagesProj/src/main/res/drawable/menu_verification.xml')
if ($LASTEXITCODE -ne 0) { throw 'Local verification vector geometry failed.' }
if ($Aapt2) {
    & $Aapt2 compile $iconPath -o $run
    if ($LASTEXITCODE -ne 0) { throw 'Local verification aapt2 vector compilation failed.' }
    Write-Output 'PASS: actual aapt2 vector compilation.'
}
$source = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java'))
$helper = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaProfileVerification.java'))
if ($helper -match 'TLRPC|ConnectionsManager|sendRequest|bot_verification|\.verified\s*=') {
    throw 'Local verification eligibility must have no protocol mutation or network dependency.'
}
if ($source -match 'user\.verified\s*\|\|\s*localVerified') { throw 'Local note must not create an in-card verificationInfoRow.' }
$headerStart = $source.IndexOf('final boolean localVerification = LumaProfileVerification.showsPrefix(')
$headerEnd = $source.IndexOf('nameTextView[a].setLeftDrawable(leftIcon);', $headerStart)
if ($headerStart -lt 0 -or $headerEnd -lt 0) { throw 'Missing native local prefix binding.' }
$header = $source.Substring($headerStart, $headerEnd - $headerStart)
foreach ($guard in @('currentAccount, user.id', 'getUserConfig().getClientUserId()', 'currentEncryptedChat != null',
        'user.bot_verification_icon', 'leftIcon == null && currentEncryptedChat == null',
        'setLeftDrawableOutside(true)', 'setLeftDrawableOutside(false)', 'set((Drawable) null, false)')) {
    if (!$header.Contains($guard)) { throw "Missing prefix precedence/reset guard: $guard" }
}
$drawableStart = $source.IndexOf('private Drawable getBotVerificationDrawable(long icon, boolean animated, int a, boolean localPreview)')
$drawableEnd = $source.IndexOf('private Drawable getEmojiStatusDrawable(', $drawableStart)
$drawable = $source.Substring($drawableStart, $drawableEnd - $drawableStart)
foreach ($guard in @('AndroidUtilities.dp(17)', 'offset(0, dp(1))', 'if (icon != 0)', 'else if (localPreview)',
        'R.drawable.luma_profile_verification', 'updateEmojiStatusDrawableColor()')) {
    if (!$drawable.Contains($guard)) { throw "Missing native prefix rendering contract: $guard" }
}
if ($drawable.IndexOf('if (icon != 0)') -gt $drawable.IndexOf('else if (localPreview)')) { throw 'Local preview overrides real server emoji.' }
$footerStart = $source.IndexOf('final boolean localVerification = LumaProfileVerification.showsFooter(')
$footerEnd = $source.IndexOf('} else if (position == infoAffiliateRow)', $footerStart)
if ($footerStart -lt 0 -or $footerEnd -lt 0) { throw 'Local note must reuse native infoSectionRow.' }
$footer = $source.Substring($footerStart, $footerEnd - $footerStart)
foreach ($guard in @('currentAccount, userId', 'getUserConfig().getClientUserId()', 'bot_verification != null, bot_manager_id != null',
        'else if (localVerification)', 'R.drawable.luma_profile_verification', 'setRelativeSize(cell.getTextView().getPaint().getFontMetricsInt())',
        'cell.setFixedSize(0)', 'cell.setText(sb)', 'cell.setFixedSize(14)', 'cell.setText(null)')) {
    if (!$footer.Contains($guard)) { throw "Missing native footer/recycled reset contract: $guard" }
}
if ($footer -match 'setCompoundDrawables|\.bot_verification\s*=|\.verified\s*=') { throw 'Footer must use inline span without server mutation.' }
if (!$source.Contains('new TextInfoPrivacyCell(mContext, resourcesProvider)') -or
        !$source.Contains('} else if (user.verified) {')) { throw 'Native footer or real post-name check was removed.' }
$icon = [xml][IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/res/drawable/luma_profile_verification.xml'))
$android = 'http://schemas.android.com/apk/res/android'
if ($icon.vector.GetAttribute('width', $android) -ne '17dp' -or $icon.vector.GetAttribute('height', $android) -ne '17dp') {
    throw 'Header marker intrinsic size must equal native wrapper size.'
}
$paths = @($icon.vector.path)
if ($paths.Count -ne 1 -or $paths[0].GetAttribute('fillType', $android) -ne 'evenOdd' -or
        [regex]::Matches($paths[0].GetAttribute('pathData', $android), 'M').Count -ne 2) {
    throw 'Badge must be a single tintable silhouette with a transparent plane cutout.'
}
Write-Output 'PASS: native prefix/footer integration, server/lock precedence, recycled resets and vector resource source guards.'
Write-Output 'Scope: real eligibility helper and build policy with modeled preferences; source/resource contracts, not device rendering or full Android compilation.'
Write-Output "Test artifacts: $run"
