/*
 * LumaGram chat archive exporter.
 *
 * The exporter deliberately lives outside ChatActivity. It talks to Telegram through
 * the same history/file APIs as the regular client and produces a portable ZIP with
 * messages.html, result.json and, optionally, a media directory.
 */
package org.telegram.messenger;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One-shot exporter. Create a fresh instance for every archive.
 *
 * Network history is requested in small pages and immediately written to page files.
 * This is important: even a very large channel does not keep its complete history in
 * Java memory. At the end, page files are joined in chronological order.
 */
public final class LumaChatExportManager implements NotificationCenter.NotificationCenterDelegate {

    public static final int HISTORY_DIALOG = 0;
    public static final int HISTORY_REPLIES = 1;
    public static final int HISTORY_SAVED = 2;

    public static final int OUTPUT_ARCHIVE = 0;
    public static final int OUTPUT_PDF = 1;

    public static final int STAGE_HISTORY = 0;
    public static final int STAGE_MEDIA = 1;
    public static final int STAGE_ARCHIVE = 2;

    private static final int PAGE_SIZE = 100;
    private static final long DOWNLOAD_TIMEOUT_MINUTES = 10;

    public static final class Options {
        public int account;
        public long dialogId;
        public long mergeDialogId;
        public long threadId;
        public long savedPeerId;
        public long savedParentDialogId;
        public int historyMode = HISTORY_DIALOG;
        public String title;
        public int outputFormat = OUTPUT_ARCHIVE;
        public boolean includeMedia;
        public boolean includePhotos = true;
        public boolean includeVideos = true;
        public boolean includeFiles = true;
        public long maxMediaBytes = 50L * 1024L * 1024L;
        public boolean secretChat;
        public boolean protectedContent;

        public Options(int account, long dialogId, String title) {
            this.account = account;
            this.dialogId = dialogId;
            this.title = title;
        }
    }

    public static final class Progress {
        public final int stage;
        public final int messages;
        public final int expectedMessages;
        public final int mediaFiles;
        public final int skippedMediaFiles;

        private Progress(int stage, int messages, int expectedMessages, int mediaFiles, int skippedMediaFiles) {
            this.stage = stage;
            this.messages = messages;
            this.expectedMessages = expectedMessages;
            this.mediaFiles = mediaFiles;
            this.skippedMediaFiles = skippedMediaFiles;
        }

        public int getPercent() {
            if (stage == STAGE_ARCHIVE) {
                return 96;
            }
            if (expectedMessages <= 0) {
                return 0;
            }
            return Math.max(0, Math.min(94, Math.round(messages * 94f / expectedMessages)));
        }
    }

    public interface Listener {
        void onProgress(@NonNull Progress progress);
        void onComplete(@NonNull File archive, @NonNull Progress finalProgress);
        void onError(@NonNull String message, @Nullable Throwable error);
        void onCancelled();
    }

    private static final class MediaAttachment {
        final TLRPC.Document document;
        final TLRPC.Photo photo;
        final TLRPC.PhotoSize photoSize;
        final String originalName;
        final String mimeType;
        final long declaredSize;

        private MediaAttachment(TLRPC.Document document, TLRPC.Photo photo, TLRPC.PhotoSize photoSize,
                                String originalName, String mimeType, long declaredSize) {
            this.document = document;
            this.photo = photo;
            this.photoSize = photoSize;
            this.originalName = originalName;
            this.mimeType = mimeType;
            this.declaredSize = declaredSize;
        }
    }

    private final Options options;
    private final Listener listener;
    private final AccountInstance account;
    private final ConnectionsManager connectionsManager;
    private final MessagesController messagesController;
    private final FileLoader fileLoader;
    private final int requestGuid = ConnectionsManager.generateClassGuid();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean terminalCallbackSent = new AtomicBoolean(false);
    private final ArrayList<File> pageFiles = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LumaChatExport");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private File sessionDir;
    private File pagesDir;
    private File mediaDir;
    private File outputDir;
    private int sourceIndex;
    private int pageIndex;
    private int offsetId;
    private int exportedMessages;
    private int expectedMessages;
    private int copiedMedia;
    private int skippedMedia;
    private long copiedMediaBytes;
    private long activeSourceDialogId;

