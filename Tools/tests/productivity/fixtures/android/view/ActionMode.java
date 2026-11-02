package android.view;
public abstract class ActionMode {
    public abstract void finish();
    public interface Callback {
        boolean onCreateActionMode(ActionMode mode,Menu menu);
        boolean onPrepareActionMode(ActionMode mode,Menu menu);
        boolean onActionItemClicked(ActionMode mode,MenuItem item);
        void onDestroyActionMode(ActionMode mode);
    }
}
