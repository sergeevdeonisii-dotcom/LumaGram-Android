package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LumaDelayedSend;
import org.telegram.messenger.LumaEmergencyMode;
import org.telegram.messenger.LumaGhostMode;
import org.telegram.messenger.LumaTextAnimation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Locale;

public class ExperimentalFeaturesActivity extends BaseFragment {

    private static final int ROW_ENABLED = 1;
    private static final int ROW_RESET = 2;
    private static final int ROW_DELAYED_SEND_ENABLED = 3;
    private static final int ROW_ACCOUNT_EXPORT = 4;
    private static final int ROW_EMERGENCY_ENABLED = 5;
    private static final int ROW_EMERGENCY_CHAT = 6;
    private static final int ROW_GHOST_ENABLED = 7;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ExperimentalFeaturesTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        contentView.addView(listView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT,
            LayoutHelper.MATCH_PARENT,
            Gravity.FILL
        ));
        actionBar.setAdaptiveBackground(listView);

        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean enabled = LumaTextAnimation.isEnabled();

        items.add(UItem.asHeader(getString(R.string.ExperimentalTypingHeader)));
        items.add(UItem.asCheck(ROW_ENABLED, getString(R.string.TextAnimationEnable))
            .setChecked(enabled));
        items.add(UItem.asShadow(getString(R.string.ExperimentalTypingInfo)));

        items.add(UItem.asHeader(getString(R.string.ExperimentalTypingSpeed)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.ExperimentalTypingFast),
            getString(R.string.ExperimentalTypingBalanced),
            getString(R.string.ExperimentalTypingSmooth)
        }, LumaTextAnimation.getSpeedLevel(), LumaTextAnimation::setSpeedLevel)
            .setEnabled(enabled));

        items.add(UItem.asHeader(getString(R.string.ExperimentalTypingBlur)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.ExperimentalTypingLight),
            getString(R.string.ExperimentalTypingBalanced),
            getString(R.string.ExperimentalTypingStrong)
        }, LumaTextAnimation.getBlurLevel(), LumaTextAnimation::setBlurLevel)
            .setEnabled(enabled));

        items.add(UItem.asHeader(getString(R.string.ExperimentalTypingHeight)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.ExperimentalTypingLow),
            getString(R.string.ExperimentalTypingMedium),
            getString(R.string.ExperimentalTypingHigh)
        }, LumaTextAnimation.getHeightLevel(), LumaTextAnimation::setHeightLevel)
            .setEnabled(enabled));

        items.add(UItem.asHeader(getString(R.string.ExperimentalTypingSwipe)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.ExperimentalTypingSwipeWord),
            getString(R.string.ExperimentalTypingSwipeLetters),
            getString(R.string.ExperimentalTypingSwipeOff)
        }, LumaTextAnimation.getSwipeMode(), LumaTextAnimation::setSwipeMode)
            .setEnabled(enabled));
        items.add(UItem.asShadow(getString(R.string.ExperimentalTypingSwipeInfo)));

        items.add(UItem.asButton(ROW_RESET, getString(R.string.ExperimentalTypingReset)));

        final boolean delayedSendEnabled = LumaDelayedSend.isEnabled();
        items.add(UItem.asHeader(getString(R.string.ExperimentalDelayedSendHeader)));
        items.add(UItem.asCheck(ROW_DELAYED_SEND_ENABLED, getString(R.string.ExperimentalDelayedSendEnable))
            .setChecked(delayedSendEnabled));
        items.add(UItem.asIntSlideView(
            1,
            LumaDelayedSend.MIN_STEP,
            LumaDelayedSend.getDelayStep(),
            LumaDelayedSend.MAX_STEP,
            step -> String.format(Locale.getDefault(), "%.1f %s", step / 5.0f, getString(R.string.ExperimentalDelayedSendSeconds)),
            LumaDelayedSend::setDelayStep
        ).setEnabled(delayedSendEnabled));
        items.add(UItem.asShadow(getString(R.string.ExperimentalDelayedSendInfo)));

        final boolean emergencyEnabled = LumaEmergencyMode.isEnabled(currentAccount);
        items.add(UItem.asHeader(getString(R.string.EmergencyConnectionHeader)));
        items.add(UItem.asCheck(ROW_EMERGENCY_ENABLED, getString(R.string.EmergencyConnectionEnable))
            .setChecked(emergencyEnabled));
        items.add(UItem.asButton(
            ROW_EMERGENCY_CHAT,
            getString(R.string.EmergencyConnectionChat),
            getEmergencyChatTitle()
        ));
        items.add(UItem.asShadow(getString(R.string.EmergencyConnectionInfo)));

        final boolean ghostEnabled = LumaGhostMode.isEnabled(currentAccount);
        items.add(UItem.asHeader(getString(R.string.ExperimentalGhostHeader)));
        items.add(UItem.asCheck(ROW_GHOST_ENABLED, getString(R.string.ExperimentalGhostEnable))
            .setChecked(ghostEnabled));
        items.add(UItem.asShadow(getString(R.string.ExperimentalGhostInfo)));

        items.add(UItem.asHeader(tr("Данные аккаунта", "Account data")));
        items.add(UItem.asButton(ROW_ACCOUNT_EXPORT,
                tr("Экспорт аккаунта", "Account export"),
                tr("HTML · чаты и медиа", "HTML · chats and media")));
        items.add(UItem.asShadow(tr(
                "Создаёт переносимую HTML-копию выбранных папок и типов чатов.",
                "Creates a portable HTML copy of selected folders and chat types.")));

        items.add(UItem.asShadow(getString(R.string.ExperimentalFeaturesWarning)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ROW_ENABLED) {
            final boolean enabled = !LumaTextAnimation.isEnabled();
            LumaTextAnimation.setEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
        } else if (item.id == ROW_RESET) {
            LumaTextAnimation.resetTuningSettings();
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                getString(R.string.ExperimentalTypingResetDone)
            ).show();
        } else if (item.id == ROW_DELAYED_SEND_ENABLED) {
            final boolean enabled = !LumaDelayedSend.isEnabled();
            LumaDelayedSend.setEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
        } else if (item.id == ROW_EMERGENCY_ENABLED) {
            if (LumaEmergencyMode.getDialogId(currentAccount) == 0) {
                BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.info,
                    getString(R.string.EmergencyConnectionChooseFirst)
                ).show();
                openEmergencyChatPicker();
                return;
            }
            final boolean enabled = !LumaEmergencyMode.isEnabled(currentAccount);
            LumaEmergencyMode.setEnabled(currentAccount, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                getString(enabled ? R.string.EmergencyConnectionEnabled : R.string.EmergencyConnectionDisabled)
            ).show();
        } else if (item.id == ROW_EMERGENCY_CHAT) {
            openEmergencyChatPicker();
        } else if (item.id == ROW_GHOST_ENABLED) {
            final boolean enabled = !LumaGhostMode.isEnabled(currentAccount);
            LumaGhostMode.setEnabled(currentAccount, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                getString(enabled ? R.string.ExperimentalGhostEnabled : R.string.ExperimentalGhostDisabled)
            ).show();
        } else if (item.id == ROW_ACCOUNT_EXPORT) {
            presentFragment(new LumaAccountExportActivity());
        }
    }

    private void openEmergencyChatPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", true);
        args.putBoolean("allowGlobalSearch", true);
        DialogsActivity activity = new DialogsActivity(args);
        activity.setCurrentAccount(currentAccount);
        activity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.isEmpty()) {
                return true;
            }
            LumaEmergencyMode.selectDialog(currentAccount, dids.get(0).dialogId);
            activity.finishFragment();
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                getString(R.string.EmergencyConnectionEnabled)
            ).show();
            return true;
        });
        presentFragment(activity);
    }

    private String getEmergencyChatTitle() {
        long dialogId = LumaEmergencyMode.getDialogId(currentAccount);
        if (dialogId == 0) {
            return getString(R.string.EmergencyConnectionChooseChat);
        }
        MessagesController controller = MessagesController.getInstance(currentAccount);
        TLObject peer = null;
        if (DialogObject.isUserDialog(dialogId)) {
            peer = controller.getUser(dialogId);
        } else if (DialogObject.isChatDialog(dialogId)) {
            peer = controller.getChat(-dialogId);
        } else if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat encryptedChat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            if (encryptedChat != null) {
                peer = controller.getUser(encryptedChat.user_id);
            }
        }
        String title = DialogObject.getDialogTitle(peer);
        return TextUtils.isEmpty(title) ? getString(R.string.EmergencyConnectionChooseChat) : title;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    private static String tr(String russian, String english) {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage()) ? russian : english;
    }
}
