package fr.nedjar.vigiechiro.audio;

import javafx.scene.paint.Color;

/**
 * Palette d'intensités utilisée pour le spectrogramme : mappe une intensité normalisée {@code t}
 * dans [0, 1] vers une {@link Color}, par interpolation linéaire entre des arrêts (stops).
 *
 * <p>Deux variantes selon le thème : {@link #SOMBRE} (fond noir → rampe magma) et {@link #CLAIR}
 * (fond blanc cassé → bleu/violet, assortie au thème clair).
 *
 * <p>Découplée du rendu (qui appelle simplement {@code at(t)}), elle est testable isolément
 * (extrémités, continuité, monotonie de luminance). Issue #12.
 */
enum Colormap {
  /**
   * Rampe sombre par défaut : fond ~#0b0f14 (identique au fond CSS de {@code
   * .audio-view-plot-area}) → bleu → magenta → jaune (style magma). Le stop 0 colle au fond pour
   * que les zones silencieuses du spectrogramme se fondent dans le fond du composant.
   */
  SOMBRE(
      new double[][] {
        {0.00, 0.043, 0.059, 0.078},
        {0.35, 0.20, 0.05, 0.45},
        {0.70, 0.85, 0.15, 0.35},
        {1.00, 1.00, 0.95, 0.30}
      }),

  /** Rampe claire : blanc cassé (assorti au fond CSS du thème clair) → bleu → violet sombre. */
  CLAIR(
      new double[][] {
        {0.00, 0.957, 0.965, 0.973},
        {0.40, 0.45, 0.70, 0.90},
        {0.75, 0.15, 0.30, 0.70},
        {1.00, 0.25, 0.00, 0.35}
      });

  // Chaque ligne : {position dans [0,1], R, G, B}. Stops triés par position croissante.
  private final double[][] stops;

  Colormap(double[][] stops) {
    this.stops = stops;
  }

  /**
   * Couleur correspondant à l'intensité normalisée {@code t} dans [0, 1], par interpolation
   * linéaire entre les stops encadrants. Au-delà des bornes, la couleur du stop terminal est
   * retournée.
   */
  Color at(double t) {
    for (int i = 1; i < stops.length; i++) {
      if (t <= stops[i][0]) {
        double[] lo = stops[i - 1];
        double[] hi = stops[i];
        double f = (t - lo[0]) / (hi[0] - lo[0]);
        return Color.color(
            lo[1] + f * (hi[1] - lo[1]), lo[2] + f * (hi[2] - lo[2]), lo[3] + f * (hi[3] - lo[3]));
      }
    }
    double[] last = stops[stops.length - 1];
    return Color.color(last[1], last[2], last[3]);
  }
}
