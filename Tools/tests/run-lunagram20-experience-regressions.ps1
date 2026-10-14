param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram20-experience'))
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'run-lunagram20-localization-regressions.ps1') -JavaHome $JavaHome -OutputRoot (Join-Path $OutputRoot 'localization')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'lunagram20-experience/Experience200Probe.java.template'))
$mapping=@{
    SETTINGS='org/telegram/messenger/LunagramExperienceSettings.java'; ACCOUNT_DATA='org/telegram/messenger/LumaAccountData.java'
    SWIPE='org/telegram/messenger/LunagramSwipeGesture.java'; SOUNDS='org/telegram/messenger/LunagramInterfaceSounds.java'
    PET='org/telegram/ui/Components/LunagramPetView.java'; ATMOSPHERE='org/telegram/ui/Components/LunagramChatAtmosphere.java'
    PET_MOTION='org/telegram/messenger/LunagramPetMotion.java'
}
foreach($key in $mapping.Keys){
    $source=[IO.File]::ReadAllText((Join-Path $base $mapping[$key]))
    $source=[regex]::Replace($source,'(?m)^\s*(?:package|import) [^;]+;\s*','')
    $source=$source.Replace('public final class ','static final class ')
    $template=$template.Replace('@@'+$key+'@@',$source)
}
function Run-Probe([string]$name,[string]$source,[string]$expected='') {
    $folder=Join-Path $run $name;New-Item -ItemType Directory -Path $folder -Force|Out-Null
    $file=Join-Path $folder 'Experience200Probe.java';[IO.File]::WriteAllText($file,$source,[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $folder $file
    if($LASTEXITCODE -ne 0){throw "Experience probe compilation failed: $name"}
    $result=& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $folder Experience200Probe 2>&1
    $exit=$LASTEXITCODE;$output=($result|Out-String)
    if($expected){if($exit -eq 0 -or !$output.Contains('AssertionError: '+$expected)){throw "Negative control $name did not detect intended regression: $output"};Write-Output "PASS negative control $name"}
    elseif($exit -ne 0){throw "Experience behavior failed: $output"}else{Write-Output $output.Trim()}
}
Run-Probe 'production' $template
$motionFolder=Join-Path $run 'pet-motion';New-Item -ItemType Directory -Path $motionFolder -Force|Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $motionFolder (Join-Path $base 'org/telegram/messenger/LunagramPetMotion.java') (Join-Path $PSScriptRoot 'lunagram20-experience/PetMotion200Test.java')
if($LASTEXITCODE -ne 0){throw 'Pet pure model compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $motionFolder PetMotion200Test
if($LASTEXITCODE -ne 0){throw 'Pet pure model regression failed'}
$mutations=@(
    @('identity-slot','"luma_user_" + userId','"luma_user_" + account','slot reuse isolates identity'),
    @('pet-system-motion','SharedConfig.animationsEnabled() && AndroidUtilities.getAnimatorDurationScale() > 0','SharedConfig.animationsEnabled()','system reduced motion pet'),
    @('pet-static-finish','if (ends > now) return allowed ? NEXT_FRAME : ends - now;','if (ends > now) return allowed ? NEXT_FRAME : NO_CALLBACK;','static happy gets final redraw'),
    @('pet-hidden-timer','!disposed && !recording && !paused && !ApplicationLoader.mainInterfacePaused','!disposed && !recording && !ApplicationLoader.mainInterfacePaused','paused pet cannot schedule work'),
    @('pet-pause-idempotence','if (paused == value) return;','/* removed pause idempotence */','unchanged compositor setters do not restart scheduler or invalidate'),
    @('pet-rail-idempotence','if (explicitRail && Float.compare(nextLeft, railLeft) == 0 && Float.compare(nextRight, railRight) == 0) return;','/* removed rail idempotence */','unchanged compositor setters do not restart scheduler or invalidate'),
    @('sound-owner','owner != UserConfig.getInstance(account).getClientUserId()','false','queued owner guard'),
    @('sound-ringer','manager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL','false','ringer mode guard'),
    @('sound-background','if (ApplicationLoader.mainInterfacePaused || owner','if (owner','queued foreground guard'),
    @('atmosphere-paused','if (paused || mode==0','if (mode==0','atmosphere paused no work'),
    @('atmosphere-reduced','SharedConfig.animationsEnabled() && AndroidUtilities.getAnimatorDurationScale()>0','SharedConfig.animationsEnabled()','reduced atmosphere static and no timer'),
    @('no-experience-grant','return LunagramSubscriptionAccess.canUse(account, feature);','return true;','expiry disables swipe/feed'),
    @('atmosphere-stale-grant','        mode=LunagramExperienceSettings.atmosphere(account,dialogId);','','already-open atmosphere respects expiry without refresh'),
    @('sound-stale-epoch','|| epoch != LunagramSubscriptionClient.accountEpoch(account)','','queued same-owner epoch guard'),
    @('incoming-shared-outgoing-key','"experience.incomingEffect"','"experience.sendEffect"','incoming default does not inherit outgoing'),
    @('incoming-selected-account','public static int incomingEffect(int account) { return allowed(account,','public static int incomingEffect(int account) { return allowed(UserConfig.selectedAccount,','incoming grant belongs to requested account')
)
foreach($m in $mutations){if(!$template.Contains($m[1])){throw "Missing mutation anchor: $($m[0])"};Run-Probe $m[0] ($template.Replace($m[1],$m[2])) $m[3]}
$activity=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/LunagramExperienceSettingsActivity.java'))
foreach($pair in @(@('petNames',2),@('soundNames',5),@('sendNames',4),@('incomingNames',4),@('atmosphereNames',5),@('intensityNames',3))){
    $body=[regex]::Match($activity,'private String\[\] '+$pair[0]+'\(\) \{ return names\(([^)]+)\); \}').Groups[1].Value
    if([regex]::Matches($body,'R\.string\.').Count -ne $pair[1]){throw "Settings labels mismatch enum: $($pair[0])"}
}
$outgoingNames=[regex]::Match($activity,'private String\[\] sendNames\(\) \{ return names\(([^)]+)\); \}').Groups[1].Value
$incomingNames=[regex]::Match($activity,'private String\[\] incomingNames\(\) \{ return names\(([^)]+)\); \}').Groups[1].Value
if(!$outgoingNames -or $incomingNames -ne $outgoingNames){throw 'Incoming and outgoing settings must offer the same effect names and stable enum order'}
foreach($required in @('if (!swipeOnly)', 'if (dialogId != 0)', 'if (LunagramSwipeGesture.validOrder(order))', 'screen.setCurrentAccount(currentAccount)', 'list.adapter.update(false)')){if(!$activity.Contains($required)){throw "Settings UI contract missing: $required"}}
foreach($required in @('case 4: case 12: return Feature.SEND_ANIMATIONS;', 'TextDetailCell.Factory.of(12, text(R.string.LunagramIncomingEffectTitle), incomingNames()[LunagramExperienceSettings.incomingEffect(currentAccount)])', 'else if (item.id == 12) LunagramExperienceSettings.setIncomingEffect(currentAccount, index);', 'if (!currentOwner() || index < 0 || index >= names.length || !LunagramFeatureGate.require(this, feature)) return;')){if(!$activity.Contains($required)){throw "Incoming settings UI contract missing: $required"}}
$chat=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/ChatActivity.java'))
foreach($required in @('lunagramAtmosphere.setPaused(true)', 'lunagramAtmosphere.setPaused(false)', 'lunagramAtmosphere.refresh(currentAccount, dialog_id)', 'chatActivityEnterView.lunagramPetView.getParent() == null', 'bottomViewsVisibilityController.getCurrentPriorityContainerId() == MESSAGE_INPUT_CONTAINER', 'pet.setAlpha(0f)', 'y < (actionBar != null ? actionBar.getBottom() : 0)', 'chatInputViewsContainer.getPetRailBounds(lunagramPetRailBounds)', 'pet.getFloorOffsetDp()', 'pet.setPaused(true)', 'pet.setPaused(false)')){if(!$chat.Contains($required)){throw "Chat attachment contract missing: $required"}}
if($chat.Contains('isLunagramPetVisible() ? dp(50) : 0') -or $chat.Contains('paddingBottom + petSpace')){throw 'Pet must never reserve message-list padding'}
$composer=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/Components/ChatActivityEnterView.java'))
foreach($required in @('lunagramPetView.dispose()', 'lunagramPetView.bindComposer(messageEditText)', 'lunagramPetView.setRecording(')){if(!$composer.Contains($required)){throw "Composer lifecycle contract missing: $required"}}
Write-Output 'PASS source integration contracts: settings enums/account routing, pet overlay/no reserved padding/disposal, atmosphere pause/refresh'
Write-Output "Results: $run"
