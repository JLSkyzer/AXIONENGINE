# Plateaux, ponts et ascenseurs

Une assembly **arrête** ce qui la heurte et **porte** ce qui repose sur elle ([ADR-178](../../decisions/ADR-178.md), CAP-14) : un joueur
se tient debout sur un plateau qui roule, un ascenseur monte ceux qu'il porte, un cochon ne traverse plus un conteneur. Rien n'est à
écrire dans la definition : la collision lit ses colliders.

## Ce qui se passe

- **La collision** suit vanilla : la boîte de l'entité glisse le long de la surface heurtée, monte une marche de la hauteur de vanilla
  (0,6 bloc) depuis le sol, et repose dessus « au sol ». Sur une pente qu'on marche (moins de 45°), on ne glisse pas : un plateau qui
  penche dans un virage ne se vide pas.
- **Le support** naît quand une chute est arrêtée par une surface qu'on marche (à moins de 45°), meurt quand on saute, quand on quitte le
  bord ou qu'on se pose sur un bloc. On ne repose que sur une assembly à la fois.
- **Le transport** : le joueur reste à sa place sur le corps qui le porte, et tourne avec lui. Quand il le quitte en l'air (saut, bord),
  il garde la vitesse horizontale que le plateau lui donnait. Le serveur le porte à la pose que son client dessine, pour ne ni le renvoyer
  en arrière ni le compter « en train de voler ».
- **La poussée** : une assembly qui avance dans un joueur l'en sort, à 10 m/s au plus (`physics.push_speed_max`), sans le mettre dans un
  bloc : coincé contre un mur, il reste contre le mur et le journal du client le dit. Un joueur pèse 80 kg
  (`physics.player_proxy_mass`) : ce qui le heurte garde de l'élan, au lieu de s'arrêter net comme contre un mur.
- Les autres entités (mobs, objets) sont arrêtées et portées au serveur. Portées, elles pèsent comme un joueur
  (`physics.player_proxy_mass`) : un véhicule chargé accélère un peu moins vite, sans être arrêté.
- **Vu par les autres** : le serveur dit à chaque client qui une assembly porte, et à quelle place sur elle. Un autre joueur voit un
  passager debout, un mob ou un objet à cette place sur le véhicule **tel que lui le voit**, sans que ses jambes marchent s'il ne
  bouge pas ; un joueur ou un mob descendu rejoint sa position en un cinquième de seconde. **Limite connue** : sur un serveur
  ou un PC surchargé, cette place peut encore glisser le long du véhicule (jusqu'à un bloc relevé à l'essai).

## Une assembly qui ne porte rien

Une porte de hangar arrête, mais n'a pas à emporter celui qui s'y appuie : au schéma 7,

```json
{ "schema": 7, "asset": "monmod:models/porte.glb", "kind": "vehicle", "carry": false }
```

La collision reste ; aucun support ne naît sur elle.

## Pour un mod

```java
// Ce qu'une assembly porte, au serveur.
List<Entity> portes = assembly.carried();

// Chaque montée et chaque descente.
registrar.registerCarryListener(new CarryListener() {
    @Override
    public void carried(Assembly assembly, Entity entity) { /* ... */ }
});
```

## Voir ce qui se passe

- `/axion carry` liste, au serveur, qui est porté, par quelle assembly, sur quel corps, et à quel retard.
- `/axion debug supports on` relie, en turquoise, la place du joueur porté à l'origine du corps qui le porte.

## Les réglages

| Clé | Défaut | Rôle |
|-----|--------|------|
| `physics.player_carry` | `true` | tout ce qui précède ; `false` rend le jeu d'avant |
| `physics.push_speed_max` | `10` | la vitesse à laquelle une assembly repousse un joueur, en m/s |
| `physics.player_proxy_mass` | `80` | la masse du joueur pour la physique, en kg ; `0` : un mur |
| `physics.carry_max_entities` | `64` | le plafond d'entités, hors joueurs, portées par une assembly |
| `net.carry_tolerance` | `0.25` | l'écart auquel le serveur reconnaît un joueur sur une assembly, en blocs |

## À savoir

- Le transport d'un joueur a été éprouvé en solo, et sur un serveur dédié avec un joueur en survie : vingt secondes debout sur un
  véhicule conduit en cercle jusqu'à environ 9 m/s, 100 ms de latence dans chaque sens, sans correction du serveur. Deux joueurs ne
  l'ont pas encore été.
- `carry` se déclare dans la definition : il ne se change pas assembly par assembly en jeu.
