package fr.iutaix.vigiechiro.audio;

/**
 * FFT complexe en place, itérative, radix-2 (Cooley-Tukey). La longueur doit être une puissance de
 * deux. Implémentation volontairement compacte : elle évite toute dépendance externe pour des
 * fenêtres de quelques centaines à quelques milliers d'échantillons, ce qui est largement suffisant
 * pour un spectrogramme de séquences de 5 secondes.
 */
final class Fft {

  private Fft() {}

  static void transform(double[] re, double[] im) {
    int n = re.length;
    if (Integer.bitCount(n) != 1) {
      throw new IllegalArgumentException("la longueur doit être une puissance de deux : " + n);
    }

    // Permutation par inversion de bits.
    for (int i = 1, j = 0; i < n; i++) {
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) {
        j ^= bit;
      }
      j ^= bit;
      if (i < j) {
        double tr = re[i];
        re[i] = re[j];
        re[j] = tr;
        double ti = im[i];
        im[i] = im[j];
        im[j] = ti;
      }
    }

    // Papillons.
    for (int len = 2; len <= n; len <<= 1) {
      double ang = -2 * Math.PI / len;
      double wLenRe = Math.cos(ang);
      double wLenIm = Math.sin(ang);
      for (int i = 0; i < n; i += len) {
        double wRe = 1;
        double wIm = 0;
        for (int k = 0; k < len / 2; k++) {
          int a = i + k;
          int b = i + k + len / 2;
          double vRe = re[b] * wRe - im[b] * wIm;
          double vIm = re[b] * wIm + im[b] * wRe;
          double uRe = re[a];
          double uIm = im[a];
          re[a] = uRe + vRe;
          im[a] = uIm + vIm;
          re[b] = uRe - vRe;
          im[b] = uIm - vIm;
          double nextWRe = wRe * wLenRe - wIm * wLenIm;
          wIm = wRe * wLenIm + wIm * wLenRe;
          wRe = nextWRe;
        }
      }
    }
  }
}
