param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram20-streamer'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$sources=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'lunagram20-video/streamer') -Recurse -Filter '*.java').FullName)
$sources+=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramStreamerMode.java'
$sources+=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeatureCatalog.java'
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 --release 8 -d $run $sources
if ($LASTEXITCODE -ne 0) { throw 'Streamer identity compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $run org.telegram.messenger.StreamerIdentityTest
if ($LASTEXITCODE -ne 0) { throw 'Streamer identity regression failed.' }
$identity=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaAccountData.java'))
if (!$identity.Contains('"luma_user_" + userId') -or !$identity.Contains('.edit().clear().apply();')) { throw 'Production identity preferences/logout clearing contract changed.' }
$mask=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramStreamerMode.java'))
foreach($mutation in @(
    @('new-toggle-bypass','if (enabled && !LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.STREAMER_MODE)) return;',''),
    @('confirmed-expiry-bypass','LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.STREAMER_MODE)','true'),
    @('unknown-privacy-exposure','LunagramSubscriptionAccess.shouldProtectExistingPrivacy(account)','false')
)) {
    if(!$mask.Contains($mutation[1])){throw "Streamer negative anchor missing: $($mutation[0])"}
    $folder=Join-Path $run $mutation[0];New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $file=Join-Path $folder 'LunagramStreamerMode.java';[IO.File]::WriteAllText($file,$mask.Replace($mutation[1],$mutation[2]),[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 --release 8 -cp $run -d $folder $file
    if($LASTEXITCODE -ne 0){throw 'Streamer negative must compile'}
    $result=& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp ($folder+[IO.Path]::PathSeparator+$run) org.telegram.messenger.StreamerIdentityTest 2>&1
    if($LASTEXITCODE -eq 0 -or ($result -join "`n") -notmatch 'AssertionError'){throw "Streamer negative did not fail behaviorally: $($mutation[0])"}
    Write-Output "PASS compiled streamer negative: $($mutation[0])"
}
if ($mask -match 'user\.(?:first_name|last_name|username|phone)\s*=(?!=)|chat\.(?:title|username)\s*=(?!=)') { throw 'Presentation mode mutates protocol data.' }
Write-Output 'PASS: identity-owned production preference integration, logout clear and non-mutating name rendering guards.'
Write-Output "Results: $run"
