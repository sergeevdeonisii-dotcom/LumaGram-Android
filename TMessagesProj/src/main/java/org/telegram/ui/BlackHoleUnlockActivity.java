package org.telegram.ui;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BlackHoleVault;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.PasscodeView;

/** Uses Telegram's existing PIN, retry throttling and configured fingerprint authentication. */
public final class BlackHoleUnlockActivity extends BaseFragment {
    private final Runnable onAccepted;
    private PasscodeView passcode;
    private long owner;
    private long epoch;
    private boolean accepted;
    public BlackHoleUnlockActivity(Runnable onAccepted) { this.onAccepted = onAccepted; }
    @Override public boolean onFragmentCreate() {
        owner = UserConfig.getInstance(currentAccount).getClientUserId(); epoch = BlackHoleVault.authenticationEpoch(); return owner > 0;
    }
    @Override public View createView(Context context) {
        actionBar.setVisibility(View.GONE);
        passcode = new PasscodeView(context) {
            @Override protected void onHidden() {
                finishFragment();
                if (accepted && UserConfig.getInstance(currentAccount).getClientUserId() == owner && onAccepted != null)
                    AndroidUtilities.runOnUIThread(onAccepted);
            }
        };
        passcode.setDelegate(view -> {
            accepted = !org.telegram.messenger.ApplicationLoader.mainInterfacePaused && epoch == BlackHoleVault.authenticationEpoch()
                    && owner == UserConfig.getInstance(currentAccount).getClientUserId();
            if (accepted) BlackHoleVault.acceptAuthentication(currentAccount, owner, epoch);
        });
        passcode.onShow(true, false);
        return fragmentView = passcode;
    }
    @Override public void onResume() { super.onResume(); if (passcode != null) passcode.onResume(); }
    @Override public void onPause() {
        if (passcode != null) passcode.onPause();
        if (!accepted && org.telegram.messenger.ApplicationLoader.mainInterfacePaused) {
            if (fragmentView != null) fragmentView.setVisibility(View.INVISIBLE);
            AndroidUtilities.runOnUIThread(() -> { if (!isFinished) finishFragment(false); });
        }
        super.onPause();
    }
    @Override public boolean onBackPressed(boolean invoked) {
        return passcode == null || passcode.onBackPressed();
    }
}
