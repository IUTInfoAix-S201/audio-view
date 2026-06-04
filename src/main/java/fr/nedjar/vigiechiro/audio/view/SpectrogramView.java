package fr.nedjar.vigiechiro.audio.view;

import fr.nedjar.vigiechiro.audio.dsp.AudioSample;
import fr.nedjar.vigiechiro.audio.render.AxisTicks;
import fr.nedjar.vigiechiro.audio.render.Colormap;
import fr.nedjar.vigiechiro.audio.viewmodel.AudioViewModel;
import fr.nedjar.vigiechiro.audio.viewmodel.SpectrogramViewModel;
import java.io.IOException;
import java.util.function.Supplier;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/**
 * Sous-vue du tracé spectrogramme (custom control fx:root {@code extends StackPane}). Issue #9.
 *
 * <p>Charge {@code SpectrogramView.fxml}, expose {@link #bindTo(SpectrogramViewModel, Supplier)}
 * pour le câblage par le parent. Encapsule le recadrage de l'image (viewport),
 * les axes (fréquence à gauche, temps en bas) et la légende dB rattachée à la zone (issue #9). Le
 * seek-au-clic est géré ici. Le repositionnement de la bande de la légende selon le thème
 * (sombre/clair) est piloté par le parent via {@link #refreshColorbar()}.
 */
public final class SpectrogramView extends StackPane {

    /** Constantes de mise en page dupliquées depuis {@link AudioView}. */
    private static final double AXIS_LEFT = 48;

    private static final double AXIS_BOTTOM = 26;

    private static final Color AXIS_TEXT = Color.web("#9aa4ad");

    @FXML
    private Pane plotHost;

    @FXML
    private ImageView image;

    @FXML
    private Pane axisLayer;

    @FXML
    private Line cursor;

    @FXML
    private Pane legend;

    // Bande dégradée de la légende dB (créée par buildColorbarLegend, réactualisée au changement de
    // thème pour refléter la colormap active).
    private Rectangle colorbarBande;

    private SpectrogramViewModel vm;
    private Supplier<Colormap> currentColormap;

