package fr.nedjar.vigiechiro.audio.dsp;

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

class AudioSampleTest {

  @Test
  void chargeUnWavMonoEtNormaliseDansMoinsUnUn() throws Exception {
    float sr = 8000f;
    double seconds = 0.5;
    Path wav = ecrireWavMono(sr, seconds, (short) 16384); // 16384 / 32768 = 0.5
    AudioSample s = AudioSample.load(wav);

    assertThat(s.sampleRate()).isEqualTo(sr);
    assertThat(s.durationSeconds()).isCloseTo(seconds, within(0.01));
    assertThat(s.samples()).hasSize((int) (sr * seconds));
    assertThat(s.samples()[0]).isCloseTo(0.5f, within(0.001f));
  }

  @Test
  void echantillonsTousBornesDansMoinsUnUn() throws Exception {
    AudioSample s = AudioSample.load(ecrireWavMono(8000f, 0.2, (short) 32767));
    for (float v : s.samples()) {
      assertThat(v).isBetween(-1.0f, 1.0f);
    }
  }

  private static Path ecrireWavMono(float sampleRate, double seconds, short value)
      throws IOException {
    int n = (int) (sampleRate * seconds);
    byte[] data = new byte[n * 2];
    for (int i = 0; i < n; i++) {
      data[i * 2] = (byte) (value & 0xFF);
      data[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
    }
    AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
    Path out = Files.createTempFile("sample-test", ".wav");
    try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, n)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out.toFile());
    }
    out.toFile().deleteOnExit();
    return out;
  }
}
