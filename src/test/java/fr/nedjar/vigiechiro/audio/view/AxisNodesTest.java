package fr.nedjar.vigiechiro.audio.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Teste la logique pure de projection temps→pixel du surlignage ({@link AxisNodes#highlightSpan})
 * sans toolkit JavaFX (issue #52). Même repère que le curseur : {@code x = axisLeft + rel · plotW}.
 */
class AxisNodesTest {

    @Test
    void spanFenetreEntierementVisible() {
        // fenêtre visible [0, 10] s, surlignage [2, 4] s, gouttière 48, zone 200 px
        // r0 = 0.2 -> x0 = 48 + 40 = 88 ; r1 = 0.4 -> x1 = 48 + 80 = 128
        double[] span = AxisNodes.highlightSpan(2, 4, 0, 10, 48, 200);
        assertThat(span).isNotNull();
        assertThat(span[0]).isCloseTo(88, within(1e-9));
        assertThat(span[1]).isCloseTo(128, within(1e-9));
    }

    @Test
    void spanPartiellementVisibleEstRecadre() {
        // surlignage [8, 12] déborde à droite : clampé à [8, 10] -> r0 = 0.8, r1 = 1.0
        double[] span = AxisNodes.highlightSpan(8, 12, 0, 10, 48, 200);
        assertThat(span[0]).isCloseTo(48 + 160, within(1e-9));
        assertThat(span[1]).isCloseTo(48 + 200, within(1e-9));
    }

    @Test
    void spanEntierementHorsChampRenvoieNull() {
        assertThat(AxisNodes.highlightSpan(-5, -1, 0, 10, 48, 200)).isNull(); // à gauche
        assertThat(AxisNodes.highlightSpan(11, 15, 0, 10, 48, 200)).isNull(); // à droite
    }

    @Test
    void spanEntreeInvalideRenvoieNull() {
        assertThat(AxisNodes.highlightSpan(4, 2, 0, 10, 48, 200)).isNull(); // fin <= début
        assertThat(AxisNodes.highlightSpan(2, 4, 0, 0, 48, 200)).isNull(); // fenêtre nulle
        assertThat(AxisNodes.highlightSpan(2, 4, 0, 10, 48, 0)).isNull(); // zone nulle
    }
}
