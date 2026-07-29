package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LumaDelayedSend;
import org.telegram.messenger.LumaTextAnimation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
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
        } else if (item.id == ROW_ACCOUNT_EXPORT) {
            presentFragment(new LumaAccountExportActivity());
        }
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
