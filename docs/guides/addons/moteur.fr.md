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

L'énergie (carburant, batterie, panne sèche) vient avec la tranche suivante d'ADR-176.