    public SpectrogramView() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("SpectrogramView.fxml"));
        loader.setRoot(this);
        loader.setController(this);
        try {
            loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Chargement de SpectrogramView.fxml impossible", e);
        }
    }

    @FXML
    private void initialize() {
        // L'image remplit la zone de tracé moins les gouttières ; le viewport est mis à jour par
        // render() pour le recadrage temps/fréquence.
        image.setLayoutX(AXIS_LEFT);
        image.setLayoutY(0);
        image.setPreserveRatio(false);
        image.fitWidthProperty().bind(plotHost.widthProperty().subtract(AXIS_LEFT));
        image.fitHeightProperty().bind(plotHost.heightProperty().subtract(AXIS_BOTTOM));
        axisLayer.prefWidthProperty().bind(plotHost.widthProperty());
        axisLayer.prefHeightProperty().bind(plotHost.heightProperty());
        StackPane.setMargin(legend, new Insets(0, 8, 0, 0));
    }

    /**
     * Câble la sous-vue à son ViewModel + à un accesseur du thème courant (utilisé pour la légende).
     */
    public void bindTo(SpectrogramViewModel vm, Supplier<Colormap> currentColormap) {
        this.vm = vm;
        this.currentColormap = currentColormap;

        buildColorbarLegend();

        ChangeListener<Object> redraw = (o, a, b) -> render();
        plotHost.widthProperty().addListener(redraw);
        plotHost.heightProperty().addListener(redraw);
        vm.sampleProperty().addListener(redraw);
        vm.spectrogramImageProperty().addListener(redraw);
        vm.windowStartBinding().addListener(redraw);
        vm.windowDurationBinding().addListener(redraw);
        vm.frequencyZoomProperty().addListener(redraw);
        vm.currentTimeProperty().addListener((o, a, b) -> positionCursor());

        setOnMousePressed(e -> seekFromX(e.getX()));
    }

    /** Réinitialise la bande de la légende après un changement de thème (parent → enfant). */
    public void refreshColorbar() {
        if (colorbarBande != null) {
            colorbarBande.setFill(buildColorbarGradient(currentColormap.get()));
        }
    }

    // ----- Rendu -----

    private void render() {
        updateImage();
        drawAxes();
        positionCursor();
    }

    private void updateImage() {
        WritableImage img = vm.getSpectrogramImage();
        AudioSample sample = vm.getSample();
        if (img == null || sample == null) {
            image.setImage(null);
            return;
        }
        image.setImage(img);
        image.setViewport(vm.viewport(img.getWidth(), img.getHeight()));
    }

    private void drawAxes() {
        axisLayer.getChildren().clear();
        AudioSample sample = vm.getSample();
        if (sample == null) {
            return;
        }
        double plotW = plotHost.getWidth() - AXIS_LEFT;
        double plotH = plotHost.getHeight() - AXIS_BOTTOM;
        if (plotW < 1 || plotH < 1) {
            return;
        }
        double fz = Math.max(1, vm.frequencyZoom());
        double fMaxFileVisible = (sample.sampleRate() / 2.0) / fz;
        addFrequencyAxis(plotW, plotH, fMaxFileVisible);
        addTimeAxis(plotW, plotH, vm.windowStart(), vm.windowDuration());
    }

    /** Graduations de fréquence (gouttière gauche), en kHz, mises à l'échelle par le facteur. */
    private void addFrequencyAxis(double plotW, double plotH, double fMaxFileVisibleHz) {
        double fMaxHz = fMaxFileVisibleHz * vm.expansionFactor();
        if (fMaxHz <= 0) {
            return;
        }
        double stepHz = AudioViewModel.niceStep(fMaxHz, 5);
        for (AxisTicks.Tick tick : AxisTicks.compute(0, fMaxHz, stepHz, plotH)) {
            double y = plotH - tick.positionPx();
            axisLayer.getChildren().add(AxisNodes.gridLine(AXIS_LEFT, y, AXIS_LEFT + plotW, y));
            axisLayer.getChildren().add(AxisNodes.tickLine(AXIS_LEFT - 4, y, AXIS_LEFT, y));
            Label lbl = AxisNodes.axisLabel(AudioViewModel.formatAxis(tick.value() / 1000.0, stepHz / 1000.0));
            lbl.setPrefSize(AXIS_LEFT - 8, 14);
            lbl.setAlignment(Pos.CENTER_RIGHT);
            lbl.setLayoutX(2);
            // borne min à 6 px : un peu d'air entre le séparateur sono/spectro et l'étiquette du haut.
            lbl.setLayoutY(AxisNodes.clamp(y - 7, 6, plotH - 14));
            axisLayer.getChildren().add(lbl);
        }
        Label unit = AxisNodes.axisLabel("kHz");
        unit.setLayoutX(2);
        unit.setLayoutY(6);
        axisLayer.getChildren().add(unit);
    }

    /** Graduations de temps (gouttière basse), en secondes, mises à l'échelle par le facteur. */
    private void addTimeAxis(double plotW, double plotH, double start, double win) {
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
            axisLayer.getChildren().add(AxisNodes.tickLine(x, plotH, x, plotH + 4));
            Label lbl = AxisNodes.axisLabel(AudioViewModel.formatAxis(tick.value(), step));
            lbl.setPrefWidth(40);
            lbl.setAlignment(Pos.CENTER);
            lbl.setLayoutX(AxisNodes.clamp(x - 20, AXIS_LEFT, AXIS_LEFT + plotW - 40));
            lbl.setLayoutY(plotH + 5);
            axisLayer.getChildren().add(lbl);
        }
        Label unit = AxisNodes.axisLabel("s");
        unit.setPrefWidth(20);
        unit.setAlignment(Pos.CENTER_RIGHT);
        unit.setLayoutX(AXIS_LEFT + plotW - 20);
        unit.setLayoutY(plotH + 5);
        axisLayer.getChildren().add(unit);
    }

    private void positionCursor() {
        if (vm == null) {
            return;
        }
        double win = vm.windowDuration();
        double start = vm.windowStart();
        double rel = win <= 0 ? -1 : (vm.currentTime() - start) / win;
        boolean show = rel >= 0 && rel <= 1;
        double plotW = plotHost.getWidth() - AXIS_LEFT;
        double plotH = plotHost.getHeight() - AXIS_BOTTOM;
        AxisNodes.positionCursor(cursor, show, AXIS_LEFT + rel * plotW, 0, plotH);
    }

    private void seekFromX(double mouseX) {
        if (vm == null) {
            return;
        }
        double plotW = plotHost.getWidth() - AXIS_LEFT;
        if (plotW <= 0 || vm.duration() <= 0) {
            return;
        }
        double rel = Math.max(0, Math.min(1, (mouseX - AXIS_LEFT) / plotW));
        vm.seek(vm.windowStart() + rel * vm.windowDuration());
    }

    // ----- Légende dB -----

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
        legend.getChildren().clear();

        Label entete = new Label("dB");
        entete.setLayoutX(6);
        entete.setLayoutY(0);
        legend.getChildren().add(entete);

        Rectangle bande = new Rectangle(stripX, stripTop, stripW, barH);
        bande.setFill(buildColorbarGradient(currentColormap.get()));
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

    // ----- Accesseurs pour les bindings responsive du parent -----

    public Pane legendPane() {
        return legend;
    }

    public Pane plotHost() {
        return plotHost;
    }
}
