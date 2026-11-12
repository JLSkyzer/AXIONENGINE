# Régler un véhicule

Un kit d'amélioration change **un** véhicule, pas son modèle : plus de couple, des freins plus mordants, quelques
kilos en moins. AXION appelle ces grandeurs des **paramètres**. Chaque véhicule règle les siens, véhicule en
mouvement ; le réglage est sauvegardé avec lui et envoyé aux joueurs qui le voient. Les autres véhicules du même
modèle n'en voient rien.

Cette page montre comment un mod d'améliorations s'y prend. Les extraits viennent de l'addon témoin, compilé et testé à
chaque changement d'AXION.

## 1. Les paramètres

| Paramètre | Unité | Ce qu'il règle |
|---|---|---|
| `mass` | kg | la masse de l'objet, véhicule ou non ; son centre de masse ne bouge pas |
| `engine.torque` | N·m | le pic du couple moteur ; toute la courbe suit, sa forme gardée |
| `engine.max_rpm` | tr/min | le régime du limiteur ; en boîte automatique, les passages de rapport suivent |
| `transmission.final_drive` | — | le rapport de pont |
| `transmission.shift_time` | s | la durée d'un passage de rapport |
| `transmission.gear.<rang>` | — | un rapport de la boîte : `transmission.gear.1` est la première, `transmission.gear.-1` la marche arrière |
| `brakes.front`, `brakes.rear`, `brakes.handbrake` | N·m | le couple des freins avant, arrière et du frein à main |
| `tires.grip` | facteur | l'adhérence de chaque pneu ; 1 : celle qu'il déclare |
| `suspension.stiffness`, `suspension.damping` | facteur | la raideur et l'amortissement de chaque suspension ; 1 : ceux qu'elle déclare |
| `steering.max_angle` | rad | le braquage maximal |
| `aero.drag`, `aero.downforce_front`, `aero.downforce_rear` | — | la traînée et l'appui aérodynamique |

**Un objet n'a que les paramètres que sa fiche déclare.** `mass` n'existe que si la fiche déclare `physics.mass`. Les
paramètres du moteur n'existent que pour un véhicule à moteur, et il y a un rapport par entrée de `gears`.
`parameters().names()` te donne la liste d'un objet, et `has(nom)` te dit si un paramètre y est.

!!! info "Une raideur réglée change la hauteur"
    Comme sur une vraie voiture, une suspension plus raide s'enfonce moins sous le poids : le véhicule se relève un
    peu. Une masse réglée, elle, garde la hauteur, parce que la raideur se compte par kilogramme.

## 2. Poser un modificateur

Un réglage ne remplace pas la valeur : il **pose un modificateur**, au nom d'une **cause**. La cause est un
identifiant dans l'espace de noms de ton mod — un par kit :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleUpgrades.java:cause"
```

Puis chaque paramètre que le kit change reçoit son modificateur :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleUpgrades.java:fit"
```

Un modificateur a une **opération**, la même que pour les attributs de Minecraft :

| Opération | Ce qu'elle fait | Exemple sur 200 |
|---|---|---|
| `ADD` | ajoute la quantité | `-50` donne 150 |
| `MULTIPLY_BASE` | ajoute un pourcentage de la base ; ceux de tous les mods s'additionnent | `0.25` donne 250 |
| `MULTIPLY_TOTAL` | multiplie le résultat ; chaque mod multiplie à son tour | `-0.25` donne 150 |

La valeur effective vaut donc :

```text
(base + somme des add) × (1 + somme des multiply_base) × produit des (1 + multiply_total)
```

La **base** est la valeur de la fiche, telle que l'administrateur du serveur l'a peut-être réglée. Reposer un
modificateur de la même cause sur le même paramètre remplace le précédent.

