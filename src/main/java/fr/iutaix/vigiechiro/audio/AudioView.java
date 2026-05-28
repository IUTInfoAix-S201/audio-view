package fr.iutaix.vigiechiro.audio;

import java.nio.file.Path;
import javafx.animation.AnimationTimer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Composant JavaFX affichant le sonogramme (amplitude / temps) et le spectrogramme (fréquence /
 * temps) d'un fichier WAV, avec un curseur de lecture synchronisé et des zooms temps / fréquence.
 *
 * <p>Le composant est instanciable depuis du code ou depuis du FXML (constructeur sans argument).
 * La source se règle via {@link #setAudioFile(Path)} ; le décodage et le calcul de la FFT sont
 * réalisés en tâche de fond. On observe ensuite {@link #currentTimeProperty()} et {@link
 * #durationProperty()} pour se synchroniser avec le reste de l'application.
 *
 * <pre>{@code
 * AudioView view = new AudioView();
 * view.setAudioFile(Path.of("samples/seq_0001.wav"));
 * view.currentTimeProperty().addListener((o, a, b) -> ...);
 * view.setPlaying(true);
 * }</pre>
 */
public class AudioView extends Region {

  /** Taille de fenêtre FFT (puissance de deux). */
  private static final int FFT_SIZE = 1024;

  /** Pas entre deux fenêtres FFT successives, en échantillons. */
  private static final int HOP = 256;

  private static final double MIN_DB = -90.0;
  private static final double MAX_DB = -10.0;

  // ----- Propriétés publiques -----
  private final ObjectProperty<Path> audioFile = new SimpleObjectProperty<>(this, "audioFile");
  private final BooleanProperty playing = new SimpleBooleanProperty(this, "playing", false);
  private final ReadOnlyDoubleWrapper currentTime =
      new ReadOnlyDoubleWrapper(this, "currentTime", 0);
  private final ReadOnlyDoubleWrapper duration = new ReadOnlyDoubleWrapper(this, "duration", 0);
  private final DoubleProperty timeZoom = new SimpleDoubleProperty(this, "timeZoom", 1);
  private final DoubleProperty frequencyZoom = new SimpleDoubleProperty(this, "frequencyZoom", 1);

  // ----- Vue interne -----
  private final VBox content = new VBox();
  private final Canvas sonoCanvas = new Canvas();
  private final Canvas spectroCanvas = new Canvas();
  private final Label timeLabel = new Label("0.00 / 0.00 s");
  private final Button playButton = new Button("Lecture");
  private final AudioPlayer player = new AudioPlayer();
  private final AnimationTimer timer;

  // ----- État audio -----
  private AudioSample sample;
  private WritableImage spectrogramImage;

  public AudioView() {
    Button zoomTimeIn = new Button("Temps +");
    Button zoomTimeOut = new Button("Temps -");
    Button zoomFreqIn = new Button("Fréq. +");
    Button zoomFreqOut = new Button("Fréq. -");
    zoomTimeIn.setOnAction(e -> timeZoom.set(Math.min(64, timeZoom.get() * 2)));
    zoomTimeOut.setOnAction(e -> timeZoom.set(Math.max(1, timeZoom.get() / 2)));
    zoomFreqIn.setOnAction(e -> frequencyZoom.set(Math.min(16, frequencyZoom.get() * 2)));
    zoomFreqOut.setOnAction(e -> frequencyZoom.set(Math.max(1, frequencyZoom.get() / 2)));
    playButton.setOnAction(e -> togglePlay());

    HBox toolbar =
        new HBox(8, playButton, zoomTimeIn, zoomTimeOut, zoomFreqIn, zoomFreqOut, timeLabel);
    toolbar.setAlignment(Pos.CENTER_LEFT);
    toolbar.setPadding(new Insets(6));

    VBox plots = new VBox(2, sonoCanvas, spectroCanvas);
    VBox.setVgrow(plots, Priority.ALWAYS);

    content.setFillWidth(true);
    content.getChildren().addAll(toolbar, plots);
    getChildren().add(content);

    // Dimensionnement des canvas
    sonoCanvas.widthProperty().bind(plots.widthProperty());
    spectroCanvas.widthProperty().bind(plots.widthProperty());
    sonoCanvas.heightProperty().bind(plots.heightProperty().multiply(0.32));
    spectroCanvas.heightProperty().bind(plots.heightProperty().multiply(0.68).subtract(2));

    // Redessins
    Runnable redraw = this::redraw;
    sonoCanvas.widthProperty().addListener((o, a, b) -> redraw.run());
    sonoCanvas.heightProperty().addListener((o, a, b) -> redraw.run());
    spectroCanvas.widthProperty().addListener((o, a, b) -> redraw.run());
    spectroCanvas.heightProperty().addListener((o, a, b) -> redraw.run());
    timeZoom.addListener((o, a, b) -> redraw.run());
    frequencyZoom.addListener((o, a, b) -> redraw.run());
    currentTime.addListener(
        (o, a, b) -> {
          updateTimeLabel();
          redraw.run();
        });

    // Déplacement du curseur de lecture par clic
    spectroCanvas.setOnMousePressed(e -> seekFromX(e.getX(), spectroCanvas.getWidth()));
    sonoCanvas.setOnMousePressed(e -> seekFromX(e.getX(), sonoCanvas.getWidth()));

    audioFile.addListener((o, oldFile, newFile) -> loadAudio(newFile));

    // timer doit etre assigne avant le listener "playing" qui le capture (champ final).
    timer =
        new AnimationTimer() {
          @Override
          public void handle(long now) {
            double pos = player.position();
            if (duration.get() > 0 && pos >= duration.get()) {
              currentTime.set(duration.get());
              setPlaying(false);
              return;
            }
            currentTime.set(pos);
          }
        };

    playing.addListener(
        (o, was, now) -> {
          if (now) {
            player.play();
            timer.start();
            playButton.setText("Pause");
          } else {
            player.pause();
            timer.stop();
            playButton.setText("Lecture");
          }
        });

    setMinSize(200, 120);
    setPrefSize(640, 360);
  }

  @Override
  protected void layoutChildren() {
    layoutInArea(content, 0, 0, getWidth(), getHeight(), 0, HPos.LEFT, VPos.TOP);
  }

  // ----- Chargement -----

  private void loadAudio(Path path) {
    setPlaying(false);
    player.close();
    currentTime.set(0);
    duration.set(0);
    sample = null;
    spectrogramImage = null;
    redraw();
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
          this.sample = result.sample();
          this.spectrogramImage = buildSpectrogramImage(result.spectrogram());
          try {
            player.load(path);
          } catch (Exception ignored) {
            // La lecture audio est optionnelle : l'affichage reste fonctionnel sans son.
          }
          duration.set(sample.durationSeconds());
          currentTime.set(0);
          redraw();
        });
    task.setOnFailed(e -> redraw());

    Thread worker = new Thread(task, "audio-view-loader");
    worker.setDaemon(true);
    worker.start();
  }

  private record LoadResult(AudioSample sample, Spectrogram spectrogram) {}

  private WritableImage buildSpectrogramImage(Spectrogram spec) {
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
        pw.setColor(x, y, colormap(norm));
      }
    }
    return img;
  }

  // ----- Rendu -----

  private void redraw() {
    drawSonogram();
    drawSpectrogram();
  }

  private double windowDuration() {
    double dur = duration.get();
    return dur <= 0 ? 0 : dur / Math.max(1, timeZoom.get());
  }

  private double windowStart() {
    double dur = duration.get();
    double win = windowDuration();
    if (win <= 0) {
      return 0;
    }
    double start = currentTime.get() - win / 2;
    return Math.max(0, Math.min(dur - win, start));
  }

  private void drawSonogram() {
    GraphicsContext g = sonoCanvas.getGraphicsContext2D();
    double w = sonoCanvas.getWidth();
    double h = sonoCanvas.getHeight();
    g.setFill(Color.web("#0b0f14"));
    g.fillRect(0, 0, w, h);
    if (sample == null || w < 1 || h < 1) {
      return;
    }

    float[] x = sample.samples();
    float sr = sample.sampleRate();
    double win = windowDuration();
    double start = windowStart();
    int s0 = (int) Math.floor(start * sr);
    int s1 = (int) Math.ceil((start + win) * sr);
    s0 = Math.max(0, Math.min(x.length, s0));
    s1 = Math.max(s0 + 1, Math.min(x.length, s1));
    double mid = h / 2.0;

    g.setStroke(Color.web("#7fd4ff"));
    g.setLineWidth(1);
    int cols = (int) w;
    double samplesPerCol = (s1 - s0) / (double) cols;
    for (int c = 0; c < cols; c++) {
      int a = s0 + (int) (c * samplesPerCol);
      int b = Math.min(s1, s0 + (int) ((c + 1) * samplesPerCol));
      float min = 0;
      float max = 0;
      for (int i = a; i < b; i++) {
        min = Math.min(min, x[i]);
        max = Math.max(max, x[i]);
      }
      g.strokeLine(c, mid - max * mid, c, mid - min * mid);
    }
    drawCursor(g, w, h, start, win);
  }

  private void drawSpectrogram() {
    GraphicsContext g = spectroCanvas.getGraphicsContext2D();
    double w = spectroCanvas.getWidth();
    double h = spectroCanvas.getHeight();
    g.setFill(Color.web("#0b0f14"));
    g.fillRect(0, 0, w, h);
    if (spectrogramImage == null || w < 1 || h < 1) {
      return;
    }

    double dur = duration.get();
    double win = windowDuration();
    double start = windowStart();
    double imgW = spectrogramImage.getWidth();
    double imgH = spectrogramImage.getHeight();

    double sx = dur <= 0 ? 0 : (start / dur) * imgW;
    double sw = dur <= 0 ? imgW : (win / dur) * imgW;

    // Zoom fréquence : on n'affiche que la tranche basse (0 .. fMax / zoom),
    // située en bas de l'image.
    double fz = Math.max(1, frequencyZoom.get());
    double visibleH = imgH / fz;
    double sy = imgH - visibleH;

    g.setImageSmoothing(false);
    g.drawImage(spectrogramImage, sx, sy, Math.max(1, sw), Math.max(1, visibleH), 0, 0, w, h);
    drawCursor(g, w, h, start, win);
  }

  private void drawCursor(GraphicsContext g, double w, double h, double start, double win) {
    if (win <= 0) {
      return;
    }
    double rel = (currentTime.get() - start) / win;
    if (rel < 0 || rel > 1) {
      return;
    }
    g.setStroke(Color.web("#ff5252"));
    g.setLineWidth(1.5);
    g.strokeLine(rel * w, 0, rel * w, h);
  }

  private void seekFromX(double mouseX, double canvasWidth) {
    if (duration.get() <= 0 || canvasWidth <= 0) {
      return;
    }
    double win = windowDuration();
    double start = windowStart();
    double t = Math.max(0, Math.min(duration.get(), start + (mouseX / canvasWidth) * win));
    player.seek(t);
    currentTime.set(t);
  }

  private void updateTimeLabel() {
    timeLabel.setText(String.format("%.2f / %.2f s", currentTime.get(), duration.get()));
  }

  private static Color colormap(double t) {
    double[][] stops = {
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
            lo[1] + f * (hi[1] - lo[1]),
            lo[2] + f * (hi[2] - lo[2]),
            lo[3] + f * (hi[3] - lo[3]));
      }
    }
    double[] last = stops[stops.length - 1];
    return Color.color(last[1], last[2], last[3]);
  }

  // ----- Accès aux propriétés -----

  public final ObjectProperty<Path> audioFileProperty() {
    return audioFile;
  }

  public final Path getAudioFile() {
    return audioFile.get();
  }

  public final void setAudioFile(Path path) {
    audioFile.set(path);
  }

  /** Pratique pour le FXML : règle la source à partir d'un chemin sous forme de chaîne. */
  public final void setSource(String path) {
    setAudioFile(path == null ? null : Path.of(path));
  }

  public final BooleanProperty playingProperty() {
    return playing;
  }

  public final boolean isPlaying() {
    return playing.get();
  }

  public final void setPlaying(boolean value) {
    playing.set(value);
  }

  public final void togglePlay() {
    setPlaying(!isPlaying());
  }

  public final ReadOnlyDoubleProperty currentTimeProperty() {
    return currentTime.getReadOnlyProperty();
  }

  public final double getCurrentTime() {
    return currentTime.get();
  }

  public final ReadOnlyDoubleProperty durationProperty() {
    return duration.getReadOnlyProperty();
  }

  public final double getDuration() {
    return duration.get();
  }

  public final DoubleProperty timeZoomProperty() {
    return timeZoom;
  }

  public final double getTimeZoom() {
    return timeZoom.get();
  }

  public final void setTimeZoom(double value) {
    timeZoom.set(value);
  }

  public final DoubleProperty frequencyZoomProperty() {
    return frequencyZoom;
  }

  public final double getFrequencyZoom() {
    return frequencyZoom.get();
  }

  public final void setFrequencyZoom(double value) {
    frequencyZoom.set(value);
  }

  /** Libère le périphérique audio. À appeler quand le composant n'est plus utilisé. */
  public void dispose() {
    timer.stop();
    player.close();
  }
}
