package fr.nedjar.vigiechiro.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.BooleanPropertyBase;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.css.CssMetaData;
import javafx.css.PseudoClass;
import javafx.css.Styleable;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.css.converter.ColorConverter;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/**
 * Composant JavaFX affichant le sonogramme (amplitude / temps) et le spectrogramme (fréquence /
 * temps) d'un fichier WAV, avec un curseur de lecture synchronisé et des zooms temps / fréquence.
 *
 * <p>Le composant est instanciable depuis du code ou depuis du FXML (constructeur sans argument).
 * La source se règle via {@link #setAudioFile(Path)} ; le décodage et le calcul de la FFT sont
 * réalisés en tâche de fond. On observe ensuite {@link #currentTimeProperty()} et {@link
 * #durationProperty()} pour se synchroniser avec le reste de l'application.
 *
 * <p>Architecture MVVM : l'état et la logique vivent dans {@link AudioViewModel} ; cette classe est
 * la <b>vue</b>, un custom control <i>fx:root</i> ({@code extends BorderPane}) qui charge {@code
 * AudioView.fxml} au constructeur ({@link FXMLLoader#setRoot}/{@link FXMLLoader#setController}) et
 * se lie au ViewModel par bindings. Le spectrogramme est un {@link ImageView} recadré par {@code
 * viewport}, le curseur et les axes sont de vrais nœuds ; seule l'enveloppe du sonogramme (des
 * milliers de segments par colonne) reste tracée sur {@link Canvas}.
 *
 * <pre>{@code
 * AudioView view = new AudioView();
 * view.setAudioFile(Path.of("samples/seq_0001.wav"));
 * view.currentTimeProperty().addListener((o, a, b) -> ...);
 * view.setPlaying(true);
 * }</pre>
 */
// Classe-vue cohésive (rendu Canvas + nœuds + chrome + délégation). Métriques de taille
// neutralisées.
@SuppressWarnings({"PMD.GodClass", "PMD.NcssCount", "PMD.CyclomaticComplexity"})
public class AudioView extends BorderPane {

  /**
   * Largeur de la gouttière gauche (graduations de fréquence) et hauteur de celle du bas (temps).
   */
  private static final double AXIS_LEFT = 48;

  private static final double AXIS_BOTTOM = 26;

  /**
   * Seuils de masquage responsive (issue #30) : sous ces tailles, la légende dB et/ou le sonogramme
   * sont masqués pour laisser toute la place au spectrogramme — plutôt que de tout tasser jusqu'à
   * devenir illisible. La barre d'outils, elle, reste toujours visible (la hauteur min du composant
   * est calculée par {@link BorderPane} depuis son {@code top}).
   */
  private static final double LEGEND_MIN_PLOT_WIDTH = 320;

  private static final double LEGEND_MIN_SPECTRO_HEIGHT = 200;

  private static final double SONO_MIN_PLOTS_HEIGHT = 120;

  private static final Color AXIS_TEXT = Color.web("#9aa4ad");
  private static final Color WAVE_COLOR_DEFAULT = Color.web("#7fd4ff");

  /** Pseudo-classe CSS {@code :light} : bascule le thème clair (voir {@code audio-view.css}). */
  private static final PseudoClass LIGHT_THEME = PseudoClass.getPseudoClass("light");

  /**
   * Couleur de l'enveloppe du sonogramme, stylable via {@code -fx-wave-color} sur {@code
   * .audio-view}. Comme elle est tracée sur Canvas (invisible au moteur CSS), elle est exposée par
   * une {@link StyleableObjectProperty} + {@link CssMetaData} ; tout changement redessine le tracé.
   */
  private final StyleableObjectProperty<Color> waveColor =
      new StyleableObjectProperty<>(WAVE_COLOR_DEFAULT) {
        @Override
        public Object getBean() {
          return AudioView.this;
        }

        @Override
        public String getName() {
          return "waveColor";
        }

        @Override
        public CssMetaData<? extends Styleable, Color> getCssMetaData() {
          return StyleableProperties.WAVE_COLOR;
        }

        @Override
        protected void invalidated() {
          drawSonogram();
        }
      };

