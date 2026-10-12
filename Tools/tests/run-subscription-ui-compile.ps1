param(
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$AndroidJar='',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/subscription-ui')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.runs'))+[IO.Path]::DirectorySeparatorChar
$output=[IO.Path]::GetFullPath($OutputRoot)
$inRunnerTemp=$env:RUNNER_TEMP -and $output.StartsWith([IO.Path]::GetFullPath($env:RUNNER_TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
if (!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -and !$inRunnerTemp) { throw 'UI test output must remain inside Tools/tests/.runs or configured runner temporary storage.' }
$suffix=if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
if (!$AndroidJar) {
    $sdk=if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'C:\Users\denis228\Documents\Codex\2026-07-23\new-chat-2\work\android-sdk' }
    $AndroidJar=Join-Path $sdk 'platforms/android-36/android.jar'
}
if (!(Test-Path -LiteralPath $javac) -or !(Test-Path -LiteralPath $AndroidJar)) { throw 'Existing JDK and Android platform jar are required.' }
$run=Join-Path $output ([guid]::NewGuid().ToString('N'))
$classes=Join-Path $run 'classes';New-Item -ItemType Directory -Path $classes -Force | Out-Null
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'subscriptions-auth/NativeUi.java.template'))
$parts=[regex]::Matches($template,'(?m)^// FILE: ([^\r\n]+)\r?\n')
$sources=@()
for ($i=0;$i -lt $parts.Count;$i++) {
    $start=$parts[$i].Index+$parts[$i].Length
    $end=if ($i+1 -lt $parts.Count) { $parts[$i+1].Index } else { $template.Length }
    $path=Join-Path $run $parts[$i].Groups[1].Value
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path,$template.Substring($start,$end-$start),[Text.UTF8Encoding]::new($false))
    $sources+=$path
}
$screen=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramSubscriptionActivity.java'
$source=[IO.File]::ReadAllText($screen)
$keys=@([regex]::Matches($source,'R\.string\.(\w+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
$fields='';$counter=1
foreach ($key in $keys) { $fields+='public static final int '+$key+'='+($counter++)+';' }
$resourcePath=Join-Path $run 'org/telegram/messenger/R.java'
[IO.File]::WriteAllText($resourcePath,'package org.telegram.messenger; public final class R { public static final class string {'+$fields+'} public static final class drawable { public static final int ic_ab_back=1; } }',[Text.UTF8Encoding]::new($false))
$sources+=$resourcePath
# Native API probe must use the real platform Base64/SystemClock/JSONObject/JSONArray.
# Only Telegram collaborators remain modeled; JVM behavior tests own their fake parser/clock.
$sources+=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'subscriptions-auth/fixtures') -Filter '*.java' -Recurse | Where-Object {
    $_.Name -ne 'AndroidUtilities.java' -and $_.FullName.Replace('\','/') -notmatch '/(?:android|org/json)/'
}).FullName)
$sources+=$screen
$sources+=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionClient.java'
$sources+=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionCatalog.java'
$sources+=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeatureCatalog.java'
& $javac -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Subscription UI Android API/model contract compilation failed.' }
Write-Output 'PASS: actual subscription screen/client compile against Android36 with modeled Telegram UI collaborators; not a full app compile or rendered UI test.'
Write-Output "Test artifacts: $run"
