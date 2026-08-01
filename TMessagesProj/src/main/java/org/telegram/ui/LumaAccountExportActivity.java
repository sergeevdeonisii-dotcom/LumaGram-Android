package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LumaAccountExportManager;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/** Selection and progress UI for the experimental full-account HTML export. */
public class LumaAccountExportActivity extends BaseFragment {

    private static final int DIALOG_PAGE_SIZE = 100;

    private static final int ROW_PRIVATE = 1;
    private static final int ROW_BOTS = 2;
    private static final int ROW_GROUPS = 3;
    private static final int ROW_CHANNELS = 4;
    private static final int ROW_MAIN = 5;
    private static final int ROW_ARCHIVE = 6;
    private static final int ROW_PHOTOS = 7;
    private static final int ROW_VIDEOS = 8;
    private static final int ROW_FILES = 9;
    private static final int ROW_START = 10;
    private static final int ROW_FILTER_BASE = 10_000;

    private final HashSet<Integer> selectedFilters = new HashSet<>();
    private final HashMap<Integer, MessagesController.DialogFilter> filterRows = new HashMap<>();
    private boolean privateChats = true;
    private boolean bots = true;
    private boolean groups = true;
    private boolean channels = true;
    private boolean mainFolder = true;
    private boolean archiveFolder = true;
    private boolean photos = true;
    private boolean videos = true;
    private boolean files = true;
    private UniversalRecyclerView listView;
    private LumaAccountExportManager manager;
    private AlertDialog dialogScan;
    private boolean dialogScanCancelled;
    private long dialogScanDeadline;
    private final ArrayList<TLRPC.Dialog> scannedDialogs = new ArrayList<>();
    private final ArrayList<Integer> dialogScanFolders = new ArrayList<>();
    private final HashSet<Long> scannedDialogIds = new HashSet<>();
    private int dialogScanFolderIndex;
    private int dialogScanRequestId;
    private int dialogScanOffsetId;
    private int dialogScanOffsetDate;
    private TLRPC.InputPeer dialogScanOffsetPeer;
    private int dialogScanFolderLoaded;
    private boolean dialogScanExcludePinned;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(tr("Экспорт аккаунта", "Account export"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        getMessagesController().loadRemoteFilters(false, success -> AndroidUtilities.runOnUIThread(() -> {
            if (listView != null && listView.adapter != null) listView.adapter.update(false);
        }));
        return fragmentView = content;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(tr("Типы чатов", "Chat types")));
        items.add(UItem.asCheck(ROW_PRIVATE, tr("Личные чаты", "Private chats")).setChecked(privateChats));
        items.add(UItem.asCheck(ROW_BOTS, tr("Чаты с ботами", "Bot chats")).setChecked(bots));
        items.add(UItem.asCheck(ROW_GROUPS, tr("Группы", "Groups")).setChecked(groups));
        items.add(UItem.asCheck(ROW_CHANNELS, tr("Каналы", "Channels")).setChecked(channels));

        items.add(UItem.asHeader(tr("Папки", "Folders")));
        items.add(UItem.asCheck(ROW_MAIN, tr("Основной список", "Main list")).setChecked(mainFolder));
        items.add(UItem.asCheck(ROW_ARCHIVE, tr("Архив", "Archive")).setChecked(archiveFolder));
        filterRows.clear();
        for (MessagesController.DialogFilter filter : getMessagesController().getDialogFilters()) {
            if (filter == null || filter.isDefault()) continue;
            int row = ROW_FILTER_BASE + filter.localId;
            filterRows.put(row, filter);
            items.add(UItem.asCheck(row, tr("Папка: ", "Folder: ") + filter.name)
                    .setChecked(selectedFilters.contains(filter.id)));
        }
        items.add(UItem.asShadow(tr(
                "Типы чатов и папки работают вместе: попадут только выбранные типы из выбранных папок.",
                "Chat types and folders are combined: only selected types from selected folders are exported.")));

        items.add(UItem.asHeader(tr("Медиа", "Media")));
        items.add(UItem.asCheck(ROW_PHOTOS, tr("Фотографии", "Photos")).setChecked(photos));
        items.add(UItem.asCheck(ROW_VIDEOS, tr("Видео и кружки", "Videos and video messages")).setChecked(videos));
        items.add(UItem.asCheck(ROW_FILES, tr("Файлы, голосовые и музыка", "Files, voice messages and music")).setChecked(files));
        items.add(UItem.asShadow(tr(
                "Медиа увеличивает время и размер архива. Для полного экспорта может понадобиться много свободного места.",
                "Media increases export time and archive size. A full export may require substantial free storage.")));

        items.add(UItem.asButton(ROW_START, tr("Создать HTML-экспорт", "Create HTML export")).accent());
        items.add(UItem.asShadow(tr(
                "В ZIP появится export_results.html со структурой и оформлением экспорта Telegram Desktop: список чатов, полные переписки и открываемые медиафайлы.",
                "The ZIP contains export_results.html with the Telegram Desktop export structure and styling: chats, full conversations and openable media.")));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        boolean handled = true;
        switch (item.id) {
            case ROW_PRIVATE: privateChats = !privateChats; break;
            case ROW_BOTS: bots = !bots; break;
            case ROW_GROUPS: groups = !groups; break;
            case ROW_CHANNELS: channels = !channels; break;
            case ROW_MAIN: mainFolder = !mainFolder; break;
            case ROW_ARCHIVE: archiveFolder = !archiveFolder; break;
            case ROW_PHOTOS: photos = !photos; break;
            case ROW_VIDEOS: videos = !videos; break;
            case ROW_FILES: files = !files; break;
            case ROW_START:
                startSelection();
                return;
            default:
                MessagesController.DialogFilter filter = filterRows.get(item.id);
                if (filter != null) {
                    if (!selectedFilters.add(filter.id)) selectedFilters.remove(filter.id);
                } else {
                    handled = false;
                }
        }
        if (handled) {
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(isChecked(item.id));
            if (listView != null && listView.adapter != null) listView.adapter.update(false);
        }
    }

