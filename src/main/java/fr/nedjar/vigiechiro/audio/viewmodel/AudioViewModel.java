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
import javafx.beans.binding.ObjectBinding;
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

    // Normalisation VISUELLE du spectrogramme : quand active, la fenêtre d'affichage dB (qui mappe les
    // magnitudes vers la colormap) est recalée sur le pic RÉEL du signal au lieu du plafond fixe MAX_DB,
    // en gardant la même largeur de dynamique. Sans cela, un enregistrement faible (pic bien sous MAX_DB)
    // s'affiche tout en noir. La fenêtre courante [spectroMinDb, spectroMaxDb] est publiée pour que la
    // légende dB la reflète. Défaut : off (fenêtre fixe historique).
    private final BooleanProperty spectrogramNormalisation =
            new SimpleBooleanProperty(this, "spectrogramNormalisation", false);
    private double spectroPeakDb = MAX_DB;
    private final ReadOnlyDoubleWrapper spectroMinDb = new ReadOnlyDoubleWrapper(this, "spectroMinDb", MIN_DB);
    private final ReadOnlyDoubleWrapper spectroMaxDb = new ReadOnlyDoubleWrapper(this, "spectroMaxDb", MAX_DB);

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

    // Temps RÉEL exposé à l'API publique (issues #51/#52/#50) : temps affiché = temps fichier ÷ facteur
    // d'expansion, cohérent avec les axes. Le temps FICHIER reste l'unité interne (lecture, fenêtres,
    // trames STFT) ; ces deux propriétés en sont la vue « réelle », recalculée quand le temps fichier
    // ou le facteur change.
    private final ReadOnlyDoubleWrapper currentTimeReal = new ReadOnlyDoubleWrapper(this, "currentTimeReal", 0);
    private final ReadOnlyDoubleWrapper durationReal = new ReadOnlyDoubleWrapper(this, "durationReal", 0);

    // Fenêtre temporelle surlignée (cri), en temps RÉEL {debut, fin} ou null (issue #52). Réglée par
    // l'API publique ; sa projection en temps FICHIER (highlightWindowFile) sert au tracé (Rectangle sur
    // l'onde et le spectrogramme) et à restreindre les grandeurs acoustiques au cri.
    private final ObjectProperty<double[]> highlightWindow = new SimpleObjectProperty<>(this, "highlightWindow", null);
    private final ObjectBinding<double[]> highlightWindowFile =
            Bindings.createObjectBinding(() -> toFileWindow(highlightWindow.get()), highlightWindow, timeExpansion);

    // Grandeurs acoustiques par cri (issue #50), en unités RÉELLES : FME et fréquence terminale en Hz
    // (× facteur), durée en ms (÷ facteur). Recalculées au chargement, sur changement de sélection et de
    // facteur d'expansion. NaN tant qu'indéterminé (aucun fichier chargé).
    private final ReadOnlyDoubleWrapper fmeHz = new ReadOnlyDoubleWrapper(this, "fmeHz", Double.NaN);
    private final ReadOnlyDoubleWrapper frequenceTerminaleHz =
            new ReadOnlyDoubleWrapper(this, "frequenceTerminaleHz", Double.NaN);
    private final ReadOnlyDoubleWrapper dureeMs = new ReadOnlyDoubleWrapper(this, "dureeMs", Double.NaN);

    // Conservés pour reconstruire l'image du spectrogramme quand la colormap change (bascule thème).
    private Spectrogram spectrogram;
    private Colormap colormap = Colormap.SOMBRE;

    public AudioViewModel() {
        audioFile.addListener((o, oldFile, newFile) -> loadAudio(newFile));
        waveNormalisation.addListener((o, a, b) -> applySonoScale());
        spectrogramNormalisation.addListener((o, a, b) -> applySpectroRange());
        playback.currentTimeProperty().addListener((o, a, b) -> updateTimeText());
        playback.durationProperty().addListener((o, a, b) -> updateTimeText());
        timeExpansion.addListener((o, a, b) -> {
            updateTimeText();
            recomputeMeasures();
        });
        // Vue « temps réel » du temps de lecture / de la durée (÷ facteur d'expansion). Bornée aux
        // propriétés fichier + facteur pour se réactualiser dès que l'un change (issues #51/#52/#50).
        currentTimeReal.bind(Bindings.createDoubleBinding(
                () -> toRealTime(playback.currentTime()), playback.currentTimeProperty(), timeExpansion));
        durationReal.bind(Bindings.createDoubleBinding(
                () -> toRealTime(playback.duration()), playback.durationProperty(), timeExpansion));
        // La sélection change → grandeurs acoustiques recalculées sur la nouvelle fenêtre (issue #50).
        highlightWindow.addListener((o, a, b) -> recomputeMeasures());
    }

    // ----- Commandes (déléguées au PlaybackModel) -----

    public void togglePlay() {
        playback.togglePlay();
    }

    /** Positionne la lecture (temps fichier, en secondes), borné à [0, durée]. */
    public void seek(double tFile) {
        playback.seek(tFile);
    }

    /**
     * Positionne la lecture en <b>temps réel</b> (secondes affichées) : converti en temps fichier (×
     * facteur d'expansion) puis borné à [0, durée]. Point d'entrée de l'API publique {@code
     * AudioView.seek(double)} (issue #51).
     */
    public void seekReal(double tReal) {
        playback.seek(toFileTime(tReal));
    }

    /**
     * Définit la fenêtre temporelle surlignée (cri) en <b>temps réel</b> {@code {debut, fin}} secondes
     * (issue #52). Les bornes sont réordonnées ; toute valeur non finie efface le surlignage.
     */
    public void setHighlightWindow(double debutReal, double finReal) {
        if (!Double.isFinite(debutReal) || !Double.isFinite(finReal)) {
            highlightWindow.set(null);
            return;
        }
        highlightWindow.set(new double[] {Math.min(debutReal, finReal), Math.max(debutReal, finReal)});
    }

    /** Efface la fenêtre surlignée (issue #52). */
    public void clearHighlightWindow() {
        highlightWindow.set(null);
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
        spectroPeakDb = MAX_DB;
        spectroMinDb.set(MIN_DB);
        spectroMaxDb.set(MAX_DB);
        errorMessage.set(null);
        ready.set(false);
        // Un nouveau fichier invalide la sélection précédente (bornes en temps réel du fichier d'avant).
        highlightWindow.set(null);
        recomputeMeasures();
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
            spectroPeakDb = result.spectroPeakDb();
            applySpectroRange();
            // Cale par défaut la vue fréquentielle sur la bande réellement utilisée.
            frequencyZoom.set(result.suggestedFrequencyZoom());
            // Alimente la lecture depuis les échantillons déjà décodés (mono, [-1, 1]) : permet la
            // normalisation peak au rendu (gain pré-multiplié) sans re-décoder le fichier (issue #32).
            playback.loadSource(result.sample().samples(), result.sample().sampleRate());
            playback.setDuration(result.durationSeconds());
            // Grandeurs acoustiques du fichier entier (aucune sélection à ce stade) — issue #50.
            recomputeMeasures();
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

    /**
     * Publie la fenêtre d'affichage dB du spectrogramme selon le mode courant, puis reconstruit l'image.
     * En normalisation visuelle, la fenêtre est recalée sur le pic réel ({@code [pic - dynamique, pic]},
     * même largeur que la fenêtre fixe) pour révéler un signal faible ; sinon elle vaut {@code [MIN_DB,
     * MAX_DB]}. Rejouable à la bascule sans re-décoder (le {@link Spectrogram} est conservé).
     */
    private void applySpectroRange() {
        if (spectrogramNormalisation.get()) {
            spectroMaxDb.set(spectroPeakDb);
            spectroMinDb.set(spectroPeakDb - (MAX_DB - MIN_DB));
        } else {
            spectroMaxDb.set(MAX_DB);
            spectroMinDb.set(MIN_DB);
        }
        rebuildSpectrogramImage();
    }

    /** Reconstruit l'image du spectrogramme depuis le {@link Spectrogram} conservé, colormap + fenêtre dB courantes. */
    private void rebuildSpectrogramImage() {
        if (spectrogram != null) {
            spectrogramImage.set(
                    SpectrogramImageFactory.build(spectrogram, colormap, spectroMinDb.get(), spectroMaxDb.get()));
        }
    }

    /**
     * Recalcule les grandeurs acoustiques (FME, fréquence terminale, durée) en unités <b>réelles</b>,
     * sur la fenêtre surlignée si présente, sinon sur le fichier entier (issue #50). Les fréquences
     * fichier sont multipliées par le facteur d'expansion, la durée fichier divisée puis convertie en
     * ms. {@code NaN} tant qu'aucun spectrogramme n'est disponible.
     */
    private void recomputeMeasures() {
        AudioSample s = sample.get();
        if (spectrogram == null || s == null || spectrogram.frameCount() == 0) {
            fmeHz.set(Double.NaN);
            frequenceTerminaleHz.set(Double.NaN);
            dureeMs.set(Double.NaN);
            return;
        }
        double sr = s.sampleRate();
        double factor = expansionFactor();
        int lastFrame = spectrogram.frameCount() - 1;
        int f0 = 0;
        int f1 = lastFrame;
        double[] win = highlightWindowFile.get();
        if (win != null) {
            int a = clampFrame(AudioAnalyzer.frameForTime(win[0], sr, AudioAnalyzer.HOP), lastFrame);
            int b = clampFrame(AudioAnalyzer.frameForTime(win[1], sr, AudioAnalyzer.HOP), lastFrame);
            f0 = Math.min(a, b);
            f1 = Math.max(a, b);
        }
        double fme = AudioAnalyzer.peakFrequencyHz(spectrogram, sr, AudioAnalyzer.FFT_SIZE, f0, f1);
        double term = AudioAnalyzer.terminalFrequencyHz(spectrogram, sr, AudioAnalyzer.FFT_SIZE, f0, f1);
        double dur = AudioAnalyzer.activeDurationSeconds(spectrogram, sr, AudioAnalyzer.HOP, f0, f1);
        fmeHz.set(Double.isNaN(fme) ? Double.NaN : fme * factor);
        frequenceTerminaleHz.set(Double.isNaN(term) ? Double.NaN : term * factor);
        dureeMs.set(dur <= 0 ? Double.NaN : dur / factor * 1000.0);
    }

    private static int clampFrame(int frame, int last) {
        return Math.max(0, Math.min(last, frame));
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

    /** Temps fichier → temps réel affiché (÷ facteur d'expansion). */
    public double toRealTime(double tFile) {
        return tFile / expansionFactor();
    }

    /** Temps réel affiché → temps fichier (× facteur d'expansion). */
    public double toFileTime(double tReal) {
        return tReal * expansionFactor();
    }

    /** Projette une fenêtre surlignée {@code {debut, fin}} de temps réel vers le temps fichier (ou null). */
    private double[] toFileWindow(double[] realWindow) {
        if (realWindow == null || realWindow.length < 2) {
            return null;
        }
        double f = expansionFactor();
        return new double[] {realWindow[0] * f, realWindow[1] * f};
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
        rebuildSpectrogramImage();
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

    /**
     * Normalisation <b>visuelle</b> du spectrogramme (défaut {@code false}) : quand active, la fenêtre
     * d'affichage dB est recalée sur le pic réel du signal, pour qu'un enregistrement faible ne
     * s'affiche pas tout en noir. N'agit que sur le tracé (mapping magnitude → couleur), pas sur le son.
     */
    public BooleanProperty spectrogramNormalisationProperty() {
        return spectrogramNormalisation;
    }

    /** Borne basse (dB) de la fenêtre d'affichage courante du spectrogramme (pour la légende). */
    public ReadOnlyDoubleProperty spectroMinDbProperty() {
        return spectroMinDb.getReadOnlyProperty();
    }

    /** Borne haute (dB) de la fenêtre d'affichage courante du spectrogramme (pour la légende). */
    public ReadOnlyDoubleProperty spectroMaxDbProperty() {
        return spectroMaxDb.getReadOnlyProperty();
    }

    public double getSpectroMinDb() {
        return spectroMinDb.get();
    }

    public double getSpectroMaxDb() {
        return spectroMaxDb.get();
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

    // ----- Temps réel + surlignage + grandeurs (API publique d'AudioView) -----

    public ReadOnlyDoubleProperty currentTimeRealProperty() {
        return currentTimeReal.getReadOnlyProperty();
    }

    public double getCurrentTimeReal() {
        return currentTimeReal.get();
    }

    public ReadOnlyDoubleProperty durationRealProperty() {
        return durationReal.getReadOnlyProperty();
    }

    public double getDurationReal() {
        return durationReal.get();
    }

    /** Fenêtre surlignée {@code {debut, fin}} en temps réel, ou {@code null} (issue #52). */
    public ObjectProperty<double[]> highlightWindowProperty() {
        return highlightWindow;
    }

    public double[] getHighlightWindow() {
        return highlightWindow.get();
    }

    /** Projection en temps fichier de la fenêtre surlignée, pour le tracé des sous-vues (issue #52). */
    public ObjectBinding<double[]> highlightWindowFileBinding() {
        return highlightWindowFile;
    }

    public double[] highlightWindowFile() {
        return highlightWindowFile.get();
    }

    public ReadOnlyDoubleProperty fmeHzProperty() {
        return fmeHz.getReadOnlyProperty();
    }

    public double getFmeHz() {
        return fmeHz.get();
    }

    public ReadOnlyDoubleProperty frequenceTerminaleHzProperty() {
        return frequenceTerminaleHz.getReadOnlyProperty();
    }

    public double getFrequenceTerminaleHz() {
        return frequenceTerminaleHz.get();
    }

    public ReadOnlyDoubleProperty dureeMsProperty() {
        return dureeMs.getReadOnlyProperty();
    }

    public double getDureeMs() {
        return dureeMs.get();
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
