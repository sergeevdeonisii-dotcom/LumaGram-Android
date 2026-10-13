package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Screen-owned, account-identity scoped history reader. It deliberately never sends read/view acknowledgements. */
public final class LunagramFeedController implements NotificationCenter.NotificationCenterDelegate {
    public interface Listener { void onFeedChanged(); }
    public interface Result { void done(boolean success); }
    public interface ReactionsResult { void done(TLRPC.ChatFull full); }
    private static final String SOURCES = "lunagram_feed_channels_v1";
    private static final int PAGE = 24, HISTORY_PAGE = 40, PARALLEL = 3;
    private final int account;
    private final long owner;
    private final AccessSession access;
    private final Listener listener;
    private final ArrayList<Integer> requests = new ArrayList<>();
    private final HashSet<String> reactionsInFlight = new HashSet<>();
    private final ArrayList<MessageObject> posts = new ArrayList<>();
    private final List<MessageObject> readOnlyPosts = Collections.unmodifiableList(posts);
    private LunagramFeedMerger<MessageObject> merger;
    private int generation, active, target;
    private boolean closed;
    private boolean newPosts;
    private boolean locked;
    private Set<Long> channels = new HashSet<>();

    public LunagramFeedController(int account, Listener listener) {
        this.account = account;
        this.owner = UserConfig.getInstance(account).getClientUserId();
        this.access = new AccessSession(account);
        this.listener = listener;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.didReceiveNewMessages);
        nc.addObserver(this, NotificationCenter.replaceMessagesObjects);
        nc.addObserver(this, NotificationCenter.messagesDeleted);
        nc.addObserver(this, NotificationCenter.didUpdateReactions);
        nc.addObserver(this, NotificationCenter.lunagramSubscriptionChanged);
    }

    /** Captured login identity, including logout/re-login of the same Telegram owner. */
    public static final class AccessSession {
        private final int account;
        private final long owner, epoch;

        public AccessSession(int account) {
            this.account = account;
            boolean valid = account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
            owner = valid ? UserConfig.getInstance(account).getClientUserId() : 0;
            epoch = valid ? LunagramSubscriptionClient.accountEpoch(account) : -1;
        }

        public boolean isCurrent() {
            return owner > 0 && account == UserConfig.selectedAccount
                && owner == UserConfig.getInstance(account).getClientUserId()
                && epoch == LunagramSubscriptionClient.accountEpoch(account);
        }

        public boolean isAvailable() {
            return isCurrent() && LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.CHANNEL_FEED);
        }
    }

    public static LinkedHashSet<Long> selected(int account) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        Set<String> saved = LumaAccountData.preferences(account).getStringSet(SOURCES, new HashSet<>());
        for (String s : saved) {
            try { long id = Long.parseLong(s); if (id > 0) result.add(id); } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    public static boolean saveSelection(AccessSession access, Collection<Long> channels) {
        if (access == null || !access.isAvailable()) return false;
        HashSet<String> values = new HashSet<>();
        for (long channel : channels) if (channel > 0) values.add(Long.toString(channel));
        LumaAccountData.preferences(access.account).edit().putStringSet(SOURCES, values).apply();
        return true;
    }

    public boolean isCurrent() { return !closed && access.isCurrent(); }
    public boolean isAvailable() { return !closed && access.isAvailable(); }
    public List<MessageObject> posts() { checkAccess(); return readOnlyPosts; }
    public boolean loading() { return checkAccess() && active > 0; }
    public boolean hasError() { return checkAccess() && merger != null && merger.hasError(); }
    public boolean atEnd() { return checkAccess() && merger != null && merger.isEnd(); }
    public boolean hasNewPosts() { return checkAccess() && newPosts; }
    public boolean checkAccess() {
        if (isAvailable()) return true;
        lock();
        return false;
    }

    private void lock() {
        // Only this screen's detached presentation is cleared. Preferences and Telegram storage survive.
        if (locked) return;
        locked = true;
        cancel();
        posts.clear();
        merger = null;
        newPosts = false;
        channels.clear();
        listener.onFeedChanged();
    }

    private boolean valid(int token) {
        return checkAccess() && token == generation && owner == UserConfig.getInstance(account).getClientUserId();
    }

    public void refresh() {
        cancel();
        if (!valid(generation)) return;
        locked = false;
        posts.clear();
        channels = selected(account);
        merger = new LunagramFeedMerger<>(channels);
        newPosts = false;
        target = PAGE;
        pump();
    }

    public void next() {
        if (!valid(generation) || merger == null || hasError() || target > posts.size() || atEnd()) return;
        target = posts.size() + PAGE;
        pump();
    }

    public void retry() {
        if (merger == null || !valid(generation)) return;
        merger.retry();
        if (target <= posts.size()) target = posts.size() + PAGE;
        pump();
    }

    private void pump() {
        if (!valid(generation) || merger == null) return;
        LunagramFeedMerger.Entry<MessageObject> next;
        while (posts.size() < target && (next = merger.poll()) != null) posts.add(next.value);
        if (posts.size() < target) {
            for (LunagramFeedMerger.Source<MessageObject> source : merger.sources()) {
                if (active >= PARALLEL) break;
                if (source.needsPage()) load(source, generation);
            }
        }
        listener.onFeedChanged();
    }

    private void load(LunagramFeedMerger.Source<MessageObject> source, int token) {
        source.loading = true;
        active++;
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Chat chat = mc.getChat(source.channel);
        if (chat != null) {
            requestHistory(source, token, chat);
        } else {
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                TLRPC.Chat cached = storage.getChat(source.channel);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!valid(token)) return;
                    if (cached != null) mc.putChat(cached, true);
                    requestHistory(source, token, cached);
                });
            });
        }
    }

    private void requestHistory(LunagramFeedMerger.Source<MessageObject> source, int token, TLRPC.Chat chat) {
        if (!valid(token)) return;
        if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || chat.left || chat.kicked) {
            source.failed = true;
            source.loading = false;
            active--;
            // Do not recurse through arbitrarily many unavailable saved sources.
            AndroidUtilities.runOnUIThread(this::pump);
            return;
        }
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(account).getInputPeer(-source.channel);
        req.offset_id = source.offset;
        req.limit = HISTORY_PAGE;
        int id = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!valid(token)) return;
            active--;
            source.loading = false;
            if (!(response instanceof TLRPC.messages_Messages) || error != null) {
                source.failed = true;
            } else {
                TLRPC.messages_Messages history = (TLRPC.messages_Messages) response;
                cachePeers(history);
                ArrayList<LunagramFeedMerger.Entry<MessageObject>> entries = new ArrayList<>();
                int offset = source.offset == 0 ? Integer.MAX_VALUE : source.offset;
                for (TLRPC.Message message : history.messages) {
                    if (message.id > 0) offset = Math.min(offset, message.id);
                    if (!(message instanceof TLRPC.TL_message) || message.id <= 0 || MessageObject.getDialogId(message) != -source.channel) continue;
                    // Rendering this detached object cannot mutate the dialog's read state.
                    MessageObject object = new MessageObject(account, message, true, true);
                    entries.add(new LunagramFeedMerger.Entry<>(source.channel, message.id, message.date, object));
                }
                merger.accept(source, entries, offset == Integer.MAX_VALUE ? 0 : offset, history.messages.isEmpty());
            }
            pump();
        }));
        requests.add(id);
    }

    private void cachePeers(TLRPC.messages_Messages result) {
        MessagesController mc = MessagesController.getInstance(account);
        mc.putUsers(result.users, false);
        mc.putChats(result.chats, false);
        MessagesStorage.getInstance(account).putUsersAndChats(result.users, result.chats, true, true);
    }

    public void loadReactions(long channel, ReactionsResult result) {
        if (!valid(generation)) return;
        int token = generation;
        TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        req.channel = MessagesController.getInstance(account).getInputChannel(channel);
        requests.add(ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!valid(token)) return;
            if (error == null && response instanceof TLRPC.TL_messages_chatFull) {
                TLRPC.TL_messages_chatFull full = (TLRPC.TL_messages_chatFull) response;
                MessagesController mc = MessagesController.getInstance(account);
                mc.putUsers(full.users, false);
                mc.putChats(full.chats, false);
                mc.putChatFull(full.full_chat);
                result.done(full.full_chat);
            } else result.done(null);
        })));
    }

    /** Same sendReaction protocol as the chat, but wait for server acceptance and expose errors. No paid reactions. */
    public void react(MessageObject post, TLRPC.Reaction reaction, Result result) {
        if (!valid(generation) || post == null || post.currentAccount != account || reaction == null || reaction instanceof TLRPC.TL_reactionPaid) return;
        String key = post.getDialogId() + ":" + post.getId();
        if (!reactionsInFlight.add(key)) return;
        int token = generation;
        TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
        req.peer = MessagesController.getInstance(account).getInputPeer(post.getDialogId());
        req.msg_id = post.getId();
        ReactionsLayoutInBubble.VisibleReaction chosen = ReactionsLayoutInBubble.VisibleReaction.fromTL(reaction);
        boolean removing = post.hasChosenReaction(chosen);
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> old = post.getChoosenReactions();
        int maximum = MessagesController.getInstance(account).getMaxUserReactionsCount();
        for (ReactionsLayoutInBubble.VisibleReaction r : old) {
            if (!r.equals(chosen) && !r.isStar) req.reaction.add(r.toTLReaction());
        }
        if (!removing) {
            while (!req.reaction.isEmpty() && req.reaction.size() >= Math.max(1, maximum)) req.reaction.remove(0);
            req.reaction.add(reaction);
        }
        if (!req.reaction.isEmpty()) req.flags |= 1;
        requests.add(ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!valid(token)) return;
            reactionsInFlight.remove(key);
            if (response instanceof TLRPC.Updates && error == null) {
                post.selectReaction(chosen, false, false);
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                listener.onFeedChanged();
                reloadPost(post, token);
                result.done(true);
            } else result.done(false);
        })));
    }

    private void reloadPost(MessageObject post, int token) {
        if (!valid(token)) return;
        TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
        req.channel = MessagesController.getInstance(account).getInputChannel(-post.getDialogId());
        req.id.add(post.getId());
        requests.add(ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!valid(token) || !(response instanceof TLRPC.messages_Messages)) return;
            TLRPC.messages_Messages messages = (TLRPC.messages_Messages) response;
            cachePeers(messages);
            for (TLRPC.Message message : messages.messages) {
                if (message.id == post.getId() && MessageObject.getDialogId(message) == post.getDialogId()) {
                    int index = posts.indexOf(post);
                    if (index >= 0) {
                        if (message instanceof TLRPC.TL_message) posts.set(index, new MessageObject(account, message, true, true));
                        else posts.remove(index);
                    }
                }
            }
            listener.onFeedChanged();
        })));
    }

    private void cancel() {
        generation++;
        for (int request : requests) ConnectionsManager.getInstance(account).cancelRequest(request, true);
        requests.clear();
        reactionsInFlight.clear();
        active = 0;
    }

    public void close() {
        closed = true;
        locked = true;
        cancel();
        posts.clear();
        merger = null;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        nc.removeObserver(this, NotificationCenter.replaceMessagesObjects);
        nc.removeObserver(this, NotificationCenter.messagesDeleted);
        nc.removeObserver(this, NotificationCenter.didUpdateReactions);
        nc.removeObserver(this, NotificationCenter.lunagramSubscriptionChanged);
    }

    @Override @SuppressWarnings("unchecked")
    public void didReceivedNotification(int event, int eventAccount, Object... args) {
        if (eventAccount != account || closed) return;
        if (event == NotificationCenter.lunagramSubscriptionChanged) {
            if (checkAccess() && locked) refresh();
            return;
        }
        if (!valid(generation) || merger == null) return;
        if (event == NotificationCenter.messagesDeleted) {
            long channel = (Long) args[1];
            if (!channels.contains(channel) || Boolean.TRUE.equals(args[2])) return;
            ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
            posts.removeIf(post -> post.getDialogId() == -channel && ids.contains(post.getId()));
            merger.remove(channel, ids);
            listener.onFeedChanged();
            return;
        }
        long dialog = (Long) args[0];
        if (!channels.contains(-dialog)) return;
        if (event == NotificationCenter.didReceiveNewMessages) {
            if (args.length > 2 && Boolean.TRUE.equals(args[2])) return;
            newPosts = true;
        } else if (event == NotificationCenter.replaceMessagesObjects) {
            for (MessageObject replacement : (ArrayList<MessageObject>) args[1]) {
                if (replacement.getDialogId() != dialog) continue;
                // Use a distinct layout object rather than stealing ChatActivity's cell state.
                MessageObject copy = new MessageObject(account, replacement.messageOwner, true, true);
                merger.replace(-dialog, copy.getId(), copy);
                for (int i = 0; i < posts.size(); i++) {
                    if (posts.get(i).getDialogId() == dialog && posts.get(i).getId() == copy.getId()) posts.set(i, copy);
                }
            }
        } else if (event == NotificationCenter.didUpdateReactions) {
            int message = (Integer) args[1];
            for (MessageObject post : posts) {
                if (post.getDialogId() == dialog && post.getId() == message) {
                    MessageObject.updateReactions(post.messageOwner, (TLRPC.TL_messageReactions) args[2]);
                    post.forceUpdate = true;
                    post.reactionsChanged = true;
                }
            }
        }
        listener.onFeedChanged();
    }
}
