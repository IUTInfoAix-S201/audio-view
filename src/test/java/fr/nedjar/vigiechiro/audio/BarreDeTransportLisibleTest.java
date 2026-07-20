package fr.nedjar.vigiechiro.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * La barre de transport doit rester <b>lisible</b> quand le composant est étroit (issue #56).
 *
 * <p>Un test vérifie d'ordinaire qu'un bouton <b>fait</b> ce qu'il doit ; celui-ci vérifie qu'on
 * puisse <b>lire</b> ce qu'il dit. La distinction n'est pas théorique : les libellés de cette barre
 * (« Temps + », « Fréq. - », l'horloge) se rendaient tronqués par une ellipse, et rien ne rougissait
 * — le défaut a été trouvé à l'œil, sur un aperçu, par le dépôt consommateur.
 *
 * <p>La largeur <b>minimale</b> d'un {@link Labeled} autorise la troncature : un conteneur en
 * déficit rogne donc ses enfants jusqu'à l'ellipse plutôt que de déborder. Une barre d'actions doit
 * <b>plier</b>, pas tronquer.
 */
class BarreDeTransportLisibleTest extends ApplicationTest {

    /** Bien en dessous de la largeur naturelle de la barre : elle doit s'y adapter, pas s'y couper. */
    private static final double LARGEUR_ETROITE = 320;

    /** La mise en page produit des écarts d'arrondi qui ne sont pas des troncatures. */
    private static final double TOLERANCE_PX = 1.0;

    private AudioView view;

    @Override
    public void start(Stage stage) {
        view = new AudioView();
        stage.setScene(new Scene(view, LARGEUR_ETROITE, 360));
        stage.show();
    }

    @Test
    void aucunLibelleDeLaBarreNestTronqueEnLargeurEtroite() {
        WaitForAsyncUtils.waitForFxEvents();

        Node barre = view.lookup("#toolbar");
        assertThat(barre)
                .as("la barre de transport doit être trouvable par son fx:id")
                .isNotNull();

        List<String> tronques = new ArrayList<>();
        collecterTronques(barre, tronques);

        assertThat(tronques)
                .as(
                        "à %.0f px, ces libellés sont rendus avec une ellipse : illisibles."
                                + " Une barre d'actions doit plier plutôt que couper.",
                        LARGEUR_ETROITE)
                .isEmpty();
    }

    private static void collecterTronques(Node noeud, List<String> tronques) {
        if (!noeud.isVisible()) {
            return;
        }
        if (noeud instanceof Labeled libelle
                && libelle.getText() != null
                && !libelle.getText().isBlank()
                && libelle.getWidth() > 0) {
            double manque = libelle.prefWidth(-1) - libelle.getWidth();
            if (manque > TOLERANCE_PX) {
                tronques.add("« %s » (manque %d px)".formatted(libelle.getText(), Math.round(manque)));
            }
        }
        if (noeud instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(enfant -> collecterTronques(enfant, tronques));
        }
    }
}
