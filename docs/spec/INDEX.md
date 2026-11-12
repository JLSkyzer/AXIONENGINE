# Index du cahier des charges AXION ENGINE

Genere par `tools/spec/spec_index.py`. Source : `docs/spec/AXIONENGINE_Cahier_des_Charges_v1.0.md` (8415 lignes).

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
| `679,1244p` | 566 | PARTIE 3 : MODÈLE DE DONNÉES CANONIQUE |
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
| `1153,1167p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;3.17 DM-17 : Attache entre assemblies |
| `1168,1205p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;3.18 DM-18 : Niveaux de qualité et budgets |
| `1206,1218p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;3.19 DM-19 : codes d'erreur |
| `1219,1244p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;3.20 Invariants globaux |
| `1245,1455p` | 211 | PARTIE 4 : FRONTIÈRE JAVA/RUST, FFI ET ABI |
| `1247,1273p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;4.1 Répartition des responsabilités |
| `1274,1291p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;4.2 ADR-004 : mécanisme d'interopérabilité |
| `1292,1310p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;4.3 IF-01 : ABI, versionnement, contrôle |
| `1311,1337p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;4.4 IF-02 : mémoire partagée et anneaux de transfert |
| `1338,1363p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;4.5 IF-03 : cycle de simulation |
| `1364,1388p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;4.6 IF-04 : déformation |
| `1389,1410p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;4.7 IF-05 : cycle de rendu |
| `1411,1429p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;4.8 IF-06 : assets, particules, structure |
| `1430,1436p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;4.9 IF-07 : erreurs et panics |
| `1437,1450p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;4.10 Propriété et durée de vie |
| `1451,1455p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;4.11 Calibration FFI |
| `1456,2578p` | 1123 | PARTIE 5 : SPÉCIFICATIONS COMPOSANT PAR COMPOSANT |
| `1460,1496p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;5.1 C-01 : Forge Integration |
| `1497,1515p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.2 C-02 : Bootstrap |
| `1516,1529p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;5.3 C-03 : Native Loader |
| `1530,1545p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;5.4 C-04 : Configuration |
| `1546,1554p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;5.5 C-05 : Diagnostics & Logging |
| `1555,1571p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;5.6 C-10 : Native Core |
| `1572,1581p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;5.7 C-11 : Math |
| `1582,1593p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;5.8 C-12 : Job System |
| `1594,1608p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;5.9 C-13 : Memory / Arenas |
| `1609,1616p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.10 C-14 : FFI Bridge |
| `1617,1624p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.11 C-15 : Telemetry natif |
| `1625,1726p` | 102 | &nbsp;&nbsp;&nbsp;&nbsp;5.12 C-16 : Noyau déterministe |
| `1651,1726p` | 76 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;5.12bis Portée de la garantie déterministe |
| `1727,1752p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;5.13 C-20 : Asset Orchestrator |
| `1753,1777p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;5.14 C-21 : Importers |
| `1778,1805p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;5.15 C-22 : Asset Validator |
| `1806,1829p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;5.16 C-23 : Asset Optimizer |
| `1830,1835p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.17 C-24 : Conteneur A3D |
| `1836,1849p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;5.18 C-25 : Asset Cache |
| `1850,1867p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.19 C-26 : Textures & Matériaux (client) |
| `1868,1894p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;5.20 C-27 : Definitions data-driven |
| `1895,1976p` | 82 | &nbsp;&nbsp;&nbsp;&nbsp;5.21 C-28 : Compilateur de déformation et de structure |
| `1977,1995p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.22 C-30 : Scene Graph |
| `1996,2020p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;5.23 C-31 : Physics World |
| `2021,2031p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;5.24 C-32 : Collider Builder |
| `2032,2037p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.25 C-33 : Vehicle System |
| `2038,2049p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;5.26 C-34 : Joints & Constraints |
| `2050,2055p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.27 C-35 : Damage Model |
| `2056,2061p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.28 C-36 : Particle Solver |
| `2062,2067p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.29 C-37 : Animation System |
| `2068,2086p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.30 C-38 : World Collision Provider |
| `2087,2096p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;5.31 C-39 : Spatial Queries |
| `2097,2126p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;5.32 C-40 : Simulation Scheduler |
| `2127,2182p` | 56 | &nbsp;&nbsp;&nbsp;&nbsp;5.33 C-41 : Impact Solver |
| `2183,2190p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.34 C-42 : Deformation Engine |
| `2191,2196p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.35 C-43 : Structural Integrity |
| `2197,2202p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.36 C-44 : Fracture & Detachment |
| `2203,2210p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.37 C-45 : Collider Refit |
| `2211,2216p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.38 C-46 : Repair System |
| `2217,2248p` | 32 | &nbsp;&nbsp;&nbsp;&nbsp;5.39 C-47 : Surface State & Wear |
| `2249,2271p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;5.40 C-48 : Attachment System |
| `2272,2284p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;5.41 C-50 : Axion Entity |
| `2285,2290p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.42 C-51 : Network Sync |
| `2291,2296p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.43 C-52 : Persistance |
| `2297,2314p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.44 C-53 : Interaction & Sièges |
| `2315,2322p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.45 C-54 : Bloc & BlockEntity 3D |
| `2323,2330p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.46 C-55 : Item 3D |
| `2331,2336p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.47 C-60 : Backend NATIVE_GL |
| `2337,2352p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;5.48 C-61 : Backend VANILLA_CONSUMER |
| `2353,2369p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;5.49 C-62 : GPU Resource Manager |
| `2370,2379p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;5.50 C-63 : Matériaux & Shaders |
| `2380,2398p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.51 C-64 : Culling & LOD |
| `2399,2415p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;5.52 C-65 : Instancing & Batching |
| `2416,2429p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;5.53 C-66 : Skinning GPU |
| `2430,2437p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.54 C-67 : Debug Renderer |
| `2438,2443p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.55 C-68 : Déformation GPU |
| `2444,2449p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.56 C-69 : Décalques & états de surface (rendu) |
| `2450,2453p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.57 C-70 : API publique |
| `2454,2478p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;5.58 C-71 : Commandes |
| `2479,2482p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.59 C-72 : Benchmark Harness |
| `2483,2490p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.60 C-73 : Overlay diagnostics |
| `2491,2508p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.61 C-74 : CLI `axion-cli` |
| `2509,2512p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.62 C-75 : Addon Blender |
| `2513,2516p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.63 C-76 : Bridge RUSTFORGE-X |
| `2517,2553p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;5.64 C-77 : Gouverneur de qualité |
| `2554,2559p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.65 C-80 : Ombres AXION |
| `2560,2565p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.66 C-81 : Sonde d'environnement & IBL synthétique |
| `2566,2571p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.67 C-82 : Occlusion culling logiciel |
| `2572,2578p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;5.68 C-83 : Effets intégrés (tone mapping, SSR) |
| `2579,2689p` | 111 | PARTIE 6 : FORMATS 3D ET PIPELINE D'ASSETS |
| `2581,2592p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;6.1 Évaluation des formats |
| `2593,2602p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;6.2 Décisions figées |
| `2603,2642p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;6.3 Pipeline complet |
| `2643,2672p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;6.4 Options de compilation |
| `2673,2689p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;6.5 Contraintes d'auteur (documentées dans `ASSETS.md`) |
| `2690,2773p` | 84 | PARTIE 7 : FORMAT INTERNE A3D |
| `2692,2702p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.1 Objectifs |
| `2703,2728p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;7.2 Structure du fichier |
| `2729,2755p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;7.3 Sections normatives |
| `2756,2762p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;7.4 Versionnement et migration |
| `2763,2773p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.5 Sécurité de lecture |
| `2774,2914p` | 141 | PARTIE 8 : WORKFLOW BLENDER ET OUTILLAGE AUTEUR |
| `2776,2792p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;8.1 Flux nominal |
| `2793,2837p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;8.2 Convention par les `extras` glTF |
| `2838,2885p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;8.3 Addon Blender (C-75) |
| `2886,2914p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;8.4 Hiérarchie conventionnelle recommandée |
| `2915,2982p` | 68 | PARTIE 9 : SCENE GRAPH, NODES, SOCKETS, ÉTATS DE NODE |
| `2917,2934p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.1 Modèle |
| `2935,2952p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.2 États de node (SM-03) |
| `2953,2967p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;9.3 Sockets |
| `2968,2973p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;9.4 Visibilité, LOD et révélation |
| `2974,2982p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;9.5 Instanciation et partage |
| `2983,3093p` | 111 | PARTIE 10 : PHYSIQUE GÉNÉRIQUE |
| `2985,2988p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;10.1 Principe |
| `2989,2996p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;10.2 Types de corps |
| `2997,3009p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.3 Formes de collision |
| `3010,3020p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;10.4 Broad phase / narrow phase |
| `3021,3036p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;10.5 Intégration et solveur |
| `3037,3048p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;10.6 Forces environnementales |
| `3049,3070p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;10.7 Événements physiques |
| `3071,3083p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.8 Ce que la physique AXION ne fait pas |
| `3084,3093p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;10.9 Déterminisme |
| `3094,3243p` | 150 | PARTIE 11 : MATÉRIAUX PHYSIQUES ET MODÈLE DE MATIÈRE |
| `3096,3099p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;11.1 Rôle |
| `3100,3115p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;11.2 Groupes de propriétés |
| `3116,3171p` | 56 | &nbsp;&nbsp;&nbsp;&nbsp;11.3 Sémantique normative des paramètres de déformation |
| `3172,3190p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;11.4 Mappage bloc → matériau |
| `3191,3243p` | 53 | &nbsp;&nbsp;&nbsp;&nbsp;11.5 Cohérence dimensionnelle (référence normative) |
| `3244,3381p` | 138 | PARTIE 12 : VÉHICULES |
| `3246,3249p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;12.1 Positionnement |
| `3250,3275p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;12.2 Modèle de roue |
| `3276,3288p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;12.3 Suspension |
| `3289,3305p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;12.4 Modèle de pneu |
| `3306,3325p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;12.5 Groupe motopropulseur |
| `3326,3339p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;12.6 Direction et freinage |
| `3340,3349p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;12.7 Aérodynamique |
| `3350,3361p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;12.8 Entrées |
| `3362,3381p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;12.9 Tests véhicules |
| `3382,3592p` | 211 | PARTIE 13 : IMPACTS, ÉNERGIE ET MODÈLE DE DOMMAGE |
| `3384,3393p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;13.1 Principe |
| `3394,3422p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;13.2 Sources d'impact |
| `3423,3460p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.3 Distribution de l'énergie (C-35) |
| `3461,3519p` | 59 | &nbsp;&nbsp;&nbsp;&nbsp;13.3bis Modèle d'indentation plastique (référence normative) |
| `3520,3538p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;13.4 Effets fonctionnels |
| `3539,3543p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;13.5 Dommage continu |
| `3544,3554p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;13.6 Budgets et dégradation |
| `3555,3592p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.7 Tests |
| `3593,3972p` | 380 | PARTIE 14 : DÉFORMATION CONTINUE DE GÉOMÉTRIE |
| `3597,3614p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;14.1 Évaluation des approches |
| `3615,3641p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;14.2 Modèle mathématique |
| `3642,3657p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;14.3 Composantes du champ |
| `3658,3709p` | 52 | &nbsp;&nbsp;&nbsp;&nbsp;14.4 Application d'un impact (noyau déterministe) |
| `3710,3724p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;14.5 Niveaux de qualité de déformation |
| `3725,3772p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;14.6 Pipeline de rendu de la déformation (C-68) |
| `3773,3786p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;14.7 LOD de déformation |
| `3787,3803p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;14.8 Compléments déclaratifs |
| `3804,3846p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.9 Déformation et collision (C-45) |
| `3847,3853p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;14.10 Déchirure |
| `3854,3873p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;14.11 Budgets, mémoire et performance |
| `3874,3901p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;14.11bis Modèle de coût : portée exacte des garanties |
| `3902,3907p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;14.12 Réplication et persistance (résumé, détail en PARTIE 21 et 22) |
| `3908,3950p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.13 Tests |
| `3951,3972p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;14.14 Acceptance de la PARTIE 14 |
| `3973,4178p` | 206 | PARTIE 15 : INTÉGRITÉ STRUCTURELLE, RUPTURE, DÉTACHEMENT, DÉBRIS |
| `3975,3993p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;15.1 Modèle |
| `3994,4048p` | 55 | &nbsp;&nbsp;&nbsp;&nbsp;15.1bis Estimation de l'aire de liaison (référence normative) |
| `4049,4077p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;15.2 Propagation de l'énergie structurelle (C-43) |
| `4078,4117p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;15.3 Rupture et détachement (C-44) |
| `4118,4134p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;15.4 Débris |
| `4135,4140p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;15.5 Effets sur les mécanismes |
| `4141,4150p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;15.6 Budgets |
| `4151,4178p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;15.7 Tests |
| `4179,4260p` | 82 | PARTIE 16 : RÉPARATION, RESTAURATION ET REMPLACEMENT |
| `4181,4191p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;16.1 Niveaux de réparation |
| `4192,4206p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;16.2 Restauration du champ de déformation |
| `4207,4229p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;16.3 Règles data-driven |
| `4230,4260p` | 31 | &nbsp;&nbsp;&nbsp;&nbsp;16.4 Remplacement de part |
| `4261,4460p` | 200 | PARTIE 17 : SOLVEUR DE PARTICULES — TISSU, CORDES, CÂBLES, FILETS, CORPS SOUPLES |
| `4263,4266p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;17.1 Positionnement |
| `4267,4282p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;17.2 Modes d'autorité |
| `4283,4354p` | 72 | &nbsp;&nbsp;&nbsp;&nbsp;17.2bis Autorité et reconstruction visuelle (référence normative) |
| `4355,4377p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;17.3 Solveur |
| `4378,4389p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;17.4 Limites et budgets |
| `4390,4414p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;17.5 Définition |
| `4415,4420p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;17.6 Cordes et câbles physiques |
| `4421,4425p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;17.7 Interaction avec la déformation |
| `4426,4460p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;17.8 Tests |
| `4461,4592p` | 132 | PARTIE 18 : ANIMATION ET COUPLAGE ANIMATION / PHYSIQUE / DÉGÂTS |
| `4463,4475p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.1 Types d'animation |
| `4476,4488p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.2 Échantillonnage et blending |
| `4489,4507p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;18.3 Animation procédurale déclarative |
| `4508,4513p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;18.4 Palettes de skinning et ordre d'application |
| `4514,4563p` | 50 | &nbsp;&nbsp;&nbsp;&nbsp;18.5 Couplage animation / physique / dégâts (scénario normatif de la porte) |
| `4564,4567p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;18.6 Root motion |
| `4568,4592p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;18.7 Tests |
| `4593,4925p` | 333 | PARTIE 19 : RENDU |
| `4595,4615p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;19.1 Ce qui est réellement contrôlable depuis un mod Forge 1.20.1 |
| `4616,4637p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;19.2 Les deux backends |
| `4638,4681p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;19.2bis Portée de l'équivalence entre backends (référence normative) |
| `4682,4690p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.3 Gestion d'état OpenGL (règle critique) |
| `4691,4714p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;19.4 Format de vertex GPU et attributs d'instance |
| `4715,4763p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;19.5 Modèle d'éclairage : PBR avec environnement synthétisé |
| `4764,4772p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.6 Déformation dans le pipeline de rendu |
| `4773,4795p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;19.7 Occlusion culling logiciel (C-82) |
| `4796,4820p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;19.8 Ombres AXION (C-80) |
| `4821,4833p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;19.9 Effets intégrés (C-83) |
| `4834,4847p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;19.10 Passes de rendu |
| `4848,4852p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;19.11 Transparence |
| `4853,4871p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;19.12 Budgets de rendu et dégradation |
| `4872,4925p` | 54 | &nbsp;&nbsp;&nbsp;&nbsp;19.13 Tests de rendu |
| `4926,4994p` | 69 | PARTIE 20 : MATÉRIAUX DE RENDU, DÉCALQUES, USURE ET ÉTATS DE SURFACE |
| `4928,4947p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;20.1 États visuels |
| `4948,4966p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;20.2 Décalques (C-69) |
| `4967,4981p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;20.3 Profils d'usure |
| `4982,4994p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;20.4 Budgets |
| `4995,5183p` | 189 | PARTIE 21 : CLIENT / SERVEUR ET NETWORKING |
| `4997,5011p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;21.1 Modèle d'autorité |
| `5012,5021p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.2 Canal et protocole |
| `5022,5041p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;21.3 Handshake |
| `5042,5064p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;21.4 Paquets |
| `5065,5082p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;21.5 Snapshots, quantification, delta |
| `5083,5122p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;21.6 Réplication de la déformation (mécanisme central) |
| `5123,5132p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.7 Interpolation client |
| `5133,5139p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;21.8 Prédiction et réconciliation |
| `5140,5146p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;21.9 Sécurité réseau |
| `5147,5156p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.10 Budgets réseau |
| `5157,5183p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;21.11 Tests |
| `5184,5354p` | 171 | PARTIE 22 : PERSISTANCE |
| `5186,5211p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;22.1 Ce qui est persisté |
| `5212,5237p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;22.2 Format NBT |
| `5238,5250p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;22.3 Sérialisation du champ de déformation |
| `5251,5262p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;22.4 Chargement et déchargement de chunk |
| `5263,5324p` | 62 | &nbsp;&nbsp;&nbsp;&nbsp;22.5 Compatibilité de sauvegarde et retrait du mod |
| `5265,5273p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.1 Constat technique |
| `5274,5294p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.2 Journal latéral (side-car) |
| `5295,5324p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.3 Matrice de comportement (normative) |
| `5325,5354p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;22.6 Tests |
| `5355,5623p` | 269 | PARTIE 23 : API PUBLIQUE ET CONTENU DATA-DRIVEN |
| `5357,5367p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;23.1 Principes |
| `5368,5495p` | 128 | &nbsp;&nbsp;&nbsp;&nbsp;23.2 Surface de l'API |
| `5496,5518p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;23.3 Points d'extension |
| `5519,5608p` | 90 | &nbsp;&nbsp;&nbsp;&nbsp;23.4 Schéma de Definition (normatif) |
| `5609,5623p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;23.5 Contenu de démonstration |
| `5624,5690p` | 67 | PARTIE 24 : MODULARITÉ, NIVEAUX DE QUALITÉ ET CONFIGURATION |
| `5626,5652p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;24.1 Modules |
| `5653,5671p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;24.2 Niveaux de qualité |
| `5672,5677p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;24.3 Chargement à la demande |
| `5678,5690p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;24.4 Fichiers de configuration |
| `5691,5827p` | 137 | PARTIE 25 : PERFORMANCE — THREADING, BUDGETS, DÉGRADATION |
| `5693,5698p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;25.1 Position de principe |
| `5699,5725p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.2 Budgets par défaut |
| `5726,5752p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.3 Techniques retenues |
| `5753,5757p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.4 Double tampon et interpolation |
| `5758,5766p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;25.5 Détection de dépassement |
| `5767,5795p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;25.6 Machine de dégradation (SM-02) |
| `5796,5806p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;25.7 Modes SAFE et DISABLED |
| `5807,5811p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.8 Watchdog |
| `5812,5827p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;25.9 Tests |
| `5828,5897p` | 70 | PARTIE 26 : COMPATIBILITÉ GÉNÉRALE |
| `5830,5835p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;26.1 Principe |
| `5836,5847p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;26.2 Empreinte Mixin (liste fermée) |
| `5848,5863p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;26.3 Interactions avec le vanilla |
| `5864,5867p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;26.4 Limites documentées |
| `5868,5882p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.5 Mods tiers |
| `5883,5897p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.6 Tests |
| `5898,6169p` | 272 | PARTIE 27 : COMPATIBILITÉ RUSTFORGE-X |
| `5900,5929p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;27.0 Position fondamentale |
| `5930,5941p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;27.1 Détection |
| `5942,5956p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;27.2 Ce que la détection change (et ne change pas) |
| `5957,6021p` | 65 | &nbsp;&nbsp;&nbsp;&nbsp;27.3 Coexistence : analyse des points de contact |
| `5959,5969p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.1 Classloading |
| `5970,5979p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.2 Instrumentation et bytecode |
| `5980,5991p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.3 Threads et scheduler |
| `5992,5995p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.4 Tick et événements Forge |
| `5996,6008p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.5 Mémoire native et bibliothèques |
| `6009,6012p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.6 Rendu |
| `6013,6016p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.7 Réseau, monde, sauvegarde |
| `6017,6021p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.8 Cycle de vie et arrêt |
| `6022,6034p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;27.4 Partage du budget CPU |
| `6035,6067p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;27.5 Bridge d'interopérabilité optionnel (C-76) |
| `6068,6073p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;27.6 Indication de non-transformation |
| `6074,6094p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;27.7 Comment RUSTFORGE-X pourrait optimiser AXION (sans qu'AXION en dépende) |
| `6095,6108p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;27.8 Ce qu'AXION ne fera jamais |
| `6109,6118p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;27.9 Fallback |
| `6119,6169p` | 51 | &nbsp;&nbsp;&nbsp;&nbsp;27.10 Stratégie de test de coexistence |
| `6121,6139p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.1 Mod de simulation (obligatoire, toujours exécuté) |
| `6140,6143p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.2 Test avec l'artefact réel (obligatoire quand disponible) |
| `6144,6169p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.3 Matrice |
| `6170,6260p` | 91 | PARTIE 28 : SÉCURITÉ, STABILITÉ, CONFINEMENT |
| `6172,6184p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;28.1 Surface d'attaque |
| `6185,6190p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;28.2 Code `unsafe` en Rust |
| `6191,6218p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;28.3 Limites dures |
| `6219,6234p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;28.4 Confinement des pannes |
| `6235,6238p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.5 Vie privée |
| `6239,6242p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.6 Reproduction d'incident |
| `6243,6260p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;28.7 Tests |
| `6261,6439p` | 179 | PARTIE 29 : STRATÉGIE DE TEST |
| `6263,6270p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;29.1 Principes |
| `6271,6289p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;29.2 Pyramide |
| `6290,6325p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;29.3 Tests unitaires |
| `6326,6341p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;29.4 Tests d'intégration |
| `6342,6367p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;29.5 Tests de gameplay et scénarios déclaratifs |
| `6368,6401p` | 34 | &nbsp;&nbsp;&nbsp;&nbsp;29.6 Test de bout en bout obligatoire (T-970) |
| `6402,6425p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;29.7 Tests de stress et d'endurance |
| `6426,6439p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;29.8 Infrastructure |
| `6440,6518p` | 79 | PARTIE 30 : BENCHMARKS |
| `6442,6446p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;30.1 Position de principe |
| `6447,6481p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;30.2 Ce qui est mesuré |
| `6482,6499p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;30.3 Méthodologie normative |
| `6500,6510p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;30.4 Format de résultat |
| `6511,6518p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;30.5 Non-régression |
| `6519,6568p` | 50 | PARTIE 31 : PROFILING ET DEBUGGING |
| `6521,6533p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;31.1 Domaines profilés |
| `6534,6540p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;31.2 Outils |
| `6541,6546p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;31.3 Mode développeur |
| `6547,6560p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;31.4 Overlays de debug |
| `6561,6568p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;31.5 Reproduction |
| `6569,6641p` | 73 | PARTIE 32 : DÉPENDANCES ET LICENCES |
| `6571,6578p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;32.1 Licence du projet |
| `6579,6616p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;32.2 Dépendances Rust |
| `6617,6628p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;32.3 Dépendances Java |
| `6629,6641p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;32.4 Outillage et SBOM |
| `6642,6774p` | 133 | PARTIE 33 : ARBORESCENCE DU DÉPÔT |
| `6754,6774p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;33.2 Contenu de l'artefact final |
| `6775,6917p` | 143 | PARTIE 34 : BUILD, CI/CD, RELEASE, INSTALLATION |
| `6777,6812p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;34.1 Chaîne de build |
| `6813,6823p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;34.2 Plateformes |
| `6824,6867p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;34.3 CI/CD |
| `6868,6890p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;34.4 Versionnement et release |
| `6891,6900p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;34.5 Installation |
| `6901,6917p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;34.6 Test d'installation propre (obligatoire avant release) |
| `6918,7031p` | 114 | PARTIE 35 : DÉCISIONS D'ARCHITECTURE (ADR) |
| `6922,6924p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-001 — glTF 2.0 / GLB comme format d'échange principal |
| `6925,6927p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-002 — Rapier3D comme moteur physique |
| `6928,6930p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-003 — Modèle de véhicule par raycast |
| `6931,6933p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-004 — JNI + DirectByteBuffer pour la frontière Java/Rust |
| `6934,6936p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-005 — Autorité serveur, sans déterminisme physique inter-plateformes |
| `6937,6939p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-006 — Rendu piloté depuis Java sur le contexte GL de Minecraft |
| `6940,6951p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-007 — Deux backends de rendu maintenus, équivalence fonctionnelle et non pixellaire |
| `6952,6956p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-008 — PBR avec environnement synthétisé *(révisé en révision 2)* |
| `6957,6961p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-011 — Déformation continue par champ de lattice *(révisé en révision 2)* |
| `6962,6965p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-012 — Solveur de particules unifié à trois modes d'autorité *(révisé)* |
| `6966,6968p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-013 — Pas d'ECS tiers, structures SoA propres |
| `6969,6971p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-014 — Job system dédié au-dessus de rayon, jamais le pool global |
| `6972,6974p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-015 — Indépendance vis-à-vis de RUSTFORGE-X, compatibilité par conception |
| `6975,6977p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-016 — Empreinte Mixin fermée et minimale |
| `6978,6980p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-017 — Persistance du seul état non dérivable |
| `6981,6992p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-018 — Réplication de la déformation par événements et reconstruction déterministe |
| `6993,6997p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-019 — Refit de collider par déformation des points d'enveloppe |
| `6998,7002p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-020 — Graphe structurel pour la rupture et le détachement |
| `7003,7007p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-021 — Carte d'ombre dédiée aux objets AXION |
| `7008,7012p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-022 — Occlusion culling logiciel plutôt que requêtes GPU |
| `7013,7017p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-023 — Niveaux de qualité et gouverneur |
| `7018,7021p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-024 — Décalques en espace objet, suivant la déformation |
| `7022,7025p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-025 — Système d'attache entre assemblies |
| `7026,7031p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-026 — Chemins accélérés GL 4.x optionnels sur une base GL 3.3 |
| `7032,7195p` | 164 | PARTIE 36 : PÉRIMÈTRE V1.0 / V1.x / HORS PÉRIMÈTRE |
| `7034,7121p` | 88 | &nbsp;&nbsp;&nbsp;&nbsp;36.1 V1.0 — obligatoire |
| `7122,7139p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;36.2 V1.x — extensions futures |
| `7140,7158p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;36.3 Hors périmètre — exclu de la V1 |
| `7159,7195p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;36.4 Audit des limitations de la révision 1 |
| `7196,7412p` | 217 | PARTIE 37 : JALONS ET DEFINITION OF DONE |
| `7198,7205p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;37.1 Règles |
| `7206,7355p` | 150 | &nbsp;&nbsp;&nbsp;&nbsp;37.2 Jalons |
| `7208,7217p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M0 — Squelette et frontière native |
| `7218,7228p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M1 — Assets, noyau déterministe, jobs |
| `7229,7238p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M2 — Scene graph, cache, optimizer, entité, API |
| `7239,7248p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M3 — Physique et premier rendu |
| `7249,7258p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M4 — Réseau, animation, culling/LOD, joints, persistance |
| `7259,7270p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M5 — Véhicules, sièges, attaches, gouverneur, overlay |
| `7271,7279p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M5b — Véhicules hors roues (amendement A5) |
| `7280,7299p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M6 — Impacts et déformation continue *(jalon central)* |
| `7300,7309p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M7 — Structure, rupture, détachement, réparation |
| `7310,7318p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M8 — Particules unifiées |
| `7319,7330p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M9 — Rendu avancé |
| `7331,7338p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M10 — Blocs, items, bout en bout |
| `7339,7347p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M11 — Compatibilité, coexistence, durcissement |
| `7348,7355p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M12 — Release 1.0.0 |
| `7356,7402p` | 47 | &nbsp;&nbsp;&nbsp;&nbsp;37.3 Definition of Done |
| `7403,7412p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;37.4 Sévérités |
| `7413,7824p` | 412 | PARTIE 38 : CRITÈRES D'ACCEPTATION ET AUDIT FINAL |
| `7415,7505p` | 91 | &nbsp;&nbsp;&nbsp;&nbsp;38.1 Checklist principale de la V1.0 |
| `7506,7529p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;38.2 Critères de correction (bloquants) — identifiants `AC-xx` |
| `7530,7546p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.3 Critères de performance — identifiants `PF-xx` |
| `7547,7563p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.4 Critères d'extensibilité — identifiants `EX-xx` |
| `7564,7608p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;38.5 Audit final obligatoire — résultats |
| `7609,7654p` | 46 | &nbsp;&nbsp;&nbsp;&nbsp;38.6 Contradictions détectées et résolutions |
| `7655,7689p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;38.7 Registre des changements de la révision 2 |
| `7690,7796p` | 107 | &nbsp;&nbsp;&nbsp;&nbsp;38.8 Registre des corrections de la révision 2.1 |
| `7695,7708p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Problèmes corrigés |
| `7709,7722p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Sections ajoutées |
| `7723,7731p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Équations modifiées |
| `7732,7743p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Exigences ajoutées ou modifiées |
| `7744,7751p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Invariants modifiés |
| `7752,7770p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Tests ajoutés |
| `7771,7778p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;ADR modifiés |
| `7779,7796p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Vérification de non-régression |
| `7797,7824p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;38.9 Registre des amendements postérieurs au gel |
| `7825,8263p` | 439 | ANNEXES |
| `7827,7871p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.1 — Codes d'erreur |
| `7872,7899p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.2 — Index des invariants |
| `7900,8116p` | 217 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.3 — Configuration complète (référence normative) |
| `8117,8146p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.4 — Sources procédurales (liste fermée V1.1, amendement A9) |
| `8147,8169p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.5 — Limitations connues de la V1.0 |
| `8170,8191p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.6 — Glossaire complémentaire |
| `8192,8263p` | 72 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.7 — Capacités exigées hors du texte gelé (ADR-160) |
| `8264,8415p` | 152 | FINAL V1.0 IMPLEMENTATION CONTRACT |
| `8266,8288p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;1. Confirmations formelles |
| `8289,8304p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;2. Boucle de travail obligatoire |
| `8305,8343p` | 39 | &nbsp;&nbsp;&nbsp;&nbsp;3. Interdictions absolues |
| `8344,8362p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;4. Obligations |
| `8363,8368p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5. Autonomie de décision |
| `8369,8382p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;6. Gestion de l'incertitude |
| `8383,8415p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;7. Critère final |
