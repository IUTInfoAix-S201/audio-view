package fr.nedjar.vigiechiro.audio.playback;

import javafx.animation.AnimationTimer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Transport audio : encapsule {@link AudioPlayer} + {@link AnimationTimer} + l'état observable de
 * lecture ({@code playing}/{@code currentTime}/{@code duration}) ainsi que les transitions — arrêt
 * en fin d'extrait, reprise à zéro au prochain démarrage, clamp du {@code seek}. Issue #14.
 *
 * <p>Les transitions sont localisées ici et partiellement testables (le {@link
 * AnimationTimer#start()} reste toolkit-bound : on ne le sollicite pas en test unitaire).
 *
 * <p>Normalisation peak (issue #32) : la source décodée est mémorisée et son gain de normalisation
 * pré-calculé une fois ; basculer {@code normalisation} rouvre le clip avec le gain (ou {@code 1.0}),
 * en préservant la position et l'état de lecture.
 */
public final class PlaybackModel {

    /** Niveau crête cible de la normalisation peak (≈ -0,26 dBFS : petite marge anti-saturation). */
    private static final double NORMALISATION_CEILING = 0.97;

    /** Boost maximal de la normalisation peak (évite de remonter le bruit de fond d'un fichier muet). */
    private static final double NORMALISATION_MAX_GAIN_DB = 40.0;

    private final AudioPlayer player = new AudioPlayer();
    private final AnimationTimer timer;

    private final BooleanProperty playing = new SimpleBooleanProperty(this, "playing", false);
    private final BooleanProperty loop = new SimpleBooleanProperty(this, "loop", false);
    private final BooleanProperty normalisation = new SimpleBooleanProperty(this, "normalisation", false);
    private final ReadOnlyDoubleWrapper currentTime = new ReadOnlyDoubleWrapper(this, "currentTime", 0);
    private final ReadOnlyDoubleWrapper duration = new ReadOnlyDoubleWrapper(this, "duration", 0);
    // Gain de normalisation effectivement appliqué au rendu, en dB (0 si désactivé ou sans source) :
    // exposé en lecture seule pour un retour visuel côté consommateur (issue #32).
    private final ReadOnlyDoubleWrapper normalisationGainDb = new ReadOnlyDoubleWrapper(this, "normalisationGainDb", 0);

    // Source décodée courante (mono, normalisée [-1, 1]) + gain de normalisation pré-calculé : permet
    // de rouvrir le clip avec ou sans gain sur bascule de normalisation, sans re-décoder le fichier.
    private float[] sourceSamples;
    private float sourceRate;
    private double normalisedGain = 1.0;

    public PlaybackModel() {
        // timer assigné avant le listener "playing" qui le capture (champ final).
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                handleTick(player.position());
            }
        };

        playing.addListener((o, was, now) -> {
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

        // Bascule de normalisation : rouvre le clip avec le gain adéquat (no-op sans source chargée)
        // et met à jour le gain exposé (dB).
        normalisation.addListener((o, was, now) -> {
            reopen();
            updateGainDb();
        });
    }

    /**
     * Une tick du timer : si la position dépasse la durée et que {@code loop} est actif, repart à
     * zéro et continue la lecture ; sinon, arrête. Extrait du timer pour pouvoir tester sans
     * toolkit (le {@link AnimationTimer#start()} interne est toolkit-bound, pas cette méthode).
     */
    void handleTick(double pos) {
        if (duration.get() > 0 && pos >= duration.get()) {
            if (loop.get()) {
                // Un Clip arrêté en fin d'extrait ne redémarre pas sur un simple seek(0) : il faut
                // relancer la lecture (sinon le curseur revient à 0 mais le son ne reboucle pas).
                player.seek(0);
                player.play();
                currentTime.set(0);
                return;
            }
            currentTime.set(duration.get());
            playing.set(false);
            return;
        }
        currentTime.set(pos);
    }

    /** Réinitialise l'état de transport (à appeler avant un nouveau chargement). */
    public void reset() {
        playing.set(false);
        player.close();
        currentTime.set(0);
        duration.set(0);
        // La valeur de normalisation est une préférence utilisateur : on la conserve d'un fichier à
        // l'autre ; seule la source (et son gain) est oubliée.
        sourceSamples = null;
        sourceRate = 0;
        normalisedGain = 1.0;
        updateGainDb();
    }

    /** Recalcule le gain effectivement appliqué au rendu (en dB) : 0 dB si désactivé ou sans source. */
    private void updateGainDb() {
        double g = (sourceSamples != null && normalisation.get()) ? normalisedGain : 1.0;
        normalisationGainDb.set(20.0 * Math.log10(g));
    }

    /**
     * Mémorise la séquence décodée (mono, normalisée [-1, 1]) comme source de lecture, pré-calcule
     * son gain de normalisation peak et ouvre le clip. Silencieux en cas d'échec : l'affichage reste
     * fonctionnel même sans périphérique audio.
     */
    public void loadSource(float[] mono, float sampleRate) {
        sourceSamples = mono;
        sourceRate = sampleRate;
        normalisedGain = AudioPlayer.peakGain(mono, NORMALISATION_CEILING, NORMALISATION_MAX_GAIN_DB);
        reopen();
        updateGainDb();
    }

    /**
     * (Ré)ouvre le clip sur la source courante avec le gain courant (normalisé ou {@code 1.0}), en
     * préservant la position et l'état de lecture. No-op s'il n'y a pas de source. Silencieux en cas
     * d'échec (périphérique audio indisponible).
     */
    private void reopen() {
        if (sourceSamples == null) {
            return;
        }
        boolean wasPlaying = playing.get();
        double t = currentTime.get();
        double gain = normalisation.get() ? normalisedGain : 1.0;
        try {
            player.loadSamples(sourceSamples, sourceRate, gain);
        } catch (Exception ignored) {
            // La lecture audio est optionnelle ; on n'interrompt pas l'affichage.
            return;
        }
        player.seek(t);
        if (wasPlaying) {
            player.play();
        }
    }

    /** Définit la durée totale (en secondes) après chargement, et remet {@code currentTime} à 0. */
    public void setDuration(double seconds) {
        duration.set(seconds);
        currentTime.set(0);
    }

    public void togglePlay() {
        playing.set(!playing.get());
    }

    /**
     * Positionne la lecture, en secondes, bornée à {@code [0, duration]}. No-op si la durée est
     * encore nulle (aucun fichier chargé).
     */
    public void seek(double tFile) {
        if (duration.get() <= 0) {
            return;
        }
        double t = Math.max(0, Math.min(duration.get(), tFile));
        player.seek(t);
        currentTime.set(t);
    }

    public void dispose() {
        timer.stop();
        player.close();
    }

    // ----- Accesseurs -----

    public BooleanProperty playingProperty() {
        return playing;
    }

    public BooleanProperty loopProperty() {
        return loop;
    }

    public boolean isLoop() {
        return loop.get();
    }

    public void setLoop(boolean value) {
        loop.set(value);
    }

    public BooleanProperty normalisationProperty() {
        return normalisation;
    }

    public boolean isNormalisation() {
        return normalisation.get();
    }

    public void setNormalisation(boolean value) {
        normalisation.set(value);
    }

    public ReadOnlyDoubleProperty normalisationGainDbProperty() {
        return normalisationGainDb.getReadOnlyProperty();
    }

    public double normalisationGainDb() {
        return normalisationGainDb.get();
    }

    public ReadOnlyDoubleProperty currentTimeProperty() {
        return currentTime.getReadOnlyProperty();
    }

    public ReadOnlyDoubleProperty durationProperty() {
        return duration.getReadOnlyProperty();
    }

    public double currentTime() {
        return currentTime.get();
    }

    public double duration() {
        return duration.get();
    }

    public boolean isPlaying() {
        return playing.get();
    }
}