  /**
   * Active le thème clair. Bascule la pseudo-classe CSS {@code :light} sur le composant ; tout le
   * rendu (chrome et couleur du sonogramme) est alors pris dans les règles {@code
   * .audio-view:light} de {@code audio-view.css}. Faux par défaut (thème sombre).
   */
  private final BooleanProperty lightTheme =
      new BooleanPropertyBase(false) {
        @Override
        protected void invalidated() {
          applyTheme(get());
        }

        @Override
        public Object getBean() {
          return AudioView.this;
        }

        @Override
        public String getName() {
          return "lightTheme";
        }
      };

  private final AudioViewModel vm = new AudioViewModel();
  // Sous-VM (issue #9) : façades dédiées à chaque tracé. La session reste vm pour l'état partagé
  // (toolbar, lecture, zooms, erreurs) ; sonoVm / spectroVm n'exposent que ce qui sert au rendu de
  // leur sous-vue, plus un calcul pur par tracé (amplitudePeak/Step, viewport).
  private final SonogramViewModel sonoVm = new SonogramViewModel(vm);
  private final SpectrogramViewModel spectroVm = new SpectrogramViewModel(vm);

  // ----- Vue (injectée depuis AudioView.fxml) -----
  @FXML private VBox plots;
  @FXML private StackPane plotsStack;
  @FXML private Pane sonoHost;
  @FXML private Pane spectroHost;
  @FXML private Canvas sonoCanvas;
  @FXML private Pane sonoAxisLayer;
  @FXML private Line sonoCursor;
  @FXML private ImageView spectroImage;
  @FXML private Pane axisLayer;
  @FXML private Line spectroCursor;
  @FXML private Pane legend;
  @FXML private Label timeLabel;
  @FXML private Button playButton;

  // Overlay d'erreur, visible quand le VM signale une erreur de chargement (issue #22).
  @FXML private Label errorOverlay;

  // Bande dégradée de la légende dB (construite par le contrôleur), réactualisée au changement de
  // thème pour refléter la colormap active.
  private Rectangle colorbarBande;

  public AudioView() {
    FXMLLoader loader = new FXMLLoader(getClass().getResource("AudioView.fxml"));
    loader.setRoot(this);
    loader.setController(this);
    try {
      loader.load();
    } catch (IOException e) {
      throw new IllegalStateException("Chargement de AudioView.fxml impossible", e);
    }
  }

