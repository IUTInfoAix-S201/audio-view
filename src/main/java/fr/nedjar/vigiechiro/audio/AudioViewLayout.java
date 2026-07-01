package fr.nedjar.vigiechiro.audio;

import fr.nedjar.vigiechiro.audio.view.SonogramView;
import fr.nedjar.vigiechiro.audio.view.SpectrogramView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

/**
 * Mise en page des deux tracés d'{@link AudioView} et adaptation <b>responsive</b> (issue #30),
 * extraites du composant pour l'alléger : répartition verticale sonogramme / spectrogramme et
 * masquage progressif (légende dB puis sonogramme) quand la place manque, plutôt que de tout tasser
 * jusqu'à l'illisible. La barre d'outils, elle, reste toujours visible.
 */
final class AudioViewLayout {

    /** Largeur de la gouttière gauche, à retrancher de la zone de tracé pour le critère de légende. */
    private static final double AXIS_LEFT = 48;

    /** En deçà de cette largeur (ou hauteur) de tracé spectro, la légende dB est masquée. */
    private static final double LEGEND_MIN_PLOT_WIDTH = 320;

    private static final double LEGEND_MIN_SPECTRO_HEIGHT = 200;

    /** En deçà de cette hauteur de la zone des tracés, le sonogramme est masqué. */
    private static final double SONO_MIN_PLOTS_HEIGHT = 120;

    private AudioViewLayout() {}

    /**
     * Installe la répartition 32 % / 68 % et les règles de visibilité responsive. {@code spectroView}
     * porte {@code VBox.vgrow=ALWAYS} dans la FXML : quand {@code sonoView} est masqué, il prend toute
     * la place. La légende vit dans {@code spectroView} ; ses critères se calculent sur sa zone de tracé.
     */
    static void installResponsive(VBox plots, SonogramView sonoView, SpectrogramView spectroView) {
        sonoView.prefHeightProperty().bind(plots.heightProperty().multiply(0.32));
        spectroView.prefHeightProperty().bind(plots.heightProperty().multiply(0.68));

        Pane legend = spectroView.legendPane();
        Pane spectroPlotHost = spectroView.plotHost();
        legend.visibleProperty()
                .bind(spectroPlotHost
                        .widthProperty()
                        .subtract(AXIS_LEFT)
                        .greaterThanOrEqualTo(LEGEND_MIN_PLOT_WIDTH)
                        .and(spectroPlotHost.heightProperty().greaterThanOrEqualTo(LEGEND_MIN_SPECTRO_HEIGHT)));
        legend.managedProperty().bind(legend.visibleProperty());

        sonoView.visibleProperty().bind(plots.heightProperty().greaterThanOrEqualTo(SONO_MIN_PLOTS_HEIGHT));
        sonoView.managedProperty().bind(sonoView.visibleProperty());
    }
}
