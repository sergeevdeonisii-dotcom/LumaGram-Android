package org.telegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.text.method.PasswordTransformationMethod;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.TextView;
import org.telegram.ui.Components.LunagramTypographyViews;
import org.telegram.ui.Components.Paint.Views.EditTextOutline;
import org.telegram.ui.Components.Paint.Views.TextPaintView;

public final class TypographyViewsProbe {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static void same(Object expected, Object actual, String label) { check(expected == actual, label); }
    private static TextView text(Typeface face) {
        TextView result = new TextView();
        if (face != null) result.setTypeface(face);
        return result;
    }

    private static void viewBoundary(boolean defaults) {
        ViewGroup root = new ViewGroup();
        TextView label = text(null);
        TextView emphasis = text(Typeface.DEFAULT_BOLD);
        TextView code = text(Typeface.MONOSPACE);
        Typeface artworkFace = Typeface.create("editor-artwork", Typeface.ITALIC);
        TextView artwork = text(artworkFace);
        TextView password = text(null);
        password.setTransformationMethod(new PasswordTransformationMethod());
        TextView preview = text(null);
        LunagramTypographyViews.ignore(preview);
        ViewGroup ignoredGroup = new ViewGroup();
        TextView ignoredLabel = text(null);
        ignoredGroup.addView(ignoredLabel);
        LunagramTypographyViews.ignore(ignoredGroup);
        WebView web = new WebView();
        TextView webLabel = text(null);
        web.addView(webLabel);
        TextPaintView painting = new TextPaintView();
        TextView paintLabel = text(null);
        painting.addView(paintLabel);
        EditTextOutline outline = new EditTextOutline();
        for (android.view.View child : new android.view.View[]{label, emphasis, code, artwork, password,
                preview, ignoredGroup, web, painting, outline}) root.addView(child);
        LunagramTypographyViews.apply(null);
        LunagramTypographyViews.attach(null);
        LunagramTypographyViews.apply(root);
        if (defaults) {
            check(root.childReads == 0 && root.addedListeners == 0 && root.postedFrames == 0,
                    "default performs no traversal or lifecycle registration");
            same(null, label.getTypeface(), "default native labels remain untouched");
        } else {
            same(LunagramTypography.resolve(null), label.getTypeface(), "native label receives active startup face");
            same(LunagramTypography.resolve(Typeface.DEFAULT_BOLD), emphasis.getTypeface(), "native emphasis keeps its role");
            check(label.typefaceWrites == 1, "native label applied exactly once");
            check(root.listenerCount() == 0 && root.pendingFrames() == 0, "small pass releases listener immediately");
        }
        same(Typeface.MONOSPACE, code.getTypeface(), "code label remains exact");
        same(artworkFace, artwork.getTypeface(), "artwork label remains exact");
        same(null, password.getTypeface(), "password transformation excludes label");
        same(null, preview.getTypeface(), "ignored preview excludes label");
        same(null, ignoredLabel.getTypeface(), "ignored parent excludes its subtree");
        same(null, webLabel.getTypeface(), "web view excludes its subtree");
        same(null, paintLabel.getTypeface(), "paint editor excludes its subtree");
        same(null, outline.getTypeface(), "outline editor excludes native text face");
        check(ignoredGroup.childReads == 0 && web.childReads == 0 && painting.childReads == 0,
                "excluded subtrees are never traversed");
        check("Original message content".equals(label.text), "view application preserves text contents");
        int writes = label.typefaceWrites;
        LunagramTypographyViews.apply(root);
        check(label.typefaceWrites == writes, "repeat pass does not rewrite mapped face");
        if (!defaults) {
            label.setTypeface(Typeface.DEFAULT_BOLD);
            LunagramTypographyViews.apply(label);
            same(LunagramTypography.resolve(Typeface.DEFAULT_BOLD), label.getTypeface(), "rebound label face is reapplied");
        }
    }

    private static void chunkLifecycle() {
        ViewGroup root = new ViewGroup();
        TextView[] labels = new TextView[400];
        for (int index = 0; index < labels.length; index++) root.addView(labels[index] = text(null));
        LunagramTypographyViews.apply(root);
        check(labels[0].typefaceWrites == 1 && labels[399].typefaceWrites == 0,
                "large screen work is bounded to one initial chunk");
        check(root.pendingFrames() == 1 && root.listenerCount() == 1, "unfinished pass schedules one continuation");
        int listenerAdds = root.addedListeners;
        LunagramTypographyViews.apply(root);
        check(root.pendingFrames() == 1 && root.addedListeners == listenerAdds,
                "duplicate root pass cannot multiply queued work");
        root.detach();
        check(root.pendingFrames() == 0 && root.listenerCount() == 0, "detach cancels continuation and listener");
        root.advanceFrame();
        check(labels[399].typefaceWrites == 0, "detached pass cannot touch remaining labels");
        LunagramTypographyViews.apply(root);
        int frames = 0;
        while (root.pendingFrames() != 0 && frames++ < 10) root.advanceFrame();
        check(root.pendingFrames() == 0 && root.listenerCount() == 0, "reattached root can complete a new bounded pass");
        check(labels[399].typefaceWrites == 1 && labels[0].typefaceWrites == 1,
                "continued pass applies remaining labels without rewriting processed labels");
    }

    private static void applicationLifecycle(boolean defaults) {
        Application application = new Application();
        LunagramTypographyViews.install(application);
        LunagramTypographyViews.install(application);
        check(application.callbacks.size() == 1, "application lifecycle callback installs once");
        TextView label = text(null);
        Activity activity = new Activity(new Window(label));
        application.callbacks.get(0).onActivityStarted(activity);
        same(defaults ? null : LunagramTypography.resolve(null), label.getTypeface(), "activity start applies decor");
        int writes = label.typefaceWrites;
        application.callbacks.get(0).onActivityResumed(activity);
        check(label.typefaceWrites == writes, "activity resume preserves already-applied face");
    }

    public static void main(String[] args) {
        Context context = new Context();
        context.preferences.values.put("family", Integer.parseInt(args[0]));
        context.preferences.values.put("weight", Integer.parseInt(args[1]));
        ApplicationLoader.applicationContext = context;
        boolean defaults = LunagramTypography.isDefault();
        viewBoundary(defaults);
        if (!defaults) chunkLifecycle();
        applicationLifecycle(defaults);
        System.out.println("PASS " + checks + " typography view assertions; family " + args[0] + "; weight "
                + args[1] + " (actual helper with modeled View tree/lifecycle; no Android rendering)");
    }
}
