param([string]$JavaHome=$env:JAVA_HOME,
    [string]$AndroidJar='C:/Users/denis228/Documents/Codex/2026-07-23/new-chat-2/work/android-sdk/platforms/android-36/android.jar',
    [string]$DependencyCache='D:/CodexBuildCache/gradle-user-caches-20261006/8.13/transforms',
    [string[]]$AdditionalJars=@(),
    [switch]$IncludePet,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram20-streamer-api'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$names=@('org/telegram/ui/Cells/UserCell.java','org/telegram/ui/Cells/UserCell2.java','org/telegram/ui/Cells/ProfileSearchCell.java','org/telegram/messenger/LunagramStreamerMode.java')
if ($IncludePet) { $names+=@('org/telegram/ui/Components/LunagramPetView.java','org/telegram/messenger/LunagramPetMotion.java','org/telegram/messenger/LunagramExperienceSettings.java','org/telegram/messenger/LunagramSwipeGesture.java') }
$sources=@($names | ForEach-Object { Join-Path $base $_ })
$cached=Join-Path $repo 'TMessagesProj/build/intermediates/compile_library_classes_jar/standalone/bundleLibCompileToJarStandalone/classes.jar'
if (!(Test-Path -LiteralPath $cached) -or !(Test-Path -LiteralPath $AndroidJar)) { throw 'Existing production classes and Android SDK are required; this probe never invokes Gradle.' }
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$all=($sources | ForEach-Object { [IO.File]::ReadAllText($_) }) -join "`n"
$r='package org.telegram.messenger; public final class R {'
$groups=[regex]::Matches($all,'R\.(\w+)\.(\w+)') | Group-Object { $_.Groups[1].Value }
$id=1
foreach ($group in $groups) {
    $r+='public static final class '+$group.Name+' {'
    foreach ($field in ($group.Group | ForEach-Object { $_.Groups[2].Value } | Sort-Object -Unique)) { $r+='public static final int '+$field+'='+($id++)+';' }
    $r+='}'
}
$r+='}'
$rpath=Join-Path $run 'R.java'
[IO.File]::WriteAllText($rpath,$r,[Text.UTF8Encoding]::new($false))
$sources+=$rpath
$jars=@($cached,$AndroidJar)
$jars+=$AdditionalJars
$jars+=@(Get-ChildItem -LiteralPath $DependencyCache -Recurse -File -Filter '*.jar' | ForEach-Object FullName)
$jars+=@(Get-ChildItem -LiteralPath (Join-Path $repo 'TMessagesProj_Modules') -Recurse -File -Filter 'classes.jar' | Where-Object FullName -Match 'compile_library_classes_jar' | ForEach-Object FullName)
$argsPath=Join-Path $run 'javac.args'
$compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','25','-classpath',('"'+(($jars -join [IO.Path]::PathSeparator).Replace('\','/'))+'"'),'-d',('"'+$run.Replace('\','/')+'"'))
$compilerArgs+=@($sources | ForEach-Object { '"'+$_.Replace('\','/')+'"' })
[IO.File]::WriteAllLines($argsPath,$compilerArgs,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx256m ('@'+$argsPath)
if ($LASTEXITCODE -ne 0) { throw 'Streamer native peer-row API compilation failed.' }
Write-Output 'PASS actual native peer rows/Streamer +optional Pet against Android36/cached production APIs (R IDs stubbed; no Gradle/resource/device claim).'
