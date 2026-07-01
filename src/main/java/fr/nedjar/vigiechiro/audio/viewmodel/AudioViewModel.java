package fr.nedjar.vigiechiro.audio.viewmodel;

import fr.nedjar.vigiechiro.audio.dsp.AudioAnalyzer;
import fr.nedjar.vigiechiro.audio.dsp.AudioSample;
import fr.nedjar.vigiechiro.audio.dsp.Spectrogram;
import fr.nedjar.vigiechiro.audio.playback.PlaybackModel;
import fr.nedjar.vigiechiro.audio.render.Colormap;
import fr.nedjar.vigiechiro.audio.render.SpectrogramImageFactory;
import java.io.IOException;
import java.nio.file.Path;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
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
import javafx.scene.image.WritableImage;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * ViewModel d'{@link AudioView} (MVVM). Il tient l'état observable et orchestre :
 *
 * <ul>
 *   <li>le chargement via {@link AudioAnalyzer} (décode + FFT + auto-échelles, sans toolkit) ;
 *   <li>la lecture via {@link PlaybackModel} (player + timer + transitions) ;
 *   <li>la fenêtre temps/fréquence et la traduction des erreurs de chargement.
 * </ul>
 *
 * <p>Pas de dépendance au graphe de scène : la vue s'y lie par bindings et lui adresse des
 * commandes. Les calculs purs (fenêtre, graduations, format) sont des méthodes statiques pour
 * rester testables sans instancier le ViewModel.
 */
@SuppressWarnings({"PMD.GodClass", "PMD.NcssCount", "PMD.CyclomaticComplexity"})
public final class AudioViewModel {

    public static final double MIN_DB = -90.0;
    public static final double MAX_DB = -10.0;

    // ----- État réglable -----
    private final ObjectProperty<Path> audioFile = new SimpleObjectProperty<>(this, "audioFile");
    private final DoubleProperty timeZoom = new SimpleDoubleProperty(this, "timeZoom", 1);
    private final DoubleProperty frequencyZoom = new SimpleDoubleProperty(this, "frequencyZoom", 1);
    private final DoubleProperty timeExpansion = new SimpleDoubleProperty(this, "timeExpansionFactor", 1);

    // ----- Transport (playing/currentTime/duration + player + timer) délégué à PlaybackModel (#14)
    // -----
    private final PlaybackModel playback = new PlaybackModel();

    // ----- État dérivé (lecture seule) -----
    private final ReadOnlyObjectWrapper<AudioSample> sample = new ReadOnlyObjectWrapper<>(this, "sample");
    private final ReadOnlyObjectWrapper<WritableImage> spectrogramImage =
            new ReadOnlyObjectWrapper<>(this, "spectrogramImage");
    private final ReadOnlyDoubleWrapper sonoScale = new ReadOnlyDoubleWrapper(this, "sonoScale", 1);
    private final ReadOnlyStringWrapper timeText = new ReadOnlyStringWrapper(this, "timeText", "0.00 / 0.00 s");

    // Normalisation VISUELLE du sonogramme (distincte de la normalisation de LECTURE portée par
    // PlaybackModel) : quand active, la forme d'onde est mise à l'échelle jusqu'à son pic réel (plafond
    // de gain relevé), pour qu'un enregistrement faible remplisse la gouttière au lieu de rester plat.
    // Les deux auto-échelles candidates sont pré-calculées à l'analyse ; la bascule ne fait que choisir
    // laquelle publier dans sonoScale (aucun re-décodage). Défaut : off (comportement historique).
    private final BooleanProperty waveNormalisation = new SimpleBooleanProperty(this, "waveNormalisation", false);
    private double peakSonoScale = 1;
    private double normalisedSonoScale = 1;

    // null tant qu'aucune erreur de chargement ; renseigné quand la Task échoue, vidé au prochain
    // (re)chargement. La vue affiche un overlay quand non null (cf. AudioView + audio-view.css).
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper(this, "errorMessage", null);

    // false tant que le décodage + STFT du fichier courant n'a pas abouti ; passe à true à la fin
    // de Task.onSucceeded (signal canonique pour TestFX / snapshots hors-écran, issue #31). Reset
    // à false au début de chaque (re)chargement et quand la source devient null.
    private final ReadOnlyBooleanWrapper ready = new ReadOnlyBooleanWrapper(this, "ready", false);

    // Fenêtre temporelle calculée (issue #23) : début et durée de la portion de signal affichée,
    // dérivés de duration/currentTime/timeZoom. La vue s'y abonne pour redessiner le contenu
    // (onde, viewport image, axes) uniquement quand la fenêtre change vraiment — au zoom 1 elle est
    // constante pendant la lecture, donc seul le curseur (lié à currentTime) bouge.
    private final DoubleBinding windowStartBinding = Bindings.createDoubleBinding(
            () -> windowStart(playback.duration(), playback.currentTime(), timeZoom.get()),
            playback.durationProperty(),
            playback.currentTimeProperty(),
            timeZoom);

