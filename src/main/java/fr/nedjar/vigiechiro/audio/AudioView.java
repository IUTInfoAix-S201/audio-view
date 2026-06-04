package fr.nedjar.vigiechiro.audio;

import fr.nedjar.vigiechiro.audio.render.Colormap;
import fr.nedjar.vigiechiro.audio.view.SonogramView;
import fr.nedjar.vigiechiro.audio.view.SpectrogramView;
import fr.nedjar.vigiechiro.audio.viewmodel.AudioViewModel;
import fr.nedjar.vigiechiro.audio.viewmodel.SonogramViewModel;
import fr.nedjar.vigiechiro.audio.viewmodel.SpectrogramViewModel;
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
import javafx.css.CssMetaData;
import javafx.css.PseudoClass;
import javafx.css.Styleable;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.css.converter.ColorConverter;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
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
 * <p>Architecture MVVM + sous-vues (issue #9) : la session vit dans {@link AudioViewModel} ; deux
 * sous-VM ({@link SonogramViewModel}, {@link SpectrogramViewModel}) sont des façades dédiées à
 * chaque tracé. La vue est un custom control <i>fx:root</i> ({@code extends BorderPane}) qui charge
 * {@code AudioView.fxml} au constructeur ; les zones de tracé sont assurées par {@link
 * SonogramView} et {@link SpectrogramView} (custom controls fx:root, chacun avec son FXML et son
 * contrôleur), branchés via {@code sousVue.bindTo(...)}.
 *
 * <pre>{@code
 * AudioView view = new AudioView();
 * view.setAudioFile(Path.of("samples/seq_0001.wav"));
 * view.currentTimeProperty().addListener((o, a, b) -> ...);
 * view.setPlaying(true);
 * }</pre>
 */
public class AudioView extends BorderPane {

    /** Largeur de la gouttière gauche, utilisée pour le binding responsive de la légende. */
    private static final double AXIS_LEFT = 48;

    /**
     * Seuils de masquage responsive (issue #30) : sous ces tailles, la légende dB et/ou le sonogramme
     * sont masqués pour laisser toute la place au spectrogramme — plutôt que de tout tasser jusqu'à
     * devenir illisible. La barre d'outils, elle, reste toujours visible (la hauteur min du composant
     * est calculée par {@link BorderPane} depuis son {@code top}).
     */
    private static final double LEGEND_MIN_PLOT_WIDTH = 320;

    private static final double LEGEND_MIN_SPECTRO_HEIGHT = 200;

    private static final double SONO_MIN_PLOTS_HEIGHT = 120;

    private static final Color WAVE_COLOR_DEFAULT = Color.web("#7fd4ff");

    /** Pseudo-classe CSS {@code :light} : bascule le thème clair (voir {@code audio-view.css}). */
    private static final PseudoClass LIGHT_THEME = PseudoClass.getPseudoClass("light");

    /**
     * Couleur de l'enveloppe du sonogramme, stylable via {@code -fx-wave-color} sur {@code
     * .audio-view}. Comme elle est tracée sur Canvas (invisible au moteur CSS), elle est exposée par
     * une {@link StyleableObjectProperty} + {@link CssMetaData} ; {@link SonogramView} l'écoute via
     * {@code bindTo} pour redessiner sur changement.
     */
    private final StyleableObjectProperty<Color> waveColor = new StyleableObjectProperty<>(WAVE_COLOR_DEFAULT) {
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
    };

    /**
     * Active le thème clair. Bascule la pseudo-classe CSS {@code :light} sur le composant ; tout le
     * rendu (chrome et couleur du sonogramme) est alors pris dans les règles {@code
     * .audio-view:light} de {@code audio-view.css}. Faux par défaut (thème sombre).
     */
    private final BooleanProperty lightTheme = new BooleanPropertyBase(false) {
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
    private final SonogramViewModel sonoVm = new SonogramViewModel(vm);
    private final SpectrogramViewModel spectroVm = new SpectrogramViewModel(vm);

    // ----- Vue (injectée depuis AudioView.fxml) -----
    @FXML
    private VBox plots;

    @FXML
    private StackPane plotsStack;

    @FXML
    private SonogramView sonoView;

    @FXML
    private SpectrogramView spectroView;

    @FXML
    private Label timeLabel;

    @FXML
    private Button playButton;

    // Overlay d'erreur, visible quand le VM signale une erreur de chargement (issue #22).
    @FXML
    private Label errorOverlay;

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

    /** Câblage shell + branchement des sous-vues, appelé par FXMLLoader après injection des @FXML. */
    @FXML
    private void initialize() {
        // Toolbar
        playButton
                .textProperty()
                .bind(Bindings.when(vm.playingProperty()).then("Pause").otherwise("Lecture"));
        timeLabel.textProperty().bind(vm.timeTextProperty());

        // Overlay d'erreur (issue #22) : affiché en surimpression quand le VM signale un échec de
        // chargement (Task.onFailed) ; masqué dès qu'un nouveau chargement réussit.
        errorOverlay.textProperty().bind(vm.errorMessageProperty());
        errorOverlay.visibleProperty().bind(vm.errorMessageProperty().isNotNull());
        errorOverlay.managedProperty().bind(errorOverlay.visibleProperty());

        // Répartition verticale sonogramme / spectrogramme (32 % / 68 %). spectroView a
        // VBox.vgrow=ALWAYS dans la FXML : quand sonoView est masqué (responsive, cf. issue #30) il
        // prend toute la place disponible.
        sonoView.prefHeightProperty().bind(plots.heightProperty().multiply(0.32));
        spectroView.prefHeightProperty().bind(plots.heightProperty().multiply(0.68));

        // Adaptation responsive (issue #30) : à l'étroit, la légende et/ou le sono sont masqués plutôt
        // que tassés. La légende est dans spectroView (issue #9) ; ses critères se calculent sur la
        // zone du tracé spectro (plotHost).
        Pane legend = spectroView.legendPane();
        Pane spectroPlotHost = spectroView.plotHost();
        legend.visibleProperty()
                .bind(spectroPlotHost
                        .widthProperty()
                        .subtract(AXIS_LEFT)
                        .greaterThanOrEqualTo(LEGEND_MIN_PLOT_WIDTH)
                        .and(spectroPlotHost.heightProperty().greaterThanOrEqualTo(LEGEND_MIN_SPECTRO_HEIGHT)));
        legend.managedProperty().bind(legend.visibleProperty());

        sonoView.visibleProperty().bind(plots.heightProperty().greaterThanOrEqualTo(SONO_MIN_PLOTS_HEIGHT));
        sonoView.managedProperty().bind(sonoView.visibleProperty());

        // Branchement des sous-vues à la session (chaque sous-vue installe ses propres listeners de
        // redraw, déclic-seek et repositionnement de curseur).
        sonoView.bindTo(sonoVm, waveColor);
        spectroView.bindTo(spectroVm, this::isLightTheme);
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
        vm.setColormap(light ? Colormap.CLAIR : Colormap.SOMBRE);
        if (spectroView != null) {
            spectroView.refreshColorbar();
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
            List<CssMetaData<? extends Styleable, ?>> list = new ArrayList<>(BorderPane.getClassCssMetaData());
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
