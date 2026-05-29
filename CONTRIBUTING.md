# Contribuer à `audio-view`

Document destiné à l'**équipe pédagogique** et aux contributeurs du composant. Côté étudiant, rien à
lire ici : le composant se consomme en boîte noire (voir le [README](README.md)).

Voir aussi [TESTING.md](TESTING.md) pour tout ce qui touche aux tests.

## Prérequis

- **JDK 25** (Temurin, Zulu, OpenJDK… peu importe : JavaFX n'a pas besoin d'être embarqué dans le JDK,
  cf. README § « Remarque sur le JDK »).
- Tout passe par le **wrapper Maven `./mvnw`** (committé) : pas besoin d'un Maven global.
- `git`. Un **affichage** est requis pour les tests TestFX (sinon `xvfb-run`, voir TESTING.md).

## Architecture (MVVM + fx:root + FXML + CSS)

Package unique `fr.iutaix.vigiechiro.audio`, aligné sur ce que les étudiants voient en R2.02 (FXML en
TP3, MVVM en TP4) :

| Élément | Rôle |
| --- | --- |
| `AudioView` (public) | La **vue** : custom control `fx:root` (`extends BorderPane`) qui charge `AudioView.fxml` au constructeur (`setRoot`/`setController`) et se lie au ViewModel. Seule classe de l'API. |
| `AudioViewModel` | Le **cœur réactif** (MVVM) : état observable + logique (décodage/FFT en tâche de fond, lecture, fenêtres temps/fréquence, auto-échelles, colormap), **sans dépendance au graphe de scène** → testable sans interface. |
| `AudioSample`, `Fft`, `Spectrogram`, `AudioPlayer` | Services internes (décodage WAV, FFT maison, STFT, lecture `Clip`). |
| `AudioView.fxml`, `audio-view.css` | Vue déclarative + thème (ressources du même package). |

`module-info.java` **exporte** uniquement le package public et l'**ouvre à `javafx.fxml`**
(`opens … to javafx.fxml`) pour l'injection. Rendu : spectrogramme en `ImageView` (recadré par
`viewport`), curseur et axes en nœuds (`Line`/`Label`), seule l'enveloppe du sonogramme reste sur
`Canvas`.

## Conventions de code

- **Google Java Format** appliqué par Spotless. Lancer `./mvnw spotless:apply` avant de committer ;
  le hook `.githooks/pre-commit` le fait aussi automatiquement (installé par `git-build-hook` au
  premier `./mvnw`). Spotless n'est **pas** attaché à `verify` (volontaire : ne pas bloquer la CI sur
  du formatage).
- **Langue française** : libellés d'IHM, Javadoc et messages d'assertion. **Identifiants ASCII**
  (sans accents).
- Commentaires : expliquer le **pourquoi**, jamais le **quoi** (le code dit déjà le quoi).
- Les **classes internes restent package-private** ; ne pas les exporter dans `module-info.java`.

## Commits : Conventional Commits

Messages en **français**, format `type: sujet`, axés sur le « pourquoi ». Le type pilote la version
publiée (semantic-release) :

| Type | Effet sur la version |
| --- | --- |
| `feat:` | version **mineure** (`x.Y.0`) |
| `fix:` | version **patch** (`x.y.Z`) |
| `BREAKING CHANGE:` (pied de message) ou `type!:` | version **majeure** (`X.0.0`) |
| `refactor:` `docs:` `ci:` `chore:` `test:` `build:` `perf:` | **aucune** release |

Toujours créer de **nouveaux** commits (pas d'`--amend`).

## Qualité : le quality-gate

Avant de pousser, ce build doit être **vert** :

```bash
xvfb-run -a ./mvnw -B -Pquality-gate verify
```

Il enchaîne :

- **maven-enforcer** : JDK ≥ 25, Maven ≥ 3.9, et **JavaFX interdit en scope `compile`/`runtime`**
  (doit rester `provided`).
- **PMD** (`pmd-ruleset.xml`, mêmes règles smells/refactoring que R2.03) : **bloquant** sous
  `-Pquality-gate` (en `verify` simple il ne fait que reporter).
- **SpotBugs** (effort `Max`, seuil `Medium`). Les faux positifs sont écartés dans
  `spotbugs-exclude.xml` — **toute nouvelle exclusion doit être justifiée** par un commentaire
  (ex. `CT_CONSTRUCTOR_THROW` du pattern fx:root, `HSM_HIDING_METHOD` du `getClassCssMetaData`).
- **JaCoCo** : couverture **≥ 80 %** des lignes sur les classes pures `Fft`, `Spectrogram`,
  `AudioSample` (le rendu JavaFX est hors gate, non testable raisonnablement en headless).
- **tests** TestFX + JUnit (voir [TESTING.md](TESTING.md)).

## Invariants à ne pas casser (le contrat)

- **Aucune dépendance runtime hors JDK + JavaFX.** La FFT maison est volontaire (résolution triviale
  sur JitPack / module-path). Depuis la refonte, `javafx.fxml` est requis (toujours du JavaFX, fourni
  par le template consommateur).
- **JavaFX reste `provided` et classifié** (`${javafx.platform}`), sur les **quatre** modules
  `javafx-base`/`graphics`/`controls`/`fxml`. Ne pas retirer les classifiers (les artefacts OpenJFX
  sans classifier sont des jars vides sans `module-info`) ni passer JavaFX en `compile`.
- **API publique additive uniquement.** Ne jamais retirer/renommer un membre public d'`AudioView`
  (c'est un contrat vu par les étudiants). Un changement cassant impose un commit majeur
  (`BREAKING CHANGE:`).
- **Compatibilité FXML** : garder le **constructeur sans argument** ; régler le composant par
  propriétés/setters (jamais par le constructeur), pour rester insérable en `<AudioView/>`.
- **Hooks de personnalisation CSS** (classes `audio-view*`, pseudo-classe `:light`, propriété
  `-fx-wave-color`) : les conserver, c'est l'API de thème.
- **Cible Java 25 / JavaFX 25** (`maven.compiler.release = 25`).
- Le `try/catch` autour de `player.load` est **intentionnel** (lecture optionnelle si pas de
  périphérique audio) : ne pas le supprimer.

## Workflow : ajouter / modifier une fonctionnalité

1. Travailler sur `main` (ou via une PR : la CI tourne sur les PR).
2. `./mvnw spotless:apply`, quality-gate vert, et **valider le rendu visuellement** (le dépôt
   [`audio-view-demo`](https://github.com/IUTInfoAix-S201/audio-view-demo) sert à ça).
3. Committer avec un message conventionnel (`feat:`/`fix:`…).
4. Pousser sur `main`.

## Release (automatique)

Aucun `git tag` manuel. Sur **push sur `main`**, le workflow `release` (semantic-release) :

1. calcule la version depuis les commits, crée le **tag `vX.Y.Z`** + la **release GitHub** (notes
   générées), via le `GITHUB_TOKEN` (pas de PAT, pas de PR) ;
2. **smoke-teste / préchauffe JitPack** : il `curl` l'artefact du tag fraîchement créé
   (`@semantic-release/exec`) — si JitPack n'arrive pas à builder, le run vire au rouge ici.

Ensuite, pour propager au demo : bumper `audio.view.version` (→ le nouveau `vX.Y.Z`) dans son
`pom.xml`, committer (`feat:`/`chore:`) et pousser. (Tant que le tag n'est pas publié sur JitPack, le
demo ne compile pas contre une API qui n'y existe pas encore.)
