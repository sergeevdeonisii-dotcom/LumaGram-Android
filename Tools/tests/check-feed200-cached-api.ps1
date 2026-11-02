param([string]$JavaHome=$env:JAVA_HOME,
    [string]$AndroidJar,
    [string]$DependencyCache,
    [string[]]$AdditionalJars=@(),
    [switch]$IncludeSendEffects,
    [switch]$IncludeExperienceUi,
    [switch]$IncludeSettingsUi,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/feed200-api'))
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'run-lunagram20-localization-regressions.ps1') -JavaHome $JavaHome -OutputRoot (Join-Path $OutputRoot 'localization')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$names=@('org/telegram/messenger/LunagramFeedMerger.java','org/telegram/messenger/LunagramFeedController.java',
    'org/telegram/messenger/LunagramFeatureCatalog.java',
    'org/telegram/messenger/MessageObject.java',
    'org/telegram/ui/Cells/ChatMessageCell.java',
    'org/telegram/ui/LunagramFeedActivity.java','org/telegram/ui/LunagramFeedSourcesActivity.java','org/telegram/ui/MainTabsActivity.java',
    'org/telegram/messenger/LunagramStreamerMode.java')
if($IncludeSendEffects){$names+=@('org/telegram/ui/TextMessageEnterTransition.java','org/telegram/messenger/LunagramSendEffectGeometry.java','org/telegram/messenger/LunagramExperienceSettings.java','org/telegram/messenger/LunagramSwipeGesture.java')}
if($IncludeExperienceUi){$names+=@('org/telegram/messenger/LunagramExperienceSettings.java','org/telegram/messenger/LunagramSwipeGesture.java',
    'org/telegram/messenger/LunagramInterfaceSounds.java','org/telegram/messenger/LunagramAudioSettings.java','org/telegram/messenger/LunagramVideoEffectsSettings.java','org/telegram/messenger/LunagramPetMotion.java',
    'org/telegram/ui/LunagramExperienceSettingsActivity.java','org/telegram/ui/LunagramVideoEffectsSettingsActivity.java',
    'org/telegram/ui/Components/LunagramPetView.java','org/telegram/ui/Components/LunagramChatAtmosphere.java')}
if($IncludeSettingsUi){$names+=@('org/telegram/messenger/LunagramExperienceSettings.java','org/telegram/messenger/LunagramSwipeGesture.java',
    'org/telegram/messenger/LunagramInterfaceSounds.java','org/telegram/messenger/LunagramAudioSettings.java','org/telegram/messenger/LunagramVideoEffectsSettings.java',
    'org/telegram/ui/LunagramExperienceSettingsActivity.java','org/telegram/ui/LunagramVideoEffectsSettingsActivity.java',
    'org/telegram/ui/SettingsActivity.java','org/telegram/ui/BlackHoleGramSettingsActivity.java','org/telegram/ui/LunagramSubscriptionActivity.java',
    'org/telegram/ui/Components/Premium/GLIcon/LunagramLogoMesh.java', 'org/telegram/ui/Components/Premium/GLIcon/ObjLoader.java',
    'org/telegram/ui/Components/Premium/GLIcon/Icon3D.java')}
$names=@($names | Select-Object -Unique)
$sources=@($names | ForEach-Object { Join-Path $base $_ })
$cached=Join-Path $repo 'TMessagesProj/build/intermediates/compile_library_classes_jar/standalone/bundleLibCompileToJarStandalone/classes.jar'
if(!(Test-Path -LiteralPath $cached) -or !(Test-Path -LiteralPath $AndroidJar)){throw 'Existing app classes and Android SDK android.jar are required. This probe does not build them.'}
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
# Resource linking is checked separately. A generated R only supplies identifiers for Java API checking.
$all=($sources | ForEach-Object {[IO.File]::ReadAllText($_)}) -join "`n"
$r='package org.telegram.messenger; public final class R {'
$groups=[regex]::Matches($all,'R\.(\w+)\.(\w+)') | Group-Object {$_.Groups[1].Value}
$id=1
foreach($group in $groups){
    $r+='public static final class '+$group.Name+' {'
    foreach($field in ($group.Group | ForEach-Object {$_.Groups[2].Value} | Sort-Object -Unique)) {$r+='public static final int '+$field+'='+($id++)+';'}
    $r+='}'
}
$r+='}'
$rpath=Join-Path $run 'R.java'
[IO.File]::WriteAllText($rpath,$r,[Text.UTF8Encoding]::new($false))
$sources+= $rpath
$jars=@($cached,$AndroidJar)
$jars+=$AdditionalJars
if($DependencyCache){$jars+=@(Get-ChildItem -LiteralPath $DependencyCache -Recurse -File -Filter '*.jar' | ForEach-Object FullName)}
$argsPath=Join-Path $run 'javac.args'
$compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','35','-classpath',('"'+(($jars -join [IO.Path]::PathSeparator).Replace('\','/'))+'"'),'-d',('"'+$run.Replace('\','/')+'"'))
$compilerArgs+=@($sources | ForEach-Object {'"'+$_.Replace('\','/')+'"'})
[IO.File]::WriteAllLines($argsPath,$compilerArgs,[Text.UTF8Encoding]::new($false))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
& (Join-Path $JavaHome ('bin/javac'+$suffix)) -J-Xmx384m ('@'+$argsPath)
if($LASTEXITCODE -ne 0){throw 'Feed Java API probe failed; this is not a full Gradle/resource/device test.'}
Write-Output 'PASS feed Java compilation against cached production API + Android SDK (resource IDs stubbed, no APK built)'
