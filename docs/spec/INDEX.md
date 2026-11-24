# Index du cahier des charges AXION ENGINE

Genere par `tools/spec/spec_index.py`. Source : `docs/spec/AXIONENGINE_Cahier_des_Charges_v1.0.md` (8635 lignes).

Le CDC est **gele (FROZEN)** et constitue la source de verite unique.
Il ne doit jamais etre lu en entier : lire la plage de lignes de la
section utile, par exemple

```bash
sed -n '3586,3965p' docs/spec/AXIONENGINE_Cahier_des_Charges_v1.0.md
```

Pour retrouver la definition d'un identifiant (`R-1220`, `C-42`, `T-808`) :

```bash
grep -P '^C-42	' docs/spec/ID-MAP.tsv
```

| Lignes | Etendue | Section |
|---|---|---|
| `1,32p` | 32 | AXION ENGINE |
| `3,32p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;Cahier des charges V1.0 : framework 3D, physique, déformation, animation et simulation pour Minecraft Forge |
| `33,80p` | 48 | TABLE DES MATIÈRES |
| `81,248p` | 168 | PARTIE 0 : PRÉAMBULE, CONVENTIONS, HYPOTHÈSES |
| `83,100p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;0.1 Nature du document |
| `101,110p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;0.2 Conventions de langage normatif |
| `111,122p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;0.3 Niveaux de maturité |
| `123,154p` | 32 | &nbsp;&nbsp;&nbsp;&nbsp;0.4 Identifiants normatifs |
| `155,175p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;0.5 Hypothèses explicites |
| `176,199p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;0.6 Terminologie |
| `200,248p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;0.7 Portée et non-portée |
| `249,357p` | 109 | PARTIE 1 : VISION, OBJECTIFS, PRINCIPES DIRECTEURS |
| `251,280p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;1.1 Résumé exécutif |
| `281,292p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;1.2 Ce qu'AXION est et n'est pas |
| `293,311p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;1.3 Objectifs |
| `312,331p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;1.4 Principes directeurs |
| `332,345p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;1.5 Limite fondamentale assumée |
| `346,357p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;1.6 Règle d'or |
| `358,678p` | 321 | PARTIE 2 : ARCHITECTURE GLOBALE ET INVENTAIRE DES COMPOSANTS |
| `360,408p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;2.1 Vue en couches |
| `409,481p` | 73 | &nbsp;&nbsp;&nbsp;&nbsp;2.2 Inventaire normatif des composants |
| `482,530p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;2.3 Graphe de dépendances |
| `531,564p` | 34 | &nbsp;&nbsp;&nbsp;&nbsp;2.4 Modèle de threads |
| `565,589p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;2.5 Cycle de vie d'un tick serveur |
| `590,609p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;2.6 Cycle de vie d'une frame client |
| `610,633p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;2.7 Séparation client / serveur |
| `634,678p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;2.8 Chaîne de dommage — vue d'ensemble |
| `679,1250p` | 572 | PARTIE 3 : MODÈLE DE DONNÉES CANONIQUE |
| `690,708p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;3.1 DM-01 : identifiants |
| `709,718p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;3.2 DM-02 : Transform |
| `719,752p` | 34 | &nbsp;&nbsp;&nbsp;&nbsp;3.3 DM-03 : Node |
| `753,787p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;3.4 DM-04 : Mesh et vertex |
| `788,820p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;3.5 DM-05 : MaterialDesc (rendu) |
| `821,854p` | 34 | &nbsp;&nbsp;&nbsp;&nbsp;3.6 DM-06 : Collider |
| `855,899p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;3.7 DM-07 : PhysicsMaterial — modèle de matière étendu |
| `900,922p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;3.8 DM-08 : BodyDesc et BodyState |
| `923,943p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;3.9 DM-09 : Assembly |
| `944,956p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;3.10 DM-10 : Skeleton et Animation |
| `957,1001p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;3.11 DM-11 : Part, zone de dommage et état |
| `1002,1050p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;3.12 DM-12 : Régions de déformation et champ |
| `1051,1076p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;3.13 DM-13 : Impact |
| `1077,1100p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;3.14 DM-14 : Intégrité structurelle |
| `1101,1130p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;3.15 DM-15 : Surface, usure et décalques |
| `1131,1152p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;3.16 DM-16 : Particules (tissu, cordes, câbles, filets, corps souples) |
| `1153,1173p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;3.17 DM-17 : Attache entre assemblies |
| `1174,1211p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;3.18 DM-18 : Niveaux de qualité et budgets |
| `1212,1224p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;3.19 DM-19 : codes d'erreur |
| `1225,1250p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;3.20 Invariants globaux |
| `1251,1461p` | 211 | PARTIE 4 : FRONTIÈRE JAVA/RUST, FFI ET ABI |
| `1253,1279p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;4.1 Répartition des responsabilités |
| `1280,1297p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;4.2 ADR-004 : mécanisme d'interopérabilité |
| `1298,1316p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;4.3 IF-01 : ABI, versionnement, contrôle |
| `1317,1343p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;4.4 IF-02 : mémoire partagée et anneaux de transfert |
| `1344,1369p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;4.5 IF-03 : cycle de simulation |
| `1370,1394p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;4.6 IF-04 : déformation |
| `1395,1416p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;4.7 IF-05 : cycle de rendu |
| `1417,1435p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;4.8 IF-06 : assets, particules, structure |
| `1436,1442p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;4.9 IF-07 : erreurs et panics |
| `1443,1456p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;4.10 Propriété et durée de vie |
| `1457,1461p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;4.11 Calibration FFI |
| `1462,2588p` | 1127 | PARTIE 5 : SPÉCIFICATIONS COMPOSANT PAR COMPOSANT |
| `1466,1502p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;5.1 C-01 : Forge Integration |
| `1503,1521p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.2 C-02 : Bootstrap |
| `1522,1535p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;5.3 C-03 : Native Loader |
| `1536,1551p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;5.4 C-04 : Configuration |
| `1552,1560p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;5.5 C-05 : Diagnostics & Logging |
| `1561,1577p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;5.6 C-10 : Native Core |
| `1578,1587p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;5.7 C-11 : Math |
| `1588,1599p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;5.8 C-12 : Job System |
| `1600,1614p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;5.9 C-13 : Memory / Arenas |
| `1615,1622p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.10 C-14 : FFI Bridge |
| `1623,1630p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.11 C-15 : Telemetry natif |
| `1631,1732p` | 102 | &nbsp;&nbsp;&nbsp;&nbsp;5.12 C-16 : Noyau déterministe |
| `1657,1732p` | 76 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;5.12bis Portée de la garantie déterministe |
| `1733,1758p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;5.13 C-20 : Asset Orchestrator |
| `1759,1783p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;5.14 C-21 : Importers |
| `1784,1811p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;5.15 C-22 : Asset Validator |
| `1812,1835p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;5.16 C-23 : Asset Optimizer |
| `1836,1841p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.17 C-24 : Conteneur A3D |
| `1842,1855p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;5.18 C-25 : Asset Cache |
| `1856,1873p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.19 C-26 : Textures & Matériaux (client) |
| `1874,1900p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;5.20 C-27 : Definitions data-driven |
| `1901,1982p` | 82 | &nbsp;&nbsp;&nbsp;&nbsp;5.21 C-28 : Compilateur de déformation et de structure |
| `1983,2001p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.22 C-30 : Scene Graph |
| `2002,2026p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;5.23 C-31 : Physics World |
| `2027,2037p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;5.24 C-32 : Collider Builder |
| `2038,2043p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.25 C-33 : Vehicle System |
| `2044,2055p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;5.26 C-34 : Joints & Constraints |
| `2056,2061p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.27 C-35 : Damage Model |
| `2062,2067p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.28 C-36 : Particle Solver |
| `2068,2073p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.29 C-37 : Animation System |
| `2074,2092p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.30 C-38 : World Collision Provider |
| `2093,2102p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;5.31 C-39 : Spatial Queries |
| `2103,2132p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;5.32 C-40 : Simulation Scheduler |
| `2133,2188p` | 56 | &nbsp;&nbsp;&nbsp;&nbsp;5.33 C-41 : Impact Solver |
| `2189,2196p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.34 C-42 : Deformation Engine |
| `2197,2202p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.35 C-43 : Structural Integrity |
| `2203,2208p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.36 C-44 : Fracture & Detachment |
| `2209,2216p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.37 C-45 : Collider Refit |
| `2217,2222p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.38 C-46 : Repair System |
| `2223,2254p` | 32 | &nbsp;&nbsp;&nbsp;&nbsp;5.39 C-47 : Surface State & Wear |
| `2255,2279p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;5.40 C-48 : Attachment System |
| `2280,2292p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;5.41 C-50 : Axion Entity |
| `2293,2298p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.42 C-51 : Network Sync |
| `2299,2304p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.43 C-52 : Persistance |
| `2305,2322p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.44 C-53 : Interaction & Sièges |
| `2323,2330p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.45 C-54 : Bloc & BlockEntity 3D |
| `2331,2338p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.46 C-55 : Item 3D |
| `2339,2344p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.47 C-60 : Backend NATIVE_GL |
| `2345,2360p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;5.48 C-61 : Backend VANILLA_CONSUMER |
| `2361,2377p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;5.49 C-62 : GPU Resource Manager |
| `2378,2387p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;5.50 C-63 : Matériaux & Shaders |
| `2388,2406p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.51 C-64 : Culling & LOD |
| `2407,2423p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;5.52 C-65 : Instancing & Batching |
| `2424,2437p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;5.53 C-66 : Skinning GPU |
| `2438,2445p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.54 C-67 : Debug Renderer |
| `2446,2451p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.55 C-68 : Déformation GPU |
| `2452,2457p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.56 C-69 : Décalques & états de surface (rendu) |
| `2458,2461p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.57 C-70 : API publique |
| `2462,2488p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;5.58 C-71 : Commandes |
| `2489,2492p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.59 C-72 : Benchmark Harness |
| `2493,2500p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.60 C-73 : Overlay diagnostics |
| `2501,2518p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.61 C-74 : CLI `axion-cli` |
| `2519,2522p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.62 C-75 : Addon Blender |
| `2523,2526p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.63 C-76 : Bridge RUSTFORGE-X |
| `2527,2563p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;5.64 C-77 : Gouverneur de qualité |
| `2564,2569p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.65 C-80 : Ombres AXION |
| `2570,2575p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.66 C-81 : Sonde d'environnement & IBL synthétique |
| `2576,2581p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.67 C-82 : Occlusion culling logiciel |
| `2582,2588p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;5.68 C-83 : Effets intégrés (tone mapping, SSR) |
| `2589,2699p` | 111 | PARTIE 6 : FORMATS 3D ET PIPELINE D'ASSETS |
| `2591,2602p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;6.1 Évaluation des formats |
| `2603,2612p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;6.2 Décisions figées |
| `2613,2652p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;6.3 Pipeline complet |
| `2653,2682p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;6.4 Options de compilation |
| `2683,2699p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;6.5 Contraintes d'auteur (documentées dans `ASSETS.md`) |
| `2700,2783p` | 84 | PARTIE 7 : FORMAT INTERNE A3D |
| `2702,2712p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.1 Objectifs |
| `2713,2738p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;7.2 Structure du fichier |
| `2739,2765p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;7.3 Sections normatives |
| `2766,2772p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;7.4 Versionnement et migration |
| `2773,2783p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.5 Sécurité de lecture |
| `2784,2924p` | 141 | PARTIE 8 : WORKFLOW BLENDER ET OUTILLAGE AUTEUR |
| `2786,2802p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;8.1 Flux nominal |
| `2803,2847p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;8.2 Convention par les `extras` glTF |
| `2848,2895p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;8.3 Addon Blender (C-75) |
| `2896,2924p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;8.4 Hiérarchie conventionnelle recommandée |
| `2925,2992p` | 68 | PARTIE 9 : SCENE GRAPH, NODES, SOCKETS, ÉTATS DE NODE |
| `2927,2944p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.1 Modèle |
| `2945,2962p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.2 États de node (SM-03) |
| `2963,2977p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;9.3 Sockets |
| `2978,2983p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;9.4 Visibilité, LOD et révélation |
| `2984,2992p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;9.5 Instanciation et partage |
| `2993,3103p` | 111 | PARTIE 10 : PHYSIQUE GÉNÉRIQUE |
| `2995,2998p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;10.1 Principe |
| `2999,3006p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;10.2 Types de corps |
| `3007,3019p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.3 Formes de collision |
| `3020,3030p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;10.4 Broad phase / narrow phase |
| `3031,3046p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;10.5 Intégration et solveur |
| `3047,3058p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;10.6 Forces environnementales |
| `3059,3080p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;10.7 Événements physiques |
| `3081,3093p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.8 Ce que la physique AXION ne fait pas |
| `3094,3103p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;10.9 Déterminisme |
| `3104,3253p` | 150 | PARTIE 11 : MATÉRIAUX PHYSIQUES ET MODÈLE DE MATIÈRE |
| `3106,3109p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;11.1 Rôle |
| `3110,3125p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;11.2 Groupes de propriétés |
| `3126,3181p` | 56 | &nbsp;&nbsp;&nbsp;&nbsp;11.3 Sémantique normative des paramètres de déformation |
| `3182,3200p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;11.4 Mappage bloc → matériau |
| `3201,3253p` | 53 | &nbsp;&nbsp;&nbsp;&nbsp;11.5 Cohérence dimensionnelle (référence normative) |
| `3254,3440p` | 187 | PARTIE 12 : VÉHICULES |
| `3256,3259p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;12.1 Positionnement |
| `3260,3285p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;12.2 Modèle de roue |
| `3286,3298p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;12.3 Suspension |
| `3299,3316p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;12.4 Modèle de pneu |
| `3317,3342p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;12.5 Groupe motopropulseur |
| `3343,3356p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;12.6 Direction et freinage |
| `3357,3366p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;12.7 Aérodynamique |
| `3367,3378p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;12.8 Entrées |
| `3379,3397p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;12.9 Tests véhicules |
| `3398,3440p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;12.10 Vues et tableau de bord |
| `3441,3651p` | 211 | PARTIE 13 : IMPACTS, ÉNERGIE ET MODÈLE DE DOMMAGE |
| `3443,3452p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;13.1 Principe |
| `3453,3481p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;13.2 Sources d'impact |
| `3482,3519p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.3 Distribution de l'énergie (C-35) |
| `3520,3578p` | 59 | &nbsp;&nbsp;&nbsp;&nbsp;13.3bis Modèle d'indentation plastique (référence normative) |
| `3579,3597p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;13.4 Effets fonctionnels |
| `3598,3602p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;13.5 Dommage continu |
| `3603,3613p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;13.6 Budgets et dégradation |
| `3614,3651p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.7 Tests |
| `3652,4031p` | 380 | PARTIE 14 : DÉFORMATION CONTINUE DE GÉOMÉTRIE |
| `3656,3673p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;14.1 Évaluation des approches |
| `3674,3700p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;14.2 Modèle mathématique |
| `3701,3716p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;14.3 Composantes du champ |
| `3717,3768p` | 52 | &nbsp;&nbsp;&nbsp;&nbsp;14.4 Application d'un impact (noyau déterministe) |
| `3769,3783p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;14.5 Niveaux de qualité de déformation |
| `3784,3831p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;14.6 Pipeline de rendu de la déformation (C-68) |
| `3832,3845p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;14.7 LOD de déformation |
| `3846,3862p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;14.8 Compléments déclaratifs |
| `3863,3905p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.9 Déformation et collision (C-45) |
| `3906,3912p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;14.10 Déchirure |
| `3913,3932p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;14.11 Budgets, mémoire et performance |
| `3933,3960p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;14.11bis Modèle de coût : portée exacte des garanties |
| `3961,3966p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;14.12 Réplication et persistance (résumé, détail en PARTIE 21 et 22) |
| `3967,4009p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.13 Tests |
| `4010,4031p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;14.14 Acceptance de la PARTIE 14 |
| `4032,4237p` | 206 | PARTIE 15 : INTÉGRITÉ STRUCTURELLE, RUPTURE, DÉTACHEMENT, DÉBRIS |
| `4034,4052p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;15.1 Modèle |
| `4053,4107p` | 55 | &nbsp;&nbsp;&nbsp;&nbsp;15.1bis Estimation de l'aire de liaison (référence normative) |
| `4108,4136p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;15.2 Propagation de l'énergie structurelle (C-43) |
| `4137,4176p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;15.3 Rupture et détachement (C-44) |
| `4177,4193p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;15.4 Débris |
| `4194,4199p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;15.5 Effets sur les mécanismes |
| `4200,4209p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;15.6 Budgets |
| `4210,4237p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;15.7 Tests |
| `4238,4319p` | 82 | PARTIE 16 : RÉPARATION, RESTAURATION ET REMPLACEMENT |
| `4240,4250p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;16.1 Niveaux de réparation |
| `4251,4265p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;16.2 Restauration du champ de déformation |
| `4266,4288p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;16.3 Règles data-driven |
| `4289,4319p` | 31 | &nbsp;&nbsp;&nbsp;&nbsp;16.4 Remplacement de part |
| `4320,4519p` | 200 | PARTIE 17 : SOLVEUR DE PARTICULES — TISSU, CORDES, CÂBLES, FILETS, CORPS SOUPLES |
| `4322,4325p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;17.1 Positionnement |
| `4326,4341p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;17.2 Modes d'autorité |
| `4342,4413p` | 72 | &nbsp;&nbsp;&nbsp;&nbsp;17.2bis Autorité et reconstruction visuelle (référence normative) |
| `4414,4436p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;17.3 Solveur |
| `4437,4448p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;17.4 Limites et budgets |
| `4449,4473p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;17.5 Définition |
| `4474,4479p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;17.6 Cordes et câbles physiques |
| `4480,4484p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;17.7 Interaction avec la déformation |
| `4485,4519p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;17.8 Tests |
| `4520,4651p` | 132 | PARTIE 18 : ANIMATION ET COUPLAGE ANIMATION / PHYSIQUE / DÉGÂTS |
| `4522,4534p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.1 Types d'animation |
| `4535,4547p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.2 Échantillonnage et blending |
| `4548,4566p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;18.3 Animation procédurale déclarative |
| `4567,4572p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;18.4 Palettes de skinning et ordre d'application |
| `4573,4622p` | 50 | &nbsp;&nbsp;&nbsp;&nbsp;18.5 Couplage animation / physique / dégâts (scénario normatif de la porte) |
| `4623,4626p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;18.6 Root motion |
| `4627,4651p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;18.7 Tests |
| `4652,4984p` | 333 | PARTIE 19 : RENDU |
| `4654,4674p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;19.1 Ce qui est réellement contrôlable depuis un mod Forge 1.20.1 |
| `4675,4696p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;19.2 Les deux backends |
| `4697,4740p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;19.2bis Portée de l'équivalence entre backends (référence normative) |
| `4741,4749p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.3 Gestion d'état OpenGL (règle critique) |
| `4750,4773p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;19.4 Format de vertex GPU et attributs d'instance |
| `4774,4822p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;19.5 Modèle d'éclairage : PBR avec environnement synthétisé |
| `4823,4831p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.6 Déformation dans le pipeline de rendu |
| `4832,4854p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;19.7 Occlusion culling logiciel (C-82) |
| `4855,4879p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;19.8 Ombres AXION (C-80) |
| `4880,4892p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;19.9 Effets intégrés (C-83) |
| `4893,4906p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;19.10 Passes de rendu |
| `4907,4911p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;19.11 Transparence |
| `4912,4930p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;19.12 Budgets de rendu et dégradation |
| `4931,4984p` | 54 | &nbsp;&nbsp;&nbsp;&nbsp;19.13 Tests de rendu |
| `4985,5053p` | 69 | PARTIE 20 : MATÉRIAUX DE RENDU, DÉCALQUES, USURE ET ÉTATS DE SURFACE |
| `4987,5006p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;20.1 États visuels |
| `5007,5025p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;20.2 Décalques (C-69) |
| `5026,5040p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;20.3 Profils d'usure |
| `5041,5053p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;20.4 Budgets |
| `5054,5272p` | 219 | PARTIE 21 : CLIENT / SERVEUR ET NETWORKING |
| `5056,5070p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;21.1 Modèle d'autorité |
| `5071,5080p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.2 Canal et protocole |
| `5081,5103p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;21.3 Handshake |
| `5104,5127p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;21.4 Paquets |
| `5128,5170p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;21.5 Snapshots, quantification, delta |
| `5171,5210p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;21.6 Réplication de la déformation (mécanisme central) |
| `5211,5220p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.7 Interpolation client |
| `5221,5228p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;21.8 Prédiction et réconciliation |
| `5229,5235p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;21.9 Sécurité réseau |
| `5236,5245p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.10 Budgets réseau |
| `5246,5272p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;21.11 Tests |
| `5273,5460p` | 188 | PARTIE 22 : PERSISTANCE |
| `5275,5300p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;22.1 Ce qui est persisté |
| `5301,5343p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;22.2 Format NBT |
| `5344,5356p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;22.3 Sérialisation du champ de déformation |
| `5357,5368p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;22.4 Chargement et déchargement de chunk |
| `5369,5430p` | 62 | &nbsp;&nbsp;&nbsp;&nbsp;22.5 Compatibilité de sauvegarde et retrait du mod |
| `5371,5379p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.1 Constat technique |
| `5380,5400p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.2 Journal latéral (side-car) |
| `5401,5430p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.3 Matrice de comportement (normative) |
| `5431,5460p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;22.6 Tests |
| `5461,5749p` | 289 | PARTIE 23 : API PUBLIQUE ET CONTENU DATA-DRIVEN |
| `5463,5473p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;23.1 Principes |
| `5474,5604p` | 131 | &nbsp;&nbsp;&nbsp;&nbsp;23.2 Surface de l'API |
| `5605,5633p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;23.3 Points d'extension |
| `5634,5734p` | 101 | &nbsp;&nbsp;&nbsp;&nbsp;23.4 Schéma de Definition (normatif) |
| `5735,5749p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;23.5 Contenu de démonstration |
| `5750,5816p` | 67 | PARTIE 24 : MODULARITÉ, NIVEAUX DE QUALITÉ ET CONFIGURATION |
| `5752,5778p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;24.1 Modules |
| `5779,5797p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;24.2 Niveaux de qualité |
| `5798,5803p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;24.3 Chargement à la demande |
| `5804,5816p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;24.4 Fichiers de configuration |
| `5817,5953p` | 137 | PARTIE 25 : PERFORMANCE — THREADING, BUDGETS, DÉGRADATION |
| `5819,5824p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;25.1 Position de principe |
| `5825,5851p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.2 Budgets par défaut |
| `5852,5878p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.3 Techniques retenues |
| `5879,5883p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.4 Double tampon et interpolation |
| `5884,5892p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;25.5 Détection de dépassement |
| `5893,5921p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;25.6 Machine de dégradation (SM-02) |
| `5922,5932p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;25.7 Modes SAFE et DISABLED |
| `5933,5937p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.8 Watchdog |
| `5938,5953p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;25.9 Tests |
| `5954,6024p` | 71 | PARTIE 26 : COMPATIBILITÉ GÉNÉRALE |
| `5956,5961p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;26.1 Principe |
| `5962,5974p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;26.2 Empreinte Mixin (liste fermée) |
| `5975,5990p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;26.3 Interactions avec le vanilla |
| `5991,5994p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;26.4 Limites documentées |
| `5995,6009p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.5 Mods tiers |
| `6010,6024p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.6 Tests |
| `6025,6296p` | 272 | PARTIE 27 : COMPATIBILITÉ RUSTFORGE-X |
| `6027,6056p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;27.0 Position fondamentale |
| `6057,6068p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;27.1 Détection |
| `6069,6083p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;27.2 Ce que la détection change (et ne change pas) |
| `6084,6148p` | 65 | &nbsp;&nbsp;&nbsp;&nbsp;27.3 Coexistence : analyse des points de contact |
| `6086,6096p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.1 Classloading |
| `6097,6106p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.2 Instrumentation et bytecode |
| `6107,6118p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.3 Threads et scheduler |
| `6119,6122p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.4 Tick et événements Forge |
| `6123,6135p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.5 Mémoire native et bibliothèques |
| `6136,6139p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.6 Rendu |
| `6140,6143p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.7 Réseau, monde, sauvegarde |
| `6144,6148p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.8 Cycle de vie et arrêt |
| `6149,6161p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;27.4 Partage du budget CPU |
| `6162,6194p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;27.5 Bridge d'interopérabilité optionnel (C-76) |
| `6195,6200p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;27.6 Indication de non-transformation |
| `6201,6221p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;27.7 Comment RUSTFORGE-X pourrait optimiser AXION (sans qu'AXION en dépende) |
| `6222,6235p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;27.8 Ce qu'AXION ne fera jamais |
| `6236,6245p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;27.9 Fallback |
| `6246,6296p` | 51 | &nbsp;&nbsp;&nbsp;&nbsp;27.10 Stratégie de test de coexistence |
| `6248,6266p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.1 Mod de simulation (obligatoire, toujours exécuté) |
| `6267,6270p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.2 Test avec l'artefact réel (obligatoire quand disponible) |
| `6271,6296p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.3 Matrice |
| `6297,6387p` | 91 | PARTIE 28 : SÉCURITÉ, STABILITÉ, CONFINEMENT |
| `6299,6311p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;28.1 Surface d'attaque |
| `6312,6317p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;28.2 Code `unsafe` en Rust |
| `6318,6345p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;28.3 Limites dures |
| `6346,6361p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;28.4 Confinement des pannes |
| `6362,6365p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.5 Vie privée |
| `6366,6369p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.6 Reproduction d'incident |
| `6370,6387p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;28.7 Tests |
| `6388,6566p` | 179 | PARTIE 29 : STRATÉGIE DE TEST |
| `6390,6397p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;29.1 Principes |
| `6398,6416p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;29.2 Pyramide |
| `6417,6452p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;29.3 Tests unitaires |
| `6453,6468p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;29.4 Tests d'intégration |
| `6469,6494p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;29.5 Tests de gameplay et scénarios déclaratifs |
| `6495,6528p` | 34 | &nbsp;&nbsp;&nbsp;&nbsp;29.6 Test de bout en bout obligatoire (T-970) |
| `6529,6552p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;29.7 Tests de stress et d'endurance |
| `6553,6566p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;29.8 Infrastructure |
| `6567,6645p` | 79 | PARTIE 30 : BENCHMARKS |
| `6569,6573p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;30.1 Position de principe |
| `6574,6608p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;30.2 Ce qui est mesuré |
| `6609,6626p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;30.3 Méthodologie normative |
| `6627,6637p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;30.4 Format de résultat |
| `6638,6645p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;30.5 Non-régression |
| `6646,6696p` | 51 | PARTIE 31 : PROFILING ET DEBUGGING |
| `6648,6660p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;31.1 Domaines profilés |
| `6661,6667p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;31.2 Outils |
| `6668,6673p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;31.3 Mode développeur |
| `6674,6688p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;31.4 Overlays de debug |
| `6689,6696p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;31.5 Reproduction |
| `6697,6769p` | 73 | PARTIE 32 : DÉPENDANCES ET LICENCES |
| `6699,6706p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;32.1 Licence du projet |
| `6707,6744p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;32.2 Dépendances Rust |
| `6745,6756p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;32.3 Dépendances Java |
| `6757,6769p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;32.4 Outillage et SBOM |
| `6770,6902p` | 133 | PARTIE 33 : ARBORESCENCE DU DÉPÔT |
| `6882,6902p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;33.2 Contenu de l'artefact final |
| `6903,7045p` | 143 | PARTIE 34 : BUILD, CI/CD, RELEASE, INSTALLATION |
| `6905,6940p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;34.1 Chaîne de build |
| `6941,6951p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;34.2 Plateformes |
| `6952,6995p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;34.3 CI/CD |
| `6996,7018p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;34.4 Versionnement et release |
| `7019,7028p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;34.5 Installation |
| `7029,7045p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;34.6 Test d'installation propre (obligatoire avant release) |
| `7046,7159p` | 114 | PARTIE 35 : DÉCISIONS D'ARCHITECTURE (ADR) |
| `7050,7052p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-001 — glTF 2.0 / GLB comme format d'échange principal |
| `7053,7055p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-002 — Rapier3D comme moteur physique |
| `7056,7058p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-003 — Modèle de véhicule par raycast |
| `7059,7061p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-004 — JNI + DirectByteBuffer pour la frontière Java/Rust |
| `7062,7064p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-005 — Autorité serveur, sans déterminisme physique inter-plateformes |
| `7065,7067p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-006 — Rendu piloté depuis Java sur le contexte GL de Minecraft |
| `7068,7079p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-007 — Deux backends de rendu maintenus, équivalence fonctionnelle et non pixellaire |
| `7080,7084p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-008 — PBR avec environnement synthétisé *(révisé en révision 2)* |
| `7085,7089p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-011 — Déformation continue par champ de lattice *(révisé en révision 2)* |
| `7090,7093p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-012 — Solveur de particules unifié à trois modes d'autorité *(révisé)* |
| `7094,7096p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-013 — Pas d'ECS tiers, structures SoA propres |
| `7097,7099p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-014 — Job system dédié au-dessus de rayon, jamais le pool global |
| `7100,7102p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-015 — Indépendance vis-à-vis de RUSTFORGE-X, compatibilité par conception |
| `7103,7105p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-016 — Empreinte Mixin fermée et minimale |
| `7106,7108p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-017 — Persistance du seul état non dérivable |
| `7109,7120p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-018 — Réplication de la déformation par événements et reconstruction déterministe |
| `7121,7125p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-019 — Refit de collider par déformation des points d'enveloppe |
| `7126,7130p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-020 — Graphe structurel pour la rupture et le détachement |
| `7131,7135p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-021 — Carte d'ombre dédiée aux objets AXION |
| `7136,7140p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-022 — Occlusion culling logiciel plutôt que requêtes GPU |
| `7141,7145p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-023 — Niveaux de qualité et gouverneur |
| `7146,7149p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-024 — Décalques en espace objet, suivant la déformation |
| `7150,7153p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-025 — Système d'attache entre assemblies |
| `7154,7159p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-026 — Chemins accélérés GL 4.x optionnels sur une base GL 3.3 |
| `7160,7323p` | 164 | PARTIE 36 : PÉRIMÈTRE V1.0 / V1.x / HORS PÉRIMÈTRE |
| `7162,7249p` | 88 | &nbsp;&nbsp;&nbsp;&nbsp;36.1 V1.0 — obligatoire |
| `7250,7267p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;36.2 V1.x — extensions futures |
| `7268,7286p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;36.3 Hors périmètre — exclu de la V1 |
| `7287,7323p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;36.4 Audit des limitations de la révision 1 |
| `7324,7567p` | 244 | PARTIE 37 : JALONS ET DEFINITION OF DONE |
| `7326,7333p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;37.1 Règles |
| `7334,7510p` | 177 | &nbsp;&nbsp;&nbsp;&nbsp;37.2 Jalons |
| `7336,7345p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M0 — Squelette et frontière native |
| `7346,7356p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M1 — Assets, noyau déterministe, jobs |
| `7357,7366p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M2 — Scene graph, cache, optimizer, entité, API |
| `7367,7376p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M3 — Physique et premier rendu |
| `7377,7386p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M4 — Réseau, animation, culling/LOD, joints, persistance |
| `7387,7398p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M5 — Véhicules, sièges, attaches, gouverneur, overlay |
| `7399,7410p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M5b — Véhicules hors roues (amendement A5) |
| `7411,7430p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M6 — Impacts et déformation continue *(jalon central)* |
| `7431,7440p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M7 — Structure, rupture, détachement, réparation |
| `7441,7449p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M8 — Particules unifiées |
| `7450,7461p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M9 — Rendu avancé |
| `7462,7469p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M10 — Blocs, items, bout en bout |
| `7470,7478p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M11 — Compatibilité, coexistence, durcissement |
| `7479,7490p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M12 — Optimisation client et serveur (amendement A22) |
| `7491,7502p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M13 — Espace (amendement A22) |
| `7503,7510p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M14 — Release 1.0.0 |
| `7511,7557p` | 47 | &nbsp;&nbsp;&nbsp;&nbsp;37.3 Definition of Done |
| `7558,7567p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;37.4 Sévérités |
| `7568,7992p` | 425 | PARTIE 38 : CRITÈRES D'ACCEPTATION ET AUDIT FINAL |
| `7570,7660p` | 91 | &nbsp;&nbsp;&nbsp;&nbsp;38.1 Checklist principale de la V1.0 |
| `7661,7684p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;38.2 Critères de correction (bloquants) — identifiants `AC-xx` |
| `7685,7701p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.3 Critères de performance — identifiants `PF-xx` |
| `7702,7718p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.4 Critères d'extensibilité — identifiants `EX-xx` |
| `7719,7763p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;38.5 Audit final obligatoire — résultats |
| `7764,7809p` | 46 | &nbsp;&nbsp;&nbsp;&nbsp;38.6 Contradictions détectées et résolutions |
| `7810,7844p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;38.7 Registre des changements de la révision 2 |
| `7845,7951p` | 107 | &nbsp;&nbsp;&nbsp;&nbsp;38.8 Registre des corrections de la révision 2.1 |
| `7850,7863p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Problèmes corrigés |
| `7864,7877p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Sections ajoutées |
| `7878,7886p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Équations modifiées |
| `7887,7898p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Exigences ajoutées ou modifiées |
| `7899,7906p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Invariants modifiés |
| `7907,7925p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Tests ajoutés |
| `7926,7933p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;ADR modifiés |
| `7934,7951p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Vérification de non-régression |
| `7952,7992p` | 41 | &nbsp;&nbsp;&nbsp;&nbsp;38.9 Registre des amendements postérieurs au gel |
| `7993,8483p` | 491 | ANNEXES |
| `7995,8043p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.1 — Codes d'erreur |
| `8044,8071p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.2 — Index des invariants |
| `8072,8310p` | 239 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.3 — Configuration complète (référence normative) |
| `8311,8346p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.4 — Sources procédurales (liste fermée V1.1, amendement A9) |
| `8347,8369p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.5 — Limitations connues de la V1.0 |
| `8370,8391p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.6 — Glossaire complémentaire |
| `8392,8483p` | 92 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.7 — Capacités exigées hors du texte gelé (ADR-160) |
| `8484,8635p` | 152 | FINAL V1.0 IMPLEMENTATION CONTRACT |
| `8486,8508p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;1. Confirmations formelles |
| `8509,8524p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;2. Boucle de travail obligatoire |
| `8525,8563p` | 39 | &nbsp;&nbsp;&nbsp;&nbsp;3. Interdictions absolues |
| `8564,8582p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;4. Obligations |
| `8583,8588p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5. Autonomie de décision |
| `8589,8602p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;6. Gestion de l'incertitude |
| `8603,8635p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;7. Critère final |
