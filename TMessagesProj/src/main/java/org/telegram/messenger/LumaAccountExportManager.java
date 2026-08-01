package org.telegram.messenger;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Sequentially exports selected dialogs and joins them into one browsable account archive. */
public final class LumaAccountExportManager {

    public static final class ChatSpec {
        public final long dialogId;
        public final String title;
        public final String type;
        public final boolean protectedContent;

        public ChatSpec(long dialogId, String title, String type, boolean protectedContent) {
            this.dialogId = dialogId;
            this.title = title;
            this.type = type;
            this.protectedContent = protectedContent;
        }
    }

    public static final class Config {
        public int account;
        public String accountName;
        public boolean includePhotos;
        public boolean includeVideos;
        public boolean includeFiles;
        public final ArrayList<ChatSpec> chats = new ArrayList<>();
    }

    public static final class Progress {
        public final int chatIndex;
        public final int chatCount;
        public final String chatTitle;
        public final int chatPercent;
        public final int messages;
        public final boolean packaging;

        Progress(int chatIndex, int chatCount, String chatTitle, int chatPercent,
                 int messages, boolean packaging) {
            this.chatIndex = chatIndex;
            this.chatCount = chatCount;
            this.chatTitle = chatTitle;
            this.chatPercent = chatPercent;
            this.messages = messages;
            this.packaging = packaging;
        }

        public int getPercent() {
            if (packaging) return 97;
            if (chatCount == 0) return 0;
            return Math.min(96, Math.round((chatIndex * 100f + chatPercent) / chatCount * .96f));
        }
    }

    public interface Listener {
        void onProgress(@NonNull Progress progress);
        void onComplete(@NonNull File archive, int exportedChats, int skippedChats,
                        int messages, int mediaFiles);
        void onError(@NonNull String message, @Nullable Throwable error);
        void onCancelled();
    }

    private static final class ExportedChat {
        final ChatSpec spec;
        final String directory;

        ExportedChat(ChatSpec spec, String directory) {
            this.spec = spec;
            this.directory = directory;
        }
    }

    private final Config config;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean terminal = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LumaAccountExport");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final ArrayList<ExportedChat> exported = new ArrayList<>();

    private File outputDir;
    private File sessionDir;
    private File chatsDir;
    private volatile LumaChatExportManager activeManager;
    private int chatIndex;
    private int skippedChats;
    private int totalMessages;
    private int totalMedia;
    private File activeArchive;

