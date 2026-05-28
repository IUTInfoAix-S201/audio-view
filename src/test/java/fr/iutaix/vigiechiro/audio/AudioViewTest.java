package fr.iutaix.vigiechiro.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Test fumant : il vérifie que le composant accepte un WAV, le décode hors du thread FX, et publie
 * une durée cohérente. Nécessite un affichage (un serveur X virtuel via {@code xvfb-run} en CI).
 */
class AudioViewTest extends ApplicationTest {

  private AudioView view;

  @Override
  public void start(Stage stage) {
    view = new AudioView();
    stage.setScene(new Scene(view, 640, 360));
    stage.show();
  }

  @Test
  void chargeUnWavEtPublieLaDuree() throws Exception {
    Path wav = ecrireSinusWav(38_400f, 1.0); // 1 seconde a 38,4 kHz

    interact(() -> view.setAudioFile(wav));
    WaitForAsyncUtils.waitForFxEvents();
    // Le decodage et la FFT sont asynchrones : on attend la mise a jour de la duree.
    WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> view.getDuration() > 0.0);

    assertThat(view.getDuration()).isCloseTo(1.0, Offset.offset(0.1));
    assertThat(view.getCurrentTime()).isEqualTo(0.0);
  }

  private static Path ecrireSinusWav(float sampleRate, double seconds) throws IOException {
    int n = (int) (sampleRate * seconds);
    byte[] data = new byte[n * 2];
    for (int i = 0; i < n; i++) {
      double t = i / (double) sampleRate;
      short s = (short) (Math.sin(2 * Math.PI * 4000 * t) * 12_000);
      data[i * 2] = (byte) (s & 0xFF);
      data[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
    }
    AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
    Path out = Files.createTempFile("audioview-test", ".wav");
    try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, n)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out.toFile());
    }
    out.toFile().deleteOnExit();
    return out;
  }
}
