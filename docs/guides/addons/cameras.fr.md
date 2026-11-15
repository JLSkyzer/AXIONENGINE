# Caméras et tableau de bord

Quand un joueur s'assoit dans ton véhicule, AXION lui donne des **vues** — l'œil du conducteur, une caméra qui suit
derrière — et un **tableau de bord** à l'écran : vitesse, régime, rapport. Sans rien écrire, ton véhicule a déjà les
deux. Cette page montre comment choisir tes propres vues, poser une caméra sur ton modèle dans Blender, ajouter des
jauges, et dessiner un cadran entier en Java.

Les extraits Java viennent de l'addon témoin, compilé et testé à chaque changement d'AXION.

## 1. Ce que ton véhicule a déjà

Chaque siège a deux vues, que le joueur fait défiler avec **F5** (la touche de perspective de Minecraft, celle qu'il a
assignée) :

| Vue | Ce qu'on voit |
|---|---|
| `cockpit` | l'œil du joueur, assis ; il regarde librement autour de lui |
| `poursuite` | derrière le véhicule, à une distance tirée de sa taille ; la souris tourne autour, et la vue revient derrière après 1,5 s sans mouvement |

À pied, F5 reste celui de Minecraft. En descendant, le joueur retrouve la perspective qu'il avait avant de monter.

Le conducteur voit aussi le tableau de bord par défaut, en bas à droite : vitesse, régime, rapport. Un passager ne le
voit pas, sauf si ta fiche le dit (section 4).

## 2. Déclarer tes vues

Ta fiche (schéma 5) peut nommer ses propres vues dans `cameras`. Dès que la liste est là, elle **remplace** les deux
vues par défaut : F5 fait défiler les tiennes, dans l'ordre où tu les écris.

```json
"cameras": [
  { "name": "pilote", "kind": "cockpit", "seats": ["conducteur"], "eye": [0, 0.62, 0.05] },
  { "name": "loin", "kind": "follow", "distance": 9, "height": 2.5, "smoothing_ms": 250 },
  { "name": "capot", "kind": "fixed", "node": "cam_capot", "fov": 80, "look": { "yaw": 60, "pitch": 30 } }
]
```

Trois sortes de vues :

- **`cockpit`** — à la place de l'occupant. Sans `eye`, c'est l'œil du joueur, comme dans Minecraft. Avec `eye`, la
  caméra se place au node du siège, décalée de `[x, y, z]` mètres dans son repère.
- **`follow`** — la poursuite. Elle regarde dans le sens où roule le véhicule (son cap à l'arrêt et en marche
  arrière), lissée dans le temps ; un bloc entre le véhicule et la caméra la rapproche, comme la troisième personne de
  Minecraft. Sans `distance` ni `height`, elle se cadre sur la taille du véhicule.
- **`fixed`** — attachée à un node de ton modèle, qui suit son mouvement : un capot, un pare-chocs, un rétroviseur, la
  nacelle d'une grue. La section 3 montre comment le poser.

| Champ | Pour | Ce qu'il règle | Bornes |
|---|---|---|---|
| `name` | toutes | le nom de la vue, unique dans la fiche | — |
| `kind` | toutes | `cockpit`, `follow` ou `fixed` | — |
| `seats` | toutes | les sièges qui ont cette vue ; tous sans | des sièges de la fiche |
| `fov` | toutes | le champ de vision, en degrés ; celui du joueur sans | 30 à 110 |
| `eye` | `cockpit` | le décalage de l'œil, en mètres, dans le repère du siège | ±4 m |
| `distance` | `follow` | le recul, en mètres | 0,5 à 64 |
| `height` | `follow` | la hauteur au-dessus du véhicule, en mètres | -8 à 32 |
| `smoothing_ms` | `follow` | le temps que met la direction à suivre le véhicule (200 sans) | 0 à 5 000 |
| `recenter_ms` | `follow` | le temps sans souris avant de revenir derrière (1 500 sans) | 0 à 5 000 |
| `node` | `fixed` | le node du modèle où se pose la caméra | un node du modèle |
| `look` | `cockpit`, `fixed` | de combien le regard tourne : `yaw` de chaque côté, `pitch` en haut et en bas, en degrés | 0 à 180 |

Sans `look`, le regard est libre au cockpit et figé sur une vue fixe. Seize vues au plus. En jeu,
`/axion defs info <ta fiche>` liste les vues et le tableau de bord qu'AXION a retenus.

