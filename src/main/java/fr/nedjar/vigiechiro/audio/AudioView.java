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
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
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

    private static final Color WAVE_COLOR_DEFAULT = Color.web("#7fd4ff");

    /** Pseudo-classe CSS {@code :light} : bascule le thème clair (voir {@code audio-view.css}). */
    private static final PseudoClass LIGHT_THEME = PseudoClass.getPseudoClass("light");

    /**
     * Pseudo-classe CSS {@code :colorblind} : reflète {@code colorblindFriendly} jusque dans la
     * couleur de l'onde du sonogramme (le spectrogramme, lui, bascule sa colormap côté Java). Voir
     * {@code audio-view.css}.
     */
    private static final PseudoClass COLORBLIND = PseudoClass.getPseudoClass("colorblind");

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

    // Champs porteurs des propriétés JavaFX "lightTheme" / "colorblindFriendly" (issue #24). La Javadoc
    // publique est sur lightThemeProperty() / colorblindFriendlyProperty() ; éviter de la dupliquer ici
    // évite le warning javadoc "Duplicate comment for property". Les effets de bord (bascule de thème /
    // colormap) sont câblés par des listeners dans le constructeur.
    private final BooleanProperty lightTheme = new SimpleBooleanProperty(this, "lightTheme", false);
    private final BooleanProperty colorblindFriendly = new SimpleBooleanProperty(this, "colorblindFriendly", false);

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
        // A11y (issue #24) : focusable + raccourcis clavier + rôle accessible. Le mapping des touches
        // vit dans AudioViewShortcuts (hors de la façade).
        setFocusTraversable(true);
        setAccessibleRoleDescription("Visualiseur audio (sonogramme et spectrogramme)");
        setOnKeyPressed(e -> AudioViewShortcuts.handle(e, this, vm));

        // Effets de bord des bascules de thème : pseudo-classe CSS + colormap du spectrogramme.
        lightTheme.addListener((o, a, light) -> applyTheme(light));
        colorblindFriendly.addListener((o, a, cb) -> {
            // Pseudo-classe :colorblind → la couleur de l'onde du sonogramme suit (via CSS), en plus de
            // la colormap Viridis du spectrogramme appliquée par applyTheme.
            pseudoClassStateChanged(COLORBLIND, cb);
            applyTheme(lightTheme.get());
        });
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

        // Répartition verticale des tracés + masquage responsive (issue #30), extraits dans
        // AudioViewLayout pour garder la façade légère.
        AudioViewLayout.installResponsive(plots, sonoView, spectroView);

        // Branchement des sous-vues à la session (chaque sous-vue installe ses propres listeners de
        // redraw, déclic-seek et repositionnement de curseur).
        sonoView.bindTo(sonoVm, waveColor);
        spectroView.bindTo(spectroVm, this::currentColormap);

        // A11y (issue #24) : libellés explicites pour lecteurs d'écran.
        playButton.setAccessibleText("Lecture ou pause de l'audio");
        sonoView.setAccessibleRoleDescription("Sonogramme (amplitude au cours du temps)");
        spectroView.setAccessibleRoleDescription("Spectrogramme (fréquence au cours du temps)");
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

    /// Fichier WAV source affiché par le composant.
    ///
    /// Le décodage et la STFT sont lancés **en tâche de fond** dès qu'on assigne un chemin non
    /// nul : [#currentTimeProperty()] et [#durationProperty()] passent à 0, puis [#durationProperty()]
    /// reçoit la durée du fichier quand l'analyse termine. [#readyProperty()] passe à `true` une
    /// fois tout en place — c'est **le signal canonique** pour les tests TestFX et les
    /// snapshots hors-écran. Assigner `null` réinitialise le composant. Une erreur de décodage
    /// est signalée via [#errorMessageProperty()].
    ///
    /// **Affichage découplé de la lecture** : si le périphérique audio est indisponible
    /// (machine sans carte son, CI headless…), le décodage et l'affichage du sonogramme et du
    /// spectrogramme fonctionnent quand même ; seule la commande Lecture sera silencieusement
    /// sans effet.
    ///
    /// Format attendu : WAV PCM 16 bits mono. Tout autre format produit une erreur.
    ///
    /// @return propriété observable ; valeur par défaut `null`
    public final ObjectProperty<Path> audioFileProperty() {
        return vm.audioFileProperty();
    }

    /// Chemin du fichier actuellement chargé, ou `null` si aucun.
    public final Path getAudioFile() {
        return vm.audioFileProperty().get();
    }

    /// Change le fichier source. Voir [#audioFileProperty()] pour la sémantique.
    public final void setAudioFile(Path path) {
        vm.audioFileProperty().set(path);
    }

    /// Surcharge pratique pour le FXML (où on n'a pas accès direct à `Path`) : convertit la
    /// chaîne en chemin via [Path#of(String, String...)]. `null` ou chaîne vide → aucun fichier.
    public final void setSource(String path) {
        setAudioFile(path == null ? null : Path.of(path));
    }

    /// État de lecture. Mettre à `true` démarre la lecture audio et avance le curseur ; mettre
    /// à `false` met en pause. En fin d'extrait, la propriété repasse automatiquement à `false`
    /// — un nouveau `true` rejoue alors **depuis zéro**.
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `false`
    public final BooleanProperty playingProperty() {
        return vm.playingProperty();
    }

    /// `true` si la lecture est en cours.
    public final boolean isPlaying() {
        return vm.playingProperty().get();
    }

    /// Démarre (`true`) ou met en pause (`false`) la lecture. Voir [#playingProperty()].
    public final void setPlaying(boolean value) {
        vm.playingProperty().set(value);
    }

    /// Bascule l'état de lecture (raccourci équivalent à `setPlaying(!isPlaying())`).
    public final void togglePlay() {
        vm.togglePlay();
    }

    /// Lecture en boucle (issue #15). À `false` (défaut), la lecture **s'arrête** en fin
    /// d'extrait — un clic suivant sur Lecture rejoue depuis zéro. À `true`, la lecture
    /// **rejoue automatiquement** depuis zéro sans interruption.
    ///
    /// La propriété est modifiable à tout moment ; elle prend effet à la prochaine atteinte de
    /// la fin de l'extrait.
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `false`
    public final BooleanProperty loopProperty() {
        return vm.loopProperty();
    }

    /// `true` si la lecture rejoue automatiquement en boucle.
    public final boolean isLoop() {
        return vm.loopProperty().get();
    }

    /// Active (`true`) ou désactive (`false`) la lecture en boucle. Voir [#loopProperty()].
    public final void setLoop(boolean value) {
        vm.loopProperty().set(value);
    }

    /// Normalisation **peak** du niveau sonore à la lecture (issue #32). À `false` (défaut), l'audio
    /// est joué tel quel. À `true`, un **gain linéaire** est appliqué au rendu pour ramener le pic du
    /// fichier près du plein régime, afin d'égaliser le volume d'un extrait à l'autre — les cris de
    /// chiroptères ayant des amplitudes très variables.
    ///
    /// **Sans modifier le fichier** : seul le rendu est affecté (PCM recalculé en mémoire). La
    /// normalisation peak est volontairement la méthode **la moins déformante** (gain constant, forme
    /// d'onde préservée, pas de compression). La propriété est modifiable à tout moment ; la bascule
    /// reprend la lecture là où elle en était.
    ///
    /// **Best-effort** : comme le reste de la lecture, sans effet si le périphérique audio est
    /// indisponible (l'affichage, lui, reste fonctionnel).
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `false`
    public final BooleanProperty normalisationProperty() {
        return vm.normalisationProperty();
    }

    /// `true` si la normalisation peak est active.
    public final boolean isNormalisation() {
        return vm.normalisationProperty().get();
    }

    /// Active (`true`) ou désactive (`false`, défaut) la normalisation peak. Voir
    /// [#normalisationProperty()].
    public final void setNormalisation(boolean value) {
        vm.normalisationProperty().set(value);
    }

    /// Gain de normalisation peak **effectivement appliqué** au rendu audio, en décibels.
    ///
    /// `0` dB quand la normalisation est désactivée ou qu'aucun fichier n'est chargé ; sinon la
    /// valeur positive (boost) appliquée pour ramener le pic du fichier près du plein régime, par
    /// ex. `+12.4`. Recalculée à chaque chargement et à chaque bascule de [#normalisationProperty()].
    /// Utile pour donner un retour visuel (« Normalisé : +X dB ») à l'utilisateur, la normalisation
    /// n'ayant par nature aucun effet sur les tracés.
    ///
    /// @return propriété lecture seule ; valeur par défaut `0`
    public final ReadOnlyDoubleProperty normalisationGainDbProperty() {
        return vm.normalisationGainDbProperty();
    }

    /// Gain de normalisation peak courant, en dB (`0` si désactivé).
    public final double getNormalisationGainDb() {
        return vm.getNormalisationGainDb();
    }

    /// Normalisation **visuelle** du sonogramme. À `false` (défaut), l'amplitude de la forme d'onde est
    /// auto-mise à l'échelle mais avec un **plafond de gain conservateur** : un enregistrement très
    /// faible (cri de chiroptère ramené dans l'audible, pic à quelques millièmes) reste alors un tracé
    /// **plat**. À `true`, le plafond est relevé pour que l'onde soit mise à l'échelle **jusqu'à son
    /// pic réel** et remplisse la gouttière, quelle que soit la faiblesse du signal.
    ///
    /// Distincte de la normalisation de lecture ([#normalisationProperty()]) : celle-ci n'agit **que
    /// sur les tracés** (le sonogramme), pas sur le volume, et n'altère ni le fichier ni la lecture.
    /// La graduation d'amplitude (gouttière gauche) reflète l'échelle courante : elle indique donc le
    /// pic réel du fichier quand la normalisation visuelle est active.
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `false`
    public final BooleanProperty waveNormalisationProperty() {
        return vm.waveNormalisationProperty();
    }

    /// `true` si la normalisation visuelle du sonogramme est active.
    public final boolean isWaveNormalisation() {
        return vm.waveNormalisationProperty().get();
    }

    /// Active (`true`) ou désactive (`false`, défaut) la normalisation visuelle du sonogramme. Voir
    /// [#waveNormalisationProperty()].
    public final void setWaveNormalisation(boolean value) {
        vm.waveNormalisationProperty().set(value);
    }

    /// Normalisation **visuelle** du spectrogramme. À `false` (défaut), les magnitudes sont mappées sur
    /// la colormap avec une **fenêtre dB fixe** : un enregistrement faible, dont toutes les magnitudes
    /// sont bien en dessous du plafond, s'affiche alors **tout en noir**. À `true`, la fenêtre dB est
    /// **recalée sur le pic réel** du signal (même largeur de dynamique), de sorte que le spectrogramme
    /// d'un signal faible révèle sa structure au lieu de rester noir.
    ///
    /// N'agit que sur le **tracé** (mapping magnitude → couleur), pas sur le son. La légende dB reflète
    /// la fenêtre courante — elle indique donc les niveaux réels du fichier quand la normalisation est
    /// active. Pendant de [#waveNormalisationProperty()] pour le spectrogramme.
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `false`
    public final BooleanProperty spectrogramNormalisationProperty() {
        return vm.spectrogramNormalisationProperty();
    }

    /// `true` si la normalisation visuelle du spectrogramme est active.
    public final boolean isSpectrogramNormalisation() {
        return vm.spectrogramNormalisationProperty().get();
    }

    /// Active (`true`) ou désactive (`false`, défaut) la normalisation visuelle du spectrogramme. Voir
    /// [#spectrogramNormalisationProperty()].
    public final void setSpectrogramNormalisation(boolean value) {
        vm.spectrogramNormalisationProperty().set(value);
    }

    /// Position courante du curseur en **temps réel** (secondes affichées), c.-à-d. cohérente avec
    /// les axes et avec [#seek(double)].
    ///
    /// **Temps réel = temps fichier ÷ facteur d'expansion** ([#timeExpansionFactorProperty()]) : pour
    /// un enregistrement chiroptère ralenti ×10, `2.0` s de fichier valent `0.2` s réelles. Quand le
    /// facteur vaut `1` (défaut) réel et fichier coïncident. Mise à jour ~60 fois par seconde pendant
    /// la lecture.
    ///
    /// @return propriété lecture seule ; valeur par défaut `0`
    public final ReadOnlyDoubleProperty currentTimeProperty() {
        return vm.currentTimeRealProperty();
    }

    /// Temps courant de lecture, en secondes réelles (cf. [#currentTimeProperty()]).
    public final double getCurrentTime() {
        return vm.getCurrentTimeReal();
    }

    /// Durée totale de l'extrait en **temps réel** (secondes affichées), cohérente avec les axes.
    ///
    /// **Temps réel = temps fichier ÷ facteur d'expansion** ([#timeExpansionFactorProperty()]). `0`
    /// tant qu'aucun fichier n'est chargé ou que l'analyse est en cours ; renseignée quand la tâche
    /// d'analyse termine ; reset à `0` à chaque nouveau chargement.
    ///
    /// @return propriété lecture seule
    public final ReadOnlyDoubleProperty durationProperty() {
        return vm.durationRealProperty();
    }

    /// Durée totale en secondes réelles, `0` si pas encore disponible.
    public final double getDuration() {
        return vm.getDurationReal();
    }

    /// Positionne la lecture / le curseur à l'instant `secondes`, en **temps réel** (mêmes secondes que
    /// les axes et [#currentTimeProperty()], donc dépendantes de [#timeExpansionFactorProperty()]).
    /// Issue #51.
    ///
    /// Converti en temps fichier (× facteur) puis **clampé** à `[0, durée]` ; sans effet tant qu'aucun
    /// fichier n'est chargé. Fonctionne que la lecture soit en cours ou en pause. Usage type : à la
    /// sélection d'un cri, `seek(debutDuCri)` cale la lecture dessus.
    public final void seek(double secondes) {
        vm.seekReal(secondes);
    }

    /// Fenêtre temporelle **surlignée** (un cri) mise en évidence sur l'onde ET le spectrogramme
    /// (issue #52). Valeur : `{debut, fin}` en **secondes réelles** (mêmes secondes que les axes et
    /// [#currentTimeProperty()]), ou `null` si aucun surlignage.
    ///
    /// Le rendu est une bande translucide aux mêmes bornes temporelles sur les deux tracés. Régler la
    /// sélection **recalcule aussi** les grandeurs acoustiques ([#fmeHzProperty()]…) sur cette fenêtre.
    /// Effacée automatiquement au chargement d'un nouveau fichier. Modifiable via cette propriété, via
    /// [#highlightWindow(double, double)] ou [#clearHighlight()].
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `null`
    public final ObjectProperty<double[]> highlightedWindowProperty() {
        return vm.highlightWindowProperty();
    }

    /// Fenêtre surlignée courante `{debut, fin}` en secondes réelles, ou `null`.
    public final double[] getHighlightedWindow() {
        return vm.getHighlightWindow();
    }

    /// Surligne la fenêtre `[debut, fin]` (secondes réelles) sur l'onde et le spectrogramme (issue #52).
    /// Les bornes sont réordonnées au besoin ; une borne non finie efface le surlignage. Voir
    /// [#highlightedWindowProperty()].
    public final void highlightWindow(double debut, double fin) {
        vm.setHighlightWindow(debut, fin);
    }

    /// Efface le surlignage (équivalent à `highlightedWindowProperty().set(null)`).
    public final void clearHighlight() {
        vm.clearHighlightWindow();
    }

    /// **FME — Fréquence du Maximum d'Énergie** (Hz réels), grandeur acoustique de référence pour trier
    /// les cris (issue #50). Fréquence où l'énergie est maximale sur la fenêtre analysée : la
    /// [#highlightedWindowProperty()] si une sélection est active, sinon le fichier entier. Exprimée en
    /// **fréquence réelle** (× facteur d'expansion). Recalculée au chargement et à chaque changement de
    /// sélection ou de facteur ; `NaN` tant qu'aucun fichier n'est chargé.
    ///
    /// @return propriété lecture seule ; valeur par défaut `NaN`
    public final ReadOnlyDoubleProperty fmeHzProperty() {
        return vm.fmeHzProperty();
    }

    /// FME courante en Hz réels, `NaN` si indéterminée.
    public final double getFmeHz() {
        return vm.getFmeHz();
    }

    /// **Fréquence terminale** (Hz réels) — heuristique de fin de balayage FM sur la fenêtre analysée
    /// (cf. [#fmeHzProperty()] pour la portée). Approximation : sur un cri à fréquence quasi constante
    /// elle rejoint la FME. Recalculée comme les autres grandeurs ; `NaN` si indéterminée. Issue #50.
    ///
    /// @return propriété lecture seule ; valeur par défaut `NaN`
    public final ReadOnlyDoubleProperty frequenceTerminaleHzProperty() {
        return vm.frequenceTerminaleHzProperty();
    }

    /// Fréquence terminale courante en Hz réels, `NaN` si indéterminée.
    public final double getFrequenceTerminaleHz() {
        return vm.getFrequenceTerminaleHz();
    }

    /// **Durée du cri** mesurée sur le signal (millisecondes réelles) : extension des trames actives de
    /// la fenêtre analysée (cf. [#fmeHzProperty()] pour la portée), convertie en temps réel (÷ facteur).
    /// Plus robuste qu'un simple `fin - debut` car mesurée sur l'énergie. `NaN` si indéterminée. Issue #50.
    ///
    /// @return propriété lecture seule ; valeur par défaut `NaN`
    public final ReadOnlyDoubleProperty dureeMsProperty() {
        return vm.dureeMsProperty();
    }

    /// Durée courante du cri en millisecondes réelles, `NaN` si indéterminée.
    public final double getDureeMs() {
        return vm.getDureeMs();
    }

    /// Zoom temporel : facteur multiplicatif appliqué à l'axe horizontal des deux tracés.
    /// La fenêtre visible vaut `durée / timeZoom` ; à `1` (par défaut) toute la durée est
    /// visible. La barre d'outils propose `×2` et `÷2`.
    ///
    /// Bornes : `1` (clampé par [#setTimeZoom(double)]) en pratique limité à `64`. Au-delà
    /// du facteur 64, les fenêtres deviennent plus courtes qu'une trame FFT et le rendu se
    /// dégrade.
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `1`
    public final DoubleProperty timeZoomProperty() {
        return vm.timeZoomProperty();
    }

    /// Valeur courante du zoom temporel.
    public final double getTimeZoom() {
        return vm.timeZoomProperty().get();
    }

    /// Règle le zoom temporel, clampé à `1` minimum (un facteur `< 1` ferait afficher du vide).
    public final void setTimeZoom(double value) {
        vm.timeZoomProperty().set(Math.max(1, value));
    }

    /// Zoom fréquentiel : facteur multiplicatif sur l'axe vertical du spectrogramme.
    /// L'image montre la tranche basse `[0, Nyquist / freqZoom]`. Calé **automatiquement** à
    /// chaque chargement sur la bande utile (les hautes fréquences vides sont coupées).
    ///
    /// Bornes : `1` (clampé par [#setFrequencyZoom(double)]), maximum pratique `64`.
    ///
    /// @return propriété observable et bidirectionnelle
    public final DoubleProperty frequencyZoomProperty() {
        return vm.frequencyZoomProperty();
    }

    /// Valeur courante du zoom fréquentiel.
    public final double getFrequencyZoom() {
        return vm.getFrequencyZoom();
    }

    /// Règle le zoom fréquentiel, clampé à `1` minimum.
    public final void setFrequencyZoom(double value) {
        vm.frequencyZoomProperty().set(Math.max(1, value));
    }

    /// Facteur d'expansion temporelle des enregistrements — typiquement `10` pour les WAV de
    /// chiroptères ralentis ×10 à l'acquisition.
    ///
    /// Définit la conversion entre valeurs **fichier** (l'audio réellement stocké) et valeurs
    /// **réelles** (celles du terrain, affichées) :
    /// - fréquence réelle = `fréquence_fichier × facteur` (kHz au lieu d'Hz pour ×10) ;
    /// - temps réel = `temps_fichier ÷ facteur`.
    ///
    /// **Toute l'API temporelle publique parle en temps réel** : [#currentTimeProperty()],
    /// [#durationProperty()], [#seek(double)] et [#highlightWindow(double, double)] sont exprimées en
    /// secondes réelles (÷ facteur), et les grandeurs acoustiques ([#fmeHzProperty()]…) en fréquences
    /// réelles (× facteur). Quand le facteur vaut `1` (défaut) réel et fichier coïncident.
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `1`
    public final DoubleProperty timeExpansionFactorProperty() {
        return vm.timeExpansionFactorProperty();
    }

    /// Facteur d'expansion temporelle courant.
    public final double getTimeExpansionFactor() {
        return vm.timeExpansionFactorProperty().get();
    }

    /// Règle le facteur d'expansion. Une valeur `≤ 0` est traitée comme `1` (sans expansion).
    /// Voir [#timeExpansionFactorProperty()] pour la sémantique exacte.
    public final void setTimeExpansionFactor(double value) {
        vm.timeExpansionFactorProperty().set(value > 0 ? value : 1);
    }

    /// Thème clair (`true`) ou sombre (`false`, défaut). Active la pseudo-classe CSS `:light`
    /// sur le composant : toute la chrome (toolbar, axes, légende) ET la rampe de couleurs du
    /// spectrogramme basculent en variante claire. Le sonogramme suit via la propriété stylable
    /// `-fx-wave-color`.
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `false`
    public final BooleanProperty lightThemeProperty() {
        return lightTheme;
    }

    /// `true` si le thème clair est actif.
    public final boolean isLightTheme() {
        return lightTheme.get();
    }

    /// Active (`true`) ou désactive (`false`, défaut) le thème clair.
    public final void setLightTheme(boolean value) {
        lightTheme.set(value);
    }

    /// Variante de palette **daltonien-friendly** (issue #24) : quand `true`, le spectrogramme
    /// utilise la rampe Viridis (perceptuellement uniforme, lisible avec les trois principales
    /// formes de daltonisme) en lieu et place des palettes sombre/clair par défaut. La bascule
    /// de [#lightThemeProperty()] continue d'affecter la chrome (toolbar, axes, fond) mais pas
    /// la rampe du spectrogramme tant que cette propriété est `true`.
    ///
    /// @return propriété observable et bidirectionnelle ; valeur par défaut `false`
    public final BooleanProperty colorblindFriendlyProperty() {
        return colorblindFriendly;
    }

    /// `true` si la palette daltonien-friendly est active.
    public final boolean isColorblindFriendly() {
        return colorblindFriendly.get();
    }

    /// Active (`true`) ou désactive (`false`, défaut) la palette daltonien-friendly.
    public final void setColorblindFriendly(boolean value) {
        colorblindFriendly.set(value);
    }

    /// Message d'erreur de chargement (français, prêt pour affichage).
    ///
    /// `null` tant qu'aucune erreur n'a été rencontrée. Renseigné quand la tâche de décodage /
    /// FFT échoue (WAV corrompu, format inattendu, périphérique manquant…). Vidé au prochain
    /// `setAudioFile(...)` réussi. Le composant affiche déjà un overlay en surimpression quand
    /// cette propriété est non nulle ; cet accesseur sert aux consommateurs qui veulent réagir
    /// au-delà (notification, log, etc.).
    ///
    /// @return propriété lecture seule
    public final ReadOnlyStringProperty errorMessageProperty() {
        return vm.errorMessageProperty();
    }

    /// Message d'erreur courant, ou `null` si tout va bien.
    public final String getErrorMessage() {
        return vm.errorMessageProperty().get();
    }

    /// Signal canonique de **fin de chargement** (issue #31) : `false` tant que le décodage et
    /// la STFT du fichier courant ne sont pas terminés, `true` une fois que `sample`, l'image du
    /// spectrogramme, `duration` et le zoom fréquentiel par défaut sont en place.
    ///
    /// Conçu pour les tests TestFX et les **snapshots hors-écran** : avant `ready=true`,
    /// `getDuration()` vaut encore `0` et les tracés sont vides ; après, un snapshot capture le
    /// rendu final. Préférer cette propriété à `durationProperty() > 0` (plus sémantique et
    /// stable).
    ///
    /// Reset à `false` à chaque `setAudioFile(...)` (y compris `null`). Si le chargement échoue,
    /// `ready` reste `false` et [#errorMessageProperty()] est renseigné.
    ///
    /// @return propriété lecture seule ; valeur par défaut `false`
    public final ReadOnlyBooleanProperty readyProperty() {
        return vm.readyProperty();
    }

    /// `true` quand le fichier courant est entièrement décodé et prêt à être rendu.
    public final boolean isReady() {
        return vm.isReady();
    }

    private void applyTheme(boolean light) {
        pseudoClassStateChanged(LIGHT_THEME, light);
        vm.setColormap(currentColormap());
        if (spectroView != null) {
            spectroView.refreshColorbar();
        }
    }

    /// Colormap effective compte tenu des deux flags : `colorblindFriendly` prend la priorité (force
    /// Viridis) sinon Sombre / Clair selon `lightTheme`. Visible aux sous-vues via {@code bindTo}.
    private Colormap currentColormap() {
        if (colorblindFriendly.get()) {
            // Variante daltonien assortie au thème : fond clair en thème clair, fond sombre sinon
            // (toutes deux CVD-safe et ancrées sur le fond pour le contraste).
            return lightTheme.get() ? Colormap.VIRIDIS_CLAIR : Colormap.VIRIDIS;
        }
        return lightTheme.get() ? Colormap.CLAIR : Colormap.SOMBRE;
    }

    /// Libère le périphérique audio et arrête l'horloge interne.
    ///
    /// **À appeler quand le composant n'est plus utilisé** (fermeture de fenêtre, retrait de la
    /// scène) : sans ça, le `Clip` audio reste ouvert et l'`AnimationTimer` continue de tourner
    /// jusqu'au prochain GC. Méthode idempotente — un second appel est sans effet.
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

    /// Métadonnées CSS de la classe — idiome JavaFX requis pour exposer les propriétés stylables
    /// d'un composant à `Scene.css.findStyleable*` : héritées de `BorderPane` plus la propriété
    /// `-fx-wave-color` propre à ce composant.
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return StyleableProperties.CSS_META_DATA;
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return StyleableProperties.CSS_META_DATA;
    }
}
