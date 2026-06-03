package fr.nedjar.vigiechiro.audio;

import java.io.IOException;
import java.nio.file.Path;
import javafx.animation.AnimationTimer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * ViewModel d'{@link AudioView} (MVVM). Il détient l'état observable et toute la logique (décodage
 * + FFT en tâche de fond, lecture audio, fenêtre temps/fréquence, auto-échelles) <b>sans aucune
 * dépendance au graphe de scène</b> : il est donc testable sans interface (voir {@code
 * AudioViewModelTest}). La vue s'y lie par bindings et lui adresse des commandes.
 *
 * <p>Les calculs purs (fenêtre, auto-échelles, graduations, colormap) sont des méthodes statiques
 * pour rester testables sans instancier le ViewModel.
 */
@SuppressWarnings({"PMD.GodClass", "PMD.NcssCount", "PMD.CyclomaticComplexity"})
final class AudioViewModel {

  /** Taille de fenêtre FFT (puissance de deux). */
  static final int FFT_SIZE = 1024;

  /** Pas entre deux fenêtres FFT successives, en échantillons. */
  static final int HOP = 256;

  static final double MIN_DB = -90.0;
  static final double MAX_DB = -10.0;

  // ----- État réglable -----
  private final ObjectProperty<Path> audioFile = new SimpleObjectProperty<>(this, "audioFile");
  private final BooleanProperty playing = new SimpleBooleanProperty(this, "playing", false);
  private final DoubleProperty timeZoom = new SimpleDoubleProperty(this, "timeZoom", 1);
  private final DoubleProperty frequencyZoom = new SimpleDoubleProperty(this, "frequencyZoom", 1);
  private final DoubleProperty timeExpansion =
      new SimpleDoubleProperty(this, "timeExpansionFactor", 1);

  // ----- État dérivé (lecture seule) -----
  private final ReadOnlyDoubleWrapper currentTime =
      new ReadOnlyDoubleWrapper(this, "currentTime", 0);
  private final ReadOnlyDoubleWrapper duration = new ReadOnlyDoubleWrapper(this, "duration", 0);
  private final ReadOnlyObjectWrapper<AudioSample> sample =
      new ReadOnlyObjectWrapper<>(this, "sample");
  private final ReadOnlyObjectWrapper<WritableImage> spectrogramImage =
      new ReadOnlyObjectWrapper<>(this, "spectrogramImage");
  private final ReadOnlyDoubleWrapper sonoScale = new ReadOnlyDoubleWrapper(this, "sonoScale", 1);
  private final ReadOnlyStringWrapper timeText =
      new ReadOnlyStringWrapper(this, "timeText", "0.00 / 0.00 s");

  // null tant qu'aucune erreur de chargement ; renseigné quand la Task échoue, vidé au prochain
  // (re)chargement. La vue affiche un overlay quand non null (cf. AudioView + audio-view.css).
  private final ReadOnlyStringWrapper errorMessage =
      new ReadOnlyStringWrapper(this, "errorMessage", null);

  private final AudioPlayer player = new AudioPlayer();
  private final AnimationTimer timer;

  // Conservés pour reconstruire l'image du spectrogramme quand la colormap change (bascule thème).
  private Spectrogram spectrogram;
  private boolean lightColormap;

  AudioViewModel() {
    // timer assigné avant le listener "playing" qui le capture (champ final).
    timer =
        new AnimationTimer() {
          @Override
          public void handle(long now) {
            double pos = player.position();
            if (duration.get() > 0 && pos >= duration.get()) {
              currentTime.set(duration.get());
              playing.set(false);
              return;
            }
            currentTime.set(pos);
          }
        };

    audioFile.addListener((o, oldFile, newFile) -> loadAudio(newFile));

    playing.addListener(
        (o, was, now) -> {
          if (now) {
            // En fin d'extrait, un nouveau démarrage repart de zéro.
            if (duration.get() > 0 && currentTime.get() >= duration.get()) {
              player.seek(0);
              currentTime.set(0);
            }
            player.play();
            timer.start();
          } else {
            player.pause();
            timer.stop();
          }
        });

    currentTime.addListener((o, a, b) -> updateTimeText());
    duration.addListener((o, a, b) -> updateTimeText());
    timeExpansion.addListener((o, a, b) -> updateTimeText());
  }

  // ----- Commandes -----

  void togglePlay() {
    playing.set(!playing.get());
  }

