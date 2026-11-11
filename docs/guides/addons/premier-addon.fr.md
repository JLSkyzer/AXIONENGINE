# Premier addon

Un addon AXION est un mod Forge ordinaire qui déclare du contenu à AXION : des definitions (ce qu'est un objet, un
véhicule), leurs modèles 3D, des matériaux physiques, des pneus. AXION ne lit rien de lui-même : il ne connaît que ce
que les mods lui envoient ([ADR-162](https://github.com/JLSkyzer/AXIONENGINE/blob/master/docs/decisions/ADR-162.md)).

Cette page déclare une caisse de 40 kg, d'abord en JSON, puis en Java.

## 1. Dépendre d'AXION

Dans `META-INF/mods.toml`, AXION est requis des deux côtés, client et serveur :

```toml
[[dependencies.monmod]]
    modId = "axion"
    mandatory = true
    versionRange = "[1.0,)"
    ordering = "AFTER"
    side = "BOTH"
```

Dans `build.gradle`, l'API seulement, à la compilation :

```groovy
dependencies {
    compileOnly "dev.axion:axion-api:<version>"
}
```

!!! warning "Ne jamais embarquer `axion-api`"
    AXION fournit l'API en jeu. Un addon qui l'embarque dans son JAR place le paquet `dev.axion.api` dans deux mods à
    la fois.

!!! info "Pas encore publié"
    L'artefact `dev.axion:axion-api` n'est pas encore publié : il le sera avec la tranche 5 d'ADR-162, et cette page
    dira alors où le trouver.

## 2. Ranger le contenu

Le contenu se range sous `axion/`, à la racine des ressources du mod :

```text
src/main/resources/
└── axion/
    ├── definitions/         caisse.json
    ├── models/              caisse.glb
    ├── physics_materials/
    ├── collision_groups/
    ├── block_materials/
    └── tires/
```

L'espace de noms est le modId : `axion/definitions/caisse.json` devient la definition `monmod:caisse`, et
`axion/models/caisse.glb` le modèle `monmod:models/caisse.glb`. Un mod ne déclare que dans le sien.

## 3. Envoyer le contenu à AXION

Le mod envoie à AXION un `ContentProvider` par les messages entre mods de Forge, pendant `InterModEnqueueEvent` :

```java
@Mod("monmod")
public final class MonMod {

    public MonMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::envoyer);
    }

    private void envoyer(InterModEnqueueEvent event) {
        InterModComms.sendTo(AxionContent.MOD_ID, AxionContent.REGISTER,
                () -> (ContentProvider) registrar -> registrar.registerFolder("axion/"));
    }
}
```

AXION appelle chaque fournisseur une fois, sur le thread principal, dans l'ordre des modId. Le `registrar` n'est
valable que pendant l'appel : ne le garde pas.

## 4. Une definition, en JSON ou en Java

Les deux formes donnent le même document : même forme canonique, même schéma, même empreinte.

=== "JSON"

    `src/main/resources/axion/definitions/caisse.json` :

    ```json
    {
      "schema": 3,
      "asset": "monmod:models/caisse.glb",
      "kind": "rigid_object",
      "physics": { "mass": 40 }
    }
    ```

    Le dossier est lu par `registrar.registerFolder("axion/")`.

=== "Java"

    Dans le fournisseur, avec le builder généré depuis le schéma :

    ```java
    registrar -> registrar.register("caisse",
            DefinitionBuilder.rigidObject("monmod:models/caisse.glb")
                    .physics(p -> p.mass(40))
                    .build())
    ```

    Le builder vérifie les types ; les bornes (une masse de 0,001 à 1 000 000 kg) se vérifient au chargement, comme
    pour un fichier.

Une erreur — doublon, champ hors bornes, modèle absent — est écrite au journal du jeu avec le mod, le fichier ou
l'appel en cause ; la definition fautive est écartée, le reste du contenu se charge.

## 5. Itérer sans redémarrer

Active `content.dev_reload` dans `config/axion-common.toml` (ou lance le jeu avec
`-Daxion.content.dev_reload=true`), puis, après chaque modification :

1. recopie les ressources par le build : `./gradlew processResources`, ou la construction de ton IDE ;
2. tape `/axion dev reload` en jeu (opérateur).

AXION rappelle ton fournisseur, relit ton dossier depuis le disque et renvoie le registre aux joueurs. En solo, les
modèles du client se recompilent aussi. Les objets déjà posés gardent leur ancienne definition : fais-en apparaître
un nouveau.

!!! tip "Le fournisseur doit pouvoir être rappelé"
    `/axion dev reload` rappelle le même `ContentProvider`. Il ne doit faire qu'enregistrer, sans autre effet.
