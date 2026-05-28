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
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

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
// AudioView est volontairement une classe-composant cohésive (API + vue + rendu + lecture en un
// seul
// point public, boîte noire pour les étudiants). Les métriques de taille / complexité de classe du
// ruleset pédagogique ne s'y appliquent pas comme à du code étudiant : on les neutralise.
@SuppressWarnings({"PMD.GodClass", "PMD.NcssCount", "PMD.CyclomaticComplexity"})
public class AudioView extends Region {

  /** Taille de fenêtre FFT (puissance de deux). */
  private static final int FFT_SIZE = 1024;

  /** Pas entre deux fenêtres FFT successives, en échantillons. */
  private static final int HOP = 256;

  private static final double MIN_DB = -90.0;
  private static final double MAX_DB = -10.0;

  /**
   * Largeur de la gouttière gauche (graduations de fréquence) et hauteur de celle du bas (temps).
   */
  private static final double AXIS_LEFT = 48;

  private static final double AXIS_BOTTOM = 26;
  private static final Color AXIS_BG = Color.web("#0b0f14");
  private static final Color AXIS_TEXT = Color.web("#9aa4ad");
  private static final Color AXIS_GRID = Color.web("#ffffff", 0.10);
  private static final Font AXIS_FONT = Font.font(10);
  private static final LinearGradient COLORBAR_GRADIENT = buildColorbarGradient();

  // ----- Propriétés publiques -----
  private final ObjectProperty<Path> audioFile = new SimpleObjectProperty<>(this, "audioFile");
  private final BooleanProperty playing = new SimpleBooleanProperty(this, "playing", false);
  private final ReadOnlyDoubleWrapper currentTime =
      new ReadOnlyDoubleWrapper(this, "currentTime", 0);
  private final ReadOnlyDoubleWrapper duration = new ReadOnlyDoubleWrapper(this, "duration", 0);
  private final DoubleProperty timeZoom = new SimpleDoubleProperty(this, "timeZoom", 1);
  private final DoubleProperty frequencyZoom = new SimpleDoubleProperty(this, "frequencyZoom", 1);
  private final DoubleProperty timeExpansion =
      new SimpleDoubleProperty(this, "timeExpansionFactor", 1);

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

  /** Auto-échelle verticale du sonogramme : facteur tel que le pic du fichier remplisse la zone. */
  private double sonoScale = 1;

