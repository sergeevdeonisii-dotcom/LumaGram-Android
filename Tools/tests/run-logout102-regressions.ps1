param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-logout102-tests')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }

# Reuse freshly generated real gift/document serializers and compiled data helper.
# The child suite is also executed, not silently borrowed from an old cached run.
$storageOutput = & (Join-Path $PSScriptRoot 'run-local-gifts102-regressions.ps1') -JavaHome $JavaHome -OutputRoot (Join-Path $OutputRoot 'data-model')
$storageOutput | ForEach-Object { Write-Output $_ }
$storageLine = $storageOutput | Where-Object { $_ -like 'Test artifacts: *' } | Select-Object -Last 1
if (!$storageLine) { throw 'Fresh local data model output missing.' }
$storageRun = $storageLine.Substring('Test artifacts: '.Length)
$baseClasses = Join-Path $storageRun 'classes'
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
function ProductionMethod([string]$source,[string]$signature) {
    $start = $source.IndexOf($signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method: $signature" }
    $open = $source.IndexOf('{',$start); $depth = 1
    for ($i=$open+1;$i -lt $source.Length;$i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if (!$depth) { return $source.Substring($start,$i-$start+1) }
    }
    throw "Unterminated production method: $signature"
}
$config = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java'))
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'logout102/UserConfig.java.template'))
$replacements = [ordered]@{
    '@@GET_OWNER@@' = 'public long getClientUserId()'; '@@GET_USER@@' = 'public TLRPC.User getCurrentUser()';
    '@@IS_ACTIVATED@@' = 'public boolean isClientActivated()'; '@@SET_USER@@' = 'public void setCurrentUser(TLRPC.User user)';
    '@@PREFERENCES@@' = 'public SharedPreferences getPreferences()'; '@@RESET_PASSWORD@@' = 'public void resetSavedPassword()';
    '@@CLEAR_CONFIG@@' = 'public void clearConfig()'
}
foreach ($entry in $replacements.GetEnumerator()) { $template = $template.Replace($entry.Key,(ProductionMethod $config $entry.Value)) }
$generatedConfig = Join-Path $run 'UserConfig.java'
[IO.File]::WriteAllText($generatedConfig,$template,[Text.UTF8Encoding]::new($false))

$private = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/BlackHolePrivateData.java'))
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'logout102/BlackHolePrivateData.java.template'))
$replacements = [ordered]@{
    '@@READ@@' = 'public static String read(int account, String name, String fallback)';
    '@@WRITE_CURRENT@@' = 'public static void write(int account, String name, String text)';
    '@@WRITE_OWNER@@' = 'public static void write(int account, long owner, String name, String text)';
    '@@REMOVE_CURRENT@@' = 'public static void remove(int account, String name)';
    '@@REMOVE_OWNER@@' = 'public static void remove(int account, long owner, String name)';
    '@@REQUIRE_OWNER@@' = 'private static void requireOwner(int account, long owner)'
}
foreach ($entry in $replacements.GetEnumerator()) { $template = $template.Replace($entry.Key,(ProductionMethod $private $entry.Value)) }
$generatedPrivate = Join-Path $run 'BlackHolePrivateData.java'
[IO.File]::WriteAllText($generatedPrivate,$template,[Text.UTF8Encoding]::new($false))

$subscription = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionClient.java'))
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'logout102/LunagramSubscriptionClient.java.template'))
$replacements = [ordered]@{
    '@@EPOCH@@' = 'private static synchronized long accountEpoch(int account)';
    '@@LOGOUT@@' = 'public static synchronized void onAccountLogout(int account)';
    '@@PERSIST@@' = 'private void persistSession(long ticket, String value)'
}
foreach ($entry in $replacements.GetEnumerator()) { $template = $template.Replace($entry.Key,(ProductionMethod $subscription $entry.Value)) }
$generatedSubscription = Join-Path $run 'LunagramSubscriptionClient.java'
[IO.File]::WriteAllText($generatedSubscription,$template,[Text.UTF8Encoding]::new($false))

