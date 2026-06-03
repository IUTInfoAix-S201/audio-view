package fr.nedjar.vigiechiro.audio;

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
final class SonogramViewModel {

  private final AudioViewModel session;

  SonogramViewModel(AudioViewModel session) {
    this.session = session;
  }

  // ----- Façades observables -----

  ReadOnlyObjectProperty<AudioSample> sampleProperty() {
    return session.sampleProperty();
  }

  ReadOnlyDoubleProperty sonoScaleProperty() {
    return session.sonoScaleProperty();
  }

  ReadOnlyDoubleProperty currentTimeProperty() {
    return session.currentTimeProperty();
  }

  DoubleBinding windowStartBinding() {
    return session.windowStartBinding();
  }

  DoubleBinding windowDurationBinding() {
    return session.windowDurationBinding();
  }

  // ----- Façades scalaires -----

  AudioSample getSample() {
    return session.getSample();
  }

  double sonoScale() {
    return session.getSonoScale();
  }

  double currentTime() {
    return session.getCurrentTime();
  }

  double windowStart() {
    return session.windowStart();
  }

  double windowDuration() {
    return session.windowDuration();
  }

  double expansionFactor() {
    return session.expansionFactor();
  }

  // ----- Commandes -----

  void seek(double tFile) {
    session.seek(tFile);
  }

  // ----- Calculs purs propres au tracé sono -----

  /**
   * Amplitude visible à l'écran : inverse de l'auto-échelle (le pic du fichier remplit ~95 % de la
   * demi-hauteur, donc le « pic visible » à la graduation vaut {@code 1/sonoScale}).
   *
   * @return pic visible, ou {@code 0} si l'auto-échelle est nulle / négative (fichier muet)
   */
  double amplitudePeak() {
    return amplitudePeak(sonoScale());
  }

  /** Pas « rond » des graduations d'amplitude (symétrique autour de 0, ~4 intervalles). */
  double amplitudeStep() {
    return amplitudeStep(sonoScale());
  }

  /** Surcharge pure (sans dépendance à l'état du VM), testable unitairement. */
  static double amplitudePeak(double sonoScale) {
    return sonoScale > 0 ? 1.0 / sonoScale : 0;
  }

  /** Surcharge pure (sans dépendance à l'état du VM), testable unitairement. */
  static double amplitudeStep(double sonoScale) {
    return AudioViewModel.niceStep(2 * amplitudePeak(sonoScale), 4);
  }
}