  public AudioView() {
    Button zoomTimeIn = new Button("Temps +");
    Button zoomTimeOut = new Button("Temps -");
    Button zoomFreqIn = new Button("Fréq. +");
    Button zoomFreqOut = new Button("Fréq. -");
    zoomTimeIn.setOnAction(e -> timeZoom.set(Math.min(64, timeZoom.get() * 2)));
    zoomTimeOut.setOnAction(e -> timeZoom.set(Math.max(1, timeZoom.get() / 2)));
    zoomFreqIn.setOnAction(e -> frequencyZoom.set(Math.min(64, frequencyZoom.get() * 2)));
    zoomFreqOut.setOnAction(e -> frequencyZoom.set(Math.max(1, frequencyZoom.get() / 2)));
    playButton.setOnAction(e -> togglePlay());

    HBox toolbar =
        new HBox(8, playButton, zoomTimeIn, zoomTimeOut, zoomFreqIn, zoomFreqOut, timeLabel);
    toolbar.setAlignment(Pos.CENTER_LEFT);
    toolbar.setPadding(new Insets(6));

    // Légende des couleurs : noeuds (Rectangle dégradé + Labels) superposés en haut-droite du
    // spectrogramme via un StackPane, placés par le layout + un binding (translateY suit la hauteur
    // du sonogramme).
    Pane legende = buildColorbarLegend();

    // Chaque Canvas est enveloppé dans un Pane de taille minimale nulle. Sinon le Canvas (non
    // redimensionnable) impose sa taille courante comme minimum, ce qui empêche la mise en page de
    // RÉTRÉCIR (l'agrandissement fonctionnait, pas la réduction). Le Canvas suit la taille de son
    // hôte par binding.
    Pane sonoHost = new Pane(sonoCanvas);
    Pane spectroHost = new Pane(spectroCanvas);
    sonoHost.setMinSize(0, 0);
    spectroHost.setMinSize(0, 0);
    sonoCanvas.widthProperty().bind(sonoHost.widthProperty());
    sonoCanvas.heightProperty().bind(sonoHost.heightProperty());
    spectroCanvas.widthProperty().bind(spectroHost.widthProperty());
    spectroCanvas.heightProperty().bind(spectroHost.heightProperty());

    VBox plots = new VBox(2, sonoHost, spectroHost);
    sonoHost.prefHeightProperty().bind(plots.heightProperty().multiply(0.32));
    spectroHost.prefHeightProperty().bind(plots.heightProperty().multiply(0.68).subtract(2));

    StackPane plotsStack = new StackPane(plots, legende);
    StackPane.setAlignment(legende, Pos.TOP_RIGHT);
    StackPane.setMargin(legende, new Insets(0, 8, 0, 0));
    legende.translateYProperty().bind(sonoCanvas.heightProperty().add(8));

    content.setFillWidth(true);
    content.getChildren().addAll(toolbar, plotsStack);
    VBox.setVgrow(plotsStack, Priority.ALWAYS);
    getChildren().add(content);

    // Redessins
    Runnable redraw = this::redraw;
    sonoCanvas.widthProperty().addListener((o, a, b) -> redraw.run());
    sonoCanvas.heightProperty().addListener((o, a, b) -> redraw.run());
    spectroCanvas.widthProperty().addListener((o, a, b) -> redraw.run());
    spectroCanvas.heightProperty().addListener((o, a, b) -> redraw.run());
    timeZoom.addListener((o, a, b) -> redraw.run());
    frequencyZoom.addListener((o, a, b) -> redraw.run());
    timeExpansion.addListener(
        (o, a, b) -> {
          updateTimeLabel();
          redraw.run();
        });
    currentTime.addListener(
        (o, a, b) -> {
          updateTimeLabel();
          redraw.run();
        });
    duration.addListener((o, a, b) -> updateTimeLabel());

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
            // Si la lecture est en fin d'extrait, un nouveau clic sur Lecture repart de zéro.
            if (duration.get() > 0 && currentTime.get() >= duration.get()) {
              player.seek(0);
              currentTime.set(0);
            }
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
    sonoScale = 1;
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
          this.sonoScale = sonoScaleFor(result.sample());
          this.spectrogramImage = buildSpectrogramImage(result.spectrogram());
          // Cale par défaut la vue fréquentielle sur la bande réellement utilisée par le signal.
          frequencyZoom.set(autoFrequencyZoom(result.spectrogram()));
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
    g.setFill(AXIS_BG);
    g.fillRect(0, 0, w, h);
    double plotX = AXIS_LEFT;
    double plotW = w - AXIS_LEFT;
    if (sample == null || plotW < 1 || h < 1) {
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
    int cols = (int) plotW;
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
      g.strokeLine(plotX + c, mid - max * sonoScale * mid, plotX + c, mid - min * sonoScale * mid);
    }
    drawCursor(g, plotX, plotW, h, start, win);
  }

