package fr.nedjar.vigiechiro.audio.dsp;

import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Représentation décodée et immuable d'un fichier WAV : échantillons mono normalisés dans [-1, 1].
 *
 * <p>Les canaux éventuels sont moyennés et le flux est converti en PCM signé 16 bits little-endian
 * avant lecture, ce qui couvre les WAV transformés de VigieChiro (PCM 16 bits mono, déjà ralentis
 * x10 donc dans la bande audible).
 */
public final class AudioSample {

    private final float[] samples;
    private final float sampleRate;

    private AudioSample(float[] samples, float sampleRate) {
        this.samples = samples;
        this.sampleRate = sampleRate;
    }

    public static AudioSample load(Path path) throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat source = in.getFormat();
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    source.getSampleRate(),
                    16,
                    source.getChannels(),
                    source.getChannels() * 2,
                    source.getSampleRate(),
                    false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in)) {
                byte[] bytes = pcm.readAllBytes();
                int channels = Math.max(1, target.getChannels());
                int frames = bytes.length / (2 * channels);
                float[] mono = new float[frames];
                for (int i = 0; i < frames; i++) {
                    int acc = 0;
                    for (int c = 0; c < channels; c++) {
                        int idx = (i * channels + c) * 2;
                        int lo = bytes[idx] & 0xFF;
                        int hi = bytes[idx + 1]; // octet de poids fort signé
                        acc += (hi << 8) | lo;
                    }
                    mono[i] = (acc / (float) channels) / 32768f;
                }
                return new AudioSample(mono, target.getSampleRate());
            }
        }
    }

    public float[] samples() {
        return samples;
    }

    public float sampleRate() {
        return sampleRate;
    }

    public double durationSeconds() {
        return sampleRate <= 0 ? 0 : samples.length / (double) sampleRate;
    }
}
