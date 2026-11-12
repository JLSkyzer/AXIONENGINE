# Ton premier addon

Dans cette page, tu déclares une **caisse de 40 kg** à AXION, d'abord avec un fichier JSON, puis en Java, et tu la
recharges sans redémarrer le jeu. Si un mot t'échappe, survole-le ou ouvre le [glossaire](glossaire.md).

Tous les extraits viennent de l'**addon témoin** d'AXION
([`java/addon-temoin`](../../../java/addon-temoin/src/main/java/com/example/addon/ExampleAddon.java) sur GitHub) :
un vrai mod, compilé à chaque build d'AXION. Ce que tu lis ici marche.

## 0. Télécharger le modèle de mod

Le plus simple pour commencer : partir d'un mod qui marche déjà.

[:material-download: Télécharger le modèle d'addon](https://maven.eriniumlab.com/api/maven/latest/file/snapshots/dev/axion/axion-addon-template?extension=zip){ .md-button .md-button--primary }

Décompresse-le, ouvre le dossier dans IntelliJ IDEA (*File › Open*, choisis le dossier), puis lance le jeu avec la
tâche Gradle `runClient`, ou dans un terminal :

```bash
./gradlew runClient
```

Le premier lancement télécharge Minecraft, Forge et AXION : compte quelques minutes. Sous Windows, la commande est
`gradlew.bat runClient`. Le fichier `README.md` du modèle dit comment le renommer à ton nom.

!!! question "D'où vient AXION dans ce projet ?"
    Du dépôt Maven d'AXION, `maven.eriniumlab.com`, que le `build.gradle` du modèle déclare. Tu n'as rien à
    installer à la main :

    ```groovy
    --8<-- "java/addon-temoin/modele/build.gradle:depot"
    ```

    `compileOnly` : ton mod compile contre l'API, mais ne l'embarque pas — AXION la fournit en jeu. `runtimeOnly` :
    AXION se lance avec ton mod quand tu testes.

## 1. Dire à Forge que ton mod a besoin d'AXION

Chaque mod a un fichier `src/main/resources/META-INF/mods.toml` qui le présente à Forge. On y ajoute AXION comme
dépendance :

```toml
--8<-- "java/addon-temoin/src/main/resources/META-INF/mods.toml:axion"
```

!!! question "Pourquoi `side = \"BOTH\"` ?"
    AXION simule les objets sur le serveur et les dessine chez chaque joueur : il doit être installé des deux côtés,
    et ton addon aussi. Sans AXION, Forge refuse de lancer le jeu et dit lequel manque.

## 2. Ranger ton contenu

Ton contenu se range sous `axion/`, à la racine des ressources du mod. Chaque sorte de contenu a son dossier :

```text
src/main/resources/
└── axion/
    ├── definitions/         crate.json    ← la fiche de ta caisse
    └── models/              crate.gltf    ← sa forme 3D, exportée de Blender
```

D'autres dossiers existent pour aller plus loin : `physics_materials/`, `tires/`, `collision_groups/`,
`block_materials/`.

!!! question "Comment AXION nomme ta caisse ?"
    Par le chemin du fichier, précédé du modId : `axion/definitions/crate.json` devient `example_addon:crate`, et
    `axion/models/crate.gltf` devient `example_addon:models/crate.gltf`. Tu n'écris jamais ces noms à la main ailleurs
    que dans tes fichiers.

## 3. La fiche de ta caisse

`src/main/resources/axion/definitions/crate.json` :

```json
--8<-- "java/addon-temoin/src/main/resources/axion/definitions/crate.json"
```

| Champ | Ce qu'il dit |
|---|---|
| `schema` | la version du format de fiche ; garde `3` |
| `asset` | le modèle 3D de l'objet |
| `kind` | le genre : `rigid_object` pour un objet qui tombe et roule, `vehicle` pour un véhicule… |
| `physics.mass` | la masse, en kilogrammes (de 0,001 à 1 000 000) |

## 4. Envoyer ton contenu à AXION

C'est le seul code Java dont ton premier addon a besoin. Quand Forge charge ton mod, il appelle son constructeur, qui
s'inscrit pour le moment où les mods s'envoient des messages :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddon.java:constructor"
```

À ce moment-là, ton mod envoie à AXION un **fournisseur** : le morceau de code qu'AXION appellera pour connaître ton
contenu.

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddon.java:provider"
```

`registerFolder("axion/")` envoie tout ton dossier `axion/`. La ligne suivante montre l'autre façon de faire : la
même sorte de fiche, écrite en Java avec le *builder*, pour une caisse de 400 kg.

!!! question "JSON ou Java ?"
    Les deux donnent exactement la même fiche, vérifiée de la même façon. Le JSON suffit pour commencer ; le Java sert
    quand tu veux calculer une fiche, par exemple dix variantes d'une même caisse.

## 5. Voir ta caisse en jeu

Lance le jeu, ouvre un monde en créatif, puis tape :

```text
/axion spawn example_addon:crate ^ ^1 ^3
```

Ta caisse apparaît trois blocs devant toi et tombe au sol. `example_addon:heavy_crate` fait apparaître celle de
400 kg.

## 6. Modifier sans redémarrer

Redémarrer Minecraft à chaque essai prend du temps. AXION peut recharger ton contenu en pleine partie :

1. active l'option `content.dev_reload` dans `config/axion-common.toml` (ou lance le jeu avec
   `-Daxion.content.dev_reload=true`) ;
2. après chaque modification, recopie tes ressources : `./gradlew processResources`, ou la construction de ton IDE ;
3. tape `/axion dev reload` en jeu.

AXION relit ton dossier et recompile tes modèles. Les caisses déjà posées gardent leur ancienne fiche : fais-en
apparaître une nouvelle pour voir le changement.

!!! warning "Ton fournisseur doit pouvoir être rappelé"
    `/axion dev reload` rappelle ton fournisseur. Il ne doit faire qu'enregistrer ton contenu, rien d'autre.

## Si ça ne marche pas

Ouvre le journal du jeu (`logs/latest.log`) et cherche le nom de ton mod (`example_addon`) sur les lignes qui
commencent par `AXION`. Chaque erreur y nomme la fiche ou le fichier, et ce qui ne va pas : un doublon, un champ hors
des bornes, un modèle introuvable. La fiche fautive est écartée, le
reste se charge.
