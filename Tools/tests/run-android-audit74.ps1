param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'Pass -JavaHome pointing to an installed JDK 17 (not a JRE).'
}
foreach ($script in @('run-luma-regressions.ps1', 'run-chat-header-regressions.ps1',
        'run-simple-text-slot-regressions.ps1', 'run-typing-regressions.ps1',
        'run-updater-audit73-regressions.ps1')) {
    Write-Output "Running $script"
    & (Join-Path $PSScriptRoot $script) -JavaHome $JavaHome
    if ($LASTEXITCODE -ne 0) { throw "$script failed." }
}
foreach ($script in @('check-blackholegram-audit70.ps1', 'check-blackholegram-tools71.ps1',
        'check-blackholegram-settings.ps1', 'check-blackholegram-upstream.ps1',
        'check-lunagram-brand.ps1', 'test-native-alignment.ps1')) {
    Write-Output "Running $script"
    & (Join-Path $PSScriptRoot $script)
    if ($LASTEXITCODE -ne 0) { throw "$script failed." }
}
Write-Output 'PASS: Android .74 source/JVM audit. Not a Gradle APK build, rendered Android UI or a live-server/device test.'
