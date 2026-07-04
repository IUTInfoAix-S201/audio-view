package fr.nedjar.vigiechiro.audio.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Teste la logique pure du {@link SonogramViewModel} (amplitudes, pas de graduation) ainsi que le
 * câblage des façades sur la session. Sans toolkit (la sous-VM est pure Java). Issue #9.
 */
class SonogramViewModelTest {

    @Test
    void picVisibleEstInverseDeLAutoEchelle() {
        AudioViewModel session = new AudioViewModel();
        SonogramViewModel vm = new SonogramViewModel(session);
        // sonoScale par défaut = 1 -> pic visible = 1
        assertThat(vm.amplitudePeak()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void picVisibleNulSiFichierMuet() {
        // sonoScale <= 0 (cas dégénéré, fichier muet) -> formule protégée renvoie 0.
        assertThat(SonogramViewModel.amplitudePeak(0)).isZero();
        assertThat(SonogramViewModel.amplitudePeak(-1)).isZero();
        // amplitudeStep retombe alors sur le step par défaut (range 0 -> niceStep renvoie 1).
        assertThat(SonogramViewModel.amplitudeStep(0)).isEqualTo(1);
    }

    @Test
    void stepDAmplitudeEstRondAutourDuPic() {
        // sonoScale = 1 -> pic = 1 -> range 2 -> niceStep(2, 4) = 0.5
        assertThat(SonogramViewModel.amplitudeStep(1)).isEqualTo(0.5);
        // sonoScale = 10 -> pic = 0.1 -> range 0.2 -> niceStep(0.2, 4) = 0.05
        assertThat(SonogramViewModel.amplitudeStep(10)).isCloseTo(0.05, within(1e-9));
    }

    @Test
    void facadesDeleguentALaSession() {
        AudioViewModel session = new AudioViewModel();
        SonogramViewModel vm = new SonogramViewModel(session);
        assertThat(vm.windowStart()).isEqualTo(session.windowStart());
        assertThat(vm.windowDuration()).isEqualTo(session.windowDuration());
        assertThat(vm.expansionFactor()).isEqualTo(session.expansionFactor());
        assertThat(vm.sonoScale()).isEqualTo(session.getSonoScale());
        assertThat(vm.currentTime()).isEqualTo(session.getCurrentTime());
        assertThat(vm.getSample()).isSameAs(session.getSample());
        assertThat(vm.sampleProperty()).isSameAs(session.sampleProperty());
        assertThat(vm.sonoScaleProperty()).isSameAs(session.sonoScaleProperty());
        assertThat(vm.currentTimeProperty()).isSameAs(session.currentTimeProperty());
        assertThat(vm.windowStartBinding()).isSameAs(session.windowStartBinding());
        assertThat(vm.windowDurationBinding()).isSameAs(session.windowDurationBinding());
        assertThat(vm.highlightWindowFileBinding()).isSameAs(session.highlightWindowFileBinding());
        assertThat(vm.highlightWindowFile()).isEqualTo(session.highlightWindowFile());
    }

    @Test
    void seekDelegueALaSession() {
        AudioViewModel session = new AudioViewModel();
        // duration = 0 par défaut -> seek est no-op (cf. PlaybackModel) ; on vérifie juste que l'appel
        // ne lève pas et que currentTime reste cohérent.
        SonogramViewModel vm = new SonogramViewModel(session);
        vm.seek(1.0);
        assertThat(session.getCurrentTime()).isZero();
    }

    @Test
    void amplitudePeakSurFichierAmplifieDix() {
        // sonoScale = 10 (amplification ×10) → pic visible = 1/10 = 0.1.
        // Tue les mutants qui inverseraient le ratio (10 au lieu de 1/10).
        assertThat(SonogramViewModel.amplitudePeak(10.0)).isCloseTo(0.1, within(1e-12));
    }

    @Test
    void amplitudeStepCalibreSurUnPicDe025() {
        // sonoScale = 4 → pic = 0.25 → range 0.5 → niceStep(0.5, 4) = 0.1.
        // Vérifie la composition pic → step.
        assertThat(SonogramViewModel.amplitudeStep(4)).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void amplitudePeakInstanceLitLaSession() {
        // L'instance lit sonoScale() de la session. sonoScale par défaut = 1 → peak = 1.
        AudioViewModel session = new AudioViewModel();
        SonogramViewModel vm = new SonogramViewModel(session);
        assertThat(vm.amplitudePeak()).isEqualTo(1.0);
        assertThat(vm.amplitudeStep()).isEqualTo(SonogramViewModel.amplitudeStep(1));
    }
}
