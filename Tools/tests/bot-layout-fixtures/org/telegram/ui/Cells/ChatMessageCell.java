package org.telegram.ui.Cells;

import android.view.View;
import org.telegram.messenger.MessageObject;

public class ChatMessageCell extends View {
    private final MessageObject message;
    public ChatMessageCell(MessageObject message) { this.message = message; }
    public MessageObject getMessageObject() { return message; }
}
