package org.telegram.ui.Components;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramMediaTools;
import org.telegram.messenger.LunagramMediaToolsPolicy;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LunagramFeatureGate;

import java.util.ArrayList;

/** Telegram-native, message-scoped actions. No automatic downloads or playback. */
public final class LunagramMediaToolsSheet {
    private LunagramMediaToolsSheet() {}
    public static boolean canShow(MessageObject message) { return LunagramMediaTools.eligible(message); }
    public static void show(BaseFragment host, MessageObject message) {
        if (!alive(host) || !canShow(message) || host.getCurrentAccount() != message.currentAccount) return;
        final LunagramMediaTools.Session session;
        try { session = new LunagramMediaTools.Session(message); } catch (RuntimeException denied) { return; }
        if (!session.current()) return;
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        add(labels, actions, R.string.LunagramMediaInspect, () -> inspect(host, session));
        if (!message.isOut()) add(labels, actions, R.string.LunagramMediaRemoveCache, () -> remove(host, session));
        if (message.type == MessageObject.TYPE_PHOTO) add(labels, actions, R.string.LunagramMediaCopyPhoto, () -> photo(host, session));
        if (LunagramMediaTools.timedMedia(message)) {
            // Existing bookmarks remain readable/removable without an active subscription.
            add(labels, actions, R.string.LunagramMediaBookmarks, () -> bookmarks(host, session));
            if (LunagramMediaTools.position(session) >= 0) {
                add(labels, actions, R.string.LunagramMediaAddBookmark, () -> {
                    if (!paid(host, session)) return;
                    try { LunagramMediaTools.addBookmark(session); feedback(host, R.string.LunagramMediaBookmarkSaved); }
                    catch (Exception error) { feedback(host, R.string.LunagramMediaBookmarkFailed); }
                });
                add(labels, actions, R.string.LunagramMediaLoop, () -> loop(host, session));
                add(labels, actions, R.string.LunagramMediaSleep, () -> sleep(host, session));
            }
            if (LunagramMediaTools.hasLoop(session)) add(labels, actions, R.string.LunagramMediaStopLoop, () -> {
                LunagramMediaTools.stopLoop(session); feedback(host, R.string.LunagramMediaLoopStopped);
            });
            if (LunagramMediaTools.hasSleep(session)) add(labels, actions, R.string.LunagramMediaStopSleep, () -> {
                LunagramMediaTools.stopSleep(session); feedback(host, R.string.LunagramMediaSleepStopped);
            });
        }
        host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(text(R.string.LunagramMediaToolsTitle))
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (alive(host) && session.current() && which >= 0 && which < actions.size()) actions.get(which).run();
                }).setNegativeButton(text(R.string.Cancel), null).create());
    }
    private static void add(ArrayList<String> labels, ArrayList<Runnable> actions, int label, Runnable action) {
        labels.add(text(label)); actions.add(action);
    }
    private static String text(int resource) { return LocaleController.getString(resource); }
    private static boolean alive(BaseFragment host) {
        return host != null && host.getParentActivity() != null && !host.getParentActivity().isFinishing() && !host.isPaused();
    }
    private static boolean paid(BaseFragment host, LunagramMediaTools.Session session) {
        if (!alive(host) || !session.current()) return false;
        return session.paid() || LunagramFeatureGate.require(host, LunagramFeatureCatalog.Feature.MEDIA_TOOLS);
    }
    private static void feedback(BaseFragment host, int message) {
        if (alive(host)) BulletinFactory.of(host).createSimpleBulletin(R.raw.info, text(message)).show();
    }
    private interface Start<T> { LunagramMediaTools.Job start(LunagramMediaTools.Callback<T> callback); }
    private static <T> void progress(BaseFragment host, LunagramMediaTools.Session session, Start<T> start, LunagramMediaTools.Callback<T> result) {
        if (!paid(host, session)) return;
        final LunagramMediaTools.Job[] job = new LunagramMediaTools.Job[1];
        AlertDialog waiting = new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(text(R.string.LunagramMediaToolsTitle)).setMessage(text(R.string.LunagramMediaWorking))
                .setNegativeButton(text(R.string.Cancel), (dialog, which) -> { if (job[0] != null) job[0].cancel(); }).create();
        if (host.showDialog(waiting, dialog -> { if (job[0] != null) job[0].cancel(); }) == null) return;
        job[0] = start.start((value, error) -> {
            if (!alive(host) || !session.current()) { waiting.dismiss(); return; }
            waiting.dismiss();
            if (error != 0) feedback(host, error); else result.done(value, 0);
        });
    }
    private static void inspect(BaseFragment host, LunagramMediaTools.Session session) {
        progress(host, session, callback -> LunagramMediaTools.metadata(session, callback), (String detail, int error) -> {
            if (!alive(host)) return;
            host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                    .setTitle(text(R.string.LunagramMediaInspect)).setMessage(detail)
                    .setPositiveButton(text(R.string.OK), null)
                    .setNeutralButton(text(R.string.LunagramMediaHash), (dialog, which) -> hash(host, session)).create());
        });
    }
    private static void hash(BaseFragment host, LunagramMediaTools.Session session) {
        if (!paid(host, session)) return;
        host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(text(R.string.LunagramMediaHash)).setMessage(text(R.string.LunagramMediaHashInfo))
                .setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(R.string.LunagramMediaCalculate), (dialog, which) ->
                        progress(host, session, callback -> LunagramMediaTools.sha256(session, callback), (String value, int error) ->
                                host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                                        .setTitle(text(R.string.LunagramMediaHash)).setMessage(value)
                                        .setPositiveButton(text(R.string.OK), null)
                                        .setNeutralButton(text(R.string.Copy), (d, w) -> {
                                            if (alive(host) && session.current()) AndroidUtilities.addToClipboard(value);
                                        }).create()))).create());
    }
    private static void remove(BaseFragment host, LunagramMediaTools.Session session) {
        if (!paid(host, session)) return;
        host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(text(R.string.LunagramMediaRemoveCache)).setMessage(text(R.string.LunagramMediaRemoveInfo))
                .setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(R.string.Delete), (dialog, which) ->
                        progress(host, session, callback -> LunagramMediaTools.removeDownloadedCopy(session, callback),
                                (Boolean ok, int error) -> feedback(host, R.string.LunagramMediaRemoved))).create());
    }
    private static void photo(BaseFragment host, LunagramMediaTools.Session session) {
        if (!paid(host, session)) return;
        host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(text(R.string.LunagramMediaCopyPhoto)).setMessage(text(R.string.LunagramMediaClipboardInfo))
                .setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(R.string.Copy), (dialog, which) ->
                        progress(host, session, callback -> LunagramMediaTools.copyPhoto(session, callback),
                                (Boolean ok, int error) -> feedback(host, R.string.LunagramMediaPhotoCopied))).create());
    }
    private static void bookmarks(BaseFragment host, LunagramMediaTools.Session session) {
        if (!alive(host) || !session.current()) return;
        final ArrayList<Long> values;
        try { values = LunagramMediaTools.bookmarks(session); }
        catch (Exception unreadable) { feedback(host, R.string.LunagramMediaBookmarkFailed); return; }
        if (values.isEmpty()) {
            host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                    .setTitle(text(R.string.LunagramMediaBookmarks)).setMessage(text(R.string.LunagramMediaBookmarksEmpty))
                    .setPositiveButton(text(R.string.OK), null).create());
            return;
        }
        CharSequence[] labels = new CharSequence[values.size()];
        for (int i = 0; i < values.size(); i++) labels[i] = LunagramMediaToolsPolicy.time(values.get(i));
        host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(text(R.string.LunagramMediaBookmarks)).setItems(labels, (dialog, which) -> {
                    if (!alive(host) || !session.current() || which < 0 || which >= values.size()) return;
                    long value = values.get(which);
                    host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                            .setTitle(LunagramMediaToolsPolicy.time(value)).setMessage(text(R.string.LunagramMediaSeekInfo))
                            .setPositiveButton(text(R.string.LunagramMediaSeek), (d, w) -> {
                                if (!LunagramMediaTools.seekBookmark(session, value)) feedback(host, R.string.LunagramMediaStartFirst);
                            }).setNeutralButton(text(R.string.Copy), (d, w) -> {
                                if (alive(host) && session.current()) AndroidUtilities.addToClipboard(LunagramMediaToolsPolicy.time(value));
                            }).setNegativeButton(text(R.string.Delete), (d, w) -> {
                                if (!alive(host)) return;
                                try { LunagramMediaTools.removeBookmark(session, value); feedback(host, R.string.LunagramMediaBookmarkRemoved); }
                                catch (Exception error) { feedback(host, R.string.LunagramMediaBookmarkFailed); }
                            }).create());
                }).setNegativeButton(text(R.string.Cancel), null).create());
    }
    private static EditTextBoldCursor input(BaseFragment host, int label, String value) {
        Context context = host.getParentActivity();
        EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider()));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, host.getResourceProvider()));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider()));
        edit.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        edit.setSingleLine(true); edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        edit.setHint(text(label)); edit.setContentDescription(text(label)); edit.setText(value);
        edit.setMinHeight(AndroidUtilities.dp(48));
        return edit;
    }
    private static void loop(BaseFragment host, LunagramMediaTools.Session session) {
        if (!paid(host, session)) return;
        long current = LunagramMediaTools.position(session), duration = LunagramMediaTools.duration(session);
        if (current < 0 || duration < 1000) { feedback(host, R.string.LunagramMediaStartFirst); return; }
        LinearLayout content = new LinearLayout(host.getParentActivity()); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        TextView hint = new TextView(host.getParentActivity()); hint.setText(text(R.string.LunagramMediaLoopInfo));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider()));
        content.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        EditTextBoldCursor start = input(host, R.string.LunagramMediaLoopStart, LunagramMediaToolsPolicy.time(Math.min(current, duration - 1000)));
        EditTextBoldCursor end = input(host, R.string.LunagramMediaLoopEnd, LunagramMediaToolsPolicy.time(duration));
        content.addView(start, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(end, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(text(R.string.LunagramMediaLoop)).setView(content)
                .setNegativeButton(text(R.string.Cancel), null).setPositiveButton(text(R.string.LunagramMediaApply), null).create();
        if (host.showDialog(dialog) == null) return;
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!paid(host, session)) return;
            long a = LunagramMediaToolsPolicy.parseTime(start.getText().toString());
            long b = LunagramMediaToolsPolicy.parseTime(end.getText().toString());
            try {
                LunagramMediaTools.setLoop(session, a, b); dialog.dismiss(); feedback(host, R.string.LunagramMediaLoopStarted);
            } catch (RuntimeException invalid) { end.setError(text(R.string.LunagramMediaLoopInvalid)); }
        });
    }
    private static void sleep(BaseFragment host, LunagramMediaTools.Session session) {
        if (!paid(host, session)) return;
        int[] minutes = {5, 15, 30, 60, 120};
        CharSequence[] labels = new CharSequence[minutes.length + 1];
        for (int i = 0; i < minutes.length; i++) labels[i] = LocaleController.formatString(R.string.LunagramMediaSleepMinutes, minutes[i]);
        labels[minutes.length] = text(R.string.LunagramMediaSleepEnd);
        host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(text(R.string.LunagramMediaSleep)).setItems(labels, (dialog, which) -> {
                    if (!paid(host, session) || which < 0 || which > minutes.length) return;
                    try {
                        LunagramMediaTools.setSleep(session, which == minutes.length ? 0 : minutes[which], which == minutes.length);
                        feedback(host, R.string.LunagramMediaSleepStarted);
                    } catch (RuntimeException invalid) { feedback(host, R.string.LunagramMediaStartFirst); }
                }).setNegativeButton(text(R.string.Cancel), null).create());
    }
}
