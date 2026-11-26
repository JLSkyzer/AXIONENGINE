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

## Plusieurs réserves, typées (schéma 8)

Au schéma 8, tu nommes tes réserves et ce qu'elles contiennent ([ADR-182](../../decisions/ADR-182.md)) : un diesel
n'accepte que du gazole, une remorque citerne porte de l'eau, une batterie se recharge lentement.

```json
"schema": 8,
"energy": {
  "stores": [
    { "name": "gazole", "carrier": "axion:diesel", "capacity": 60.0, "unit_mass": 0.84 },
    { "name": "citerne", "carrier": "mymod:eau", "capacity": 800.0, "unit_mass": 1.0, "max_fill_rate": 5.0 }
  ],
  "battery": { "capacity": 1.0, "starter_draw": 0.02, "charge_rate": 0.002 }
},
"powertrain": {
  "engine": { "torque_curve": [[0, 0], [1500, 260], [3500, 240]], "idle_rpm": 800, "max_rpm": 4500,
              "inertia": 0.3, "braking_torque": 40.0,
              "starter": { "time": 1.0, "variable": "contact" },
              "fuel": { "store": "gazole", "consumption": [[0.0, 0.8], [1.0, 16.0]] } }
}
```

(Le reste de `powertrain` — boîte, roues motrices — est celui d'un véhicule ordinaire.)

- **Jusqu'à 4 réserves**, la batterie `energy.battery` comprise.
- **`carrier`** : ce qu'elle contient, un identifiant que tu choisis librement (`axion:diesel`, `axion:petrol`,
  `mymod:hydrogene`…). Le moteur n'en traite aucun à part. Sans `carrier`, la réserve accepte tout.
- **L'unité est celle de ton contenu** : des litres, des kilogrammes… La capacité, la consommation, `unit_mass` et les
  versements s'expriment dans la même.
- **`unit_mass`** : la masse d'une unité, ajoutée à `physics.mass` (la masse à sec). Le véhicule pèse la somme de ses
  réserves.
