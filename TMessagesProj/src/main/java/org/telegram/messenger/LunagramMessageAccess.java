package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

/** Conservative boundary for new local tools; never bypass Telegram's copy/content restrictions. */
public final class LunagramMessageAccess {
    private LunagramMessageAccess() {}
    public static boolean canUse(int account, MessageObject message) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || message == null || message.currentAccount != account || message.messageOwner == null
                || account != UserConfig.selectedAccount || UserConfig.getInstance(account).getClientUserId() <= 0
                || SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter
                || DialogObject.isEncryptedDialog(message.getDialogId()) || message.getDialogId() == UserObject.VERIFY
                || message.isEphemeral() || message.isSecret() || !message.canForwardMessage() || message.isSponsored() || message.needDrawBluredPreview()
                || message.messageOwner.noforwards || message.messageOwner.ttl > 0 || message.messageOwner.ttl_period > 0
                || message.messageOwner.media != null && message.messageOwner.media.ttl_seconds > 0
                || message.type == MessageObject.TYPE_PAID_MEDIA || message.type == MessageObject.TYPE_STORY
                || message.messageOwner.rich_message != null || BlackHoleVault.blocks(account, message.getDialogId())) return false;
        TLRPC.Chat chat = message.getDialogId() < 0 ? MessagesController.getInstance(account).getChat(-message.getDialogId()) : null;
        // A missing channel record cannot prove that copying is allowed.
        if (message.getDialogId() < 0 && (chat == null || chat.noforwards)) return false;
        return message.messageOwner.action == null || message.messageOwner.action instanceof TLRPC.TL_messageActionEmpty;
    }
}
