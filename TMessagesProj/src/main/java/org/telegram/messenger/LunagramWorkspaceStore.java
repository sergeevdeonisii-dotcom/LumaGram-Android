package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;
import java.util.function.BooleanSupplier;

/** Explicit, encrypted per-chat workspace. No automatic capture, server mutations or plaintext fallback. */
public final class LunagramWorkspaceStore {
    public enum Failure { LOCKED, SUBSCRIPTION, STORAGE, CORRUPT, INVALID }
    public static final class WorkspaceException extends Exception {
        public final Failure failure;
        WorkspaceException(Failure failure) { super(failure.name()); this.failure = failure; }
    }
    public static final class Session {
        public final int account;
        public final long dialogId;
        private final long owner, epoch;
        private final boolean deniedContext;
        private final BooleanSupplier context;
        public Session(int account, long dialogId, boolean protectedContext, BooleanSupplier context) {
            this.account = account; this.dialogId = dialogId; this.context = context;
            deniedContext = protectedContext;
            boolean valid = account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
            owner = valid ? UserConfig.getInstance(account).getClientUserId() : 0;
            epoch = valid ? LunagramSubscriptionClient.accountEpoch(account) : -1;
        }
        public boolean isCurrent() {
            if (owner <= 0 || account != UserConfig.selectedAccount || dialogId == 0 || deniedContext
                    || owner != UserConfig.getInstance(account).getClientUserId()
                    || epoch != LunagramSubscriptionClient.accountEpoch(account)
                    || SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter
                    || DialogObject.isEncryptedDialog(dialogId) || DialogObject.isFolderDialogId(dialogId)
                    || BlackHoleVault.blocks(account, dialogId)) return false;
            if (dialogId < 0) {
                TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
                if (chat == null || chat.noforwards || chat.left || chat.kicked) return false;
            }
            try { return context != null && context.getAsBoolean(); }
            catch (RuntimeException ignored) { return false; }
        }
        public boolean canMutate() {
            return isCurrent() && LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.LOCAL_WORKSPACE) && isCurrent();
        }
        private String key() { return "lunagram_workspace_v1_" + dialogId; }
    }
    private static void require(Session session, boolean mutate) throws WorkspaceException {
        if (session == null || !session.isCurrent()) throw new WorkspaceException(Failure.LOCKED);
        if (!BlackHolePrivateData.isAvailable()) throw new WorkspaceException(Failure.STORAGE);
        if (mutate && !session.canMutate()) throw new WorkspaceException(Failure.SUBSCRIPTION);
    }
    public static synchronized LunagramWorkspaceModel.Document load(Session session) throws WorkspaceException {
        require(session, false);
        String raw;
        try { raw = BlackHolePrivateData.read(session.account, session.key(), null); }
        catch (Exception unavailable) { throw new WorkspaceException(Failure.STORAGE); }
        require(session, false);
        try { return LunagramWorkspaceModel.decode(raw, session.dialogId); }
        catch (Exception corrupt) { throw new WorkspaceException(Failure.CORRUPT); }
    }
    public static synchronized void put(Session session, LunagramWorkspaceModel.Entry entry) throws WorkspaceException {
        require(session, true);
        LunagramWorkspaceModel.Document previous = load(session); // Never overwrite unreadable/corrupt data.
        if (entry == null) throw new WorkspaceException(Failure.INVALID);
        LunagramWorkspaceModel.Document updated;
        try { updated = previous.put(entry); }
        catch (IllegalArgumentException invalid) { throw new WorkspaceException(Failure.INVALID); }
        persist(session, updated, true);
    }
    public static synchronized void delete(Session session, String id) throws WorkspaceException {
        require(session, false);
        LunagramWorkspaceModel.Document previous = load(session);
        if (id == null || previous.find(id) == null) throw new WorkspaceException(Failure.INVALID);
        persist(session, previous.remove(id), false);
    }
    public static synchronized String exportJson(Session session) throws WorkspaceException {
        LunagramWorkspaceModel.Document document = load(session);
        try {
            String result = LunagramWorkspaceModel.encode(document);
            require(session, false);
            return result;
        } catch (WorkspaceException e) { throw e; }
        catch (Exception invalid) { throw new WorkspaceException(Failure.INVALID); }
    }
    private static void persist(Session session, LunagramWorkspaceModel.Document document, boolean mutate) throws WorkspaceException {
        String encoded;
        try { encoded = LunagramWorkspaceModel.encode(document); }
        catch (Exception invalid) { throw new WorkspaceException(Failure.INVALID); }
        require(session, mutate);
        try { BlackHolePrivateData.write(session.account, session.owner, session.key(), encoded); }
        catch (Exception unavailable) { throw new WorkspaceException(Failure.STORAGE); }
        require(session, false);
    }
    private LunagramWorkspaceStore() {}
}
