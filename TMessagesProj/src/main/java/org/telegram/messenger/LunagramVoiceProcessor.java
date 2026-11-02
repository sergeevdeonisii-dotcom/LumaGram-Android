package org.telegram.messenger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Stateful PCM16 effects. Each channel has independent history; duration is unchanged. */
public final class LunagramVoiceProcessor {
    public static final int ORIGINAL = 0, ROBOT = 1, RADIO = 2, BASS = 3, CARTOON = 4, TELEPHONE = 5;
    private final int effect, channels, sampleRate;
    private final double[] previousInput, highPass, lowPass, bass;
    private final double[][] delay;
    private final int grain;
    private int writePosition;
    private long frames;

    public LunagramVoiceProcessor(int effect, int sampleRate, int channels) {
        if (effect < ORIGINAL || effect > TELEPHONE || sampleRate <= 0 || channels < 1 || channels > 2) {
            throw new IllegalArgumentException("Invalid voice format/effect");
        }
        this.effect = effect;
        this.channels = channels;
        this.sampleRate = sampleRate;
        previousInput = new double[channels]; highPass = new double[channels];
        lowPass = new double[channels]; bass = new double[channels];
        grain = Math.max(32, sampleRate / 25);
        delay = new double[channels][grain * 3];
    }

    public void process(ByteBuffer pcm, int length) {
        if (length < 0 || length > pcm.limit() || length % (2 * channels) != 0) {
            throw new IllegalArgumentException("Unaligned PCM frame");
        }
        if (effect == ORIGINAL) return;
        pcm.order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset < length; offset += 2 * channels) {
            for (int channel = 0; channel < channels; channel++) {
                double value = pcm.getShort(offset + channel * 2) / 32768.0;
                if (effect == BASS || effect == CARTOON) {
                    delay[channel][writePosition] = value;
                    double ratio = effect == BASS ? 0.72 : 1.42;
                    double phase = ((frames * Math.abs(1 - ratio)) % grain) / grain;
                    double other = (phase + 0.5) % 1.0;
                    double a = ratio < 1 ? phase : 1 - phase;
                    double b = ratio < 1 ? other : 1 - other;
                    double weight = 0.5 - 0.5 * Math.cos(2 * Math.PI * phase);
                    value = tap(channel, grain + a * grain) * weight + tap(channel, grain + b * grain) * (1 - weight);
                }
                switch (effect) {
                    case ROBOT:
                        value = bandPass(value, channel, 100, 5000) * Math.sin(2 * Math.PI * 80 * frames / sampleRate) * 1.7;
                        break;
                    case RADIO:
                        value = bandPass(value, channel, 180, 4200);
                        value = Math.tanh(value * 2.4) * 0.70;
                        value = Math.rint(value * 128) / 128;
                        break;
                    case BASS:
                        bass[channel] += (1 - Math.exp(-2 * Math.PI * 240 / sampleRate)) * (value - bass[channel]);
                        value = value * 0.9 + bass[channel] * 0.65;
                        break;
                    case CARTOON:
                        value = bandPass(value, channel, 160, 9000) * 1.25;
                        break;
                    case TELEPHONE:
                        value = bandPass(value, channel, 300, 3300);
                        value = Math.tanh(value * 1.6) * 0.8;
                        break;
                }
                pcm.putShort(offset + channel * 2, (short) Math.round(Math.max(-1, Math.min(32767.0 / 32768, value)) * 32768));
            }
            writePosition = (writePosition + 1) % delay[0].length;
            frames++;
        }
    }

    public int latencyFrames() { return effect == BASS || effect == CARTOON ? grain * 3 / 2 : 0; }

    private double bandPass(double value, int channel, double high, double low) {
        double coefficient = Math.exp(-2 * Math.PI * high / sampleRate);
        highPass[channel] = coefficient * (highPass[channel] + value - previousInput[channel]);
        previousInput[channel] = value;
        lowPass[channel] += (1 - Math.exp(-2 * Math.PI * low / sampleRate)) * (highPass[channel] - lowPass[channel]);
        return lowPass[channel];
    }

    private double tap(int channel, double distance) {
        double position = (writePosition - distance + delay[channel].length) % delay[channel].length;
        int index = (int) position;
        double fraction = position - index;
        return delay[channel][index] * (1 - fraction) + delay[channel][(index + 1) % delay[channel].length] * fraction;
    }
}
