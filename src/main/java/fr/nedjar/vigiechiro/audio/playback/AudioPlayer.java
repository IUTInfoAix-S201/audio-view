package fr.nedjar.vigiechiro.audio.playback;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;

/**
 * Fine enveloppe autour d'un {@link Clip} de {@code javax.sound.sampled}, exposant une horloge en
 * secondes (lecture / pause / déplacement). Le clip charge la séquence courte (5 secondes) en
 * mémoire, ce qui donne une position précise pour synchroniser le curseur via un {@code
 * AnimationTimer}.
 *
 * <p>La lecture est alimentée par les échantillons <b>déjà décodés</b> (mono, normalisés dans
 * [-1, 1]) plutôt que par un re-décodage du fichier : cela permet d'appliquer au rendu un gain
 * linéaire, y compris un boost ({@code gain > 1}) impossible via le seul {@code MASTER_GAIN} du
 * mixeur — base de la normalisation peak (issue #32). Le fichier d'origine n'est jamais modifié.
 */
public final class AudioPlayer implements AutoCloseable {

    private Clip clip;

    /**
     * Charge en mémoire une séquence mono déjà décodée (échantillons dans [-1, 1]) en lui appliquant
     * un {@code gain} linéaire au rendu. Le PCM 16 bits est reconstruit par {@link #toPcm16(float[],
     * double)}, ce qui autorise un gain {@code > 1} (boost). No-op silencieux si la séquence est
     * vide. À {@code gain == 1.0} le rendu est l'audio d'origine (en mono).
     *
     * @param mono échantillons mono normalisés dans [-1, 1]
     * @param sampleRate fréquence d'échantillonnage, en Hz
     * @param gain facteur linéaire appliqué à chaque échantillon (1.0 = inchangé)
     */
    public void loadSamples(float[] mono, float sampleRate, double gain) throws LineUnavailableException {
        close();
        if (mono == null || mono.length == 0) {
            return;
        }
        byte[] pcm = toPcm16(mono, gain);
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        clip = AudioSystem.getClip();
        clip.open(format, pcm, 0, pcm.length);
    }

    public void play() {
        if (clip != null) {
            clip.start();
        }
    }

    public void pause() {
        if (clip != null) {
            clip.stop();
        }
    }

    /** Position de lecture courante, en secondes. */
    public double position() {
        return clip == null ? 0 : clip.getMicrosecondPosition() / 1_000_000.0;
    }

    /** Durée totale, en secondes. */
    public double length() {
        return clip == null ? 0 : clip.getMicrosecondLength() / 1_000_000.0;
    }

    /**
     * {@code true} si la lecture a atteint la fin de l'extrait. Comparé sur les durées <b>internes au
     * Clip</b> (mêmes unités), donc fiable — contrairement à un test {@code position ≥ durée} où la
     * durée provient d'un calcul flottant séparé : l'arrondi microseconde fait plafonner la position
     * un cheveu sous la durée, et la condition ne se déclenche jamais.
     */
    public boolean isAtEnd() {
        return clip != null && clip.getMicrosecondPosition() >= clip.getMicrosecondLength();
    }

    public void seek(double seconds) {
        if (clip != null) {
            double clamped = Math.max(0, Math.min(length(), seconds));
            clip.setMicrosecondPosition((long) (clamped * 1_000_000.0));
        }
    }

    @Override
    public void close() {
        if (clip != null) {
            clip.stop();
            clip.close();
            clip = null;
        }
    }

    // ----- Calculs purs (testables sans périphérique audio) -----

    /**
     * Gain de normalisation <b>peak</b> : facteur linéaire ramenant le pic du signal au plafond {@code
     * ceiling}. C'est la méthode la moins déformante (simple gain constant, forme d'onde préservée).
     *
     * <ul>
     *   <li>signal quasi muet (pic {@code < 1e-6}) → {@code 1.0} (on n'amplifie pas le bruit de fond) ;
     *   <li>boost plafonné à {@code maxGainDb} décibels (filet de sécurité) ;
     *   <li>signal déjà plus fort que le plafond → gain {@code < 1} (atténuation) : tous les fichiers
     *       finissent au même niveau crête.
     * </ul>
     *
     * @param mono échantillons mono normalisés dans [-1, 1]
     * @param ceiling niveau crête cible dans ]0, 1] (ex. 0.97 pour une petite marge anti-saturation)
     * @param maxGainDb boost maximal autorisé, en dB (ex. 40 dB ≈ ×100)
     * @return le facteur de gain à appliquer au rendu
     */
    public static double peakGain(float[] mono, double ceiling, double maxGainDb) {
        float peak = 0;
        if (mono != null) {
            for (float v : mono) {
                float a = Math.abs(v);
                if (a > peak) {
                    peak = a;
                }
            }
        }
        if (peak < 1e-6f) {
            return 1.0;
        }
        double gain = ceiling / peak;
        double maxGain = Math.pow(10, maxGainDb / 20.0);
        return Math.min(gain, maxGain);
    }

    /**
     * Convertit des échantillons mono normalisés ([-1, 1]) en PCM 16 bits signé little-endian, après
     * application d'un {@code gain} linéaire. Les valeurs débordant après gain sont écrêtées dans
     * [-32768, 32767] (filet de sécurité ; en normalisation peak le pic vise le plafond, donc aucun
     * écrêtage en pratique).
     *
     * @param mono échantillons mono normalisés dans [-1, 1] (peut être {@code null} → tableau vide)
     * @param gain facteur linéaire appliqué avant quantification
     * @return les octets PCM 16 bits LE (2 octets par échantillon)
     */
    public static byte[] toPcm16(float[] mono, double gain) {
        int n = mono == null ? 0 : mono.length;
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int s = Math.round((float) (mono[i] * gain * 32767.0));
            if (s > 32767) {
                s = 32767;
            } else if (s < -32768) {
                s = -32768;
            }
            out[2 * i] = (byte) (s & 0xFF);
            out[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }
}
