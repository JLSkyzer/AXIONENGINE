# Attaches

Une **attache** relie deux assemblies : une remorque à sa voiture, une voiture tirée à la corde, un module monté sur une
machine, un treuil accroché à un arbre. Elle se pose en jeu et se défait ; ta fiche déclare seulement **où** ton modèle
peut s'attacher, et **comment**. Ces points s'appellent des **sockets**.

!!! info "Une page qui grandit"
    Cette page décrit d'abord la déclaration des sockets (schéma 6). Les gestes en jeu, l'API Java, le treuil et la
    prise de force s'y ajoutent au fil du chantier des attaches.

## 1. Les sortes d'attache

| Type | Ce qu'il fait | Exemple |
|---|---|---|
| `rigid` | soude les deux sockets | un module monté, une voiture sanglée sur un plateau |
| `hitch` | une rotule qui pivote dans un cône | l'attelage d'une remorque |
| `rope` | une distance maximale, souple | remorquage à la corde, amarrage |
| `winch` | une corde dont la longueur se pilote | un treuil qui enroule et déroule |
| `magnet` | une attache qui existe tant qu'une variable est vraie | un électroaimant de grue |

Pour faire monter un joueur ou une entité, ce sont les **sièges** qui s'en chargent, pas les attaches.

## 2. Poser un socket dans Blender

Un socket est un node de ton modèle — le plus simple est un **empty** — placé là où l'attache se fait : la boule
d'attelage, l'anneau de remorquage, la sortie du câble d'un treuil.

1. Ajoute un empty, place-le au point d'attache et donne-lui un nom clair : `attelage`.
2. Fais-en un enfant de la pièce qui porte l'attache (le châssis, le bras d'une grue).
3. Donne-lui le rôle `socket`. Dans la console Python de Blender, l'empty sélectionné :

    ```python
    bpy.context.object["axion"] = {"role": "socket"}
    ```

    Un node de rôle `socket` n'est jamais dessiné. Le rôle est **obligatoire** : un socket qui nomme un node sans ce
    rôle refuse la fiche, en nommant le node.
4. À l'export glTF, coche `Inclure` → `Propriétés personnalisées`.

## 3. Déclarer tes sockets

Au schéma 6, chaque socket est un objet. Le plus court suffit :

```json
"sockets": [ { "name": "attelage", "accepts": ["hitch", "rope"] } ]
```

Sans `node`, le socket se pose sur le node qui porte son nom. Un exemple plus complet :

```json
"sockets": [
  { "name": "boule", "node": "socket_towbar", "accepts": ["hitch", "rope"],
    "tag": "ball_50", "mates": ["coupler_50"], "max_force": 60000, "hitch": { "cone": 40 } },
  { "name": "treuil", "node": "sortie_cable", "accepts": ["winch"], "anchor": true,
    "winch": { "speed": 0.8, "min_length": 0.5, "max_length": 30, "variable": "treuil" },
    "transfer": { "power": { "pdf": "pto_in" } } }
]
```

| Champ | Ce qu'il règle | Bornes |
|---|---|---|
| `name` | le nom du socket, unique dans la fiche | — |
| `node` | le node du modèle, de rôle `socket` ; le nom du socket sans | un node du modèle |
| `accepts` | les types d'attache que le socket prend | au moins un des cinq |
| `tag` | la forme du socket (une boule de 50 mm, un crochet) | nom simple `[a-z0-9_.-]` |
| `mates` | les formes qu'il épouse ; toutes sans | 16 noms simples |
| `max_force`, `max_torque` | au-delà, l'attache rompt, en N et N·m | positifs |
| `misalignment` | l'écart entre les deux sockets, en mètres, au-delà duquel l'attache rompt | 0,001 à 16 |
| `anchor` | le socket peut s'accrocher à un bloc du monde | — |
| `hitch.cone` | de combien la rotule balance de chaque côté, en degrés, sur ses deux axes de balancement ; elle tourne librement sur elle-même | 0 à 180 |
| `rope.length` | la longueur de la corde, en mètres | 0,1 à 256 |
| `winch.speed` | la vitesse d'enroulement, en m/s | 0 à 20 |
| `winch.min_length`, `winch.max_length` | les longueurs extrêmes du câble | 0,1 à 256 |
| `winch.variable` | la variable qui pilote le treuil | une variable de la fiche |
| `magnet.variable` | la variable qui allume l'aimant | une variable de la fiche |
| `transfer.power` | la prise de force : chaque variable de ta fiche, recopiée dans la variable nommée de l'autre assembly | 16 au plus |
| `transfer.damage` | la part d'un choc qui traverse l'attache | 0 à 1 |

Trente-deux sockets au plus.

**Deux sockets s'attachent** s'ils ont un type en commun, et si chacun épouse la forme de l'autre quand il déclare des
`mates`. Quand les deux déclarent les paramètres d'un même type, ceux du socket d'où part l'attache l'emportent ; pour
les seuils de rupture, c'est le plus faible des deux.

!!! warning "Refusé, jamais deviné"
    Un bloc de paramètres pour un type que le socket n'accepte pas — un `rope` sur un socket qui n'accepte que
    `hitch` — refuse la fiche en nommant le champ, comme un treuil dont `max_length` ne dépasse pas `min_length`.

!!! note "Le transfert de dommage"
    `transfer.damage` se déclare et se garde dès maintenant ; le choc ne traverse l'attache qu'à partir du jalon des
    impacts (M6), quand AXION mesure l'énergie d'un choc.

Le builder Java a les mêmes champs, générés depuis le même schéma :

```java
.socket("attelage", new String[] {"hitch", "rope"}, s -> s.tag("ball_50").maxForce(40000.0)
        .hitch(h -> h.cone(40.0)))
```

## 4. Les interactions `attach` et `detach`

Un joueur attache et détache en cliquant un node de ton modèle. L'interaction nomme le socket qu'elle vise :

```json
"interaction": [
  { "node": "attelage", "action": "attach", "socket": "attelage", "kinds": ["hitch"], "distance": 3 },
  { "node": "attelage", "action": "detach", "socket": "attelage" }
]
```

| Champ | Pour | Ce qu'il règle |
|---|---|---|
| `socket` | `attach`, `detach` | le socket visé, obligatoire |
| `kinds` | `attach` | les types permis par ce geste, parmi ceux que le socket accepte ; tous sans |
| `distance` | `attach` | à quelle distance chercher l'autre socket, en mètres (0,1 à 16) |

En jeu, `attach` prendra le socket compatible et libre le plus proche d'une autre assembly, ou le bloc visé pour un
socket qui s'ancre au monde : ce geste arrive avec la suite du chantier, et cette page le détaillera.
