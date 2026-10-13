param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram20-settings'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Extract([string]$source,[string]$signature){
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal);if($start -lt 0){throw "Missing production method $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++){if($source[$end] -eq '{'){$depth++};if($source[$end] -eq '}'){$depth--}}
    if($depth -ne 0){throw 'Unbalanced production source'}
    return $source.Substring($start,$end-$start)
}
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$nav=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/MainTabsActivity.java'))
$feed=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/LunagramFeedActivity.java'))
$message=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/messenger/MessageObject.java'))
$nativeCell=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/Cells/ChatMessageCell.java'))
$settings=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/SettingsActivity.java'))
$experience=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/LunagramExperienceSettingsActivity.java'))
$video=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/LunagramVideoEffectsSettingsActivity.java'))
$subscription=[IO.File]::ReadAllText((Join-Path $base 'org/telegram/ui/LunagramSubscriptionActivity.java'))
$navMethods=(@('private int logicalToPosition(int logical)','private int positionToLogical(int position)','private int indexToPosition(int index)',
    'protected int getFragmentsCount()','private void syncFeedVisibility()','private void updateTabsVisibility(boolean animated)') | ForEach-Object{Extract $nav $_}) -join "`n"
$cell=(Extract $feed 'private final class FeedMessageCell extends ChatMessageCell').Replace('@Override ','')
$bind=(Extract $feed 'void bind(MessageObject value)').Replace('org.telegram.messenger.LunagramStreamerMode','LunagramStreamerMode')
$glass=(Extract $feed '@Override public BlurredBackgroundSourceRenderNode getGlassSource()').Replace('@Override ','')
$widthMethods=(@('private int getParentWidth()','public void setLayoutParentWidth(int width)','public int getLayoutParentWidth()','public void resetLayout()','public boolean checkLayout()') | ForEach-Object{Extract $message $_}) -join "`n"
$hostMethods=(@('private int getHostTabletSide()','private int getGroupPhotosWidth()') | ForEach-Object{Extract $nativeCell $_}) -join "`n"
$photoStart=$nativeCell.IndexOf('boolean useFullWidth = false;',[StringComparison]::Ordinal)
if($photoStart -lt 0){throw 'Native media width branch missing'}
$photo=[regex]::Match($nativeCell.Substring($photoStart),'photoWidth = \(int\) \([^;]*? \* 0\.7f\);').Value
$link=[regex]::Match($nativeCell,'linkPreviewMaxWidth = (?:getHostTabletSide\(\)|AndroidUtilities\.getMinTabletSide\(\)) - dp\([^;]+;').Value
$text=[regex]::Match($nativeCell,'maxWidth = (?:getHostTabletSide\(\)|AndroidUtilities\.getMinTabletSide\(\)) - dp\(\(isArticle[^;]+;').Value
if(!$photo -or !$link -or !$text -or !$nativeCell.Contains('int availableWidth = maxWidth;') -or !$nativeCell.Contains('reactionsLayoutInBubble.measure(availableWidth, Gravity.LEFT)')){throw 'Actual native photo/link/reaction constraint seams missing'}
$constraints='int photoConstraint(){int photoWidth;'+$photo+'return photoWidth;} int linkConstraint(){int linkPreviewMaxWidth;'+$link+'return linkPreviewMaxWidth;} int reactionConstraint(){int maxWidth;'+$text+'int availableWidth = maxWidth; return availableWidth;}'
$drawStart=$nativeCell.IndexOf('public void drawContent(Canvas canvas, boolean preview)',[StringComparison]::Ordinal)
$drawEnd=$nativeCell.IndexOf('boolean newPart =',$drawStart,[StringComparison]::Ordinal)
if($drawStart -lt 0 -or $drawEnd -le $drawStart -or !$nativeCell.Contains('drawContent(canvas, false);')){throw 'Normal native draw dispatch/guard missing'}
$drawGuard=$nativeCell.Substring($drawStart,$drawEnd-$drawStart).Replace('public void drawContent(Canvas canvas, boolean preview)','void drawContent(boolean preview)')+'contentDraws++; }'
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'lunagram20-settings/LunagramSettingsBugfix20Probe.java.template'))
$template=$template.Replace('/* NAV_METHODS */',$navMethods).Replace('/* FEED_CELL */',$cell).Replace('/* POST_BIND */',$bind).Replace('/* GLASS_GETTER */',$glass).Replace('/* MESSAGE_WIDTH_METHODS */',$widthMethods).Replace('/* NATIVE_DRAW_GUARD */',$drawGuard).Replace('/* NATIVE_HOST_METHODS */',$hostMethods).Replace('/* NATIVE_HOST_CONSTRAINTS */',$constraints)
$probe=Join-Path $run 'LunagramSettingsBugfix20Probe.java';[IO.File]::WriteAllText($probe,$template,[Text.UTF8Encoding]::new($false))
$javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probe (Join-Path $base 'org/telegram/ui/Components/Premium/GLIcon/LunagramLogoMesh.java') (Join-Path $PSScriptRoot 'lunagram20-settings/LunagramLogoMeshTest.java')
if($LASTEXITCODE -ne 0){throw 'Actual nav/feed extraction compilation failed'}
& $java -Xmx128m -cp $run LunagramSettingsBugfix20Probe
if($LASTEXITCODE -ne 0){throw 'Actual nav/feed behavior failed'}
& $java -Xmx128m -cp $run LunagramLogoMeshTest
if($LASTEXITCODE -ne 0){throw 'Actual logo mesh failed'}
foreach($mutation in @(@('recycled-layout',$template.Replace('display.resetLayout();','').Replace('display.setLayoutParentWidth(message.getParentWidth());','display.setLayoutParentWidth(0);')),
    @('previous-preview-blank',$template.Replace('display.setLayoutParentWidth(message.getParentWidth());','display.preview = true; display.setLayoutParentWidth(message.getParentWidth());')),
    @('tablet-layout-width',$template.Replace('int newMinSize = layoutParentWidth > 0 ? layoutParentWidth :','int newMinSize =')),
    @('tablet-photo-width',$template.Replace($photo,$photo.Replace('getHostTabletSide()','AndroidUtilities.getMinTabletSide()'))),
    @('tablet-link-width',$template.Replace($link,$link.Replace('getHostTabletSide()','AndroidUtilities.getMinTabletSide()'))),
    @('tablet-reaction-width',$template.Replace($text,$text.Replace('getHostTabletSide()','AndroidUtilities.getMinTabletSide()'))),
    @('tablet-double-pane',$template.Replace('if (object != null && object.getLayoutParentWidth() > 0) return Math.min(width, object.getLayoutParentWidth());','')),
    @('hidden-map',$template.Replace('logical > POSITION_FEED ? 1 : 0','logical > POSITION_FEED ? 0 : 0')),
    @('empty-glass',$template.Replace('glassSource.updateDisplayListIfNeeded();','')))){
    $folder=Join-Path $run $mutation[0];New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $file=Join-Path $folder 'LunagramSettingsBugfix20Probe.java';[IO.File]::WriteAllText($file,$mutation[1],[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $folder $file
    if($LASTEXITCODE -ne 0){throw 'Negative control must compile'}
    $negative=& $java -Xmx128m -cp $folder LunagramSettingsBugfix20Probe 2>&1
    if($LASTEXITCODE -eq 0){throw ('Negative control not caught: '+$mutation[0])}
    Write-Output ('PASS executable negative control: '+$mutation[0])
}
foreach($required in @('tabsView.setClickable(factor > .99f)','tabsView.setEnabled(factor > .99f)',
    'setFeedVisible(currentAccount, false)','final int position = indexToPosition(tabIndex)','if (position < 0) return;',
    'position = positionToLogical(position);')){if(!$nav.Contains($required)){throw "Navigation integration missing: $required"}}
if(!$feed.Contains('addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))') -or
    !$feed.Contains('setClipToOutline(true)')){throw 'Native feed origin/body/actions must remain one rounded card'}
if($settings.IndexOf('SettingCell.Factory.of(LUNAGRAM_EXPERIENCE_ROW') -lt $settings.IndexOf('SettingCell.Factory.of(BHG_ADVANCED_ROW')){throw 'Capabilities must follow Lunagram-expanded in main Settings'}
if(!$experience.Contains('LunagramExperienceSettings.setFeedVisible') -or $experience.Contains('LunagramPetFox') -or $experience.Contains('LunagramPetGhost')){throw 'Feed reenable and Off/Cat-only settings lost'}
if(!$video.Contains('list.setSections()') -or $video.Contains('LunagramMemeInfo')){throw 'Video settings must use native sections without unrelated duplicated meme text'}
foreach($required in @('Icon3D.TYPE_LUNAGRAM','onSurfaceTextureDestroyed(SurfaceTexture surface)','logoSurfaceGeneration++',
    'generation != logoSurfaceGeneration','whenReady(() ->','subscriptionLogo.cancelIdleAnimation()',
    'subscriptionLogo.stopThread()','subscriptionLogo.getLocalVisibleRect','SharedConfig.animationsEnabled()',
    'AndroidUtilities.getAnimatorDurationScale()','power.isPowerSaveMode()')){if(!$subscription.Contains($required)){throw "3D logo lifecycle/motion contract missing: $required"}}
Write-Output 'PASS native source integration guards. Scope: actual extracted methods + modeled cells/RenderNode/lifecycle, not device pixels.'
