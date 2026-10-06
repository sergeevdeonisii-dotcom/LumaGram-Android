package org.telegram.ui.Cells;

import android.view.View;
import org.telegram.messenger.MessageObject;

public class ChatActionCell extends View {
    private final MessageObject message;
    public ChatActionCell(MessageObject message) { this.message = message; }
    public MessageObject getMessageObject() { return message; }
}
