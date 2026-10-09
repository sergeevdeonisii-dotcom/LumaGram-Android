param(
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)][string]$GroovyJar,
    [string]$OutputRoot
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$java = Join-Path $JavaHome 'bin/java'
if ($IsWindows -or $env:OS -eq 'Windows_NT') { $java += '.exe' }
if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $GroovyJar)) {
    throw 'An existing JDK 17 and Groovy core JAR are required.'
}
if (-not $OutputRoot) { $OutputRoot = Join-Path ([IO.Path]::GetTempPath()) 'lunagram-signing-tests' }
$run = Join-Path $OutputRoot ('signing100-' + [guid]::NewGuid().ToString())
& $java -Xmx128m -cp $GroovyJar groovy.ui.GroovyMain (Join-Path $PSScriptRoot 'release100/Signing100Regression.groovy') $repo $run
if ($LASTEXITCODE -ne 0) { throw 'Release signing regression failed.' }
Write-Output "Test artifacts: $run"
