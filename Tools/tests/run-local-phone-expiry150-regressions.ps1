param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/local-phone-expiry150'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix);$java=Join-Path $JavaHome ('bin/java'+$suffix)
function Body([string]$source,[string]$signature){
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing production signature: $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($i=$open+1;$i -lt $source.Length;$i++){
        if($source[$i] -eq '{'){$depth++};if($source[$i] -eq '}'){$depth--}
        if(!$depth){return $source.Substring($start,$i-$start+1)}
    }
    throw 'Unterminated native local phone guard.'
}
$source=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/FragmentUsernameBottomSheet.java'))
$guard=Body $source 'private static final class LocalPhonePreviewGuard'
$probe=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'anonymous-number150/LocalPhoneExpiry150Probe.java.template')).Replace('/* ACTUAL_GUARD */',$guard)
$profile=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java'))
$probe=$probe.Replace('/* ACTUAL_PROFILE_REFRESH */',(Body $profile 'private void refreshLocalSubscriptionDisplay()'))
$probe=$probe.Replace('/* ACTUAL_PROFILE_SHEET */',((Body $profile 'private void dismissLocalStarRatingSheet()')+"`n"+(Body $profile 'private void trackLocalStarRatingSheet(')))
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $run -Force | Out-Null
$path=Join-Path $run 'LocalPhoneExpiry150Probe.java';[IO.File]::WriteAllText($path,$probe,[Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $path
if($LASTEXITCODE -ne 0){throw 'Local phone production observer probe did not compile.'}
& $java -Xmx128m -cp $run LocalPhoneExpiry150Probe
if($LASTEXITCODE -ne 0){throw 'Local phone observer lifecycle regression failed.'}
foreach($control in @(
    @{Name='retained-expiry';Old='&& LumaAnonymousNumber.isEnabled(account)';New='';Expected='expiry closes already-open local phone'},
    @{Name='owner-reuse';Old='owner == UserConfig.getInstance(account).getClientUserId()';New='owner > 0';Expected='stale click checks actual owner'},
    @{Name='observer-leak';Old='NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);';New='';Expected='expiry removes every observer'},
    @{Name='stale-profile-rating';Old='ratingView.set(LumaStarRating.getDisplayRating(currentAccount, userId, userInfo.stars_rating));';New='if (ratingView.rating == null) ratingView.set(LumaStarRating.getDisplayRating(currentAccount, userId, userInfo.stars_rating));';Expected='open self-profile resets local rating on expiry'},
    @{Name='retained-local-level-popup';Old='            dismissLocalStarRatingSheet();';New='';Expected='expired local level popup closes'},
    @{Name='old-sheet-dismissal';Old='if (localStarRatingSheet == sheet) {';New='if (sheet != null) {';Expected='delayed old dismissal cannot clear newer local sheet'}
)){
    $mutant=$probe.Replace($control.Old,$control.New);if($mutant -eq $probe){throw "Negative mutation not applied: $($control.Name)"}
    $folder=Join-Path $run $control.Name;New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $file=Join-Path $folder 'LocalPhoneExpiry150Probe.java';[IO.File]::WriteAllText($file,$mutant,[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $folder $file
    if($LASTEXITCODE -ne 0){throw 'Negative local-phone observer probe did not compile.'}
    $preference=$ErrorActionPreference;$ErrorActionPreference='Continue'
    $result=(& $java -Xmx128m -cp $folder LocalPhoneExpiry150Probe 2>&1|Out-String);$exitCode=$LASTEXITCODE;$ErrorActionPreference=$preference
    if($exitCode -eq 0 -or !$result.Contains($control.Expected)){throw "Negative control missed: $($control.Name)"}
}
foreach($required in @('final LocalPhonePreviewGuard guard = localPhonePreview',
    'if (guard != null) sheet.setOnDismissListener(guard::detach)',
    'open(context, TYPE_PHONE, phone, owner, preview, resourcesProvider, true, currentAccount)',
    'open(context, type, name, owner, info, resourcesProvider, false)')) {
    if(!$source.Contains($required)){throw "Missing local-only/native boundary: $required"}
}
if(([regex]::Matches($source,'if \(guard != null && !guard.allowAction\(\)\) return;')).Count -ne 2){throw 'Both copy and browser actions must recheck the local owner/access.'}
if($source.IndexOf('if (guard != null) guard.attach();') -lt $source.IndexOf('sheet.show();')){throw 'Never register a local observer for a failed sheet show.'}
foreach($required in @('addObserver(this, NotificationCenter.lunagramSubscriptionChanged)',
    'removeObserver(this, NotificationCenter.lunagramSubscriptionChanged)',
    'if (id == NotificationCenter.lunagramSubscriptionChanged)', 'refreshLocalSubscriptionDisplay();',
    'AndroidUtilities.updateVisibleRows(listView);')) {
    if(!$profile.Contains($required)){throw "Missing native profile expiry rebind/lifecycle: $required"}
}
if(!$profile.Contains('if (displayRating != userFull.stars_rating) trackLocalStarRatingSheet(sheet)')){throw 'Only a local override may acquire rating-sheet lifecycle tracking.'}
foreach($signature in @('public void onFragmentDestroy()', 'public void onPause()')){
    if(!(Body $profile $signature).Contains('dismissLocalStarRatingSheet();')){throw 'Native fragment lifecycle must clean up its local rating popup.'}
}
Write-Output 'PASS: six negative controls; native profile/header/footer rebind, local-rating popup expiry and genuine server boundary.'
Write-Output "Test artifacts: $run"
