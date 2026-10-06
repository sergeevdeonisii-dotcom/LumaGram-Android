package org.telegram.ui.Components.chat;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;

public class ChatActivityDraftMessageMeasureController {
    private RecyclerView recyclerView;
    private int messageIdToOverride;
    private long groupIdToOverride;
    private boolean hasAdditionalHeight;

    public int getOverrideMeasureHeight(MessageObject messageObject, int measuredHeight) {
        // BlackHoleGram keeps live drafts AND pending outgoing messages compact.
        // Filling the viewport here leaves a large gap below "Thinking" and
        // makes an outgoing row jump up, then collapse on server acknowledgement.
        hasAdditionalHeight = false;
        if (!filter(messageObject)) {
            return measuredHeight;
        }

        if (!messageObject.isBotPendingDraft && !(messageObject.getId() < 0 && messageObject.isOutOwner())) {
            setMessageIdToOverride(0, 0);
        }
        return measuredHeight;
    }

    public void setRecyclerView(RecyclerView recyclerView) {
        this.recyclerView = recyclerView;
    }

    public boolean hasAdditionalHeight() {
        return hasAdditionalHeight;
    }

    public boolean onMessageIdChanged(int oldMessageId, int newMessageId, long groupId) {
        if (messageIdToOverride == oldMessageId) {
            return setMessageIdToOverride(newMessageId, groupId);
        }
        return false;
    }

    public boolean setMessageIdToOverride(int messageIdToOverride, long groupIdToOverride) {
        if (this.messageIdToOverride != messageIdToOverride || this.groupIdToOverride != groupIdToOverride) {
            this.messageIdToOverride = messageIdToOverride;
            this.groupIdToOverride = groupIdToOverride;
            if (messageIdToOverride == 0) {
                hasAdditionalHeight = false;
            }
            return true;
        }
        return false;
    }

    public void onScroll() {
        if (messageIdToOverride <= 0 || recyclerView == null) {
            return;
        }

        boolean found = false;
        for (int a = 0, N = recyclerView.getChildCount(); a < N; a++) {
            if (filter(recyclerView.getChildAt(a))) {
                found = true;
                break;
            }
        }
        if (!found) {
            setMessageIdToOverride(0, 0);
        }
    }

    public void onRequestLayout() {
        if (messageIdToOverride == 0 || recyclerView == null) {
            return;
        }

        for (int a = 0, N = recyclerView.getChildCount(); a < N; a++) {
            recyclerView.getChildAt(a).forceLayout();
        }
    }


    public boolean filter(View view) {
        if (view instanceof ChatMessageCell) {
            return filter(((ChatMessageCell) view).getMessageObject());
        } else if (view instanceof ChatActionCell) {
            return filter(((ChatActionCell) view).getMessageObject());
        }
        return false;
    }

    public boolean filter(MessageObject messageObject) {
        return messageIdToOverride != 0 && messageObject != null && (messageObject.getId() == messageIdToOverride || groupIdToOverride != 0 && messageObject.getGroupId() == groupIdToOverride);
    }
}
