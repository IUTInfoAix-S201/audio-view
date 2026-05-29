package fr.nedjar.vigiechiro.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;

/**
 * Teste la logique pure du ViewModel (fenêtre, auto-échelles, graduations, format) sans interface :
 * c'est tout l'intérêt du MVVM — la logique d'affichage est vérifiable sans démarrer JavaFX.
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
  void autoEchelleSonogrammeSurLePic() throws Exception {
    // pic = 16384/32768 = 0.5 -> facteur = 0.95 / 0.5 = 1.9
    AudioSample s = AudioSample.load(ecrireWavMono(8000f, 0.2, (short) 16384));
    assertThat(AudioViewModel.sonoScaleFor(s)).isCloseTo(1.9, within(0.05));
  }

  @Test
  void cadrageFrequentielZoomeSurUneToneBasse() throws Exception {
    // tone à 500 Hz dans un fichier 8 kHz (Nyquist 4 kHz) : bande utile basse -> zoom > 1
    AudioSample s = AudioSample.load(ecrireSinusWav(8000f, 1.0, 500));
    Spectrogram spec = Spectrogram.compute(s, 1024, 256);
    double zoom = AudioViewModel.autoFrequencyZoom(spec);
    assertThat(zoom).isGreaterThan(1.5).isLessThanOrEqualTo(64.0);
  }

  private static Path ecrireWavMono(float sampleRate, double seconds, short value)
      throws IOException {
    int n = (int) (sampleRate * seconds);
    byte[] data = new byte[n * 2];
    for (int i = 0; i < n; i++) {
      data[i * 2] = (byte) (value & 0xFF);
      data[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
    }
    return ecrire(data, sampleRate, n);
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
    return ecrire(data, sampleRate, n);
  }

  private static Path ecrire(byte[] data, float sampleRate, int frames) throws IOException {
    AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
    Path out = Files.createTempFile("vm-test", ".wav");
    try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, frames)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out.toFile());
    }
    out.toFile().deleteOnExit();
    return out;
  }
}
