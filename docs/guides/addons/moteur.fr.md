# Moteur, démarreur et contact

Un véhicule peut avoir un moteur qui **démarre** : il est arrêté tant que le conducteur n'a pas mis le contact
([ADR-176](../../decisions/ADR-176.md) D1). Sans démarreur déclaré, le moteur tourne toujours, comme avant : rien ne
change pour tes definitions existantes.

## Déclarer le démarreur

Au schéma 7, dans `powertrain.engine` :

```json
"powertrain": {
  "engine": { "torque_curve": [[0, 0], [1500, 90], [3500, 120]], "idle_rpm": 900, "max_rpm": 6500,
              "inertia": 0.15, "braking_torque": 15.0,
              "starter": { "time": 0.8, "variable": "contact" } }
},
"variables": {
  "contact": { "type": "bool", "default": false, "replicate": "all", "write": "driver" }
},
"seats": [
  { "name": "conducteur", "node": "siege", "role": "driver",
    "bindings": [ { "command": 3, "label": "mymod.contact", "variable": "contact", "mode": "toggle" } ] }
]
```

- **`time`** : la durée du démarreur, de 0,05 à 10 secondes.
- **`variable`** : le contact. Un **booléen de l'assembly**, que **tu ne sauvegardes pas** (pas de `persist`) : un contact
  sauvegardé « mis » ferait redémarrer le moteur à chaque chargement. Une variable de siège n'est pas prédite côté client :
  elle est refusée. La commande générique qui la bascule est une liaison ordinaire (`toggle`), comme pour toute variable.

## Ce que fait le moteur

- **Contact mis** (le front montant de la variable) : le démarreur tourne `time` secondes — le moteur ne donne encore
  rien —, puis le moteur prend, au ralenti.
- **Contact coupé** : le moteur s'arrête aussitôt, démarreur compris.
- **Arrêté** : aucun couple, régime à zéro, plus de charge. Une boîte **automatique** passe au point mort ; une
  **manuelle** garde son rapport, comme une voiture, et le frein moteur reste. Le frein de stationnement reste serré sans
  conducteur.
- **Le conducteur qui descend ne coupe pas le moteur** : c'est la variable qui dit le contact.
- **Un véhicule garé démarre** : le contact seul le réveille.
- **Sauvegarde** : le moteur n'est pas sauvegardé ; à un chargement, il est arrêté, contact coupé.
- **Un moteur qui tourne ne cale pas** (ADR-159 D6) : rien ici n'ouvre le calage.

## Le lire

La source `engine.running` vaut 1 quand le moteur tourne, 0 sinon : une piste procédurale peut allumer un voyant ou
couper un son. Ton mod la lit dans l'API par `assembly.source("engine.running")`, et un mod pose le contact en écrivant la variable.

## Le réservoir et la batterie

Une definition au schéma 7 peut déclarer son énergie ([ADR-176](../../decisions/ADR-176.md) D3) :

```json
"energy": {
  "fuel": { "capacity": 40.0, "unit_mass": 0.75, "consumption": [[0.0, 0.4], [1.0, 9.0]] },
  "battery": { "capacity": 1.0, "starter_draw": 0.02, "charge_rate": 0.002 }
}
```

- **`fuel.consumption`** : des litres par heure selon la charge du moteur (de 0 à 1), multipliés par le régime sur le régime
  maximal. Les charges croissent strictement.
- **`unit_mass`** : la masse d'un litre. Elle exige `physics.mass`, qui est alors la **masse à sec**.
- **`battery`** : le démarreur en tire `starter_draw` par seconde, le moteur qui tourne lui rend `charge_rate`. Elle exige un
  `starter`.
- **Réservoir vide** : le moteur s'arrête, et ne repart pas sans nouveau front du contact. **Batterie ou réservoir vide** : le
  démarreur ne part pas.
- **Un véhicule endormi ne brûle rien** : la combustion suit l'éveil, comme tout le reste de la simulation.
- Sans `energy`, rien ne change.
- L'énergie nourrit un groupe motopropulseur : une definition sans `powertrain` — une remorque — ne peut pas la
  déclarer.

- **La masse suit le carburant** : `physics.mass` est la masse **à sec**, et le moteur y ajoute `unit_mass` × le carburant
  restant — par crans de 1/255 de la capacité, pour ne pas réveiller le véhicule à chaque goutte. La cause s'appelle
  `axion:energy`, elle se voit dans `/axion params`, et elle ne se sauvegarde pas : elle se recalcule.
- **Sauvegarde** : le carburant et la batterie se gardent (`axion:energy`) ; un véhicule relu repart du carburant qu'il avait,
  non d'un plein.

## Remplir et lire l'énergie

- **API** : `assembly.energy()` donne `fuel()`, `fuelCapacity()`, `fuelRatio()`, `battery()` ; `refuel(litres)` verse, et rend ce qui est
  entré (jamais plus que ce qui manque) ; le plein part au natif sans réveiller le véhicule.
- **Commande** : `/axion energy <cible>` voit l'énergie ; `/axion energy <cible> refuel <litres>` remplit, `battery <charge>` pose la
  batterie (opérateurs, journalisées).
- **Interaction** : une action `refuel { "litres": 5 }` dans la liste `actions` d'une interaction verse dans le réservoir de la
  definition. AXION ne fournit pas de pompe : c'est ton contenu qui la fait.
- **Sources** : `energy.fuel` (litres), `energy.fuel_ratio` et `energy.battery` (de 0 à 1) pour une piste procédurale ou une jauge.

Le buggy d'exemple a un démarreur (variable `contact`, commande 3), un réservoir de 20 litres et une batterie : son exemple est le
modèle de tout ce qui précède.
