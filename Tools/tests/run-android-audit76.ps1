param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run-android-audit74.ps1') -JavaHome $JavaHome
if ($LASTEXITCODE -ne 0) { throw 'Existing Android source/JVM audit failed.' }
foreach ($script in @('run-round-video-regressions.ps1', 'run-updater76-regressions.ps1')) {
    & (Join-Path $PSScriptRoot $script) -JavaHome $JavaHome -OutputRoot $OutputRoot
    if ($LASTEXITCODE -ne 0) { throw "$script failed." }
}
Write-Output 'PASS: Android .76 source/JVM audit. APK compilation, physical camera/UI and real network checks remain separate.'
