# Pluie, neige et adhérence

Sous la pluie, une route glisse. AXION le simule **par véhicule** et **par matériau** ([ADR-176](../../decisions/ADR-176.md) D5) :
un véhicule qui roule sous un toit ne glisse pas, un pneu pluie adhère mieux que les autres, et la glace ne glisse pas plus mouillée
qu'elle ne le fait déjà.

## Ce que fait le moteur

- Toutes les 10 ticks, le serveur regarde si **la pluie tombe sur le véhicule** : ciel visible au-dessus de lui, biome où il ne neige
  pas. L'orage compte comme la pluie. Sous un toit, dans un tunnel, dans un biome froid, il ne pleut pas sur lui.
- Son **mouillé**, de 0 (sec) à 1, monte vers 1 en `physics.wet_rise_seconds` (2 s par défaut) sous la pluie, et redescend vers 0 en
  `physics.wet_dry_seconds` (30 s) sinon. Le mouillé ne se sauvegarde pas : un véhicule rechargé repart sec.
- Le coefficient d'adhérence de chaque roue est multiplié par `1 − mouillé × (1 − min(1, wet du matériau × wet_grip du pneu))`.
  À 0 il ne change pas ; à 1, sur un matériau à 0,75 avec un pneu ordinaire, il vaut 0,75.

## Déclarer ce qui glisse

Dans un **matériau physique** (`axion/physics_materials/<nom>.json`), la clé optionnelle `wet` : le facteur du frottement quand il est
mouillé, de 0,05 à 1. **0,75 par défaut.** La glace, qui glisse déjà, déclare `1`.

```json
{ "density": 916.7, "friction": 0.05, "wet": 1.0 }
```

Dans un **profil de pneu** (`axion/tires/<chemin>.json`), la clé optionnelle `wet_grip`, de 0,1 à 4 (**1 par défaut**) : un pneu pluie
déclare plus de 1 et rend ce que la route mouillée ôte. Avec un matériau à 0,5, un pneu à 2 retrouve l'adhérence du sec.

```json
{ "schema": 1, "mu_long": 1.0, "mu_lat": 1.0,
  "long": { "peak_slip": 0.12, "peak_value": 1.0, "tail_slip": 0.5, "tail_value": 0.75 },
  "lat":  { "peak_slip": 0.15, "peak_value": 1.0, "tail_slip": 0.6, "tail_value": 0.8 },
  "wet_grip": 1.35 }
```

## La neige est du contenu

Aucune règle météo pour la neige : les exemples font correspondre `minecraft:snow`, `minecraft:snow_block` et `minecraft:powder_snow` au
matériau `axion:snow` (frottement 0,35, `wet` 1), comme n'importe quel bloc a son matériau. Ces valeurs sont des choix de conception,
pas des mesures ; ton contenu déclare les siennes (voir [les matériaux](../MATERIALS.md)).

**Le sol, bloc par bloc.** Chaque roue prend le matériau du bloc qu'elle touche ([ADR-180](../../decisions/ADR-180.md)) :
une route sur de l'herbe adhère comme la route. La première couche de neige, qui n'a pas de collision, couvre le bloc
dessous de son matériau parce que le mappage des exemples la déclare `cover` ([les matériaux](../MATERIALS.md)) ; le tien
déclare les siennes.

## Le lire

- La source procédurale `vehicle.wet` (de 0 à 1, schéma 7) : une jauge, une piste de matériau, des essuie-glaces.
- Le client le reçoit du serveur, en un octet ; il ne lit pas la pluie lui-même, donc il prédit la même chose que le serveur.

## Ce que ça ne fait pas

- Pas de flaques, pas d'aquaplanage : le mouillé est un facteur sur le coefficient, uniforme sous le véhicule.
- Pas de rendu de la route mouillée ni des traces : c'est du rendu, hors de ce chantier.
