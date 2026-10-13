package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramExperienceSettings;
import org.telegram.messenger.LunagramFeatureCatalog.Feature;
import org.telegram.messenger.LunagramMediaAccess;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.LunagramSwipeGesture;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import java.util.ArrayList;

public final class LunagramExperienceSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final long dialogId;
    private final boolean swipeOnly;
    private UniversalRecyclerView list;
    private LunagramMediaAccess.Session ownerSession;
    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        ownerSession = LunagramMediaAccess.forAccount(currentAccount);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.lunagramSubscriptionChanged);
        return true;
    }
    @Override public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.lunagramSubscriptionChanged);
        super.onFragmentDestroy();
    }
    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (account == currentAccount && list != null) list.adapter.update(false);
    }
    private boolean currentOwner() {
        return !isFinished && currentAccount == UserConfig.selectedAccount
                && ownerSession != null && ownerSession.isCurrent();
    }
    private Feature featureFor(int id) {
        switch (id) {
            case 1: case 11: return Feature.SWIPE_ACTIONS;
            case 2: return Feature.LUNA_PET;
            case 3: return Feature.INTERFACE_SOUNDS;
            case 4: return Feature.SEND_ANIMATIONS;
            case 5: case 6: return Feature.CHAT_ATMOSPHERE;
            case 7: return Feature.STREAMER_MODE;
            case 9: return Feature.VOICE_STEREO;
            case 10: return Feature.CHANNEL_FEED;
            default: return null;
        }
    }
    public LunagramExperienceSettingsActivity() { this(0, false); }
    public LunagramExperienceSettingsActivity(long dialogId) { this(dialogId, false); }
    public LunagramExperienceSettingsActivity(long dialogId, boolean swipeOnly) { this.dialogId = dialogId; this.swipeOnly = swipeOnly; }
    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(text(swipeOnly ? R.string.LunagramSwipeTitle : R.string.LunagramExperienceTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        list = new UniversalRecyclerView(this, this::fill, this::click, null);
        list.setSections(); list.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        list.listenReorder((section, items) -> {
            if (!currentOwner() || !LunagramFeatureGate.require(this, Feature.SWIPE_ACTIONS)) return;
            int[] order = new int[items.size()];
            for (int i = 0; i < items.size(); i++) order[i] = items.get(i).id - 100;
            if (LunagramSwipeGesture.validOrder(order)) LunagramExperienceSettings.setSwipeOrder(currentAccount, order);
        });
        list.allowReorder(true); list.setReorderLongPressEnabled(true);
        frame.addView(list, LayoutHelper.createFrame(-1, -1, Gravity.FILL));
        actionBar.setAdaptiveBackground(list); return fragmentView = frame;
    }
    private void fill(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!swipeOnly) {
            items.add(UItem.asHeader(text(R.string.BHGAppearanceHeader)));
            items.add(TextDetailCell.Factory.of(2, text(R.string.LunagramPetTitle), petNames()[LunagramExperienceSettings.pet(currentAccount)]));
            items.add(TextDetailCell.Factory.of(3, text(R.string.LunagramSoundsTitle), soundNames()[LunagramExperienceSettings.soundPack(currentAccount)]));
            items.add(TextDetailCell.Factory.of(4, text(R.string.LunagramSendEffectTitle), sendNames()[LunagramExperienceSettings.sendEffect(currentAccount)]));
            items.add(UItem.asCheck(10, text(R.string.LunagramFeedShowTab)).setChecked(LunagramExperienceSettings.feedVisible(currentAccount)));
            if (dialogId != 0) {
                items.add(TextDetailCell.Factory.of(5, text(R.string.LunagramAtmosphereTitle), atmosphereNames()[LunagramExperienceSettings.atmosphere(currentAccount, dialogId)]));
                items.add(TextDetailCell.Factory.of(6, text(R.string.LunagramAtmosphereIntensity), intensityNames()[LunagramExperienceSettings.atmosphereIntensity(currentAccount, dialogId)]));
            }
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(text(R.string.LunagramSwipeTitle)));
            items.add(UItem.asButton(11, R.drawable.msg_customize, text(R.string.LunagramSwipeOrder)));
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(text(R.string.LunagramStreamerTitle)));
            items.add(UItem.asCheck(7, text(R.string.LunagramStreamerEnable)).setChecked(org.telegram.messenger.LunagramStreamerMode.isEnabled(currentAccount)));
            items.add(UItem.asShadow(text(R.string.LunagramStreamerInfo)));
            items.add(UItem.asHeader(text(R.string.LunagramRecordingHeader)));
            items.add(UItem.asButton(8, R.drawable.msg_camera, text(R.string.LunagramVideoEffects)));
            items.add(UItem.asCheck(9, text(R.string.LunagramStereoVoice)).setChecked(org.telegram.messenger.LunagramAudioSettings.isStereoEnabled(currentAccount)));
            items.add(UItem.asShadow(text(R.string.LunagramStereoVoiceInfo)));
            return;
        }
        items.add(UItem.asHeader(text(R.string.LunagramSwipeTitle)));
        items.add(UItem.asCheck(1, text(R.string.LunagramSwipeEnable)).setChecked(LunagramExperienceSettings.swipeEnabled(currentAccount)));
        items.add(UItem.asShadow(text(R.string.LunagramSwipeInfo)));
        items.add(UItem.asHeader(text(R.string.LunagramSwipeOrder)));
        adapter.reorderSectionStart();
        for (int action : LunagramExperienceSettings.swipeOrder(currentAccount)) {
            items.add(UItem.asButton(100 + action, actionIcon(action), actionName(action)).setReordering(true));
        }
        adapter.reorderSectionEnd();
        items.add(UItem.asShadow(text(R.string.LunagramSwipeOrderInfo)));
    }
    private void click(UItem item, View view, int position, float x, float y) {
        if (!currentOwner()) return;
        // Explicitly ending an existing privacy shield never requires a purchase.
        if (item.id == 7 && org.telegram.messenger.LunagramStreamerMode.isEnabled(currentAccount)) {
            org.telegram.messenger.LunagramStreamerMode.setEnabled(currentAccount, false);
            list.adapter.update(false);
            return;
        }
        Feature feature = featureFor(item.id);
        if (feature != null && !LunagramFeatureGate.require(this, feature)) return;
        if (item.id == 1) {
            boolean enabled = !LunagramExperienceSettings.swipeEnabled(currentAccount); LunagramExperienceSettings.setSwipeEnabled(currentAccount, enabled);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id >= 2 && item.id <= 6) {
            String[] names = item.id == 2 ? petNames() : item.id == 3 ? soundNames() : item.id == 4 ? sendNames() : item.id == 5 ? atmosphereNames() : intensityNames();
            int selected = item.id == 2 ? LunagramExperienceSettings.pet(currentAccount) : item.id == 3 ? LunagramExperienceSettings.soundPack(currentAccount) : item.id == 4 ? LunagramExperienceSettings.sendEffect(currentAccount) : item.id == 5 ? LunagramExperienceSettings.atmosphere(currentAccount, dialogId) : LunagramExperienceSettings.atmosphereIntensity(currentAccount, dialogId);
            showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), names, item.text.toString(), selected, (dialog, index) -> {
                if (!currentOwner() || index < 0 || index >= names.length || !LunagramFeatureGate.require(this, feature)) return;
                if (item.id == 2) LunagramExperienceSettings.setPet(currentAccount, index);
                else if (item.id == 3) { LunagramExperienceSettings.setSoundPack(currentAccount, index); org.telegram.messenger.LunagramInterfaceSounds.play(currentAccount, org.telegram.messenger.LunagramInterfaceSounds.SWITCH); }
                else if (item.id == 4) LunagramExperienceSettings.setSendEffect(currentAccount, index);
                else if (item.id == 5) LunagramExperienceSettings.setAtmosphere(currentAccount, dialogId, index);
                else LunagramExperienceSettings.setAtmosphereIntensity(currentAccount, dialogId, index);
                list.adapter.update(false);
            }));
        } else if (item.id == 7) {
            org.telegram.messenger.LunagramStreamerMode.setEnabled(currentAccount, !org.telegram.messenger.LunagramStreamerMode.isEnabled(currentAccount)); list.adapter.update(false);
        } else if (item.id == 8) {
            LunagramVideoEffectsSettingsActivity screen = new LunagramVideoEffectsSettingsActivity(); screen.setCurrentAccount(currentAccount); presentFragment(screen);
        } else if (item.id == 9) {
            org.telegram.messenger.LunagramAudioSettings.setStereoEnabled(currentAccount, !org.telegram.messenger.LunagramAudioSettings.isStereoEnabled(currentAccount)); list.adapter.update(false);
        } else if (item.id == 10) {
            LunagramExperienceSettings.setFeedVisible(currentAccount, !LunagramExperienceSettings.feedVisible(currentAccount)); list.adapter.update(false);
        } else if (item.id == 11) {
            LunagramExperienceSettingsActivity screen = new LunagramExperienceSettingsActivity(dialogId, true);
            screen.setCurrentAccount(currentAccount); presentFragment(screen);
        }
    }
    private String text(int id) { return LocaleController.getString(id); }
    private String[] petNames() { return names(R.string.LunagramEffectsOff, R.string.LunagramPetCat); }
    private String[] soundNames() { return names(R.string.LunagramEffectsOff, R.string.LunagramSoundSpace, R.string.LunagramSoundTypewriter, R.string.LunagramSoundRetro, R.string.LunagramSoundDrops); }
    private String[] sendNames() { return names(R.string.Default, R.string.LunagramSendLetters, R.string.LunagramSendDrop, R.string.LunagramSendPlane); }
    private String[] atmosphereNames() { return names(R.string.LunagramEffectsOff, R.string.LunagramAtmosphereStars, R.string.LunagramAtmosphereRain, R.string.LunagramAtmosphereFireflies, R.string.LunagramAtmosphereAquarium); }
    private String[] intensityNames() { return names(R.string.AutoDownloadLow, R.string.SpeedNormal, R.string.AutoDownloadHigh); }
    private String[] names(int... ids) { String[] values = new String[ids.length]; for (int i=0;i<ids.length;i++) values[i]=text(ids[i]); return values; }
    private String actionName(int action) { return text(action == 1 ? R.string.Reply : action == 2 ? R.string.Copy : action == 3 ? R.string.TranslateMessage : R.string.LunagramSaveToSelf); }
    private int actionIcon(int action) { return action == 1 ? R.drawable.menu_reply : action == 2 ? R.drawable.msg_copy : action == 3 ? R.drawable.msg_translate : R.drawable.msg_saved; }
    @Override public void onResume() { super.onResume(); if (list != null) list.adapter.update(false); }
}
