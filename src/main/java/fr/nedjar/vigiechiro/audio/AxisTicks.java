package fr.nedjar.vigiechiro.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Calcule la liste des graduations (« ticks ») d'un axe rectiligne, en valeur et en position pixel
 * : pour une plage {@code [minValue, maxValue]} affichée sur une longueur {@code lengthPx}, énumère
 * les multiples de {@code step} compris dans cette plage et leur position normalisée {@code (value
 * - min) / (max - min) * length}.
 *
 * <p>Pur, sans dépendance au graphe de scène : testable isolément. L'appelant choisit le pas (via
 * {@link AudioViewModel#niceStep}) et le format des libellés (via {@link
 * AudioViewModel#formatAxis}, en kHz pour l'axe fréquence, en s pour l'axe temps). Il décide aussi
 * de l'orientation (les freq vont du bas vers le haut : {@code y = lengthPx - tick.positionPx()}).
 * Issue #13.
 */
final class AxisTicks {

  private AxisTicks() {}

  /** Position en pixels et valeur métier d'une graduation. */
  record Tick(double positionPx, double value) {}

  /**
   * Énumère les ticks de la plage {@code [minValue, maxValue]} au pas {@code step}, sur une
   * longueur {@code lengthPx}. Retourne une liste vide en cas de paramètres dégénérés ({@code step
   * ≤ 0} ou {@code lengthPx ≤ 0}).
   */
  static List<Tick> compute(double minValue, double maxValue, double step, double lengthPx) {
    if (step <= 0 || lengthPx <= 0) {
      return List.of();
    }
    double range = maxValue - minValue;
    int firstIndex = (int) Math.ceil(minValue / step);
    int lastIndex = (int) Math.floor(maxValue / step);
    List<Tick> ticks = new ArrayList<>(Math.max(0, lastIndex - firstIndex + 1));
    for (int i = firstIndex; i <= lastIndex; i++) {
      double value = i * step;
      double frac = range > 0 ? (value - minValue) / range : 0;
      ticks.add(new Tick(lengthPx * frac, value));
    }
    return ticks;
  }
}