  /** Positionne la lecture (temps fichier, en secondes), borné à [0, durée]. */
  void seek(double tFile) {
    if (duration.get() <= 0) {
      return;
    }
    double t = Math.max(0, Math.min(duration.get(), tFile));
    player.seek(t);
    currentTime.set(t);
  }

  void dispose() {
    timer.stop();
    player.close();
  }

  // ----- Chargement (orchestration) -----

  private void loadAudio(Path path) {
    playing.set(false);
    player.close();
    currentTime.set(0);
    duration.set(0);
    sample.set(null);
    spectrogramImage.set(null);
    spectrogram = null;
    sonoScale.set(1);
    errorMessage.set(null);
    if (path == null) {
      return;
    }

    Task<LoadResult> task =
        new Task<>() {
          @Override
          protected LoadResult call() throws Exception {
            AudioSample loaded = AudioSample.load(path);
            Spectrogram spec = Spectrogram.compute(loaded, FFT_SIZE, HOP);
            return new LoadResult(loaded, spec);
          }
        };
    task.setOnSucceeded(
        e -> {
          LoadResult result = task.getValue();
          spectrogram = result.spectrogram();
          sample.set(result.sample());
          sonoScale.set(sonoScaleFor(result.sample()));
          spectrogramImage.set(buildSpectrogramImage(spectrogram));
          // Cale par défaut la vue fréquentielle sur la bande réellement utilisée.
          frequencyZoom.set(autoFrequencyZoom(result.spectrogram()));
          try {
            player.load(path);
          } catch (Exception ignored) {
            // La lecture audio est optionnelle : l'affichage reste fonctionnel sans son.
          }
          duration.set(result.sample().durationSeconds());
          currentTime.set(0);
          updateTimeText();
        });
    // Sans cet onFailed, une Task qui échoue (WAV corrompu, format inattendu…) laissait le
    // composant vide en silence, sans aucun retour pour l'utilisateur (issue #22).
    task.setOnFailed(e -> errorMessage.set(formatLoadError(task.getException())));

    Thread worker = new Thread(task, "audio-view-loader");
    worker.setDaemon(true);
    worker.start();
  }

  /** Traduit en français les causes d'échec courantes de {@link AudioSample#load}. */
  static String formatLoadError(Throwable cause) {
    if (cause instanceof UnsupportedAudioFileException) {
      return "Fichier audio non pris en charge (WAV PCM 16 bits attendu).";
    }
    if (cause instanceof IOException) {
      return "Impossible de lire le fichier audio.";
    }
    String msg = cause == null ? null : cause.getMessage();
    return "Erreur de chargement" + (msg != null ? " : " + msg : ".");
  }

  private record LoadResult(AudioSample sample, Spectrogram spectrogram) {}

  private void updateTimeText() {
    timeText.set(formatTimeText(currentTime.get(), duration.get(), expansionFactor()));
  }

  // ----- Calculs purs (testables sans interface) -----

  double expansionFactor() {
    double f = timeExpansion.get();
    return f > 0 ? f : 1;
  }

  double windowDuration() {
    return windowDuration(duration.get(), timeZoom.get());
  }

  double windowStart() {
    return windowStart(duration.get(), currentTime.get(), timeZoom.get());
  }

  static double windowDuration(double dur, double timeZoom) {
    return dur <= 0 ? 0 : dur / Math.max(1, timeZoom);
  }

  static double windowStart(double dur, double current, double timeZoom) {
    double win = windowDuration(dur, timeZoom);
    if (win <= 0) {
      return 0;
    }
    double start = current - win / 2;
    return Math.max(0, Math.min(dur - win, start));
  }

  static String formatTimeText(double currentFile, double durationFile, double factor) {
    double f = factor > 0 ? factor : 1;
    return String.format("%.2f / %.2f s", currentFile / f, durationFile / f);
  }

  /**
   * Auto-échelle verticale du sonogramme : facteur tel que le pic du fichier remplisse ~95% de la
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

  /** Pas de graduation « rond » (1, 2, 5 × 10^k) couvrant range avec ~target intervalles. */
  static double niceStep(double range, int target) {
    if (range <= 0 || target <= 0) {
      return 1;
    }
    double raw = range / target;
    double mag = Math.pow(10, Math.floor(Math.log10(raw)));
    double norm = raw / mag;
    double step;
    if (norm < 1.5) {
      step = 1;
    } else if (norm < 3) {
      step = 2;
    } else if (norm < 7) {
      step = 5;
    } else {
      step = 10;
    }
    return step * mag;
  }

