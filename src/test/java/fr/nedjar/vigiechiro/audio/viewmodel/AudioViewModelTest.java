package fr.nedjar.vigiechiro.audio.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.junit.jupiter.api.Test;

/**
 * Teste la logique pure du ViewModel (fenêtre, graduations, format, traduction des erreurs) sans
 * interface : c'est tout l'intérêt du MVVM — la logique d'affichage est vérifiable sans démarrer
 * JavaFX. L'analyse audio (décode + STFT + auto-échelles) est testée dans {@link
 * AudioAnalyzerTest}.
 */
class AudioViewModelTest {

  @Test
  void fenetreSuitLeZoomTemporel() {
    assertThat(AudioViewModel.windowDuration(10, 1)).isEqualTo(10);
    assertThat(AudioViewModel.windowDuration(10, 2)).isEqualTo(5);
    assertThat(AudioViewModel.windowDuration(0, 4)).isEqualTo(0);
  }

  @Test
  void debutDeFenetreCentreEtBorne() {
    // zoom 1 : vue complète, début collé à 0
    assertThat(AudioViewModel.windowStart(10, 5, 1)).isEqualTo(0);
    // zoom 2 : fenêtre de 5 centrée sur 5 -> début 2.5
    assertThat(AudioViewModel.windowStart(10, 5, 2)).isCloseTo(2.5, within(1e-9));
    // curseur en fin : début borné à durée - fenêtre
    assertThat(AudioViewModel.windowStart(10, 10, 2)).isCloseTo(5.0, within(1e-9));
  }

  @Test
  void texteTempsMisALEchelleParLeFacteur() {
    // 40.8 s fichier / 10 = 4.08 s réel ; 49.49 / 10 = 4.95 s (locale FR ou EN)
    assertThat(AudioViewModel.formatTimeText(40.8, 49.49, 10)).matches("4[.,]08 / 4[.,]95 s");
    assertThat(AudioViewModel.formatTimeText(1.0, 2.0, 1)).matches("1[.,]00 / 2[.,]00 s");
  }

  @Test
  void graduationsRondesEtFormat() {
    assertThat(AudioViewModel.niceStep(80, 4)).isEqualTo(20);
    assertThat(AudioViewModel.niceStep(2, 6)).isEqualTo(0.5);
    assertThat(AudioViewModel.formatAxis(45.0, 5.0)).isEqualTo("45");
    assertThat(AudioViewModel.formatAxis(0.5, 0.5)).matches("0[.,]5");
  }

  @Test
  void formatLoadErrorTraduitLesCausesCourantes() {
    // Message dédié pour les deux causes typiques (format inattendu / fichier illisible).
    assertThat(AudioViewModel.formatLoadError(new UnsupportedAudioFileException("x")))
        .contains("non pris en charge");
    assertThat(AudioViewModel.formatLoadError(new IOException("boum")))
        .contains("Impossible de lire");
    // Fallback : préfixe générique + détail de l'exception quand il existe.
    assertThat(AudioViewModel.formatLoadError(new RuntimeException("oops")))
        .contains("Erreur de chargement")
        .contains("oops");
    // Robustesse : pas de NPE si l'exception ou son message sont null.
    assertThat(AudioViewModel.formatLoadError(new RuntimeException()))
        .contains("Erreur de chargement");
    assertThat(AudioViewModel.formatLoadError(null)).contains("Erreur de chargement");
  }
}