  /** Câblage vue ↔ ViewModel, appelé par le FXMLLoader après injection des champs {@code @FXML}. */
  @FXML
  private void initialize() {
    playButton
        .textProperty()
        .bind(Bindings.when(vm.playingProperty()).then("Pause").otherwise("Lecture"));
    timeLabel.textProperty().bind(vm.timeTextProperty());

    // Sonogramme : le Canvas (non redimensionnable) suit son hôte à taille minimale nulle.
    sonoCanvas.widthProperty().bind(sonoHost.widthProperty());
    sonoCanvas.heightProperty().bind(sonoHost.heightProperty());

    // Spectrogramme : l'image remplit la zone de tracé (hôte moins les gouttières) ; le recadrage
    // temps/fréquence se fait par viewport (mis à jour dans updateSpectrogram).
    spectroImage.setLayoutX(AXIS_LEFT);
    spectroImage.setLayoutY(0);
    spectroImage.setPreserveRatio(false);
    spectroImage.fitWidthProperty().bind(spectroHost.widthProperty().subtract(AXIS_LEFT));
    spectroImage.fitHeightProperty().bind(spectroHost.heightProperty().subtract(AXIS_BOTTOM));
    axisLayer.prefWidthProperty().bind(spectroHost.widthProperty());
    axisLayer.prefHeightProperty().bind(spectroHost.heightProperty());
    sonoAxisLayer.prefWidthProperty().bind(sonoHost.widthProperty());
    sonoAxisLayer.prefHeightProperty().bind(sonoHost.heightProperty());

    // Répartition verticale sonogramme / spectrogramme (32 % / 68 %).
    sonoHost.prefHeightProperty().bind(plots.heightProperty().multiply(0.32));
    spectroHost.prefHeightProperty().bind(plots.heightProperty().multiply(0.68));

    // Adaptation responsive (issue #30) : à l'étroit, on masque les éléments accessoires plutôt
    // que de tout tasser. La toolbar reste toujours visible (minHeight calculé par BorderPane
    // depuis son top, cf. AudioView.fxml). spectroHost a VBox.vgrow=ALWAYS dans la FXML : quand le
    // sonogramme est masqué, le spectrogramme prend toute la place disponible.
    legend
        .visibleProperty()
        .bind(
            spectroHost
                .widthProperty()
                .subtract(AXIS_LEFT)
                .greaterThanOrEqualTo(LEGEND_MIN_PLOT_WIDTH)
                .and(spectroHost.heightProperty().greaterThanOrEqualTo(LEGEND_MIN_SPECTRO_HEIGHT)));
    legend.managedProperty().bind(legend.visibleProperty());
    sonoHost
        .visibleProperty()
        .bind(plots.heightProperty().greaterThanOrEqualTo(SONO_MIN_PLOTS_HEIGHT));
    sonoHost.managedProperty().bind(sonoHost.visibleProperty());

    // Overlay d'erreur (issue #22) : affiché en surimpression quand le VM signale un échec de
    // chargement (Task.onFailed) ; masqué dès qu'un nouveau chargement réussit.
    errorOverlay.textProperty().bind(vm.errorMessageProperty());
    errorOverlay.visibleProperty().bind(vm.errorMessageProperty().isNotNull());
    errorOverlay.managedProperty().bind(errorOverlay.visibleProperty());

    buildColorbarLegend();
    StackPane.setMargin(legend, new Insets(0, 8, 0, 0));
    legend.translateYProperty().bind(sonoCanvas.heightProperty().add(8));

    // Perf (issue #23) : on sépare deux familles de déclencheurs pour éviter le redessin complet
    // à chaque pulse de lecture (~60 fps). Le **contenu lourd** (onde Canvas, viewport ImageView,
    // nœuds d'axes régénérés) ne se redessine que sur changement de la fenêtre temporelle, du
    // zoom fréquence, des données ou de la taille. Le **curseur** (Line), lui, se repositionne
    // sur chaque tick de currentTime (cheap). Au zoom 1, windowStart/Duration sont constants
    // pendant la lecture → contentUpdate ne se déclenche pas, seul le curseur bouge.
    ChangeListener<Object> contentUpdate = (o, a, b) -> refresh();
    sonoCanvas.widthProperty().addListener(contentUpdate);
    sonoCanvas.heightProperty().addListener(contentUpdate);
    spectroHost.widthProperty().addListener(contentUpdate);
    spectroHost.heightProperty().addListener(contentUpdate);
    vm.windowStartBinding().addListener(contentUpdate);
    vm.windowDurationBinding().addListener(contentUpdate);
    vm.frequencyZoomProperty().addListener(contentUpdate);
    vm.timeExpansionFactorProperty().addListener(contentUpdate);
    vm.spectrogramImageProperty().addListener(contentUpdate);
    vm.sampleProperty().addListener(contentUpdate);

    vm.currentTimeProperty().addListener((o, a, b) -> updateCursors());

    // Déplacement du curseur de lecture par clic.
    spectroHost.setOnMousePressed(e -> seekFromX(e.getX(), spectroHost.getWidth()));
    sonoHost.setOnMousePressed(e -> seekFromX(e.getX(), sonoHost.getWidth()));
  }

  // ----- Commandes (handlers FXML) -----

  @FXML
  private void onTogglePlay() {
    vm.togglePlay();
  }

  @FXML
  private void onZoomTimeIn() {
    vm.timeZoomProperty().set(Math.min(64, vm.timeZoomProperty().get() * 2));
  }

  @FXML
  private void onZoomTimeOut() {
    vm.timeZoomProperty().set(Math.max(1, vm.timeZoomProperty().get() / 2));
  }

  @FXML
  private void onZoomFreqIn() {
    vm.frequencyZoomProperty().set(Math.min(64, vm.frequencyZoomProperty().get() * 2));
  }

  @FXML
  private void onZoomFreqOut() {
    vm.frequencyZoomProperty().set(Math.max(1, vm.frequencyZoomProperty().get() / 2));
  }

  // ----- Rendu -----

  private void refresh() {
    drawSonogram();
    updateSpectrogram();
    updateAxes();
    updateCursors();
  }

