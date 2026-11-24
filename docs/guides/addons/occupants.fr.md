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

1. **Agrandir ou rapetisser le modèle**, et garder `scale` à 1. La Charger du dépôt de test, agrandie ×1,5, en est l'exemple.
2. **Garder le modèle à sa taille réelle** et régler `scale` : la même Charger à 1, avec `scale` à 0,67 (1 ÷ 1,5) à chaque siège.

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

La definition de test `axion:test/siege_occupant` (dans `content/test`) règle les deux sièges ; le GameTest
`lOccupantReglePoseEchelleEtOeilEtSaBoiteResteCelleDuJoueur` en vérifie la pose, l'échelle, l'œil et la boîte.
