param(
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$OutputRoot='D:/CodexBuildCache/Lunagram-access150',
    [string]$AndroidJar,
    [switch]$SkipNegativeControls
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
$java=Join-Path $JavaHome ('bin/java'+$suffix)
if(!(Test-Path -LiteralPath $javac)){throw 'A JDK is required.'}
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
$classes=Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$names=@('LunagramSubscriptionAccess','LunagramEntitlementLease','LunagramSubscriptionClient','LunagramSubscriptionCatalog','LunagramFeatureCatalog','LumaGhostMode')
$production=@($names | ForEach-Object { Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/messenger/'+$_+'.java') })
$hashes=@($production | ForEach-Object { (Get-FileHash -LiteralPath $_).Hash })
$ownFixtures=@(Get-ChildItem (Join-Path $PSScriptRoot 'access150/fixtures') -Recurse -Filter '*.java' | ForEach-Object FullName)
$excluded=@('AndroidUtilities.java','DispatchQueue.java','SystemClock.java','LumaBuildPolicy.java','ConnectionsManager.java')
$authFixtures=@(Get-ChildItem (Join-Path $PSScriptRoot 'subscriptions-auth/fixtures') -Recurse -Filter '*.java' | Where-Object { $_.Name -notin $excluded } | ForEach-Object FullName)
$test=Join-Path $PSScriptRoot 'access150/Access150Test.java'
$sources=$production+$ownFixtures+$authFixtures+@($test)
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Actual subscription authority compile failed.'}
& $java -Xmx96m -cp $classes org.telegram.messenger.Access150Test
if($LASTEXITCODE -ne 0){throw 'Actual subscription authority regression failed.'}
if(!$SkipNegativeControls) {
    # Each mutation must compile and then fail a behavioral assertion. This proves
    # guards are exercised, rather than merely counted in source text.
    $mutations=@(
        @{Name='offline-cap';Class='LunagramEntitlementLease';Before='elapsed - receivedElapsed >= OFFLINE_LIMIT_MS';After='false'},
        @{Name='auth-revision';Class='LunagramSubscriptionClient';Before=' && authRevision == authRevision(account)';After=''},
        @{Name='restore-race';Class='LunagramSubscriptionAccess';Before='entry.client != source && !entry.client.isAccountCurrent()';After='entry.client != source && false'},
        @{Name='cold-privacy';Class='LunagramSubscriptionAccess';Before='if (entry.lease == null) return true;';After='if (entry.lease == null) return false;'},
        @{Name='expiry-privacy';Class='LunagramSubscriptionAccess';Before='&& entry.lease.observedTier(now) == LunagramFeatureCatalog.Tier.ULTRA';After='&& true'},
        @{Name='account-epoch';Class='LunagramSubscriptionAccess';Before='old.owner != owner || old.epoch != epoch';After='old.owner != owner'},
        @{Name='auth-not-paid-expiry';Class='LunagramSubscriptionAccess';Before='entry.privacyRevoked = state.explicitDisconnect;';After='entry.privacyRevoked = state.revoked;'},
        @{Name='past-boundary';Class='LunagramEntitlementLease';Before='cap > elapsed ? cap : Long.MAX_VALUE';After='cap'},
        @{Name='history-not-access';Class='LunagramSubscriptionAccess';Before='current(entry) && !entry.authorizationRevoked && entry.lease != null';After='current(entry) && entry.lease != null'},
        @{Name='shared-observation';Class='LunagramSubscriptionClient';Before='requireCurrentObservation(state);';After='/* broken shared admission */'},
        @{Name='persisted-observation';Class='LunagramSubscriptionClient';Before='requireNotOlder(state, stored);';After='/* broken durable admission */'}
        @{Name='reminder-transition';Class='LunagramSubscriptionAccess';Before='Utilities.globalQueue.postRunnable(() -> LunagramMessageReminders.onAccessChanged(entry.account));';After='/* missing queued reminder transition */'}
    )
    foreach($mutation in $mutations) {
        $original=Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/messenger/'+$mutation.Class+'.java')
        $text=[IO.File]::ReadAllText($original)
        if(!$text.Contains($mutation.Before)){throw "Negative control source moved: $($mutation.Name)"}
        $dir=Join-Path $run ('negative-'+$mutation.Name); New-Item -ItemType Directory -Path $dir -Force | Out-Null
        $mutant=Join-Path $dir ($mutation.Class+'.java')
        [IO.File]::WriteAllText($mutant,$text.Replace($mutation.Before,$mutation.After),[Text.UTF8Encoding]::new($false))
        & $javac -J-Xmx128m -encoding UTF-8 -cp $classes -d $dir $mutant
        if($LASTEXITCODE -ne 0){throw "Negative control failed to compile: $($mutation.Name)"}
        $separator=[IO.Path]::PathSeparator
        $previousPreference=$ErrorActionPreference; $ErrorActionPreference='Continue'
        $negativeOutput=& $java -Xmx96m -cp ($dir+$separator+$classes) org.telegram.messenger.Access150Test 2>&1
        $negativeExit=$LASTEXITCODE; $ErrorActionPreference=$previousPreference
        [IO.File]::WriteAllLines((Join-Path $dir 'expected-failure.txt'),[string[]]$negativeOutput)
        if($negativeExit -eq 0 -or ($negativeOutput -join "`n") -notmatch 'AssertionError') {throw "Negative control did not fail behaviorally: $($mutation.Name)"}
    }
    Write-Host 'PASS: twelve independently compiled negative controls reject broken offline, revoke, restoring, privacy, expiry, owner, durable-admission and reminder-transition guards.'
}
if($AndroidJar) {
    if(!(Test-Path -LiteralPath $AndroidJar)){throw 'Android SDK jar does not exist.'}
    $native=Join-Path $run 'native-android'; New-Item -ItemType Directory -Path $native -Force | Out-Null
    $nativeFixtures=@(($ownFixtures+$authFixtures) | Where-Object { $_ -notmatch '[/\\]fixtures[/\\]android[/\\]|[/\\]org[/\\]json[/\\]' -and [IO.Path]::GetFileName($_) -ne 'LunagramSubscriptionClock.java' })
    $clock=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionClock.java'
    & $javac -J-Xmx128m -encoding UTF-8 -classpath $AndroidJar -d $native ($production+$nativeFixtures+@($clock))
    if($LASTEXITCODE -ne 0){throw 'Android 36 authority/client/clock API compile failed.'}
    Write-Host 'PASS: actual Access, Lease, Client and Boot Clock compile against the Android SDK (modeled Telegram dependencies; no device).'
}
for($i=0;$i -lt $production.Count;$i++) {
    if((Get-FileHash -LiteralPath $production[$i]).Hash -ne $hashes[$i]){throw 'Authority production source changed during checks: rerun after freeze.'}
}
$controller=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java'))
if(([regex]::Matches($controller,'LumaGhostMode\.shouldSuppressActivity\(currentAccount\)')).Count -ne 10){throw 'An outgoing presence/typing/read guard no longer uses startup privacy protection.'}
if(([regex]::Matches($controller,'LumaGhostMode\.isEnabled\(currentAccount\)')).Count -ne 1 -or !$controller.Contains('messageId <= 0 || !LumaGhostMode.isEnabled(currentAccount)')){throw 'Explicit manual-read action must remain paid-gated.'}
foreach($path in @('messenger/LocationController.java','ui/EmojiAnimationsOverlay.java')) {
    $content=[IO.File]::ReadAllText((Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/'+$path)))
    if(!$content.Contains('LumaGhostMode.shouldSuppressActivity(currentAccount)')){throw "Missing startup privacy protection: $path"}
}
Write-Host "Test artifacts: $run"
