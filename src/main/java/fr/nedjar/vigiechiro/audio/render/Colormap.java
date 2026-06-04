package fr.nedjar.vigiechiro.audio.render;

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
public enum Colormap {
    /**
     * Rampe sombre par défaut, fidèle à « inferno » de matplotlib (violet profond → magenta → orange
     * → jaune pâle), perceptuellement uniforme. Le stop 0 est forcé sur le fond du composant ~#0b0f14
     * (identique au fond CSS de {@code .audio-view-plot-area}) pour que le silence s'y fonde ; la
     * plage basse-médiane monte vite en luminance, ce qui fait ressortir les signaux faibles — là où
     * l'ancienne rampe 4 stops les noyait dans un violet trop sombre.
     */
    SOMBRE(new double[][] {
        {0.00, 0.043, 0.059, 0.078}, // #0b0f14 fond du composant (le silence s'y fond)
        {0.14, 0.110, 0.046, 0.230}, // violet profond
        {0.28, 0.290, 0.047, 0.420}, // violet
        {0.42, 0.473, 0.111, 0.428}, // #781c6d magenta-violet
        {0.56, 0.683, 0.190, 0.361}, // #bb3754 magenta-rouge
        {0.70, 0.865, 0.317, 0.226}, // #ed6925 orange
        {0.85, 0.968, 0.561, 0.120}, // orange-jaune
        {1.00, 0.988, 0.998, 0.645} // #fcffa4 jaune pâle
    }),

    /** Rampe claire : blanc cassé (assorti au fond CSS du thème clair) → bleu → violet sombre. */
    CLAIR(new double[][] {
        {0.00, 0.957, 0.965, 0.973},
        {0.40, 0.45, 0.70, 0.90},
        {0.75, 0.15, 0.30, 0.70},
        {1.00, 0.25, 0.00, 0.35}
    }),

    /**
     * Variante daltonien (issue #24) pour le <b>thème sombre</b> : teintes « Viridis » de matplotlib
     * (lisibles avec deutéranopie/protanopie/tritanopie), mais <b>ancrées sur le fond ~#0b0f14</b> au
     * stop 0 — le silence se fond dans le fond et les signaux faibles ressortent (contraste élevé),
     * là où le bas violet de Viridis pur les noyait. Monotone en luminance perçue.
     */
    VIRIDIS(new double[][] {
        {0.00, 0.043, 0.059, 0.078}, // #0b0f14 fond sombre (silence)
        {0.10, 0.267, 0.005, 0.329}, // #440154 violet sombre
        {0.22, 0.283, 0.141, 0.458}, // #482a74
        {0.34, 0.254, 0.265, 0.530}, // #414487
        {0.46, 0.207, 0.372, 0.553}, // #355e8d bleu
        {0.58, 0.128, 0.567, 0.551}, // #21918c teal
        {0.70, 0.135, 0.659, 0.518}, // #22a884 vert
        {0.85, 0.478, 0.821, 0.318}, // #7ad151 vert clair
        {1.00, 0.993, 0.906, 0.144} // #fde725 jaune
    }),

    /**
     * Variante daltonien (issue #24) pour le <b>thème clair</b> : ancrée sur le fond clair ~#eef1f4
     * (le silence se fond) puis descend en luminance par des teintes CVD-safe (bleu clair → teal →
     * bleu → violet sombre) jusqu'au signal fort. Monotone décroissante en luminance, cohérente avec
     * la chrome claire — contrairement au Viridis sombre qui jurait dans une fenêtre claire.
     */
    VIRIDIS_CLAIR(new double[][] {
        {0.00, 0.933, 0.945, 0.957}, // ~#eef1f4 fond clair (silence)
        {0.25, 0.470, 0.700, 0.800}, // bleu clair
        {0.50, 0.130, 0.520, 0.620}, // teal-bleu
        {0.75, 0.180, 0.280, 0.520}, // bleu sombre
        {1.00, 0.267, 0.005, 0.329} // #440154 violet sombre (signal fort)
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
    public Color at(double t) {
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
