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
    private LaunchActivity passcodeHost;
    private PasscodeView registeredPasscode;
    public BlackHoleUnlockActivity(Runnable onAccepted) { this.onAccepted = onAccepted; }
    @Override public boolean onFragmentCreate() {
        owner = UserConfig.getInstance(currentAccount).getClientUserId(); epoch = BlackHoleVault.authenticationEpoch(); return owner > 0 && super.onFragmentCreate();
    }
    @Override public View createView(Context context) {
        unregisterPasscodeView();
        accepted = false;
        actionBar.setVisibility(View.GONE);
        passcode = new PasscodeView(context) {
            @Override protected void onHidden() {
                if (isFinished || this != passcode || getParentActivity() == null) return;
                finishFragment();
                if (accepted && onAccepted != null) AndroidUtilities.runOnUIThread(() -> {
                    if (!org.telegram.messenger.ApplicationLoader.mainInterfacePaused
                            && owner > 0 && owner == UserConfig.getInstance(currentAccount).getClientUserId()
                            && epoch == BlackHoleVault.authenticationEpoch() && BlackHoleVault.isUnlocked(currentAccount))
                        onAccepted.run();
                });
            }
        };
        passcode.setDelegate(view -> {
            accepted = !isFinished && !isPaused() && getParentActivity() != null && view == passcode
                    && !org.telegram.messenger.ApplicationLoader.mainInterfacePaused && epoch == BlackHoleVault.authenticationEpoch()
                    && owner > 0 && owner == UserConfig.getInstance(currentAccount).getClientUserId();
            if (accepted) BlackHoleVault.acceptAuthentication(currentAccount, owner, epoch);
        });
        passcode.onShow(true, false);
        return fragmentView = passcode;
    }
    private void registerPasscodeView() {
        if (isFinished || isPaused() || !(getParentActivity() instanceof LaunchActivity) || passcode == null) {
            unregisterPasscodeView();
            return;
        }
        LaunchActivity host = (LaunchActivity) getParentActivity();
        if (passcodeHost == host && registeredPasscode == passcode) return;
        unregisterPasscodeView();
        passcodeHost = host;
        registeredPasscode = passcode;
        host.addOverlayPasscodeView(passcode);
    }
    private void unregisterPasscodeView() {
        if (passcodeHost != null && registeredPasscode != null) passcodeHost.removeOverlayPasscodeView(registeredPasscode);
        passcodeHost = null;
        registeredPasscode = null;
    }
    @Override public void onResume() {
        super.onResume();
        if (isFinished) return;
        // Native fingerprint arbitration requires registration before PasscodeView.onResume().
        registerPasscodeView();
        if (passcode != null) passcode.onResume();
    }
    @Override public void onPause() {
        super.onPause();
        unregisterPasscodeView();
        if (passcode != null) passcode.onPause();
        if (!accepted && org.telegram.messenger.ApplicationLoader.mainInterfacePaused) {
            if (fragmentView != null) fragmentView.setVisibility(View.INVISIBLE);
            AndroidUtilities.runOnUIThread(() -> { if (!isFinished) finishFragment(false); });
        }
    }
    @Override public void onFragmentDestroy() {
        unregisterPasscodeView();
        if (passcode != null) passcode.onPause();
        super.onFragmentDestroy();
    }
    @Override public boolean onBackPressed(boolean invoked) {
        return passcode == null || passcode.onBackPressed();
    }
}
