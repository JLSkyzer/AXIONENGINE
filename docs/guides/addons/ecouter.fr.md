# Écouter et refuser un geste

Ton mod peut suivre ce que les joueurs font avec les assemblies — s'asseoir, descendre, cliquer sur une
interaction — et **refuser** un geste avant qu'il ait lieu : une voiture fermée à clé, un siège réservé à un
grade, un bouton qu'on ne presse qu'une fois ([ADR-174](../../decisions/ADR-174.md)). Les attaches ont leur propre
écouteur, décrit dans [Attaches](attaches.md).

## Enregistrer un écouteur

Comme un contrôleur, un écouteur s'enregistre avec le reste de ton contenu :

```java
registrar.registerSeatListener(new SeatListener() {
    @Override
    public String allowEnter(Assembly assembly, String seat, Entity passenger) {
        boolean ferme = assembly.variables().getBoolean("verrouillee");
        return ferme && seat.equals("conducteur") ? "monmod.voiture.fermee" : null;
    }

    @Override
    public void exited(Assembly assembly, String seat, Entity passenger, String cause) {
        // cause : « dismounted » (il est descendu) ou « ejected » (AXION l'a fait descendre)
    }
});

registrar.registerInteractionListener(new InteractionListener() {
    @Override
    public String allow(Assembly assembly, String node, Player player, String action) {
        return node.equals("coffre") && !player.isCreative() ? "monmod.coffre.reserve" : null;
    }
});
```

- **Juger** : `allowEnter`, `allowExit` (une descente voulue) et `allow` (une interaction) rendent `null` pour
  laisser faire, ou la **clé de traduction** de la raison. Le joueur la lit dans la barre d'action ; le journal du
  serveur nomme ton mod.
- **Suivre** : `entered`, `exited` et `interacted` arrivent après coup.
- Chaque méthode a un défaut : ne redéfinis que ce qui t'intéresse.

## Les règles

- Les écouteurs sont appelés **au serveur**, sur son thread, dans l'ordre des mods puis de leur enregistrement. Le
  **premier refus l'emporte**.
- Une **éjection** ne se refuse pas : un siège qui disparaît, la mort du passager (R-712). L'écouteur en est
  seulement prévenu, avec la cause `ejected`.
- Une **exception** est journalisée en nommant ton mod, et ne refuse rien. Après `content.listener_strikes`
  exceptions de suite (20), l'écouteur est **désactivé pour la session** (`E-1012`).

## Les événements de contenu

Une definition déclare ses événements au schéma 7, et à qui chacun part :

```json
"events": {
  "klaxon": { "replicate": "occupants" },
  "portiere_claque": { "replicate": "all" },
  "compteur": { "replicate": "none" }
}
```

Ils s'émettent par l'action `{ "emit_event": { "event": "portiere_claque", "value": 1 } }` d'une liste
d'interaction, par une interaction `"action": "emit_event", "event": "klaxon"`, ou par ton mod :
`assembly.emit("klaxon", 1.0)`. Un événement que la definition ne déclare pas est refusé, en se nommant.

```java
registrar.registerContentEventListener(new ContentEventListener() {
    @Override
    public void onEvent(Assembly assembly, String event, double value) {
        // au serveur, au tick de l'émission
    }

    @Override
    public void onClientEvent(Entity assembly, String event, double value) {
        // chez un client à qui l'événement part : un son, des particules
    }
});
```

- `replicate` : `all` (par défaut) aux joueurs qui suivent l'assembly, `occupants` à ses seuls occupants, `none` au
  serveur seul.
- Un événement est un **instant** : il ne se sauvegarde pas. Pour un état qui dure, prends une variable.
- Un joueur en reçoit au plus `net.events_per_player_tick` par tick (16).

## Les capteurs

Un capteur est un volume posé à un node, qui ne bloque rien et voit entrer et sortir les autres assemblies et les
entités (joueurs, créatures). Au schéma 7 ([ADR-174](../../decisions/ADR-174.md) D5) :

```json
"sensors": [
  { "name": "porte", "node": "seuil", "shape": "box", "size": [1.0, 2.0, 0.2] },
  { "name": "sas", "node": "seuil", "shape": "sphere", "radius": 1.5, "replicate": "occupants" }
]
```

- Une boîte a sa taille entière (`size`), une sphère son rayon (`radius`), en blocs ; 16 capteurs au plus.
- Chaque capteur déclare deux événements, `sensor.porte.enter` et `sensor.porte.exit`, de valeur le nombre de ce qui
  y est après le changement ; `replicate` dit à qui ils partent, comme pour un événement déclaré. Un `trigger` les nomme
  (`event:sensor.porte.enter`), ton écouteur les reçoit comme les autres.
- La source `sensor.porte.count` lit ce que le capteur compte, au serveur comme au client.
- Les autres corps de la même assembly n'y comptent pas, ni le terrain. Une assembly qui disparaît dedans en sort.
