package fr.iutaix.vigiechiro.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;

class SpectrogramTest {

  @Test
  void dimensionsCoherentes() throws Exception {
    AudioSample s = AudioSample.load(ecrireSinusWav(8000f, 0.5, 1000));
    Spectrogram spec = Spectrogram.compute(s, 1024, 256);
    assertThat(spec.binCount()).isEqualTo(1024 / 2 + 1);
    assertThat(spec.frameCount()).isGreaterThan(0);
  }

  @Test
  void lePicSpectralTombeSurLaBonneFrequence() throws Exception {
    float sr = 8000f;
    int fftSize = 1024;
    double freq = 1000;
    AudioSample s = AudioSample.load(ecrireSinusWav(sr, 1.0, freq));
    Spectrogram spec = Spectrogram.compute(s, fftSize, 256);

    int binAttendu = (int) Math.round(freq * fftSize / sr);
    int frame = spec.frameCount() / 2;
    int binMax = 0;
    float max = -Float.MAX_VALUE;
    for (int b = 0; b < spec.binCount(); b++) {
      float v = spec.magnitudeDb(frame, b);
      if (v > max) {
        max = v;
        binMax = b;
      }
    }
    assertThat(Math.abs(binMax - binAttendu)).isLessThanOrEqualTo(2);
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
    Path out = Files.createTempFile("spectro-test", ".wav");
    try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, n)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out.toFile());
    }
    out.toFile().deleteOnExit();
    return out;
  }
}
