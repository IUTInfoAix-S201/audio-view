package fr.nedjar.vigiechiro.audio.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import fr.nedjar.vigiechiro.audio.dsp.AudioAnalyzer.AnalyzedAudio;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.junit.jupiter.api.Test;

/**
 * Teste l'analyse audio (décode + STFT + auto-échelles) <b>sans toolkit JavaFX</b>. C'est l'intérêt
 * principal de l'extraction : la logique du pipeline est vérifiable sans démarrer JavaFX.
 */
class AudioAnalyzerTest {

    @Test
    void autoEchelleSonogrammeSurLePic() throws Exception {
        // pic = 16384/32768 = 0.5 -> facteur = 0.95 / 0.5 = 1.9
        AudioSample s = AudioSample.load(ecrireWavMono(8000f, 0.2, (short) 16384));
        assertThat(AudioAnalyzer.sonoScaleFor(s)).isCloseTo(1.9, within(0.05));
    }

    @Test
    void cadrageFrequentielZoomeSurUneToneBasse() throws Exception {
        // tone à 500 Hz dans un fichier 8 kHz (Nyquist 4 kHz) : bande utile basse -> zoom > 1
        AudioSample s = AudioSample.load(ecrireSinusWav(8000f, 1.0, 500));
        Spectrogram spec = Spectrogram.compute(s, AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        double zoom = AudioAnalyzer.autoFrequencyZoom(spec);
        assertThat(zoom).isGreaterThan(1.5).isLessThanOrEqualTo(64.0);
    }

    @Test
    void analyzeRegroupeDecodeSTFTEtAutoEchelles() throws Exception {
        // analyze() = orchestration end-to-end : on vérifie que tous les champs du record sont
        // renseignés cohéremment pour un sinus 500 Hz.
        Path wav = ecrireSinusWav(8000f, 1.0, 500);
        AnalyzedAudio result = AudioAnalyzer.analyze(wav);

        assertThat(result.sample()).isNotNull();
        assertThat(result.spectrogram()).isNotNull();
        assertThat(result.durationSeconds()).isCloseTo(1.0, within(0.05));
        assertThat(result.sonoScale()).isGreaterThan(0);
        assertThat(result.suggestedFrequencyZoom()).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(64);
    }

    @Test
    void analyzePropageLesErreursDeFormat() throws IOException {
        // fichier au mauvais format -> UnsupportedAudioFileException remontée
        Path bogus = Files.createTempFile("bogus", ".wav");
        Files.writeString(bogus, "ceci n'est pas un wav");
        bogus.toFile().deleteOnExit();
        assertThatThrownBy(() -> AudioAnalyzer.analyze(bogus)).isInstanceOf(UnsupportedAudioFileException.class);
    }

    @Test
    void sonoScaleSurFichierMuetVaut1() throws Exception {
        // Pic ~ 0 (silence) → seuil 1e-6 non franchi → renvoie 1 (pas d'amplification).
        // Tue les mutants qui retourneraient peak ou 100 / peak en cas de silence.
        AudioSample s = AudioSample.load(ecrireWavMono(8000f, 0.2, (short) 0));
        assertThat(AudioAnalyzer.sonoScaleFor(s)).isEqualTo(1.0);
    }

    @Test
    void sonoScaleSurFichierTresFaiblePlafonneA100() throws Exception {
        // Pic minuscule (1/32768 = 3e-5) → 0.95 / peak ≈ 31000 → plafonné à 100.
        // Tue les mutants qui retireraient le plafond Math.min(0.95/peak, 100).
        AudioSample s = AudioSample.load(ecrireWavMono(8000f, 0.2, (short) 1));
        assertThat(AudioAnalyzer.sonoScaleFor(s)).isEqualTo(100.0);
    }

    @Test
    void sonoScaleNormaliseReleveLePlafondPourRemplirJusquAuPic() throws Exception {
        // Même fichier très faible (pic 1/32768) : le plafond par défaut bride à 100, mais la
        // normalisation visuelle remonte jusqu'à SONO_NORMALISED_MAX_GAIN (remplissage jusqu'au pic).
        // Tue les mutants qui ignoreraient le paramètre maxGain ou réutiliseraient le plafond par défaut.
        AudioSample s = AudioSample.load(ecrireWavMono(8000f, 0.2, (short) 1));
        assertThat(AudioAnalyzer.sonoScaleFor(s)).isEqualTo(AudioAnalyzer.SONO_MAX_GAIN);
        assertThat(AudioAnalyzer.sonoScaleFor(s, AudioAnalyzer.SONO_NORMALISED_MAX_GAIN))
                .isEqualTo(AudioAnalyzer.SONO_NORMALISED_MAX_GAIN)
                .isGreaterThan(AudioAnalyzer.sonoScaleFor(s));
    }

    @Test
    void analyseFournitLesDeuxEchellesNormaliseeSuperieureOuEgale() throws Exception {
        // analyze() pré-calcule les deux auto-échelles ; la normalisée n'est jamais inférieure à la
        // plafonnée (même formule, plafond plus haut) — la vue bascule de l'une à l'autre sans re-décoder.
        AudioAnalyzer.AnalyzedAudio result = AudioAnalyzer.analyze(ecrireWavMono(8000f, 0.2, (short) 1));
        assertThat(result.normalisedSonoScale()).isGreaterThanOrEqualTo(result.sonoScale());
    }

    @Test
    void spectroPicDbCroitAvecLeNiveauDuSignal() throws Exception {
        // Le pic dB du spectrogramme suit le niveau : un signal fort a un pic plus haut qu'un faible.
        // C'est ce pic qui sert à recaler la fenêtre dB (normalisation visuelle du spectro).
        double fort = AudioAnalyzer.spectroPeakDbFor(Spectrogram.compute(
                AudioSample.load(ecrireWavMono(8000f, 0.2, (short) 16384)), AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP));
        double faible = AudioAnalyzer.spectroPeakDbFor(Spectrogram.compute(
                AudioSample.load(ecrireWavMono(8000f, 0.2, (short) 64)), AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP));
        assertThat(fort).isGreaterThan(faible).isFinite();
    }

    @Test
    void analyseFournitLePicDbDuSpectrogramme() throws Exception {
        // analyze() pré-calcule le pic dB, cohérent avec le calcul direct sur son spectrogramme.
        AudioAnalyzer.AnalyzedAudio r = AudioAnalyzer.analyze(ecrireWavMono(8000f, 0.2, (short) 64));
        assertThat(r.spectroPeakDb()).isEqualTo(AudioAnalyzer.spectroPeakDbFor(r.spectrogram()));
    }

    @Test
    void sonoScaleSurFichierSatureVautMoinsD1() throws Exception {
        // Pic max = 32767/32768 ≈ 1 → facteur ≈ 0.95 / 1 = 0.95.
        // Tue les mutants qui inverseraient le ratio peak/0.95.
        AudioSample s = AudioSample.load(ecrireWavMono(8000f, 0.2, Short.MAX_VALUE));
        assertThat(AudioAnalyzer.sonoScaleFor(s)).isCloseTo(0.95, within(0.001));
    }

    @Test
    void autoFrequencyZoomEstBorneEntre1Et64() throws Exception {
        // Tone très bas (50 Hz / 8 kHz) : bande utile minuscule → fraction ≈ 0 → zoom borné à 64.
        // Tue les mutants qui retireraient le Math.min(64, ...).
        AudioSample s = AudioSample.load(ecrireSinusWav(8000f, 1.0, 50));
        Spectrogram spec = Spectrogram.compute(s, AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        double zoom = AudioAnalyzer.autoFrequencyZoom(spec);
        assertThat(zoom).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(64);
    }

    @Test
    void autoFrequencyZoomSurBruitLargeVaut1() throws Exception {
        // Bruit blanc (toutes les fréquences) → plusHaut proche du Nyquist → fraction proche
        // de 1 → zoom = 1 (pas de cadrage utile). Tue les mutants qui forceraient zoom > 1
        // même quand la bande utile couvre tout le spectre.
        AudioSample s = AudioSample.load(ecrireBruitWav(8000f, 1.0));
        Spectrogram spec = Spectrogram.compute(s, AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        double zoom = AudioAnalyzer.autoFrequencyZoom(spec);
        assertThat(zoom).isEqualTo(1.0);
    }

    // ----- Grandeurs acoustiques par cri (issue #50) -----

    @Test
    void fmeRetrouveLaFrequenceDominante() throws Exception {
        // Sinus pur à 500 Hz (8 kHz, Δf = 8000/1024 ≈ 7,8 Hz) : le bin de max d'énergie retombe sur
        // ~500 Hz. Grandeur de référence pour trier les cris.
        Spectrogram spec = Spectrogram.compute(
                AudioSample.load(ecrireSinusWav(8000f, 1.0, 500)), AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        double fme = AudioAnalyzer.peakFrequencyHz(spec, 8000, AudioAnalyzer.FFT_SIZE, 0, spec.frameCount() - 1);
        assertThat(fme).isCloseTo(500, within(15.0));
    }

    @Test
    void fmeSurFenetreRestreinteResteCoherente() throws Exception {
        // La FME calculée sur un sous-intervalle de trames (le « cri ») retrouve la même fréquence :
        // c'est ce qui permet une FME par observation.
        Spectrogram spec = Spectrogram.compute(
                AudioSample.load(ecrireSinusWav(8000f, 1.0, 500)), AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        int n = spec.frameCount();
        double fme = AudioAnalyzer.peakFrequencyHz(spec, 8000, AudioAnalyzer.FFT_SIZE, n / 4, 3 * n / 4);
        assertThat(fme).isCloseTo(500, within(15.0));
    }

    @Test
    void fmeSurSilenceEstNaN() throws Exception {
        // Fichier muet : pic sous le plancher absolu → aucune fréquence exploitable → NaN.
        Spectrogram spec = Spectrogram.compute(
                AudioSample.load(ecrireWavMono(8000f, 0.3, (short) 0)), AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        double fme = AudioAnalyzer.peakFrequencyHz(spec, 8000, AudioAnalyzer.FFT_SIZE, 0, spec.frameCount() - 1);
        assertThat(fme).isNaN();
    }

    @Test
    void frequenceTerminaleSurTonConstantRejointLaFME() throws Exception {
        // Sur un cri à fréquence quasi constante, la fréquence terminale (dernière trame active)
        // retombe sur la fréquence dominante.
        Spectrogram spec = Spectrogram.compute(
                AudioSample.load(ecrireSinusWav(8000f, 1.0, 500)), AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        double term = AudioAnalyzer.terminalFrequencyHz(spec, 8000, AudioAnalyzer.FFT_SIZE, 0, spec.frameCount() - 1);
        assertThat(term).isCloseTo(500, within(15.0));
    }

    @Test
    void frequenceTerminaleSurSilenceEstNaN() throws Exception {
        Spectrogram spec = Spectrogram.compute(
                AudioSample.load(ecrireWavMono(8000f, 0.3, (short) 0)), AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        double term = AudioAnalyzer.terminalFrequencyHz(spec, 8000, AudioAnalyzer.FFT_SIZE, 0, spec.frameCount() - 1);
        assertThat(term).isNaN();
    }

    @Test
    void dureeActiveCouvreLeSignalConstant() throws Exception {
        // Tone constant de 1 s : la durée active mesurée sur l'énergie couvre presque tout le fichier
        // (bornée par le dernier début de trame STFT), donc entre 0,8 s et la durée du fichier.
        Spectrogram spec = Spectrogram.compute(
                AudioSample.load(ecrireSinusWav(8000f, 1.0, 500)), AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        double dur = AudioAnalyzer.activeDurationSeconds(spec, 8000, AudioAnalyzer.HOP, 0, spec.frameCount() - 1);
        assertThat(dur).isGreaterThan(0.8).isLessThanOrEqualTo(1.0);
    }

    @Test
    void dureeActiveSurSilenceEst0() throws Exception {
        // Muet : aucune trame au-dessus du plancher → durée nulle (et non « fichier entier »).
        Spectrogram spec = Spectrogram.compute(
                AudioSample.load(ecrireWavMono(8000f, 0.3, (short) 0)), AudioAnalyzer.FFT_SIZE, AudioAnalyzer.HOP);
        double dur = AudioAnalyzer.activeDurationSeconds(spec, 8000, AudioAnalyzer.HOP, 0, spec.frameCount() - 1);
        assertThat(dur).isZero();
    }

    @Test
    void binFrequenceHzEtInstantVersTrame() {
        // bin → Hz : bin · sampleRate / fftSize ; instant → trame : t · sampleRate / hop (arrondi).
        assertThat(AudioAnalyzer.binFrequencyHz(64, 8000, 1024)).isCloseTo(500, within(1e-9));
        assertThat(AudioAnalyzer.frameForTime(0.5, 8000, 256)).isEqualTo((int) Math.round(0.5 * 8000 / 256));
    }

    private static Path ecrireBruitWav(float sampleRate, double seconds) throws IOException {
        int n = (int) (sampleRate * seconds);
        byte[] data = new byte[n * 2];
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < n; i++) {
            short v = (short) (rnd.nextInt(20000) - 10000);
            data[i * 2] = (byte) (v & 0xFF);
            data[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
        Path out = Files.createTempFile("bruit-test", ".wav");
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, n)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out.toFile());
        }
        out.toFile().deleteOnExit();
        return out;
    }

    private static Path ecrireWavMono(float sampleRate, double seconds, short value) throws IOException {
        int n = (int) (sampleRate * seconds);
        byte[] data = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            data[i * 2] = (byte) (value & 0xFF);
            data[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return ecrire(data, sampleRate, n);
    }

    private static Path ecrireSinusWav(float sampleRate, double seconds, double freq) throws IOException {
        int n = (int) (sampleRate * seconds);
        byte[] data = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / (double) sampleRate;
            short v = (short) (Math.sin(2 * Math.PI * freq * t) * 12000);
            data[i * 2] = (byte) (v & 0xFF);
            data[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return ecrire(data, sampleRate, n);
    }

    private static Path ecrire(byte[] data, float sampleRate, int frames) throws IOException {
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
        Path out = Files.createTempFile("analyzer-test", ".wav");
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, frames)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out.toFile());
        }
        out.toFile().deleteOnExit();
        return out;
    }
}
