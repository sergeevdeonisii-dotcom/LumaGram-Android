param([string]$JavaHome = $env:JAVA_HOME, [string]$AndroidJar,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs/composer-layout150'))
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sourceRoot = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui'
$run = Join-Path $OutputRoot ('run-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
function Check([bool]$Valid, [string]$Message) { if (!$Valid) { throw $Message } }
function Method([string]$Source, [string]$Signature) {
    $start = $Source.IndexOf($Signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw ('Missing method: ' + $Signature) }
    $open = $Source.IndexOf('{', $start)
    $depth = 1
    for ($end = $open + 1; $end -lt $Source.Length -and $depth -gt 0; $end++) {
        if ($Source[$end] -eq '{') { $depth++ }
        if ($Source[$end] -eq '}') { $depth-- }
    }
    Check ($depth -eq 0) ('Unbalanced method: ' + $Signature)
    return $Source.Substring($start, $end - $start)
}
$composer = [IO.File]::ReadAllText((Join-Path $sourceRoot 'Components/ChatActivityEnterView.java'))
$chat = [IO.File]::ReadAllText((Join-Path $sourceRoot 'ChatActivity.java'))
$reply = [IO.File]::ReadAllText((Join-Path $sourceRoot 'Components/ChatReplyContainer.java'))
$cameraBase = [IO.File]::ReadAllText((Join-Path $sourceRoot 'Components/InstantCameraViewBase.java'))
$cameraNew = [IO.File]::ReadAllText((Join-Path $sourceRoot 'Components/InstantCameraView2.java'))
$cameraLegacy = [IO.File]::ReadAllText((Join-Path $sourceRoot 'Components/InstantCameraView.java'))
$inputContainer = [IO.File]::ReadAllText((Join-Path $sourceRoot 'Components/chat/ChatInputViewsContainer.java'))
$methods = [ordered]@{
    ACTUAL_CANCEL_RUNNING = 'private void cancelRunningRecordUiAnimation()'
    ACTUAL_CANCEL_LOCK = 'private void cancelRecordLockUiAnimation()'
    ACTUAL_CANCEL_PANEL = 'private void cancelRecordPanelUiAnimation()'
    ACTUAL_CLEAR_CALLBACKS = 'private static void clearRecordAnimationCallbacks(Animator animation)'
    ACTUAL_RESTORE_PARENT = 'private void restoreRecordPreviewParent()'
    ACTUAL_START_RUNNING = 'private void startRunningRecordUiAnimation(boolean animated)'
    ACTUAL_MOTION_ENABLED = 'private static boolean isRecordUiAnimationEnabled()'
    ACTUAL_START_PANEL = 'private void startRecordPanelUiAnimation()'
    ACTUAL_RECORD_VISIBILITY = 'public boolean isRecordingUiVisible()'
    ACTUAL_FAST_EXIT_HIDE = 'private void hideFastRecordUiContent()'
}
$fixture = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'composer-layout150/RecordUi150Probe.java.template'))
foreach ($entry in $methods.GetEnumerator()) { $fixture = $fixture.Replace("/* $($entry.Key) */", (Method $composer $entry.Value)) }
$probe = Join-Path $run 'RecordUi150Probe.java'
[IO.File]::WriteAllText($probe, $fixture, [Text.UTF8Encoding]::new($false))
$capsuleMethod = Method $cameraBase 'protected final void updateCameraControlsCapsule(LinearLayout controls)'
$capsuleFixture = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'composer-layout150/CameraControls150Probe.java.template'))
$capsuleProbe = Join-Path $run 'CameraControls150Probe.java'
[IO.File]::WriteAllText($capsuleProbe, $capsuleFixture.Replace('/* ACTUAL_CAMERA_CAPSULE */', $capsuleMethod), [Text.UTF8Encoding]::new($false))
$replyMethods = [ordered]@{
    ACTUAL_BACKGROUND = (Method $chat 'private void updateLiquidInputBackgroundGeometry()')
    ACTUAL_CONTROL_VISIBLE = (Method $chat 'private static boolean isLiquidInputControlVisible(View view)')
    ACTUAL_CONTROL_RIGHT = (Method $chat 'private int getLiquidInputControlRight(View view)')
    ACTUAL_REPLY_PANEL = (Method $chat 'private void updateLiquidGlassReplyPanelLayout(boolean enabled)')
    ACTUAL_CONTENT_SETTER = (Method $reply 'public void setLiquidGlassContentInset(int contentInsetPx)')
    ACTUAL_OUTER_MODE = (Method $reply 'public void setLiquidGlassMode(boolean enabled)')
    ACTUAL_LAYOUT_MODE = (Method $reply 'private void setLiquidGlassMode(boolean enabled)')
    ACTUAL_GET_PX = (Method $reply 'public int getTextInsetPx(boolean withImage)')
    ACTUAL_GET_DP = (Method $reply 'public int getTextInsetDp(boolean withImage)')
    ACTUAL_LEFT_MARGIN = (Method $reply 'private void setLeftMargin(View view, int leftMargin)')
    ACTUAL_RIGHT_MARGIN = (Method $reply 'private void setRightMargin(View view, int rightMargin)')
}
$replyFixture = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'composer-layout150/ReplyPreview150Probe.java.template'))
foreach ($entry in $replyMethods.GetEnumerator()) { $replyFixture = $replyFixture.Replace("/* $($entry.Key) */", $entry.Value) }
$replyProbe = Join-Path $run 'ReplyPreview150Probe.java'
[IO.File]::WriteAllText($replyProbe, $replyFixture, [Text.UTF8Encoding]::new($false))
$petMethods = [ordered]@{
    ACTUAL_PET_HOST = (Method $chat 'private boolean isLunagramPetHostAvailable()')
    ACTUAL_PET_VISIBLE = (Method $chat 'private boolean isLunagramPetVisible()')
    ACTUAL_PET_ON_RAIL = (Method $chat 'private void setLunagramPetOnRail(boolean visible)')
    ACTUAL_PET_PLACEMENT = (Method $chat 'private void updateLunagramPetPlacement()').Replace('org.telegram.ui.Components.LunagramPetView', 'PetView')
    ACTUAL_PET_RAIL = (Method $inputContainer 'public boolean getPetRailBounds(RectF out)')
}
$petFixture = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'composer-layout150/PetMount150Probe.java.template'))
foreach ($entry in $petMethods.GetEnumerator()) { $petFixture = $petFixture.Replace("/* $($entry.Key) */", $entry.Value) }
$petProbe = Join-Path $run 'PetMount150Probe.java'
[IO.File]::WriteAllText($petProbe, $petFixture, [Text.UTF8Encoding]::new($false))
$production = @((Join-Path $sourceRoot 'Components/LumaComposerGeometry.java'), (Join-Path $sourceRoot 'Components/LumaRecordUiToken.java'))
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $production (Join-Path $PSScriptRoot 'composer-layout150/ComposerGeometry150Test.java') $probe $capsuleProbe $replyProbe $petProbe
Check ($LASTEXITCODE -eq 0) 'Production geometry/ownership probe compilation failed.'
& $java -Xmx128m -cp $classes org.telegram.ui.Components.ComposerGeometry150Test
Check ($LASTEXITCODE -eq 0) 'Composer geometry failed.'
& $java -Xmx128m -cp $classes RecordUi150Probe
Check ($LASTEXITCODE -eq 0) 'Extracted animation-owner probe failed.'
& $java -Xmx128m -cp $classes CameraControls150Probe
Check ($LASTEXITCODE -eq 0) 'Extracted camera native-touch capsule probe failed.'
& $java -Xmx128m -cp $classes ReplyPreview150Probe
Check ($LASTEXITCODE -eq 0) 'Extracted composer preview content/close layout probe failed.'
& $java -Xmx128m -cp $classes PetMount150Probe
Check ($LASTEXITCODE -eq 0) 'Extracted actual pet rail/host floor placement probe failed.'
# Confirm the executable probe catches the original static-origin defect and missing close
# reservation; these mutations affect only generated fixtures, never the production files.
$replyMutations = @(
    @('static-left', 'Math.max(dp(64), liquidGlassContentInsetPx) + (withImage ? dp(44) : 0)', 'dp(64) + (withImage ? dp(44) : 0)'),
    @('pencil-overlap', 'Math.max(dp(64), liquidGlassContentInsetPx) + (withImage ? dp(44) : 0)', 'liquidGlassContentInsetPx + (withImage ? dp(44) : 0)'),
    @('pencil-left-edge', 'setLeftMargin(icon, dp(LumaComposerGeometry.replyIconInsetDp(enabled)));', 'setLeftMargin(icon, 0);'),
    @('close-overlap', 'enabled ? dp(45) + dp(48) + dp(8) : dp(52)', 'enabled ? dp(45) : dp(52)'),
    @('missing-propagation', 'replyLayout.setLiquidGlassContentInset(contentInset);', '')
)
foreach ($mutation in $replyMutations) {
    Check ($replyFixture.Contains($mutation[1])) ('Negative mutation target missing: ' + $mutation[0])
    $negative = Join-Path $run ('negative-' + $mutation[0])
    New-Item -ItemType Directory -Path $negative | Out-Null
    $negativeProbe = Join-Path $negative 'ReplyPreview150Probe.java'
    [IO.File]::WriteAllText($negativeProbe, $replyFixture.Replace($mutation[1], $mutation[2]), [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -cp $classes -d $negative $negativeProbe
    Check ($LASTEXITCODE -eq 0) ('Negative fixture compilation failed: ' + $mutation[0])
    & $java -Xmx128m -cp ($negative + [IO.Path]::PathSeparator + $classes) ReplyPreview150Probe --expect-failure
    Check ($LASTEXITCODE -eq 0) ('Negative fixture was not detected: ' + $mutation[0])
}
$petMutations = @(
    @('wrong-floor', 'overlayHeight + dp(pet.getFloorOffsetDp())', 'overlayHeight'),
    @('rounded-corner', 'railLeft = visibleLeft + radius, railRight = visibleRight - radius', 'railLeft = visibleLeft, railRight = visibleRight'),
    @('pet-reserves-space', 'lunagramPetOnRail = visible;', 'lunagramPetOnRail = visible; checkUi_chatListViewPaddings();')
)
foreach ($mutation in $petMutations) {
    Check ($petFixture.Contains($mutation[1])) ('Negative pet mutation target missing: ' + $mutation[0])
    $negative = Join-Path $run ('negative-pet-' + $mutation[0])
    New-Item -ItemType Directory -Path $negative | Out-Null
    $negativeProbe = Join-Path $negative 'PetMount150Probe.java'
    [IO.File]::WriteAllText($negativeProbe, $petFixture.Replace($mutation[1], $mutation[2]), [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -d $negative $negativeProbe
    Check ($LASTEXITCODE -eq 0) ('Negative pet fixture compilation failed: ' + $mutation[0])
    & $java -Xmx128m -cp $negative PetMount150Probe --expect-failure
    Check ($LASTEXITCODE -eq 0) ('Negative pet fixture was not detected: ' + $mutation[0])
}
$record = Method $composer 'protected void updateRecordInterface(int recordState, boolean animated)'
Check ($record.Contains('recordUiToken.begin()') -and $record.Contains('cancelRunningRecordUiAnimation();')) 'Restart must invalidate old animation before cancel.'
Check ($record -match '(?s)boolean shouldShowFastTransition = false;\s*if \(runningAnimationAudio != null\) \{(?:(?!cancelRunningRecordUiAnimation).)*?\}\s*cancelRunningRecordUiAnimation\(\);') 'Stop/preview must cancel delayed lock even after main entry animator already ended.'
Check ($record -match 'if \(shouldShowFastTransition \|\| recordState == RECORD_STATE_CANCEL_BY_TIME\) \{\s*hideFastRecordUiContent\(\);') 'Quick cancel must remove glass timer/slide before input fades back.'
Check ([regex]::Matches($record, 'public void onAnimationEnd').Count -eq [regex]::Matches($record, 'if \(destroyed \|\| !recordUiToken.isCurrent\(recordAnimationEpoch\)\) return;').Count) 'Every record completion must reject stale/destroyed owner.'
Check ((Method $composer 'public void onDestroy()').Contains('recordUiToken.invalidate();')) 'Destroy invalidates all callbacks.'
$lock = Method $composer 'private void startLockTransition()'
Check ($lock.Contains('cancelRecordLockUiAnimation();') -and $lock.Contains('recordLockAnimation = animatorSet;') -and $lock.Contains('if (recordLockAnimation != animatorSet) return;') -and $lock.Contains('if (!isRecordUiAnimationEnabled())')) 'Delayed lock transition must retain an owner, reject stale deadline and honour reduced motion.'
Check ($composer.Contains('circleSlideDelta = compactRecord ? 0 : slideDelta') -and $composer.Contains('if (compactRecord) radius = Math.min(radius, compactRadius);') -and $composer.Contains('if (!compactRecord && LiteMode.isEnabled(LiteMode.FLAGS_CHAT)')) 'Liquid recording circle must remain bounded without external waves.'
Check ($composer.Contains('audioVideoButtonContainer.getLocationInWindow(compactControlLocation)') -and $composer.Contains('getLocationInWindow(compactOverlayLocation)')) 'Recording circle follows actual measured composer location.'
Check ($composer.Contains('getMeasuredWidth() * audioVideoButtonContainer.getScaleX()') -and $composer.Contains('getMeasuredHeight() * audioVideoButtonContainer.getScaleY()') -and $record.Contains('audioVideoButtonContainer.setScaleX(1f);')) 'Transformed origins need scaled half-size; restarted hidden action must normalize scale.'
Check ($composer.Contains('sendRect.set(cx - dp(24), cy - dp(24), cx + dp(24), cy + dp(24))')) 'Compact glyph retains48dp touch/a11y bounds.'
# Scope these checks to the separate overlay owners: an implementation only in RecordCircle
# would leave ControlsView offset by its different250dp height and silently pass a whole-file grep.
$controls = Method $composer 'public class ControlsView extends FrameLayout'
$circle = Method $composer 'public class RecordCircle extends View'
$controlsDraw = Method $controls 'protected void onDraw(Canvas canvas)'
foreach ($owner in @($controls, $circle)) {
    Check ($owner.Contains('private final int[] compactControlLocation = new int[2];') -and $owner.Contains('private final int[] compactOverlayLocation = new int[2];')) 'Each recording overlay must own independent window-coordinate arrays.'
    Check ($owner.Contains('audioVideoButtonContainer.getLocationInWindow(compactControlLocation);') -and $owner.Contains('getLocationInWindow(compactOverlayLocation);')) 'Both recording overlays convert the real send control into their own window origin.'
    Check ($owner.Contains('audioVideoButtonContainer.getMeasuredWidth() * audioVideoButtonContainer.getScaleX(), compactOverlayLocation[0]') -and $owner.Contains('audioVideoButtonContainer.getMeasuredHeight() * audioVideoButtonContainer.getScaleY(), compactOverlayLocation[1]')) 'Both overlay conversions must use transformed send size on both axes.'
}
Check ($controlsDraw -match '(?s)final boolean compactControls = LiteMode.isEnabled\(LiteMode.FLAG_LIQUID_GLASS\)\s*&& sendButtonVisible && audioVideoButtonContainer != null\s*&& audioVideoButtonContainer.isAttachedToWindow\(\)') 'Measured compact stack applies only to locked and attached glass controls.'
Check ($controlsDraw.Contains('compactPauseTop = LumaComposerGeometry.recordAuxiliaryCenterY(sendCenterY,') -and $controlsDraw.Contains('dp(LumaComposerGeometry.recordDiameterDp(LiteMode.getLiquidGlassInputHeightDp())), dp(48), 0, 1) - dp(18)')) 'Pause center sits48dp from actual send center with distinct touch regions and compact visual gap.'
Check ($controlsDraw.Contains('lockY = lerp(lockY, compactPauseTop + dpf2(30) * (1f - sc), snapAnimationProgress);')) 'Gesture lock must interpolate to measured compact anchor instead of abruptly replacing stock drag geometry.'
Check ($controlsDraw.Contains('compactControls ? compactPauseTop : getMeasuredHeight() - dp(118)') -and $controlsDraw.Contains('final float onceOffset = dpf2(12);')) 'Compact upper circle uses stride48 while stock geometry and exit anchor remain unchanged.'
Check ($controlsDraw.Contains('if (compactControls && s > .9f && exitProgress2 == 0f)') -and $controlsDraw.Contains('pauseRect.inset(-dp(6), -dp(6));') -and $controlsDraw.Contains('if (compactControls) onceRect.inset(-dp(6), -dp(6));')) 'Compact pause/once visual36 circles must expose native48 hit regions.'
$timerDraw = Method (Method $composer 'public class TimerView extends View') 'protected void onDraw(Canvas canvas)'
Check ($timerDraw.Contains('float x = dp(LumaComposerGeometry.recordTimerInsetDp(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)));') -and $timerDraw.Contains('left = x + staticLayout.getLineWidth(0);')) 'Timer inset must move all timer text and its measured trailing edge together; stock stays0.'
foreach ($camera in @($cameraNew, $cameraLegacy)) {
    Check ((Method $camera 'protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)').Contains('updateCameraControlsCapsule(buttonsLayout);')) 'Both camera implementations must refresh capsule geometry on measure/settings changes.'
    Check ((Method $camera 'public void setButtonsBackground(').Contains('updateCameraControlsCapsule(buttonsLayout);')) 'Both camera implementations must refresh capsule geometry when glass background is installed.'
    Check ($camera.Contains('setScaleType(ImageView.ScaleType.CENTER)')) 'Camera symbols must remain centered within expanded native-touch slots.'
}
Check ($chat.Contains('if (chatActivityEnterView.isRecordingUiVisible())')) 'Left island must persist through recording/cancel visibility.'
Check ($record.Contains('if (messageEditText != null) messageEditText.setAlpha(0f);') -and $record.Contains('EMOJI_BUTTON_ALPHA.set(emojiButton, 0f)')) 'Ordinary glyphs must hide before recording content appears.'
Check ($record.Contains('recordTimer.setStartDelay(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0 : 200)') -and $record.Contains('? 220 : emojiButtonPaddingAlpha') -and $record.Contains('? 0 : 490')) 'Timer and circle leave before placeholder returns.'
$delete = Method $composer 'public void playDeleteAnimation()'
Check ($delete.Contains('alpha = 1f;') -and $delete.Contains('drawable.setProgress(0);') -and $delete.Contains('drawable.start();')) 'Trash animation restarts from visible frame0.'
$sendAs = Method $composer 'public void updateSendAsButton(boolean forceHide, boolean animated)'
Check ([regex]::Matches($sendAs, 'sendAsTextTranslation\(liquid,').Count -eq 9) 'All send-as enter/update/cancel/static text/emoji offsets must be glass-aware.'
Check ($composer.Contains('dp(LumaComposerGeometry.sendAsTextInsetDp(true, 0)) : dp(68) + width')) 'Send-as glass inset must match normal while stock retains avatar reserve.'
$replyRow = Method $chat 'private void updateLiquidGlassReplyPanelLayout(boolean enabled)'
Check (!$replyRow.Contains('leftMargin = dp(45)') -and $replyRow.Contains('replyParams.leftMargin = 0;')) 'Do not stack reply parent inset over inner content inset.'
Check ($chat.Contains('replyLayout.current().getTextInsetPx(false)') -and $chat.Contains('replyLayout.current().getTextInsetPx(true)') -and $reply.Contains('setLeftMargin(image, getTextInsetPx(false))')) 'Reply thumbnail/runtime/recycled layouts must share actual pixel content origin.'
Check ([regex]::Matches($reply, 'setClipChildren\(true\)').Count -eq 2 -and [regex]::Matches($reply, 'setMaxLines\(1\)').Count -eq 3 -and [regex]::Matches($reply, 'setScrollNonFitText\(false\)').Count -eq 3) 'Preview rows clip to their measured bounds and all text slots use one-line ellipsis, never scrolling under close.'
Check ((Method $reply 'private void setLeftMargin(View view, int leftMargin)').Contains('if (params.leftMargin != leftMargin)') -and (Method $reply 'private void setRightMargin(View view, int rightMargin)').Contains('if (params.rightMargin != rightMargin)')) 'Preview margin updates must not churn layout on each preDraw.'
Check ($chat.Contains('LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 80, Gravity.TOP | Gravity.LEFT)') -and $chat.Contains('lunagramPetView.setClickable(false)') -and $chat.Contains('lunagramPetView.setFocusable(false)')) 'Pet must mount full-width80 as a noninteractive overlay, never reserving conversation space.'
Check ((Method $chat 'public void onResume()').Contains('lunagramPetView.setPaused(false)') -and (Method $chat 'public void onPause()').Contains('lunagramPetView.setPaused(true)') -and !(Method $chat 'public void onPause()').Contains('lunagramPetView.setVisibility(View.GONE)')) 'Pet lifecycle pauses motion without a GONE/visible feedback trap.'
Check (!$chat.Contains('isLunagramPetVisible() ? dp(50) : 0') -and !$petMethods.ACTUAL_PET_ON_RAIL.Contains('checkUi_chatListViewPaddings();')) 'Pet visibility and placement must not reserve message-list space.'
Check (!$petMethods.ACTUAL_PET_RAIL.Contains('tmpRect') -and !$petMethods.ACTUAL_PET_RAIL.Contains('new RectF') -and !$petMethods.ACTUAL_PET_RAIL.Contains('setBounds(')) 'Pet geometry getter is allocation-free and does not expose or mutate drawable scratch state.'
if ($AndroidJar) {
    Check (Test-Path -LiteralPath $AndroidJar) 'Android36 jar missing.'
    # Compile actual animation cleanup against framework types; UI behavior remains modeled above.
    $native = 'import android.animation.*; public final class RecordAnimatorApi150 {' + (Method $composer $methods.ACTUAL_CLEAR_CALLBACKS) + '}'
    $nativeProbe = Join-Path $run 'RecordAnimatorApi150.java'
    [IO.File]::WriteAllText($nativeProbe, $native, [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $classes $nativeProbe
    Check ($LASTEXITCODE -eq 0) 'Actual AnimatorSet/ValueAnimator Android API compilation failed.'
    Write-Output 'PASS: actual animation cleanup compiled against Android36 framework.'
    $replyApi = 'import android.view.View; import android.widget.FrameLayout; import static android.view.View.VISIBLE; import org.telegram.ui.Components.LumaComposerGeometry; public final class ReplyPreviewApi150 {' +
        'private boolean liquidGlassMode; private int liquidGlassContentInsetPx; private View icon,name,obj,objHint,image; private static int dp(float v){return (int)Math.ceil(v);} ' +
        $replyMethods.ACTUAL_LAYOUT_MODE + $replyMethods.ACTUAL_GET_PX + $replyMethods.ACTUAL_LEFT_MARGIN + $replyMethods.ACTUAL_RIGHT_MARGIN + '}'
    $replyApiProbe = Join-Path $run 'ReplyPreviewApi150.java'
    [IO.File]::WriteAllText($replyApiProbe, $replyApi, [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -cp ($AndroidJar + [IO.Path]::PathSeparator + $classes) -d $classes $replyApiProbe
    Check ($LASTEXITCODE -eq 0) 'Actual preview margin/visibility methods Android API compilation failed.'
    Write-Output 'PASS: actual preview margin/visibility methods compiled against Android36 framework.'
    $petApi = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'composer-layout150/PetMountApi150.java.template'))
    foreach ($entry in $petMethods.GetEnumerator()) { $petApi = $petApi.Replace("/* $($entry.Key) */", $entry.Value) }
    $petApiProbe = Join-Path $run 'PetMountApi150.java'
    [IO.File]::WriteAllText($petApiProbe, $petApi, [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $classes $petApiProbe
    Check ($LASTEXITCODE -eq 0) 'Actual pet placement/window/RectF getter Android API compilation failed.'
    Write-Output 'PASS: actual pet placement/window/RectF getter compiled against Android36 framework.'
} else { Write-Output 'Android36 compilation skipped: supply -AndroidJar.' }
Write-Output 'PASS: recording visibility/cancel ordering/measured compact stack/native-touch camera/timer/send-as/reply source integration guards.'
Write-Output 'Scope: production pure geometry + extracted actual ownership/camera/preview/pet methods with negative controls; modeled animator/view ownership and window bounds, no APK/device visual claim.'
Write-Output "Test artifacts: $run"
