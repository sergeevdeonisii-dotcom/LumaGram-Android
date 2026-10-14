package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.text.style.CharacterStyle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeedController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.ProfileSearchCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/** A chronological projection of selected channels, with explicit reactions but no read acknowledgements. */
public final class LunagramFeedActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {
    private LunagramFeedController feed;
    private RecyclerListView list;
    private LinearLayoutManager layout;
    private FeedAdapter adapter;
    private LinkedHashSet<Long> selected;
    private boolean reactionPickerLoading;
    private AlertDialog reactionDialog;
    private boolean visible;
    private final ArrayList<String> localFilter = new ArrayList<>();
    private final LinkedHashSet<String> revealedPosts = new LinkedHashSet<>();
    private long owner;
    private TextView newPosts;
    private BlurredBackgroundSourceRenderNode glassSource;

    public LunagramFeedActivity(int account) { setCurrentAccount(account); }

    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        owner = getUserConfig().getClientUserId();
        selected = LunagramFeedController.selected(currentAccount);
        feed = new LunagramFeedController(currentAccount, this::updateFeed);
        return owner > 0;
    }

    @Override public View createView(Context context) {
        hasOwnBackground = true;
        actionBar.setTitle(LocaleController.getString(R.string.LunagramFeed));
        actionBar.setAddToContainer(false);
        ActionBarMenuItem menu = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        menu.addSubItem(1, LocaleController.getString(R.string.LunagramFeedSources));
        menu.addSubItem(2, LocaleController.getString(R.string.LunagramFeedRefresh));
        menu.addSubItem(3, LocaleController.getString(R.string.LulFeedFilter));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == 1) chooseSources();
                else if (id == 2) { reactionPickerLoading = false; feed.refresh(); }
                else if (id == 3) showLocalFilter();
            }
        });
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = root;
        AndroidUtilities.removeFromParent(actionBar);
        root.addView(actionBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        newPosts = button(context, R.string.LunagramFeedNewPosts);
        newPosts.setVisibility(View.GONE);
        newPosts.setOnClickListener(v -> { reactionPickerLoading = false; feed.refresh(); list.scrollToPosition(0); });
        root.addView(newPosts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list = new RecyclerListView(context);
        list.setClipToPadding(false);
        list.setItemAnimator(null);
        list.setLayoutManager(layout = new LinearLayoutManager(context));
        list.setAdapter(adapter = new FeedAdapter());
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (Build.VERSION.SDK_INT >= 31) {
            glassSource = new BlurredBackgroundSourceRenderNode(null);
            glassSource.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(root.getWidth()); hash.add(root.getHeight());
                    hash.add(root.getDrawingTime()); hash.add(layout.findFirstVisibleItemPosition());
                    hash.add(feed.posts().size());
                    if (list.getChildCount() > 0) hash.add(list.getChildAt(0).getTop());
                }
                @Override public void renderNodeUpdateDisplayList(Canvas canvas) { root.draw(canvas); }
            });
        }
        list.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = AndroidUtilities.getDefaultWindowInsets(insets, false);
            list.setPadding(0, AndroidUtilities.dp(8), 0, system.bottom + AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
            return insets;
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && layout.findLastVisibleItemPosition() >= adapter.getItemCount() - 5) feed.next();
            }
        });
        if (feed.posts().isEmpty()) feed.refresh();
        return fragmentView;
    }

    private void chooseSources() {
        if (owner != getUserConfig().getClientUserId()) return;
        if (!feed.checkAccess()) { openSubscription(); return; }
        presentFragment(new LunagramFeedSourcesActivity(currentAccount, () -> {
            if (owner != getUserConfig().getClientUserId() || !feed.checkAccess()) return;
            selected = LunagramFeedController.selected(currentAccount);
            reactionPickerLoading = false;
            feed.refresh();
            if (list != null) list.scrollToPosition(0);
        }));
    }

    private void showLocalFilter() {
        if (!feed.isCurrent() || !feed.checkAccess() || getParentActivity() == null
                || !LunagramFeatureGate.require(this, org.telegram.messenger.LunagramFeatureCatalog.Feature.READING_TOOLS)) return;
        final org.telegram.messenger.LunagramMediaAccess.Session session = org.telegram.messenger.LunagramMediaAccess.forAccount(currentAccount);
        org.telegram.ui.Components.EditTextBoldCursor input = org.telegram.ui.Components.LumaDialogInput.create(getParentActivity(),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE, true, getResourceProvider());
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(org.telegram.messenger.LunagramFeedFilter.MAX_INPUT)});
        input.setMinLines(3); input.setMaxLines(6);
        input.setText(android.text.TextUtils.join("\n", localFilter));
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.LulFeedFilter))
                .setMessage(LocaleController.getString(R.string.LulFeedFilterInfo))
                .setView(org.telegram.ui.Components.LumaDialogInput.wrap(input))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .setNeutralButton(LocaleController.getString(R.string.LulFeedFilterClear), (d, which) -> {
                    if (!feed.isCurrent() || !session.isCurrent()) return;
                    localFilter.clear(); revealedPosts.clear(); updateFeed();
                }).setNegativeButton(LocaleController.getString(R.string.Cancel), null).create();
        if (showDialog(dialog) == null || dialog.getButton(AlertDialog.BUTTON_POSITIVE) == null) return;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!feed.isCurrent() || !session.canUse(org.telegram.messenger.LunagramFeatureCatalog.Feature.READING_TOOLS)) { dialog.dismiss(); return; }
            try {
                java.util.List<String> rules = org.telegram.messenger.LunagramFeedFilter.parse(input.getText().toString());
                localFilter.clear(); localFilter.addAll(rules); revealedPosts.clear(); updateFeed(); dialog.dismiss();
            } catch (IllegalArgumentException invalid) { input.setError(LocaleController.getString(R.string.LulFeedFilterLimit)); }
        });
    }

    private boolean foldPost(MessageObject post) {
        if (post == null || post.messageOwner == null || post.isSponsored() || post.isEphemeral()
                || post.messageOwner.action != null && !(post.messageOwner.action instanceof TLRPC.TL_messageActionEmpty)
                || !org.telegram.messenger.LunagramSubscriptionAccess.canUse(currentAccount, org.telegram.messenger.LunagramFeatureCatalog.Feature.READING_TOOLS)) return false;
        return !revealedPosts.contains(post.getDialogId() + ":" + post.getId())
                && org.telegram.messenger.LunagramFeedFilter.matches(localFilter, post.messageOwner.message);
    }

    private void openSubscription() {
        if (!feed.isCurrent()) return;
        LunagramSubscriptionActivity screen = new LunagramSubscriptionActivity();
        screen.setCurrentAccount(currentAccount);
        presentFragment(screen);
    }

    private void updateFeed() {
        if (adapter == null || list == null) return;
        if (!feed.isAvailable()) {
            reactionPickerLoading = false;
            if (reactionDialog != null) { reactionDialog.dismiss(); reactionDialog = null; }
        }
        if (newPosts != null) newPosts.setVisibility(feed.hasNewPosts() ? View.VISIBLE : View.GONE);
        // History callbacks run on the UI thread, but may arrive in a RecyclerView layout callback.
        if (list.isComputingLayout()) { list.post(this::updateFeed); return; }
        adapter.notifyDataSetChanged();
    }

    private void openPost(MessageObject post) {
        if (post == null || owner != getUserConfig().getClientUserId() || !feed.checkAccess()) return;
        Bundle args = new Bundle();
        args.putLong("chat_id", -post.getDialogId());
        args.putInt("message_id", post.getId());
        ChatActivity chat = new ChatActivity(args);
        chat.setCurrentAccount(currentAccount);
        presentFragment(chat);
    }

    private void failedReaction() {
        if (visible && fragmentView != null) BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.LunagramFeedReactionFailed)).show();
    }

    private void react(MessageObject post, TLRPC.Reaction reaction) {
        if (!feed.checkAccess()) return;
        if (reaction instanceof TLRPC.TL_reactionPaid) {
            // Stars are never spent from a feed preview. The normal post contains the explicit paid flow.
            openPost(post);
            return;
        }
        feed.react(post, reaction, ok -> { if (!ok) failedReaction(); });
    }

    private void chooseReaction(MessageObject post) {
        if (reactionPickerLoading || !visible || owner != getUserConfig().getClientUserId()) return;
        if (!feed.checkAccess()) return;
        reactionPickerLoading = true;
        feed.loadReactions(-post.getDialogId(), full -> {
            reactionPickerLoading = false;
            if (!visible || getParentActivity() == null || owner != getUserConfig().getClientUserId()) return;
            if (!feed.checkAccess()) return;
            if (full == null) { failedReaction(); return; }
            ArrayList<TLRPC.Reaction> choices = new ArrayList<>();
            if (full.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
                for (TLRPC.Reaction reaction : ((TLRPC.TL_chatReactionsSome) full.available_reactions).reactions) {
                    if (reaction instanceof TLRPC.TL_reactionEmoji || reaction instanceof TLRPC.TL_reactionCustomEmoji) choices.add(reaction);
                }
            } else if (full.available_reactions instanceof TLRPC.TL_chatReactionsAll) {
                for (TLRPC.TL_availableReaction available : MediaDataController.getInstance(currentAccount).getEnabledReactionsList()) {
                    if (available.inactive || available.premium && !getUserConfig().isPremium()) continue;
                    TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
                    reaction.emoticon = available.reaction;
                    choices.add(reaction);
                }
            }
            if (choices.isEmpty()) {
                showDialog(new AlertDialog.Builder(getParentActivity()).setMessage(LocaleController.getString(R.string.LunagramFeedReactionsDisabled))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
                return;
            }
            ReactionGrid grid = new ReactionGrid(getParentActivity(), post, choices);
            reactionDialog = new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(LocaleController.getString(R.string.LunagramFeedReact)).setView(grid)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create();
            showDialog(reactionDialog);
        });
    }

    /** Emoji are choices in a compact grid, not tall full-width textual menu rows. */
    private final class ReactionGrid extends RecyclerListView {
        private final GridLayoutManager manager;
        private final int count;
        ReactionGrid(Context context, MessageObject post, ArrayList<TLRPC.Reaction> choices) {
            super(context);
            count = choices.size();
            setLayoutManager(manager = new GridLayoutManager(context, 1));
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
            setClipToPadding(false);
            setItemAnimator(null);
            setNestedScrollingEnabled(false);
            setAdapter(new RecyclerListView.SelectionAdapter() {
                @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return false; }
                @Override public int getItemCount() { return choices.size(); }
                @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                    ReactionOption option = new ReactionOption(parent.getContext());
                    option.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48) + AndroidUtilities.dp(8)));
                    return new RecyclerListView.Holder(option);
                }
                @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                    TLRPC.Reaction reaction = choices.get(position);
                    ReactionOption option = (ReactionOption) holder.itemView;
                    option.bind(reaction);
                    option.target.setOnClickListener(v -> {
                        if (!visible || owner != getUserConfig().getClientUserId()) return;
                        if (!feed.checkAccess()) return;
                        if (reactionDialog != null) reactionDialog.dismiss();
                        react(post, reaction);
                    });
                }
                @Override public void onViewRecycled(RecyclerView.ViewHolder holder) {
                    ((ReactionOption) holder.itemView).release();
                }
            });
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int slotSize = AndroidUtilities.dp(48) + AndroidUtilities.dp(8);
            int columns = Math.max(1, Math.min(8, (width - getPaddingLeft() - getPaddingRight()) / slotSize));
            if (manager.getSpanCount() != columns) manager.setSpanCount(columns);
            int rows = (count + columns - 1) / columns;
            int height = Math.min(AndroidUtilities.dp(280), rows * slotSize + getPaddingTop() + getPaddingBottom());
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) height = Math.min(height, MeasureSpec.getSize(heightSpec));
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    private final class ReactionOption extends FrameLayout {
        final FrameLayout target;
        private final TextView emoji;
        private final BackupImageView custom;
        ReactionOption(Context context) {
            super(context);
            target = new FrameLayout(context);
            target.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
            target.setFocusable(true);
            addView(target, LayoutHelper.createFrame(48, 48, Gravity.CENTER));
            emoji = new TextView(context);
            emoji.setGravity(Gravity.CENTER);
            emoji.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
            emoji.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            emoji.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            target.addView(emoji, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            custom = new BackupImageView(context);
            custom.setSize(AndroidUtilities.dp(28), AndroidUtilities.dp(28));
            custom.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            target.addView(custom, LayoutHelper.createFrame(28, 28, Gravity.CENTER));
        }
        void bind(TLRPC.Reaction reaction) {
            release();
            if (reaction instanceof TLRPC.TL_reactionEmoji) {
                String emoticon = ((TLRPC.TL_reactionEmoji) reaction).emoticon;
                emoji.setText(Emoji.replaceEmoji(emoticon, emoji.getPaint().getFontMetricsInt(), false));
                emoji.setVisibility(View.VISIBLE);
                custom.setVisibility(View.GONE);
                target.setContentDescription(emoticon);
            } else {
                emoji.setVisibility(View.GONE);
                custom.setVisibility(View.VISIBLE);
                // BackupImageView paints the receiver and registers/releases it on attach/detach.
                custom.setAnimatedEmojiDrawable(AnimatedEmojiDrawable.make(currentAccount,
                    AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW, ((TLRPC.TL_reactionCustomEmoji) reaction).document_id));
                target.setContentDescription(LocaleController.getString(R.string.LunagramFeedReact));
            }
        }
        void release() { custom.setAnimatedEmojiDrawable(null); }
    }

    @Override public void onResume() {
        super.onResume();
        visible = true;
        if (!feed.isCurrent()) { feed.close(); selected.clear(); updateFeed(); return; }
        if (!feed.checkAccess()) { updateFeed(); return; }
        LinkedHashSet<Long> now = LunagramFeedController.selected(currentAccount);
        if (!now.equals(selected) || feed.posts().isEmpty()) { selected = now; feed.refresh(); }
        updateFeed();
    }

    @Override public void onPause() { visible = false; if (reactionDialog != null) reactionDialog.dismiss(); super.onPause(); }
    @Override public void onFragmentDestroy() { if (reactionDialog != null) reactionDialog.dismiss(); feed.close(); super.onFragmentDestroy(); }
    @Override public void onParentScrollToTop() { if (list != null) list.smoothScrollToPosition(0); }
    @Override public BlurredBackgroundSourceRenderNode getGlassSource() {
        if (glassSource == null || fragmentView == null || fragmentView.getWidth() <= 0 || fragmentView.getHeight() <= 0) return null;
        // MainTabs draws the child node, it does not populate it on behalf of a feed.
        glassSource.setSize(fragmentView.getWidth(), fragmentView.getHeight());
        glassSource.updateDisplayListIfNeeded();
        return glassSource;
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (fragmentView != null) fragmentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
            updateFeed();
        };
        int[] keys = {Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundGray, Theme.key_windowBackgroundWhiteBlackText,
            Theme.key_windowBackgroundWhiteBlueText, Theme.key_windowBackgroundWhiteGrayText, Theme.key_chat_inBubble, Theme.key_chat_messageTextIn};
        for (int key : keys) descriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, key));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        return descriptions;
    }

    private TextView button(Context context, int text) {
        TextView view = new TextView(context);
        view.setText(LocaleController.getString(text));
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(AndroidUtilities.dp(48));
        view.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        view.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        return view;
    }

    private final class PostView extends LinearLayout {
        final ProfileSearchCell origin;
        final ChatMessageCell message;
        final TextView folded;
        MessageObject post;
        PostView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(0, 0, 0, AndroidUtilities.dp(12));
            setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), getThemedColor(Theme.key_windowBackgroundWhite)));
            setClipToOutline(true);
            origin = new ProfileSearchCell(context);
            origin.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
            addView(origin, new LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(64)));
            message = new FeedMessageCell(context);
            message.setDelegate(cellDelegate);
            addView(message, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            folded = button(context, R.string.LulFeedFolded);
            folded.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20));
            addView(folded, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            folded.setOnClickListener(v -> {
                if (post == null || !feed.isCurrent() || !feed.checkAccess()) return;
                if (revealedPosts.size() >= 512) revealedPosts.clear();
                revealedPosts.add(post.getDialogId() + ":" + post.getId()); bind(post);
            });
            origin.setOnClickListener(v -> openPost(post));
        }
        void bind(MessageObject value) {
            ((FeedMessageCell) message).cancelBodyLongPress();
            post = value;
            TLRPC.Chat chat = getMessagesController().getChat(-post.getDialogId());
            String title = org.telegram.messenger.LunagramStreamerMode.isEnabled(currentAccount)
                    ? org.telegram.messenger.LunagramStreamerMode.hiddenName(true)
                    : chat == null ? LocaleController.formatString(R.string.LunagramFeedChannelFallback, -post.getDialogId())
                    : org.telegram.messenger.LunagramStreamerMode.displayName(currentAccount, chat);
            origin.setData(chat, null, title, LocaleController.formatDateTime(post.messageOwner.date, true), false, false);
            origin.setContentDescription(LocaleController.formatString(R.string.LunagramFeedOrigin, title, post.getId()));
            boolean fold = foldPost(post);
            folded.setVisibility(fold ? View.VISIBLE : View.GONE);
            message.setVisibility(fold ? View.GONE : View.VISIBLE);
            if (!fold) {
                MessageObject display = new MessageObject(currentAccount, post.messageOwner, true, true);
                display.setLayoutParentWidth(message.getParentWidth());
                // A recycled cell can keep the same width; invalidate the clone's screen-width text layout on EVERY bind.
                display.resetLayout();
                message.setMessageObject(display, null, false, false, false);
            }
        }
    }

    /** A feed has no ChatActivity parent to supply its actual width or paint gradient bubbles. */
    private final class FeedMessageCell extends ChatMessageCell {
        private int feedWidth;
        private MessageObject bodyTouchPost;
        private float bodyTouchX, bodyTouchY;
        FeedMessageCell(Context context) { super(context, LunagramFeedActivity.this.currentAccount, false, null, LunagramFeedActivity.this.resourceProvider); }
        void cancelBodyLongPress() {
            bodyTouchPost = null;
            cancelCheckLongPress();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                cancelBodyLongPress();
                final boolean handled = super.onTouchEvent(event);
                // Native links/media/reaction chips keep their own gesture. Plain text needs the
                // long-press entry point normally supplied by ChatActivity's RecyclerListView.
                if (handled || !cellDelegate.canPerformActions() || getMessageObject() == null
                        || event.getX() < getBackgroundDrawableLeft() || event.getX() > getBackgroundDrawableRight()
                        || event.getY() < getBackgroundDrawableTop() || event.getY() > getBackgroundDrawableBottom()) return handled;
                bodyTouchPost = getMessageObject();
                bodyTouchX = event.getX(); bodyTouchY = event.getY();
                startCheckLongPress();
                return true;
            }
            if (bodyTouchPost != null) {
                final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (action != MotionEvent.ACTION_MOVE || event.getPointerCount() != 1
                        || Math.abs(event.getX() - bodyTouchX) > slop || Math.abs(event.getY() - bodyTouchY) > slop) {
                    cancelBodyLongPress();
                }
                return true;
            }
            return super.onTouchEvent(event);
        }
        @Override protected boolean onLongPress() {
            if (bodyTouchPost == null) return super.onLongPress();
            if (!cellDelegate.canPerformActions() || bodyTouchPost != getMessageObject()) {
                cancelBodyLongPress();
                return false;
            }
            chooseReaction(bodyTouchPost);
            return true;
        }
        @Override protected void onDetachedFromWindow() {
            cancelBodyLongPress();
            super.onDetachedFromWindow();
        }
        @Override public int getParentWidth() {
            if (feedWidth > 0) return feedWidth;
            int available = list == null ? 0 : list.getWidth() - AndroidUtilities.dp(16);
            return Math.max(1, available > 0 ? available : AndroidUtilities.displaySize.x - AndroidUtilities.dp(16));
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width > 0 && width != feedWidth) {
                feedWidth = width;
                setParentViewSize(width, list == null ? 0 : list.getHeight());
                MessageObject display = getMessageObject();
                if (display != null) {
                    display.setLayoutParentWidth(width);
                    display.generateLayout(null); display.forceUpdate = true;
                    setMessageObject(display, null, false, false, false);
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private final ChatMessageCell.ChatMessageCellDelegate cellDelegate = new ChatMessageCell.ChatMessageCellDelegate() {
        @Override public boolean canPerformActions() {
            // ChatMessageCell's default is false: without this even its native reaction hit targets are inert.
            return visible && feed != null && feed.isAvailable() && owner == getUserConfig().getClientUserId();
        }
        @Override public boolean canPerformReply() { return false; }
        private void open(ChatMessageCell cell) { openPost(cell.getMessageObject()); }
        @Override public void didPressImage(ChatMessageCell cell, float x, float y, boolean fullPreview) { open(cell); }
        @Override public boolean needPlayMessage(ChatMessageCell cell, MessageObject message, boolean muted) { open(cell); return false; }
        @Override public void didLongPress(ChatMessageCell cell, float x, float y) { chooseReaction(cell.getMessageObject()); }
        @Override public void didPressOther(ChatMessageCell cell, float x, float y) { open(cell); }
        @Override public void didPressTime(ChatMessageCell cell) { open(cell); }
        @Override public void didPressReplyMessage(ChatMessageCell cell, int id, float x, float y, boolean longpress) { open(cell); }
        @Override public void didPressChannelAvatar(ChatMessageCell cell, TLRPC.Chat chat, int postId, float x, float y, boolean forward) { open(cell); }
        @Override public void didPressUserAvatar(ChatMessageCell cell, TLRPC.User user, float x, float y, boolean forward) { open(cell); }
        @Override public void didPressUrl(ChatMessageCell cell, CharacterStyle url, boolean longPress) { open(cell); }
        @Override public void didPressWebPage(ChatMessageCell cell, TLRPC.WebPage page, String url, boolean safe) { open(cell); }
        @Override public void didPressInstantButton(ChatMessageCell cell, int type) { open(cell); }
        @Override public void didPressCommentButton(ChatMessageCell cell) { open(cell); }
        @Override public void didPressSideButton(ChatMessageCell cell) { open(cell); }
        @Override public void didPressBotButton(ChatMessageCell cell, TL_keyboard.KeyboardButtonProto button) { open(cell); }
        @Override public void didPressExtendedMediaPreview(ChatMessageCell cell, TL_keyboard.KeyboardInlineButton button) { open(cell); }
        @Override public void didPressVoteButtons(ChatMessageCell cell, ArrayList<TLRPC.PollAnswer> buttons, int count, int x, int y) { open(cell); }
        @Override public void didPressPollMedia(ChatMessageCell cell, ImageReceiver receiver, TLRPC.PollAnswer answer, TLRPC.MessageMedia media, float x, float y, int index) { open(cell); }
        @Override public void didPressReaction(ChatMessageCell cell, TLRPC.ReactionCount reaction, boolean longpress, float x, float y) {
            if (!canPerformActions() || cell == null || reaction == null || reaction.reaction == null) return;
            if (longpress) chooseReaction(cell.getMessageObject());
            else react(cell.getMessageObject(), reaction.reaction);
        }
    };

    private final class FeedAdapter extends RecyclerListView.SelectionAdapter {
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return false; }
        @Override public int getItemCount() { return feed.posts().size() + 1; }
        @Override public int getItemViewType(int position) { return position < feed.posts().size() ? 0 : 1; }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View view = type == 0 ? new PostView(parent.getContext()) : new LinearLayout(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (type == 0) {
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) view.getLayoutParams();
                params.leftMargin = params.rightMargin = AndroidUtilities.dp(8);
                params.bottomMargin = AndroidUtilities.dp(12);
            }
            return new RecyclerListView.Holder(view);
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof PostView) {
                if (!feed.checkAccess() || position >= feed.posts().size()) {
                    holder.itemView.setVisibility(View.INVISIBLE);
                    list.post(LunagramFeedActivity.this::updateFeed);
                    return;
                }
                holder.itemView.setVisibility(View.VISIBLE);
                ((PostView) holder.itemView).bind(feed.posts().get(position));
                return;
            }
            LinearLayout footer = (LinearLayout) holder.itemView;
            footer.removeAllViews();
            footer.setOrientation(LinearLayout.VERTICAL);
            footer.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
            TextView status = new TextView(footer.getContext());
            status.setTextSize(16);
            status.setGravity(Gravity.CENTER);
            status.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            boolean emptySelection = selected == null || selected.isEmpty();
            boolean locked = !feed.isAvailable();
            int text = locked ? R.string.LunagramFeedUltraRequired : emptySelection ? R.string.LunagramFeedEmpty : feed.hasError() ? R.string.LunagramFeedError : feed.loading() ? R.string.LunagramFeedLoading
                : feed.posts().isEmpty() ? R.string.LunagramFeedNoPosts : feed.atEnd() ? R.string.LunagramFeedEnd : R.string.LunagramFeedLoadMore;
            status.setText(LocaleController.getString(text));
            footer.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (locked) {
                if (feed.isCurrent()) {
                    TextView subscription = button(footer.getContext(), R.string.LunagramSubscriptionTitle);
                    subscription.setOnClickListener(v -> openSubscription());
                    footer.addView(subscription);
                }
                return;
            }
            if (emptySelection || feed.hasError() || !feed.loading() && !feed.atEnd()) {
                TextView action = button(footer.getContext(), emptySelection ? R.string.LunagramFeedChoose : feed.hasError() ? R.string.LunagramFeedRetry : R.string.LunagramFeedLoadMore);
                action.setOnClickListener(v -> { if (emptySelection) chooseSources(); else if (feed.hasError()) feed.retry(); else feed.next(); });
                footer.addView(action);
            }
            if (feed.hasError()) {
                TextView sources = button(footer.getContext(), R.string.LunagramFeedSources);
                sources.setOnClickListener(v -> chooseSources());
                footer.addView(sources);
            }
        }
    }
}
