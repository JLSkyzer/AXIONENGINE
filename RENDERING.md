# Rendu

Comment AXION ENGINE dessine ses assemblies aujourd'hui (jalon M3), et ce qui, dans son
éclairage, est physiquement fondé ou synthétisé (R-1514). La référence normative reste le cahier
des charges (PARTIE 19, fiches 5.47 à 5.51) ; les choix d'implémentation sont dans les ADR cités.
Les options se règlent dans `axion-client.toml` : voir [CONFIGURATION.md](CONFIGURATION.md),
sections `[render]`, `[quality]` et `[overlay]`.

## Deux backends

| | `NATIVE_GL` (C-60) | `VANILLA_CONSUMER` (C-61) |
|---|---|---|
| Dessin | OpenGL 3.3, programmes d'AXION, instanciation, arènes GPU partagées | `RenderType` et `VertexConsumer` de Minecraft, aucun appel OpenGL direct (R-741) |
| Éclairage | BRDF du §19.5 (ci-dessous) | celui des entités de Minecraft : albédo × lightmap, ombrage par la normale |
| Cartes de normales et ORM | lues | ignorées (R-1513) |
| Émission | passe additive à part (passe 5 du §19.10) | `RenderType` des yeux d'entité : pleine lumière |

**Choix du backend** (`render.backend`, `auto` par défaut). Le natif, sauf si le contexte n'offre
pas OpenGL 3.3, si un shader ne se compile pas (`E-4001`, jamais un crash, R-761), ou si un
**shaderpack est en usage** (H-09) : le vanilla alors. Un mod de shaders installé, pack coupé,
laisse le natif dessiner ; le choix se refait quand le pack change (ADR-129). L'état du pack se
lit par l'API v0 d'Iris, qu'Oculus embarque aussi ; un mod de shaders dont l'état ne se lit pas
est tenu pour actif. Sous shaderpack, c'est le pack qui éclaire : c'est ce que le joueur attend.

**Passes.** Opaques, cutout et émission après les entités ; translucides après les blocs
translucides ; overlays de debug après les particules (§19.10). Chaque passe rend l'état OpenGL
qu'elle a trouvé (R-1502, R-1503), y compris sous Oculus et Embeddium.

## Le modèle d'éclairage du backend natif

**Physiquement fondé** — le cœur de la BRDF, en espace linéaire :

- distribution GGX, visibilité de Smith à hauteurs corrélées, Fresnel de Schlick ;
- partage de l'énergie entre diffus et spéculaire (le diffus reçoit `1 − F`) ;
- flux métal-rugosité de glTF : `F0 = 0,04` pour un diélectrique, l'albédo pour un métal ;
  rugosité bornée à 0,045 ; cartes ORM (occlusion, rugosité, métal) et cartes de normales en
  espace tangent, telles que glTF les définit ;
- réponse spéculaire ambiante intégrée, approchée analytiquement (Karis, 2014), en place de la
  table `brdfLUT`.

**Synthétisé** — choisi pour accorder l'objet au monde de Minecraft, sans fondement physique :

- **La lumière directe** est le soleil, ou la lune sous l'horizon : direction tirée du ciel de
  Minecraft, intensité de son assombrissement (pluie et orage compris), sans saut au coucher.
  Les intensités sont choisies par le calcul, non mesurées : un albédo blanc face au soleil de
  midi sort à peu près blanc (`WorldLight`).
- **Le soleil n'atteint que ce que le ciel voit** : il est pondéré par la part ciel de la
  lightmap du sommet, ce qui tient lieu d'ombres portées.
- **L'ambiante** prend son intensité et sa teinte dans la lightmap de Minecraft — l'heure, le ciel
  visible, la lumière des blocs (R-1511) : un objet dans une grotte sombre reste sombre. Sa
  répartition selon la normale vient d'un hémisphère ciel-sol, en attendant la sonde de C-81.
- **Spéculaire analytique seulement**, comme au niveau Q-1 de R-1512 : ni préfiltrage, ni sonde,
  ni clearcoat, sheen, anisotropie, parallax ou SSR.
- **Sortie** : passage en gamma par une puissance 1/2,2, sans tone mapping (C-83, R-1550, à
  venir) ; brouillard de Minecraft, en sphère ou en cylindre.

Le backend vanilla n'a pas de PBR : les `RenderType` de Minecraft n'en exposent pas les entrées
(R-1513).

## Culling, niveaux de détail et instancing

Chaque frame, le client remet au natif les assemblies que Minecraft lui donne à dessiner, leur pose
relative à la caméra, et le natif décide (C-64, C-65, [ADR-154](docs/decisions/ADR-154.md)) :

- **Culling** : une assembly au-delà de `render.max_distance`, ou entière hors du champ, n'est pas
  dessinée. Une assembly aux corps articulés écartés de leur repos, ou marquée `noCulling`, l'est
  toujours ; une boîte de repli aussi. L'occlusion viendra avec C-82 (M9).
- **Niveaux de détail** : l'asset compilé porte ses LOD (4 par défaut) ; le natif choisit le niveau
  de chaque assembly selon sa taille à l'écran — sous 200, 80, 30 et 10 pixels —, avec une hystérésis
  de 10 % et le décalage `render.lod_bias`, et dit au client le mesh de chaque dessin.
- **Plafond** : au-delà de `budgets.max_visible_instances`, la frame descend d'un niveau et les
  assemblies les plus lointaines sont écartées.
- **Échéance** : la préparation ne dépasse pas `budgets.render_prep_ns` ; une assembly qu'elle n'a pas
  eu le temps d'examiner reprend sa décision de la frame d'avant. Elle n'attend jamais le pas
  physique, même en solo, où le serveur intégré partage la bibliothèque native.
