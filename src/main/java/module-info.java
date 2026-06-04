/**
 * Composant audio de la SAE 2.01 VigieChiro PR Companion. Seul {@code AudioView} est exposé ; le
 * décodage, la FFT, la lecture, les sous-vues, les ViewModels et le rendu restent internes (boîte
 * noire) — leurs packages sont {@code public} mais non exportés.
 */
module fr.nedjar.vigiechiro.audio {
    requires transitive javafx.base;
    requires transitive javafx.graphics;
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;

    exports fr.nedjar.vigiechiro.audio;

    // AudioView et les sous-vues SonogramView/SpectrogramView se chargent depuis leur FXML
    // (fx:root) : javafx.fxml doit pouvoir injecter les @FXML par réflexion dans ces deux paquets.
    opens fr.nedjar.vigiechiro.audio to
            javafx.fxml;
    opens fr.nedjar.vigiechiro.audio.view to
            javafx.fxml;
}
