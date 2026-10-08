package org.telegram.ui.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.view.View;
public class BaseFragment {
    public int currentAccount, finishes, baseCreates;
    public boolean isFinished;
    private boolean paused = true;
    public Activity parentActivity;
    public View actionBar = new View(new Context()), fragmentView;
    public boolean onFragmentCreate() { baseCreates++; return true; }
    public View createView(Context context) { return null; }
    public Activity getParentActivity() { return parentActivity; }
    public boolean isPaused() { return paused; }
    public void onResume() { paused = false; }
    public void onPause() { paused = true; }
    public void onFragmentDestroy() { isFinished = true; }
    public void finishFragment() { finishes++; isFinished = true; }
    public void finishFragment(boolean animated) { finishFragment(); }
    public boolean onBackPressed(boolean invoked) { return true; }
}
