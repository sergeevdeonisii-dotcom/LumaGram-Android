param(
    [string]$JavaHome='D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1',
    [string]$AndroidJar='D:/CodexTools/Lunagram-Android-SDK/platforms/android-36/android.jar',
    [string]$DependencyCache='D:/CodexBuildCache/gradle-user-caches-20261006/8.13/transforms',
    [string]$LifecycleJar='D:/CodexBuildCache/gradle-user-caches-20261006/modules-2/files-2.1/androidx.lifecycle/lifecycle-common-jvm/2.8.7/7174a594afb73a9ad9ac9074ce78b94af3cc52a7/lifecycle-common-jvm-2.8.7.jar',
    [switch]$Integration,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram-tools-api')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$names=@(
    'org/telegram/messenger/LunagramLinkTools.java','org/telegram/messenger/LunagramLinkSettings.java',
    'org/telegram/messenger/LunagramSpeechChunks.java','org/telegram/messenger/LunagramFeedFilter.java',
    'org/telegram/messenger/LunagramStickerList.java','org/telegram/messenger/LunagramMessageAccess.java',
    'org/telegram/messenger/LunagramFeatureCatalog.java','org/telegram/messenger/LunagramMediaAccess.java',
    'org/telegram/messenger/LunagramTextTools.java','org/telegram/ui/Components/LunagramLinkToolsSheet.java',
    'org/telegram/ui/Components/LunagramSpeechSheet.java','org/telegram/ui/LunagramStickerListActivity.java',
    'org/telegram/ui/Components/LumaDialogInput.java'
)
if($Integration){
    $names+=@('org/telegram/ui/ChatActivity.java','org/telegram/ui/Components/ChatActivityEnterView.java',
        'org/telegram/messenger/UserConfig.java','org/telegram/ui/StickersActivity.java',
        'org/telegram/ui/BlackHoleGramSettingsActivity.java','org/telegram/ui/LunagramFeedActivity.java',
        'org/telegram/ui/LunagramSubscriptionActivity.java','org/telegram/ui/Components/AudioPlayerAlert.java',
        'org/telegram/ui/Components/LunagramTextToolsSheet.java','org/telegram/ui/Components/LunagramTextSelectionGuard.java',
        'org/telegram/ui/LunagramReaderActivity.java','org/telegram/messenger/LunagramTextHistory.java',
        'org/telegram/ui/Components/LunagramSwipeMenu.java','org/telegram/messenger/LunagramSwipeGesture.java',
        'org/telegram/messenger/LunagramExperienceSettings.java','org/telegram/ui/LunagramExperienceSettingsActivity.java',
        'org/telegram/ui/Components/LunagramChatAtmosphere.java','org/telegram/ui/TextMessageEnterTransition.java',
        'org/telegram/messenger/LunagramInterfaceSounds.java','org/telegram/messenger/LunagramAudioSettings.java',
        'org/telegram/messenger/LunagramVoiceProcessor.java','org/telegram/messenger/LunagramVoiceSource.java',
        'org/telegram/messenger/MediaController.java','org/telegram/messenger/LunagramMediaTools.java',
        'org/telegram/messenger/LunagramMediaToolsPolicy.java','org/telegram/ui/Components/LunagramMediaToolsSheet.java',
        'org/telegram/messenger/FileLoader.java','org/telegram/messenger/LunagramWorkspaceStore.java',
        'org/telegram/messenger/LunagramWorkspaceModel.java','org/telegram/ui/LunagramWorkspaceActivity.java',
        'org/telegram/messenger/LunagramLocalLabelsModel.java','org/telegram/messenger/LunagramLocalLabels.java',
        'org/telegram/ui/Components/LunagramAliasSheet.java','org/telegram/ui/ProxyListActivity.java',
        'org/telegram/ui/Components/ChatAvatarContainer.java','org/telegram/messenger/LunagramFeedController.java',
        'org/telegram/messenger/LunagramFeedMerger.java',
        'org/telegram/messenger/LunagramIncomingAnimation.java','org/telegram/messenger/LunagramSendEffectGeometry.java',
        'org/telegram/ui/Components/LunagramIncomingMessageEffect.java','org/telegram/ui/Cells/ChatMessageCell.java',
        'org/telegram/ui/recyclerview/ChatListItemAnimator.java',
        'org/telegram/ui/MessageEnterTransitionContainer.java','org/telegram/ui/VoiceMessageEnterTransition.java',
        'org/telegram/messenger/LunagramTypography.java','org/telegram/messenger/AndroidUtilities.java',
        'org/telegram/messenger/ApplicationLoader.java','org/telegram/ui/LunagramTypographyActivity.java',
        'org/telegram/ui/BlackHoleSearchActivity.java','org/telegram/ui/Components/LunagramTypographyViews.java',
        'org/telegram/ui/ActionBar/BaseFragment.java','org/telegram/ui/ActionBar/AlertDialog.java',
        'org/telegram/ui/ActionBar/BottomSheet.java','org/telegram/ui/ActionBar/SimpleTextView.java',
        'org/telegram/ui/ActionBar/Theme.java','org/telegram/ui/Components/AnimatedTextView.java',
        'org/telegram/ui/Components/Text.java','org/telegram/ui/Components/RecyclerListView.java',
        'org/telegram/ui/Components/Paint/PaintTypeface.java',
        'org/telegram/ui/ActionBar/ActionBar.java','org/telegram/ui/ActionBar/ActionBarMenu.java',
        'org/telegram/ui/ActionBar/ActionBarMenuItem.java',
        'org/telegram/ui/Components/SendingFileDrawable.java','org/telegram/ui/Components/PlayingGameDrawable.java')
}
$sources=@($names | Sort-Object -Unique | ForEach-Object { Join-Path $base $_ })
$cached=Join-Path $repo 'TMessagesProj/build/intermediates/compile_library_classes_jar/standalone/bundleLibCompileToJarStandalone/classes.jar'
$javac=Join-Path $JavaHome 'bin/javac.exe'
foreach($required in @($cached,$AndroidJar,$javac,$DependencyCache,$LifecycleJar)){
    if(!(Test-Path -LiteralPath $required)){throw "Missing cached compile dependency: $required"}
}
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$hashes=@{};foreach($source in $sources){$hashes[$source]=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash}
# Identifier-only R is not resource linking. Listed app classes and Android APIs are actual sources.
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
$compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','35','-classpath',('"'+(($jars -join [IO.Path]::PathSeparator).Replace('\','/'))+'"'),'-d',('"'+$run.Replace('\','/')+'"'))
$compilerArgs+=@(($sources+@($rpath)) | ForEach-Object {'"'+$_.Replace('\','/')+'"'})
[IO.File]::WriteAllLines($argsPath,$compilerArgs,[Text.UTF8Encoding]::new($false))
Write-Output "Compiling $($sources.Count) actual sources against Android36; artifacts: $run"
$memory=if($Integration){'-J-Xmx512m'}else{'-J-Xmx384m'}
& $javac $memory ('@'+$argsPath)
if($LASTEXITCODE -ne 0){throw 'Tools cached Android API compilation failed.'}
foreach($source in $sources){if($hashes[$source] -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash){throw "Source changed during check; rerun: $source"}}
Write-Output 'PASS actual tools sources against cached production + Android36 APIs (R IDs stubbed; no APK/device claim).'
