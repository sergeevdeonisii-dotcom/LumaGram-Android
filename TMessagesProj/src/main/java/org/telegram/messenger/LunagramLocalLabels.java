package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;
import org.telegram.utils.proxy.ProxySettings;
import java.util.Map;

/** Local aliases and proxy labels, never Telegram titles or global connection settings. */
public final class LunagramLocalLabels {
    private static final String KEY = "lunagram_local_labels_v1";
    private static final Snapshot[] CACHE = new Snapshot[UserConfig.MAX_ACCOUNT_COUNT];
    private static final class Snapshot {
        final long owner, epoch;
        final Map<String, LunagramLocalLabelsModel.Label> labels;
        Snapshot(Session session, LunagramLocalLabelsModel model) {
            owner = session.owner; epoch = session.epoch; labels = model.snapshot();
        }
        boolean matches(Session session) { return owner == session.owner && epoch == session.epoch; }
    }
    /** Also waits for in-flight reads/writes; never deletes the encrypted user data. */
    public static synchronized void clearAccountCache(int account) {
        if (account >= 0 && account < CACHE.length) CACHE[account] = null;
    }
    public static final class Session {
        public final int account;
        public final long dialogId;
        private final long owner, epoch;
        private final String key;
        private final ProxySettings proxy;
        private Session(int account, long dialog, ProxySettings proxy) {
            if(account<0 || account>=UserConfig.MAX_ACCOUNT_COUNT) throw new IllegalArgumentException("Account");
            this.account=account; dialogId=dialog; this.proxy=proxy;
            owner=UserConfig.getInstance(account).getClientUserId(); epoch=LunagramSubscriptionClient.accountEpoch(account);
            key=proxy==null ? LunagramLocalLabelsModel.aliasKey(dialog) : proxyKey(proxy);
        }
        public boolean current() {
            if(owner<=0 || account!=UserConfig.selectedAccount || owner!=UserConfig.getInstance(account).getClientUserId()
                    || epoch!=LunagramSubscriptionClient.accountEpoch(account)
                    || SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter) return false;
            if(proxy!=null) return proxy.isValid(); // Immutable identity, no global proxy reads or writes.
            if(DialogObject.isEncryptedDialog(dialogId) || DialogObject.isFolderDialogId(dialogId)
                    || BlackHoleVault.blocks(account,dialogId)) return false;
            TLRPC.Chat chat=MessagesController.getInstance(account).getChat(-dialogId);
            return chat!=null && !chat.left && !chat.kicked;
        }
        public boolean paid() { return current() && LunagramSubscriptionAccess.canUse(account,LunagramFeatureCatalog.Feature.LOCAL_WORKSPACE) && current(); }
        private void require(boolean mutate) {
            if(!(mutate ? paid() : current()) || !BlackHolePrivateData.isAvailable()) throw new IllegalStateException("Local labels unavailable");
        }
        public String realTitle() {
            if(proxy!=null || !current()) return "";
            TLRPC.Chat chat=MessagesController.getInstance(account).getChat(-dialogId); return chat==null ? "" : chat.title;
        }
    }
    public static Session forChat(int account,long dialog) { return new Session(account,dialog,null); }
    public static Session forProxy(int account,ProxySettings proxy) {
        if(proxy==null || !proxy.isValid()) throw new IllegalArgumentException("Proxy"); return new Session(account,0,proxy);
    }
    private static String proxyKey(ProxySettings proxy) {
        return LunagramLocalLabelsModel.proxyKey(proxy.getType().name(),proxy.getAddress(),Integer.toString(proxy.getPort()),
                proxy.getUser(),proxy.getPassword(),proxy.getSecret());
    }
    public static synchronized LunagramLocalLabelsModel.Label get(Session session) throws Exception {
        session.require(false);
        Snapshot snapshot = CACHE[session.account];
        if (snapshot == null || !snapshot.matches(session)) {
            CACHE[session.account] = null;
            LunagramLocalLabelsModel model=LunagramLocalLabelsModel.decode(BlackHolePrivateData.read(session.account,KEY,null));
            session.require(false);
            snapshot = new Snapshot(session, model);
            CACHE[session.account] = snapshot;
        }
        session.require(false);
        LunagramLocalLabelsModel.Label label = snapshot.labels.get(session.key);
        return label == null ? LunagramLocalLabelsModel.EMPTY : label;
    }
    public static synchronized void set(Session session,String name,String group) throws Exception {
        LunagramLocalLabelsModel.Label value=new LunagramLocalLabelsModel.Label(name,group);
        boolean mutation=!value.empty(); session.require(mutation);
        // A write always validates fresh encrypted storage, never a cached model.
        // Invalidate even if decoding, authorization or writing subsequently fails.
        CACHE[session.account] = null;
        // Unreadable/corrupt storage must never be replaced by an empty snapshot.
        LunagramLocalLabelsModel model=LunagramLocalLabelsModel.decode(BlackHolePrivateData.read(session.account,KEY,null));
        session.require(mutation); model.put(session.key,value);
        String encoded=model.encode(); session.require(mutation);
        BlackHolePrivateData.write(session.account,session.owner,KEY,encoded); session.require(false);
    }
    public static void clear(Session session) throws Exception { set(session,"",""); }
    /** Presentation only; failure falls back to the real identity. Does not authorize mutations. */
    public static String alias(int account,long dialog) {
        try{return get(forChat(account,dialog)).name;}catch(Exception unavailable){return "";}
    }
    public static LunagramLocalLabelsModel.Label proxyLabel(int account,ProxySettings proxy) {
        try{return get(forProxy(account,proxy));}catch(Exception unavailable){return LunagramLocalLabelsModel.EMPTY;}
    }
    private LunagramLocalLabels() {}
}
