# Tests

Pile de test : **JUnit 5 + AssertJ + TestFX**. Voir [CONTRIBUTING.md](CONTRIBUTING.md) pour les
conventions générales.

## Deux familles de tests

**1. Logique pure (sans interface)** — c'est le bénéfice du MVVM : la logique d'affichage vit dans
`AudioViewModel` sous forme de méthodes **statiques**, vérifiables sans démarrer JavaFX. Avec le DSP,
ces tests **ne nécessitent pas d'affichage** :

| Test | Couvre |
| --- | --- |
| `AudioViewModelTest` | fenêtre/zoom, auto-échelle du sonogramme, cadrage fréquentiel, graduations « rondes », format des libellés. |
| `FftTest` | FFT radix-2 (linéarité, Parseval, signaux connus). |
| `SpectrogramTest` | STFT (dimensions, magnitudes en dB). |
| `AudioSampleTest` | décodage WAV → échantillons normalisés. |

**2. Rendu (TestFX)** — `AudioViewTest` : smoke test qui instancie le composant (chargement du FXML,
injection `@FXML`, chargement d'un WAV) et vérifie le rendu de base. Il **nécessite un affichage**.

> Règle : préférer tester la logique dans `AudioViewModel` (statiques pures) plutôt qu'en TestFX
> chaque fois que c'est possible. TestFX est réservé au câblage vue ↔ ViewModel.

## Lancer les tests

```bash
./mvnw verify                          # affichage requis (TestFX)
xvfb-run -a ./mvnw verify              # sans écran (Linux headless)
xvfb-run -a ./mvnw -B -Pquality-gate verify   # quality-gate complet (= CI)
```

La CI GitHub Actions (`.github/workflows/ci.yml`) lance le quality-gate headless sur une **matrice de
JDK** : Temurin 25 (JDK vanilla, prouve la portabilité de la config Maven, condition du build
JitPack) et Zulu 25 jdk+fx (filet de sécurité, aligné sur le poste de dev).

## Mode classpath

Les tests **compilent et s'exécutent en classpath** (`useModulePath=false` côté compiler et
surefire), alors que l'artefact publié est **modulaire**. Ne pas réactiver le module-path pour les
tests sans vérifier que TestFX passe toujours (injection `@FXML` sur le paquet ouvert à `javafx.fxml`,
chargement de la ressource FXML…).

## Couverture & qualité des tests

- **JaCoCo** (couverture) : gate **≥ 80 %** des lignes sur les classes pures `Fft`, `Spectrogram`,
  `AudioSample`, vérifié pendant `verify`. Rapport HTML : `target/site/jacoco/index.html`. Le rendu
  JavaFX d'`AudioView` est volontairement **hors gate** (non testable raisonnablement en headless).
- **PITest** (mutation testing) : mesure si les tests **attrapent vraiment** les bugs (qualité, pas
  seulement couverture). Non lié à `verify` (lent), scopé aux mêmes classes pures :

  ```bash
  ./mvnw test-compile pitest:mutationCoverage
  ```

  Rapport : `target/pit-reports/`.

## Conventions de test

- Messages d'assertion en **français** (`.as("…")`), pour des erreurs lisibles.
- **Duplication tolérée** entre fichiers de test : chaque test est autonome, pas de helper partagé.
- Les fixtures audio sont **générées à la volée** (WAV PCM 16 bits en mémoire) plutôt que stockées.
