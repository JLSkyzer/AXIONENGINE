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
- **celle de la fiche**, si elle en donne une, dans la section `tuning` :

```json
--8<-- "java/addon-temoin/src/main/resources/axion/definitions/upgradable_crate.json"
```

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
| `modifiers(nom)` | chaque modificateur, dans l'ordre de la pose : sa cause, son origine (`api`, `commande`, `sauvegarde`), s'il est actif, et sinon pourquoi |

## 5. Le tester

Un GameTest pose l'objet, le règle, et vérifie :

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:upgrade"
```

Tu le lances comme les autres GameTests ([Tester ton addon](tester.md)).

## 6. Voir et régler en jeu

La commande `/axion params` montre les paramètres de l'objet que tu vises, et chaque modificateur avec son origine.
Elle demande les droits d'opérateur (niveau 2) :

```text
/axion params @e[type=axion:assembly,limit=1,sort=nearest]
```

```text
AXION ENGINE — example_addon:upgradable_crate : 1 paramètre(s), 1 modificateur(s)
  mass = 150 kg (base 200)
    example_addon:stage_one multiply_total -0.25 (api)
```

Ajoute un nom de paramètre pour voir aussi ses bornes. Pour essayer un réglage sans écrire de code :

```text
/axion params @e[type=axion:assembly,limit=1,sort=nearest] set essai:allegement mass add -20
/axion params @e[type=axion:assembly,limit=1,sort=nearest] remove essai:allegement
```

Chaque `set` et chaque `remove` est écrit dans le journal du serveur, avec qui l'a tapé.

## 7. Sauvegarde, joueurs, mises à jour

- **La sauvegarde** garde les modificateurs posés, dans leur ordre. Au rechargement, chacun se repose par les mêmes
  règles que `modify`.
- **Les joueurs** reçoivent les modificateurs, pas les valeurs : chacun refait le calcul avec la même fiche et
  obtient exactement les mêmes nombres. Côté client, `parameters()` se lit ; `modify` et `remove` y lèvent
  `IllegalStateException`.
- **Quand ta fiche change** — une plage resserrée, un rapport retiré —, un modificateur sauvegardé que les nouvelles
  bornes refusent n'est pas perdu : il reste posé, inactif, et le journal le dit (`E-7011`). `/axion params` le
  montre, et on peut le retirer.
