package org.telegram.ui.Components;

import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramMediaAccess;
import org.telegram.messenger.LunagramSpeechChunks;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LunagramFeatureGate;

/** User-started speech only. Host must close this on pause/destroy; no service or file output. */
public final class LunagramSpeechSheet {
    private final BaseFragment host;
    private final int account;
    private final LunagramMediaAccess.Session session;
    private final BooleanSupplier contextValid;
    private final List<String> chunks;
    private TextToSpeech engine;
    private AlertDialog dialog;
    private boolean closed, started, engineReady;
    private int chunk;
    private final Runnable prepareTimeout = () -> { if (!closed && !engineReady) fail(); };
    private final Runnable privacyTick = new Runnable() {
        @Override public void run() {
            if (!valid()) { close(); return; }
            AndroidUtilities.runOnUIThread(this, 250);
        }
    };
    private LunagramSpeechSheet(BaseFragment host, String source, BooleanSupplier valid) {
        this.host = host; account = host.getCurrentAccount(); session = LunagramMediaAccess.forAccount(account);
        contextValid = valid; chunks = LunagramSpeechChunks.split(source, 2000);
    }
    public static LunagramSpeechSheet show(BaseFragment host, String source, BooleanSupplier valid) {
        if (host == null || host.getParentActivity() == null || valid == null || !valid.getAsBoolean()
                || !LunagramFeatureGate.require(host, LunagramFeatureCatalog.Feature.READING_TOOLS)) return null;
        LunagramSpeechSheet sheet = new LunagramSpeechSheet(host, source, valid);
        if (sheet.chunks.isEmpty()) return null;
        sheet.confirm(); return sheet;
    }
    private boolean valid() {
        return !closed && host.getParentActivity() != null && !host.getParentActivity().isFinishing() && !host.isPaused()
                && account == UserConfig.selectedAccount && session.canUse(LunagramFeatureCatalog.Feature.READING_TOOLS) && contextValid.getAsBoolean();
    }
    private void confirm() {
        dialog = new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(s(R.string.LulSpeak)).setMessage(s(R.string.LulSpeechPrivacy))
                .setPositiveButton(s(R.string.LulSpeakStart), (d, which) -> { started = true; AndroidUtilities.runOnUIThread(this::start); })
                .setNegativeButton(s(R.string.Cancel), null).create();
        if (host.showDialog(dialog, d -> { if (!started) close(); }) == null) close();
    }
    private void start() {
        if (!valid()) { close(); return; }
        dialog = new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(s(R.string.LulSpeak)).setMessage(s(R.string.LulSpeechPreparing))
                .setNegativeButton(s(R.string.LulSpeechStop), (d, which) -> close()).create();
        if (host.showDialog(dialog, d -> close()) == null) { close(); return; }
        try {
            // Post even a synchronous constructor callback until the engine field is assigned.
            engine = new TextToSpeech(ApplicationLoader.applicationContext, status -> AndroidUtilities.runOnUIThread(() -> ready(status), 1));
            AndroidUtilities.runOnUIThread(privacyTick, 250);
            AndroidUtilities.runOnUIThread(prepareTimeout, 10000);
        } catch (RuntimeException unavailable) { fail(); }
    }
    private void ready(int status) {
        if (!valid()) { close(); return; }
        if (status != TextToSpeech.SUCCESS || engine == null) { fail(); return; }
        try {
            Set<Voice> installed = engine.getVoices();
            ArrayList<Voice> available = new ArrayList<>();
            if (installed != null) for (Voice voice : installed)
                if (!voice.isNetworkConnectionRequired() && (voice.getFeatures() == null
                        || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) available.add(voice);
            if (available.isEmpty()) { fail(); return; }
            String language = LocaleController.getInstance().getCurrentLocale().getLanguage();
            Voice selected = null;
            for (Voice voice : available) if (voice.getLocale().getLanguage().equals(language)
                    && (selected == null || voice.getQuality() > selected.getQuality())) selected = voice;
            if (selected == null) { fail(); return; }
            if (engine.setVoice(selected) != TextToSpeech.SUCCESS) { fail(); return; }
            engineReady = true; AndroidUtilities.cancelRunOnUIThread(prepareTimeout);
            engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { AndroidUtilities.runOnUIThread(() -> { if (valid()) { chunk++; speakNext(); } else close(); }); }
                @Override public void onError(String id) { AndroidUtilities.runOnUIThread(() -> { if (!closed) fail(); }); }
            });
            speakNext();
        } catch (RuntimeException unavailable) { fail(); }
    }
    private void speakNext() {
        if (!valid() || engine == null || chunk >= chunks.size()) { close(); return; }
        dialog.setMessage(s(R.string.LulSpeechPlaying) + " " + (chunk + 1) + "/" + chunks.size());
        try {
            if (engine.speak(chunks.get(chunk), TextToSpeech.QUEUE_FLUSH, null, "lunagram-" + chunk) == TextToSpeech.ERROR) fail();
        } catch (RuntimeException failed) { fail(); }
    }
    private void fail() {
        boolean show = valid(); close();
        if (show && host.getParentActivity() != null) host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(s(R.string.LulSpeak)).setMessage(s(R.string.LulSpeechUnavailable)).setPositiveButton(s(R.string.OK), null).create());
    }
    public void close() {
        if (closed) return; closed = true;
        AndroidUtilities.cancelRunOnUIThread(privacyTick);
        AndroidUtilities.cancelRunOnUIThread(prepareTimeout);
        TextToSpeech previous = engine; engine = null;
        if (previous != null) {
            try { previous.stop(); } catch (RuntimeException ignored) {}
            try { previous.shutdown(); } catch (RuntimeException ignored) {}
        }
        chunks.clear();
        AlertDialog old = dialog; dialog = null;
        if (old != null && old.isShowing()) old.dismiss();
    }
    private static String s(int id) { return LocaleController.getString(id); }
}
