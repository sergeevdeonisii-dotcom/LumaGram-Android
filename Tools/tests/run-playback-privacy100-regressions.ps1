param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (-not (Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
if (-not $OutputRoot) { $OutputRoot = Join-Path ([IO.Path]::GetTempPath()) 'lunagram-playback-privacy' }
$run = Join-Path $OutputRoot ('playback-privacy100-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$base = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger'
$source = [IO.File]::ReadAllText((Join-Path $base 'MusicPlayerService.java'))
function Slice([string]$From, [string]$To) {
    $start=$source.IndexOf($From, [StringComparison]::Ordinal)
    $end=$source.IndexOf($To, $start, [StringComparison]::Ordinal)
    if ($start -lt 0 -or $end -le $start) { throw 'Production test extraction boundary is missing.' }
    return $source.Substring($start,$end-$start)
}
$decision = Slice '        // An artwork callback or account change' '        Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);'
$decision = $decision.Replace(') return;', ') return null;')
$lock = Slice '    private boolean isMetadataLocked()' '    private void refreshPrivacy(boolean forceRedact)'
$modern = (Slice '            MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()' '            bldr.setVisibility(')
$legacy = Slice '                RemoteControlClient.MetadataEditor metadataEditor = remoteControlClient.editMetadata(true);' '                AndroidUtilities.runOnUIThread(new Runnable()'
$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'playback-privacy100/PlaybackPrivacy100Probe.java.template'))
$template = $template.Replace('/* LOCK_METHOD */',$lock).Replace('/* DECISION_BODY */',$decision).Replace('/* MODERN_METADATA */',$modern).Replace('/* LEGACY_METADATA */',$legacy)
$fixture = Join-Path $run 'PlaybackPrivacy100Probe.java'
[IO.File]::WriteAllText($fixture,$template)
& $javac -J-Xmx96m -encoding UTF-8 -d $classes (Join-Path $base 'LumaPlaybackPrivacy.java') $fixture
if ($LASTEXITCODE -ne 0) { throw 'Actual playback metadata probe compilation failed.' }
& $java -Xmx64m -cp $classes org.telegram.messenger.PlaybackPrivacy100Probe
if ($LASTEXITCODE -ne 0) { throw 'Playback privacy regressions failed.' }
$script:checks=0
function Check([bool]$Value,[string]$Label) { if(-not $Value){throw $Label};$script:checks++ }
Check (-not $source.Contains('UserConfig.selectedAccount')) 'Playback metadata must use the message account, not selected UI account.'
Check ($source.Contains('BlackHoleVault.contains(messageObject.currentAccount, messageObject.getDialogId())') -and -not $source.Contains('BlackHoleVault.blocks(')) 'Vault authentication must not unhide external metadata.'
$art = Slice '        Bitmap albumArt = null;' '        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {'
Check ($art.Contains('if (!redact && messageObject.isMusic())') -and $art.Contains('else if (!redact && (messageObject.isVoice() || messageObject.isRoundVideo()))')) 'Music and sender artwork must both be inside non-redacted branches.'
Check ($source.Contains('AudioInfo audioInfo = redact ? null') -and $source.Contains('loadingFilePath = null;')) 'Private album and pending artwork must be cleared.'
$click = Slice '        Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);' '        Notification notification;'
Check ($click.Contains('if (!redact) intent.putExtra') -and $click.Contains('if (!redact && messageObject.isMusic())') -and $click.Contains('else if (!redact && (messageObject.isVoice() || messageObject.isRoundVideo()))')) 'Private notification clicks must not expose dialog/message/account links.'
Check ($click.Contains('PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT')) 'Notification activity must be explicit immutable and freshly replaced.'
Check ($source.Contains('if (runningInstance == existing) existing.refreshPrivacy(forceRedact);') -and $source.Contains('if (runningInstance == this) runningInstance = null;')) 'Queued privacy refresh must not address a destroyed/replaced service.'
Check ($source.Contains('generation != metadataGeneration') -and $source.Contains('getPlayingMessageObject() != messageObject')) 'Delayed legacy callbacks must not survive track/metadata replacement.'
Check ($source.Contains('notificationRedacted != redact') -and $source.Contains('remoteControlClient.editMetadata(true)')) 'Same-message privacy changes must clear legacy artwork/album.'
Check ($source.Contains('MusicPlayerService::refreshPrivacyIfRunning, 1')) 'Unlock refresh must run after PasscodeView delegate clears waiting state.'
Check ($source.Contains('addObserver(this, NotificationCenter.appDidLogout)') -and $source.Contains('removeObserver(this, NotificationCenter.appDidLogout)')) 'Logout observer must have paired lifecycle cleanup.'
Check ($source.Contains('addObserver(this, NotificationCenter.activeAccountChanged)') -and $source.Contains('removeObserver(this, NotificationCenter.activeAccountChanged)')) 'Account observer must have paired lifecycle cleanup.'
$session = [IO.File]::ReadAllText((Join-Path $base 'TelegramMediaSession.java'))
$passcode = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/PasscodeView.java'))
Check ($session.Contains('MusicPlayerService.refreshPrivacyIfRunning(forceRedact);') -and $passcode.Contains('TelegramMediaSession.refreshPrivacyIfCreated(true);')) 'Actual passcode lock must immediately redact an existing music service even before appLocked is set.'
Write-Output "PASS: $script:checks playback privacy integration guards. No device or full Android build was run."
Write-Output "Test artifacts: $run"
