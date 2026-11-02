package org.telegram.ui.Components;

import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import java.util.function.BooleanSupplier;

/** Recheck the originating message/owner before native Copy, Cut, Share or Process Text. */
public final class LunagramTextSelectionGuard implements ActionMode.Callback {
    private final BooleanSupplier allowed;
    private final Runnable revoked;
    public LunagramTextSelectionGuard(BooleanSupplier allowed, Runnable revoked) {
        this.allowed = allowed;
        this.revoked = revoked;
    }
    private boolean valid(ActionMode mode) {
        if (allowed.getAsBoolean()) return true;
        mode.finish();
        revoked.run();
        return false;
    }
    @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { return valid(mode); }
    @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return valid(mode); }
    @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        // false delegates a still-authorized action to TextView, true consumes a revoked action.
        return !valid(mode);
    }
    @Override public void onDestroyActionMode(ActionMode mode) {}
}
