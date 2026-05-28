# VigieChiro Audio View

Composant JavaFX 25 affichant le **sonogramme** (amplitude / temps) et le **spectrogramme**
(fréquence / temps) d'un fichier WAV, avec curseur de lecture synchronisé et zooms temps / fréquence.
Brique fournie aux étudiants de la SAE 2.01 (R2.02 + R2.03) : le calcul FFT, le rendu Canvas et la
lecture audio sont internes au composant. On l'instancie avec un chemin de fichier et on observe ses
propriétés.

## Distribution via JitPack

Le composant est publié sur [JitPack](https://jitpack.io) à partir d'un tag Git. Aucune
authentification n'est nécessaire côté étudiant pour un dépôt public.

### Publier une version (équipe pédagogique)

1. Pousser le code sur `main`.
2. Créer un tag de version, par exemple :
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. Optionnel : déclencher le build sur https://jitpack.io en cherchant `IUTInfoAix-S201/audio-view`
   puis en cliquant sur « Get it » en face du tag (cela amorce le cache et révèle les logs de build).

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
> du template sans l'imposer.

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
| `dispose()` | — | Libère le clip audio. |

Le spectrogramme et le sonogramme sont gradués automatiquement : axe **temps** (s) en bas, axe
**fréquence** (kHz) à gauche, avec des pas « ronds » qui suivent les zooms. Un clic sur le sonogramme
ou le spectrogramme déplace le curseur de lecture.

## Développement

```bash
mvn spotless:apply   # formatage Google Java Format avant commit
mvn verify           # compilation + tests (TestFX)
```

Les tests utilisent TestFX et nécessitent un affichage. En local sous Linux sans écran, ou en CI,
lancez-les derrière un serveur X virtuel :

```bash
xvfb-run -a mvn verify
```

Le wrapper Maven n'est pas committé ici ; pour l'ajouter, exécutez une fois `mvn -N wrapper:wrapper`.

## Choix techniques

- **Aucune dépendance runtime** hors JDK + JavaFX : la FFT (radix-2) est embarquée, ce qui rend la
  résolution triviale sur JitPack et sur le module-path.
- **Artefact modulaire** (`module-info.java`) exportant uniquement `fr.iutaix.vigiechiro.audio` :
  le décodage, la FFT et la lecture restent une boîte noire pour les étudiants.
- **Lecture** via `javax.sound.sampled` (`Clip`) pilotée par un `AnimationTimer`, pour un curseur
  synchronisé au plus près. Les WAV transformés étant déjà ralentis x10 (signal dans la bande
  audible), la lecture PCM standard suffit.
- **Rendu** : spectrogramme pré-calculé en image puis recadré à l'affichage (zoom temps = découpe
  horizontale, zoom fréquence = découpe verticale), sonogramme tracé en enveloppe min/max par
  colonne.

## Remarque sur le JDK côté JitPack

Le composant compile sur **n'importe quel JDK 25+** : les modules JavaFX sont récupérés par Maven
sous forme d'artefacts OpenJFX classifiés (`${javafx.platform}`, réglé par les profils OS du
`pom.xml`), donc aucun JDK à JavaFX embarqué n'est nécessaire côté JitPack. Si JitPack ne propose
pas encore `openjdk25`, basculez le `jitpack.yml` sur l'installation manuelle d'un Temurin 25
(snippet commenté fourni dans le fichier).

## Licence

MIT.