    private final DoubleBinding windowDurationBinding = Bindings.createDoubleBinding(
            () -> windowDuration(playback.duration(), timeZoom.get()), playback.durationProperty(), timeZoom);

    // Conservés pour reconstruire l'image du spectrogramme quand la colormap change (bascule thème).
    private Spectrogram spectrogram;
    private Colormap colormap = Colormap.SOMBRE;

    public AudioViewModel() {
        audioFile.addListener((o, oldFile, newFile) -> loadAudio(newFile));
        waveNormalisation.addListener((o, a, b) -> applySonoScale());
        playback.currentTimeProperty().addListener((o, a, b) -> updateTimeText());
        playback.durationProperty().addListener((o, a, b) -> updateTimeText());
        timeExpansion.addListener((o, a, b) -> updateTimeText());
    }

    // ----- Commandes (déléguées au PlaybackModel) -----

    public void togglePlay() {
        playback.togglePlay();
    }

    /** Positionne la lecture (temps fichier, en secondes), borné à [0, durée]. */
    public void seek(double tFile) {
        playback.seek(tFile);
    }

    public void dispose() {
        playback.dispose();
    }

    // ----- Chargement (orchestration) -----

    private void loadAudio(Path path) {
        playback.reset();
        sample.set(null);
        spectrogramImage.set(null);
        spectrogram = null;
        peakSonoScale = 1;
        normalisedSonoScale = 1;
        sonoScale.set(1);
        errorMessage.set(null);
        ready.set(false);
        if (path == null) {
            return;
        }

        Task<AudioAnalyzer.AnalyzedAudio> task = new Task<>() {
            @Override
            protected AudioAnalyzer.AnalyzedAudio call() throws Exception {
                return AudioAnalyzer.analyze(path);
            }
        };
        task.setOnSucceeded(e -> {
            AudioAnalyzer.AnalyzedAudio result = task.getValue();
            spectrogram = result.spectrogram();
            sample.set(result.sample());
            peakSonoScale = result.sonoScale();
            normalisedSonoScale = result.normalisedSonoScale();
            applySonoScale();
            spectrogramImage.set(SpectrogramImageFactory.build(spectrogram, colormap, MIN_DB, MAX_DB));
            // Cale par défaut la vue fréquentielle sur la bande réellement utilisée.
            frequencyZoom.set(result.suggestedFrequencyZoom());
            // Alimente la lecture depuis les échantillons déjà décodés (mono, [-1, 1]) : permet la
            // normalisation peak au rendu (gain pré-multiplié) sans re-décoder le fichier (issue #32).
            playback.loadSource(result.sample().samples(), result.sample().sampleRate());
            playback.setDuration(result.durationSeconds());
            // ready en DERNIER : le signal n'est émis qu'une fois TOUT en place (sample, image,
            // duration, frequencyZoom calé) — les listeners qui prennent un snapshot voient déjà
            // l'état final.
            ready.set(true);
        });
        // Sans cet onFailed, une Task qui échoue (WAV corrompu, format inattendu…) laissait le
        // composant vide en silence, sans aucun retour pour l'utilisateur (issue #22).
        task.setOnFailed(e -> errorMessage.set(formatLoadError(task.getException())));

        Thread worker = new Thread(task, "audio-view-loader");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Publie dans {@link #sonoScaleProperty()} l'auto-échelle correspondant au mode courant :
     * l'échelle normalisée (remplissage jusqu'au pic) si la normalisation visuelle est active, sinon
     * l'échelle plafonnée par défaut. Rejouable à la bascule sans re-décoder le fichier.
     */
    private void applySonoScale() {
        sonoScale.set(waveNormalisation.get() ? normalisedSonoScale : peakSonoScale);
    }

    /** Traduit en français les causes d'échec courantes de {@link AudioSample#load}. */
    public static String formatLoadError(Throwable cause) {
        if (cause instanceof UnsupportedAudioFileException) {
            return "Fichier audio non pris en charge (WAV PCM 16 bits attendu).";
        }
        if (cause instanceof IOException) {
            return "Impossible de lire le fichier audio.";
        }
        String msg = cause == null ? null : cause.getMessage();
        return "Erreur de chargement" + (msg != null ? " : " + msg : ".");
    }

    private void updateTimeText() {
        timeText.set(formatTimeText(playback.currentTime(), playback.duration(), expansionFactor()));
    }

    // ----- Calculs purs (testables sans interface) -----

    public double expansionFactor() {
        double f = timeExpansion.get();
        return f > 0 ? f : 1;
    }

    public double windowDuration() {
        return windowDuration(playback.duration(), timeZoom.get());
    }

    public double windowStart() {
        return windowStart(playback.duration(), playback.currentTime(), timeZoom.get());
    }

    /**
     * Binding observable de {@link #windowStart()} (déclenche un redessin quand la fenêtre bouge).
     */
    public DoubleBinding windowStartBinding() {
        return windowStartBinding;
    }

    /** Binding observable de {@link #windowDuration()} (idem). */
    public DoubleBinding windowDurationBinding() {
        return windowDurationBinding;
    }

    public static double windowDuration(double dur, double timeZoom) {
        return dur <= 0 ? 0 : dur / Math.max(1, timeZoom);
    }

    public static double windowStart(double dur, double current, double timeZoom) {
        double win = windowDuration(dur, timeZoom);
        if (win <= 0) {
            return 0;
        }
        double start = current - win / 2;
        return Math.max(0, Math.min(dur - win, start));
    }

    public static String formatTimeText(double currentFile, double durationFile, double factor) {
        double f = factor > 0 ? factor : 1;
        return String.format("%.2f / %.2f s", currentFile / f, durationFile / f);
    }

    /** Pas de graduation « rond » (1, 2, 5 × 10^k) couvrant range avec ~target intervalles. */
    public static double niceStep(double range, int target) {
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

    public static String formatAxis(double value, double step) {
        // Nombre de décimales calé sur le pas. Plafond à 4 (et non 3) : sous forte normalisation
        // visuelle, l'échelle d'amplitude d'un signal très faible devient minuscule (pas ~5e-4), et un
        // plafond à 3 écrasait toutes les graduations à « 0.000 » / arrondissait 0.0005 en « 0.001 ».
        int decimals = step >= 1 ? 0 : (int) Math.min(4, Math.ceil(-Math.log10(step)));
        return String.format("%." + decimals + "f", value);
    }

    /** Change la colormap active et reconstruit l'image du spectrogramme si un fichier est chargé. */
    public void setColormap(Colormap colormap) {
        if (this.colormap == colormap) {
            return;
        }
        this.colormap = colormap;
        if (spectrogram != null) {
            spectrogramImage.set(SpectrogramImageFactory.build(spectrogram, colormap, MIN_DB, MAX_DB));
        }
    }

    // ----- Accesseurs (pour la vue) -----

    public ObjectProperty<Path> audioFileProperty() {
        return audioFile;
    }

    public BooleanProperty playingProperty() {
        return playback.playingProperty();
    }

    public BooleanProperty loopProperty() {
        return playback.loopProperty();
    }

    public BooleanProperty normalisationProperty() {
        return playback.normalisationProperty();
    }

    public ReadOnlyDoubleProperty normalisationGainDbProperty() {
        return playback.normalisationGainDbProperty();
    }

    /**
     * Normalisation <b>visuelle</b> du sonogramme (défaut {@code false}) : quand active, la forme
     * d'onde est mise à l'échelle jusqu'à son pic réel plutôt que bornée par le plafond conservateur,
     * de sorte qu'un enregistrement faible remplisse la gouttière. Distincte de la normalisation de
     * lecture ({@link #normalisationProperty()}), qui n'agit que sur le volume.
     */
    public BooleanProperty waveNormalisationProperty() {
        return waveNormalisation;
    }

    public double getNormalisationGainDb() {
        return playback.normalisationGainDb();
    }

    public DoubleProperty timeZoomProperty() {
        return timeZoom;
    }

    public DoubleProperty frequencyZoomProperty() {
        return frequencyZoom;
    }

    public DoubleProperty timeExpansionFactorProperty() {
        return timeExpansion;
    }

    public ReadOnlyDoubleProperty currentTimeProperty() {
        return playback.currentTimeProperty();
    }

    public ReadOnlyDoubleProperty durationProperty() {
        return playback.durationProperty();
    }

    public ReadOnlyObjectProperty<AudioSample> sampleProperty() {
        return sample.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<WritableImage> spectrogramImageProperty() {
        return spectrogramImage.getReadOnlyProperty();
    }

    public ReadOnlyDoubleProperty sonoScaleProperty() {
        return sonoScale.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty timeTextProperty() {
        return timeText.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty errorMessageProperty() {
        return errorMessage.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty readyProperty() {
        return ready.getReadOnlyProperty();
    }

    public boolean isReady() {
        return ready.get();
    }

    public AudioSample getSample() {
        return sample.get();
    }

    public WritableImage getSpectrogramImage() {
        return spectrogramImage.get();
    }

    public double getSonoScale() {
        return sonoScale.get();
    }

    public double getCurrentTime() {
        return playback.currentTime();
    }

    public double getDuration() {
        return playback.duration();
    }

    public double getFrequencyZoom() {
        return frequencyZoom.get();
    }
}
