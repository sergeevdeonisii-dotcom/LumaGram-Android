package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LumaDelayedSend;
import org.telegram.messenger.LumaEmergencyMode;
import org.telegram.messenger.LumaGhostMode;
import org.telegram.messenger.LumaDeletedMessages;
import org.telegram.messenger.LumaAnonymousNumber;
import org.telegram.messenger.LumaProfileVerification;
import org.telegram.messenger.LumaStarRating;
import org.telegram.messenger.LumaTextAnimation;
import org.telegram.messenger.LumaRoundVideoQuality;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Locale;

public class ExperimentalFeaturesActivity extends BaseFragment {

    private enum Section {
        ROOT(0, R.string.ExperimentalFeaturesTitle),
        PRIVACY(101, R.string.BlackHoleAdvancedPrivacyTitle),
        PROFILE(102, R.string.BlackHoleAdvancedProfileTitle),
        TYPING(103, R.string.BlackHoleAdvancedTypingTitle),
        SENDING(104, R.string.BlackHoleAdvancedSendingTitle),
        CONNECTION(105, R.string.BlackHoleAdvancedConnectionTitle);

        final int id;
        final int titleRes;

        Section(int id, int titleRes) {
            this.id = id;
            this.titleRes = titleRes;
        }

        static Section fromId(int id) {
            for (Section section : values()) {
                if (section != ROOT && section.id == id) {
                    return section;
                }
            }
            return null;
        }
    }

    private static final int ROW_ENABLED = 1;
    private static final int ROW_RESET = 2;
    private static final int ROW_DELAYED_SEND_ENABLED = 3;
    private static final int ROW_ACCOUNT_EXPORT = 4;
    private static final int ROW_EMERGENCY_ENABLED = 5;
    private static final int ROW_EMERGENCY_CHAT = 6;
    private static final int ROW_GHOST_ENABLED = 7;
    private static final int ROW_STAR_RATING_ENABLED = 8;
    private static final int ROW_STAR_RATING_LEVEL = 9;
    private static final int ROW_DELETED_MESSAGES_ENABLED = 10;
    private static final int ROW_ANONYMOUS_NUMBER_ENABLED = 11;
    private static final int ROW_ANONYMOUS_NUMBER = 12;
    private static final int ROW_PROFILE_VERIFICATION_ENABLED = 13;
    private static final int ROW_GHOST_SCHEDULE_SEND_ENABLED = 14;
    private static final int ROW_ROUND_VIDEO_QUALITY = 15;

    private final Section section;
    private UniversalRecyclerView listView;

    public ExperimentalFeaturesActivity() {
        this(Section.ROOT);
    }

    private ExperimentalFeaturesActivity(Section section) {
        this.section = section;
    }

