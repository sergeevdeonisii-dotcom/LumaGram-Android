$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Source([string]$path) { [System.IO.File]::ReadAllText((Join-Path $repo $path)) }
function Check([bool]$ok, [string]$label) { if (!$ok) { throw $label }; Write-Output "PASS: $label" }
$base = 'TMessagesProj/src/main/java/org/telegram/'
$root = Source ($base + 'ui/BlackHoleGramSettingsActivity.java')
$tools = Source ($base + 'ui/BlackHoleToolsActivity.java')
$search = Source ($base + 'ui/BlackHoleSearchActivity.java')
$settings = Source ($base + 'messenger/BlackHoleSettings.java')
$vault = Source ($base + 'messenger/BlackHoleVault.java')
$unlock = Source ($base + 'ui/BlackHoleUnlockActivity.java')
$privateData = Source ($base + 'messenger/BlackHolePrivateData.java')
$journal = Source ($base + 'messenger/BlackHoleNotificationJournal.java')
$dialogs = Source ($base + 'ui/DialogsActivity.java')
$chat = Source ($base + 'ui/ChatActivity.java')
$launch = Source ($base + 'ui/LaunchActivity.java')
$notifications = Source ($base + 'messenger/NotificationsController.java')
$dialogSearch = Source ($base + 'ui/Adapters/DialogsSearchAdapter.java')
Check ($root.Contains('new BlackHoleSearchActivity()') -and $root.Contains('new BlackHoleToolsActivity(item.id - 100)')) 'All six tools have reachable settings entries'
foreach ($title in @('BHGProfiles', 'BHGNotes', 'BHGJournal', 'BHGTransfer', 'BHGVault')) {
    Check ($root.Contains("R.string.$title") -and $search.Contains("R.string.$title")) "Tool reachable from settings and search ($title)"
}
Check ($tools.Contains('ACTION_CREATE_DOCUMENT') -and $tools.Contains('ACTION_OPEN_DOCUMENT')) 'Transfer uses Android document picker without broad file permissions'
Check ($tools.Contains('BlackHoleSettings.MAX_FILE_BYTES') -and $settings.Contains('MAX_FILE_BYTES = 64 * 1024')) 'File imports are bounded to 64 KiB'
Check ($settings -match '(?s)validate\(changes\).*?v.putAll\(changes\); validate\(v\).*?LumaTextAnimation.setEnabled') 'Merged imports are validated before any write'
Check ($settings.Contains('default: throw new IllegalArgumentException') -and $tools.Contains('BHGImportConfirm')) 'Unknown preferences rejected and imports confirmed'
Check ($tools.Contains('BlackHoleSettings.profile') -and $tools.Contains('BlackHoleSettings.saveProfile')) 'Validated preset and custom profile snapshots are wired'
Check ($privateData.Contains('KeyStore.getInstance("AndroidKeyStore")') -and $privateData.Contains('BlackHoleSealedData.seal') -and $privateData.Contains('target.edit().putString(name, Base64.encodeToString(value')) 'Production notes/journal persist only authenticated ciphertext'
Check ($privateData.Contains('Build.VERSION.SDK_INT < 23') -and $privateData.Contains('throw new IllegalStateException("Keystore unavailable")')) 'Keystore failures never fall back to plaintext'
Check ($journal.Contains('MAX_ENTRIES = 200') -and $journal.Contains('30L * 24 * 60 * 60 * 1000') -and $journal.Contains('keys.add(e.key())')) 'Journal bounded retention and deduplication'
Check ($notifications -match '(?s)notificationManager.notify\(id, notification.build\(\)\);\s+if \(!story\) recordJournalForDialog') 'Journal records after notification posting, not polling'
Check ($vault.Contains('expectedEpoch == authenticationEpoch') -and $vault.Contains('expectedOwner == UserConfig.getInstance(account).getClientUserId()')) 'Vault authentication bound to identity and lifecycle epoch'
Check ($unlock.Contains('new PasscodeView(context)') -and $unlock.Contains('passcode.setDelegate') -and $unlock.Contains('!org.telegram.messenger.ApplicationLoader.mainInterfacePaused')) 'Native PIN/fingerprint authentication rejects background acceptance'
Check ($launch.Contains('BlackHoleVault.lockAll()') -and $launch.Contains('BlackHoleVault.blocks(vaultPlaying.currentAccount')) 'Backgrounding locks the vault and stops protected playback'
Check ($chat.Contains('BlackHoleVault.blocks(currentAccount, vaultDialog)') -and $chat.Contains('BlackHoleVault.blocks(currentAccount, dialog_id)')) 'Direct opening and cached resumed chats both enforce the lock'
Check ($chat.Contains('fragmentView.setVisibility(View.INVISIBLE)') -and $launch.Contains('FLAG_SECURE')) 'Private views and recent-task thumbnails masked'
Check ($dialogs.Contains('getDialogsArrayIncludingVault') -and $dialogs.Contains('if (!org.telegram.messenger.BlackHoleVault.contains(currentAccount, dialog.id)) visible.add(dialog)')) 'Lists hide vault chats without deleting server dialogs'
Check ($dialogSearch.Contains('pruneVaultResults()') -and $dialogSearch.Contains('visibleHints(currentAccount)') -and $dialogSearch.Contains('searchResultNames.remove(n)')) 'Search/hints filtering preserves matching name indexes'
Check ((Source ($base + 'ui/FilteredSearchView.java')).Contains('pruneVaultMessages()') -and (Source ($base + 'ui/Components/SearchViewPager.java')).Contains('selectedFiles.entrySet().removeIf')) 'Cached media and selected results pruned on return'
Check ((Source ($base + 'ui/Adapters/DialogsAdapter.java')).Contains('MessagesController.getInstance(currentAccount).sortDialogs(null)')) 'Pin reordering propagates from visible copies to original lists'
Check ($notifications.Contains('if (BlackHoleVault.contains(currentAccount, dialogId)) return') -and $tools.Contains('removeNotificationsForDialog(did)')) 'Hidden chats suppress notifications and cancel existing ones'
foreach ($receiver in @('WearReplyReceiver', 'AutoMessageHeardReceiver', 'NotificationCallbackReceiver')) {
    Check ((Source ($base + "messenger/$receiver.java")).Contains('BlackHoleVault.blocks')) "Notification actions enforce vault lock ($receiver)"
}
foreach ($locale in @('values', 'values-ru')) {
    $xml = [xml](Source "TMessagesProj/src/main/res/$locale/strings.xml")
    $names = @([regex]::Matches($root + $tools + $search, 'R.string.(BHG\w+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    foreach ($name in $names) { Check (@($xml.resources.string | Where-Object name -CEQ $name).Count -eq 1) "Tool label exists ($locale / $name)" }
}
Write-Output 'Tools .71 source integration checks passed. Not Android UI, biometric or device tests.'
