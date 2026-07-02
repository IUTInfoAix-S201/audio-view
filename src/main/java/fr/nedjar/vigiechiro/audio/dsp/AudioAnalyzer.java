package fr.nedjar.vigiechiro.audio.dsp;

import fr.nedjar.vigiechiro.audio.viewmodel.AudioViewModel;
import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Pipeline d'analyse audio synchrone et <b>sans dépendance au toolkit JavaFX</b> : décode le WAV,
 * calcule la STFT et dérive les paramètres d'affichage (auto-échelle du sonogramme, cadrage
 * fréquentiel). Issue #10.
 *
 * <p>Testable isolément : l'orchestration (Task/Thread daemon + câblage des propriétés) reste dans
 * {@link AudioViewModel}, qui appelle simplement {@link #analyze(Path)} en tâche de fond.
 */
public final class AudioAnalyzer {

    private AudioAnalyzer() {}

    /** Taille de fenêtre FFT (puissance de deux). */
    public static final int FFT_SIZE = 1024;

    /** Pas entre deux fenêtres FFT successives, en échantillons. */
    public static final int HOP = 256;

    /**
     * Plafond de gain de l'auto-échelle <b>par défaut</b> (conservateur) : borne l'amplification d'un
     * fichier quasi muet pour ne pas transformer son plancher de bruit en pleine onde.
     */
    public static final double SONO_MAX_GAIN = 100.0;

    /**
     * Plafond de gain de l'auto-échelle en <b>normalisation visuelle</b> : bien plus haut, pour qu'un
     * enregistrement réel mais très faible (pic à quelques millièmes, typique des chiroptères ramenés
     * dans l'audible) remplisse quand même la gouttière. Reste fini pour éviter d'exploser un fichier
     * numériquement muet. ~+74 dB, à comparer au plafond par défaut de +40 dB ({@link #SONO_MAX_GAIN}).
     */
    public static final double SONO_NORMALISED_MAX_GAIN = 5000.0;

    /** Résultat complet d'une analyse : tout ce dont la vue a besoin pour afficher l'audio. */
    public record AnalyzedAudio(
            AudioSample sample,
            Spectrogram spectrogram,
            double sonoScale,
            double normalisedSonoScale,
            double suggestedFrequencyZoom,
            double spectroPeakDb,
            double durationSeconds) {}

    /**
     * Analyse synchrone d'un WAV : décode → STFT → auto-échelles. Lance {@link IOException} / {@link
     * UnsupportedAudioFileException} sur fichier illisible ou non pris en charge.
     */
    public static AnalyzedAudio analyze(Path path) throws IOException, UnsupportedAudioFileException {
        AudioSample sample = AudioSample.load(path);
        Spectrogram spectrogram = Spectrogram.compute(sample, FFT_SIZE, HOP);
        return new AnalyzedAudio(
                sample,
                spectrogram,
                sonoScaleFor(sample),
                sonoScaleFor(sample, SONO_NORMALISED_MAX_GAIN),
                autoFrequencyZoom(spectrogram),
                spectroPeakDbFor(spectrogram),
                sample.durationSeconds());
    }

    /**
     * Magnitude maximale du spectrogramme, en dB (pic réel du signal). Sert à <b>caler la fenêtre
     * dynamique dB</b> du spectrogramme sur le signal (normalisation visuelle du spectro), pour qu'un
     * enregistrement faible ne s'affiche pas en noir. Renvoie {@link AudioViewModel#MAX_DB} si le
     * spectrogramme est vide ou muet (pas de pic exploitable).
     */
    public static double spectroPeakDbFor(Spectrogram spec) {
        double peak = Double.NEGATIVE_INFINITY;
        for (int f = 0; f < spec.frameCount(); f++) {
            for (int b = 0; b < spec.binCount(); b++) {
                peak = Math.max(peak, spec.magnitudeDb(f, b));
            }
        }
        return Double.isFinite(peak) ? peak : AudioViewModel.MAX_DB;
    }

    /**
     * Auto-échelle verticale du sonogramme (plafond conservateur par défaut, {@link #SONO_MAX_GAIN}).
     *
     * @see #sonoScaleFor(AudioSample, double)
     */
    public static double sonoScaleFor(AudioSample s) {
        return sonoScaleFor(s, SONO_MAX_GAIN);
    }

    /**
     * Auto-échelle verticale du sonogramme : facteur tel que le pic du fichier remplisse ~95 % de la
     * demi-hauteur. Les enregistrements de chiroptères étant de faible amplitude, sans cela la forme
     * d'onde resterait minuscule. Le gain est plafonné à {@code maxGain} pour ne pas amplifier
     * démesurément un fichier quasi muet : {@link #SONO_MAX_GAIN} en affichage par défaut (prudent),
     * {@link #SONO_NORMALISED_MAX_GAIN} en normalisation visuelle (remplissage jusqu'au pic réel).
     */
    public static double sonoScaleFor(AudioSample s, double maxGain) {
        float peak = 0;
        for (float v : s.samples()) {
            float a = Math.abs(v);
            if (a > peak) {
                peak = a;
            }
        }
        return peak > 1e-6f ? Math.min(0.95 / peak, maxGain) : 1.0;
    }

    /**
     * Zoom fréquentiel par défaut calé sur la bande réellement utilisée : on cherche la fréquence la
     * plus haute dont l'énergie dépasse un seuil sous le pic, puis on cadre {@code [0, fMax]} avec
     * une marge. Évite d'afficher une grande zone vide en haut du spectrogramme.
     */
    public static double autoFrequencyZoom(Spectrogram spec) {
        int bins = spec.binCount();
        int frames = spec.frameCount();
        if (bins < 2 || frames < 1) {
            return 1;
        }
        double globalMax = -Double.MAX_VALUE;
        double[] binMax = new double[bins];
        for (int b = 0; b < bins; b++) {
            double m = -Double.MAX_VALUE;
            for (int f = 0; f < frames; f++) {
                m = Math.max(m, spec.magnitudeDb(f, b));
            }
            binMax[b] = m;
            globalMax = Math.max(globalMax, m);
        }
        double seuil = globalMax - 35.0; // dB sous le pic
        int plusHaut = 0;
        for (int b = 0; b < bins; b++) {
            if (binMax[b] >= seuil) {
                plusHaut = b;
            }
        }
        double fraction = Math.min(1.0, ((plusHaut + 1) / (double) (bins - 1)) * 1.3);
        if (fraction <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(64, 1.0 / fraction));
    }
}