    private boolean isChecked(int id) {
        if (id == ROW_PRIVATE) return privateChats;
        if (id == ROW_BOTS) return bots;
        if (id == ROW_GROUPS) return groups;
        if (id == ROW_CHANNELS) return channels;
        if (id == ROW_MAIN) return mainFolder;
        if (id == ROW_ARCHIVE) return archiveFolder;
        if (id == ROW_PHOTOS) return photos;
        if (id == ROW_VIDEOS) return videos;
        if (id == ROW_FILES) return files;
        MessagesController.DialogFilter filter = filterRows.get(id);
        return filter != null && selectedFilters.contains(filter.id);
    }

    private void startSelection() {
        if (!privateChats && !bots && !groups && !channels) {
            bulletin(tr("Выберите хотя бы один тип чатов", "Select at least one chat type"));
            return;
        }
        if (!mainFolder && !archiveFolder && selectedFilters.isEmpty()) {
            bulletin(tr("Выберите хотя бы одну папку", "Select at least one folder"));
            return;
        }
        beginDialogScan();
    }

    private void beginDialogScan() {
        if (getParentActivity() == null) return;
        dialogScanCancelled = false;
        dialogScanDeadline = System.currentTimeMillis() + 5L * 60L * 1000L;
        scannedDialogs.clear();
        scannedDialogIds.clear();
        dialogScanFolders.clear();
        if (mainFolder || !selectedFilters.isEmpty()) dialogScanFolders.add(0);
        if (archiveFolder || !selectedFilters.isEmpty()) dialogScanFolders.add(1);
        dialogScanFolderIndex = 0;
        dialogScanRequestId = 0;
        resetDialogScanOffset();
        dialogScan = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_LOADING, resourceProvider);
        dialogScan.setMessage(tr("Загрузка полного списка чатов…", "Loading the complete chat list..."));
        dialogScan.setCancelable(true);
        dialogScan.setCancelDialog(true);
        dialogScan.setOnCancelListener(dialog -> {
            dialogScanCancelled = true;
            if (dialogScanRequestId != 0) {
                getConnectionsManager().cancelRequest(dialogScanRequestId, true);
                dialogScanRequestId = 0;
            }
        });
        dialogScan.show();
        continueDialogScan();
    }

    private void continueDialogScan() {
        if (dialogScanCancelled || dialogScan == null || !dialogScan.isShowing()) return;
        if (dialogScanFolderIndex >= dialogScanFolders.size()) {
            dismiss(dialogScan);
            dialogScan = null;
            finishSelection();
            return;
        }
        if (System.currentTimeMillis() >= dialogScanDeadline) {
            if (dialogScanRequestId != 0) {
                getConnectionsManager().cancelRequest(dialogScanRequestId, true);
                dialogScanRequestId = 0;
            }
            dismiss(dialogScan);
            dialogScan = null;
            showError(tr(
                    "Не удалось получить полный список чатов. Проверьте интернет и попробуйте снова.",
                    "Could not load the complete chat list. Check your connection and try again."));
            return;
        }
        dialogScan.setMessage(tr("Загрузка полного списка чатов… Найдено: ",
                "Loading the complete chat list... Found: ") + scannedDialogs.size());
        if (dialogScanRequestId == 0) requestDialogPage();
        AndroidUtilities.runOnUIThread(this::continueDialogScan, 500);
    }

    private void requestDialogPage() {
        if (dialogScanFolderIndex >= dialogScanFolders.size()) return;
        int folderId = dialogScanFolders.get(dialogScanFolderIndex);
        TLRPC.TL_messages_getDialogs request = new TLRPC.TL_messages_getDialogs();
        request.limit = DIALOG_PAGE_SIZE;
        request.exclude_pinned = dialogScanExcludePinned;
        request.offset_id = dialogScanOffsetId;
        request.offset_date = dialogScanOffsetDate;
        request.offset_peer = dialogScanOffsetPeer == null
                ? new TLRPC.TL_inputPeerEmpty() : dialogScanOffsetPeer;
        if (folderId != 0) {
            request.flags |= 2;
            request.folder_id = folderId;
        }
        dialogScanRequestId = getConnectionsManager().sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> handleDialogPage(response, error)));
    }

    private void handleDialogPage(org.telegram.tgnet.TLObject response, TLRPC.TL_error error) {
        dialogScanRequestId = 0;
        if (dialogScanCancelled || dialogScan == null || !dialogScan.isShowing()) return;
        if (error != null || !(response instanceof TLRPC.messages_Dialogs)) {
            dismiss(dialogScan);
            dialogScan = null;
            String detail = error == null || error.text == null ? "" : "\n" + error.text;
            showError(tr("Не удалось загрузить полный список чатов.",
                    "Could not load the complete chat list.") + detail);
            return;
        }

        TLRPC.messages_Dialogs page = (TLRPC.messages_Dialogs) response;
        MessagesController controller = getMessagesController();
        boolean excludedPinnedOnRequest = dialogScanExcludePinned;
        boolean containsPinned = false;
        int addedDialogs = 0;
        controller.putUsers(page.users, false);
        controller.putChats(page.chats, false);
        for (TLRPC.Dialog dialog : page.dialogs) {
            // Dialog.id is a client-only field and is not serialized by MTProto. A raw
            // messages.getDialogs response therefore has id == 0 until it is initialized.
            DialogObject.initDialog(dialog);
            containsPinned |= dialog != null && dialog.pinned;
            if (dialog != null && scannedDialogIds.add(dialog.id)) {
                scannedDialogs.add(dialog);
                addedDialogs++;
            }
        }
        dialogScanFolderLoaded += addedDialogs;
        // Some Telegram servers return a short page containing only pinned dialogs first.
        // All following pages must explicitly exclude those pinned rows.
        dialogScanExcludePinned = true;

        boolean reachedCount = page.count > 0 && dialogScanFolderLoaded >= page.count;
        boolean shortPage = page.dialogs.size() < DIALOG_PAGE_SIZE;
        if (page.dialogs.isEmpty()
                || (excludedPinnedOnRequest || !containsPinned) && (shortPage || reachedCount)) {
            dialogScanFolderIndex++;
            resetDialogScanOffset();
            continueDialogScan();
            return;
        }
        if (excludedPinnedOnRequest && addedDialogs == 0) {
            dismiss(dialogScan);
            dialogScan = null;
            showError(tr("Telegram повторил ту же страницу чатов. Попробуйте экспорт ещё раз.",
                    "Telegram repeated the same dialog page. Please retry the export."));
            return;
        }

        TLRPC.Dialog offsetDialog = null;
        TLRPC.Message offsetMessage = null;
        for (int i = page.dialogs.size() - 1; i >= 0 && offsetMessage == null; i--) {
            TLRPC.Dialog candidate = page.dialogs.get(i);
            if (candidate == null || candidate.pinned || candidate.top_message <= 0) continue;
            TLRPC.Message fallbackMessage = null;
            for (TLRPC.Message message : page.messages) {
                if (message == null || message.id <= 0 || message.date <= 0
                        || MessageObject.getDialogId(message) != candidate.id) {
                    continue;
                }
                if (message.id == candidate.top_message) {
                    offsetDialog = candidate;
                    offsetMessage = message;
                    break;
                }
                if (fallbackMessage == null || message.date > fallbackMessage.date) {
                    fallbackMessage = message;
                }
            }
            // Some service/deleted top messages are omitted from the response. Any dated
            // message returned for the same last dialog is still a valid pagination anchor.
            if (offsetMessage == null && fallbackMessage != null) {
                offsetDialog = candidate;
                offsetMessage = fallbackMessage;
            }
        }

        // A pinned-only first response has no regular-dialog offset. Probe the regular list
        // from its beginning instead of mistaking the pinned count for the complete account.
        if ((offsetDialog == null || offsetMessage == null)
                && !excludedPinnedOnRequest && containsPinned) {
            dialogScanOffsetId = 0;
            dialogScanOffsetDate = 0;
            dialogScanOffsetPeer = new TLRPC.TL_inputPeerEmpty();
            requestDialogPage();
            return;
        }

        TLRPC.InputPeer offsetPeer = offsetDialog == null ? null : controller.getInputPeer(offsetDialog.id);
        // Deleted/service top messages can be omitted. The oldest dated response message is
        // still a valid MTProto pagination tuple.
        if (offsetMessage == null || offsetPeer instanceof TLRPC.TL_inputPeerEmpty) {
            offsetMessage = null;
            offsetPeer = null;
            for (TLRPC.Message message : page.messages) {
                if (message == null || message.id <= 0 || message.date <= 0) continue;
                TLRPC.InputPeer peer = controller.getInputPeer(MessageObject.getDialogId(message));
                if (peer instanceof TLRPC.TL_inputPeerEmpty) continue;
                if (offsetMessage == null || message.date < offsetMessage.date) {
                    offsetMessage = message;
                    offsetPeer = peer;
                }
            }
        }

        // Last resort for a dialog whose top message is not present in page.messages.
        if (offsetMessage == null || offsetPeer == null) {
            for (int i = page.dialogs.size() - 1; i >= 0; i--) {
                TLRPC.Dialog candidate = page.dialogs.get(i);
                if (candidate == null || candidate.pinned || candidate.top_message <= 0) continue;
                TLRPC.InputPeer peer = controller.getInputPeer(candidate.id);
                if (peer instanceof TLRPC.TL_inputPeerEmpty) continue;
                dialogScanOffsetId = candidate.top_message;
                dialogScanOffsetDate = Math.max(0, candidate.last_message_date);
                dialogScanOffsetPeer = peer;
                requestDialogPage();
                return;
            }
        }

        if (offsetMessage == null || offsetPeer == null) {
            dismiss(dialogScan);
            dialogScan = null;
            showError(tr("Telegram не вернул смещение для следующей страницы чатов.",
                    "Telegram did not return an offset for the next dialog page."));
            return;
        }
        dialogScanOffsetId = offsetMessage.id;
        dialogScanOffsetDate = offsetMessage.date;
        dialogScanOffsetPeer = offsetPeer;
        requestDialogPage();
    }

    private void resetDialogScanOffset() {
        dialogScanOffsetId = 0;
        dialogScanOffsetDate = 0;
        dialogScanOffsetPeer = new TLRPC.TL_inputPeerEmpty();
        dialogScanFolderLoaded = 0;
        dialogScanExcludePinned = false;
    }

    private void finishSelection() {
        ArrayList<LumaAccountExportManager.ChatSpec> chats = collectChats();
        if (chats.isEmpty()) {
            bulletin(tr("Под выбранные условия не найдено чатов", "No chats match the selected options"));
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle(tr("Начать экспорт?", "Start export?"));
        builder.setMessage(tr(
                "Будет экспортировано чатов: " + chats.size() + ". Не закрывайте LumaGram; для большого аккаунта это может занять долгое время и потребовать много памяти.",
                "Chats to export: " + chats.size() + ". Keep LumaGram open; a large account may take a long time and substantial storage."));
        builder.setPositiveButton(tr("Начать", "Start"), (dialog, which) -> startExport(chats));
        builder.setNegativeButton(tr("Отмена", "Cancel"), null);
        showDialog(builder.create());
    }

    private ArrayList<LumaAccountExportManager.ChatSpec> collectChats() {
        ArrayList<LumaAccountExportManager.ChatSpec> result = new ArrayList<>();
        AccountInstance account = AccountInstance.getInstance(currentAccount);
        for (TLRPC.Dialog dialog : scannedDialogs) {
            if (dialog == null || DialogObject.isFolderDialogId(dialog.id)
                    || DialogObject.isEncryptedDialog(dialog.id)) continue;
            String type;
            String title;
            boolean protectedContent = false;
            if (DialogObject.isUserDialog(dialog.id)) {
                TLRPC.User user = getMessagesController().getUser(dialog.id);
                if (user == null) continue;
                if (user.bot) {
                    if (!bots) continue;
                    type = tr("Бот", "Bot");
                } else {
                    if (!privateChats) continue;
                    type = tr("Личный чат", "Private chat");
                }
                title = user.self ? tr("Избранное", "Saved Messages") : UserObject.getUserName(user);
            } else if (DialogObject.isChatDialog(dialog.id)) {
                TLRPC.Chat chat = getMessagesController().getChat(-dialog.id);
                if (chat == null) continue;
                protectedContent = chat.noforwards;
                if (ChatObject.isChannel(chat) && !chat.megagroup) {
                    if (!channels) continue;
                    type = tr("Канал", "Channel");
                } else {
                    if (!groups) continue;
                    type = tr("Группа", "Group");
                }
                title = chat.title;
            } else {
                continue;
            }
            boolean folderMatch = mainFolder && dialog.folder_id == 0
                    || archiveFolder && dialog.folder_id == 1;
            if (!folderMatch) {
                for (MessagesController.DialogFilter filter : getMessagesController().getDialogFilters()) {
                    if (filter != null && selectedFilters.contains(filter.id)
                            && filter.includesDialog(account, dialog.id, dialog)) {
                        folderMatch = true;
                        break;
                    }
                }
            }
            if (folderMatch) result.add(new LumaAccountExportManager.ChatSpec(
                    dialog.id, title, type, protectedContent));
        }
        return result;
    }

    private void startExport(ArrayList<LumaAccountExportManager.ChatSpec> chats) {
        if (getParentActivity() == null) return;
        LumaAccountExportManager.Config config = new LumaAccountExportManager.Config();
        config.account = currentAccount;
        TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
        config.accountName = self == null ? "Telegram" : UserObject.getUserName(self);
        config.includePhotos = photos;
        config.includeVideos = videos;
        config.includeFiles = files;
        config.chats.addAll(chats);

        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_LOADING, resourceProvider);
        progress.setMessage(tr("Подготовка экспорта…", "Preparing export..."));
        progress.setCancelable(true);
        progress.setCancelDialog(true);
        progress.setOnCancelListener(dialog -> {
            if (manager != null) manager.cancel();
        });

        manager = new LumaAccountExportManager(config, new LumaAccountExportManager.Listener() {
            @Override
            public void onProgress(LumaAccountExportManager.Progress value) {
                if (!progress.isShowing()) return;
                progress.setProgress(value.getPercent());
                if (value.packaging) {
                    progress.setMessage(tr("Сборка общего ZIP-архива…", "Building the final ZIP archive..."));
                } else {
                    progress.setMessage(tr("Чат ", "Chat ") + (value.chatIndex + 1) + " / " + value.chatCount
                            + "\n" + value.chatTitle + "\n" + tr("Сообщений: ", "Messages: ") + value.messages);
                }
            }

            @Override
            public void onComplete(File archive, int exportedChats, int skippedChats, int messages, int mediaFiles) {
                dismiss(progress);
                manager = null;
                if (getParentActivity() == null) {
                    //noinspection ResultOfMethodCallIgnored
                    archive.delete();
                    return;
                }
                MediaController.saveFile(archive.getAbsolutePath(), getParentActivity(), 2,
                        archive.getName(), "application/zip", uri -> {
                            // The user-facing copy now lives in Downloads/Telegram. Do not keep a
                            // second plaintext export in the app's private cache.
                            //noinspection ResultOfMethodCallIgnored
                            archive.delete();
                            showComplete(exportedChats, skippedChats, messages, mediaFiles);
                        });
            }

            @Override
            public void onError(String message, Throwable error) {
                dismiss(progress);
                manager = null;
                if (error != null) FileLog.e(error);
                showError(message);
            }

            @Override
            public void onCancelled() {
                dismiss(progress);
                manager = null;
            }
        });
        progress.show();
        manager.start();
    }

    private void showComplete(int chats, int skipped, int messages, int media) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle(tr("Экспорт готов", "Export ready"));
        builder.setMessage(tr(
                "ZIP сохранён в «Загрузки/Telegram». Распакуйте его и откройте export_results.html.\n\nЧатов: " + chats + "\nПропущено: " + skipped + "\nСообщений: " + messages + "\nМедиафайлов: " + media,
                "The ZIP was saved to Downloads/Telegram. Extract it and open export_results.html.\n\nChats: " + chats + "\nSkipped: " + skipped + "\nMessages: " + messages + "\nMedia files: " + media));
        builder.setPositiveButton("OK", null);
        showDialog(builder.create());
    }

    private void showError(String message) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle(tr("Ошибка экспорта", "Export error"));
        builder.setMessage(message);
        builder.setPositiveButton("OK", null);
        showDialog(builder.create());
    }

    private void bulletin(String text) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show();
    }

    private static void dismiss(AlertDialog dialog) {
        try {
            dialog.dismiss();
        } catch (Throwable ignore) {
        }
    }

    private static String tr(String russian, String english) {
        Locale locale = org.telegram.messenger.LocaleController.getInstance().getCurrentLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage()) ? russian : english;
    }
}