    public LumaAccountExportManager(@NonNull Config config, @NonNull Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    public void start() {
        if (config.chats.isEmpty()) {
            fail(localized("Нет выбранных чатов для экспорта.", "No chats were selected for export."), null);
            return;
        }
        try {
            File cache = ApplicationLoader.applicationContext.getExternalCacheDir();
            if (cache == null) cache = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            if (cache == null) throw new IllegalStateException("No cache directory");
            outputDir = new File(cache, "luma_account_exports");
            if (!outputDir.exists() && !outputDir.mkdirs()) throw new IllegalStateException("Could not create output directory");
            sessionDir = new File(outputDir, ".session_" + System.currentTimeMillis());
            chatsDir = new File(sessionDir, "chats");
            if (!chatsDir.mkdirs()) throw new IllegalStateException("Could not create session directory");
        } catch (Throwable e) {
            fail(localized("Не удалось подготовить папку экспорта.", "Could not prepare the export folder."), e);
            return;
        }
        exportNext();
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        LumaChatExportManager manager = activeManager;
        if (manager != null) manager.cancel();
        finishCancelled();
    }

    private void exportNext() {
        if (cancelled.get() || terminal.get()) return;
        if (chatIndex >= config.chats.size()) {
            notifyProgress(new Progress(chatIndex, config.chats.size(), "", 100, totalMessages, true));
            worker.execute(this::buildArchive);
            return;
        }

        ChatSpec spec = config.chats.get(chatIndex);
        LumaChatExportManager.Options options = new LumaChatExportManager.Options(config.account, spec.dialogId, spec.title);
        options.includeMedia = config.includePhotos || config.includeVideos || config.includeFiles;
        options.includePhotos = config.includePhotos;
        options.includeVideos = config.includeVideos;
        options.includeFiles = config.includeFiles;
        options.maxMediaBytes = 0;
        options.protectedContent = spec.protectedContent;

        activeManager = new LumaChatExportManager(options, new LumaChatExportManager.Listener() {
            @Override
            public void onProgress(LumaChatExportManager.Progress progress) {
                notifyProgress(new Progress(chatIndex, config.chats.size(), spec.title,
                        progress.getPercent(), totalMessages + progress.messages, false));
            }

            @Override
            public void onComplete(File archive, LumaChatExportManager.Progress progress) {
                activeManager = null;
                worker.execute(() -> consumeChatArchive(spec, archive, progress));
            }

            @Override
            public void onError(String message, Throwable error) {
                if (error != null) FileLog.e(error);
                activeManager = null;
                skippedChats++;
                chatIndex++;
                exportNext();
            }

            @Override
            public void onCancelled() {
                finishCancelled();
            }
        });
        activeManager.start();
    }

    private void consumeChatArchive(ChatSpec spec, File archive, LumaChatExportManager.Progress progress) {
        try {
            checkCancelled();
            String directory = String.format(Locale.US, "chat_%04d_%s", chatIndex + 1,
                    safeId(spec.dialogId));
            File destination = new File(chatsDir, directory);
            if (!destination.mkdirs()) throw new IllegalStateException("Could not create chat directory");
            extractZip(archive, destination);
            archive.delete();
            exported.add(new ExportedChat(spec, directory));
            totalMessages += progress.messages;
            totalMedia += progress.mediaFiles;
            chatIndex++;
            AndroidUtilities.runOnUIThread(this::exportNext);
        } catch (CancelledException e) {
            finishCancelled();
        } catch (Throwable e) {
            FileLog.e(e);
            archive.delete();
            skippedChats++;
            chatIndex++;
            AndroidUtilities.runOnUIThread(this::exportNext);
        }
    }

    private void buildArchive() {
        try {
            checkCancelled();
            writeIndex(new File(sessionDir, "index.html"));
            writeManifest(new File(sessionDir, "account.json"));
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
            activeArchive = uniqueFile(outputDir, "LumaGram_Account_" + timestamp + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(activeArchive)))) {
                addDirectory(zip, sessionDir, "");
            }
            checkCancelled();
            File result = activeArchive;
            activeArchive = null;
            deleteRecursively(sessionDir);
            if (!terminal.compareAndSet(false, true)) return;
            worker.shutdown();
            AndroidUtilities.runOnUIThread(() -> listener.onComplete(result, exported.size(), skippedChats,
                    totalMessages, totalMedia));
        } catch (CancelledException e) {
            finishCancelled();
        } catch (Throwable e) {
            fail(localized("Не удалось собрать архив аккаунта.", "Could not build the account archive."), e);
        }
    }

    private void writeIndex(File target) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
            writer.write("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
            writer.write("<title>LumaGram - " + html(config.accountName) + "</title><style>");
            writer.write(LumaExportHtmlTheme.CSS);
            writer.write("</style></head><body><div class=\"page_wrap\" id=\"home\"><div class=\"page_header\"><div class=\"content\"><div class=\"text bold\">" + html(localized("Чаты", "Chats")) + "</div></div></div><div class=\"page_body list_page\">");
            writer.write("<div class=\"page_about\"><div class=\"bold\">" + html(config.accountName) + "</div><div class=\"details\">" + exported.size() + " " + html(localized("чатов", "chats")) + " · " + totalMessages + " " + html(localized("сообщений", "messages")) + "</div></div>");
            writer.write("<input class=\"export_search\" id=\"q\" placeholder=\"" + html(localized("Поиск чатов", "Search chats")) + "\"><div class=\"entry_list\" id=\"list\">");
            for (int i = 0; i < exported.size(); i++) {
                ExportedChat chat = exported.get(i);
                String title = TextUtils.isEmpty(chat.spec.title) ? localized("Без названия", "Untitled") : chat.spec.title;
                int color = (title.hashCode() & 0x7fffffff) % 8 + 1;
                writer.write("<a class=\"entry block_link clearfix chat\" href=\"#\" data-chat=\"" + chat.directory + "\" data-title=\"" + attribute(title) + "\" data-name=\"" + attribute(title.toLowerCase(locale())) + "\"><span class=\"pull_left userpic userpic" + color + "\"><span class=\"initials\">" + html(initial(title)) + "</span></span><div class=\"body\"><div class=\"name bold\">" + html(title) + "</div><div class=\"details_entry details\">" + html(chat.spec.type) + "</div><div class=\"info details\">" + html(localized("Открыть историю сообщений", "Open message history")) + "</div></div></a>");
            }
            writer.write("</div>");
            if (exported.isEmpty()) {
                writer.write("<div class=\"empty\">" + html(localized("Нет доступных чатов для просмотра", "No available chats to display")) + "</div>");
            }
            writer.write("</div></div><button class=\"account_back\" id=\"back\" aria-label=\"" + html(localized("Назад", "Back")) + "\">‹</button>");
            if (!exported.isEmpty()) {
                writer.write("<iframe class=\"account_view\" id=\"conversation\" title=\"" + html(localized("История чата", "Chat history")) + "\"></iframe>");
            }
            writer.write("<script>const pages={");
            for (int i = 0; i < exported.size(); i++) {
                checkCancelled();
                ExportedChat chat = exported.get(i);
                if (i > 0) writer.write(',');
                writer.write(JSONObject.quote(chat.directory));
                writer.write(':');
                writeEmbeddedHtml(writer, new File(new File(chatsDir, chat.directory), "messages.html"), chat.directory);
            }
            writer.write("};const q=document.getElementById('q'),ch=[...document.querySelectorAll('.chat')],view=document.getElementById('conversation'),back=document.getElementById('back');q.oninput=()=>ch.forEach(x=>x.hidden=!x.dataset.name.includes(q.value.toLowerCase()));window.closeChat=()=>{if(!view)return;view.classList.remove('visible');back.classList.remove('visible');view.srcdoc='';document.title='LumaGram'};const openChat=x=>{if(!view)return;view.srcdoc=pages[x.dataset.chat]||'';view.classList.add('visible');back.classList.add('visible');document.title=x.dataset.title+' - LumaGram'};ch.forEach(x=>x.onclick=e=>{e.preventDefault();openChat(x)});back.onclick=closeChat;document.addEventListener('keydown',e=>{if(e.key==='Escape')closeChat()});</script></body></html>");
        }
    }

    private void writeEmbeddedHtml(BufferedWriter writer, File source, String directory) throws Exception {
        final String prefix = "<!doctype html><html><head>";
        writer.write('"');
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8), 64 * 1024)) {
            char[] initial = new char[prefix.length()];
            int initialRead = 0;
            while (initialRead < initial.length) {
                int read = reader.read(initial, initialRead, initial.length - initialRead);
                if (read < 0) break;
                initialRead += read;
            }
            String beginning = new String(initial, 0, initialRead);
            if (prefix.equals(beginning)) {
                writeJsonText(writer, prefix + "<base href=\"chats/" + directory + "/\"><style>.page_header .content .text{padding-left:70px!important}</style>");
            } else {
                writeJsonText(writer, beginning);
            }
            char[] buffer = new char[64 * 1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                checkCancelled();
                writeJsonText(writer, buffer, read);
            }
        }
        writer.write('"');
    }

    private static void writeJsonText(BufferedWriter writer, String value) throws Exception {
        writeJsonText(writer, value.toCharArray(), value.length());
    }

    private static void writeJsonText(BufferedWriter writer, char[] value, int length) throws Exception {
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < length; i++) {
            char c = value[i];
            switch (c) {
                case '"': writer.write("\\\""); break;
                case '\\': writer.write("\\\\"); break;
                case '\b': writer.write("\\b"); break;
                case '\f': writer.write("\\f"); break;
                case '\n': writer.write("\\n"); break;
                case '\r': writer.write("\\r"); break;
                case '\t': writer.write("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        writer.write("\\u");
                        writer.write(hex[(c >> 12) & 15]);
                        writer.write(hex[(c >> 8) & 15]);
                        writer.write(hex[(c >> 4) & 15]);
                        writer.write(hex[c & 15]);
                    } else {
                        writer.write(c);
                    }
            }
        }
    }

    private void writeManifest(File target) throws Exception {
        JSONObject root = new JSONObject();
        root.put("about", "LumaGram account export");
        root.put("account", config.accountName);
        root.put("exported_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()));
        root.put("chats", exported.size());
        root.put("skipped_chats", skippedChats);
        root.put("messages", totalMessages);
        root.put("media_files", totalMedia);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
            writer.write(root.toString(2));
        }
    }

    private void extractZip(File source, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(source)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                checkCancelled();
                File target = new File(destination, entry.getName());
                if (!target.getCanonicalPath().startsWith(root)) throw new SecurityException("Unsafe ZIP entry");
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            checkCancelled();
                            output.write(buffer, 0, read);
                        }
                    }
                }
                input.closeEntry();
            }
        }
    }

    private void addDirectory(ZipOutputStream zip, File directory, String prefix) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[64 * 1024];
        for (File file : files) {
            checkCancelled();
            String name = prefix + file.getName();
            if (file.isDirectory()) {
                addDirectory(zip, file, name + "/");
            } else {
                zip.putNextEntry(new ZipEntry(name));
                try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkCancelled();
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private void notifyProgress(Progress progress) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!terminal.get()) listener.onProgress(progress);
        });
    }

    private void finishCancelled() {
        if (!terminal.compareAndSet(false, true)) return;
        worker.execute(() -> {
            if (activeArchive != null) activeArchive.delete();
            deleteRecursively(sessionDir);
            worker.shutdown();
            AndroidUtilities.runOnUIThread(listener::onCancelled);
        });
    }

    private void fail(String message, Throwable error) {
        if (!terminal.compareAndSet(false, true)) return;
        cancelled.set(true);
        if (error != null) FileLog.e(error);
        worker.execute(() -> {
            if (activeArchive != null) activeArchive.delete();
            deleteRecursively(sessionDir);
            worker.shutdown();
            AndroidUtilities.runOnUIThread(() -> listener.onError(message, error));
        });
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.get()) throw new CancelledException();
    }

    private static String safeId(long id) {
        return id < 0 ? "m" + Long.toString(id).substring(1) : "p" + id;
    }

    private static String initial(String title) {
        if (TextUtils.isEmpty(title)) return "?";
        return title.substring(0, title.offsetByCodePoints(0, 1)).toUpperCase(locale());
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String attribute(String value) {
        return html(value).replace("\n", " ").replace("\r", " ");
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

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static Locale locale() {
        Locale value = LocaleController.getInstance().getCurrentLocale();
        return value == null ? Locale.getDefault() : value;
    }

    private static String localized(String russian, String english) {
        return "ru".equalsIgnoreCase(locale().getLanguage()) ? russian : english;
    }

    private static final class CancelledException extends Exception {
    }
}