- **Instancing** (backend natif) : les opaques sont triés par asset, mesh et sens des faces ; une série
  d'au moins `render.instancing_threshold` meshes identiques (4 par défaut) se dessine d'un seul appel,
  une plus courte dessin par dessin. Sous GL 4.3 et `render.indirect`, les séries au même état partent
  d'un seul `glMultiDrawElementsIndirect`. L'image est la même par les trois chemins (T-478, T-907).

## Ressources GPU

- **Maillages** sous-alloués dans des arènes de 16 Mio partagées (VBO, EBO), tampon d'instances
  commun, libération différée de 3 frames sans usage (C-62).
- **Budget** : `budgets.gpu_mem_bytes` borne les maillages du backend natif **et** les textures
  qu'AXION téléverse, mipmaps compris, dans les deux backends ; au-delà, l'asset le moins
  récemment vu parmi les non visibles est déchargé en entier — maillage, textures, handle natif —,
  et se recharge quand il revient (R-750, ADR-133). Les textures de Minecraft qu'AXION ne fait que
  référencer n'y comptent pas.
- **Dégradation** : la tenue du budget passe par des paliers, chacun journalisé avec sa cause
  (§19.12) — `NOMINAL`, `UNLOADING` (des assets non visibles sont déchargés), `UNRESOLVED` (les
  assets en trop sont tous visibles ; le LOD forcé, par une résidence par niveau, et la qualité de
  déformation, leviers suivants de R-750, viennent ensuite et avec la M6). La descente est immédiate ; la remontée se fait d'un
  palier à la fois, après 30 s sous le seuil et 10 s au moins après la précédente (SM-02). Le
  palier courant est la jauge `axion.render.gpu_degradation_level`.
- **Textures** : décodées et mipmappées hors du render thread, téléversées par Minecraft
  (C-26, ADR-122).
- **Shaders** : ressources du JAR (`assets/axion/shaders/`), rechargés avec les packs de
  ressources. Les variantes — permutations des onze définitions de R-762 — se compilent **à la
  demande**, une étape GL par frame, avec la variante de base en secours immédiat (T-904) ; leur
  nombre est borné par `render.max_shader_variants`. Les sources n'implémentent aujourd'hui que
  `CUTOUT`. Un cache binaire (`GL_ARB_get_program_binary`) garde les programmes dans
  `<gameDir>/axion/cache/shaders/`, invalidé par le hash des sources et la chaîne du pilote
  (R-760, T-905).
- **Thread** : tout appel OpenGL affirme le render thread (INV-12), ce que T-019 vérifie sur le
  bytecode.

## Overlays de debug

`/axion debug <overlay> on|off`, en mode développeur (`-Daxion.dev=true`) (C-67, ADR-121). Sont
tracés `colliders`, `aabb`, `bodies`, `contacts`, `normals`, `joints`, `limits`, `world_tiles`,
`center_of_mass`, `network`, `lod` (l'emprise de chaque assembly dessinée, colorée par son niveau) et
`culling` (retenue en vert, écartée en rouge) ; les joints et leurs plages sont décrits dans
[PHYSICS.md](PHYSICS.md#erreurs-réseau-et-debug). Éteint, un overlay ne coûte qu'un test de drapeau
par frame (R-2280).

## Mesures

`/axion metrics export` porte, avec les métriques natives, celles que le rendu mesure (ADR-133) :
le temps CPU des passes d'AXION par frame (`budgets.render_prep_ns`), la mémoire GPU tenue et ses
dépassements (`budgets.gpu_mem_bytes`), les instances et les triangles dessinés
(`budgets.max_visible_instances`, `budgets.max_triangles_frame`), et les jauges `axion.render.*` :
backend actif, octets de textures et de maillages, évictions, palier de dégradation, objets GL
vivants, état des shaders du natif. Sur un client relié à un serveur distant, où la commande
tourne sur le serveur, `/axion metrics export client` écrit l'export du client (ADR-144 §2). Sous
le backend natif, le temps GPU de ses passes opaque et translucide est relevé par horodatages GPU,
lus quelques frames plus tard sans jamais attendre le GPU (`axion.render.gpu_opaque_ns`,
`axion.render.gpu_translucent_ns`, relevés perdus `axion.render.gpu_timings_dropped`, ADR-146
§2) ; le backend vanilla n'en a pas, R-741 lui interdisant tout appel OpenGL.

## Vérification

Le **banc de rendu** (ADR-126) lance le client automatiquement sous vanilla, sous le backend
natif et sous un shaderpack (Oculus et Embeddium) : aucune erreur OpenGL (T-471), état rendu
intact (T-472), bascule et retour sous shaderpack (T-480, T-490), compilation à la demande sans
à-coup (T-904, jugée sur GPU matériel seulement, ADR-127), transparence ordonnée (T-476), aucune
texture d'AXION laissée sur le GPU par une bascule de backend (T-481), ressources d'AXION
reconstruites par un vrai changement de resource pack (T-483), temps GPU des passes du natif
connu (ADR-146 §2), culling et LOD des assemblies posées hors du champ, 200 copies d'un cube sans appel
de dessin de plus (T-477), chemins non instancié et indirect à l'image identique (T-478, T-907), 100
assemblies mesurées sous leurs plafonds (T-485). La CI le joue à chaque push.

## Ce qui n'existe pas encore

Niveaux de qualité Q-0 à Q-4, sonde d'environnement (C-81) et tone mapping (C-83), effet des
définitions autres que `CUTOUT` (skinning, déformation, parallax, clearcoat, sheen, anisotropie,
décalques, ombres reçues, SSR), occlusion culling (C-82, M9), LOD forcé sous pression de mémoire GPU
(résidence par niveau, ADR-154), temps GPU par matériau, d'ombre et de décalques (M9, avec ces passes).
