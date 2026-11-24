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
- **Commande** : `/axion wear <cible>` la montre ; `/axion wear <cible> set <roue> <0..1>` et `reset` la posent (opérateurs,
  journalisées).
- **Interaction** : une action `set_wear { "wheel": "roue_arriere_gauche", "value": 0 }` dans la liste `actions` d'une interaction
  (sans `wheel`, toutes les roues).
- **Diagnostic** : l'overlay des roues (`/axion debug`) dessine un second cercle, plus petit à mesure que le pneu s'use.
