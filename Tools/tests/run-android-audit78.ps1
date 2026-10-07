param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-78-audit')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run-android-audit76.ps1') -JavaHome $JavaHome -OutputRoot $OutputRoot
if ($LASTEXITCODE -ne 0) { throw 'Existing Android source/JVM audit failed.' }
foreach ($script in @('run-round-fps77-regressions.ps1', 'run-round-camera77-regressions.ps1', 'run-round-cadence78-regressions.ps1')) {
    & (Join-Path $PSScriptRoot $script) -JavaHome $JavaHome -OutputRoot $OutputRoot
    if ($LASTEXITCODE -ne 0) { throw "$script failed." }
}
Write-Output 'PASS: Android .78 source/JVM/cadence audit. APK compilation, GPU/codec and phone playback remain separate checks.'
