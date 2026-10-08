$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$java = 'TMessagesProj/src/main/java/org/telegram/'
function Source([string]$path) { [System.IO.File]::ReadAllText((Join-Path $repo $path)) }
function Check([bool]$condition, [string]$description) {
    if (!$condition) { throw $description }
    Write-Output "PASS: $description"
}
$controller = Source ($java + 'messenger/MessagesController.java')
$storage = Source ($java + 'messenger/MessagesStorage.java')
$chat = Source ($java + 'ui/ChatActivity.java')
$media = Source ($java + 'messenger/MediaController.java')
$cell = Source ($java + 'ui/Cells/ChatMessageCell.java')
$message = Source ($java + 'messenger/MessageObject.java')
$gifts = Source ($java + 'ui/Gifts/ProfileGiftsContainer.java')
$updates = Source ($java + 'ui/LumaUpdateActivity.java')

Check ($controller.Contains('markMessagesAsDeleted(dialogId, messages, true, forAll, 0, topicId, true)') -and $controller.Contains('movedToScheduledMessageId, null, true)')) 'Explicit deletion propagates its flag to storage and UI without using the scheduled-result slot'
Check ($storage.Contains('LumaDeletedMessages.shouldRetain(currentAccount, forceLocalRemoval, scheduled, quickReplies || welcomeMessages)') -and $storage.Contains('LumaDeletedMessages.forgetDeleted(currentAccount, did, mids)')) 'Storage bypasses retention and clears only explicitly removed tombstones'
Check ($chat.Contains('Boolean.TRUE.equals(args[7])') -and $chat.Contains('processDeletedMessages(markAsDeletedMessages, channelId, sent, !movedToScheduled, forceLocalRemoval)') -and $chat.Contains('LumaDeletedMessages.shouldRetain(currentAccount, forceLocalRemoval,')) 'Chat UI removes explicit deletions while retaining remote ordinary deletions'
Check ($storage.Contains('LumaDeletedMessages.snapshotDeleted(currentAccount)') -and [regex]::Matches($storage, '!LumaDeletedMessages\.isDeleted\(deletedBeforeBatch, did, mid\)').Count -eq 2 -and $chat.Contains('!obj.lumaRetainedDeleted && obj.messageOwner.reply_to != null')) 'Replayed and explicit removal of retained messages do not decrement unread/reply counts twice'
Check ($message.Contains('deleted = lumaRetainedDeleted = LumaDeletedMessages.isDeleted') -and $chat.Contains('obj.lumaRetainedDeleted = true') -and $chat.Contains('if (forceLocalRemoval) obj.lumaRetainedDeleted = false')) 'Retained state is restored on load and distinguished from disappearing messages'
Check ([regex]::Matches($cell, '!currentMessageObject\.lumaRetainedDeleted \|\| currentMessageObject\.deletedByThanos').Count -eq 3) 'Retained album captions and name/status layouts stay drawable'

foreach ($entry in @(
    'void openPhotoViewerForMessage\(ChatMessageCell cell, MessageObject message\) \{\s+if \(blockEphemeralMediaInGhostMode\(message\)\) return;',
    'boolean needPlayMessage\(ChatMessageCell cell, MessageObject messageObject, boolean muted\) \{\s+if \(blockEphemeralMediaInGhostMode\(messageObject\)\) return false;',
    'void didPressImage\(ChatMessageCell cell, float x, float y, boolean fullPreview\) \{\s+MessageObject message = cell.getMessageObject\(\);\s+if \(blockEphemeralMediaInGhostMode\(message\)\) return;',
    'void didStartVideoStream\(MessageObject message\) \{\s+if \(blockEphemeralMediaInGhostMode\(message\)\)'
)) { Check ($chat -match $entry) "Ephemeral-media opening entry has a ghost guard ($entry)" }
Check ($chat.Contains('if (blockEphemeralMediaInGhostMode(messageObject)) return null;') -and $media.Contains('LumaGhostMode.shouldBlockEphemeralMedia(messageObject.currentAccount,')) 'Read callback and playlist playback also respect ephemeral-media ghost policy'
Check ($gifts.Contains('visibilityProgress.setOnCancelListener') -and $gifts.Contains('operation.cancel();') -and $gifts.Contains('operation.bindRequest(ConnectionsManager.getInstance(currentAccount).sendRequest') -and [regex]::Matches($gifts, '!operation\.isActive\(\)').Count -ge 5 -and $gifts.Contains('if (visibilityOperation != operation) return;')) 'Gift visibility loading and mutation both stop after cancellation/stale ownership; old callbacks do not close new operations'
Check ($gifts -match '(?s)protected void onDetachedFromWindow\(\) \{\s+if \(visibilityOperation != null\).*?operation.cancel\(\);') 'Leaving the gifts profile cancels its active bulk operation'
Check ($updates.Contains('.accent().setEnabled(!controller.isChecking())') -and $updates.Contains('if (!showResult || isFinished || isPaused() || getContext() == null || getParentActivity() == null) return;') -and $updates.Contains('checkProgressDialog == progressDialog')) 'Updater disables duplicate taps and avoids result dialogs after cancel, pause or destruction'
Check ($updates.Contains('showDialog(progressDialog, dismissed -> {') -and $updates.Contains('checkProgressDialog = null;')) 'Updater progress dialog is owned and dismissed by the fragment lifecycle'
foreach ($locale in @('values', 'values-ru')) {
    $strings = [xml](Source "TMessagesProj/src/main/res/$locale/strings.xml")
    Check (@($strings.resources.string | Where-Object name -CEQ 'GhostEphemeralMediaBlocked').Count -eq 1) "Ephemeral media explanation exists ($locale)"
    Check ((@($strings.resources.string | Where-Object name -CEQ 'LumaUpdateAutomaticInfo')[0].'#text') -match '15') "Update frequency matches the 15-minute implementation ($locale)"
}
Write-Output 'Source integration checks passed; these do not replace Android UI/device testing.'
