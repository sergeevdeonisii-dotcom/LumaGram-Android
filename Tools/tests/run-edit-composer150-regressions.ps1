param([string]$JavaHome = $env:JAVA_HOME, [string]$AndroidJar,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs/edit-composer150'))
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
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
    $open = $Source.IndexOf('{', $start); $depth = 1
    for ($end = $open + 1; $end -lt $Source.Length -and $depth -gt 0; $end++) {
        if ($Source[$end] -eq '{') { $depth++ }
        if ($Source[$end] -eq '}') { $depth-- }
    }
    Check ($depth -eq 0) ('Unbalanced method: ' + $Signature)
    return $Source.Substring($start, $end - $start)
}
$source = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java'))
$layout = Method $source 'private void updateEditingComposerLayout()'
$field = Method $source 'private void updateFieldRight(int attachVisible)'
$fixture = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'composer-layout150/EditComposer150Probe.java.template'))
$fixture = $fixture.Replace('/* ACTUAL_EDIT_LAYOUT */', $layout).Replace('/* ACTUAL_FIELD_RIGHT */', $field)
$probe = Join-Path $run 'EditComposer150Probe.java'
[IO.File]::WriteAllText($probe, $fixture, [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $probe
Check ($LASTEXITCODE -eq 0) 'Extracted edit composer methods failed to compile.'
& $java -Xmx128m -cp $classes EditComposer150Probe
Check ($LASTEXITCODE -eq 0) 'Extracted edit composer state/geometry checks failed.'
$edit = Method $source 'public void setEditingMessageObject('
Check (!$edit.Contains('rightMargin = dp(4)') -and [regex]::Matches($edit,'updateEditingComposerLayout\(\);').Count -eq 2) 'Enter and exit must both apply true editing geometry.'
$measureStart = $source.LastIndexOf('protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)')
$measure = Method $source.Substring($measureStart) 'protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)'
Check ($measure -match '(?s)updateEditingComposerLayout\(\);\s*if \(editingMessageObject != null\).*?else if \(botCommandsMenuButton') 'Editing must own measurement before bot/send-as/normal branches.'
Check ($measure -match '(?s)if \(i == 0 && editingMessageObject != null\).*?setTranslationY\(0\);.*?continue;') 'Bottom-anchored Ai must not animate down across Done on line count change.'
$show = Method $source 'private void showAiButton(boolean show_)'
Check ($show.Contains('updateEditingComposerLayout();') -and $show.Contains('if (!shownAiButton)') -and $show.Contains('editingMessageObject != null ? 1f : 0f')) 'Ai visibility, fade completion and hint anchor must follow current owner.'
Check ($source -match 'sendOutlineView\.setColorFilter\(getThemedColor\(Theme\.key_telegram_color\), PorterDuff\.Mode\.SRC_IN\);\s*updateEditingComposerLayout\(\);') 'Day/night color update must refresh editing bubble.'
if ($AndroidJar) {
    Check (Test-Path -LiteralPath $AndroidJar) 'Android jar missing.'
    $native = @'
import android.view.*; import android.widget.*; import android.graphics.*;
import android.graphics.drawable.Drawable; import java.util.HashMap;
public class EditComposerAndroidApi150 {
    static int dp(float n) { return (int)n; } static int DEFAULT_HEIGHT=44, VISIBLE=0,GONE=8;
    boolean editingComposerLayout,shownAiButton,shownRichButton; Object editingMessageObject;
    int editingAiBackgroundColor,editingAiPressedColor,editingAiIconColor;
    View emojiButton; EditText messageEditText; ImageView aiButton,richButton; FrameLayout messageEditTextContainer;
    static class Done extends View { Done(android.content.Context c) { super(c); } int width(){return 44;} } Done doneButton;
    HashMap<View,Float> animationParamsX=new HashMap<>();
    static class Hint { void hide(){} } Hint aiHint;
    int getThemedColor(int n) {return n;}
    static class Theme {
        static int key_chat_messagePanelBackground=1,key_listSelector=2,key_chat_messagePanelIcons=3,key_glass_defaultIcon=4,RIPPLE_MASK_CIRCLE_20DP=5;
        static Drawable createSimpleSelectorCircleDrawable(int s,int b,int p){return null;}
        static Drawable createSelectorDrawable(int p,int mask,int size){return null;}
    }
    /* ACTUAL_EDIT_LAYOUT */
}
'@
    $nativeFile = Join-Path $run 'EditComposerAndroidApi150.java'
    [IO.File]::WriteAllText($nativeFile, $native.Replace('/* ACTUAL_EDIT_LAYOUT */',$layout), [Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $classes $nativeFile
    Check ($LASTEXITCODE -eq 0) 'Editing layout method failed Android framework API compile.'
    Write-Output 'PASS: actual editing layout method compiled against Android framework.'
}
Write-Output 'PASS: editing transition/measurement/Ai visibility/theme integration guards.'
Write-Output 'Scope: extracted production methods with modeled state, plus optional Android API compilation; no APK or device visual claim.'
Write-Output "Test artifacts: $run"
