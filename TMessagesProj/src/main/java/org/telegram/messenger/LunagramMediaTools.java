package org.telegram.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.PersistableBundle;
import android.os.SystemClock;
import androidx.core.content.FileProvider;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local media tools. No network, recorder changes, implicit playback or unbounded persistence. */
public final class LunagramMediaTools {
    private LunagramMediaTools() {}
    private static final String BOOKMARK_KEY = "lunagram_media_bookmarks_v1";
    private static final DispatchQueue WORKER = new DispatchQueue("lunagramMediaTools");
    private static final AtomicBoolean WORKER_BUSY = new AtomicBoolean();
    private static final long CLIP_LIFETIME = 60 * 60 * 1000L;

    public static boolean eligible(MessageObject message) {
        if (message == null || message.messageOwner == null || message.getId() <= 0
                || message.currentAccount < 0 || message.currentAccount >= UserConfig.MAX_ACCOUNT_COUNT
                || !LunagramMessageAccess.canUse(message.currentAccount, message)
                || message.getDialogId() == 0 || DialogObject.isEncryptedDialog(message.getDialogId())
                || message.isSecretMedia() || message.messageOwner.ttl > 0 || message.messageOwner.noforwards
                || message.isSending() || message.isEditing() || message.isSendError() || !message.canForwardMessage()
                || BlackHoleVault.blocks(message.currentAccount, message.getDialogId())
                || MessagesController.getInstance(message.currentAccount).isPeerNoForwards(message.getDialogId())) return false;
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        return media != null && media.ttl_seconds == 0
                && (media instanceof TLRPC.TL_messageMediaPhoto || media instanceof TLRPC.TL_messageMediaDocument);
    }

    public static boolean timedMedia(MessageObject message) {
        return eligible(message) && (message.isVoice() || message.isMusic() || message.isRoundVideo());
    }

    public static final class Session {
        public final MessageObject message;
        public final int account;
        public final long owner;
        private final long epoch, dialog, document, photo;
        private final int messageId;
        private final LunagramMediaAccess.Session access;
        public Session(MessageObject message) {
            if (!eligible(message)) throw new IllegalArgumentException("Unsupported media");
            this.message = message; account = message.currentAccount;
            owner = UserConfig.getInstance(account).getClientUserId();
            epoch = LunagramSubscriptionClient.accountEpoch(account);
            dialog = message.getDialogId(); messageId = message.getId();
            document = message.getDocument() == null ? 0 : message.getDocument().id;
            TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
            photo = media == null || media.photo == null ? 0 : media.photo.id;
            access = LunagramMediaAccess.forAccount(account);
        }
        public boolean current() {
            TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
            return UserConfig.selectedAccount == account && owner > 0 && access.isCurrent()
                    && message.currentAccount == account
                    && UserConfig.getInstance(account).getClientUserId() == owner
                    && LunagramSubscriptionClient.accountEpoch(account) == epoch && eligible(message)
                    && message.getDialogId() == dialog && message.getId() == messageId
                    && (message.getDocument() == null ? 0 : message.getDocument().id) == document
                    && (media == null || media.photo == null ? 0 : media.photo.id) == photo;
        }
        public boolean paid() { return current() && access.canUse(LunagramFeatureCatalog.Feature.MEDIA_TOOLS) && current(); }
        public void require(boolean paid) {
            if (!(paid ? paid() : current())) throw new IllegalStateException("Media access changed");
        }
        private LunagramMediaToolsPolicy.Bookmark ref(long position) {
            return new LunagramMediaToolsPolicy.Bookmark(account, owner, dialog, messageId, document, position);
        }
    }

