package fr.nedjar.vigiechiro.audio.viewmodel;

import fr.nedjar.vigiechiro.audio.dsp.AudioSample;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;

/**
 * ViewModel du tracé sonogramme — façade sur la session {@link AudioViewModel} qui n'expose que ce
 * dont la sous-vue sono a besoin (échantillons, fenêtre temporelle, auto-échelle d'amplitude,
 * facteur d'expansion, position curseur, commande seek). Issue #9.
 *
 * <p>Ajoute deux calculs purs propres à la gouttière d'amplitude (pic visible et pas de
 * graduation), testables sans toolkit.
 */
public final class SonogramViewModel {

    private final AudioViewModel session;

    public SonogramViewModel(AudioViewModel session) {
        this.session = session;
    }

    // ----- Façades observables -----

    public ReadOnlyObjectProperty<AudioSample> sampleProperty() {
        return session.sampleProperty();
    }

    public ReadOnlyDoubleProperty sonoScaleProperty() {
        return session.sonoScaleProperty();
    }

    public ReadOnlyDoubleProperty currentTimeProperty() {
        return session.currentTimeProperty();
    }

    public DoubleBinding windowStartBinding() {
        return session.windowStartBinding();
    }

    public DoubleBinding windowDurationBinding() {
        return session.windowDurationBinding();
    }

    // ----- Façades scalaires -----

    public AudioSample getSample() {
        return session.getSample();
    }

    public double sonoScale() {
        return session.getSonoScale();
    }

    public double currentTime() {
        return session.getCurrentTime();
    }

    public double windowStart() {
        return session.windowStart();
    }

    public double windowDuration() {
        return session.windowDuration();
    }

    public double expansionFactor() {
        return session.expansionFactor();
    }

    // ----- Commandes -----

    public void seek(double tFile) {
        session.seek(tFile);
    }

    // ----- Calculs purs propres au tracé sono -----

    /**
     * Amplitude visible à l'écran : inverse de l'auto-échelle (le pic du fichier remplit ~95 % de la
     * demi-hauteur, donc le « pic visible » à la graduation vaut {@code 1/sonoScale}).
     *
     * @return pic visible, ou {@code 0} si l'auto-échelle est nulle / négative (fichier muet)
     */
    public double amplitudePeak() {
        return amplitudePeak(sonoScale());
    }

    /** Pas « rond » des graduations d'amplitude (symétrique autour de 0, ~4 intervalles). */
    public double amplitudeStep() {
        return amplitudeStep(sonoScale());
    }

    /** Surcharge pure (sans dépendance à l'état du VM), testable unitairement. */
    public static double amplitudePeak(double sonoScale) {
        return sonoScale > 0 ? 1.0 / sonoScale : 0;
    }

    /** Surcharge pure (sans dépendance à l'état du VM), testable unitairement. */
    public static double amplitudeStep(double sonoScale) {
        return AudioViewModel.niceStep(2 * amplitudePeak(sonoScale), 4);
    }
}
