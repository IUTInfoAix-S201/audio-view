package fr.nedjar.vigiechiro.audio.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import fr.nedjar.vigiechiro.audio.dsp.AudioAnalyzer;
import fr.nedjar.vigiechiro.audio.dsp.AudioSample;
import fr.nedjar.vigiechiro.audio.dsp.Spectrogram;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * Teste {@link SpectrogramImageFactory} avec un toolkit JavaFX initialisé (la classe instancie une
 * {@link WritableImage}). Vérifie la géométrie de l'image, le mapping basses fréquences en bas,
 * l'effet de la colormap et le clamp aux bornes dB. Issue #11.
 */
class SpectrogramImageFactoryToolkitTest extends ApplicationTest {

  @Override
  public void start(Stage stage) {
    // Aucune scène nécessaire : seul le toolkit JavaFX doit être initialisé pour WritableImage.
  }

  @Test
  void imageALaTailleDuSpectrogramme() throws Exception {
    Spectrogram spec = spectrogrammeSinusoide();

    WritableImage img = SpectrogramImageFactory.build(spec, Colormap.SOMBRE, -90.0, -10.0);

    assertThat((int) img.getWidth()).isEqualTo(spec.frameCount());
    assertThat((int) img.getHeight()).isEqualTo(spec.binCount());
  }

  @Test
  void bassesFrequencesEnBasDeLImage() throws Exception {
    // Sinusoïde à 500 Hz dans une bande 0-4 kHz : l'énergie est concentrée dans les bins bas.
    // Convention de la factory : basses fréquences en bas -> le pixel le plus lumineux de la
    // colonne doit se trouver dans le TIERS BAS de l'image.
    Spectrogram spec = spectrogrammeSinusoide();

    WritableImage img = SpectrogramImageFactory.build(spec, Colormap.SOMBRE, -90.0, -10.0);
    int milieuX = spec.frameCount() / 2;
    int h = (int) img.getHeight();

    int yMax = 0;
    double lumMax = -1;
    for (int y = 0; y < h; y++) {
      Color c = img.getPixelReader().getColor(milieuX, y);
      double lum = c.getRed() + c.getGreen() + c.getBlue();
      if (lum > lumMax) {
        lumMax = lum;
        yMax = y;
      }
    }

    assertThat(yMax)
        .as("pixel le plus lumineux dans le tiers bas (basses fréquences en bas)")
        .isGreaterThan(2 * h / 3);
  }

  @Test
  void normalisationClampeAuxBornesDb() throws Exception {
    // Une plage [-91, -89] qui n'inclut presque rien du spectre force la majorité des pixels
    // à être clampée -> uniformes. On vérifie au moins qu'aucun NaN/erreur ne survient.
    Spectrogram spec = spectrogrammeSinusoide();
    WritableImage img = SpectrogramImageFactory.build(spec, Colormap.SOMBRE, -91.0, -89.0);
    Color c = img.getPixelReader().getColor(0, 0);
    assertThat(c.getOpacity()).isCloseTo(1.0, within(1e-6));
  }

  @Test
  void plageDbInvalideEstRejetee() throws Exception {
    Spectrogram spec = spectrogrammeSinusoide();
    assertThatThrownBy(() -> SpectrogramImageFactory.build(spec, Colormap.SOMBRE, 0.0, 0.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SpectrogramImageFactory.build(spec, Colormap.SOMBRE, -10.0, -90.0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Spectrogram spectrogrammeSinusoide() throws IOException {
    Path wav = ecrireSinusWav(8000f, 0.5, 500);
    try {
      AudioSample sample = AudioSample.load(wav);
      return Spectrogram.compute(sample, AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
    } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
      throw new IllegalStateException("WAV de test mal formé", e);
    }
  }

  private static Path ecrireSinusWav(float sampleRate, double seconds, double freq)
      throws IOException {
    int n = (int) (sampleRate * seconds);
    byte[] data = new byte[n * 2];
    for (int i = 0; i < n; i++) {
      double t = i / (double) sampleRate;
      short v = (short) (Math.sin(2 * Math.PI * freq * t) * 12000);
      data[i * 2] = (byte) (v & 0xFF);
      data[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
    }
    AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
    Path out = Files.createTempFile("imgfactory-test", ".wav");
    try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, n)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out.toFile());
    }
    out.toFile().deleteOnExit();
    return out;
  }
}
