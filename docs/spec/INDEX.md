# Index du cahier des charges AXION ENGINE

Genere par `tools/spec/spec_index.py`. Source : `cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md` (8345 lignes).

Le CDC est **gele (FROZEN)** et constitue la source de verite unique.
Il ne doit jamais etre lu en entier : lire la plage de lignes de la
section utile, par exemple

```bash
sed -n '3586,3965p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md
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
| `1456,2572p` | 1117 | PARTIE 5 : SPÉCIFICATIONS COMPOSANT PAR COMPOSANT |
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
| `1868,1890p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;5.20 C-27 : Definitions data-driven |
| `1891,1972p` | 82 | &nbsp;&nbsp;&nbsp;&nbsp;5.21 C-28 : Compilateur de déformation et de structure |
| `1973,1991p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.22 C-30 : Scene Graph |
| `1992,2016p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;5.23 C-31 : Physics World |
| `2017,2027p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;5.24 C-32 : Collider Builder |
| `2028,2033p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.25 C-33 : Vehicle System |
| `2034,2045p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;5.26 C-34 : Joints & Constraints |
| `2046,2051p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.27 C-35 : Damage Model |
| `2052,2057p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.28 C-36 : Particle Solver |
| `2058,2063p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.29 C-37 : Animation System |
| `2064,2082p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.30 C-38 : World Collision Provider |
| `2083,2092p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;5.31 C-39 : Spatial Queries |
| `2093,2122p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;5.32 C-40 : Simulation Scheduler |
| `2123,2178p` | 56 | &nbsp;&nbsp;&nbsp;&nbsp;5.33 C-41 : Impact Solver |
| `2179,2186p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.34 C-42 : Deformation Engine |
| `2187,2192p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.35 C-43 : Structural Integrity |
| `2193,2198p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.36 C-44 : Fracture & Detachment |
| `2199,2206p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.37 C-45 : Collider Refit |
| `2207,2212p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.38 C-46 : Repair System |
| `2213,2244p` | 32 | &nbsp;&nbsp;&nbsp;&nbsp;5.39 C-47 : Surface State & Wear |
| `2245,2267p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;5.40 C-48 : Attachment System |
| `2268,2280p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;5.41 C-50 : Axion Entity |
| `2281,2286p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.42 C-51 : Network Sync |
| `2287,2292p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.43 C-52 : Persistance |
| `2293,2310p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.44 C-53 : Interaction & Sièges |
| `2311,2318p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.45 C-54 : Bloc & BlockEntity 3D |
| `2319,2326p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.46 C-55 : Item 3D |
| `2327,2332p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.47 C-60 : Backend NATIVE_GL |
| `2333,2348p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;5.48 C-61 : Backend VANILLA_CONSUMER |
| `2349,2365p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;5.49 C-62 : GPU Resource Manager |
| `2366,2375p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;5.50 C-63 : Matériaux & Shaders |
| `2376,2394p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;5.51 C-64 : Culling & LOD |
| `2395,2411p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;5.52 C-65 : Instancing & Batching |
| `2412,2425p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;5.53 C-66 : Skinning GPU |
| `2426,2433p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.54 C-67 : Debug Renderer |
| `2434,2439p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.55 C-68 : Déformation GPU |
| `2440,2445p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.56 C-69 : Décalques & états de surface (rendu) |
| `2446,2449p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.57 C-70 : API publique |
| `2450,2472p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;5.58 C-71 : Commandes |
| `2473,2476p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.59 C-72 : Benchmark Harness |
| `2477,2484p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;5.60 C-73 : Overlay diagnostics |
| `2485,2502p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;5.61 C-74 : CLI `axion-cli` |
| `2503,2506p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.62 C-75 : Addon Blender |
| `2507,2510p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;5.63 C-76 : Bridge RUSTFORGE-X |
| `2511,2547p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;5.64 C-77 : Gouverneur de qualité |
| `2548,2553p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.65 C-80 : Ombres AXION |
| `2554,2559p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.66 C-81 : Sonde d'environnement & IBL synthétique |
| `2560,2565p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5.67 C-82 : Occlusion culling logiciel |
| `2566,2572p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;5.68 C-83 : Effets intégrés (tone mapping, SSR) |
| `2573,2683p` | 111 | PARTIE 6 : FORMATS 3D ET PIPELINE D'ASSETS |
| `2575,2586p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;6.1 Évaluation des formats |
| `2587,2596p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;6.2 Décisions figées |
| `2597,2636p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;6.3 Pipeline complet |
| `2637,2666p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;6.4 Options de compilation |
| `2667,2683p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;6.5 Contraintes d'auteur (documentées dans `ASSETS.md`) |
| `2684,2767p` | 84 | PARTIE 7 : FORMAT INTERNE A3D |
| `2686,2696p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.1 Objectifs |
| `2697,2722p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;7.2 Structure du fichier |
| `2723,2749p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;7.3 Sections normatives |
| `2750,2756p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;7.4 Versionnement et migration |
| `2757,2767p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;7.5 Sécurité de lecture |
| `2768,2908p` | 141 | PARTIE 8 : WORKFLOW BLENDER ET OUTILLAGE AUTEUR |
| `2770,2786p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;8.1 Flux nominal |
| `2787,2831p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;8.2 Convention par les `extras` glTF |
| `2832,2879p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;8.3 Addon Blender (C-75) |
| `2880,2908p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;8.4 Hiérarchie conventionnelle recommandée |
| `2909,2976p` | 68 | PARTIE 9 : SCENE GRAPH, NODES, SOCKETS, ÉTATS DE NODE |
| `2911,2928p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.1 Modèle |
| `2929,2946p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;9.2 États de node (SM-03) |
| `2947,2961p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;9.3 Sockets |
| `2962,2967p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;9.4 Visibilité, LOD et révélation |
| `2968,2976p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;9.5 Instanciation et partage |
| `2977,3087p` | 111 | PARTIE 10 : PHYSIQUE GÉNÉRIQUE |
| `2979,2982p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;10.1 Principe |
| `2983,2990p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;10.2 Types de corps |
| `2991,3003p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.3 Formes de collision |
| `3004,3014p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;10.4 Broad phase / narrow phase |
| `3015,3030p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;10.5 Intégration et solveur |
| `3031,3042p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;10.6 Forces environnementales |
| `3043,3064p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;10.7 Événements physiques |
| `3065,3077p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;10.8 Ce que la physique AXION ne fait pas |
| `3078,3087p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;10.9 Déterminisme |
| `3088,3237p` | 150 | PARTIE 11 : MATÉRIAUX PHYSIQUES ET MODÈLE DE MATIÈRE |
| `3090,3093p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;11.1 Rôle |
| `3094,3109p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;11.2 Groupes de propriétés |
| `3110,3165p` | 56 | &nbsp;&nbsp;&nbsp;&nbsp;11.3 Sémantique normative des paramètres de déformation |
| `3166,3184p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;11.4 Mappage bloc → matériau |
| `3185,3237p` | 53 | &nbsp;&nbsp;&nbsp;&nbsp;11.5 Cohérence dimensionnelle (référence normative) |
| `3238,3375p` | 138 | PARTIE 12 : VÉHICULES |
| `3240,3243p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;12.1 Positionnement |
| `3244,3269p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;12.2 Modèle de roue |
| `3270,3282p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;12.3 Suspension |
| `3283,3299p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;12.4 Modèle de pneu |
| `3300,3319p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;12.5 Groupe motopropulseur |
| `3320,3333p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;12.6 Direction et freinage |
| `3334,3343p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;12.7 Aérodynamique |
| `3344,3355p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;12.8 Entrées |
| `3356,3375p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;12.9 Tests véhicules |
| `3376,3586p` | 211 | PARTIE 13 : IMPACTS, ÉNERGIE ET MODÈLE DE DOMMAGE |
| `3378,3387p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;13.1 Principe |
| `3388,3416p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;13.2 Sources d'impact |
| `3417,3454p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.3 Distribution de l'énergie (C-35) |
| `3455,3513p` | 59 | &nbsp;&nbsp;&nbsp;&nbsp;13.3bis Modèle d'indentation plastique (référence normative) |
| `3514,3532p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;13.4 Effets fonctionnels |
| `3533,3537p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;13.5 Dommage continu |
| `3538,3548p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;13.6 Budgets et dégradation |
| `3549,3586p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;13.7 Tests |
| `3587,3966p` | 380 | PARTIE 14 : DÉFORMATION CONTINUE DE GÉOMÉTRIE |
| `3591,3608p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;14.1 Évaluation des approches |
| `3609,3635p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;14.2 Modèle mathématique |
| `3636,3651p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;14.3 Composantes du champ |
| `3652,3703p` | 52 | &nbsp;&nbsp;&nbsp;&nbsp;14.4 Application d'un impact (noyau déterministe) |
| `3704,3718p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;14.5 Niveaux de qualité de déformation |
| `3719,3766p` | 48 | &nbsp;&nbsp;&nbsp;&nbsp;14.6 Pipeline de rendu de la déformation (C-68) |
| `3767,3780p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;14.7 LOD de déformation |
| `3781,3797p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;14.8 Compléments déclaratifs |
| `3798,3840p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.9 Déformation et collision (C-45) |
| `3841,3847p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;14.10 Déchirure |
| `3848,3867p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;14.11 Budgets, mémoire et performance |
| `3868,3895p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;14.11bis Modèle de coût : portée exacte des garanties |
| `3896,3901p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;14.12 Réplication et persistance (résumé, détail en PARTIE 21 et 22) |
| `3902,3944p` | 43 | &nbsp;&nbsp;&nbsp;&nbsp;14.13 Tests |
| `3945,3966p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;14.14 Acceptance de la PARTIE 14 |
| `3967,4172p` | 206 | PARTIE 15 : INTÉGRITÉ STRUCTURELLE, RUPTURE, DÉTACHEMENT, DÉBRIS |
| `3969,3987p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;15.1 Modèle |
| `3988,4042p` | 55 | &nbsp;&nbsp;&nbsp;&nbsp;15.1bis Estimation de l'aire de liaison (référence normative) |
| `4043,4071p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;15.2 Propagation de l'énergie structurelle (C-43) |
| `4072,4111p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;15.3 Rupture et détachement (C-44) |
| `4112,4128p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;15.4 Débris |
| `4129,4134p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;15.5 Effets sur les mécanismes |
| `4135,4144p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;15.6 Budgets |
| `4145,4172p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;15.7 Tests |
| `4173,4254p` | 82 | PARTIE 16 : RÉPARATION, RESTAURATION ET REMPLACEMENT |
| `4175,4185p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;16.1 Niveaux de réparation |
| `4186,4200p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;16.2 Restauration du champ de déformation |
| `4201,4223p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;16.3 Règles data-driven |
| `4224,4254p` | 31 | &nbsp;&nbsp;&nbsp;&nbsp;16.4 Remplacement de part |
| `4255,4454p` | 200 | PARTIE 17 : SOLVEUR DE PARTICULES — TISSU, CORDES, CÂBLES, FILETS, CORPS SOUPLES |
| `4257,4260p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;17.1 Positionnement |
| `4261,4276p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;17.2 Modes d'autorité |
| `4277,4348p` | 72 | &nbsp;&nbsp;&nbsp;&nbsp;17.2bis Autorité et reconstruction visuelle (référence normative) |
| `4349,4371p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;17.3 Solveur |
| `4372,4383p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;17.4 Limites et budgets |
| `4384,4408p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;17.5 Définition |
| `4409,4414p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;17.6 Cordes et câbles physiques |
| `4415,4419p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;17.7 Interaction avec la déformation |
| `4420,4454p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;17.8 Tests |
| `4455,4586p` | 132 | PARTIE 18 : ANIMATION ET COUPLAGE ANIMATION / PHYSIQUE / DÉGÂTS |
| `4457,4469p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.1 Types d'animation |
| `4470,4482p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;18.2 Échantillonnage et blending |
| `4483,4501p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;18.3 Animation procédurale déclarative |
| `4502,4507p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;18.4 Palettes de skinning et ordre d'application |
| `4508,4557p` | 50 | &nbsp;&nbsp;&nbsp;&nbsp;18.5 Couplage animation / physique / dégâts (scénario normatif de la porte) |
| `4558,4561p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;18.6 Root motion |
| `4562,4586p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;18.7 Tests |
| `4587,4919p` | 333 | PARTIE 19 : RENDU |
| `4589,4609p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;19.1 Ce qui est réellement contrôlable depuis un mod Forge 1.20.1 |
| `4610,4631p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;19.2 Les deux backends |
| `4632,4675p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;19.2bis Portée de l'équivalence entre backends (référence normative) |
| `4676,4684p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.3 Gestion d'état OpenGL (règle critique) |
| `4685,4708p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;19.4 Format de vertex GPU et attributs d'instance |
| `4709,4757p` | 49 | &nbsp;&nbsp;&nbsp;&nbsp;19.5 Modèle d'éclairage : PBR avec environnement synthétisé |
| `4758,4766p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;19.6 Déformation dans le pipeline de rendu |
| `4767,4789p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;19.7 Occlusion culling logiciel (C-82) |
| `4790,4814p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;19.8 Ombres AXION (C-80) |
| `4815,4827p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;19.9 Effets intégrés (C-83) |
| `4828,4841p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;19.10 Passes de rendu |
| `4842,4846p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;19.11 Transparence |
| `4847,4865p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;19.12 Budgets de rendu et dégradation |
| `4866,4919p` | 54 | &nbsp;&nbsp;&nbsp;&nbsp;19.13 Tests de rendu |
| `4920,4988p` | 69 | PARTIE 20 : MATÉRIAUX DE RENDU, DÉCALQUES, USURE ET ÉTATS DE SURFACE |
| `4922,4941p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;20.1 États visuels |
| `4942,4960p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;20.2 Décalques (C-69) |
| `4961,4975p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;20.3 Profils d'usure |
| `4976,4988p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;20.4 Budgets |
| `4989,5171p` | 183 | PARTIE 21 : CLIENT / SERVEUR ET NETWORKING |
| `4991,5005p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;21.1 Modèle d'autorité |
| `5006,5015p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.2 Canal et protocole |
| `5016,5029p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;21.3 Handshake |
| `5030,5052p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;21.4 Paquets |
| `5053,5070p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;21.5 Snapshots, quantification, delta |
| `5071,5110p` | 40 | &nbsp;&nbsp;&nbsp;&nbsp;21.6 Réplication de la déformation (mécanisme central) |
| `5111,5120p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.7 Interpolation client |
| `5121,5127p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;21.8 Prédiction et réconciliation |
| `5128,5134p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;21.9 Sécurité réseau |
| `5135,5144p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;21.10 Budgets réseau |
| `5145,5171p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;21.11 Tests |
| `5172,5341p` | 170 | PARTIE 22 : PERSISTANCE |
| `5174,5199p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;22.1 Ce qui est persisté |
| `5200,5224p` | 25 | &nbsp;&nbsp;&nbsp;&nbsp;22.2 Format NBT |
| `5225,5237p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;22.3 Sérialisation du champ de déformation |
| `5238,5249p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;22.4 Chargement et déchargement de chunk |
| `5250,5311p` | 62 | &nbsp;&nbsp;&nbsp;&nbsp;22.5 Compatibilité de sauvegarde et retrait du mod |
| `5252,5260p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.1 Constat technique |
| `5261,5281p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.2 Journal latéral (side-car) |
| `5282,5311p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;22.5.3 Matrice de comportement (normative) |
| `5312,5341p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;22.6 Tests |
| `5342,5601p` | 260 | PARTIE 23 : API PUBLIQUE ET CONTENU DATA-DRIVEN |
| `5344,5352p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;23.1 Principes |
| `5353,5476p` | 124 | &nbsp;&nbsp;&nbsp;&nbsp;23.2 Surface de l'API |
| `5477,5496p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;23.3 Points d'extension |
| `5497,5586p` | 90 | &nbsp;&nbsp;&nbsp;&nbsp;23.4 Schéma de Definition (normatif) |
| `5587,5601p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;23.5 Contenu de démonstration |
| `5602,5668p` | 67 | PARTIE 24 : MODULARITÉ, NIVEAUX DE QUALITÉ ET CONFIGURATION |
| `5604,5630p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;24.1 Modules |
| `5631,5649p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;24.2 Niveaux de qualité |
| `5650,5655p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;24.3 Chargement à la demande |
| `5656,5668p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;24.4 Fichiers de configuration |
| `5669,5805p` | 137 | PARTIE 25 : PERFORMANCE — THREADING, BUDGETS, DÉGRADATION |
| `5671,5676p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;25.1 Position de principe |
| `5677,5703p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.2 Budgets par défaut |
| `5704,5730p` | 27 | &nbsp;&nbsp;&nbsp;&nbsp;25.3 Techniques retenues |
| `5731,5735p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.4 Double tampon et interpolation |
| `5736,5744p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;25.5 Détection de dépassement |
| `5745,5773p` | 29 | &nbsp;&nbsp;&nbsp;&nbsp;25.6 Machine de dégradation (SM-02) |
| `5774,5784p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;25.7 Modes SAFE et DISABLED |
| `5785,5789p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;25.8 Watchdog |
| `5790,5805p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;25.9 Tests |
| `5806,5875p` | 70 | PARTIE 26 : COMPATIBILITÉ GÉNÉRALE |
| `5808,5813p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;26.1 Principe |
| `5814,5825p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;26.2 Empreinte Mixin (liste fermée) |
| `5826,5841p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;26.3 Interactions avec le vanilla |
| `5842,5845p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;26.4 Limites documentées |
| `5846,5860p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.5 Mods tiers |
| `5861,5875p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;26.6 Tests |
| `5876,6147p` | 272 | PARTIE 27 : COMPATIBILITÉ RUSTFORGE-X |
| `5878,5907p` | 30 | &nbsp;&nbsp;&nbsp;&nbsp;27.0 Position fondamentale |
| `5908,5919p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;27.1 Détection |
| `5920,5934p` | 15 | &nbsp;&nbsp;&nbsp;&nbsp;27.2 Ce que la détection change (et ne change pas) |
| `5935,5999p` | 65 | &nbsp;&nbsp;&nbsp;&nbsp;27.3 Coexistence : analyse des points de contact |
| `5937,5947p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.1 Classloading |
| `5948,5957p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.2 Instrumentation et bytecode |
| `5958,5969p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.3 Threads et scheduler |
| `5970,5973p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.4 Tick et événements Forge |
| `5974,5986p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.5 Mémoire native et bibliothèques |
| `5987,5990p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.6 Rendu |
| `5991,5994p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.7 Réseau, monde, sauvegarde |
| `5995,5999p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.3.8 Cycle de vie et arrêt |
| `6000,6012p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;27.4 Partage du budget CPU |
| `6013,6045p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;27.5 Bridge d'interopérabilité optionnel (C-76) |
| `6046,6051p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;27.6 Indication de non-transformation |
| `6052,6072p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;27.7 Comment RUSTFORGE-X pourrait optimiser AXION (sans qu'AXION en dépende) |
| `6073,6086p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;27.8 Ce qu'AXION ne fera jamais |
| `6087,6096p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;27.9 Fallback |
| `6097,6147p` | 51 | &nbsp;&nbsp;&nbsp;&nbsp;27.10 Stratégie de test de coexistence |
| `6099,6117p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.1 Mod de simulation (obligatoire, toujours exécuté) |
| `6118,6121p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.2 Test avec l'artefact réel (obligatoire quand disponible) |
| `6122,6147p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;27.10.3 Matrice |
| `6148,6238p` | 91 | PARTIE 28 : SÉCURITÉ, STABILITÉ, CONFINEMENT |
| `6150,6162p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;28.1 Surface d'attaque |
| `6163,6168p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;28.2 Code `unsafe` en Rust |
| `6169,6196p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;28.3 Limites dures |
| `6197,6212p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;28.4 Confinement des pannes |
| `6213,6216p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.5 Vie privée |
| `6217,6220p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;28.6 Reproduction d'incident |
| `6221,6238p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;28.7 Tests |
| `6239,6417p` | 179 | PARTIE 29 : STRATÉGIE DE TEST |
| `6241,6248p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;29.1 Principes |
| `6249,6267p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;29.2 Pyramide |
| `6268,6303p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;29.3 Tests unitaires |
| `6304,6319p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;29.4 Tests d'intégration |
| `6320,6345p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;29.5 Tests de gameplay et scénarios déclaratifs |
| `6346,6379p` | 34 | &nbsp;&nbsp;&nbsp;&nbsp;29.6 Test de bout en bout obligatoire (T-970) |
| `6380,6403p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;29.7 Tests de stress et d'endurance |
| `6404,6417p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;29.8 Infrastructure |
| `6418,6496p` | 79 | PARTIE 30 : BENCHMARKS |
| `6420,6424p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;30.1 Position de principe |
| `6425,6459p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;30.2 Ce qui est mesuré |
| `6460,6477p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;30.3 Méthodologie normative |
| `6478,6488p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;30.4 Format de résultat |
| `6489,6496p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;30.5 Non-régression |
| `6497,6546p` | 50 | PARTIE 31 : PROFILING ET DEBUGGING |
| `6499,6511p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;31.1 Domaines profilés |
| `6512,6518p` | 7 | &nbsp;&nbsp;&nbsp;&nbsp;31.2 Outils |
| `6519,6524p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;31.3 Mode développeur |
| `6525,6538p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;31.4 Overlays de debug |
| `6539,6546p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;31.5 Reproduction |
| `6547,6619p` | 73 | PARTIE 32 : DÉPENDANCES ET LICENCES |
| `6549,6556p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;32.1 Licence du projet |
| `6557,6594p` | 38 | &nbsp;&nbsp;&nbsp;&nbsp;32.2 Dépendances Rust |
| `6595,6606p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;32.3 Dépendances Java |
| `6607,6619p` | 13 | &nbsp;&nbsp;&nbsp;&nbsp;32.4 Outillage et SBOM |
| `6620,6748p` | 129 | PARTIE 33 : ARBORESCENCE DU DÉPÔT |
| `6728,6748p` | 21 | &nbsp;&nbsp;&nbsp;&nbsp;33.2 Contenu de l'artefact final |
| `6749,6891p` | 143 | PARTIE 34 : BUILD, CI/CD, RELEASE, INSTALLATION |
| `6751,6786p` | 36 | &nbsp;&nbsp;&nbsp;&nbsp;34.1 Chaîne de build |
| `6787,6797p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;34.2 Plateformes |
| `6798,6841p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;34.3 CI/CD |
| `6842,6864p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;34.4 Versionnement et release |
| `6865,6874p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;34.5 Installation |
| `6875,6891p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;34.6 Test d'installation propre (obligatoire avant release) |
| `6892,7005p` | 114 | PARTIE 35 : DÉCISIONS D'ARCHITECTURE (ADR) |
| `6896,6898p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-001 — glTF 2.0 / GLB comme format d'échange principal |
| `6899,6901p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-002 — Rapier3D comme moteur physique |
| `6902,6904p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-003 — Modèle de véhicule par raycast |
| `6905,6907p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-004 — JNI + DirectByteBuffer pour la frontière Java/Rust |
| `6908,6910p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-005 — Autorité serveur, sans déterminisme physique inter-plateformes |
| `6911,6913p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-006 — Rendu piloté depuis Java sur le contexte GL de Minecraft |
| `6914,6925p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-007 — Deux backends de rendu maintenus, équivalence fonctionnelle et non pixellaire |
| `6926,6930p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-008 — PBR avec environnement synthétisé *(révisé en révision 2)* |
| `6931,6935p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-011 — Déformation continue par champ de lattice *(révisé en révision 2)* |
| `6936,6939p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-012 — Solveur de particules unifié à trois modes d'autorité *(révisé)* |
| `6940,6942p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-013 — Pas d'ECS tiers, structures SoA propres |
| `6943,6945p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-014 — Job system dédié au-dessus de rayon, jamais le pool global |
| `6946,6948p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-015 — Indépendance vis-à-vis de RUSTFORGE-X, compatibilité par conception |
| `6949,6951p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-016 — Empreinte Mixin fermée et minimale |
| `6952,6954p` | 3 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-017 — Persistance du seul état non dérivable |
| `6955,6966p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-018 — Réplication de la déformation par événements et reconstruction déterministe |
| `6967,6971p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-019 — Refit de collider par déformation des points d'enveloppe |
| `6972,6976p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-020 — Graphe structurel pour la rupture et le détachement |
| `6977,6981p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-021 — Carte d'ombre dédiée aux objets AXION |
| `6982,6986p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-022 — Occlusion culling logiciel plutôt que requêtes GPU |
| `6987,6991p` | 5 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-023 — Niveaux de qualité et gouverneur |
| `6992,6995p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-024 — Décalques en espace objet, suivant la déformation |
| `6996,6999p` | 4 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-025 — Système d'attache entre assemblies |
| `7000,7005p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;ADR-026 — Chemins accélérés GL 4.x optionnels sur une base GL 3.3 |
| `7006,7169p` | 164 | PARTIE 36 : PÉRIMÈTRE V1.0 / V1.x / HORS PÉRIMÈTRE |
| `7008,7095p` | 88 | &nbsp;&nbsp;&nbsp;&nbsp;36.1 V1.0 — obligatoire |
| `7096,7113p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;36.2 V1.x — extensions futures |
| `7114,7132p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;36.3 Hors périmètre — exclu de la V1 |
| `7133,7169p` | 37 | &nbsp;&nbsp;&nbsp;&nbsp;36.4 Audit des limitations de la révision 1 |
| `7170,7376p` | 207 | PARTIE 37 : JALONS ET DEFINITION OF DONE |
| `7172,7179p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;37.1 Règles |
| `7180,7319p` | 140 | &nbsp;&nbsp;&nbsp;&nbsp;37.2 Jalons |
| `7182,7191p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M0 — Squelette et frontière native |
| `7192,7202p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M1 — Assets, noyau déterministe, jobs |
| `7203,7212p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M2 — Scene graph, cache, optimizer, entité, API |
| `7213,7222p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M3 — Physique et premier rendu |
| `7223,7232p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M4 — Réseau, animation, culling/LOD, joints, persistance |
| `7233,7243p` | 11 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M5 — Véhicules, sièges, attaches, gouverneur, overlay |
| `7244,7263p` | 20 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M6 — Impacts et déformation continue *(jalon central)* |
| `7264,7273p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M7 — Structure, rupture, détachement, réparation |
| `7274,7282p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M8 — Particules unifiées |
| `7283,7294p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M9 — Rendu avancé |
| `7295,7302p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M10 — Blocs, items, bout en bout |
| `7303,7311p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M11 — Compatibilité, coexistence, durcissement |
| `7312,7319p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;M12 — Release 1.0.0 |
| `7320,7366p` | 47 | &nbsp;&nbsp;&nbsp;&nbsp;37.3 Definition of Done |
| `7367,7376p` | 10 | &nbsp;&nbsp;&nbsp;&nbsp;37.4 Sévérités |
| `7377,7774p` | 398 | PARTIE 38 : CRITÈRES D'ACCEPTATION ET AUDIT FINAL |
| `7379,7469p` | 91 | &nbsp;&nbsp;&nbsp;&nbsp;38.1 Checklist principale de la V1.0 |
| `7470,7493p` | 24 | &nbsp;&nbsp;&nbsp;&nbsp;38.2 Critères de correction (bloquants) — identifiants `AC-xx` |
| `7494,7510p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.3 Critères de performance — identifiants `PF-xx` |
| `7511,7527p` | 17 | &nbsp;&nbsp;&nbsp;&nbsp;38.4 Critères d'extensibilité — identifiants `EX-xx` |
| `7528,7572p` | 45 | &nbsp;&nbsp;&nbsp;&nbsp;38.5 Audit final obligatoire — résultats |
| `7573,7618p` | 46 | &nbsp;&nbsp;&nbsp;&nbsp;38.6 Contradictions détectées et résolutions |
| `7619,7653p` | 35 | &nbsp;&nbsp;&nbsp;&nbsp;38.7 Registre des changements de la révision 2 |
| `7654,7760p` | 107 | &nbsp;&nbsp;&nbsp;&nbsp;38.8 Registre des corrections de la révision 2.1 |
| `7659,7672p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Problèmes corrigés |
| `7673,7686p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Sections ajoutées |
| `7687,7695p` | 9 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Équations modifiées |
| `7696,7707p` | 12 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Exigences ajoutées ou modifiées |
| `7708,7715p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Invariants modifiés |
| `7716,7734p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Tests ajoutés |
| `7735,7742p` | 8 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;ADR modifiés |
| `7743,7760p` | 18 | &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Vérification de non-régression |
| `7761,7774p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;38.9 Registre des amendements postérieurs au gel |
| `7775,8193p` | 419 | ANNEXES |
| `7777,7820p` | 44 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.1 — Codes d'erreur |
| `7821,7848p` | 28 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.2 — Index des invariants |
| `7849,8062p` | 214 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.3 — Configuration complète (référence normative) |
| `8063,8088p` | 26 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.4 — Sources procédurales (liste fermée V1.0) |
| `8089,8111p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.5 — Limitations connues de la V1.0 |
| `8112,8133p` | 22 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.6 — Glossaire complémentaire |
| `8134,8193p` | 60 | &nbsp;&nbsp;&nbsp;&nbsp;ANNEXE A.7 — Capacités exigées hors du texte gelé (ADR-160) |
| `8194,8345p` | 152 | FINAL V1.0 IMPLEMENTATION CONTRACT |
| `8196,8218p` | 23 | &nbsp;&nbsp;&nbsp;&nbsp;1. Confirmations formelles |
| `8219,8234p` | 16 | &nbsp;&nbsp;&nbsp;&nbsp;2. Boucle de travail obligatoire |
| `8235,8273p` | 39 | &nbsp;&nbsp;&nbsp;&nbsp;3. Interdictions absolues |
| `8274,8292p` | 19 | &nbsp;&nbsp;&nbsp;&nbsp;4. Obligations |
| `8293,8298p` | 6 | &nbsp;&nbsp;&nbsp;&nbsp;5. Autonomie de décision |
| `8299,8312p` | 14 | &nbsp;&nbsp;&nbsp;&nbsp;6. Gestion de l'incertitude |
| `8313,8345p` | 33 | &nbsp;&nbsp;&nbsp;&nbsp;7. Critère final |
