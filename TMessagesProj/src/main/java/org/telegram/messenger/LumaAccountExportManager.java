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
        final int messages;

        ExportedChat(ChatSpec spec, String directory, int messages) {
            this.spec = spec;
            this.directory = directory;
            this.messages = messages;
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
            File privateCache = ApplicationLoader.applicationContext.getCacheDir();
            File exportCache = ApplicationLoader.applicationContext.getExternalCacheDir();
            if (exportCache == null) exportCache = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            if (privateCache == null || exportCache == null) throw new IllegalStateException("No cache directory");
            outputDir = new File(exportCache, "luma_account_exports");
            if (!outputDir.exists() && !outputDir.mkdirs()) throw new IllegalStateException("Could not create output directory");
            File sessionRoot = new File(privateCache, "luma_account_export_sessions");
            if (!sessionRoot.exists() && !sessionRoot.mkdirs()) throw new IllegalStateException("Could not create session root");
            sessionDir = new File(sessionRoot, ".session_" + System.currentTimeMillis());
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
        options.desktopAccountLayout = true;

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
            String directory = String.format(Locale.US, "chat_%03d", chatIndex + 1);
            File destination = new File(chatsDir, directory);
            if (!destination.mkdirs()) throw new IllegalStateException("Could not create chat directory");
            extractZip(archive, destination);
            archive.delete();
            exported.add(new ExportedChat(spec, directory, progress.messages));
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
            LumaExportHtmlTheme.writeAssets(sessionDir);
            File listsDir = new File(sessionDir, "lists");
            if (!listsDir.exists() && !listsDir.mkdirs()) {
                throw new IllegalStateException("Could not create lists directory");
            }
            writeChatsList(new File(listsDir, "chats.html"));
            writeIndex(new File(sessionDir, "export_results.html"));
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
            writeDocumentHead(writer, localized("Экспортированные данные", "Exported Data"));
            String accountName = TextUtils.isEmpty(config.accountName) ? "LumaGram" : config.accountName;
            int color = (accountName.hashCode() & 0x7fffffff) % 8 + 1;
            writer.write("<body onload=\"CheckLocation();\"><div class=\"page_wrap\">");
            writer.write("<div class=\"page_header\"><div class=\"content\"><div class=\"text bold\">"
                    + html(localized("Экспортированные данные", "Exported Data")) + "</div></div></div>");
            writer.write("<div class=\"page_body\"><div class=\"personal_info clearfix\">");
            writer.write("<div class=\"pull_right userpic_wrap\"><div class=\"userpic userpic" + color
                    + "\" style=\"width: 90px; height: 90px\"><div class=\"initials\" style=\"line-height: 90px\">"
                    + html(initial(accountName)) + "</div></div></div>");
            writer.write("<div class=\"rows names\"><div class=\"row\"><div class=\"label details\">"
                    + html(localized("Имя", "Name")) + "</div><div class=\"value bold\">" + html(accountName)
                    + "</div></div></div></div>");
            writer.write("<div class=\"sections with_divider\"><a class=\"section block_link chats\" href=\"lists/chats.html#allow_back\" onclick=\"return LumaExportOpen('lists/chats')\">"
                    + "<div class=\"counter details\">" + exported.size() + "</div><div class=\"label bold\">"
                    + html(localized("Чаты", "Chats")) + "</div></a></div>");
            writer.write("<div class=\"page_about details with_divider\">"
                    + html(localized("Здесь находятся данные, экспортированные из LumaGram.",
                    "Here are the data exported from LumaGram.")) + "</div></div></div>");
            writer.write("<iframe id=\"luma_export_view\" title=\"" + html(localized("Просмотр экспорта", "Export viewer"))
                    + "\" hidden></iframe><script>(function(){const pages={");
            writer.write(JSONObject.quote("lists/chats"));
            writer.write(':');
            writeEmbeddedHtml(writer, new File(new File(sessionDir, "lists"), "chats.html"), "lists/");
            for (ExportedChat chat : exported) {
                checkCancelled();
                writer.write(',');
                writer.write(JSONObject.quote(chat.directory));
                writer.write(':');
                writeEmbeddedHtml(writer, new File(new File(chatsDir, chat.directory), "messages.html"),
                        "chats/" + chat.directory + "/");
            }
            writer.write("};const view=document.getElementById('luma_export_view'),stack=[];"
                    + "function show(key){const page=pages[key];if(!page)return true;view.srcdoc=page;view.hidden=false;return false;}"
                    + "window.LumaExportOpen=function(key){if(!pages[key])return true;if(stack[stack.length-1]!==key)stack.push(key);return show(key);};"
                    + "window.LumaExportBack=function(){stack.pop();if(stack.length)return show(stack[stack.length-1]);view.hidden=true;view.srcdoc='';return false;};"
                    + "})();</script></body></html>");
        }
    }

    private void writeChatsList(File target) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
            writeDocumentHead(writer, localized("Чаты", "Chats"));
            writer.write("<body onload=\"CheckLocation();\"><div class=\"page_wrap\"><div class=\"page_header\">"
                    + "<a class=\"content block_link\" href=\"../export_results.html\" onclick=\"if(parent!==window&&parent.LumaExportBack)return parent.LumaExportBack();return GoBack(this)\">"
                    + "<div class=\"text bold\">" + html(localized("Чаты", "Chats")) + "</div></a></div>");
            writer.write("<div class=\"page_body list_page\"><div class=\"page_about details\">"
                    + html(localized("В этом списке показаны экспортированные чаты.",
                    "This list contains the exported chats.")) + "</div><div class=\"entry_list\">");
            for (ExportedChat chat : exported) {
                checkCancelled();
                String title = TextUtils.isEmpty(chat.spec.title) ? localized("Без названия", "Untitled") : chat.spec.title;
                int color = (title.hashCode() & 0x7fffffff) % 8 + 1;
                writer.write("<a class=\"entry block_link clearfix\" href=\"../chats/" + chat.directory
                        + "/messages.html#allow_back\" onclick=\"if(parent!==window&&parent.LumaExportOpen)return parent.LumaExportOpen('"
                        + chat.directory + "')\"><div class=\"pull_left userpic_wrap\"><div class=\"userpic userpic"
                        + color + "\" style=\"width: 48px; height: 48px\"><div class=\"initials\" style=\"line-height: 48px\">"
                        + html(initial(title)) + "</div></div></div><div class=\"body\"><div class=\"pull_right info details\">"
                        + chat.messages + "</div><div class=\"name bold\">" + html(title)
                        + "</div><div class=\"details_entry details\">" + html(chat.spec.type) + "</div></div></a>");
            }
            writer.write("</div></div></div></body></html>");
        }
    }

    private static void writeDocumentHead(BufferedWriter writer, String title) throws Exception {
        writer.write("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>" + html(title) + "</title>"
                + "<meta content=\"width=device-width, initial-scale=1.0\" name=\"viewport\"/>"
                + "<style>");
        writer.write(LumaExportHtmlTheme.inlineCss());
        writer.write("#luma_export_view{position:fixed;inset:0;width:100%;height:100%;border:0;z-index:1000;background:#fff}"
                + "@media(max-width:520px){.page_header .content,.page_body{width:100%;box-sizing:border-box}}"
                + "@media(prefers-color-scheme:dark){#luma_export_view{background:#1a2026}}</style><script>");
        writer.write(LumaExportHtmlTheme.script());
        writer.write("</script></head>");
    }

    private void writeEmbeddedHtml(BufferedWriter writer, File source, String basePath) throws Exception {
        final String prefix = "<!DOCTYPE html><html><head>";
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
                writeJsonText(writer, prefix + "<base href=\"" + basePath + "\">");
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
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
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

    private static String initial(String title) {
        if (TextUtils.isEmpty(title)) return "?";
        return title.substring(0, title.offsetByCodePoints(0, 1)).toUpperCase(locale());
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
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
