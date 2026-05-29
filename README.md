# VigieChiro Audio View

Composant JavaFX 25 affichant le **sonogramme** (amplitude / temps) et le **spectrogramme**
(fréquence / temps) d'un fichier WAV, avec curseur de lecture synchronisé et zooms temps / fréquence.
Brique fournie aux étudiants de la SAE 2.01 (R2.02 + R2.03) : le calcul FFT, le rendu et la lecture
audio sont internes au composant. On l'instancie avec un chemin de fichier et on observe ses
propriétés. En interne, le composant suit une architecture **MVVM** (vue `fx:root` + FXML + CSS) ;
les contributeurs trouveront les détails dans [CONTRIBUTING.md](CONTRIBUTING.md) et
[TESTING.md](TESTING.md).

## Distribution via JitPack

Le composant est publié sur [JitPack](https://jitpack.io) à partir d'un tag Git. Aucune
authentification n'est nécessaire côté étudiant pour un dépôt public.

### Publier une version (équipe pédagogique)

La publication est **automatique** : il suffit de **pousser sur `main`** avec des messages au format
[Conventional Commits](https://www.conventionalcommits.org). Le workflow `release`
([semantic-release](https://semantic-release.gitbook.io)) calcule la version (`feat:` → mineure,
`fix:` → patch, `BREAKING CHANGE:` → majeure), crée le **tag `vX.Y.Z`** et la **release GitHub**, puis
**smoke-teste / préchauffe JitPack** pour ce tag. Aucun `git tag` manuel.

Détails du processus dans [CONTRIBUTING.md](CONTRIBUTING.md).

### Consommer le composant (template étudiant)

Dans le `pom.xml` du template, ajouter le dépôt JitPack puis la dépendance. Le `groupId` est imposé
par JitPack (`com.github.<organisation>`), l'`artifactId` est le nom du dépôt.

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.IUTInfoAix-S201</groupId>
  <artifactId>audio-view</artifactId>
  <version>v1.0.0</version>
</dependency>
```

> JavaFX est déclaré en `provided` dans le composant : c'est le template qui apporte JavaFX 25 et
> gère les natifs par OS (via son `javafx-maven-plugin`). Le composant suit donc la version JavaFX
> du template sans l'imposer. Le template doit fournir les modules `javafx-controls` **et
> `javafx-fxml`** (ce dernier est requis depuis que le composant est en FXML).

## Utilisation

### Depuis le code

```java
AudioView view = new AudioView();
view.setAudioFile(Path.of("samples/transformes/seq_0001.wav"));

// Synchronisation avec le reste de l'IHM
view.currentTimeProperty().addListener((obs, oldT, newT) -> mettreAJourSurlignage(newT.doubleValue()));
view.durationProperty().addListener((obs, oldD, newD) -> reglerEchelle(newD.doubleValue()));

view.setPlaying(true); // ou view.togglePlay();
```

### Depuis du FXML

Le composant a un constructeur sans argument, il s'insère donc directement :

```xml
<?import fr.iutaix.vigiechiro.audio.AudioView?>

<AudioView fx:id="audioView" VBox.vgrow="ALWAYS"/>
```

La source se règle ensuite dans le contrôleur (conforme au principe « le FXML décrit, le contrôleur
câble ») :

```java
@FXML private AudioView audioView;

public void afficher(Path wav) {
    audioView.setAudioFile(wav);
}
```

Pensez à appeler `audioView.dispose()` à la fermeture pour libérer le périphérique audio.

## API publique

| Membre | Type | Rôle |
| --- | --- | --- |
| `audioFileProperty()` / `setAudioFile(Path)` | `ObjectProperty<Path>` | Source WAV ; déclenche le décodage et la FFT en tâche de fond. |
| `setSource(String)` | — | Variante pratique acceptant un chemin sous forme de chaîne. |
| `playingProperty()` / `setPlaying(boolean)` / `togglePlay()` | `BooleanProperty` | Lecture / pause. |
| `currentTimeProperty()` | `ReadOnlyDoubleProperty` | Position de lecture en secondes (curseur). |
| `durationProperty()` | `ReadOnlyDoubleProperty` | Durée totale en secondes. |
| `timeZoomProperty()` | `DoubleProperty` | Facteur de zoom temporel (1 = vue complète). |
| `frequencyZoomProperty()` | `DoubleProperty` | Facteur de zoom fréquentiel (1 = pleine bande). |
| `timeExpansionFactorProperty()` / `setTimeExpansionFactor(double)` | `DoubleProperty` | Met à l'échelle les libellés des axes (fréquence ×facteur, temps ÷facteur). Ex. `10` pour les WAV ralentis ×10 → axes en unités réelles. Défaut `1`. N'affecte pas `currentTime`/`duration`. |
| `lightThemeProperty()` / `setLightTheme(boolean)` / `isLightTheme()` | `BooleanProperty` | Active le **thème clair** (défaut : sombre). Bascule la chrome **et** la colormap du spectrogramme. |
| `dispose()` | — | Libère le clip audio. |

Le spectrogramme et le sonogramme sont gradués automatiquement : axe **temps** (s) en bas, axe
**fréquence** (kHz) à gauche, avec des pas « ronds » qui suivent les zooms, et une **légende de
couleurs** (intensité en dB) en haut à droite du spectrogramme. Un clic sur le sonogramme ou le
spectrogramme déplace le curseur de lecture.

À l'ouverture d'un fichier, l'affichage s'adapte au contenu : le **sonogramme est mis à l'échelle sur
le pic** du signal (les enregistrements faibles restent lisibles) et la **vue fréquentielle est
cadrée sur la bande réellement utilisée**. La lecture s'arrête en fin d'extrait ; un nouveau clic sur
*Lecture* repart de zéro.

## Thème et personnalisation (CSS)

Le composant est sombre par défaut et fournit un **thème clair** :

```java
audioView.setLightTheme(true); // bascule chrome + colormap du spectrogramme
```

L'aspect est entièrement piloté par CSS (feuille interne `audio-view.css`). On peut surcharger les
classes du composant depuis sa propre feuille (appliquée à la scène ou au composant) :

```css
.audio-view              { -fx-wave-color: #7fd4ff; }  /* couleur de l'enveloppe du sonogramme */
.audio-view-cursor       { -fx-stroke: #ff5252; }      /* curseur de lecture */
.audio-view-axis-label   { -fx-text-fill: #9aa4ad; }   /* libellés d'axes */
.audio-view:light .audio-view-plot-area { -fx-background-color: #f4f6f8; } /* fond en thème clair */
```

`-fx-wave-color` est une propriété CSS stylable exposée par le composant (le sonogramme étant tracé
sur `Canvas`, le moteur CSS ne le verrait pas autrement).

## Développement

Tout passe par le wrapper **`./mvnw`** (committé) :

```bash
./mvnw spotless:apply              # formatage Google Java Format avant commit
./mvnw verify                      # compilation + tests (TestFX, affichage requis)
xvfb-run -a ./mvnw verify          # idem sans écran (Linux headless)
xvfb-run -a ./mvnw -Pquality-gate verify   # quality-gate complet (= CI)
```

Conventions de contribution (architecture, code, commits, qualité, release) :
[CONTRIBUTING.md](CONTRIBUTING.md). Tests (familles de tests, couverture, mutation) :
[TESTING.md](TESTING.md).

## Choix techniques

- **Aucune dépendance runtime** hors JDK + JavaFX : la FFT (radix-2) est embarquée, ce qui rend la
  résolution triviale sur JitPack et sur le module-path. (Modules JavaFX requis :
  `base`/`graphics`/`controls`/`fxml`.)
- **Architecture MVVM** : la logique (décodage/FFT, lecture, fenêtres, auto-échelles) vit dans un
  ViewModel sans dépendance au graphe de scène (donc testable sans interface) ; `AudioView` est la
  vue, un custom control **`fx:root`** (`AudioView.fxml`) stylé par **`audio-view.css`**.
- **Artefact modulaire** (`module-info.java`) exportant uniquement `fr.iutaix.vigiechiro.audio` (et
  l'ouvrant à `javafx.fxml`) : le décodage, la FFT et la lecture restent une boîte noire.
- **Lecture** via `javax.sound.sampled` (`Clip`) pilotée par un `AnimationTimer`, pour un curseur
  synchronisé au plus près. Les WAV transformés étant déjà ralentis x10 (signal dans la bande
  audible), la lecture PCM standard suffit.
- **Rendu** : spectrogramme pré-calculé en image, affiché par un `ImageView` recadré via `viewport`
  (zoom temps = découpe horizontale, zoom fréquence = tranche basse) ; curseur et axes sont de vrais
  nœuds (`Line`/`Label`) ; seule l'enveloppe min/max du sonogramme reste tracée sur `Canvas`.

## Remarque sur le JDK côté JitPack

Le composant compile sur **n'importe quel JDK 25+** : les modules JavaFX sont récupérés par Maven
sous forme d'artefacts OpenJFX classifiés (`${javafx.platform}`, réglé par les profils OS du
`pom.xml`), donc aucun JDK à JavaFX embarqué n'est nécessaire côté JitPack. Si JitPack ne propose
pas encore `openjdk25`, basculez le `jitpack.yml` sur l'installation manuelle d'un Temurin 25
(snippet commenté fourni dans le fichier).

## Licence

MIT.
