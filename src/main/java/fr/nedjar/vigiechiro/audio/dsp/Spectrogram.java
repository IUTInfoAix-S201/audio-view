package fr.nedjar.vigiechiro.audio.dsp;

/**
 * Transformée de Fourier à court terme (STFT) : découpe le signal en trames successives de {@code
 * fftSize} échantillons espacées de {@code hop} ({@code hop = fftSize / 4} → recouvrement de 75 %),
 * applique une fenêtre de Hann (limite les fuites spectrales aux bords de trame), exécute la FFT et
 * stocke la magnitude de chaque bin en dBFS. Le résultat est une matrice {@code [trame][bin]} prête
 * à être convertie en image pour le rendu du spectrogramme.
 *
 * <p>Conventions : {@code fftSize} doit être une puissance de deux (contrainte de {@link Fft}) ; le
 * bin 0 correspond au DC, le bin {@code fftSize/2} à la fréquence de Nyquist.
 */
public final class Spectrogram {

  /**
   * Plancher ajouté à la magnitude linéaire avant {@code log10} pour éviter {@code log10(0) = -∞}
   * sur les bins silencieux. Choisi pour que {@code 20·log10(1e-9) ≈ -180 dBFS} : assez bas pour
   * rester sous tout seuil d'affichage et assez haut pour produire un float fini.
   */
  private static final double FLOOR_ANTI_LOG_ZERO = 1e-9;

  /** Conversion d'une magnitude linéaire en dBFS : {@code 20·log10(magnitude)}. */
  private static final double DBFS_FACTOR = 20.0;

  /** Matrice {@code [trame][bin]}, bin {@code 0 .. fftSize / 2}. */
  private final float[][] magnitudesDb;

  private Spectrogram(float[][] magnitudesDb) {
    this.magnitudesDb = magnitudesDb;
  }

  /**
   * Calcule la STFT du signal. Trois étapes par trame : <b>(1)</b> extraire {@code fftSize}
   * échantillons à partir de {@code frame · hop} et les pondérer par la fenêtre de Hann ;
   * <b>(2)</b> exécuter la FFT en place ; <b>(3)</b> convertir chaque coefficient en magnitude dBFS
   * (avec plancher anti-log(0)). Si le signal est plus court que {@code fftSize}, une trame unique
   * paddée à zéro est produite.
   */
  public static Spectrogram compute(AudioSample sample, int fftSize, int hop) {
    float[] samples = sample.samples();
    int frames = countFrames(samples.length, fftSize, hop);
    int bins = fftSize / 2 + 1;
    double[] hannWindow = hannWindow(fftSize);
    double[] realBuf = new double[fftSize];
    double[] imagBuf = new double[fftSize];
    float[][] magnitudesDb = new float[frames][bins];

    for (int frame = 0; frame < frames; frame++) {
      int start = frame * hop;
      extractWindowedFrame(samples, start, hannWindow, realBuf, imagBuf);
      Fft.transform(realBuf, imagBuf);
      writeMagnitudesDb(realBuf, imagBuf, bins, fftSize, magnitudesDb[frame]);
    }
    return new Spectrogram(magnitudesDb);
  }

  /**
   * Nombre de trames pour un signal donné — formule dense de la STFT : 1 trame de base (paddée si
   * besoin), plus une trame par pas {@code hop} après les premières {@code fftSize} échantillons.
   * Toujours au moins 1.
   */
  static int countFrames(int sampleLength, int fftSize, int hop) {
    return Math.max(1, 1 + Math.max(0, sampleLength - fftSize) / hop);
  }

  /**
   * Copie {@code fftSize} échantillons à partir de {@code start} dans {@code realBuf} en
   * multipliant chacun par le coefficient de fenêtre correspondant ; les positions au-delà du
   * signal sont paddées à zéro. {@code imagBuf} est remis à zéro (entrée purement réelle).
   */
  private static void extractWindowedFrame(
      float[] samples, int start, double[] window, double[] realBuf, double[] imagBuf) {
    int fftSize = window.length;
    for (int i = 0; i < fftSize; i++) {
      int idx = start + i;
      double sampleValue = idx < samples.length ? samples[idx] : 0.0;
      realBuf[i] = sampleValue * window[i];
      imagBuf[i] = 0.0;
    }
  }

  /**
   * Convertit les {@code bins} premiers coefficients complexes {@code (re, im)} de la FFT en
   * magnitudes dBFS et les écrit dans {@code targetRow}. La normalisation par {@code fftSize} rend
   * les amplitudes indépendantes de la taille de FFT.
   */
  private static void writeMagnitudesDb(
      double[] re, double[] im, int bins, int fftSize, float[] targetRow) {
    for (int bin = 0; bin < bins; bin++) {
      double magnitudeLinear = Math.hypot(re[bin], im[bin]) / fftSize;
      targetRow[bin] = (float) toDecibels(magnitudeLinear);
    }
  }

  /**
   * Magnitude linéaire → dBFS, avec plancher anti-log(0) : {@code 20 · log10(magnitudeLinear +
   * 1e-9)}.
   */
  private static double toDecibels(double magnitudeLinear) {
    return DBFS_FACTOR * Math.log10(magnitudeLinear + FLOOR_ANTI_LOG_ZERO);
  }

  /**
   * Fenêtre de Hann de longueur {@code n} : {@code w[i] = 0.5 · (1 - cos(2π · i / (n - 1)))}.
   * Réduit les fuites spectrales par rapport à une fenêtre rectangulaire en atténuant
   * progressivement les bords de trame.
   */
  private static double[] hannWindow(int n) {
    double[] window = new double[n];
    for (int i = 0; i < n; i++) {
      window[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)));
    }
    return window;
  }

  /** Nombre de trames dans la STFT. */
  public int frameCount() {
    return magnitudesDb.length;
  }

  /** Nombre de bins par trame ({@code fftSize / 2 + 1} ; bin 0 = DC, dernier = Nyquist). */
  public int binCount() {
    return magnitudesDb.length == 0 ? 0 : magnitudesDb[0].length;
  }

  /** Magnitude en dBFS du bin {@code bin} de la trame {@code frame}. */
  public float magnitudeDb(int frame, int bin) {
    return magnitudesDb[frame][bin];
  }
}
