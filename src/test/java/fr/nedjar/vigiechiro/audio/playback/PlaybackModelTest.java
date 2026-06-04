package fr.nedjar.vigiechiro.audio.playback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Teste le {@link PlaybackModel} sur ses parties sans toolkit JavaFX : seek (clamp + no-op sans
 * fichier), setDuration, reset depuis l'état initial. {@code togglePlay} déclenche {@code
 * AnimationTimer#start()} (toolkit-bound) — couvert indirectement par le smoke TestFX. Issue #14.
 */
class PlaybackModelTest {

  @Test
  void etatInitialEstVide() {
    PlaybackModel m = new PlaybackModel();
    assertThat(m.isPlaying()).isFalse();
    assertThat(m.currentTime()).isZero();
    assertThat(m.duration()).isZero();
  }

  @Test
  void setDurationFixeLaDureeEtRemetLeCurseurAZero() {
    PlaybackModel m = new PlaybackModel();
    m.setDuration(42.5);
    assertThat(m.duration()).isEqualTo(42.5);
    assertThat(m.currentTime()).isZero();
  }

  @Test
  void seekClampDansLesBornes() {
    PlaybackModel m = new PlaybackModel();
    m.setDuration(10);

    m.seek(3.0);
    assertThat(m.currentTime()).isEqualTo(3.0);

    // au-delà de la durée : borné à duration
    m.seek(99);
    assertThat(m.currentTime()).isEqualTo(10);

    // valeur négative : bornée à 0
    m.seek(-5);
    assertThat(m.currentTime()).isZero();
  }

  @Test
  void seekNoOpQuandPasDeFichierCharge() {
    // duration = 0 : aucun fichier chargé, seek ne doit pas faire bouger currentTime.
    PlaybackModel m = new PlaybackModel();
    m.seek(5);
    assertThat(m.currentTime()).isZero();
  }

  @Test
  void resetRemetLeTransportAZero() {
    // depuis l'état initial (playing déjà false), reset ne déclenche pas le listener "playing"
    // qui sollicite AnimationTimer.start() (toolkit-bound) — donc safe en test unitaire.
    PlaybackModel m = new PlaybackModel();
    m.setDuration(8);
    m.seek(4);

    m.reset();

    assertThat(m.isPlaying()).isFalse();
    assertThat(m.duration()).isZero();
    assertThat(m.currentTime()).isZero();
  }

  @Test
  void exposeLesProprietesObservables() {
    // Smoke : les propriétés retournées sont bien câblées aux valeurs internes.
    PlaybackModel m = new PlaybackModel();
    m.setDuration(7);
    assertThat(m.durationProperty().get()).isEqualTo(7);

    m.seek(2);
    assertThat(m.currentTimeProperty().get()).isEqualTo(2);

    assertThat(m.playingProperty().get()).isFalse();
  }
}
