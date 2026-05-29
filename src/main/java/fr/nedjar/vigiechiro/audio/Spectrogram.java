package fr.nedjar.vigiechiro.audio;

/**
 * Transformée de Fourier à court terme (STFT) produisant une matrice de magnitudes en décibels,
 * prête à être convertie en image pour le rendu du spectrogramme. Une fenêtre de Hann est appliquée
 * avant chaque FFT pour limiter les fuites spectrales.
 */
final class Spectrogram {

  private final float[][] magnitudesDb; // [trame][bin], bin 0 .. fftSize/2
  private final int hop;
  private final int fftSize;
  private final float sampleRate;

  private Spectrogram(float[][] magnitudesDb, int hop, int fftSize, float sampleRate) {
    this.magnitudesDb = magnitudesDb;
    this.hop = hop;
    this.fftSize = fftSize;
    this.sampleRate = sampleRate;
  }

  static Spectrogram compute(AudioSample sample, int fftSize, int hop) {
    float[] x = sample.samples();
    int bins = fftSize / 2 + 1;
    int frames = Math.max(1, 1 + Math.max(0, x.length - fftSize) / hop);
    float[][] out = new float[frames][bins];
    double[] window = hann(fftSize);
    double[] re = new double[fftSize];
    double[] im = new double[fftSize];

    for (int f = 0; f < frames; f++) {
      int start = f * hop;
      for (int i = 0; i < fftSize; i++) {
        int idx = start + i;
        double s = idx < x.length ? x[idx] : 0.0;
        re[i] = s * window[i];
        im[i] = 0.0;
      }
      Fft.transform(re, im);
      for (int b = 0; b < bins; b++) {
        double mag = Math.hypot(re[b], im[b]) / fftSize;
        out[f][b] = (float) (20.0 * Math.log10(mag + 1e-9));
      }
    }
    return new Spectrogram(out, hop, fftSize, sample.sampleRate());
  }

  private static double[] hann(int n) {
    double[] w = new double[n];
    for (int i = 0; i < n; i++) {
      w[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)));
    }
    return w;
  }

  int frameCount() {
    return magnitudesDb.length;
  }

  int binCount() {
    return magnitudesDb.length == 0 ? 0 : magnitudesDb[0].length;
  }

  float magnitudeDb(int frame, int bin) {
    return magnitudesDb[frame][bin];
  }
}
