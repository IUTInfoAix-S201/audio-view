package fr.nedjar.vigiechiro.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import fr.nedjar.vigiechiro.audio.AxisTicks.Tick;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Vérifie {@link AxisTicks#compute} : ticks aux multiples du pas dans la plage, positions
 * proportionnelles à la longueur, et robustesse aux cas dégénérés. Pur, sans toolkit.
 */
class AxisTicksTest {

  @Test
  void grilleRegulierePartantDuMin() {
    List<Tick> ticks = AxisTicks.compute(0, 10, 2, 100);
    assertThat(ticks).hasSize(6);
    assertThat(ticks.get(0).value()).isEqualTo(0.0);
    assertThat(ticks.get(0).positionPx()).isEqualTo(0.0);
    assertThat(ticks.get(5).value()).isEqualTo(10.0);
    assertThat(ticks.get(5).positionPx()).isCloseTo(100.0, within(1e-9));
    assertThat(ticks.get(3).value()).isEqualTo(6.0);
    assertThat(ticks.get(3).positionPx()).isCloseTo(60.0, within(1e-9));
  }

  @Test
  void bornesNonAligneesSurLePasDemarrentAuMultipleSuivant() {
    // [0.5, 9.5], pas 2 → firstIndex=ceil(0.25)=1, lastIndex=floor(4.75)=4 → ticks à 2, 4, 6, 8.
    List<Tick> ticks = AxisTicks.compute(0.5, 9.5, 2, 90);
    assertThat(ticks).hasSize(4);
    assertThat(ticks.get(0).value()).isEqualTo(2.0);
    // position = (2 - 0.5) / 9 * 90 = 15
    assertThat(ticks.get(0).positionPx()).isCloseTo(15.0, within(1e-9));
    assertThat(ticks.get(3).value()).isEqualTo(8.0);
    assertThat(ticks.get(3).positionPx()).isCloseTo(75.0, within(1e-9));
  }

  @Test
  void plageNulleDonneUneSeuleGraduationEnPositionZero() {
    List<Tick> ticks = AxisTicks.compute(5, 5, 1, 100);
    assertThat(ticks).hasSize(1);
    assertThat(ticks.get(0).value()).isEqualTo(5.0);
    assertThat(ticks.get(0).positionPx()).isEqualTo(0.0);
  }

  @Test
  void valeursNegativesGereesCorrectement() {
    // [-1, 1] au pas 0.5 → ceil(-2)=-2, floor(2)=2 → 5 ticks : -1, -0.5, 0, 0.5, 1
    List<Tick> ticks = AxisTicks.compute(-1, 1, 0.5, 100);
    assertThat(ticks).hasSize(5);
    assertThat(ticks.get(0).value()).isCloseTo(-1.0, within(1e-9));
    assertThat(ticks.get(0).positionPx()).isCloseTo(0.0, within(1e-9));
    assertThat(ticks.get(2).value()).isCloseTo(0.0, within(1e-9));
    assertThat(ticks.get(2).positionPx()).isCloseTo(50.0, within(1e-9));
    assertThat(ticks.get(4).value()).isCloseTo(1.0, within(1e-9));
    assertThat(ticks.get(4).positionPx()).isCloseTo(100.0, within(1e-9));
  }

  @Test
  void parametresDegeneresRetournentListeVide() {
    assertThat(AxisTicks.compute(0, 10, 0, 100)).isEmpty();
    assertThat(AxisTicks.compute(0, 10, -1, 100)).isEmpty();
    assertThat(AxisTicks.compute(0, 10, 1, 0)).isEmpty();
    assertThat(AxisTicks.compute(0, 10, 1, -1)).isEmpty();
  }
}
