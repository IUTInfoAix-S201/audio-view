package fr.nedjar.vigiechiro.audio;

import fr.nedjar.vigiechiro.audio.viewmodel.AudioViewModel;
import javafx.scene.input.KeyEvent;

/**
 * Raccourcis clavier d'{@link AudioView} (issue #24), extraits du composant pour l'alléger : le
 * mapping touche → action est une responsabilité propre, sans état, et sans intérêt pour la métrique
 * de taille de la façade d'API. Le composant doit avoir le focus (Tab) pour recevoir les touches.
 *
 * <ul>
 *   <li><b>Espace</b> : démarrer / mettre en pause la lecture
 *   <li><b>←</b> / <b>→</b> : reculer / avancer d'1 seconde fichier
 *   <li><b>Origine</b> / <b>Fin</b> : aller au début / à la fin de l'extrait
 *   <li><b>+</b> ou <b>=</b> / <b>-</b> : zoom temporel × 2 / ÷ 2
 *   <li><b>PgUp</b> / <b>PgDown</b> : zoom fréquentiel × 2 / ÷ 2
 * </ul>
 */
final class AudioViewShortcuts {

    /** Facteur de zoom maximal (au-delà, les fenêtres deviennent plus courtes qu'une trame FFT). */
    private static final double ZOOM_MAX = 64;

    private AudioViewShortcuts() {}

    /**
     * Applique le raccourci associé à {@code e} et consomme l'événement s'il correspond à une action.
     * Les commandes de transport passent par le {@code vm} (dont {@code seek}, hors API publique) ; les
     * zooms passent par la {@code view} pour bénéficier de son clamp public.
     */
    static void handle(KeyEvent e, AudioView view, AudioViewModel vm) {
        switch (e.getCode()) {
            case SPACE -> vm.togglePlay();
            case LEFT -> vm.seek(Math.max(0, vm.getCurrentTime() - 1));
            case RIGHT -> vm.seek(Math.min(vm.getDuration(), vm.getCurrentTime() + 1));
            case HOME -> vm.seek(0);
            case END -> vm.seek(vm.getDuration());
            case EQUALS, PLUS, ADD -> view.setTimeZoom(Math.min(ZOOM_MAX, view.getTimeZoom() * 2));
            case MINUS, SUBTRACT -> view.setTimeZoom(Math.max(1, view.getTimeZoom() / 2));
            case PAGE_UP -> view.setFrequencyZoom(Math.min(ZOOM_MAX, view.getFrequencyZoom() * 2));
            case PAGE_DOWN -> view.setFrequencyZoom(Math.max(1, view.getFrequencyZoom() / 2));
            default -> {
                return;
            }
        }
        e.consume();
    }
}
