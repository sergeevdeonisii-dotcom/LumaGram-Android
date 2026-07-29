package org.telegram.ui.Components;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LumaChatExportManager;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.Locale;

public final class LumaChatExportSheet {

    private LumaChatExportSheet() {
    }

    public static void show(ChatActivity chatActivity) {
        if (chatActivity == null || chatActivity.getParentActivity() == null) {
            return;
        }
        if (chatActivity.getCurrentEncryptedChat() != null) {
            showError(chatActivity, tr(
                "Секретные чаты пока нельзя экспортировать.",
                "Secret chats cannot be exported yet."
            ));
            return;
        }
        if (chatActivity.isPeerNoForwards()) {
            showError(chatActivity, tr(
                "В этом чате включена защита контента, поэтому экспорт недоступен.",
                "This chat protects its content, so it cannot be exported."
            ));
            return;
        }

        CharSequence[] choices = new CharSequence[] {
            tr("HTML + JSON — без медиа", "HTML + JSON - no media"),
            tr("HTML + JSON — с медиа до 50 МБ", "HTML + JSON - media up to 50 MB")
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(
            chatActivity.getParentActivity(),
            chatActivity.getResourceProvider()
        );
        builder.setTitle(tr("Скачать чат", "Export chat"));
        builder.setMessage(tr(
            "LumaGram соберёт историю в ZIP. Внутри будут удобная HTML-страница и полный JSON. Выберите, добавлять ли фотографии, видео, голосовые и файлы.",
            "LumaGram will create a ZIP containing a readable HTML page and the full JSON history. Choose whether to include photos, videos, voice messages and files."
        ));
        builder.setItems(choices, (dialog, which) -> startExport(chatActivity, which == 1));
        builder.setNegativeButton(tr("Отмена", "Cancel"), null);
        chatActivity.showDialog(builder.create());
    }

    private static void startExport(ChatActivity chatActivity, boolean includeMedia) {
        if (chatActivity.getParentActivity() == null) {
            return;
        }
        LumaChatExportManager.Options options = createOptions(chatActivity, includeMedia);
        final LumaChatExportManager[] manager = new LumaChatExportManager[1];

        AlertDialog progressDialog = new AlertDialog(
            chatActivity.getParentActivity(),
            AlertDialog.ALERT_TYPE_LOADING,
            chatActivity.getResourceProvider()
        );
        progressDialog.setMessage(tr("Подготовка истории…", "Preparing history..."));
        progressDialog.setCancelable(true);
        progressDialog.setCancelDialog(true);
        progressDialog.setOnCancelListener(dialog -> {
            if (manager[0] != null) {
                manager[0].cancel();
            }
        });

        manager[0] = new LumaChatExportManager(options, new LumaChatExportManager.Listener() {
            @Override
            public void onProgress(LumaChatExportManager.Progress progress) {
                if (!progressDialog.isShowing()) {
                    return;
                }
                progressDialog.setProgress(progress.getPercent());
                if (progress.stage == LumaChatExportManager.STAGE_ARCHIVE) {
                    progressDialog.setMessage(tr("Создание ZIP-архива…", "Creating ZIP archive..."));
                } else if (progress.stage == LumaChatExportManager.STAGE_MEDIA) {
                    progressDialog.setMessage(tr(
                        "Сообщений: " + progress.messages + " · файлов: " + progress.mediaFiles,
                        "Messages: " + progress.messages + " · files: " + progress.mediaFiles
                    ));
                } else {
                    String total = progress.expectedMessages > 0 ? " / " + progress.expectedMessages : "";
                    progressDialog.setMessage(
                        tr("Загрузка сообщений: ", "Loading messages: ") + progress.messages + total
                    );
                }
            }

            @Override
            public void onComplete(File archive, LumaChatExportManager.Progress finalProgress) {
                dismiss(progressDialog);
                if (chatActivity.getParentActivity() == null) {
                    return;
                }
                MediaController.saveFile(
                    archive.getAbsolutePath(),
                    chatActivity.getParentActivity(),
                    2,
                    archive.getName(),
                    "application/zip",
                    uri -> showComplete(chatActivity, uri, archive, finalProgress)
                );
            }

            @Override
            public void onError(String message, Throwable error) {
                dismiss(progressDialog);
                if (error != null) {
                    FileLog.e(error);
                }
                showError(chatActivity, message);
            }

            @Override
            public void onCancelled() {
                dismiss(progressDialog);
            }
        });

        progressDialog.show();
        manager[0].start();
    }

    private static LumaChatExportManager.Options createOptions(ChatActivity chatActivity, boolean includeMedia) {
        LumaChatExportManager.Options options = new LumaChatExportManager.Options(
            chatActivity.getCurrentAccount(),
            chatActivity.getDialogId(),
            getTitle(chatActivity)
        );
        options.includeMedia = includeMedia;
        options.maxMediaBytes = 50L * 1024L * 1024L;
        options.mergeDialogId = chatActivity.getMergeDialogId();
        options.threadId = chatActivity.getThreadId();
        options.secretChat = chatActivity.getCurrentEncryptedChat() != null;
        options.protectedContent = chatActivity.isPeerNoForwards();

        if (chatActivity.getChatMode() == ChatActivity.MODE_SAVED) {
            options.historyMode = LumaChatExportManager.HISTORY_SAVED;
            options.savedPeerId = chatActivity.getThreadId();
            options.mergeDialogId = 0;
        } else if (ChatObject.isMonoForum(chatActivity.getCurrentChat()) && chatActivity.getThreadId() != 0) {
            options.historyMode = LumaChatExportManager.HISTORY_SAVED;
            options.savedPeerId = chatActivity.getThreadId();
            options.savedParentDialogId = chatActivity.getDialogId();
            options.mergeDialogId = 0;
        } else if (chatActivity.getThreadId() != 0) {
            options.historyMode = LumaChatExportManager.HISTORY_REPLIES;
            options.mergeDialogId = 0;
        }
        return options;
    }

    private static String getTitle(ChatActivity chatActivity) {
        TLRPC.User user = chatActivity.getCurrentUser();
        if (user != null) {
            String name = UserObject.getUserName(user);
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
        }
        TLRPC.Chat chat = chatActivity.getCurrentChat();
        if (chat != null && !TextUtils.isEmpty(chat.title)) {
            return chat.title;
        }
        return tr("Чат", "Chat");
    }

    private static void showComplete(
        ChatActivity chatActivity,
        Uri savedUri,
        File archive,
        LumaChatExportManager.Progress finalProgress
    ) {
        if (chatActivity.getParentActivity() == null) {
            return;
        }
        String details = tr(
            "Архив сохранён в «Загрузки/Telegram».\n\nСообщений: " + finalProgress.messages
                + "\nМедиафайлов: " + finalProgress.mediaFiles
                + (finalProgress.skippedMediaFiles > 0
                    ? "\nПропущено медиа: " + finalProgress.skippedMediaFiles
                    : ""),
            "The archive was saved to Downloads/Telegram.\n\nMessages: " + finalProgress.messages
                + "\nMedia files: " + finalProgress.mediaFiles
                + (finalProgress.skippedMediaFiles > 0
                    ? "\nSkipped media: " + finalProgress.skippedMediaFiles
                    : "")
        );
        AlertDialog.Builder builder = new AlertDialog.Builder(
            chatActivity.getParentActivity(),
            chatActivity.getResourceProvider()
        );
        builder.setTitle(tr("Чат скачан", "Chat exported"));
        builder.setMessage(details);
        builder.setPositiveButton(tr("Поделиться", "Share"), (dialog, which) -> share(chatActivity, savedUri, archive));
        builder.setNegativeButton("OK", null);
        chatActivity.showDialog(builder.create());
    }

    private static void share(ChatActivity chatActivity, Uri savedUri, File archive) {
        if (chatActivity.getParentActivity() == null) {
            return;
        }
        try {
            Uri uri = savedUri;
            if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
                File shareFile = archive;
                if (uri != null && "file".equalsIgnoreCase(uri.getScheme()) && !TextUtils.isEmpty(uri.getPath())) {
                    File savedFile = new File(uri.getPath());
                    if (savedFile.exists()) {
                        shareFile = savedFile;
                    }
                }
                if (shareFile == null || !shareFile.exists()) {
                    showError(chatActivity, tr("Архив больше недоступен.", "The archive is no longer available."));
                    return;
                }
                uri = Build.VERSION.SDK_INT >= 24
                    ? FileProvider.getUriForFile(
                        chatActivity.getParentActivity(),
                        ApplicationLoader.getApplicationId() + ".provider",
                        shareFile
                    )
                    : Uri.fromFile(shareFile);
            }
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, archive.getName());
            if (Build.VERSION.SDK_INT >= 24) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            chatActivity.getParentActivity().startActivityForResult(
                Intent.createChooser(intent, tr("Поделиться архивом", "Share archive")),
                500
            );
        } catch (Throwable e) {
            FileLog.e(e);
            showError(chatActivity, tr(
                "Не удалось открыть меню «Поделиться».",
                "Could not open the share menu."
            ));
        }
    }

    private static void showError(ChatActivity chatActivity, String message) {
        if (chatActivity == null || chatActivity.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(
            chatActivity.getParentActivity(),
            chatActivity.getResourceProvider()
        );
        builder.setTitle(tr("Скачать чат", "Export chat"));
        builder.setMessage(message);
        builder.setPositiveButton("OK", null);
        chatActivity.showDialog(builder.create());
    }

    private static void dismiss(AlertDialog dialog) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                dialog.dismiss();
            } catch (Throwable ignore) {
            }
        });
    }

    private static String tr(String russian, String english) {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage()) ? russian : english;
    }
}
