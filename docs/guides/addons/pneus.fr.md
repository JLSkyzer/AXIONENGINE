# Pneus et usure

Un pneu peut **s'user** : plus il glisse, moins il adhère ([ADR-176](../../decisions/ADR-176.md) D4). Un pneu qui ne déclare pas
d'usure ne s'use jamais : rien ne change pour tes definitions existantes.

## Déclarer l'usure d'un pneu

Dans le profil du pneu (`axion/tires/<chemin>.json`), la clé optionnelle `wear` :

```json
{
  "schema": 1, "mu_long": 1.0, "mu_lat": 1.0,
  "long": { "peak_slip": 0.12, "peak_value": 1.0, "tail_slip": 0.5, "tail_value": 0.75 },
  "lat":  { "peak_slip": 0.15, "peak_value": 1.0, "tail_slip": 0.6, "tail_value": 0.8 },
  "wear": { "rate": 0.002, "grip_loss": 0.4 }
}
```

- **`rate`** : l'usure par **kilojoule** de travail de frottement. C'est l'énergie que la gomme dépense à glisser sur le sol : la
  charge de la roue, par la vitesse à laquelle elle glisse, par le temps. Un pneu qui roule sans glisser s'use à peine ; un
  burnout l'use vite. `0` (le défaut) : le pneu ne s'use pas.
- **`grip_loss`** : la part d'adhérence qu'un pneu **usé à fond** perd, de 0 à 1 (0,4 par défaut). L'usure ne change ni le rayon ni
  le pic de la courbe : ceux-là restent au dommage.

La definition nomme ce profil comme tout pneu : `"tire": "monmod:tire/route"` dans la roue.

## Ce que fait le moteur

- L'usure de chaque roue va de 0 (neuf) à 1 (usé à fond). Une roue en l'air, ou un véhicule endormi, n'use rien.
- Elle se **sauvegarde par nom de roue** : une roue ajoutée, retirée ou réordonnée ne décale pas les autres, et ton
  `migrations` peut renommer une roue. Un pneu neuf n'est pas écrit.
- Elle voyage au client en un octet par roue : une piste procédurale ou une jauge la lit par `wheel.<roue>.wear`.

## La changer

- **API** : `assembly.vehicle().orElseThrow().setWear("roue_arriere_gauche", 0.0)` — le changement de pneus d'un garage — ;
  `state().wheels().get(i).wear()` la lit.
- **Commande** : `/axion wear <cible>` la montre, avec ce que chaque roue touche et le mouillé de la route ; `/axion wear <cible> set <roue> <0..1>` et `reset` la posent (opérateurs,
  journalisées).
- **Interaction** : une action `set_wear { "wheel": "roue_arriere_gauche", "value": 0 }` dans la liste `actions` d'une interaction
  (sans `wheel`, toutes les roues).
- **Diagnostic** : l'overlay des roues (`/axion debug`) dessine un second cercle, plus petit à mesure que le pneu s'use.

## Montrer l'usure

Le moteur ne dessine aucun pneu usé de lui-même : il n'y a pas de modèle ni de texture d'usure par défaut, et il n'y a pas à en
fournir un seul. L'usure est une **source** (`wheel.<roue>.wear`, de 0 à 1) que les pistes `procedural` de la definition
branchent sur les canaux d'apparence d'un nœud de ton asset :

- **Échanger deux modèles** : mets le pneu neuf et le pneu usé dans l'asset comme deux nœuds, chacun avec sa géométrie et sa
  texture, et pilote leur `visibility` avec `map`. Une texture usée est donc un nœud dont le matériau est celui du pneu usé.
- **Teinter** : `color` multiplie l'albédo d'un nœud par un facteur par canal (`axis` : `r`, `g` ou `b`), pour assombrir un pneu
  à mesure qu'il s'use.

```json
"procedural": [
  { "node": "pneu_avg_neuf", "channel": "visibility", "source": "wheel.avant_gauche.wear", "map": [[0.3, 1], [0.5, 0]] },
  { "node": "pneu_avg_use",  "channel": "visibility", "source": "wheel.avant_gauche.wear", "map": [[0.3, 0], [0.5, 1]] }
]
```

Une piste `visibility` cache son nœud sous 0,5 ; `map` est linéaire entre ses points et tient ses bouts. Ici le pneu neuf se
montre jusqu'à 0,3 d'usure et s'efface vers 0,5, le pneu usé fait l'inverse. Le client lit l'usure reçue du serveur : le
changement de pneus (`setWear`, `/axion wear`, `set_wear`) remet le modèle neuf dès que le client reçoit l'état suivant. Ce que le moteur ne fait pas :
changer la texture d'**un même** nœud selon l'usure, ni dessiner des couches d'usure sur la surface (C-47) ; deux nœuds ou une
teinte tiennent ce besoin. Un test (`ProceduralTracksTest`) tient l'échange par `visibility` et `map` sur `wheel.<roue>.wear`.

## Essieux et anti-roulis

Deux roues d'un même essieu portent le même `axle` ; leur suspension peut déclarer un anti-roulis, qui redresse le
véhicule en virage ([ADR-159](../../decisions/ADR-159.md) D5) :

```json
{ "node": "roue_avant_gauche", "radius": 0.3, "axle": "avant", "suspension": { "stiffness": 25.0, "anti_roll": 15.0 } },
{ "node": "roue_avant_droite", "radius": 0.3, "axle": "avant", "suspension": { "stiffness": 25.0, "anti_roll": 15.0 } }
```

- Il passe de la charge de la roue la moins comprimée à la plus comprimée, à proportion de l'écart : `anti_roll` se
  compte comme `stiffness`, par kilogramme porté. À plat, il ne fait rien.
- Les deux roues de l'essieu déclarent la même valeur ; sans essieu de deux roues, il est refusé. Rien n'est déduit.
- Une roue en l'air ne reçoit rien, mais celle qui touche en tient compte.
