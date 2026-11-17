# Attaches

Une **attache** relie deux assemblies : une remorque à sa voiture, une voiture tirée à la corde, un module monté sur une
machine, un treuil accroché à un arbre. Elle se pose en jeu et se défait ; ta fiche déclare seulement **où** ton modèle
peut s'attacher, et **comment**. Ces points s'appellent des **sockets**.

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

Quand un joueur clique, `attach` prend le socket **compatible et libre le plus proche** d'une autre assembly, dans
`distance` blocs autour du tien (`attachment.snap_distance`, 2 par défaut). S'il n'en trouve pas et que ton socket
s'ancre au monde, il s'accroche au **bloc que vise le joueur**. Le joueur lit en bas de l'écran ce qui a été fait, ou
pourquoi rien ne l'a été. `detach` défait l'attache du socket.

Le type retenu est le premier que les deux sockets acceptent, dans l'ordre `rigid`, `hitch`, `rope`, `winch`, `magnet`.

## 5. Ce qui se décide à l'attache

| Grandeur | D'où elle vient |
|---|---|
| la longueur d'une corde | `rope.length` d'un des sockets, celui d'où part l'attache d'abord ; sans, la distance entre les deux sockets au moment de l'attache |
| la longueur d'un treuil au départ | la distance entre les deux sockets, ramenée dans ses bornes |
| les bornes et la vitesse d'un treuil | `winch.*` ; sans : 0,5 à 30 blocs, 1 bloc/s |
| l'angle d'une rotule | `hitch.cone` ; sans : 45° |
| la rupture | la plus faible des deux `max_force`, des deux `max_torque`, des deux `misalignment` |

Une attache `rigid`, `hitch` ou `magnet` dont les sockets s'écartent de plus que `misalignment` pendant une seconde
rompt. Une corde s'écarte par nature : seule sa force la rompt.

Une corde `rope` tendue s'étire comme une sangle de remorquage, quelques centimètres sous l'effort : molle puis
retendue d'un coup, elle amortit le choc au lieu de le transmettre en un instant. Un câble de treuil reste raide, pour
suivre sa longueur. Une corde tient donc mieux qu'un attelage rigide aux à-coups d'une conduite au clavier ; elle ne
pousse jamais : un véhicule remorqué qui ne freine pas rattrape celui qui le tire.

## 6. Le treuil, l'aimant, la prise de force

- **Le treuil** suit sa variable (`winch.variable`) : **négative**, il enroule jusqu'à sa plus courte longueur ;
  **positive**, il déroule jusqu'à la plus longue ; **nulle**, il garde la longueur qu'il a. Lie la variable à une
  commande d'un siège (voir [Régler un véhicule](regler.md)) et le joueur pilote le treuil.
- **L'aimant** n'existe que tant que sa variable (`magnet.variable`) est vraie : éteint, il lâche ; on ne l'attache pas
  éteint.
- **La prise de force** recopie, à chaque tick, chaque variable de `transfer.power` dans la variable nommée de l'autre
  assembly. Un tracteur dont `pdf` tourne entraîne l'outil qui lit `pto_in`, dans ses animations ou ses contrôleurs.

## 7. Par l'API Java

`assembly.attachments()` pose, ancre, défait et lit les attaches d'une assembly, sur le serveur :

```java
AttachResult resultat = remorqueuse.attachments().attach("attelage", remorque, "timon");
if (!resultat.attached()) {
    LOGGER.info("pas d'attelage : {} — {}", resultat.status(), resultat.message());
}
remorqueuse.attachments().anchor("treuil", 120, 64, -35);   // au bloc
remorqueuse.attachments().setWinchLength("treuil", 4.0);    // la longueur cible du treuil
remorqueuse.attachments().detach("attelage");
```

`attach` prend un quatrième argument pour imposer le type (`"rope"`). Le résultat dit pourquoi une attache est refusée :

| Statut | Pourquoi |
|---|---|
| `UNKNOWN_SOCKET` | un socket nommé n'existe pas |
| `INCOMPATIBLE` | aucun type commun, ou une forme que l'autre n'épouse pas |
| `SOCKET_BUSY` | un des deux sockets est déjà attaché |
| `CYCLE` | les deux assemblies sont déjà reliées, directement ou par une chaîne (`E-8030`) |
| `CHAIN` | la chaîne dépasserait `attachment.max_chain` attaches de bout en bout (8) |
| `UNREACHABLE` | deux dimensions, la même assembly, ou une definition pas encore compilée |
| `NOT_ANCHORABLE` | le socket ne déclare pas `anchor` |
| `MAGNET_OFF` | un aimant éteint |
| `REFUSED` | un écouteur l'a refusée |

`list()` rend chaque attache : l'autre extrémité ou le bloc, le type, la longueur, l'écart et la tension du dernier tick.

**Suivre et refuser** : un mod enregistre un écouteur avec le reste de son contenu,

```java
registrar.registerAttachmentListener(new AttachmentListener() {
    @Override
    public boolean allow(Assembly assembly, String socket, Optional<Assembly> other, String otherSocket, String kind) {
        return !"magnet".equals(kind) || estAutorise(assembly);
    }

    @Override
    public void detached(Assembly assembly, String socket, Optional<Assembly> other, String otherSocket, String cause) {
        // cause : detached, broken, misaligned, magnet_off ou removed
    }
});
```

Il voit chaque attache du serveur, celles d'un joueur comme celles d'un autre mod.

## 8. Les commandes

| Commande | Effet |
|---|---|
| `/axion attach <cible> <socket> <autre> <socket_autre>` | attache deux assemblies |
| `/axion anchor <cible> <socket> <x y z>` | ancre un socket à un bloc |
| `/axion detach <cible> <socket>` | défait l'attache d'un socket |
| `/axion attaches <cible>` | liste les attaches d'une assembly, leur longueur, leur écart, leur tension |

## 9. Sauvegarde et chunks

Une attache se sauvegarde avec l'assembly d'où elle part. Si l'autre extrémité est déchargée — son chunk, le joueur
parti — ou passe dans une autre dimension, l'attache est **suspendue**, pas supprimée : elle revient quand l'autre
revient. Au redémarrage du serveur, chaque attache se rétablit dès que ses deux extrémités sont chargées. Seule la
destruction d'une extrémité la défait pour de bon.

## 10. Ce que voit le joueur

Une corde ou un câble de treuil se dessine entre les deux sockets, ou jusqu'au bloc d'ancrage : **tendu**, droit ;
**avec du mou**, il pend d'autant, et se pose sur le sol qu'il rencontre. Une attache rigide, une rotule ou un aimant n'ont rien à dessiner : les deux sockets
se touchent.

Tes animations procédurales lisent les attaches, comme les roues ou les variables :

| Source | Ce qu'elle lit |
|---|---|
| `attach.<socket>.attached` | 1 si le socket est attaché, 0 sinon |
| `attach.<socket>.length` | la longueur de sa corde, en blocs |
| `attach.<socket>.tension` | la force qu'elle transmet, en newtons |
| `socket.<socket>.misalignment` | l'écart entre les deux sockets d'une attache rigide, d'une rotule ou d'un aimant, en blocs |

Le tambour d'un treuil qui tourne avec la longueur du câble :

```json
{ "node": "tambour", "channel": "rotation", "axis": "x", "source": "attach.treuil.length", "scale": -12.5 }
```
