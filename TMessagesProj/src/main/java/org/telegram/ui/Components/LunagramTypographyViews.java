package org.telegram.ui.Components;

import android.app.Activity;
import android.app.Application;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.TextView;

import org.telegram.messenger.LunagramTypography;
import org.telegram.ui.Components.Paint.Views.EditTextOutline;
import org.telegram.ui.Components.Paint.Views.TextPaintView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.WeakHashMap;

/** Startup font for native labels. One-shot lifecycle/row-attachment work, never per-frame traversal. */
public final class LunagramTypographyViews {
    private static final WeakHashMap<View, Pass> pending = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> ignored = new WeakHashMap<>();
    private static final WeakHashMap<TextView, Typeface> applied = new WeakHashMap<>();
    private static boolean installed;

    private LunagramTypographyViews() { }

    public static void ignore(View view) { ignored.put(view, Boolean.TRUE); }

    public static void install(Application application) {
        if (installed) return;
        installed = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { attach(activity.getWindow()); }
            @Override public void onActivityResumed(Activity activity) { attach(activity.getWindow()); }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    public static void attach(Window window) {
        if (window != null) apply(window.getDecorView());
    }

    public static void apply(View root) {
        if (root == null || LunagramTypography.isDefault() || pending.containsKey(root)) return;
        Pass pass = new Pass(root);
        pending.put(root, pass);
        pass.run();
    }

    private static final class Pass implements Runnable, View.OnAttachStateChangeListener {
        private final WeakReference<View> root;
        private final ArrayDeque<View> queue = new ArrayDeque<>();

        Pass(View view) {
            root = new WeakReference<>(view);
            queue.add(view);
            view.addOnAttachStateChangeListener(this);
        }

        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { finish(); }

        private void finish() {
            View view = root.get();
            if (view != null) {
                view.removeCallbacks(this);
                view.removeOnAttachStateChangeListener(this);
                pending.remove(view);
            }
            queue.clear();
        }

        @Override public void run() {
            View view = root.get();
            if (view == null) { queue.clear(); return; }
            // Native rows are small and finish immediately. Large new screens are split into chunks.
            for (int n = 0; n < 160 && !queue.isEmpty(); n++) {
                View child = queue.removeFirst();
                if (child instanceof WebView || child instanceof TextPaintView
                        || child instanceof EditTextOutline || ignored.containsKey(child)) continue;
                if (child instanceof TextView) {
                    TextView text = (TextView) child;
                    if (!(text.getTransformationMethod() instanceof PasswordTransformationMethod)) {
                        Typeface current = text.getTypeface();
                        if (!applied.containsKey(text) || applied.get(text) != current) {
                            Typeface next = LunagramTypography.resolve(current);
                            applied.put(text, next);
                            if (next != current) text.setTypeface(next);
                        }
                    }
                }
                if (child instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) child;
                    for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
                }
            }
            if (queue.isEmpty()) finish(); else view.postOnAnimation(this);
        }
    }
}