  /** Enveloppe min/max de l'onde par colonne, tracée sur Canvas (le curseur est un nœud). */
  private void drawSonogram() {
    GraphicsContext g = sonoCanvas.getGraphicsContext2D();
    double w = sonoCanvas.getWidth();
    double h = sonoCanvas.getHeight();
    // Fond transparent : il provient de la CSS (.audio-view-plot-area), on n'efface que le tracé.
    g.clearRect(0, 0, w, h);
    double plotX = AXIS_LEFT;
    double plotW = w - AXIS_LEFT;
    AudioSample sample = sonoVm.getSample();
    if (sample == null || plotW < 1 || h < 1) {
      return;
    }

    float[] x = sample.samples();
    float sr = sample.sampleRate();
    double win = sonoVm.windowDuration();
    double start = sonoVm.windowStart();
    double sonoScale = sonoVm.sonoScale();
    int s0 = (int) Math.floor(start * sr);
    int s1 = (int) Math.ceil((start + win) * sr);
    s0 = Math.max(0, Math.min(x.length, s0));
    s1 = Math.max(s0 + 1, Math.min(x.length, s1));
    double mid = h / 2.0;

    g.setStroke(waveColor.get());
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
  }

  /** Recadre l'image du spectrogramme (fenêtre temps + tranche basse de fréquence) via viewport. */
  private void updateSpectrogram() {
    WritableImage img = spectroVm.getSpectrogramImage();
    AudioSample sample = spectroVm.getSample();
    if (img == null || sample == null) {
      spectroImage.setImage(null);
      return;
    }
    spectroImage.setImage(img);
    spectroImage.setViewport(spectroVm.viewport(img.getWidth(), img.getHeight()));
  }

  /**
   * Régénère les graduations (fréquence à gauche, temps en bas) en nœuds dans {@code axisLayer}.
   */
  private void updateAxes() {
    axisLayer.getChildren().clear();
    sonoAxisLayer.getChildren().clear();
    AudioSample spectroSample = spectroVm.getSample();
    if (spectroSample == null) {
      return;
    }
    double spectroPlotW = spectroHost.getWidth() - AXIS_LEFT;
    double spectroPlotH = spectroHost.getHeight() - AXIS_BOTTOM;
    if (spectroPlotW >= 1 && spectroPlotH >= 1) {
      double fz = Math.max(1, spectroVm.frequencyZoom());
      double fMaxFileVisible = (spectroSample.sampleRate() / 2.0) / fz;
      addFrequencyAxis(spectroPlotW, spectroPlotH, fMaxFileVisible);
      addTimeAxis(spectroPlotW, spectroPlotH, spectroVm.windowStart(), spectroVm.windowDuration());
    }
    double sonoPlotW = sonoCanvas.getWidth() - AXIS_LEFT;
    double sonoPlotH = sonoCanvas.getHeight();
    if (sonoPlotW >= 1 && sonoPlotH >= 1) {
      addSonoTimeGrid(sonoPlotW, sonoPlotH, sonoVm.windowStart(), sonoVm.windowDuration());
      addSonoAmplitudeAxis(sonoPlotW, sonoPlotH);
    }
  }

  /** Grille de fond du sonogramme : ticks temporels alignés sur ceux du spectro. */
  private void addSonoTimeGrid(double plotW, double plotH, double start, double win) {
    if (win <= 0) {
      return;
    }
    double factor = sonoVm.expansionFactor();
    double t0 = start / factor;
    double t1 = (start + win) / factor;
    double step = AudioViewModel.niceStep(t1 - t0, 6);
    for (AxisTicks.Tick tick : AxisTicks.compute(t0, t1, step, plotW)) {
      double x = AXIS_LEFT + tick.positionPx();
      sonoAxisLayer.getChildren().add(gridLine(x, 0, x, plotH));
    }
  }

  /**
   * Graduations d'amplitude (gouttière gauche du sonogramme), en valeurs fichier autour de zéro. Le
   * « pic visible » correspond à {@code 1/sonoScale} (l'auto-échelle ramène le pic du fichier près
   * du bord) ; les valeurs intermédiaires sont arrondies par {@link AudioViewModel#niceStep}. Le
   * tick à 0 fait office d'axe zéro horizontal.
   */
  private void addSonoAmplitudeAxis(double plotW, double plotH) {
    double peak = sonoVm.amplitudePeak();
    if (peak <= 0 || plotH < 4) {
      return;
    }
    double step = sonoVm.amplitudeStep();
    for (AxisTicks.Tick tick : AxisTicks.compute(-peak, peak, step, plotH)) {
      double y = plotH - tick.positionPx(); // max en haut, comme l'axe fréquence
      sonoAxisLayer.getChildren().add(gridLine(AXIS_LEFT, y, AXIS_LEFT + plotW, y));
      sonoAxisLayer.getChildren().add(tickLine(AXIS_LEFT - 4, y, AXIS_LEFT, y));
      Label lbl = axisLabel(AudioViewModel.formatAxis(tick.value(), step));
      lbl.setPrefSize(AXIS_LEFT - 8, 14);
      lbl.setAlignment(Pos.CENTER_RIGHT);
      lbl.setLayoutX(2);
      lbl.setLayoutY(clamp(y - 7, 0, plotH - 14));
      sonoAxisLayer.getChildren().add(lbl);
    }
  }