$tlrpc = [IO.File]::ReadAllText((Join-Path $storageRun 'TLRPC.java'))
$lastBrace = $tlrpc.LastIndexOf('}')
$tlrpc = $tlrpc.Insert($lastBrace,"`npublic static class User extends TLObject { public long id; }`n")
$generatedRpc = Join-Path $run 'TLRPC.java'
[IO.File]::WriteAllText($generatedRpc,$tlrpc,[Text.UTF8Encoding]::new($false))
$sources = @($generatedConfig,$generatedPrivate,$generatedRpc,$generatedSubscription,(Join-Path $PSScriptRoot 'logout102/Logout102Test.java'))
$sources += (Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'logout102/fixtures') -Recurse -Filter '*.java').FullName
$sources += @('TMessagesProj/src/main/java/org/telegram/messenger/BlackHoleNotes.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/BlackHoleNotificationJournal.java',
    'Tools/tests/fixtures/org/json/JSONObject.java','Tools/tests/fixtures/org/json/JSONArray.java') | ForEach-Object { Join-Path $repo $_ }
& $javac -J-Xmx128m -encoding UTF-8 -cp $baseClasses -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Logout regression compilation failed.' }
$separator = [IO.Path]::PathSeparator
& $java -Xmx128m -cp ($classes + $separator + $baseClasses) org.telegram.messenger.Logout102Test
if ($LASTEXITCODE -ne 0) { throw 'Logout regressions failed.' }

# A future encrypted writer must deliberately join the reviewed logout barriers.
$writeFiles = @()
if (Get-Command rg -ErrorAction SilentlyContinue) {
    $writePaths = & rg -l 'BlackHolePrivateData\.write\(' (Join-Path $repo 'TMessagesProj/src/main/java') -g '*.java'
    if ($LASTEXITCODE -notin @(0,1)) { throw 'Encrypted writer inventory search failed.' }
    $writeFiles = @($writePaths | ForEach-Object { [IO.Path]::GetFileName($_) })
} else {
    Get-ChildItem -LiteralPath (Join-Path $repo 'TMessagesProj/src/main/java') -Recurse -Filter '*.java' | ForEach-Object {
        $text = [IO.File]::ReadAllText($_.FullName)
        if ($text -match 'BlackHolePrivateData\.write\(') { $writeFiles += $_.Name }
    }
}
$actualWriters = ($writeFiles | Sort-Object -Unique) -join ','
if ($actualWriters -ne 'BlackHoleNotes.java,BlackHoleNotificationJournal.java,LumaLocalGifts.java,LunagramSubscriptionClient.java') {
    throw "Encrypted writer inventory changed; review logout barriers: $actualWriters"
}
$clear = ProductionMethod $config 'public void clearConfig()'
foreach ($guard in @('synchronized (sync)', 'logoutOwner = currentUser != null ? currentUser.id : 0;',
    'currentUser = null;', 'clientUserId = 0;', 'synchronized (BlackHoleNotes.class) { }',
    'synchronized (BlackHoleNotificationJournal.class) { }', 'synchronized (LumaLocalGifts.class)',
    'LunagramSubscriptionClient.onAccountLogout(currentAccount);',
    'LumaLocalGifts.clearCache(currentAccount);', 'LumaAccountData.clearOnLogout(currentAccount, logoutOwner);',
    'getPreferences().edit().clear().apply();', 'saveConfig(true);')) {
    if (!$clear.Contains($guard)) { throw "Missing logout guard/reset: $guard" }
}
if (($clear | Select-String -Pattern 'LumaAccountData\.clearOnLogout' -AllMatches).Matches.Count -ne 1) { throw 'Local account cleanup must run once.' }
$persist = ProductionMethod $subscription 'private void persistSession(long ticket, String value)'
foreach ($guard in @('synchronized (LunagramSubscriptionClient.class)', 'if (!current(ticket))',
    'BlackHolePrivateData.write(account, owner, SESSION_KEY, value);')) {
    if (!$persist.Contains($guard)) { throw "Missing subscription write barrier: $guard" }
}
foreach ($guard in @('this.accountEpoch = accountEpoch(account);', 'accountEpoch == accountEpoch(account)', 'persistSession(ticket, new JSONObject()')) {
    if (!$subscription.Contains($guard)) { throw "Missing subscription epoch/write wiring: $guard" }
}
if (($subscription | Select-String -Pattern 'BlackHolePrivateData\.write\(' -AllMatches).Matches.Count -ne 1) { throw 'Subscription writes must remain inside the reviewed persistence method.' }
Write-Output 'PASS: all current encrypted write sites inventoried; identity-first/sequential drain/one cleanup/save wiring checked.'
Write-Output 'Scope: real extracted UserConfig identity/reset and subscription persist/epoch methods, real local gift/note/journal/data helpers and AES-GCM; platform preferences/keys/JSON/save notifications are deterministic JVM models, not real Android lifecycle.'
Write-Output "Test artifacts: $run"
