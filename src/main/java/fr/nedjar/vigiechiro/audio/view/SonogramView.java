package fr.nedjar.vigiechiro.audio.view;

import fr.nedjar.vigiechiro.audio.dsp.AudioSample;
import fr.nedjar.vigiechiro.audio.render.AxisTicks;
import fr.nedjar.vigiechiro.audio.viewmodel.AudioViewModel;
import fr.nedjar.vigiechiro.audio.viewmodel.SonogramViewModel;
import java.io.IOException;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

/**
 * Sous-vue du tracé sonogramme (custom control fx:root {@code extends StackPane}). Issue #9.
 *
 * <p>Charge {@code SonogramView.fxml}, expose une méthode {@link #bindTo(SonogramViewModel,
 * ReadOnlyObjectProperty)} pour le câblage par le parent (qui détient la session et la couleur
 * d'onde stylable). Encapsule le rendu du Canvas (enveloppe min/max), la grille temporelle, l'axe
 * d'amplitude et le repositionnement du curseur ; le seek-au-clic est géré ici.
 *
 * <p>La racine est un {@link StackPane} qui dimensionne {@code plotHost} (un {@link Pane}
 * intermédiaire) à sa taille effective. Les autres enfants (axisLayer, canvas, cursor) s'y
 * raccrochent par bindings sur {@code plotHost} — sans cet intermédiaire, le calcul de pref width
 * remontait par auto-référence depuis la VBox parent et la gouttière d'axes restait invisible.
 */
public final class SonogramView extends StackPane {

    /**
     * Largeur de la gouttière gauche, dupliquée depuis {@link AudioView} (constante de mise en page).
     */
    private static final double AXIS_LEFT = 48;

    @FXML
    private Pane plotHost;

    @FXML
    private Pane axisLayer;

    @FXML
    private Canvas canvas;

    @FXML
    private Line cursor;

    private SonogramViewModel vm;
    private ReadOnlyObjectProperty<Color> waveColor;

    public SonogramView() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("SonogramView.fxml"));
        loader.setRoot(this);
        loader.setController(this);
        try {
            loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Chargement de SonogramView.fxml impossible", e);
        }
    }

    @FXML
    private void initialize() {
        canvas.widthProperty().bind(plotHost.widthProperty());
        canvas.heightProperty().bind(plotHost.heightProperty());
        axisLayer.prefWidthProperty().bind(plotHost.widthProperty());
        axisLayer.prefHeightProperty().bind(plotHost.heightProperty());
    }

    /**
     * Câble cette sous-vue à son ViewModel + à la couleur d'onde stylable. Les listeners sont
     * installés ici (et non dans {@link #initialize()}) car le ViewModel n'est pas connu à la
     * construction (la session est créée par {@link AudioView}).
     */
    public void bindTo(SonogramViewModel vm, ReadOnlyObjectProperty<Color> waveColor) {
        this.vm = vm;
        this.waveColor = waveColor;

        ChangeListener<Object> redraw = (o, a, b) -> render();
        plotHost.widthProperty().addListener(redraw);
        plotHost.heightProperty().addListener(redraw);
        vm.sampleProperty().addListener(redraw);
        vm.windowStartBinding().addListener(redraw);
        vm.windowDurationBinding().addListener(redraw);
        vm.sonoScaleProperty().addListener(redraw);
        waveColor.addListener(redraw);
        vm.currentTimeProperty().addListener((o, a, b) -> positionCursor());

        setOnMousePressed(e -> seekFromX(e.getX()));
    }

    // ----- Rendu -----

    private void render() {
        drawCanvas();
        drawAxes();
        positionCursor();
    }

    /** Enveloppe min/max de l'onde par colonne, tracée sur Canvas (le curseur est un nœud). */
    private void drawCanvas() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        double plotX = AXIS_LEFT;
        double plotW = w - AXIS_LEFT;
        AudioSample sample = vm.getSample();
        if (sample == null || plotW < 1 || h < 1) {
            return;
        }

        float[] x = sample.samples();
        float sr = sample.sampleRate();
        double win = vm.windowDuration();
        double start = vm.windowStart();
        double sonoScale = vm.sonoScale();
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

    /** Régénère grille temporelle de fond + gouttière gauche d'amplitude. */
    private void drawAxes() {
        axisLayer.getChildren().clear();
        if (vm.getSample() == null) {
            return;
        }
        double plotW = canvas.getWidth() - AXIS_LEFT;
        double plotH = canvas.getHeight();
        if (plotW < 1 || plotH < 1) {
            return;
        }
        addTimeGrid(plotW, plotH, vm.windowStart(), vm.windowDuration());
        addAmplitudeAxis(plotW, plotH);
    }

    /** Grille de fond du sonogramme : ticks temporels alignés sur ceux du spectro. */
    private void addTimeGrid(double plotW, double plotH, double start, double win) {
        if (win <= 0) {
            return;
        }
        double factor = vm.expansionFactor();
        double t0 = start / factor;
        double t1 = (start + win) / factor;
        double step = AudioViewModel.niceStep(t1 - t0, 6);
        for (AxisTicks.Tick tick : AxisTicks.compute(t0, t1, step, plotW)) {
            double x = AXIS_LEFT + tick.positionPx();
            axisLayer.getChildren().add(AxisNodes.gridLine(x, 0, x, plotH));
        }
    }

    /**
     * Graduations d'amplitude (gouttière gauche). Le « pic visible » correspond à {@code 1/sonoScale}
     * (cf. {@link SonogramViewModel#amplitudePeak()}) ; les valeurs intermédiaires sont arrondies par
     * {@link AudioViewModel#niceStep}. Le tick à 0 fait office d'axe zéro horizontal.
     */
    private void addAmplitudeAxis(double plotW, double plotH) {
        double peak = vm.amplitudePeak();
        if (peak <= 0 || plotH < 4) {
            return;
        }
        double step = vm.amplitudeStep();
        for (AxisTicks.Tick tick : AxisTicks.compute(-peak, peak, step, plotH)) {
            double y = plotH - tick.positionPx();
            axisLayer.getChildren().add(AxisNodes.gridLine(AXIS_LEFT, y, AXIS_LEFT + plotW, y));
            axisLayer.getChildren().add(AxisNodes.tickLine(AXIS_LEFT - 4, y, AXIS_LEFT, y));
            Label lbl = AxisNodes.axisLabel(AudioViewModel.formatAxis(tick.value(), step));
            lbl.setPrefSize(AXIS_LEFT - 8, 14);
            lbl.setAlignment(Pos.CENTER_RIGHT);
            lbl.setLayoutX(2);
            lbl.setLayoutY(AxisNodes.clamp(y - 7, 0, plotH - 14));
            axisLayer.getChildren().add(lbl);
        }
    }

    private void positionCursor() {
        if (vm == null) {
            return;
        }
        double win = vm.windowDuration();
        double start = vm.windowStart();
        double rel = win <= 0 ? -1 : (vm.currentTime() - start) / win;
        boolean show = rel >= 0 && rel <= 1;
        double plotW = canvas.getWidth() - AXIS_LEFT;
        AxisNodes.positionCursor(cursor, show, AXIS_LEFT + rel * plotW, 0, canvas.getHeight());
    }

    private void seekFromX(double mouseX) {
        if (vm == null) {
            return;
        }
        double plotW = plotHost.getWidth() - AXIS_LEFT;
        if (plotW <= 0 || vm.windowDuration() <= 0) {
            return;
        }
        double rel = Math.max(0, Math.min(1, (mouseX - AXIS_LEFT) / plotW));
        vm.seek(vm.windowStart() + rel * vm.windowDuration());
    }
}
