package android.view;

import java.util.ArrayList;
import java.util.List;

/** Deterministic main-thread callback queue; does not model Android drawing. */
public class View {
    private final List<OnAttachStateChangeListener> listeners = new ArrayList<>();
    private final List<Runnable> frames = new ArrayList<>();
    public int addedListeners;
    public int postedFrames;
    public interface OnAttachStateChangeListener {
        void onViewAttachedToWindow(View view);
        void onViewDetachedFromWindow(View view);
    }
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
        listeners.add(listener);
        addedListeners++;
    }
    public void removeOnAttachStateChangeListener(OnAttachStateChangeListener listener) { listeners.remove(listener); }
    public boolean removeCallbacks(Runnable callback) { return frames.removeIf(item -> item == callback); }
    public void postOnAnimation(Runnable callback) { frames.add(callback); postedFrames++; }
    public int pendingFrames() { return frames.size(); }
    public int listenerCount() { return listeners.size(); }
    public void advanceFrame() {
        List<Runnable> work = new ArrayList<>(frames);
        frames.clear();
        for (Runnable callback : work) callback.run();
    }
    public void detach() {
        for (OnAttachStateChangeListener listener : new ArrayList<>(listeners)) listener.onViewDetachedFromWindow(this);
    }
}
