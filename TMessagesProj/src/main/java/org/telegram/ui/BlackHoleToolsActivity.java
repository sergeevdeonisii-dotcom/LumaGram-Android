package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BlackHoleNotes;
import org.telegram.messenger.BlackHoleNotificationJournal;
import org.telegram.messenger.BlackHolePrivateData;
import org.telegram.messenger.BlackHoleSettings;
import org.telegram.messenger.BlackHoleVault;
import org.telegram.messenger.LumaBuildPolicy;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LumaDialogInput;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

public final class BlackHoleToolsActivity extends BaseFragment {
    public static final int PROFILES = 1, NOTES = 2, JOURNAL = 3, TRANSFER = 4, VAULT = 5;
    private static final int ADD = 10, CLEAR = 11, ENABLED = 12, SAVE_PROFILE = 13,
            EXPORT = 14, IMPORT = 15, UNLOCK = 16, LOCK = 17, SETUP = 18;
    private static final int CREATE_FILE = 7181, OPEN_FILE = 7182;
    private final int page;
    private final long initialDialog;
    private UniversalRecyclerView listView;
    private ArrayList<BlackHoleNotificationJournal.Entry> journal = new ArrayList<>();
    private String exportText;
    private long owner;
    private boolean busy, openedInitial;
    private int journalLoadGeneration;

