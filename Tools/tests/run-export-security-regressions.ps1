param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/export-security'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes=Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@(Get-ChildItem (Join-Path $PSScriptRoot 'fixtures') -Recurse -File -Filter '*.java' | Where-Object {$_.Name -notin @('AndroidUtilities.java','BlackHolePrivateData.java')} | ForEach-Object FullName)
$sources+=@('export-security/AndroidUtilities.java','export-security/ExportSecurityTest.java','org/telegram/messenger/LumaExportSessionTest.java' | ForEach-Object {Join-Path $PSScriptRoot $_})
$sources+=@('LumaAccountExportManager','LumaExportSession' | ForEach-Object {Join-Path $repo "TMessagesProj/src/main/java/org/telegram/messenger/$_.java"})
. (Join-Path $PSScriptRoot 'feature-access150/add-fixtures.ps1')
$sources=@(Add-FeatureAccessFixtures $sources $repo)
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx256m -encoding UTF-8 -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Export security host compilation failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $classes org.telegram.messenger.ExportSecurityTest (Join-Path $run 'sandbox') $repo
if($LASTEXITCODE -ne 0){throw 'Export security regressions failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $classes org.telegram.messenger.LumaExportSessionTest (Join-Path $run 'session-sandbox') $repo
if($LASTEXITCODE -ne 0){throw 'Existing export session regressions failed.'}
$negativeControls=@(
    @{Name='epoch'; Class='LumaExportSession'; From='&& epoch == LunagramSubscriptionClient.accountEpoch(account)'; To=''; Failure='same-owner re-login cannot revive stale session'},
    @{Name='external-cache'; Class='LumaAccountExportManager'; From='outputDir = new File(privateCache,'; To='outputDir = new File(ApplicationLoader.applicationContext.getExternalCacheDir(),'; Failure='plaintext output only in private cache'},
    @{Name='expiry-read'; Class='LumaAccountExportManager'; From='if (!canReadCompletedExport()) {'; To='if (!ownsAccount()) {'; Failure='finished archive survives subscription expiry'},
    @{Name='unguarded-read'; Class='LumaAccountExportManager'; From='return session.isCurrent();'; To='return true;'; Failure='Save/Share boundary closes on same-owner new login'}
)
foreach($control in $negativeControls){
    $mutant=Join-Path $run $control.Name
    New-Item -ItemType Directory -Path $mutant -Force | Out-Null
    $original=[IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/java/org/telegram/messenger/$($control.Class).java"))
    if(!$original.Contains($control.From)){throw "Mutation anchor missing: $($control.Name)"}
    $mutated=Join-Path $mutant "$($control.Class).java"
    [IO.File]::WriteAllText($mutated,$original.Replace($control.From,$control.To),[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx256m -encoding UTF-8 -cp $classes -d $mutant $mutated
    if($LASTEXITCODE -ne 0){throw "Negative control must compile: $($control.Name)"}
    $result=& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp "$mutant;$classes" org.telegram.messenger.ExportSecurityTest (Join-Path $mutant 'sandbox') $repo 2>&1
    if($LASTEXITCODE -eq 0 -or !($result -join "`n").Contains($control.Failure)){throw "Negative control failed for wrong reason: $($control.Name): $result"}
}
$sourceControls=@{
    'save-callback'='identity gate on delayed save callback';
    'share-boundary'='identity rechecked at external URI grant boundary';
    'scan-owner'='stale scan response cannot modify new owner or restarted scan';
    'save-owner'='account delayed save callback gate'
}
foreach($name in $sourceControls.Keys){
    $result=& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $classes org.telegram.messenger.ExportSecurityTest (Join-Path $run 'unused') $repo $name 2>&1
    if($LASTEXITCODE -eq 0 -or !($result -join "`n").Contains($sourceControls[$name])){throw "UI source control failed for wrong reason: $name : $result"}
}
Write-Output 'PASS 4 compiled security negative controls + 4 UI source-contract negative controls'
Write-Output "Artifacts: $run"
