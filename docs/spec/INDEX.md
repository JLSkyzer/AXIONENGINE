# Index du cahier des charges AXION ENGINE

Genere par `tools/spec/spec_index.py`. Source : `docs/spec/AXIONENGINE_Cahier_des_Charges_v1.0.md` (8533 lignes).

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
| `1462,2586p` | 1125 | PARTIE 5 : SPÉCIFICATIONS COMPOSANT PAR COMPOSANT |
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
| `2462,2486p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;5.58 C-71 : Commandes |
| `2487,2490p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.59 C-72 : Benchmark Harness |
| `2491,2498p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.60 C-73 : Overlay diagnostics |
| `2499,2516p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.61 C-74 : CLI `axion-cli` |
| `2517,2520p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.62 C-75 : Addon Blender |
| `2521,2524p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.63 C-76 : Bridge RUSTFORGE-X |
| `2525,2561p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;5.64 C-77 : Gouverneur de qualité |
| `2562,2567p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.65 C-80 : Ombres AXION |
| `2568,2573p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.66 C-81 : Sonde d'environnement & IBL synthétique |
| `2574,2579p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.67 C-82 : Occlusion culling logiciel |
| `2580,2586p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;5.68 C-83 : Effets intégrés (tone mapping, SSR) |
| `2587,2697p` | 111 | PARTIE 6 : FORMATS 3D ET PIPELINE D'ASSETS |
| `2589,2600p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;6.1 Évaluation des formats |
| `2601,2610p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;6.2 Décisions figées |
| `2611,2650p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;6.3 Pipeline complet |
| `2651,2680p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;6.4 Options de compilation |
| `2681,2697p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;6.5 Contraintes d'auteur (documentées dans `ASSETS.md`) |
| `2698,2781p` | 84 | PARTIE 7 : FORMAT INTERNE A3D |
| `2700,2710p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.1 Objectifs |
| `2711,2736p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;7.2 Structure du fichier |
| `2737,2763p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;7.3 Sections normatives |
| `2764,2770p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;7.4 Versionnement et migration |
| `2771,2781p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.5 Sécurité de lecture |
| `2782,2922p` | 141 | PARTIE 8 : WORKFLOW BLENDER ET OUTILLAGE AUTEUR |
| `2784,2800p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;8.1 Flux nominal |
| `2801,2845p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;8.2 Convention par les `extras` glTF |
| `2846,2893p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;8.3 Addon Blender (C-75) |
| `2894,2922p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;8.4 Hiérarchie conventionnelle recommandée |
| `2923,2990p` | 68 | PARTIE 9 : SCENE GRAPH, NODES, SOCKETS, ÉTATS DE NODE |
| `2925,2942p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.1 Modèle |
| `2943,2960p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.2 États de node (SM-03) |
| `2961,2975p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;9.3 Sockets |
| `2976,2981p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;9.4 Visibilité, LOD et révélation |
| `2982,2990p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;9.5 Instanciation et partage |
| `2991,3101p` | 111 | PARTIE 10 : PHYSIQUE GÉNÉRIQUE |
| `2993,2996p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;10.1 Principe |
| `2997,3004p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;10.2 Types de corps |
| `3005,3017p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.3 Formes de collision |
| `3018,3028p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;10.4 Broad phase / narrow phase |
| `3029,3044p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;10.5 Intégration et solveur |
| `3045,3056p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;10.6 Forces environnementales |
| `3057,3078p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;10.7 Événements physiques |
| `3079,3091p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.8 Ce que la physique AXION ne fait pas |
| `3092,3101p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;10.9 Déterminisme |
| `3102,3251p` | 150 | PARTIE 11 : MATÉRIAUX PHYSIQUES ET MODÈLE DE MATIÈRE |
| `3104,3107p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;11.1 Rôle |
| `3108,3123p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;11.2 Groupes de propriétés |
| `3124,3179p` | 56 | &nbsp;&nbsp;&nbsp;&nbsp;11.3 Sémantique normative des paramètres de déformation |
| `3180,3198p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;11.4 Mappage bloc → matériau |
| `3199,3251p` | 53 | &nbsp;&nbsp;&nbsp;&nbsp;11.5 Cohérence dimensionnelle (référence normative) |
| `3252,3435p` | 184 | PARTIE 12 : VÉHICULES |
| `3254,3257p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;12.1 Positionnement |
| `3258,3283p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;12.2 Modèle de roue |
| `3284,3296p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;12.3 Suspension |
| `3297,3313p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;12.4 Modèle de pneu |
| `3314,3337p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;12.5 Groupe motopropulseur |
| `3338,3351p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;12.6 Direction et freinage |
| `3352,3361p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;12.7 Aérodynamique |
| `3362,3373p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;12.8 Entrées |
| `3374,3392p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;12.9 Tests véhicules |
| `3393,3435p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;12.10 Vues et tableau de bord |
| `3436,3646p` | 211 | PARTIE 13 : IMPACTS, ÉNERGIE ET MODÈLE DE DOMMAGE |
| `3438,3447p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;13.1 Principe |
| `3448,3476p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;13.2 Sources d'impact |
| `3477,3514p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.3 Distribution de l'énergie (C-35) |
| `3515,3573p` | 59 | &nbsp;&nbsp;&nbsp;&nbsp;13.3bis Modèle d'indentation plastique (référence normative) |
| `3574,3592p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;13.4 Effets fonctionnels |
| `3593,3597p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;13.5 Dommage continu |
| `3598,3608p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;13.6 Budgets et dégradation |
| `3609,3646p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.7 Tests |
| `3647,4026p` | 380 | PARTIE 14 : DÉFORMATION CONTINUE DE GÉOMÉTRIE |
| `3651,3668p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;14.1 Évaluation des approches |
| `3669,3695p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;14.2 Modèle mathématique |
| `3696,3711p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;14.3 Composantes du champ |
| `3712,3763p` | 52 | &nbsp;&nbsp;&nbsp;&nbsp;14.4 Application d'un impact (noyau déterministe) |
| `3764,3778p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;14.5 Niveaux de qualité de déformation |
| `3779,3826p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;14.6 Pipeline de rendu de la déformation (C-68) |
| `3827,3840p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;14.7 LOD de déformation |
| `3841,3857p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;14.8 Compléments déclaratifs |
| `3858,3900p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.9 Déformation et collision (C-45) |
| `3901,3907p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;14.10 Déchirure |
| `3908,3927p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;14.11 Budgets, mémoire et performance |
| `3928,3955p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;14.11bis Modèle de coût : portée exacte des garanties |
| `3956,3961p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;14.12 Réplication et persistance (résumé, détail en PARTIE 21 et 22) |
| `3962,4004p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.13 Tests |
| `4005,4026p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;14.14 Acceptance de la PARTIE 14 |
| `4027,4232p` | 206 | PARTIE 15 : INTÉGRITÉ STRUCTURELLE, RUPTURE, DÉTACHEMENT, DÉBRIS |
| `4029,4047p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;15.1 Modèle |
| `4048,4102p` | 55 | &nbsp;&nbsp;&nbsp;&nbsp;15.1bis Estimation de l'aire de liaison (référence normative) |
| `4103,4131p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;15.2 Propagation de l'énergie structurelle (C-43) |
| `4132,4171p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;15.3 Rupture et détachement (C-44) |
| `4172,4188p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;15.4 Débris |
| `4189,4194p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;15.5 Effets sur les mécanismes |
| `4195,4204p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;15.6 Budgets |
| `4205,4232p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;15.7 Tests |
| `4233,4314p` | 82 | PARTIE 16 : RÉPARATION, RESTAURATION ET REMPLACEMENT |
| `4235,4245p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;16.1 Niveaux de réparation |
| `4246,4260p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;16.2 Restauration du champ de déformation |
| `4261,4283p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;16.3 Règles data-driven |
| `4284,4314p` | 31 | &nbsp;&nbsp;&nbsp;&nbsp;16.4 Remplacement de part |
| `4315,4514p` | 200 | PARTIE 17 : SOLVEUR DE PARTICULES — TISSU, CORDES, CÂBLES, FILETS, CORPS SOUPLES |
| `4317,4320p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;17.1 Positionnement |
| `4321,4336p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;17.2 Modes d'autorité |
| `4337,4408p` | 72 | &nbsp;&nbsp;&nbsp;&nbsp;17.2bis Autorité et reconstruction visuelle (référence normative) |
| `4409,4431p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;17.3 Solveur |
| `4432,4443p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;17.4 Limites et budgets |
| `4444,4468p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;17.5 Définition |
| `4469,4474p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;17.6 Cordes et câbles physiques |
| `4475,4479p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;17.7 Interaction avec la déformation |
| `4480,4514p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;17.8 Tests |
| `4515,4646p` | 132 | PARTIE 18 : ANIMATION ET COUPLAGE ANIMATION / PHYSIQUE / DÉGÂTS |
| `4517,4529p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.1 Types d'animation |
| `4530,4542p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.2 Échantillonnage et blending |
| `4543,4561p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;18.3 Animation procédurale déclarative |
| `4562,4567p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;18.4 Palettes de skinning et ordre d'application |
| `4568,4617p` | 50 | &nbsp;&nbsp;&nbsp;&nbsp;18.5 Couplage animation / physique / dégâts (scénario normatif de la porte) |
| `4618,4621p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;18.6 Root motion |
| `4622,4646p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;18.7 Tests |
| `4647,4979p` | 333 | PARTIE 19 : RENDU |
| `4649,4669p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;19.1 Ce qui est réellement contrôlable depuis un mod Forge 1.20.1 |
| `4670,4691p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;19.2 Les deux backends |
| `4692,4735p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;19.2bis Portée de l'équivalence entre backends (référence normative) |
| `4736,4744p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.3 Gestion d'état OpenGL (règle critique) |
| `4745,4768p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;19.4 Format de vertex GPU et attributs d'instance |
| `4769,4817p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;19.5 Modèle d'éclairage : PBR avec environnement synthétisé |
| `4818,4826p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.6 Déformation dans le pipeline de rendu |
| `4827,4849p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;19.7 Occlusion culling logiciel (C-82) |
| `4850,4874p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;19.8 Ombres AXION (C-80) |
| `4875,4887p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;19.9 Effets intégrés (C-83) |
| `4888,4901p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;19.10 Passes de rendu |
| `4902,4906p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;19.11 Transparence |
| `4907,4925p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;19.12 Budgets de rendu et dégradation |
| `4926,4979p` | 54 | &nbsp;&nbsp;&nbsp;&nbsp;19.13 Tests de rendu |
| `4980,5048p` | 69 | PARTIE 20 : MATÉRIAUX DE RENDU, DÉCALQUES, USURE ET ÉTATS DE SURFACE |
| `4982,5001p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;20.1 États visuels |
| `5002,5020p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;20.2 Décalques (C-69) |
| `5021,5035p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;20.3 Profils d'usure |
| `5036,5048p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;20.4 Budgets |
| `5049,5249p` | 201 | PARTIE 21 : CLIENT / SERVEUR ET NETWORKING |
| `5051,5065p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;21.1 Modèle d'autorité |
| `5066,5075p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.2 Canal et protocole |
| `5076,5098p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;21.3 Handshake |
| `5099,5121p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;21.4 Paquets |
| `5122,5147p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;21.5 Snapshots, quantification, delta |
| `5148,5187p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;21.6 Réplication de la déformation (mécanisme central) |
| `5188,5197p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.7 Interpolation client |
| `5198,5205p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;21.8 Prédiction et réconciliation |
| `5206,5212p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;21.9 Sécurité réseau |
| `5213,5222p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.10 Budgets réseau |
| `5223,5249p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;21.11 Tests |
| `5250,5426p` | 177 | PARTIE 22 : PERSISTANCE |
| `5252,5277p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;22.1 Ce qui est persisté |
| `5278,5309p` | 32 | &nbsp;&nbsp;&nbsp;&nbsp;22.2 Format NBT |
| `5310,5322p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;22.3 Sérialisation du champ de déformation |
| `5323,5334p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;22.4 Chargement et déchargement de chunk |
| `5335,5396p` | 62 | &nbsp;&nbsp;&nbsp;&nbsp;22.5 Compatibilité de sauvegarde et retrait du mod |
| `5337,5345p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.1 Constat technique |
| `5346,5366p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.2 Journal latéral (side-car) |
| `5367,5396p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.3 Matrice de comportement (normative) |
| `5397,5426p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;22.6 Tests |
| `5427,5712p` | 286 | PARTIE 23 : API PUBLIQUE ET CONTENU DATA-DRIVEN |
| `5429,5439p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;23.1 Principes |
| `5440,5570p` | 131 | &nbsp;&nbsp;&nbsp;&nbsp;23.2 Surface de l'API |
| `5571,5596p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;23.3 Points d'extension |
| `5597,5697p` | 101 | &nbsp;&nbsp;&nbsp;&nbsp;23.4 Schéma de Definition (normatif) |
| `5698,5712p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;23.5 Contenu de démonstration |
| `5713,5779p` | 67 | PARTIE 24 : MODULARITÉ, NIVEAUX DE QUALITÉ ET CONFIGURATION |
| `5715,5741p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;24.1 Modules |
| `5742,5760p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;24.2 Niveaux de qualité |
| `5761,5766p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;24.3 Chargement à la demande |
| `5767,5779p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;24.4 Fichiers de configuration |
| `5780,5916p` | 137 | PARTIE 25 : PERFORMANCE — THREADING, BUDGETS, DÉGRADATION |
| `5782,5787p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;25.1 Position de principe |
| `5788,5814p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.2 Budgets par défaut |
| `5815,5841p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.3 Techniques retenues |
| `5842,5846p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.4 Double tampon et interpolation |
| `5847,5855p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;25.5 Détection de dépassement |
| `5856,5884p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;25.6 Machine de dégradation (SM-02) |
| `5885,5895p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;25.7 Modes SAFE et DISABLED |
| `5896,5900p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.8 Watchdog |
| `5901,5916p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;25.9 Tests |
| `5917,5986p` | 70 | PARTIE 26 : COMPATIBILITÉ GÉNÉRALE |
| `5919,5924p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;26.1 Principe |
| `5925,5936p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;26.2 Empreinte Mixin (liste fermée) |
| `5937,5952p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;26.3 Interactions avec le vanilla |
| `5953,5956p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;26.4 Limites documentées |
| `5957,5971p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.5 Mods tiers |
| `5972,5986p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.6 Tests |
| `5987,6258p` | 272 | PARTIE 27 : COMPATIBILITÉ RUSTFORGE-X |
| `5989,6018p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;27.0 Position fondamentale |
| `6019,6030p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;27.1 Détection |
| `6031,6045p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;27.2 Ce que la détection change (et ne change pas) |
| `6046,6110p` | 65 | &nbsp;&nbsp;&nbsp;&nbsp;27.3 Coexistence : analyse des points de contact |
| `6048,6058p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.1 Classloading |
| `6059,6068p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.2 Instrumentation et bytecode |
| `6069,6080p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.3 Threads et scheduler |
| `6081,6084p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.4 Tick et événements Forge |
| `6085,6097p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.5 Mémoire native et bibliothèques |
| `6098,6101p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.6 Rendu |
| `6102,6105p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.7 Réseau, monde, sauvegarde |
| `6106,6110p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.8 Cycle de vie et arrêt |
| `6111,6123p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;27.4 Partage du budget CPU |
| `6124,6156p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;27.5 Bridge d'interopérabilité optionnel (C-76) |
| `6157,6162p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;27.6 Indication de non-transformation |
| `6163,6183p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;27.7 Comment RUSTFORGE-X pourrait optimiser AXION (sans qu'AXION en dépende) |
| `6184,6197p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;27.8 Ce qu'AXION ne fera jamais |
| `6198,6207p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;27.9 Fallback |
| `6208,6258p` | 51 | &nbsp;&nbsp;&nbsp;&nbsp;27.10 Stratégie de test de coexistence |
| `6210,6228p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.1 Mod de simulation (obligatoire, toujours exécuté) |
| `6229,6232p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.2 Test avec l'artefact réel (obligatoire quand disponible) |
| `6233,6258p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.3 Matrice |
| `6259,6349p` | 91 | PARTIE 28 : SÉCURITÉ, STABILITÉ, CONFINEMENT |
| `6261,6273p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;28.1 Surface d'attaque |
| `6274,6279p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;28.2 Code `unsafe` en Rust |
| `6280,6307p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;28.3 Limites dures |
| `6308,6323p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;28.4 Confinement des pannes |
| `6324,6327p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.5 Vie privée |
| `6328,6331p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.6 Reproduction d'incident |
| `6332,6349p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;28.7 Tests |
| `6350,6528p` | 179 | PARTIE 29 : STRATÉGIE DE TEST |
| `6352,6359p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;29.1 Principes |
| `6360,6378p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;29.2 Pyramide |
| `6379,6414p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;29.3 Tests unitaires |
| `6415,6430p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;29.4 Tests d'intégration |
| `6431,6456p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;29.5 Tests de gameplay et scénarios déclaratifs |
| `6457,6490p` | 34 | &nbsp;&nbsp;&nbsp;&nbsp;29.6 Test de bout en bout obligatoire (T-970) |
| `6491,6514p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;29.7 Tests de stress et d'endurance |
| `6515,6528p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;29.8 Infrastructure |
| `6529,6607p` | 79 | PARTIE 30 : BENCHMARKS |
| `6531,6535p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;30.1 Position de principe |
| `6536,6570p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;30.2 Ce qui est mesuré |
| `6571,6588p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;30.3 Méthodologie normative |
| `6589,6599p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;30.4 Format de résultat |
| `6600,6607p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;30.5 Non-régression |
| `6608,6657p` | 50 | PARTIE 31 : PROFILING ET DEBUGGING |
| `6610,6622p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;31.1 Domaines profilés |
| `6623,6629p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;31.2 Outils |
| `6630,6635p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;31.3 Mode développeur |
| `6636,6649p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;31.4 Overlays de debug |
| `6650,6657p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;31.5 Reproduction |
| `6658,6730p` | 73 | PARTIE 32 : DÉPENDANCES ET LICENCES |
| `6660,6667p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;32.1 Licence du projet |
| `6668,6705p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;32.2 Dépendances Rust |
| `6706,6717p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;32.3 Dépendances Java |
| `6718,6730p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;32.4 Outillage et SBOM |
| `6731,6863p` | 133 | PARTIE 33 : ARBORESCENCE DU DÉPÔT |
| `6843,6863p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;33.2 Contenu de l'artefact final |
| `6864,7006p` | 143 | PARTIE 34 : BUILD, CI/CD, RELEASE, INSTALLATION |
| `6866,6901p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;34.1 Chaîne de build |
| `6902,6912p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;34.2 Plateformes |
| `6913,6956p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;34.3 CI/CD |
| `6957,6979p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;34.4 Versionnement et release |
| `6980,6989p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;34.5 Installation |
| `6990,7006p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;34.6 Test d'installation propre (obligatoire avant release) |
| `7007,7120p` | 114 | PARTIE 35 : DÉCISIONS D'ARCHITECTURE (ADR) |
| `7011,7013p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-001 — glTF 2.0 / GLB comme format d'échange principal |
| `7014,7016p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-002 — Rapier3D comme moteur physique |
| `7017,7019p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-003 — Modèle de véhicule par raycast |
| `7020,7022p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-004 — JNI + DirectByteBuffer pour la frontière Java/Rust |
| `7023,7025p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-005 — Autorité serveur, sans déterminisme physique inter-plateformes |
| `7026,7028p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-006 — Rendu piloté depuis Java sur le contexte GL de Minecraft |
| `7029,7040p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-007 — Deux backends de rendu maintenus, équivalence fonctionnelle et non pixellaire |
| `7041,7045p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-008 — PBR avec environnement synthétisé *(révisé en révision 2)* |
| `7046,7050p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-011 — Déformation continue par champ de lattice *(révisé en révision 2)* |
| `7051,7054p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-012 — Solveur de particules unifié à trois modes d'autorité *(révisé)* |
| `7055,7057p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-013 — Pas d'ECS tiers, structures SoA propres |
| `7058,7060p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-014 — Job system dédié au-dessus de rayon, jamais le pool global |
| `7061,7063p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-015 — Indépendance vis-à-vis de RUSTFORGE-X, compatibilité par conception |
| `7064,7066p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-016 — Empreinte Mixin fermée et minimale |
| `7067,7069p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-017 — Persistance du seul état non dérivable |
| `7070,7081p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-018 — Réplication de la déformation par événements et reconstruction déterministe |
| `7082,7086p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-019 — Refit de collider par déformation des points d'enveloppe |
| `7087,7091p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-020 — Graphe structurel pour la rupture et le détachement |
| `7092,7096p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-021 — Carte d'ombre dédiée aux objets AXION |
| `7097,7101p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-022 — Occlusion culling logiciel plutôt que requêtes GPU |
| `7102,7106p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-023 — Niveaux de qualité et gouverneur |
| `7107,7110p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-024 — Décalques en espace objet, suivant la déformation |
| `7111,7114p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-025 — Système d'attache entre assemblies |
| `7115,7120p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-026 — Chemins accélérés GL 4.x optionnels sur une base GL 3.3 |
| `7121,7284p` | 164 | PARTIE 36 : PÉRIMÈTRE V1.0 / V1.x / HORS PÉRIMÈTRE |
| `7123,7210p` | 88 | &nbsp;&nbsp;&nbsp;&nbsp;36.1 V1.0 — obligatoire |
| `7211,7228p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;36.2 V1.x — extensions futures |
| `7229,7247p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;36.3 Hors périmètre — exclu de la V1 |
| `7248,7284p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;36.4 Audit des limitations de la révision 1 |
| `7285,7501p` | 217 | PARTIE 37 : JALONS ET DEFINITION OF DONE |
| `7287,7294p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;37.1 Règles |
| `7295,7444p` | 150 | &nbsp;&nbsp;&nbsp;&nbsp;37.2 Jalons |
| `7297,7306p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M0 — Squelette et frontière native |
| `7307,7317p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M1 — Assets, noyau déterministe, jobs |
| `7318,7327p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M2 — Scene graph, cache, optimizer, entité, API |
| `7328,7337p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M3 — Physique et premier rendu |
| `7338,7347p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M4 — Réseau, animation, culling/LOD, joints, persistance |
| `7348,7359p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M5 — Véhicules, sièges, attaches, gouverneur, overlay |
| `7360,7368p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M5b — Véhicules hors roues (amendement A5) |
| `7369,7388p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M6 — Impacts et déformation continue *(jalon central)* |
| `7389,7398p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M7 — Structure, rupture, détachement, réparation |
| `7399,7407p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M8 — Particules unifiées |
| `7408,7419p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M9 — Rendu avancé |
| `7420,7427p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M10 — Blocs, items, bout en bout |
| `7428,7436p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M11 — Compatibilité, coexistence, durcissement |
| `7437,7444p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M12 — Release 1.0.0 |
| `7445,7491p` | 47 | &nbsp;&nbsp;&nbsp;&nbsp;37.3 Definition of Done |
| `7492,7501p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;37.4 Sévérités |
| `7502,7919p` | 418 | PARTIE 38 : CRITÈRES D'ACCEPTATION ET AUDIT FINAL |
| `7504,7594p` | 91 | &nbsp;&nbsp;&nbsp;&nbsp;38.1 Checklist principale de la V1.0 |
| `7595,7618p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;38.2 Critères de correction (bloquants) — identifiants `AC-xx` |
| `7619,7635p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.3 Critères de performance — identifiants `PF-xx` |
| `7636,7652p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.4 Critères d'extensibilité — identifiants `EX-xx` |
| `7653,7697p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;38.5 Audit final obligatoire — résultats |
| `7698,7743p` | 46 | &nbsp;&nbsp;&nbsp;&nbsp;38.6 Contradictions détectées et résolutions |
| `7744,7778p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;38.7 Registre des changements de la révision 2 |
| `7779,7885p` | 107 | &nbsp;&nbsp;&nbsp;&nbsp;38.8 Registre des corrections de la révision 2.1 |
| `7784,7797p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Problèmes corrigés |
| `7798,7811p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Sections ajoutées |
| `7812,7820p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Équations modifiées |
| `7821,7832p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Exigences ajoutées ou modifiées |
| `7833,7840p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Invariants modifiés |
| `7841,7859p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Tests ajoutés |
| `7860,7867p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;ADR modifiés |
| `7868,7885p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Vérification de non-régression |
| `7886,7919p` | 34 | &nbsp;&nbsp;&nbsp;&nbsp;38.9 Registre des amendements postérieurs au gel |
| `7920,8381p` | 462 | ANNEXES |
| `7922,7968p` | 47 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.1 — Codes d'erreur |
| `7969,7996p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.2 — Index des invariants |
| `7997,8230p` | 234 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.3 — Configuration complète (référence normative) |
| `8231,8260p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.4 — Sources procédurales (liste fermée V1.1, amendement A9) |
| `8261,8283p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.5 — Limitations connues de la V1.0 |
| `8284,8305p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.6 — Glossaire complémentaire |
| `8306,8381p` | 76 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.7 — Capacités exigées hors du texte gelé (ADR-160) |
| `8382,8533p` | 152 | FINAL V1.0 IMPLEMENTATION CONTRACT |
| `8384,8406p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;1. Confirmations formelles |
| `8407,8422p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;2. Boucle de travail obligatoire |
| `8423,8461p` | 39 | &nbsp;&nbsp;&nbsp;&nbsp;3. Interdictions absolues |
| `8462,8480p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;4. Obligations |
| `8481,8486p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5. Autonomie de décision |
| `8487,8500p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;6. Gestion de l'incertitude |
| `8501,8533p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;7. Critère final |
