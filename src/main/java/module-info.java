/**
 * Composant audio de la SAE 2.01 VigieChiro PR Companion. Seul {@code AudioView} est exposé ; le
 * décodage, la FFT et la lecture restent internes (boîte noire).
 */
module fr.nedjar.vigiechiro.audio {
  requires transitive javafx.base;
  requires transitive javafx.graphics;
  requires javafx.controls;
  requires javafx.fxml;
  requires java.desktop;

  exports fr.nedjar.vigiechiro.audio;

  // AudioView se charge depuis AudioView.fxml (fx:root) : javafx.fxml doit pouvoir injecter
  // les @FXML par réflexion dans le paquet.
  opens fr.nedjar.vigiechiro.audio to
      javafx.fxml;
}
