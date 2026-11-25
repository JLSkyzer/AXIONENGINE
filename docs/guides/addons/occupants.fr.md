# Mettre un siège à l'échelle

Un joueur assis dans ton véhicule est, par défaut, un joueur de Minecraft : 1,8 m, assis dans la pose du jeu, l'œil à celui du
jeu. Si ton modèle est plus grand, ou plus petit, que Minecraft, le joueur flotte ou s'enfonce dans son siège. Un siège règle ce
qu'il fait de son **occupant** ([ADR-177](../../decisions/ADR-177.md) D1), au schéma 7 :

```json
"seats": [ { "name": "conducteur", "node": "siege",
             "occupant": { "pose": "sit", "scale": 0.67, "eye": [0, 0.75, 0.05] } } ]
```

Les trois clés sont facultatives ; un siège sans `occupant` garde la pose, l'échelle et l'œil de Minecraft.

## Les trois réglages

- **`pose`** : `sit` (assis, la pose du jeu, par défaut) ou `stand` (debout : une plateforme de tir, un poste debout). Debout, les
  **pieds** du joueur sont au node du siège, sans le décalage de monture de Minecraft ; assis, c'est son assise qui y est.
- **`scale`** : de 0,25 à 4, **1 par défaut**. L'occupant est **dessiné** à cette échelle, autour du point de son siège : sa taille
  change, pas la place où il est assis.
- **`eye`** : l'œil de l'occupant à la première personne, dans le repère du node du siège, en mètres. Sans lui, c'est l'œil de
  Minecraft, **multiplié par `scale`** : un occupant à 0,67 voit de 0,67 fois plus bas. Une vue de caméra qui déclare son propre
  `eye` (voir [les caméras](cameras.md)) le garde.

## Deux manières d'accorder le modèle et le joueur

AXION laisse les deux, à toi de choisir :

1. **Agrandir ou rapetisser le modèle**, et garder `scale` à 1 : un modèle agrandi ×1,5 dans Blender, par exemple, dont le joueur de Minecraft garde la taille.
2. **Garder le modèle à sa taille réelle** et régler `scale` : le même modèle à 1, avec `scale` à 0,67 (1 ÷ 1,5) à chaque siège.

Le choix tient à ce que tu veux ailleurs. Un modèle à sa taille réelle se comporte comme dans le jeu partout où il sert (une autre
carrosserie, un autre mod) ; une échelle de siège évite de refaire un modèle que tu ne contrôles pas.

## Ce qui ne change pas

- **La boîte de collision du joueur** reste celle d'un joueur de Minecraft. L'échelle ne touche que le dessin : la sortie sûre
  (R-711), les coups, ce que le serveur lit, tout reste ce que fait un joueur normal.
- **Les autres joueurs** voient l'occupant à la même échelle : `scale` vient de la definition, que chaque client lit.
- **La troisième personne** : la caméra y recule de l'œil que Minecraft a posé ; `eye` et `scale` n'y changent rien.

## À savoir

- Le nom du joueur, au-dessus de lui, est dessiné avec lui : il suit l'échelle.
- Assis ou debout, le serveur pose le joueur au même node ; seule la hauteur diffère (le décalage de monture, 0,35 m pour un joueur
  assis).
- L'API écrit ces champs comme les autres : `seat("conducteur", s -> s.occupant(o -> o.scale(0.67)))`.

## Le mannequin du joueur

Pour régler `scale` ou `eye` dans Blender sans lancer le jeu, AXION fournit le **mannequin du joueur** ([ADR-177](../../decisions/ADR-177.md) D2,
CAP-56) : un joueur de Minecraft à ses vraies cotes, en GLB.

```powershell
axion-cli mannequin mannequin
```

écrit `joueur_large.glb` (bras de 4 pixels) et `joueur_fin.glb` (bras de 3) dans le dossier, et refuse d'écraser sans `--force`.

- **Ses cotes ne sont écrites nulle part** : elles sont lues dans le modèle du joueur de Minecraft, cuit comme le fait le client
  (`PlayerModel.createMesh`). Le modèle fait 1,875 m — l'échelle de 0,9375 que le rendu applique aux joueurs —, la boîte de collision
  1,8 m. Douze parties, articulées aux pivots du jeu : `head`, `body`, les bras et les jambes, et la seconde couche de chacune
  (`hat`, `jacket`, `right_sleeve`, `left_sleeve`, `right_pants`, `left_pants`).
- **Trois poses**, un clip d'une image chacun : `stand` (debout), `sneak` (accroupi) et `sit` (assis, la pose de monture). Elles viennent du
  `setupAnim` du jeu, pas d'un dessin.
- **Posé sur un siège** : donne pour parent au nœud racine du mannequin (`joueur_large` ou `joueur_fin`) le node `SEAT` de ton
  modèle, **sans toucher à sa position, sa rotation ni son échelle** : elles se lisent désormais dans le repère du siège (dans Blender,
  après `Ctrl+P`, fais `Alt+P` › *Clear Parent Inverse*). Joue le clip `sit`. L'origine de l'entité du joueur est alors **sous le node de
  0,35 m** — le décalage de monture de Minecraft —, là où le serveur le met. Il regarde vers `-Z`, l'avant d'AXION.
- **Pour accorder le modèle et le joueur** : mets le mannequin sur le siège et agrandis le modèle (première convention). Pour la
  seconde, intercale un node vide entre les deux : enfant du `SEAT`, à position et rotation nulles, d'échelle ton `scale`, et parent de la
  racine du mannequin ; lis alors sur lui où tombent la tête et l'œil. **Ne change pas l'échelle de la racine elle-même** : en pose `sit`,
  son origine est 1,06 m au-dessus du siège, et le mannequin grandirait autour de ce point, pas autour du siège comme dans le jeu.
- **Sa texture** est neutre, au format d'un skin (64 × 64), teintée par partie pour qu'on s'y repère : aucune texture de Mojang.
- **Il ne diverge jamais du jeu** : `./gradlew genMannequin` l'écrit depuis le modèle du jeu, et le banc de rendu compare chaque sommet des
  deux fichiers, aux trois poses, à celui que le jeu dessine (1 mm), puis le joueur assis à l'origine que le mannequin assis pose sous le
  node. Si Minecraft change son modèle, le banc échoue et se règle par `./gradlew genMannequin`.

## Voir ce que le siège règle

`/axion debug seats on` dessine, à chaque siège, en orange l'occupant à l'échelle réglée et une croix à son œil, et en gris la boîte de collision d'un
joueur, que l'échelle ne touche pas.

La definition de test `axion:test/siege_occupant` (dans `content/test`) règle les deux sièges ; le GameTest
`lOccupantReglePoseEchelleEtOeilEtSaBoiteResteCelleDuJoueur` en vérifie la pose, l'échelle, l'œil et la boîte.
