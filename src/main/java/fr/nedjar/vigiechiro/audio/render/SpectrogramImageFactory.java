package fr.nedjar.vigiechiro.audio.render;

import fr.nedjar.vigiechiro.audio.dsp.Spectrogram;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

/**
 * Construit la {@link WritableImage} d'un {@link Spectrogram} pour une {@link Colormap} donnée :
 * normalisation des magnitudes en dB sur la plage {@code [minDb, maxDb]} puis projection à travers
 * la colormap, basses fréquences en bas (origine de l'image en haut). Issue #11.
 *
 * <p>Toolkit-bound (instancie {@link WritableImage}) : couvert par {@link
 * SpectrogramImageFactoryToolkitTest} (TestFX initialise le toolkit).
 */
public final class SpectrogramImageFactory {

  private SpectrogramImageFactory() {}

  /**
   * Construit l'image du spectrogramme. La hauteur de l'image est le nombre de bins (haute
   * fréquence en haut), la largeur est le nombre de trames.
   *
   * @throws IllegalArgumentException si {@code minDb >= maxDb} (plage de normalisation invalide)
   */
  public static WritableImage build(
      Spectrogram spec, Colormap colormap, double minDb, double maxDb) {
    if (minDb >= maxDb) {
      throw new IllegalArgumentException("minDb doit être strictement inférieur à maxDb");
    }
    int w = Math.max(1, spec.frameCount());
    int h = Math.max(1, spec.binCount());
    WritableImage img = new WritableImage(w, h);
    PixelWriter pw = img.getPixelWriter();
    double range = maxDb - minDb;
    for (int x = 0; x < spec.frameCount(); x++) {
      for (int y = 0; y < h; y++) {
        int bin = h - 1 - y; // basses fréquences en bas
        double db = spec.magnitudeDb(x, bin);
        double norm = (db - minDb) / range;
        norm = Math.max(0, Math.min(1, norm));
        pw.setColor(x, y, colormap.at(norm));
      }
    }
    return img;
  }
}
