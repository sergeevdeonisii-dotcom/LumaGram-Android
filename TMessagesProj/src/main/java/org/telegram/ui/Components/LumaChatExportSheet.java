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
            tr("PDF — красивый документ с фотографиями", "PDF - styled document with photos"),
            tr("HTML + JSON — без медиа", "HTML + JSON - no media"),
            tr("HTML + JSON — с медиа до 50 МБ", "HTML + JSON - media up to 50 MB")
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(
            chatActivity.getParentActivity(),
            chatActivity.getResourceProvider()
        );
        builder.setTitle(tr("Скачать чат", "Export chat"));
        builder.setMessage(tr(
            "Выберите формат. PDF оформляется как переписка с пузырями, датами, фотографиями и нумерацией страниц. HTML + JSON сохраняется в ZIP для просмотра и обработки.",
            "Choose a format. PDF is styled like a chat with bubbles, dates, photos and page numbers. HTML + JSON is saved as a ZIP for viewing and processing."
        ));
        builder.setItems(choices, (dialog, which) -> {
            int outputFormat = which == 0
                ? LumaChatExportManager.OUTPUT_PDF
                : LumaChatExportManager.OUTPUT_ARCHIVE;
            boolean includeMedia = which == 0 || which == 2;
            startExport(chatActivity, outputFormat, includeMedia);
        });
        builder.setNegativeButton(tr("Отмена", "Cancel"), null);
        chatActivity.showDialog(builder.create());
    }

    private static void startExport(ChatActivity chatActivity, int outputFormat, boolean includeMedia) {
        if (chatActivity.getParentActivity() == null) {
            return;
        }
        LumaChatExportManager.Options options = createOptions(chatActivity, outputFormat, includeMedia);
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
                    progressDialog.setMessage(outputFormat == LumaChatExportManager.OUTPUT_PDF
                        ? tr("Оформление PDF-документа…", "Rendering PDF document...")
                        : tr("Создание ZIP-архива…", "Creating ZIP archive..."));
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
            public void onComplete(File output, LumaChatExportManager.Progress finalProgress) {
                dismiss(progressDialog);
                if (chatActivity.getParentActivity() == null) {
                    //noinspection ResultOfMethodCallIgnored
                    output.delete();
                    return;
                }
                boolean pdf = outputFormat == LumaChatExportManager.OUTPUT_PDF;
                MediaController.saveFile(
                    output.getAbsolutePath(),
                    chatActivity.getParentActivity(),
                    2,
                    output.getName(),
                    pdf ? "application/pdf" : "application/zip",
                    uri -> {
                        if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
                            // MediaStore owns the saved copy; discard the private cache duplicate.
                            //noinspection ResultOfMethodCallIgnored
                            output.delete();
                        }
                        showComplete(chatActivity, uri, output, finalProgress, pdf);
                    }
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

    private static LumaChatExportManager.Options createOptions(
        ChatActivity chatActivity,
        int outputFormat,
        boolean includeMedia
    ) {
        LumaChatExportManager.Options options = new LumaChatExportManager.Options(
            chatActivity.getCurrentAccount(),
            chatActivity.getDialogId(),
            getTitle(chatActivity)
        );
        options.outputFormat = outputFormat;
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
        File output,
        LumaChatExportManager.Progress finalProgress,
        boolean pdf
    ) {
        if (chatActivity.getParentActivity() == null) {
            return;
        }
        String details = tr(
            (pdf ? "PDF сохранён" : "Архив сохранён") + " в «Загрузки/Telegram».\n\nСообщений: " + finalProgress.messages
                + "\nМедиафайлов: " + finalProgress.mediaFiles
                + (finalProgress.skippedMediaFiles > 0
                    ? "\nПропущено медиа: " + finalProgress.skippedMediaFiles
                    : ""),
            (pdf ? "The PDF was saved" : "The archive was saved") + " to Downloads/Telegram.\n\nMessages: " + finalProgress.messages
                + "\nMedia files: " + finalProgress.mediaFiles
                + (finalProgress.skippedMediaFiles > 0
                    ? "\nSkipped media: " + finalProgress.skippedMediaFiles
                    : "")
        );
        AlertDialog.Builder builder = new AlertDialog.Builder(
            chatActivity.getParentActivity(),
            chatActivity.getResourceProvider()
        );
        builder.setTitle(pdf ? tr("PDF готов", "PDF ready") : tr("Чат скачан", "Chat exported"));
        builder.setMessage(details);
        builder.setPositiveButton(tr("Поделиться", "Share"),
            (dialog, which) -> share(chatActivity, savedUri, output, pdf));
        builder.setNegativeButton("OK", null);
        chatActivity.showDialog(builder.create());
    }

    private static void share(ChatActivity chatActivity, Uri savedUri, File output, boolean pdf) {
        if (chatActivity.getParentActivity() == null) {
            return;
        }
        try {
            Uri uri = savedUri;
            if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
                File shareFile = output;
                if (uri != null && "file".equalsIgnoreCase(uri.getScheme()) && !TextUtils.isEmpty(uri.getPath())) {
                    File savedFile = new File(uri.getPath());
                    if (savedFile.exists()) {
                        shareFile = savedFile;
                    }
                }
                if (shareFile == null || !shareFile.exists()) {
                    showError(chatActivity, tr("Файл больше недоступен.", "The file is no longer available."));
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
            intent.setType(pdf ? "application/pdf" : "application/zip");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, output.getName());
            if (Build.VERSION.SDK_INT >= 24) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            chatActivity.getParentActivity().startActivityForResult(
                Intent.createChooser(intent, pdf
                    ? tr("Поделиться PDF", "Share PDF")
                    : tr("Поделиться архивом", "Share archive")),
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
