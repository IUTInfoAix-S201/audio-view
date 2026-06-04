package fr.nedjar.vigiechiro.audio.playback;

import java.nio.file.Path;
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
 */
public final class PlaybackModel {

    private final AudioPlayer player = new AudioPlayer();
    private final AnimationTimer timer;

    private final BooleanProperty playing = new SimpleBooleanProperty(this, "playing", false);
    private final BooleanProperty loop = new SimpleBooleanProperty(this, "loop", false);
    private final ReadOnlyDoubleWrapper currentTime = new ReadOnlyDoubleWrapper(this, "currentTime", 0);
    private final ReadOnlyDoubleWrapper duration = new ReadOnlyDoubleWrapper(this, "duration", 0);

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
    }

    /**
     * Une tick du timer : si la position dépasse la durée et que {@code loop} est actif, repart à
     * zéro et continue la lecture ; sinon, arrête. Extrait du timer pour pouvoir tester sans
     * toolkit (le {@link AnimationTimer#start()} interne est toolkit-bound, pas cette méthode).
     */
    void handleTick(double pos) {
        if (duration.get() > 0 && pos >= duration.get()) {
            if (loop.get()) {
                player.seek(0);
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
    }

    /**
     * Charge un fichier dans le {@link AudioPlayer}. Silencieux en cas d'échec : l'affichage reste
     * fonctionnel même sans périphérique audio.
     */
    public void loadFile(Path path) {
        try {
            player.load(path);
        } catch (Exception ignored) {
            // La lecture audio est optionnelle ; on n'interrompt pas l'affichage.
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
