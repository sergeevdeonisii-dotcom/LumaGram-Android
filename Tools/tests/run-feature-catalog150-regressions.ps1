param([string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs/feature-catalog150'))
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeatureCatalog.java'
$run = Join-Path $OutputRoot ('run-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $run -Force | Out-Null
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
& $javac -J-Xmx128m -encoding UTF-8 --release 8 -d $run $source (Join-Path $PSScriptRoot 'feature-catalog150/FeatureCatalog150Test.java')
if ($LASTEXITCODE -ne 0) { throw 'Pure feature catalog compilation failed.' }
& $java -Xmx128m -cp $run org.telegram.messenger.FeatureCatalog150Test
if ($LASTEXITCODE -ne 0) { throw 'Pure feature catalog regression failed.' }
$text = [IO.File]::ReadAllText($source)
foreach ($forbidden in @('import android.', 'BuildConfig', 'UserConfig', 'SharedPreferences', 'ConnectionsManager',
        'LunagramSubscriptionClient', 'NotificationCenter', 'System.currentTimeMillis', 'LumaGhostMode', 'BlackHoleVault')) {
    if ($text.Contains($forbidden)) { throw "Catalog must remain pure and disconnected: $forbidden" }
}
$references = @(& rg -l -F 'LunagramFeatureCatalog' (Join-Path $repo 'TMessagesProj/src/main/java') --glob '*.java')
if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1) { throw 'Catalog reference guard failed.' }
$allowed = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'feature-catalog150/runtime-call-sites.txt'))
$prefix = (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/').Replace('\','/').TrimEnd('/') + '/'
foreach ($reference in $references) {
    if ($reference.Replace('\','/').Substring($prefix.Length) -notin $allowed) {
        throw "Unreviewed runtime tier call site: $reference"
    }
}
Write-Output 'PASS: catalog remains pure; runtime uses are restricted to the reviewed authority, effect boundaries and native entry UI.'
Write-Output 'Scope: immutable tier vocabulary and call-site inventory, Java8; runtime policy is tested separately.'
Write-Output "Test artifacts: $run"
