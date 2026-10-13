param(
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$AndroidJar='C:/Users/denis228/Documents/Codex/2026-07-23/new-chat-2/work/android-sdk/platforms/android-36/android.jar',
    [string]$DependencyCache='D:/CodexBuildCache/gradle-user-caches-20261006/8.13/transforms',
    [string]$LifecycleJar='D:/CodexBuildCache/gradle-user-caches-20261006/modules-2/files-2.1/androidx.lifecycle/lifecycle-common-jvm/2.8.7/7174a594afb73a9ad9ac9074ce78b94af3cc52a7/lifecycle-common-jvm-2.8.7.jar',
    [switch]$IncludeMedia,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram20-camera-api')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$names=@(
    'org/telegram/ui/Components/InstantCameraViewBase.java',
    'org/telegram/ui/Components/InstantCameraView.java',
    'org/telegram/ui/Components/InstantCameraView2.java',
    'org/telegram/ui/Components/LumaRoundCameraFlip.java',
    'org/telegram/messenger/LunagramVideoEffectsSettings.java',
    'org/telegram/messenger/LunagramMediaAccess.java',
    'org/telegram/messenger/LunagramFeatureCatalog.java'
)
if ($IncludeMedia) {
    $names+=@(
        'org/telegram/messenger/LunagramAudioSettings.java',
        'org/telegram/messenger/LunagramVoiceProcessor.java',
        'org/telegram/messenger/LunagramVoiceSource.java',
        'org/telegram/messenger/MediaController.java',
        'org/telegram/ui/PhotoViewer.java',
        'org/telegram/ui/Components/LunagramMemeEditor.java',
        'org/telegram/ui/Components/ChatActivityEnterView.java',
        'org/telegram/ui/LunagramVideoEffectsSettingsActivity.java'
    )
}
$sources=@($names | ForEach-Object { Join-Path $base $_ })
$sources+=@(Get-ChildItem -LiteralPath (Join-Path $base 'org/telegram/utils/camera/roundvideo') -File -Filter '*.java' | ForEach-Object FullName)
$cached=Join-Path $repo 'TMessagesProj/build/intermediates/compile_library_classes_jar/standalone/bundleLibCompileToJarStandalone/classes.jar'
$javac=Join-Path $JavaHome 'bin/javac.exe'
foreach ($required in @($cached,$AndroidJar,$javac,$DependencyCache)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing existing compile dependency: $required" }
}
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$initialHashes=@{}
foreach ($source in $sources) { $initialHashes[$source]=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash }
# Resource names are compiled, but this isolated API check does not run aapt.
$all=($sources | ForEach-Object { [IO.File]::ReadAllText($_) }) -join "`n"
$r='package org.telegram.messenger; public final class R {'
$groups=[regex]::Matches($all,'R\.(\w+)\.(\w+)') | Group-Object { $_.Groups[1].Value }
$id=1
foreach ($group in $groups) {
    $r+='public static final class '+$group.Name+' {'
    foreach ($field in ($group.Group | ForEach-Object { $_.Groups[2].Value } | Sort-Object -Unique)) {
        $r+='public static final int '+$field+'='+($id++)+';'
    }
    $r+='}'
}
$r+='}'
$rpath=Join-Path $run 'R.java'
[IO.File]::WriteAllText($rpath,$r,[Text.UTF8Encoding]::new($false))
$jars=@($cached,$AndroidJar,$LifecycleJar)
$jars+=@(Get-ChildItem -LiteralPath $DependencyCache -Recurse -File -Filter '*.jar' | ForEach-Object FullName)
$jars+=@(Get-ChildItem -LiteralPath (Join-Path $repo 'TMessagesProj_Modules') -Recurse -File -Filter 'classes.jar' | Where-Object FullName -Match 'compile_library_classes_jar' | ForEach-Object FullName)
$argsPath=Join-Path $run 'javac.args'
$compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','30','-classpath',('"'+(($jars -join [IO.Path]::PathSeparator).Replace('\','/'))+'"'),'-d',('"'+$run.Replace('\','/')+'"'))
$compilerArgs+=@(($sources+@($rpath)) | ForEach-Object { '"'+$_.Replace('\','/')+'"' })
[IO.File]::WriteAllLines($argsPath,$compilerArgs,[Text.UTF8Encoding]::new($false))
Write-Output ("Compiling {0} actual camera sources against Android36 and {1} cached jars." -f $sources.Count,$jars.Count)
Write-Output "Artifacts: $run"
& $javac -J-Xmx512m ('@'+$argsPath)
if ($LASTEXITCODE -ne 0) { throw 'Actual camera-source Android API compilation failed.' }
foreach ($source in $sources) {
    if ($initialHashes[$source] -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) {
        throw "Source changed during compilation; rerun after edits: $source"
    }
}
Write-Output 'PASS actual camera Controller, ConcurrentCamera, Session, GL/effects, both recorder views/Base and Flip against Android36/cached production APIs (R IDs stubbed; no Gradle/resource/device claim).'
