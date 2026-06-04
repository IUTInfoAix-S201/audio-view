package fr.nedjar.vigiechiro.audio.playback;

import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Fine enveloppe autour d'un {@link Clip} de {@code javax.sound.sampled}, exposant une horloge en
 * secondes (lecture / pause / déplacement). Le clip charge la séquence courte (5 secondes) en
 * mémoire, ce qui donne une position précise pour synchroniser le curseur via un {@code
 * AnimationTimer}.
 */
public final class AudioPlayer implements AutoCloseable {

  private Clip clip;

  public void load(Path path)
      throws IOException, UnsupportedAudioFileException, LineUnavailableException {
    close();
    try (AudioInputStream in = AudioSystem.getAudioInputStream(path.toFile())) {
      clip = AudioSystem.getClip();
      clip.open(in);
    }
  }

  public void play() {
    if (clip != null) {
      clip.start();
    }
  }

  public void pause() {
    if (clip != null) {
      clip.stop();
    }
  }

  /** Position de lecture courante, en secondes. */
  public double position() {
    return clip == null ? 0 : clip.getMicrosecondPosition() / 1_000_000.0;
  }

  /** Durée totale, en secondes. */
  public double length() {
    return clip == null ? 0 : clip.getMicrosecondLength() / 1_000_000.0;
  }

  public void seek(double seconds) {
    if (clip != null) {
      double clamped = Math.max(0, Math.min(length(), seconds));
      clip.setMicrosecondPosition((long) (clamped * 1_000_000.0));
    }
  }

  @Override
  public void close() {
    if (clip != null) {
      clip.stop();
      clip.close();
      clip = null;
    }
  }
}
