# Tester ton addon

Deux filets t'évitent de découvrir une faute en jouant : le **validateur**, qui vérifie ton contenu sans lancer le
jeu, et les **GameTests**, qui le font vivre dans un vrai serveur. Le modèle d'addon a déjà les deux. Et en jeu,
une commande te dit ce qui est chargé, et pourquoi le reste ne l'est pas.

## 1. Le validateur, sans lancer le jeu

```bash
./gradlew checkAxionContent
```

Il lit ton dossier `src/main/resources/axion/` et lui applique **exactement** les règles du jeu : il fait tourner le
moteur d'AXION, sans Minecraft, et compile chacun de tes modèles et chacune de tes fiches contre son modèle. Si tout va
bien :

```text
AXION — vérification du contenu de example_addon (…/src/main/resources/axion)
  1 models
  2 definitions
Aucune faute : le contenu se charge comme en jeu.
```

Sinon, chaque faute est nommée, dans les mots du journal du jeu :

```text
  FAUTE : example_addon:plume — E-7001 example_addon:axion/definitions/plume.json — $.physics.mass : -5 hors de [0.001, 1000000]
  FAUTE : example_addon:fantome — E-7001 example_addon:axion/definitions/fantome.json — $.asset : modèle introuvable : example_addon:axion/models/absent.glb
  FAUTE : example_addon:axion/models/casse.glb — compilation échouée (import : source gltf mal formée : …), code -3050 ; remplacé par axion:builtin/missing
```

!!! question "Pourquoi mon build échoue-t-il ?"
    `./gradlew build` lance le validateur : un contenu qui ne se chargerait pas en jeu fait échouer le build, et dit
    pourquoi. C'est voulu — mieux vaut l'apprendre là que devant un objet absent.

    ```groovy
    --8<-- "java/addon-temoin/modele/build.gradle:verification"
    ```

!!! info "Ce qu'il ne voit pas"
    Une fiche écrite en Java avec le *builder* n'existe qu'au lancement de ton mod. Les GameTests, ci-dessous, la
    couvrent.

## 2. Les GameTests, dans un vrai serveur

Un GameTest pose une scène dans un monde, attend, et vérifie. AXION te donne les aides, dans
`dev.axion.api.testing.AxionGameTests` :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:test"
```

| Aide | Ce qu'elle fait |
|---|---|
| `assertLoaded(helper, id)` | fait échouer le test, avec la cause, si la fiche n'est pas chargée |
| `spawn(helper, id, position)` | fait apparaître l'objet, à une position relative à la dalle |
| `succeedWhenAtRest(helper, objet)` | réussit quand l'objet ne bouge plus |
| `succeedWhenMoved(helper, objet, distance)` | réussit quand l'objet s'est déplacé d'au moins cette distance |

Lance-les :

```bash
./gradlew runGameTestServer
```

Le serveur démarre, joue les tests, dit `All 5 required tests passed :)` — les cinq du modèle — et s'arrête. Un test
qui échoue écrit sa cause au journal.

!!! question "D'où vient la dalle ?"
    `AxionGameTests.DALLE` désigne une dalle de 9 × 9 blocs que le modèle d'addon range dans
    `src/main/resources/data/example_addon/structures/dalle.nbt`. Forge cherche le gabarit d'un test dans l'espace de
    noms du mod qui le porte : si tu renommes ton mod, renomme aussi ce dossier.

## 3. En jeu : ce qui est chargé, et pourquoi le reste ne l'est pas

Un objet qui n'apparaît pas ? Demande-le au jeu, sans quitter la partie (il faut être opérateur) :

```text
/axion defs list
```

Ici, avec une fiche `plume.json` dont la masse est négative :

```text
AXION ENGINE — definitions : 3 chargée(s), 1 refusée(s)
  refusée : E-7001 example_addon:axion/definitions/plume.json — $.physics.mass : -5 hors de [0.001, 1000000]
  example_addon (3) : crate, heavy_crate, upgradable_crate
```

Les refus viennent en premier : ce sont eux que tu cherches. Pour une fiche précise :

```text
/axion defs info example_addon:crate
```

```text
AXION ENGINE — example_addon:crate
  genre : RIGID_OBJECT
  modèle : example_addon:axion/models/crate.gltf
  schéma : 3
  enregistrée par : example_addon — axion/definitions/crate.json
  empreinte : …
```

Pour une fiche refusée, la réponse est sa cause, la même que dans le journal et le validateur. La touche Tab complète
les noms, ceux des fiches refusées compris.