    private volatile String waitingFileName;
    private volatile File downloadedFile;
    private volatile CountDownLatch waitingFileLatch;
    private volatile TLRPC.Document activeDocument;
    private volatile TLRPC.PhotoSize activePhotoSize;
    private volatile File activeArchive;

    public LumaChatExportManager(@NonNull Options options, @NonNull Listener listener) {
        this.options = options;
        this.listener = listener;
        account = AccountInstance.getInstance(options.account);
        connectionsManager = account.getConnectionsManager();
        messagesController = account.getMessagesController();
        fileLoader = account.getFileLoader();
    }

    public void start() {
        if (options.secretChat || DialogObject.isEncryptedDialog(options.dialogId)) {
            fail(localized("Секретные чаты пока нельзя экспортировать.", "Secret chats cannot be exported yet."), null);
            return;
        }
        if (options.protectedContent) {
            fail(localized("Экспорт недоступен: в чате включена защита контента.", "Export is unavailable because this chat protects its content."), null);
            return;
        }
        if (options.dialogId == 0 || options.account < 0 || options.account >= UserConfig.MAX_ACCOUNT_COUNT) {
            fail(localized("Не удалось определить чат для экспорта.", "Could not determine the chat to export."), null);
            return;
        }
        try {
            createSessionDirectories();
        } catch (Throwable e) {
            fail(localized("Не удалось подготовить папку экспорта.", "Could not prepare the export folder."), e);
            return;
        }
        if (options.includeMedia) {
            account.getNotificationCenter().addObserver(this, NotificationCenter.fileLoaded);
            account.getNotificationCenter().addObserver(this, NotificationCenter.fileLoadFailed);
        }
        activeSourceDialogId = options.dialogId;
        notifyProgress(STAGE_HISTORY);
        requestNextPage();
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        connectionsManager.cancelRequestsForGuid(requestGuid);
        CountDownLatch latch = waitingFileLatch;
        if (latch != null) {
            latch.countDown();
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (activeDocument != null) {
                fileLoader.cancelLoadFile(activeDocument);
            } else if (activePhotoSize != null) {
                fileLoader.cancelLoadFile(activePhotoSize);
            }
        });
        finishCancelled();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    private void createSessionDirectories() throws Exception {
        File externalCache = ApplicationLoader.applicationContext.getExternalCacheDir();
        if (externalCache == null) {
            externalCache = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
        }
        if (externalCache == null) {
            throw new IllegalStateException("No cache directory");
        }
        outputDir = new File(externalCache, "luma_chat_exports");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalStateException("Could not create output directory");
        }
        sessionDir = new File(outputDir, ".session_" + System.currentTimeMillis() + "_" + Math.abs(options.dialogId));
        pagesDir = new File(sessionDir, "pages");
        mediaDir = new File(sessionDir, "media");
        if (!pagesDir.mkdirs() || !mediaDir.mkdirs()) {
            throw new IllegalStateException("Could not create session directory");
        }
    }

    private void requestNextPage() {
        if (cancelled.get()) {
            return;
        }
        final TLObject request;
        final int currentSource = sourceIndex;
        if (currentSource > 0) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = messagesController.getInputPeer(activeSourceDialogId);
            req.offset_id = offsetId;
            req.limit = PAGE_SIZE;
            request = req;
        } else if (options.historyMode == HISTORY_REPLIES) {
            TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
            req.peer = messagesController.getInputPeer(options.dialogId);
            req.msg_id = (int) options.threadId;
            req.offset_id = offsetId;
            req.limit = PAGE_SIZE;
            request = req;
        } else if (options.historyMode == HISTORY_SAVED) {
            TLRPC.TL_messages_getSavedHistory req = new TLRPC.TL_messages_getSavedHistory();
            req.peer = messagesController.getInputPeer(options.savedPeerId);
            if (options.savedParentDialogId != 0) {
                req.parent_peer = messagesController.getInputPeer(options.savedParentDialogId);
            }
            req.offset_id = offsetId;
            req.limit = PAGE_SIZE;
            request = req;
        } else {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = messagesController.getInputPeer(options.dialogId);
            req.offset_id = offsetId;
            req.limit = PAGE_SIZE;
            request = req;
        }
        if (!hasValidPeer(request)) {
            fail(localized("Не удалось получить доступ к истории чата.", "Could not access this chat's history."), null);
            return;
        }
        int requestId = connectionsManager.sendRequest(request, (response, error) -> {
            if (cancelled.get()) {
                return;
            }
            if (error != null) {
                String detail = TextUtils.isEmpty(error.text) ? "" : " (" + error.text + ")";
                fail(localized("Ошибка загрузки истории чата", "Could not load chat history") + detail, null);
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                fail(localized("Сервер вернул неизвестный ответ.", "The server returned an unexpected response."), null);
                return;
            }
            TLRPC.messages_Messages page = (TLRPC.messages_Messages) response;
            worker.execute(() -> processPage(page));
        });
        connectionsManager.bindRequestToGuid(requestId, requestGuid);
    }

    private boolean hasValidPeer(TLObject request) {
        if (request instanceof TLRPC.TL_messages_getHistory) {
            return ((TLRPC.TL_messages_getHistory) request).peer != null;
        } else if (request instanceof TLRPC.TL_messages_getReplies) {
            return ((TLRPC.TL_messages_getReplies) request).peer != null && options.threadId != 0;
        } else if (request instanceof TLRPC.TL_messages_getSavedHistory) {
            TLRPC.TL_messages_getSavedHistory req = (TLRPC.TL_messages_getSavedHistory) request;
            return req.peer != null && (options.savedParentDialogId == 0 || req.parent_peer != null);
        }
        return false;
    }

    private void processPage(TLRPC.messages_Messages page) {
        if (cancelled.get()) {
            return;
        }
        try {
            if (pageIndex == 0 && page.count > 0) {
                expectedMessages += page.count;
            }
            if (page.messages == null || page.messages.isEmpty()) {
                advanceSourceOrBuild();
                return;
            }

            LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
            for (TLRPC.User user : page.users) {
                users.put(user.id, user);
            }
            LongSparseArray<TLRPC.Chat> chats = new LongSparseArray<>();
            for (TLRPC.Chat chat : page.chats) {
                chats.put(chat.id, chat);
            }

            ArrayList<TLRPC.Message> accepted = new ArrayList<>();
            int nextOffset = 0;
            for (TLRPC.Message message : page.messages) {
                if (message == null || message instanceof TLRPC.TL_messageEmpty || message.id <= 0) {
                    continue;
                }
                if (message.noforwards) {
                    throw new ProtectedContentException();
                }
                accepted.add(message);
                if (nextOffset == 0 || message.id < nextOffset) {
                    nextOffset = message.id;
                }
            }
            if (accepted.isEmpty() || nextOffset == 0 || nextOffset == offsetId) {
                advanceSourceOrBuild();
                return;
            }

            File pageFile = new File(pagesDir, String.format(Locale.US, "%08d.jsonl", pageFiles.size()));
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(pageFile), StandardCharsets.UTF_8))) {
                Collections.reverse(accepted);
                for (TLRPC.Message message : accepted) {
                    if (cancelled.get()) {
                        return;
                    }
                    MessageObject object = new MessageObject(options.account, message, users, chats, false, false,
                            options.historyMode == HISTORY_SAVED);
                    JSONObject json = createMessageJson(message, object, users, chats);
                    copyMediaIfRequested(message, object, json);
                    writer.write(json.toString());
                    writer.newLine();
                    exportedMessages++;
                    notifyProgress(options.includeMedia ? STAGE_MEDIA : STAGE_HISTORY);
                }
            }
            pageFiles.add(pageFile);
            pageIndex++;
            offsetId = nextOffset;
            requestNextPage();
        } catch (ProtectedContentException e) {
            fail(localized("Экспорт остановлен: история содержит защищённый контент.", "Export stopped because the history contains protected content."), e);
        } catch (Throwable e) {
            fail(localized("Не удалось обработать историю чата.", "Could not process the chat history."), e);
        }
    }

    private void advanceSourceOrBuild() {
        if (sourceIndex == 0 && options.historyMode == HISTORY_DIALOG && options.mergeDialogId != 0) {
            sourceIndex = 1;
            activeSourceDialogId = options.mergeDialogId;
            offsetId = 0;
            pageIndex = 0;
            requestNextPage();
            return;
        }
        notifyProgress(STAGE_ARCHIVE);
        worker.execute(options.outputFormat == OUTPUT_PDF ? this::buildPdf : this::buildArchive);
    }

    private JSONObject createMessageJson(TLRPC.Message message, MessageObject object,
                                         LongSparseArray<TLRPC.User> users,
                                         LongSparseArray<TLRPC.Chat> chats) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", message.id);
        json.put("source_dialog_id", String.valueOf(activeSourceDialogId));
        json.put("date", formatDate(message.date));
        json.put("date_unixtime", String.valueOf(message.date));
        json.put("out", message.out);
        long fromId = object.getFromChatId();
        json.put("from_id", String.valueOf(fromId));
        json.put("from", resolveSenderName(fromId, message, users, chats));
        int replyId = object.getReplyMsgId();
        if (replyId != 0) {
            json.put("reply_to_message_id", replyId);
        }
        String rawText = message.message;
        if (TextUtils.isEmpty(rawText) && object.messageText != null) {
            rawText = object.messageText.toString();
        }
        json.put("text", rawText == null ? "" : rawText);
        json.put("type", getMessageType(object));
        return json;
    }

    private String resolveSenderName(long fromId, TLRPC.Message message,
                                     LongSparseArray<TLRPC.User> users,
                                     LongSparseArray<TLRPC.Chat> chats) {
        if (!TextUtils.isEmpty(message.post_author)) {
            return message.post_author;
        }
        if (fromId > 0) {
            TLRPC.User user = users.get(fromId);
            if (user == null) {
                user = messagesController.getUser(fromId);
            }
            return user == null ? String.valueOf(fromId) : UserObject.getUserName(user);
        } else if (fromId < 0) {
            TLRPC.Chat chat = chats.get(-fromId);
            if (chat == null) {
                chat = messagesController.getChat(-fromId);
            }
            return chat == null || TextUtils.isEmpty(chat.title) ? String.valueOf(fromId) : chat.title;
        }
        return localized("Системное сообщение", "Service message");
    }

    private String getMessageType(MessageObject object) {
        if (object.isVoice()) return "voice_message";
        if (object.isRoundVideo()) return "video_message";
        if (object.isVideo()) return "video_file";
        if (object.isMusic()) return "audio_file";
        if (object.isGif()) return "animation";
        if (object.isSticker()) return "sticker";
        if (object.getDocument() != null) return "file";
        TLRPC.MessageMedia media = MessageObject.getMedia(object.messageOwner);
        if (media != null && media.photo != null) return "photo";
        if (object.messageOwner.action != null) return "service";
        return "message";
    }

    private void copyMediaIfRequested(TLRPC.Message message, MessageObject object, JSONObject json) throws Exception {
        MediaAttachment attachment = findAttachment(message, object);
        if (attachment == null) {
            return;
        }
        json.put("media_original_name", attachment.originalName);
        json.put("mime_type", attachment.mimeType);
        if (attachment.declaredSize > 0) {
            json.put("media_size", attachment.declaredSize);
        }
        TLRPC.MessageMedia messageMedia = MessageObject.getMedia(message);
        if (!options.includeMedia || messageMedia != null && messageMedia.ttl_seconds != 0) {
            return;
        }
        boolean image = attachment.mimeType.startsWith("image/");
        boolean video = attachment.mimeType.startsWith("video/");
        if (image && !options.includePhotos
                || video && !options.includeVideos
                || !image && !video && !options.includeFiles) {
            return;
        }
        if (options.outputFormat == OUTPUT_PDF && !attachment.mimeType.startsWith("image/")) {
            return;
        }
        if (options.maxMediaBytes > 0 && attachment.declaredSize > options.maxMediaBytes) {
            skippedMedia++;
            return;
        }
        if (options.maxMediaBytes > 0 && attachment.declaredSize > 0
                && copiedMediaBytes + attachment.declaredSize > options.maxMediaBytes) {
            skippedMedia++;
            return;
        }

        File source = existingMediaFile(message, attachment);
        if (source == null || !source.exists()) {
            source = downloadMedia(object, attachment);
        }
        if (cancelled.get()) {
            return;
        }
        if (source == null || !source.exists()) {
            skippedMedia++;
            return;
        }
        if (options.maxMediaBytes > 0 && source.length() > options.maxMediaBytes) {
            skippedMedia++;
            return;
        }
        if (options.maxMediaBytes > 0 && copiedMediaBytes + source.length() > options.maxMediaBytes) {
            skippedMedia++;
            return;
        }
        if (mediaDir.getUsableSpace() < source.length() + 10L * 1024L * 1024L) {
            throw new IllegalStateException("Not enough storage space");
        }
        String storedName = Math.abs(activeSourceDialogId) + "_" + message.id + "_" + sanitizeFileName(attachment.originalName);
        File destination = uniqueFile(mediaDir, storedName);
        copyFile(source, destination);
        json.put("media", "media/" + destination.getName());
        copiedMedia++;
        copiedMediaBytes += destination.length();
    }

    @Nullable
    private MediaAttachment findAttachment(TLRPC.Message message, MessageObject object) {
        TLRPC.Document document = object.getDocument();
        if (document != null) {
            String name = FileLoader.getDocumentFileName(document);
            if (TextUtils.isEmpty(name)) {
                name = "file_" + message.id + extensionForMime(document.mime_type);
            }
            return new MediaAttachment(document, null, null, name,
                    TextUtils.isEmpty(document.mime_type) ? "application/octet-stream" : document.mime_type,
                    document.size);
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media == null || media.photo == null || media.photo.sizes == null) {
            return null;
        }
        TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes,
                AndroidUtilities.getPhotoSize(true), false, null, true);
        if (size == null) {
            return null;
        }
        return new MediaAttachment(null, media.photo, size, "photo_" + message.id + ".jpg", "image/jpeg", size.size);
    }

    @Nullable
    private File existingMediaFile(TLRPC.Message message, MediaAttachment attachment) {
        String attachPath = message.attachPath;
        if (!TextUtils.isEmpty(attachPath)) {
            File attached = new File(attachPath);
            if (attached.exists()) {
                return attached;
            }
        }
        File result;
        if (attachment.document != null) {
            result = fileLoader.getPathToAttach(attachment.document, null, false, true);
        } else {
            result = fileLoader.getPathToAttach(attachment.photoSize, null, false, true);
        }
        if (result != null && result.exists()) {
            return result;
        }
        result = fileLoader.getPathToMessage(message, true, true);
        return result != null && result.exists() ? result : null;
    }

    @Nullable
    private File downloadMedia(MessageObject object, MediaAttachment attachment) throws InterruptedException {
        final String fileName = FileLoader.getAttachFileName(
                attachment.document != null ? attachment.document : attachment.photoSize);
        waitingFileName = fileName;
        downloadedFile = null;
        waitingFileLatch = new CountDownLatch(1);
        activeDocument = attachment.document;
        activePhotoSize = attachment.photoSize;
        AndroidUtilities.runOnUIThread(() -> {
            if (cancelled.get()) {
                CountDownLatch latch = waitingFileLatch;
                if (latch != null) latch.countDown();
                return;
            }
            if (attachment.document != null) {
                fileLoader.loadFile(attachment.document, object, FileLoader.PRIORITY_HIGH,
                        object.shouldEncryptPhotoOrVideo() ? 2 : 0);
            } else {
                fileLoader.loadFile(ImageLocation.getForPhoto(attachment.photoSize, attachment.photo), object,
                        "jpg", FileLoader.PRIORITY_HIGH, 0);
            }
        });
        boolean signaled = waitingFileLatch.await(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        File result = downloadedFile;
        if (!signaled && !cancelled.get()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (attachment.document != null) {
                    fileLoader.cancelLoadFile(attachment.document);
                } else {
                    fileLoader.cancelLoadFile(attachment.photoSize);
                }
            });
        }
        waitingFileLatch = null;
        waitingFileName = null;
        activeDocument = null;
        activePhotoSize = null;
        if (result != null && result.exists()) {
            return result;
        }
        if (attachment.document != null) {
            result = fileLoader.getPathToAttach(attachment.document, null, false, true);
        } else {
            result = fileLoader.getPathToAttach(attachment.photoSize, null, false, true);
        }
        return result != null && result.exists() ? result : null;
    }

    private void buildArchive() {
        if (cancelled.get()) {
            return;
        }
        File archive = null;
        try {
            File json = new File(sessionDir, "result.json");
            File html = new File(sessionDir, "messages.html");
            buildJson(json);
            ensureNotCancelled();
            buildHtml(html);
            ensureNotCancelled();
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
            archive = uniqueFile(outputDir, "LumaGram_" + sanitizeFileName(options.title) + "_" + timestamp + ".zip");
            activeArchive = archive;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
                addToZip(zip, json, "result.json");
                ensureNotCancelled();
                addToZip(zip, html, "messages.html");
                ensureNotCancelled();
                File[] mediaFiles = mediaDir.listFiles();
                if (mediaFiles != null) {
                    for (File media : mediaFiles) {
                        ensureNotCancelled();
                        if (media.isFile()) {
                            addToZip(zip, media, "media/" + media.getName());
                        }
                    }
                }
            }
            ensureNotCancelled();
            activeArchive = null;
            cleanupSession(false);
            complete(archive);
        } catch (ExportCancelledException e) {
            if (archive != null) archive.delete();
        } catch (Throwable e) {
            fail(localized("Не удалось создать ZIP-архив.", "Could not create the ZIP archive."), e);
        } finally {
            activeArchive = null;
        }
    }

    private void buildPdf() {
        if (cancelled.get()) {
            return;
        }
        File pdf = null;
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
            pdf = uniqueFile(outputDir, "LumaGram_" + sanitizeFileName(options.title) + "_" + timestamp + ".pdf");
            activeArchive = pdf;
            LumaChatPdfRenderer.render(pdf, safeTitle(), pageFiles, sessionDir, this::ensureNotCancelled);
            ensureNotCancelled();
            activeArchive = null;
            cleanupSession(false);
            complete(pdf);
        } catch (ExportCancelledException e) {
            if (pdf != null) pdf.delete();
        } catch (Throwable e) {
            fail(localized("Не удалось создать PDF-документ.", "Could not create the PDF document."), e);
        } finally {
            activeArchive = null;
        }
    }

    private void buildJson(File target) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
            writer.write("{\n  \"about\": \"LumaGram chat export\",\n");
            writer.write("  \"name\": " + JSONObject.quote(safeTitle()) + ",\n");
            writer.write("  \"dialog_id\": " + JSONObject.quote(String.valueOf(options.dialogId)) + ",\n");
            writer.write("  \"exported_at\": " + JSONObject.quote(formatDate((int) (System.currentTimeMillis() / 1000L))) + ",\n");
            writer.write("  \"messages\": [\n");
            boolean first = true;
            for (int i = pageFiles.size() - 1; i >= 0; i--) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(pageFiles.get(i)), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ensureNotCancelled();
                        if (!first) writer.write(",\n");
                        writer.write("    " + line);
                        first = false;
                    }
                }
            }
            writer.write("\n  ]\n}\n");
        }
    }

    private void buildHtml(File target) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
            writer.write("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
            writer.write("<title>" + html(safeTitle()) + "</title><style>");
            writer.write("body{margin:0;background:#0b0d12;color:#eef2ff;font:15px system-ui,-apple-system,sans-serif}.wrap{max-width:860px;margin:auto;padding:24px 14px 60px}h1{font-size:24px}.meta{color:#8d96aa;margin-bottom:24px}.m{max-width:78%;padding:10px 12px;margin:7px 0;border-radius:17px;background:#202532;box-shadow:0 5px 18px #0005}.m.out{margin-left:auto;background:#5943a7}.from{font-weight:700;color:#9dc6ff}.date{font-size:12px;color:#aeb6c8;margin-left:8px}.text{white-space:pre-wrap;word-break:break-word;margin-top:4px}.media{display:inline-block;margin-top:7px;color:#9ed2ff}img.preview{display:block;max-width:100%;max-height:480px;border-radius:12px;margin-top:8px}@media(max-width:600px){.m{max-width:90%}}</style></head><body><div class=\"wrap\">");
            writer.write("<h1>" + html(safeTitle()) + "</h1><div class=\"meta\">LumaGram · " + html(formatDate((int) (System.currentTimeMillis() / 1000L))) + "</div>");
            for (int i = pageFiles.size() - 1; i >= 0; i--) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(pageFiles.get(i)), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ensureNotCancelled();
                        JSONObject item = new JSONObject(line);
                        writer.write("<article class=\"m" + (item.optBoolean("out") ? " out" : "") + "\">");
                        writer.write("<div><span class=\"from\">" + html(item.optString("from")) + "</span><span class=\"date\">" + html(item.optString("date")) + "</span></div>");
                        String text = item.optString("text");
                        if (!TextUtils.isEmpty(text)) {
                            writer.write("<div class=\"text\">" + html(text) + "</div>");
                        }
                        String media = item.optString("media");
                        if (!TextUtils.isEmpty(media)) {
                            String label = item.optString("media_original_name", media);
                            if (item.optString("mime_type").startsWith("image/")) {
                                writer.write("<a href=\"" + htmlAttribute(media) + "\"><img class=\"preview\" loading=\"lazy\" src=\"" + htmlAttribute(media) + "\" alt=\"" + htmlAttribute(label) + "\"></a>");
                            } else {
                                writer.write("<a class=\"media\" href=\"" + htmlAttribute(media) + "\">?? " + html(label) + "</a>");
                            }
                        }
                        writer.write("</article>");
                    }
                }
            }
            writer.write("</div></body></html>");
        }
    }

    private void addToZip(ZipOutputStream zip, File file, String entryName) throws Exception {
        zip.putNextEntry(new ZipEntry(entryName));
        byte[] buffer = new byte[64 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                ensureNotCancelled();
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    private void copyFile(File source, File destination) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (cancelled.get()) {
                    destination.delete();
                    return;
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private void ensureNotCancelled() throws ExportCancelledException {
        if (cancelled.get()) {
            throw new ExportCancelledException();
        }
    }

    private void notifyProgress(int stage) {
        Progress progress = progress(stage);
        AndroidUtilities.runOnUIThread(() -> {
            if (!terminalCallbackSent.get()) {
                listener.onProgress(progress);
            }
        });
    }

    private Progress progress(int stage) {
        int expected = Math.max(expectedMessages, exportedMessages);
        return new Progress(stage, exportedMessages, expected, copiedMedia, skippedMedia);
    }

    private void complete(File archive) {
        if (!terminalCallbackSent.compareAndSet(false, true)) {
            return;
        }
        removeObservers();
        Progress finalProgress = progress(STAGE_ARCHIVE);
        worker.shutdown();
        AndroidUtilities.runOnUIThread(() -> listener.onComplete(archive, finalProgress));
    }

    private void fail(String message, Throwable error) {
        if (!terminalCallbackSent.compareAndSet(false, true)) {
            return;
        }
        cancelled.set(true);
        connectionsManager.cancelRequestsForGuid(requestGuid);
        CountDownLatch latch = waitingFileLatch;
        if (latch != null) latch.countDown();
        removeObservers();
        final File archive = activeArchive;
        worker.execute(() -> {
            if (archive != null && archive.exists()) {
                archive.delete();
            }
            cleanupSession(true);
            worker.shutdown();
        });
        AndroidUtilities.runOnUIThread(() -> listener.onError(message, error));
    }

    private void finishCancelled() {
        if (!terminalCallbackSent.compareAndSet(false, true)) {
            return;
        }
        removeObservers();
        final File archive = activeArchive;
        worker.execute(() -> {
            if (archive != null && archive.exists()) {
                archive.delete();
            }
            cleanupSession(true);
            worker.shutdown();
        });
        AndroidUtilities.runOnUIThread(listener::onCancelled);
    }

    private void removeObservers() {
        AndroidUtilities.runOnUIThread(() -> {
            account.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoaded);
            account.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadFailed);
        });
    }

    private void cleanupSession(boolean includeOutputPages) {
        if (sessionDir != null) {
            deleteRecursively(sessionDir);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    @Override
    public void didReceivedNotification(int id, int accountId, Object... args) {
        if ((id != NotificationCenter.fileLoaded && id != NotificationCenter.fileLoadFailed) || args.length == 0) {
            return;
        }
        String name = String.valueOf(args[0]);
        if (!TextUtils.equals(name, waitingFileName)) {
            return;
        }
        if (id == NotificationCenter.fileLoaded && args.length > 1 && args[1] instanceof File) {
            downloadedFile = (File) args[1];
        }
        CountDownLatch latch = waitingFileLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    private String safeTitle() {
        return TextUtils.isEmpty(options.title) ? localized("Чат", "Chat") : options.title;
    }

    private static String formatDate(int unixSeconds) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(unixSeconds * 1000L));
    }

    private static String sanitizeFileName(String value) {
        if (TextUtils.isEmpty(value)) return "chat";
        String clean = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (clean.contains("__")) clean = clean.replace("__", "_");
        if (clean.length() > 80) clean = clean.substring(0, 80);
        return TextUtils.isEmpty(clean) ? "chat" : clean;
    }

    private static File uniqueFile(File directory, String name) {
        File result = new File(directory, name);
        if (!result.exists()) return result;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10_000; i++) {
            result = new File(directory, base + " (" + i + ")" + extension);
            if (!result.exists()) return result;
        }
        return new File(directory, base + "_" + System.currentTimeMillis() + extension);
    }

    private static String extensionForMime(String mime) {
        if (TextUtils.isEmpty(mime)) return "";
        if (mime.equals("image/jpeg")) return ".jpg";
        if (mime.equals("image/png")) return ".png";
        if (mime.equals("video/mp4")) return ".mp4";
        if (mime.equals("audio/ogg")) return ".ogg";
        if (mime.equals("application/pdf")) return ".pdf";
        return "";
    }

    private static String html(String value) {
        if (value == null) return "";
        return android.text.TextUtils.htmlEncode(value).replace("\n", "<br>");
    }

    private static String htmlAttribute(String value) {
        return html(value).replace("`", "&#96;");
    }

    private static String localized(String russian, String english) {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage()) ? russian : english;
    }

    private static final class ProtectedContentException extends Exception {
    }

    private static final class ExportCancelledException extends Exception {
    }
}
