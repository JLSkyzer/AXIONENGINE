# Physique

Ce que fait la physique d'AXION ENGINE aujourd'hui (jalon M3), et comment un pack la pilote. La
référence normative reste le cahier des charges (PARTIE 10, fiches 5.23 à 5.32) ; les choix
d'implémentation sont dans les ADR cités. Les options se règlent dans `axion-common.toml` :
voir [CONFIGURATION.md](CONFIGURATION.md), sections `[sim]`, `[physics]`, `[world]` et
`[budgets]`.

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
Frottement et rebond viennent du matériau dominant de la section
(`axion/world/block_materials.json`). Les blocs de fluide deviennent des volumes de fluide, à la
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
| `collides_with` | tous | groupes que le corps heurte ; `[]` n'en heurte aucun |

La description du corps traverse la frontière avec sa création (DM-08, ADR-130). Les groupes
se nomment parmi les huit réservés — `world`, `assembly`, `part`, `debris`, `entity_proxy`,
`sensor`, `particle`, `debug` — et deux corps n'interagissent que si chacun est membre d'un
groupe que l'autre heurte. Les tuiles du monde sont membres de `world` et ne filtrent rien ; les
proxies d'entités sont membres d'`entity_proxy` et ne heurtent qu'`assembly`. Un autre nom est
refusé à la validation : le registre des groupes tiers reste à concevoir (ADR-132).

Les formes des colliders d'un corps dynamique sont des primitives, des enveloppes convexes de 4
à 256 points ou des composés d'au plus 64 formes ; jamais un maillage concave (INV-13).

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
`physics.contact_event_threshold`, entrée et sortie de capteur. Un événement perdu faute de
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
actifs (`max_active_bodies`) et la mémoire native comptée (`native_mem_bytes`) — dont la mémoire
des mondes `rapier` ne fait pas encore partie (ADR-133).

## Ce qui n'existe pas encore

Liaisons entre corps et leurs ruptures (C-34), matériaux de contact déclarés par les assemblies
(DM-07 ; contact neutre en attendant), densité propre à chaque fluide, chaîne de dommage et
déformation (C-41..C-45, M6), pilotage des véhicules (M5).
