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
