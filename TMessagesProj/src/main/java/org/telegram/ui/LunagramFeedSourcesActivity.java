package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeedController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Edits a draft; Back discards, Done applies, and adding uses Telegram's searchable channel picker. */
public final class LunagramFeedSourcesActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final LinkedHashSet<Long> checked = new LinkedHashSet<>();
    private final ArrayList<Long> channels = new ArrayList<>();
    private final Runnable changed;
    private long owner;
    private RecyclerListView list;
    private SourcesAdapter adapter;
    private LunagramFeedController.AccessSession access;
    private ActionBarMenuItem done;
    private DialogsActivity sourcePicker;

    public LunagramFeedSourcesActivity(int account, Runnable changed) {
        setCurrentAccount(account);
        this.changed = changed;
    }

    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        owner = getUserConfig().getClientUserId();
        access = new LunagramFeedController.AccessSession(currentAccount);
        checked.addAll(LunagramFeedController.selected(currentAccount));
        channels.addAll(checked);
        getNotificationCenter().addObserver(this, NotificationCenter.lunagramSubscriptionChanged);
        return owner > 0;
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.LunagramFeedSources));
        done = actionBar.createMenu().addItem(1, R.drawable.ic_ab_done);
        done.setContentDescription(LocaleController.getString(R.string.Done));
        done.setVisibility(access.isAvailable() ? View.VISIBLE : View.GONE);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == 1 && owner == getUserConfig().getClientUserId() && access.isAvailable()) {
                    if (!LunagramFeedController.saveSelection(access, checked)) return;
                    if (changed != null) changed.run();
                    finishFragment();
                }
            }
        });
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;
        list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter = new SourcesAdapter());
        frame.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        list.setOnItemClickListener((view, position) -> {
            if (owner != getUserConfig().getClientUserId() || !access.isAvailable()) return;
            if (position == 0) addSource();
            else if (position <= channels.size()) {
                long channel = channels.get(position - 1);
                if (!checked.remove(channel)) checked.add(channel);
                ((TextCheckCell) view).setChecked(checked.contains(channel));
            }
        });
        return fragmentView;
    }

    private void addSource() {
        if (!access.isAvailable()) return;
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_CHANNELS_ONLY);
        args.putBoolean("allowSwitchAccount", false);
        args.putBoolean("checkCanWrite", false);
        args.putBoolean("allowGlobalSearch", false);
        DialogsActivity picker = new DialogsActivity(args);
        sourcePicker = picker;
        picker.setCurrentAccount(currentAccount);
        picker.setDelegate((fragment, dids, message, param, notify, date, repeat, topics) -> {
            if (owner != getUserConfig().getClientUserId() || fragment.getCurrentAccount() != currentAccount || !access.isAvailable()) return false;
            for (org.telegram.messenger.MessagesStorage.TopicKey key : dids) {
                TLRPC.Chat chat = getMessagesController().getChat(-key.dialogId);
                if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || chat.left || chat.kicked) continue;
                if (!channels.contains(chat.id)) channels.add(chat.id);
                checked.add(chat.id);
            }
            adapter.notifyDataSetChanged();
            fragment.finishFragment();
            sourcePicker = null;
            return true;
        });
        presentFragment(picker);
    }

    @Override public void onResume() {
        super.onResume();
        if (owner != getUserConfig().getClientUserId() || !access.isCurrent()) finishFragment();
        else updateAccess();
    }

    private void updateAccess() {
        boolean available = access.isAvailable();
        if (done != null) done.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available && sourcePicker != null) { sourcePicker.finishFragment(); sourcePicker = null; }
        if (list != null && list.isComputingLayout()) { list.post(this::updateAccess); return; }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.lunagramSubscriptionChanged && account == currentAccount) updateAccess();
    }

    @Override public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.lunagramSubscriptionChanged);
        sourcePicker = null;
        super.onFragmentDestroy();
    }

    private final class SourcesAdapter extends RecyclerListView.SelectionAdapter {
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return access.isAvailable() && holder.getItemViewType() != 2; }
        @Override public int getItemCount() { return access.isAvailable() ? channels.size() + 2 : 1; }
        @Override public int getItemViewType(int position) { return !access.isAvailable() ? 2 : position == 0 ? 0 : position <= channels.size() ? 1 : 2; }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View view = type == 0 ? new TextCell(parent.getContext()) : type == 1 ? new TextCheckCell(parent.getContext()) : new TextInfoPrivacyCell(parent.getContext());
            if (type != 2) view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(view);
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (!access.isAvailable()) {
                if (holder.itemView instanceof TextInfoPrivacyCell) ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(R.string.LunagramFeedUltraRequired));
                else { holder.itemView.setVisibility(View.INVISIBLE); list.post(LunagramFeedSourcesActivity.this::updateAccess); }
                return;
            }
            holder.itemView.setVisibility(View.VISIBLE);
            if (position == 0) ((TextCell) holder.itemView).setText(LocaleController.getString(R.string.LunagramFeedAddSource), true);
            else if (position <= channels.size()) {
                long channel = channels.get(position - 1);
                TLRPC.Chat chat = getMessagesController().getChat(channel);
                String name = org.telegram.messenger.LunagramStreamerMode.isEnabled(currentAccount)
                        ? org.telegram.messenger.LunagramStreamerMode.hiddenName(true)
                        : chat == null ? LocaleController.formatString(R.string.LunagramFeedChannelFallback, channel)
                        : org.telegram.messenger.LunagramStreamerMode.displayName(currentAccount, chat);
                ((TextCheckCell) holder.itemView).setTextAndCheck(name, checked.contains(channel), position < channels.size());
            } else ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(R.string.LunagramFeedSourcesHint));
        }
    }
}