    public static ExperimentalFeaturesActivity forSection(int id) {
        Section section = Section.fromId(id);
        return new ExperimentalFeaturesActivity(section == null ? Section.ROOT : section);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(section.titleRes));
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
        switch (section) {
            case ROOT:
                fillSections(items);
                break;
            case PRIVACY:
                fillPrivacyItems(items);
                break;
            case PROFILE:
                fillProfileItems(items);
                break;
            case TYPING:
                fillTypingItems(items);
                break;
            case SENDING:
                fillSendingItems(items);
                break;
            case CONNECTION:
                fillConnectionItems(items);
                break;
        }
    }

    private void fillSections(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.BlackHoleAdvancedAccountHeader)));
        items.add(sectionItem(Section.PRIVACY, IconBackgroundColors.GREEN,
                R.drawable.settings_privacy, R.string.BlackHoleAdvancedPrivacyInfo));
        items.add(sectionItem(Section.PROFILE, IconBackgroundColors.BLUE,
                R.drawable.settings_account, R.string.BlackHoleAdvancedProfileInfo));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.BlackHoleAdvancedAppHeader)));
        items.add(sectionItem(Section.TYPING, IconBackgroundColors.PURPLE,
                R.drawable.settings_features, R.string.BlackHoleAdvancedTypingInfo));
        items.add(sectionItem(Section.SENDING, IconBackgroundColors.ORANGE,
                R.drawable.settings_chat, R.string.BlackHoleAdvancedSendingInfo));
        items.add(sectionItem(Section.CONNECTION, IconBackgroundColors.CYAN,
                R.drawable.settings_data, R.string.BlackHoleAdvancedConnectionInfo));
        items.add(UItem.asShadow(getString(R.string.BlackHoleAdvancedSectionsInfo)));
    }

    private UItem sectionItem(Section section, IconBackgroundColors colors, int iconRes, int infoRes) {
        return SettingsActivity.SettingCell.Factory.of(section.id, colors.top, colors.bottom,
                iconRes, getString(section.titleRes), getString(infoRes));
    }

    private void fillTypingItems(ArrayList<UItem> items) {
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
        items.add(UItem.asShadow(getString(R.string.ExperimentalFeaturesWarning)));
    }

    private void fillSendingItems(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.LumaRoundVideoQualityTitle)));
        items.add(UItem.asCheck(ROW_ROUND_VIDEO_QUALITY, getString(R.string.LumaRoundVideoQualityEnable))
                .setChecked(LumaRoundVideoQuality.isEnabled()));
        items.add(UItem.asShadow(getString(R.string.LumaRoundVideoQualityInfo)));
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
    }

    private void fillConnectionItems(ArrayList<UItem> items) {
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

        items.add(UItem.asHeader(tr("Данные аккаунта", "Account data")));
        items.add(UItem.asButton(ROW_ACCOUNT_EXPORT,
                tr("Экспорт аккаунта", "Account export"),
                tr("HTML · чаты и медиа", "HTML · chats and media")));
        items.add(UItem.asShadow(tr(
                "Создаёт переносимую HTML-копию выбранных папок и типов чатов.",
                "Creates a portable HTML copy of selected folders and chat types.")));
    }

    private void fillPrivacyItems(ArrayList<UItem> items) {
        final boolean ghostEnabled = LumaGhostMode.isEnabled(currentAccount);
        items.add(UItem.asHeader(getString(R.string.ExperimentalGhostHeader)));
        items.add(UItem.asCheck(ROW_GHOST_ENABLED, getString(R.string.ExperimentalGhostEnable))
            .setChecked(ghostEnabled));
        items.add(UItem.asCheck(
            ROW_GHOST_SCHEDULE_SEND_ENABLED,
            LocaleController.formatString(R.string.ExperimentalGhostScheduledSend, LumaGhostMode.SCHEDULE_SEND_DELAY_SECONDS)
        ).setChecked(LumaGhostMode.isScheduledSendEnabled(currentAccount)).setEnabled(ghostEnabled));
        items.add(UItem.asShadow(LocaleController.formatString(
            R.string.ExperimentalGhostScheduledSendInfo,
            LumaGhostMode.SCHEDULE_SEND_DELAY_SECONDS
        ) + "\n\n" + getString(R.string.ExperimentalGhostInfo)));

        final boolean deletedMessagesEnabled = LumaDeletedMessages.isEnabled(currentAccount);
        items.add(UItem.asHeader(getString(R.string.ExperimentalDeletedMessagesHeader)));
        items.add(UItem.asCheck(ROW_DELETED_MESSAGES_ENABLED, getString(R.string.ExperimentalDeletedMessagesEnable))
            .setChecked(deletedMessagesEnabled));
        items.add(UItem.asShadow(getString(R.string.ExperimentalDeletedMessagesInfo)));
    }

    private void fillProfileItems(ArrayList<UItem> items) {
        final boolean starRatingEnabled = LumaStarRating.isEnabled(currentAccount);
        items.add(UItem.asHeader(getString(R.string.ExperimentalStarRatingHeader)));
        items.add(UItem.asCheck(ROW_STAR_RATING_ENABLED, getString(R.string.ExperimentalStarRatingEnable))
            .setChecked(starRatingEnabled));
        items.add(UItem.asButton(
            ROW_STAR_RATING_LEVEL,
            LocaleController.formatString(R.string.ExperimentalStarRatingLevel, LumaStarRating.getLevel(currentAccount))
        ).setEnabled(starRatingEnabled));
        items.add(UItem.asShadow(getString(R.string.ExperimentalStarRatingInfo)));

        final boolean anonymousNumberEnabled = LumaAnonymousNumber.isEnabled(currentAccount);
        items.add(UItem.asHeader(getString(R.string.ExperimentalAnonymousNumberHeader)));
        items.add(UItem.asCheck(ROW_ANONYMOUS_NUMBER_ENABLED, getString(R.string.ExperimentalAnonymousNumberEnable))
            .setChecked(anonymousNumberEnabled));
        items.add(UItem.asButton(
            ROW_ANONYMOUS_NUMBER,
            LocaleController.formatString(R.string.ExperimentalAnonymousNumberValue, "+" + LumaAnonymousNumber.getPhone(currentAccount))
        ).setEnabled(anonymousNumberEnabled));
        items.add(UItem.asShadow(getString(R.string.ExperimentalAnonymousNumberInfo)));

        final boolean localVerificationEnabled = LumaProfileVerification.isEnabled(currentAccount);
        items.add(UItem.asHeader(getString(R.string.ExperimentalProfileVerificationHeader)));
        items.add(UItem.asCheck(ROW_PROFILE_VERIFICATION_ENABLED, getString(R.string.ExperimentalProfileVerificationEnable))
            .setChecked(localVerificationEnabled));
        items.add(UItem.asShadow(getString(R.string.ExperimentalProfileVerificationInfo)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (section == Section.ROOT) {
            Section destination = Section.fromId(item.id);
            if (destination != null) {
                ExperimentalFeaturesActivity screen = new ExperimentalFeaturesActivity(destination);
                screen.setCurrentAccount(currentAccount);
                presentFragment(screen);
            }
            return;
        }
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
        } else if (item.id == ROW_ROUND_VIDEO_QUALITY) {
            final boolean enabled = !LumaRoundVideoQuality.isEnabled();
            LumaRoundVideoQuality.setEnabled(enabled);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(enabled);
            if (listView != null && listView.adapter != null) listView.adapter.update(false);
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
        } else if (item.id == ROW_GHOST_SCHEDULE_SEND_ENABLED) {
            final boolean enabled = !LumaGhostMode.isScheduledSendEnabled(currentAccount);
            LumaGhostMode.setScheduledSendEnabled(currentAccount, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                enabled
                    ? LocaleController.formatString(R.string.ExperimentalGhostScheduledSendEnabled, LumaGhostMode.SCHEDULE_SEND_DELAY_SECONDS)
                    : getString(R.string.ExperimentalGhostScheduledSendDisabled)
            ).show();
        } else if (item.id == ROW_STAR_RATING_ENABLED) {
            final boolean enabled = !LumaStarRating.isEnabled(currentAccount);
            LumaStarRating.setEnabled(currentAccount, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                getString(enabled ? R.string.ExperimentalStarRatingEnabled : R.string.ExperimentalStarRatingDisabled)
            ).show();
        } else if (item.id == ROW_STAR_RATING_LEVEL) {
            showStarRatingLevelDialog();
        } else if (item.id == ROW_ANONYMOUS_NUMBER_ENABLED) {
            final boolean enabled = !LumaAnonymousNumber.isEnabled(currentAccount);
            LumaAnonymousNumber.setEnabled(currentAccount, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                getString(enabled ? R.string.ExperimentalAnonymousNumberEnabled : R.string.ExperimentalAnonymousNumberDisabled)
            ).show();
        } else if (item.id == ROW_ANONYMOUS_NUMBER) {
            showAnonymousNumberDialog();
        } else if (item.id == ROW_PROFILE_VERIFICATION_ENABLED) {
            final boolean enabled = !LumaProfileVerification.isEnabled(currentAccount);
            LumaProfileVerification.setEnabled(currentAccount, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                getString(enabled ? R.string.ExperimentalProfileVerificationEnabled : R.string.ExperimentalProfileVerificationDisabled)
            ).show();
        } else if (item.id == ROW_DELETED_MESSAGES_ENABLED) {
            final boolean enabled = !LumaDeletedMessages.isEnabled(currentAccount);
            LumaDeletedMessages.setEnabled(currentAccount, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                getString(enabled ? R.string.ExperimentalDeletedMessagesEnabled : R.string.ExperimentalDeletedMessagesDisabled)
            ).show();
        } else if (item.id == ROW_ACCOUNT_EXPORT) {
            LumaAccountExportActivity screen = new LumaAccountExportActivity();
            screen.setCurrentAccount(currentAccount);
            presentFragment(screen);
        }
    }

    private void showStarRatingLevelDialog() {
        final EditText editText = new EditText(getParentActivity());
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setSingleLine(true);
        editText.setText(String.valueOf(LumaStarRating.getLevel(currentAccount)));
        editText.setSelectAllOnFocus(true);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle(getString(R.string.ExperimentalStarRatingChooseTitle));
        builder.setView(editText);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.OK), (dialog, which) -> {
            int level;
            try {
                level = Integer.parseInt(editText.getText().toString());
            } catch (Exception e) {
                level = 0;
            }
            if (level < LumaStarRating.MIN_LEVEL || level > LumaStarRating.MAX_LEVEL) {
                BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.info,
                    getString(R.string.ExperimentalStarRatingInvalid)
                ).show();
                return;
            }
            LumaStarRating.setLevel(currentAccount, level);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
        });
        showDialog(builder.create());
        editText.requestFocus();
    }

    private void showAnonymousNumberDialog() {
        final EditText editText = new EditText(getParentActivity());
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setSingleLine(true);
        editText.setHint("00000000");
        editText.setText(LumaAnonymousNumber.getDigits(currentAccount));
        editText.setSelectAllOnFocus(true);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle(getString(R.string.ExperimentalAnonymousNumberChooseTitle));
        builder.setMessage(getString(R.string.ExperimentalAnonymousNumberChooseInfo));
        builder.setView(editText);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.OK), (dialog, which) -> {
            String value = editText.getText().toString();
            if (value.replaceAll("\\D", "").length() != 8) {
                BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.info,
                    getString(R.string.ExperimentalAnonymousNumberInvalid)
                ).show();
                return;
            }
            LumaAnonymousNumber.setDigits(currentAccount, value);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
        });
        showDialog(builder.create());
        editText.requestFocus();
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
