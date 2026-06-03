package fr.nedjar.vigiechiro.audio;

import javafx.scene.control.Label;
import javafx.scene.shape.Line;

/**
 * Helpers de construction de nœuds d'axes — extraits d'{@link AudioView} pour être partagés par
 * {@link SonogramView} et {@link SpectrogramView} (issue #9). Toutes les méthodes sont statiques et
 * stylent les nœuds via les classes CSS du composant.
 */
final class AxisNodes {

  private AxisNodes() {}

  /** Ligne de grille de fond (sous les tracés), classe CSS {@code audio-view-grid}. */
  static Line gridLine(double x1, double y1, double x2, double y2) {
    Line line = new Line(x1, y1, x2, y2);
    line.getStyleClass().add("audio-view-grid");
    line.setManaged(false);
    line.setMouseTransparent(true);
    return line;
  }

  /** Tick d'axe (petit segment perpendiculaire), classe CSS {@code audio-view-tick}. */
  static Line tickLine(double x1, double y1, double x2, double y2) {
    Line line = new Line(x1, y1, x2, y2);
    line.getStyleClass().add("audio-view-tick");
    line.setManaged(false);
    line.setMouseTransparent(true);
    return line;
  }

  /** Étiquette d'axe, classe CSS {@code audio-view-axis-label}. */
  static Label axisLabel(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("audio-view-axis-label");
    label.setMouseTransparent(true);
    return label;
  }

  /** Positionne un curseur de lecture vertical (et le masque si {@code show=false}). */
  static void positionCursor(Line line, boolean show, double x, double y0, double y1) {
    line.setVisible(show);
    if (show) {
      line.setStartX(x);
      line.setEndX(x);
      line.setStartY(y0);
      line.setEndY(y1);
    }
  }

  static double clamp(double value, double lo, double hi) {
    return Math.max(lo, Math.min(hi, value));
  }
}
