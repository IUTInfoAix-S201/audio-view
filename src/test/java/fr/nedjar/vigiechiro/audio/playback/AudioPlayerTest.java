package fr.nedjar.vigiechiro.audio.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class AudioPlayerTest {

    @Test
    void sansClipChargeLesPositionsSontNulles() {
        try (AudioPlayer p = new AudioPlayer()) {
            assertThat(p.position()).isZero();
            assertThat(p.length()).isZero();
        }
    }

    @Test
    void operationsSansClipSontSansEffetEtSansErreur() {
        AudioPlayer p = new AudioPlayer();
        assertThatCode(() -> {
                    p.play();
                    p.pause();
                    p.seek(1.0);
                    p.close();
                })
                .doesNotThrowAnyException();
    }

    // ----- peakGain (normalisation peak, issue #32) -----

    @Test
    void peakGainBoosteUnFichierFaible() {
        // Pic à 0,1 → gain = 0,97 / 0,1 = 9,7 (sous le plafond de boost).
        float[] mono = {0.1f, -0.05f, 0.08f};
        assertThat(AudioPlayer.peakGain(mono, 0.97, 40)).isCloseTo(9.7, within(1e-4));
    }

    @Test
    void peakGainAtteneUnFichierDejaPlusFortQueLePlafond() {
        // Pic à 1,0 → gain = 0,97 (atténuation : tous les fichiers finissent au même niveau crête).
        float[] mono = {1.0f, -0.4f};
        assertThat(AudioPlayer.peakGain(mono, 0.97, 40)).isCloseTo(0.97, within(1e-4));
    }

    @Test
    void peakGainRenvoieUnPourUnSignalQuasiMuet() {
        // Pic sous le seuil 1e-6 : on n'amplifie pas le bruit de fond.
        float[] mono = {0f, 0f, 1e-7f, -1e-7f};
        assertThat(AudioPlayer.peakGain(mono, 0.97, 40)).isEqualTo(1.0);
    }

    @Test
    void peakGainPlafonneLeBoost() {
        // Pic à 0,001 → gain brut 970, plafonné à 10^(40/20) = 100.
        float[] mono = {0.001f};
        assertThat(AudioPlayer.peakGain(mono, 0.97, 40)).isCloseTo(100.0, within(1e-6));
    }

    @Test
    void peakGainSurEntreeNulleOuVideRenvoieUn() {
        assertThat(AudioPlayer.peakGain(null, 0.97, 40)).isEqualTo(1.0);
        assertThat(AudioPlayer.peakGain(new float[0], 0.97, 40)).isEqualTo(1.0);
    }

    // ----- toPcm16 (quantification 16 bits LE + gain) -----

    @Test
    void toPcm16ProduitDeuxOctetsParEchantillonEnLittleEndian() {
        // 0,5 × 32767 ≈ 16384 = 0x4000 → octet bas 0x00, octet haut 0x40.
        byte[] pcm = AudioPlayer.toPcm16(new float[] {0.5f}, 1.0);
        assertThat(pcm).hasSize(2);
        assertThat(pcm[0]).isEqualTo((byte) 0x00);
        assertThat(pcm[1]).isEqualTo((byte) 0x40);
        assertThat(leShort(pcm, 0)).isEqualTo(16384);
    }

    @Test
    void toPcm16AppliqueLeGain() {
        // 0,25 × gain 4 = 1,0 → plein régime positif (32767).
        byte[] pcm = AudioPlayer.toPcm16(new float[] {0.25f}, 4.0);
        assertThat(leShort(pcm, 0)).isEqualTo(32767);
    }

    @Test
    void toPcm16EcreteLesDepassementsDansLesBornes16Bits() {
        // Dépassement après gain : écrêtage à [-32768, 32767] (filet de sécurité).
        byte[] haut = AudioPlayer.toPcm16(new float[] {2.0f}, 1.0);
        assertThat(leShort(haut, 0)).isEqualTo(32767);

        byte[] bas = AudioPlayer.toPcm16(new float[] {-2.0f}, 1.0);
        assertThat(leShort(bas, 0)).isEqualTo(-32768);
    }

    @Test
    void toPcm16SurEntreeNulleRenvoieUnTableauVide() {
        assertThat(AudioPlayer.toPcm16(null, 1.0)).isEmpty();
    }

    /** Reconstruit l'échantillon 16 bits signé à l'octet {@code i} (little-endian). */
    private static int leShort(byte[] pcm, int i) {
        int lo = pcm[i] & 0xFF;
        int hi = pcm[i + 1]; // poids fort signé
        return (hi << 8) | lo;
    }
}
