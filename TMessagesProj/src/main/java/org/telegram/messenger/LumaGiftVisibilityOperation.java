package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;

/** UI-thread state for a cancellable, sequential hide/show-all operation. */
public final class LumaGiftVisibilityOperation {
    private final int account;
    private final long ownerId;
    private boolean finished;
    private int requestId;

    public LumaGiftVisibilityOperation(int account) {
        this.account = account;
        ownerId = UserConfig.getInstance(account).getClientUserId();
    }

    public boolean isCurrentOwner() {
        return ownerId != 0 && ownerId == UserConfig.getInstance(account).getClientUserId();
    }

    public boolean isActive() {
        return !finished && isCurrentOwner();
    }

    public void bindRequest(int id) {
        if (isActive()) requestId = id;
        else if (id != 0) ConnectionsManager.getInstance(account).cancelRequest(id, true);
    }

    public void requestFinished() {
        requestId = 0;
    }

    public void complete() {
        finished = true;
        requestId = 0;
    }

    public void cancel() {
        if (finished) return;
        finished = true;
        if (requestId != 0) ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
        requestId = 0;
    }
}
