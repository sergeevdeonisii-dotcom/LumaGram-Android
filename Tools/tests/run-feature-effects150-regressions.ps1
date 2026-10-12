param([string]$JavaHome = $env:JAVA_HOME, [Parameter(Mandatory=$true)][string]$BaseClasses,
    [Parameter(Mandatory=$true)][string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = Join-Path $OutputRoot ('effects150-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$base = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger'
$text = [IO.File]::ReadAllText((Join-Path $base 'LiteMode.java'))
$fieldStart = $text.IndexOf('    private static final String LIQUID_GLASS_ENABLED =')
$fieldEnd = $text.IndexOf('    public static final int FLAG_CALLS_ANIMATIONS', $fieldStart)
$methodStart = $text.IndexOf('    public static boolean getLiquidGlassEnabled()')
$methodEnd = $text.IndexOf('    public static void toggleFlag(int flag)', $methodStart)
if ($fieldStart -lt 0 -or $fieldEnd -le $fieldStart -or $methodStart -lt 0 -or $methodEnd -le $methodStart) { throw 'Missing actual glass extraction boundaries.' }
$source = 'package org.telegram.messenger; public final class LiteMode { public static final int FLAG_LIQUID_GLASS = 1; public static boolean isEnabled(int flag) { return true; }' + $text.Substring($fieldStart, $fieldEnd-$fieldStart) + $text.Substring($methodStart, $methodEnd-$methodStart) + '}'
$path = Join-Path $run 'LiteMode.java'
[IO.File]::WriteAllText($path, $source, [Text.UTF8Encoding]::new($false))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$sources = @($path, (Join-Path $base 'LumaEmergencyMode.java'), (Join-Path $PSScriptRoot 'feature-access150/FeatureEffects150Test.java'))
& (Join-Path $JavaHome ('bin/javac'+$suffix)) -J-Xmx128m -encoding UTF-8 -cp $BaseClasses -d $run $sources
if ($LASTEXITCODE -ne 0) { throw 'Actual feature effect compilation failed.' }
& (Join-Path $JavaHome ('bin/java'+$suffix)) -Xmx128m -cp ($run + [IO.Path]::PathSeparator + $BaseClasses) org.telegram.messenger.FeatureEffects150Test
if ($LASTEXITCODE -ne 0) { throw 'Feature effect regression failed.' }
Write-Output "Test artifacts: $run"
