package fr.nedjar.vigiechiro.audio.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

/**
 * Teste la logique pure du {@link SpectrogramViewModel} (calcul du viewport) et le câblage des
 * façades sur la session. Sans toolkit ({@link Rectangle2D} est une valeur géométrique). Issue #9.
 */
class SpectrogramViewModelTest {

    @Test
    void viewportZoomTemporel() {
        // zoom 1 (vue complète) : recadrage = image entière en largeur, tranche basse complète en
        // hauteur (zoom freq 1).
        Rectangle2D r = SpectrogramViewModel.viewport(10, 0, 10, 1, 1000, 500);
        assertThat(r.getMinX()).isZero();
        assertThat(r.getWidth()).isCloseTo(1000, within(1e-9));
        assertThat(r.getMinY()).isZero();
        assertThat(r.getHeight()).isCloseTo(500, within(1e-9));
    }

    @Test
    void viewportFenetreCentree() {
        // fenêtre de 2 s sur 10 s totale, centrée sur t = 5 -> start = 4
        // x = 4/10 * 1000 = 400, largeur = 2/10 * 1000 = 200
        Rectangle2D r = SpectrogramViewModel.viewport(10, 4, 2, 1, 1000, 500);
        assertThat(r.getMinX()).isCloseTo(400, within(1e-9));
        assertThat(r.getWidth()).isCloseTo(200, within(1e-9));
    }

    @Test
    void viewportZoomFrequenceTrancheBasse() {
        // zoom freq 4 -> hauteur visible = 500 / 4 = 125, démarre en bas (y = 500 - 125 = 375)
        Rectangle2D r = SpectrogramViewModel.viewport(10, 0, 10, 4, 1000, 500);
        assertThat(r.getMinY()).isCloseTo(375, within(1e-9));
        assertThat(r.getHeight()).isCloseTo(125, within(1e-9));
    }

    @Test
    void viewportDureeNulleAfficheToutEnLargeur() {
        Rectangle2D r = SpectrogramViewModel.viewport(0, 0, 0, 1, 1000, 500);
        assertThat(r.getMinX()).isZero();
        assertThat(r.getWidth()).isCloseTo(1000, within(1e-9));
    }

    @Test
    void viewportImageDegenereeRenvoieNull() {
        assertThat(SpectrogramViewModel.viewport(10, 0, 10, 1, 0, 500)).isNull();
        assertThat(SpectrogramViewModel.viewport(10, 0, 10, 1, 1000, 0)).isNull();
    }

    @Test
    void viewportZoomFrequenceBorneA1() {
        // zoom < 1 traité comme 1 (cas d'entrée incorrect)
        Rectangle2D r = SpectrogramViewModel.viewport(10, 0, 10, 0.5, 1000, 500);
        assertThat(r.getHeight()).isCloseTo(500, within(1e-9));
    }

    @Test
    void facadesDeleguentALaSession() {
        AudioViewModel session = new AudioViewModel();
        SpectrogramViewModel vm = new SpectrogramViewModel(session);
        assertThat(vm.duration()).isEqualTo(session.getDuration());
        assertThat(vm.frequencyZoom()).isEqualTo(session.getFrequencyZoom());
        assertThat(vm.windowStart()).isEqualTo(session.windowStart());
        assertThat(vm.windowDuration()).isEqualTo(session.windowDuration());
        assertThat(vm.expansionFactor()).isEqualTo(session.expansionFactor());
        assertThat(vm.currentTime()).isEqualTo(session.getCurrentTime());
        assertThat(vm.getSample()).isSameAs(session.getSample());
        assertThat(vm.getSpectrogramImage()).isSameAs(session.getSpectrogramImage());
        assertThat(vm.sampleProperty()).isSameAs(session.sampleProperty());
        assertThat(vm.spectrogramImageProperty()).isSameAs(session.spectrogramImageProperty());
        assertThat(vm.currentTimeProperty()).isSameAs(session.currentTimeProperty());
        assertThat(vm.durationProperty()).isSameAs(session.durationProperty());
        assertThat(vm.frequencyZoomProperty()).isSameAs(session.frequencyZoomProperty());
        assertThat(vm.windowStartBinding()).isSameAs(session.windowStartBinding());
        assertThat(vm.windowDurationBinding()).isSameAs(session.windowDurationBinding());
    }

    @Test
    void viewportInstanceUtiliseLEtatDeLaSession() {
        AudioViewModel session = new AudioViewModel();
        SpectrogramViewModel vm = new SpectrogramViewModel(session);
        // session vierge : duration = 0 -> viewport en largeur complète, zoom 1
        Rectangle2D r = vm.viewport(800, 400);
        assertThat(r.getMinX()).isZero();
        assertThat(r.getWidth()).isCloseTo(800, within(1e-9));
        assertThat(r.getMinY()).isZero();
        assertThat(r.getHeight()).isCloseTo(400, within(1e-9));
    }
}
