package fr.nedjar.vigiechiro.audio.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Teste le {@link PlaybackModel} sur ses parties sans toolkit JavaFX : seek (clamp + no-op sans
 * fichier), setDuration, reset depuis l'état initial. {@code togglePlay} déclenche {@code
 * AnimationTimer#start()} (toolkit-bound) — couvert indirectement par le smoke TestFX. Issue #14.
 */
class PlaybackModelTest {

    @Test
    void etatInitialEstVide() {
        PlaybackModel m = new PlaybackModel();
        assertThat(m.isPlaying()).isFalse();
        assertThat(m.currentTime()).isZero();
        assertThat(m.duration()).isZero();
    }

    @Test
    void setDurationFixeLaDureeEtRemetLeCurseurAZero() {
        PlaybackModel m = new PlaybackModel();
        m.setDuration(42.5);
        assertThat(m.duration()).isEqualTo(42.5);
        assertThat(m.currentTime()).isZero();
    }

    @Test
    void seekClampDansLesBornes() {
        PlaybackModel m = new PlaybackModel();
        m.setDuration(10);

        m.seek(3.0);
        assertThat(m.currentTime()).isEqualTo(3.0);

        // au-delà de la durée : borné à duration
        m.seek(99);
        assertThat(m.currentTime()).isEqualTo(10);

        // valeur négative : bornée à 0
        m.seek(-5);
        assertThat(m.currentTime()).isZero();
    }

    @Test
    void seekNoOpQuandPasDeFichierCharge() {
        // duration = 0 : aucun fichier chargé, seek ne doit pas faire bouger currentTime.
        PlaybackModel m = new PlaybackModel();
        m.seek(5);
        assertThat(m.currentTime()).isZero();
    }

    @Test
    void resetRemetLeTransportAZero() {
        // depuis l'état initial (playing déjà false), reset ne déclenche pas le listener "playing"
        // qui sollicite AnimationTimer.start() (toolkit-bound) — donc safe en test unitaire.
        PlaybackModel m = new PlaybackModel();
        m.setDuration(8);
        m.seek(4);

        m.reset();

        assertThat(m.isPlaying()).isFalse();
        assertThat(m.duration()).isZero();
        assertThat(m.currentTime()).isZero();
    }

    @Test
    void exposeLesProprietesObservables() {
        // Smoke : les propriétés retournées sont bien câblées aux valeurs internes.
        PlaybackModel m = new PlaybackModel();
        m.setDuration(7);
        assertThat(m.durationProperty().get()).isEqualTo(7);

        m.seek(2);
        assertThat(m.currentTimeProperty().get()).isEqualTo(2);

        assertThat(m.playingProperty().get()).isFalse();
    }

    @Test
    void loopEstFauxParDefaut() {
        // Comportement par défaut inchangé (issue #15).
        PlaybackModel m = new PlaybackModel();
        assertThat(m.isLoop()).isFalse();
    }

    @Test
    void normalisationEstFausseParDefaut() {
        // Comportement par défaut inchangé : audio joué tel quel (issue #32).
        PlaybackModel m = new PlaybackModel();
        assertThat(m.isNormalisation()).isFalse();
    }

    @Test
    void basculeNormalisationSansSourceEstSilencieuse() {
        // Sans fichier chargé, reopen() est un no-op : la bascule ne touche pas au player (toolkit-
        // free, pas de périphérique sollicité) mais met bien à jour la propriété.
        PlaybackModel m = new PlaybackModel();
        assertThatCode(() -> {
                    m.setNormalisation(true);
                    m.setNormalisation(false);
                })
                .doesNotThrowAnyException();
        assertThat(m.isNormalisation()).isFalse();
    }

    @Test
    void gainDbEstNulParDefautEtSuitLaNormalisation() {
        // Gain exposé (dB) : 0 sans source, 0 normalisation OFF, +X dB quand ON. Calcul indépendant
        // du périphérique audio (reopen avale l'échec, updateGainDb tourne quand même). Issue #32.
        PlaybackModel m = new PlaybackModel();
        assertThat(m.normalisationGainDb()).isZero();

        m.loadSource(new float[] {0.1f, -0.05f}, 44_100f); // pic 0,1
        assertThat(m.normalisationGainDb()).isZero(); // normalisation OFF → 0 dB

        m.setNormalisation(true);
        // gain = 0,97 / 0,1 = 9,7 → 20·log10(9,7) ≈ 19,73 dB
        assertThat(m.normalisationGainDb()).isCloseTo(19.73, within(0.1));

        m.setNormalisation(false);
        assertThat(m.normalisationGainDb()).isZero();
    }

    @Test
    void loadSourceEtBasculeNeJettentPasMemeSansPeripheriqueAudio() {
        // loadSource pré-calcule le gain (peakGain) puis ouvre le clip en best-effort : sans carte
        // son, l'exception est avalée. Toggle ensuite : reopen() reste silencieux. L'affichage ne
        // doit jamais être interrompu par la lecture (invariant du composant).
        PlaybackModel m = new PlaybackModel();
        assertThatCode(() -> {
                    m.loadSource(new float[] {0.1f, -0.2f, 0.05f}, 44_100f);
                    m.setNormalisation(true);
                    m.setNormalisation(false);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void handleTickEnFinDExtraitSansLoopArreteLaLecture() {
        // Sans loop : en fin d'extrait, currentTime clamp à duration, playing repasse à false.
        PlaybackModel m = new PlaybackModel();
        m.setDuration(10);
        m.handleTick(10, true);
        assertThat(m.currentTime()).isEqualTo(10);
        assertThat(m.isPlaying()).isFalse();
    }

    @Test
    void handleTickEnFinDExtraitAvecLoopRevientAZero() {
        // Avec loop=true : repart de zéro au lieu de s'arrêter (issue #15).
        PlaybackModel m = new PlaybackModel();
        m.setDuration(10);
        m.setLoop(true);
        m.handleTick(10, true);
        assertThat(m.currentTime()).isZero();
        assertThat(m.isLoop()).isTrue();
    }

    @Test
    void handleTickEnCoursDeLectureMetAJourLaPosition() {
        // Position au milieu de l'extrait : copiée dans currentTime, peu importe loop.
        PlaybackModel m = new PlaybackModel();
        m.setDuration(10);
        m.handleTick(3.5, false);
        assertThat(m.currentTime()).isEqualTo(3.5);

        m.setLoop(true);
        m.handleTick(7, false);
        assertThat(m.currentTime()).isEqualTo(7);
    }
}