!!! warning "Refusé, jamais deviné"
    Un champ qui n'appartient pas à la sorte de la vue — un `distance` sur un cockpit, un `node` sur une poursuite —
    refuse la fiche en nommant le champ, comme une valeur hors de ses bornes. Tu le vois au chargement, dans le
    journal, plutôt qu'en jeu.

Le builder Java a les mêmes champs, générés depuis le même schéma :

```java
.camera("loin", "follow", c -> c.distance(9.0).height(2.5))
```

## 3. Poser une caméra sur ton modèle, dans Blender

Une vue `fixed` regarde depuis un node de ton modèle. Le plus simple est un **empty** :

1. Ajoute un empty (`Ajouter` → `Vide` → `Flèches`), place-le où doit être l'objectif, et donne-lui un nom clair :
   `cam_capot`.
2. Oriente-le : dans Blender, la vue regarde le long de sa flèche **Y** et son haut est sa flèche **Z**. Un empty sans
   rotation regarde donc vers le +Y de la scène. (À l'export, Blender passe ses axes au repère du glTF, où ce regard
   devient le −Z du node, celui d'une caméra glTF : c'est ce qu'AXION lit.)
3. Fais-en un enfant de la pièce qu'il doit suivre (une porte, une nacelle) : la vue suivra ce node, pas seulement la
   caisse.
4. Donne-lui le rôle `camera`. Dans la console Python de Blender, l'empty sélectionné :

    ```python
    bpy.context.object["axion"] = {"role": "camera"}
    ```

    Un node de rôle `camera` n'est jamais dessiné ni touché par les collisions. Le rôle n'est pas obligatoire — une
    vue `fixed` peut nommer n'importe quel node —, mais sans lui, un node qui porte de la géométrie se dessinerait.
5. À l'export glTF, coche `Inclure` → `Propriétés personnalisées` : c'est ainsi que le rôle arrive à AXION.

Dans ta fiche, la vue nomme le node par son nom :

```json
{ "name": "capot", "kind": "fixed", "node": "cam_capot", "look": { "yaw": 45, "pitch": 20 } }
```

## 4. Le tableau de bord

Le tableau de bord se règle dans `dashboard` :

```json
"dashboard": {
  "default": true,
  "seats": ["conducteur", "copilote"],
  "gauges": [
    { "name": "turbo", "kind": "dial", "source": "var.boost", "min": 0, "max": 2,
      "label": "gauge.monaddon.turbo", "unit": "bar", "anchor": "bottom_left" },
    { "name": "sport", "kind": "light", "source": "var.sport", "label": "gauge.monaddon.sport",
      "anchor": "bottom_left" }
  ],
  "widgets": ["example_addon:tachometer"]
}
```

- **`default`** : `false` retire le tableau par défaut (vitesse, régime, rapport) ; tes jauges restent.
- **`seats`** : les sièges qui voient le tableau. Sans, le conducteur seul.
- **`gauges`** : tes jauges, 32 au plus.
- **`widgets`** : des cadrans dessinés en Java par un mod (section 5), 16 au plus.

Une jauge a une sorte, une source, et le reste est facultatif :

| Sorte | Ce qu'elle dessine |
|---|---|
| `dial` | un cadran à aiguille, de `min` à `max` |
| `bar` | une barre qui se remplit de `min` à `max` |
| `text` | la valeur, écrite, suivie de son `unit` |
| `light` | un voyant, allumé quand la valeur atteint `min` (0,5 sans) |

La **source** est celle des animations procédurales : `vehicle.speed`, `engine.rpm`, `transmission.gear`,
`wheel.<roue>.compression`, `part.<pièce>.health`, `var.<variable>` pour une variable de ta fiche, et les autres de la
même liste. Une source mal écrite refuse la fiche, et un cadran ou une barre sans `min` et `max` — ou dont `max`
ne dépasse pas `min` — aussi.

| Champ | Ce qu'il règle | Bornes |
|---|---|---|
| `label` | une **clé de traduction**, que ton mod traduit dans ses fichiers de langue | — |
| `unit` | le texte écrit après la valeur | — |
| `min`, `max` | l'étendue du cadran ou de la barre ; le seuil d'un voyant | — |
| `anchor` | le coin ou le bord de l'écran : `top_left`, `top`, `top_right`, `left`, `center`, `right`, `bottom_left`, `bottom`, `bottom_right` | — |
| `offset` | un décalage `[x, y]` depuis l'ancre, en pixels de l'interface | ±4 096 |
| `size` | la taille, en pixels de l'interface (48 sans) | 8 à 512 |

Sans `offset`, les jauges d'une même ancre se rangent l'une au-dessus de l'autre, vers l'intérieur de l'écran, sans
jamais recouvrir la barre d'objets. Le tableau par défaut est le premier du coin bas droit.

## 5. Dessiner un cadran en Java : un widget

Une jauge déclarée couvre les cas courants. Pour un cadran à toi — un compte-tours avec sa zone rouge, un GPS, un écran
de radio —, ton mod enregistre un **widget** : une fonction qu'AXION appelle à chaque image, chez le client, avec une
toile où dessiner et le véhicule tel que le joueur le voit.

Le témoin dessine un compte-tours. Son identifiant :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleTachometer.java:id"
```

Le widget lui-même : un arc gradué, une aiguille, le régime écrit dessous. Il dit aussi sa taille et son ancre :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleTachometer.java:widget"
```

Il s'enregistre avec le reste de ton contenu :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleTachometer.java:register"
```

et ta fiche le nomme dans `dashboard.widgets` : `"widgets": ["example_addon:tachometer"]`.

Ce que la **toile** (`DashboardCanvas`) sait faire :

| Méthode | Effet |
|---|---|
| `width()`, `height()` | la taille du widget, en pixels de l'interface ; l'origine est en haut à gauche |
| `fill(x, y, l, h, argb)` | un rectangle plein |
| `line(x1, y1, x2, y2, argb)` | un segment — une aiguille, une graduation |
| `text(texte, x, y, argb)` | un texte tel quel |
| `translated(clé, x, y, argb, arguments…)` | un texte traduit dans la langue du joueur |
| `textWidth(texte)`, `lineHeight()` | de quoi centrer un texte |
| `texture(ressource, x, y, l, h)` | une image de ton mod, étirée au rectangle |

Ce qui sort de ton rectangle est coupé. L'échelle choisie par le joueur est déjà appliquée : tu dessines toujours en
pixels de l'interface.

Ce qu'il **lit** (`VehicleView`) : le même véhicule qu'un contrôleur ([Régler un véhicule](regler.md), section 6) —
vitesse, régime, rapport, entrée du conducteur, roues, variables et paramètres. Pour le conducteur, ce sont les
valeurs prédites par son client ; pour un passager, celles qu'il voit à l'écran.

**Ses règles** :

- il dessine à partir de ce qu'il lit et ne garde rien d'une image à l'autre ;
- aucune classe de Minecraft n'y paraît : le widget se charge sur un serveur sans écran, et se teste sans jeu
  (section 6) ;
- le mod qui l'enregistre est exigé des joueurs dès qu'une fiche le nomme, comme pour un contrôleur ; une fiche qui
  nomme un widget qu'aucun mod n'enregistre est refusée au chargement.

**Son coût est mesuré**, image par image. Une image qui dépasse le budget (`hud.widget_budget_ns`, 200 µs par défaut)
ou une exception compte une faute ; vingt de suite **désactivent le widget pour la session**, et le journal le dit en
nommant ton mod (`E-1011`). Le compte-tours du témoin prend environ 70 µs par image.

!!! tip "Beaucoup de petits traits ?"
    Chaque appel à la toile ne coûte presque rien : AXION les regroupe et les envoie à la carte graphique d'un bloc.
    Un segment de cent pixels reste un seul appel `line`.

## 6. Le tester

Une toile qui note ce qu'on lui demande suffit à vérifier un widget, sans écran. Le GameTest du témoin dessine le
compte-tours à 3 500 tr/min — la moitié de sa zone rouge — et vérifie que l'aiguille pointe droit vers le haut et que
le régime est écrit :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:tachometer"
```

Tu le lances comme les autres GameTests ([Tester ton addon](tester.md)).

## 7. Ce que règle le joueur

Le joueur garde la main sur ce qu'il voit, dans la section `[hud]` d'`axion-client.toml`
([Configuration](../CONFIGURATION.md)) :

| Option | Effet |
|---|---|
| `hud.dashboard` | `false` masque tout le tableau de bord, le tien compris |
| `hud.dashboard_scale` | son échelle, de 0,5 à 2 |
| `hud.speed_unit` | `kmh` ou `mph` pour la vitesse du tableau par défaut |

**F1** masque le tableau de bord avec le reste de l'interface. Ces options se rechargent sans redémarrer.

!!! info "Et les autres mods de caméra ?"
    Si un autre mod déplace aussi la caméra, AXION lui laisse la main et le dit une fois dans le journal. Les vues ne
    passent pas par le serveur : elles sont un choix du joueur, que le serveur ignore.
