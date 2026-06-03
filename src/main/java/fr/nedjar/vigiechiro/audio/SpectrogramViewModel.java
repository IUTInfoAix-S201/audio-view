package fr.nedjar.vigiechiro.audio;

import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.WritableImage;

/**
 * ViewModel du tracé spectrogramme — façade sur la session {@link AudioViewModel} qui n'expose que
 * ce dont la sous-vue spectro a besoin (image, échantillons, fenêtre temporelle, zoom fréquence,
 * facteur d'expansion, position curseur, commande seek). Issue #9.
 *
 * <p>Ajoute le calcul pur du recadrage temps/fréquence (viewport de l'{@code ImageView}), testable
 * sans toolkit (Rectangle2D est une valeur géométrique sans dépendance graphique).
 */
final class SpectrogramViewModel {

  private final AudioViewModel session;

  SpectrogramViewModel(AudioViewModel session) {
    this.session = session;
  }

  // ----- Façades observables -----

  ReadOnlyObjectProperty<WritableImage> spectrogramImageProperty() {
    return session.spectrogramImageProperty();
  }

  ReadOnlyObjectProperty<AudioSample> sampleProperty() {
    return session.sampleProperty();
  }

  ReadOnlyDoubleProperty currentTimeProperty() {
    return session.currentTimeProperty();
  }

  ReadOnlyDoubleProperty durationProperty() {
    return session.durationProperty();
  }

  DoubleProperty frequencyZoomProperty() {
    return session.frequencyZoomProperty();
  }

  DoubleBinding windowStartBinding() {
    return session.windowStartBinding();
  }

  DoubleBinding windowDurationBinding() {
    return session.windowDurationBinding();
  }

  // ----- Façades scalaires -----

  WritableImage getSpectrogramImage() {
    return session.getSpectrogramImage();
  }

  AudioSample getSample() {
    return session.getSample();
  }

  double duration() {
    return session.getDuration();
  }

  double frequencyZoom() {
    return session.getFrequencyZoom();
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

  double currentTime() {
    return session.getCurrentTime();
  }

  // ----- Commandes -----

  void seek(double tFile) {
    session.seek(tFile);
  }

  // ----- Calculs purs propres au tracé spectro -----

  /**
   * Recadrage de l'image spectrogramme (viewport de l'{@code ImageView}). Zoom temporel : tranche
   * horizontale {@code [start, start+win] / dur} en pixels image. Zoom fréquentiel : tranche basse
   * de hauteur {@code imgH / freqZoom} (basses fréquences en bas de l'image, cf. {@link
   * SpectrogramImageFactory}).
   *
   * @return {@code null} si {@code imgW <= 0} ou {@code imgH <= 0} (rien à afficher)
   */
  Rectangle2D viewport(double imgW, double imgH) {
    return viewport(duration(), windowStart(), windowDuration(), frequencyZoom(), imgW, imgH);
  }

  /** Surcharge pure (sans dépendance à l'état du VM), testable unitairement. */
  static Rectangle2D viewport(
      double dur,
      double windowStart,
      double windowDuration,
      double freqZoom,
      double imgW,
      double imgH) {
    if (imgW <= 0 || imgH <= 0) {
      return null;
    }
    double sx = dur <= 0 ? 0 : (windowStart / dur) * imgW;
    double sw = dur <= 0 ? imgW : (windowDuration / dur) * imgW;
    double fz = Math.max(1, freqZoom);
    double visibleH = imgH / fz;
    double sy = imgH - visibleH;
    return new Rectangle2D(sx, sy, Math.max(1, sw), Math.max(1, visibleH));
  }
}
