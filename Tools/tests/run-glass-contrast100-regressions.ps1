param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-100-glass-tests'
)
$ErrorActionPreference = 'Stop'
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'A Java compiler is required.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram'
$run = Join-Path $OutputRoot ('run-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
function Check([bool]$Valid, [string]$Message) { if (!$Valid) { throw $Message } }
function Method([string]$Text, [string]$Signature) {
    $start = $Text.IndexOf($Signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw ('Missing production method: ' + $Signature) }
    $open = $Text.IndexOf('{', $start)
    $depth = 0
    for ($i = $open; $i -lt $Text.Length; $i++) {
        if ($Text[$i] -eq '{') { $depth++ }
        if ($Text[$i] -eq '}') { $depth--; if ($depth -eq 0) { return $Text.Substring($start, $i - $start + 1) } }
    }
    throw ('Unbalanced method: ' + $Signature)
}
$production = @(
    'messenger/LumaGlassContrast.java', 'ui/ChatActivity.java',
    'ui/Components/blur3/drawable/color/impl/LumaChatGlassColorProvider.java',
    'ui/Components/blur3/drawable/color/impl/BlurredBackgroundProviderImpl.java',
    'ui/Components/ChatAvatarContainer.java', 'ui/Components/ChatActivityEnterView.java',
    'ui/Business/BusinessBotButton.java', 'ui/Components/MessagePreviewView.java'
) | ForEach-Object { Join-Path $source $_ }
$hashes = @($production | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash })
$chat = [IO.File]::ReadAllText($production[1])
$delegate = $chat.Substring($chat.IndexOf('public class ThemeDelegate implements', [StringComparison]::Ordinal))
$dispatch = Method $chat 'private int applyChatGlassContrast(int key, int color)'
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'glass-contrast100/GlassContrast100Test.java.template'))
$test = Join-Path $run 'GlassContrast100Test.java'
[IO.File]::WriteAllText($test, $template.Replace('@DISPATCH@', $dispatch), [Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $production[0] $test
if ($LASTEXITCODE -ne 0) { throw 'Glass color policy/dispatch compilation failed.' }
& $java -Xmx128m -cp $classes org.telegram.messenger.GlassContrast100Test
if ($LASTEXITCODE -ne 0) { throw 'Glass contrast regression failed.' }

$inputProvider = [IO.File]::ReadAllText($production[2])
$providers = [IO.File]::ReadAllText($production[3])
Check ($inputProvider.Contains('LumaGlassContrast.panel(Theme.multAlpha(tintedColor, alpha), dark, true)')) 'Input glass must use the protected panel policy.'
foreach ($name in @('bottomPanelChatActivity', 'topPanelChatActivity', 'topPanelChatActivityTags')) {
    $provider = Method $providers ('public static BlurredBackgroundProvider ' + $name)
    Check ($provider.Contains('LumaGlassContrast.panel(') -and $provider.Contains('LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)')) ('Chat glass protection missing: ' + $name)
}
Check ((Method $delegate 'public int getColor(int key)').Contains('applyChatGlassContrast(key, getRawColor(key))')) 'Normal theme access must apply chat contrast.'
Check ((Method $delegate 'public int getCurrentColor(int key, boolean ignoreAnimation)').Contains('applyChatGlassContrast(key, getRawCurrentColor(key, ignoreAnimation))')) 'Animated theme access must apply chat contrast.'
Check ((Method $delegate 'public int getPreviewColor(int key)').Contains('getRawColor(key)')) 'Opaque preview must preserve the original custom-theme palette.'
$preview = [IO.File]::ReadAllText($production[7])
Check ($preview.Contains('getPreviewColor(Theme.key_actionBarDefaultTitle)') -and $preview.Contains('getPreviewColor(Theme.key_actionBarDefaultSubtitle)')) 'Preview labels must match their opaque (non-glass) header.'
$refresh = Method $chat 'private void refreshChatGlassTextColors()'
foreach ($token in @('avatarContainer.updateChatTextColors()', 'bizBotButton.updateColors()', 'pinnedMessageTextView[i].setTextColor', 'pinnedNameTextView[i].setTextColor', 'closePinned.setColorFilter', 'pinnedListButton.setColorFilter')) {
    Check ($refresh.Contains($token)) ('Cached chrome label refresh missing: ' + $token)
}
$modeChange = Method $chat 'private void updateLiquidGlassMode()'
Check ($modeChange.Contains('refreshChatGlassTextColors()') -and $modeChange.Contains('chatActivityEnterView.updateColors()') -and $modeChange.Contains('drawable.updateColors()')) 'Returning from glass settings must refresh surfaces and labels together.'
Check (($chat | Select-String -Pattern 'refreshChatGlassTextColors\(\);' -AllMatches).Matches.Count -ge 2) 'Theme change must also rebind cached chat chrome.'
$inputView = [IO.File]::ReadAllText($production[5])
Check ($inputView -match '(?s)public void updateColors\(\) \{\s*if \(messageEditText != null\).*?setHintColor.*?setHintTextColor.*?setCursorColor') 'Input hint and cursor must update immediately on a palette change.'
$avatar = Method ([IO.File]::ReadAllText($production[4])) 'public void updateChatTextColors()'
Check ($avatar.Contains('lastSubtitle != null') -and $avatar.Contains('overrideSubtitleColor != null') -and $avatar.Contains('animatedSubtitleTextView.setTextColor')) 'Header must preserve connection/explicit subtitle state and recolor both label types.'
$business = Method ([IO.File]::ReadAllText($production[6])) 'public void updateColors()'
Check ($business.Contains('key_chat_topPanelMessage') -and $business.Contains('key_featuredStickers_buttonText')) 'Business subtitle and its separate button label must use their respective theme colors.'
Check (!$dispatch.Contains('LumaBuildPolicy')) 'Personal and public builds must share the contrast fix.'
for ($i = 0; $i -lt $production.Length; $i++) {
    Check ((Get-FileHash -LiteralPath $production[$i] -Algorithm SHA256).Hash -eq $hashes[$i]) 'Production changed during tests; rerun.'
}
Write-Output 'Glass integration/source guards passed for both editions. This is not a device screenshot/UI test.'
Write-Output ('Test output: ' + $run)