Retirer le kit, c'est retirer sa cause, d'un seul appel :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleUpgrades.java:remove"
```

Ces appels se font **sur le serveur**, sur son thread principal : là où tourne une commande, un clic sur un objet, un
événement de Forge côté serveur.

## 3. Les bornes : refusé, jamais tronqué

Un réglage reste dans deux limites :

- **celle du moteur** : une valeur que la fiche aurait pu déclarer. Une masse négative, un couple infini sont
  impossibles ;
- **celle de la fiche**, si elle en donne une, dans `tuning.ranges` :

```json
--8<-- "java/addon-temoin/src/main/resources/axion/definitions/upgradable_crate.json"
```

(La variable `ballast` et `tuning.modifiers` sont expliqués à la [section 5](#5-une-variable-de-la-fiche-règle-un-paramètre).)

Avec le *builder*, c'est `.tuning(tuning -> tuning.range(Parameters.MASS, range -> range.min(100).max(300)))`. Une
plage s'exprime dans l'unité du paramètre, et doit contenir la base.

Un modificateur qui ferait sortir sa valeur de ces limites est **refusé** : `modify` lève une
`IllegalArgumentException` et rien ne change. AXION ne coupe jamais la valeur à la borne sans le dire. Le message
nomme le paramètre, la valeur qu'il aurait eue et les bornes :

```text
« mass » vaudrait 75.0 kg, hors de [100, 300] (plage de la definition, tuning.ranges)
```

**Retirer réussit toujours**, même quand un autre modificateur en dépendait. Prends un mod qui retire un allègement
dont le turbo d'un autre mod avait besoin pour rester sous le couple maximal. Le turbo n'est pas effacé : il reste
posé, **inactif**, avec sa raison, et redevient actif dès que la valeur rentre dans ses bornes.

## 4. Plusieurs mods sur le même véhicule

Ton kit et celui d'un autre mod se composent, sans s'écraser :

- chaque mod pose ses modificateurs sous sa propre cause, et ne retire que les siens ;
- l'ordre de calcul est celui des noms de cause, pas celui de la pose : le résultat est le même, quel mod ait réglé
  en premier ;
- un véhicule porte au plus 64 modificateurs ; au-delà, `modify` refuse.

Pour lire l'état d'un paramètre :

| Méthode | Ce qu'elle rend |
|---|---|
| `value(nom)` | la valeur effective |
| `base(nom)` | la valeur de la fiche |
| `range(nom)` | les bornes effectives |
| `unit(nom)` | l'unité |
| `modifiers(nom)` | chaque modificateur : ceux de la fiche d'abord, puis les posés dans leur ordre ; sa cause, son origine (`variable`, `api`, `commande`, `sauvegarde`), s'il est actif, et sinon pourquoi |

## 5. Une variable de la fiche règle un paramètre

Un réglage peut aussi venir de la fiche elle-même : une **variable** — basculée par une touche du siège, posée par une
interaction ou par ton code — règle un paramètre. C'est la section `tuning.modifiers`. Dans la caisse réglable plus
haut, chaque cran de `ballast` ajoute 40 kg :

```json
"modifiers": {
  "ballast": { "parameter": "mass", "operation": "add", "variable": "ballast", "values": [0, 40, 80] }
}
```

- **La quantité** : `values` en donne une par valeur d'une variable `int` (depuis son `min`) ou `bool` (`false`, puis
  `true`). Pour une variable `float`, `scale` la donne proportionnelle : quantité = `scale` × valeur.
- **L'opération** est l'une des trois de la [section 2](#2-poser-un-modificateur) ; ce modificateur se compose avec
  ceux des mods.
- **La cause** est `<id de la fiche>/<nom>` — ici `example_addon:upgradable_crate/ballast`. Elle ne se pose ni ne se
  retire par l'API : elle suit sa variable.
- **La variable doit être `"replicate": "all"`** : chaque joueur refait le calcul, il lui faut la valeur. La fiche
  est refusée sinon.
- **Rien de plus n'est sauvegardé** : la variable l'est si elle est `persist`, et le modificateur en découle.

Avec le *builder* : `.tuning(tuning -> tuning.modifier("ballast", Parameters.MASS, "add", "ballast", m -> m.values(0,
40, 80)))`.

La fiche est refusée au chargement si une quantité, à elle seule, sortait le paramètre de ses bornes. En jeu, une
écriture de la variable qui le ferait sortir de ses bornes — avec les modificateurs d'un autre mod, par exemple — est
**refusée** : la touche ne fait rien et le journal le dit (`E-7010`), `variables().set` lève une
`IllegalArgumentException`. Un retour automatique, comme une commande `hold` relâchée, passe toujours, comme un
retrait.

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:ballast"
```

!!! example "Le mode sport du buggy d'exemple"
    Le buggy d'AXION a un mode sport sur la commande 2 (touche H par défaut) : une seule variable, `sport`, règle
    trois paramètres — couple +30 %, limiteur +500 tr/min, adhérence +20 %. Sa fiche est dans le dépôt d'AXION,
    `content/examples/axion/definitions/example/buggy.json`.

## 6. Le tester

Un GameTest pose l'objet, le règle, et vérifie :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:upgrade"
```

Tu le lances comme les autres GameTests ([Tester ton addon](tester.md)).

## 7. Voir et régler en jeu

La commande `/axion params` montre les paramètres de l'objet que tu vises, et chaque modificateur avec son origine.
Elle demande les droits d'opérateur (niveau 2) :

```text
/axion params @e[type=axion:assembly,limit=1,sort=nearest]
```

```text
AXION ENGINE — example_addon:upgradable_crate : 1 paramètre(s), 2 modificateur(s)
  mass = 150 kg (base 200)
    example_addon:upgradable_crate/ballast add 0 (variable)
    example_addon:stage_one multiply_total -0.25 (api)
```

Ajoute un nom de paramètre pour voir aussi ses bornes. Pour essayer un réglage sans écrire de code :

```text
/axion params @e[type=axion:assembly,limit=1,sort=nearest] set essai:allegement mass add -20
/axion params @e[type=axion:assembly,limit=1,sort=nearest] remove essai:allegement
```

Chaque `set` et chaque `remove` est écrit dans le journal du serveur, avec qui l'a tapé.

## 8. Sauvegarde, joueurs, mises à jour

- **La sauvegarde** garde les modificateurs posés, dans leur ordre. Au rechargement, chacun se repose par les mêmes
  règles que `modify`.
- **Les joueurs** reçoivent les modificateurs et les variables, pas les valeurs : chacun refait le calcul avec la même
  fiche et obtient exactement les mêmes nombres. Côté client, `parameters()` se lit ; `modify` et `remove` y lèvent
  `IllegalStateException`.
- **Quand ta fiche change** — une plage resserrée, un rapport retiré —, un modificateur sauvegardé que les nouvelles
  bornes refusent n'est pas perdu : il reste posé, inactif, et le journal le dit (`E-7011`). `/axion params` le
  montre, et on peut le retirer.
