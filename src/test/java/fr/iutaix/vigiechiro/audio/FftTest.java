package fr.iutaix.vigiechiro.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Test;

class FftTest {

  @Test
  void longueurNonPuissanceDeDeuxLeveUneException() {
    double[] re = new double[6];
    double[] im = new double[6];
    assertThatThrownBy(() -> Fft.transform(re, im)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void transformeeDUneImpulsionEstUnSpectrePlat() {
    int n = 8;
    double[] re = new double[n];
    double[] im = new double[n];
    re[0] = 1.0; // impulsion en 0 -> spectre plat de module 1
    Fft.transform(re, im);
    for (int k = 0; k < n; k++) {
      assertThat(Math.hypot(re[k], im[k])).isCloseTo(1.0, within(1e-9));
    }
  }

  @Test
  void transformeeDUnSignalConstantSeConcentreEnZero() {
    int n = 8;
    double[] re = new double[n];
    double[] im = new double[n];
    for (int i = 0; i < n; i++) {
      re[i] = 1.0;
    }
    Fft.transform(re, im);
    assertThat(re[0]).isCloseTo(n, within(1e-9));
    assertThat(im[0]).isCloseTo(0.0, within(1e-9));
    for (int k = 1; k < n; k++) {
      assertThat(Math.hypot(re[k], im[k])).isCloseTo(0.0, within(1e-9));
    }
  }

  @Test
  void correspondAuDftNaifPourUnSignalAleatoire() {
    int n = 16;
    Random rnd = new Random(42);
    double[] x = new double[n];
    for (int i = 0; i < n; i++) {
      x[i] = rnd.nextDouble() * 2 - 1;
    }
    double[] re = x.clone();
    double[] im = new double[n];
    Fft.transform(re, im);
    for (int k = 0; k < n; k++) {
      double sumRe = 0;
      double sumIm = 0;
      for (int t = 0; t < n; t++) {
        double ang = -2 * Math.PI * k * t / n;
        sumRe += x[t] * Math.cos(ang);
        sumIm += x[t] * Math.sin(ang);
      }
      assertThat(re[k]).isCloseTo(sumRe, within(1e-9));
      assertThat(im[k]).isCloseTo(sumIm, within(1e-9));
    }
  }
}
