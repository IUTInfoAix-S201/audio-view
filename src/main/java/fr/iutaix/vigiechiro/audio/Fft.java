package fr.iutaix.vigiechiro.audio;

/**
 * FFT complexe en place, itérative, radix-2 (Cooley-Tukey).
 *
 * <p>{@link #transform(double[], double[])} remplace les tableaux {@code real} / {@code imag} par
 * leur transformée de Fourier discrète. La longueur doit être une puissance de deux.
 *
 * <p>Implémentation volontairement compacte et sans dépendance : pour des fenêtres de quelques
 * centaines à quelques milliers d'échantillons (spectrogramme de séquences de 5 s), c'est largement
 * suffisant. L'algorithme se lit en trois étapes : vérifier la taille, réordonner par inversion de
 * bits, puis combiner les sous-transformées par « papillons ».
 */
final class Fft {

  private Fft() {}

  /**
   * Calcule en place la transformée de Fourier discrète du signal complexe ({@code real}, {@code
   * imag}).
   *
   * @param real partie réelle (longueur = puissance de deux) ; écrasée par le résultat
   * @param imag partie imaginaire (même longueur que {@code real}) ; écrasée par le résultat
   * @throws IllegalArgumentException si la longueur n'est pas une puissance de deux
   */
  static void transform(double[] real, double[] imag) {
    requirePowerOfTwo(real.length);
    bitReversalReorder(real, imag);
    combineButterflies(real, imag);
  }

  private static void requirePowerOfTwo(int length) {
    if (Integer.bitCount(length) != 1) {
      throw new IllegalArgumentException("la longueur doit être une puissance de deux : " + length);
    }
  }

  /**
   * Réordonne les échantillons par inversion de bits de leur indice : c'est le pré-requis de la FFT
   * itérative en place (chaque indice {@code i} est échangé avec son indice « bits inversés »
   * {@code j}).
   */
  private static void bitReversalReorder(double[] real, double[] imag) {
    int n = real.length;
    for (int i = 1, j = 0; i < n; i++) {
      // j énumère les indices dans l'ordre « bits inversés », en suivant i.
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) {
        j ^= bit;
      }
      j ^= bit;
      if (i < j) {
        swap(real, i, j);
        swap(imag, i, j);
      }
    }
  }

  /**
   * Combine itérativement les sous-transformées de taille croissante (2, 4, …, n) par papillons de
   * Cooley-Tukey. À chaque étage, le facteur de rotation (« twiddle ») {@code w = e^(-2iπ/size)}
   * est appliqué de proche en proche par multiplications successives, sans recalculer de
   * sinus/cosinus.
   */
  private static void combineButterflies(double[] real, double[] imag) {
    int n = real.length;
    for (int size = 2; size <= n; size <<= 1) {
      int half = size / 2;
      double angle = -2 * Math.PI / size;
      double stepRe = Math.cos(angle); // multiplicateur faisant tourner le twiddle d'un cran
      double stepIm = Math.sin(angle);
      for (int start = 0; start < n; start += size) {
        double twiddleRe = 1; // w^0
        double twiddleIm = 0;
        for (int k = 0; k < half; k++) {
          int top = start + k;
          int bottom = top + half;
          // élément du bas multiplié par le twiddle
          double rotatedRe = real[bottom] * twiddleRe - imag[bottom] * twiddleIm;
          double rotatedIm = real[bottom] * twiddleIm + imag[bottom] * twiddleRe;
          double topRe = real[top];
          double topIm = imag[top];
          real[top] = topRe + rotatedRe;
          imag[top] = topIm + rotatedIm;
          real[bottom] = topRe - rotatedRe;
          imag[bottom] = topIm - rotatedIm;
          // twiddle <- twiddle * step (rotation d'un cran pour le k suivant)
          double nextTwiddleRe = twiddleRe * stepRe - twiddleIm * stepIm;
          twiddleIm = twiddleRe * stepIm + twiddleIm * stepRe;
          twiddleRe = nextTwiddleRe;
        }
      }
    }
  }

  private static void swap(double[] values, int i, int j) {
    double tmp = values[i];
    values[i] = values[j];
    values[j] = tmp;
  }
}