  /** Graduations de fréquence (gouttière gauche), en kHz, mises à l'échelle par le facteur. */
  private void addFrequencyAxis(double plotW, double plotH, double fMaxFileVisibleHz) {
    double fMaxHz = fMaxFileVisibleHz * spectroVm.expansionFactor();
    if (fMaxHz <= 0) {
      return;
    }
    double stepHz = AudioViewModel.niceStep(fMaxHz, 5);
    // L'axe fréquence est inversé (le 0 est en bas) : on calcule la position « naturelle » via
    // AxisTicks puis on la retourne (y = plotH - positionPx).
    for (AxisTicks.Tick tick : AxisTicks.compute(0, fMaxHz, stepHz, plotH)) {
      double y = plotH - tick.positionPx();
      axisLayer.getChildren().add(gridLine(AXIS_LEFT, y, AXIS_LEFT + plotW, y));
      axisLayer.getChildren().add(tickLine(AXIS_LEFT - 4, y, AXIS_LEFT, y));
      Label lbl = axisLabel(AudioViewModel.formatAxis(tick.value() / 1000.0, stepHz / 1000.0));
      lbl.setPrefSize(AXIS_LEFT - 8, 14);
      lbl.setAlignment(Pos.CENTER_RIGHT);
      lbl.setLayoutX(2);
      // borne min à 6 px : laisse un peu d'air entre le séparateur sono/spectro et l'étiquette
      // tout en haut de l'axe, peu importe la valeur de la plus haute graduation.
      lbl.setLayoutY(clamp(y - 7, 6, plotH - 14));
      axisLayer.getChildren().add(lbl);
    }
    Label unit = axisLabel("kHz");
    unit.setLayoutX(2);
    unit.setLayoutY(6);
    axisLayer.getChildren().add(unit);
  }

  /** Graduations de temps (gouttière basse), en secondes, mises à l'échelle par le facteur. */
  private void addTimeAxis(double plotW, double plotH, double start, double win) {
    if (win <= 0) {
      return;
    }
    double factor = spectroVm.expansionFactor();
    double t0 = start / factor;
    double t1 = (start + win) / factor;
    double step = AudioViewModel.niceStep(t1 - t0, 6);
    for (AxisTicks.Tick tick : AxisTicks.compute(t0, t1, step, plotW)) {
      double x = AXIS_LEFT + tick.positionPx();
      axisLayer.getChildren().add(gridLine(x, 0, x, plotH));
      axisLayer.getChildren().add(tickLine(x, plotH, x, plotH + 4));
      Label lbl = axisLabel(AudioViewModel.formatAxis(tick.value(), step));
      lbl.setPrefWidth(40);
      lbl.setAlignment(Pos.CENTER);
      lbl.setLayoutX(clamp(x - 20, AXIS_LEFT, AXIS_LEFT + plotW - 40));
      lbl.setLayoutY(plotH + 5);
      axisLayer.getChildren().add(lbl);
    }
    Label unit = axisLabel("s");
    unit.setPrefWidth(20);
    unit.setAlignment(Pos.CENTER_RIGHT);
    unit.setLayoutX(AXIS_LEFT + plotW - 20);
    unit.setLayoutY(plotH + 5);
    axisLayer.getChildren().add(unit);
  }

  /** Repositionne les deux curseurs (sonogramme et spectrogramme) selon le temps courant. */
  private void updateCursors() {
    // Fenêtre commune (même session) : on lit depuis sonoVm — spectroVm donnerait la même valeur.
    double win = sonoVm.windowDuration();
    double start = sonoVm.windowStart();
    double rel = win <= 0 ? -1 : (sonoVm.currentTime() - start) / win;
    boolean show = rel >= 0 && rel <= 1;
    double sonoPlotW = sonoCanvas.getWidth() - AXIS_LEFT;
    positionCursor(sonoCursor, show, AXIS_LEFT + rel * sonoPlotW, 0, sonoCanvas.getHeight());
    double spectroPlotW = spectroHost.getWidth() - AXIS_LEFT;
    double spectroPlotH = spectroHost.getHeight() - AXIS_BOTTOM;
    positionCursor(spectroCursor, show, AXIS_LEFT + rel * spectroPlotW, 0, spectroPlotH);
  }