  /**
   * Auto-échelle verticale du sonogramme : facteur tel que le pic du fichier remplisse ~95% de la
   * demi-hauteur. Les enregistrements de chiroptères étant de faible amplitude, sans cela la forme
   * d'onde resterait minuscule. Plafonné pour ne pas amplifier démesurément un fichier quasi muet.
   */
  private static double sonoScaleFor(AudioSample s) {
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
  private static double autoFrequencyZoom(Spectrogram spec) {
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
    // Fraction de la bande de Nyquist occupée, avec 30 % de marge au-dessus du dernier cri.
    double fraction = Math.min(1.0, ((plusHaut + 1) / (double) (bins - 1)) * 1.3);
    if (fraction <= 0) {
      return 1;
    }
    return Math.max(1, Math.min(64, 1.0 / fraction));
  }

  private void drawSpectrogram() {
    GraphicsContext g = spectroCanvas.getGraphicsContext2D();
    double w = spectroCanvas.getWidth();
    double h = spectroCanvas.getHeight();
    g.setFill(AXIS_BG);
    g.fillRect(0, 0, w, h);
    double plotX = AXIS_LEFT;
    double plotW = w - AXIS_LEFT;
    double plotH = h - AXIS_BOTTOM;
    if (spectrogramImage == null || sample == null || plotW < 1 || plotH < 1) {
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
    g.drawImage(
        spectrogramImage, sx, sy, Math.max(1, sw), Math.max(1, visibleH), plotX, 0, plotW, plotH);

    // Fréquence max visible dans le fichier (Hz), avant application du facteur d'expansion.
    double fMaxFileVisible = (sample.sampleRate() / 2.0) / fz;
    drawFrequencyAxis(g, plotX, plotW, plotH, fMaxFileVisible);
    drawTimeAxis(g, plotX, plotW, plotH, start, win);
    drawCursor(g, plotX, plotW, plotH, start, win);
  }

  /**
   * Construit la légende des couleurs (intensité en dB) comme un assemblage de noeuds : fond
   * arrondi semi-transparent, bande dégradée reprenant la colormap, libellés dB. Étant de vrais
   * noeuds, elle se rend correctement en HiDPI et son placement est géré par le layout (pas de
   * dessin Canvas).
   */
  private Pane buildColorbarLegend() {
    double w = 56;
    double barH = 150;
    double headerH = 14;
    double stripW = 10;
    double stripX = w - stripW - 6;
    double stripTop = headerH;

    Pane legende = new Pane();
    legende.setMouseTransparent(true);
    legende.setPrefSize(w, headerH + barH + 8);
    legende.setMaxSize(w, headerH + barH + 8);
    legende.setStyle("-fx-background-color: rgba(11,15,20,0.6); -fx-background-radius: 6;");

    Label entete = new Label("dB");
    entete.setFont(AXIS_FONT);
    entete.setTextFill(AXIS_TEXT);
    entete.setLayoutX(6);
    entete.setLayoutY(0);
    legende.getChildren().add(entete);

    Rectangle bande = new Rectangle(stripX, stripTop, stripW, barH);
    bande.setFill(COLORBAR_GRADIENT);
    bande.setStroke(AXIS_TEXT);
    bande.setStrokeWidth(1);
    legende.getChildren().add(bande);

    double range = MAX_DB - MIN_DB;
    double step = niceStep(range, 4);
    int nTicks = (int) Math.round(range / step);
    for (int i = 0; i <= nTicks; i++) {
      double db = MIN_DB + i * step;
      double y = stripTop + barH * (1 - (db - MIN_DB) / range);
      Label valeur = new Label(formatAxis(db, step));
      valeur.setFont(AXIS_FONT);
      valeur.setTextFill(AXIS_TEXT);
      valeur.setLayoutX(2);
      valeur.setLayoutY(y - 8);
      valeur.setPrefWidth(stripX - 6);
      valeur.setAlignment(Pos.CENTER_RIGHT);
      legende.getChildren().add(valeur);
    }
    return legende;
  }

  /** Dégradé reproduisant la colormap du spectrogramme (haut = intensité max). */
  private static LinearGradient buildColorbarGradient() {
    int n = 16;
    Stop[] stops = new Stop[n + 1];
    for (int i = 0; i <= n; i++) {
      double offset = i / (double) n;
      stops[i] = new Stop(offset, colormap(1 - offset));
    }
    return new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE, stops);
  }

  private void drawCursor(
      GraphicsContext g, double plotX, double plotW, double plotH, double start, double win) {
    if (win <= 0) {
      return;
    }
    double rel = (currentTime.get() - start) / win;
    if (rel < 0 || rel > 1) {
      return;
    }
    g.setStroke(Color.web("#ff5252"));
    g.setLineWidth(1.5);
    double cx = plotX + rel * plotW;
    g.strokeLine(cx, 0, cx, plotH);
  }

  /** Graduations de fréquence (gouttière gauche), en kHz, mises à l'échelle par le facteur. */
  private void drawFrequencyAxis(
      GraphicsContext g, double plotX, double plotW, double plotH, double fMaxFileVisibleHz) {
    double fMaxHz = fMaxFileVisibleHz * expansionFactor();
    if (fMaxHz <= 0) {
      return;
    }
    g.setFont(AXIS_FONT);
    g.setTextBaseline(VPos.CENTER);
    double stepHz = niceStep(fMaxHz, 5);
    int nTicks = (int) Math.floor(fMaxHz / stepHz);
    for (int i = 0; i <= nTicks; i++) {
      double f = i * stepHz;
      double y = plotH * (1 - f / fMaxHz);
      g.setStroke(AXIS_GRID);
      g.setLineWidth(1);
      g.strokeLine(plotX, y, plotX + plotW, y);
      g.setStroke(AXIS_TEXT);
      g.strokeLine(plotX - 4, y, plotX, y);
      g.setFill(AXIS_TEXT);
      g.setTextAlign(TextAlignment.RIGHT);
      double ty = Math.max(7, Math.min(plotH - 2, y));
      g.fillText(formatAxis(f / 1000.0, stepHz / 1000.0), plotX - 6, ty);
    }
    g.setFill(AXIS_TEXT);
    g.setTextAlign(TextAlignment.LEFT);
    g.setTextBaseline(VPos.TOP);
    g.fillText("kHz", 2, 1);
  }

  /** Graduations de temps (gouttière basse), en secondes, mises à l'échelle par le facteur. */
  private void drawTimeAxis(
      GraphicsContext g, double plotX, double plotW, double plotH, double start, double win) {
    if (win <= 0) {
      return;
    }
    double factor = expansionFactor();
    double t0 = start / factor;
    double t1 = (start + win) / factor;
    double range = t1 - t0;
    double step = niceStep(range, 6);
    g.setFont(AXIS_FONT);
    g.setTextBaseline(VPos.TOP);
    g.setTextAlign(TextAlignment.CENTER);
    int firstIndex = (int) Math.ceil(t0 / step);
    int lastIndex = (int) Math.floor(t1 / step);
    for (int i = firstIndex; i <= lastIndex; i++) {
      double t = i * step;
      double x = plotX + plotW * ((t - t0) / range);
      g.setStroke(AXIS_GRID);
      g.setLineWidth(1);
      g.strokeLine(x, 0, x, plotH);
      g.setStroke(AXIS_TEXT);
      g.strokeLine(x, plotH, x, plotH + 4);
      g.setFill(AXIS_TEXT);
      double tx = Math.max(plotX + 10, Math.min(plotX + plotW - 10, x));
      g.fillText(formatAxis(t, step), tx, plotH + 5);
    }
    g.setFill(AXIS_TEXT);
    g.setTextAlign(TextAlignment.RIGHT);
    g.fillText("s", plotX + plotW, plotH + 5);
  }

  private double expansionFactor() {
    double f = timeExpansion.get();
    return f > 0 ? f : 1;
  }

  /** Pas de graduation « rond » (1, 2, 5 × 10^k) couvrant range avec ~target intervalles. */
  private static double niceStep(double range, int target) {
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

  private static String formatAxis(double value, double step) {
    int decimals = step >= 1 ? 0 : (int) Math.min(3, Math.ceil(-Math.log10(step)));
    return String.format("%." + decimals + "f", value);
  }

  private void seekFromX(double mouseX, double canvasWidth) {
    double plotW = canvasWidth - AXIS_LEFT;
    if (duration.get() <= 0 || plotW <= 0) {
      return;
    }
    double rel = Math.max(0, Math.min(1, (mouseX - AXIS_LEFT) / plotW));
    double win = windowDuration();
    double start = windowStart();
    double t = Math.max(0, Math.min(duration.get(), start + rel * win));
    player.seek(t);
    currentTime.set(t);
  }

  private void updateTimeLabel() {
    double f = expansionFactor();
    timeLabel.setText(String.format("%.2f / %.2f s", currentTime.get() / f, duration.get() / f));
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
            lo[1] + f * (hi[1] - lo[1]), lo[2] + f * (hi[2] - lo[2]), lo[3] + f * (hi[3] - lo[3]));
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
    timeZoom.set(Math.max(1, value));
  }

  public final DoubleProperty frequencyZoomProperty() {
    return frequencyZoom;
  }

  public final double getFrequencyZoom() {
    return frequencyZoom.get();
  }

  public final void setFrequencyZoom(double value) {
    frequencyZoom.set(Math.max(1, value));
  }

  public final DoubleProperty timeExpansionFactorProperty() {
    return timeExpansion;
  }

  public final double getTimeExpansionFactor() {
    return timeExpansion.get();
  }

  /**
   * Règle le facteur d'expansion temporelle des enregistrements (par exemple 10 pour les WAV de
   * chiroptères ralentis ×10 lors de l'acquisition). Il n'affecte que les libellés affichés (axes
   * et barre d'outils) : la fréquence affichée vaut {@code fréquence_fichier × facteur} et le temps
   * affiché {@code temps_fichier ÷ facteur}. Les propriétés {@link #currentTimeProperty()} et
   * {@link #durationProperty()} restent exprimées dans le temps du fichier (utilisé pour la
   * lecture).
   */
  public final void setTimeExpansionFactor(double value) {
    timeExpansion.set(value > 0 ? value : 1);
  }

  /** Libère le périphérique audio. À appeler quand le composant n'est plus utilisé. */
  public void dispose() {
    timer.stop();
    player.close();
  }
}
