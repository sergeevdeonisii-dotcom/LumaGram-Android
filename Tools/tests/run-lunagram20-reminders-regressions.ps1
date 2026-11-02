param([string]$JavaHome = 'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testRoot = Join-Path $PSScriptRoot 'lunagram20-reminders'
$run = Join-Path $PSScriptRoot ('.runs/lunagram20-reminders-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $testRoot 'fixtures') -Filter '*.java' -Recurse | ForEach-Object FullName)
$messenger = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger'
$sources += @(Join-Path $messenger 'LunagramReminderRef.java'; Join-Path $messenger 'LunagramMessageReminders.java'; Join-Path $messenger 'LunagramMessageReminderReceiver.java'; Join-Path $testRoot 'LunagramRemindersTest.java')
$sources += Join-Path $messenger 'LunagramFeatureCatalog.java'
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Message reminder host compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $classes org.telegram.messenger.LunagramRemindersTest
if ($LASTEXITCODE -ne 0) { throw 'Message reminder behavior checks failed' }
$source=[IO.File]::ReadAllText((Join-Path $messenger 'LunagramMessageReminders.java'))
foreach($mutation in @(
    @('schedule-bypass','LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.MESSAGE_REMINDERS)','true'),
    @('delivery-bypass','if (!LunagramSubscriptionAccess.canUse(reference.account, LunagramFeatureCatalog.Feature.MESSAGE_REMINDERS)) {','if (false) {'),
    @('renewal-overdue','if (!allowed || reference.whenMillis <= System.currentTimeMillis())','if (!allowed)')
)) {
    if(!$source.Contains($mutation[1])){throw "Reminder negative anchor missing: $($mutation[0])"}
    $folder=Join-Path $run $mutation[0];New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $file=Join-Path $folder 'LunagramMessageReminders.java';[IO.File]::WriteAllText($file,$source.Replace($mutation[1],$mutation[2]),[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -cp $classes -d $folder $file
    if($LASTEXITCODE -ne 0){throw 'Reminder negative must compile'}
    $result=& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp ($folder+[IO.Path]::PathSeparator+$classes) org.telegram.messenger.LunagramRemindersTest 2>&1
    if($LASTEXITCODE -eq 0 -or ($result -join "`n") -notmatch 'AssertionError'){throw "Reminder negative did not fail behaviorally: $($mutation[0])"}
    Write-Output "PASS compiled reminder negative: $($mutation[0])"
}
[xml]$manifest = Get-Content -LiteralPath (Join-Path $repo 'TMessagesProj/src/main/AndroidManifest.xml') -Raw
$android = 'http://schemas.android.com/apk/res/android'
$receiver = @($manifest.manifest.application.receiver | Where-Object { $_.GetAttribute('name', $android) -eq '.LunagramMessageReminderReceiver' })
if ($receiver.Count -ne 1 -or $receiver[0].GetAttribute('exported', $android) -ne 'false') { throw 'Message reminder receiver must be singular and private' }
$actions = @($receiver[0].'intent-filter'.action | ForEach-Object { $_.GetAttribute('name', $android) })
if ($actions -notcontains 'android.intent.action.BOOT_COMPLETED' -or $actions -notcontains 'android.intent.action.MY_PACKAGE_REPLACED') { throw 'Reminder boot/update restore routes missing' }
Write-Output 'Lunagram 2.0 message reminders: host behavior and private receiver manifest checks passed; no real OS alarms or notifications used.'
Write-Output "Results: $run"
