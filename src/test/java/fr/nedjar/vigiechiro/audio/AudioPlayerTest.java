package fr.nedjar.vigiechiro.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class AudioPlayerTest {

  @Test
  void sansClipChargeLesPositionsSontNulles() {
    try (AudioPlayer p = new AudioPlayer()) {
      assertThat(p.position()).isZero();
      assertThat(p.length()).isZero();
    }
  }

  @Test
  void operationsSansClipSontSansEffetEtSansErreur() {
    AudioPlayer p = new AudioPlayer();
    assertThatCode(
            () -> {
              p.play();
              p.pause();
              p.seek(1.0);
              p.close();
            })
        .doesNotThrowAnyException();
  }
}
