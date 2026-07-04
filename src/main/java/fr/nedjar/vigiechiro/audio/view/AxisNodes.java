package fr.nedjar.vigiechiro.audio.view;

import javafx.scene.control.Label;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/**
 * Helpers de construction de nœuds d'axes — extraits d'{@link AudioView} pour être partagés par
 * {@link SonogramView} et {@link SpectrogramView} (issue #9). Toutes les méthodes sont statiques et
 * stylent les nœuds via les classes CSS du composant.
 */
public final class AxisNodes {

    private AxisNodes() {}

    /** Ligne de grille de fond (sous les tracés), classe CSS {@code audio-view-grid}. */
    public static Line gridLine(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.getStyleClass().add("audio-view-grid");
        line.setManaged(false);
        line.setMouseTransparent(true);
        return line;
    }

    /** Tick d'axe (petit segment perpendiculaire), classe CSS {@code audio-view-tick}. */
    public static Line tickLine(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.getStyleClass().add("audio-view-tick");
        line.setManaged(false);
        line.setMouseTransparent(true);
        return line;
    }

    /** Étiquette d'axe, classe CSS {@code audio-view-axis-label}. */
    public static Label axisLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("audio-view-axis-label");
        label.setMouseTransparent(true);
        return label;
    }

    /** Positionne un curseur de lecture vertical (et le masque si {@code show=false}). */
    public static void positionCursor(Line line, boolean show, double x, double y0, double y1) {
        line.setVisible(show);
        if (show) {
            line.setStartX(x);
            line.setEndX(x);
            line.setStartY(y0);
            line.setEndY(y1);
        }
    }

    /**
     * Bornes horizontales {@code [x0, x1]} (px) d'une fenêtre temporelle {@code [hStart, hEnd]} (temps
     * fichier) projetée sur la fenêtre visible {@code [windowStart, windowStart + windowDuration]} et
     * recadrée à la zone de tracé {@code [axisLeft, axisLeft + plotW]}. Même conversion temps→pixel que
     * {@link #positionCursor}. Renvoie {@code null} si la fenêtre est invalide ou entièrement hors champ
     * (issue #52).
     */
    public static double[] highlightSpan(
            double hStart, double hEnd, double windowStart, double windowDuration, double axisLeft, double plotW) {
        if (windowDuration <= 0 || plotW <= 0 || hEnd <= hStart) {
            return null;
        }
        double r0 = clamp((hStart - windowStart) / windowDuration, 0, 1);
        double r1 = clamp((hEnd - windowStart) / windowDuration, 0, 1);
        if (r1 <= r0) {
            return null; // fenêtre entièrement à gauche ou à droite du champ visible
        }
        return new double[] {axisLeft + r0 * plotW, axisLeft + r1 * plotW};
    }

    /**
     * Positionne un rectangle de surlignage sur {@code [xspan[0], xspan[1]] × [y, y + height]} (et le
     * masque si {@code xspan} est null ou la hauteur nulle). Issue #52.
     */
    public static void positionHighlight(Rectangle rect, double[] xspan, double y, double height) {
        boolean show = xspan != null && height > 0;
        rect.setVisible(show);
        if (show) {
            rect.setX(xspan[0]);
            rect.setWidth(Math.max(0, xspan[1] - xspan[0]));
            rect.setY(y);
            rect.setHeight(height);
        }
    }

    public static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
