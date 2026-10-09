param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger'
$suffix = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (-not (Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
if (-not $OutputRoot) { $OutputRoot = Join-Path ([IO.Path]::GetTempPath()) 'lunagram-media-browser' }
$run = Join-Path $OutputRoot ('media-browser100-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @((Join-Path $base 'PackageValidator.java'), (Join-Path $base 'LumaMediaBrowserPolicy.java'))
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'media-browser100') -Filter '*.java' -File -Recurse | ForEach-Object { $_.FullName })
& $javac -J-Xmx96m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Media-browser validator tests failed to compile.' }
$integration = @('TelegramMediaSession.java', 'MusicBrowserService.java', 'BlackHoleVault.java') | ForEach-Object { Join-Path $base $_ }
$integration += Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/PasscodeView.java'
& $java -Xmx64m -cp $classes org.telegram.messenger.MediaBrowser100Test $integration
if ($LASTEXITCODE -ne 0) { throw 'Media-browser regressions failed.' }
$session = [IO.File]::ReadAllText((Join-Path $base 'TelegramMediaSession.java'))
$service = [IO.File]::ReadAllText((Join-Path $base 'MusicBrowserService.java'))
$vault = [IO.File]::ReadAllText((Join-Path $base 'BlackHoleVault.java'))
$script:checks = 0
function Check([bool]$ok, [string]$label) { if (-not $ok) { throw $label }; $script:checks++ }
Check ($session.Contains('PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT') -and -not $session.Contains('PendingIntent.FLAG_MUTABLE')) 'Session activity must be immutable.'
Check ($session.Contains('BlackHoleVault.contains(account, dialogId)') -and -not $session.Contains('BlackHoleVault.blocks(')) 'External metadata must not rely on vault UI unlock.'
Check ($session.Contains('SharedConfig.isWaitingForPasscodeEnter')) 'Pending passcode entry must gate metadata.'
Check ($session.Contains('browseGeneration++;') -and $session.Contains('currentOwner != UserConfig.getInstance(currentAccount).getClientUserId()')) 'Account reset must cover identity reuse and generation.'
Check ([regex]::Matches($session, 'if \(!isCurrentRequest\(account, owner, generation\)\)').Count -eq 2) 'Both database callbacks must validate their captured account owner and generation.'
Check ($session.Contains('isCurrentRequest(account, owner, generation) && !isPasscodeLocked()')) 'Final detached browse delivery must recheck passcode and identity.'
Check ($session.Contains('request.callback.onResult(Collections.emptyList());') -and $session.Contains('for (Runnable callback : oldMusicCallbacks) callback.run();')) 'Account changes must complete abandoned requests, not hang results.'
Check ($session.Contains('message.readAttachPath(data, owner);')) 'Asynchronous loads must not read attachment paths for a new slot owner.'
Check ($session.Contains('if (!canExposeDialog(currentAccount, dialogId)) continue;') -and $session.Contains('if (!canExposeDialog(currentAccount, did)) return mediaItems;')) 'Both root and child browse metadata must exclude protected dialogs.'
Check ($session.Contains('if (!canExposeDialog(messageObject.currentAccount, messageObject.getDialogId()))')) 'Direct playback metadata must enforce hidden-dialog privacy.'
Check ($session.Contains('session.setMetadata(null);') -and $session.Contains('session.setQueue(null);') -and $session.Contains('session.setQueueTitle(null);')) 'Old metadata and queue must be cleared together.'
Check ($vault.Contains('TelegramMediaSession.refreshPrivacyIfCreated();')) 'Protecting a dialog must invalidate published media metadata.'
Check ($session.Contains('if (existing != null) AndroidUtilities.runOnUIThread(() -> {') -and $session.Contains('if (forceRedact) existing.clearPublishedMetadata();')) 'Privacy refresh must use UI thread and fail closed even before appLocked is set.'
Check ($session -match 'else existing.refreshPrivacy\(\);\s*\}\);\s*MusicPlayerService.refreshPrivacyIfRunning\(forceRedact\);') 'Both existing media sessions must receive lock/protection refresh even without a browser connection.'
$passcode = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/PasscodeView.java'))
Check ($passcode -match 'public void onShow\(boolean fingerprint, boolean animated, int x, int y, Runnable onShow, Runnable onStart\)\s*\{\s*org.telegram.messenger.TelegramMediaSession.refreshPrivacyIfCreated\(true\);') 'Showing the real app passcode must clear retained external metadata before any early return.'
Check ([regex]::Matches($session, 'if \(!canControlPlaylist\(\)\) return;').Count -eq 2 -and $session.Contains('for (MessageObject target : MediaController.getInstance().getPlaylist())')) 'Both next/previous controls must validate all possible shuffle/offline targets.'
Check ($session -match 'if \(!LumaMediaBrowserPolicy.validQueueIndex\(queueId, playlist.size\(\)\)\) return;\s*MessageObject target = playlist.get\(\(int\) queueId\);\s*if \(target == null \|\| !canExposeDialog\(target.currentAccount, target.getDialogId\(\)\)\) return;') 'Queue requests must validate bounds and target privacy before playback.'
Check ($service.Contains('if (!PackageValidator.isKnownCaller(this, clientPackageName, clientUid))') -and -not $service.Contains('boolean isSelf')) 'Browser root must use the full ownership/certificate gate.'
Check ($service.Contains('result.sendResult(Collections.emptyList());')) 'Passcode rejection must complete the browser result.'
Write-Output "PASS: $script:checks MediaBrowser integration guards. Not an on-device Android Auto or penetration test."
Write-Output "Test artifacts: $run"
