package org.telegram.messenger;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

/** Short generated sounds; no assets, network, microphone, or background playback. */
public final class LunagramInterfaceSounds {
    public static final int SEND = 1, TAP = 2, SWITCH = 3;
    private static final DispatchQueue queue = new DispatchQueue("lunagram-interface-sounds");
    private static long lastRequest;
    private static AudioTrack track;
    private LunagramInterfaceSounds() { }
    public static synchronized void play(int account, int event) {
        final int pack = LunagramExperienceSettings.soundPack(account);
        if (pack == 0 || event < SEND || event > SWITCH || ApplicationLoader.applicationContext == null) return;
        long now = SystemClock.elapsedRealtime(); if (now - lastRequest < 90) return; lastRequest = now;
        final long owner = UserConfig.getInstance(account).getClientUserId();
        final long epoch = LunagramSubscriptionClient.accountEpoch(account);
        queue.postRunnable(() -> {
            if (ApplicationLoader.mainInterfacePaused || owner <= 0 || account != UserConfig.selectedAccount || owner != UserConfig.getInstance(account).getClientUserId()
                    || epoch != LunagramSubscriptionClient.accountEpoch(account)
                    || LunagramExperienceSettings.soundPack(account) != pack) return;
            try {
                AudioManager manager = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
                if (manager == null || manager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return;
                if (track != null) { AudioTrack previous = track; track = null; previous.release(); }
                short[] sound = synthesize(pack, event);
                AudioTrack created = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(22050).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(sound.length * 2).build();
                track = created;
                if (created.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("Interface sound initialization failed");
                created.setVolume(0.12f);
                if (created.write(sound, 0, sound.length) != sound.length) throw new IllegalStateException("Interface sound write failed");
                created.play();
                queue.postRunnable(() -> { if (track == created) { track = null; try { created.release(); } catch (Throwable error) { FileLog.e(error); } } }, 260);
            } catch (Throwable error) { if (track != null) { AudioTrack failed = track; track = null; try { failed.release(); } catch (Throwable ignored) { } } FileLog.e(error); }
        });
    }
    static short[] synthesize(int pack, int event) {
        int count = event == TAP ? 1764 : 3969;
        short[] output = new short[count]; double phase = 0; int noise = 173;
        for (int i = 0; i < count; i++) {
            double t = i / 22050.0, p = i / (double) count;
            double frequency = (event == SEND ? 740 : event == SWITCH ? 560 : 420) * (pack == 1 ? 1 + p * 0.5 : pack == 4 ? 1.4 - p * 0.6 : 1);
            phase += 2 * Math.PI * frequency / 22050.0;
            double sample;
            if (pack == 2) { noise = noise * 1664525 + 1013904223; sample = ((noise >>> 16) / 32768.0 - 1) * Math.exp(-t * 65) + Math.sin(phase) * 0.15; }
            else if (pack == 3) sample = Math.sin(phase) >= 0 ? 0.45 : -0.45;
            else sample = Math.sin(phase) * 0.6 + Math.sin(phase * (pack == 1 ? 1.5 : 2)) * 0.15;
            double envelope = Math.min(1, i / 80.0) * Math.pow(1 - p, pack == 4 ? 3 : 1.5);
            output[i] = (short) Math.max(-20000, Math.min(20000, sample * envelope * 18000));
        }
        return output;
    }
}
