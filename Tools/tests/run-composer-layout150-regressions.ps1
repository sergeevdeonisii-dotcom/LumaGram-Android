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
$production = @((Join-Path $sourceRoot 'Components/LumaComposerGeometry.java'), (Join-Path $sourceRoot 'Components/LumaRecordUiToken.java'))
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $production (Join-Path $PSScriptRoot 'composer-layout150/ComposerGeometry150Test.java') $probe
Check ($LASTEXITCODE -eq 0) 'Production geometry/ownership probe compilation failed.'
& $java -Xmx128m -cp $classes org.telegram.ui.Components.ComposerGeometry150Test
Check ($LASTEXITCODE -eq 0) 'Composer geometry failed.'
& $java -Xmx128m -cp $classes RecordUi150Probe
Check ($LASTEXITCODE -eq 0) 'Extracted animation-owner probe failed.'
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
Check ($chat.Contains('replyLayout.current().getTextInsetDp(false)') -and $chat.Contains('replyLayout.current().getTextInsetDp(true)') -and $reply.Contains('setLeftMargin(image, dp(getTextInsetDp(false)))')) 'Reply thumbnail/runtime/recycled layouts must share one semantic inset.'
if ($AndroidJar) {
    Check (Test-Path -LiteralPath $AndroidJar) 'Android36 jar missing.'
    # Compile actual animation cleanup against framework types; UI behavior remains modeled above.
    $native = 'import android.animation.*; public final class RecordAnimatorApi150 {' + (Method $composer $methods.ACTUAL_CLEAR_CALLBACKS) + '}'
    $nativeProbe = Join-Path $run 'RecordAnimatorApi150.java'
    [IO.File]::WriteAllText($nativeProbe, $native, [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $classes $nativeProbe
    Check ($LASTEXITCODE -eq 0) 'Actual AnimatorSet/ValueAnimator Android API compilation failed.'
    Write-Output 'PASS: actual animation cleanup compiled against Android36 framework.'
} else { Write-Output 'Android36 compilation skipped: supply -AndroidJar.' }
Write-Output 'PASS: recording visibility/cancel ordering/send-as/reply source integration guards.'
Write-Output 'Scope: production pure geometry + extracted actual ownership methods; modeled animator/view ownership, no APK/device visual claim.'
Write-Output "Test artifacts: $run"
