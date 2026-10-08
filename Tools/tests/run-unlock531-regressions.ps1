param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-531-unlock-tests'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath($OutputRoot)
if (-not $output.StartsWith([IO.Path]::GetFullPath('D:\CodexBuildCache') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Regression artifacts must remain inside D:\CodexBuildCache.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'An existing JavaHome containing javac.exe is required.'
}
function Method([string]$source, [string]$signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method $signature" }
    $open = $source.IndexOf('{', $start); $depth = 1
    for ($i = $open + 1; $i -lt $source.Length; $i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if ($depth -eq 0) { return $source.Substring($start, $i - $start + 1) }
    }
    throw "Unclosed production method $signature"
}
$unlock = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/BlackHoleUnlockActivity.java'
$launch = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java'
$unlockHash = (Get-FileHash -LiteralPath $unlock -Algorithm SHA256).Hash
$launchHash = (Get-FileHash -LiteralPath $launch -Algorithm SHA256).Hash
$run = Join-Path $output ('run-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$launchMethod = Method ([IO.File]::ReadAllText($launch)) 'public boolean allowShowFingerprintDialog(PasscodeView passcodeView)'
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'unlock531/LaunchActivity.java.template'))
$launchSource = Join-Path $run 'LaunchActivity.java'
[IO.File]::WriteAllText($launchSource, $template.Replace('// PRODUCTION_ARBITRATION_METHOD', $launchMethod), [Text.UTF8Encoding]::new($false))
$sources = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'unlock531') -Recurse -Filter '*.java').FullName)
$sources += @($unlock, $launchSource, (Join-Path $PSScriptRoot 'fixtures/org/telegram/messenger/UserConfig.java'))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx96m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Unlock lifecycle test compilation failed.' }
if ((Get-FileHash -LiteralPath $unlock -Algorithm SHA256).Hash -ne $unlockHash -or
    (Get-FileHash -LiteralPath $launch -Algorithm SHA256).Hash -ne $launchHash) {
    throw 'Unlock or arbitration source changed during compilation; rerun after source freeze.'
}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx96m -cp $classes org.telegram.ui.Unlock531RegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Unlock lifecycle regressions failed.' }
Write-Output ('Unlock fragment SHA-256: ' + $unlockHash)
Write-Output ('Arbitration source SHA-256: ' + $launchHash)
Write-Output ('Test artifacts: ' + $run)
