package org.telegram.messenger;

/** Pure predicates shared by the asynchronous media-browser privacy boundary. */
final class LumaMediaBrowserPolicy {
    private LumaMediaBrowserPolicy() { }

    static boolean validQueueIndex(long index, int size) {
        return index >= 0 && index < size;
    }

    static boolean sameOwner(int account, long owner, long generation,
            int selectedAccount, long liveOwner, long liveGeneration) {
        return owner > 0 && account == selectedAccount && owner == liveOwner && generation == liveGeneration;
    }

    static boolean canExpose(long owner, long dialogId, boolean encrypted,
            boolean vaultProtected, boolean passcodeLocked) {
        // Vault UI authentication deliberately does not relax an external-media boundary.
        return owner > 0 && dialogId != 0 && !encrypted && !vaultProtected && !passcodeLocked;
    }
}
