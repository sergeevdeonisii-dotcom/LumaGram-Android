package org.telegram.ui.ActionBar;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
/** Modeled Telegram sheet seam; Android Dialog/View APIs come from the real supplied SDK. */
public class BottomSheet extends Dialog {
    protected ViewGroup containerView;
    public BottomSheet(Context context, boolean focus) { super(context); containerView = new FrameLayout(context); }
    public void setShowWithoutAnimation(boolean value) {}
    public void setApplyTopPadding(boolean value) {}
    public void setApplyBottomPadding(boolean value) {}
    public void setAllowNestedScroll(boolean value) {}
    public void setCanDismissWithSwipe(boolean value) {}
    public void skipDismissAnimation() {}
    protected void cancelSheetAnimation() {}
    public void fixNavigationBar(int value) {}
    public void setCustomView(View view) { setContentView(view); }
    public void dismissInternal() { super.dismiss(); }
}
