# Faire évoluer son contenu

Une assembly sauvegardée garde son état — la pose de chacun de ses corps, l'état de chacun de ses joints — pendant que
ta definition change : tu ajoutes une porte, tu retires un joint, tu réordonnes tes corps
([ADR-175](../../decisions/ADR-175.md)). Ce qui se retrouve par son nom est restauré ; le reste est dit, jamais
appliqué à un mauvais élément.

## Les corps et les joints se retrouvent par leur nom

Depuis le schéma de sauvegarde 6, chaque corps est rangé sous le nom de son node racine, et chaque joint sous le nom que
ta definition lui donne (`joints[].name`). Réordonner tes corps ou tes joints, ou changer l'index d'un node en
réexportant ton modèle, ne mélange donc plus rien : « capot » garde l'état de « capot ».

| Ce qui change dans ta definition | Ce que devient l'état sauvegardé |
|---|---|
| un corps ou un joint **renommé** | l'ancien est perdu, le nouveau repart de sa pose de repos |
| un corps ou un joint **ajouté** | il prend sa pose de repos : un corps, relative au corps racine restauré et à sa vitesse ; un joint, intact |
| un corps ou un joint **retiré** | son état est perdu |
| le **corps racine** | toujours restauré : il est la pose de l'assembly |

Renommer sans le dire, c'est perdre : la restauration ne sait pas que « portiere » est l'ancienne « porte ». Pour
renommer sans perdre, déclare une migration (plus bas, « Révision, migrations et remplacement »).

## Ce que dit le journal

Quand quelque chose est perdu ou nouveau, le journal du serveur le dit **une fois par assembly**, sous le code `E-7012` :

```text
AXION : E-7012 l'état sauvegardé d'une assembly de monmod:voiture est restauré en partie :
perdu — corps « coffre », joint « attelage » ; nouveau, à sa pose de repos — corps « porte »
```

Rien d'autre ne change : l'assembly vit, au repos là où elle n'a pas d'état.

## Révision, migrations et remplacement

Au schéma 7, une definition déclare sa **révision** et ce qui mène une sauvegarde d'une révision à la suivante
([ADR-175](../../decisions/ADR-175.md) D3) :

```json
"revision": 3,
"migrations": [
  { "from": 1, "rename": { "nodes": { "porte_g": "portiere_gauche" } } },
  { "from": 2, "rename": { "variables": { "phare": "feux" }, "joints": { "gond": "charniere" } } }
],
"replaces": [ "monmod:ancienne_voiture" ]
```

- **`revision`** : un entier, 1 par défaut. Monte-la quand tu renommes quelque chose qu'une sauvegarde nomme.
- **`migrations`** : une par révision que tu quittes (`from`), qui **renomment** — `nodes` (corps et état des nodes),
  `joints`, `variables`, `seats` (les sièges d'une variable par siège), `sockets` (des attaches), `parameters`. Une
  assembly sauvegardée à la révision 1, chargée sous la révision 3, passe par les deux. Une migration ne crée ni ne
  calcule rien. Son `from` précède la révision de la definition, et deux migrations ne partent pas de la même. Les
  renommages d'une même migration valent tous à la fois : deux noms peuvent s'échanger.
- **`replaces`** : les identifiants de definitions que celle-ci remplace. Une assembly sauvegardée sous l'ancien
  identifiant s'y relie au lieu de rester inerte ; si une definition porte encore cet identifiant, elle le garde.
  Sans alias, une definition retirée laisse ses assemblies inertes et intactes.

Un contenu qui change **sans** nouvelle révision se restaure par nom, et le journal le dit une fois.

## Recharger sans perdre ses assemblies

Quand tu recharges tes definitions (`/reload`, ou en développement, à la sauvegarde d'un fichier), les assemblies déjà
posées sont **reliées** à la definition nouvelle ([ADR-175](../../decisions/ADR-175.md) D5) : elles gardent leur place,
leur vitesse, leurs corps, leurs attaches et leurs occupants. Le journal dit chaque reliure.

- **Ce qui est relié** : une assembly dont la definition a changé de contenu, ou a été remplacée (`replaces`). Une
  definition qui n'a pas changé ne relie rien.
- **Comment** : l'état est repris tel qu'une sauvegarde le garde, mené de la révision de l'assembly à celle de la
  definition par tes `migrations`, puis lu contre la definition nouvelle, **par nom**. Ce qui n'existe plus est dit au
  journal et perdu ; ce qui est nouveau part de sa pose de repos.
- **Les occupants** : chacun garde le siège qui porte le même nom — ou celui que la migration lui donne —, même s'il a
  changé de rang. Ceux dont le siège est supprimé prennent ensuite les premiers libres, sans déloger personne ; sans
  place, ils sont éjectés (`NO_SEAT`).
- **Le rythme** : `content.rebind_per_tick` (4 par défaut) assemblies par tick. Les autres vivent, jusqu'à leur tour,
  entièrement sur la definition d'avant — jamais sur un mélange des deux.
- **Un tick sans corps** : le corps natif est retiré puis recréé au tick suivant ; l'assembly n'a pas bougé.
- **Lire la révision** : `assembly.revision()` dit la révision de la definition que l'assembly lit ; elle monte quand
  la reliure a eu lieu. `axion.entity.rebound` et `axion.entity.rebind_ns` mesurent les reliures.

## Les sauvegardes d'avant les noms

Une assembly sauvegardée avant le schéma 6 n'a pas de noms. AXION nomme ses corps d'après l'asset chargé — le node que
chacun portait — et rapproche ses joints par rang, seulement si leur nombre n'a pas changé ; le journal dit que l'état
est « d'avant les noms ». À la première sauvegarde suivante, elle est au schéma 6 : plus de rapprochement par index.