    public BlackHoleToolsActivity(int page) { this(page, 0); }
    public BlackHoleToolsActivity(int page, long initialDialog) { this.page = page; this.initialDialog = initialDialog; }
    @Override public boolean onFragmentCreate() {
        owner = UserConfig.getInstance(currentAccount).getClientUserId(); return owner > 0 && super.onFragmentCreate();
    }
    private boolean currentOwner() { return !isFinished && owner > 0 && owner == UserConfig.getInstance(currentAccount).getClientUserId(); }
    private boolean privatePage() { return page == VAULT || page == NOTES || page == JOURNAL; }
    private boolean needsUnlock() {
        return privatePage() && (page == VAULT || !SharedConfig.passcodeHash.isEmpty() || BlackHoleVault.hasDialogs(currentAccount))
                && !BlackHoleVault.isUnlocked(currentAccount);
    }
    private int title() {
        switch (page) {
            case PROFILES: return R.string.BHGProfiles;
            case NOTES: return R.string.BHGNotes;
            case JOURNAL: return R.string.BHGJournal;
            case TRANSFER: return R.string.BHGTransfer;
            default: return R.string.BHGVault;
        }
    }
    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(title()));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        listView = new UniversalRecyclerView(this, this::fill, this::click, this::longClick);
        listView.setSections(); root.addView(listView, LayoutHelper.createFrame(-1, -1, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        return fragmentView = root;
    }
    private void fill(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (needsUnlock()) {
            items.add(UItem.asButton(UNLOCK, R.drawable.msg_secret, getString(R.string.BHGUnlock)));
            items.add(UItem.asShadow(getString(R.string.BHGVaultLockInfo)));
            if (SharedConfig.passcodeHash.isEmpty()) items.add(UItem.asButton(SETUP, getString(R.string.BHGSetPasscode)));
            return;
        }
        if (page == PROFILES) {
            for (int id : profileIds()) {
                boolean selected = false;
                try { selected = BlackHoleSettings.capture(currentAccount).equals(BlackHoleSettings.profile(currentAccount, id)); } catch (Exception e) { FileLog.e(e); }
                items.add(UItem.asRadio(100 + id, profileName(id), getString(R.string.BHGApplyProfile)).setChecked(selected));
            }
            items.add(UItem.asShadow(getString(LumaBuildPolicy.isFriendsEdition() ? R.string.LumaFriendsProfilesInfo : R.string.BHGProfilesInfo)));
            items.add(UItem.asButton(SAVE_PROFILE, R.drawable.msg_saved, getString(R.string.BHGSaveProfile)));
        } else if (page == TRANSFER) {
            items.add(UItem.asButton(EXPORT, R.drawable.msg_saved, getString(R.string.BHGExport)).setEnabled(!busy));
            items.add(UItem.asButton(IMPORT, R.drawable.msg_download, getString(R.string.BHGImport)).setEnabled(!busy));
            items.add(UItem.asShadow(getString(R.string.BHGTransferInfo)));
        } else if (page == JOURNAL) {
            items.add(UItem.asCheck(ENABLED, getString(R.string.BHGJournalEnable))
                    .setChecked(BlackHoleNotificationJournal.isEnabled(currentAccount)).setEnabled(BlackHolePrivateData.isAvailable()));
            items.add(UItem.asShadow(getString(R.string.BHGJournalInfo)));
            items.add(UItem.asButton(CLEAR, R.drawable.msg_delete, getString(R.string.BHGJournalClear)).red());
            for (int n = 0; n < journal.size(); n++) {
                BlackHoleNotificationJournal.Entry e = journal.get(n);
                items.add(UItem.asHeader(e.title));
                UItem row = TextDetailCell.Factory.of(200 + n, e.text, org.telegram.messenger.LocaleController.formatDateAudio(e.date / 1000, true));
                row.object = e;
                items.add(row);
            }
            if (journal.isEmpty()) items.add(UItem.asShadow(getString(R.string.BHGJournalEmpty)));
        } else {
            items.add(UItem.asButton(ADD, R.drawable.msg_add, getString(page == VAULT ? R.string.BHGVaultAdd : R.string.BHGNoteAdd))
                    .setEnabled(page == VAULT || BlackHolePrivateData.isAvailable()));
            items.add(UItem.asShadow(getString(page == VAULT ? R.string.BHGVaultInfo : R.string.BHGNotesInfo)));
            ArrayList<Long> ids = new ArrayList<>(page == VAULT ? BlackHoleVault.dialogs(currentAccount) : BlackHoleNotes.dialogs(currentAccount));
            Collections.sort(ids);
            for (int n = 0; n < ids.size(); n++) {
                long did = ids.get(n);
                UItem row = UItem.asButton(300 + n, page == VAULT ? R.drawable.msg_secret : R.drawable.msg_edit, dialogTitle(currentAccount, did));
                row.object = did; items.add(row);
            }
            if (ids.isEmpty()) items.add(UItem.asShadow(getString(page == VAULT ? R.string.BHGVaultEmpty : R.string.BHGNotesEmpty)));
            if (page == VAULT) items.add(UItem.asButton(LOCK, R.drawable.msg_secret, getString(R.string.BHGVaultLock)));
        }
        if (privatePage() && !BlackHolePrivateData.isAvailable() && page != VAULT)
            items.add(UItem.asShadow(getString(R.string.BHGKeystoreUnavailable)));
    }
    private void click(UItem item, View view, int position, float x, float y) {
        if (!currentOwner()) return;
        if (item.id == UNLOCK) { unlock(); return; }
        if (item.id == SETUP) { presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE)); return; }
        if (needsUnlock()) return;
        if (page == PROFILES && item.id >= 100 && item.id < 103) { applyProfile(this, item.id - 100); refresh(); }
        else if (item.id == SAVE_PROFILE) showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.BHGSaveProfile)).setItems(profileNames(), (d, which) -> {
                    if (!currentOwner()) return;
                    try { BlackHoleSettings.saveProfile(currentAccount, profileIds()[which]); done(); } catch (Exception e) { error(e); }
                }).setNegativeButton(getString(R.string.Cancel), null).create());
        else if (item.id == EXPORT) exportSettings();
        else if (item.id == IMPORT) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(intent, OPEN_FILE); } catch (Exception e) { error(e); }
        } else if (item.id == ENABLED) {
            BlackHoleNotificationJournal.setEnabled(currentAccount, !BlackHoleNotificationJournal.isEnabled(currentAccount)); refresh();
        } else if (item.id == CLEAR) confirm(getString(R.string.BHGJournalClear), () -> {
            journalLoadGeneration++;
            BlackHoleNotificationJournal.clear(currentAccount); journal.clear(); refresh();
        });
        else if (item.id == ADD) pickDialog();
        else if (item.id == LOCK) { BlackHoleVault.lockAll(); refresh(); }
        else if (item.object instanceof Long) {
            long did = (Long) item.object;
            if (page == VAULT) openChat(did); else editNote(did);
        } else if (page == JOURNAL && item.object instanceof BlackHoleNotificationJournal.Entry) {
            BlackHoleNotificationJournal.Entry entry = (BlackHoleNotificationJournal.Entry) item.object;
            showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider).setTitle(entry.title).setMessage(entry.text)
                    .setPositiveButton(getString(R.string.OK), null).create());
        }
    }
    private boolean longClick(UItem item, View view, int position, float x, float y) {
        if (!currentOwner() || needsUnlock() || !(item.object instanceof Long)) return false;
        long did = (Long) item.object;
        if (page == NOTES) {
            confirm(getString(R.string.Delete), () -> {
                try { BlackHoleNotes.save(currentAccount, did, ""); refresh(); } catch (Exception e) { error(e); }
            }); return true;
        }
        if (page != VAULT) return false;
        confirm(getString(R.string.BHGVaultRemove), () -> { BlackHoleVault.setProtected(currentAccount, did, false); reloadDialogs(); refresh(); });
        return true;
    }
    private void unlock() {
        if (SharedConfig.passcodeHash.isEmpty()) {
            presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE)); return;
        }
        BlackHoleUnlockActivity screen = new BlackHoleUnlockActivity(() -> { if (currentOwner()) { loadJournal(); refresh(); openInitial(); } });
        screen.setCurrentAccount(currentAccount); presentFragment(screen);
    }
    private void refresh() { if (listView != null) { listView.setVisibility(View.VISIBLE); listView.adapter.update(true); } }
    private void loadJournal() {
        final int generation = ++journalLoadGeneration;
        if (page != JOURNAL || needsUnlock()) { journal.clear(); return; }
        final long expectedOwner = owner;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (UserConfig.getInstance(currentAccount).getClientUserId() != expectedOwner) return;
                ArrayList<BlackHoleNotificationJournal.Entry> data = BlackHoleNotificationJournal.entries(currentAccount);
                AndroidUtilities.runOnUIThread(() -> {
                    if (generation == journalLoadGeneration && !isPaused() && currentOwner() && !needsUnlock()) {
                        journal = data; refresh();
                    }
                });
            } catch (Exception e) { AndroidUtilities.runOnUIThread(() -> {
                if (generation == journalLoadGeneration && !isPaused() && currentOwner()) error(e);
            }); }
        });
    }
    private void pickDialog() {
        Bundle args = new Bundle(); args.putBoolean("onlySelect", true); args.putBoolean("checkCanWrite", false);
        args.putBoolean("allowGlobalSearch", false); args.putBoolean("allowSwitchAccount", false);
        DialogsActivity picker = new DialogsActivity(args); picker.setCurrentAccount(currentAccount);
        picker.setDelegate((fragment, ids, message, param, notify, date, repeat, topics) -> {
            if (!currentOwner() || needsUnlock() || ids.isEmpty()) return true;
            long did = ids.get(0).dialogId; picker.finishFragment();
            if (page == VAULT) protectDialog(did); else AndroidUtilities.runOnUIThread(() -> editNote(did), 250);
            return true;
        }); presentFragment(picker);
    }
    private void protectDialog(long did) {
        if (!currentOwner() || needsUnlock()) return;
        BlackHoleVault.setProtected(currentAccount, did, true);
        getParentActivity().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        NotificationsController.getInstance(currentAccount).removeNotificationsForDialog(did);
        Utilities.globalQueue.postRunnable(() -> {
            if (UserConfig.getInstance(currentAccount).getClientUserId() != owner) return;
            try { BlackHoleNotificationJournal.removeDialog(currentAccount, did); } catch (Exception e) { FileLog.e(e); }
        });
        reloadDialogs(); refresh();
    }
    public static String dialogTitle(int account, long id) {
        MessagesController mc = MessagesController.getInstance(account);
        if (DialogObject.isEncryptedDialog(id)) {
            TLRPC.EncryptedChat chat = mc.getEncryptedChat((int) id);
            return chat == null ? getString(R.string.SecretChat) : UserObject.getUserName(mc.getUser(chat.user_id));
        }
        if (id > 0) return UserObject.getUserName(mc.getUser(id));
        TLRPC.Chat chat = mc.getChat(-id); return chat == null ? getString(R.string.BHGUnknownChat) : chat.title;
    }
    private void editNote(long did) {
        if (!currentOwner() || needsUnlock() || BlackHoleVault.blocks(currentAccount, did)) return;
        try {
            EditTextBoldCursor field = LumaDialogInput.create(getParentActivity(),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    true, resourceProvider);
            field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(BlackHoleNotes.MAX_LENGTH)});
            field.setMinLines(3); field.setMaxLines(8);
            field.setText(BlackHoleNotes.get(currentAccount, did)); field.setSelection(field.length());
            showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider).setTitle(dialogTitle(currentAccount, did))
                    .setMessage(getString(R.string.BHGNotesInfo)).setView(LumaDialogInput.wrap(field))
                    .setPositiveButton(getString(R.string.Save), (d, w) -> {
                        if (!currentOwner() || needsUnlock()) return;
                        try { BlackHoleNotes.save(currentAccount, did, field.getText().toString()); refresh(); } catch (Exception e) { error(e); }
                    }).setNegativeButton(getString(R.string.Cancel), null).create());
        } catch (Exception e) { error(e); }
    }
    private void openInitial() {
        if (initialDialog == 0 || openedInitial || needsUnlock()) return;
        openedInitial = true;
        if (page == NOTES) editNote(initialDialog); else if (page == VAULT && BlackHoleVault.contains(currentAccount, initialDialog)) openChat(initialDialog);
    }
    private void openChat(long did) {
        if (BlackHoleVault.blocks(currentAccount, did)) return;
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(did)) args.putInt("enc_id", (int) did);
        else if (did > 0) args.putLong("user_id", did); else args.putLong("chat_id", -did);
        ChatActivity chat = new ChatActivity(args); chat.setCurrentAccount(currentAccount); presentFragment(chat);
    }
    private void exportSettings() {
        try {
            exportText = BlackHoleSettings.encode(BlackHoleSettings.capture(currentAccount));
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, "Lunagram-settings.json");
            startActivityForResult(intent, CREATE_FILE);
        } catch (Exception e) { exportText = null; error(e); }
    }
    @Override public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != CREATE_FILE && requestCode != OPEN_FILE) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || !currentOwner()) { exportText = null; return; }
        final Uri uri = data.getData(); final String content = exportText; exportText = null; busy = true; refresh();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (UserConfig.getInstance(currentAccount).getClientUserId() != owner) return;
                if (requestCode == CREATE_FILE) {
                    if (content == null) throw new IllegalStateException("no export snapshot");
                    try (OutputStream output = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new IllegalStateException("file unavailable"); output.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    AndroidUtilities.runOnUIThread(() -> { busy = false; if (currentOwner()) { refresh(); done(); } });
                } else {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try (InputStream input = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IllegalStateException("file unavailable");
                        byte[] bytes = new byte[4096]; int count;
                        while ((count = input.read(bytes)) != -1) {
                            if (buffer.size() + count > BlackHoleSettings.MAX_FILE_BYTES) throw new IllegalArgumentException("file too large");
                            buffer.write(bytes, 0, count);
                        }
                    }
                    Map<String, Object> values = BlackHoleSettings.decode(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                    AndroidUtilities.runOnUIThread(() -> {
                        busy = false; if (!currentOwner()) return; refresh();
                        confirm(getString(R.string.BHGImportConfirm), () -> {
                            try { BlackHoleSettings.apply(currentAccount, values); reloadDialogs(); done(); } catch (Exception e) { error(e); }
                        });
                    });
                }
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> { busy = false; if (currentOwner()) { refresh(); error(e); } });
            }
        });
    }
    private void confirm(String text, Runnable action) {
        showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider).setTitle(getString(title())).setMessage(text)
                .setPositiveButton(getString(R.string.OK), (d, w) -> { if (currentOwner() && !needsUnlock()) action.run(); })
                .setNegativeButton(getString(R.string.Cancel), null).create());
    }
    private void reloadDialogs() { getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload); }
    private void done() { BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.BHGDone)).show(); }
    private void error(Exception e) { FileLog.e(e); if (getParentActivity() != null) BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.BHGOperationFailed)).show(); }
    @Override public void onResume() {
        super.onResume(); if (!currentOwner()) { finishFragment(); return; }
        loadJournal(); refresh(); if (!needsUnlock()) AndroidUtilities.runOnUIThread(this::openInitial, 250);
    }
    @Override public void onPause() {
        journalLoadGeneration++;
        if (privatePage()) {
            if (listView != null) listView.setVisibility(View.INVISIBLE);
            dismissCurrentDialog();
        }
        super.onPause();
    }
    public static String profileName(int id) { return getString(id == 1 ? R.string.BHGProfileGhost : id == 2 ? R.string.BHGProfileWork : R.string.BHGProfileNormal); }
    private static int[] profileIds() {
        return LumaBuildPolicy.allowsPrivacyTools() ? new int[]{0, 1, 2} : new int[]{0, 2};
    }
    private static String[] profileNames() {
        int[] ids = profileIds();
        String[] names = new String[ids.length];
        for (int n = 0; n < ids.length; n++) names[n] = profileName(ids[n]);
        return names;
    }
    public static void showProfiles(BaseFragment host) {
        final long owner = UserConfig.getInstance(host.getCurrentAccount()).getClientUserId();
        host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider()).setTitle(getString(R.string.BHGProfiles))
                .setItems(profileNames(), (d, id) -> {
                    if (!host.isFinished && owner > 0 && owner == UserConfig.getInstance(host.getCurrentAccount()).getClientUserId()) applyProfile(host, profileIds()[id]);
                }).setNegativeButton(getString(R.string.Cancel), null).create());
    }
    private static void applyProfile(BaseFragment host, int id) {
        try {
            BlackHoleSettings.apply(host.getCurrentAccount(), BlackHoleSettings.profile(host.getCurrentAccount(), id));
            NotificationCenter.getInstance(host.getCurrentAccount()).postNotificationName(NotificationCenter.dialogsNeedReload);
            BulletinFactory.of(host).createSimpleBulletin(R.raw.info, getString(R.string.BHGDone)).show();
        } catch (Exception e) { FileLog.e(e); BulletinFactory.of(host).createSimpleBulletin(R.raw.info, getString(R.string.BHGOperationFailed)).show(); }
    }
}
