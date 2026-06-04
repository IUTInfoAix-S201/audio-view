package fr.nedjar.vigiechiro.audio.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * Couvre les branches de {@link PlaybackModel} qui sollicitent le toolkit JavaFX : {@code
 * togglePlay} (listener qui démarre/arrête {@link javafx.animation.AnimationTimer}), replay depuis
 * zéro en fin d'extrait, {@code dispose}. Les autres parties sont testées sans toolkit dans {@link
 * PlaybackModelTest}. Issue #14.
 */
class PlaybackModelToolkitTest extends ApplicationTest {

  @Override
  public void start(Stage stage) {
    // Aucune scène nécessaire : seul le toolkit JavaFX doit être initialisé pour qu'
    // AnimationTimer.start() fonctionne.
  }

  @Test
  void togglePlayBasculeLaPropriete() {
    PlaybackModel m = new PlaybackModel();
    m.setDuration(5);

    interact(m::togglePlay);
    assertThat(m.isPlaying()).isTrue();

    interact(m::togglePlay);
    assertThat(m.isPlaying()).isFalse();

    interact(m::dispose);
  }

  @Test
  void demarrerEnFinDExtraitRepartDuDebut() {
    PlaybackModel m = new PlaybackModel();
    m.setDuration(5);
    interact(() -> m.seek(5)); // curseur collé à la durée

    interact(m::togglePlay);
    assertThat(m.currentTime()).isZero();

    interact(m::togglePlay);
    interact(m::dispose);
  }

  @Test
  void disposeEstSansErreurDansTousLesEtats() {
    // dispose sans avoir rien fait
    PlaybackModel pristine = new PlaybackModel();
    assertThatCode(() -> interact(pristine::dispose)).doesNotThrowAnyException();

    // dispose pendant la lecture
    PlaybackModel playing = new PlaybackModel();
    playing.setDuration(5);
    interact(playing::togglePlay);
    assertThatCode(() -> interact(playing::dispose)).doesNotThrowAnyException();
  }
}
