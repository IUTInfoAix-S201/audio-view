package fr.nedjar.vigiechiro.audio.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

/**
 * Teste les deux variantes de {@link Colormap} indépendamment du rendu : extrémités, interpolation
 * linéaire entre stops, et monotonie de la luminance (sombre : croît avec t ; clair : décroît).
 */
class ColormapTest {

    @Test
    void extremesCorrespondentAuxPremierEtDernierStops() {
        // SOMBRE : t=0 -> #0b0f14 (assorti au fond CSS), t=1 -> jaune (dernier stop).
        Color sombre0 = Colormap.SOMBRE.at(0);
        assertThat(sombre0.getRed()).isCloseTo(0.043, within(1e-3));
        assertThat(sombre0.getGreen()).isCloseTo(0.059, within(1e-3));
        assertThat(sombre0.getBlue()).isCloseTo(0.078, within(1e-3));

        Color sombre1 = Colormap.SOMBRE.at(1);
        assertThat(sombre1.getRed()).isCloseTo(1.0, within(1e-6));
        assertThat(sombre1.getGreen()).isCloseTo(0.95, within(1e-6));
        assertThat(sombre1.getBlue()).isCloseTo(0.30, within(1e-6));

        // CLAIR : t=0 -> blanc cassé (assorti au fond CSS), t=1 -> violet sombre.
        Color clair0 = Colormap.CLAIR.at(0);
        assertThat(clair0.getRed()).isCloseTo(0.957, within(1e-3));
        assertThat(clair0.getBlue()).isCloseTo(0.973, within(1e-3));

        Color clair1 = Colormap.CLAIR.at(1);
        assertThat(clair1.getRed()).isCloseTo(0.25, within(1e-6));
        assertThat(clair1.getGreen()).isCloseTo(0.00, within(1e-6));
        assertThat(clair1.getBlue()).isCloseTo(0.35, within(1e-6));
    }

    @Test
    void interpolationLineaireEntreLesStops() {
        // SOMBRE : entre stop 0 (0.043, 0.059, 0.078) et stop à t=0.35 (0.20, 0.05, 0.45).
        // À t=0.175 (mi-chemin), chaque composante doit être à la moitié des deux bornes.
        Color mid = Colormap.SOMBRE.at(0.175);
        assertThat(mid.getRed()).isCloseTo((0.043 + 0.20) / 2, within(1e-6));
        assertThat(mid.getGreen()).isCloseTo((0.059 + 0.05) / 2, within(1e-6));
        assertThat(mid.getBlue()).isCloseTo((0.078 + 0.45) / 2, within(1e-6));
    }

    @Test
    void luminanceMonotoneCroissantePourSombre() {
        // Sombre va du noir vers le clair → luminance perçue strictement croissante avec t.
        double y0 = luminance(Colormap.SOMBRE.at(0));
        double y04 = luminance(Colormap.SOMBRE.at(0.4));
        double y08 = luminance(Colormap.SOMBRE.at(0.8));
        double y1 = luminance(Colormap.SOMBRE.at(1));
        assertThat(y0).isLessThan(y04).isLessThan(y08).isLessThan(y1);
    }

    @Test
    void luminanceMonotoneDecroissantePourClair() {
        // Clair va du blanc cassé vers le violet sombre → luminance strictement décroissante.
        double y0 = luminance(Colormap.CLAIR.at(0));
        double y04 = luminance(Colormap.CLAIR.at(0.4));
        double y08 = luminance(Colormap.CLAIR.at(0.8));
        double y1 = luminance(Colormap.CLAIR.at(1));
        assertThat(y0).isGreaterThan(y04).isGreaterThan(y08).isGreaterThan(y1);
    }

    // Luminance perçue ITU-R BT.709, sans toolkit (Color est un simple value object).
    private static double luminance(Color c) {
        return 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
    }
}
