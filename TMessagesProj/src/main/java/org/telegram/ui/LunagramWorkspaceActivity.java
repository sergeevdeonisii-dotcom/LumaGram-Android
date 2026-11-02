package org.telegram.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramWorkspaceModel;
import org.telegram.messenger.LunagramWorkspaceModel.Document;
import org.telegram.messenger.LunagramWorkspaceModel.Entry;
import org.telegram.messenger.LunagramWorkspaceModel.Kind;
import org.telegram.messenger.LunagramWorkspaceStore;
import org.telegram.messenger.LunagramWorkspaceStore.Session;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LunagramTextSelectionGuard;
import org.telegram.ui.Components.LunagramTextToolsSheet;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;

/** Native, explicit local tools. Parent owns the weak Host and all Telegram navigation/draft actions. */
public final class LunagramWorkspaceActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    public interface Host {
        boolean canUseContext();
        String getCurrentDraft();
        LunagramTextToolsSheet.Apply captureDraftApply();
        void openMessage(long dialogId, int messageId);
        void search(String query);
    }

    private final Session session;
    private final WeakReference<Host> host;
    private final int messageId;
    private final Kind category;
    private final String entryId;
    private final boolean editing;
    private UniversalRecyclerView list;
    private LinearLayout content;
    private EditTextBoldCursor nameInput, textInput, collectionInput;
    private final ArrayList<Entry> visible = new ArrayList<>();
    private Document document;
    private String loadError;
    private String previewDraft = "";
    private LunagramTextToolsSheet.Apply draftApply;
    private boolean draftPreviewCaptured;
    private boolean linkMessage;

    public static void open(BaseFragment parent, int account, long dialogId, int messageId,
            boolean protectedContext, String currentDraft, Host host) {
        if (parent == null || parent.getParentActivity() == null || host == null) return;
        WeakReference<Host> reference = new WeakReference<>(host);
        Session session = new Session(account, dialogId, protectedContext, () -> {
            Host value = reference.get();
            return value != null && value.canUseContext();
        });
        if (!session.isCurrent()) {
            new AlertDialog.Builder(parent.getParentActivity(), parent.getResourceProvider())
                    .setTitle(s(R.string.LunagramWorkspaceTitle)).setMessage(s(R.string.LunagramWorkspaceLocked))
                    .setPositiveButton(s(R.string.OK), null).show();
            return;
        }
        // currentDraft is intentionally not persisted/captured: saving requests a fresh draft from Host.
        parent.presentFragment(new LunagramWorkspaceActivity(session, reference, messageId, null, null, false));
    }

    private LunagramWorkspaceActivity(Session session, WeakReference<Host> host, int messageId,
            Kind category, String entryId, boolean editing) {
        this.session = session; this.host = host; this.messageId = messageId;
        this.category = category; this.entryId = entryId; this.editing = editing;
        setCurrentAccount(session.account);
    }
    @Override public boolean onFragmentCreate() {
        if (!session.isCurrent()) return false;
        getNotificationCenter().addObserver(this, NotificationCenter.lunagramSubscriptionChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        return super.onFragmentCreate();
    }
    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(category == null ? s(R.string.LunagramWorkspaceTitle) : title(category));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        reload();
        if (editing || entryId != null) {
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            fragmentView = scroll;
            if (editing) editor(context); else preview(context);
        } else {
            FrameLayout frame = new FrameLayout(context);
            frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
            list = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
            list.setSections();
            frame.addView(list, LayoutHelper.createFrame(-1, -1));
            fragmentView = frame;
            actionBar.setAdaptiveBackground(list);
        }
        return fragmentView;
    }
    private void reload() {
        document = null; loadError = null;
        try { document = LunagramWorkspaceStore.load(session); }
        catch (LunagramWorkspaceStore.WorkspaceException e) { loadError = failure(e); }
    }
    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!session.isCurrent()) { items.add(UItem.asShadow(s(R.string.LunagramWorkspaceLocked))); return; }
        if (loadError != null) { items.add(UItem.asShadow(loadError)); return; }
        items.add(UItem.asShadow(s(session.canMutate() ? R.string.LunagramWorkspaceAbout : R.string.LunagramWorkspaceReadOnly)));
        if (category == null) {
            for (Kind kind : Kind.values()) {
                int count = 0; for (Entry entry : document.entries) if (entry.kind == kind) count++;
                items.add(UItem.asButton(10 + kind.ordinal(), title(kind), Integer.toString(count)));
            }
            items.add(UItem.asShadow(s(R.string.LunagramWorkspaceLocalOnly)));
            items.add(UItem.asButton(2, s(R.string.LunagramWorkspaceCopyJson)));
            return;
        }
        if (session.canMutate()) items.add(UItem.asButton(1, s(category == Kind.DRAFT ? R.string.LunagramWorkspaceSaveDraft
                : category == Kind.MARKER ? R.string.LunagramWorkspaceSavePosition : R.string.LunagramWorkspaceAdd)).accent());
        visible.clear();
        for (Entry entry : document.entries) if (entry.kind == category) visible.add(entry);
        visible.sort(Comparator.comparing((Entry entry) -> entry.collection).thenComparingLong(entry -> -entry.created));
        String group = null;
        for (int i = 0; i < visible.size(); i++) {
            Entry entry = visible.get(i);
            if (!entry.collection.isEmpty() && !entry.collection.equals(group)) {
                group = entry.collection; items.add(UItem.asHeader(group));
            }
            String value = entry.kind == Kind.CHECKLIST ? s(entry.checked ? R.string.LunagramWorkspaceDone : R.string.LunagramWorkspacePending)
                    : entry.messageId > 0 ? reference(entry.messageId) : "";
            items.add(UItem.asButton(1000 + i, entry.name, value));
        }
        if (visible.isEmpty()) items.add(UItem.asShadow(s(R.string.LunagramWorkspaceEmpty)));
    }
    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!available()) return;
        if (item.id >= 10 && item.id < 10 + Kind.values().length) openChild(Kind.values()[item.id - 10], null, false);
        else if (item.id == 1 && mutationAllowed()) {
            if ((category == Kind.BOOKMARK || category == Kind.MARKER) && messageId <= 0) { message(s(R.string.LunagramWorkspaceNoMessage)); return; }
            if (category == Kind.DRAFT && draft().trim().isEmpty()) { message(s(R.string.LunagramWorkspaceNoDraft)); return; }
            openChild(category, null, true);
        } else if (item.id == 2) {
            confirm(s(R.string.LunagramWorkspaceExportConfirm), () -> {
                if (!available()) return;
                try { copy(LunagramWorkspaceStore.exportJson(session)); }
                catch (LunagramWorkspaceStore.WorkspaceException e) { message(failure(e)); }
            });
        } else if (item.id >= 1000 && item.id - 1000 < visible.size()) openChild(category, visible.get(item.id - 1000).id, false);
    }
    private void openChild(Kind category, String entryId, boolean editing) {
        presentFragment(new LunagramWorkspaceActivity(session, host, messageId, category, entryId, editing));
    }
    private void editor(Context context) {
        if (loadError != null) { paragraph(loadError, false); return; }
        nameInput = input(context, s(R.string.LunagramWorkspaceName), LunagramWorkspaceModel.MAX_NAME, false);
        if (category == Kind.BOOKMARK || category == Kind.CHECKLIST)
            collectionInput = input(context, s(R.string.LunagramWorkspaceCollection), LunagramWorkspaceModel.MAX_NAME, false);
        if (category == Kind.TEMPLATE || category == Kind.CHECKLIST || category == Kind.SEARCH || category == Kind.DRAFT) {
            if (category == Kind.DRAFT && draft().length() > LunagramWorkspaceModel.MAX_TEXT) {
                paragraph(s(R.string.LunagramWorkspaceInvalid), false); return;
            }
            textInput = input(context, s(category == Kind.SEARCH ? R.string.LunagramWorkspaceQuery : R.string.LunagramWorkspaceText),
                    category == Kind.SEARCH ? LunagramWorkspaceModel.MAX_QUERY : LunagramWorkspaceModel.MAX_TEXT, true);
            if (category == Kind.DRAFT) textInput.setText(draft());
        } else {
            nameInput.setText(reference(messageId));
            paragraph(reference(messageId), false);
            paragraph(s(R.string.LunagramWorkspaceLocalOnly), true);
        }
        if (category == Kind.CHECKLIST && messageId > 0) {
            TextCheckCell link = new TextCheckCell(context, resourceProvider);
            link.setTextAndCheck(s(R.string.LunagramWorkspaceLinkMessage), false, false);
            link.setOnClickListener(view -> { linkMessage = !linkMessage; link.setChecked(linkMessage); });
            content.addView(link, LayoutHelper.createLinear(-1, -2));
        }
        button(s(R.string.Save), () -> {
            if (!mutationAllowed()) return;
            try {
                Entry entry = Entry.create(category, nameInput.getText().toString().trim(),
                        textInput == null ? "" : textInput.getText().toString(),
                        collectionInput == null ? "" : collectionInput.getText().toString().trim(),
                        category == Kind.BOOKMARK || category == Kind.MARKER || category == Kind.CHECKLIST && linkMessage ? messageId : 0);
                LunagramWorkspaceStore.put(session, entry);
                finishFragment();
            } catch (LunagramWorkspaceStore.WorkspaceException e) { message(failure(e)); }
            catch (IllegalArgumentException e) { message(s(R.string.LunagramWorkspaceInvalid)); }
        });
        paragraph(s(R.string.LunagramWorkspaceAbout), true);
    }
    private void preview(Context context) {
        if (loadError != null) { paragraph(loadError, false); return; }
        Entry entry = document == null ? null : document.find(entryId);
        if (entry == null) { paragraph(s(R.string.LunagramWorkspaceEmpty), false); return; }
        paragraph(entry.name, false);
        if (entry.kind == Kind.TEMPLATE || entry.kind == Kind.DRAFT) {
            if (!draftPreviewCaptured) {
                Host value = host.get();
                draftApply = available() && value != null ? value.captureDraftApply() : null;
                previewDraft = draft();
                draftPreviewCaptured = true;
            }
            paragraph(s(R.string.LunagramWorkspaceCurrentDraft), true);
            paragraph(previewDraft.isEmpty() ? "—" : previewDraft, false);
            paragraph(s(R.string.LunagramWorkspaceSavedText), true);
        }
        paragraph(entry.text.isEmpty() ? reference(entry.messageId) : entry.text, false);
        if (!entry.collection.isEmpty()) paragraph(entry.collection, true);
        if (entry.kind == Kind.TEMPLATE || entry.kind == Kind.DRAFT) {
            button(s(R.string.LunagramWorkspaceInsert), () -> {
                if (!mutationAllowed()) return;
                Runnable apply = () -> {
                    if (!mutationAllowed()) return;
                    if (draftApply == null || !draftApply.apply(entry.text)) message(s(R.string.LunagramWorkspaceDraftChanged));
                    else closeWorkspace();
                };
                if (!previewDraft.isEmpty()) confirm(s(R.string.LunagramWorkspaceReplaceConfirm), apply); else apply.run();
            });
        } else if (entry.kind == Kind.CHECKLIST) {
            paragraph(s(entry.checked ? R.string.LunagramWorkspaceDone : R.string.LunagramWorkspacePending), true);
            button(s(entry.checked ? R.string.LunagramWorkspaceMarkUndone : R.string.LunagramWorkspaceMarkDone), () -> {
                if (!mutationAllowed()) return;
                try {
                    Entry current = LunagramWorkspaceStore.load(session).find(entry.id);
                    if (current == null) return;
                    LunagramWorkspaceStore.put(session, current.toggled());
                    refreshPreview();
                } catch (LunagramWorkspaceStore.WorkspaceException e) { message(failure(e)); }
            });
            if (entry.messageId > 0) button(s(R.string.LunagramWorkspaceOpenMessage), () -> {
                if (!available()) return;
                navigate(() -> { Host value = host.get(); if (value != null) value.openMessage(session.dialogId, entry.messageId); });
            });
        } else if (entry.kind == Kind.SEARCH) {
            button(s(R.string.LunagramWorkspaceRunSearch), () -> {
                if (!available()) return;
                navigate(() -> { Host value = host.get(); if (value != null) value.search(entry.text); });
            });
        } else if (entry.messageId > 0) {
            button(s(R.string.LunagramWorkspaceOpenMessage), () -> {
                if (!available()) return;
                navigate(() -> { Host value = host.get(); if (value != null) value.openMessage(session.dialogId, entry.messageId); });
            });
        }
        if (!entry.text.isEmpty()) button(s(R.string.LunagramWorkspaceCopy), () -> { if (available()) copy(entry.text); });
        button(s(R.string.Delete), () -> confirm(s(R.string.LunagramWorkspaceDeleteConfirm), () -> {
            if (!available()) return;
            try { LunagramWorkspaceStore.delete(session, entry.id); finishFragment(); }
            catch (LunagramWorkspaceStore.WorkspaceException e) { message(failure(e)); }
        }));
        if (!session.canMutate()) paragraph(s(R.string.LunagramWorkspaceReadOnly), true);
    }
    private EditTextBoldCursor input(Context context, String hint, int max, boolean multiline) {
        EditTextBoldCursor input = new EditTextBoldCursor(context) {
            @Override public boolean onTextContextMenuItem(int id) {
                if (!available()) { setText(""); return true; }
                return super.onTextContextMenuItem(id);
            }
        };
        input.setTextSize(16); input.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText)); input.setHint(hint);
        input.setSingleLine(!multiline); input.setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.setContentDescription(hint);
        input.setCustomSelectionActionModeCallback(new LunagramTextSelectionGuard(this::available, () -> input.setText("")));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        input.setMinHeight(AndroidUtilities.dp(multiline ? 120 : 56));
        input.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        input.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), getThemedColor(Theme.key_windowBackgroundWhite)));
        content.addView(input, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        return input;
    }
    private void paragraph(String value, boolean secondary) {
        TextView text = new TextView(content.getContext()); text.setText(value);
        text.setTextSize(secondary ? 14 : 16);
        text.setTextColor(getThemedColor(secondary ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText));
        text.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        content.addView(text, LayoutHelper.createLinear(-1, -2, 4, 4, 4, 16));
    }
    private void button(String label, Runnable action) {
        TextSettingsCell button = new TextSettingsCell(content.getContext(), resourceProvider);
        button.setText(label, false);
        button.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), getThemedColor(Theme.key_windowBackgroundWhite)));
        button.setOnClickListener(view -> action.run());
        content.addView(button, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 8));
    }
    private boolean available() { return !isFinished && session.isCurrent() && host.get() != null; }
    private void navigate(Runnable action) {
        if (!available() || !closeWorkspace()) return;
        if (session.isCurrent()) action.run();
    }
    private boolean closeWorkspace() {
        INavigationLayout layout = getParentLayout();
        if (layout == null || layout.getLastFragment() != this) return false;
        ArrayList<BaseFragment> stack = new ArrayList<>(layout.getFragmentStack());
        // Only the contiguous workspace opened with this exact session; never remove another chat.
        for (int i = stack.size() - 2; i >= 0; i--) {
            BaseFragment fragment = stack.get(i);
            if (!(fragment instanceof LunagramWorkspaceActivity)
                    || ((LunagramWorkspaceActivity) fragment).session != session) break;
            fragment.removeSelfFromStack(true);
        }
        return finishFragment(false);
    }
    private boolean mutationAllowed() {
        return available() && LunagramFeatureGate.require(this, LunagramFeatureCatalog.Feature.LOCAL_WORKSPACE) && session.canMutate();
    }
    private String draft() {
        Host value = host.get();
        if (!available() || value == null) return "";
        String draft = value.getCurrentDraft();
        return draft == null ? "" : draft;
    }
    private void copy(String text) {
        if (!available()) return;
        message(s(AndroidUtilities.addToClipboard(text) ? R.string.LunagramWorkspaceCopied : R.string.LunagramWorkspaceCopyFailed));
    }
    private void confirm(String text, Runnable action) {
        if (!available() || getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider).setTitle(s(R.string.LunagramWorkspaceTitle))
                .setMessage(text).setPositiveButton(s(R.string.OK), (dialog, which) -> { if (available()) action.run(); })
                .setNegativeButton(s(R.string.Cancel), null).create());
    }
    private void message(String text) {
        if (isFinished || getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider).setTitle(s(R.string.LunagramWorkspaceTitle))
                .setMessage(text).setPositiveButton(s(R.string.OK), null).create());
    }
    private void refreshPreview() {
        reload();
        if (content != null && !editing) { content.removeAllViews(); preview(content.getContext()); }
        if (list != null && list.adapter != null) list.adapter.update(false);
    }
    @Override public void onResume() {
        super.onResume();
        if (!session.isCurrent()) { clearAndFinish(); return; }
        if (!editing) refreshPreview(); // Never discard unsaved form input on resume.
    }
    private void clearAndFinish() {
        document = null; visible.clear(); previewDraft = ""; draftApply = null;
        if (content != null) content.removeAllViews();
        if (fragmentView != null) fragmentView.setVisibility(View.INVISIBLE);
        finishFragment();
    }
    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount) return;
        if (!session.isCurrent()) clearAndFinish(); else if (!editing) refreshPreview();
    }
    @Override public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.lunagramSubscriptionChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        document = null; visible.clear(); previewDraft = ""; draftApply = null;
        super.onFragmentDestroy();
    }
    private static String failure(LunagramWorkspaceStore.WorkspaceException e) {
        switch (e.failure) {
            case LOCKED: return s(R.string.LunagramWorkspaceLocked);
            case SUBSCRIPTION: return s(R.string.LunagramWorkspaceReadOnly);
            case CORRUPT: return s(R.string.LunagramWorkspaceCorrupt);
            case STORAGE: return s(R.string.LunagramWorkspaceStorageError);
            default: return s(R.string.LunagramWorkspaceInvalid);
        }
    }
    private static String title(Kind kind) {
        switch (kind) {
            case TEMPLATE: return s(R.string.LunagramWorkspaceTemplates);
            case BOOKMARK: return s(R.string.LunagramWorkspaceBookmarks);
            case CHECKLIST: return s(R.string.LunagramWorkspaceChecklists);
            case DRAFT: return s(R.string.LunagramWorkspaceDrafts);
            case SEARCH: return s(R.string.LunagramWorkspaceSearches);
            default: return s(R.string.LunagramWorkspaceMarker);
        }
    }
    private static String reference(int id) { return LocaleController.formatString(R.string.LunagramWorkspaceMessageRef, id); }
    private static String s(int resource) { return LocaleController.getString(resource); }
}
