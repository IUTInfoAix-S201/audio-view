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

    // ----- Grandeurs acoustiques par cri (issue #50) -----
    //
    // Calculs purs sur le Spectrogram déjà calculé, en unités FICHIER (Hz fichier, temps fichier).
    // La conversion vers le temps réel (fréquence × facteur, temps ÷ facteur) est faite par le
    // ViewModel qui connaît timeExpansionFactor. Toutes les fonctions bornent l'intervalle de trames
    // [fromFrame, toFrame] au spectrogramme, ce qui permet de restreindre le calcul à la fenêtre d'un
    // cri (temps_debut/fin) ou de couvrir tout le fichier (0 .. frameCount-1).

    /**
     * Seuil (en dB sous le pic de la fenêtre analysée) en-dessous duquel une trame est considérée
     * <b>inactive</b>. Sert à délimiter le cri (durée) et à repérer sa fin (fréquence terminale). Choisi
     * assez large pour englober un cri de chiroptère au-dessus du plancher de bruit sans happer les
     * trames quasi muettes des bords.
     */
    public static final double MEASURE_ACTIVE_RANGE_DB = 20.0;

    /**
     * Plancher absolu (dBFS) en-dessous duquel une fenêtre est considérée <b>sans signal</b>. Évite que
     * le seuil <i>relatif</i> ({@link #MEASURE_ACTIVE_RANGE_DB} sous le pic) ne déclare « actif » un
     * fichier silencieux (dont le pic est déjà au plancher anti-log ≈ -180 dBFS). Placé sous le niveau
     * d'un vrai cri ramené dans l'audible mais bien au-dessus du silence numérique.
     */
    public static final double MEASURE_FLOOR_DB = -80.0;

    /**
     * Indice de trame STFT couvrant l'instant {@code tSeconds} (temps fichier), pour convertir une
     * fenêtre temporelle en intervalle de trames. Non borné (le clamp est fait par l'appelant).
     */
    public static int frameForTime(double tSeconds, double sampleRate, int hop) {
        if (hop <= 0 || sampleRate <= 0) {
            return 0;
        }
        return (int) Math.round(tSeconds * sampleRate / hop);
    }

    /** Fréquence (Hz, temps fichier) associée au bin {@code bin} : {@code bin · sampleRate / fftSize}. */
    public static double binFrequencyHz(int bin, double sampleRate, int fftSize) {
        return fftSize <= 0 ? 0 : bin * sampleRate / fftSize;
    }

    /**
     * <b>FME — Fréquence du Maximum d'Énergie</b> (Hz, temps fichier) sur l'intervalle de trames
     * {@code [fromFrame, toFrame]} (inclus, borné au spectrogramme) : fréquence du bin de magnitude
     * maximale. C'est le discriminant acoustique de référence. Renvoie {@code NaN} si le spectrogramme
     * est vide ou l'intervalle dégénéré.
     */
    public static double peakFrequencyHz(Spectrogram spec, double sampleRate, int fftSize, int fromFrame, int toFrame) {
        int bin = peakBin(spec, fromFrame, toFrame);
        return bin < 0 ? Double.NaN : binFrequencyHz(bin, sampleRate, fftSize);
    }

    /**
     * <b>Fréquence terminale</b> (Hz, temps fichier) — heuristique de fin de balayage FM : fréquence
     * dominante de la <b>dernière trame active</b> de l'intervalle (trame dont le pic dépasse {@code pic
     * de la fenêtre − }{@link #MEASURE_ACTIVE_RANGE_DB}). Approximation volontairement simple : sur un
     * cri à fréquence quasi constante elle rejoint la FME. Renvoie {@code NaN} si aucune trame active.
     */
    public static double terminalFrequencyHz(
            Spectrogram spec, double sampleRate, int fftSize, int fromFrame, int toFrame) {
        int f0 = Math.max(0, fromFrame);
        int f1 = Math.min(spec.frameCount() - 1, toFrame);
        if (spec.binCount() == 0 || f1 < f0) {
            return Double.NaN;
        }
        double peak = windowPeakDb(spec, f0, f1);
        if (peak < MEASURE_FLOOR_DB) {
            return Double.NaN;
        }
        double threshold = peak - MEASURE_ACTIVE_RANGE_DB;
        for (int f = f1; f >= f0; f--) {
            int bin = frameDominantBin(spec, f);
            if (spec.magnitudeDb(f, bin) >= threshold) {
                return binFrequencyHz(bin, sampleRate, fftSize);
            }
        }
        return Double.NaN;
    }

    /**
     * <b>Durée active</b> (secondes, temps fichier) sur l'intervalle : de la première à la dernière
     * trame <b>active</b> (pic ≥ {@code pic de la fenêtre − }{@link #MEASURE_ACTIVE_RANGE_DB}), largeur
     * d'une trame incluse ({@code hop}). Mesure le cri sur le signal plutôt que sur les seules bornes
     * fournies. Renvoie {@code 0} si aucune trame active.
     */
    public static double activeDurationSeconds(
            Spectrogram spec, double sampleRate, int hop, int fromFrame, int toFrame) {
        int f0 = Math.max(0, fromFrame);
        int f1 = Math.min(spec.frameCount() - 1, toFrame);
        if (spec.binCount() == 0 || f1 < f0 || sampleRate <= 0) {
            return 0;
        }
        double peak = windowPeakDb(spec, f0, f1);
        if (peak < MEASURE_FLOOR_DB) {
            return 0;
        }
        double threshold = peak - MEASURE_ACTIVE_RANGE_DB;
        int first = -1;
        int last = -1;
        for (int f = f0; f <= f1; f++) {
            int bin = frameDominantBin(spec, f);
            if (spec.magnitudeDb(f, bin) >= threshold) {
                if (first < 0) {
                    first = f;
                }
                last = f;
            }
        }
        return first < 0 ? 0 : (last - first + 1) * hop / sampleRate;
    }

    /** Bin de magnitude maximale, tous frames confondus, sur {@code [fromFrame, toFrame]} ; {@code -1} si vide. */
    private static int peakBin(Spectrogram spec, int fromFrame, int toFrame) {
        int f0 = Math.max(0, fromFrame);
        int f1 = Math.min(spec.frameCount() - 1, toFrame);
        if (spec.binCount() == 0 || f1 < f0) {
            return -1;
        }
        int best = -1;
        double bestDb = Double.NEGATIVE_INFINITY;
        for (int f = f0; f <= f1; f++) {
            for (int b = 0; b < spec.binCount(); b++) {
                double m = spec.magnitudeDb(f, b);
                if (m > bestDb) {
                    bestDb = m;
                    best = b;
                }
            }
        }
        return bestDb < MEASURE_FLOOR_DB ? -1 : best;
    }

    /** Bin dominant (magnitude max) d'une trame donnée. */
    private static int frameDominantBin(Spectrogram spec, int frame) {
        int best = 0;
        double bestDb = Double.NEGATIVE_INFINITY;
        for (int b = 0; b < spec.binCount(); b++) {
            double m = spec.magnitudeDb(frame, b);
            if (m > bestDb) {
                bestDb = m;
                best = b;
            }
        }
        return best;
    }

    /** Magnitude dB maximale sur l'intervalle de trames {@code [f0, f1]} (bornes déjà validées). */
    private static double windowPeakDb(Spectrogram spec, int f0, int f1) {
        double peak = Double.NEGATIVE_INFINITY;
        for (int f = f0; f <= f1; f++) {
            for (int b = 0; b < spec.binCount(); b++) {
                peak = Math.max(peak, spec.magnitudeDb(f, b));
            }
        }
        return peak;
    }
}
