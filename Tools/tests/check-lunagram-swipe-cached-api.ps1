param(
    [string]$JavaHome='D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1',
    [string]$AndroidJar='D:/CodexTools/Lunagram-Android-SDK/platforms/android-36/android.jar',
    [string]$DependencyCache='D:/CodexBuildCache/gradle-user-caches-20261006/8.13/transforms',
    [string]$LifecycleJar='D:/CodexBuildCache/gradle-user-caches-20261006/modules-2/files-2.1/androidx.lifecycle/lifecycle-common-jvm/2.8.7/7174a594afb73a9ad9ac9074ce78b94af3cc52a7/lifecycle-common-jvm-2.8.7.jar',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram-swipe-api')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$names=@('org/telegram/ui/ChatActivity.java','org/telegram/ui/Components/LunagramSwipeMenu.java',
    'org/telegram/messenger/LunagramSwipeGesture.java','org/telegram/messenger/LunagramExperienceSettings.java',
    'org/telegram/messenger/LunagramFeatureCatalog.java','org/telegram/messenger/LunagramMediaAccess.java',
    'org/telegram/messenger/LunagramAudioSettings.java','org/telegram/messenger/LunagramInterfaceSounds.java',
    'org/telegram/ui/LunagramExperienceSettingsActivity.java','org/telegram/ui/Components/LunagramChatAtmosphere.java',
    'org/telegram/ui/TextMessageEnterTransition.java')
$sources=@($names | ForEach-Object { Join-Path $base $_ })
$cached=Join-Path $repo 'TMessagesProj/build/intermediates/compile_library_classes_jar/standalone/bundleLibCompileToJarStandalone/classes.jar'
$javac=Join-Path $JavaHome 'bin/javac.exe'
foreach($required in @($cached,$AndroidJar,$javac,$DependencyCache,$LifecycleJar)){
    if(!(Test-Path -LiteralPath $required)){throw "Missing cached compile dependency: $required"}
}
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$hashes=@{};foreach($source in $sources){$hashes[$source]=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash}
# Identifier-only R is not resource linking. App sources and Android APIs are real.
$all=($sources | ForEach-Object {[IO.File]::ReadAllText($_)}) -join "`n"
$r='package org.telegram.messenger; public final class R {'
$groups=[regex]::Matches($all,'(?<!\w)R\.(\w+)\.(\w+)') | Group-Object {$_.Groups[1].Value}
$id=1
foreach($group in $groups){
    $r+='public static final class '+$group.Name+' {'
    foreach($field in ($group.Group | ForEach-Object {$_.Groups[2].Value} | Sort-Object -Unique)){$r+='public static final int '+$field+'='+($id++)+';'}
    $r+='}'
}
$r+='}';$rpath=Join-Path $run 'R.java'
[IO.File]::WriteAllText($rpath,$r,[Text.UTF8Encoding]::new($false))
$jars=@($cached,$AndroidJar,$LifecycleJar)
$jars+=@(Get-ChildItem -LiteralPath $DependencyCache -Recurse -File -Filter '*.jar' | ForEach-Object FullName)
$jars+=@(Get-ChildItem -LiteralPath (Join-Path $repo 'TMessagesProj_Modules') -Recurse -File -Filter 'classes.jar' | Where-Object FullName -Match 'compile_library_classes_jar' | ForEach-Object FullName)
$argsPath=Join-Path $run 'javac.args'
$compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','25','-classpath',('"'+(($jars -join [IO.Path]::PathSeparator).Replace('\','/'))+'"'),'-d',('"'+$run.Replace('\','/')+'"'))
$compilerArgs+=@(($sources+@($rpath)) | ForEach-Object {'"'+$_.Replace('\','/')+'"'})
[IO.File]::WriteAllLines($argsPath,$compilerArgs,[Text.UTF8Encoding]::new($false))
Write-Output "Compiling full ChatActivity, SwipeMenu, SwipeGesture, ExperienceSettings against Android36 and $($jars.Count) cached jars; artifacts: $run"
& $javac -J-Xmx512m ('@'+$argsPath)
if($LASTEXITCODE -ne 0){throw 'Full swipe-source cached Android API compilation failed.'}
foreach($source in $sources){if($hashes[$source] -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash){throw "Source changed during check; rerun: $source"}}
Write-Output 'PASS actual full swipe sources against cached production + Android36 APIs (R IDs stubbed; no Gradle/resource/APK/device claim).'
