# Index du cahier des charges AXION ENGINE

Genere par `tools/spec/spec_index.py`. Source : `docs/spec/AXIONENGINE_Cahier_des_Charges_v1.0.md` (8612 lignes).

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
| `1462,2587p` | 1126 | PARTIE 5 : SPÉCIFICATIONS COMPOSANT PAR COMPOSANT |
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
| `2462,2487p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;5.58 C-71 : Commandes |
| `2488,2491p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.59 C-72 : Benchmark Harness |
| `2492,2499p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.60 C-73 : Overlay diagnostics |
| `2500,2517p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.61 C-74 : CLI `axion-cli` |
| `2518,2521p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.62 C-75 : Addon Blender |
| `2522,2525p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.63 C-76 : Bridge RUSTFORGE-X |
| `2526,2562p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;5.64 C-77 : Gouverneur de qualité |
| `2563,2568p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.65 C-80 : Ombres AXION |
| `2569,2574p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.66 C-81 : Sonde d'environnement & IBL synthétique |
| `2575,2580p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.67 C-82 : Occlusion culling logiciel |
| `2581,2587p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;5.68 C-83 : Effets intégrés (tone mapping, SSR) |
| `2588,2698p` | 111 | PARTIE 6 : FORMATS 3D ET PIPELINE D'ASSETS |
| `2590,2601p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;6.1 Évaluation des formats |
| `2602,2611p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;6.2 Décisions figées |
| `2612,2651p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;6.3 Pipeline complet |
| `2652,2681p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;6.4 Options de compilation |
| `2682,2698p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;6.5 Contraintes d'auteur (documentées dans `ASSETS.md`) |
| `2699,2782p` | 84 | PARTIE 7 : FORMAT INTERNE A3D |
| `2701,2711p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.1 Objectifs |
| `2712,2737p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;7.2 Structure du fichier |
| `2738,2764p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;7.3 Sections normatives |
| `2765,2771p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;7.4 Versionnement et migration |
| `2772,2782p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.5 Sécurité de lecture |
| `2783,2923p` | 141 | PARTIE 8 : WORKFLOW BLENDER ET OUTILLAGE AUTEUR |
| `2785,2801p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;8.1 Flux nominal |
| `2802,2846p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;8.2 Convention par les `extras` glTF |
| `2847,2894p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;8.3 Addon Blender (C-75) |
| `2895,2923p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;8.4 Hiérarchie conventionnelle recommandée |
| `2924,2991p` | 68 | PARTIE 9 : SCENE GRAPH, NODES, SOCKETS, ÉTATS DE NODE |
| `2926,2943p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.1 Modèle |
| `2944,2961p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.2 États de node (SM-03) |
| `2962,2976p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;9.3 Sockets |
| `2977,2982p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;9.4 Visibilité, LOD et révélation |
| `2983,2991p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;9.5 Instanciation et partage |
| `2992,3102p` | 111 | PARTIE 10 : PHYSIQUE GÉNÉRIQUE |
| `2994,2997p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;10.1 Principe |
| `2998,3005p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;10.2 Types de corps |
| `3006,3018p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.3 Formes de collision |
| `3019,3029p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;10.4 Broad phase / narrow phase |
| `3030,3045p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;10.5 Intégration et solveur |
| `3046,3057p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;10.6 Forces environnementales |
| `3058,3079p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;10.7 Événements physiques |
| `3080,3092p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.8 Ce que la physique AXION ne fait pas |
| `3093,3102p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;10.9 Déterminisme |
| `3103,3252p` | 150 | PARTIE 11 : MATÉRIAUX PHYSIQUES ET MODÈLE DE MATIÈRE |
| `3105,3108p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;11.1 Rôle |
| `3109,3124p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;11.2 Groupes de propriétés |
| `3125,3180p` | 56 | &nbsp;&nbsp;&nbsp;&nbsp;11.3 Sémantique normative des paramètres de déformation |
| `3181,3199p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;11.4 Mappage bloc → matériau |
| `3200,3252p` | 53 | &nbsp;&nbsp;&nbsp;&nbsp;11.5 Cohérence dimensionnelle (référence normative) |
| `3253,3436p` | 184 | PARTIE 12 : VÉHICULES |
| `3255,3258p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;12.1 Positionnement |
| `3259,3284p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;12.2 Modèle de roue |
| `3285,3297p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;12.3 Suspension |
| `3298,3314p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;12.4 Modèle de pneu |
| `3315,3338p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;12.5 Groupe motopropulseur |
| `3339,3352p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;12.6 Direction et freinage |
| `3353,3362p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;12.7 Aérodynamique |
| `3363,3374p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;12.8 Entrées |
| `3375,3393p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;12.9 Tests véhicules |
| `3394,3436p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;12.10 Vues et tableau de bord |
| `3437,3647p` | 211 | PARTIE 13 : IMPACTS, ÉNERGIE ET MODÈLE DE DOMMAGE |
| `3439,3448p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;13.1 Principe |
| `3449,3477p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;13.2 Sources d'impact |
| `3478,3515p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.3 Distribution de l'énergie (C-35) |
| `3516,3574p` | 59 | &nbsp;&nbsp;&nbsp;&nbsp;13.3bis Modèle d'indentation plastique (référence normative) |
| `3575,3593p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;13.4 Effets fonctionnels |
| `3594,3598p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;13.5 Dommage continu |
| `3599,3609p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;13.6 Budgets et dégradation |
| `3610,3647p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.7 Tests |
| `3648,4027p` | 380 | PARTIE 14 : DÉFORMATION CONTINUE DE GÉOMÉTRIE |
| `3652,3669p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;14.1 Évaluation des approches |
| `3670,3696p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;14.2 Modèle mathématique |
| `3697,3712p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;14.3 Composantes du champ |
| `3713,3764p` | 52 | &nbsp;&nbsp;&nbsp;&nbsp;14.4 Application d'un impact (noyau déterministe) |
| `3765,3779p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;14.5 Niveaux de qualité de déformation |
| `3780,3827p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;14.6 Pipeline de rendu de la déformation (C-68) |
| `3828,3841p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;14.7 LOD de déformation |
| `3842,3858p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;14.8 Compléments déclaratifs |
| `3859,3901p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.9 Déformation et collision (C-45) |
| `3902,3908p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;14.10 Déchirure |
| `3909,3928p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;14.11 Budgets, mémoire et performance |
| `3929,3956p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;14.11bis Modèle de coût : portée exacte des garanties |
| `3957,3962p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;14.12 Réplication et persistance (résumé, détail en PARTIE 21 et 22) |
| `3963,4005p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.13 Tests |
| `4006,4027p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;14.14 Acceptance de la PARTIE 14 |
| `4028,4233p` | 206 | PARTIE 15 : INTÉGRITÉ STRUCTURELLE, RUPTURE, DÉTACHEMENT, DÉBRIS |
| `4030,4048p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;15.1 Modèle |
| `4049,4103p` | 55 | &nbsp;&nbsp;&nbsp;&nbsp;15.1bis Estimation de l'aire de liaison (référence normative) |
| `4104,4132p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;15.2 Propagation de l'énergie structurelle (C-43) |
| `4133,4172p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;15.3 Rupture et détachement (C-44) |
| `4173,4189p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;15.4 Débris |
| `4190,4195p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;15.5 Effets sur les mécanismes |
| `4196,4205p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;15.6 Budgets |
| `4206,4233p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;15.7 Tests |
| `4234,4315p` | 82 | PARTIE 16 : RÉPARATION, RESTAURATION ET REMPLACEMENT |
| `4236,4246p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;16.1 Niveaux de réparation |
| `4247,4261p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;16.2 Restauration du champ de déformation |
| `4262,4284p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;16.3 Règles data-driven |
| `4285,4315p` | 31 | &nbsp;&nbsp;&nbsp;&nbsp;16.4 Remplacement de part |
| `4316,4515p` | 200 | PARTIE 17 : SOLVEUR DE PARTICULES — TISSU, CORDES, CÂBLES, FILETS, CORPS SOUPLES |
| `4318,4321p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;17.1 Positionnement |
| `4322,4337p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;17.2 Modes d'autorité |
| `4338,4409p` | 72 | &nbsp;&nbsp;&nbsp;&nbsp;17.2bis Autorité et reconstruction visuelle (référence normative) |
| `4410,4432p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;17.3 Solveur |
| `4433,4444p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;17.4 Limites et budgets |
| `4445,4469p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;17.5 Définition |
| `4470,4475p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;17.6 Cordes et câbles physiques |
| `4476,4480p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;17.7 Interaction avec la déformation |
| `4481,4515p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;17.8 Tests |
| `4516,4647p` | 132 | PARTIE 18 : ANIMATION ET COUPLAGE ANIMATION / PHYSIQUE / DÉGÂTS |
| `4518,4530p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.1 Types d'animation |
| `4531,4543p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.2 Échantillonnage et blending |
| `4544,4562p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;18.3 Animation procédurale déclarative |
| `4563,4568p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;18.4 Palettes de skinning et ordre d'application |
| `4569,4618p` | 50 | &nbsp;&nbsp;&nbsp;&nbsp;18.5 Couplage animation / physique / dégâts (scénario normatif de la porte) |
| `4619,4622p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;18.6 Root motion |
| `4623,4647p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;18.7 Tests |
| `4648,4980p` | 333 | PARTIE 19 : RENDU |
| `4650,4670p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;19.1 Ce qui est réellement contrôlable depuis un mod Forge 1.20.1 |
| `4671,4692p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;19.2 Les deux backends |
| `4693,4736p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;19.2bis Portée de l'équivalence entre backends (référence normative) |
| `4737,4745p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.3 Gestion d'état OpenGL (règle critique) |
| `4746,4769p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;19.4 Format de vertex GPU et attributs d'instance |
| `4770,4818p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;19.5 Modèle d'éclairage : PBR avec environnement synthétisé |
| `4819,4827p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.6 Déformation dans le pipeline de rendu |
| `4828,4850p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;19.7 Occlusion culling logiciel (C-82) |
| `4851,4875p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;19.8 Ombres AXION (C-80) |
| `4876,4888p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;19.9 Effets intégrés (C-83) |
| `4889,4902p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;19.10 Passes de rendu |
| `4903,4907p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;19.11 Transparence |
| `4908,4926p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;19.12 Budgets de rendu et dégradation |
| `4927,4980p` | 54 | &nbsp;&nbsp;&nbsp;&nbsp;19.13 Tests de rendu |
| `4981,5049p` | 69 | PARTIE 20 : MATÉRIAUX DE RENDU, DÉCALQUES, USURE ET ÉTATS DE SURFACE |
| `4983,5002p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;20.1 États visuels |
| `5003,5021p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;20.2 Décalques (C-69) |
| `5022,5036p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;20.3 Profils d'usure |
| `5037,5049p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;20.4 Budgets |
| `5050,5264p` | 215 | PARTIE 21 : CLIENT / SERVEUR ET NETWORKING |
| `5052,5066p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;21.1 Modèle d'autorité |
| `5067,5076p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.2 Canal et protocole |
| `5077,5099p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;21.3 Handshake |
| `5100,5123p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;21.4 Paquets |
| `5124,5162p` | 39 | &nbsp;&nbsp;&nbsp;&nbsp;21.5 Snapshots, quantification, delta |
| `5163,5202p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;21.6 Réplication de la déformation (mécanisme central) |
| `5203,5212p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.7 Interpolation client |
| `5213,5220p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;21.8 Prédiction et réconciliation |
| `5221,5227p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;21.9 Sécurité réseau |
| `5228,5237p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.10 Budgets réseau |
| `5238,5264p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;21.11 Tests |
| `5265,5444p` | 180 | PARTIE 22 : PERSISTANCE |
| `5267,5292p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;22.1 Ce qui est persisté |
| `5293,5327p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;22.2 Format NBT |
| `5328,5340p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;22.3 Sérialisation du champ de déformation |
| `5341,5352p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;22.4 Chargement et déchargement de chunk |
| `5353,5414p` | 62 | &nbsp;&nbsp;&nbsp;&nbsp;22.5 Compatibilité de sauvegarde et retrait du mod |
| `5355,5363p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.1 Constat technique |
| `5364,5384p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.2 Journal latéral (side-car) |
| `5385,5414p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.3 Matrice de comportement (normative) |
| `5415,5444p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;22.6 Tests |
| `5445,5732p` | 288 | PARTIE 23 : API PUBLIQUE ET CONTENU DATA-DRIVEN |
| `5447,5457p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;23.1 Principes |
| `5458,5588p` | 131 | &nbsp;&nbsp;&nbsp;&nbsp;23.2 Surface de l'API |
| `5589,5616p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;23.3 Points d'extension |
| `5617,5717p` | 101 | &nbsp;&nbsp;&nbsp;&nbsp;23.4 Schéma de Definition (normatif) |
| `5718,5732p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;23.5 Contenu de démonstration |
| `5733,5799p` | 67 | PARTIE 24 : MODULARITÉ, NIVEAUX DE QUALITÉ ET CONFIGURATION |
| `5735,5761p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;24.1 Modules |
| `5762,5780p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;24.2 Niveaux de qualité |
| `5781,5786p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;24.3 Chargement à la demande |
| `5787,5799p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;24.4 Fichiers de configuration |
| `5800,5936p` | 137 | PARTIE 25 : PERFORMANCE — THREADING, BUDGETS, DÉGRADATION |
| `5802,5807p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;25.1 Position de principe |
| `5808,5834p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.2 Budgets par défaut |
| `5835,5861p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.3 Techniques retenues |
| `5862,5866p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.4 Double tampon et interpolation |
| `5867,5875p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;25.5 Détection de dépassement |
| `5876,5904p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;25.6 Machine de dégradation (SM-02) |
| `5905,5915p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;25.7 Modes SAFE et DISABLED |
| `5916,5920p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.8 Watchdog |
| `5921,5936p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;25.9 Tests |
| `5937,6007p` | 71 | PARTIE 26 : COMPATIBILITÉ GÉNÉRALE |
| `5939,5944p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;26.1 Principe |
| `5945,5957p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;26.2 Empreinte Mixin (liste fermée) |
| `5958,5973p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;26.3 Interactions avec le vanilla |
| `5974,5977p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;26.4 Limites documentées |
| `5978,5992p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.5 Mods tiers |
| `5993,6007p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.6 Tests |
| `6008,6279p` | 272 | PARTIE 27 : COMPATIBILITÉ RUSTFORGE-X |
| `6010,6039p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;27.0 Position fondamentale |
| `6040,6051p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;27.1 Détection |
| `6052,6066p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;27.2 Ce que la détection change (et ne change pas) |
| `6067,6131p` | 65 | &nbsp;&nbsp;&nbsp;&nbsp;27.3 Coexistence : analyse des points de contact |
| `6069,6079p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.1 Classloading |
| `6080,6089p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.2 Instrumentation et bytecode |
| `6090,6101p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.3 Threads et scheduler |
| `6102,6105p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.4 Tick et événements Forge |
| `6106,6118p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.5 Mémoire native et bibliothèques |
| `6119,6122p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.6 Rendu |
| `6123,6126p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.7 Réseau, monde, sauvegarde |
| `6127,6131p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.8 Cycle de vie et arrêt |
| `6132,6144p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;27.4 Partage du budget CPU |
| `6145,6177p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;27.5 Bridge d'interopérabilité optionnel (C-76) |
| `6178,6183p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;27.6 Indication de non-transformation |
| `6184,6204p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;27.7 Comment RUSTFORGE-X pourrait optimiser AXION (sans qu'AXION en dépende) |
| `6205,6218p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;27.8 Ce qu'AXION ne fera jamais |
| `6219,6228p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;27.9 Fallback |
| `6229,6279p` | 51 | &nbsp;&nbsp;&nbsp;&nbsp;27.10 Stratégie de test de coexistence |
| `6231,6249p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.1 Mod de simulation (obligatoire, toujours exécuté) |
| `6250,6253p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.2 Test avec l'artefact réel (obligatoire quand disponible) |
| `6254,6279p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.3 Matrice |
| `6280,6370p` | 91 | PARTIE 28 : SÉCURITÉ, STABILITÉ, CONFINEMENT |
| `6282,6294p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;28.1 Surface d'attaque |
| `6295,6300p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;28.2 Code `unsafe` en Rust |
| `6301,6328p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;28.3 Limites dures |
| `6329,6344p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;28.4 Confinement des pannes |
| `6345,6348p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.5 Vie privée |
| `6349,6352p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.6 Reproduction d'incident |
| `6353,6370p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;28.7 Tests |
| `6371,6549p` | 179 | PARTIE 29 : STRATÉGIE DE TEST |
| `6373,6380p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;29.1 Principes |
| `6381,6399p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;29.2 Pyramide |
| `6400,6435p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;29.3 Tests unitaires |
| `6436,6451p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;29.4 Tests d'intégration |
| `6452,6477p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;29.5 Tests de gameplay et scénarios déclaratifs |
| `6478,6511p` | 34 | &nbsp;&nbsp;&nbsp;&nbsp;29.6 Test de bout en bout obligatoire (T-970) |
| `6512,6535p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;29.7 Tests de stress et d'endurance |
| `6536,6549p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;29.8 Infrastructure |
| `6550,6628p` | 79 | PARTIE 30 : BENCHMARKS |
| `6552,6556p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;30.1 Position de principe |
| `6557,6591p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;30.2 Ce qui est mesuré |
| `6592,6609p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;30.3 Méthodologie normative |
| `6610,6620p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;30.4 Format de résultat |
| `6621,6628p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;30.5 Non-régression |
| `6629,6678p` | 50 | PARTIE 31 : PROFILING ET DEBUGGING |
| `6631,6643p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;31.1 Domaines profilés |
| `6644,6650p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;31.2 Outils |
| `6651,6656p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;31.3 Mode développeur |
| `6657,6670p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;31.4 Overlays de debug |
| `6671,6678p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;31.5 Reproduction |
| `6679,6751p` | 73 | PARTIE 32 : DÉPENDANCES ET LICENCES |
| `6681,6688p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;32.1 Licence du projet |
| `6689,6726p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;32.2 Dépendances Rust |
| `6727,6738p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;32.3 Dépendances Java |
| `6739,6751p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;32.4 Outillage et SBOM |
| `6752,6884p` | 133 | PARTIE 33 : ARBORESCENCE DU DÉPÔT |
| `6864,6884p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;33.2 Contenu de l'artefact final |
| `6885,7027p` | 143 | PARTIE 34 : BUILD, CI/CD, RELEASE, INSTALLATION |
| `6887,6922p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;34.1 Chaîne de build |
| `6923,6933p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;34.2 Plateformes |
| `6934,6977p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;34.3 CI/CD |
| `6978,7000p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;34.4 Versionnement et release |
| `7001,7010p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;34.5 Installation |
| `7011,7027p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;34.6 Test d'installation propre (obligatoire avant release) |
| `7028,7141p` | 114 | PARTIE 35 : DÉCISIONS D'ARCHITECTURE (ADR) |
| `7032,7034p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-001 — glTF 2.0 / GLB comme format d'échange principal |
| `7035,7037p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-002 — Rapier3D comme moteur physique |
| `7038,7040p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-003 — Modèle de véhicule par raycast |
| `7041,7043p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-004 — JNI + DirectByteBuffer pour la frontière Java/Rust |
| `7044,7046p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-005 — Autorité serveur, sans déterminisme physique inter-plateformes |
| `7047,7049p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-006 — Rendu piloté depuis Java sur le contexte GL de Minecraft |
| `7050,7061p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-007 — Deux backends de rendu maintenus, équivalence fonctionnelle et non pixellaire |
| `7062,7066p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-008 — PBR avec environnement synthétisé *(révisé en révision 2)* |
| `7067,7071p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-011 — Déformation continue par champ de lattice *(révisé en révision 2)* |
| `7072,7075p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-012 — Solveur de particules unifié à trois modes d'autorité *(révisé)* |
| `7076,7078p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-013 — Pas d'ECS tiers, structures SoA propres |
| `7079,7081p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-014 — Job system dédié au-dessus de rayon, jamais le pool global |
| `7082,7084p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-015 — Indépendance vis-à-vis de RUSTFORGE-X, compatibilité par conception |
| `7085,7087p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-016 — Empreinte Mixin fermée et minimale |
| `7088,7090p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-017 — Persistance du seul état non dérivable |
| `7091,7102p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-018 — Réplication de la déformation par événements et reconstruction déterministe |
| `7103,7107p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-019 — Refit de collider par déformation des points d'enveloppe |
| `7108,7112p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-020 — Graphe structurel pour la rupture et le détachement |
| `7113,7117p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-021 — Carte d'ombre dédiée aux objets AXION |
| `7118,7122p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-022 — Occlusion culling logiciel plutôt que requêtes GPU |
| `7123,7127p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-023 — Niveaux de qualité et gouverneur |
| `7128,7131p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-024 — Décalques en espace objet, suivant la déformation |
| `7132,7135p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-025 — Système d'attache entre assemblies |
| `7136,7141p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-026 — Chemins accélérés GL 4.x optionnels sur une base GL 3.3 |
| `7142,7305p` | 164 | PARTIE 36 : PÉRIMÈTRE V1.0 / V1.x / HORS PÉRIMÈTRE |
| `7144,7231p` | 88 | &nbsp;&nbsp;&nbsp;&nbsp;36.1 V1.0 — obligatoire |
| `7232,7249p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;36.2 V1.x — extensions futures |
| `7250,7268p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;36.3 Hors périmètre — exclu de la V1 |
| `7269,7305p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;36.4 Audit des limitations de la révision 1 |
| `7306,7549p` | 244 | PARTIE 37 : JALONS ET DEFINITION OF DONE |
| `7308,7315p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;37.1 Règles |
| `7316,7492p` | 177 | &nbsp;&nbsp;&nbsp;&nbsp;37.2 Jalons |
| `7318,7327p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M0 — Squelette et frontière native |
| `7328,7338p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M1 — Assets, noyau déterministe, jobs |
| `7339,7348p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M2 — Scene graph, cache, optimizer, entité, API |
| `7349,7358p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M3 — Physique et premier rendu |
| `7359,7368p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M4 — Réseau, animation, culling/LOD, joints, persistance |
| `7369,7380p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M5 — Véhicules, sièges, attaches, gouverneur, overlay |
| `7381,7392p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M5b — Véhicules hors roues (amendement A5) |
| `7393,7412p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M6 — Impacts et déformation continue *(jalon central)* |
| `7413,7422p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M7 — Structure, rupture, détachement, réparation |
| `7423,7431p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M8 — Particules unifiées |
| `7432,7443p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M9 — Rendu avancé |
| `7444,7451p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M10 — Blocs, items, bout en bout |
| `7452,7460p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M11 — Compatibilité, coexistence, durcissement |
| `7461,7472p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M12 — Optimisation client et serveur (amendement A22) |
| `7473,7484p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M13 — Espace (amendement A22) |
| `7485,7492p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M14 — Release 1.0.0 |
| `7493,7539p` | 47 | &nbsp;&nbsp;&nbsp;&nbsp;37.3 Definition of Done |
| `7540,7549p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;37.4 Sévérités |
| `7550,7974p` | 425 | PARTIE 38 : CRITÈRES D'ACCEPTATION ET AUDIT FINAL |
| `7552,7642p` | 91 | &nbsp;&nbsp;&nbsp;&nbsp;38.1 Checklist principale de la V1.0 |
| `7643,7666p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;38.2 Critères de correction (bloquants) — identifiants `AC-xx` |
| `7667,7683p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.3 Critères de performance — identifiants `PF-xx` |
| `7684,7700p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.4 Critères d'extensibilité — identifiants `EX-xx` |
| `7701,7745p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;38.5 Audit final obligatoire — résultats |
| `7746,7791p` | 46 | &nbsp;&nbsp;&nbsp;&nbsp;38.6 Contradictions détectées et résolutions |
| `7792,7826p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;38.7 Registre des changements de la révision 2 |
| `7827,7933p` | 107 | &nbsp;&nbsp;&nbsp;&nbsp;38.8 Registre des corrections de la révision 2.1 |
| `7832,7845p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Problèmes corrigés |
| `7846,7859p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Sections ajoutées |
| `7860,7868p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Équations modifiées |
| `7869,7880p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Exigences ajoutées ou modifiées |
| `7881,7888p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Invariants modifiés |
| `7889,7907p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Tests ajoutés |
| `7908,7915p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;ADR modifiés |
| `7916,7933p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Vérification de non-régression |
| `7934,7974p` | 41 | &nbsp;&nbsp;&nbsp;&nbsp;38.9 Registre des amendements postérieurs au gel |
| `7975,8460p` | 486 | ANNEXES |
| `7977,8024p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.1 — Codes d'erreur |
| `8025,8052p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.2 — Index des invariants |
| `8053,8288p` | 236 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.3 — Configuration complète (référence normative) |
| `8289,8323p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.4 — Sources procédurales (liste fermée V1.1, amendement A9) |
| `8324,8346p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.5 — Limitations connues de la V1.0 |
| `8347,8368p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.6 — Glossaire complémentaire |
| `8369,8460p` | 92 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.7 — Capacités exigées hors du texte gelé (ADR-160) |
| `8461,8612p` | 152 | FINAL V1.0 IMPLEMENTATION CONTRACT |
| `8463,8485p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;1. Confirmations formelles |
| `8486,8501p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;2. Boucle de travail obligatoire |
| `8502,8540p` | 39 | &nbsp;&nbsp;&nbsp;&nbsp;3. Interdictions absolues |
| `8541,8559p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;4. Obligations |
| `8560,8565p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5. Autonomie de décision |
| `8566,8579p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;6. Gestion de l'incertitude |
| `8580,8612p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;7. Critère final |