    public static final class Job {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
        public boolean cancelled() { return cancelled.get(); }
        private void check(Session session) {
            if (cancelled()) throw new IllegalStateException("Cancelled");
            session.require(true);
        }
    }
    public interface Callback<T> { void done(T result, int error); }
    private interface Work<T> { T run(Job job) throws Exception; }
    private static <T> Job work(Session session, Work<T> work, Callback<T> callback) {
        Job job = new Job();
        if (!session.paid() || !WORKER_BUSY.compareAndSet(false, true)) {
            AndroidUtilities.runOnUIThread(() -> { if (!job.cancelled()) callback.done(null, R.string.LunagramMediaUnavailable); });
            return job;
        }
        WORKER.postRunnable(() -> {
            T result = null; int error = 0;
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); job.check(session); result = work.run(job); job.check(session); }
            catch (Exception failure) { error = R.string.LunagramMediaFileUnavailable; }
            finally { WORKER_BUSY.set(false); }
            final T value = result; final int failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (!job.cancelled()) callback.done(session.current() ? value : null,
                        session.paid() ? failure : R.string.LunagramMediaUnavailable);
            });
        });
        return job;
    }

    /** Only the FileLoader-resolved downloaded copy; never message.attachPath or a content URI. */
    private static File downloaded(Session session) throws Exception {
        session.require(true);
        File file = FileLoader.getInstance(session.account).getPathToMessage(session.message.messageOwner).getCanonicalFile();
        File[] roots = { ApplicationLoader.applicationContext.getCacheDir(),
                new File(ApplicationLoader.getFilesDirFixed(), "cache"),
                ApplicationLoader.applicationContext.getExternalFilesDir(null),
                ApplicationLoader.applicationContext.getExternalCacheDir() };
        if (!LunagramMediaToolsPolicy.ownedFile(file, roots) || file.length() <= 0
                || FileLoader.isFileBusyForLunagramTools(file)) throw new IllegalStateException("File unavailable");
        String original = session.message.messageOwner.attachPath;
        if (original != null && !original.isEmpty() && new File(original).getCanonicalFile().equals(file))
            throw new IllegalStateException("Original attachment");
        return file;
    }

    public static Job metadata(Session session, Callback<String> callback) {
        return work(session, job -> {
            File file = downloaded(session);
            String mime = session.message.getMimeType();
            StringBuilder details = new StringBuilder();
            details.append(LocaleController.formatString(R.string.LunagramMediaBytes, file.length()));
            if (mime != null && mime.length() <= 120) details.append("\n").append(mime);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            job.check(session);
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                details.append("\n").append(bounds.outWidth).append(" × ").append(bounds.outHeight);
                if (bounds.outMimeType != null && !bounds.outMimeType.equals(mime)) details.append("\n").append(bounds.outMimeType);
            } else if (mime != null && (mime.startsWith("audio/") || mime.startsWith("video/"))) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(file.getPath());
                    for (int i = 0; i < Math.min(extractor.getTrackCount(), 8); i++) {
                        job.check(session);
                        MediaFormat format = extractor.getTrackFormat(i);
                        String codec = format.getString(MediaFormat.KEY_MIME);
                        if (codec != null && codec.length() <= 100) details.append("\n").append(codec);
                        if (format.containsKey(MediaFormat.KEY_WIDTH) && format.containsKey(MediaFormat.KEY_HEIGHT))
                            details.append(" · ").append(format.getInteger(MediaFormat.KEY_WIDTH)).append(" × ").append(format.getInteger(MediaFormat.KEY_HEIGHT));
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) details.append(" · ").append(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)).append(" Hz");
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) details.append(" · ").append(LocaleController.formatString(R.string.LunagramMediaChannels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)));
                    }
                } catch (Exception unsupported) { job.check(session); }
                finally { extractor.release(); }
            }
            return details.toString();
        }, callback);
    }

    public static Job sha256(Session session, Callback<String> callback) {
        return work(session, job -> LunagramMediaToolsPolicy.sha256(downloaded(session), () -> job.check(session)), callback);
    }

    /** Explicitly confirmed by UI; queue serializes this with FileLoader starts/finishes. */
    public static Job removeDownloadedCopy(Session session, Callback<Boolean> callback) {
        Job job = new Job();
        FileLoader.getInstance(session.account).getFileLoaderQueue().postRunnable(() -> {
            boolean removed = false;
            try {
                job.check(session);
                // Sent files can be shared originals or upload inputs; never remove them here.
                if (session.message.isOut() || PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisibleOrAnimating())
                    throw new IllegalStateException("In use");
                File target = downloaded(session);
                MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                if (playing != null) {
                    File active = FileLoader.getInstance(playing.currentAccount).getPathToMessage(playing.messageOwner).getCanonicalFile();
                    if (target.equals(active)) throw new IllegalStateException("In use");
                }
                job.check(session);
                if (FileLoader.isFileBusyForLunagramTools(target)) throw new IllegalStateException("In use");
                removed = target.delete(); // One resolved, owned file only. No siblings/keys/deleteOnExit.
            } catch (Exception unavailable) { /* UI reports a non-sensitive recovery message. */ }
            final boolean ok = removed;
            AndroidUtilities.runOnUIThread(() -> {
                if (job.cancelled()) return;
                if (!session.current()) { callback.done(false, R.string.LunagramMediaUnavailable); return; }
                if (ok) { session.message.mediaExists = false; ImageLoader.getInstance().clearMemory(); }
                callback.done(ok, ok ? 0 : R.string.LunagramMediaDeleteUnavailable);
            });
        });
        return job;
    }

    public static Job copyPhoto(Session session, Callback<Boolean> callback) {
        if (session.message.type != MessageObject.TYPE_PHOTO) {
            callback.done(false, R.string.LunagramMediaFileUnavailable); return new Job();
        }
        final AtomicBoolean clipboardSet = new AtomicBoolean();
        return work(session, job -> {
            File source = downloaded(session);
            if (source.length() > LunagramMediaToolsPolicy.MAX_CLIPBOARD_BYTES) throw new IllegalArgumentException("Photo limit");
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) throw new IllegalArgumentException("Not image");
            String suffix;
            if ("image/jpeg".equals(bounds.outMimeType)) suffix = ".jpg";
            else if ("image/png".equals(bounds.outMimeType)) suffix = ".png";
            else if ("image/webp".equals(bounds.outMimeType)) suffix = ".webp";
            else throw new IllegalArgumentException("Unsupported image");
            // The existing FileProvider files/cache mapping is reused; no new broad root.
            File directory = new File(ApplicationLoader.getFilesDirFixed(), "cache/lunagram_media_clipboard");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cache unavailable");
            File[] old = directory.listFiles();
            if (old != null) {
                int retained = 0;
                for (File item : old) if (item.getName().startsWith("luma_clip_") && item.isFile()
                        && item.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
                        && System.currentTimeMillis() - item.lastModified() > CLIP_LIFETIME) item.delete(); else retained++;
                if (retained >= 24) throw new IllegalStateException("Clipboard cache full");
            }
            File copy = File.createTempFile("luma_clip_", suffix, directory);
            boolean completed = false;
            try {
                long total = 0;
                try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(copy)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = input.read(buffer)) >= 0) {
                        job.check(session); total += n;
                        if (total > LunagramMediaToolsPolicy.MAX_CLIPBOARD_BYTES) throw new IllegalArgumentException("Photo limit");
                        output.write(buffer, 0, n);
                    }
                }
                job.check(session);
                Uri uri = FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", copy);
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        job.check(session);
                        Context context = ApplicationLoader.applicationContext;
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData data = ClipData.newUri(context.getContentResolver(), LocaleController.getString(R.string.LunagramMediaPhoto), uri);
                        if (android.os.Build.VERSION.SDK_INT >= 24) {
                            PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                            data.getDescription().setExtras(extras);
                        }
                        clipboard.setPrimaryClip(data); // Android grants only this URI to the paste recipient.
                        clipboardSet.set(true);
                    } catch (Exception unavailable) { copy.delete(); }
                });
                AndroidUtilities.runOnUIThread(() -> expireClipboard(copy, uri), CLIP_LIFETIME);
                completed = true;
                return true;
            } finally { if (!completed) copy.delete(); }
        }, (result, error) -> callback.done(Boolean.TRUE.equals(result) && clipboardSet.get(),
                error != 0 ? error : clipboardSet.get() ? 0 : R.string.LunagramMediaFileUnavailable));
    }
    private static void expireClipboard(File copy, Uri uri) {
        try {
            Context context = ApplicationLoader.applicationContext;
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            File directory = new File(ApplicationLoader.getFilesDirFixed(), "cache/lunagram_media_clipboard").getCanonicalFile();
            if (copy.getCanonicalFile().getParentFile().equals(directory) && copy.getName().startsWith("luma_clip_")) copy.delete();
        } catch (Exception ignored) { /* Only our unique temporary copy may be removed. */ }
    }

    private static ArrayList<LunagramMediaToolsPolicy.Bookmark> allBookmarks(Session session) throws Exception {
        session.require(false);
        String saved = BlackHolePrivateData.read(session.account, BOOKMARK_KEY, "");
        if (saved.length() > 16384) throw new IllegalStateException("Bookmark data limit");
        ArrayList<LunagramMediaToolsPolicy.Bookmark> result = new ArrayList<>();
        for (String line : saved.split("\n")) {
            if (line.isEmpty()) continue;
            LunagramMediaToolsPolicy.Bookmark item = LunagramMediaToolsPolicy.Bookmark.decode(line);
            if (item == null || item.account != session.account || item.owner != session.owner
                    || result.size() >= LunagramMediaToolsPolicy.MAX_BOOKMARKS) throw new IllegalStateException("Invalid bookmark data");
            result.add(item);
        }
        session.require(false); return result;
    }
    public static synchronized ArrayList<Long> bookmarks(Session session) throws Exception {
        if (!timedMedia(session.message)) return new ArrayList<>();
        ArrayList<Long> result = new ArrayList<>();
        LunagramMediaToolsPolicy.Bookmark ref = session.ref(0);
        for (LunagramMediaToolsPolicy.Bookmark item : allBookmarks(session)) if (item.sameMedia(ref)) result.add(item.position);
        Collections.sort(result); return result;
    }
    private static void saveBookmarks(Session session, ArrayList<LunagramMediaToolsPolicy.Bookmark> items, boolean paid) throws Exception {
        session.require(paid);
        StringBuilder data = new StringBuilder();
        for (LunagramMediaToolsPolicy.Bookmark item : items) data.append(item.encode()).append('\n');
        BlackHolePrivateData.write(session.account, session.owner, BOOKMARK_KEY, data.toString());
    }
    public static synchronized void addBookmark(Session session) throws Exception {
        session.require(true);
        long position = position(session);
        if (position < 0 || !timedMedia(session.message)) throw new IllegalStateException("Start playback first");
        LunagramMediaToolsPolicy.Bookmark ref = session.ref(position / 1000 * 1000);
        ArrayList<LunagramMediaToolsPolicy.Bookmark> all = allBookmarks(session);
        for (LunagramMediaToolsPolicy.Bookmark item : all) if (item.sameMedia(ref) && item.position == ref.position) return;
        if (all.size() >= LunagramMediaToolsPolicy.MAX_BOOKMARKS) throw new IllegalStateException("Bookmark limit");
        all.add(ref); saveBookmarks(session, all, true);
    }
    public static synchronized void removeBookmark(Session session, long position) throws Exception {
        session.require(false);
        LunagramMediaToolsPolicy.Bookmark ref = session.ref(position);
        ArrayList<LunagramMediaToolsPolicy.Bookmark> all = allBookmarks(session);
        all.removeIf(item -> item.sameMedia(ref) && item.position == position);
        saveBookmarks(session, all, false);
    }
    public static boolean seekBookmark(Session session, long position) {
        // Access to the owner's saved data survives expiry; seeking never starts playback.
        if (!session.current() || position(session) < 0 || position < 0 || position > duration(session)) return false;
        return MediaController.getInstance().seekToProgressMs(session.message, position);
    }

    private static Session playbackSession;
    private static LunagramMediaToolsPolicy.Playback playback;
    private static Runnable timer;
    private static MessageObject effectMessage;
    private static final NotificationCenter.NotificationCenterDelegate ACCOUNT_CHANGED = (id, account, args) -> resetPlayback();
    private static boolean sameMessage(MessageObject a, MessageObject b) {
        return a != null && b != null && a.currentAccount == b.currentAccount && a.getDialogId() == b.getDialogId()
                && a.getId() == b.getId() && a.getDocument() != null && b.getDocument() != null && a.getDocument().id == b.getDocument().id;
    }
    public static long position(Session session) {
        if (!session.current() || !timedMedia(session.message)
                || !sameMessage(session.message, MediaController.getInstance().getPlayingMessageObject())) return -1;
        return MediaController.getInstance().getProgressMs(session.message);
    }
    public static long duration(Session session) {
        long seconds = session.message.audioPlayerDuration > 0 ? session.message.audioPlayerDuration : (long) session.message.getDuration();
        return Math.min(LunagramMediaToolsPolicy.MAX_DURATION_MS, Math.max(0, seconds * 1000));
    }
    private static void preparePlayback(Session session) {
        session.require(true);
        if (position(session) < 0) throw new IllegalStateException("Start playback first");
        if (playbackSession == null || !sameMessage(playbackSession.message, session.message) || !playbackSession.paid()) resetPlayback();
        if (playback == null) {
            playbackSession = session; playback = new LunagramMediaToolsPolicy.Playback();
            NotificationCenter.getGlobalInstance().addObserver(ACCOUNT_CHANGED, NotificationCenter.activeAccountChanged);
        }
    }
    public static void setLoop(Session session, long from, long to) {
        preparePlayback(session); playback.setLoop(from, to, duration(session));
    }
    public static void stopLoop(Session session) {
        if (session.current() && playbackSession != null && sameMessage(playbackSession.message, session.message)) playback.clearLoop();
    }
    public static boolean hasLoop(Session session) {
        return session.current() && playbackSession != null && sameMessage(playbackSession.message, session.message) && playback.hasLoop();
    }
    public static void setSleep(Session session, int minutes, boolean atEnd) {
        preparePlayback(session);
        if (timer != null) AndroidUtilities.cancelRunOnUIThread(timer);
        playback.setTimer(minutes, atEnd, SystemClock.elapsedRealtime());
        if (!atEnd) {
            Session captured = playbackSession;
            timer = () -> {
                if (playbackSession != captured) return;
                if (!captured.paid() || !sameMessage(captured.message, MediaController.getInstance().getPlayingMessageObject())) { resetPlayback(); return; }
                resetPlayback();
                effectMessage = captured.message;
                try { MediaController.getInstance().pauseMessage(captured.message, false); }
                finally { effectMessage = null; }
            };
            AndroidUtilities.runOnUIThread(timer, minutes * 60000L);
        }
    }
    public static void stopSleep(Session session) {
        if (session.current() && playbackSession != null && sameMessage(playbackSession.message, session.message)) {
            playback.clearTimer(); if (timer != null) AndroidUtilities.cancelRunOnUIThread(timer); timer = null;
        }
    }
    public static boolean hasSleep(Session session) {
        return session.current() && playbackSession != null && sameMessage(playbackSession.message, session.message) && playback.hasTimer();
    }
    public static void resetPlayback() {
        if (timer != null) AndroidUtilities.cancelRunOnUIThread(timer);
        NotificationCenter.getGlobalInstance().removeObserver(ACCOUNT_CHANGED, NotificationCenter.activeAccountChanged);
        timer = null; playbackSession = null; playback = null;
    }
    /** Called only by the existing player's progress/end callbacks on UI thread. */
    public static boolean onPlayback(MessageObject message, long position, long duration, boolean ended) {
        // ExoPlayer can synchronously emit STATE_ENDED again during pause/seek.
        if (sameMessage(effectMessage, message)) return true;
        Session session = playbackSession;
        if (session == null || playback == null) return false;
        if (!session.paid() || !sameMessage(session.message, message)
                || !sameMessage(message, MediaController.getInstance().getPlayingMessageObject())) { resetPlayback(); return false; }
        long action = playback.evaluate(position, duration, SystemClock.elapsedRealtime(), ended);
        if (action == LunagramMediaToolsPolicy.Playback.PAUSE) {
            resetPlayback(); effectMessage = message;
            try { MediaController.getInstance().pauseMessage(message, false); }
            finally { effectMessage = null; }
            return true;
        }
        if (action >= 0) {
            effectMessage = message;
            try { return MediaController.getInstance().seekToProgressMs(message, action); }
            finally { effectMessage = null; }
        }
        return false;
    }
}
