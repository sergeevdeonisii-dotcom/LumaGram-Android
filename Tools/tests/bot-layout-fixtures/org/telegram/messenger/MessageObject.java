package org.telegram.messenger;

public class MessageObject {
    private final int id;
    private final long groupId;
    private final boolean out;
    public final boolean isBotPendingDraft;

    public MessageObject(int id, long groupId, boolean pending, boolean out) {
        this.id = id;
        this.groupId = groupId;
        this.isBotPendingDraft = pending;
        this.out = out;
    }
    public int getId() { return id; }
    public long getGroupId() { return groupId; }
    public boolean isOutOwner() { return out; }
}
