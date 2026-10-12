param(
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$AndroidJar='',
    [string]$OutputRoot='D:\CodexBuildCache\Lunagram-gift-preview105-tests'
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix=if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
$java=Join-Path $JavaHome ('bin/java'+$suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
function Body([string]$source,[string]$signature) {
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production signature: $signature" }
    $open=$source.IndexOf('{',$start);$depth=1
    for ($i=$open+1;$i -lt $source.Length;$i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if (!$depth) { return $source.Substring($start,$i-$start+1) }
    }
    throw "Unterminated production signature: $signature"
}
$screen=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Gifts/LumaLocalGiftPreview.java'
$source=[IO.File]::ReadAllText($screen)
foreach ($import in [regex]::Matches($source,'(?m)^import (org\.telegram\.[\w.]+);')) {
    $relative=$import.Groups[1].Value.Replace('.','/')+'.java'
    if ($relative -eq 'org/telegram/messenger/R.java') { continue }
    if (!(Test-Path -LiteralPath (Join-Path $repo ('TMessagesProj/src/main/java/'+$relative)))) {
        throw "Native collaborator import does not exist: $relative"
    }
}
$personal=Body $source 'private static void showPersonal('
$show=Body $source 'public static void show(BaseFragment fragment, LumaLocalGifts.LocalSavedGift gift, PagerSource pager)'
foreach ($guard in @('gift.ownerUserId <= 0','gift.ownerUserId != UserConfig.getInstance(account).getClientUserId()',
    '!(gift.gift instanceof TL_stars.TL_starGiftUnique)','unique.attributes == null || unique.resell_amount != null',
    'if (LumaBuildPolicy.isPersonalEdition())','if (account != UserConfig.selectedAccount) return;',
    'showPersonal(fragment, gift, unique, context, account, resources, pager);')) {
    if (!$show.Contains($guard)) { throw "Missing read-only native preview boundary: $guard" }
}
foreach ($guard in @('new StarGiftSheet(context, account, gift.ownerUserId, resources)',
    '.setProfilePreviewPager(pager)', '.setLocalPreview(fragment, gift)', 'fragment.showDialog(sheet)')) {
    if (!$personal.Contains($guard)) { throw "Missing native NFT preview integration: $guard" }
}
if ($personal -match 'GiftCell|LumaLocalGiftPreviewInfo|new AlertDialog|\.set\(gift') { throw 'Personal preview must use the local binder, never a normal gift tile or generic owned gift setter.' }
if ($source -match 'ConnectionsManager|sendRequest\(|TL_payments|saveStarGift|transferStarGift|resellStarGift') { throw 'Preview must never acquire transaction capabilities.' }
$header=Body $source 'public static StarGiftSheet.TopView createPreviewHeader('
foreach($guard in @('top.setGift(unique, false, false, false, false, false)', 'modelName, null, null', 'top.optionsView.setVisibility(View.VISIBLE)')) {
    if(!$header.Contains($guard)){throw "Missing read-only native header/menu/model subtitle guard: $guard"}
}
if (!$source.Contains('if (!LumaBuildPolicy.isPersonalEdition())') -or !$source.Contains('local.setText(getString(R.string.LumaLocalGiftPreviewInfo))')) { throw 'Public preview must retain its local-only notice.' }

$native=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Stars/StarGiftSheet.java'))
$topSource=Body $native 'public static class TopView'
foreach ($guard in @('public final FrameLayout imageLayout','public final ImageView optionsView',
    'setGiftImage(imageView[page].getImageReceiver(), gift, 160)', 'buttonsLayout.setVisibility(withButtons ? View.VISIBLE : View.GONE)',
    'setPattern(page, findAttribute(gift.attributes, TL_stars.starGiftAttributePattern.class), false)',
    'this.pattern[0].attach()', 'this.pattern[0].detach()', 'public int getFinalHeight()')) {
    if (!$topSource.Contains($guard)) { throw "Native header API/lifecycle changed; re-audit integration: $guard" }
}
$emoji=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/AnimatedEmojiDrawable.java'))
$receiver=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/ImageReceiver.java'))
foreach ($guard in @('new AnimatedEmojiDrawable(', 'receiver.setUniqKeyPrefix("luma_gift_preview_',
    'for (AnimatedEmojiDrawable pattern : previewPatterns)', 'SharedConfig.animationsEnabled()', 'AndroidUtilities.getAnimatorDurationScale()')) {
    if (!$source.Contains($guard)) { throw "Missing isolated/reduced-motion preview guard: $guard" }
}
if ($source -match 'AnimatedEmojiDrawable\.make\(' -or !$emoji.Contains('imageReceiver.setAllowLoadingOnAttachedOnly(true)') -or
        !$receiver.Contains('if (allowLoadingOnAttachedOnly && !attachedToWindow)')) {
    throw 'Private preview must not change globally shared animation, and its key must be installed before deferred attachment load.'
}
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$probeTemplate=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'local-gifts-ui102/PreviewAnimationProbe.java.template'))
$probe=$probeTemplate.Replace('// PRODUCTION_PREVIEW_TOP_VIEW',(Body $source 'private static final class PreviewTopView'))
$probe=$probe.Replace('// PRODUCTION_NATIVE_SET_PATTERN',(Body $topSource 'public void setPattern('))
$probe=$probe.Replace('// PRODUCTION_NATIVE_PATTERN_DEFAULT',(Body $topSource 'protected AnimatedEmojiDrawable createReadOnlyPatternDrawable('))
$probePath=Join-Path $run 'PreviewAnimationProbe.java'
[IO.File]::WriteAllText($probePath,$probe,[Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probePath
if ($LASTEXITCODE -ne 0) { throw 'Production native header lifecycle probe did not compile.' }
& $java -Xmx128m -cp $run PreviewAnimationProbe
if ($LASTEXITCODE -ne 0) { throw 'Native NFT header lifecycle regression failed.' }
$negatives=@(
    @{Name='late-load-after-dismiss';Old='receiver.setAllowStartLottieAnimation(running);';New='receiver.setAllowStartLottieAnimation(true);';Expected='pattern binding must not bypass'},
    @{Name='hidden-craft-height';Old='setMeasuredDimension(getMeasuredWidth(), getFinalHeight());';New='setMeasuredDimension(getMeasuredWidth(), 900);';Expected='hidden craft pages'},
    @{Name='missing-detach';Old='super.onDetachedFromWindow(); // Native TopView detaches its animated pattern too.';New='';Expected='native pattern and identity observers detached'},
    @{Name='pattern-play-bypass';Old='if (receiver != null && !receiver.getAllowStartAnimation()) receiver.stopAnimation();';New='';Expected='pattern binding must not bypass'},
    @{Name='pattern-dismiss';Old='if (pattern != null) setReceiverAnimation(pattern.getImageReceiver(), running);';New='';Expected='native pattern animates when allowed'}
)
foreach ($control in $negatives) {
    $mutant=$probe.Replace($control.Old,$control.New)
    if ($mutant -eq $probe) { throw "Negative control not applied: $($control.Name)" }
    $folder=Join-Path $run $control.Name;New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $path=Join-Path $folder 'PreviewAnimationProbe.java';[IO.File]::WriteAllText($path,$mutant,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $folder $path
    if ($LASTEXITCODE -ne 0) { throw "Negative control failed to compile: $($control.Name)" }
    $oldPreference=$ErrorActionPreference;$ErrorActionPreference='Continue'
    $result=(& $java -Xmx128m -cp $folder PreviewAnimationProbe 2>&1 | Out-String);$code=$LASTEXITCODE
    $ErrorActionPreference=$oldPreference
    if ($code -eq 0 -or !$result.Contains($control.Expected)) { throw "Negative control missed its defect: $($control.Name)" }
}
Write-Output 'PASS: native-only personal routing, exact visual allowlist, no transaction callbacks, isolated main/pattern lifecycle, pre26 reduced motion and five negative controls.'

if (!$AndroidJar) {
    $sdk=if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if ($sdk) { $AndroidJar=Join-Path $sdk 'platforms/android-36/android.jar' }
}
if ($AndroidJar) {
    if (!(Test-Path -LiteralPath $AndroidJar)) { throw 'Configured Android platform jar is missing.' }
    $template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'local-gifts-ui102/PreviewNativeUi.java.template'))
    $parts=[regex]::Matches($template,'(?m)^// FILE: ([^\r\n]+)\r?\n')
    $sources=@()
    for ($i=0;$i -lt $parts.Count;$i++) {
        $start=$parts[$i].Index+$parts[$i].Length
        $end=if ($i+1 -lt $parts.Count) { $parts[$i+1].Index } else { $template.Length }
        $path=Join-Path $run $parts[$i].Groups[1].Value
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        [IO.File]::WriteAllText($path,$template.Substring($start,$end-$start),[Text.UTF8Encoding]::new($false));$sources+=$path
    }
    $resources='package org.telegram.messenger; public final class R {'
    foreach ($kind in @('string','raw')) {
        $resources+='public static final class '+$kind+'{';$counter=1
        foreach ($key in @([regex]::Matches($source,('R\.'+$kind+'\.(\w+)')) | ForEach-Object {$_.Groups[1].Value} | Sort-Object -Unique)) {
            $resources+='public static final int '+$key+'='+($counter++)+';'
        }
        $resources+='}'
    }
    $resources+='}';$resourcePath=Join-Path $run 'org/telegram/messenger/R.java'
    [IO.File]::WriteAllText($resourcePath,$resources,[Text.UTF8Encoding]::new($false));$sources+=$resourcePath;$sources+=$screen
    $classes=Join-Path $run 'android-classes';New-Item -ItemType Directory -Path $classes -Force | Out-Null
    & $javac -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $classes $sources
    if ($LASTEXITCODE -ne 0) { throw 'Actual gift preview Android API/model contract compilation failed.' }
    Write-Output 'PASS: actual LumaLocalGiftPreview.java compiles against Android36 with modeled Telegram UI collaborators; not a full application build or device render.'
} else {
    Write-Output 'NOTE: Android API compilation not run (pass -AndroidJar or set ANDROID_HOME for Android36).'
}
Write-Output "Test artifacts: $run"
