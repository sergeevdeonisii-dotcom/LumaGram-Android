param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/subscription-auth'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.runs'))+[IO.Path]::DirectorySeparatorChar
$output=[IO.Path]::GetFullPath($OutputRoot)
$inRunnerTemp=$env:RUNNER_TEMP -and $output.StartsWith([IO.Path]::GetFullPath($env:RUNNER_TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
if (!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -and !$inRunnerTemp) { throw 'Auth test output must remain inside Tools/tests/.runs or configured runner temporary storage.' }
$suffix=if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac=Join-Path $JavaHome ('bin/javac'+$suffix);$java=Join-Path $JavaHome ('bin/java'+$suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
$clientPath=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionClient.java'
$screenPath=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramSubscriptionActivity.java'
$client=[IO.File]::ReadAllText($clientPath);$screen=[IO.File]::ReadAllText($screenPath)
foreach ($required in @('https://lunagram-billing.pages.dev','https://t.me/LunagramAppPayBot?start=',
    'SSLContext.getInstance("TLSv1.2")','tls.init(null, null, null)','setInstanceFollowRedirects(false)',
    'setConnectTimeout(10_000)','setReadTimeout(10_000)','BODY_LIMIT = 32 * 1024',
    'REQUEST_TIMEOUT_MS = 15_000','WATCHDOG.schedule(timeout, REQUEST_TIMEOUT_MS)','requireDeadline(deadline)',
    'BlackHolePrivateData.write(account, owner','UserConfig.selectedAccount == account','ticket == generation')) {
    if (!$client.Contains($required)) { throw "Missing billing safety contract: $required" }
}
if (($screen+$client) -match 'FileLog|System\.out|android\.util\.Log|setHostnameVerifier|TrustManager|setPremium\(|\.setSubscription\(') {
    throw 'Subscription code must not log secrets, weaken TLS or gate Telegram features.'
}
foreach ($required in @('client.dispose()','client.pause()','cancelRunOnUIThread(poll)','runOnUIThread(poll, 3000)',
    'LunagramSubscriptionEphemeral','LunagramSubscriptionTotalPrice','client.previewCode(code)', 'client.confirmCode()',
    'LunagramSubscriptionClient.purchaseUrl(selectedPlan, selectedPeriod)','field.setSaveEnabled(false)')) {
    if (!$screen.Contains($required)) { throw "Missing subscription UI lifecycle contract: $required" }
}
$run=Join-Path $output ([guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $run -Force | Out-Null
$sources=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'subscriptions-auth/fixtures') -Filter '*.java' -Recurse).FullName)
$sources+=$clientPath
$sources+=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionCatalog.java'
$sources+=Join-Path $PSScriptRoot 'subscriptions-auth/LunagramSubscriptionClientTest.java'
& $javac -J-Xmx128m -encoding UTF-8 -d $run $sources
if ($LASTEXITCODE -ne 0) { throw 'Subscription client model compilation failed.' }
& $java -Xmx128m -cp $run org.telegram.messenger.LunagramSubscriptionClientTest
if ($LASTEXITCODE -ne 0) { throw 'Subscription auth contracts failed.' }
Write-Output 'PASS: fixed TLS host/paths, no redirects/logs/Telegram Premium mutation, and source UI lifetime/polling guards.'
Write-Output "Test artifacts: $run"
