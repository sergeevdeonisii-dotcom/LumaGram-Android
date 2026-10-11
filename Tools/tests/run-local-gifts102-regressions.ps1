param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-local-gifts102-tests')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
function ProductionClass([string]$source, [string]$name) {
    $pattern = 'public static (?:final |abstract )?class ' + [regex]::Escape($name) + '(?:\s|\{)'
    $match = [regex]::Match($source, $pattern)
    if (!$match.Success) { throw "Missing production TL class: $name" }
    $start = $match.Index
    $open = $source.IndexOf('{', $start); $depth = 1
    for ($i = $open + 1; $i -lt $source.Length; $i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if (!$depth) { return $source.Substring($start, $i - $start + 1) }
    }
    throw "Unterminated production TL class: $name"
}
$generated = @()
foreach ($name in @('TL_stars','TLRPC')) {
    $template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot ('local-gifts102/' + $name + '.java.template')))
    $path = if ($name -eq 'TL_stars') { 'TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stars.java' } else { 'TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java' }
    $production = [IO.File]::ReadAllText((Join-Path $repo $path))
    foreach ($match in [regex]::Matches($template, '@@([A-Za-z0-9_]+)@@')) {
        $template = $template.Replace($match.Value, (ProductionClass $production $match.Groups[1].Value))
    }
    $file = Join-Path $run ($name + '.java')
    [IO.File]::WriteAllText($file, $template, [Text.UTF8Encoding]::new($false))
    $generated += $file
}
$sources = $generated + @((Join-Path $PSScriptRoot 'local-gifts102/LocalGifts102Test.java'),
    (Join-Path $PSScriptRoot 'local-gifts102/LegacyClone103Probe.java'))
$sources += (Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'local-gifts102/fixtures') -Recurse -Filter '*.java').FullName
$sources += @('SerializedData','AbstractSerializedData','InputSerializedData','OutputSerializedData','TLDataSourceType','TLObject','Vector') | ForEach-Object { Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/tgnet/' + $_ + '.java') }
$sources += @('TMessagesProj/src/main/java/org/telegram/messenger/LumaLocalGifts.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/LumaAccountData.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/BlackHoleSealedData.java',
    'Tools/tests/fixtures/android/content/SharedPreferences.java') | ForEach-Object { Join-Path $repo $_ }
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Local gift regression compilation failed.' }
& $java -Xmx128m -cp $classes org.telegram.messenger.LocalGifts102Test
if ($LASTEXITCODE -ne 0) { throw 'Local gift regressions failed.' }
$local = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaLocalGifts.java'))
if ($local -match 'ConnectionsManager|StarsController|sendRequest\(|TL_payments|saveStarGift|transferStarGift|resellStarGift') { throw 'Local data helper must never invoke server inventory/payments.' }
$private = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/BlackHolePrivateData.java'))
if (!$local.Contains('BlackHolePrivateData.write(account, owner, STORE') -or !$private.Contains('BlackHoleSealedData.seal(')) { throw 'Persistence must go through the encrypted identity-bound adapter.' }
Write-Output 'PASS: no network/payment/server gift-list dependency; production encrypted persistence integration.'
if (!$local.Contains('field.getDeclaringClass() == TLRPC.TL_photoPathSize.class') -or !$local.Contains('field.getName().equals("svgPath")')) {
    throw 'SVG runtime cache exclusion must remain scoped to the exact protocol-derived field.'
}
if ($local -match 'Log\.w\([^;]*(getMessage\(|getLocalizedMessage\(|printStackTrace\(|,\s*error\))') { throw 'Add diagnostics must never log exception messages/stack traces.' }
Write-Output 'PASS: narrow derived SVG Path exclusion and sanitized phase/class-only failure diagnostics.'
Write-Output 'Scope: real local data helper, real identity-scoped logout, AES-GCM and production gift/attribute/document/SVG-thumbnail serializers; JVM Android/Keystore/SVG-renderer adapters and minimal TL factory routing, not device UI or the specific market gift.'
Write-Output "Test artifacts: $run"