  private static void positionCursor(Line line, boolean show, double x, double y0, double y1) {
    line.setVisible(show);
    if (show) {
      line.setStartX(x);
      line.setEndX(x);
      line.setStartY(y0);
      line.setEndY(y1);
    }
  }

  private static Line gridLine(double x1, double y1, double x2, double y2) {
    Line line = new Line(x1, y1, x2, y2);
    line.getStyleClass().add("audio-view-grid");
    line.setManaged(false);
    line.setMouseTransparent(true);
    return line;
  }

  private static Line tickLine(double x1, double y1, double x2, double y2) {
    Line line = new Line(x1, y1, x2, y2);
    line.getStyleClass().add("audio-view-tick");
    line.setManaged(false);
    line.setMouseTransparent(true);
    return line;
  }

  private static Label axisLabel(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("audio-view-axis-label");
    label.setMouseTransparent(true);
    return label;
  }

  private static double clamp(double value, double lo, double hi) {
    return Math.max(lo, Math.min(hi, value));
  }

  /** Remplit la légende des couleurs (intensité en dB) : fond, bande dégradée, libellés dB. */
  private void buildColorbarLegend() {
    double w = 56;
    double barH = 150;
    double headerH = 14;
    double stripW = 10;
    double stripX = w - stripW - 6;
    double stripTop = headerH;

    legend.setPrefSize(w, headerH + barH + 8);
    legend.setMaxSize(w, headerH + barH + 8);

    Label entete = new Label("dB");
    entete.setLayoutX(6);
    entete.setLayoutY(0);
    legend.getChildren().add(entete);

    Rectangle bande = new Rectangle(stripX, stripTop, stripW, barH);
    bande.setFill(buildColorbarGradient(colormapFor(isLightTheme())));
    bande.setStroke(AXIS_TEXT);
    bande.setStrokeWidth(1);
    legend.getChildren().add(bande);
    colorbarBande = bande;

    double range = AudioViewModel.MAX_DB - AudioViewModel.MIN_DB;
    double step = AudioViewModel.niceStep(range, 4);
    int nTicks = (int) Math.round(range / step);
    for (int i = 0; i <= nTicks; i++) {
      double db = AudioViewModel.MIN_DB + i * step;
      double y = stripTop + barH * (1 - (db - AudioViewModel.MIN_DB) / range);
      Label valeur = new Label(AudioViewModel.formatAxis(db, step));
      valeur.setLayoutX(2);
      valeur.setLayoutY(y - 8);
      valeur.setPrefWidth(stripX - 6);
      valeur.setAlignment(Pos.CENTER_RIGHT);
      legend.getChildren().add(valeur);
    }
  }

