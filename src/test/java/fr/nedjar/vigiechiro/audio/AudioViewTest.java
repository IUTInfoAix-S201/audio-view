package fr.nedjar.vigiechiro.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Test fumant : il vérifie que le composant accepte un WAV, le décode hors du thread FX, et publie
 * une durée cohérente. Nécessite un affichage (un serveur X virtuel via {@code xvfb-run} en CI).
 */
class AudioViewTest extends ApplicationTest {

    private AudioView view;

    @Override
    public void start(Stage stage) {
        view = new AudioView();
        stage.setScene(new Scene(view, 640, 360));
        stage.show();
    }

    @Test
    void chargeUnWavEtPublieLaDuree() throws Exception {
        Path wav = ecrireSinusWav(38_400f, 1.0); // 1 seconde a 38,4 kHz

        interact(() -> view.setAudioFile(wav));
        WaitForAsyncUtils.waitForFxEvents();
        // Le decodage et la FFT sont asynchrones : on attend la mise a jour de la duree.
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> view.getDuration() > 0.0);

        assertThat(view.getDuration()).isCloseTo(1.0, Offset.offset(0.1));
        assertThat(view.getCurrentTime()).isEqualTo(0.0);
    }

    @Test
    void readyEstFalseAvantChargementEtTrueQuandLaSTFTEstPrete() throws Exception {
        // Signal canonique de fin de chargement (issue #31) : avant setAudioFile, ready=false ;
        // après que la Task d'analyse termine, ready passe à true et tout l'état est en place
        // (sample/spectrogramImage/duration), garanti pour un snapshot hors-écran.
        assertThat(view.isReady()).isFalse();

        Path wav = ecrireSinusWav(38_400f, 1.0);
        interact(() -> view.setAudioFile(wav));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> view.isReady());

        assertThat(view.isReady()).isTrue();
        assertThat(view.getDuration()).isGreaterThan(0);

        // Reset à false sur source nulle.
        interact(() -> view.setAudioFile(null));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(view.isReady()).isFalse();
    }

    @Test
    void seekPositionneLeCurseurEnTempsReel() throws Exception {
        // Seek programmatique (issue #51) : en temps réel (facteur 1 ici), clampé à [0, durée].
        Path wav = ecrireSinusWav(38_400f, 1.0);
        interact(() -> view.setAudioFile(wav));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> view.isReady());

        interact(() -> view.seek(0.5));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(view.getCurrentTime()).isCloseTo(0.5, Offset.offset(0.05));

        // Au-delà de la durée : clampé à la durée. En deçà de 0 : clampé à 0.
        interact(() -> view.seek(999));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(view.getCurrentTime()).isCloseTo(view.getDuration(), Offset.offset(0.05));
        interact(() -> view.seek(-5));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(view.getCurrentTime()).isZero();
    }

    @Test
    void surlignageExposeLaFenetreEnTempsReel() throws Exception {
        // Surlignage d'un cri (issue #52) : la fenêtre est exposée en temps réel {debut, fin}.
        Path wav = ecrireSinusWav(38_400f, 1.0);
        interact(() -> view.setAudioFile(wav));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> view.isReady());

        interact(() -> view.highlightWindow(0.2, 0.4));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(view.getHighlightedWindow()).containsExactly(0.2, 0.4);

        interact(() -> view.clearHighlight());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(view.getHighlightedWindow()).isNull();
    }

    @Test
    void grandeursAcoustiquesRenseigneesApresChargement() throws Exception {
        // FME / durée exposées après analyse (issue #50). Sinus 4 kHz -> FME ~ 4 kHz (facteur 1).
        Path wav = ecrireSinusWav(38_400f, 1.0);
        interact(() -> view.setAudioFile(wav));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> view.isReady());

        assertThat(view.getFmeHz()).isCloseTo(4000, Offset.offset(60.0));
        assertThat(view.getDureeMs()).isGreaterThan(0);
    }

    @Test
    void tempsReelEtFrequencesSuiventLeFacteurDExpansion() throws Exception {
        // Convention (issues #50/#51/#52) : temps réel = temps fichier ÷ facteur, fréquence = × facteur.
        Path wav = ecrireSinusWav(38_400f, 1.0);
        interact(() -> view.setAudioFile(wav));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> view.isReady());

        interact(() -> view.setTimeExpansionFactor(10));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(view.getDuration()).isCloseTo(0.1, Offset.offset(0.02)); // 1 s fichier ÷ 10
        assertThat(view.getFmeHz()).isCloseTo(40_000, Offset.offset(700.0)); // 4 kHz × 10
    }

    private static Path ecrireSinusWav(float sampleRate, double seconds) throws IOException {
        int n = (int) (sampleRate * seconds);
        byte[] data = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / (double) sampleRate;
            short s = (short) (Math.sin(2 * Math.PI * 4000 * t) * 12_000);
            data[i * 2] = (byte) (s & 0xFF);
            data[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
        Path out = Files.createTempFile("audioview-test", ".wav");
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, n)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out.toFile());
        }
        out.toFile().deleteOnExit();
        return out;
    }
}
