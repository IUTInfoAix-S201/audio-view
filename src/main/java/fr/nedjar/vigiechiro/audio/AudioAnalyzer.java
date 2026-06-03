package fr.nedjar.vigiechiro.audio;

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
final class AudioAnalyzer {

  private AudioAnalyzer() {}

  /** Taille de fenêtre FFT (puissance de deux). */
  static final int FFT_SIZE = 1024;

  /** Pas entre deux fenêtres FFT successives, en échantillons. */
  static final int HOP = 256;

  /** Résultat complet d'une analyse : tout ce dont la vue a besoin pour afficher l'audio. */
  record AnalyzedAudio(
      AudioSample sample,
      Spectrogram spectrogram,
      double sonoScale,
      double suggestedFrequencyZoom,
      double durationSeconds) {}

  /**
   * Analyse synchrone d'un WAV : décode → STFT → auto-échelles. Lance {@link IOException} / {@link
   * UnsupportedAudioFileException} sur fichier illisible ou non pris en charge.
   */
  static AnalyzedAudio analyze(Path path) throws IOException, UnsupportedAudioFileException {
    AudioSample sample = AudioSample.load(path);
    Spectrogram spectrogram = Spectrogram.compute(sample, FFT_SIZE, HOP);
    return new AnalyzedAudio(
        sample,
        spectrogram,
        sonoScaleFor(sample),
        autoFrequencyZoom(spectrogram),
        sample.durationSeconds());
  }

  /**
   * Auto-échelle verticale du sonogramme : facteur tel que le pic du fichier remplisse ~95 % de la
   * demi-hauteur. Les enregistrements de chiroptères étant de faible amplitude, sans cela la forme
   * d'onde resterait minuscule. Plafonné pour ne pas amplifier démesurément un fichier quasi muet.
   */
  static double sonoScaleFor(AudioSample s) {
    float peak = 0;
    for (float v : s.samples()) {
      float a = Math.abs(v);
      if (a > peak) {
        peak = a;
      }
    }
    return peak > 1e-6f ? Math.min(0.95 / peak, 100.0) : 1.0;
  }

  /**
   * Zoom fréquentiel par défaut calé sur la bande réellement utilisée : on cherche la fréquence la
   * plus haute dont l'énergie dépasse un seuil sous le pic, puis on cadre {@code [0, fMax]} avec
   * une marge. Évite d'afficher une grande zone vide en haut du spectrogramme.
   */
  static double autoFrequencyZoom(Spectrogram spec) {
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
