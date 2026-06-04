package fr.nedjar.vigiechiro.audio;

import static org.assertj.core.api.Assertions.assertThat;

import fr.nedjar.vigiechiro.audio.view.SonogramView;
import fr.nedjar.vigiechiro.audio.view.SpectrogramView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Garde-fou de compatibilité d'API publique d'{@link AudioView} — issue #26.
 *
 * <p>Liste figée de la signature exacte des membres publics. Toute divergence (suppression,
 * renommage, changement de type, ajout non répertorié) fait échouer le build, matérialisant la
 * décision « cassant volontaire ? bump majeur. ajout assumé ? mise à jour de cette liste ».
 *
 * <p>Périmètre : le contrat vu par les étudiants — {@link AudioView} uniquement. Les sous-vues
 * {@link SonogramView} / {@link SpectrogramView} sont {@code public final} pour FXMLLoader mais
 * n'ont pas de méthode d'instance publique à protéger.
 */
class AudioViewApiTest {

    /**
     * Membres publics attendus d'{@link AudioView}. Format :
     *
     * <ul>
     *   <li>{@code <init>(types)} pour les constructeurs ;
     *   <li>{@code nom(types):typeRetour} pour les méthodes.
     * </ul>
     *
     * <p>Les types sont les noms canoniques avec génériques préservés (effacement Java mais on
     * récupère via {@code getGenericReturnType().getTypeName()}).
     *
     * <p><b>Modifier cette liste, c'est modifier le contrat.</b> Ajouter une entrée = on a décidé
     * d'exposer un nouveau membre. Retirer ou modifier une entrée = changement cassant, demande un
     * commit {@code BREAKING CHANGE:} (semantic-release tagera une majeure).
     */
    private static final Set<String> EXPECTED_MEMBERS = Set.of(
            "<init>()",
            "audioFileProperty():javafx.beans.property.ObjectProperty<java.nio.file.Path>",
            "getAudioFile():java.nio.file.Path",
            "setAudioFile(java.nio.file.Path):void",
            "setSource(java.lang.String):void",
            "playingProperty():javafx.beans.property.BooleanProperty",
            "isPlaying():boolean",
            "setPlaying(boolean):void",
            "togglePlay():void",
            "currentTimeProperty():javafx.beans.property.ReadOnlyDoubleProperty",
            "getCurrentTime():double",
            "durationProperty():javafx.beans.property.ReadOnlyDoubleProperty",
            "getDuration():double",
            "timeZoomProperty():javafx.beans.property.DoubleProperty",
            "getTimeZoom():double",
            "setTimeZoom(double):void",
            "frequencyZoomProperty():javafx.beans.property.DoubleProperty",
            "getFrequencyZoom():double",
            "setFrequencyZoom(double):void",
            "timeExpansionFactorProperty():javafx.beans.property.DoubleProperty",
            "getTimeExpansionFactor():double",
            "setTimeExpansionFactor(double):void",
            "lightThemeProperty():javafx.beans.property.BooleanProperty",
            "isLightTheme():boolean",
            "setLightTheme(boolean):void",
            "errorMessageProperty():javafx.beans.property.ReadOnlyStringProperty",
            "getErrorMessage():java.lang.String",
            "dispose():void",
            "getClassCssMetaData():java.util.List<javafx.css.CssMetaData<? extends javafx.css.Styleable, ?>>",
            "getCssMetaData():java.util.List<javafx.css.CssMetaData<? extends javafx.css.Styleable, ?>>");

    @Test
    void apiPubliqueDAudioViewNeRegressePas() {
        Set<String> missing = new HashSet<>(EXPECTED_MEMBERS);
        missing.removeAll(currentApiMembers());
        assertThat(missing)
                .as("Membres publics d'AudioView retirés ou renommés (changement cassant)."
                        + " Si volontaire, mettre à jour EXPECTED_MEMBERS et committer en BREAKING CHANGE:")
                .isEmpty();
    }

    @Test
    void aucunMembrePublicAjouteSansMettreAJourLaListe() {
        Set<String> extra = new HashSet<>(currentApiMembers());
        extra.removeAll(EXPECTED_MEMBERS);
        assertThat(extra)
                .as("Nouveaux membres publics d'AudioView non répertoriés."
                        + " Si l'ajout est volontaire (commit feat:), ajouter ces entrées à"
                        + " EXPECTED_MEMBERS ; sinon, réduire la visibilité.")
                .isEmpty();
    }

    /** Énumère via réflexion les membres publics réellement déclarés sur {@link AudioView}. */
    private static Set<String> currentApiMembers() {
        Set<String> all = new HashSet<>();
        for (Constructor<?> c : AudioView.class.getDeclaredConstructors()) {
            if (Modifier.isPublic(c.getModifiers())) {
                all.add("<init>" + paramsSignature(c.getGenericParameterTypes()));
            }
        }
        for (Method m : AudioView.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()) {
                all.add(m.getName()
                        + paramsSignature(m.getGenericParameterTypes())
                        + ":"
                        + m.getGenericReturnType().getTypeName());
            }
        }
        return all;
    }

    private static String paramsSignature(Type[] params) {
        return Arrays.stream(params).map(Type::getTypeName).collect(Collectors.joining(",", "(", ")"));
    }
}
