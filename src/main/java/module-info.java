/**
 * Composant audio de la SAE 2.01 VigieChiro PR Companion. Seul {@code AudioView} est exposé ; le
 * décodage, la FFT et la lecture restent internes (boîte noire).
 */
module fr.iutaix.vigiechiro.audio {
  requires javafx.controls;
  requires javafx.graphics;
  requires java.desktop;

  exports fr.iutaix.vigiechiro.audio;
}