  static String formatAxis(double value, double step) {
    int decimals = step >= 1 ? 0 : (int) Math.min(3, Math.ceil(-Math.log10(step)));
    return String.format("%." + decimals + "f", value);
  }

  /**
   * Couleur de la colormap du spectrogramme pour une intensité normalisée {@code t} dans [0,1].
   * Deux rampes : sombre (fond noir, énergie magma) ou claire (fond blanc cassé assorti au fond CSS
   * du thème clair, énergie bleu → violet) selon {@code light}.
   */
  static Color colormap(double t, boolean light) {
    double[][] stops =
        light
            ? new double[][] {
              {0.00, 0.957, 0.965, 0.973},
              {0.40, 0.45, 0.70, 0.90},
              {0.75, 0.15, 0.30, 0.70},
              {1.00, 0.25, 0.00, 0.35}
            }
            : new double[][] {
              {0.00, 0.00, 0.00, 0.00},
              {0.35, 0.20, 0.05, 0.45},
              {0.70, 0.85, 0.15, 0.35},
              {1.00, 1.00, 0.95, 0.30}
            };
    for (int i = 1; i < stops.length; i++) {
      if (t <= stops[i][0]) {
        double[] lo = stops[i - 1];
        double[] hi = stops[i];
        double f = (t - lo[0]) / (hi[0] - lo[0]);
        return Color.color(
            lo[1] + f * (hi[1] - lo[1]), lo[2] + f * (hi[2] - lo[2]), lo[3] + f * (hi[3] - lo[3]));
      }
    }
    double[] last = stops[stops.length - 1];
    return Color.color(last[1], last[2], last[3]);
  }

  /** Construit l'image du spectrogramme (nécessite le toolkit JavaFX). */
  WritableImage buildSpectrogramImage(Spectrogram spec) {
    int w = Math.max(1, spec.frameCount());
    int h = Math.max(1, spec.binCount());
    WritableImage img = new WritableImage(w, h);
    PixelWriter pw = img.getPixelWriter();
    for (int x = 0; x < spec.frameCount(); x++) {
      for (int y = 0; y < h; y++) {
        int bin = h - 1 - y; // basses fréquences en bas
        double db = spec.magnitudeDb(x, bin);
        double norm = (db - MIN_DB) / (MAX_DB - MIN_DB);
        norm = Math.max(0, Math.min(1, norm));
        pw.setColor(x, y, colormap(norm, lightColormap));
      }
    }
    return img;
  }

  /**
   * Bascule la colormap (sombre/claire) et reconstruit l'image du spectrogramme si un est chargé.
   */
  void setLightColormap(boolean light) {
    if (lightColormap == light) {
      return;
    }
    lightColormap = light;
    if (spectrogram != null) {
      spectrogramImage.set(buildSpectrogramImage(spectrogram));
    }
  }

  // ----- Accesseurs (pour la vue) -----

  ObjectProperty<Path> audioFileProperty() {
    return audioFile;
  }

  BooleanProperty playingProperty() {
    return playing;
  }

  DoubleProperty timeZoomProperty() {
    return timeZoom;
  }

  DoubleProperty frequencyZoomProperty() {
    return frequencyZoom;
  }

  DoubleProperty timeExpansionFactorProperty() {
    return timeExpansion;
  }

  ReadOnlyDoubleProperty currentTimeProperty() {
    return currentTime.getReadOnlyProperty();
  }

  ReadOnlyDoubleProperty durationProperty() {
    return duration.getReadOnlyProperty();
  }

  ReadOnlyObjectProperty<AudioSample> sampleProperty() {
    return sample.getReadOnlyProperty();
  }

  ReadOnlyObjectProperty<WritableImage> spectrogramImageProperty() {
    return spectrogramImage.getReadOnlyProperty();
  }

  ReadOnlyDoubleProperty sonoScaleProperty() {
    return sonoScale.getReadOnlyProperty();
  }

  ReadOnlyStringProperty timeTextProperty() {
    return timeText.getReadOnlyProperty();
  }

  ReadOnlyStringProperty errorMessageProperty() {
    return errorMessage.getReadOnlyProperty();
  }

  AudioSample getSample() {
    return sample.get();
  }

  WritableImage getSpectrogramImage() {
    return spectrogramImage.get();
  }

  double getSonoScale() {
    return sonoScale.get();
  }

  double getCurrentTime() {
    return currentTime.get();
  }

  double getDuration() {
    return duration.get();
  }

  double getFrequencyZoom() {
    return frequencyZoom.get();
  }
}
