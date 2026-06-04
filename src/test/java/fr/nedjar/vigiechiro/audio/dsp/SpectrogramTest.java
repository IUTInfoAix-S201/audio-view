package fr.nedjar.vigiechiro.audio.dsp;

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

  @Test
  void signalPlusCourtQueFftSizeProduitUneSeuleTrame() throws Exception {
    // 100 échantillons < fftSize=1024 → 1 trame paddée à 0 sur la queue (cas bordure).
    AudioSample s = AudioSample.load(ecrireWavSamples(8000f, 100, 1000, 12000));
    Spectrogram spec = Spectrogram.compute(s, 1024, 256);
    assertThat(spec.frameCount()).isEqualTo(1);
    assertThat(spec.binCount()).isEqualTo(1024 / 2 + 1);
  }

  @Test
  void nombreDeTramesCroitParPasDeHop() throws Exception {
    // Vérifie la formule dense : 1 + max(0, n - fftSize) / hop, plancher à 1.
    AudioSample s1024 = AudioSample.load(ecrireWavSamples(8000f, 1024, 1000, 12000));
    AudioSample s1280 = AudioSample.load(ecrireWavSamples(8000f, 1024 + 256, 1000, 12000));
    AudioSample s1536 = AudioSample.load(ecrireWavSamples(8000f, 1024 + 512, 1000, 12000));
    assertThat(Spectrogram.compute(s1024, 1024, 256).frameCount()).isEqualTo(1);
    assertThat(Spectrogram.compute(s1280, 1024, 256).frameCount()).isEqualTo(2);
    assertThat(Spectrogram.compute(s1536, 1024, 256).frameCount()).isEqualTo(3);
  }

  @Test
  void silenceProduitUnPlancherFiniSansLogDeZero() throws Exception {
    // Signal entièrement nul → magnitudes brutes nulles. Le plancher anti-log(0) doit éviter
    // -Infinity ; valeur cible ~ 20·log10(1e-9) = -180 dBFS. Tous les bins doivent être finis
    // et bien sous le seuil d'utilité.
    AudioSample s = AudioSample.load(ecrireWavSamples(8000f, 8000, 0, 0));
    Spectrogram spec = Spectrogram.compute(s, 1024, 256);
    for (int f = 0; f < spec.frameCount(); f++) {
      for (int b = 0; b < spec.binCount(); b++) {
        float v = spec.magnitudeDb(f, b);
        assertThat(v).isFinite().isLessThan(-100);
      }
    }
  }

  @Test
  void fenetreDeHannLimiteLesFuitesAutourDuPic() throws Exception {
    // Sinusoïde 1000 Hz / sample 8 kHz / fftSize 1024 → pic au bin ~128. La fenêtre de Hann
    // réduit fortement les fuites : à 20 bins de distance, on doit voir un creux significatif
    // (≥ 40 dB), ce qui ne serait pas le cas avec une fenêtre rectangulaire.
    float sr = 8000f;
    int fftSize = 1024;
    double freq = 1000;
    AudioSample s = AudioSample.load(ecrireWavSamples(sr, (int) sr, freq, 12000));
    Spectrogram spec = Spectrogram.compute(s, fftSize, 256);

    int binPic = (int) Math.round(freq * fftSize / sr);
    int frame = spec.frameCount() / 2;
    float pic = spec.magnitudeDb(frame, binPic);
    float loin = spec.magnitudeDb(frame, binPic + 20);
    assertThat(pic - loin).isGreaterThan(40);
  }

  private static Path ecrireSinusWav(float sampleRate, double seconds, double freq)
      throws IOException {
    return ecrireWavSamples(sampleRate, (int) (sampleRate * seconds), freq, 12000);
  }

  /** Génère un WAV mono 16 bits PCM signé little-endian, de {@code n} échantillons. */
  private static Path ecrireWavSamples(float sampleRate, int n, double freq, double amplitude)
      throws IOException {
    byte[] data = new byte[n * 2];
    for (int i = 0; i < n; i++) {
      double t = i / (double) sampleRate;
      short v = (short) (Math.sin(2 * Math.PI * freq * t) * amplitude);
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
