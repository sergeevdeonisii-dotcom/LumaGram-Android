package org.telegram.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramMediaAccess;
import org.telegram.messenger.LunagramStickerList;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LumaDialogInput;
import org.telegram.ui.Components.StickersAlert;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/** Preview-first transfer of a list; installing each pack remains a separate Telegram confirmation. */
public final class LunagramStickerListActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final LunagramMediaAccess.Session session;
    private UniversalRecyclerView list;
    private List<LunagramStickerList.Pack> imported = new ArrayList<>();
    public LunagramStickerListActivity(int account) { setCurrentAccount(account); session = LunagramMediaAccess.forAccount(account); }
    private boolean valid() { return !isFinished && currentAccount == UserConfig.selectedAccount && session.canUse(LunagramFeatureCatalog.Feature.LINK_TOOLS); }
    @Override public boolean onFragmentCreate() {
        if (!valid()) return false;
        getNotificationCenter().addObserver(this, NotificationCenter.stickersDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        getMediaDataController().checkStickers(MediaDataController.TYPE_IMAGE);
        getMediaDataController().checkStickers(MediaDataController.TYPE_EMOJIPACKS);
        return super.onFragmentCreate();
    }
    @Override public View createView(Context context) {
        actionBar.setTitle(s(R.string.LulStickerList)); actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() { @Override public void onItemClick(int id) { if (id == -1) finishFragment(); } });
        FrameLayout root = new FrameLayout(context); root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        list = new UniversalRecyclerView(this, this::fill, this::click, null); list.setSections();
        root.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(list); return fragmentView = root;
    }
    private void fill(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(1, R.drawable.msg_copy, s(R.string.LulStickerExport)));
        items.add(UItem.asButton(2, R.drawable.msg_download, s(R.string.LulStickerImport)));
        items.add(UItem.asShadow(s(R.string.LulStickerInfo)));
        if (!imported.isEmpty()) {
            items.add(UItem.asHeader(s(R.string.LulStickerPreview)));
            for (int i = 0; i < imported.size(); i++) {
                LunagramStickerList.Pack pack = imported.get(i);
                items.add(UItem.asButton(100 + i, pack.name, pack.emoji ? s(R.string.Emoji) : s(R.string.StickersName)));
            }
            items.add(UItem.asShadow(s(R.string.LulStickerOpenInfo)));
        }
    }
    private void click(UItem item, View view, int position, float x, float y) {
        if (!valid() || getParentActivity() == null) return;
        if (item.id == 1) export(); else if (item.id == 2) importForm();
        else if (item.id >= 100 && item.id - 100 < imported.size()) {
            LunagramStickerList.Pack pack = imported.get(item.id - 100);
            TLRPC.TL_inputStickerSetShortName input = new TLRPC.TL_inputStickerSetShortName(); input.short_name = pack.name;
            showDialog(new StickersAlert(getParentActivity(), this, input, null, null, getResourceProvider(), true));
        }
    }
    private void export() {
        if (!getMediaDataController().areStickersLoaded(MediaDataController.TYPE_IMAGE)
                || !getMediaDataController().areStickersLoaded(MediaDataController.TYPE_EMOJIPACKS)) {
            notice(R.string.LulStickerLoading); return;
        }
        ArrayList<LunagramStickerList.Pack> packs = new ArrayList<>();
        for (int type : new int[]{MediaDataController.TYPE_IMAGE, MediaDataController.TYPE_EMOJIPACKS})
            for (TLRPC.TL_messages_stickerSet set : getMediaDataController().getStickerSets(type)) {
                if (set == null || set.set == null || set.set.archived || set.set.short_name == null || set.set.short_name.isEmpty()) continue;
                try { packs.add(new LunagramStickerList.Pack(set.set.short_name, type == MediaDataController.TYPE_EMOJIPACKS)); }
                catch (IllegalArgumentException invalid) { notice(R.string.LulStickerInvalid); return; }
            }
        try {
            final String text = LunagramStickerList.format(packs);
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(s(R.string.LulStickerExport))
                    .setMessage(LocaleController.formatString(R.string.LulStickerCount, packs.size()) + "\n\n" + s(R.string.LulStickerClipboard))
                    .setPositiveButton(s(R.string.Copy), (d, which) -> { if (valid()) AndroidUtilities.addToClipboard(text); })
                    .setNegativeButton(s(R.string.Cancel), null).create());
        } catch (IllegalArgumentException invalid) { notice(R.string.LulStickerInvalid); }
    }
    private void importForm() {
        EditTextBoldCursor input = LumaDialogInput.create(getParentActivity(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, true, getResourceProvider());
        input.setHint(s(R.string.LulStickerPaste)); input.setMaxLines(6); input.setMinLines(3);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LunagramStickerList.MAX_TEXT)});
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(s(R.string.LulStickerImport))
                .setView(LumaDialogInput.wrap(input)).setMessage(s(R.string.LulStickerOpenInfo))
                .setPositiveButton(s(R.string.LulStickerPreview), null).setNegativeButton(s(R.string.Cancel), null).create();
        if (showDialog(dialog) == null || dialog.getButton(AlertDialog.BUTTON_POSITIVE) == null) return;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!valid()) { dialog.dismiss(); return; }
            try {
                List<LunagramStickerList.Pack> parsed = LunagramStickerList.parse(input.getText().toString());
                if (parsed.isEmpty()) { input.setError(s(R.string.LulStickerInvalid)); return; }
                imported = parsed; list.adapter.update(false); dialog.dismiss();
            } catch (IllegalArgumentException invalid) { input.setError(s(R.string.LulStickerInvalid)); }
        });
    }
    private void notice(int text) { showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setMessage(s(text)).setPositiveButton(s(R.string.OK), null).create()); }
    @Override public void didReceivedNotification(int id, int account, Object... args) { if (!valid()) finishFragment(); else if (list != null) list.adapter.update(false); }
    @Override public void onResume() { super.onResume(); if (!valid()) finishFragment(); }
    @Override public void onFragmentDestroy() { imported.clear(); getNotificationCenter().removeObserver(this, NotificationCenter.stickersDidLoad); getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout); super.onFragmentDestroy(); }
    private static String s(int id) { return LocaleController.getString(id); }
}
