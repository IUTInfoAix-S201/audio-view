package fr.nedjar.vigiechiro.audio.dsp;

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

  @Test
  void taille1EstUneIdentite() {
    // Borne basse : Integer.bitCount(1) == 1 → accepté ; aucun papillon, valeur inchangée.
    double[] re = {3.5};
    double[] im = {1.2};
    Fft.transform(re, im);
    assertThat(re[0]).isCloseTo(3.5, within(1e-12));
    assertThat(im[0]).isCloseTo(1.2, within(1e-12));
  }

  @Test
  void taille2EstUnSeulPapillon() {
    // FFT(a, b) = (a + b, a - b) — la plus petite combinaison de Cooley-Tukey.
    double[] re = {2.0, 3.0};
    double[] im = new double[2];
    Fft.transform(re, im);
    assertThat(re[0]).isCloseTo(5.0, within(1e-12));
    assertThat(im[0]).isCloseTo(0.0, within(1e-12));
    assertThat(re[1]).isCloseTo(-1.0, within(1e-12));
    assertThat(im[1]).isCloseTo(0.0, within(1e-12));
  }

  @Test
  void cosinusAligneDonnePicReelPurEnBinEtSymetrique() {
    // x[i] = cos(2π·k0·i/n) → re[k0] = re[n-k0] = n/2, partie imaginaire nulle (phase 0).
    // Vérifier re ET im détecte les mutants qui inverseraient le signe du twiddle.
    int n = 16;
    int k0 = 3;
    double[] re = new double[n];
    double[] im = new double[n];
    for (int i = 0; i < n; i++) {
      re[i] = Math.cos(2 * Math.PI * k0 * i / n);
    }
    Fft.transform(re, im);
    assertThat(re[k0]).isCloseTo(n / 2.0, within(1e-9));
    assertThat(im[k0]).isCloseTo(0.0, within(1e-9));
    assertThat(re[n - k0]).isCloseTo(n / 2.0, within(1e-9));
    assertThat(im[n - k0]).isCloseTo(0.0, within(1e-9));
    for (int k = 0; k < n; k++) {
      if (k == k0 || k == n - k0) {
        continue;
      }
      assertThat(Math.hypot(re[k], im[k])).isCloseTo(0.0, within(1e-9));
    }
  }

  @Test
  void sinusAligneDonnePartieImaginairePureEtConjugeeAuxBinsSymetriques() {
    // x[i] = sin(2π·k0·i/n) → im[k0] = -n/2, im[n-k0] = +n/2, re purement nul aux deux pics.
    // Le signe du twiddle (e^{-2iπ/n}) est ici critique : un mutant flipperait im.
    int n = 16;
    int k0 = 3;
    double[] re = new double[n];
    double[] im = new double[n];
    for (int i = 0; i < n; i++) {
      re[i] = Math.sin(2 * Math.PI * k0 * i / n);
    }
    Fft.transform(re, im);
    assertThat(re[k0]).isCloseTo(0.0, within(1e-9));
    assertThat(im[k0]).isCloseTo(-n / 2.0, within(1e-9));
    assertThat(re[n - k0]).isCloseTo(0.0, within(1e-9));
    assertThat(im[n - k0]).isCloseTo(n / 2.0, within(1e-9));
  }

  @Test
  void linearite() {
    // FFT(a·x + b·y) == a·FFT(x) + b·FFT(y) — propriété fondamentale de la transformée linéaire,
    // tue les mutants qui altèrent une accumulation conditionnellement.
    int n = 16;
    Random rnd = new Random(7);
    double[] x = new double[n];
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i] = rnd.nextDouble() * 2 - 1;
      y[i] = rnd.nextDouble() * 2 - 1;
    }
    double a = 1.7;
    double b = -0.3;

    double[] reCombined = new double[n];
    double[] imCombined = new double[n];
    for (int i = 0; i < n; i++) {
      reCombined[i] = a * x[i] + b * y[i];
    }
    Fft.transform(reCombined, imCombined);

    double[] reX = x.clone();
    double[] imX = new double[n];
    Fft.transform(reX, imX);
    double[] reY = y.clone();
    double[] imY = new double[n];
    Fft.transform(reY, imY);

    for (int k = 0; k < n; k++) {
      assertThat(reCombined[k]).isCloseTo(a * reX[k] + b * reY[k], within(1e-9));
      assertThat(imCombined[k]).isCloseTo(a * imX[k] + b * imY[k], within(1e-9));
    }
  }

  @Test
  void parsevalLEnergieEstConserveeAuFacteurN() {
    // Σ |x[i]|² == (1/n) · Σ |X[k]|² — théorème de Parseval. Détecte une normalisation erronée
    // (mutant `/= n` mal placé, par exemple) que les autres tests ne couvrent pas.
    int n = 16;
    Random rnd = new Random(123);
    double[] x = new double[n];
    double energyTime = 0;
    for (int i = 0; i < n; i++) {
      x[i] = rnd.nextDouble() * 2 - 1;
      energyTime += x[i] * x[i];
    }

    double[] re = x.clone();
    double[] im = new double[n];
    Fft.transform(re, im);
    double energyFreq = 0;
    for (int k = 0; k < n; k++) {
      energyFreq += re[k] * re[k] + im[k] * im[k];
    }
    assertThat(energyTime).isCloseTo(energyFreq / n, within(1e-9));
  }
}