- **`max_fill_rate`** : les unités qu'on peut y verser par seconde, au plus. Absent : pas de limite.
- **`engine.fuel`** : la réserve que le moteur brûle, et sa consommation en unités par heure (mêmes règles qu'au schéma 7).
  `energy.fuel` n'existe pas à côté de `stores`.
- **Le démarreur** tire la batterie `energy.battery`, ou une réserve : `"starter": { "time": 1.0, "variable": "contact",
  "store": "auxiliaire", "draw": 0.02, "charge_rate": 0.002 }`. Pas les deux, et pas la réserve que le moteur brûle.
- **Sans moteur** : une definition peut déclarer des réserves sans `powertrain` — une remorque citerne pèse ce qu'elle
  porte. Elle doit avoir des roues (`wheels`).
- Au schéma 7, le réservoir et la batterie se lisent comme les réserves `fuel` et `battery` : rien à changer.

### Remplir une réserve typée

Un versement d'un autre type n'entre pas. Ta pompe ou ta borne appelle l'API à chaque tick ; `max_fill_rate` borne ce
qui entre, quoi qu'elle demande.

- **API** : `assembly.energy().store("citerne")` donne `carrier()`, `capacity()`, `amount()`, `ratio()` ;
  `fill(type, quantité)` rend ce qui est entré ; `set(quantité)` pose le contenu. `stores()` les donne toutes.
  `refuel(litres)` ne verse que dans un réservoir moteur sans type.
- **Interaction** : `refuel { "carrier": "axion:diesel", "amount": 5 }` verse dans la première réserve qui l'accepte et où
  il reste de la place ; ajoute `"store": "gazole"` pour viser une réserve. La forme `{ "litres": 5 }` reste réservée au
  réservoir sans type.
- **Commande** : `/axion energy <cible>` liste les réserves ; `/axion energy <cible> refuel <quantité> <type>` verse
  (opérateurs, journalisée).
- **Sources** : `energy.<réserve>.amount` (dans son unité) et `energy.<réserve>.ratio` (de 0 à 1), pour une piste ou une
  jauge. `energy.fuel`, `energy.fuel_ratio` et `energy.battery` continuent de désigner la réserve du moteur et celle du
  démarreur.
- **Sauvegarde** : chaque contenu se garde sous le nom de sa réserve. Renomme une réserve avec une migration
  `rename.stores` ([ADR-175](../../decisions/ADR-175.md)) ; une réserve retirée reste dans la sauvegarde et le journal la
  signale (`E-7012`) ; une réserve nouvelle naît pleine.

## Le moteur électrique (schéma 8)

Un véhicule peut avoir jusqu'à **4 moteurs électriques** ([ADR-182](../../decisions/ADR-182.md) D3), avec ou sans
moteur à combustion.

```json
"energy": { "stores": [ { "name": "traction", "carrier": "axion:electricity", "capacity": 40.0, "max_fill_rate": 0.014 } ] },
"powertrain": {
  "electric_motors": [
    { "name": "arriere", "store": "traction", "variable": "contact",
      "torque_curve": [[0, 150], [4000, 150], [9000, 60]], "max_rpm": 9000, "efficiency": 0.92,
      "regen": { "lift_off": 20, "brake": 60, "efficiency": 0.7 },
      "coupling": { "wheels": ["roue_arriere_gauche", "roue_arriere_droite"], "ratio": 8.0 } }
  ]
}
```

- **La réserve se compte en kWh** : `capacity` 40 est une batterie de 40 kWh ; `max_fill_rate` en kWh par seconde.
- **Couple** : la courbe (régime, N·m au moteur) multipliée par l'accélérateur, dès 0 tr/min. Au-delà de `max_rpm`, plus
  rien. Ni ralenti, ni démarreur, ni embrayage.
- **Consommation** : la puissance `couple × vitesse` divisée par `efficiency` (0,9 sans la déclarer). Réserve vide : le
  moteur ne donne rien.
- **Récupération** : accélérateur lâché, le moteur retient les roues de `lift_off` N·m, plus `brake` N·m à pleine pédale
  de frein, et rend à sa réserve l'énergie prise, multipliée par `regen.efficiency` (0,7 sans la déclarer). Elle freine
  comme un frein : elle peut tenir le véhicule à l'arrêt, jamais le faire repartir. Réserve pleine : pas de
  récupération, les freins restent. L'ABS la relâche avec le frein. Sans `regen`, aucune.
- **`coupling`** : des roues et leur rapport de réduction (couple à parts égales). Sans `coupling`, le couple s'ajoute à
  l'entrée de la boîte : rapports, `final_drive` et différentiel s'y appliquent — sans `transmission`, la boîte directe
  de rapport `final_drive`.
- **Marche arrière** : le sens engagé par la boîte, comme pour un moteur à combustion (frein tenu à l'arrêt en boîte
  directe ou automatique). Point mort : le moteur ne fait rien.
- **`variable`** (facultatif) : un booléen de l'assembly qui autorise le moteur ; sans lui, il l'est toujours. Tu peux le
  sauvegarder (`persist`) : un moteur autorisé ne donne rien sans pédale.
- **Sans moteur à combustion** : omets `engine`. `engine.running` reste à 0, et le paramètre `engine.max_rpm` n'existe
  pas. Les roues motrices (`drive_wheels`) ne sont exigées que pour un moteur couplé à la boîte.
- **Sources** : `motor.<nom>.rpm` (tr/min, positif dans le sens engagé) et `motor.<nom>.power` (kW, négatif en
  récupération) pour un compteur ou une animation ; `energy.<réserve>.ratio` pour la jauge de batterie.

## L'hybride (schéma 8)

Avec `engine` **et** `electric_motors`, le véhicule est hybride ([ADR-182](../../decisions/ADR-182.md) D4).

**En parallèle**, les couples s'additionnent et la même pédale commande les deux. Le moteur à combustion arrêté reste
débrayé : avec un contact coupé, le véhicule roule à l'électricité seule. La stratégie est la tienne : chaque moteur
électrique a le paramètre `motor.<nom>.torque` ([Régler](regler.md)) — le pic de sa courbe, en N·m — qu'une variable, un
modificateur ou le contrôleur de ton mod baisse, coupe ou renforce. À 0, le moteur ne donne plus rien ; sa récupération,
elle, reste.

**En prolongateur d'autonomie**, le moteur à combustion n'entraîne **jamais** les roues : il charge une réserve.

```json
"engine": {
  "torque_curve": [[0, 0], [1500, 45], [3500, 60], [6500, 45]], "max_rpm": 6500,
  "fuel": { "store": "essence", "consumption": [[0.0, 0.4], [1.0, 9.0]] },
  "generator": { "store": "traction", "power_kw": 20.0, "efficiency": 0.9, "start_below": 0.2, "stop_above": 0.8 }
}
```

- Il **démarre** quand `traction` passe sous `start_below` (0,2 sans le déclarer) et **s'arrête** à `stop_above` (0,8).
- Il tourne à son régime de plus grande puissance, charge `power_kw` au plus — jamais plus que le moteur ne donne,
  rendement compris (`efficiency`, 0,9) — et brûle son carburant selon sa courbe.
- Avec un démarreur (`starter`), il ne démarre que contact mis ; sans, dès que la réserve le demande.
- Il tient le véhicule éveillé tant qu'il charge ; un véhicule déjà endormi ne démarre qu'à son réveil (un conducteur, un
  contact).
- Les roues motrices ne sont exigées que pour un moteur électrique couplé à la boîte.
- `engine.running`, `engine.rpm` et `engine.load` le suivent.

## Trois exemples complets

Le mod d'exemple (`java/addon-temoin`) porte trois véhicules prêts à copier, sur le modèle du buggy d'AXION ; chacun a
son test en jeu. Fais-les apparaître avec `/axion spawn example_addon:electric_buggy` (ou `diesel_buggy`,
`hybrid_buggy`) ; la touche de commande 3 met le contact.

=== "Électrique"

    ```json
    --8<-- "java/addon-temoin/src/main/resources/axion/definitions/electric_buggy.json"
    ```

    ```java
    --8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:electric"
    ```

=== "Diesel"

    ```json
    --8<-- "java/addon-temoin/src/main/resources/axion/definitions/diesel_buggy.json"
    ```

    ```java
    --8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:diesel"
    ```

=== "Hybride à prolongateur"

    ```json
    --8<-- "java/addon-temoin/src/main/resources/axion/definitions/hybrid_buggy.json"
    ```

    ```java
    --8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:hybrid"
    ```
