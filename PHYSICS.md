# Physique

Ce que fait la physique d'AXION ENGINE aujourd'hui (jalon M3, et les joints de M4), et comment un
pack la pilote. La référence normative reste le cahier des charges (PARTIE 10, fiches 5.23 à
5.32) ; les choix d'implémentation sont dans les ADR cités. Les options se règlent dans
`axion-common.toml` : voir [CONFIGURATION.md](CONFIGURATION.md), sections `[sim]`, `[physics]`,
`[world]` et `[budgets]`.

## Le modèle

- **Moteur** : [`rapier3d`](https://rapier.rs) (ADR-002), dans le crate `ax-physics`. Ses types
  ne sortent pas du crate (R-1753). La rustdoc du crate donne la carte de ses modules.
- **Autorité serveur.** La simulation tourne sur le serveur ; le client reçoit les états et les
  interpole, sans jamais intégrer lui-même (INV-02).
- **Un monde par dimension**, créé au premier besoin, détruit quand plus aucune assembly ni aucun
  joueur ne l'occupe (R-610). Les positions monde sont en double précision ; chaque monde simule
  en simple précision autour d'une origine flottante (R-462).
- **Pas fixe** `sim.fixed_dt` (1/60 s par défaut), au plus `sim.max_substeps` sous-pas par tick.
  Un retard au-delà est abandonné, jamais rattrapé : pas de spirale (R-990).
- **Reproductible** sur une même machine et un même binaire (R-1020), pas d'une machine à
  l'autre (ADR-005). La chaîne de dommage bit-exacte est un autre sujet (C-16, M6).

## Le monde de Minecraft

Les blocs autour de chaque assembly deviennent des **tuiles de collision** par section de 16³
(C-38) : des boîtes regroupées, ou un champ de hauteur pour un terrain régulier, au plus
`world.tiles_per_tick` tuiles extraites par tick dans un rayon de `world.tile_radius` tuiles.
Frottement et rebond viennent du matériau physique dominant de la section, que résolvent les
mappages `data/<ns>/axion/block_materials/` — par bloc, puis par étiquette, puis le défaut
(§11.4, ADR-145) ; les matériaux et leur format sont décrits dans [MATERIALS.md](MATERIALS.md).
Les blocs de fluide deviennent des volumes de fluide, à la
densité de l'eau quel que soit le fluide pour l'instant : un corps qui y entre subit poussée
d'Archimède et traînée.

Les **entités vanilla** proches d'une assembly — celles que Minecraft tient pour solides ou
poussables, vivantes et non spectatrices, joueurs compris — y sont représentées par des proxies
cinématiques (capsule pour un être vivant, boîte sinon), recalculés à chaque tick (R-614,
ADR-123). Un choc contre une assembly pousse l'entité, joueurs exceptés (`physics.entity_push`),
et blesse une entité vivante comme une chute de même vitesse (`physics.entity_damage`).

## Le bloc `physics` d'une definition

```json
"physics": {
  "mass": 1650.0,
  "center_of_mass": [0, 0.45, -0.1],
  "linear_damping": 0.05,
  "angular_damping": 0.3,
  "ccd": true,
  "collision_group": "assembly",
  "collides_with": ["world", "assembly", "entity_proxy", "debris"]
}
```

| Champ | Sans déclaration | Effet |
|---|---|---|
| `mass` | calculée : densité × volume de chaque collider | masse du corps, en kg (0,001 à 10⁶) ; l'inertie suit, à la même répartition |
| `center_of_mass` | calculé depuis les colliders | centre de masse en repère du modèle ; l'inertie y est transportée |
| `linear_damping`, `angular_damping` | ceux de `rapier` | amortissements, positifs ou nuls |
| `ccd` | automatique (voir plus bas) | détection continue toujours active |
| `collision_group` | `assembly` | groupe dont le corps est membre |
| `collides_with` | le masque par défaut du groupe, sinon tous | groupes que le corps heurte ; `[]` n'en heurte aucun |

La description du corps traverse la frontière avec sa création (DM-08, ADR-130). Deux corps
n'interagissent que si chacun est membre d'un groupe que l'autre heurte. Les huit groupes
réservés — `world`, `assembly`, `part`, `debris`, `entity_proxy`, `sensor`, `particle`, `debug`
— ont les bits 0 à 7 ; les tuiles du monde sont membres de `world` et ne filtrent rien, les
proxies d'entités membres d'`entity_proxy` et ne heurtent qu'`assembly`.

Un pack déclare ses propres groupes, un par fichier (registre `axion:collision_group`, ADR-132
A1) : `data/<ns>/axion/collision_groups/<chemin>.json` déclare `<ns>:<chemin>`, et son seul
champ, `collides_with`, facultatif, est le masque par défaut du groupe — celui que prend une
definition qui le nomme sans `collides_with`. Les groupes tiers, triés par identifiant, ont les
bits 8 à 31, 24 au plus ; au-delà, ou s'il nomme un groupe inconnu, un groupe est refusé et
nommé au journal. Les groupes se chargent avant les definitions : une definition qui nomme un
groupe inconnu est refusée, avec son chemin. Un corps garde le filtre posé à sa création ; un
rechargement qui change le registre le dit au journal.

Les formes des colliders d'un corps dynamique sont des primitives, des enveloppes convexes de 4
à 256 points ou des composés d'au plus 64 formes ; jamais un maillage concave (INV-13).

## Corps et joints

Au schéma 2 (`"schema": 2`, format complet dans
[`docs/schema/definition-2.json`](docs/schema/definition-2.json)), une definition peut découper son
assembly en plusieurs corps reliés par des joints (C-34, [ADR-151](docs/decisions/ADR-151.md)). La
porte d'essai, que [`tools/assets/joints_essai.py`](tools/assets/joints_essai.py) génère avec un
pendule, une chaîne et une grille, en donne un exemple :

```json
"kind": "static",
"bodies": [{ "name": "montant", "root_node": "montant" }],
"joints": [{
  "name": "gond", "type": "revolute", "a": "montant", "b_node": "battant",
  "anchor": [-0.5, 0.0, 0.0], "axis": [0, 1, 0], "limits": [0.0, 1.6],
  "motor": { "target": 1.2, "stiffness": 4000, "damping": 400, "max_force": 5000 },
  "jam": { "curve": [[0.5, 0.0]], "friction": 50 }
}]
```

### Les corps

Chaque entrée de `bodies[]` crée un corps au node `root_node` de l'asset ; le `b_node` d'un joint en
crée un à ce node s'il n'est pas déjà la racine d'un corps. Une assembly a au plus 64 corps ; sans
`bodies` ni `joints`, elle reste un corps unique.

- Le premier corps déclaré est le **corps racine** : sa pose est celle de l'entité, et il prend le
  `kind` de la definition — le montant de la porte est fixe. Les autres corps sont dynamiques.
- Un node appartient au corps dont il est la racine, sinon à celui de son plus proche ancêtre qui en
  est une, et à défaut au corps racine. Ses colliders et ses meshes suivent ce corps. Chaque corps porte
  au moins un collider.
- `physics.mass` reste la masse de l'assembly, répartie entre les corps selon la masse que leurs
  densités leur donnent. `bodies[].mass` fixe celle d'un corps, les autres se partageant le reste ; des
  masses déclarées qui atteignent celle de l'assembly la font refuser.
- Les corps d'une assembly ne se touchent pas entre eux, sauf `physics.self_collision: true` ; même
  alors, deux corps reliés par un joint ne se touchent que si ce joint déclare `collide: true`.
- L'assembly reste l'unité d'activité : rayon de simulation, plafond de corps actifs — qui compte
  chacun de ses corps — et garde-fous agissent sur tous ses corps à la fois.

### Les joints

`a` nomme un corps déclaré, `b_node` le node racine de l'autre. `anchor` est le point du joint dans le
repère de `b_node`, son origine par défaut ; `axis` est son axe dans ce même repère. Les deux repères
sont pris à la pose de repos de l'asset : le joint ne déplace rien à l'apparition. Unités : blocs
(mètres), radians, newtons, newtons-mètres, joules.

| `type` | Ce qu'il laisse libre | Ses réglages |
|---|---|---|
| `fixed` | rien : il soude | — |
| `revolute` | la rotation autour de `axis` | `axis` (obligatoire), `limits` en radians, `motor`, `friction`, `jam` |
| `prismatic` | la translation le long de `axis` | `axis` (obligatoire), `limits` en blocs, `motor`, `friction`, `jam` |
| `spherical` | les trois rotations autour de l'ancrage | `friction` |
| `generic` | ce que disent ses `axes` | `axes` (obligatoire), `friction` |
| `rope` | tout, ses deux ancrages à `max_length` au plus l'un de l'autre | `max_length` (obligatoire) |
| `spring` | tout, ses deux ancrages rappelés à `rest_length` l'un de l'autre | `rest_length` et `stiffness` (obligatoires), `damping` |

Tous les types acceptent `break` et `collide`.

- **`axes`** (`generic`) : six entrées dans le repère de `b_node` — x, y, z linéaires, puis x, y, z
  angulaires —, chacune `locked`, `free` ou `limited` avec ses `limits`.
- **`motor`** : `speed` seule fait un moteur en vitesse ; `target` avec `stiffness`, et `damping`, un
  servo en position. `max_force` plafonne son effort.
- **`friction`** : une friction interne, qui freine l'axe libre sans le bloquer ni gêner le moteur.
- **`break`** : seuils de rupture en `force`, `torque` et `energy` ; le premier franchi rompt le joint,
  qui émet `JOINT_BROKEN`.
- **`jam`**, le grippage, sur un joint qui déclare des `limits` : `curve` donne, selon le
  désalignement de l'ancrage en blocs (abscisses positives, strictement croissantes), la fraction de
  plage qui reste, de 1 à 0. La plage se resserre autour de la position du moment, et la friction
  monte vers `jam.friction`. Un désalignement remis à 0 rend les réglages d'origine. Rien en jeu ne
  produit encore de désalignement : C-42 (M6) puis l'action `jam_joint` (M7) le feront.
- Un réglage absent n'agit pas : pas de limite, pas de moteur, pas de friction, pas de seuil, pas de
  grippage.

### Erreurs, réseau et debug

- Au chargement, un réglage sans objet pour son type, un champ obligatoire manquant, un axe nul, des
  limites croisées, une courbe de grippage mal formée ou un `a` qui ne nomme aucun corps déclaré font
  refuser la definition, avec le chemin du champ fautif (`$.joints[0].limits`). Les nodes se résolvent
  contre l'asset dès qu'il est compilé (ADR-156) : un `root_node` ou un `b_node` qui n'en nomme aucun, un
  joint dont les deux bouts tombent sur le même corps, plus de 64 corps avec ceux que créent les joints,
  ou une racine de corps sous un node animé, fait refuser la definition, avec le chemin et le nom fautifs
  (`E-7001 … — $.joints[0].b_node « capot » : aucun node de ce nom dans l'asset`). Une assembly qui la
  nomme reste inerte.
- `modules.joints = false` (voir [CONFIGURATION.md](CONFIGURATION.md)) ignore les joints, avec un
  avertissement : les corps déclarés restent séparés.
- Rupture et grippage se décident au serveur. Les clients reçoivent la pose de chaque corps relative au
  corps racine et l'état de chaque joint, et dessinent chaque corps à sa pose. Ces poses et ces états
  ne sont pas encore écrits dans la sauvegarde : c'est le chantier persistance de M4 (ADR-151 D8).
- `/axion debug joints on` trace chaque joint d'un ancrage à l'autre, avec son axe : vert, ambre s'il
  est grippé, rose s'il est rompu. `/axion debug limits on` trace la plage permise de chaque axe limité
  — celui d'un `revolute` ou d'un `prismatic`, les axes `limited` d'un `generic` —, et sa position :
  un arc pour une rotation, un segment pour une translation, en bleu tant que le joint est libre. Ces
  overlays ne montrent rien chez un client relié à un serveur distant, qui ne simule pas.

## Activité, plafonds et CCD

- **Rayon de simulation.** Un corps hors de `sim.simulation_radius` de tout joueur s'endort
  avant le pas, et se réveille à son retour (R-612). La gestion endort, elle ne supprime
  jamais.
- **Plafond.** Au plus `budgets.max_active_bodies` corps actifs sur le serveur, toutes
  dimensions confondues : au-delà, les plus éloignés d'un joueur s'endorment, et les plus
  anciens d'abord à distance égale (R-613).
- **CCD.** Déclarée, elle reste active. Sinon, elle s'active d'elle-même au sous-pas où le
  corps parcourt plus de la moitié de sa plus petite dimension (`|v|·dt > 0,5 × min_dim`, fiche
  5.23), et se coupe en deçà. La plus petite dimension est celle de la pièce la plus fine du
  corps. Contre le seul décor fixe, `rapier` balaie de toute façon un corps rapide.
- **Garde-fous.** Vitesses bornées à 300 m/s et 100 rad/s par défaut ; un dépassement est
  borné, signalé (`CLAMPED`) et journalisé au plus une fois par minute et par corps (R-180). Un
  état non fini ou hors du monde ramène le corps à son dernier état valide et l'endort
  (R-181, `E-2030`).

## Forces et événements

Gravité de dimension (`physics.gravity`), échelle de gravité par corps, vent, traînée relative au
vent, portance des surfaces déclarées (R-1000) et flottabilité s'appliquent à chaque sous-pas
(§10.6).

La simulation émet des événements par lot borné (`physics.max_events_per_tick`, R-1011) :
sommeil et réveil, début et fin de contact, impulsion de contact au-delà de
`physics.contact_event_threshold`, entrée et sortie de capteur, rupture d'un joint
(`JOINT_BROKEN`), entrée et sortie de grippage (`JOINT_JAMMED`). Un événement perdu faute de
place est compté, jamais tu.

## Dégradation

Le gouverneur FM-21 juge le **p95** de la durée du pas sur des fenêtres de 100 ticks, jamais un
pic (R-1870). Trois fenêtres de suite au-dessus de `budgets.sim_ns_per_tick` font descendre d'un
palier : une itération de solveur et un sous-pas de moins, puis le rayon de simulation réduit d'un
quart, puis le plafond de corps actifs réduit de moitié. Il remonte d'un palier après 30 s sous
60 % du budget. Chaque transition est journalisée avec sa cause (R-1880). Sous surcharge, la
reproductibilité cède : c'est la définition même d'une dégradation (R-1890).

## Mesures

`/axion metrics export` écrit les métriques de la session, dont la consommation et les
dépassements de chaque budget (`axion.budget.<budget>.consumed` et `.overruns`). Côté physique,
sont alimentés : la durée du pas (`sim_ns_per_tick`), celle du submit (`submit_ns`), les corps
actifs (`max_active_bodies`) et la mémoire native (`native_mem_bytes`). Celle-ci est **toute**
comptée — mondes `rapier` compris — par l'allocateur global de la bibliothèque native, par arène
(`axion.mem.<arène>.bytes`) : `PERSISTENT` par défaut, `SCRATCH` pour ce qu'un job alloue
(INV-08, ADR-133).

Le pas suit l'ordre des étapes de R-660 : commandes, puis les étapes du monde — intégration,
joints et contacts répétés à chaque sous-pas, seul bloc où l'ordre revient en arrière —, puis la
récolte et l'écriture des états. Chaque étape exécutée est journalisée, et en build de debug l'ordre
est vérifié à chaque tick (T-390). La durée de chaque étape existante est exposée :
`axion.sim.last_step_commands_ns`, `_integration_ns`, `_joints_ns`, `_contacts_ns`,
`_serialization_ns` (T-391) ;
les budgets par étape viennent avec le gouverneur (C-77, ADR-144).

## Requêtes spatiales

Le monde physique répond aux lancers de rayon, balayages de forme et recouvrements, seuls ou par
lots (C-39, T-380..T-384). Un filtre choisit les groupes de collision, exclut un corps — celui qui
interroge — et dit que faire des capteurs. Une requête ne modifie jamais le monde, et lit la
géométrie laissée par le dernier pas, l'état du début du tick (R-651). Elles ne sont appelées
aujourd'hui que côté natif : aucune API Java ni fonction de la frontière ne les expose encore.

## Ce qui n'existe pas encore

Sauvegarde des corps secondaires et de l'état des joints (chantier persistance de M4), joints entre
assemblies (C-48, M5), producteurs du grippage (C-42 en M6, action `jam_joint` en M7), matériau des
parts d'assembly jusqu'au natif (DM-07, second temps d'ADR-145 ; contact neutre en attendant),
densité propre à chaque fluide, chaîne de dommage et déformation (C-41..C-45, M6), pilotage des
véhicules (M5).
