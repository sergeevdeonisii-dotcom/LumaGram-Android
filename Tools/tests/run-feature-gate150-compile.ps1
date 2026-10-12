param([string]$JavaHome = $env:JAVA_HOME, [Parameter(Mandatory=$true)][string]$AndroidJar,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs/feature-gate150'))
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'feature-access150/GateNativeUi.java.template'))
$parts = [regex]::Matches($template, '(?m)^// FILE: ([^\r\n]+)\r?\n')
$sources = @()
for ($i=0; $i -lt $parts.Count; $i++) {
    $start = $parts[$i].Index + $parts[$i].Length
    $end = if ($i+1 -lt $parts.Count) { $parts[$i+1].Index } else { $template.Length }
    $path = Join-Path $run $parts[$i].Groups[1].Value
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path, $template.Substring($start, $end-$start), [Text.UTF8Encoding]::new($false))
    $sources += $path
}
$sourcePath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramFeatureGate.java'
$sources += $sourcePath
. (Join-Path $PSScriptRoot 'feature-access150/add-fixtures.ps1')
$sources = @(Add-FeatureAccessFixtures $sources $repo)
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
& (Join-Path $JavaHome ('bin/javac'+$suffix)) -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $run $sources
if ($LASTEXITCODE -ne 0) { throw 'Native feature gate Android API compilation failed.' }
$source = [IO.File]::ReadAllText($sourcePath)
foreach ($required in @('canUse(account, feature)', 'minimumTier(feature)', 'LunagramFeatureRequiresTier',
        '.setNegativeButton(', 'screen.setCurrentAccount(account)', 'host.presentFragment(screen)')) {
    if (!$source.Contains($required)) { throw "Missing native gate requirement: $required" }
}
foreach ($locale in @('values','values-ru')) {
    $xml = [xml][IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    $value = @($xml.resources.string | Where-Object { $_.name -eq 'LunagramFeatureRequiresTier' })
    if ($value.Count -ne 1 -or !$value[0].InnerText.Contains('%1$s')) { throw "Missing tier disclosure localization: $locale" }
}
Write-Output 'PASS: actual native feature gate compiles against Android36; required-tier/account-bound navigation contracts pass. No rendered UI or full app compilation.'
Write-Output "Test artifacts: $run"
