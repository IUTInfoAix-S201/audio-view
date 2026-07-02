package fr.nedjar.vigiechiro.audio.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import fr.nedjar.vigiechiro.audio.dsp.AudioAnalyzer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Couvre la branche {@link AudioViewModel} qui sollicite le toolkit JavaFX : le chargement asynchrone
 * (Task/onSucceeded sur le thread FX) et la bascule de <b>normalisation visuelle</b> du sonogramme. Sur
 * un fichier très faible, l'auto-échelle publiée passe du plafond conservateur ({@link
 * AudioAnalyzer#SONO_MAX_GAIN}) à l'échelle normalisée ({@link AudioAnalyzer#SONO_NORMALISED_MAX_GAIN})
 * sans re-décoder le fichier.
 */
class AudioViewModelToolkitTest extends ApplicationTest {

    @Override
    public void start(Stage stage) {
        // Aucune scène : seul le toolkit JavaFX doit être initialisé (Task.onSucceeded / listeners FX).
    }

    @Test
    void normalisationVisuelleBasculeLAutoEchelleDuSonogramme() throws Exception {
        AudioViewModel vm = new AudioViewModel();
        // Fichier très faible : pic = 1 LSB (1/32768) → l'auto-échelle veut ~31000×, bornée différemment
        // selon le mode.
        Path wav = ecrireWavMonoConstant(8000f, 0.2, (short) 1);

        interact(() -> vm.audioFileProperty().set(wav));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, vm::isReady);

        // Par défaut : plafond conservateur.
        assertThat(vm.getSonoScale()).isEqualTo(AudioAnalyzer.SONO_MAX_GAIN);

        // Normalisation visuelle active : remplissage jusqu'au pic (plafond relevé), sans re-décodage.
        interact(() -> vm.waveNormalisationProperty().set(true));
        assertThat(vm.getSonoScale()).isEqualTo(AudioAnalyzer.SONO_NORMALISED_MAX_GAIN);

        // Retour arrière : on retrouve l'échelle plafonnée (la bascule est réversible).
        interact(() -> vm.waveNormalisationProperty().set(false));
        assertThat(vm.getSonoScale()).isEqualTo(AudioAnalyzer.SONO_MAX_GAIN);

        interact(vm::dispose);
    }

    @Test
    void normalisationVisuelleRecaleLaFenetreDbDuSpectrogramme() throws Exception {
        AudioViewModel vm = new AudioViewModel();
        // Fichier très faible : son pic dB est bien sous le plafond fixe MAX_DB (d'où le spectro noir).
        Path wav = ecrireWavMonoConstant(8000f, 0.2, (short) 1);

        interact(() -> vm.audioFileProperty().set(wav));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, vm::isReady);

        // Par défaut : fenêtre dB fixe [MIN_DB, MAX_DB].
        assertThat(vm.getSpectroMinDb()).isEqualTo(AudioViewModel.MIN_DB);
        assertThat(vm.getSpectroMaxDb()).isEqualTo(AudioViewModel.MAX_DB);

        // Normalisation visuelle : la fenêtre se recale sur le pic réel (haut < MAX_DB pour ce signal
        // faible), en gardant la même largeur de dynamique.
        interact(() -> vm.spectrogramNormalisationProperty().set(true));
        assertThat(vm.getSpectroMaxDb()).isLessThan(AudioViewModel.MAX_DB);
        assertThat(vm.getSpectroMaxDb() - vm.getSpectroMinDb())
                .isEqualTo(AudioViewModel.MAX_DB - AudioViewModel.MIN_DB);
        assertThat(vm.getSpectrogramImage()).isNotNull();

        // Retour arrière : fenêtre fixe restaurée.
        interact(() -> vm.spectrogramNormalisationProperty().set(false));
        assertThat(vm.getSpectroMaxDb()).isEqualTo(AudioViewModel.MAX_DB);

        interact(vm::dispose);
    }

    private static Path ecrireWavMonoConstant(float sampleRate, double seconds, short value) throws IOException {
        int n = (int) (sampleRate * seconds);
        byte[] data = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            data[i * 2] = (byte) (value & 0xFF);
            data[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(sampleRate, 16, 1, true, false);
        Path out = Files.createTempFile("vm-toolkit-test", ".wav");
        try (javax.sound.sampled.AudioInputStream ais =
                new javax.sound.sampled.AudioInputStream(new ByteArrayInputStream(data), fmt, n)) {
            javax.sound.sampled.AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, out.toFile());
        }
        out.toFile().deleteOnExit();
        return out;
    }
}
