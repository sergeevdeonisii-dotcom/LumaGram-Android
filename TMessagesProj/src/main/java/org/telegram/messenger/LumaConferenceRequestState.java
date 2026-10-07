package org.telegram.messenger;

/** Identifies one pending conference lookup so an ended invite cannot reappear. */
public final class LumaConferenceRequestState {
    private long generation;
    private int account = -1;
    private int messageId;
    private long callId;

    public synchronized long begin(int account, long callId, int messageId) {
        this.account = account;
        this.callId = callId;
        this.messageId = messageId;
        return ++generation;
    }

    public synchronized boolean isCurrent(long token) {
        return account >= 0 && generation == token;
    }

    public synchronized void finish(long token) {
        if (isCurrent(token)) clear();
    }

    public synchronized long cancelMessage(int account, int messageId) {
        return this.account == account && this.messageId == messageId ? clear() : 0;
    }

    public synchronized long cancelCall(int account, long callId) {
        return this.account == account && this.callId == callId ? clear() : 0;
    }

    public synchronized long cancelAll() {
        return clear();
    }

    private long clear() {
        long previousCall = callId;
        account = -1;
        messageId = 0;
        callId = 0;
        ++generation;
        return previousCall;
    }
}