  /** Dégradé reproduisant la {@link Colormap} fournie (haut = intensité max) pour la légende dB. */
  private static LinearGradient buildColorbarGradient(Colormap colormap) {
    int n = 16;
    Stop[] stops = new Stop[n + 1];
    for (int i = 0; i <= n; i++) {
      double offset = i / (double) n;
      stops[i] = new Stop(offset, colormap.at(1 - offset));
    }
    return new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE, stops);
  }

  /**
   * Conversion thème (booléen) → {@link Colormap} : sert de pont entre l'API publique et l'enum.
   */
  private static Colormap colormapFor(boolean light) {
    return light ? Colormap.CLAIR : Colormap.SOMBRE;
  }

  private void seekFromX(double mouseX, double hostWidth) {
    double plotW = hostWidth - AXIS_LEFT;
    if (spectroVm.duration() <= 0 || plotW <= 0) {
      return;
    }
    double rel = Math.max(0, Math.min(1, (mouseX - AXIS_LEFT) / plotW));
    spectroVm.seek(spectroVm.windowStart() + rel * spectroVm.windowDuration());
  }

  // ----- API publique (déléguée au ViewModel) -----

  public final ObjectProperty<Path> audioFileProperty() {
    return vm.audioFileProperty();
  }

  public final Path getAudioFile() {
    return vm.audioFileProperty().get();
  }

  public final void setAudioFile(Path path) {
    vm.audioFileProperty().set(path);
  }

  /** Pratique pour le FXML : règle la source à partir d'un chemin sous forme de chaîne. */
  public final void setSource(String path) {
    setAudioFile(path == null ? null : Path.of(path));
  }

  public final BooleanProperty playingProperty() {
    return vm.playingProperty();
  }

  public final boolean isPlaying() {
    return vm.playingProperty().get();
  }

  public final void setPlaying(boolean value) {
    vm.playingProperty().set(value);
  }

  public final void togglePlay() {
    vm.togglePlay();
  }

  public final ReadOnlyDoubleProperty currentTimeProperty() {
    return vm.currentTimeProperty();
  }

  public final double getCurrentTime() {
    return vm.getCurrentTime();
  }

  public final ReadOnlyDoubleProperty durationProperty() {
    return vm.durationProperty();
  }

  public final double getDuration() {
    return vm.getDuration();
  }

  public final DoubleProperty timeZoomProperty() {
    return vm.timeZoomProperty();
  }

  public final double getTimeZoom() {
    return vm.timeZoomProperty().get();
  }

  public final void setTimeZoom(double value) {
    vm.timeZoomProperty().set(Math.max(1, value));
  }

  public final DoubleProperty frequencyZoomProperty() {
    return vm.frequencyZoomProperty();
  }

  public final double getFrequencyZoom() {
    return vm.getFrequencyZoom();
  }

  public final void setFrequencyZoom(double value) {
    vm.frequencyZoomProperty().set(Math.max(1, value));
  }

  public final DoubleProperty timeExpansionFactorProperty() {
    return vm.timeExpansionFactorProperty();
  }

  public final double getTimeExpansionFactor() {
    return vm.timeExpansionFactorProperty().get();
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
    vm.timeExpansionFactorProperty().set(value > 0 ? value : 1);
  }

  public final BooleanProperty lightThemeProperty() {
    return lightTheme;
  }

  public final boolean isLightTheme() {
    return lightTheme.get();
  }

  /** Active (vrai) ou désactive (faux, défaut) le thème clair. */
  public final void setLightTheme(boolean value) {
    lightTheme.set(value);
  }

  /**
   * Message d'erreur de chargement (issue #22). {@code null} tant qu'aucune erreur n'a été
   * rencontrée ; renseigné quand la {@link javafx.concurrent.Task} de décodage/FFT échoue (WAV
   * corrompu, format inattendu…), vidé au prochain (re)chargement.
   */
  public final ReadOnlyStringProperty errorMessageProperty() {
    return vm.errorMessageProperty();
  }

  public final String getErrorMessage() {
    return vm.errorMessageProperty().get();
  }

  private void applyTheme(boolean light) {
    pseudoClassStateChanged(LIGHT_THEME, light);
    Colormap colormap = colormapFor(light);
    vm.setColormap(colormap);
    if (colorbarBande != null) {
      colorbarBande.setFill(buildColorbarGradient(colormap));
    }
  }

  /** Libère le périphérique audio. À appeler quand le composant n'est plus utilisé. */
  public void dispose() {
    vm.dispose();
  }

  // ----- Métadonnées CSS (propriétés stylables) -----

  private static final class StyleableProperties {
    private static final CssMetaData<AudioView, Color> WAVE_COLOR =
        new CssMetaData<>("-fx-wave-color", ColorConverter.getInstance(), WAVE_COLOR_DEFAULT) {
          @Override
          public boolean isSettable(AudioView node) {
            return !node.waveColor.isBound();
          }

          @Override
          public StyleableProperty<Color> getStyleableProperty(AudioView node) {
            return node.waveColor;
          }
        };

    private static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

    static {
      List<CssMetaData<? extends Styleable, ?>> list =
          new ArrayList<>(BorderPane.getClassCssMetaData());
      list.add(WAVE_COLOR);
      CSS_META_DATA = Collections.unmodifiableList(list);
    }

    private StyleableProperties() {}
  }

  /** Métadonnées CSS de la classe (héritées de BorderPane + {@code -fx-wave-color}). */
  public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
    return StyleableProperties.CSS_META_DATA;
  }

  @Override
  public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
    return StyleableProperties.CSS_META_DATA;
  }
}
