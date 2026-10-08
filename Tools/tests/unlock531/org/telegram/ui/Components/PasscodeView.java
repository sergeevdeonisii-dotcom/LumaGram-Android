package org.telegram.ui.Components;
import android.content.Context;
import android.view.View;
import org.telegram.ui.LaunchActivity;
public class PasscodeView extends View {
    public interface PasscodeViewDelegate { void didAcceptedPassword(PasscodeView view); }
    private PasscodeViewDelegate delegate;
    public int resumes, pauses;
    public boolean shown;
    public boolean fingerprintAllowed;
    public PasscodeView(Context context) { super(context); }
    public void setDelegate(PasscodeViewDelegate value) { delegate = value; }
    public void onShow(boolean fingerprint, boolean animated) { shown = true; }
    public void onResume() {
        resumes++;
        // The native PasscodeView calls this arbitration predicate before entering its try/catch.
        fingerprintAllowed = getContext() instanceof LaunchActivity
                && ((LaunchActivity) getContext()).allowShowFingerprintDialog(this);
    }
    public void onPause() { pauses++; }
    public boolean onBackPressed() { return true; }
    protected void onHidden() {}
    public void deliverNativeAcceptanceForTest() { delegate.didAcceptedPassword(this); }
    public void completeHideForTest() { onHidden(); }
}
