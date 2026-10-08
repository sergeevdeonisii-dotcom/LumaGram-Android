package org.telegram.ui;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BlackHoleVault;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.PasscodeView;

/** Actual unlock fragment + actual arbitration method against lifecycle fixtures. No real PIN/biometric UI. */
public final class Unlock531RegressionTest {
    private static int assertions, opened;
    private static boolean legacyArbitration(LaunchActivity host, PasscodeView view) {
        // Verbatim pre-fix expression observed in LaunchActivity; it indexed -1 with both owners absent.
        return host.overlayPasscodeViews.isEmpty() && host.passcodeDialog != null
                ? view == host.passcodeDialog.passcodeView
                : host.overlayPasscodeViews.get(host.overlayPasscodeViews.size() - 1) == view;
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static BlackHoleUnlockActivity screen(LaunchActivity host) {
        BlackHoleVault.lockAll();
        UserConfig.ids[0] = 53101;
        ApplicationLoader.mainInterfacePaused = false;
        AndroidUtilities.queue.clear();
        BlackHoleUnlockActivity screen = new BlackHoleUnlockActivity(() -> opened++);
        screen.parentActivity = host;
        check(screen.onFragmentCreate(), "Logged-in owner creates unlock fragment");
        check(screen.baseCreates == 1, "Base fragment lifecycle creation retained");
        screen.createView(host);
        return screen;
    }
    public static void main(String[] args) {
        LaunchActivity unregisteredHost = new LaunchActivity();
        PasscodeView unregistered = new PasscodeView(unregisteredHost);
        boolean legacyCrashed = false;
        try { legacyArbitration(unregisteredHost, unregistered); }
        catch (IndexOutOfBoundsException expected) { legacyCrashed = true; }
        check(legacyCrashed, "Verbatim previous arbitration crashes for empty overlay list and null dialog");
        boolean deniedOrThrew = false;
        try { deniedOrThrew = !unregisteredHost.allowShowFingerprintDialog(unregistered); }
        catch (IndexOutOfBoundsException expected) { deniedOrThrew = true; }
        check(deniedOrThrew, "Unregistered private view does not qualify for native fingerprint arbitration");
        check(!unregisteredHost.allowShowFingerprintDialog(null), "Null biometric view is denied");
        check(!unregisteredHost.allowShowFingerprintDialog(unregistered), "Zero overlays and null dialog fail closed without throwing");
        unregisteredHost.passcodeDialog = new LaunchActivity.PasscodeDialog();
        unregisteredHost.passcodeDialog.passcodeView = unregistered;
        check(unregisteredHost.allowShowFingerprintDialog(unregistered), "Native host dialog is eligible when no overlay is present");
        PasscodeView top = new PasscodeView(unregisteredHost);
        unregisteredHost.addOverlayPasscodeView(top);
        check(unregisteredHost.allowShowFingerprintDialog(top) && !unregisteredHost.allowShowFingerprintDialog(unregistered),
                "Only top native overlay is eligible, not host dialog behind it");
        unregisteredHost.removeOverlayPasscodeView(top);
        check(unregisteredHost.allowShowFingerprintDialog(unregistered), "Removing overlay restores host dialog eligibility");

        LaunchActivity host = new LaunchActivity();
        BlackHoleUnlockActivity screen = screen(host);
        PasscodeView view = (PasscodeView) screen.fragmentView;
        check(view.shown, "Native PIN view initialization retained");
        screen.onResume();
        check(host.overlayPasscodeViews.size() == 1 && host.overlayPasscodeViews.get(0) == view && view.fingerprintAllowed,
                "View registered before native resume/arbitration; no empty-list crash");
        screen.onResume();
        check(host.overlayPasscodeViews.size() == 1 && view.resumes == 2, "Repeated resume does not duplicate registration");
        screen.onPause();
        check(host.overlayPasscodeViews.isEmpty() && view.pauses == 1 && screen.isPaused(), "Pause unregisters and cancels native retry work");
        int before = BlackHoleVault.acceptCalls;
        view.deliverNativeAcceptanceForTest();
        check(BlackHoleVault.acceptCalls == before && !BlackHoleVault.isUnlocked(0), "Late paused acceptance cannot unlock");
        screen.onResume();
        check(host.overlayPasscodeViews.size() == 1, "Returning fragment restores single registration");
        screen.parentActivity = null;
        screen.onResume();
        check(host.overlayPasscodeViews.isEmpty(), "Missing parent releases registration from previous host");
        view.deliverNativeAcceptanceForTest();
        check(BlackHoleVault.acceptCalls == before, "Missing-parent callback cannot authenticate a detached fragment");
        screen.parentActivity = host;
        screen.onResume();
        UserConfig.ids[0] = 53102;
        view.deliverNativeAcceptanceForTest();
        check(BlackHoleVault.acceptCalls == before, "Replacement account cannot accept previous owner's callback");
        UserConfig.ids[0] = 53101;
        BlackHoleVault.lockAll();
        view.deliverNativeAcceptanceForTest();
        check(BlackHoleVault.acceptCalls == before, "Revoked authentication epoch cannot unlock");
        screen.onFragmentDestroy();
        check(host.overlayPasscodeViews.isEmpty(), "Destruction removes native overlay registration");
        view.deliverNativeAcceptanceForTest();
        check(BlackHoleVault.acceptCalls == before, "Destroyed fragment cannot accept native callback");
        screen.onResume();
        check(host.overlayPasscodeViews.isEmpty(), "Destroyed fragment cannot register behind another page");

        host = new LaunchActivity(); screen = screen(host); view = (PasscodeView) screen.fragmentView;
        screen.onResume(); view.deliverNativeAcceptanceForTest();
        check(BlackHoleVault.isUnlocked(0), "Valid active-owner native acceptance retains vault authentication");
        before = opened;
        view.completeHideForTest(); AndroidUtilities.flush();
        check(opened == before + 1 && screen.finishes == 1, "Successful hide opens protected screen once");
        view.completeHideForTest(); AndroidUtilities.flush();
        check(opened == before + 1 && screen.finishes == 1, "Duplicate hide callback is ignored");

        host = new LaunchActivity(); screen = screen(host); view = (PasscodeView) screen.fragmentView;
        screen.onResume(); view.deliverNativeAcceptanceForTest(); view.completeHideForTest();
        before = opened;
        UserConfig.ids[0] = 53102; AndroidUtilities.flush();
        check(opened == before, "Owner replacement before queued continuation blocks delivery");

        host = new LaunchActivity(); screen = screen(host); view = (PasscodeView) screen.fragmentView;
        screen.onResume(); view.deliverNativeAcceptanceForTest(); view.completeHideForTest();
        before = opened;
        BlackHoleVault.lockAll(); AndroidUtilities.flush();
        check(opened == before, "Background lock/revoked epoch before continuation blocks delivery");

        host = new LaunchActivity(); screen = screen(host); view = (PasscodeView) screen.fragmentView;
        screen.onResume(); view.deliverNativeAcceptanceForTest();
        ApplicationLoader.mainInterfacePaused = true;
        screen.onPause(); view.completeHideForTest();
        before = opened; AndroidUtilities.flush();
        check(opened == before, "Backgrounded application cannot deliver accepted continuation");

        host = new LaunchActivity(); screen = screen(host); view = (PasscodeView) screen.fragmentView;
        screen.onResume();
        screen.createView(host);
        check(host.overlayPasscodeViews.isEmpty(), "View recreation removes previous overlay registration");
        screen.onResume();
        before = BlackHoleVault.acceptCalls;
        view.deliverNativeAcceptanceForTest(); view.completeHideForTest();
        check(BlackHoleVault.acceptCalls == before && screen.finishes == 0, "Replaced native view cannot accept or close the new view");
        check(host.overlayPasscodeViews.size() == 1 && host.overlayPasscodeViews.get(0) == screen.fragmentView,
                "Recreated current view is the only registered overlay");
        screen.onFragmentDestroy();
        check(host.overlayPasscodeViews.isEmpty(), "Recreated registration released on destruction");
        System.out.println("PASS: " + assertions + " unlock lifecycle/arbitration assertions. Actual fragment and arbitration method; lifecycle fixtures only, no device PIN/fingerprint validation.");
    }
}
