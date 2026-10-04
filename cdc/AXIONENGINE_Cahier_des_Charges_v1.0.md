# AXION ENGINE

## Cahier des charges V1.0 : framework 3D, physique, déformation, animation et simulation pour Minecraft Forge

```text
Nom du projet          : AXION ENGINE
Identifiant de mod     : axion
Version                : 1.0.0
Révision du document   : 2.1 (audit architectural complet + corrections
                         dimensionnelles et de portée ; périmètre inchangé)
Statut                 : FINAL / FROZEN
Cible                  : Minecraft 1.20.1 + Forge 47.x
Java                   : 17
Architecture           : Java + Rust
Relation avec RUSTFORGE-X : Projet indépendant, compatible optionnellement
Licence                : Apache-2.0
Public visé            : agent de développement autonome + relecteurs humains
```

> **Note de révision 2.1.** Correction ciblée, sans aucune réduction de périmètre :
> unités et formules physiques rendues dimensionnellement cohérentes (capacité de
> rupture, aire de liaison, aire de contact d'impact), portée exacte du « coût nul »
> de la déformation, portée exacte de la garantie déterministe, autorité en présence
> d'une simulation visuelle client plus fine, comportement et récupérabilité des
> données après retrait du mod, et nature de l'équivalence entre backends de rendu.
> Le registre complet figure en **38.8**. Aucun composant, aucune fonctionnalité et
> aucune ambition n'ont été retirés ni reportés.
>
> **Note de révision 2.** Cette révision ne réduit aucune fonctionnalité de la révision 1. Elle en corrige les décisions qui s'éloignaient de la vision fonctionnelle du moteur, et elle ajoute les systèmes manquants : déformation continue de géométrie, solveur d'impacts énergétique, intégrité structurelle, rupture et détachement pilotés par la physique, réparation multi-niveaux, usure de surface et décalques, rendu PBR avec environnement synthétisé, ombres propres, occlusion culling logiciel, solveur de particules unifié (tissu, cordes, câbles, filets, corps souples) avec autorité serveur optionnelle, système d'attache entre assemblies, gouverneur de qualité, et noyau déterministe partagé client/serveur. Le registre complet des changements et l'audit des limitations levées figurent en **PARTIE 38.7** et **PARTIE 36.4**.

---

# TABLE DES MATIÈRES

```text
PARTIE 0    Préambule, conventions, hypothèses
PARTIE 1    Vision, objectifs, principes directeurs
PARTIE 2    Architecture globale et inventaire des composants
PARTIE 3    Modèle de données canonique
PARTIE 4    Frontière Java/Rust, FFI et ABI
PARTIE 5    Spécifications composant par composant
PARTIE 6    Formats 3D et pipeline d'assets
PARTIE 7    Format interne A3D
PARTIE 8    Workflow Blender et outillage auteur
PARTIE 9    Scene graph, nodes, sockets, états de node
PARTIE 10   Physique générique
PARTIE 11   Matériaux physiques et modèle de matière
PARTIE 12   Véhicules
PARTIE 13   Impacts, énergie et modèle de dommage
PARTIE 14   Déformation continue de géométrie
PARTIE 15   Intégrité structurelle, rupture, détachement, débris
PARTIE 16   Réparation, restauration et remplacement
PARTIE 17   Solveur de particules : tissu, cordes, câbles, filets, corps souples
PARTIE 18   Animation et couplage animation / physique / dégâts
PARTIE 19   Rendu
PARTIE 20   Matériaux de rendu, décalques, usure et états de surface
PARTIE 21   Client / serveur et networking
PARTIE 22   Persistance
PARTIE 23   API publique et contenu data-driven
PARTIE 24   Modularité, niveaux de qualité et configuration
PARTIE 25   Performance : threading, budgets, dégradation
PARTIE 26   Compatibilité générale (Forge, vanilla, mods tiers)
PARTIE 27   Compatibilité RUSTFORGE-X
PARTIE 28   Sécurité, stabilité, confinement
PARTIE 29   Stratégie de test
PARTIE 30   Benchmarks
PARTIE 31   Profiling et debugging
PARTIE 32   Dépendances et licences
PARTIE 33   Arborescence du dépôt
PARTIE 34   Build, CI/CD, release, installation
PARTIE 35   Décisions d'architecture (ADR)
PARTIE 36   Périmètre V1.0 / V1.x / hors périmètre
PARTIE 37   Jalons et Definition of Done
PARTIE 38   Critères d'acceptation et audit final
ANNEXES     Codes d'erreur, invariants, configuration, sources, glossaire
FINAL V1.0 IMPLEMENTATION CONTRACT
```

---

# PARTIE 0 : PRÉAMBULE, CONVENTIONS, HYPOTHÈSES

## 0.1 Nature du document

Ce document est une **spécification d'implémentation gelée**. Il n'est ni une roadmap, ni une étude de faisabilité, ni un manifeste.

Il doit permettre à un agent de développement autonome de construire AXION ENGINE depuis un dépôt vide jusqu'à un artefact `axion-<version>-mc1.20.1-forge47.jar` compilable, testable, installable, jouable et publiable, sans avoir à redéfinir l'architecture, le périmètre ou les contrats fondamentaux.

Lorsque le document ne peut pas fixer une implémentation exacte (parce qu'elle dépend du code final, du matériel ou de contenus inconnus), il fournit systématiquement :

```text
1. le contrat
2. les invariants
3. les contraintes et limites dures
4. une implémentation de référence
5. les budgets et le comportement en surcharge
6. le fallback
7. les tests qui valident cette implémentation
```

## 0.2 Conventions de langage normatif

| Terme | Signification |
|---|---|
| DOIT / OBLIGATOIRE | exigence stricte, non négociable, couverte par la suite d'acceptation |
| NE DOIT PAS | interdiction stricte |
| DEVRAIT | recommandation forte ; dérogation autorisée si justifiée par un ADR daté |
| PEUT | option laissée à l'agent d'implémentation |
| N/A | non applicable au périmètre courant |

## 0.3 Niveaux de maturité

| Niveau | Signification | Activé par défaut | Doit compiler | Doit avoir un fallback |
|---|---|---|---|---|
| `STABLE` | spécifié, implémenté, testé, benchmarké, documenté | oui | oui | oui |
| `EXPERIMENTAL` | **réellement implémenté**, partiellement validé, garanties limitées | non | oui | oui |
| `FUTURE` | interfaces, modèle de données et tests de contrat présents ; implémentation absente et refusant l'activation | non | oui | sans objet |

- R-001 : une fonctionnalité `STABLE` ne contient aucun `TODO`, `FIXME`, `todo!()`, `unimplemented!()`, stub, placeholder, faux benchmark ni implémentation factice. Vérification mécanique en CI (job `lint-no-fiction`).
- R-002 : une fonctionnalité N'EST PAS classée `FUTURE` au motif qu'elle est difficile. Elle n'est classée `FUTURE` que si son implémentation est **impossible** dans le contexte Minecraft/Forge 1.20.1, ou si elle est **objectivement dangereuse** pour la stabilité. Toute classification `FUTURE` porte sa justification technique explicite (PARTIE 36).
- R-003 : `EXPERIMENTAL` implique du code réel, testé, avec budget et fallback. Une fonctionnalité `EXPERIMENTAL` non implémentée est un défaut bloquant.

## 0.4 Identifiants normatifs

```text
R-xxx     exigence (requirement)
C-xx      composant
IF-xx     interface / contrat
DM-xx     modèle de données
SM-xx     machine à états
INV-xx    invariant
FM-xx     mode de défaillance
RISK-xx   risque
T-xxx     test
S-xx      test de stress
B-xx      benchmark
ADR-xxx   décision d'architecture
M-x       jalon
E-xxxx    code d'erreur runtime
P-xx      principe directeur
H-xx      hypothèse
Q-xx      niveau de qualité
AC-xx     critère de correction (PARTIE 38)
PF-xx     critère de performance (PARTIE 38)
EX-xx     critère d'extensibilité (PARTIE 38)
```

Toute implémentation DOIT citer les identifiants concernés dans l'en-tête de module et dans les messages de commit :

```text
feat(C-42): champ de déformation plastique quantifié [R-1420, T-802]
```

## 0.5 Hypothèses explicites

| ID | Hypothèse | Impact si fausse | Repli obligatoire |
|---|---|---|---|
| H-01 | Minecraft 1.20.1 + Forge 47.x tourne sur Java 17 (LTS) | pas de Panama/FFM stable | JNI + `DirectByteBuffer` uniquement (ADR-004) |
| H-02 | Le pilote OpenGL expose au minimum GL 3.2 core (exigence Minecraft) | le client ne démarre pas de toute façon | aucun, hors périmètre |
| H-03 | GL 3.3 ou `ARB_instanced_arrays` + `ARB_draw_instanced` disponibles | pas d'instancing GPU | un draw call par objet, batching CPU (C-65) |
| H-04 | `ARB_texture_buffer_object` (GL 3.1 core) disponible | pas de TBO pour palettes et lattices | repli UBO avec pagination (C-66, C-68) |
| H-05 | GL 4.3 (`ARB_compute_shader`, `ARB_multi_draw_indirect`) disponible sur une partie du parc | chemins accélérés indisponibles | chemins GL 3.3 équivalents, obligatoires et testés |
| H-06 | Le thread serveur et le thread de rendu client sont identifiables | affinité non déterminable | travail synchrone sur le thread appelant, mode `SAFE` |
| H-07 | Le coût d'un appel JNI simple est de l'ordre de 20 à 100 ns | modèle de coût FFI faux | recalibrage mesuré au démarrage, agrandissement des lots |
| H-08 | La géométrie de collision du monde est extractible via `Level.getBlockCollisions` | pas de collision monde fiable | heightfield reconstruite par échantillonnage (C-38 mode `SAMPLED`) |
| H-09 | Les mods de shaders (Iris, Oculus) prennent le contrôle du pipeline | le backend natif casse le rendu | bascule automatique sur `VANILLA_CONSUMER` (C-61) |
| H-10 | Le framebuffer principal de Minecraft est LDR (RGBA8) en 1.20.1 | pas de HDR de sortie | tone mapping local en shader, pas de pipeline HDR (ADR-008) |
| H-11 | Les textures de couleur et de profondeur du `RenderTarget` principal sont accessibles en lecture | pas de SSR ni de contact shadows écran | contact shadows géométriques et probe seule |
| H-12 | La persistance d'entité (NBT) reste accessible | pas de persistance d'entité | fichiers par dimension dans `<gameDir>/axion/world/<worldId>/` |
| H-13 | Un mod peut charger une bibliothèque native via `System.load` (chemin absolu) | pas de partie Rust | mode `DISABLED`, jeu jouable |
| H-14 | Les opérations IEEE-754 `+ - * / sqrt` en `f32` sont bit-exactes sur les configurations de la **matrice de validation déterministe** (5.12bis), et le compilateur n'y introduit ni contraction FMA ni réassociation | le noyau de déformation diverge entre client et serveur | détection par empreinte, bascule automatique en mode de réplication `SNAPSHOT` pour les assemblies concernées (5.12bis, PARTIE 21.6) |

Toute hypothèse ajoutée pendant l'implémentation DOIT être inscrite ici via un ADR.

## 0.6 Terminologie

| Terme | Définition normative |
|---|---|
| Asset | ressource 3D compilée, immuable, identifiée par un `AssetId` |
| A3D | format de conteneur binaire interne d'AXION (PARTIE 7) |
| Node | nœud du scene graph : transform local, parent, contenus optionnels |
| Socket | point d'ancrage nommé, exposé à l'API et au gameplay |
| Body | corps rigide simulé par le moteur physique |
| Assembly | instance runtime : asset + scene graph + bodies + états + déformation |
| Part | pièce : sous-arbre de nodes portant santé, intégrité et état de dommage |
| Deformation Region | volume paramétré (lattice) déformant la géométrie qu'il englobe |
| Deformation Field | champ de déplacement discret stocké aux nœuds d'un lattice |
| Élastique | déplacement temporaire qui revient vers zéro |
| Plastique | déplacement permanent, persisté |
| Structural Link | liaison entre deux parts, porteuse d'une intégrité et d'un seuil de rupture |
| Impact | événement physique enrichi : point, normale, énergie, masse effective, aire |
| Wear | état de surface cumulatif (rayures, saleté, brûlure, oxydation) |
| Décalque | projection en espace objet d'une texture d'altération de surface |
| Assembly attachment | liaison logique et physique entre deux assemblies (remorquage, montage) |
| Niveau de qualité | `Q-0` à `Q-4`, gouvernant le coût autorisé d'un sous-système |
| Gouverneur de qualité | composant qui choisit et ajuste les niveaux depuis les budgets mesurés |
| Noyau déterministe | ensemble d'opérations garanties bit-identiques entre client et serveur **sur les configurations de la matrice de validation déterministe** (5.12bis) |

## 0.7 Portée et non-portée

Dans la portée V1.0 :

```text
- mod Forge 1.20.1 chargeable, client et serveur dédié
- bibliothèque native Rust chargée par la JVM
- pipeline d'assets 3D (glTF 2.0/GLB, OBJ, STL) vers format interne A3D
- scene graph hiérarchique, sockets, LOD, états de node
- moteur physique générique : rigid bodies, colliders, joints, requêtes spatiales
- matériaux physiques avec propriétés mécaniques complètes
- véhicules à roues génériques (moteur, transmission, différentiel, direction,
  freinage, aérodynamique, surfaces portantes)
- solveur d'impacts énergétique
- DÉFORMATION CONTINUE DE GÉOMÉTRIE : élastique, plastique, structurelle,
  avec 5 niveaux de qualité, sur GPU et CPU
- intégrité structurelle, rupture, détachement, débris
- réparation et restauration multi-niveaux
- usure de surface, décalques, matériaux de dommage
- animation squelettique, de nodes, procédurale, et couplage complet avec
  la physique, la déformation et les dégâts
- solveur de particules unifié : tissu, cordes, câbles, filets, corps souples,
  avec trois modes d'autorité
- système d'attache entre assemblies
- rendu PBR avec environnement synthétisé, ombres propres, décalques,
  parallax, occlusion culling logiciel, instancing, batching, skinning et
  déformation GPU, deux backends
- synchronisation client/serveur autoritative serveur, incluant la déformation
- persistance compacte de tout état non dérivable
- API publique versionnée et contenu 100 % data-driven
- gouverneur de qualité, budgets, dégradation progressive
- outillage : commandes, debug renderer, benchmarks, CLI, addon Blender
- coexistence testée avec RUSTFORGE-X
```

Hors portée V1.0 (liste exhaustive et motivée en PARTIE 36.3) :

```text
- support Fabric / NeoForge / autres versions de Minecraft
- import FBX et Collada natif
- simulation de fluides
- pipeline HDR de sortie et post-processing plein écran
- global illumination, ray tracing
- remplacement du renderer de terrain ou de l'éclairage vanilla
- redistribution de Minecraft, Forge ou d'assets tiers
```

---

# PARTIE 1 : VISION, OBJECTIFS, PRINCIPES DIRECTEURS

## 1.1 Résumé exécutif

AXION ENGINE est un **moteur 3D, physique, de déformation et de simulation générique embarqué dans Minecraft**, exposé sous forme de framework.

Il n'est pas un mod de contenu. Il fournit **l'infrastructure** permettant à d'autres — y compris à des créateurs sans compétence Java — de définir des objets 3D animés, physiquement simulés et **réellement déformables**, très au-delà de ce que permettent les systèmes vanilla.

```text
                        Minecraft 1.20.1 + Forge 47.x
                                     |
                            +--------+--------+
                            |   AXION ENGINE  |
                            +--------+--------+
                                     |
  +-----------+-----------+----------+----------+-----------+-----------+
  |           |           |          |          |           |           |
Assets    Scene graph  Physique   Impacts   Déformation  Animation    Rendu
GLB/OBJ   nodes /      rigid      énergie   champs /     squelette   PBR /
/STL/A3D  sockets      bodies     & dommage lattices /   / procédural ombres /
                       joints               structure                décalques
  |           |           |          |          |           |           |
  +-----------+-----------+----------+----------+-----------+-----------+
                                     |
                    Particules (tissu, cordes, câbles, filets, soft)
                                     |
                        API publique + définitions JSON
                                     |
   véhicules, machines, mobs, créatures, meubles, items 3D, blocs complexes,
   vêtements, structures, objets destructibles, équipements, ...
```

## 1.2 Ce qu'AXION est et n'est pas

| AXION EST | AXION N'EST PAS |
|---|---|
| un framework générique de simulation 3D et de déformation | un mod de voitures |
| un moteur physique et de dommage intégré à Minecraft | un remplacement du moteur de Minecraft |
| un pipeline d'assets 3D | un éditeur 3D |
| un renderer PBR additif dédié aux objets AXION | un remplacement du renderer de Minecraft |
| une API publique versionnée | une bibliothèque de contenu |
| indépendant de RUSTFORGE-X | isolé de RUSTFORGE-X |
| un moteur autoritatif serveur | un moteur pair-à-pair ou lockstep |

## 1.3 Objectifs

| ID | Objectif |
|---|---|
| R-010 | Définir un objet 3D physique complet **sans écrire une ligne de Java** (asset + JSON) |
| R-011 | Permettre à un mod tiers d'étendre le moteur via une API publique stable |
| R-012 | **Produire de vrais dégâts physiques et visuels** : un choc doit cabosser réellement la géométrie, progressivement, localement, de façon permanente ou élastique selon le matériau |
| R-013 | Rendre le système de dommage **entièrement générique** : carrosseries, panneaux, structures, machines, blocs, créatures, objets souples — aucune logique par type de contenu |
| R-014 | Lier les dégâts aux **événements physiques réels** (énergie, masse effective, normale, aire), jamais à une variable abstraite isolée |
| R-015 | Simuler N objets physiques et déformables avec un coût CPU/GPU **borné et configurable**, en dégradant proprement |
| R-016 | Fournir un rendu moderne (PBR, normal/roughness/metallic/AO/emissive, décalques, ombres propres) cohérent avec Minecraft |
| R-017 | Fonctionner en serveur dédié sans aucune dépendance au code client |
| R-018 | Ne jamais corrompre un monde ; le retrait du mod laisse le monde chargeable |
| R-019 | Ne jamais faire planter l'instance à cause d'un asset, d'une définition, d'un paquet ou d'un NBT hostile |
| R-020 | Fonctionner identiquement avec et sans RUSTFORGE-X installé |
| R-021 | Rendre toute décision de dégradation et tout niveau de qualité observables et explicables |
| R-022 | Fournir des mesures reproductibles pour toute affirmation de performance |
| R-023 | Garantir que l'état de dommage et de déformation survit à la sauvegarde, au redémarrage, à la reconnexion et au changement de dimension |

## 1.4 Principes directeurs

| ID | Principe | Conséquence normative |
|---|---|---|
| P-01 | **Générique d'abord** | aucune branche conditionnelle sur un nom de contenu, de véhicule ou de mod dans le moteur (INV-01) |
| P-02 | **Additif** | AXION n'altère aucun comportement vanilla existant |
| P-03 | **Autorité serveur** | la vérité physique, structurelle et plastique est sur le serveur (INV-02) |
| P-04 | **Thread autoritatif intouchable** | aucune mutation d'état Minecraft hors du thread autoritatif (INV-03) |
| P-05 | **Frontière FFI par lot** | jamais un appel FFI par élément (INV-04) |
| P-06 | **Budget avant fonctionnalité** | tout sous-système a un budget CPU, GPU si applicable, mémoire, un comportement en surcharge et un fallback |
| P-07 | **Niveaux de qualité plutôt que suppression** | une fonctionnalité coûteuse est graduée (Q-0..Q-4), jamais retirée |
| P-08 | **Échec confiné** | un asset, un objet ou une frame défaillants ne dégradent que leur périmètre |
| P-09 | **Aucune panique traversante** | aucune panic Rust ne traverse la frontière FFI (INV-05) |
| P-10 | **Données avant code** | tout contenu est déclaratif ; le code n'est requis que pour étendre le moteur |
| P-11 | **Indépendance** | aucun système d'AXION ne nécessite RUSTFORGE-X (INV-06) |
| P-12 | **Isolation des API tierces** | toute API Forge/Minecraft passe par une couche d'abstraction unique |
| P-13 | **Mesure ou silence** | aucun chiffre de performance publié sans fichier de résultats généré |
| P-14 | **Séparation visuel / collision** | la géométrie visuelle déformée et la géométrie de collision sont deux représentations distinctes, couplées par une politique explicite et budgétée (INV-13) |
| P-15 | **Reconstruction plutôt que réplication** | ce que le client peut recalculer de façon déterministe n'est pas transmis (INV-15) |

## 1.5 Limite fondamentale assumée

AXION s'exécute **à l'intérieur** de Minecraft :

```text
- le tick serveur est à 20 Hz et son état est mono-thread autoritatif
- le rendu passe par un contexte OpenGL détenu par Minecraft, framebuffer LDR
- les mods de shaders peuvent prendre le contrôle du pipeline
- le réseau passe par le protocole Minecraft/Forge
- la mémoire Java est gérée par la JVM
```

Conséquence normative : AXION borne son coût, gradue sa qualité, dégrade proprement et mesure tout ce qu'il fait. Il ne promet aucune performance non mesurée.

## 1.6 Règle d'or

```text
Si AXION ne peut pas prouver qu'une opération est sûre, il ne l'effectue pas.
Si AXION ne peut pas tenir son budget, il baisse son niveau de qualité de façon
visible et réversible, plutôt que de faire chuter le TPS ou le framerate.
Si une fonctionnalité est difficile, AXION la gradue ; il ne la supprime pas.
Si AXION échoue, il échoue localement, bruyamment dans les logs, et
silencieusement pour le reste du jeu.
```

---
# PARTIE 2 : ARCHITECTURE GLOBALE ET INVENTAIRE DES COMPOSANTS

## 2.1 Vue en couches

```text
+---------------------------------------------------------------------------+
| L6  OUTILS, GOUVERNANCE ET SURFACES                                       |
|     API publique (C-70)   Commandes (C-71)   Bench (C-72)                 |
|     Overlay diagnostics (C-73)  CLI (C-74)  Addon Blender (C-75)          |
|     Bridge RUSTFORGE-X optionnel (C-76)   Gouverneur de qualité (C-77)    |
+---------------------------------------------------------------------------+
| L5  RENDU (CLIENT UNIQUEMENT)                                             |
|     Backend NATIVE_GL (C-60)      Backend VANILLA_CONSUMER (C-61)         |
|     GPU Resources (C-62)          Matériaux & Shaders (C-63)              |
|     Culling & LOD (C-64)          Instancing & Batching (C-65)            |
|     Skinning GPU (C-66)           Debug Renderer (C-67)                   |
|     Déformation GPU (C-68)        Décalques & surfaces (C-69)             |
|     Ombres AXION (C-80)           Sonde d'environnement / IBL (C-81)      |
|     Occlusion logicielle (C-82)   Effets intégrés & SSR (C-83)            |
+---------------------------------------------------------------------------+
| L4  INTÉGRATION MINECRAFT                                                 |
|     AxionEntity (C-50)   Network Sync (C-51)   Persistance (C-52)         |
|     Interaction & sièges (C-53)  Bloc & BlockEntity (C-54)  Item 3D (C-55)|
+---------------------------------------------------------------------------+
| L3  SIMULATION (RUST)                                                     |
|     Scene Graph (C-30)      Physics World (C-31)   Collider Builder (C-32)|
|     Vehicles (C-33)         Joints (C-34)          Damage Model (C-35)    |
|     Particle Solver (C-36)  Animation (C-37)       World Collision (C-38) |
|     Spatial Queries (C-39)  Simulation Scheduler (C-40)                   |
|     Impact Solver (C-41)    Deformation Engine (C-42)                     |
|     Structural Integrity (C-43)  Fracture & Detachment (C-44)             |
|     Collider Refit (C-45)   Repair (C-46)                                 |
|     Surface State & Wear (C-47)  Attachment System (C-48)                 |
+---------------------------------------------------------------------------+
| L2  ASSETS                                                                |
|     Orchestrateur (C-20)   Importers (C-21)   Validator (C-22)            |
|     Optimizer (C-23)       Conteneur A3D (C-24)   Cache (C-25)            |
|     Textures & matériaux (C-26)  Definitions (C-27)                       |
|     Compilateur de déformation & structure (C-28)                         |
+---------------------------------------------------------------------------+
| L1  RUNTIME NATIF                                                         |
|     Core & handles (C-10)  Math (C-11)   Job System (C-12)                |
|     Mémoire & arènes (C-13)  Pont FFI (C-14)  Télémétrie (C-15)           |
|     Noyau déterministe (C-16)                                             |
+---------------------------------------------------------------------------+
| L0  ANCRAGE JVM                                                           |
|     Forge Integration (C-01)  Bootstrap (C-02)  Native Loader (C-03)      |
|     Configuration (C-04)      Diagnostics (C-05)                          |
+---------------------------------------------------------------------------+
```

## 2.2 Inventaire normatif des composants

| ID | Composant | Langage | Couche | Côté | Maturité V1.0 | Jalon |
|---|---|---|---|---|---|---|
| C-01 | Forge Integration | Java | L0 | both | STABLE | M0 |
| C-02 | Bootstrap | Java | L0 | both | STABLE | M0 |
| C-03 | Native Loader | Java | L0 | both | STABLE | M0 |
| C-04 | Configuration | Java + Rust | L0 | both | STABLE | M0 |
| C-05 | Diagnostics & Logging | Java + Rust | L0 | both | STABLE | M0 |
| C-10 | Native Core (handles, erreurs) | Rust | L1 | both | STABLE | M0 |
| C-11 | Math | Rust | L1 | both | STABLE | M0 |
| C-12 | Job System | Rust | L1 | both | STABLE | M1 |
| C-13 | Memory / Arenas | Rust | L1 | both | STABLE | M0 |
| C-14 | FFI Bridge | Java + Rust | L1 | both | STABLE | M0 |
| C-15 | Telemetry natif | Rust | L1 | both | STABLE | M1 |
| C-16 | Noyau déterministe | Rust | L1 | both | STABLE | M1 |
| C-20 | Asset Orchestrator | Java | L2 | both | STABLE | M1 |
| C-21 | Importers (glTF/GLB, OBJ, STL) | Rust | L2 | both | STABLE | M1 |
| C-22 | Asset Validator | Rust | L2 | both | STABLE | M1 |
| C-23 | Asset Optimizer (LOD, tangentes, cache) | Rust | L2 | both | STABLE | M2 |
| C-24 | Conteneur A3D | Rust + Java | L2 | both | STABLE | M1 |
| C-25 | Asset Cache | Rust + Java | L2 | both | STABLE | M2 |
| C-26 | Textures & Matériaux | Java | L2 | client | STABLE | M3 |
| C-27 | Definitions data-driven | Java | L2 | both | STABLE | M2 |
| C-28 | Compilateur de déformation & structure | Rust | L2 | both | STABLE | M6 |
| C-30 | Scene Graph | Rust | L3 | both | STABLE | M2 |
| C-31 | Physics World | Rust | L3 | server | STABLE | M3 |
| C-32 | Collider Builder | Rust | L3 | both | STABLE | M3 |
| C-33 | Vehicle System | Rust | L3 | server | STABLE | M5 |
| C-34 | Joints & Constraints | Rust | L3 | server | STABLE | M4 |
| C-35 | Damage Model | Rust | L3 | server | STABLE | M6 |
| C-36 | Particle Solver (tissu, cordes, câbles, filets, soft) | Rust | L3 | both | STABLE (VISUAL, SERVER_SIMPLE) / EXPERIMENTAL (SERVER_FULL, auto-collision, déchirure) | M8 |
| C-37 | Animation System | Rust | L3 | both | STABLE | M4 |
| C-38 | World Collision Provider | Java + Rust | L3 | server | STABLE | M3 |
| C-39 | Spatial Queries | Rust | L3 | both | STABLE | M3 |
| C-40 | Simulation Scheduler | Rust | L3 | both | STABLE | M3 |
| C-41 | Impact Solver | Rust | L3 | server | STABLE | M6 |
| C-42 | Deformation Engine | Rust | L3 | both (autorité serveur) | STABLE | M6 |
| C-43 | Structural Integrity | Rust | L3 | server | STABLE | M7 |
| C-44 | Fracture & Detachment | Rust | L3 | server | STABLE | M7 |
| C-45 | Collider Refit | Rust | L3 | server | STABLE | M7 |
| C-46 | Repair System | Rust + Java | L3 | server | STABLE | M7 |
| C-47 | Surface State & Wear | Rust | L3 | both | STABLE | M9 |
| C-48 | Attachment System | Rust + Java | L3 | server | STABLE | M5 |
| C-50 | Axion Entity | Java | L4 | both | STABLE | M2 |
| C-51 | Network Sync | Java + Rust | L4 | both | STABLE | M4 |
| C-52 | Persistance | Java + Rust | L4 | server | STABLE | M4 |
| C-53 | Interaction & Sièges | Java | L4 | both | STABLE | M5 |
| C-54 | Bloc & BlockEntity 3D | Java | L4 | both | STABLE | M10 |
| C-55 | Item 3D | Java | L4 | client | STABLE | M10 |
| C-60 | Backend NATIVE_GL | Java | L5 | client | STABLE | M3 |
| C-61 | Backend VANILLA_CONSUMER | Java | L5 | client | STABLE | M3 |
| C-62 | GPU Resource Manager | Java | L5 | client | STABLE | M3 |
| C-63 | Matériaux & Shaders | Java | L5 | client | STABLE | M3 |
| C-64 | Culling & LOD | Rust + Java | L5 | client | STABLE | M4 |
| C-65 | Instancing & Batching | Java | L5 | client | STABLE | M4 |
| C-66 | Skinning GPU | Java + Rust | L5 | client | STABLE | M4 |
| C-67 | Debug Renderer | Java | L5 | client | STABLE | M3 |
| C-68 | Déformation GPU | Java + Rust | L5 | client | STABLE | M6 |
| C-69 | Décalques & états de surface (rendu) | Java + Rust | L5 | client | STABLE | M9 |
| C-70 | API publique `axion-api` | Java | L6 | both | STABLE | M2 |
| C-71 | Commandes `/axion` | Java | L6 | both | STABLE | M1 |
| C-72 | Benchmark Harness | Java + Rust + scripts | L6 | both | STABLE | M2 |
| C-73 | Overlay diagnostics | Java | L6 | client | STABLE | M5 |
| C-74 | CLI `axion-cli` | Rust | L6 | outil | STABLE | M2 |
| C-75 | Addon Blender | Python | L6 | outil | STABLE | M9 |
| C-76 | Bridge RUSTFORGE-X | Java | L6 | both | STABLE | M11 |
| C-77 | Gouverneur de qualité | Rust + Java | L6 | both | STABLE | M5 |
| C-80 | Ombres AXION | Java + Rust | L5 | client | STABLE | M9 |
| C-81 | Sonde d'environnement & IBL synthétique | Java + Rust | L5 | client | STABLE | M9 |
| C-82 | Occlusion culling logiciel | Rust | L5 | client | STABLE | M9 |
| C-83 | Effets intégrés (tone mapping, SSR) | Java | L5 | client | STABLE (tone mapping) / EXPERIMENTAL (SSR) | M9 |

## 2.3 Graphe de dépendances

```text
C-01 Forge Integration
 └─> C-02 Bootstrap
      ├─> C-04 Configuration ──> C-77 Gouverneur de qualité
      ├─> C-03 Native Loader ──> C-14 FFI ──> C-10 Core
      │                                        ├─> C-11 Math ──> C-16 Noyau déterministe
      │                                        ├─> C-13 Memory
      │                                        ├─> C-12 Jobs
      │                                        └─> C-15 Telemetry
      ├─> C-05 Diagnostics
      └─> C-76 Bridge RUSTFORGE-X (optionnel, jamais requis)

C-20 Asset Orchestrator
 ├─> C-21 Importers ─> C-22 Validator ─> C-23 Optimizer ─> C-28 Deformation Compiler ─> C-24 A3D
 ├─> C-25 Cache
 ├─> C-26 Textures & matériaux        (client)
 └─> C-27 Definitions

C-40 Simulation Scheduler
 ├─> C-30 Scene Graph
 ├─> C-31 Physics World ─> C-32 Collider Builder ─> C-24 A3D
 │        ├─> C-34 Joints
 │        ├─> C-33 Vehicles
 │        ├─> C-38 World Collision
 │        ├─> C-39 Queries
 │        └─> C-48 Attachment
 ├─> C-41 Impact Solver ─> C-35 Damage Model
 │                          ├─> C-42 Deformation Engine ─> C-16 Noyau déterministe
 │                          │        └─> C-45 Collider Refit ─> C-31
 │                          ├─> C-43 Structural Integrity ─> C-44 Fracture & Detachment
 │                          └─> C-47 Surface State & Wear
 ├─> C-46 Repair (inverse de C-42, C-43, C-35, C-47)
 ├─> C-36 Particle Solver
 └─> C-37 Animation

C-50 Axion Entity ─> C-51 Network ─> C-52 Persistance
                 └─> C-53 Interaction

Rendu (client) : C-60 | C-61
   ├─> C-62 GPU Resources    ├─> C-63 Shaders     ├─> C-64 Culling/LOD ─> C-82 Occlusion
   ├─> C-65 Instancing       ├─> C-66 Skinning    ├─> C-68 Déformation GPU
   ├─> C-69 Décalques        ├─> C-80 Ombres      ├─> C-81 Sonde/IBL
   ├─> C-83 Effets           └─> C-67 Debug
```

Aucune dépendance cyclique n'est autorisée. Le graphe est vérifié mécaniquement (T-004 : ArchUnit côté Java, test de graphe de modules côté Rust).

## 2.4 Modèle de threads

```text
+---------------------------------------------------------------------+
| THREAD AUTORITATIF (Server thread | Client main/render thread)       |
|  - exécute Minecraft, Forge, les autres mods                         |
|  - exécute les hooks AXION (submit / collect / apply)                |
|  - SEUL autorisé à muter l'état Minecraft (INV-03)                   |
|  - SEUL autorisé à émettre des commandes OpenGL (INV-12)             |
+---------------------------------------------------------------------+
        | submit (buffer direct + 1 appel FFI)   ^ résultats (buffer direct)
        v                                        |
+---------------------------------------------------------------------+
| AXION WORKER POOL (Rust, N = clamp(cores-2, 1, config.max_workers))  |
|  - "axion-worker-<i>", daemon, priorité NORM-1                       |
|  - physique, impacts, déformation, structure, animation, particules, |
|    culling, occlusion, préparation de rendu                          |
|  - NE DOIT PAS appeler la JVM (aucune JNIEnv attachée) (INV-07)      |
+---------------------------------------------------------------------+
+---------------------------------------------------------------------+
| AXION ASSET THREAD (1, priorité MIN, "axion-assets")                 |
|  - import, validation, optimisation, compilation de déformation,     |
|    écriture de cache ; ne rappelle jamais Java                       |
+---------------------------------------------------------------------+
+---------------------------------------------------------------------+
| AXION WATCHDOG (1, priorité MAX, "axion-watchdog")                   |
|  - budgets, blocages, fuites ; déclenche dégradation et mode SAFE    |
+---------------------------------------------------------------------+
```

- R-030 : aucun thread AXION créé avant `FMLCommonSetupEvent` ; tous daemon, préfixe `axion-`.
- R-031 : à l'arrêt, jointure avec timeout de 5 s puis abandon journalisé (`E-1050`).
- R-032 : le pool de workers NE DOIT PAS attacher de `JNIEnv` ; communication exclusivement par mémoire partagée et sondage depuis Java (INV-07).

## 2.5 Cycle de vie d'un tick serveur

```text
TICK_BEGIN  (ServerTickEvent PRE, priorité HIGHEST)
  | C-01 ouvre la fenêtre de tick, incrémente l'epoch
  | C-38 publie les deltas de collision monde (blocs modifiés)
  | C-50 publie les entrées de contrôle validées, les commandes d'API,
  |      les dégâts d'origine Minecraft (explosions, projectiles, feu)
  | C-40 axion_sim_submit(...)                       -> 1 appel FFI
  v
PHASE_VANILLA (tick Minecraft + autres mods)
  | workers Rust : 1..N sous-pas de simulation, puis chaîne de dommage
  v
TICK_COLLECT  (ServerTickEvent POST, priorité LOWEST)
  | C-40 axion_sim_collect(deadline_ns)              -> 1 appel FFI
  |   -> états de bodies, événements, deltas de déformation, ruptures,
  |      détachements, refits de collider, états de surface
  | C-50 applique transforms, sockets, événements (thread autoritatif)
  | C-44 crée/supprime les entités de débris via l'API Minecraft
  | C-51 émet les snapshots et les événements réseau dus à ce tick
  | C-15 collecte les métriques du tick ; C-77 met à jour les niveaux de qualité
  v
TICK_END
```

## 2.6 Cycle de vie d'une frame client

```text
FRAME_BEGIN (RenderLevelStageEvent.Stage.AFTER_SKY)
  | C-40 interpolation (alpha = partialTick) ; C-36 pas de particules visuelles
  | C-81 met à jour la sonde d'environnement si due
  | C-82 rastérise le tampon d'occlusion logiciel (si Q >= MEDIUM)
  | C-64 axion_render_prepare(camera, frustum, budget)  -> 1 appel FFI
  |     -> instances visibles triées, LOD, matrices de nodes, palettes de
  |        skinning, pages de champs de déformation, listes de décalques
  v
STAGE AFTER_SKY (juste après)      -> C-80 passe d'ombre AXION (depth only)
STAGE AFTER_ENTITIES               -> C-60/C-61 passes opaque, cutout, émissive
STAGE AFTER_TRANSLUCENT_BLOCKS     -> C-60/C-61 passe translucide triée
STAGE AFTER_PARTICLES              -> C-67 overlays de debug si activés
  v
FRAME_END
  | C-15 métriques de frame ; C-62 recyclage GPU ; C-77 ajustement de qualité
```

## 2.7 Séparation client / serveur

| Sous-système | Serveur dédié | Client |
|---|---|---|
| Physique rigid body | autoritative | non simulée ; interpolation des snapshots |
| Physique véhicule | autoritative | prédiction du seul véhicule piloté |
| Impacts et calcul de dommage | **autoritatif exclusif** | jamais |
| Champ de déformation plastique | **autoritatif** | reconstruit par le noyau déterministe (C-16) depuis les impacts reçus |
| Champ de déformation élastique | calculé si un collider ou un socket en dépend | calculé intégralement (visuel) |
| Intégrité structurelle, rupture, détachement | **autoritatif exclusif** | reçoit les événements |
| Refit de collider | oui | seulement pour le véhicule prédit |
| Usure de surface (rayures, saleté, brûlure) | autoritative, quantifiée | reconstruite depuis les événements |
| Décalques | jamais | oui |
| Particules (tissu/corde) mode VISUAL | jamais | oui |
| Particules mode SERVER_SIMPLE / SERVER_FULL | autoritative | interpolée |
| Animation squelettique | échantillonnée si un socket, un collider ou une déformation en dépend | complète |
| Culling, occlusion, LOD, ombres, IBL | jamais | oui |
| Rendu, textures, shaders | jamais chargés | oui |
| Sections A3D chargées | `HEAD NODE GEOM PHYS SKEL ANIM PART DEFM STRC SOCK META` | toutes |

- R-040 : aucune classe de `dev.axion.client.**` référencée depuis du code exécuté sur serveur dédié (T-010).
- R-041 : le serveur ignore les sections purement visuelles (`MATL TEXR LODM DECL`).
- R-042 : le serveur charge la section `DEFM` (régions de déformation) car la déformation plastique est autoritative et pilote le refit de collider.

## 2.8 Chaîne de dommage — vue d'ensemble

Cette chaîne est le cœur fonctionnel ajouté par la révision 2. Elle est **entièrement générique** et ne connaît aucun type de contenu.

```text
 (1) CONTACT PHYSIQUE                      C-31
     point, normale, impulsion, vitesses relatives, masses, matériaux
        |
        v
 (2) SOLVEUR D'IMPACT                      C-41
     énergie absorbée, masse effective, aire de contact, direction,
     composantes normale et tangentielle, part et zone touchées
        |
        +---------------------------------------------------+
        v                                                   v
 (3) MODÈLE DE DOMMAGE                     C-35        (3') USURE DE SURFACE   C-47
     dommage structurel + dommage visuel                rayure, éraflure,
     par part, par zone, avec multiplicateurs           saleté, brûlure
        |                                                   |
        +----------------------+                            v
        v                      v                       décalques (C-69)
 (4) DÉFORMATION               (5) INTÉGRITÉ STRUCTURELLE   C-43
     C-42                          intégrité des liaisons, propagation
     champ élastique + plastique   |
     par région, quantifié         v
        |                      (6) RUPTURE / DÉTACHEMENT    C-44
        |                          nouvelle assembly, débris, joints cassés
        v                          |
 (7) REFIT DE COLLIDER   C-45 <----+
     déformation des points d'enveloppe, re-hull budgété
        |
        v
 (8) RENDU                C-68 / C-69 / C-60
     déplacement en vertex shader, normales par gradient analytique,
     décalques, matériaux de dommage
        |
        v
 (9) RÉSEAU               C-51        (10) PERSISTANCE     C-52
     événements d'impact + empreinte      champ quantifié compressé,
     de champ ; instantané si divergence   intégrité, états de part
```

Chaque étape est spécifiée en PARTIE 13 à 16, avec son budget, son niveau de qualité, son fallback et ses tests.

---
# PARTIE 3 : MODÈLE DE DONNÉES CANONIQUE

Toutes les structures sont définies **une seule fois**, dans le crate `ax-model`, et projetées vers Java par génération de code (`tools/codegen/gen-java-model`). Toute divergence est une erreur de build (T-005).

Règles générales :

- R-100 : toute structure traversant la frontière FFI est `#[repr(C)]`, sans pointeur brut, sans champ de taille variable.
- R-101 : toute structure persistée ou transmise est sérialisée en format binaire explicite documenté, jamais par un format implicite.
- R-102 : unités SI, espace Minecraft : 1 unité = 1 bloc = 1 mètre, Y vers le haut, repère main droite, radians, kilogrammes, secondes, joules, pascals.
- R-103 : `f32` pour tout ce qui traverse la frontière, sauf les positions monde en `f64`.

## 3.1 DM-01 : identifiants

```rust
#[repr(C)] pub struct AssetId(pub u64);   // FNV-1a 64 de "namespace:path"

#[repr(C)]
#[derive(Copy, Clone, PartialEq, Eq)]
pub struct Handle { pub index: u32, pub generation: u32 }   // generation 0 = invalide

pub type BodyHandle = Handle;      pub type AssemblyHandle = Handle;
pub type AssetHandle = Handle;     pub type ColliderHandle = Handle;
pub type JointHandle = Handle;     pub type ParticleSetHandle = Handle;
pub type RegionHandle = Handle;    pub type AttachmentHandle = Handle;
```

- R-110 : invalidation par incrément de `generation` à la libération ; accès périmé = `E-2001`, sans effet de bord.
- R-111 : `AssetId(0)` et `Handle{0,0}` sont réservés à « absent ».
- R-112 : les collisions de hash sont détectées à la compilation (`E-3010`).

## 3.2 DM-02 : Transform

```rust
#[repr(C)] pub struct Transform { pub translation: [f32;3], pub rotation: [f32;4], pub scale: [f32;3] }
#[repr(C)] pub struct WorldTransform { pub position: [f64;3], pub rotation: [f32;4] }
```

- R-120 : le scale non uniforme est autorisé sur les nodes de rendu, **interdit** sur un node portant un collider (`E-3020`).
- R-121 : tout quaternion externe est renormalisé ; norme hors `[0.9, 1.1]` = `E-2010`.

## 3.3 DM-03 : Node

```rust
#[repr(C)]
pub struct NodeDesc {
    pub name_hash: u64,
    pub parent:    u32,          // u32::MAX = racine
    pub local:     Transform,
    pub flags:     u32,          // NodeFlags
    pub mesh:      u32,          // u32::MAX = aucun
    pub collider:  u32,
    pub bone:      u32,
    pub part:      u16,          // u16::MAX = aucune
    pub region:    u16,          // région de déformation, u16::MAX = aucune
    pub lod_mask:  u8,
    pub state:     u8,           // NodeStateSource (voir 9.2)
    pub _pad:      [u8;2],
}

bitflags! { pub struct NodeFlags: u32 {
    const VISIBLE = 1<<0;  const SOCKET = 1<<1;  const WHEEL = 1<<2;   const SEAT = 1<<3;
    const LIGHT   = 1<<4;  const DETACHABLE = 1<<5;  const ANIMATED = 1<<6;
    const PHYSICS_DRIVEN = 1<<7;  const HIDDEN_IN_FIRST_PERSON = 1<<8;  const NO_CULL = 1<<9;
    const EMISSIVE = 1<<10; const DEFORMABLE = 1<<11; const INTERNAL = 1<<12;
    const REVEALED_ON_DAMAGE = 1<<13; const CLOTH_ANCHOR = 1<<14; const DECAL_TARGET = 1<<15;
    const SHADOW_CASTER = 1<<16; const NO_DEFORM_NORMALS = 1<<17;
}}
```

`INTERNAL` + `REVEALED_ON_DAMAGE` permettent de modéliser les éléments internes (moteur, structure, câblage) apparaissant lorsque la pièce qui les couvre est déformée ou détruite (exigence 3 de la révision).

- R-130 : hiérarchie stockée en ordre topologique (parent avant enfant), vérifié à la compilation (`E-3021`).
- R-131 : profondeur maximale 32 (`E-3022`) ; R-132 : 4096 nodes par asset (`E-3023`).

## 3.4 DM-04 : Mesh et vertex

```rust
#[repr(C)]
pub struct MeshDesc {
    pub vertex_offset: u32, pub vertex_count: u32,
    pub index_offset:  u32, pub index_count:  u32,
    pub material: u16, pub lod: u8, pub flags: u8,     // SKINNED, DOUBLE_SIDED, TRANSPARENT, DEFORMABLE
    pub aabb_min: [f32;3], pub aabb_max: [f32;3],
    pub region:   u16,                                  // région de déformation dominante
    pub _pad:     u16,
}

/// Format de vertex canonique, entrelacé, 48 octets, aligné 4. FIGÉ EN V1.0.
#[repr(C)]
pub struct Vertex {
    pub position: [f32;3],   // 12
    pub normal:   [i8;4],    // 4
    pub tangent:  [i8;4],    // 4  (w = signe du bitangent)
    pub uv0:      [u16;2],   // 4  UNORM16
    pub uv1:      [u16;2],   // 4  UNORM16 (lightmap / AO / second jeu)
    pub color:    [u8;4],    // 4
    pub bones:    [u8;4],    // 4
    pub weights:  [u8;4],    // 4  UNORM8, somme = 255
    pub region:   u8,        // 1  index de région de déformation (255 = aucune)
    pub def_w:    u8,        // 1  poids de déformation UNORM8 (rigidité locale)
    pub _pad:     [u8;6],    // 6  réservé, à zéro
}                            // total 48
```

- R-140 : format de vertex **unique et figé**. `region` et `def_w` remplacent 2 octets de la zone réservée de la révision 1 : la taille et l'alignement sont inchangés.
- R-141 : `def_w` encode le poids de déformation par sommet (0 = totalement rigide, 255 = pleinement déformable). Il provient des vertex groups Blender (PARTIE 8) ou d'un calcul par défaut.
- R-142 : UV ramenés dans `[0,1]` par l'optimizer ; hors `[-8,9]` avant normalisation = `E-3030`.
- R-143 : plafonds par asset : 2 000 000 vertices, 6 000 000 indices (`E-3031`).

## 3.5 DM-05 : MaterialDesc (rendu)

```rust
#[repr(C)]
pub struct MaterialDesc {
    pub name_hash:       u64,
    pub albedo_tex:      u16, pub normal_tex: u16, pub orm_tex: u16, pub emissive_tex: u16,
    pub height_tex:      u16,      // parallax, u16::MAX = aucune
    pub damage_tex:      u16,      // masque de dommage (R = éraflure, G = déformation, B = brûlure, A = saleté)
    pub albedo_factor:   [f32;4],
    pub emissive_factor: [f32;3],
    pub metallic:        f32,
    pub roughness:       f32,
    pub occlusion_strength: f32,
    pub normal_scale:    f32,
    pub alpha_cutoff:    f32,
    pub parallax_scale:  f32,
    pub clearcoat:       f32,      // couche de vernis (carrosserie)
    pub clearcoat_roughness: f32,
    pub sheen:           f32,      // tissus
    pub anisotropy:      f32,      // métal brossé
    pub blend_mode:      u8,       // OPAQUE | CUTOUT | TRANSLUCENT
    pub cull_mode:       u8,       // BACK | NONE
    pub shading_model:   u8,       // PBR | PBR_CLEARCOAT | PBR_SHEEN | UNLIT | VANILLA_COMPAT
    pub _pad:            u8,
    pub flags:           u16,      // VERTEX_COLOR, TINTABLE, FULLBRIGHT, WEAR_ENABLED, DECAL_RECEIVER
    pub wear_profile:    u16,      // index de profil d'usure (C-47), u16::MAX = aucun
}
```

- R-150 : `TRANSLUCENT` implique tri par distance et exclusion du regroupement d'instances se recouvrant.
- R-151 : le modèle d'éclairage V1.0 est un **PBR metallic-roughness avec environnement synthétisé** (PARTIE 19.5, ADR-008).

## 3.6 DM-06 : Collider

```rust
#[repr(C)]
pub struct ColliderDesc {
    pub shape: ColliderShape,
    pub local: Transform,
    pub material: u16, pub group: u32, pub mask: u32,
    pub flags: u32,          // SENSOR, CCD_ENABLED, DAMAGE_ZONE, REFITTABLE, NO_REFIT
    pub density: f32,
    pub damage_zone: u16, pub part: u16,
    pub region: u16,         // région de déformation appliquée au refit
    pub hull_points_offset: u32, pub hull_points_count: u32,   // points sources pour le refit
}

#[repr(C, u32)]
pub enum ColliderShape {
    Sphere { radius: f32 },
    Box { half_extents: [f32;3] },
    Capsule { half_height: f32, radius: f32 },
    Cylinder { half_height: f32, radius: f32 },
    Cone { half_height: f32, radius: f32 },
    ConvexHull { points_offset: u32, points_count: u32 },
    TriMesh { vertices_offset: u32, vertices_count: u32, indices_offset: u32, indices_count: u32 },
    Heightfield { rows: u32, cols: u32, data_offset: u32, scale: [f32;3] },
    Compound { children_offset: u32, children_count: u32 },
}
```

- R-160 : `TriMesh` et `Heightfield` sont **interdits sur un body dynamique** (`E-2020`), y compris après déformation (INV-13).
- R-161 : un `ConvexHull` a entre 4 et 256 points après réduction (`E-3040`).
- R-162 : la décomposition convexe automatique est faite **à la compilation d'asset**, jamais au runtime.
- R-163 : `hull_points` est l'ensemble des points sources conservés pour permettre le refit après déformation (PARTIE 14.9). Il est plafonné à 256 points par collider.

## 3.7 DM-07 : PhysicsMaterial — modèle de matière étendu

```rust
#[repr(C)]
pub struct PhysicsMaterial {
    pub name_hash:           u64,
    // contact
    pub friction:            f32,   // [0, 2]
    pub restitution:         f32,   // [0, 1]
    pub rolling_friction:    f32,
    pub friction_combine:    u8, pub restitution_combine: u8, pub _pad0: [u8;2],
    // matière
    pub density:             f32,   // kg/m3
    pub hardness:            f32,   // [0,1] résistance à la rayure/éraflure
    pub stiffness:           f32,   // Pa (module apparent), pilote le rayon de déformation
    pub elasticity:          f32,   // [0,1] part du déplacement qui revient
    pub plasticity:          f32,   // [0,1] part du dépassement qui devient permanent
    pub yield_strength:      f32,   // Pa, seuil au-delà duquel la déformation devient plastique
    pub fracture_strength:   f32,   // Pa, seuil de rupture locale
    pub deformation_resistance: f32,// multiplicateur global [0.01, 100]
    pub max_strain:          f32,   // déformation relative maximale avant rupture [0,1]
    pub damping:             f32,   // amortissement du retour élastique
    pub fracture_energy:     f32,   // J/m², énergie spécifique de rupture (travail
                                    // de rupture par unité d'aire de liaison, Gc)
    pub hardness_pressure:   f32,   // Pa, dureté dynamique d'indentation
                                    // (Tabor : 3 x yield_strength si non déclarée)
    pub indenter_radius_ref: f32,   // m, rayon d'indenteur de référence (contact BLUNT)
    pub spread_factor:       f32,   // sans dimension [0.5, 8]
    pub thickness_spread:    f32,   // sans dimension [0, 20]
    pub damage_multiplier:   f32,   // multiplicateur de dommage reçu
    pub energy_absorption:   f32,   // [0,1] part de l'énergie convertie en dommage
    // surface
    pub scratch_resistance:  f32,   // [0,1]
    pub burn_threshold:      f32,   // J, énergie thermique avant marquage
    pub soil_rate:           f32,   // vitesse d'encrassement
    // audio / effets
    pub sound_group:         u32,
    pub particle_group:      u32,
    pub flags:               u32,   // BRITTLE, DUCTILE, SOFT, RIGID, FLAMMABLE, NON_DEFORMABLE
}
```

- R-170 : `BRITTLE` (fragile) désactive la plasticité et transforme tout dépassement en rupture ; `DUCTILE` privilégie la plasticité ; `SOFT` autorise de grandes déformations élastiques ; `NON_DEFORMABLE` exclut la matière du système de déformation (verre, chrome fin, pierre).
- R-171 : les matériaux physiques sont data-driven (`data/<ns>/axion/physics_materials/*.json`), avec héritage par `parent`, et une bibliothèque de base fournie (`axion:steel`, `axion:aluminium`, `axion:plastic`, `axion:glass`, `axion:rubber`, `axion:wood`, `axion:fabric`, `axion:stone`, `axion:flesh`). Cette bibliothèque est du **contenu de référence**, pas une exception codée en dur : elle est chargée comme n'importe quel datapack.

## 3.8 DM-08 : BodyDesc et BodyState

```rust
#[repr(C)]
pub struct BodyDesc {
    pub body_type: u8, pub ccd: u8, pub can_sleep: u8, pub _pad: u8,
    pub mass: f32, pub center_of_mass: [f32;3], pub inertia: [f32;3],
    pub linear_damping: f32, pub angular_damping: f32, pub gravity_scale: f32,
    pub max_linear_vel: f32, pub max_angular_vel: f32,
}

#[repr(C)]
pub struct BodyState {
    pub handle: BodyHandle,
    pub position: [f64;3], pub rotation: [f32;4],
    pub lin_vel: [f32;3],  pub ang_vel: [f32;3],
    pub flags: u32,        // SLEEPING, TOUCHING_GROUND, IN_FLUID, CLAMPED, DEFORMED, DAMAGED
}
```

- R-180 : `max_linear_vel` défaut 300 m/s, `max_angular_vel` 100 rad/s ; dépassement clampé, flag `CLAMPED`, journalisé au plus une fois par body et par minute.
- R-181 : position NaN ou hors limites du monde → sommeil forcé, `E-2030`, retour au dernier état valide.

## 3.9 DM-09 : Assembly

```rust
#[repr(C)]
pub struct AssemblyDesc {
    pub asset: AssetId, pub kind: u16, pub flags: u32,
    pub spawn: WorldTransform, pub dimension: u64,
    pub owner_entity: i32, pub definition: u64,
    pub quality_hint: u8, pub _pad: [u8;3],
}

#[repr(u16)]
pub enum AssemblyKind {
    Static = 0, RigidObject = 1, Vehicle = 2, Articulated = 3,
    Character = 4, Visual = 5, Structure = 6, Machine = 7, SoftObject = 8,
}
```

- R-190 : plafonds par assembly : 64 bodies, 128 joints, 64 parts, 32 régions de déformation, 16 sièges, 8 ensembles de particules, 64 décalques. Dépassement = `E-3050` à la compilation.
- R-191 : `Structure` et `Machine` sont des variantes de `Articulated` avec des défauts différents (sommeil agressif, pas de sommeil) ; elles n'introduisent **aucune** logique spécifique.

## 3.10 DM-10 : Skeleton et Animation

```rust
#[repr(C)] pub struct BoneDesc { pub name_hash: u64, pub parent: u16, pub _pad: u16,
                                 pub node: u32, pub inverse_bind: [f32;16] }
#[repr(C)] pub struct AnimationDesc { pub name_hash: u64, pub duration: f32,
                                      pub track_offset: u32, pub track_count: u32, pub flags: u32 }
#[repr(C)] pub struct TrackDesc { pub target_node: u32, pub channel: u8, pub interp: u8,
                                  pub _pad: [u8;2], pub key_offset: u32, pub key_count: u32 }
```

- R-200 : 128 bones maximum par squelette (`E-3060`) ; 256 pistes actives par assembly.

## 3.11 DM-11 : Part, zone de dommage et état

```rust
#[repr(C)]
pub struct PartDesc {
    pub name_hash:        u64,
    pub root_node:        u32,
    pub parent_part:      u16,     // u16::MAX = racine
    pub flags:            u32,     // DETACHABLE, CRITICAL, HIDE_WHEN_DESTROYED, STRUCTURAL,
                                   // INTERNAL, NO_DEFORM, REPAIRABLE, SPAWNS_DEBRIS
    pub max_health:       f32,     // dommage visuel/fonctionnel
    pub structural_capacity: f32,  // J, énergie structurelle absorbable avant rupture
    pub detach_threshold: f32,     // [0,1] fraction d'intégrité déclenchant le détachement
    pub propagation:      f32,     // [0,1] fraction transmise au parent
    pub mass:             f32,
    pub material:         u16,     // PhysicsMaterial dominant
    pub region_first:     u16, pub region_count: u16,   // régions de déformation
    pub mesh_intact:      u32, pub mesh_damaged: u32, pub mesh_destroyed: u32,
    pub debris_definition: u64,    // definition à instancier au détachement, 0 = auto
    pub _pad:             u16,
}

#[repr(C)]
pub struct DamageZoneDesc {
    pub name_hash: u64, pub part: u16, pub region: u16,
    pub shape: ColliderShape,      // volume de la zone, en espace de part
    pub multiplier: f32,           // multiplicateur de dommage
    pub deform_multiplier: f32,    // multiplicateur de déformation
    pub parent_zone: u16, pub _pad: u16,
}

#[repr(C)]
pub struct PartState {
    pub health:     f32,           // [0,1]
    pub integrity:  f32,           // [0,1] intégrité structurelle
    pub absorbed:   f32,           // J cumulés
    pub deform_max: f32,           // m, déplacement plastique maximal observé
    pub stage:      u8,            // INTACT | SCRATCHED | DAMAGED | HEAVY | DESTROYED | DETACHED
    pub flags:      u8,            // JAMMED, BURNING, DISABLED, REVEALED
    pub _pad:       [u8;2],
}
```

Les **sous-zones** sont obtenues par `parent_zone` : une zone peut affiner une zone parente (exigence « zones et sous-zones »).

## 3.12 DM-12 : Régions de déformation et champ

```rust
/// Région de déformation : lattice de contrôle attaché à un sous-arbre de nodes.
#[repr(C)]
pub struct DeformRegionDesc {
    pub name_hash:   u64,
    pub part:        u16,
    pub root_node:   u32,
    pub res:         [u8;3],       // résolution du lattice, chaque axe dans [2, 16]
    pub flags:       u8,           // SHELL, VOLUME, ANCHORED_BORDER, ALLOW_TEAR
    pub obb_center:  [f32;3],      // OBB en espace de part
    pub obb_half:    [f32;3],
    pub obb_rot:     [f32;4],
    pub material:    u16,          // PhysicsMaterial de la région (peut différer de la part)
    pub thickness:   f32,          // m, épaisseur de tôle apparente (pilote le rayon)
    pub max_disp:    f32,          // m, déplacement maximal admissible par nœud
    pub anchor_mask_offset: u32,   // bitset des nœuds ancrés (non déformables)
    pub node_count:  u32,          // res.x*res.y*res.z
    pub hull_binding_offset: u32,  // liaison vers les points d'enveloppe de collider
    pub hull_binding_count:  u32,
    pub _pad:        u16,
}

/// État runtime du champ. Plastique = persistant, élastique = transitoire.
/// Stockage : i8 quantifié sur une grille de pas quant_step = max_disp / 127.
#[repr(C)]
pub struct DeformFieldHeader {
    pub region:      RegionHandle,
    pub node_count:  u32,
    pub quant_step:  f32,
    pub plastic_offset: u32,       // i8[node_count*3] dans l'arène de déformation
    pub elastic_offset: u32,       // f16[node_count*3], 0 si Q < MEDIUM
    pub velocity_offset: u32,      // f16[node_count*3], 0 sauf Q = ULTRA
    pub dirty_slabs: u32,          // bitset de tranches modifiées (max 32 tranches)
    pub version:     u32,          // incrémenté à chaque modification plastique
    pub energy:      f32,          // J cumulés dans la région
    pub max_strain:  f32,          // déformation relative maximale atteinte
    pub flags:       u32,          // HAS_RESIDUAL, TORN, SATURATED
    pub residual_offset: u32,      // liste éparse (vertex_index u32, disp i8[3], _pad u8)
    pub residual_count:  u32,
}
```

- R-210 : le champ plastique est **quantifié `i8`** et constitue la représentation autoritative, réseau et persistée. Tout calcul produit un champ quantifié par arrondi déterministe (C-16).
- R-211 : la mémoire d'un champ est allouée **à la première déformation**, jamais au spawn. Une assembly intacte ne consomme aucune mémoire de champ (exigence de performance 7).
- R-212 : taille maximale d'un lattice : 16×16×16 = 4096 nœuds ; taille maximale cumulée des champs d'une assembly : `deformation.max_field_bytes_per_assembly` (défaut 256 KiB).
- R-213 : les résidus par sommet (`HAS_RESIDUAL`) n'existent qu'aux niveaux `Q-3` et `Q-4`, sont plafonnés par assembly (`deformation.max_residual_vertices`, défaut 4096) et ne sont **jamais** transmis sur le réseau (INV-15) : ils sont reconstruits localement à partir du champ et des impacts.

## 3.13 DM-13 : Impact

```rust
#[repr(C)]
pub struct ImpactDesc {
    pub assembly:      AssemblyHandle,
    pub other:         AssemblyHandle,   // {0,0} si monde ou entité vanilla
    pub part:          u16, pub zone: u16, pub region: u16, pub _pad: u16,
    pub point_local:   [f32;3],   // espace de part, quantifié 1/1024 m
    pub normal_local:  [f32;3],   // quantifié i8 par composante à la sérialisation
    pub energy:        f32,       // J effectivement absorbés
    pub normal_energy: f32,       // J composante normale
    pub shear_energy:  f32,       // J composante tangentielle
    pub effective_mass:f32,       // kg
    pub contact_area:  f32,       // m²
    pub rel_speed:     f32,       // m/s
    pub material_self: u16, pub material_other: u16,
    pub source:        u8,        // CONTACT | EXPLOSION | PROJECTILE | FIRE | API | FALL | CRUSH
    pub flags:         u8,        // SHARP, BLUNT, THERMAL, CONTINUOUS
    pub seq:           u32,       // numéro de séquence déterministe par assembly
}
```

- R-220 : l'`ImpactDesc` est l'**unique** entrée du système de dommage. Les dégâts d'origine Minecraft (explosion, projectile, feu, chute) sont convertis en `ImpactDesc` par C-50 avant d'atteindre C-35 (exigence 10).
- R-221 : `seq` est monotone par assembly et sert à l'ordre déterministe de reconstruction côté client.

## 3.14 DM-14 : Intégrité structurelle

```rust
/// Graphe structurel : les sommets sont des parts, les arêtes des liaisons.
#[repr(C)]
pub struct StructuralLinkDesc {
    pub name_hash:  u64,
    pub part_a:     u16, pub part_b: u16,
    pub kind:       u8,        // WELD | BOLT | HINGE | GLUE | ORGANIC | SNAP
    pub flags:      u8,        // LOAD_BEARING, SEVERABLE, REFORMABLE
    pub _pad:       u16,
    pub capacity:   f32,       // J avant rupture
    pub tensile:    f32,       // N avant rupture en traction
    pub shear:      f32,       // N avant rupture en cisaillement
    pub torque:     f32,       // N.m avant rupture en torsion
    pub anchor_local: [f32;3], // point d'application
    pub joint:      u32,       // joint physique associé, u32::MAX = aucun
    pub propagation: f32,      // [0,1] fraction d'énergie transmise à travers la liaison
}

#[repr(C)]
pub struct StructuralLinkState { pub integrity: f32, pub absorbed: f32, pub flags: u32 }
```

## 3.15 DM-15 : Surface, usure et décalques

```rust
#[repr(C)]
pub struct WearProfileDesc {
    pub name_hash: u64,
    pub scratch_gain: f32, pub soil_gain: f32, pub burn_gain: f32, pub rust_gain: f32,
    pub scratch_decay: f32, pub soil_decay: f32,     // par minute de jeu
    pub decal_atlas: u16, pub _pad: u16,
}

/// État d'usure : 4 canaux quantifiés par zone de surface (au plus 32 zones par part).
#[repr(C)]
pub struct WearState { pub scratch: u8, pub soil: u8, pub burn: u8, pub rust: u8 }

#[repr(C)]
pub struct DecalInstance {
    pub center_local: [f32;3], pub radius: f32,
    pub rotation:  f32,          // rotation autour de la normale
    pub normal:    [i8;4],
    pub atlas_index: u16,        // index dans l'atlas de décalques
    pub part:      u16,
    pub intensity: u8, pub kind: u8,   // SCRATCH | DENT_RING | BURN | DIRT | CRACK | PAINT
    pub _pad:      u16,
}
```

- R-230 : le nombre de décalques par assembly est plafonné (`render.max_decals_per_assembly`, défaut 64) avec éviction du plus ancien et du moins intense.
- R-231 : l'usure est **quantifiée sur 8 bits par canal** et n'est resynchronisée qu'au franchissement d'un seuil de 1/16, ce qui borne son coût réseau.

## 3.16 DM-16 : Particules (tissu, cordes, câbles, filets, corps souples)

```rust
#[repr(C)]
pub struct ParticleSetDesc {
    pub name_hash:  u64,
    pub kind:       u8,      // CLOTH | ROPE | CABLE | NET | SOFT
    pub authority:  u8,      // VISUAL | SERVER_SIMPLE | SERVER_FULL
    pub quality:    u8,      // niveau minimal requis
    pub flags:      u8,      // SELF_COLLIDE, TEARABLE, TWO_SIDED, WIND_AFFECTED
    pub particle_count: u32,
    pub constraint_offset: u32, pub constraint_count: u32,
    pub anchor_offset: u32,  pub anchor_count: u32,
    pub mass_per_particle: f32,
    pub stiffness: f32, pub bending: f32, pub damping: f32,
    pub tear_threshold: f32, pub wind_influence: f32,
    pub max_distance: f32,
    pub collision_proxy_offset: u32, pub collision_proxy_count: u32,
    pub render_mesh: u32,    // mesh piloté, u32::MAX = géométrie générée (corde)
}
```

## 3.17 DM-17 : Attache entre assemblies

```rust
#[repr(C)]
pub struct AttachmentDesc {
    pub kind:      u8,    // RIGID | HITCH | ROPE | WINCH | MAGNET | SEATED
    pub flags:     u8,    // AUTO_DETACH_ON_BREAK, TRANSFER_DAMAGE, TRANSFER_POWER
    pub _pad:      u16,
    pub a_assembly: AssemblyHandle, pub a_socket: u64,
    pub b_assembly: AssemblyHandle, pub b_socket: u64,
    pub max_force: f32, pub max_torque: f32, pub max_length: f32,
    pub joint:     JointHandle,
}
```

## 3.18 DM-18 : Niveaux de qualité et budgets

```rust
#[repr(u8)]
pub enum QualityLevel { Off = 0, Low = 1, Medium = 2, High = 3, Ultra = 4 }

#[repr(C)]
pub struct QualityProfile {
    pub deformation: u8, pub particles: u8, pub shadows: u8, pub decals: u8,
    pub occlusion:   u8, pub lighting:  u8, pub skinning: u8, pub collider_refit: u8,
    pub reflections: u8, pub parallax:  u8, pub _pad: [u8;6],
}

#[repr(C)]
pub struct Budgets {
    pub sim_ns_per_tick:        u64,
    pub damage_ns_per_tick:     u64,
    pub deformation_ns_per_tick:u64,
    pub particles_ns_per_tick:  u64,
    pub render_prep_ns:         u64,
    pub occlusion_ns:           u64,
    pub asset_ns_per_tick:      u64,
    pub native_mem_bytes:       u64,
    pub deform_mem_bytes:       u64,
    pub gpu_mem_bytes:          u64,
    pub max_active_bodies:      u32,
    pub max_deformed_assemblies:u32,
    pub max_visible_instances:  u32,
    pub max_triangles_frame:    u32,
    pub max_collider_refits_per_tick: u32,
    pub max_decals_frame:       u32,
    pub max_shadow_instances:   u32,
}
```

- R-240 : ces valeurs sont des **budgets de conception configurables**, jamais présentés comme des mesures.
- R-241 : chaque budget a une stratégie de dégradation (PARTIE 25.6), une métrique de dépassement et un niveau de qualité associé.

## 3.19 DM-19 : codes d'erreur

```text
-1000..-1099   bootstrap, natif, ABI
-2000..-2099   runtime, handles, simulation
-3000..-3099   assets, validation, compilation
-4000..-4099   rendu, GPU
-5000..-5099   réseau
-6000..-6099   persistance
-7000..-7099   API publique / definitions
-8000..-8099   déformation, structure, dommage
```

## 3.20 Invariants globaux

| ID | Invariant | Vérification |
|---|---|---|
| INV-01 | Aucun littéral de nom de mod, de véhicule ou de contenu dans le moteur | T-006 |
| INV-02 | Le client ne modifie jamais l'état autoritatif (physique, plastique, structurel) | T-417, T-442, T-826 |
| INV-03 | Aucune API Minecraft mutante hors du thread autoritatif | T-011 |
| INV-04 | Moins de 32 traversées FFI par tick en régime établi | T-012 |
| INV-05 | Aucune panic Rust ne traverse la frontière | T-013 |
| INV-06 | Aucun système d'AXION ne référence RUSTFORGE-X hors de C-76 | T-014 |
| INV-07 | Aucun thread worker Rust n'attache de JNIEnv | T-015 |
| INV-08 | Toute allocation native est comptée dans un budget | T-016 |
| INV-09 | Tout handle libéré est invalidé | T-017 |
| INV-10 | Aucune donnée AXION dans les fichiers de région du monde | T-018 |
| INV-11 | Le retrait du mod laisse le monde chargeable | T-435, T-713 |
| INV-12 | Aucun appel OpenGL hors du render thread | T-019 |
| INV-13 | Aucun collider de body dynamique n'est un trimesh, y compris après déformation | T-855 |
| INV-14 | Le noyau déterministe produit un résultat bit-identique client/serveur **sur toute configuration appartenant à la matrice de validation déterministe** (5.12bis) ; hors matrice, le mode de réplication bascule sur `SNAPSHOT` | T-820, T-820b |
| INV-15 | Aucune donnée de déformation par sommet n'est transmise sur le réseau | T-825 |
| INV-16 | Une assembly intacte n'alloue aucune mémoire de champ, n'exécute aucun pas de simulation de déformation et n'emprunte aucune variante de shader déformée (voir 14.11bis pour la portée exacte) | T-808 |
| INV-17 | Le client ne crée jamais de déformation plastique de sa propre initiative | T-826 |
| INV-18 | Toute déformation appliquée est bornée par `max_disp` et par le budget mémoire | T-809 |
| INV-19 | Aucun sous-système n'existe sans budget déclaré et mesuré | T-007 (audit statique du registre de budgets) |

---

# PARTIE 4 : FRONTIÈRE JAVA/RUST, FFI ET ABI

## 4.1 Répartition des responsabilités

| Domaine | Java | Rust | Justification |
|---|---|---|---|
| Cycle de vie Forge, registries, events | ✔ | ✘ | API Forge accessible depuis la JVM seulement |
| Entités, blocs, items, capabilities | ✔ | ✘ | idem |
| Networking (canaux, paquets) | transport | sérialisation dense des payloads | Netty est Java ; l'encodage compact est mieux en Rust |
| Persistance NBT | conteneur | sérialisation des blobs (champ, structure, usure) | idem |
| Commandes, permissions, chat | ✔ | ✘ | API Minecraft |
| Appels OpenGL, contexte GL | ✔ | ✘ | contexte détenu par la JVM (ADR-006) |
| Chargement de ressources | ✔ | ✘ | `ResourceManager` est Java |
| Import et compilation d'assets | ✘ | ✔ | parsing et géométrie lourds |
| LOD, tangentes, cache de sommets, décomposition convexe | ✘ | ✔ | calcul massif |
| Compilation des régions de déformation et du graphe structurel | ✘ | ✔ | géométrie et graphes |
| Scene graph, transforms | ✘ | ✔ | SoA, SIMD |
| Physique, collisions, joints, véhicules | ✘ | ✔ | cœur du besoin |
| Solveur d'impacts, dommage, déformation, structure, rupture | ✘ | ✔ | cœur du besoin, calcul intensif |
| Refit de collider | ✘ | ✔ | géométrie |
| Particules (tissu, cordes, soft) | ✘ | ✔ | itératif, parallélisable |
| Animation, blending, palettes | ✘ | ✔ | calcul massif |
| Culling, occlusion logicielle, LOD, tri | ✘ | ✔ | calcul massif par frame |
| Construction des buffers de rendu (contenu) | ✘ | ✔ | remplissage direct de mémoire mappée |
| Soumission des draw calls, gestion des ressources GL | ✔ | ✘ | contexte GL |
| Décision de dégradation et niveaux de qualité | ✘ | ✔ (décision) / ✔ Java (application côté rendu) | à côté des mesures |

Règle de frontière (R-250) : **la donnée traverse au plus une fois par tick et par direction, sous forme de lot.**

## 4.2 ADR-004 : mécanisme d'interopérabilité

| Option | Avantages | Inconvénients | Verdict |
|---|---|---|---|
| JNI + `DirectByteBuffer` | disponible sur Java 17, zéro copie côté natif, un appel par lot | durée de vie manuelle | **retenu** |
| JNI paramètres primitifs | simple | un appel par élément | retenu pour le contrôle seulement |
| Panama / FFM | ergonomie | preview sur Java 17 (H-01) | `FUTURE`, derrière `NativeBridge` |
| JNA / JNR | simplicité | surcoût par appel | rejeté |
| Processus séparé + IPC | isolation | latence incompatible | rejeté pour le runtime, retenu pour `axion-cli` |

```text
INTERDIT                                IMPOSÉ
1 appel FFI par body                    1 appel avec un buffer de N bodies
1 appel FFI par impact                  1 appel avec un buffer de N impacts
1 appel FFI par nœud de lattice         1 appel avec les pages de champ modifiées
1 appel FFI par instance rendue         1 appel avec un buffer d'instances
```

## 4.3 IF-01 : ABI, versionnement, contrôle

```c
#define AXION_ABI_VERSION 1u

int32_t axion_abi_version(void);
int32_t axion_init(const uint8_t* config_cbor, size_t len, uint64_t* out_ctx);
int32_t axion_shutdown(uint64_t ctx);
int32_t axion_last_error(uint64_t ctx, uint8_t* out_utf8, size_t cap, size_t* out_len);
int32_t axion_set_quality(uint64_t ctx, const AxionQualityProfile* q);
```

- R-260 : `axion_abi_version()` appelée avant toute autre fonction ; écart = `DISABLED` (`E-1002`).
- R-261 : ABI versionnée indépendamment du produit ; toute rupture incrémente `AXION_ABI_VERSION`.
- R-262 : aucune structure `repr(Rust)` ne traverse ; uniquement `repr(C)`, entiers, ou tampons à schéma versionné.
- R-263 : chaînes UTF-8 avec longueur explicite ; aucun `char*` terminé par zéro.
- R-264 : aucune fonction ne renvoie de pointeur brut sans code d'état.
- R-265 : tous les symboles exportés portent le préfixe `axion_` (PARTIE 27.3).

## 4.4 IF-02 : mémoire partagée et anneaux de transfert

```c
typedef struct { void* ptr; uint64_t capacity; uint32_t kind; uint32_t generation; } AxionBufferInfo;

int32_t axion_buffer_acquire(uint64_t ctx, uint32_t kind, uint64_t min_capacity, AxionBufferInfo* out);
int32_t axion_buffer_release(uint64_t ctx, uint32_t kind, uint32_t generation);
```

```text
AXION_BUF_SIM_IN         commandes de simulation Java -> Rust
AXION_BUF_SIM_OUT        états de bodies Rust -> Java
AXION_BUF_EVENTS         événements physiques, dommage, rupture, détachement
AXION_BUF_IMPACT_IN      impacts d'origine Minecraft (explosion, projectile, feu, API)
AXION_BUF_DEFORM_OUT     pages de champ de déformation modifiées (client : upload GPU)
AXION_BUF_DEFORM_NET     paquets de déformation sérialisés (serveur -> réseau)
AXION_BUF_RENDER_OUT     instances visibles, matrices, palettes, décalques, LOD
AXION_BUF_SHADOW_OUT     instances de la passe d'ombre
AXION_BUF_ASSET_IN/OUT   compilation d'assets
AXION_BUF_NET_OUT        payloads réseau sérialisés
AXION_BUF_PERSIST        blobs de persistance sérialisés
AXION_BUF_DEBUG          géométrie de debug
```

- R-270 : un tampon peut être réalloué entre deux ticks ; Java re-acquiert quand `generation` change ; usage d'une vue périmée = `E-2002`.
- R-271 : little-endian imposé ; en-tête de 32 octets (magic `AXNB`, kind, generation, schema_version, payload_len, element_count, crc32c optionnel).

## 4.5 IF-03 : cycle de simulation

```c
int32_t axion_sim_submit(uint64_t ctx, uint64_t tick, uint32_t command_count, uint32_t impact_count);
int32_t axion_sim_collect(uint64_t ctx, uint64_t deadline_ns,
                          AxionCollectResult* out);
int32_t axion_sim_cancel(uint64_t ctx);
```

```c
typedef struct {
    uint32_t state_count;      /* BodyState[] */
    uint32_t event_count;      /* PhysicsEvent[] + DamageEvent[] */
    uint32_t deform_page_count;/* pages de champ modifiées */
    uint32_t refit_count;      /* colliders refités ce tick */
    uint32_t detach_count;     /* détachements décidés */
    uint32_t net_bytes;        /* octets prêts à émettre */
    uint32_t flags;            /* AXION_SIM_INCOMPLETE, AXION_SIM_DEGRADED */
} AxionCollectResult;
```

- R-280 : `submit` ne bloque pas au-delà de `budgets.submit_ns` (défaut 200 µs).
- R-281 : `collect` incomplet → Java conserve l'état précédent, extrapole, et le travail continue au tick suivant. Jamais d'abandon silencieux.
- R-282 : `submit` sans `collect` (crash tiers) → le prochain `submit` clôt implicitement le cycle, incrémente `axion.sim.unbalanced`.
- R-283 : pas fixe `sim.fixed_dt` (défaut 1/60 s), `sim.max_substeps` (défaut 4), accumulateur clampé, aucune spirale de rattrapage.

## 4.6 IF-04 : déformation

```c
/* Applique un lot d'impacts et renvoie les pages de champ modifiées. */
int32_t axion_deform_apply(uint64_t ctx, uint32_t impact_count, uint32_t* out_page_count);

/* Reconstruction client : rejoue des impacts reçus du serveur, dans l'ordre de seq. */
int32_t axion_deform_replay(uint64_t ctx, uint32_t impact_count, uint32_t* out_page_count);

/* Empreinte d'un champ, pour détection de divergence. */
int32_t axion_deform_digest(uint64_t ctx, uint64_t assembly, uint64_t* out_digest);

/* Application d'un instantané autoritatif (resynchronisation). */
int32_t axion_deform_snapshot_apply(uint64_t ctx, uint64_t assembly, const uint8_t* blob, size_t len);

/* Extraction d'un instantané compact (réseau ou persistance). */
int32_t axion_deform_snapshot_write(uint64_t ctx, uint64_t assembly, uint32_t* out_len);

/* Réparation : restaure progressivement le champ vers zéro. */
int32_t axion_deform_repair(uint64_t ctx, uint64_t assembly, uint32_t part, float amount);
```

- R-290 : `axion_deform_apply` et `axion_deform_replay` utilisent **le même noyau** (C-16) et produisent des champs bit-identiques pour la même séquence d'impacts, dans la portée définie en 5.12bis. Hors de cette portée, le mode de réplication `SNAPSHOT` s'applique et la correction est préservée (INV-14).
- R-291 : `axion_deform_digest` renvoie un hachage 64 bits du champ plastique quantifié de toutes les régions de l'assembly, calculé dans un ordre fixe.

## 4.7 IF-05 : cycle de rendu

```c
int32_t axion_render_prepare(uint64_t ctx, const AxionCameraDesc* cam, float partial_tick,
                             uint64_t budget_ns, AxionRenderResult* out);
int32_t axion_render_release(uint64_t ctx, uint32_t frame_id);
int32_t axion_occlusion_build(uint64_t ctx, const AxionOccluderBatch* occluders, uint64_t budget_ns);
int32_t axion_shadow_prepare(uint64_t ctx, const AxionShadowDesc* d, AxionRenderResult* out);
```

```c
typedef struct {
    uint32_t instance_count; uint32_t draw_group_count;
    uint32_t palette_bytes;  uint32_t deform_page_count;
    uint32_t decal_count;    uint32_t triangle_estimate;
    uint32_t frame_id;       uint32_t flags;   /* AXION_RENDER_DEGRADED */
} AxionRenderResult;
```

- R-300 : `axion_render_prepare` ne bloque pas au-delà de `budgets.render_prep_ns` ; au-delà elle renvoie un résultat partiel avec `AXION_RENDER_DEGRADED`.
- R-301 : le contenu des tampons de rendu est valide jusqu'à `axion_render_release(frame_id)`.

## 4.8 IF-06 : assets, particules, structure

```c
int32_t axion_asset_compile(uint64_t ctx, uint64_t asset_id, uint32_t source_format,
                            uint64_t source_len, const uint8_t* options_cbor, size_t options_len,
                            uint32_t* out_job_id);
int32_t axion_asset_poll(uint64_t ctx, uint32_t job_id, uint32_t* out_status,
                         uint64_t* out_size, int32_t* out_error);
int32_t axion_asset_load(uint64_t ctx, uint64_t asset_id, uint32_t sections_mask, AssetHandle* out);
int32_t axion_asset_unload(uint64_t ctx, AssetHandle h);

int32_t axion_particles_step(uint64_t ctx, float dt, uint64_t budget_ns, uint32_t* out_updated);
int32_t axion_structure_query(uint64_t ctx, uint64_t assembly, uint32_t* out_link_count);
int32_t axion_persist_write(uint64_t ctx, uint64_t assembly, uint32_t* out_len);
int32_t axion_persist_read(uint64_t ctx, uint64_t assembly, const uint8_t* blob, size_t len);
```

La compilation est asynchrone sur `axion-assets` ; Java sonde. Aucun rappel Rust → Java (INV-07).

## 4.9 IF-07 : erreurs et panics

- R-310 : chaque fonction exportée est enveloppée dans `catch_unwind`. Une panic capturée : journalisée avec backtrace, renvoie `E-2000`, incrémente `axion.native.panics`, et met le contexte en `POISONED` si l'invariant interne n'est pas restaurable.
- R-311 : un contexte `POISONED` refuse tout appel sauf `axion_shutdown` et `axion_last_error` ; Java bascule en `DISABLED`.
- R-312 : `ax-ffi` est compilé avec `panic = "unwind"` ; `panic = "abort"` est interdit.
- R-313 : aucune fonction FFI n'alloue côté Java ni n'appelle une méthode Java.

## 4.10 Propriété et durée de vie

```text
Rust possède : tampons de transfert, assets compilés, monde physique, scene graphs,
               squelettes, champs de déformation, graphes structurels, ensembles
               de particules, états d'usure
Java possède : objets Minecraft, ressources GPU, entités ; il ne détient que des
               vues (DirectByteBuffer) sur la mémoire native
```

- R-320 : Java ne libère jamais la mémoire d'un tampon direct fourni par Rust.
- R-321 : toute ressource native créée par Java est libérée explicitement ; un `Cleaner` de secours journalise `E-2003` et compte la fuite.
- R-322 : à `axion_shutdown`, bilan allocations/libérations journalisé ; un bilan non nul fait échouer T-016.

## 4.11 Calibration FFI

- R-330 : au démarrage, C-15 mesure l'aller-retour FFI vide sur 10 000 itérations et enregistre la médiane dans `axion.ffi.roundtrip_ns`. Cette valeur dimensionne les lots et est **mesurée**, jamais supposée (H-07).

---
# PARTIE 5 : SPÉCIFICATIONS COMPOSANT PAR COMPOSANT

Format de fiche : **Purpose / Inputs / Outputs / State / Dependencies / API / Algorithm / Failure modes / Fallback / Budget / Metrics / Tests / Acceptance**. Les fiches courtes omettent les rubriques sans objet. Les composants dont la spécification complète occupe une partie dédiée renvoient à celle-ci.

## 5.1 C-01 : Forge Integration

**Purpose.** Unique point d'ancrage entre Forge et AXION ; isole tout le reste des API Forge (P-12).

**Inputs.** `FMLConstructModEvent`, `FMLCommonSetupEvent`, `FMLClientSetupEvent`, `FMLLoadCompleteEvent`, `AddReloadListenerEvent`, `RegisterCommandsEvent`, `ServerStarting/Stopping/StoppedEvent`, `TickEvent.Server/ClientTickEvent`, `LevelEvent.Load/Unload`, `EntityJoinLevelEvent`, `RenderLevelStageEvent`, `LivingDamageEvent`, `ExplosionEvent`, `BlockEvent`, `RegisterClientReloadListenersEvent`.

**State.** `UNLOADED -> CONSTRUCTED -> SETUP -> LOAD_COMPLETE -> RUNNING_(CLIENT|SERVER) -> STOPPING -> UNLOADED`

**API.**

```java
public interface PlatformAdapter {                    // IF-10
    String  platformName();          // "forge-47"
    String  minecraftVersion();
    boolean isModLoaded(String modid);
    boolean isAuthoritativeThread();
    boolean isClient();
    long    currentTick();
    void    runOnAuthoritativeThread(Runnable r);
    Path    gameDir(); Path configDir();
}
```

**Algorithm.** Abonnement `HIGHEST` sur PRE, `LOWEST` sur POST ; chaque hook enveloppé d'un `try/catch` qui ne relance jamais ; désactivation d'un hook après 5 échecs consécutifs (`E-1010`, mode `SAFE`).

- R-400 : AXION n'annule aucun événement Forge et ne modifie le résultat d'aucun événement d'un autre mod.
- R-401 : aucune classe hors de `dev.axion.forge` n'importe `net.minecraftforge.*` (T-020).
- R-402 : les événements de dommage vanilla (`LivingDamageEvent`, `ExplosionEvent.Detonate`, dégâts de projectile, feu, chute) sont **observés** et convertis en `ImpactDesc` par C-50 lorsqu'ils concernent une `AxionEntity`. Cette conversion est déclarative (PARTIE 13.4) et n'altère jamais le comportement vanilla pour les autres entités.

**Budget.** `budgets.idle_hook_ns` (défaut 50 µs par tick, aucune assembly présente).

**Failure modes.** FM-01 Forge hors plage → `DISABLED` (`E-1001`) ; FM-02 exception dans un hook → compteur puis désactivation ; FM-03 PRE sans POST → fermeture forcée de la fenêtre.

**Tests.** T-100..T-103, T-020.

**Acceptance.** JAR chargé sur Forge 47.x client et serveur ; surcoût des hooks à vide sous budget.

## 5.2 C-02 : Bootstrap

**State.** `INIT -> CONFIG -> LOAD_NATIVE -> HANDSHAKE -> PROBE -> READY | DEGRADED | DISABLED`

**Algorithm.**

```text
1. configuration (fichier + -Daxion.*)          2. enabled == false -> DISABLED
3. détection de plateforme                       4. extraction du natif (C-03)
5. chargement                                    6. handshake ABI, sinon DISABLED
7. axion_init(config_cbor) -> ctx                8. calibration FFI + capacités GPU (client)
9. acquisition des tampons                       10. profil de qualité initial (C-77) -> READY
échec 3..9 : journaliser, DISABLED, jamais d'exception non capturée
```

- R-410 : en `DISABLED`, les entités AXION sont chargées **inertes** : aucune physique, aucun rendu, **aucune modification de leur NBT**. Elles retrouvent leur état exact au prochain démarrage réussi (INV-11).

**Tests.** T-110..T-114.

## 5.3 C-03 : Native Loader

```text
resource = "/natives/<os>-<arch>/<libname>"
expected = SHA-256 lu depuis "/natives/<os>-<arch>/<libname>.sha256"
target   = <gameDir>/axion/native/<expected>/<libname>
extraction atomique si absent ; System.load(target)
```

- R-420 : vérification SHA-256 obligatoire (`E-1003`) ; chemin versionné par hash ; `libname` unique au projet (`axion_native`) ; `System.load` sur chemin absolu, jamais `System.loadLibrary` (isolation vis-à-vis de `java.library.path` et des autres mods natifs).
- R-421 : système de fichiers `noexec` ou en lecture seule → tentative dans `java.io.tmpdir`, puis `DISABLED` propre.

**Tests.** T-120..T-124.

## 5.4 C-04 : Configuration

```text
défauts compilés (ax-model/config_defaults.rs)
  -> <configDir>/axion-common.toml
  -> <configDir>/axion-client.toml | axion-server.toml
  -> propriétés -Daxion.<chemin>=<valeur>
  -> validation (type, plage, défaut) -> CBOR -> axion_init
```

- R-430 : toute option a défaut, plage, description, et est documentée dans `CONFIGURATION.md` **généré** depuis la source unique ; une option non documentée casse le build (T-021).
- R-431 : les options `hot = true` sont rechargeables par `/axion config reload`.
- R-432 : les options de simulation devant être cohérentes sont **imposées par le serveur** au handshake.

**Tests.** T-130..T-133, T-021.

## 5.5 C-05 : Diagnostics & Logging

- R-440 : AXION n'ouvre **jamais** de connexion réseau sortante et ne transmet aucune télémétrie (T-022).
- R-441 : journal natif dans `<gameDir>/axion/logs/`, rotation 10 × 8 MiB.
- R-442 : `/axion diag dump` produit une archive locale : configuration effective, capacités matérielles et GPU, versions, métriques, 500 dernières erreurs, assets chargés, niveaux de qualité actifs et leurs causes, mods installés (identifiants et versions). Aucune donnée de monde, de chat ou de joueur.
- R-443 : tout `E-xxxx` est documenté en ANNEXE A.1 (T-023).

**Tests.** T-140, T-141, T-022, T-023.

## 5.6 C-10 : Native Core

```rust
pub struct Context {
    pub config: Config, pub quality: QualityProfile, pub state: RuntimeState,
    pub assets: HandleTable<Asset>, pub assemblies: HandleTable<Assembly>,
    pub bodies: HandleTable<BodyRef>, pub regions: HandleTable<DeformRegion>,
    pub particles: HandleTable<ParticleSet>, pub attachments: HandleTable<Attachment>,
    pub buffers: BufferPool, pub telemetry: Telemetry, pub jobs: JobSystem,
    pub deform_arena: DeformArena,
}
```

- R-450 : slots réutilisés avec incrément de génération ; contexte unique par processus (`E-1004` sur double init) ; aucun pointeur brut exposé.

**Tests.** T-150..T-152.

## 5.7 C-11 : Math

**Décision.** `glam` (MIT/Apache-2.0) : `Vec3`, `Vec3A`, `Quat`, `Mat4`, `Affine3A`, SIMD.

- R-460 : aucun crate d'AXION ne définit son propre type vecteur ou matrice.
- R-461 : main droite, Y haut, matrices colonne-major, quaternions `(x,y,z,w)`.
- R-462 : positions monde en `DVec3` ; simulation locale d'une assembly en `f32` **relativement à une origine flottante** rebasée au-delà de 1024 blocs.

**Tests.** T-160..T-162.

## 5.8 C-12 : Job System

**Décision.** Pool dédié au-dessus de `rayon`, jamais le pool global.

- R-470 : `ThreadPoolBuilder::build_global()` est **interdit** (PARTIE 27.4).
- R-471 : `n = clamp(available_parallelism() - 2, 1, config.max_workers)` ; défaut `min(4, cores-2)` client, `min(8, cores-2)` serveur.
- R-472 : tout job est annulable et porte une deadline ; un job en dépassement est marqué, jamais tué ; son résultat est utilisé au cycle suivant.
- R-473 : granularité adaptative visant 100 µs par tâche, mesurée.
- R-474 : les jobs sont typés (`PHYSICS`, `DAMAGE`, `DEFORM`, `PARTICLES`, `ANIM`, `CULL`, `OCCLUSION`, `ASSET`) et chaque type porte son propre budget et sa propre métrique (INV-19).

**Tests.** T-170..T-173.

## 5.9 C-13 : Memory / Arenas

```text
PERSISTENT   assets, monde physique, graphes structurels
DEFORM       champs de déformation (arène dédiée, budget propre, défragmentable)
FRAME        rendu, remis à zéro par frame
SCRATCH      par job, remis à zéro en fin de job
```

- R-480 : aucune allocation native n'échappe au comptage (INV-08).
- R-481 : l'arène `DEFORM` est un allocateur de blocs de taille fixe (pages de 1 KiB) avec liste libre ; le dépassement de `budgets.deform_mem_bytes` déclenche : (1) compactage des champs saturés vers une résolution inférieure, (2) éviction des champs des assemblies les plus éloignées et les moins récemment vues, (3) refus de nouvelles déformations avec `E-8001` et métrique.
- R-482 : bilan non nul à l'arrêt = échec de test (T-016).

**Tests.** T-180, T-181, T-016, T-809.

## 5.10 C-14 : FFI Bridge

- R-490 : chaque fonction exportée : `#[no_mangle] extern "C"`, `catch_unwind`, validation de tous les arguments, exécution, conversion `Result -> i32`. Moins de 50 lignes ; la logique est dans les crates métier.
- R-491 : toute lecture d'un tampon fourni par Java valide `magic`, `generation`, `schema_version`, `payload_len` (`E-2002`).
- R-492 : côté Java, une classe unique `NativeBridge` déclare toutes les méthodes `native`.

**Tests.** T-012, T-013, T-190, T-191.

## 5.11 C-15 : Telemetry natif

- R-500 : chaque composant expose durée, opérations, erreurs, mémoire. Chaque **budget** de DM-18 a une métrique de consommation et une métrique de dépassement (INV-19).
- R-501 : le coût de la télémétrie reste sous 1 % du composant mesuré (B-08).
- R-502 : export JSON par `/axion metrics export`, inclus au dump de diagnostic.

**Tests.** T-200, T-201, T-007, B-08.

## 5.12 C-16 : Noyau déterministe

**Purpose.** Fournir l'ensemble des opérations **garanties bit-identiques** entre client et serveur **sur les configurations de la matrice de validation déterministe** (5.12bis). C'est la brique qui permet de répliquer la déformation par événements plutôt que par données (P-15, INV-14). Hors matrice, la réplication bascule sur `SNAPSHOT` : la correction est préservée, seul le mécanisme change.

**Contenu.**

```rust
pub mod det {
    /// Opérations autorisées : + - * / sqrt (IEEE-754, exactes et spécifiées).
    /// Toute autre fonction est fournie ici sous forme d'approximation
    /// polynomiale déterministe, compilée sans fast-math ni FMA implicite.
    pub fn falloff(x: f32) -> f32;           // (1-x²)², polynôme exact
    pub fn smooth01(x: f32) -> f32;          // 3x²-2x³
    pub fn inv_sqrt(x: f32) -> f32;          // 1.0 / sqrt(x), pas d'approximation rapide
    pub fn quantize_i8(v: f32, step: f32) -> i8;  // arrondi demi-loin-de-zéro
    pub fn clamp(v: f32, lo: f32, hi: f32) -> f32;
    pub fn digest64(bytes: &[u8]) -> u64;    // xxHash64, implémentation figée
    pub struct DetRng(u64);                  // PCG32, graine explicite
}
```

- R-510 : les crates utilisant `det` sont compilés sans `fast-math`, avec contraction FMA désactivée (`-C llvm-args=-fp-contract=off` et `-C target-feature=-fma` sur les cibles concernées). Aucune fonction transcendante de la bibliothèque standard (`sin`, `cos`, `exp`, `powf`, `cbrt`) n'est utilisée dans un chemin déterministe ; seules `+ - * / sqrt` et les fonctions de `det` sont autorisées.
- R-511 : les opérations SIMD dans un chemin déterministe utilisent des largeurs de vecteur **fixées à la compilation** (aucun dispatch runtime sur les capacités du CPU) et un ordre de réduction fixé.
- R-512 : `DetRng` est une PCG32 à graine explicite dérivée de `(assembly_uuid, impact.seq)` ; aucun appel à un générateur global.
- R-513 : le noyau est couvert par des **vecteurs d'or** : 10 000 cas d'entrée/sortie versionnés, rejoués sur chaque configuration de la matrice de validation (T-820). Une divergence sur une configuration de la matrice est un défaut **bloquant**.

### 5.12bis Portée de la garantie déterministe

**Énoncé normatif.** Le noyau `det` produit un résultat **bit-identique** entre
client et serveur **pour les triplets cible, chaînes de compilation et jeux
d'instructions figurant dans la matrice de validation déterministe et validés
par les vecteurs d'or de la version courante**. Aucune garantie bit-identique
n'est donnée hors de cette matrice.

**Matrice de validation déterministe (V1.0).**

```text
| cible                  | chaîne          | jeu d'instructions imposé |
|------------------------|-----------------|---------------------------|
| x86_64-pc-windows-msvc | rustc épinglé   | SSE2 (baseline), FMA off  |
| x86_64-unknown-linux-gnu | rustc épinglé | SSE2 (baseline), FMA off  |
| aarch64-apple-darwin   | rustc épinglé   | NEON baseline, FMA off    |
| x86_64-apple-darwin    | rustc épinglé   | SSE2 (baseline), FMA off  |
| aarch64-unknown-linux-gnu | rustc épinglé| NEON baseline, FMA off    |
La version exacte de rustc est celle de rust-toolchain.toml. Un changement de
version majeure de rustc exige de rejouer et, si nécessaire, de régénérer les
vecteurs d'or, avec incrément de DET_KERNEL_VERSION.
```

**Identification à l'exécution.** Le natif expose une **empreinte de
configuration déterministe** `det_profile` = hash de (triplet cible,
`DET_KERNEL_VERSION`, jeu d'instructions effectivement compilé, drapeaux de
virgule flottante). Elle est échangée au handshake (PARTIE 21.3).

**Comportement hors matrice (normatif).**

```text
1. si det_profile du client == det_profile du serveur
      -> mode de réplication RECONSTRUCT (défaut) : impacts rejoués localement
2. si det_profile diffère, ou si l'une des deux extrémités signale une
   configuration absente de la matrice
      -> mode de réplication SNAPSHOT pour toutes les assemblies concernées :
         le serveur envoie des instantanés de champ quantifiés au lieu de
         s'appuyer sur la reconstruction
      -> axion.det.mode = "snapshot", journalisé une fois par session avec la
         cause, et affiché par /axion status et /axion compat
      -> le budget réseau de déformation est relevé au plafond
         net.max_deform_snapshots_per_second_fallback (défaut 16)
3. dans les deux modes, l'empreinte de champ reste vérifiée périodiquement :
   le mode SNAPSHOT n'est pas une renonciation à la correction, seulement un
   changement de mécanisme de réplication
```

- R-514 : le mode `SNAPSHOT` **ne dégrade aucune fonctionnalité** : la
  déformation, la structure, la persistance et le rendu sont identiques ; seul
  le coût réseau augmente, ce qui est mesuré (B-23) et budgété.
- R-515 : AXION ne **refuse jamais** de fonctionner sur une plateforme hors
  matrice. Il bascule en `SNAPSHOT` et le signale. Le refus de support est
  réservé à l'absence de binaire natif (mode `DISABLED`, R-410).
- R-516 : l'ajout d'une configuration à la matrice exige : compilation, exécution
  complète des vecteurs d'or, et archivage du résultat. Une configuration non
  validée n'est jamais déclarée déterministe, même si elle passe en pratique.
- R-517 : les termes « déterministe » et « bit-identique » employés dans ce
  document renvoient **toujours** à cette portée. Aucun passage ne DOIT affirmer
  une garantie universelle.

**Fallback (deux niveaux).**
1. *Préventif* : si les `det_profile` des deux extrémités diffèrent, ou si l'une
   est hors matrice, le mode de réplication est `SNAPSHOT` dès le handshake
   (5.12bis). Aucune divergence n'a alors l'occasion de se produire.
2. *Curatif* : si, en mode `RECONSTRUCT`, une empreinte de champ diverge malgré
   tout, le serveur envoie un instantané autoritatif de la région concernée,
   incrémente `axion.det.divergences`, et bascule l'assembly en `SNAPSHOT` après
   `net.divergence_tolerance` divergences (défaut 3). Le jeu continue ; la cause
   est journalisée avec l'assembly, la région et les deux `det_profile`.

**Budget.** Aucun budget propre ; son coût est comptabilisé dans le sous-système appelant.

**Tests.** T-820 (vecteurs d'or sur **chaque** configuration de la matrice), T-820b (configuration hors matrice simulée → bascule `SNAPSHOT`, signalée, jeu correct), T-820c (`det_profile` différents entre client et serveur → `SNAPSHOT` dès le handshake, aucune divergence observée), T-820d (divergence injectée en mode `RECONSTRUCT` → instantané, puis bascule après le seuil), T-821 (même séquence d'impacts → même empreinte sur les configurations de la matrice), T-822 (résultat indépendant du nombre de workers).

**Acceptance.** Sur **chaque configuration de la matrice de validation**, la reconstruction client d'une séquence de 1000 impacts produit exactement le champ du serveur, sans instantané de resynchronisation. Sur une configuration hors matrice simulée, le mode `SNAPSHOT` s'active automatiquement, est signalé à l'utilisateur, et l'état final du champ est identique à celui du serveur.

## 5.13 C-20 : Asset Orchestrator

**State (SM-01, par asset).** `DISCOVERED -> QUEUED -> COMPILING -> COMPILED -> LOADED -> UNLOADED`, plus `CACHED` et `FAILED`.

```text
1. à AddReloadListenerEvent, énumérer :
     assets/<ns>/axion/models/**.{a3d,glb,gltf,obj,stl}
     data/<ns>/axion/definitions/**.json
     data/<ns>/axion/physics_materials/**.json
     data/<ns>/axion/collision_groups/**.json
     data/<ns>/axion/block_materials/**.json
     data/<ns>/axion/wear_profiles/**.json
     data/<ns>/axion/repair_rules/**.json
2. clé = sha256(contenu || options || COMPILER_VERSION)
3. cache présent et valide -> CACHED, sinon axion_asset_compile (asynchrone)
4. sondage à chaque tick, budget budgets.asset_ns_per_tick
5. un asset FAILED est journalisé une fois, remplacé par axion:builtin/missing,
   et n'empêche jamais le chargement du monde
```

- R-520 : rechargement de ressources → recompilation des seuls assets dont la clé a changé.
- R-521 : la compilation ne bloque jamais le thread autoritatif. Sur serveur dédié, barrière au `ServerStartingEvent` limitée à `assets.startup_timeout_s` (défaut 120 s) ; au-delà, les definitions concernées sont désactivées (`E-3001`).
- R-522 : un asset de secours `axion:builtin/missing` est embarqué et toujours disponible.

**Tests.** T-210..T-214.

## 5.14 C-21 : Importers

| Format | Extension | Statut | Données importées |
|---|---|---|---|
| glTF 2.0 binaire | `.glb` | **recommandé, complet** | meshes, matériaux, textures, hiérarchie, skins, animations, `extras` |
| glTF 2.0 JSON | `.gltf` | supporté, complet | idem |
| Wavefront OBJ | `.obj` (+ `.mtl`) | supporté, statique | meshes, UV, normales, matériaux basiques |
| STL | `.stl` | supporté, géométrie seule | triangles (colliders, formes simples) |

```text
glTF : parsing strict ; aucune extension inconnue interprétée ; aucun URI externe
       ni chemin remontant ; Y-up main droite conservé ; 1 unité glTF = 1 bloc ;
       une primitive = un mesh ; lecture des extras AXION (PARTIE 8) : colliders,
       sockets, roues, sièges, parts, régions de déformation, vertex groups de
       déformation, liaisons structurelles, matériaux physiques, LOD ; skins et
       animations (128 bones max)
```

- R-530 : extensions glTF supportées : `KHR_materials_emissive_strength`, `KHR_texture_transform`, `KHR_materials_unlit`, `KHR_materials_clearcoat`, `KHR_materials_sheen`, `KHR_materials_anisotropy`, `KHR_materials_ior`, `KHR_materials_specular`. Une extension listée dans `extensionsRequired` et non supportée provoque `E-3003` ; listée dans `extensionsUsed` seulement, elle est ignorée avec avertissement.
- R-531 : aucun chemin absolu, aucune sortie du répertoire de l'asset (`E-3002`).
- R-532 : images extraites mais **non décodées** par l'importer ; décodage par le `ResourceManager` (PNG uniquement, `E-3004` sinon).
- R-533 : fichier source au-delà de `assets.max_source_bytes` (défaut 128 MiB) refusé sans lecture complète (`E-3005`).

**Tests.** T-220..T-226.

## 5.15 C-22 : Asset Validator

```text
STRUCTURE   acyclique, ordre topologique, profondeur <= 32
LIMITES     nodes <= 4096, vertices <= 2e6, indices <= 6e6, bones <= 128,
            matériaux <= 256, textures <= 128, animations <= 128, parts <= 64,
            colliders <= 256, régions <= 32, liaisons structurelles <= 256
GÉOMÉTRIE   indices dans les bornes, pas de NaN/Inf, aire de triangle > 1e-9,
            AABB finie et <= 512 blocs
UV          finis ; NORMALES normalisables ; SKIN indices valides, poids normalisables
PHYSIQUE    densité > 0, masse dans [0.001, 1e6] kg, convexes 4..256 points,
            pas de trimesh sur body dynamique
DÉFORMATION résolution de lattice dans [2,16]³, OBB non dégénérée, épaisseur > 0,
            max_disp > 0 et <= 0.5 × plus petite dimension de l'OBB,
            chaque node DEFORMABLE appartient à exactement une région,
            poids de déformation dans [0,1], au moins un nœud ancré par région
            si le drapeau ANCHORED_BORDER est posé
STRUCTURE   graphe de parts acyclique, chaque liaison référence deux parts existantes,
            capacités > 0, aucune part orpheline non racine
NOMS        uniques par catégorie, ASCII imprimable, <= 64 octets
```

- R-540 : validation **stricte à la compilation** et **re-vérifiée au chargement** (défense en profondeur contre un cache altéré).
- R-541 : violation → erreur nommée et localisée (node, mesh, région, index).
- R-542 : réparations autorisées (liste fermée) : normale nulle recalculée, poids non normalisés renormalisés, triangle dégénéré supprimé, région sans ancrage → ancrage de la face la plus rigide, poids de déformation manquant → calculé par distance au bord de la région.

**Tests.** T-230..T-233, T-800 (validation de région), T-850 (validation de graphe structurel).

## 5.16 C-23 : Asset Optimizer

```text
1. fusion des vertices identiques (tolérances déclarées)
2. génération des normales manquantes (angle-weighted)
3. génération des tangentes (MikkTSpace) si normal map ou parallax
4. quantification vers le format Vertex canonique
5. optimisation du cache de sommets et de la localité (Forsyth)
6. génération de LOD (contraction d'arêtes, métrique quadrique, bords et coutures
   préservés) : LOD0 source, LOD1 ~50 %, LOD2 ~25 %, LOD3 ~10 %
7. AABB par mesh et par asset
8. décomposition convexe des colliders `auto_convex` (V-HACD), bornée
9. extraction des points d'enveloppe conservés pour le refit (<= 256 par collider)
10. remise à C-28 pour la compilation de déformation et de structure
11. sections A3D et compression zstd
```

- R-550 : la génération de LOD préserve la topologie de bord, ne produit jamais de mesh vide, et **conserve la correspondance sommet → région de déformation** : chaque sommet d'un LOD reçoit la région et le poids du sommet source le plus proche. Un LOD fourni par l'auteur remplace le LOD généré, mais doit alors porter ses propres attributs de région (sinon ils sont recalculés).
- R-551 : décomposition convexe bornée (`max_hulls` 32, `max_vertices_per_hull` 64, `max_decomp_ms` 5000) ; dépassement → enveloppe convexe globale, avertissement.
- R-552 : compression `zstd` niveau 9, sections décompressables indépendamment.
- R-553 : l'optimizer est **déterministe** : mêmes entrées et même version → même sortie bit à bit (T-243).

**Tests.** T-240..T-244, T-801 (LOD conserve les régions).

## 5.17 C-24 : Conteneur A3D

Spécifié en **PARTIE 7**.

**Tests.** T-250..T-253, T-590, T-591.

## 5.18 C-25 : Asset Cache

```text
clé = sha256(source || options || COMPILER_VERSION || ABI)
<gameDir>/axion/cache/<2 hex>/<clé>.a3d ; index.bin (magic, version, CRC, LRU)
```

- R-560 : magic, version de schéma et CRC32C sur chaque entrée ; entrée invalide supprimée et recompilée.
- R-561 : `assets.cache_max_bytes` (défaut 2 GiB), éviction LRU.
- R-562 : `COMPILER_VERSION` incrémentée à toute modification de C-21, C-22, C-23 ou C-28 affectant la sortie ; vérifié en CI.
- R-563 : le cache est hors du monde (INV-10).

**Tests.** T-260..T-263.

## 5.19 C-26 : Textures & Matériaux (client)

```text
- textures = ResourceLocation classiques chargées par le TextureManager (PNG)
- textures embarquées d'un GLB extraites à la compilation vers le cache et
  enregistrées comme DynamicTexture
- atlas AXION dédié pour les textures <= 256x256 ; au-delà, texture individuelle
- slots par matériau : albedo, normal, ORM, emissive, height, damage
- absent -> texture neutre 1x1 (albedo blanc, normal plat, ORM = (1, 1, 0),
  emissive noir, height 0.5, damage 0)
- atlas de décalques séparé, 2048x2048, 16x16 tuiles
```

- R-570 : taille maximale 4096×4096 (`E-3006`) ; mipmapping activé ; anisotropie selon le réglage vanilla.
- R-571 : AXION ne modifie ni n'enrichit l'atlas de blocs vanilla.

**Tests.** T-270..T-273, T-870 (atlas de décalques).

## 5.20 C-27 : Definitions data-driven

```text
data/<ns>/axion/definitions/<name>.json         objets, véhicules, personnages, machines
data/<ns>/axion/physics_materials/<name>.json   matériaux physiques
data/<ns>/axion/collision_groups/<name>.json    groupes et masques
data/<ns>/axion/block_materials/<name>.json     mappage bloc -> matériau physique
data/<ns>/axion/wear_profiles/<name>.json       profils d'usure
data/<ns>/axion/repair_rules/<name>.json        règles de réparation
```

```text
1. JSON strict          2. validation de schéma versionné ("schema": 1 obligatoire)
3. résolution des références (asset, matériaux, nodes, sockets, parts, régions)
4. compilation en CompiledDefinition, transfert au natif
5. enregistrement dans la registry, synchronisée serveur -> client au login
```

- R-580 : une definition invalide est refusée **individuellement** (`E-7001`).
- R-581 : une definition NE PEUT PAS déclencher d'exécution de code arbitraire : aucun script, aucune expression évaluée, aucune référence de classe (ADR-009).

**Tests.** T-280..T-284.

## 5.21 C-28 : Compilateur de déformation et de structure

**Purpose.** Transformer les annotations d'auteur et la géométrie en données de déformation et de structure prêtes pour le runtime. C'est le composant qui rend la déformation continue **gratuite au runtime en préparation** : tout ce qui peut être précalculé l'est.

**Inputs.** IR validé et optimisé (nodes, meshes, colliders, parts, extras), options de compilation.

**Outputs.** Sections A3D `DEFM` (régions, liaisons sommet → lattice, poids, ancrages, liaisons de points d'enveloppe) et `STRC` (graphe structurel).

**Algorithm (normatif).**

```text
A. RÉGIONS
 1. collecter les régions déclarées (extras "deform_region" ou definition)
 2. pour chaque part DEFORMABLE sans région déclarée : générer une région
    englobante automatique (OBB ajustée par analyse en composantes principales
    des sommets de la part), résolution choisie par :
        res_axe = clamp(round(longueur_axe / target_cell_size), 2, 16)
        target_cell_size = max(thickness * 4, 0.10 m), ajustable par options
 3. refuser toute région dont le volume est nul ou dont un axe dépasse 16

B. LIAISON SOMMET -> LATTICE
 4. pour chaque sommet appartenant à une région : calculer ses coordonnées
    normalisées dans l'OBB ; la déformation sera une interpolation trilinéaire
    des 8 nœuds de la cellule contenante -> AUCUNE donnée par sommet n'est
    stockée en dehors de (region: u8, def_w: u8) déjà présents dans Vertex
 5. les sommets hors de toute région ont region = 255 et ne sont jamais déformés

C. POIDS DE DÉFORMATION
 6. def_w par sommet = produit de :
       - le vertex group Blender "axion_deform" s'il existe (valeur 0..1)
       - un facteur de rigidité de bord : 1 - smoothstep(d_bord / rigid_margin)
         où d_bord est la distance au bord de la région
       - 0 si le sommet appartient à un node NO_DEFORM ou à un matériau
         NON_DEFORMABLE
 7. quantifier en u8

D. ANCRAGES
 8. les nœuds de lattice situés hors de l'enveloppe de la part, ou marqués par
    l'auteur, ou appartenant à une face déclarée ANCHORED_BORDER, sont ancrés :
    leur déplacement est forcé à zéro (les bords soudés ne bougent pas)

E. LIAISON POINTS D'ENVELOPPE -> LATTICE
 9. pour chaque collider REFITTABLE de la part : ses points d'enveloppe reçoivent
    la même liaison trilinéaire, permettant le refit (PARTIE 14.9)

F. GRAPHE STRUCTUREL
10. les liaisons déclarées (extras "structural_link" ou definition) sont compilées
11. les liaisons non déclarées entre une part et sa part parente sont générées
    automatiquement, de type WELD, avec des capacités dérivées d'une AIRE DE
    LIAISON A_link [m²] estimée par la méthode de 15.1bis :

        capacity [J]    = material.fracture_energy   [J/m²] * A_link [m²]
        tensile  [N]    = material.fracture_strength [Pa]   * A_link [m²]
        shear    [N]    = tensile * material_shear_ratio    (— , défaut 0.6)
        torque   [N.m]  = shear * r_link                    (r_link en m, 15.1bis)

    Chaque produit est dimensionnellement vérifié par T-806b. Aucune grandeur
    n'est obtenue en multipliant une énergie par une aire ni en assimilant un
    volume à une aire.
12. vérifier l'acyclicité et l'absence de part orpheline

G. VALIDATION FINALE
13. taille totale des données de déformation par asset <= assets.max_deform_bytes
    (défaut 4 MiB) ; sinon réduction automatique des résolutions de lattice,
    avec avertissement, jusqu'à respecter la limite
```

- R-590 : la compilation de déformation est **entièrement hors ligne** (compilation d'asset). Aucun calcul de liaison n'a lieu au runtime.
- R-591 : la génération automatique de région et de liaison structurelle rend le système utilisable **sans aucune annotation d'auteur** : un GLB brut d'une carrosserie devient déformable avec des défauts raisonnables. Les annotations servent à contrôler, pas à activer.
- R-592 : le compilateur est déterministe (même entrée, même sortie bit à bit).

**Budget.** Compilation : `assets.max_compile_ms` partagé avec le reste du pipeline.

**Failure modes.** FM-10 région dégénérée → refus nommé (`E-8010`) ; FM-11 dépassement de taille → réduction automatique + avertissement ; FM-12 graphe structurel cyclique → refus (`E-8011`).

**Metrics.** `axion.deform.regions_compiled`, `axion.deform.lattice_nodes`, `axion.deform.compile_ms`, `axion.struct.links_generated`.

**Tests.** T-800 (région auto sur une carrosserie de référence), T-801 (LOD conserve les régions), T-802 (poids de déformation cohérents avec les vertex groups), T-803 (ancrages générés sur les bords soudés), T-804 (liaisons de points d'enveloppe complètes), T-805 (graphe structurel auto acyclique), T-806 (réduction automatique sous la limite de taille), T-807 (déterminisme de compilation).

**Acceptance.** Un GLB de véhicule de référence **sans aucune annotation** produit des régions de déformation, des poids, des ancrages et un graphe structurel valides ; le même GLB annoté produit exactement les régions déclarées par l'auteur.

---
## 5.22 C-30 : Scene Graph

```rust
pub struct SceneGraph {
    pub parent: Vec<u32>, pub local: Vec<Affine3A>, pub world: Vec<Affine3A>,
    pub flags: Vec<u32>, pub dirty: BitSet, pub mesh: Vec<u32>, pub bone: Vec<u32>,
    pub part: Vec<u16>, pub region: Vec<u16>, pub visible: BitSet, pub state: Vec<u8>,
}
```

Propagation linéaire grâce à l'ordre topologique, sans récursion ; parallélisable **entre** assemblies, jamais à l'intérieur.

- R-600 : les nodes `PHYSICS_DRIVEN` reçoivent leur transform de C-31 avant propagation.
- R-601 : la déformation **n'affecte pas** les transforms de nodes : elle agit sur les sommets et sur les points d'enveloppe. Un node dont la géométrie est déformée conserve sa transform. Les effets de désalignement (une porte tordue) sont modélisés par le **décalage du repère de joint** (PARTIE 18.5), pas par une transform de node arbitraire. Cette séparation évite toute rétroaction déformation → hiérarchie.

**Budget.** `axion.scene.propagate_ns` inclus dans `budgets.sim_ns_per_tick`.

**Tests.** T-290..T-292, T-612.

## 5.23 C-31 : Physics World

**Décision (ADR-002).** `rapier3d` + `parry3d` (Apache-2.0).

```text
- un IslandManager, une BroadPhase, une NarrowPhase, un PhysicsPipeline par dimension
- pas fixe sim.fixed_dt, sim.max_substeps
- solveur : physics.velocity_iterations (4), physics.position_iterations (1)
- sleeping (seuils 0.05 m/s, 0.05 rad/s, 0.5 s)
- CCD sur déclaration, ou automatique si |v| * dt > 0.5 * plus petite dimension
- contacts persistants avec conservation de l'impulsion maximale par paire et
  par tick, exposés à C-41
```

- R-610 : un monde physique par dimension, créé au premier besoin, détruit quand la dimension n'a plus d'assembly ni de joueur.
- R-611 : gravité par dimension (`physics.gravity`, défaut −9.81), jamais déduite de la gravité vanilla des entités.
- R-612 : au-delà de `physics.simulation_radius` (défaut 128 blocs), sommeil forcé, jamais suppression.
- R-613 : plafond `budgets.max_active_bodies` ; au-delà, endormissement des bodies les plus éloignés et les plus anciens, déterministe et journalisé.
- R-614 : les entités vanilla sont représentées par des **colliders cinématiques temporaires** (capsule pour les êtres vivants, AABB sinon) reconstruits chaque tick dans le rayon d'influence. L'effet d'une collision sur une entité vanilla est appliqué côté Java via les API standard, sur le thread autoritatif, et est configurable.
- R-615 : la narrow phase publie pour chaque paire de contact : point, normale, impulsion normale et tangentielle, vitesse relative au point, masses et matériaux — données requises par C-41 (exigence 10).

**Failure modes.** FM-20 NaN → restauration du dernier état valide (`E-2030`) ; FM-21 budget dépassé → réduction des sous-pas puis sommeil ; FM-22 empilement instable → amortissement local puis sommeil.

**Tests.** T-300..T-307.

## 5.24 C-32 : Collider Builder

Sources de collider par priorité : extras du node → definition → génération automatique (`auto_box`, `auto_sphere`, `auto_capsule`, `auto_convex`, `auto_compound`) → aucun.

- R-620 : génération à la **compilation**, jamais au runtime.
- R-621 : `auto_compound` regroupe les colliders des enfants — mode recommandé pour les carrosseries.
- R-622 : masse déclarée prioritaire, sinon calculée depuis les densités ; centre de masse calculable ou déclarable.
- R-623 : tout collider de body dynamique est marqué `REFITTABLE` par défaut si sa part porte au moins une région de déformation ; l'auteur peut poser `NO_REFIT` (collider structurel figé, par exemple un châssis).

**Tests.** T-310..T-312.

## 5.25 C-33 : Vehicle System

Spécifié en **PARTIE 12**.

**Tests.** T-320..T-329.

## 5.26 C-34 : Joints & Constraints

Types V1.0 : `FIXED`, `REVOLUTE`, `PRISMATIC`, `SPHERICAL`, `GENERIC` (6 DOF configurables), `ROPE` (distance maximale), `SPRING`.

Chaque joint supporte limites, moteur (vitesse cible + force max), amortissement, raideur, **friction interne**, et seuils de rupture (force, couple, énergie).

- R-630 : la rupture d'un joint est un événement synchronisé, jamais un effet client.
- R-631 : un joint dont les deux bodies dorment n'est pas résolu.
- R-632 : un joint peut être **grippé** (`JAMMED`) : sa friction interne et ses limites sont modifiées par la déformation de la région qui porte son ancrage (PARTIE 18.5). Le grippage est un état déclaratif et réversible par réparation.

**Tests.** T-330..T-334, T-950..T-953.

## 5.27 C-35 : Damage Model

Spécifié en **PARTIE 13**.

**Tests.** T-340..T-349, T-830..T-839.

## 5.28 C-36 : Particle Solver

Spécifié en **PARTIE 17**.

**Tests.** T-350..T-357, T-930..T-945.

## 5.29 C-37 : Animation System

Spécifié en **PARTIE 18**.

**Tests.** T-360..T-368, T-950..T-959.

## 5.30 C-38 : World Collision Provider

```text
1. Java maintient un cache de tuiles de collision (sections 16³ alignées)
2. une tuile est produite en itérant les VoxelShapes via Level.getBlockCollisions
   sur l'AABB de la section -> liste compacte de boîtes quantifiées en 1/16 bloc
3. les tuiles sont envoyées par lot et converties en compounds statiques
4. un BlockEvent ou un LevelChunkEvent invalide la tuile ; reconstruction amortie
   à world.tiles_per_tick (défaut 8) par tick
5. les tuiles sans assembly proche (world.tile_radius, défaut 3) sont libérées
```

- R-640 : la construction ne charge **jamais** de chunk ; une section non chargée est traitée comme **pleine et solide** (choix conservateur, empêche toute traversée).
- R-641 : au-delà de 4096 boîtes, la tuile est représentée par une heightfield conservative, avec avertissement.
- R-642 : les fluides sont des **volumes de flottabilité** (capteurs) ; poussée d'Archimède et traînée appliquées selon le volume immergé approché par échantillonnage de 8 points de l'AABB. Modèle explicitement approximatif, documenté.
- R-643 : chaque tuile porte le **matériau physique** dominant de ses blocs, résolu par le mappage data-driven `block_materials` (héritage par tag, défaut générique). Aucun nom de bloc n'est codé en dur. Ce matériau alimente la friction des roues et l'énergie des impacts contre le monde.

**Tests.** T-370..T-375.

## 5.31 C-39 : Spatial Queries

`raycast`, `sweep`, `overlap`, versions par lot ; filtres par groupe, masque, exclusion d'assembly, capteurs.

- R-650 : les requêtes ne mutent jamais le monde physique et sont parallélisables.
- R-651 : une requête depuis Java pendant la fenêtre de simulation renvoie l'état du **début du tick**.
- R-652 : les requêtes utilisent la géométrie de collision **courante**, c'est-à-dire refitée après déformation. La divergence tolérée entre géométrie visuelle et géométrie de collision est bornée par `deformation.collider_refit_threshold` et documentée (PARTIE 14.9).

**Tests.** T-380..T-384, T-856.

## 5.32 C-40 : Simulation Scheduler

**Ordre normatif d'un pas de simulation (figé ; toute variation exige un ADR) :**

```text
 1. application des commandes (spawn, despawn, forces, inputs, tuiles monde,
    impacts externes, réparations, attaches)
 2. animation procédurale et échantillonnage des pistes pilotant des nodes
    physiques, des sockets ou des repères de joint
 3. véhicules : entrées, raycasts de roues, forces de suspension et de pneu
 4. intégration physique (Rapier) : forces -> vitesses -> positions -> contacts
 5. joints, ruptures de joints
 6. collecte des contacts et des événements
 7. SOLVEUR D'IMPACTS (C-41) : contacts -> ImpactDesc enrichis
 8. MODÈLE DE DOMMAGE (C-35) : dommage visuel, structurel, usure
 9. DÉFORMATION (C-42) : champ élastique et plastique, par région
10. INTÉGRITÉ STRUCTURELLE (C-43) : propagation, seuils
11. RUPTURE ET DÉTACHEMENT (C-44) : création de débris, coupure de liaisons
12. REFIT DE COLLIDER (C-45), budgété, au plus N par tick
13. particules (C-36)
14. propagation du scene graph (C-30)
15. sérialisation des états, événements, pages de champ et paquets réseau
```

- R-660 : cet ordre est fixe. Les étapes 7 à 12 forment la **chaîne de dommage** et s'exécutent après l'intégration physique du dernier sous-pas du tick, une seule fois par tick (pas par sous-pas), afin de rendre le coût du dommage indépendant du nombre de sous-pas.
- R-661 : chaque étape a un budget et une métrique ; le dépassement cumulé déclenche la dégradation (PARTIE 25.6).
- R-662 : côté client, s'exécutent : 2, 9 (élastique et rejeu déterministe du plastique), 13, 14, plus l'interpolation ; les étapes 3 à 12 ne s'exécutent que pour le véhicule sous prédiction locale, et **jamais** pour produire du plastique autoritatif (INV-17).

**Tests.** T-390..T-392, T-827.

## 5.33 C-41 : Impact Solver

**Purpose.** Convertir un contact physique brut en une description d'impact exploitable par le dommage, la déformation et l'usure. C'est le point unique où la physique devient du dégât (exigence 10).

**Inputs.** Manifolds de contact de C-31 (point, normale, impulsions normale et tangentielle, vitesse relative, handles, matériaux), impacts externes convertis (explosions, projectiles, feu, chute, API).

**Outputs.** `ImpactDesc[]` triés de façon déterministe.

**Algorithm (normatif).**

```text
Pour chaque manifold retenu (impulsion normale > physics.contact_event_threshold) :

 1. masse effective de la paire au point de contact :
        m_eff = 1 / (1/m_a + 1/m_b)      (m = INF pour statique -> m_eff = m_autre)
 2. vitesse relative normale v_n et tangentielle v_t au point
 3. énergie cinétique relative absorbée :
        E_n = 0.5 * m_eff * v_n²  * (1 - restitution_combinée²)
        E_t = 0.5 * m_eff * v_t²  * friction_combinée
        E   = (E_n + E_t) * material_self.energy_absorption
 4. géométrie de contact par MODÈLE D'INDENTATION PLASTIQUE (13.3bis) :
        R_eff [m]  = rayon d'indenteur effectif (courbure relative du contact si
                     exploitable, sinon material.indenter_radius_ref), divisé par
                     sharp_factor si SHARP, multiplié par blunt_factor si BLUNT,
                     borné à [R_min, R_max]
        H_eff [Pa] = material_self.hardness_pressure
        V_ind [m³] = E / H_eff                       (travail = pression x volume)
        d_ind [m]  = sqrt( V_ind / (PI * R_eff) )    (calotte sphérique, d << R)
        A     [m²] = 2 * sqrt( PI * R_eff * V_ind )  (= 2 * PI * R_eff * d_ind)
        A          = clamp(A, A_min, A_max)
    A_min et A_max sont dérivés de l'épaisseur de la région. Toutes les
    dimensions sont vérifiées par T-830b. Le calcul n'emploie que + - * / sqrt,
    donc il reste utilisable par le noyau déterministe (R-510).
 5. localisation : point converti en espace de part ; part, zone et région
    déterminées par le collider touché (chaque collider porte part/zone/region)
 6. sous-zone : la zone la plus profonde contenant le point (parent_zone)
 7. construction de l'ImpactDesc, seq attribué par un compteur par assembly
 8. agrégation : deux impacts sur la même part, dans la même cellule de lattice,
    au cours du même tick, sont fusionnés (somme des énergies, normale pondérée)
```

- R-670 : les impacts sont **triés** par `(assembly, part, region, cell_index, seq)` avant traitement, afin de garantir la reproductibilité et la reconstruction client (INV-14).
- R-671 : le nombre d'impacts traités par tick est plafonné (`damage.max_impacts_per_tick`, défaut 512). Au-delà, les impacts sont agrégés par cellule de lattice (jamais perdus silencieusement) et un compteur d'agrégation est incrémenté.
- R-672 : les impacts d'énergie inférieure à `damage.min_impact_energy` (défaut 5 J) ne produisent ni dommage ni déformation, mais peuvent produire de l'usure (rayure) si `SHARP`.
- R-673 : l'énergie d'un impact est plafonnée par `damage.max_impact_energy` (défaut 5 MJ) pour empêcher toute explosion numérique depuis une valeur aberrante.

**Budget.** `budgets.damage_ns_per_tick` (défaut 1 ms), partagé avec C-35, C-43, C-47.

**Failure modes.** FM-30 masse effective infinie des deux côtés → impact ignoré ; FM-31 énergie non finie → impact rejeté et journalisé (`E-8020`) ; FM-32 collider sans part → impact attribué à la part racine.

**Metrics.** `axion.impact.count`, `axion.impact.energy_sum`, `axion.impact.aggregated`, `axion.impact.rejected`, `axion.impact.solve_ns`.

**Tests.** T-830 (masse effective correcte pour dynamique/statique), T-831 (énergie cohérente avec la variation d'énergie cinétique, à 5 % près), T-832 (agrégation par cellule), T-833 (SHARP produit une aire plus petite et une pénétration plus profonde), T-834 (plafond d'impacts respecté sans perte silencieuse), T-835 (impacts triés de façon déterministe).

**Acceptance.** Sur le scénario de référence « collision frontale à 10 m/s contre un mur », l'énergie totale rapportée par C-41 est cohérente avec la perte d'énergie cinétique mesurée du body, à 10 % près, et la répartition entre parts correspond à la géométrie touchée.

## 5.34 C-42 : Deformation Engine

Spécifié intégralement en **PARTIE 14**.

**Budget.** `budgets.deformation_ns_per_tick` (défaut 1,5 ms), `budgets.deform_mem_bytes` (défaut 128 MiB).

**Tests.** T-808..T-828.

## 5.35 C-43 : Structural Integrity

Spécifié intégralement en **PARTIE 15**.

**Tests.** T-850..T-859.

## 5.36 C-44 : Fracture & Detachment

Spécifié intégralement en **PARTIE 15**.

**Tests.** T-860..T-869.

## 5.37 C-45 : Collider Refit

Spécifié intégralement en **PARTIE 14.9**.

**Budget.** `budgets.max_collider_refits_per_tick` (défaut 8).

**Tests.** T-855..T-858.

## 5.38 C-46 : Repair System

Spécifié intégralement en **PARTIE 16**.

**Tests.** T-880..T-889.

## 5.39 C-47 : Surface State & Wear

**Purpose.** Accumuler les altérations de surface qui ne relèvent pas de la géométrie : rayures, éraflures, saleté, brûlure, oxydation. Génère les décalques et pilote les paramètres de matériau de dommage.

**Modèle.**

```text
- chaque part est découpée en au plus 32 zones de surface (dérivées des zones de
  dommage, ou d'une subdivision automatique de l'OBB de la part)
- chaque zone porte un WearState (4 canaux u8)
- un impact incrémente les canaux selon :
      scratch += (E_t / A) * (1 - material.scratch_resistance) * profile.scratch_gain
      burn    += E_thermal * profile.burn_gain
      soil    += profile.soil_gain * exposition (pluie, boue, temps)
      rust    += profile.rust_gain * humidité * (1 - intégrité de la peinture)
- décroissance : scratch et soil décroissent selon profile.*_decay (lavage, pluie)
- au franchissement d'un multiple de 1/16 sur un canal, un événement est émis
- les impacts SHARP produisent en plus un décalque ponctuel (DecalInstance)
```

- R-680 : l'usure est autoritative serveur, quantifiée u8, et n'est resynchronisée qu'au franchissement de seuil (R-231).
- R-681 : les décalques sont générés **sur le client** à partir des événements d'impact (position, normale, type, intensité) ; leur liste n'est pas persistée intégralement, seulement les canaux d'usure et au plus `persistence.max_persisted_decals` (défaut 16) décalques marquants par assembly.
- R-682 : la brûlure n'implique aucun système de feu propre : elle est alimentée par les `ImpactDesc` de source `FIRE` produits par C-50 depuis les mécanismes vanilla.

**Budget.** Partagé avec `budgets.damage_ns_per_tick`.

**Metrics.** `axion.wear.zones`, `axion.wear.events`, `axion.decal.spawned`, `axion.decal.evicted`.

**Tests.** T-870 (rayure produite par un impact tangentiel), T-871 (décroissance sous la pluie), T-872 (seuils réseau respectés), T-873 (décalques plafonnés et évincés), T-874 (persistance des canaux d'usure), T-875 (brûlure depuis un dégât de feu vanilla).

**Acceptance.** Un objet frotté contre un mur présente des rayures visibles cohérentes avec la trajectoire, synchronisées, persistées, et son coût réseau reste sous le seuil mesuré.

## 5.40 C-48 : Attachment System

**Purpose.** Lier deux assemblies entre elles de façon générique : remorquage, attelage, treuil, montage d'un module sur une machine, objet posé et fixé, personnage portant un équipement physique.

**Modèle.** `AttachmentDesc` (DM-17) reliant deux sockets. Types : `RIGID` (joint fixe), `HITCH` (rotule limitée, attelage), `ROPE` (contrainte de distance), `WINCH` (corde à longueur variable pilotée), `MAGNET` (contrainte activable), `SEATED` (occupant).

```text
- création : API publique, definition, ou interaction déclarative
- le joint physique correspondant est créé par C-34
- la rupture du joint détruit l'attache et émet un événement
- TRANSFER_DAMAGE : une fraction déclarée de l'énergie d'impact traverse l'attache
- TRANSFER_POWER  : un couple peut être transmis (treuil, prise de force)
- une attache dont un socket devient invalide (pièce détruite) est rompue
```

- R-690 : le graphe d'attaches est acyclique ; une tentative de cycle est refusée (`E-8030`).
- R-691 : profondeur maximale de chaîne d'attaches `attachment.max_chain` (défaut 8) ; au-delà, refus.
- R-692 : les attaches sont persistées par identifiant d'assembly ; une attache dont l'autre extrémité n'est pas chargée est **suspendue**, pas supprimée, et rétablie au chargement.

**Budget.** Négligeable ; comptabilisé dans la physique.

**Tests.** T-890 (remorquage : la remorque suit), T-891 (rupture au-delà de la force maximale), T-892 (cycle refusé), T-893 (chaîne de 8, stable), T-894 (persistance et rétablissement), T-895 (transfert de dommage à travers une attache).

## 5.41 C-50 : Axion Entity

**Décision (ADR-010).** Une classe d'entité unique `AxionEntity`, un `EntityType` unique `axion:assembly`, contenu déterminé par la definition.

- R-700 : n'hérite ni de `LivingEntity` ni de `VehicleEntity` ; implémente elle-même montée, sièges et dégâts.
- R-701 : `tick()` n'exécute aucune physique ; la simulation est pilotée par C-40 en un lot.
- R-702 : l'AABB vanilla est mise à jour depuis l'AABB physique **courante** (déformation incluse), afin que la visée et la sélection restent cohérentes.
- R-703 : implémente `IEntityAdditionalSpawnData` : definition, état complet, états de parts, empreinte de champ de déformation et instantané compressé si l'assembly est déformée.
- R-704 : une definition inconnue au chargement laisse l'entité **inerte et intacte**, jamais supprimée.
- R-705 : C-50 est le point de conversion des dégâts Minecraft en `ImpactDesc` (R-402), selon un mappage déclaratif (PARTIE 13.4).

**Tests.** T-400..T-404, T-836.

## 5.42 C-51 : Network Sync

Spécifié intégralement en **PARTIE 21**.

**Tests.** T-410..T-425, T-823..T-828.

## 5.43 C-52 : Persistance

Spécifiée intégralement en **PARTIE 22**.

**Tests.** T-430..T-437, T-884.

## 5.44 C-53 : Interaction & Sièges

```text
- un siège est un node SEAT : nom, rôle (driver | passenger), offsets de sortie,
  contrôles autorisés
- montée par clic droit sur un collider du sous-arbre du siège ou une zone déclarée
- position imposée chaque tick depuis le socket du siège
- sortie : premier emplacement libre parmi les candidats, sinon position du siège
- interactions déclaratives sur node : sit, toggle_animation, play_animation,
  open_container, emit_event, set_variable, repair, attach, detach
```

- R-710 : les entrées client sont bornées en fréquence (≤ 1 paquet par tick client) et **validées** côté serveur ; une entrée invalide est ignorée et journalisée (INV-02).
- R-711 : la collision joueur/assembly n'éjecte jamais dans un bloc solide.
- R-712 : un siège dont la part est `DESTROYED` ou `DETACHED` éjecte son occupant avec un événement, à la position sûre la plus proche.

**Tests.** T-440..T-444, T-957.

## 5.45 C-54 : Bloc & BlockEntity 3D

- R-720 : un `BlockEntity` `axion:model` porte un `AssetId` et une definition légère ; le rendu passe par le renderer AXION.
- R-721 : la collision du bloc reste une `VoxelShape` **déclarée obligatoirement** dans la definition. AXION ne remplace jamais la collision de blocs vanilla (P-02, pathfinding préservé).
- R-722 : un bloc AXION **peut être déformable et destructible** : sa géométrie visuelle utilise le même système de régions et de parts que les assemblies, son état de dommage est persisté dans le `BlockEntity`, et sa `VoxelShape` peut basculer sur une variante déclarée à chaque changement d'étape de dommage (jamais en continu). Cela couvre l'exigence « blocs complexes » sans casser la collision vanilla.

**Tests.** T-450..T-452, T-876 (bloc déformable).

## 5.46 C-55 : Item 3D

- R-730 : `BakedModel` personnalisé + `BlockEntityWithoutLevelRenderer` (mécanisme standard Forge).
- R-731 : plafond `render.item_max_triangles` (défaut 20 000) en GUI ; au-delà, LOD le plus bas.
- R-732 : un item peut afficher l'état de dommage et de déformation de l'objet qu'il représente (par exemple une pièce détachée ramassée), via le même champ de déformation compact stocké dans son NBT.

**Tests.** T-460..T-462, T-877.

## 5.47 C-60 : Backend NATIVE_GL

Spécifié intégralement en **PARTIE 19**.

**Tests.** T-470..T-485, T-900..T-919.

## 5.48 C-61 : Backend VANILLA_CONSUMER

**Activation.** Mod de shaders actif (obligatoire), `render.backend = vanilla`, échec d'initialisation du backend natif, ou pilote insuffisant.

- R-740 : l'équivalence entre backends est **fonctionnelle et géométrique**, pas
  pixellaire. Elle est définie et bornée en 19.2bis. Les capacités visuelles
  diffèrent, et ces différences sont déclarées, signalées et testées.
- R-741 : le backend vanilla n'émet aucun appel OpenGL direct.
- R-742 : le skinning **et la déformation** y sont appliqués sur **CPU** pendant l'émission des sommets, avec plafonds `render.vanilla_max_skinned_vertices` (défaut 50 000/frame) et `render.vanilla_max_deformed_vertices` (défaut 100 000/frame) ; au-delà, pose de repos et géométrie non déformée pour les instances excédentaires, avec métrique.
- R-743 : les décalques y sont rendus comme des quads additionnels en `RenderType` translucide, plafonnés à `render.vanilla_max_decals` (défaut 64/frame).
- R-744 : bascule à chaud possible, libérant proprement les ressources de l'autre backend.

**Budget.** Le backend vanilla porte les mêmes budgets de frame, avec des plafonds spécifiques plus bas, déclarés ci-dessus.

**Tests.** T-490 (Iris détecté → bascule automatique), T-491 (invariants géométriques : mêmes instances, mêmes transforms, mêmes LOD, même géométrie déformée entre backends), T-491b (le basculement ne modifie ni simulation, ni persistance, ni réseau, ni gameplay : états comparés bit à bit avant/après), T-491c (silhouette : couverture en pixels comparée sous tolérance déclarée), T-491d (matrice de capacités : chaque capacité indisponible est désactivée sans artefact et signalée dans le log, `/axion status` et l'overlay), T-492 (bascule à chaud sans fuite), T-493 (skinning et déformation CPU plafonnés, métriques incrémentées), T-901, T-902 (plafonds respectés).

## 5.49 C-62 : GPU Resource Manager

```text
- meshes GPU sous-alloués dans des arènes de 16 MiB (VBO/EBO partagés)
- textures = ressources Minecraft ou DynamicTexture, référencées, jamais possédées
- TBO de palettes de bones (triple buffering)
- TBO/texture de champs de déformation (pages de 1 KiB, pool)
- buffer d'instances, buffer de décalques
- libération différée : 3 frames sans usage
```

- R-750 : toute allocation GPU est comptée ; dépassement de `budgets.gpu_mem_bytes` → déchargement LRU des assets non visibles, puis réduction forcée de LOD, puis réduction du niveau de qualité de déformation.
- R-751 : toute ressource GL est libérée sur le render thread (INV-12).
- R-752 : changement de resource pack → libération et reconstruction complètes.

**Tests.** T-500..T-503, T-903 (pool de pages de déformation).

## 5.50 C-63 : Matériaux & Shaders

Voir **PARTIE 19.4 et 19.5**.

- R-760 : les shaders sont des ressources du JAR (`assets/axion/shaders/`), rechargeables, compilés au démarrage du backend, avec un **cache binaire** (`GL_ARB_get_program_binary` si disponible) dans `<gameDir>/axion/cache/shaders/`, invalidé par le hash des sources et la chaîne du pilote.
- R-761 : un échec de compilation bascule sur le backend vanilla (`E-4001`), jamais un crash.
- R-762 : les variantes sont générées par permutation de `#define` : `SKINNED`, `DEFORMED`, `DEFORMED_RESIDUAL`, `PARALLAX`, `CLEARCOAT`, `SHEEN`, `ANISO`, `DECALS`, `CUTOUT`, `SHADOW_RECV`, `SSR`. Le nombre de variantes réellement compilées est borné (`render.max_shader_variants`, défaut 64) et les variantes sont compilées **à la demande**, en arrière-plan, avec une variante de secours immédiate.

**Tests.** T-510..T-512, T-904 (compilation à la demande sans à-coup), T-905 (cache binaire).

## 5.51 C-64 : Culling & LOD

```text
1. culling de distance (render.max_distance)
2. frustum culling par AABB monde (6 plans, SIMD, par lot)
3. culling d'occlusion (C-82) si Q >= MEDIUM
4. sélection de LOD par taille apparente en pixels, avec hystérésis de 10 %
5. tri : opaques par (programme, matériau, mesh) ; translucides par distance
6. plafonnement : au-delà de budgets.max_visible_instances, dégradation d'un LOD
   puis rejet des plus lointaines
7. sélection du LOD de déformation (PARTIE 14.7) et du LOD de particules
```

- R-770 : la liste produite est **stable** entre frames (hystérésis) pour éviter tout scintillement.
- R-771 : le culling s'exécute en natif, sur le pool de workers, avec deadline.
- R-772 : une assembly **déformée** ne peut pas être dégradée au point de perdre visuellement sa déformation à courte distance : la sélection de LOD de déformation est plafonnée par la distance (PARTIE 14.7).

**Tests.** T-520..T-524, T-906.

## 5.52 C-65 : Instancing & Batching

```text
clé = (programme, matériau, mesh, LOD, blend, variante de déformation)
 - count >= render.instancing_threshold (défaut 4) et instancing disponible :
      VBO d'instances (mat4x3, tint, lightmap, offsets de palette / champ / décalques)
      glDrawElementsInstancedBaseVertex
 - GL 4.3 disponible et render.indirect = true : glMultiDrawElementsIndirect
 - sinon : un draw call par instance
```

- R-780 : format d'instance figé : `mat4x3 model` (48 o), `vec4 tint` (16 o), `ivec2 lightmap` (8 o), `uint palette_offset`, `uint deform_offset`, `uint decal_offset_count`, `uint flags` (16 o) = **88 octets**.
- R-781 : le chemin non instancié reste fonctionnel et testé (H-03).
- R-782 : le chemin indirect (H-05) est optionnel, activé seulement si détecté, et produit une image identique au chemin instancié (test de comparaison d'images).

**Tests.** T-530..T-533, T-907 (chemin indirect équivalent).

## 5.53 C-66 : Skinning GPU

```text
- palettes mat4x3 dans un TBO (chemin par défaut) ou un UBO paginé (repli)
- 128 bones max par palette ; offset de palette par instance
- vertex shader : 4 bones/poids
```

- R-790 : le chemin TBO est le chemin par défaut ; l'UBO paginé est le repli (H-04).
- R-791 : plafond `render.max_skinned_instances` (défaut 64) ; au-delà, pose de repos, avec métrique.
- R-792 : **ordre d'application** : skinning **puis** déformation. Le sommet est d'abord transformé par la palette de bones, puis déplacé par le champ de déformation exprimé dans l'espace de la région. Cet ordre est figé et documenté (PARTIE 14.6) ; il permet à une créature animée d'être cabossée sans que la déformation ne suive les os.

**Tests.** T-540..T-542, T-908 (skinning + déformation combinés).

## 5.54 C-67 : Debug Renderer

Overlays : colliders, AABB, bodies, contacts et normales, joints et limites, raycasts, roues et suspension, bones, nodes, pivots, sockets, zones et sous-zones de dommage, **lattices de déformation** (nœuds, déplacements, ancrages), **graphe structurel** (liaisons colorées par intégrité), points d'impact et énergies, décalques, LOD, culling, occluders, tuiles monde, centre de masse, réseau (interpolé vs autoritatif), budgets.

- R-800 : client-only, désactivé par défaut ; **quand un overlay est éteint, aucune donnée de debug n'est produite en natif, aucun tampon n'est rempli et aucun draw call n'est émis pour lui** — le coût résiduel est celui d'un test de drapeau par frame, mesuré (T-551).

**Tests.** T-550, T-551, T-909 (overlay de lattice).

## 5.55 C-68 : Déformation GPU

Spécifié intégralement en **PARTIE 14.6 et 19.6**.

**Tests.** T-810..T-816, T-910..T-913.

## 5.56 C-69 : Décalques & états de surface (rendu)

Spécifié intégralement en **PARTIE 20**.

**Tests.** T-870..T-879, T-914..T-916.

## 5.57 C-70 : API publique

Spécifiée intégralement en **PARTIE 23**.

## 5.58 C-71 : Commandes

```text
/axion status | metrics [export] | diag dump | compat
/axion assets list | info <id> | reload
/axion defs list | info <id>
/axion spawn <definition> [pos] [nbt] | remove <selector>
/axion sim pause|resume|step <n> | budget <ns>
/axion damage <selector> <part> <energy> [--point x y z] [--normal x y z]
/axion deform show <selector> | reset <selector> [part] | quality <level>
/axion repair <selector> [part] [--level visual|deform|part|structural|full]
/axion attach <a> <socket> <b> <socket> <kind> | detach <id>
/axion debug <overlay> on|off
/axion render backend <native|vanilla|auto> | quality <subsystem> <level>
/axion bench <scenario> [--json <f>]
/axion config reload | get <k> | set <k> <v>
```

- R-810 : toute commande mutante est journalisée avec auteur et paramètres ; `/axion damage` et `/axion deform` sont des outils de test soumis à permission 2.
- R-811 : `/axion spawn` refuse au-delà de `limits.max_spawn_per_command` (défaut 64).

**Tests.** T-560..T-562, T-878.

## 5.59 C-72 : Benchmark Harness

Spécifié en **PARTIE 30**.

## 5.60 C-73 : Overlay diagnostics

Affiche FPS, TPS, budgets consommés par sous-système, **niveaux de qualité actifs et leur cause**, bodies actifs/endormis, assemblies déformées, mémoire de déformation, instances visibles, draw calls, triangles, mémoire native et GPU, backend, dégradations actives.

- R-820 : l'overlay indique **pourquoi** chaque dégradation et chaque baisse de qualité sont actives, en une ligne lisible (R-021).

**Tests.** T-570, T-980.

## 5.61 C-74 : CLI `axion-cli`

```text
axion-cli compile <src> -o <out.a3d> [--options options.json]
axion-cli inspect <file.a3d>          structure, sections, régions, graphe structurel
axion-cli validate <file.a3d|json>
axion-cli lod <file.a3d> --ratios ...
axion-cli deform <file.a3d> --impact x,y,z --energy J --normal x,y,z -o out.obj
          (applique une séquence d'impacts hors jeu et exporte le résultat)
axion-cli diff <a.a3d> <b.a3d>
axion-cli replay <incident.json>
axion-cli bench-asset <src>
```

- R-830 : `axion-cli` partage **exactement** le code de C-21, C-22, C-23, C-24, C-28, C-41, C-42, C-43. Une divergence de comportement entre le jeu et le CLI est un défaut bloquant. `axion-cli deform` est l'outil de référence pour valider visuellement une déformation sans lancer Minecraft.

**Tests.** T-580, T-817 (déformation CLI identique à la déformation en jeu).

## 5.62 C-75 : Addon Blender

Spécifié en **PARTIE 8**.

## 5.63 C-76 : Bridge RUSTFORGE-X

Spécifié en **PARTIE 27**.

## 5.64 C-77 : Gouverneur de qualité

**Purpose.** Choisir et ajuster en continu les niveaux de qualité de chaque sous-système à partir des budgets mesurés, de la configuration et des capacités matérielles. C'est le composant qui remplace la suppression de fonctionnalités par leur graduation (P-07).

**Inputs.** Métriques de C-15, capacités GPU détectées, configuration utilisateur, mode (client/serveur).

**Outputs.** `QualityProfile` (DM-18) publié au natif et au renderer, avec la cause de chaque changement.

**Algorithm.**

```text
1. profil initial : déduit des capacités (GL version, extensions, VRAM, cœurs)
   et de la configuration ; l'utilisateur peut forcer un niveau par sous-système
2. à chaque seconde, pour chaque sous-système :
      consommation = p95 du budget sur la fenêtre glissante
      si consommation > 100 % du budget pendant 3 fenêtres  -> niveau - 1
      si consommation < 60 % du budget pendant 30 s          -> niveau + 1
   avec un plancher et un plafond par sous-système, et au plus un changement
   toutes les 10 s par sous-système (hystérésis)
3. tout changement est journalisé : sous-système, ancien niveau, nouveau niveau,
   métrique déclenchante, valeur, budget
4. un niveau forcé par l'utilisateur n'est jamais modifié automatiquement ;
   si son budget est dépassé, la dégradation générale (PARTIE 25.6) s'applique
```

- R-840 : un niveau forcé par l'utilisateur est respecté ; le gouverneur ne le contourne jamais silencieusement.
- R-841 : le gouverneur n'affecte **jamais** la simulation autoritative serveur d'une façon qui changerait le résultat du jeu : côté serveur, il n'agit que sur des paramètres explicitement déclarés comme sans effet sur la correction (résolution de refit de collider, fréquence d'usure, LOD de particules serveur). La liste de ces paramètres est fermée et documentée.
- R-842 : `/axion render quality <subsystem> <level>` et `/axion deform quality <level>` permettent le pilotage manuel.

**Budget.** Coût propre négligeable, mesuré, sous 0,1 % d'un cœur.

**Metrics.** `axion.quality.level{subsystem}`, `axion.quality.changes`, `axion.quality.cause{subsystem}`.

**Tests.** T-980 (baisse sur surcharge), T-981 (remontée avec hystérésis), T-982 (niveau forcé respecté), T-983 (aucun effet serveur sur la correction : simulation identique à tous les niveaux, T-984 comparaison d'états).

**Acceptance.** Sous surcharge artificielle, les niveaux baissent dans l'ordre déclaré, la cause est affichée, le jeu reste jouable, et la simulation serveur produit le même résultat qu'à qualité maximale.

## 5.65 C-80 : Ombres AXION

Spécifié en **PARTIE 19.8**.

**Tests.** T-917..T-919.

## 5.66 C-81 : Sonde d'environnement & IBL synthétique

Spécifié en **PARTIE 19.5**.

**Tests.** T-920..T-922.

## 5.67 C-82 : Occlusion culling logiciel

Spécifié en **PARTIE 19.7**.

**Tests.** T-923..T-926.

## 5.68 C-83 : Effets intégrés (tone mapping, SSR)

Spécifié en **PARTIE 19.9**.

**Tests.** T-927..T-929.

---
# PARTIE 6 : FORMATS 3D ET PIPELINE D'ASSETS

## 6.1 Évaluation des formats

| Format | Géométrie | UV | Normales | Tangentes | Matériaux | Textures embarquées | Hiérarchie | Squelette | Animations | Vertex groups | Métadonnées custom | Licence | Verdict V1.0 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **glTF 2.0 / GLB** | ✔ | ✔ multi | ✔ | ✔ | PBR metallic-roughness + extensions | ✔ (GLB) | ✔ | ✔ | ✔ | via `WEIGHTS`/attributs custom | ✔ (`extras`) | spec Khronos ouverte | **format principal recommandé** |
| **OBJ + MTL** | ✔ | ✔ | ✔ | ✘ (générées) | basiques | ✘ | groupes seulement | ✘ | ✘ | ✘ | ✘ | libre | **supporté** (statique) |
| **STL** | ✔ | ✘ | par face | ✘ | ✘ | ✘ | ✘ | ✘ | ✘ | ✘ | ✘ | libre | **supporté** (collision / forme) |
| PLY | ✔ | partiel | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ | ✘ | propriétés | propriétés | libre | hors périmètre |
| Collada / DAE | ✔ | ✔ | ✔ | partiel | ✔ | ✘ | ✔ | ✔ | ✔ | ✔ | ✔ | ouverte, XML verbeux | hors périmètre (conversion Blender) |
| FBX | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | **propriétaire Autodesk** | **exclu** |
| USD / USDZ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ouverte, implémentation C++ lourde | hors périmètre |

## 6.2 Décisions figées

- **ADR-001 : glTF 2.0 / GLB est le format d'échange principal.** Spécification ouverte, export Blender natif, couverture complète, conteneur binaire autonome, bibliothèque Rust mature, et surtout **support des `extras` par node, mesh, primitive et matériau**, ce qui permet de transporter toute la métadonnée AXION (déformation, structure, dommage) sans format parallèle.
- **OBJ** est un format d'import de première classe pour la géométrie statique. Limites documentées : pas de hiérarchie, pas d'animation, pas de tangentes, matériaux approximés, **pas de métadonnée** — un OBJ reçoit donc des régions de déformation et un graphe structurel **générés automatiquement** (C-28), ce qui le rend malgré tout déformable.
- **STL** est supporté pour les colliders et les objets sans texture.
- **FBX est exclu** : le SDK est propriétaire et sa licence est incompatible avec une distribution Apache-2.0 ; les réimplémentations libres sont incomplètes. Contournement documenté : Blender importe FBX et exporte GLB.
- **Collada, USD, PLY sont hors périmètre**, même contournement.

- R-850 : la documentation `ASSETS.md` contient le tableau 6.1 et la liste explicite de ce que chaque format **ne peut pas** transporter, avec la conséquence sur la déformation et la structure.

## 6.3 Pipeline complet

```text
   Source 3D (Blender, autre DCC, scanner, marketplace)
        |  export
        v
   .glb / .gltf / .obj / .stl     -> assets/<ns>/axion/models/
        |
        v
   [C-21 IMPORTER]    parsing strict, refus des extensions inconnues,
        |             aucun accès hors du répertoire, lecture des extras AXION
        v
   IR (nodes, meshes, matériaux, skins, animations, annotations)
        |
        v
   [C-22 VALIDATOR]   structure, limites, géométrie, physique, déformation,
        |             graphe structurel, noms ; refus nommé ou réparations
        v
   [C-23 OPTIMIZER]   fusion, normales, tangentes, quantification, cache de
        |             sommets, LOD (régions conservées), décomposition convexe,
        |             extraction des points d'enveloppe
        v
   [C-28 DEFORM/STRUCT COMPILER]   régions, liaisons trilinéaires implicites,
        |             poids de déformation, ancrages, liaisons de points
        |             d'enveloppe, graphe structurel
        v
   [C-24 A3D WRITER]  sections, compression zstd, CRC, index
        v
   <gameDir>/axion/cache/<clé>.a3d          [C-25 CACHE]
        v
   [C-24 A3D READER]  chargement partiel par masque de sections
        |
        +--> natif : NODE GEOM PHYS SKEL ANIM PART DEFM STRC SOCK META  (serveur + client)
        +--> GPU   : VBO/EBO/textures/atlas de décalques                (client)
```

- R-860 : le pipeline est **identique** en jeu et dans `axion-cli` (R-830).
- R-861 : un asset peut être livré **déjà compilé** en `.a3d` ; il saute l'import et l'optimisation mais **jamais la validation**.
- R-862 : la documentation recommande la livraison de `.a3d` précompilés pour les modpacks de production, ce qui supprime tout coût de compilation au premier chargement.

## 6.4 Options de compilation

`<model>.axion.json` à côté de la source, ou bloc `axion` dans les `extras` racine :

```json
{
  "schema": 1,
  "scale": 1.0, "up_axis": "y",
  "generate_tangents": true, "generate_normals": "if_missing",
  "lod": { "enabled": true, "ratios": [1.0, 0.5, 0.25, 0.1], "error_target": 0.02 },
  "collision": { "mode": "auto_convex", "max_hulls": 24, "resolution": 100000 },
  "deformation": {
    "enabled": true,
    "auto_regions": true,
    "target_cell_size": 0.12,
    "max_resolution": [12, 8, 12],
    "default_material": "axion:steel",
    "rigid_margin": 0.05,
    "anchor_borders": true
  },
  "structure": { "auto_links": true, "default_link": "weld" },
  "materials": { "default_roughness": 0.6, "default_metallic": 0.0 },
  "compress": true, "max_texture_size": 2048
}
```

- R-870 : toute option a un défaut ; le fichier est facultatif.
- R-871 : `up_axis: "z"` applique une rotation de −90° autour de X (exports Blender en Z-up).
- R-872 : `deformation.enabled: false` produit un asset sans section `DEFM` : l'objet reste rendu et simulé, mais n'est pas déformable. C'est un choix d'auteur explicite, pas un défaut.

## 6.5 Contraintes d'auteur (documentées dans `ASSETS.md`)

```text
- 1 unité = 1 bloc = 1 mètre
- origine au centre de gravité approximatif ; pour un véhicule, Y=0 au contact
  des roues au repos ; -Z = avant, +X = droite, +Y = haut
- noms de nodes ASCII, sans espace, snake_case
- un matériau par primitive ; éviter les matériaux à usage unique
- textures PNG, puissance de deux, <= 2048x2048 recommandé
- trianguler à l'export, appliquer les modificateurs, appliquer les échelles
- pour la déformation : une part = un ensemble cohérent de matière ; découper
  la carrosserie en panneaux (aile, porte, capot) plutôt qu'un mesh unique
- les bords soudés doivent être marqués ancrés (ou laissés à la détection auto)
```

---

# PARTIE 7 : FORMAT INTERNE A3D

## 7.1 Objectifs

```text
- chargement partiel par masque de sections
- validation bon marché avant lecture (en-tête, tailles, CRC)
- versionnement et migration
- détection de corruption
- little-endian imposé, structures utilisables en place
- extensibilité vers l'avant : toute section inconnue est ignorée proprement
```

## 7.2 Structure du fichier

```text
+-------------------------------------------------------------+
| HEADER (64 octets, non compressé)                           |
|   0   u32  magic         = 0x41_33_44_00  ("A3D\0")         |
|   4   u16  version_major = 1                                |
|   6   u16  version_minor = 1                                |
|   8   u32  flags                                            |
|  12   u32  section_count                                    |
|  16   u64  asset_id                                         |
|  24   u64  source_hash                                      |
|  32   u32  compiler_version                                 |
|  36   u32  header_crc32c                                    |
|  40   u64  total_size                                       |
|  48   u8[16] reserved (zéro)                                |
+-------------------------------------------------------------+
| SECTION TABLE (section_count * 32 octets)                   |
|   0 u32 tag | 4 u32 flags | 8 u64 offset                    |
|  16 u64 size_compressed | 24 u32 size_uncompressed          |
|  28 u32 crc32c                                              |
+-------------------------------------------------------------+
| SECTIONS (alignées sur 16 octets)                           |
+-------------------------------------------------------------+
```

## 7.3 Sections normatives

| Tag | Contenu | Serveur | Client |
|---|---|---|---|
| `NODE` | `NodeDesc[]` + table des noms | ✔ | ✔ |
| `GEOM` | `MeshDesc[]`, `Vertex[]`, `u32 indices[]` | ✔ | ✔ |
| `MATL` | `MaterialDesc[]` | ✘ | ✔ |
| `TEXR` | table de `ResourceLocation` + textures extraites | ✘ | ✔ |
| `PHYS` | `ColliderDesc[]`, points de convexes, heightfields, **points d'enveloppe** | ✔ | ✔ |
| `SKEL` | `BoneDesc[]` | ✔ | ✔ |
| `ANIM` | `AnimationDesc[]`, `TrackDesc[]`, clés | ✔ | ✔ |
| `PART` | `PartDesc[]`, `DamageZoneDesc[]` (avec sous-zones) | ✔ | ✔ |
| **`DEFM`** | `DeformRegionDesc[]`, bitsets d'ancrage, liaisons de points d'enveloppe | ✔ | ✔ |
| **`STRC`** | `StructuralLinkDesc[]`, ordre topologique du graphe de parts | ✔ | ✔ |
| **`WEAR`** | zones de surface, `WearProfileDesc` référencés | ✔ | ✔ |
| **`PART_SET`** | `ParticleSetDesc[]`, contraintes, ancrages, proxies | ✔ (si autorité serveur) | ✔ |
| `LODM` | table LOD → meshes | ✘ | ✔ |
| `SOCK` | sockets nommés (node + offset) | ✔ | ✔ |
| `DECL` | ensembles de décalques prédéfinis par l'auteur | ✘ | ✔ |
| `META` | noms complets, unités, auteur, licence, options de compilation | ✔ | ✔ |
| `EXTR` | extras auteur non interprétés, exposés à l'API | ✔ | ✔ |

- R-880 : tout lecteur ignore proprement une section de tag inconnu.
- R-881 : sections alignées sur 16 octets pour l'accès direct aux structures `repr(C)`.
- R-882 : CRC32C vérifié au chargement ; une section corrompue invalide l'asset (`E-3007`) et déclenche la recompilation si la source est disponible.
- R-883 : les liaisons sommet → lattice **ne sont pas stockées** : elles sont implicites (coordonnées normalisées dans l'OBB de la région, calculées dans le vertex shader et dans le solveur). Seuls `region` et `def_w`, déjà dans `Vertex`, sont nécessaires. Ce choix supprime toute donnée par sommet supplémentaire (exigence de stockage compact).

## 7.4 Versionnement et migration

- R-890 : `version_major` différent → refus explicite (`E-3008`), recompilation si possible.
- R-891 : `version_minor` supérieur → lecture des sections connues, avertissement.
- R-892 : `compiler_version` différent → entrée de cache invalidée.
- R-893 : toute évolution du format est accompagnée d'un test de migration et d'un fichier d'exemple de l'ancienne version dans `tests/fixtures/a3d/`.

## 7.5 Sécurité de lecture

- R-900 : avant toute allocation, vérification de `offset + size <= total_size <= taille réelle`.
- R-901 : toute taille annoncée est plafonnée par les limites de C-22 **avant** allocation.
- R-902 : décompression zstd avec taille de sortie connue à l'avance, jamais en flux non borné.
- R-903 : fuzzing continu en CI (`cargo-fuzz`, cible `a3d_reader`), corpus versionné, tolérance zéro.

**Tests.** T-250..T-253, T-590, T-591.

---

# PARTIE 8 : WORKFLOW BLENDER ET OUTILLAGE AUTEUR

## 8.1 Flux nominal

```text
Blender (modélisation, matériaux, rig, animations, annotations AXION)
   |  [Addon AXION : panneau "Axion Engine"]
   |     - rôles de nodes, colliders, sockets, roues, sièges
   |     - parts, zones et sous-zones de dommage
   |     - régions de déformation, vertex groups de déformation
   |     - matériaux physiques, liaisons structurelles, points de rupture
   |     - LOD, profils d'usure
   |     - visualisation et validation locales
   v
export GLB avec extras AXION
   v
resource pack / mod  ->  compilation (jeu ou axion-cli)  ->  runtime
```

## 8.2 Convention par les `extras` glTF

Toutes les annotations vivent sous une clé unique `axion` dans les `extras` du node, du mesh ou du matériau. Cette convention est **le seul mécanisme d'annotation** et fonctionne sans l'addon, en éditant les propriétés personnalisées de Blender.

```json
// extras d'un node
{
  "axion": {
    "role": "mesh",
    "part": "fender_fl",
    "material": "axion:steel",
    "deform_region": {
      "name": "fender_fl",
      "resolution": [8, 6, 8],
      "thickness": 0.0012,
      "max_disp": 0.18,
      "anchor_faces": ["-x", "+z"],
      "mode": "shell"
    },
    "deform_weight_group": "axion_deform",
    "structural_links": [
      { "to": "body", "kind": "weld", "capacity": 9000.0, "tensile": 22000.0 }
    ],
    "damage_zones": [
      { "name": "fender_fl_edge", "multiplier": 1.4, "deform_multiplier": 1.6,
        "parent": "fender_fl" }
    ],
    "wear_profile": "axion:painted_steel",
    "lod": [0, 1, 2]
  }
}
```

**Rôles reconnus** (`role`) :

```text
mesh (défaut) | collider | wheel | seat | socket | light | part_root |
damage_zone | deform_region | cloth_anchor | particle_set | internal | hidden
```

- R-910 : un node de rôle `collider`, `socket`, `damage_zone`, `deform_region`, `cloth_anchor` n'est jamais rendu.
- R-911 : un extras inconnu est **conservé** en section `EXTR` et exposé à l'API, jamais interprété.
- R-912 : un extras invalide produit un avertissement et le défaut, sauf s'il rend l'asset incohérent (`E-3009`).
- R-913 : **aucune annotation n'est obligatoire.** Un GLB brut produit un asset complet grâce aux générations automatiques de C-28. Les annotations contrôlent, elles n'activent pas.

## 8.3 Addon Blender (C-75)

**Périmètre V1.0 (STABLE).**

```text
PANNEAUX
  - Objet : rôle, part, matériau physique, profil d'usure, LOD, drapeaux
  - Déformation : région (résolution, épaisseur, déplacement max, mode, ancrages),
    vertex group de poids, aperçu du lattice
  - Structure : liaisons vers d'autres parts, type, capacités, points de rupture
  - Dommage : zones et sous-zones, multiplicateurs
  - Véhicule : roues, suspension, sièges, sockets

OPÉRATEURS
  - Générer collider (box / sphere / capsule / convex / compound)
  - Générer région de déformation depuis la sélection (OBB par ACP)
  - Peindre le poids de déformation (accès au weight paint sur "axion_deform")
  - Créer socket, marquer roue (côté détecté), créer siège
  - Générer les liaisons structurelles par proximité de parts
  - Créer une hiérarchie de véhicule conventionnelle
  - Générer les variantes de mesh de dommage (duplication + sculpt de base)

VISUALISATION (viewport overlay)
  - colliders, sockets, zones et sous-zones de dommage
  - lattices de déformation (grille, nœuds ancrés en rouge)
  - poids de déformation (dégradé)
  - graphe structurel (liens entre parts, épaisseur = capacité)
  - parts (couleur par part), LOD

VALIDATION
  - exécute les mêmes règles que C-22 et C-28 (fichier de règles généré)
  - liste les erreurs et avertissements avec sélection de l'objet fautif
  - simulation d'impact locale : applique le noyau de déformation (via axion-cli)
    et affiche le résultat dans Blender, sans lancer Minecraft

EXPORT
  - export GLB avec les réglages corrects, puis compilation optionnelle en .a3d
IMPORT
  - lecture d'un .a3d et reconstruction d'une scène d'inspection
```

- R-920 : compatible Blender 3.6 LTS et 4.x ; distribué séparément (`tools/blender/axion_blender_addon.zip`), Apache-2.0, sans dépendance à Minecraft.
- R-921 : l'addon n'est **pas requis** ; la documentation décrit le workflow manuel équivalent.
- R-922 : le validateur de l'addon utilise les **mêmes règles** que C-22/C-28, via `tools/blender/rules.json` **généré** depuis la source Rust. Une divergence est un défaut.
- R-923 : l'aperçu d'impact appelle `axion-cli deform`, donc le **même code** que le jeu (R-830). L'auteur voit exactement ce que produira le moteur.

**Tests.** T-600..T-602, T-940 (aperçu d'impact identique au jeu), T-941 (validateur identique à C-22/C-28 sur un corpus commun).

## 8.4 Hiérarchie conventionnelle recommandée

```text
vehicle_root
├── body                       part=body, material=axion:steel
│   ├── body_collider          role=collider, shape=auto_convex
│   ├── fender_fl              part=fender_fl, deform_region auto, link->body (weld)
│   ├── fender_fr              part=fender_fr
│   ├── door_l                 part=door_l, link->body (hinge), joint=revolute
│   ├── door_r                 part=door_r
│   ├── hood                   part=hood, link->body (hinge)
│   ├── trunk                  part=trunk
│   ├── bumper_front           part=bumper_front, material=axion:plastic
│   ├── glass_windshield       part=glass_ws, material=axion:glass (BRITTLE)
│   ├── headlight_l            role=light, part=headlight_l, material=axion:glass
│   └── engine_bay             role=internal, flags=REVEALED_ON_DAMAGE
│       ├── engine_block       part=engine, material=axion:steel, NO_DEFORM
│       └── radiator           part=radiator, material=axion:aluminium
├── wheel_fl .. wheel_rr       role=wheel
├── steering_wheel             animé par la direction
├── seat_driver / seat_passenger_*   role=seat
├── socket_towbar / socket_exhaust   role=socket
└── interior                   lod=[0]
```

Cette hiérarchie est une **convention documentée**, pas une contrainte : le moteur ne connaît que des rôles, des parts, des régions, des matériaux et des liaisons (P-01).

---

# PARTIE 9 : SCENE GRAPH, NODES, SOCKETS, ÉTATS DE NODE

## 9.1 Modèle

Une assembly runtime possède :

```text
- un scene graph (nodes en ordre topologique, transforms locales et monde)
- 1..N bodies physiques, chacun associé à un node racine de body
- 0..N joints entre bodies, avec repères d'ancrage éventuellement décalés
- 0..1 squelette et ses poses courantes
- 0..N parts avec santé, intégrité, étape et état
- 0..N régions de déformation, avec champ élastique et plastique
- un graphe structurel de liaisons entre parts
- 0..N ensembles de particules
- 0..N attaches vers d'autres assemblies
- des états d'usure par zone de surface et une liste de décalques
- un état de LOD, de visibilité et de qualité par node
```

## 9.2 États de node (SM-03)

```text
STATIC             transform locale constante depuis l'asset
ANIMATION_DRIVEN   transform locale issue d'une piste d'animation
PROCEDURAL         transform locale calculée par un système (roue, direction,
                   compression de suspension, aiguille)
JOINT_DRIVEN       transform locale issue de l'état d'un joint physique
                   (porte sur charnière, capot, bras de machine)
PHYSICS_DRIVEN     transform monde issue d'un body ; la locale est recalculée
DETACHED           le node appartient désormais à une autre assembly (débris)
```

- R-930 : priorité en cas de sources multiples : `DETACHED` > `PHYSICS_DRIVEN` > `JOINT_DRIVEN` > `PROCEDURAL` > `ANIMATION_DRIVEN` > `STATIC`. Un conflit déclaré est un avertissement de compilation, la priorité s'applique.
- R-931 : un node `PHYSICS_DRIVEN` ne peut pas avoir de parent `ANIMATION_DRIVEN` ou `PROCEDURAL` (`E-7002`).
- R-932 : `JOINT_DRIVEN` est le mécanisme par lequel une porte animée peut devenir physique : l'animation pilote le **moteur** du joint plutôt que la transform, ce qui permet à la physique de reprendre la main à tout moment (PARTIE 18.5).
- R-933 : la **déformation ne change jamais l'état d'un node** ni sa transform (R-601).

## 9.3 Sockets

```java
public interface Socket {
    String name();
    Matrix4f worldTransform();     // interpolé sur client, autoritatif sur serveur
    Vector3d worldPosition();
    boolean  isValid();            // false si la part porteuse est détruite ou détachée
    float    misalignment();       // m, écart dû à la déformation locale
}
```

- R-940 : un socket dont la part porteuse est détruite devient invalide ; toute attache est libérée avec un événement.
- R-941 : la position d'un socket **tient compte de la déformation** : elle est calculée en appliquant le champ de la région englobante au point d'ancrage. `misalignment()` expose l'écart par rapport à la position nominale, ce qui permet à une definition de déclarer qu'au-delà d'un seuil un mécanisme se grippe ou une attache se rompt.

## 9.4 Visibilité, LOD et révélation

- R-950 : chaque node porte un `lod_mask` ; l'intérieur d'un véhicule est typiquement `lod=[0]`.
- R-951 : le LOD visuel ne modifie **jamais** les colliders.
- R-952 : un node `INTERNAL` est invisible tant que la part qui le couvre est `INTACT` ; il devient visible dès que cette part atteint l'étape déclarée (`REVEALED_ON_DAMAGE`, seuil déclaratif). C'est le mécanisme générique « laisser apparaître les éléments internes ».

## 9.5 Instanciation et partage

- R-960 : plusieurs assemblies partageant le même asset partagent la **même mémoire de géométrie** (natif et GPU). Seuls l'état, les transforms, les palettes, **les champs de déformation** et les listes de décalques sont par instance.
- R-961 : compte de références explicite ; déchargement à zéro référence après `assets.unload_delay_s` (défaut 60 s).
- R-962 : une assembly intacte ne possède **aucun** champ de déformation alloué (INV-16). L'allocation est paresseuse, au premier impact dépassant le seuil.

**Tests.** T-610..T-613, T-808, T-954.

---
# PARTIE 10 : PHYSIQUE GÉNÉRIQUE

## 10.1 Principe

Le moteur physique est **générique** : il connaît des bodies, des colliders, des matériaux, des contraintes et des forces. Tout comportement spécifique est le produit d'une **composition de données** (P-01, INV-01).

## 10.2 Types de corps

| Type | Intégré | Déplaçable | Masse | Usage |
|---|---|---|---|---|
| `STATIC` | non | non | infinie | décor, tuiles de collision monde |
| `KINEMATIC` | non | par transform imposée | infinie | plateformes, proxies d'entités vanilla, objets scriptés |
| `DYNAMIC` | oui | par forces | finie | objets, véhicules, pièces détachées, débris |

## 10.3 Formes de collision

```text
PRIMITIVES  Sphere, Box, Capsule, Cylinder, Cone
CONVEXE     ConvexHull (4..256 points)              <- seule forme refitable
COMPOSÉ     Compound (jusqu'à 64 enfants)
CONCAVE     TriMesh   (statique et kinematic uniquement)
TERRAIN     Heightfield (statique uniquement)
```

- R-970 : `TriMesh` et `Heightfield` sont interdits sur un body dynamique (INV-13), **y compris comme résultat d'une déformation**. Le refit produit toujours des convexes.
- R-971 : `Cylinder` et `Cone` sont supportés mais documentés comme plus coûteux et moins stables ; le générateur automatique ne les produit jamais et le refit les convertit en `ConvexHull` avant déformation.

## 10.4 Broad phase / narrow phase

```text
BROAD  grille SAP multi-résolution (Rapier), physics.broadphase_cell_size (2.0)
NARROW algorithmes de Parry (GJK/EPA convexes, tests spécialisés primitives)
FILTRE (group & other.mask) != 0 && (other.group & mask) != 0
       + exclusion intra-assembly sauf déclaration explicite
```

- R-980 : les groupes de collision sont data-driven, attribués par nom. Groupes réservés : `world`, `assembly`, `part`, `debris`, `entity_proxy`, `sensor`, `particle`, `debug`.

## 10.5 Intégration et solveur

```text
Chaque sous-pas dt = sim.fixed_dt :
  1. forces externes (gravité, flottabilité, traînée, portance, vent, API)
  2. intégration des vitesses
  3. broad puis narrow phase
  4. résolution des contacts et des joints (solveur itératif à impulsions)
  5. intégration des positions
  6. CCD pour les bodies concernés
  7. mise à jour du sommeil
```

- R-990 : `sim.fixed_dt` ∈ {1/30, 1/60, 1/120}, défaut 1/60 ; `sim.max_substeps` défaut 4 ; accumulateur clampé, aucune spirale de rattrapage.
- R-991 : itérations du solveur configurables et **réductibles automatiquement** par le gouverneur de qualité côté client uniquement ; côté serveur, elles ne sont réduites que par la dégradation générale, ce qui est journalisé.

## 10.6 Forces environnementales

| Force | Modèle | Paramètres |
|---|---|---|
| Gravité | `F = m·g·gravity_scale` | par dimension |
| Flottabilité | `F = ρ_fluide·V_immergé·g`, V approché par 8 points de l'AABB | densité du fluide |
| Traînée fluide/air | `F = −0.5·ρ·Cd·A·v·|v|` | `Cd`, `A` déclarés |
| Portance | `F = 0.5·ρ·Cl·A·|v|²` sur surfaces déclarées | surfaces portantes déclaratives |
| Vent | champ configurable par dimension | `physics.wind` |

- R-1000 : les surfaces portantes déclaratives rendent possibles avions et bateaux **sans aucun système dédié**. Il n'existe ni système « avion » ni système « bateau » : ce sont des compositions de masse, de surfaces, de moteurs et de contraintes.

## 10.7 Événements physiques

```rust
#[repr(C)]
pub struct PhysicsEvent {
    pub kind: u32,   // CONTACT_START | CONTACT_END | CONTACT_IMPULSE | SENSOR_ENTER |
                     // SENSOR_EXIT | JOINT_BROKEN | JOINT_JAMMED | SLEEP | WAKE |
                     // CLAMPED | RECOVERED | ATTACH | DETACH_ATTACHMENT
    pub assembly_a: AssemblyHandle, pub assembly_b: AssemblyHandle,
    pub node_a: u32, pub node_b: u32,
    pub point: [f32;3], pub normal: [f32;3],
    pub impulse: f32, pub tangent_impulse: f32, pub relative_velocity: f32,
    pub effective_mass: f32,
    pub material_a: u16, pub material_b: u16,
    pub data: u32,
}
```

- R-1010 : les événements sont produits en natif, transmis par lot, appliqués côté Java sur le thread autoritatif.
- R-1011 : plafond `physics.max_events_per_tick` (défaut 4096) ; au-delà, agrégation par paire (impulsion maximale conservée) et compteur de perte. Aucune perte silencieuse.
- R-1012 : les contacts sous `physics.contact_event_threshold` (défaut 0.5 N·s) ne remontent pas à Java, mais **sont tout de même transmis à C-41** si leur impulsion dépasse le seuil d'usure, afin de permettre les rayures d'un frottement léger.

## 10.8 Ce que la physique AXION ne fait pas

```text
- elle ne remplace pas la physique des entités vanilla
- elle ne modifie pas le mouvement des joueurs (sauf assis)
- elle ne pousse pas les blocs
- elle ne casse pas de blocs par défaut ; si une definition l'active, cela passe
  par les API vanilla, sur le thread autoritatif, avec les événements Forge
  correspondants, et respecte les protections de zone
- elle ne simule pas les fluides
- elle n'est pas déterministe entre machines (voir 10.9)
```

## 10.9 Déterminisme

- **Décision (ADR-005)** : AXION n'exige pas de déterminisme bit-à-bit **de la physique** entre machines. L'autorité serveur rend le lockstep inutile, et l'exiger interdirait la parallélisation et le SIMD.
- **Décision complémentaire (ADR-018)** : la **chaîne de dommage** (impacts → dommage → déformation) est, elle, **déterministe** et calculée par le noyau C-16, afin que le client puisse la reconstruire depuis les événements plutôt que de recevoir des données (P-15, INV-14). Cette exigence est locale à une fonction pure : elle n'impose rien à la physique.
- R-1020 : la simulation est **reproductible sur une même machine et un même binaire** pour une même séquence d'entrées : ordre d'itération stable (jamais de `HashMap` non ordonnée dans un chemin de simulation), fusion des résultats parallèles dans un ordre fixe, PRNG à graine explicite.

**Tests.** T-620..T-622, T-820..T-822.

---

# PARTIE 11 : MATÉRIAUX PHYSIQUES ET MODÈLE DE MATIÈRE

## 11.1 Rôle

Le `PhysicsMaterial` (DM-07) est le **seul** point où sont déclarées les propriétés de matière. Il gouverne : le contact, la déformation, la rupture, l'usure, le son et les particules d'impact. Aucun comportement de matière n'est codé en dur (P-01).

## 11.2 Groupes de propriétés

```text
CONTACT       friction, restitution, rolling_friction, modes de combinaison
MASSE         density
RIGIDITÉ      stiffness (Pa), hardness_pressure (Pa), deformation_resistance (—)
SEUILS        yield_strength (Pa), fracture_strength (Pa), max_strain (—),
              fracture_energy (J/m²)
GÉOMÉTRIE     indenter_radius_ref (m), spread_factor (—), thickness_spread (—)
COMPORTEMENT  elasticity, plasticity, damping
DOMMAGE       damage_multiplier, energy_absorption
SURFACE       hardness, scratch_resistance, burn_threshold, soil_rate
DRAPEAUX      BRITTLE, DUCTILE, SOFT, RIGID, FLAMMABLE, NON_DEFORMABLE
EFFETS        sound_group, particle_group
```

## 11.3 Sémantique normative des paramètres de déformation

```text
stiffness             module apparent, en Pa. Détermine le RAYON de déformation :
                      un matériau rigide concentre l'enfoncement, un matériau
                      souple le répartit.
yield_strength        contrainte au-delà de laquelle une part du déplacement
                      devient permanente. En dessous, tout est élastique.
fracture_strength     contrainte au-delà de laquelle la matière rompt localement
                      (déchirure si ALLOW_TEAR, sinon plafonnement + dommage
                      structurel accru).
elasticity  [0,1]     fraction du déplacement élastique restituée.
plasticity  [0,1]     fraction du dépassement de yield convertie en permanent.
max_strain  [0,1]     déformation relative maximale avant rupture obligatoire.
damping               amortissement du retour élastique (oscillation de tôle).
hardness_pressure (Pa) dureté dynamique d'indentation : pression moyenne sous
                      l'indenteur pendant l'enfoncement plastique. C'est elle qui
                      convertit une ÉNERGIE en un VOLUME enfoncé (13.3bis).
                      Valeur par défaut si non déclarée : 3 x yield_strength
                      (relation de Tabor), bornée à [1e5, 1e11] Pa.
indenter_radius_ref (m) rayon d'indenteur de référence utilisé lorsque la courbure
                      réelle du contact n'est pas exploitable. Modulé par les
                      drapeaux SHARP et BLUNT.
spread_factor     (—) étalement latéral du champ, appliqué au rayon de contact.
thickness_spread  (—) étalement supplémentaire proportionnel à l'épaisseur de la
                      région (une tôle mince répartit l'enfoncement plus loin).
deformation_resistance (—) DIVISEUR sans dimension appliqué à la profondeur
                      obtenue. Une valeur > 1 rend la matière plus difficile à
                      enfoncer, une valeur < 1 plus facile. Plage [0.01, 100].
fracture_energy (J/m²) énergie spécifique de rupture (travail de rupture par
                      unité d'aire de liaison rompue, analogue au taux de
                      restitution d'énergie critique Gc). Multipliée par une AIRE
                      elle produit une capacité en joules (15.1bis).
energy_absorption[0,1] fraction de l'énergie d'impact convertie en travail de
                      déformation et de dommage (le reste part en restitution,
                      en son et en mouvement).
```

**Comportements typiques obtenus par composition, sans code dédié :**

| Matière | Réglage caractéristique | Résultat observé |
|---|---|---|
| Acier de carrosserie | `DUCTILE`, yield modéré, plasticity élevée, fracture élevé | se cabosse, garde la forme, ne casse pas |
| Aluminium | idem, stiffness plus faible | s'enfonce plus largement |
| Plastique de pare-chocs | `SOFT`, elasticity élevée, yield haut | absorbe et revient, marque peu |
| Verre | `BRITTLE`, `NON_DEFORMABLE`, fracture bas | ne se déforme pas, casse net |
| Caoutchouc | `SOFT`, elasticity ≈ 1, plasticity ≈ 0 | rebondit, ne garde rien |
| Bois | `BRITTLE` modéré, plasticity faible | fend et se rompt |
| Pierre | `RIGID`, `NON_DEFORMABLE`, fracture élevé | ne bouge pas, casse par blocs |
| Tissu | `SOFT`, géré par le solveur de particules | drapé |
| Chair | `SOFT`, plasticity faible, elasticity moyenne | s'enfonce et revient |

- R-1030 : la bibliothèque de matériaux de base (`axion:steel`, `aluminium`, `plastic`, `glass`, `rubber`, `wood`, `stone`, `fabric`, `flesh`, `concrete`, `carbon_fiber`) est fournie comme **contenu de datapack** du mod, surchargeable et extensible. Le moteur n'a aucune connaissance de ces noms.
- R-1031 : l'héritage est supporté (`"parent": "axion:steel"`), avec surcharge champ par champ.
- R-1032 : les valeurs par défaut d'un matériau non déclaré sont celles d'un acier générique, avec avertissement à la compilation de la definition.

## 11.4 Mappage bloc → matériau

```text
data/<ns>/axion/block_materials/<name>.json
{
  "schema": 1,
  "default": "axion:stone",
  "by_tag":   { "minecraft:ice": "axion:ice", "minecraft:logs": "axion:wood" },
  "by_block": { "minecraft:sand": "axion:sand" }
}
```

- R-1040 : la résolution suit l'ordre `by_block` → `by_tag` → `default`. Aucun nom de bloc n'apparaît dans le moteur : ce fichier est du contenu.
- R-1041 : ce mappage alimente la friction des roues, l'énergie des impacts contre le monde, le son et les particules.

**Tests.** T-840 (héritage de matériaux), T-841 (mappage par tag), T-842 (chaque comportement typique du tableau 11.3 est reproduit par un scénario de référence).

---

## 11.5 Cohérence dimensionnelle (référence normative)

Toutes les grandeurs du moteur sont en unités SI (R-102). Le tableau suivant est
la référence unique ; il est vérifié mécaniquement par la suite de tests
dimensionnels `T-xxxb`, qui recalcule chaque formule avec des unités symboliques.

| Grandeur | Symbole | Unité SI | Origine |
|---|---|---|---|
| Masse | m, m_eff | kg | `BodyDesc.mass`, masse réduite du contact |
| Vitesse relative | v_n, v_t | m/s | narrow phase |
| Impulsion | J | N·s | narrow phase |
| Énergie d'impact | E, E_n, E_t | J | `0.5·m_eff·v²` |
| Densité | density | kg/m³ | matériau |
| Module apparent | stiffness | Pa | matériau |
| Dureté d'indentation | hardness_pressure | Pa | matériau |
| Limite d'élasticité | yield_strength | Pa | matériau |
| Résistance à la rupture | fracture_strength | Pa | matériau |
| **Énergie spécifique de rupture** | fracture_energy | **J/m²** | matériau |
| Rayon d'indenteur | R_eff, indenter_radius_ref | m | contact / matériau |
| Volume indenté | V_ind, V_def | m³ | `E / hardness_pressure` |
| Profondeur d'indentation | d_ind, depth | m | `sqrt(V/(pi·R))` |
| Aire de contact | A | m² | `2·sqrt(pi·R·V)` |
| Aire de liaison | A_link | m² | estimation géométrique 15.1bis |
| Bras de levier de liaison | r_link | m | estimation géométrique 15.1bis |
| Contrainte apparente | sigma | Pa | `E / (A · thickness)` |
| Capacité de liaison | capacity | J | `fracture_energy · A_link` |
| Effort de rupture | tensile, shear | N | `fracture_strength · A_link` |
| Couple de rupture | torque | N·m | `shear · r_link` |
| Épaisseur | thickness | m | région |
| Déplacement de nœud | d_i | m | champ, quantifié i8 |
| Déformation relative | strain, max_strain | sans dimension | norme de `F − I` |
| Facteurs d'étalement | spread_factor, thickness_spread | sans dimension | matériau |
| Résistance à la déformation | deformation_resistance | sans dimension (diviseur) | matériau |
| Fractions | elasticity, plasticity, energy_absorption | sans dimension, dans [0,1] | matériau |

**Règles normatives.**

- R-1035 : aucune formule du moteur ne DOIT combiner des grandeurs d'unités
  incompatibles. Toute nouvelle formule est accompagnée de sa vérification
  dimensionnelle dans le commentaire de tête du module et d'un test `T-xxxb`.
- R-1036 : une énergie n'est **jamais** multipliée par une aire. Une énergie
  divisée par une pression donne un volume ; une énergie **spécifique** (J/m²)
  multipliée par une aire donne une énergie.
- R-1037 : l'intersection de deux volumes est un **volume** (m³). Toute grandeur
  surfacique dérivée d'une intersection de boîtes DOIT être obtenue par une
  projection ou une section explicitement définie (15.1bis), jamais par
  assimilation d'un volume à une aire.
- R-1038 : lorsqu'un matériau ne déclare pas `hardness_pressure`, la valeur
  `3 × yield_strength` est utilisée et journalisée une fois par matériau
  (relation de Tabor, approximation documentée).

**Tests dimensionnels associés.** T-830b, T-806b, T-810b, T-840b.

# PARTIE 12 : VÉHICULES

## 12.1 Positionnement

Le système de véhicules est une **application** du moteur générique : un châssis (body dynamique) + N roues (raycasts + forces) + un groupe motopropulseur + des entrées. Il n'introduit aucun collider spécial ni aucune règle réservée, et il subit la chaîne de dommage exactement comme n'importe quelle assembly.

## 12.2 Modèle de roue

**Décision (ADR-003).** Roues par **raycast** (« raycast vehicle »), pas par corps rigides roulants.

Motifs : stabilité à haute vitesse, coût constant, absence de tunneling, contrôle direct du modèle de pneu, comportement prévisible sur géométrie en blocs.

```text
Pour chaque roue, à chaque sous-pas :
 1. rayon depuis l'ancrage de suspension, direction -up du châssis,
    longueur = rest_length + max_travel + radius
 2. si contact :
      compression = (rest_length + radius - distance) / max_travel
      F_ressort   = stiffness * compression * mass_ref
      F_amort     = damping * d(compression)/dt
      F_susp      = clamp(F_ressort + F_amort, 0, max_force)
      appliquer F_susp * normale au châssis au point d'ancrage
 3. vitesses de glissement longitudinale et latérale au point de contact
 4. forces de pneu (12.4) dans le plan de contact
 5. sinon : roue en l'air, seule la traînée de rotation s'applique
```

- R-1050 : le rayon inclut le rayon de la roue (détection avant interpénétration).
- R-1051 : un raycast de roue ignore les colliders de sa propre assembly, les proxies d'entités et les débris de la même assembly durant `vehicle.debris_ignore_s` (défaut 1 s).
- R-1052 : nombre de roues libre (1 à 32) ; aucune architecture n'est privilégiée.
- R-1053 : **couplage avec la déformation** : l'ancrage de suspension d'une roue suit la déformation de la région qui le porte. Un impact qui enfonce un passage de roue décale l'ancrage, ce qui modifie le carrossage apparent et la géométrie de direction — sans aucune règle dédiée. L'amplitude de ce décalage est bornée par `vehicle.max_suspension_misalignment` (défaut 0.15 m) au-delà duquel la roue est déclarée `JAMMED` (elle ne tourne plus librement) puis, au seuil structurel, se détache.

## 12.3 Suspension

```json
"suspension": {
  "rest_length": 0.35, "max_travel": 0.25, "stiffness": 30.0,
  "damping_compression": 2.3, "damping_rebound": 2.8,
  "max_force": 60000.0, "anti_roll": 0.0
}
```

- R-1060 : paramètres exprimés **relativement à la masse**, pour rester plausibles sur des masses différentes.
- R-1061 : l'anti-roulis est un couple entre deux roues d'un essieu **déclaré**, jamais déduit.

## 12.4 Modèle de pneu

Courbe de glissement normalisée + cercle de friction combinée.

```text
slip_long = (v_roue - v_sol) / max(|v_sol|, eps)
slip_lat  = atan2(v_lat, |v_long|)
F_long = F_n * mu_long * curve(slip_long)
F_lat  = F_n * mu_lat  * curve(slip_lat)
cercle : si sqrt(F_long² + F_lat²) > mu * F_n -> mise à l'échelle des composantes
```

`curve(s)` est déclarative (4 points : `peak_slip`, `peak_value`, `tail_slip`, `tail_value`), ce qui couvre route, tout-terrain, glace et chenilles sans code spécifique.

- R-1070 : le coefficient effectif est le produit du coefficient du pneu et de celui du **matériau physique de la surface touchée** (11.4). La glace est glissante sans aucune règle dédiée à la glace.
- R-1071 : un pneu peut être **endommagé** : sa part porte une santé ; en dessous d'un seuil déclaré, `peak_value` est multiplié par un facteur déclaré et le rayon effectif diminue (crevaison). C'est une application du modèle de dommage générique, pas un système « crevaison ».

## 12.5 Groupe motopropulseur

```json
"powertrain": {
  "engine": { "torque_curve": [[0,0],[1000,180],[3000,260],[5500,240],[7000,180]],
              "idle_rpm": 800, "max_rpm": 7000, "inertia": 0.3, "braking_torque": 25.0 },
  "transmission": { "type": "manual", "gears": [-3.2,0.0,3.5,2.1,1.4,1.0,0.8],
                    "final_drive": 3.7, "shift_time": 0.25,
                    "auto_shift_up_rpm": 6200, "auto_shift_down_rpm": 2200,
                    "efficiency": 0.92 },
  "differential": { "type": "open", "front_rear_split": 0.0, "lock": 0.0 },
  "drive_wheels": ["wheel_rl", "wheel_rr"]
}
```

- R-1080 : `type: "direct"` court-circuite la transmission (moteurs électriques, treuils, machines).
- R-1081 : différentiels `open`, `locked`, `limited_slip` ; la transmission intégrale s'obtient par un différentiel central déclaré.
- R-1082 : la simulation du groupe motopropulseur est **optionnelle** : une definition peut piloter directement le couple par roue via l'API.
- R-1083 : **couplage avec le dommage** : la part portant le moteur, la transmission ou le radiateur peut être `DISABLED` par le modèle de dommage ; l'effet (couple nul, régime plafonné, surchauffe) est **déclaratif** dans la definition (`"on_part_disabled": {"engine": {"torque_scale": 0.0}}`), jamais codé en dur.

## 12.6 Direction et freinage

```json
"steering": { "max_angle": 0.61, "speed": 3.0, "return_speed": 4.0,
              "speed_sensitivity": 0.6, "ackermann": 1.0,
              "steered_wheels": ["wheel_fl","wheel_fr"] },
"brakes":   { "max_torque_front": 3000.0, "max_torque_rear": 1800.0,
              "handbrake_torque": 4000.0, "handbrake_wheels": ["wheel_rl","wheel_rr"],
              "abs": { "enabled": false, "slip_threshold": 0.15 } }
```

- R-1090 : ABS, antipatinage et contrôle de stabilité sont des **modules déclaratifs** optionnels, désactivés par défaut, implémentés comme des correcteurs sur les couples.
- R-1091 : une déformation de la région portant la crémaillère au-delà d'un seuil déclaré introduit un **biais de direction** persistant (`steering_bias`), effet émergent du couplage déformation/socket (R-941), déclaré et borné.

## 12.7 Aérodynamique

```json
"aero": { "drag_coefficient": 0.32, "frontal_area": 2.2,
          "downforce_front": 0.0, "downforce_rear": 0.0,
          "surfaces": [ { "node": "wing_l", "area": 1.2, "cl": 0.8, "cd": 0.05 } ] }
```

- R-1100 : la déformation d'une région augmente le `drag_coefficient` effectif selon un facteur déclaré proportionnel au déplacement plastique moyen — effet émergent, borné, désactivable.

## 12.8 Entrées

```rust
#[repr(C)]
pub struct VehicleInput {
    pub throttle: f32, pub brake: f32, pub steer: f32, pub clutch: f32,
    pub handbrake: f32, pub gear: i8, pub flags: u8, pub _pad: [u8;2],
}
```

- R-1110 : toute entrée réseau est clampée dans ses bornes avant usage ; NaN → zéro et journalisation (INV-02).

## 12.9 Tests véhicules

```text
T-320  équilibre de suspension au repos, pas d'enfoncement
T-321  accélération : vitesse croissante, monotone, plafonnée
T-322  freinage : distance d'arrêt cohérente et reproductible
T-323  virage : rayon cohérent avec l'angle et l'empattement (± 10 %)
T-324  perte d'adhérence sur faible friction, récupération
T-325  franchissement d'escalier de blocs sans blocage ni éjection
T-326  chute de 10 blocs : atterrissage sans explosion numérique
T-327  véhicules à 6 roues et à 1 roue fonctionnels sans code spécifique
T-328  différentiel : roue en l'air ne consomme pas tout le couple en locked
T-329  100 véhicules simultanés : budget respecté ou dégradation propre
T-960  moteur DISABLED par dommage -> couple nul selon la definition
T-961  passage de roue enfoncé -> décalage d'ancrage borné puis JAMMED
T-962  pneu endommagé -> adhérence et rayon réduits selon la definition
T-963  déformation -> augmentation bornée de la traînée
```

---
# PARTIE 13 : IMPACTS, ÉNERGIE ET MODÈLE DE DOMMAGE

## 13.1 Principe

Le dommage n'est **jamais** une variable abstraite décrémentée. Il est le produit d'un événement physique réel, converti en énergie, localisé, puis distribué entre quatre effets : **déformation**, **dommage structurel**, **usure de surface** et **état fonctionnel**.

```text
contact physique -> ImpactDesc (C-41) -> distribution (C-35) -> 4 effets
```

Aucun de ces effets ne connaît le type d'objet concerné (P-01).

## 13.2 Sources d'impact

| Source | Origine | Conversion |
|---|---|---|
| `CONTACT` | manifold de contact de C-31 | directe (C-41) |
| `FALL` | contact au sol avec vitesse verticale dominante | `CONTACT` avec drapeau `BLUNT` |
| `CRUSH` | contact persistant à forte impulsion normale sur plusieurs ticks | `CONTINUOUS` |
| `EXPLOSION` | `ExplosionEvent.Detonate` vanilla ou API | rayon → N impacts échantillonnés sur l'enveloppe exposée |
| `PROJECTILE` | dégât de projectile vanilla ou API | impact ponctuel `SHARP` à l'endroit du raycast |
| `FIRE` | dégât de feu ou de lave vanilla | impact `THERMAL`, énergie = f(durée, source) |
| `API` | `Assembly.damage(...)` ou `applyImpact(...)` | direct, validé |

**Conversion d'une explosion (normative).**

```text
1. pour chaque assembly dans le rayon :
2.   échantillonner K points sur l'enveloppe convexe de l'assembly, K = clamp(
       surface_apparente / damage.explosion_sample_area, 4, 64)
3.   pour chaque point : raycast depuis le centre de l'explosion ;
       si occulté par un autre collider de la même assembly -> énergie réduite
       du facteur déclaré d'occultation interne
4.   énergie du point = E_total * exposition * attenuation(distance) / K
5.   normale = direction centre -> point ; drapeau BLUNT
6.   émettre un ImpactDesc par point
```

- R-1120 : la conversion des sources Minecraft est **déclarative** : `data/<ns>/axion/damage_sources/*.json` associe un type de `DamageSource` vanilla à un profil (`energy_per_damage_point`, `flags`, `sample_mode`). Le moteur ne connaît aucun nom de source ; le mod fournit un fichier par défaut couvrant les sources vanilla usuelles.
- R-1121 : une source non mappée produit un impact `BLUNT` d'énergie `damage * default_energy_per_point` au centre de masse, avec avertissement une seule fois par source.

## 13.3 Distribution de l'énergie (C-35)

```text
Entrée : ImpactDesc (part, zone, région, énergie E, aire A, normale N, drapeaux)

 1. multiplicateurs :
      E_eff = E * zone.multiplier * material.damage_multiplier
              * (zone.parent_zone ? parent.multiplier : 1)
 2. contrainte apparente :
      sigma = E_eff / (A * thickness)          [Pa]
 3. répartition (fractions déclarées par le matériau, somme = 1) :
      f_deform    = part de l'énergie allant à la déformation
      f_structure = part allant au dommage structurel
      f_surface   = part allant à l'usure
      f_lost      = part dissipée (son, chaleur, mouvement)
    Les trois premières fractions sont dérivées du matériau :
      f_deform    = plasticity_weight  = clamp(sigma / yield_strength, 0, 1) * ductility
      f_structure = clamp(sigma / fracture_strength, 0, 1)
      f_surface   = (1 - hardness) * shear_ratio
      puis normalisation, avec f_lost = 1 - somme, plancher à 0
 4. effets :
      C-42 reçoit (E_eff * f_deform, point, normale, aire, sigma)
      C-43 reçoit (E_eff * f_structure, part, liaisons attachées)
      C-47 reçoit (E_eff * f_surface, point, normale, drapeaux)
 5. santé fonctionnelle de la part :
      health -= E_eff / part.max_health_energy      (borné à 0)
      max_health_energy est dérivé de part.max_health et du matériau
 6. propagation vers la part parente : E_eff * part.propagation, en tant
    qu'impact secondaire de source CONTINUOUS, sans effet de surface
 7. étapes : INTACT -> SCRATCHED -> DAMAGED -> HEAVY -> DESTROYED,
    seuils déclarés par la definition, hystérésis pour éviter les oscillations
```

- R-1130 : un matériau `BRITTLE` force `f_deform = 0` et transfère cette part à `f_structure` : le verre ne se cabosse pas, il casse.
- R-1140 : un matériau `NON_DEFORMABLE` force `f_deform = 0` sans transfert : la pierre absorbe sans se marquer.
- R-1150 : la profondeur de la chaîne de propagation est plafonnée (`damage.max_propagation_depth`, défaut 4) pour empêcher toute récursion pathologique.
- R-1160 : un impact ne peut jamais produire plus d'énergie qu'il n'en apporte (conservation vérifiée par test T-837).

## 13.3bis Modèle d'indentation plastique (référence normative)

Le moteur doit dériver d'une énergie d'impact une **aire de contact** et une
**profondeur** dimensionnellement correctes, calculables en temps réel et
compatibles avec le noyau déterministe. Le modèle retenu est une **indentation
plastique à pression constante**, dans l'esprit de la relation de Meyer/Tabor.

**Hypothèses explicitement assumées.**

```text
H-I1  l'enfoncement est dominé par l'écoulement plastique et non par le contact
      hertzien élastique : le travail est absorbé à pression sensiblement
      constante H_eff sous l'indenteur
H-I2  l'indenteur est assimilé à une sphère de rayon effectif R_eff
H-I3  l'empreinte est une calotte sphérique peu profonde (d << R_eff)
H-I4  aucune dépendance à la vitesse de déformation n'est modélisée
```

**Formulation et vérification dimensionnelle.**

```text
V_ind = E / H_eff                        [J] / [Pa]            = m³
d_ind = sqrt( V_ind / (pi * R_eff) )     sqrt( m³ / m )        = m
A     = 2 * pi * R_eff * d_ind
      = 2 * sqrt( pi * R_eff * V_ind )   sqrt( m * m³ )        = m²
```

La seconde écriture de `A` est celle réellement utilisée : elle évite de
propager `d_ind` et n'emploie que `+ - * / sqrt`, donc elle reste utilisable par
le noyau déterministe (R-510). L'ancienne formulation en puissance `2/3` est
supprimée : elle était dimensionnellement fausse et exigeait `powf`, interdit
dans un chemin déterministe.

**Domaine de validité et bornes.**

```text
E = 0                -> V_ind = 0, d_ind = 0, A = A_min       (aucun dommage)
E petit              -> A et d_ind croissent en sqrt(E)       (monotone)
d_ind > R_eff / 2    -> H-I3 violée : d_ind plafonné à R_eff/2 et A à pi*R_eff²,
                        incrément de axion.impact.deep_clamped
A_min = thickness²                    plancher, empêche une aire nulle
A_max = aire projetée de l'AABB de la part sur le plan de contact
R_min = thickness / 2 , R_max = plus petite demi-dimension de l'OBB de la région
H_eff borné à [1e5, 1e11] Pa
```

- R-1155 : ce modèle est une **approximation temps réel documentée**, ni une
  résolution du contact de Hertz, ni une simulation FEM. Hypothèses, formulation
  et limites sont reprises dans `DAMAGE.md`.
- R-1156 : `sharp_factor` et `blunt_factor` sont déclarés (matériau ou profil de
  source de dégât), sans dimension, bornés à `[1, 64]` et `[1, 16]`. Un impact
  `SHARP` réduit `R_eff`, donc réduit `A` et augmente `d_ind` à énergie égale :
  comportement attendu d'une pointe.
- R-1157 : toutes les grandeurs produites sont finies et bornées avant écriture
  dans l'`ImpactDesc` ; une valeur non finie rejette l'impact (`E-8020`).
- R-1158 : la stabilité numérique est assurée par les bornes ci-dessus et par le
  plancher `A_min` : aucune division par une quantité pouvant tendre vers zéro
  n'apparaît dans le chemin.

## 13.4 Effets fonctionnels

Les conséquences de gameplay sont **entièrement déclaratives** :

```json
"on_part_stage": {
  "engine":     { "DAMAGED":  { "set_variable": { "engine_power": 0.6 } },
                  "DESTROYED":{ "set_variable": { "engine_power": 0.0 },
                                "emit_event": "engine_dead" } },
  "door_l":     { "HEAVY":    { "jam_joint": "door_l_hinge" } },
  "headlight_l":{ "DESTROYED":{ "hide_node": "headlight_l_beam" } },
  "glass_ws":   { "DESTROYED":{ "swap_mesh": "windshield_broken",
                                "spawn_debris": "axion:debris/glass" } }
}
```

- R-1170 : la liste des actions est **fermée** : `set_variable`, `emit_event`, `hide_node`, `show_node`, `swap_mesh`, `jam_joint`, `break_joint`, `detach_part`, `disable_seat`, `spawn_debris`, `play_animation`, `set_material_param`. Toute action supplémentaire passe par l'API Java (ADR-009).
- R-1171 : les variables définies alimentent les sources procédurales et les contrôleurs déclaratifs (par exemple `engine_power` multiplie le couple). Le moteur n'attribue **aucune** sémantique à ces noms.

## 13.5 Dommage continu

- R-1180 : un contact persistant à forte charge (écrasement, véhicule sur le toit, poids d'un autre objet) produit un impact `CONTINUOUS` par tick, d'énergie `impulse_normale * v_relative * dt`, avec un plafond par tick et une agrégation par cellule. Cela couvre l'écrasement progressif sans mécanisme dédié.
- R-1181 : le feu produit un impact `THERMAL` par tick tant que la source est active, alimentant l'usure `burn` et, au-delà du seuil du matériau `FLAMMABLE`, le dommage structurel.

## 13.6 Budgets et dégradation

| Élément | Budget | Comportement en surcharge |
|---|---|---|
| Impacts par tick | `damage.max_impacts_per_tick` (512) | agrégation par cellule, jamais perte silencieuse |
| Temps de la chaîne de dommage | `budgets.damage_ns_per_tick` (1 ms) | report des impacts de plus faible énergie au tick suivant, file bornée |
| Profondeur de propagation | 4 | tronquée |
| Assemblies traitées par tick | `damage.max_assemblies_per_tick` (64) | rotation équitable, priorité aux assemblies visibles par un joueur |

- R-1190 : le report d'impacts est **borné** (`damage.pending_queue_max`, défaut 4096) ; au-delà, agrégation forcée. Le compteur de report est une métrique.

## 13.7 Tests

```text
T-830..T-835  (C-41, voir 5.33)
T-836  conversion d'une explosion vanilla en impacts échantillonnés
T-837  conservation de l'énergie : somme des effets <= énergie de l'impact
T-838  matériau BRITTLE : aucune déformation, rupture au seuil
T-839  matériau NON_DEFORMABLE : ni déformation ni transfert
T-843  propagation vers la part parente, profondeur plafonnée
T-844  étapes avec hystérésis, pas d'oscillation
T-845  actions déclaratives appliquées (chaque action de la liste fermée)
T-846  dommage continu par écrasement
T-847  dommage thermique depuis un feu vanilla
T-848  budget dépassé -> report borné, aucune perte silencieuse
T-849  source de dégât non mappée -> impact par défaut, avertissement unique

TESTS DU MODÈLE D'INDENTATION (13.3bis)
T-830b DIMENSIONS : chaque grandeur du modèle est recalculée avec des unités
       symboliques ; V_ind en m³, d_ind en m, A en m², sigma en Pa. Toute
       expression produisant une unité inattendue fait échouer le test.
T-830c PÉNÉTRATION NULLE : E = 0 -> V_ind = 0, d_ind = 0, A = A_min, aucun
       dommage, aucune allocation de champ
T-830d PÉNÉTRATION FAIBLE : E juste au-dessus de damage.min_impact_energy ->
       d_ind > 0, d_ind << R_eff, A comprise entre A_min et A_max
T-830e PÉNÉTRATION CROISSANTE : sur 20 énergies croissantes, d_ind et A sont
       strictement croissantes et suivent sqrt(E) à 1 % près
T-830f BORNES : E extrême -> d_ind plafonné à R_eff/2, A plafonnée à pi*R_eff²,
       compteur deep_clamped incrémenté ; E négative ou non finie -> rejet E-8020
T-830g STABILITÉ NUMÉRIQUE : R_eff -> R_min, thickness -> 0+, H_eff -> bornes,
       10 000 tirages aléatoires : aucun NaN, aucun Inf, aucune division par zéro
T-830h SHARP/BLUNT : à énergie égale, SHARP donne A plus petite et d_ind plus
       grande que BLUNT, dans les bornes déclarées
T-840b UNITÉS DES MATÉRIAUX : chaque champ de PhysicsMaterial est validé contre
       l'unité déclarée en 11.5 ; une valeur hors plage physique est refusée
```

---

# PARTIE 14 : DÉFORMATION CONTINUE DE GÉOMÉTRIE

> Cette partie spécifie le système central de la révision 2. Il est **générique** : il s'applique à toute géométrie portant une région de déformation, quelle que soit la nature de l'objet (carrosserie, panneau, porte, structure, machine, bloc complexe, créature, objet souple). Aucune règle n'y dépend d'un type de contenu.

## 14.1 Évaluation des approches

| Approche | Coût CPU | Coût GPU | Coût mémoire | Coût réseau | Qualité | Verdict |
|---|---|---|---|---|---|---|
| Variantes de mesh (intact/abîmé/détruit) | nul | nul | faible | 2 bits | discrète, non progressive | **conservé comme fallback Q-0** |
| **Champ de déformation par lattice (FFD trilinéaire)** | faible (N nœuds ≪ N sommets) | nul en CPU, quelques lectures en vertex shader | 1,5 à 12 KiB par région | événements seulement | continue, lisse, progressive | **retenu, cœur du système** |
| Résidu épars par sommet | modéré, borné | une lecture supplémentaire | 8 octets par sommet touché | jamais transmis | détails fins, arêtes vives | **retenu aux niveaux Q-3/Q-4** |
| Déformation par bones dédiés | faible | nulle (déjà skinné) | négligeable | poses | limitée aux articulations | **retenu comme complément déclaratif** |
| Morph targets | faible | mémoire GPU importante | élevé (une copie par cible) | poids | prédéfinie par l'auteur | **retenu comme complément déclaratif Q-2+** |
| Simulation masse-ressort sur le lattice | modéré | nulle | +2 tableaux | événements | plis, oscillations, propagation | **retenu au niveau Q-4** |
| FEM volumétrique complet | très élevé | — | très élevé | — | physiquement exact | **hors périmètre** (exigence 6) |
| Déformation par sommet non structurée | élevé (upload par frame) | élevé | très élevé | prohibitif | excellente | **rejeté** |
| Fracture Voronoï dynamique | très élevé | élevé | élevé | prohibitif | excellente | **hors périmètre**, remplacée par la découpe en parts précompilée |

**Décision (ADR-011).** Le système retenu est un **champ de déformation par lattice (FFD trilinéaire) avec composantes élastique et plastique**, complété par : un **résidu épars par sommet** aux niveaux élevés, une **simulation masse-ressort sur les nœuds du lattice** au niveau maximal, et des **compléments déclaratifs** (bones de déformation, morph targets) que l'auteur peut ajouter. Les variantes de mesh restent le fallback du niveau `Q-0`.

Ce choix satisfait simultanément : progressivité, généricité, coût indépendant de la densité du maillage, stockage compact, réplication par événements, et compatibilité avec le skinning et les LOD.

## 14.2 Modèle mathématique

Une région définit une boîte orientée (OBB) et un lattice régulier de `res.x × res.y × res.z` nœuds.

```text
Coordonnées normalisées d'un point p en espace de part :
    u = OBB⁻¹(p) ∈ [0,1]³

Déplacement au point p (interpolation trilinéaire des 8 nœuds de la cellule) :
    D(p) = Σ_{i∈cellule} w_i(u) · d_i          avec Σ w_i = 1

Position déformée d'un sommet v :
    v' = v + def_w(v) · D(v)

Gradient de déformation (pour les normales) :
    F(p) = I + ∂D/∂p = I + def_w · Σ_i (∇w_i(u) · OBB⁻¹) ⊗ d_i

Normale déformée :
    n' = normalize( cof(F) · n )    où cof(F) = det(F) · F⁻ᵀ
Tangente déformée :
    t' = normalize( F · t )
```

- R-1200 : `cof(F)` est calculé **analytiquement** dans le vertex shader et dans le solveur CPU. Aucune recalcul de normales par parcours de triangles n'est nécessaire — c'est ce qui rend la déformation compatible avec des meshes très denses sans coût CPU (exigence 8).
- R-1201 : lorsque `det(F) <= 0` (inversion locale), la normale est conservée et un compteur `axion.deform.inverted_cells` est incrémenté. Le champ est alors localement clampé au tick suivant.
- R-1202 : un node portant `NO_DEFORM_NORMALS` conserve ses normales d'origine (utile pour les surfaces où la modification des normales produirait des artefacts, par exemple une texture plate).

## 14.3 Composantes du champ

```text
d_i = d_plastique_i + d_élastique_i

d_plastique  i8[3], pas de quantification quant_step = max_disp / 127
             AUTORITATIF, persisté, répliqué par reconstruction
d_élastique  f16[3], transitoire, jamais persisté ni répliqué
             (le client le recalcule identiquement à partir des mêmes impacts,
              et sa divergence éventuelle est visuellement sans conséquence)
v_i          f16[3], vitesse de nœud, niveau Q-4 uniquement
```

- R-1210 : le champ plastique est la **seule** donnée autoritative. Sa quantification `i8` garantit que la reconstruction client est exacte (INV-14) et que le stockage est compact (3 octets par nœud).
- R-1211 : `max_disp` est déclaré par région et plafonné par la validation à la moitié de la plus petite dimension de l'OBB, ce qui borne l'amplitude et évite les auto-intersections grossières (INV-18).

## 14.4 Application d'un impact (noyau déterministe)

```text
ENTRÉE : point P (espace de part), normale N, énergie E_def, aire A,
         contrainte sigma, matériau M, région R, drapeaux

 1. profondeur nominale (même modèle d'indentation qu'en 13.3bis, appliqué à
    la seule fraction d'énergie destinée à la déformation) :
      V_def [m³] = E_def / M.hardness_pressure
      depth [m]  = sqrt( V_def / (PI * R_eff) ) / M.deformation_resistance
      depth       = clamp(depth, 0, R.max_disp)
    R_eff est celui porté par l'ImpactDesc ; la cohérence A ≈ 2·PI·R_eff·depth
    entre C-41 et C-42 est vérifiée par T-810b.
 2. rayon d'influence :
      radius = sqrt(A / pi) * M.spread_factor
               + R.thickness * M.thickness_spread
      radius = clamp(radius, R.cell_size, R.max_radius)
      (spread_factor et thickness_spread sont des constantes du matériau,
       déclarées, jamais codées en dur)
 3. direction : dir = -N (enfoncement), plus une composante tangentielle
      dir = normalize( -N * (1 - shear_ratio) + T * shear_ratio )
      où T est la direction tangentielle de l'impact et
      shear_ratio = E_shear / (E_normal + E_shear)
 4. pour chaque nœud i du lattice dans radius autour de P :
      r  = |x_i - P| / radius
      w  = det::falloff(r)                       // (1-r²)², nul au-delà de r=1
      si le nœud est ancré : continuer
      delta = dir * depth * w
      // séparation élastique / plastique
      sigma_local = sigma * w
      si sigma_local <= M.yield_strength :
          d_élastique_i += delta
      sinon :
          excess   = (sigma_local - M.yield_strength) / sigma_local
          d_plast  = delta * excess * M.plasticity
          d_élast  = delta - d_plast
          d_plastique_i = det::quantize_i8(
              clamp(d_plastique_i * step + d_plast, -max_disp, +max_disp), step)
          d_élastique_i += d_élast
 5. contrainte de non-inversion : après application, chaque cellule dont le
    jacobien devient négatif voit ses nœuds ramenés par bissection (au plus
    4 itérations) jusqu'à jacobien positif
 6. accumulation : R.energy += E_def ; R.max_strain = max(...)
 7. déchirure (si R.flags & ALLOW_TEAR et sigma_local > M.fracture_strength) :
    marquer la cellule TORN -> voir 14.10
 8. marquer les tranches modifiées dans dirty_slabs ; version += 1
```

- R-1220 : toutes les opérations de ce noyau utilisent exclusivement `+ - * / sqrt` et les fonctions de `det` (C-16). Aucune fonction transcendante. Ordre d'itération des nœuds fixé par index croissant.
- R-1221 : le retour élastique est intégré chaque tick : `d_élastique_i *= (1 - M.damping·dt)`, avec seuil d'annulation. Au niveau `Q-4`, un ressort amorti est intégré à la place, produisant une oscillation de tôle.
- R-1222 : l'accumulation est **naturellement correcte** : deux impacts successifs au même endroit s'additionnent dans le champ plastique, en saturant à `max_disp` (T-812).

## 14.5 Niveaux de qualité de déformation

| Niveau | Champ plastique | Champ élastique | Résidu par sommet | Solveur masse-ressort | Normales | Refit de collider | Usage |
|---|---|---|---|---|---|---|---|
| `Q-0 OFF` | non | non | non | non | — | variantes de collider | fallback : variantes de mesh uniquement |
| `Q-1 LOW` | oui, lattice ≤ 6³ | non | non | non | gradient analytique | au franchissement d'étape | machines modestes |
| `Q-2 MEDIUM` | oui, lattice ≤ 10³ | oui | non | non | gradient analytique | seuil de déplacement | défaut |
| `Q-3 HIGH` | oui, lattice ≤ 14³ | oui | oui, borné | non | gradient + résidu | seuil de déplacement | recommandé |
| `Q-4 ULTRA` | oui, lattice ≤ 16³ | oui | oui | oui (plis, oscillations, propagation entre nœuds) | gradient + résidu | seuil réduit | machines puissantes |

- R-1230 : le niveau s'applique **par assembly**, choisi par le gouverneur de qualité à partir du niveau global, de la distance à la caméra et du budget restant.
- R-1231 : la **baisse de niveau ne perd jamais le champ plastique** : elle réduit la résolution effective par sous-échantillonnage (moyenne des nœuds), ce qui conserve l'aspect général du dommage. La remontée de niveau ré-interpole. Le champ persisté est toujours celui de la résolution compilée, jamais celui du niveau courant (INV-18).
- R-1232 : le niveau `Q-0` reste **visuellement cohérent** : les étapes de dommage déclenchent les variantes de mesh, et l'objet paraît endommagé sans être déformé. C'est le fallback obligatoire de tout le système (exigence 25 : « désactivation → fallback fonctionnel »).
- R-1233 : **côté serveur**, le niveau de déformation n'affecte que la finesse du refit de collider et la fréquence du retour élastique, jamais le champ plastique lui-même : la simulation autoritative produit le même champ à tous les niveaux (R-841, T-983).

## 14.6 Pipeline de rendu de la déformation (C-68)

```text
STOCKAGE GPU
  - un pool de pages de 1 KiB dans un TBO (format RGBA8_SNORM, 256 nœuds/page)
    -> le champ plastique quantifié i8 est uploadé tel quel, sans conversion
  - un second TBO pour le champ élastique (RGBA16F), alloué seulement si Q >= 2
  - un TBO de résidus (index de sommet u32 + i8[3]) si Q >= 3
  - une table de régions par instance (OBB inverse, résolution, quant_step,
    offsets de pages) dans un UBO de 64 KiB, paginé si nécessaire

UPLOAD
  - seules les pages marquées dirty sont uploadées (glBufferSubData sur la plage)
  - au plus render.max_deform_pages_per_frame pages par frame (défaut 256) ;
    les pages restantes attendent la frame suivante (le rendu montre alors
    l'état de la frame précédente pour ces zones, écart borné à 1 frame)

VERTEX SHADER
  int   r  = v_region;                         // attribut de sommet
  if (r != 255) {
      Region reg = u_regions[inst.deform_offset + r];
      vec3  u  = (reg.obb_inv * vec4(pos,1)).xyz;      // coordonnées normalisées
      vec3  D  = trilinear(reg, u);                    // 8 lectures TBO
      mat3  F  = deformationGradient(reg, u);          // 8 lectures partagées
      pos     += v_def_w * D;
      normal   = normalize(cofactor(F) * normal);
      tangent  = normalize(F * tangent);
  }
  #ifdef DEFORMED_RESIDUAL
      pos += residualLookup(gl_VertexID + inst.residual_base);
  #endif

ORDRE
  skinning -> déformation -> transform d'instance      (R-792)

CHEMIN COMPUTE (optionnel, H-05)
  si ARB_compute_shader est disponible et Q >= 3, le calcul des résidus et la
  pré-transformation des meshes très denses (> compute.threshold_vertices,
  défaut 200 000) sont effectués une fois par changement de champ dans un
  buffer persistant, au lieu d'être refaits à chaque sommet et chaque frame.
  Ce chemin est une OPTIMISATION : le rendu est identique sans lui.
```

- R-1240 : la déformation n'entraîne **aucune reconstruction de mesh** ni réallocation de VBO. La géométrie source reste partagée entre toutes les instances (R-960).
- R-1241 : les 8 lectures TBO sont partagées entre le calcul de `D` et celui de `F` (les mêmes valeurs de nœuds servent aux deux), ce qui borne le coût à 8 lectures par sommet déformé.
- R-1242 : un mesh dont aucun sommet n'a de région (`region == 255` partout) est compilé avec la variante de shader **non déformée** : il ne paie aucune lecture de champ ni aucun calcul de gradient. C'est un coût GPU **identique à celui d'un mesh non déformable**, et non un coût nul au sens absolu (14.11bis).
- R-1243 : en backend `VANILLA_CONSUMER`, la déformation est appliquée sur CPU au moment de l'émission des sommets, avec le plafond `render.vanilla_max_deformed_vertices` ; au-delà, l'instance est émise non déformée et un compteur est incrémenté.

## 14.7 LOD de déformation

```text
distance / taille apparente -> niveau de déformation effectif
  taille_px > 200          : niveau global
  200 >= taille_px > 80    : min(niveau global, Q-3)
  80  >= taille_px > 30    : min(niveau global, Q-2)
  30  >= taille_px > 10    : min(niveau global, Q-1)
  taille_px <= 10          : Q-1 (champ plastique seul, lattice sous-échantillonné)
```

- R-1250 : le champ plastique reste appliqué **à toute distance** tant que l'objet est rendu : un véhicule cabossé doit rester visiblement cabossé de loin. Seuls l'élastique, les résidus et le solveur sont supprimés à distance.
- R-1251 : hystérésis de 10 % sur les seuils, comme pour le LOD géométrique.

## 14.8 Compléments déclaratifs

En plus du champ, un auteur peut déclarer :

```json
"deformation_extras": {
  "bones": [ { "bone": "door_l_bend", "driven_by": "region:door_l",
               "axis": "z", "scale": 0.8, "max_angle": 0.35 } ],
  "morphs": [ { "target": "hood_crumple", "driven_by": "region:hood.max_strain",
                "map": [[0,0],[0.3,1.0]] } ]
}
```

- R-1260 : les **bones de déformation** sont pilotés par une statistique du champ (déplacement moyen, direction dominante, déformation maximale) et permettent des effets structurels qu'un lattice local rend mal : une porte qui se voile globalement, un châssis qui se vrille. Ils réutilisent le squelette existant : **aucun coût GPU supplémentaire par rapport à un mesh déjà skinné**, seul le calcul de la statistique de champ s'ajoute côté CPU (mesuré par B-19).
- R-1261 : les **morph targets** sont pilotés de la même façon ; ils sont chargés en mémoire GPU seulement si la definition les déclare, et sont plafonnés (`render.max_morph_targets_per_asset`, défaut 8).
- R-1262 : ces compléments sont **facultatifs** ; leur absence ne dégrade pas le système de base.

## 14.9 Déformation et collision (C-45)

> Règle fondamentale (P-14, INV-13) : la géométrie visuelle déformée et la géométrie de collision sont **deux représentations distinctes**. Il est interdit de transformer un mesh visuel déformé en trimesh dynamique.

```text
LIAISON
  chaque collider REFITTABLE conserve ses points d'enveloppe (<= 256), liés au
  même lattice que les sommets (compilé par C-28)

DÉCLENCHEMENT
  un refit est planifié lorsque, depuis le dernier refit :
      max_i |d_plastique_i - d_plastique_i_au_dernier_refit|
        > deformation.collider_refit_threshold   (défaut 0.04 m)
    OU la part change d'étape de dommage
    OU une déchirure est apparue dans la région

EXÉCUTION (budgétée)
  au plus budgets.max_collider_refits_per_tick refits par tick (défaut 8),
  ordonnés par (visible par un joueur, énergie récente, ancienneté)
  1. appliquer D(p) à chaque point d'enveloppe (même noyau, même champ)
  2. recalculer l'enveloppe convexe (QuickHull sur <= 256 points : coût borné)
  3. si le nombre de points dépasse 256 -> réduction par distance
  4. remplacer la forme du collider dans Rapier (Collider::set_shape) sans
     recréer le body ni perdre son état
  5. recalculer masse et inertie du compound si la variation de volume
     dépasse deformation.mass_update_threshold (défaut 5 %)

VARIANTES DE COLLIDER
  une definition peut déclarer des colliders alternatifs par étape de dommage
  ("collider_variants": {"HEAVY": "body_collider_crushed"}). Ils sont utilisés
  au niveau Q-0 et comme secours si le refit échoue.

ÉCART TOLÉRÉ
  la géométrie de collision peut retarder d'au plus collider_refit_threshold
  sur la géométrie visuelle. Cet écart est documenté, mesuré
  (axion.deform.collider_lag_m) et exposé par le debug renderer.
```

- R-1270 : un refit ne DOIT jamais réveiller un body endormi ni modifier sa vitesse. C'est un changement de forme, pas une impulsion.
- R-1271 : si le re-hull échoue (points dégénérés), le collider **conserve sa forme précédente** et un compteur est incrémenté. Jamais de collider invalide.
- R-1272 : le refit est interdit sur un collider `NO_REFIT` et sur tout collider de body `STATIC`.
- R-1273 : le coût d'un refit est mesuré (B-21) et son budget est distinct de celui de la déformation.

## 14.10 Déchirure

- R-1280 : la déchirure est **`EXPERIMENTAL`** en V1.0, activée par région (`ALLOW_TEAR`) et par niveau (`Q-3`+). Elle est **implémentée**, pas seulement spécifiée (R-003).
- R-1281 : mécanisme : lorsqu'une cellule dépasse `fracture_strength`, elle est marquée `TORN`. Les sommets appartenant à une cellule `TORN` reçoivent un déplacement supplémentaire séparant les deux côtés selon la normale de rupture, et le matériau bascule sur une variante « bord déchiré » (matériau déclaré). Aucune retopologie n'a lieu : la géométrie n'est pas re-maillée.
- R-1282 : au-delà de `tear.max_cells_per_region` (défaut 8 %), la région entière est considérée rompue et déclenche le mécanisme de rupture structurelle (PARTIE 15), qui lui **peut** détacher une part entière — ce qui produit une vraie séparation, avec une géométrie propre issue de la découpe en parts précompilée.
- R-1283 : fallback : si la déchirure est désactivée, le dépassement de `fracture_strength` alimente directement le dommage structurel. Le résultat reste correct, simplement moins spectaculaire.

## 14.11 Budgets, mémoire et performance

| Ressource | Budget | Comportement en surcharge |
|---|---|---|
| Temps CPU par tick | `budgets.deformation_ns_per_tick` (1,5 ms) | traitement par lot avec report borné ; baisse de niveau par C-77 |
| Mémoire de champ | `budgets.deform_mem_bytes` (128 MiB) | compactage par sous-échantillonnage, puis éviction LRU des assemblies lointaines, puis refus avec `E-8001` |
| Mémoire par assembly | `deformation.max_field_bytes_per_assembly` (256 KiB) | sous-échantillonnage automatique de la région la plus grande |
| Assemblies déformées simultanées | `budgets.max_deformed_assemblies` (256) | les assemblies excédentaires conservent leur champ mais ne reçoivent plus de nouveaux impacts déformants ; elles reçoivent toujours le dommage structurel |
| Pages uploadées par frame | `render.max_deform_pages_per_frame` (256) | report d'une frame, borné |
| Résidus par assembly | `deformation.max_residual_vertices` (4096) | les résidus les plus faibles sont fusionnés dans le champ |

**Règles de performance obligatoires :**

- R-1290 : **aucune allocation** dans la boucle chaude de déformation après échauffement. Les pages sont prises dans un pool préalloué ; les tampons de travail sont des arènes `SCRATCH` (T-816).
- R-1291 : le traitement est **vectorisé** : les nœuds d'une région sont traités par blocs de 4 ou 8 avec `Vec3A`, avec un ordre de réduction fixe (R-511).
- R-1292 : le traitement est **parallélisé par assembly** (jamais à l'intérieur d'une région, pour préserver le déterminisme d'ordre).
- R-1293 : seules les **tranches modifiées** (`dirty_slabs`) sont ré-uploadées et ré-évaluées.
- R-1294 : une assembly sans champ alloué ne consomme **ni mémoire d'arène `DEFORM`, ni temps de simulation de déformation, ni variante de shader déformée**. La portée exacte de cette garantie est donnée en 14.11bis (INV-16).
- R-1295 : le champ élastique d'une assembly endormie et sans impact récent est **libéré** après `deformation.elastic_release_s` (défaut 5 s), le plastique étant conservé.

## 14.11bis Modèle de coût : portée exacte des garanties

Le système distingue **quatre coûts indépendants**. Les garanties de « coût nul »
portent sur les trois premiers, jamais sur le quatrième.

| Coût | Assembly intacte | Assembly déformée | Garantie |
|---|---|---|---|
| **1. État et simulation CPU** | **nul** : aucun pas de déformation exécuté, aucune entrée dans la boucle de C-42 | proportionnel au nombre de nœuds des régions touchées et aux tranches marquées `dirty` | INV-16, T-808, budget `budgets.deformation_ns_per_tick` |
| **2. Mémoire** | **nul** : aucune page de l'arène `DEFORM`, aucune page GPU de champ | plastique 3 o/nœud + élastique 6 o/nœud (Q≥2) + vitesses 6 o/nœud (Q-4) + résidus | INV-16, T-808, budget `budgets.deform_mem_bytes` |
| **3. Compilation et chargement** | **non nul, payé une fois par asset** : régions, poids, ancrages, liaisons d'enveloppe sont produits à la compilation et chargés avec l'asset. Ce coût est indépendant de l'état de dommage et est plafonné par `assets.max_deform_bytes`. | identique | budget `assets.max_compile_ms`, B-09, B-10 |
| **4. Rendu GPU** | **coût de la variante non déformée**, mesuré identique à un asset non déformable (T-910) : ni lecture de champ, ni calcul de gradient | par sommet déformé : 8 lectures de nœuds partagées entre déplacement et gradient, plus le cofacteur pour la normale, plus une lecture de résidu si `Q ≥ 3` | budget de frame, B-25, gradué par 14.5 et 14.7 |

**Règles normatives.**

- R-1296 : les termes « coût nul » et « aucun coût » employés au sujet de la
  déformation désignent **exclusivement** les coûts 1 et 2 ci-dessus, pour une
  assembly intacte. Aucune formulation du document ne DOIT laisser entendre que
  le rendu d'un mesh déformé est gratuit.
- R-1297 : le coût 4 est **réel, mesuré et budgété**. Il est comptabilisé dans le
  budget de frame, mesuré par B-25 séparément pour les chemins UBO, TBO et
  compute, et gradué par le niveau de qualité et le LOD de déformation.
- R-1298 : le coût 3 est payé une fois par asset, indépendamment du nombre
  d'instances et de leur état, et il est nul si l'auteur a désactivé la
  déformation à la compilation (`deformation.enabled: false`, R-872).
- R-1299 : une assembly entièrement réparée libère son champ après
  `deformation.elastic_release_s` et **retrouve exactement** le profil de coût
  d'une assembly intacte (T-808b).

## 14.12 Réplication et persistance (résumé, détail en PARTIE 21 et 22)

- R-1300 : **aucune donnée par sommet ni par nœud n'est transmise en régime normal** (INV-15). Le serveur envoie les `ImpactDesc` compacts ; le client les rejoue avec le même noyau et obtient le même champ (INV-14).
- R-1301 : le serveur joint périodiquement une **empreinte** de champ ; en cas de divergence, il envoie un instantané compact (champ plastique quantifié, zstd) de la seule région divergente.
- R-1302 : la persistance stocke le champ plastique quantifié compressé, jamais la géométrie.

## 14.13 Tests

```text
T-808  assembly intacte : zéro octet dans l'arène DEFORM, zéro pas de simulation
       de déformation, variante de shader non déformée sélectionnée
T-808b assembly déformée puis entièrement réparée : le champ est libéré et
       l'assembly retrouve exactement le profil de coût d'une assembly intacte
T-809  bornes : aucun déplacement ne dépasse max_disp ; budget mémoire respecté
T-810  impact faible -> petite déformation locale, profondeur cohérente
T-811  impact fort  -> déformation importante, saturée à max_disp
T-812  impacts répétés -> accumulation correcte et monotone, puis saturation
T-813  sous le seuil plastique -> déformation purement élastique, retour à zéro
T-814  au-dessus du seuil -> déformation permanente conservée après retour élastique
T-815  normales : gradient analytique cohérent avec des normales recalculées
       par différences finies (erreur < 2 %)
T-816  aucune allocation dans la boucle chaude après échauffement
T-817  axion-cli deform produit le même champ que le jeu (bit à bit)
T-818  nœuds ancrés immobiles ; bord soudé non déformé
T-810b COHÉRENCE C-41 / C-42 : pour un même impact, A ≈ 2·pi·R_eff·depth à 2 %
       près ; les deux composants utilisent le même modèle d'indentation
T-810c deformation_resistance agit bien comme DIVISEUR : doubler sa valeur
       divise la profondeur par deux, à énergie et géométrie égales
T-819  cellule inversée détectée et corrigée, aucun artefact visuel persistant
T-820  vecteurs d'or du noyau déterministe sur 3 plateformes
T-821  même séquence d'impacts -> même empreinte client/serveur
T-822  résultat indépendant du nombre de workers
T-823  reconstruction client depuis les événements, sans instantané
T-824  divergence forcée -> instantané envoyé, convergence
T-825  aucune donnée par sommet ni par nœud en régime normal (audit de paquets)
T-826  client falsifié ne produit aucune déformation autoritative
T-827  client n'exécute pas la chaîne de dommage des assemblies distantes
T-828  sauvegarde/rechargement : champ identique bit à bit
T-851  Q-0 : fallback par variantes de mesh, cohérent et testé
T-852  baisse puis remontée de niveau : le champ plastique est conservé
T-853  LOD de déformation : cabossage visible à toute distance de rendu
T-854  1000 objets déformables : budget respecté ou dégradation propre
T-855  aucun collider dynamique n'est un trimesh après déformation
T-856  collision cohérente avec la géométrie déformée, écart <= seuil déclaré
T-857  refit budgété : au plus N par tick, priorité respectée
T-858  refit n'éveille pas un body endormi et ne modifie pas sa vitesse
T-859  déchirure : cellules TORN, bascule de matériau, seuil de rupture régionale
```

## 14.14 Acceptance de la PARTIE 14

```text
Sur le scénario de référence « collision frontale d'un véhicule à 12 m/s contre
un mur de blocs » :
  - l'avant du véhicule s'enfonce visiblement et progressivement ;
  - la profondeur d'enfoncement croît avec la vitesse d'impact sur une série
    de 5 vitesses, de façon monotone et reproductible ;
  - un second impact au même endroit accroît l'enfoncement jusqu'à saturation ;
  - la déformation est permanente après arrêt du véhicule ;
  - les pièces adjacentes sont affectées selon la propagation déclarée ;
  - le capot atteint son seuil structurel et se détache, devenant un débris
    physique autonome ;
  - le compartiment moteur devient visible ;
  - la collision du véhicule reflète l'avant enfoncé, à l'écart déclaré près ;
  - un second client connecté voit exactement la même déformation ;
  - après sauvegarde, arrêt et rechargement du serveur, l'état est identique ;
  - une réparation restaure progressivement la géométrie ;
  - aucun de ces comportements n'a nécessité de code spécifique au véhicule.
```

---
# PARTIE 15 : INTÉGRITÉ STRUCTURELLE, RUPTURE, DÉTACHEMENT, DÉBRIS

## 15.1 Modèle

Le graphe structurel est un graphe **acyclique** dont les sommets sont les parts et les arêtes les `StructuralLinkDesc` (DM-14). Il est compilé par C-28, complété automatiquement pour les liens non déclarés, et validé.

```text
Exemple générique (aucune de ces valeurs n'est codée dans le moteur) :

  chassis
    ├─(weld,  cap 12 kJ)─ body
    │                       ├─(weld,  cap 9 kJ)─ fender_fl
    │                       ├─(hinge, cap 4 kJ)─ door_l      [joint revolute]
    │                       ├─(hinge, cap 5 kJ)─ hood        [joint revolute]
    │                       ├─(bolt,  cap 3 kJ)─ bumper_front
    │                       └─(glue,  cap 1 kJ)─ glass_ws    [BRITTLE]
    ├─(bolt,  cap 40 kJ)─ engine
    ├─(bolt,  cap 20 kJ)─ suspension_fl ─(bolt, cap 15 kJ)─ wheel_fl
    └─(bolt,  cap 8 kJ)─ light_l
```

## 15.1bis Estimation de l'aire de liaison (référence normative)

Une liaison structurelle non déclarée reçoit des capacités dérivées de la
géométrie. Cela exige une **aire** `A_link` [m²] et un **bras de levier**
`r_link` [m] définis sans ambiguïté. L'intersection de deux boîtes englobantes
étant un **volume**, elle ne peut pas être employée telle quelle.

**Méthode normative (compilation, C-28).**

```text
ENTRÉE : AABB_a et AABB_b des deux parts, en espace d'asset

1. boîte de recouvrement O = AABB_a inter AABB_b
   demi-dimensions de O triées par ordre croissant : e0 <= e1 <= e2   [m]

2. CAS 1 - recouvrement non vide (e0 > 0) :
       le plan de liaison est perpendiculaire à l'axe le plus mince (e0)
       A_link = (2*e1) * (2*e2)                                  [m * m] = m²
       r_link = sqrt(e1*e1 + e2*e2)                              [m]
   A_link est l'aire de la SECTION de la zone de recouvrement dans le plan de
   jonction. Ce n'est ni le volume de O, ni l'aire totale de sa surface : c'est
   une section, explicitement définie.

3. CAS 2 - parts adjacentes sans recouvrement (O vide ou e0 = 0) :
       n = axe du plus petit écart entre les deux boîtes
       A_link = aire de l'intersection des PROJECTIONS de AABB_a et AABB_b sur
                le plan perpendiculaire à n                       [m²]
       r_link = demi-diagonale de cette intersection projetée     [m]
       si l'écart dépasse struct.max_gap (défaut 0.05 m), aucune liaison n'est
       générée

4. BORNES :
       A_link = clamp(A_link, A_link_min, A_link_max)
       A_link_min = min(thickness_a, thickness_b)²
       A_link_max = struct.max_link_area (défaut 4 m²)
       r_link     = clamp(r_link, 0.01, 4.0)                      [m]

5. le matériau de la liaison est celui de la part la plus FAIBLE des deux au sens
   de fracture_energy (choix conservateur)
```

**Ce que la méthode approxime.** `A_link` approxime l'aire réelle de la surface
soudée, boulonnée ou collée entre deux pièces. L'approximation par boîtes
englobantes est retenue pour son coût constant et son déterminisme ; elle
**surestime** pour des pièces creuses ou concaves. C'est pourquoi elle est
plafonnée, et pourquoi une valeur déclarée par l'auteur est **toujours
prioritaire** sur la valeur dérivée.

- R-1315 : la génération automatique ne s'applique qu'aux liaisons non déclarées.
  Une liaison déclarée n'est jamais recalculée.
- R-1316 : les valeurs dérivées sont écrites en clair dans le rapport de
  compilation et exposées par `axion-cli inspect`, afin que l'auteur puisse les
  reprendre et les figer dans sa definition.
- R-1317 : le calcul est déterministe et vérifié dimensionnellement (T-806b).

## 15.2 Propagation de l'énergie structurelle (C-43)

```text
Entrée : E_struct pour une part p (issu de C-35, étape 4)

 1. p.absorbed += E_struct
 2. p.integrity = clamp(1 - p.absorbed / p.structural_capacity, 0, 1)
 3. pour chaque liaison L attachée à p :
      part_transmise = E_struct * L.propagation * (1 - p.integrity_avant)
        (une part déjà affaiblie transmet davantage : la structure ne tient plus)
      L.absorbed += part_transmise
      L.integrity = clamp(1 - L.absorbed / L.capacity, 0, 1)
      si L.integrity == 0 -> rupture (15.3)
      sinon, propager le reliquat à l'autre part, en décrémentant la profondeur
 4. profondeur maximale damage.max_propagation_depth (4)
 5. contribution de la déformation : une région dont max_strain dépasse
    material.max_strain injecte de l'énergie structurelle dans sa part,
    proportionnellement au dépassement. C'est le pont entre déformation et
    rupture : trop cabossé finit par casser.
 6. contribution des charges statiques : une liaison LOAD_BEARING dont la force
    ou le couple mesurés dans le joint associé dépassent tensile/shear/torque
    perd de l'intégrité par tick, proportionnellement au dépassement
    (une porte pendante finit par tomber).
```

- R-1310 : l'intégrité ne remonte jamais spontanément ; seule la réparation (PARTIE 16) la restaure.
- R-1311 : toute variation d'intégrité franchissant un multiple de 1/16 émet un événement réseau.
- R-1312 : les liaisons `REFORMABLE` (magnétique, encliquetage) peuvent être rétablies sans réparation, selon des conditions déclarées.

## 15.3 Rupture et détachement (C-44)

```text
Déclencheurs de rupture d'une liaison L :
  - L.integrity atteint 0
  - la force, le couple ou l'énergie instantanés dépassent tensile/shear/torque
  - la part associée atteint DESTROYED et déclare detach_threshold
  - une région ALLOW_TEAR dépasse tear.max_cells_per_region

Conséquence :
 1. le joint physique associé (s'il existe) est détruit -> événement JOINT_BROKEN
 2. si la part devient déconnectée de la racine dans le graphe résiduel :
      -> DÉTACHEMENT
 3. sinon : la part reste attachée par ses autres liaisons, avec un jeu accru
    (le joint restant reçoit une friction et des limites dégradées : JAMMED)

DÉTACHEMENT (normatif)
 1. calcul de la composante connexe détachée (parts + nodes + colliders)
 2. création d'une NOUVELLE assembly de type RigidObject :
      - asset : le même, avec un masque de nodes restreint au sous-arbre
      - masse : somme des masses des parts détachées
      - colliders : ceux des parts détachées, déjà refités
      - CHAMP DE DÉFORMATION : les régions détachées sont TRANSFÉRÉES telles
        quelles (le capot arraché reste cabossé) — c'est un transfert de
        propriété de pages, sans copie ni recalcul
      - vitesse : vitesse du point de détachement de l'assembly d'origine,
        plus une impulsion de séparation dérivée de l'énergie excédentaire
      - états d'usure et décalques de la zone concernée : transférés
 3. l'assembly d'origine :
      - masque les nodes détachés, met à jour son compound de collision en une
        opération (retrait des colliders concernés), recalcule masse et inertie
      - révèle les nodes INTERNAL désormais exposés
      - marque la part DETACHED
 4. événement DETACH émis et synchronisé
```

- R-1320 : le détachement est **atomique** du point de vue du tick : il n'existe aucun état intermédiaire où la part appartiendrait aux deux assemblies ou à aucune.
- R-1321 : une part `CRITICAL` détachée déclenche l'événement déclaratif associé (par exemple `assembly_disabled`), dont l'effet est data-driven.
- R-1322 : le nombre de détachements par tick est plafonné (`damage.max_detach_per_tick`, défaut 4) ; les autres sont reportés au tick suivant, dans un ordre déterministe.

## 15.4 Débris

```text
- un débris est une assembly normale, marquée LOW_PRIORITY pour le réseau
- durée de vie damage.debris_lifetime_s (défaut 90 s), plafond global
  damage.max_debris (défaut 96 par dimension), éviction FIFO des plus anciens
- un débris peut lui-même être déformé et cassé (récursivement), avec un niveau
  de qualité réduit d'un cran et une profondeur de récursion plafonnée
  (damage.max_debris_generation, défaut 2)
- un débris immobile depuis debris_sleep_s (défaut 5 s) est endormi
- un débris peut être ramassé si la definition le déclare : il devient un item
  portant son champ de déformation compact (R-732)
```

- R-1330 : un débris ne peut pas engendrer indéfiniment d'autres débris : la génération est bornée et vérifiée (T-865).
- R-1331 : les débris sont persistés seulement si `damage.persist_debris` est vrai (défaut faux) ; en revanche, **les parts détachées encore rattachées logiquement** à leur assembly d'origine (mode `dangling`) sont toujours persistées.

## 15.5 Effets sur les mécanismes

- R-1340 : une liaison `HINGE` dont l'intégrité descend sous un seuil déclaré met le joint associé en `JAMMED` : friction interne augmentée, plage angulaire réduite selon le désalignement de socket (R-941). Une porte cabossée s'ouvre mal, puis ne s'ouvre plus, puis tombe. Cette progression est entièrement déclarative.
- R-1341 : un siège dont la part est détachée éjecte son occupant (R-712).
- R-1342 : un socket porté par une part détachée devient invalide et rompt les attaches (R-940).

## 15.6 Budgets

| Élément | Budget | Surcharge |
|---|---|---|
| Propagation structurelle | inclus dans `budgets.damage_ns_per_tick` | profondeur tronquée |
| Détachements par tick | 4 | report déterministe |
| Débris par dimension | 96 | éviction FIFO |
| Génération de débris | 2 | refus, la part reste masquée |
| Composante connexe | 64 parts | au-delà, détachement refusé et journalisé |

## 15.7 Tests

```text
T-850  graphe structurel valide, acyclique, complété automatiquement
T-860  seuil structurel dépassé -> rupture de la liaison
T-861  part déconnectée -> détachement en nouvelle assembly valide
T-862  le débris conserve la déformation, l'usure et les décalques de la part
T-863  vitesse héritée cohérente avec le point de détachement
T-864  masse, inertie et compound de l'assembly d'origine mis à jour
T-865  génération de débris bornée, aucun engendrement infini
T-866  plafond de débris respecté, éviction FIFO
T-867  charge statique : porte pendante finit par tomber
T-868  déformation excessive -> énergie structurelle -> rupture
T-869  détachement pendant qu'un joueur est assis -> éjection sûre
T-806b DIMENSIONS DE LIAISON : capacity en J, tensile et shear en N, torque en
       N·m ; A_link en m², r_link en m ; toute combinaison incohérente échoue
T-806c AIRE DE LIAISON, CAS 1 : deux boîtes se recouvrant -> A_link égale la
       section attendue (2·e1 × 2·e2), jamais le volume de recouvrement
T-806d AIRE DE LIAISON, CAS 2 : deux boîtes adjacentes sans recouvrement ->
       A_link égale l'aire des projections intersectées ; au-delà de max_gap,
       aucune liaison générée
T-806e BORNES ET PRIORITÉ : A_link plafonnée ; une valeur déclarée par l'auteur
       n'est jamais recalculée
T-879  parts détachées en mode dangling persistées et restaurées
```

---

# PARTIE 16 : RÉPARATION, RESTAURATION ET REMPLACEMENT

## 16.1 Niveaux de réparation

| Niveau | Effet | Réversible |
|---|---|---|
| `SURFACE` | remet à zéro l'usure (rayures, saleté, brûlure) et supprime les décalques | oui |
| `DEFORM` | réduit progressivement le champ plastique vers zéro | oui |
| `PART` | restaure la santé et l'étape d'une part, remet son mesh intact | oui |
| `STRUCTURAL` | restaure l'intégrité des liaisons, dégrippe les joints | oui |
| `REPLACE` | remplace une part détruite ou détachée par une part neuve | oui |
| `FULL` | applique tous les niveaux précédents | oui |

## 16.2 Restauration du champ de déformation

```text
repair_deform(part, amount ∈ [0,1]) :
  pour chaque région de la part :
    pour chaque nœud i :
      d_plastique_i = det::quantize_i8(d_plastique_i * step * (1 - amount), step)
    marquer les tranches modifiées, version += 1
  recalculer max_strain ; planifier un refit de collider
```

- R-1350 : la réparation du champ est **progressive et déterministe** : le client la reconstruit à partir de l'événement `RepairEvent(part, level, amount)`, sans transfert de champ (INV-15).
- R-1351 : la réparation ne peut jamais **augmenter** un déplacement ni introduire de nouvelle déformation.
- R-1352 : une part `DETACHED` ne peut pas être réparée en place : elle doit être rattachée (`reattach`) ou remplacée. `reattach` échoue si la part parente est `DESTROYED`.

## 16.3 Règles data-driven

```text
data/<ns>/axion/repair_rules/<name>.json
{
  "schema": 1,
  "levels": {
    "surface":    { "time_s": 5.0,  "cost": [], "requires": [] },
    "deform":     { "time_s": 20.0, "amount_per_second": 0.05,
                    "requires": ["axion:tool/hammer"] },
    "part":       { "time_s": 15.0, "consumes": ["minecraft:iron_ingot x2"] },
    "structural": { "time_s": 30.0, "consumes": ["minecraft:iron_ingot x4"] },
    "replace":    { "time_s": 40.0, "consumes": ["<part.repair_item>"] }
  },
  "partial_allowed": true,
  "interruptible": true
}
```

- R-1360 : les coûts et les prérequis sont **déclaratifs** et exprimés en items Minecraft ou en événements ; le moteur ne connaît aucun item. Un modpack peut définir ses propres outils.
- R-1361 : la réparation est **interruptible** et son avancement est persisté si `interruptible` est vrai.
- R-1362 : l'API publique expose `repair(part, level, amount)` sans passer par les règles, pour les mods qui implémentent leur propre économie.

## 16.4 Remplacement de part

```text
1. la part cible doit être DESTROYED ou DETACHED
2. les nodes de la part sont restaurés depuis l'asset (mesh intact)
3. le champ de déformation des régions de la part est remis à zéro
4. les colliders sont restaurés depuis l'asset (pas de refit)
5. les liaisons structurelles attachées retrouvent leur intégrité nominale
6. l'usure et les décalques de la part sont effacés
7. si la part était détachée en une assembly de débris, cette assembly n'est pas
   supprimée automatiquement : le remplacement crée une pièce neuve, le débris
   reste dans le monde (comportement déclarable par "consume_debris": true)
```

**Tests.**

```text
T-880  réparation de surface : usure et décalques effacés
T-881  réparation de déformation progressive, monotone, convergente vers zéro
T-882  réparation de part : mesh, santé, colliders restaurés
T-883  réparation structurelle : intégrité et joints restaurés, JAMMED levé
T-884  état de réparation partielle persisté et repris après rechargement
T-885  remplacement d'une part détachée
T-886  reattach d'une part dangling ; échec si parent détruit
T-887  réparation reconstruite par le client depuis l'événement, sans champ transmis
T-888  la réparation n'introduit jamais de déformation
T-889  règles data-driven : coûts, temps, prérequis, interruption
```

---

# PARTIE 17 : SOLVEUR DE PARTICULES — TISSU, CORDES, CÂBLES, FILETS, CORPS SOUPLES

## 17.1 Positionnement

Le solveur de particules est un **système générique unique** (C-36) qui couvre tissus, drapeaux, vêtements, capes, cordes, câbles, filets, sangles et objets souples. Il n'existe pas de « système cape » ni de « système corde » : il existe des ensembles de particules avec des contraintes et des ancrages.

## 17.2 Modes d'autorité

| Mode | Simulation | Réseau | Effet gameplay | Maturité V1.0 |
|---|---|---|---|---|
| `VISUAL` | client uniquement | aucun | aucun | **STABLE** |
| `SERVER_SIMPLE` | serveur, jeu de particules réduit (≤ 32) | état compact à basse fréquence | oui (contraintes, collisions) | **STABLE** |
| `SERVER_FULL` | serveur, jeu complet | état compressé, priorité basse | oui | **EXPERIMENTAL** |

- R-1370 : le mode est déclaré par ensemble de particules dans la definition. Un tissu décoratif est `VISUAL` ; une corde de remorquage est `SERVER_SIMPLE` ; un filet de cargaison qui retient réellement des objets est `SERVER_FULL`.
- R-1371 : en mode `VISUAL`, aucun code de particules n'est exécuté ni chargé sur serveur dédié.
- R-1372 : en mode `SERVER_SIMPLE`, le serveur simule une **version réduite**
  (≤ 32 particules) qui porte l'intégralité de l'autorité physique et gameplay ;
  le client simule une version complète **purement visuelle**, contrainte par
  l'état serveur interpolé. C'est le meilleur rapport coût/qualité et le mode par
  défaut pour tout ce qui doit avoir un effet.

## 17.2bis Autorité et reconstruction visuelle (référence normative)

Cette section vaut pour **tous** les systèmes où le client dispose d'une
représentation plus fine que le serveur : particules (`SERVER_SIMPLE`), champ
élastique de déformation, décalques, usure interpolée, animations non
autoritatives.

**Répartition normative de l'autorité.**

| Domaine | Autorité | Le client peut |
|---|---|---|
| État physique (positions, vitesses, contacts, contraintes résolues) | **serveur exclusif** | interpoler, extrapoler de façon bornée |
| Gameplay (contrôles, montée, interaction, effets déclaratifs) | **serveur exclusif** | émettre des intentions, jamais des résultats |
| Dégâts, santé, intégrité, étapes de part | **serveur exclusif** | afficher |
| Événements de rupture, détachement, attache | **serveur exclusif** | recevoir et rejouer visuellement |
| États persistants (champ plastique, structure, usure, attaches) | **serveur exclusif** | reconstruire à l'identique, jamais initier |
| Champ élastique de déformation | dérivé, non persisté | simuler intégralement |
| Particules `VISUAL` | aucune autorité | simuler intégralement |
| Particules `SERVER_SIMPLE` / `SERVER_FULL` | **serveur** sur le proxy ou l'état complet | raffiner visuellement autour de l'état reçu |
| Décalques, usure sub-quantum, poses non autoritatives | dérivé | générer localement |

- R-1373 : **une simulation visuelle client, quelle que soit sa finesse, ne peut
  jamais modifier un état physique, gameplay ou persistant autoritatif.** Elle
  n'écrit que dans des tampons de rendu et dans des états locaux non persistés.
  Aucun chemin de code ne relie une sortie de simulation visuelle à une entrée du
  serveur (INV-02, INV-17). Vérifié statiquement par T-938b.
- R-1374 : le client **ne renvoie jamais** l'état de sa simulation visuelle. Les
  seuls paquets C→S sont ceux listés en 21.4 ; aucun ne transporte de position de
  particule, de nœud de champ ou de sommet.

**Reconstruction visuelle à partir de l'état serveur (normatif).**

```text
CORDES ET CÂBLES (kind = rope | cable)
 1. le serveur publie N_s particules proxy (positions quantifiées 1/1024 m,
    N_s <= server_segments), à la fréquence de snapshot de l'assembly
 2. le client interpole ces N_s positions dans le temps (Hermite)
 3. le client possède N_c particules visuelles (N_c >= N_s), liées aux proxies
    par une CORRESPONDANCE PRÉCOMPILÉE PAR ABSCISSE CURVILIGNE : la particule
    visuelle j est ancrée entre les proxies floor(j*(N_s-1)/(N_c-1)) et le
    suivant, avec un poids barycentrique fixe
 4. à chaque frame, le solveur visuel résout ses contraintes AVEC les positions
    proxy interpolées imposées comme contraintes dures d'ancrage
 5. dérive maximale autorisée d'une particule visuelle par rapport à sa position
    interpolée de référence : particles.visual_drift_max (défaut 0.25 m) ;
    au-delà, la particule est ramenée par projection

TISSUS ET FILETS (kind = cloth | net)
 1. le serveur publie les positions des seules particules ANCRÉES et d'un
    sous-ensemble régulier de particules de contrôle (au plus 32)
 2. la correspondance contrôle -> maillage visuel est une LIAISON BARYCENTRIQUE
    PRÉCOMPILÉE par C-28, stockée dans la section PART_SET
 3. le solveur visuel ajoute ses contraintes internes au-dessus de ces ancrages
 4. même règle de dérive maximale

CHAMP ÉLASTIQUE DE DÉFORMATION
 le client le recalcule intégralement à partir des mêmes impacts ; il n'est ni
 transmis ni persisté (R-1210). Une divergence élastique est visuellement
 négligeable et sans effet gameplay, puisque seule la composante plastique est
 autoritative.
```

- R-1375 : la correspondance visuelle ↔ proxy est **précompilée**, déterministe
  et versionnée ; elle n'est jamais recalculée au runtime.
- R-1376 : si l'état serveur d'un ensemble n'a pas été reçu depuis
  `particles.visual_freeze_ms` (défaut 500 ms), la simulation visuelle est gelée
  dans sa dernière pose et fondue vers la pose de repos. Elle ne continue jamais
  d'évoluer librement, ce qui bornerait mal la divergence.
- R-1377 : la dérive visuelle est **mesurée** (`axion.particles.visual_drift_m`)
  et exposée par l'overlay de debug, afin qu'une divergence anormale soit
  observable plutôt que silencieuse.

## 17.3 Solveur

**Décision (ADR-012).** XPBD (Extended Position Based Dynamics) implémenté dans AXION, sans dépendance externe.

```text
Par sous-pas :
 1. prédiction : x_pred = x + v·dt + a·dt²
 2. n_iterations (défaut 8, gradué par la qualité) résolutions de :
      - contraintes de distance (structurelles) avec compliance
      - contraintes de flexion (particules distantes de 2)
      - contraintes d'ancrage (particule fixée à un socket, déformation incluse)
      - contraintes de volume (mode SOFT, préservation approchée)
      - collisions contre proxies (sphères, capsules) — tous niveaux
      - collisions contre les tuiles de collision monde — Q >= 2
      - auto-collision par hachage spatial — Q >= 3, EXPERIMENTAL
 3. v = (x_pred - x)/dt ; amortissement global et amortissement de vent
 4. déchirure : une contrainte dont l'étirement dépasse tear_threshold est
    supprimée — Q >= 3, EXPERIMENTAL
```

- R-1380 : le nombre d'itérations, la fréquence de sous-pas et les types de collision activés sont gradués par le niveau de qualité `particles`.
- R-1381 : les collisions contre le monde utilisent les mêmes tuiles que la physique (C-38) : aucune structure supplémentaire.

## 17.4 Limites et budgets

| Ressource | Budget | Surcharge |
|---|---|---|
| Particules par ensemble | 4096 (`SERVER_FULL` : 512) | refus à la compilation |
| Ensembles simulés simultanément | `particles.max_sets` (défaut 48 client, 16 serveur) | sélection par distance et priorité ; les autres figés en pose de repos avec fondu de 0,5 s |
| Temps CPU | `budgets.particles_ns_per_tick` (1 ms) | réduction des itérations, puis du nombre d'ensembles |
| Mémoire | comptée dans l'arène `PERSISTENT` | éviction des ensembles lointains |

- R-1390 : un ensemble figé est repris **exactement** là où il s'était arrêté, sans saut, grâce à un fondu de position.
- R-1391 : le maillage de simulation est dérivé du mesh de rendu par fusion de sommets ; au-delà du plafond, une grille plus grossière est générée et le mesh de rendu est piloté par interpolation barycentrique (liaison précompilée par C-28).

## 17.5 Définition

```json
"particles": [
  { "name": "cape", "kind": "cloth", "authority": "visual",
    "mesh": "cape", "anchors": ["cape_anchor_l", "cape_anchor_r"],
    "stiffness": 0.8, "bending": 0.3, "damping": 0.05,
    "mass_per_particle": 0.02, "wind_influence": 1.0,
    "collision_proxies": [ { "type": "capsule", "node": "body",
                             "radius": 0.4, "height": 1.2 } ],
    "max_distance_from_anchor": 3.0 },

  { "name": "tow_rope", "kind": "rope", "authority": "server_simple",
    "from": { "assembly": "self", "socket": "socket_towbar" },
    "to":   { "attachment": "current" },
    "segments": 24, "server_segments": 12,
    "max_length": 6.0, "stiffness": 0.9, "break_force": 20000.0,
    "radius": 0.03, "material": "axion:rope" },

  { "name": "cargo_net", "kind": "net", "authority": "server_full",
    "mesh": "net", "anchors": ["hook_1","hook_2","hook_3","hook_4"],
    "stiffness": 0.95, "tear_threshold": 1.6, "self_collide": false }
]
```

## 17.6 Cordes et câbles physiques

- R-1400 : une corde `SERVER_SIMPLE` fournit **à la fois** une contrainte de distance maximale entre les deux ancrages (joint `ROPE`, résolu par le solveur physique, stable et peu coûteux) **et** une chaîne de particules serveur qui donne sa forme et permet la collision. La contrainte garantit la correction ; les particules donnent le réalisme.
- R-1401 : un treuil (`WINCH`) fait varier `max_length` dans le temps, piloté par une variable de definition ou par l'API.
- R-1402 : la rupture au-delà de `break_force` émet un événement, détruit l'attache et laisse deux extrémités libres qui continuent d'être simulées en mode `VISUAL`.

## 17.7 Interaction avec la déformation

- R-1410 : les ancrages suivent la **déformation** de leur région (R-941). Un crochet enfoncé déplace la corde qui y est attachée.
- R-1420 : un ancrage dont la part est détachée bascule sur l'assembly de débris si elle existe, sinon devient libre.

## 17.8 Tests

```text
T-350  cape suspendue : repos sans oscillation perpétuelle
T-351  suivi d'ancrage sans étirement au-delà de max_distance
T-352  collision contre proxy capsule sans interpénétration visible
T-353  plafond d'ensembles respecté, transition sans scintillement
T-354  serveur dédié : aucun code de particules VISUAL exécuté
T-355  corde SERVER_SIMPLE : distance entre ancrages jamais > max_length
T-356  corde : rupture au seuil, événement reçu, extrémités libres
T-357  4096 particules : budget respecté ou dégradation propre
T-930  collision contre les tuiles monde (Q >= 2)
T-931  auto-collision (Q >= 3) : pas d'auto-traversée visible
T-932  déchirure de contrainte au seuil (Q >= 3)
T-933  filet SERVER_FULL retient un objet physique
T-934  treuil : variation de longueur pilotée
T-935  ancrage suivant la déformation
T-936  ancrage sur part détachée -> transfert ou libération
T-937  ensemble figé puis repris sans saut
T-938  mode VISUAL n'a aucun effet gameplay (test d'égalité d'état serveur)
T-938b AUDIT STATIQUE : aucun chemin de code ne relie une sortie de simulation
       visuelle client à une entrée serveur, à un état persistant ou à un champ
       plastique (règle ArchUnit + scan du crate ax-particles)
T-938c DIVERGENCE VISUELLE SANS DIVERGENCE GAMEPLAY : deux clients dont les
       solveurs visuels sont volontairement désaccordés (itérations, dt, ordre)
       observent des poses de corde différentes, mais l'état serveur, les
       événements, les dégâts et la persistance sont strictement identiques
T-938d dérive visuelle plafonnée : au-delà de visual_drift_max, projection
       appliquée et métrique incrémentée
T-938e gel visuel après visual_freeze_ms sans état serveur, puis reprise sans saut
T-938f la correspondance proxy -> maillage visuel est précompilée et déterministe
T-945  1000 ensembles VISUAL : dégradation propre, framerate borné
```

---
# PARTIE 18 : ANIMATION ET COUPLAGE ANIMATION / PHYSIQUE / DÉGÂTS

## 18.1 Types d'animation

| Type | Source | Côté | Usage |
|---|---|---|---|
| Squelettique | pistes glTF sur des bones | client (+ serveur si un socket, un collider ou une déformation en dépend) | personnages, créatures, machines articulées |
| Node (rigide) | pistes glTF sur des nodes | idem | pièces mobiles simples |
| Procédurale | calculée par un système | les deux | roue, direction, suspension, aiguilles, variables |
| Joint-driven | état d'un joint physique | serveur → client | portes, capots, bras, vérins |
| Déformation-driven | statistique d'un champ de déformation | les deux | bones de voilage, morph targets (14.8) |
| Événementielle | déclenchée par un événement de gameplay | serveur → client (déclencheur) | ouverture, tir, démarrage |

- R-1430 : l'échantillonnage côté serveur est **conditionnel** : il n'a lieu que si un socket, un collider, une région de déformation ou une attache en dépend (T-360).

## 18.2 Échantillonnage et blending

```text
- recherche binaire avec curseur mémorisé par instance (O(1) amorti)
- interpolation STEP | LINEAR | CUBIC_SPLINE conforme à glTF 2.0
- rotations : slerp ou spline quaternion normalisée
- 4 couches (BASE, ADDITIVE_1, ADDITIVE_2, OVERRIDE), poids [0,1], masque de bones
- blending en espace local (TRS), jamais en matrices
- crossfade linéaire sur une durée déclarée
```

- R-1440 : le nombre de couches est figé à 4. Une machine à états d'animation complète est hors périmètre V1.0 ; elle est remplacée par des couches, des poids pilotables et des variables déclaratives, ce qui couvre les besoins sans introduire un langage de graphe.

## 18.3 Animation procédurale déclarative

```json
"procedural": [
  { "node": "wheel_fl", "channel": "rotation", "axis": "x",
    "source": "wheel.wheel_fl.spin_angle" },
  { "node": "steering_wheel", "channel": "rotation", "axis": "z",
    "source": "vehicle.steer", "scale": 8.0 },
  { "node": "needle_rpm", "channel": "rotation", "axis": "z",
    "source": "engine.rpm", "map": [[0,0.0],[7000,-4.2]] },
  { "node": "susp_fl", "channel": "translation", "axis": "y",
    "source": "wheel.wheel_fl.compression", "scale": -0.25 },
  { "bone": "door_l_bend", "channel": "rotation", "axis": "z",
    "source": "region.door_l.mean_disp", "scale": 2.0, "clamp": [-0.35, 0.35] }
]
```

- R-1450 : la liste des `source` est **fermée, documentée et versionnée** (ANNEXE A.4). Elle inclut désormais les sources de déformation, de structure, d'usure et d'attache.

## 18.4 Palettes de skinning et ordre d'application

- R-1460 : les palettes sont calculées en natif et écrites directement dans le tampon de rendu, sans copie côté Java.
- R-1461 : ordre figé : **skinning → déformation → transform d'instance** (R-792). Justification : la déformation est une propriété du volume physique de l'objet, pas du squelette ; l'appliquer après le skinning permet de cabosser une créature ou une machine animée sans que le cabossage suive les mouvements des os.
- R-1462 : conséquence documentée : pour un objet très articulé, une région de déformation doit couvrir une zone dont la pose varie peu, sinon le champ paraît « accroché au monde ». La validation émet un avertissement lorsqu'une région couvre des sommets influencés par des bones dont l'amplitude d'animation dépasse un seuil.

## 18.5 Couplage animation / physique / dégâts (scénario normatif de la porte)

Ce scénario est **spécifié et testé** ; il illustre le mécanisme générique.

```text
ÉTAT INITIAL
  door_l : part, région de déformation, liaison structurelle HINGE vers body,
           joint REVOLUTE, node JOINT_DRIVEN, animation "door_open" pilotant
           le MOTEUR du joint (pas la transform)

(1) CHOC
    C-41 produit un ImpactDesc sur la part door_l, zone door_l_edge

(2) DOMMAGE
    C-35 répartit : f_deform vers C-42, f_structure vers C-43, f_surface vers C-47
    door_l.health baisse, étape passe INTACT -> DAMAGED

(3) DÉFORMATION
    C-42 enfonce la région door_l ; le socket de la charnière se décale
    (misalignment > 0) ; le bone door_l_bend voile la porte (18.3)

(4) DÉSALIGNEMENT ET GRIPPAGE
    R-1340 : l'intégrité de la liaison HINGE descend ;
    le joint REVOLUTE reçoit une friction interne accrue et une plage réduite,
    calculées à partir de misalignment et de l'intégrité, selon les paramètres
    déclarés. La porte s'ouvre plus difficilement, puis reste bloquée (JAMMED).

(5) DÉPASSEMENT DE LIMITE
    Si une force externe force la porte au-delà de la plage réduite,
    le joint accumule de l'énergie structurelle (15.2, contribution des charges).

(6) RUPTURE
    L'intégrité de la liaison atteint 0 -> JOINT_BROKEN.

(7) DÉTACHEMENT
    door_l devient déconnectée -> nouvelle assembly RigidObject, conservant
    sa déformation, son usure et ses décalques (15.3).

(8) ÉTAT D'ANIMATION
    Le node passe de JOINT_DRIVEN à DETACHED. L'animation "door_open" cesse
    d'avoir un effet sur ce node. Si la definition déclare
    "on_detach": {"door_l": {"emit_event": "door_lost"}}, l'événement est émis.
    Une definition peut au contraire déclarer que la part détachée conserve une
    animation propre (par exemple un rotor qui continue de tourner) via
    "detached_animation": "spin_down".
```

- R-1470 : ce couplage n'introduit **aucune** règle spécifique aux portes. Il combine : part, région, liaison structurelle, joint, socket, état de node, et actions déclaratives.
- R-1471 : le passage `JOINT_DRIVEN → DETACHED` est atomique et synchronisé.

## 18.6 Root motion

- R-1480 : le root motion est lu mais n'affecte jamais la position d'une assembly physique. Il est utilisable par l'API pour piloter un contrôleur cinématique (`AssemblyKind::Character`).

## 18.7 Tests

```text
T-360  serveur dédié : aucune animation échantillonnée si rien n'en dépend
T-361  interpolation conforme à glTF (comparaison à un échantillonneur de référence)
T-362  crossfade sans saut
T-363  couches additives correctes ; T-364 masque de bones respecté
T-365  roue tourne proportionnellement à la distance parcourue
T-366  source inconnue -> definition refusée
T-367  128 bones : palette correcte
T-368  1000 assemblies animées : budget respecté ou dégradation propre
T-950  porte : choc -> déformation -> désalignement mesurable
T-951  porte : grippage progressif du joint, plage réduite
T-952  porte : rupture de liaison au seuil
T-953  porte : détachement, assembly valide, déformation conservée
T-954  ordre skinning -> déformation vérifié numériquement
T-955  bone de voilage piloté par la statistique de champ
T-956  morph target piloté par max_strain
T-957  occupant éjecté si le siège est détaché
T-958  animation d'une part détachée selon la definition
T-959  avertissement si une région couvre des sommets fortement animés
```

---

# PARTIE 19 : RENDU

## 19.1 Ce qui est réellement contrôlable depuis un mod Forge 1.20.1

| Capacité | Disponible ? | Mécanisme | Décision AXION |
|---|---|---|---|
| Dessiner de la géométrie custom dans le monde | ✔ | `RenderLevelStageEvent` | utilisé |
| Shaders GLSL propres | ✔ | compilation via LWJGL | utilisé |
| VAO/VBO/EBO/UBO/TBO propres | ✔ | LWJGL | utilisé |
| Instancing (`glDrawElementsInstanced`) | ✔ si GL 3.3 | LWJGL | utilisé, avec repli |
| Multi-draw indirect | ✔ si GL 4.3 | LWJGL | chemin accéléré optionnel |
| Compute shaders | ✔ si GL 4.3 | LWJGL | chemin accéléré optionnel |
| Matrices de vue/projection courantes | ✔ | `RenderLevelStageEvent`, `RenderSystem` | utilisé |
| Lightmap vanilla | ✔ | texture liée, `LevelRenderer.getLightColor` | utilisé |
| Texture de couleur et de profondeur du framebuffer principal | ✔ | `Minecraft.getMainRenderTarget()` | utilisé pour SSR et contact shadows (H-11) |
| Rendu dans un FBO propre (depth map) | ✔ | FBO créé par le mod | **utilisé pour les ombres AXION** |
| Écrire dans le G-buffer d'un shaderpack | ✘ | pipeline privé d'Iris | backend vanilla à la place |
| Modifier l'éclairage vanilla | ✘ | non additif (P-02) | jamais |
| Post-processing plein écran | techniquement possible | conflit systématique avec les shaderpacks | **hors périmètre**, remplacé par du tone mapping en shader |
| Framebuffer HDR | ✘ en 1.20.1 (RGBA8) | H-10 | tone mapping local |

**Conséquence normative** : AXION rend ses objets **en plus** du monde vanilla, dans le même framebuffer, avec sa propre géométrie, ses propres shaders et une passe d'ombre dédiée, sans altérer aucune passe vanilla.

## 19.2 Les deux backends

```text
NATIVE_GL         pipeline dédié GL 3.3 (+ chemins GL 4.3 optionnels) :
                  VAO/VBO/EBO/UBO/TBO, shaders AXION, PBR, instancing,
                  skinning et déformation GPU, décalques, ombres, occlusion
                  -> chemin par défaut

VANILLA_CONSUMER  RenderType + VertexConsumer + PoseStack, skinning et
                  déformation CPU plafonnés, décalques en quads, pas d'ombre
                  propre, éclairage fourni par vanilla ou par le shaderpack
                  -> obligatoire avec Iris/Oculus actif, et repli universel
```

- R-1490 : le choix est automatique (`render.backend = auto`), réévalué au chargement du monde, au changement de resource pack et au changement de shaderpack.
- R-1491 : les deux backends sont maintenus, testés et livrés. Le backend vanilla n'est pas un chemin mort.
- R-1492 : les deux backends respectent les mêmes **invariants géométriques**
  (19.2bis) : même position, même orientation, même échelle, même LOD, même
  géométrie déformée, mêmes pièces visibles ou masquées. Les capacités visuelles
  (éclairage, ombres, décalques, réflexions, parallax) diffèrent selon une
  matrice déclarée. Aucune équivalence pixel à pixel n'est exigée ni promise.

## 19.2bis Portée de l'équivalence entre backends (référence normative)

**Ce qui est garanti identique — invariants géométriques (testés strictement).**

```text
- présence et absence des instances (mêmes objets rendus, mêmes objets cullés)
- transform monde de chaque instance et de chaque node
- niveau de LOD sélectionné
- géométrie déformée : mêmes positions de sommets après champ et résidus,
  aux tolérances de quantification près
- pose de skinning
- visibilité des parts (masquées, détruites, révélées, détachées)
- silhouette : la couverture en pixels d'une instance ne diffère pas de plus de
  render.backend_silhouette_tolerance (défaut 1 %) entre les deux backends
- ordre des passes opaque / cutout / translucide
```

**Ce qui peut différer — capacités visuelles (matrice déclarée).**

| Capacité | NATIVE_GL | VANILLA_CONSUMER | Si indisponible |
|---|---|---|---|
| Modèle d'éclairage | PBR + sonde synthétisée | `VANILLA_COMPAT`, ou shaderpack actif | dégradation propre, jamais d'objet noir |
| Ombres AXION | carte d'ombre + contact | contact uniquement, ou ombres du shaderpack | désactivée, signalée |
| Décalques | passe dédiée, projection en espace objet | quads translucides plafonnés | réduits puis désactivés, signalé |
| Parallax, clearcoat, sheen, anisotropie | disponibles | non disponibles | ignorés, matériau rendu sans eux |
| SSR | `EXPERIMENTAL`, Q-4 | jamais | repli sur la sonde |
| Instancing, multi-draw indirect | disponibles | non | draw calls individuels |
| Skinning et déformation | GPU | CPU, plafonnés | pose de repos / géométrie non déformée au-delà du plafond, avec métrique |
| Occlusion culling logiciel | disponible | disponible (indépendant du backend) | désactivé, signalé |

- R-1493 : toute capacité indisponible dans le backend actif est **désactivée
  proprement** (aucun artefact, aucune erreur GL) et **signalée** : entrée de log
  unique au démarrage du backend, ligne dans `/axion status`, et indicateur dans
  l'overlay de diagnostic.
- R-1494 : le basculement de backend ne DOIT **jamais** modifier la simulation,
  les données persistées, l'état réseau ou le gameplay. Il ne touche qu'au rendu.
  Vérifié par T-491b.
- R-1495 : la matrice de capacités est **exposée par l'API** (`AxionApi.render()
  .capabilities()`) afin qu'un mod tiers sache ce qui est disponible avant de
  s'appuyer dessus.
- R-1496 : aucune formulation du document ne DOIT affirmer que les deux backends
  produisent une image identique. Le terme retenu est « équivalence fonctionnelle
  et géométrique ».

## 19.3 Gestion d'état OpenGL (règle critique)

- R-1500 : tout état **mis en cache par `GlStateManager`/`RenderSystem`** (blend, blend func, depth test/mask/func, cull, color mask, textures liées, programme, viewport, scissor, polygon offset, FBO lié) DOIT être modifié **exclusivement** via `RenderSystem`/`GlStateManager`. L'appel GL brut correspondant est interdit.
- R-1501 : les états non gérés par vanilla (VAO, VBO/EBO/UBO/TBO, attributs, `glVertexAttribDivisor`) peuvent être manipulés directement mais DOIVENT être restaurés en fin de passe.
- R-1502 : chaque passe est encadrée par un `GlStateGuard` en `try-finally`.
- R-1503 : en développement (`-Daxion.debug.gl=true`), comparaison post-passe de l'état réel à l'état attendu et `glGetError` après chaque groupe d'appels. Coût nul en production.
- R-1504 : aucun appel GL hors du render thread (INV-12).
- R-1505 : la passe d'ombre lie un FBO propre et **restaure** le FBO vanilla via `RenderSystem`, avec vérification en mode développeur.

## 19.4 Format de vertex GPU et attributs d'instance

```text
Sommet (48 o) :
  0  vec3   position          (float)
  1  vec4   normal            (byte normalisé)
  2  vec4   tangent           (byte normalisé)
  3  vec2   uv0               (ushort normalisé)
  4  vec2   uv1               (ushort normalisé)
  5  vec4   color             (ubyte normalisé)
  6  uvec4  bones             (ubyte entier)
  7  vec4   weights           (ubyte normalisé)
  8  uvec2  region_defw       (ubyte entier : region, def_w)

Instance (88 o, divisor 1) :
  9..11  mat4x3 model
  12     vec4   tint
  13     ivec2  lightmap
  14     uint   palette_offset
  15     uint   deform_offset
  16     uint   decal_offset_count
  17     uint   flags
```

## 19.5 Modèle d'éclairage : PBR avec environnement synthétisé

**Décision (ADR-008, révisée).** AXION implémente un **PBR metallic-roughness complet (Cook-Torrance GGX + Smith + Fresnel Schlick)**, alimenté par une **sonde d'environnement synthétisée** (C-81), et non par un simple Blinn-Phong.

**Justification technique.** L'objection classique — « Minecraft ne fournit pas d'irradiance environnementale » — se résout en **synthétisant** cette irradiance à partir de données que le jeu fournit réellement : couleur du ciel, couleur du brouillard, position et couleur du soleil et de la lune, heure, météo, biome, et lightmap vanilla à la position de l'objet. C'est une approche éprouvée et cohérente : elle produit des métaux et des vernis crédibles tout en restant accordée au monde.

```text
SONDE D'ENVIRONNEMENT (C-81)
  - un cubemap dynamique 32x32x6, 6 niveaux de mip, reconstruit toutes les
    render.probe_interval_frames frames (défaut 20) ou au changement notable
    de conditions (heure, météo, dimension)
  - contenu : dégradé de ciel vanilla, couleur de brouillard, couleur du soleil
    ou de la lune, couleur du sol dominante (échantillonnée sous la caméra),
    modulés par la pluie et l'épaisseur de nuages
  - préfiltrage GGX par niveau de mip (roughness croissante), calculé en une
    passe de 6*32*32 pixels : coût négligeable et borné
  - une harmonique sphérique L1 (4 coefficients RGB) est extraite pour la
    diffuse ambiante
  - un facteur d'occlusion par instance module la sonde selon la lightmap
    vanilla au centre de l'assembly (ou par node si render.per_node_lightmap)

BRDF
  vec3  F0 = mix(vec3(0.04), albedo, metallic);
  float D  = GGX(N, H, roughness);
  float G  = SmithHeightCorrelated(N, V, L, roughness);
  vec3  F  = FresnelSchlick(dot(H,V), F0);
  vec3  spec_direct = D*G*F / (4*NdotL*NdotV);
  vec3  diff_direct = (1-F)*(1-metallic)*albedo/PI;
  vec3  direct = (diff_direct + spec_direct) * sunColor * NdotL * shadow;

  vec3  irradiance = shDiffuse(u_shL1, N) * lightmapFactor;
  vec3  prefiltered = textureLod(u_probe, R, roughness*5.0).rgb * lightmapFactor;
  vec2  brdfLUT = texture(u_brdfLut, vec2(NdotV, roughness)).rg;
  vec3  ambient = (1-metallic)*albedo*irradiance*ao
                + prefiltered*(F0*brdfLUT.x + brdfLUT.y);

  vec3  color = direct + ambient + emissive;
  #ifdef CLEARCOAT
      color += clearcoatLobe(...);      // couche de vernis, carrosserie
  #endif
  color = tonemap(color);               // 19.9
```

- R-1510 : la table `brdfLUT` (256×256 RG16F) est **précalculée et embarquée** dans le JAR, pas générée au démarrage.
- R-1511 : `lightmapFactor` accorde le PBR à l'éclairage vanilla : un objet dans une grotte sombre reste sombre. Sans ce facteur, le PBR paraîtrait déconnecté du monde.
- R-1512 : le modèle est **gradué** : `Q-1` désactive le préfiltrage spéculaire (spéculaire analytique seulement), `Q-2` active la sonde, `Q-3` ajoute clearcoat/sheen/anisotropie, `Q-4` ajoute le parallax et le SSR optionnel.
- R-1513 : le backend `VANILLA_CONSUMER` utilise le modèle `VANILLA_COMPAT` : albedo × lightmap, avec une modulation douce par la normale et l'émissive. Il n'y a pas de PBR dans ce backend, car les `RenderType` vanilla n'exposent pas les entrées nécessaires ; cette limite est documentée. Quand un shaderpack est actif, c'est **lui** qui fournit l'éclairage, ce qui est le comportement attendu par le joueur.
- R-1514 : `RENDERING.md` documente précisément ce qui est physiquement fondé et ce qui est synthétisé.

## 19.6 Déformation dans le pipeline de rendu

Voir **14.6**. Points normatifs complémentaires :

- R-1520 : la variante de shader `DEFORMED` n'est compilée et utilisée que pour les meshes dont au moins un sommet porte une région. Un asset non déformable n'a **aucun surcoût**.
- R-1521 : les normales déformées sont obtenues par le gradient analytique (14.2) ; aucun recalcul CPU.
- R-1522 : le calcul du gradient partage les 8 lectures de nœuds avec le calcul du déplacement.
- R-1523 : les instances déformées et non déformées d'un même mesh sont regroupées séparément (clé de batch incluant la variante), ce qui évite toute branche divergente coûteuse dans le shader.

## 19.7 Occlusion culling logiciel (C-82)

**Décision (ADR-022).** AXION implémente un **rastériseur d'occlusion logiciel en Rust**, plutôt que des requêtes d'occlusion GPU.

**Justification.** Les requêtes GPU introduisent une latence d'au moins une frame, produisent des artefacts de pop et s'intègrent mal avec le culling vanilla. Un rastériseur logiciel de faible résolution donne un résultat **dans la même frame**, sans synchronisation GPU, et se parallélise bien.

```text
1. tampon de profondeur logiciel 256x144 (résolution configurable), f32
2. occluders :
     - boîtes des sections de chunk marquées opaques par le renderer vanilla
       (obtenues via C-64/Mixin ; si indisponible, cette source est désactivée)
     - AABB des assemblies AXION suffisamment grandes et opaques
     - au plus render.max_occluders (défaut 512), triés par surface projetée
3. rastérisation conservative des faces avant, SIMD, par tuiles de 8x8
4. test : l'AABB d'une instance est projetée ; si sa profondeur minimale est
   supérieure à la profondeur maximale du tampon sur son rectangle -> occultée
5. hystérésis : une instance déclarée occultée le reste au moins 3 frames
```

- R-1530 : le rastériseur est **conservatif** : il ne peut produire que des faux négatifs (objet visible déclaré visible à tort), jamais des faux positifs (objet visible déclaré occulté). Vérifié par T-925.
- R-1531 : budget `budgets.occlusion_ns` (défaut 800 µs) ; dépassement → réduction de la résolution puis désactivation, journalisée.
- R-1532 : désactivé aux niveaux `Q-0` et `Q-1`.

## 19.8 Ombres AXION (C-80)

**Décision (ADR-021).** AXION rend une **carte d'ombre dédiée à ses propres objets**, appliquée uniquement à ses propres objets.

**Justification.** Ombrer le monde vanilla exigerait de modifier son shading, ce qui est non additif (P-02) et incompatible avec les shaderpacks. En revanche, une carte d'ombre couvrant les seules instances AXION est peu coûteuse (peu d'objets, une passe de profondeur) et apporte l'essentiel : auto-ombrage d'un véhicule, ombre d'un objet sur un autre, ancrage visuel au sol.

```text
- une texture de profondeur 1024² à 2048² (selon le niveau), FBO propre
- projection orthographique depuis la direction du soleil/lune vanilla, cadrée
  sur l'AABB des instances AXION visibles, avec marge et quantification de la
  position pour éviter le scintillement
- rendu de profondeur : seules les instances SHADOW_CASTER, LOD réduit d'un cran,
  au plus budgets.max_shadow_instances (défaut 128)
- application : PCF 3x3 (Q-2), PCF 5x5 avec bruit bleu (Q-3+), biais de pente
- ombre de contact : un quad projeté sous l'assembly, dimensionné par l'AABB,
  toujours actif à partir de Q-1, et seul mécanisme au niveau Q-1
- l'ombre AXION est appliquée sur les objets AXION et sur un quad de réception
  au sol optionnel (render.shadow_ground_quad, défaut vrai), jamais sur les
  blocs vanilla eux-mêmes
```

- R-1540 : la passe d'ombre est **désactivée** en backend `VANILLA_CONSUMER` (le shaderpack fournit ses propres ombres) et aux niveaux `Q-0`.
- R-1541 : la passe d'ombre restaure intégralement l'état GL et le FBO vanilla (R-1505).
- R-1542 : budget `render.shadow_ns` inclus dans le budget de frame ; dépassement → réduction de résolution puis retour à l'ombre de contact seule.

## 19.9 Effets intégrés (C-83)

**Tone mapping (STABLE).** Le shading est calculé en linéaire HDR interne ; la sortie applique un opérateur de tone mapping (`ACES` approché ou `Reinhard` étendu, configurable) puis la correction gamma, calibré pour que les surfaces neutres correspondent exactement au rendu vanilla d'une texture équivalente. Cela évite le clipping des spéculaires et des émissives sur un framebuffer LDR (H-10).

- R-1550 : la calibration est vérifiée par un test d'image : une surface diffuse blanche AXION sous éclairage vanilla donne la même valeur de pixel qu'un bloc blanc vanilla, à 2 LSB près (T-927).

**Réflexions en espace écran — SSR (EXPERIMENTAL).** Utilise la texture de couleur et de profondeur du framebuffer principal (H-11), en marche de rayon à pas fixe avec raffinement binaire, uniquement sur les fragments dont `roughness < render.ssr_max_roughness` (défaut 0.25) et `metallic > 0.5`.

- R-1560 : SSR est `EXPERIMENTAL`, désactivé par défaut, niveau `Q-4` uniquement, désactivé automatiquement en backend vanilla et en présence d'un shaderpack. Ses artefacts (bords d'écran, occultations) sont documentés. En cas d'échec du rayon, le résultat retombe sur la sonde d'environnement — donc **jamais** de pixel noir.
- R-1561 : SSR est **implémenté**, pas seulement spécifié (R-003), et couvert par T-928.

**Hors périmètre.** Bloom, DOF, motion blur, et tout post-processing plein écran : ils modifieraient l'image entière, donc le rendu vanilla, ce qui est non additif et entre systématiquement en conflit avec les shaderpacks.

## 19.10 Passes de rendu

```text
PASSE 0  SHADOW      FBO propre, depth only, instances SHADOW_CASTER
PASSE 1  OPAQUE      depth write on, blend off, tri par (programme, matériau, mesh)
PASSE 2  CUTOUT      depth write on, discard par alpha
PASSE 3  DECALS      lecture de profondeur, blend, projection en espace objet
PASSE 4  TRANSLUCENT depth write off, blend, tri arrière-avant strict
PASSE 5  EMISSIVE    additif, éléments marqués EMISSIVE
PASSE 6  DEBUG       lignes et formes
```

- R-1570 : passes 0 au stage `AFTER_SKY` ; 1, 2, 3, 5 au stage `AFTER_ENTITIES` ; 4 au stage `AFTER_TRANSLUCENT_BLOCKS` ; 6 au stage `AFTER_PARTICLES`.

## 19.11 Transparence

- R-1580 : tri des translucides en natif, par distance au carré ; deux instances translucides qui se recouvrent dans l'axe de vue ne sont jamais regroupées.
- R-1581 : le tri **intra-mesh** des triangles translucides n'est pas effectué (coût prohibitif) ; la documentation recommande de séparer les surfaces translucides en meshes distincts. Limite explicite.

## 19.12 Budgets de rendu et dégradation

Ordre de dégradation côté rendu (chaque palier journalisé, réversible avec hystérésis) :

```text
1. SSR désactivé
2. parallax désactivé
3. résolution d'ombre réduite, puis ombre de contact seule, puis aucune ombre
4. occlusion culling : résolution réduite, puis désactivé
5. sonde d'environnement : intervalle de reconstruction allongé, puis SH seule
6. déformation : niveau réduit d'un cran (résidus, puis élastique)
7. décalques : nombre réduit
8. biais de LOD +1, puis +2
9. distance de rendu AXION réduite par paliers de 16 blocs
10. nodes de détail et d'intérieur masqués
11. instances skinnées réduites (pose de repos au-delà)
12. plafond d'instances rendues (les plus proches d'abord)
```

## 19.13 Tests de rendu

```text
T-470  rendu vanilla inchangé (comparaison d'images, tolérance 0)
T-471  aucune erreur GL sur 10 000 frames
T-472  état GL et FBO restaurés après chaque passe
T-473  aucun appel GL hors du render thread
T-474  assembly visible, orientée, éclairée
T-475  LOD aux distances attendues ; T-476 transparence ordonnée
T-477  instancing : 200 copies en <= 3 draw calls
T-478  chemin non instancié visuellement équivalent
T-479  échec de compilation de shader -> bascule vanilla
T-480  Iris actif -> backend vanilla, objets visibles et éclairés par le shaderpack
T-481  bascule de backend à chaud sans fuite GPU
T-482  ombre de contact présente et dimensionnée
T-483  changement de resource pack : ressources reconstruites
T-484  budget GPU dépassé -> dégradation par paliers, journalisée
T-485  100 assemblies visibles : draw calls et triangles mesurés et bornés
T-900  PBR : métal, vernis, plastique visuellement distincts et stables
T-901  déformation visible en backend vanilla, géométrie identique au backend
       natif aux tolérances de quantification près
T-902  plafonds CPU du backend vanilla respectés
T-903  pool de pages de déformation : pas de fuite après 10 000 cycles
T-904  compilation de variantes à la demande sans à-coup mesurable
T-905  cache binaire de shaders valide et invalidé au changement de pilote
T-906  culling stable, sans scintillement
T-907  chemin indirect (GL 4.3) visuellement identique au chemin instancié
T-908  skinning + déformation combinés corrects (comparaison CPU)
T-909  overlay de lattice de déformation
T-910  variante non déformée : coût GPU par sommet mesuré identique à celui d'un
       asset non déformable équivalent (B-25), et lectures de champ à zéro
T-911  8 lectures TBO par sommet déformé (compteur d'instrumentation)
T-912  pages dirty seules uploadées
T-913  plafond de pages par frame respecté, écart d'une frame au plus
T-914  décalques projetés correctement sur surface déformée
T-915  atlas de décalques, éviction correcte
T-916  usure visible et cohérente avec les canaux
T-917  ombre AXION : auto-ombrage d'un véhicule
T-918  ombre AXION : pas de scintillement au déplacement
T-919  ombre désactivée sous shaderpack
T-920  sonde d'environnement : métal reflète le ciel, change avec l'heure
T-921  sonde : coût borné, reconstruction espacée
T-922  objet en grotte reste sombre (lightmapFactor)
T-923  occlusion : objet derrière un mur non dessiné
T-924  occlusion : budget respecté
T-925  occlusion conservative : aucun faux positif sur 10 000 cas aléatoires
T-926  occlusion désactivée -> image identique, seulement plus de draw calls
T-927  tone mapping calibré sur le rendu vanilla (2 LSB)
T-928  SSR : reflet plausible, repli sur la sonde si le rayon échoue
T-929  SSR désactivé automatiquement sous shaderpack et en backend vanilla
```

---

# PARTIE 20 : MATÉRIAUX DE RENDU, DÉCALQUES, USURE ET ÉTATS DE SURFACE

## 20.1 États visuels

Le renderer distingue les états suivants, **sans variante de mesh obligatoire** :

| État | Mécanisme visuel |
|---|---|
| intact | matériau nominal |
| rayé | canal `scratch` → rugosité accrue, albédo légèrement éclairci, normal map de rayures modulée |
| sale | canal `soil` → mélange vers une couleur de saleté, rugosité accrue, réflexions atténuées |
| oxydé | canal `rust` → mélange vers une texture de rouille, métallicité réduite |
| brûlé | canal `burn` → assombrissement, rugosité maximale, émissive résiduelle si actif |
| légèrement déformé | déplacement du champ + accentuation des normales par le gradient |
| fortement déformé | idem + `damage_tex` canal G modulé par `max_strain` local |
| cassé / déchiré | matériau de bord déclaré, arêtes vives |
| détruit | variante de mesh `destroyed` ou masquage |
| détaché | rendu comme une assembly indépendante |

- R-1590 : les canaux d'usure et la déformation modulent des **paramètres de matériau**, ce qui produit une transition continue et évite la multiplication des textures. Les textures de dommage (`damage_tex`) sont facultatives ; en leur absence, une modulation procédurale par défaut est appliquée.
- R-1591 : `max_strain` local est reconstruit dans le fragment shader depuis le gradient de déformation (`||F - I||`), sans donnée supplémentaire.

## 20.2 Décalques (C-69)

```text
- projection en ESPACE OBJET : chaque décalque est un disque orienté défini par
  (centre local, normale, rayon, rotation, index d'atlas, intensité, type)
- rendu : passe dédiée, lecture de la profondeur, reconstruction de la position
  monde, transformation en espace de part, test d'appartenance au disque,
  puis échantillonnage de l'atlas
- LE DÉCALQUE SUIT LA DÉFORMATION : la position locale du fragment est
  dé-déformée par l'inverse approché du champ avant le test, ce qui fait qu'une
  rayure reste sur la tôle même quand la tôle s'enfonce
- au plus render.max_decals_per_assembly (64) et budgets.max_decals_frame (512)
- éviction : plus ancien et moins intense d'abord ; fondu de disparition
```

- R-1600 : l'inverse du champ est approché par une itération de Newton unique à partir de la position déformée. L'erreur est bornée et documentée ; elle est imperceptible pour les amplitudes autorisées.
- R-1601 : les décalques sont **client-only** dans leur représentation ; seuls les événements qui les créent et les canaux d'usure sont autoritatifs (R-681).
- R-1602 : un décalque de type `DENT_RING` est généré automatiquement autour d'un impact plastique significatif, ce qui accentue visuellement le cabossage sans coût géométrique.

## 20.3 Profils d'usure

```json
data/<ns>/axion/wear_profiles/painted_steel.json
{
  "schema": 1,
  "scratch_gain": 1.0, "soil_gain": 0.6, "burn_gain": 1.0, "rust_gain": 0.2,
  "scratch_decay": 0.0, "soil_decay": 0.25,
  "decal_atlas": "axion:decals/metal",
  "rust_requires_scratch": true
}
```

- R-1610 : `rust_requires_scratch` illustre la composition : la rouille n'apparaît que là où la peinture est entamée. C'est une règle de données, pas de code.

## 20.4 Budgets

| Ressource | Budget | Surcharge |
|---|---|---|
| Décalques par assembly | 64 | éviction |
| Décalques par frame | 512 | les plus lointains ignorés |
| Atlas de décalques | 2048² | refus de nouvelles entrées à la compilation |
| Zones d'usure par part | 32 | agrégation |
| Coût de la passe de décalques | inclus dans le budget de frame | passe désactivée au niveau `Q-1` |

**Tests.** T-870..T-879, T-914..T-916.

---
# PARTIE 21 : CLIENT / SERVEUR ET NETWORKING

## 21.1 Modèle d'autorité

```text
SERVEUR (autoritatif)                        CLIENT
 - simulation physique                        - interpolation des snapshots
 - impacts, dommage, déformation plastique    - reconstruction déterministe de
 - intégrité, rupture, détachement              la déformation depuis les impacts
 - usure quantifiée                           - déformation élastique visuelle
 - validation des entrées                     - décalques, particules VISUAL
 - décision de spawn/despawn                  - prédiction du seul véhicule piloté
                                              - aucune autorité, jamais
```

- R-1620 : le serveur ne fait jamais confiance au client, y compris en solo (serveur intégré, même code). INV-02, INV-17.

## 21.2 Canal et protocole

```text
Canal Forge : SimpleChannel "axion:main"
Version de protocole : "AXION-1"
```

- R-1630 : AXION est **requis des deux côtés**. Un client sans AXION ne peut pas rejoindre un serveur avec AXION : les entités AXION ne pourraient être ni représentées ni simulées, et toute dégradation produirait des entités fantômes.
- R-1631 : la version de protocole est indépendante de la version du mod et n'est incrémentée qu'en cas de rupture.

## 21.3 Handshake

```text
S2C_Handshake :
  protocol_version u16 | sim.fixed_dt f32 | sim.max_substeps u8
  physics.gravity f32  | snapshot_rate_hz u8 | interpolation_delay_ms u16
  prediction_enabled bool | deformation_enabled bool
  deform_quant_policy u8  (politique de quantification, doit être identique)
  definitions_hash u64 | damage_sources_hash u64 | materials_hash u64
```

- R-1640 : si un hash diffère, le serveur envoie les données correspondantes (definitions, matériaux, sources de dégâts) compressées avant l'entrée en jeu. Les **assets** ne transitent jamais par le réseau : ils viennent des resource packs. Un asset manquant côté client produit l'asset de secours et un avertissement, jamais une déconnexion.
- R-1641 : `deform_quant_policy` garantit que les deux côtés quantifient identiquement. Une divergence de politique désactive la reconstruction et bascule sur les instantanés (mode dégradé, journalisé).

## 21.4 Paquets

| Paquet | Sens | Fréquence | Contenu |
|---|---|---|---|
| `S2C_Handshake` | S→C | login | 21.3 |
| `S2C_Registry` | S→C | login / reload | definitions, matériaux, sources, profils d'usure (zstd) |
| `S2C_AssemblySpawn` | S→C | apparition | definition, transform, états de parts, intégrités, **empreinte de champ + instantané compressé si déformée**, usure, attaches |
| `S2C_AssemblyDespawn` | S→C | disparition | handle, raison |
| `S2C_Snapshot` | S→C | `snapshot_rate_hz` | delta d'états des assemblies visibles |
| `S2C_Impacts` | S→C | à l'occurrence | **lot d'`ImpactDesc` compacts** (source de la reconstruction) |
| `S2C_DamageState` | S→C | au changement | états de parts, intégrités de liaisons, canaux d'usure modifiés |
| `S2C_Structural` | S→C | à l'occurrence | ruptures de liaisons, détachements (avec identifiant de la nouvelle assembly) |
| `S2C_DeformSnapshot` | S→C | resynchronisation | champ plastique quantifié d'une région, zstd |
| `S2C_Repair` | S→C | à l'occurrence | part, niveau, quantité |
| `S2C_Attachment` | S→C | à l'occurrence | création, rupture d'attache |
| `S2C_Correction` | S→C | divergence | état autoritatif complet du véhicule prédit |
| `C2S_Input` | C→S | ≤ 20 Hz | entrées + numéro de séquence |
| `C2S_Interact` | C→S | action | montée, descente, interaction, réparation demandée |
| `C2S_Ack` | C→S | par snapshot | dernier snapshot reçu, **empreintes de champ des assemblies proches** |

- R-1650 : tout paquet reçu est validé (taille, bornes, cohérence, appartenance). Un paquet invalide est ignoré et journalisé ; il ne lève jamais d'exception dans le pipeline Netty (`E-5001`).
- R-1651 : taille maximale d'un paquet AXION : 32 KiB ; au-delà, fragmentation ordonnée.

## 21.5 Snapshots, quantification, delta

```text
Entrée d'assembly dans un snapshot :
  handle          varint
  flags           u8   (champs présents)
  position        3 x i32 quantifié 1/1024 bloc, en delta sur le dernier ack
  rotation        u32  quaternion "smallest three" (3 x 10 bits + 2 bits)
  lin_vel/ang_vel 3 x i16 quantifiés (optionnels)
  états procéduraux selon un descripteur déclaré et versionné
```

- R-1660 : la quantification est **déclarée par champ**, jamais implicite.
- R-1661 : delta par rapport au dernier snapshot **acquitté** (fenêtre de 32) ; sinon snapshot complet.
- R-1662 : fréquence adaptative par assembly : `HIGH` 20 Hz (pilotée par un joueur proche), `NORMAL` 10 Hz, `LOW` 2 Hz (endormie ou lointaine, débris), `NONE` hors `net.sync_radius` (96 blocs).
- R-1663 : une assembly endormie et inchangée n'envoie **rien**.
- R-1664 : débit plafonné par joueur (`net.max_bytes_per_second_per_player`, défaut 32 KiB/s) ; au-delà, dégradation par priorité, jamais omission silencieuse.

## 21.6 Réplication de la déformation (mécanisme central)

**Décision (ADR-018).** La déformation est répliquée **par événements et reconstruction**, pas par données.

```text
SERVEUR
  1. applique les impacts, produit le champ plastique quantifié
  2. envoie les ImpactDesc compacts aux clients dans le rayon de synchronisation
  3. joint périodiquement (toutes les net.digest_interval_ticks, défaut 40)
     une empreinte 64 bits par assembly déformée

CLIENT
  4. rejoue les impacts reçus, dans l'ordre de seq, avec le noyau C-16
  5. obtient le même champ plastique, bit à bit (INV-14)
  6. renvoie ses empreintes dans C2S_Ack

RESYNCHRONISATION
  7. si le serveur constate une empreinte différente, il envoie un
     S2C_DeformSnapshot de la ou des régions divergentes (champ i8 zstd)
  8. le client applique l'instantané et repart de là
```

**Format compact d'un `ImpactDesc` réseau (32 octets) :**

```text
assembly     varint
part         u8      zone u8      region u8      flags u8
point_local  3 x i16 quantifié 1/1024 m dans l'OBB de la région
normal_local 3 x i8  octonormalisé
energy       f16     normal_energy f16    shear_energy f16
area         f16     effective_mass f16
seq          varint
```

- R-1670 : **aucune donnée par nœud de lattice ni par sommet n'est envoyée en régime normal** (INV-15). Le trafic de déformation est proportionnel au nombre d'impacts, pas à la complexité géométrique.
- R-1671 : un `S2C_DeformSnapshot` d'une région de 8³ nœuds pèse 1536 octets bruts, typiquement quelques centaines d'octets après zstd. Il est plafonné (`net.max_deform_snapshots_per_second`, défaut 4) et priorisé par proximité.
- R-1672 : un client qui rejoint en cours de partie reçoit l'instantané complet dans `S2C_AssemblySpawn`, jamais l'historique des impacts.
- R-1673 : les impacts sont émis en **lots** (un paquet par tick au plus, contenant tous les impacts de ce tick pour les assemblies visibles), avec un plafond `net.max_impacts_per_packet` (défaut 64) ; l'excédent est reporté ou remplacé par un instantané si le retard devient important.
- R-1674 : la réparation est répliquée de la même manière (événement `S2C_Repair`, reconstruction déterministe).

## 21.7 Interpolation client

```text
- tampon de snapshots, rendu à (temps_serveur_estimé - interpolation_delay_ms)
- position par Hermite (position + vitesse), rotation par slerp
- tampon vide -> extrapolation bornée à extrapolation_max_ms (250 ms) puis figement
- retour de snapshot -> correction douce sur 200 ms, ou téléportation si l'écart
  dépasse net.snap_threshold (2 blocs)
```

## 21.8 Prédiction et réconciliation

- R-1680 : la prédiction est **limitée au véhicule piloté** ; aucune autre assembly n'est prédite.
- R-1681 : le client prédit le mouvement, **jamais la déformation plastique** (INV-17). Un choc pendant la prédiction produit chez le client une déformation **élastique** provisoire ; le plastique n'apparaît qu'à la réception de l'impact autoritatif. L'écart est imperceptible (une frame ou deux) et cette règle supprime toute possibilité de dommage fantôme.
- R-1682 : réconciliation : historique de 64 entrées, rejeu depuis l'état serveur ; si le rejeu dépasse son budget, le client accepte l'état serveur sans rejeu.
- R-1683 : la prédiction est désactivable et automatiquement désactivée au-delà de `net.prediction_max_ping` (400 ms) ou si le taux de corrections dépasse un seuil.

## 21.9 Sécurité réseau

- R-1690 : limitation de débit C→S (`net.max_packets_per_second`, défaut 40) ; abus soutenu → déconnexion.
- R-1691 : toute donnée décompressée est bornée ; un `S2C_Registry` ou un `S2C_DeformSnapshot` malformé ne peut pas provoquer d'allocation non bornée.
- R-1692 : aucun paquet ne transporte de chemin de fichier, de nom de classe ou de donnée sérialisée Java.
- R-1693 : les `ImpactDesc` reçus par le client sont **validés et bornés** avant rejeu (énergie, aire, indices) ; un impact invalide est ignoré et déclenche une demande d'instantané.

## 21.10 Budgets réseau

| Élément | Budget | Surcharge |
|---|---|---|
| Débit par joueur | 32 KiB/s | dégradation par priorité |
| Impacts par paquet | 64 | report ou instantané |
| Instantanés de déformation par seconde | 4 | priorisation par distance |
| États de dommage par tick | 128 | agrégation par assembly |
| Taille d'un paquet | 32 KiB | fragmentation |

## 21.11 Tests

```text
T-410  connexion, apparition, mouvement
T-411  bande passante mesurée sous le plafond
T-412  perte de 20 % : mouvement plausible
T-413  latence 200 ms : interpolation sans saccade
T-414  extrapolation bornée puis figement
T-415  prédiction : réponse immédiate du véhicule piloté
T-416  réconciliation : correction douce
T-417  entrée falsifiée -> ignorée
T-418  débit plafonné : dégradation, pas d'omission silencieuse
T-419  client sans AXION -> refus clair
T-420  registres synchronisés, hash vérifié
T-421  asset manquant côté client -> fallback visuel
T-422  paquet corrompu -> ignoré, pas d'exception Netty
T-423  100 assemblies + 10 joueurs : bande passante et CPU mesurés
T-424  reconnexion : état complet reçu, déformation incluse
T-425  1 heure, 20 joueurs simulés : aucune désynchronisation
T-823  reconstruction depuis les événements, sans instantané
T-824  divergence forcée -> instantané, convergence
T-825  audit de paquets : aucune donnée par sommet ni par nœud en régime normal
T-829  100 impacts par seconde sur 20 assemblies : trafic mesuré et borné
```

---

# PARTIE 22 : PERSISTANCE

## 22.1 Ce qui est persisté

| Donnée | Persistée | Forme |
|---|---|---|
| Definition appliquée | ✔ | id 64 bits |
| Transform monde, vitesses, sommeil | ✔ | quantifié |
| Santé, intégrité, étape, énergie absorbée par part | ✔ | 8 octets par part |
| Intégrité des liaisons structurelles | ✔ | 2 octets par liaison |
| **Champ plastique de chaque région déformée** | ✔ | i8 quantifié, zstd |
| Canaux d'usure par zone de surface | ✔ | 4 octets par zone |
| Décalques marquants | ✔ | au plus 16 par assembly |
| Parts détachées encore rattachées logiquement (dangling) | ✔ | liste |
| État du groupe motopropulseur | ✔ | compound |
| Attaches | ✔ | par identifiant d'assembly |
| Avancement d'une réparation en cours | ✔ | part, niveau, fraction |
| Blob personnalisé d'un mod tiers | ✔ | CBOR, plafonné |
| Occupants | ✘ | reconstruit par la monture vanilla |
| Champ élastique, vitesses de nœud | ✘ | dérivé, transitoire |
| Résidus par sommet | ✘ | reconstruits depuis le champ |
| Matrices, poses, palettes, contacts, îlots | ✘ | dérivé |
| État de particules | ✘ | dérivé (repos au chargement) |
| Débris séparés | configurable, faux par défaut | assemblies indépendantes |

- R-1700 : rien de dérivable n'est persisté. Le coût de sauvegarde est proportionnel à l'**état réel de dommage**, pas à la complexité géométrique.
- R-1701 : la taille totale du blob de persistance d'une assembly est plafonnée (`persistence.max_bytes_per_assembly`, défaut 64 KiB). Au-delà : les régions les moins déformées sont sous-échantillonnées avant écriture, en dernier recours les décalques et l'usure sont abandonnés (dans cet ordre), et l'événement est journalisé. **Le champ plastique n'est jamais abandonné.**

## 22.2 Format NBT

```text
axion:v         int      version du schéma (2)
axion:def       long
axion:pos       [double] 3        axion:rot  [float] 4
axion:lvel      [float]  3        axion:avel [float] 3
axion:flags     int
axion:parts     byte[]   8 octets par part : stage, flags, health u8, integrity u8,
                         absorbed f16, deform_max f16
axion:links     byte[]   2 octets par liaison : integrity u8, flags u8
axion:deform    byte[]   blob binaire : nombre de régions, puis par région
                         (index u16, res u8[3], quant_step f16, données i8 zstd)
axion:wear      byte[]   4 octets par zone de surface
axion:decals    byte[]   décalques marquants (16 octets chacun)
axion:dangling  list     parts détachées encore rattachées
axion:attach    list     attaches (uuid distant, type, sockets)
axion:repair    compound réparation en cours
axion:power     compound
axion:custom    byte[]   CBOR, optionnel
```

- R-1710 : le schéma est versionné ; une version **supérieure** rend l'assembly inerte **sans perte** (les octets sont conservés et réécrits tels quels). Une version inférieure est migrée par une fonction explicite et testée.
- R-1711 : AXION n'écrit rien en dehors du NBT de ses propres entités, du NBT de ses `BlockEntity`, et de `<gameDir>/axion/` (INV-10). Un unique `SavedData` `axion_registry` conserve les correspondances d'attaches inter-assemblies ; son absence est tolérée et n'entraîne que la perte des attaches.

## 22.3 Sérialisation du champ de déformation

```text
Par région déformée :
  index u16 | res u8[3] | quant_step f16 | flags u8
  données : node_count * 3 octets i8, compressés zstd niveau 3
Optimisation : les régions dont tous les nœuds sont nuls ne sont pas écrites.
```

- R-1720 : la sérialisation est effectuée **en natif** (C-42) et transmise à Java comme un blob opaque, ce qui évite toute traversée FFI par nœud.
- R-1721 : au chargement, le champ est restauré tel quel, sans rejeu d'impacts : le rechargement est donc exact et instantané (T-828).
- R-1722 : ordre de grandeur documenté et **mesuré** par B-24, jamais affirmé sans mesure.

## 22.4 Chargement et déchargement de chunk

```text
chunk chargé   -> AxionEntity désérialisée, assembly créée ENDORMIE,
                  champ de déformation restauré, colliders refités une fois,
                  réveil si un joueur est proche
chunk déchargé -> assembly détruite côté natif, état écrit dans le NBT
dimension déchargée -> monde physique détruit
```

- R-1730 : la création au chargement ne bloque pas le thread autoritatif au-delà de `persistence.max_load_ns_per_tick` (défaut 500 µs) ; l'excédent est mis en file. Le refit initial de collider est compté dans le budget de refit.

## 22.5 Compatibilité de sauvegarde et retrait du mod

### 22.5.1 Constat technique

Lorsqu'un monde est chargé **sans** AXION, Minecraft rencontre des entités et des
`BlockEntity` dont le type n'est pas enregistré. Le comportement vanilla est de
**les ignorer au chargement du chunk** ; si ce chunk est ensuite sauvegardé, les
données correspondantes ne sont pas réécrites et sont donc perdues. Ce
comportement appartient à Minecraft, pas à AXION, mais AXION ne peut pas se
contenter de le constater : il doit rendre la perte **récupérable**.

### 22.5.2 Journal latéral (side-car)

- R-1743 : AXION maintient un **journal latéral** hors du monde, dans
  `<gameDir>/axion/world/<worldId>/assemblies.<n>.journal`, où `worldId` est un
  hachage stable du chemin du niveau et de sa graine. Il contient, pour chaque
  assembly et chaque bloc AXION persistant : identifiant, dimension, position,
  definition, et le blob de persistance complet (22.2), avec magic, version de
  schéma et CRC.
- R-1744 : le journal est écrit **de façon incrémentale** au même moment que la
  sauvegarde du monde, par segments append-only compactés périodiquement. Son
  écriture est budgétée (`persistence.journal_ns_per_tick`, défaut 200 µs) et ne
  bloque jamais le thread autoritatif.
- R-1745 : le journal est un **filet de sécurité**, jamais la source de vérité.
  Tant que l'entité existe dans le monde, c'est son NBT qui fait foi. Le journal
  n'est consulté que pour les assemblies dont l'entité a disparu sans événement
  de suppression explicite.
- R-1746 : le journal respecte INV-10 : il est hors du monde, et son absence
  n'empêche jamais le chargement ni le jeu. Sa taille est plafonnée
  (`persistence.journal_max_bytes`, défaut 256 MiB) avec compactage puis éviction
  des entrées les plus anciennes, journalisée.

### 22.5.3 Matrice de comportement (normative)

| # | Situation | Comportement | Données |
|---|---|---|---|
| 1 | **Monde chargé sans AXION** | Minecraft ignore les entités et `BlockEntity` AXION ; le monde se charge, se joue et se sauvegarde normalement | le NBT des entités peut être perdu par vanilla ; le **journal latéral subsiste** dans `<gameDir>/axion/`, intact |
| 2 | **Definition AXION supprimée, mod présent** | l'assembly est chargée **inerte** : pas de physique, pas de rendu, pas de dommage, **NBT réécrit à l'identique** ; message une fois par definition | aucune perte ; réapparition de la definition = retour à la normale |
| 3 | **Monde rechargé après réinstallation d'AXION** | les assemblies dont l'entité a survécu reprennent depuis leur NBT ; celles dont l'entité a disparu sont **restaurées depuis le journal** si le `worldId` correspond, à leur position et dimension enregistrées, en état endormi, avec un rapport listant les restaurations | restauration complète, déformation et structure incluses |
| 4 | **Asset visuel absent** (resource pack retiré) | l'assembly reste simulée et persistée ; le rendu utilise `axion:builtin/missing` ; avertissement unique | aucune perte ; **jamais** de déconnexion ni de suppression |
| 5 | **Entité/assembly dont le mod fournisseur de contenu est absent** (definition d'un mod tiers) | identique au cas 2 : inerte, préservée, restaurable | aucune perte |
| 6 | **Mode `DISABLED`** (natif absent, ABI incompatible) | assemblies chargées inertes, NBT réécrit à l'identique, journal non mis à jour | aucune perte (R-410) |

- R-1740 : un monde créé avec AXION est **toujours chargeable sans AXION**, sans
  erreur bloquante et sans corruption. Vérifié par T-435 et T-713.
- R-1741 : le retrait puis la réinstallation d'AXION restaure les assemblies avec
  leur déformation, leur structure, leur usure et leurs attaches : depuis le NBT
  si l'entité a survécu, **sinon depuis le journal latéral**. Vérifié par T-713 et
  T-713b.
- R-1742 : une definition supprimée rend les assemblies concernées **inertes mais
  préservées**, jamais supprimées automatiquement. Seule une commande explicite
  (`/axion remove`) les supprime.
- R-1747 : AXION ne supprime **jamais** silencieusement une donnée persistante.
  Toute suppression est soit demandée explicitement, soit imposée par un plafond
  déclaré, et elle est journalisée avec sa cause.
- R-1748 : la restauration depuis le journal est **idempotente** et refuse de
  créer un doublon : une assembly dont l'identifiant est déjà présent dans le
  monde n'est jamais recréée.
- R-1749 : la règle R-1630 reste inchangée : **AXION demeure obligatoire côté
  client pour se connecter à un serveur AXION.** Rien dans cette section ne rend
  le mod optionnel en réseau ; il ne s'agit que de la survie des données locales.

## 22.6 Tests

```text
T-430  transform et vitesses préservées
T-431  états de parts et intégrités préservés
T-432  blob personnalisé préservé, dépassement refusé proprement
T-433  version future -> entité inerte, données intactes après re-sauvegarde
T-434  migration v1 -> v2
T-435  monde chargé sans AXION puis rechargé avec : état identique
T-436  10 000 assemblies : temps de sauvegarde mesuré, pas de blocage
T-437  aucune écriture hors des emplacements autorisés (audit de fichiers),
       journal latéral inclus et situé hors du monde
T-713b RETRAIT PUIS RÉINSTALLATION AVEC PERTE D'ENTITÉS : monde joué sans AXION
       sur les chunks concernés, entités supprimées par vanilla, puis
       réinstallation : les assemblies sont restaurées depuis le journal latéral,
       à leur position, avec champ, structure, usure et attaches identiques
T-713c journal absent ou corrompu -> aucune restauration, aucun crash, message
       clair, monde jouable
T-713d restauration idempotente : aucun doublon si l'entité a survécu
T-713e definition absente (cas 2) puis réapparue : NBT inchangé entre-temps,
       retour à la normale sans perte
T-713f asset visuel absent (cas 4) : simulation et persistance intactes,
       rendu de secours, aucune déconnexion
T-828  champ de déformation identique bit à bit après cycle
T-890b attaches restaurées, suspendues si l'autre extrémité est déchargée
T-884  réparation en cours reprise après rechargement
```

---

# PARTIE 23 : API PUBLIQUE ET CONTENU DATA-DRIVEN

## 23.1 Principes

- R-1750 : `axion-api` est un module Gradle séparé, publié indépendamment, **sans dépendance à l'implémentation ni au natif**.
- R-1751 : versionnement sémantique de l'API, indépendant du mod ; compatibilité binaire ascendante garantie en 1.x.
- R-1752 : annotations `@Stable`, `@Experimental`, `@Internal` ; rien d'`@Internal` dans `axion-api`.
- R-1753 : aucun type Rapier, glam ou handle natif brut n'est exposé.
- R-1754 : chaque méthode documente effet, thread autorisé, coût et conditions d'échec.
- R-1755 : toute méthode mutante vérifie le thread appelant et lève `IllegalStateException` hors du thread autoritatif.

## 23.2 Surface de l'API

```java
package dev.axion.api;

public interface AxionApi {
    static AxionApi get();
    static Optional<AxionApi> getIfReady();

    ApiVersion        version();
    RuntimeState      state();
    QualityProfile    quality();
    AssetService      assets();
    DefinitionService definitions();
    AssemblyService   assemblies();
    PhysicsService    physics();
    DamageService     damage();
    DeformationService deformation();
    AttachmentService attachments();
    EventBus          events();
    RegistryAccess    registries();
    Diagnostics       diagnostics();
}

public interface AssemblyService {
    Assembly spawn(Level level, DefinitionRef def, Vec3 pos, Quaternionf rot);
    Optional<Assembly> byEntity(Entity e);
    Collection<Assembly> inRange(Level level, Vec3 center, double radius);
}

public interface Assembly {
    UUID id(); DefinitionRef definition(); Entity entity();
    Transform transform(); Vec3 linearVelocity(); Vec3 angularVelocity();

    void applyForce(Vec3 f, @Nullable Vec3 worldPoint);
    void applyImpulse(Vec3 j, @Nullable Vec3 worldPoint);
    void applyTorque(Vec3 t);
    void setKinematicTransform(Transform t);

    Optional<Socket> socket(String name);
    Collection<String> socketNames();

    PartView part(String name);
    Collection<String> partNames();
    StructureView structure();
    DeformationView deformation();
    SurfaceView surface();

    Optional<VehicleControl> vehicle();
    AnimationControl animation();
    Collection<ParticleSetView> particleSets();
    Collection<AttachmentView> attachments();
    CustomData customData();

    boolean isValid(); void remove();
}

/// Point d'entrée unique du dommage : tout passe par un impact.
public interface DamageService {
    /// Applique un impact physique complet. Serveur uniquement.
    void applyImpact(Assembly target, ImpactSpec spec);
    /// Raccourci : convertit un DamageSource Minecraft selon le mappage déclaratif.
    void applyVanillaDamage(Assembly target, DamageSource src, float amount,
                            @Nullable Vec3 point);
    /// Réparation.
    void repair(Assembly target, @Nullable String part, RepairLevel level, float amount);
    /// Détachement forcé (outil, gameplay).
    boolean detach(Assembly target, String part);
}

public interface ImpactSpec {
    static Builder builder();
    interface Builder {
        Builder point(Vec3 worldPoint);
        Builder normal(Vec3 worldNormal);
        Builder energy(float joules);
        Builder area(float squareMeters);
        Builder effectiveMass(float kg);
        Builder flags(ImpactFlag... flags);   // SHARP, BLUNT, THERMAL, CONTINUOUS
        Builder source(ImpactSource src);
        ImpactSpec build();                   // valide et borne toutes les valeurs
    }
}

public interface DeformationView {
    boolean isDeformed();
    float   maxDisplacement(String part);      // m
    float   meanDisplacement(String part);
    float   maxStrain(String part);
    int     regionCount();
    QualityLevel activeLevel();
    /// Lecture d'un champ, en lecture seule, copiée. Coûteux : usage debug/outil.
    @Experimental float[] sampleField(String region);
    /// Restauration progressive. Serveur uniquement.
    void    restore(String part, float amount);
}

public interface StructureView {
    float integrity(String link);
    Collection<String> linkNames();
    boolean isDetached(String part);
    /// Graphe résiduel : parts encore connectées à la racine.
    Set<String> connectedParts();
}

public interface SurfaceView {
    float scratch(String part); float soil(String part);
    float burn(String part);    float rust(String part);
    void  clean(String part);   // serveur
}

public interface PartView {
    String name(); PartStage stage();
    float health(); float integrity(); float absorbedEnergy();
    boolean isJammed(); boolean isRevealed();
}
```

**Événements** : `AssemblySpawnEvent`, `AssemblyRemoveEvent`, `ContactEvent`, `SensorEvent`, `ImpactEvent`, `PartStageChangeEvent`, `PartDeformedEvent`, `LinkBrokenEvent`, `PartDetachedEvent`, `DebrisSpawnedEvent`, `RepairEvent`, `AttachEvent`, `DetachEvent`, `SeatEnterEvent`, `SeatExitEvent`, `VehicleInputEvent`, `QualityChangeEvent`, `DegradationEvent`.

- R-1760 : tous les événements sont émis sur le **thread autoritatif**, dans la phase `TICK_COLLECT`. Aucun événement depuis un worker.
- R-1761 : un handler qui lève une exception est isolé, journalisé avec le mod propriétaire quand il est identifiable, et désactivé après 5 échecs.
- R-1762 : `ImpactSpec.Builder.build()` **valide et borne** toutes les valeurs : un mod tiers ne peut pas injecter une énergie aberrante.

## 23.3 Points d'extension

| Extension | Mécanisme | Registry |
|---|---|---|
| Matériau physique | JSON ou `registerDynamic` | `axion:physics_material` |
| Groupe de collision | JSON | `axion:collision_group` |
| Profil d'usure | JSON | `axion:wear_profile` |
| Règle de réparation | JSON | `axion:repair_rule` |
| Mappage de source de dégât | JSON | `axion:damage_source` |
| Source procédurale | `ProceduralSourceProvider` | `axion:procedural_source` |
| Contrôleur de véhicule | `VehicleController` | `axion:vehicle_controller` |
| **Modèle de dommage** | `DamageDistributor` (remplace la répartition 13.3) | `axion:damage_distributor` |
| **Noyau de déformation** | `DeformationKernel` (`@Experimental`, doit rester déterministe) | `axion:deformation_kernel` |
| Hook de rendu | `AssemblyRenderHook` (client) | `axion:render_hook` |
| Importer de format | `AssetImporter` | `axion:importer` |

- R-1770 : une extension tierce est **isolée** : son coût est mesuré séparément et son dépassement répété du budget la désactive avec un message la nommant.
- R-1771 : les extensions Java ne s'exécutent jamais sur les workers natifs ; elles sont appelées sur le thread autoritatif entre deux pas, ou sur le render thread pour les hooks de rendu.
- R-1772 : un `DeformationKernel` tiers **doit** respecter le contrat de déterminisme ; s'il ne le respecte pas, la reconstruction client échoue et le système bascule automatiquement sur l'envoi d'instantanés pour les assemblies concernées, avec un avertissement nommant le mod. Le jeu reste correct.

## 23.4 Schéma de Definition (normatif)

```json
{
  "schema": 1,
  "asset": "mymod:models/pickup.glb",
  "kind": "vehicle",

  "physics": { "mass": 1650.0, "center_of_mass": [0,0.45,-0.1],
               "linear_damping": 0.05, "angular_damping": 0.3, "ccd": true,
               "collision_group": "assembly",
               "collides_with": ["world","assembly","entity_proxy","debris"] },

  "bodies": [ { "name": "chassis", "root_node": "body", "colliders": "auto_compound" } ],

  "joints": [ { "name": "hood_hinge", "type": "revolute", "a": "chassis", "b_node": "hood",
                "axis": [1,0,0], "limits": [0.0,1.4],
                "motor": { "speed": 2.0, "max_force": 200.0 },
                "break_force": 8000.0, "jam_curve": [[0,1.0],[0.05,0.5],[0.12,0.0]] } ],

  "wheels": [ { "node": "wheel_fl", "steering": true, "powered": false,
                "radius": 0.36, "width": 0.24, "mass": 20.0, "tire": "mymod:tire/road",
                "suspension": { "rest_length": 0.35, "max_travel": 0.25,
                                "stiffness": 30.0, "damping_compression": 2.3,
                                "damping_rebound": 2.8, "max_force": 60000.0 } } ],

  "powertrain": { }, "steering": { }, "brakes": { }, "aero": { },

  "parts": [
    { "name": "hood", "root_node": "hood", "material": "axion:steel",
      "health": 100.0, "structural_capacity": 9000.0,
      "detach_threshold": 0.0, "propagation": 0.25, "mass": 18.0,
      "meshes": { "intact": "hood", "damaged": null, "destroyed": "hood_wrecked" },
      "flags": ["detachable"],
      "deform_regions": ["hood_front", "hood_rear"],
      "collider_variants": { "HEAVY": "hood_collider_crushed" },
      "repair_item": "minecraft:iron_ingot" }
  ],

  "structural_links": [
    { "name": "hood_to_body", "a": "body", "b": "hood", "kind": "hinge",
      "capacity": 5000.0, "tensile": 14000.0, "shear": 9000.0, "torque": 600.0,
      "joint": "hood_hinge", "propagation": 0.35, "flags": ["load_bearing"] }
  ],

  "damage_zones": [
    { "name": "front", "part": "hood", "region": "hood_front",
      "multiplier": 1.5, "deform_multiplier": 1.6, "node": "zone_front" },
    { "name": "front_center", "parent": "front", "multiplier": 1.2 }
  ],

  "deformation": { "enabled": true, "min_quality": "low",
                   "extras": { "bones": [], "morphs": [] } },

  "wear": { "profile": "axion:painted_steel" },

  "particles": [ ],

  "seats": [ { "name": "driver", "node": "seat_driver", "role": "driver",
               "exit_offsets": [[-1.2,0,0],[1.2,0,0]],
               "controls": ["throttle","brake","steer","gear"] } ],

  "sockets": ["socket_towbar", "socket_exhaust"],

  "procedural": [ ],
  "animations": { "door_open": { "clip": "door_l_open", "layer": 1,
                                 "drives_joint": "door_l_hinge",
                                 "trigger": "interact:door_l" } },

  "on_part_stage": { },
  "on_detach": { },

  "render": { "shadow": "map", "max_distance": 128, "lod_bias": 0,
              "decals": true },

  "interaction": [ { "node": "door_l", "action": "toggle_animation",
                     "animation": "door_open" },
                   { "node": "seat_driver", "action": "sit", "seat": "driver" } ],

  "repair_rule": "axion:default_vehicle",

  "custom": { }
}
```

- R-1780 : `schema` obligatoire ; valeur inconnue → refus (`E-7003`).
- R-1781 : tous les champs sauf `schema`, `asset` et `kind` sont optionnels avec des défauts documentés. **Un objet déformable et destructible fonctionne avec `asset` + `kind` seuls**, grâce aux générations automatiques de C-28.
- R-1782 : les actions d'interaction et les actions de `on_part_stage` forment des **listes fermées** (13.4, PARTIE 24).
- R-1783 : le schéma JSON complet est publié dans `docs/schema/definition-1.json` et validé en CI contre toutes les definitions du dépôt.

## 23.5 Contenu de démonstration

- R-1790 : le mod embarque **exactement cinq** definitions de démonstration, désactivables (`content.examples`, défaut `false` en release) :
  1. `axion:example/crate` — caisse physique déformable (bois)
  2. `axion:example/buggy` — véhicule 4 roues minimal, carrosserie déformable, capot détachable
  3. `axion:example/flag` — drapeau (particules `VISUAL`)
  4. `axion:example/barrier` — barrière métallique déformable et cassable (structure)
  5. `axion:example/winch_rope` — treuil et corde (`SERVER_SIMPLE`, attache)

  Elles servent de tests vivants et de documentation exécutable, et couvrent l'ensemble des systèmes de la révision 2.

**Tests.** T-630..T-634, T-970 (les 5 exemples fonctionnent de bout en bout).

---

# PARTIE 24 : MODULARITÉ, NIVEAUX DE QUALITÉ ET CONFIGURATION

## 24.1 Modules

| Module | Obligatoire | Côté | Désactivable | Effet si désactivé |
|---|---|---|---|---|
| `core` (C-01..C-16) | ✔ | both | ✘ | — |
| `assets` (C-20..C-28) | ✔ | both | ✘ | — |
| `scene` (C-30) | ✔ | both | ✘ | — |
| `physics` (C-31, C-32, C-38, C-39, C-40) | ✔ | both | ✘ | — |
| `vehicles` (C-33) | ✘ | server | ✔ | definitions `kind: vehicle` refusées avec message |
| `joints` (C-34) | ✘ | server | ✔ | joints ignorés avec avertissement |
| `damage` (C-35, C-41) | ✘ | server | ✔ | aucun dégât appliqué ; les parts restent INTACT |
| `deformation` (C-42, C-45, C-68) | ✘ | both | ✔ | équivaut à `Q-0` : variantes de mesh seules |
| `structure` (C-43, C-44) | ✘ | server | ✔ | pas de rupture ni de détachement |
| `repair` (C-46) | ✘ | server | ✔ | API de réparation inopérante, message clair |
| `wear` (C-47, C-69) | ✘ | both | ✔ | pas d'usure ni de décalques |
| `particles` (C-36) | ✘ | both | ✔ | ensembles figés en pose de repos |
| `animation` (C-37) | ✘ | both | ✔ | poses de repos |
| `attachments` (C-48) | ✘ | server | ✔ | attaches refusées avec message |
| `render` (C-60..C-69, C-80..C-83) | ✔ (client) | client | ✘ | — |
| `blocks` (C-54) | ✘ | both | ✔ | blocs AXION non enregistrés |
| `items` (C-55) | ✘ | client | ✔ | modèle vanilla de secours |
| `integration_rfx` (C-76) | ✘ | both | ✔ | aucune intégration |

- R-1800 : un module désactivé n'est pas initialisé, ne crée aucun thread, n'alloue rien et n'apparaît pas dans les budgets.
- R-1801 : la désactivation n'empêche jamais le chargement du monde et **ne détruit aucune donnée persistée** : désactiver `deformation` conserve les champs en NBT, qui redeviennent actifs à la réactivation.
- R-1802 : la liste des modules actifs est affichée par `/axion status` et incluse au dump.

## 24.2 Niveaux de qualité

| Sous-système | Q-0 | Q-1 | Q-2 (défaut) | Q-3 | Q-4 |
|---|---|---|---|---|---|
| `deformation` | variantes de mesh | plastique ≤ 6³ | + élastique, ≤ 10³ | + résidus, ≤ 14³ | + masse-ressort, ≤ 16³, déchirure |
| `particles` | figé | 4 itérations, proxies | + collision monde | + auto-collision, déchirure | + sous-pas doublés |
| `shadows` | aucune | contact | carte 1024, PCF 3×3 | carte 2048, PCF 5×5 | + cascade unique affinée |
| `decals` | aucun | 16 par assembly | 32 | 64 | 64 + haute résolution |
| `occlusion` | off | off | 256×144 | 320×180 | 384×216 |
| `lighting` | vanilla-compat | PBR sans sonde | PBR + sonde SH | + préfiltrage spéculaire | + clearcoat/sheen/aniso |
| `skinning` | CPU plafonné | GPU 16 instances | 32 | 64 | 128 |
| `collider_refit` | variantes | seuil d'étape | seuil 4 cm | seuil 2 cm | seuil 1 cm |
| `reflections` | off | off | sonde | sonde | + SSR (EXPERIMENTAL) |
| `parallax` | off | off | off | off | activé |

- R-1810 : chaque niveau est **implémenté et testé**. Un niveau n'est jamais un placeholder.
- R-1811 : le gouverneur (C-77) choisit et ajuste ; l'utilisateur peut forcer par sous-système.
- R-1812 : les niveaux serveur (`deformation` refit, `particles`, `collider_refit`) n'affectent jamais la correction de la simulation (R-841).

## 24.3 Chargement à la demande

- R-1820 : le code client n'est jamais chargé sur serveur dédié (R-040), par séparation de packages et `DistExecutor`.
- R-1821 : assets chargés à la demande, déchargés après `assets.unload_delay_s`.
- R-1822 : les ressources GPU de déformation (pages, TBO) sont allouées à la première assembly déformée visible, jamais au démarrage.

## 24.4 Fichiers de configuration

```text
<configDir>/axion-common.toml     simulation, budgets, limites, dommage, déformation
<configDir>/axion-client.toml     rendu, qualité, debug, overlay
<configDir>/axion-server.toml     réseau, limites globales, autorisations
```

Contenu complet, défauts et plages : **ANNEXE A.3**.

- R-1830 : les options devant être cohérentes entre client et serveur sont imposées par le serveur au handshake ; la valeur locale du client est ignorée et un message le signale.

---
# PARTIE 25 : PERFORMANCE — THREADING, BUDGETS, DÉGRADATION

## 25.1 Position de principe

Aucun chiffre de performance n'est affirmé dans ce document. Les valeurs ci-dessous sont des **budgets de conception** que le système s'impose et surveille, pas des mesures. Les mesures sont produites par la PARTIE 30 et archivées.

- R-1840 : **la performance ne se résout jamais par la suppression d'une fonctionnalité.** Elle se résout par : LOD, culling, occlusion, sommeil, budgets, batching, SIMD, multithreading, GPU, cache, pooling, chargement à la demande, dirty tracking, **niveaux de qualité** et dégradation progressive. Toute proposition de suppression d'une fonctionnalité pour raison de performance est refusée en revue et remplacée par un niveau de qualité supplémentaire.

## 25.2 Budgets par défaut

| Budget | Clé | Défaut | Portée |
|---|---|---|---|
| Simulation physique par tick | `budgets.sim_ns_per_tick` | 3 000 000 | serveur |
| Chaîne de dommage par tick | `budgets.damage_ns_per_tick` | 1 000 000 | serveur |
| Déformation par tick | `budgets.deformation_ns_per_tick` | 1 500 000 | both |
| Particules par tick | `budgets.particles_ns_per_tick` | 1 000 000 | both |
| Préparation de rendu par frame | `budgets.render_prep_ns` | 2 000 000 | client |
| Occlusion logicielle | `budgets.occlusion_ns` | 800 000 | client |
| Assets par tick | `budgets.asset_ns_per_tick` | 1 000 000 | both |
| Hooks à vide | `budgets.idle_hook_ns` | 50 000 | both |
| Soumission FFI | `budgets.submit_ns` | 200 000 | both |
| Mémoire native | `budgets.native_mem_bytes` | 512 MiB | both |
| Mémoire de déformation | `budgets.deform_mem_bytes` | 128 MiB | both |
| Mémoire GPU | `budgets.gpu_mem_bytes` | 512 MiB | client |
| Bodies actifs | `budgets.max_active_bodies` | 2048 | serveur |
| Assemblies déformées | `budgets.max_deformed_assemblies` | 256 | both |
| Refits de collider par tick | `budgets.max_collider_refits_per_tick` | 8 | serveur |
| Instances visibles | `budgets.max_visible_instances` | 512 | client |
| Triangles par frame | `budgets.max_triangles_frame` | 3 000 000 | client |
| Décalques par frame | `budgets.max_decals_frame` | 512 | client |
| Instances d'ombre | `budgets.max_shadow_instances` | 128 | client |
| Débit réseau par joueur | `net.max_bytes_per_second_per_player` | 32 768 | serveur |

- R-1850 : **tout sous-système possède un budget déclaré, une métrique de consommation, une métrique de dépassement, un comportement en surcharge et un fallback** (INV-19). L'absence de l'un des cinq est un défaut bloquant, vérifié par T-007 qui compare le registre de budgets à l'inventaire des composants.

## 25.3 Techniques retenues

| Technique | Où | Statut |
|---|---|---|
| Multithreading (pool dédié) | simulation, dommage, déformation, particules, animation, culling, occlusion, assets | ✔ |
| Job system avec deadline et vol de travail | C-12 | ✔ |
| Parallélisation par îlot physique | C-31 | ✔ |
| Parallélisation par assembly | scene graph, déformation, animation, particules | ✔ |
| Structures SoA | scene graph, champs, culling, animation | ✔ |
| SIMD explicite | culling, gradients, nœuds de lattice, occlusion | ✔ |
| Dirty tracking | scene graph, champs (tranches), pages GPU | ✔ |
| Batching et instancing | C-65 | ✔ |
| Multi-draw indirect | C-65, si GL 4.3 | ✔ optionnel |
| Compute shaders | C-68, si GL 4.3 | ✔ optionnel |
| Réduction des allocations (arènes, pools) | partout | ✔ |
| Réduction des copies (mémoire partagée) | FFI | ✔ |
| Réduction des appels FFI (1 par phase) | INV-04 | ✔ |
| Séparation simulation / rendu (double tampon) | C-40 | ✔ |
| Sommeil agressif | bodies, particules, champs élastiques | ✔ |
| Cache de compilation d'assets et de shaders | C-25, C-63 | ✔ |
| LOD géométrique, de déformation, de particules, d'ombre | C-64, 14.7, 17.4, 19.8 | ✔ |
| Culling distance / frustum / section / occlusion | C-64, C-82 | ✔ |
| Chargement et allocation à la demande | assets, champs, pages GPU | ✔ |
| Niveaux de qualité gouvernés | C-77 | ✔ |
| Compilation JIT de comportements | — | ✘ hors périmètre |
| Physique GPU | — | ✘ hors périmètre |

## 25.4 Double tampon et interpolation

- R-1860 : la simulation écrit dans un tampon ; le rendu lit le précédent. Aucun verrou côté render thread. L'échange a lieu en `TICK_COLLECT`.
- R-1861 : côté client, deux états consécutifs sont conservés pour l'interpolation.

## 25.5 Détection de dépassement

```text
mesure par phase -> fenêtre glissante de 100 ticks (ou frames)
p50, p95, max     -> dépassement déclaré si p95 > budget sur 3 fenêtres
```

- R-1870 : la décision est prise sur le **p95**, jamais sur un pic isolé.

## 25.6 Machine de dégradation (SM-02)

```text
NORMAL -> DEGRADED_1 -> DEGRADED_2 -> DEGRADED_3 -> SAFE
remontée d'un palier à la fois, au plus un toutes les 10 s, après 30 s sous seuil
```

**Paliers, simulation serveur :**

```text
DEGRADED_1  velocity_iterations -1 (min 2) ; max_substeps -1 (min 1) ;
            seuil de refit de collider relevé ; fréquence d'usure divisée par 2
DEGRADED_2  rayon de simulation -25 % ; sommeil des bodies lointains ;
            niveau de déformation -1 pour les assemblies non visibles
DEGRADED_3  particules serveur réduites au minimum ; snapshot_rate -1 cran ;
            plafond de bodies actifs -50 % ; refits limités à 2 par tick
SAFE        seules les assemblies pilotées par un joueur sont simulées ;
            les autres sont figées (kinematic) et signalées ;
            la chaîne de dommage est mise en file et traitée à débit réduit
            (les impacts ne sont PAS perdus)
```

**Paliers, rendu client :** voir 19.12.

- R-1880 : chaque transition est journalisée avec sa cause (métrique, valeur, budget) et émise comme `DegradationEvent` ; l'overlay et `/axion status` l'affichent.
- R-1890 : la dégradation ne modifie jamais le résultat de la simulation d'une façon non observable : elle endort, elle diffère, elle réduit la finesse — elle ne supprime ni ne téléporte.
- R-1891 : en mode `SAFE`, les impacts en attente sont **conservés** et traités progressivement. Aucun dommage n'est perdu à cause d'une surcharge (T-848).
- R-1892 : le mode `SAFE` reste jouable.

## 25.7 Modes SAFE et DISABLED

```text
SAFE      runtime actif, simulation minimale, rendu minimal.
          Déclencheurs : dépassement persistant, panic récupérée, watchdog,
          H-06 invalidée.
DISABLED  runtime natif inutilisé. Assemblies inertes et préservées.
          Déclencheurs : natif absent, ABI incompatible, contexte POISONED,
          configuration.
```

## 25.8 Watchdog

- R-1900 : vérification toutes les 500 ms : jobs actifs au-delà de `watchdog.job_timeout_ms` (5000), mémoire native et de déformation sous budget, croissance anormale des panics, progression du thread autoritatif.
- R-1901 : le watchdog **ne tue jamais de thread** : il déclenche des dégradations, des annulations coopératives, puis `SAFE`, puis `DISABLED`.

## 25.9 Tests

```text
T-640  dégradation déclenchée par surcharge, paliers dans l'ordre
T-641  remontée avec hystérésis, sans oscillation
T-642  mode SAFE : jeu jouable, assemblies pilotées fonctionnelles
T-643  watchdog : job bloqué -> annulation, pas de deadlock
T-644  scaling mesuré 1..N workers (B-01, B-18)
T-645  aucune allocation dans la boucle chaude après échauffement
T-646  INV-04 : appels FFI par tick sous le seuil
T-007  registre de budgets complet : chaque composant a ses cinq éléments
T-847b impacts conservés en mode SAFE
```

---

# PARTIE 26 : COMPATIBILITÉ GÉNÉRALE

## 26.1 Principe

- R-1910 : AXION n'utilise **aucun** Mixin sur du code de mod tiers ; sa liste de cibles est fermée, minimale et documentée.
- R-1911 : AXION ne modifie ni ne remplace aucun système vanilla (rendu du monde, éclairage, physique d'entité, pathfinding, réseau vanilla, sauvegarde de chunk).
- R-1912 : en cas de conflit détecté, AXION **se retire** de la fonctionnalité concernée et le journalise.

## 26.2 Empreinte Mixin (liste fermée)

| Cible | Type | Motif | Échec toléré |
|---|---|---|---|
| `net.minecraft.client.renderer.LevelRenderer` | `@Inject` | état de visibilité des sections (culling C-64, occluders C-82) | ✔ culling de section désactivé |
| `net.minecraft.client.renderer.GameRenderer` | `@Inject` | détection d'un pipeline de shaders tiers | ✔ backend natif conservé, détection par `ModList` seule |
| `net.minecraft.world.entity.Entity` | `@Inject` sur `positionRider` / `getDismountLocationForPassenger` | occupants sur socket, sortie sûre | ✔ position d'occupant par tick classique |
| `net.minecraft.client.renderer.LightTexture` | `@Inject` (lecture seule) | accès à la texture de lightmap pour le PBR | ✔ facteur de lightmap approché par `getLightColor` |

- R-1920 : toutes les cibles sont du **code Minecraft**, jamais du code de mod (T-024).
- R-1921 : aucune injection n'annule le comportement vanilla ; toutes sont non bloquantes en cas d'échec (colonne « échec toléré »). Une injection Mixin obligatoire au démarrage est interdite.

## 26.3 Interactions avec le vanilla

| Système | Interaction | Garantie |
|---|---|---|
| Entités | `AxionEntity` normale ; entités vanilla en proxies cinématiques | aucun changement de comportement vanilla |
| Blocs | lecture des `VoxelShape` pour les tuiles | aucun bloc modifié, aucun chunk chargé |
| Chunks | suivi du chargement | jamais de chargement forcé |
| Dimensions | un monde physique par dimension | cycle de vie suivi |
| Capabilities | AXION n'attache rien à des objets tiers | additif |
| Événements Forge | abonnement seul, jamais d'annulation | R-400 |
| Dégâts vanilla | **observés** et convertis en impacts pour les seules `AxionEntity` | comportement vanilla inchangé pour les autres entités |
| Réseau | canal dédié | aucun paquet vanilla modifié |
| Sauvegarde | NBT d'entité et de BlockEntity + répertoire dédié | INV-10 |
| Pathfinding | non modifié | limite documentée (26.4) |
| Redstone, tick de bloc, météo | non touchés | — |

## 26.4 Limites documentées

- R-1930 : les mobs vanilla ne contournent pas les assemblies AXION (le pathfinding vanilla ignore les entités non bloquantes). Injecter des obstacles dans le pathfinding serait non additif et coûteux. **Atténuation fournie** : une definition peut déclarer `"pathfinding_blocker": true`, ce qui fait poser par AXION des `VoxelShape` temporaires **uniquement via l'API d'entité vanilla `canBeCollidedWith` et une entité de blocage dédiée**, sans toucher au pathfinding lui-même. Cette atténuation est `EXPERIMENTAL`, désactivée par défaut, et son coût est budgété.

## 26.5 Mods tiers

| Catégorie | Risque | Mesure |
|---|---|---|
| Shaders (Iris, Oculus) | prise du pipeline | bascule automatique `VANILLA_CONSUMER` |
| Optimisation de rendu (Sodium/Embeddium, Rubidium) | remplacement du renderer de terrain | AXION n'en dépend pas ; la visibilité de section utilise l'API publique quand elle existe, sinon culling de section et occluders de terrain désactivés, journalisé |
| Optimisation de tick | modification du tick d'entité | AXION n'en dépend pas (R-701) |
| Physique tierce | double simulation | aucune interaction : AXION ne touche pas aux entités des autres mods |
| Transformation de bytecode | conflit de classes | AXION n'instrumente aucun code tiers |
| Mods natifs | conflit de bibliothèque | nom unique, `System.load` absolu, symboles préfixés |
| Protection de zone | destruction de blocs | AXION ne casse aucun bloc par défaut ; si activé, passe par les événements Forge |

- R-1940 : la détection d'un mod tiers sert **uniquement** à choisir un chemin de compatibilité, jamais à modifier la logique métier.
- R-1941 : `/axion compat` affiche la liste des mods détectés et l'effet de cette détection.

## 26.6 Tests

```text
T-650  Forge + AXION seul (client, serveur)
T-651  + mod de shaders : bascule, rendu correct
T-652  + mod d'optimisation de rendu : pas d'erreur, culling dégradé
T-653  + 100 mods synthétiques : démarrage, aucune interférence
T-654  + mod natif tiers simulé : deux natifs chargés
T-655  + mod transformateur de bytecode simulé : démarrage normal
T-024  aucune cible Mixin hors du code Minecraft
T-656  échec simulé de chaque Mixin -> démarrage et dégradation propre
```

---

# PARTIE 27 : COMPATIBILITÉ RUSTFORGE-X

## 27.0 Position fondamentale

```text
AXION ENGINE est un projet INDÉPENDANT :
  dépôt, mod, build, installation, code, architecture, tests, releases propres,
  fonctionnel sans RUSTFORGE-X, sans dépendance obligatoire.

ET il est CONÇU POUR COEXISTER correctement avec RUSTFORGE-X installé
simultanément dans la même instance.

Configuration A : Minecraft + Forge + AXION                -> fonctionnement complet
Configuration B : Minecraft + Forge + RUSTFORGE-X + AXION  -> fonctionnement complet
```

- **INV-06** : aucun système d'AXION ne référence RUSTFORGE-X hors de C-76, et aucun ne le nécessite.
- R-1950 : relation **unidirectionnelle et optionnelle**. AXION peut détecter RUSTFORGE-X ; AXION ne suppose **jamais** que RUSTFORGE-X modifie ou remplace son fonctionnement interne. Aucune dépendance circulaire n'existe ni ne peut exister.

```text
                 ┌─────────────────┐
                 │    Minecraft    │
                 │      Forge      │
                 └────────┬────────┘
             ┌────────────┴────────────┐
      RUSTFORGE-X                AXION ENGINE
       indépendant                indépendant
   runtime / optimisation    3D / physique / déformation
             └──────────┬──────────────┘
                   Compatibles
```

## 27.1 Détection

```java
boolean present = ModList.get().isLoaded("rustforgex");
Optional<String> version = ModList.get().getModContainerById("rustforgex")
        .map(c -> c.getModInfo().getVersion().toString());
```

- R-1960 : détection **une seule fois**, à `FMLLoadCompleteEvent`, résultat immuable pour la session.
- R-1961 : la détection ne charge **aucune** classe de RUSTFORGE-X, n'utilise pas `Class.forName` et ne scanne pas ses fichiers.
- R-1962 : le résultat est affiché par `/axion compat` et inclus au dump.

## 27.2 Ce que la détection change (et ne change pas)

**Ne change jamais :** le comportement de la simulation, du dommage, de la déformation, du rendu, les formats d'asset, de réseau et de persistance, l'API publique, ni aucun résultat de jeu.

**Peut changer (mesures de coexistence, toutes désactivables) :**

| Mesure | Effet | Clé | Défaut |
|---|---|---|---|
| Partage de budget CPU | réduction du nombre de workers AXION | `integration.rustforgex.cpu_share` | `auto` |
| Journalisation enrichie | version de RUSTFORGE-X dans les dumps | — | toujours |
| Marquage de non-transformation | annotation des méthodes de frontière | `integration.rustforgex.hint_no_transform` | `true` |
| Bridge d'information | activation si une API compatible est présente | `integration.rustforgex.bridge` | `auto` |

- R-1970 : `integration.rustforgex.mode = off` désactive **toutes** ces mesures ; AXION se comporte alors exactement comme en configuration A (T-663).

## 27.3 Coexistence : analyse des points de contact

### 27.3.1 Classloading

| Risque | Analyse | Mesure |
|---|---|---|
| Collision de packages | `dev.rustforgex.**` vs `dev.axion.**` | impossible |
| Collision de ressources | `rustforgex.mixins.json` vs `axion.mixins.json` | noms distincts |
| Chargement forcé | AXION ne scanne ni ne force le chargement d'aucune classe tierce | — |
| Dépendance croisée | aucune : C-76 n'utilise que `ModList` | — |

- R-1980 : AXION **ne déclare pas** RUSTFORGE-X dans `mods.toml`, ni en dépendance obligatoire, ni en dépendance optionnelle versionnée. Une dépendance déclarée créerait un ordre de chargement et une contrainte de version inutiles.

### 27.3.2 Instrumentation et bytecode

| Risque | Analyse | Mesure |
|---|---|---|
| Deux transformateurs sur la même classe | RUSTFORGE-X instrumente potentiellement de nombreuses classes ; AXION en touche 4, toutes Minecraft | surface minimale documentée, échec toléré (R-1921) |
| Ordre de transformation | ModLauncher chaîne les transformateurs | AXION n'utilise que Mixin, jamais un `ILaunchPluginService` ni un `ITransformer` propre |
| Stabilité des empreintes de bytecode | les runtimes adaptatifs dépendent de la stabilité du bytecode | R-1990 |

- R-1990 : AXION **ne génère aucune classe à l'exécution** : pas de `ClassWriter` runtime, pas de proxy dynamique dans un chemin chaud, pas de `LambdaMetafactory` custom. Cela préserve la stabilité des empreintes dont dépendent les runtimes adaptatifs.

### 27.3.3 Threads et scheduler

| Risque | Analyse | Mesure |
|---|---|---|
| Sursouscription CPU | chaque mod a son pool | partage de budget (27.4) |
| Pool global partagé | `rayon` global, `ForkJoinPool.commonPool()` | AXION n'utilise **ni l'un ni l'autre** (R-470) |
| Priorités | conflit | workers NORM-1, watchdog MAX, assets MIN |
| Identification | RUSTFORGE-X observe les threads tiers | threads AXION nommés `axion-*`, daemon |
| Blocage du thread autoritatif | les deux ajoutent des hooks | budgets et deadlines des deux côtés |

- R-2000 : AXION n'utilise ni `ForkJoinPool.commonPool()`, ni le pool global de `rayon`, ni `CompletableFuture` sans exécuteur explicite dans un chemin chaud.

### 27.3.4 Tick et événements Forge

- R-2010 : AXION ne suppose **jamais** être le premier ou le dernier gestionnaire d'un événement. Ses hooks sont idempotents par tick et robustes à l'ordre (T-668). L'appariement `submit`/`collect` est auto-réparateur (R-282).

### 27.3.5 Mémoire native et bibliothèques

| Risque | Mesure |
|---|---|
| Collision de nom de bibliothèque | `axion_native` vs `rfx_native` |
| Collision de symboles | préfixe `axion_` exclusif |
| Chemin d'extraction partagé | `<gameDir>/axion/native/<hash>/` vs `<gameDir>/rustforgex/native/<hash>/` |
| `java.library.path` | `System.load` sur chemin absolu |
| Dépendances Rust dupliquées | liaison statique, aucun symbole non préfixé exporté |
| Budget mémoire natif | budgets séparés, total visible dans `/axion status` |

- R-2020 : le binaire natif est **lié statiquement** (hors libc système et CRT Windows) et n'exporte que les symboles `axion_*` (script de version sur Linux/macOS, `.def` sur Windows). Vérifié par T-661.

### 27.3.6 Rendu

- R-2030 : AXION respecte strictement la discipline d'état GL (19.3) et restaure FBO et état après chaque passe, y compris la passe d'ombre. Si un autre mod ajoute une préparation de rendu, elle n'entre pas en conflit tant qu'elle ne détourne pas le contexte GL. AXION ne réserve jamais le framebuffer principal ni ne modifie sa configuration.

### 27.3.7 Réseau, monde, sauvegarde

- R-2040 : canal `axion:main` distinct ; données hors du monde dans `<gameDir>/axion/` ; aucun couplage d'ordre de sauvegarde.

### 27.3.8 Cycle de vie et arrêt

- R-2050 : AXION libère toutes ses ressources natives à `ServerStoppedEvent` et à la fermeture du client, sans dépendre de l'ordre d'arrêt des autres mods.
- R-2051 : AXION n'installe aucun `Runtime.addShutdownHook` bloquant ni finaliseur.

## 27.4 Partage du budget CPU

```text
si RUSTFORGE-X détecté et integration.rustforgex.cpu_share == "auto" :
    workers_axion = clamp(floor((cores - 2) * 0.5), 1, config.max_workers)
sinon :
    workers_axion = clamp(cores - 2, 1, config.max_workers)
```

- R-2060 : heuristique **documentée**, pas une négociation. Journalisée, visible dans `/axion status`, surchargeable (`full | half | auto | <n>`).
- R-2061 : AXION ne suppose pas connaître le nombre de threads de RUSTFORGE-X et ne tente jamais de le modifier.
- R-2062 : la réduction du nombre de workers **ne réduit aucune fonctionnalité** : elle allonge le temps de calcul, ce que le gouverneur de qualité absorbe en ajustant les niveaux. Les fonctionnalités restent toutes disponibles.

## 27.5 Bridge d'interopérabilité optionnel (C-76)

```java
final class RfxBridge {
    private static final String[] CANDIDATE_ENTRY_POINTS = { "dev.rustforgex.api.RustForgeXApi" };

    static Optional<RfxBridge> tryCreate() {
        if (!ModList.get().isLoaded("rustforgex")) return Optional.empty();
        if (!config.integration.rustforgex.bridge) return Optional.empty();
        for (String cn : CANDIDATE_ENTRY_POINTS) {
            try {
                Class<?> c = Class.forName(cn, false, RfxBridge.class.getClassLoader());
                MethodHandle get = lookupOptional(c, "get");
                if (get == null) continue;
                return Optional.of(new RfxBridge(c, get));
            } catch (Throwable t) {
                LOG.debug("Bridge RUSTFORGE-X indisponible ({}) : {}", cn, t.toString());
            }
        }
        return Optional.empty();
    }
}
```

- R-2070 : bridge **entièrement réflexif** ; aucun type de RUSTFORGE-X référencé à la compilation, aucune dépendance Gradle, même `compileOnly`.
- R-2071 : toute exception, tout `NoClassDefFoundError`, toute signature inattendue est capturée, journalisée en `DEBUG`, et désactive le bridge pour la session, **sans aucun impact** sur le reste d'AXION.
- R-2072 : le bridge n'est **jamais** sur un chemin chaud : au plus un échange par seconde, plus les transitions d'état.
- R-2073 : le bridge ne délègue **aucune** fonctionnalité. Il échange des **informations** :
  1. AXION publie son état (workers, budgets, niveaux de qualité, mode de dégradation, mémoire native) ;
  2. AXION peut lire une information d'environnement (par exemple le nombre de threads utilisés par l'autre runtime) pour affiner son heuristique de partage CPU.
- R-2074 : si le bridge est absent ou inactif, AXION fonctionne **exactement** de la même manière, aux heuristiques de partage CPU près.
- R-2075 : **aucun test d'acceptation d'AXION ne dépend du bridge.** Le bridge a ses propres tests, tous exécutables sans RUSTFORGE-X (par simulation).

## 27.6 Indication de non-transformation

- R-2080 : AXION expose une annotation `@AxionNativeBoundary` (rétention `CLASS`, dans `axion-api`) sur les méthodes qui traversent la frontière FFI ou qui doivent s'exécuter sur le thread autoritatif sans réordonnancement.
- R-2081 : cette annotation est **purement informative**. AXION ne suppose pas qu'un autre mod la lit, la comprend ou la respecte. Son ignorance n'a aucune conséquence sur la correction d'AXION, qui repose sur ses propres invariants (INV-03, INV-04, R-282).
- R-2082 : `COMPATIBILITY.md` la décrit pour tout mod d'optimisation, sans désigner RUSTFORGE-X comme destinataire privilégié.

## 27.7 Comment RUSTFORGE-X pourrait optimiser AXION (sans qu'AXION en dépende)

Cette section est **informative** et ne crée aucune obligation ni aucune dépendance.

```text
- le code Java d'AXION dans les chemins chauds est un passe-plat vers le natif :
  il est court, sans allocation, sans réflexion, et annoté @AxionNativeBoundary.
  Un runtime adaptatif y trouvera peu à optimiser, ce qui est voulu : le travail
  lourd est déjà natif.
- les hooks d'AXION sont peu nombreux, bornés en temps et idempotents : un
  runtime qui les mesure obtiendra des profils stables.
- AXION ne génère aucune classe à l'exécution (R-1990) : les empreintes de
  bytecode restent stables, ce qui évite les invalidations en cascade.
- AXION expose ses budgets et ses niveaux de qualité par le bridge : un runtime
  qui libère du CPU peut le signaler, et AXION relèvera naturellement ses
  niveaux par son propre gouverneur, sans logique dédiée.
- réciproquement, si RUSTFORGE-X consomme davantage de CPU, les budgets d'AXION
  seront dépassés et son gouverneur baissera ses niveaux : la coexistence
  s'autorégule par les mesures, pas par une négociation.
```

## 27.8 Ce qu'AXION ne fera jamais

```text
- déclarer une dépendance (obligatoire ou optionnelle) dans mods.toml
- importer, compiler contre, ou embarquer du code de RUSTFORGE-X
- supposer une version, une API, un comportement ou une présence
- déléguer une fonctionnalité, un calcul ou une décision
- désactiver une de ses fonctionnalités parce que RUSTFORGE-X est présent
  (hors partage de budget CPU, qui n'enlève aucune fonctionnalité)
- modifier ses formats, ses protocoles ou son API selon sa présence
- charger une de ses classes
- l'empêcher de fonctionner
```

## 27.9 Fallback

```text
RUSTFORGE-X absent  -> AXION : fonctionnement normal, complet, sans dégradation
RUSTFORGE-X présent -> AXION : fonctionnement normal, complet
                              + heuristique de partage CPU (désactivable)
                              + bridge d'information optionnel (réflexif)
                              + journalisation enrichie
```

## 27.10 Stratégie de test de coexistence

### 27.10.1 Mod de simulation (obligatoire, toujours exécuté)

`axion-testmod-coexist`, écrit par le projet AXION, reproduit les comportements de coexistence d'un runtime adaptatif natif :

```text
- charge sa propre bibliothèque native (stub Rust, symboles cx_*)
- crée un pool de workers (cores/2), un thread d'analyse, un watchdog
- s'abonne à ServerTickEvent en HIGHEST (PRE) et LOWEST (POST)
- installe un ILaunchPluginService instrumentant des classes Minecraft,
  y compris celles ciblées par les Mixins d'AXION
- alloue et libère de la mémoire native en continu
- expose une classe dev.rustforgex.api.RustForgeXApi factice, activable,
  pour exercer le chemin du bridge (présence, absence, exceptions)
- peut simuler : hook lent, exception dans un hook, absence de POST,
  consommation CPU variable
```

- R-2090 : ce mod est **obligatoire** dans la matrice de CI et rend le scénario de coexistence testable sans dépendre d'un tiers. Il n'est **jamais** distribué.

### 27.10.2 Test avec l'artefact réel (obligatoire quand disponible)

- R-2100 : la CI cherche `rustforgex-<version>.jar` (variable `RUSTFORGEX_JAR` ou dépôt d'artefacts). Présent → matrice complète. Absent → job `SKIPPED_UNAVAILABLE`, et `COMPATIBILITY.md` de la release indique « coexistence avec RUSTFORGE-X : vérifiée par simulation, non vérifiée avec l'artefact réel pour cette version ». Il est **interdit** de déclarer la coexistence vérifiée sans exécution réelle.

### 27.10.3 Matrice

| ID | Configuration | Vérifications |
|---|---|---|
| T-660 | Forge + AXION | tous les tests de base |
| T-661 | Forge + AXION | table de symboles : seuls `axion_*` |
| T-662 | Forge + testmod seul | le simulateur fonctionne |
| T-663 | + AXION, `mode = off` | aucune différence de comportement mesurable |
| T-664 | + AXION, `mode = auto` | heuristique CPU appliquée et journalisée ; **toutes les fonctionnalités restent disponibles** |
| T-665 | API factice activée | bridge actif, échange de métriques, aucune régression |
| T-666 | API factice absente | bridge inactif, log DEBUG, aucune régression |
| T-667 | API factice levant des exceptions | bridge désactivé proprement |
| T-668 | priorités d'événements inversées | aucun effet |
| T-669 | hook tiers lent (50 ms) | dégradation propre, sans blâmer ni bloquer |
| T-670 | hook tiers absent au POST | appariement implicite, état sain |
| T-671 | instrumentation tierce sur les classes Mixin d'AXION | démarrage, Mixins appliqués ou fonctionnalité désactivée proprement |
| T-676 | testmod consommant 50 % du CPU | gouverneur baisse les niveaux, jeu jouable, déformation toujours présente à Q-1 |
| T-672 | + RUSTFORGE-X réel (si disponible) | 30 min de jeu, sauvegarde, rechargement, arrêt propre |
| T-673 | idem, serveur dédié + 4 clients | 30 min, aucune désynchronisation |
| T-674 | retrait d'AXION après T-672 | monde chargeable, RUSTFORGE-X fonctionne |
| T-675 | retrait de RUSTFORGE-X après T-672 | monde chargeable, AXION identique |

**Acceptance.** T-660 à T-671 et T-676 passent inconditionnellement. T-672 à T-675 passent si l'artefact est disponible, sinon sont rapportés `SKIPPED_UNAVAILABLE`, jamais déclarés réussis.

---

# PARTIE 28 : SÉCURITÉ, STABILITÉ, CONFINEMENT

## 28.1 Surface d'attaque

```text
- assets (resource packs, y compris server resource pack)
- definitions, matériaux, profils, règles (datapacks)
- paquets réseau reçus, dont les lots d'ImpactDesc et les instantanés de champ
- NBT d'entités et de BlockEntity (mondes partagés)
- entrées de contrôle des joueurs
- fichiers de cache sur disque
```

- R-2110 : toute donnée de ces sources est traitée comme hostile : validée, bornée, incapable de provoquer une allocation non bornée, une boucle infinie, un accès hors limites ou une exécution de code.

## 28.2 Code `unsafe` en Rust

- R-2120 : `unsafe` confiné à `ax-ffi` (frontière JNI) et `ax-mem` (arènes et pool de pages). Tout autre crate déclare `#![forbid(unsafe_code)]`.
- R-2121 : chaque bloc `unsafe` porte un commentaire `// SAFETY:` ; `clippy::undocumented_unsafe_blocks` en `deny`.
- R-2122 : les crates contenant du `unsafe` sont couverts par Miri en CI sur leurs tests unitaires.

## 28.3 Limites dures

| Limite | Clé | Défaut |
|---|---|---|
| Taille de source d'asset | `assets.max_source_bytes` | 128 MiB |
| Taille d'asset compilé | `assets.max_compiled_bytes` | 256 MiB |
| Données de déformation par asset | `assets.max_deform_bytes` | 4 MiB |
| Vertices / indices par asset | — | 2e6 / 6e6 |
| Nodes / bones / matériaux / parts / régions / liaisons | — | 4096 / 128 / 256 / 64 / 32 / 256 |
| Nœuds de lattice par région | — | 4096 (16³) |
| Champ par assembly | `deformation.max_field_bytes_per_assembly` | 256 KiB |
| Résidus par assembly | `deformation.max_residual_vertices` | 4096 |
| Impacts par tick | `damage.max_impacts_per_tick` | 512 |
| Énergie d'un impact | `damage.max_impact_energy` | 5 MJ |
| Détachements par tick | `damage.max_detach_per_tick` | 4 |
| Débris par dimension | `damage.max_debris` | 96 |
| Génération de débris | `damage.max_debris_generation` | 2 |
| Particules par ensemble | — | 4096 |
| Assemblies par dimension | `limits.max_assemblies_per_dimension` | 4096 |
| Paquets C→S par seconde | `net.max_packets_per_second` | 40 |
| Taille d'un paquet | — | 32 KiB |
| Persistance par assembly | `persistence.max_bytes_per_assembly` | 64 KiB |
| Blob personnalisé | `persistence.max_custom_bytes` | 16 KiB |
| Durée de compilation d'un asset | `assets.max_compile_ms` | 30 000 |

- R-2130 : chaque limite est vérifiée **avant** l'allocation correspondante.
- R-2131 : le franchissement produit une erreur nommée, jamais une dégradation silencieuse.

## 28.4 Confinement des pannes

```text
Asset défaillant        -> cet asset marqué FAILED, fallback visuel
Definition défaillante  -> refusée individuellement
Assembly défaillante    -> figée (kinematic) et signalée
Champ de déformation
  incohérent            -> région remise à zéro, instantané demandé au serveur
Panic Rust récupérée    -> compteur, log ; contexte incohérent -> POISONED -> DISABLED
Erreur GL               -> passe abandonnée ; 3 frames en erreur -> backend vanilla
Extension tierce        -> isolée, désactivée après 5 échecs
Dépassement de budget   -> baisse de niveau puis dégradation
```

- R-2140 : aucun de ces chemins ne provoque l'arrêt du serveur, la fermeture du client, la perte du monde ou la corruption d'une sauvegarde.

## 28.5 Vie privée

- R-2150 : aucune donnée personnelle collectée, stockée ou transmise ; aucune connexion sortante ; contenu du dump documenté exhaustivement.

## 28.6 Reproduction d'incident

- R-2160 : si `debug.record_incidents = true` (défaut faux), AXION enregistre la séquence d'entrées de simulation et d'impacts permettant de rejouer un scénario hors ligne via `axion-cli replay`. Aucune donnée de monde ni de joueur.

## 28.7 Tests

```text
T-680  fuzzing des importeurs : 10 M d'exécutions, aucun crash
T-681  fuzzing du lecteur A3D : idem
T-682  fuzzing du décodeur de paquets, incluant ImpactDesc et instantanés : idem
T-683  asset annonçant des tailles absurdes : refus sans allocation
T-684  definition récursive ou cyclique : refus
T-685  NBT malformé, champ de déformation corrompu : entité inerte, pas de crash
T-686  cache altéré : rejeté et recompilé
T-687  100 000 paquets/s d'un client : rate limiting, serveur stable
T-688  compilation dépassant le temps limite : annulée proprement
T-689  aucun accès disque hors des répertoires autorisés
T-690  lot d'impacts hostile (énergie 1e30, indices invalides) : borné, ignoré
T-691  instantané de champ hostile (taille annoncée énorme) : refus sans allocation
```

---
# PARTIE 29 : STRATÉGIE DE TEST

## 29.1 Principes

- R-2170 : un test qui échoue n'est **jamais** désactivé pour faire passer la CI.
- R-2171 : un test instable est traité comme un défaut réel.
- R-2172 : tout composant `STABLE` a ses tests, ses métriques, son fallback et sa documentation.
- R-2173 : tout défaut corrigé donne lieu à un test de non-régression portant son identifiant.
- R-2174 : **toute fonctionnalité `EXPERIMENTAL` est testée comme une fonctionnalité `STABLE`**, avec des tolérances plus larges ; elle n'est simplement pas activée par défaut.

## 29.2 Pyramide

```text
                    /\
                   /  \      Compatibilité & coexistence     ~40 tests
                  /----\
                 /      \    Stress & endurance              ~30 tests
                /--------\
               /          \  Gameplay & bout en bout        ~140 tests
              /------------\
             /              \ Intégration (Java<->Rust,
            /                \ Forge, client/serveur, réseau) ~170 tests
           /------------------\
          /                    \ Unitaires (Rust + Java)      ~900 tests
         /______________________\
```

Objectifs de couverture fonctionnelle, pas quotas de remplissage.

## 29.3 Tests unitaires

**Rust :**

```text
ax-math      quaternions, rebasage, conversions, propriétés (proptest)
ax-model     encodage/décodage repr(C), tailles et alignements figés, parité Java
ax-mem       arènes, pool de pages de déformation, bilans, dépassements
ax-jobs      ordonnancement, annulation, deadlines, absence de pool global
ax-core      handles, générations, contexte
ax-det       NOYAU DÉTERMINISTE : vecteurs d'or, absence de transcendantes,
             stabilité inter-plateformes, quantification
ax-asset     importeurs, validateur (une erreur par règle), optimizer,
             compilateur de déformation et de structure, lecteur/écrivain A3D
ax-scene     propagation, dirty tracking, ordre topologique, états de node
ax-physics   intégration analytique, contacts, joints, sommeil, requêtes,
             tuiles monde, flottabilité
ax-impact    masse effective, énergie, aire, agrégation, tri déterministe
ax-damage    répartition, propagation, étapes, hystérésis, actions déclaratives
ax-deform    noyau d'application, élastique/plastique, saturation, ancrages,
             gradient, quantification, LOD, sérialisation, réparation
ax-struct    graphe, propagation, rupture, composante connexe, détachement
ax-refit     déformation de points d'enveloppe, re-hull, bornes, budget
ax-vehicle   suspension, pneus, transmission, différentiels, couplage dommage
ax-anim      échantillonnage glTF, blending, palettes, sources procédurales
ax-cloth     convergence XPBD, contraintes, ancrages, déchirure, modes d'autorité
ax-render-prep culling, occlusion logicielle, LOD, tri, palettes, pages
ax-net       quantification, delta, ImpactDesc compact, empreintes, bornes,
             décodage hostile
ax-wear      canaux, seuils, décroissance, décalques
```

**Java :** configuration, NBT, encodage de paquets, ressources, registries, cycle de vie des handles, API publique (contrats, threads, erreurs, validation de `ImpactSpec`), règles ArchUnit (isolation Forge, client/serveur, absence de cycle, absence de nom de mod, absence de `commonPool`).

- R-2180 : les tailles et alignements `repr(C)` sont vérifiés par assertions statiques en Rust **et** par un test Java lisant les offsets générés (T-005).

## 29.4 Tests d'intégration

```text
Java <-> Rust    chaque contrat IF-01..IF-07, données réelles et hostiles,
                 comptage des traversées FFI
Bootstrap        nominal, natif absent, ABI incompatible, disque non inscriptible
Assets           compilation, cache, reload, fallback, déformation compilée
Forge            client, serveur dédié, cycle de vie complet, échecs de Mixin
Client/serveur   connexion, spawn, mouvement, impact, déformation, détachement,
                 réparation, déconnexion, reconnexion
Networking       perte, latence, désordre, corruption, rate limiting,
                 reconstruction de déformation, divergence et resynchronisation
Persistance      sauvegarde, rechargement, migration, retrait du mod
Rendu            backends, bascule, ressources GPU, shaders, ombres, occlusion
```

## 29.5 Tests de gameplay et scénarios déclaratifs

GameTest de Minecraft 1.20.1 + scénarios JSON :

```json
tests/gameplay/damage/frontal_crash.json
{
  "structure": "axion:test/flat_32",
  "setup":  [ { "spawn": "axion:example/buggy", "at": [16,4,8], "rot": [0,0,0,1] },
              { "fill": { "from": [12,4,20], "to": [20,8,20], "block": "minecraft:stone" } } ],
  "inputs": [ { "tick": 20, "assembly": 0, "throttle": 1.0 } ],
  "asserts":[ { "tick": 120, "assembly": 0, "part": "bumper_front",
                "deform_max": { "min": 0.05 } },
              { "tick": 120, "assembly": 0, "part": "hood",
                "stage": { "in": ["DAMAGED","HEAVY","DESTROYED"] } },
              { "tick": 200, "assembly": 0, "structure": { "hood_to_body":
                { "integrity_max": 0.5 } } },
              { "tick": 200, "debris_count": { "min": 0 } } ],
  "tolerance": { "position": 0.5, "deform": 0.02 }
}
```

Domaines couverts : physique, empilements, véhicules, joints, **impacts**, **déformation**, **structure et détachement**, **réparation**, **usure et décalques**, sièges, interactions, sockets, attaches, animations, particules, blocs, items, persistance, réseau.

- R-2190 : chaque scénario est reproductible sur une même machine ; sa tolérance est déclarée et jamais élargie pour faire passer un test.

## 29.6 Test de bout en bout obligatoire (T-970)

```text
BLENDER
  scène de référence tests/fixtures/blender/reference_vehicle.blend
  (carrosserie en panneaux, portes sur charnières, capot, vitres BRITTLE,
   compartiment moteur INTERNAL, roues, sièges, sockets, vertex groups de
   déformation, liaisons structurelles annotées)
   ↓ export GLB par l'addon
IMPORT               C-21, sans erreur
VALIDATION           C-22, zéro erreur, avertissements listés et attendus
COMPILATION A3D      C-23 + C-28 : régions, poids, ancrages, graphe structurel
DEFINITION JSON      tests/fixtures/definitions/reference_vehicle.json
CHARGEMENT MINECRAFT serveur dédié + client
SPAWN                /axion spawn
PHYSIQUE             le véhicule repose, roule, tourne, freine
COLLISION            impact frontal contre un mur de blocs à 12 m/s
IMPACT               C-41 produit des impacts cohérents en énergie
DÉFORMATION          l'avant s'enfonce visiblement et progressivement
DÉGÂTS STRUCTURELS   intégrités décroissantes, capot en HEAVY
DÉTACHEMENT          le capot se détache et devient un débris physique
RÉVÉLATION           le compartiment moteur devient visible
COLLIDER             la collision reflète l'avant enfoncé (écart <= seuil)
RENDU                déformation, décalques et usure visibles dans les deux backends
RÉSEAU               un second client voit exactement la même déformation
SAUVEGARDE           arrêt du serveur
RECHARGEMENT         état identique bit à bit (champ, structure, usure)
RÉPARATION           /axion repair --level full restaure progressivement
VÉRIFICATION FINALE  aucun code Java spécifique au véhicule n'a été écrit
```

- R-2200 : ce scénario est **obligatoire** et exécuté en CI à chaque build de `main`. Son échec bloque la fusion.
- R-2201 : une variante `T-971` exécute le même scénario avec `deformation` désactivé (`Q-0`) et vérifie que le fallback par variantes de mesh produit un résultat cohérent, persisté et synchronisé.

## 29.7 Tests de stress et d'endurance

```text
S-01  2000 corps rigides, 10 min
S-02  200 véhicules pilotés par script, 10 min
S-03  assets à 2e6 vertices, 50 assemblies visibles
S-04  10 000 collisions/s soutenues
S-05  500 joints en chaînes et grilles
S-06  compilation de 500 assets à froid
S-07  spawn/despawn de 100 000 assemblies (fuites de handles, mémoire, GPU)
S-08  20 joueurs simulés, 1 h, serveur dédié
S-09  100 changements de resource pack
S-10  chargement/déchargement de chunks en continu, 1 h
S-11  alternance de dimensions, 30 min
S-12  dégradation forcée puis relâchement, 100 cycles
S-13  1000 assemblies déformables recevant 10 impacts/s chacune, 10 min
S-14  500 détachements et 5000 débris sur 10 min, avec éviction
S-15  déformation + réparation en boucle sur 1000 objets, 30 min
S-16  1000 ensembles de particules VISUAL, 10 min
S-17  saturation du budget mémoire de déformation, éviction et récupération
```

- R-2210 : tout test d'endurance mesure la mémoire (JVM, native, déformation, GPU) au début et à la fin ; une croissance monotone non expliquée est un échec.

## 29.8 Infrastructure

```text
./gradlew test | integrationTest | gameTest | stressTest
cargo nextest run --workspace
fixtures : tests/fixtures/{models,a3d,definitions,packets,worlds,renders,blender,det}
corpus de fuzzing versionné : fuzz/corpus/<cible>/
graines fixes pour tout PRNG de test
```

- R-2220 : aucun test ne dépend du réseau, d'un service externe, de l'horloge murale, ni de l'ordre d'exécution des autres tests.

---

# PARTIE 30 : BENCHMARKS

## 30.1 Position de principe

- R-2230 : **aucun chiffre de performance publié sans fichier de résultats généré** par le harnais. Documentation, README, notes de version et sorties de `/axion` ne citent que des valeurs issues de `benchmarks/results/`.
- R-2231 : tout résultat archivé porte date, commit, plateforme (OS, CPU, GPU, pilote, JVM), configuration, niveaux de qualité, version du harnais, et données brutes par itération.

## 30.2 Ce qui est mesuré

| ID | Benchmark | Mesure |
|---|---|---|
| B-01 | Simulation N corps | temps de pas vs N et workers |
| B-02 | Scene graph | propagation vs nombre de nodes |
| B-03 | Requêtes spatiales | raycasts/s, sweeps/s |
| B-04 | Véhicules | temps par véhicule, scaling |
| B-05 | Animation | échantillonnage par assembly et par bone |
| B-06 | Particules | temps par particule et par itération, par mode d'autorité |
| B-07 | FFI | aller-retour vide, coût par élément en lot |
| B-08 | Télémétrie | surcoût de l'instrumentation |
| B-09 | Compilation d'asset | par format, taille, options |
| B-10 | Chargement A3D | temps et mémoire par masque de sections |
| B-11 | Préparation de rendu | culling + LOD + tri vs instances |
| B-12 | Rendu GPU | draw calls, triangles, temps GPU (timer queries) |
| B-13 | Réseau | octets/s par assembly et par joueur, coût CPU d'encodage |
| B-14 | Collision monde | construction de tuile, tuiles/s |
| B-15 | Mémoire | empreinte par assembly et par asset |
| B-16 | Tick complet | coût AXION dans le tick, à vide et en charge |
| B-17 | Frame complète | coût AXION dans la frame, à vide et en charge |
| **B-18** | **Solveur d'impacts** | impacts/s, coût par impact, scaling |
| **B-19** | **Déformation** | coût par impact et par nœud, par niveau de qualité, scaling |
| **B-20** | **Mémoire de déformation** | octets par assembly déformée, par région, après compression |
| **B-21** | **Refit de collider** | coût par refit vs nombre de points, re-hull |
| **B-22** | **Propagation structurelle et détachement** | coût par détachement, par taille de composante |
| **B-23** | **Réseau de déformation** | octets par impact, par instantané, trafic vs nombre d'impacts |
| **B-24** | **Persistance de déformation** | octets écrits, temps de sérialisation et de restauration |
| **B-25** | **Déformation GPU** | coût par sommet déformé, chemins UBO/TBO/compute |
| **B-26** | **Décalques et usure** | coût de la passe, par décalque |
| **B-27** | **Ombres AXION** | coût de la passe, par résolution et par instance |
| **B-28** | **Occlusion logicielle** | coût de rastérisation, taux de rejet |
| **B-29** | **Sonde d'environnement** | coût de reconstruction et de préfiltrage |
| **B-30** | **Scénario de bout en bout** | coût total d'un crash complet (impact → réseau → rendu) |

## 30.3 Méthodologie normative

```text
1. échauffement 30 s non mesuré
2. au moins 5 exécutions indépendantes de 60 s
3. rapporter p50, p95, p99, min, max, écart-type ; jamais la moyenne seule
4. configuration figée et journalisée (fréquence CPU, alimentation, VSync off,
   niveaux de qualité explicites)
5. comparaisons uniquement au sein d'une même session de mesure
6. toute comparaison "avec / sans" utilise la même scène, la même graine,
   le même matériel
```

- R-2240 : `criterion` pour Rust, `JMH` pour les micro-benchmarks Java, harnais C-72 en jeu (monde déterministe, entrées scriptées, sortie JSON).
- R-2241 : B-01..B-10, B-13..B-16, B-18..B-24 et B-30 sont exécutables en serveur dédié headless.
- R-2242 : les benchmarks GPU utilisent `GL_TIME_ELAPSED` et séparent le temps CPU de préparation du temps GPU.
- R-2243 : chaque benchmark de déformation est exécuté **à chaque niveau de qualité** et le résultat archivé par niveau, ce qui documente le coût réel de chaque graduation.

## 30.4 Format de résultat

```json
{ "schema": 2, "benchmark": "B-19", "commit": "…", "date": "…",
  "platform": { "os":"…","cpu":"…","cores":8,"gpu":"…","driver":"…","jvm":"…" },
  "config": { "workers":4, "fixed_dt":0.0166667, "quality": { "deformation": 3 } },
  "parameters": { "assemblies":100, "impacts_per_second":10, "lattice":"10x8x10" },
  "runs": [ { "samples_ns": [ … ] } ],
  "stats": { "p50_ns":0,"p95_ns":0,"p99_ns":0,"min_ns":0,"max_ns":0,"stddev_ns":0 } }
```

## 30.5 Non-régression

- R-2250 : la CI exécute un sous-ensemble rapide (B-01, B-02, B-07, B-09, B-11, **B-18, B-19, B-21, B-23**) et compare au dernier résultat archivé de la même plateforme.
- R-2251 : une régression supérieure à 20 % sur le p95 **bloque la fusion** jusqu'à explication documentée.
- R-2252 : les résultats de CI servent à détecter les régressions grossières ; les chiffres publiés proviennent de mesures sur matériel de référence documenté.

---

# PARTIE 31 : PROFILING ET DEBUGGING

## 31.1 Domaines profilés

```text
CPU Java    hooks, encodage réseau, persistance, extensions tierces
CPU natif   par phase de simulation, par étape de la chaîne de dommage,
            par job, par assembly, par région de déformation
GPU         par passe, par matériau, passe d'ombre, passe de décalques
Mémoire     JVM, native par catégorie, arène de déformation, GPU par arène
FFI         appels, octets, temps
Assets      par étape du pipeline, y compris compilation de déformation
Réseau      octets par type de paquet, dont impacts et instantanés
```

## 31.2 Outils

- R-2260 : `/axion metrics export` produit un JSON complet.
- R-2261 : `-Daxion.profile=chrome` produit une trace Chrome Trace Event ouvrable dans Perfetto, incluant les spans natifs (phase → job → sous-tâche → région).
- R-2262 : les spans natifs sont désactivés par défaut, avec un surcoût mesuré (B-08).
- R-2263 : AXION est compatible avec async-profiler et JFR ; aucun agent propriétaire ; symboles natifs en debug info dans les builds de développement.

## 31.3 Mode développeur

`-Daxion.dev=true` : assertions (threads, invariants, état GL, bornes de champ), sommes de contrôle des tampons FFI, validation renforcée au chargement, overlays disponibles, traces détaillées, cache d'assets désactivable, **vérification systématique de l'empreinte de champ client/serveur à chaque tick**.

- R-2270 : le mode développeur est **totalement inerte** en production (T-702).

## 31.4 Overlays de debug

```text
colliders, aabb, bodies, contacts, normals, joints, limits, raycasts, wheels,
suspension, bones, nodes, pivots, sockets, damage_zones, subzones,
impacts (points et vecteurs d'énergie), deform_lattice (nœuds, déplacements,
ancrages), deform_heat (carte de déformation par couleur), strain,
structure (liaisons colorées par intégrité), parts, wear, decals, lod, culling,
occluders, occlusion_buffer, shadow_map, probe, world_tiles, center_of_mass,
network (interpolé vs autoritatif, divergence de champ), budgets, quality
```

- R-2280 : chaque overlay est activable indépendamment ; éteint, il ne produit aucune donnée, ne remplit aucun tampon et n'émet aucun draw call. Le coût résiduel est un test de drapeau par frame, mesuré (T-551).

## 31.5 Reproduction

`axion-cli replay <incident.json>` rejoue une séquence d'entrées et d'impacts hors du jeu, avec le même code natif. `axion-cli deform` applique une séquence d'impacts sur un `.a3d` et exporte le résultat en OBJ pour inspection.

**Tests.** T-700..T-703, T-984 (empreinte vérifiée en mode dev).

---

# PARTIE 32 : DÉPENDANCES ET LICENCES

## 32.1 Licence du projet

**Apache-2.0.** Compatibilité avec les dépendances retenues, clause de brevet explicite, licence usuelle de l'écosystème.

- R-2290 : un fichier `NOTICE` généré et vérifié en CI liste dépendances, licences et attributions.
- R-2291 : AXION ne redistribue ni Minecraft, ni Forge, ni leurs assets, ni aucun mod tiers, ni aucun asset dont la licence ne le permet pas.
- R-2292 : les assets d'exemple sont créés par le projet, publiés sous CC0, avec leurs sources Blender dans `tools/examples/`.

## 32.2 Dépendances Rust

| Crate | Rôle | Licence | Maturité | Risque | Alternative |
|---|---|---|---|---|---|
| `rapier3d` | physique (bodies, joints, CCD, sleeping) | Apache-2.0 | mature | moyen (structurant) | implémentation propre (coût majeur), `physx-rs` (build C++) |
| `parry3d` | collision, requêtes, décomposition convexe, **QuickHull pour le refit** | Apache-2.0 | mature | faible | inclus avec rapier |
| `nalgebra` | algèbre requise par rapier | Apache-2.0 | mature | faible | non substituable sans changer rapier |
| `glam` | maths du moteur (SIMD) | MIT OR Apache-2.0 | mature | faible | `nalgebra` seul |
| `rayon` | vol de travail | MIT OR Apache-2.0 | mature | faible | pool propre |
| `gltf` | parsing glTF/GLB | MIT OR Apache-2.0 | mature | faible | parseur propre |
| `tobj` | parsing OBJ/MTL | MIT | mature | faible | parseur propre |
| `stl_io` | parsing STL | MIT | stable | très faible | parseur propre |
| `zstd` | compression (sections A3D, instantanés, persistance) | BSD-3-Clause | mature | faible | `lz4_flex` (MIT) |
| `crc32c` | intégrité | Apache-2.0 | stable | très faible | propre |
| `xxhash-rust` | empreintes de champ (`digest64`) | BSD-2-Clause | stable | très faible | propre |
| `serde` + `ciborium` | CBOR (config, blobs) | MIT OR Apache-2.0 | mature | faible | encodage propre |
| `meshopt` | optimisation de cache de sommets | MIT | mature | faible | Forsyth propre |
| `mikktspace` | tangentes | Zlib | référence | très faible | propre |
| `half` | `f16` (champs élastiques, quantification réseau) | MIT OR Apache-2.0 | stable | très faible | propre |
| `log` + `tracing` | journalisation et spans | MIT / dual | mature | très faible | — |
| `thiserror`, `bitflags` | erreurs, drapeaux | dual | mature | très faible | — |
| `jni` | bindings JNI | MIT OR Apache-2.0 | mature | faible | `extern "C"` brut |

**Non adoptés et motifs :**

| Écarté | Motif |
|---|---|
| `bevy_ecs`, `hecs`, `legion` | besoins couverts par des SoA et des tables de handles ; une dépendance structurante en moins (ADR-013) |
| `wgpu` | le rendu passe par le contexte GL de Minecraft (ADR-006) |
| `physx-rs`, bindings Bullet/Jolt | build C++, cross-compilation fragile, distribution lourde |
| `assimp` | dépendance C++ massive pour des formats hors périmètre |
| bibliothèque FEM ou de fracture tierce | aucune ne cible le temps réel contraint ni le déterminisme requis ; l'architecture retenue (14.1) est plus adaptée et sans dépendance |
| `serde_json` en runtime jeu | le JSON est parsé côté Java par Gson, déjà présent |

- R-2300 : toute nouvelle dépendance exige justification, licence compatible, maturité, alternative évaluée, ajout au SBOM et au `NOTICE`, et un ADR si structurante.
- R-2301 : `cargo-deny` en CI **bloque** : licence non allowlistée, avis RustSec, doublon non justifié, source hors `crates.io`.
- R-2302 : `Cargo.lock` versionné ; mises à jour délibérées.

## 32.3 Dépendances Java

| Dépendance | Rôle | Licence | Note |
|---|---|---|---|
| Minecraft + Forge 47.x | plateforme | non redistribuables | fournis par l'utilisateur |
| Mixin (via Forge) | injections minimales | MIT | déjà présent |
| Gson (via Minecraft) | JSON | Apache-2.0 | déjà présent |
| SLF4J / Log4j (via Minecraft) | journalisation | Apache-2.0 / MIT | déjà présents |
| JUnit 5, AssertJ, ArchUnit, JMH | tests et benchmarks | EPL-2.0 / Apache-2.0 | tests seulement |

- R-2310 : AXION n'embarque (shade) **aucune** bibliothèque Java tierce.

## 32.4 Outillage et SBOM

```text
Gradle 8.x, ForgeGradle 6.x, JDK 17 (Temurin)
Rust stable épinglé (rust-toolchain.toml), cargo, cross (optionnel)
Python 3.10+ et Blender 3.6+ pour l'addon (outil)
cargo-deny, cargo-fuzz, cargo-nextest, cargo-miri (CI)
```

- R-2320 : chaque release publie un SBOM CycloneDX couvrant Java et Rust, généré automatiquement.

---

# PARTIE 33 : ARBORESCENCE DU DÉPÔT

```text
axion-engine/
├── settings.gradle, build.gradle, gradle.properties, gradle/
├── Cargo.toml, Cargo.lock, rust-toolchain.toml, deny.toml
├── LICENSE (Apache-2.0), NOTICE, CHANGELOG.md, README.md
├── ARCHITECTURE.md, BUILDING.md, INSTALLATION.md, CONFIGURATION.md
├── COMPATIBILITY.md, ASSETS.md, PHYSICS.md, DAMAGE.md, DEFORMATION.md,
│   RENDERING.md, NETWORKING.md, API.md, BENCHMARKS.md, TROUBLESHOOTING.md,
│   RELEASING.md, SECURITY.md, CONTRIBUTING.md
├── docs/
│   ├── AGENT.md                 contrat de l'agent de développement
│   ├── spec/                    copie versionnée de ce cahier des charges
│   ├── decisions/               ADR-001.md ... ADR-026.md
│   ├── schema/                  definition-1.json, a3d-1.md, packets-1.md,
│   │                            deform-field-1.md, persistence-2.md
│   └── diagrams/
├── crates/
│   ├── ax-core/                 C-10
│   ├── ax-math/                 C-11
│   ├── ax-det/                  C-16  NOYAU DÉTERMINISTE (+ vecteurs d'or)
│   ├── ax-mem/                  C-13
│   ├── ax-jobs/                 C-12
│   ├── ax-model/                DM-* + génération Java + config par défaut
│   ├── ax-telemetry/            C-15
│   ├── ax-ffi/                  C-14 (cdylib, points d'entrée JNI)
│   ├── ax-asset/                C-21..C-25
│   ├── ax-deform-compile/       C-28 (régions, poids, ancrages, structure)
│   ├── ax-scene/                C-30
│   ├── ax-physics/              C-31, C-32, C-38, C-39, C-40
│   ├── ax-vehicle/              C-33
│   ├── ax-impact/               C-41
│   ├── ax-damage/               C-35, C-47
│   ├── ax-deform/               C-42  (application, champ, LOD, sérialisation)
│   ├── ax-struct/               C-43, C-44
│   ├── ax-refit/                C-45
│   ├── ax-repair/               C-46
│   ├── ax-anim/                 C-37
│   ├── ax-particles/            C-36
│   ├── ax-attach/               C-48
│   ├── ax-render-prep/          C-64, C-82 (culling, occlusion, LOD, pages)
│   ├── ax-net/                  C-51 (sérialisation, quantification, empreintes)
│   ├── ax-persist/              C-52 (sérialisation des blobs)
│   ├── ax-quality/              C-77 (partie native)
│   ├── ax-cli/                  C-74
│   └── ax-bench/                C-72 (criterion)
├── java/
│   ├── axion-api/               C-70, module publié séparément
│   │   └── src/main/java/dev/axion/api/**
│   └── axion-mod/
│       ├── src/main/java/dev/axion/
│       │   ├── AxionMod.java
│       │   ├── forge/           C-01 (SEUL package important net.minecraftforge)
│       │   ├── bootstrap/       C-02, C-03
│       │   ├── bridge/          C-14 côté Java (NativeBridge, buffers)
│       │   ├── config/          C-04
│       │   ├── diag/            C-05, C-71
│       │   ├── quality/         C-77 côté Java
│       │   ├── asset/           C-20, C-26 (partie commune), C-27
│       │   ├── entity/          C-50, C-53
│       │   ├── damage/          conversion des dégâts vanilla en impacts
│       │   ├── net/             C-51 côté Java
│       │   ├── persistence/     C-52 côté Java
│       │   ├── block/           C-54
│       │   ├── integration/rustforgex/   C-76 (réflexif, isolé)
│       │   └── client/          tout le code client-only
│       │       ├── render/      C-60..C-66, C-68, C-69
│       │       ├── shadow/      C-80
│       │       ├── probe/       C-81
│       │       ├── effects/     C-83
│       │       ├── debug/       C-67, C-73
│       │       └── item/        C-55
│       ├── src/main/resources/
│       │   ├── META-INF/mods.toml, axion.mixins.json, pack.mcmeta
│       │   ├── assets/axion/shaders/**       (PBR, déformation, décalques,
│       │   │                                  ombre, occlusion, brdf_lut.png)
│       │   ├── assets/axion/axion/models/builtin/**
│       │   ├── assets/axion/decals/**
│       │   └── data/axion/axion/{definitions,physics_materials,
│       │                          block_materials,wear_profiles,
│       │                          repair_rules,damage_sources}/**
│       └── src/test/java/**
├── testmods/
│   └── axion-testmod-coexist/   27.10.1 (java + stub natif cx_*)
├── tools/
│   ├── blender/                 C-75 (addon + rules.json généré)
│   ├── codegen/                 génération Java depuis ax-model
│   ├── bench/                   harnais, agrégation, comparaison
│   ├── ci/
│   └── examples/                sources Blender des exemples (CC0)
├── tests/
│   ├── integration/  gameplay/  stress/  compat/
│   └── fixtures/
│       ├── models/ a3d/ definitions/ packets/ renders/ worlds/
│       ├── blender/             reference_vehicle.blend et variantes
│       └── det/                 vecteurs d'or du noyau déterministe
├── fuzz/
│   ├── fuzz_targets/            gltf, obj, stl, a3d, packets, impacts,
│   │                            deform_snapshot, nbt
│   └── corpus/
└── benchmarks/
    └── results/                 résultats archivés (JSON), par version
```

- R-2330 : **aucun fichier, module ou artefact de RUSTFORGE-X n'est présent ni requis**. La seule mention hors documentation est la chaîne `"rustforgex"` dans C-76 et dans les tests de coexistence.
- R-2331 : `axion-testmod-coexist` n'est jamais inclus dans la release.

## 33.2 Contenu de l'artefact final

```text
axion-<version>-mc1.20.1-forge47.jar
├── META-INF/{MANIFEST.MF, mods.toml}
├── dev/axion/**/*.class          (dont dev/axion/api/**)
├── axion.mixins.json, axion.refmap.json, pack.mcmeta
├── assets/axion/**               shaders, LUT BRDF, décalques, modèles de
│                                 secours, langues
├── data/axion/**                 definitions d'exemple, matériaux, profils,
│                                 règles, mappages de sources de dégâts
└── natives/
    ├── windows-x86_64/axion_native.dll        (+ .sha256)
    ├── linux-x86_64/libaxion_native.so        (+ .sha256)
    ├── linux-aarch64/libaxion_native.so       (+ .sha256)
    ├── macos-x86_64/libaxion_native.dylib     (+ .sha256)
    └── macos-aarch64/libaxion_native.dylib    (+ .sha256)
```

---

# PARTIE 34 : BUILD, CI/CD, RELEASE, INSTALLATION

## 34.1 Chaîne de build

```text
./gradlew build
  ├── :codegen           classes Java de modèle depuis ax-model (échec si divergence)
  ├── :buildNatives      cargo build --release -p ax-ffi par cible + SHA-256
  ├── :compileJava       axion-api puis axion-mod
  ├── :test              cargo test/nextest + JUnit
  ├── :packageNatives    copie dans resources/natives/
  ├── :jar               assemblage
  └── :validateJar       natifs et .sha256 présents, aucune dépendance shadée,
                         mods.toml valide, aucune classe client dans le chemin
                         serveur, table de symboles native (R-2020),
                         présence de la LUT BRDF et des shaders requis
```

```bash
./gradlew clean build
```

```bash
./gradlew buildNatives -Paxion.targets=windows-x86_64,linux-x86_64
```

```bash
cargo test --workspace --all-features
```

```bash
./gradlew runGameTest
```

- R-2340 : build réussi **sans réseau** après un premier `--refresh-dependencies`.
- R-2341 : cible native indisponible → production ignorée avec avertissement, JAR marqué `partial` ; un JAR `partial` ne peut pas être publié (bloqué par `validateJar`).
- R-2342 : build **reproductible** : horodatages normalisés, ordre déterministe, versions verrouillées (T-710).

## 34.2 Plateformes

| Plateforme | Statut | Natif |
|---|---|---|
| Windows x86_64 | supporté | `axion_native.dll` (CRT statique) |
| Linux x86_64 (glibc ≥ 2.28) | supporté | `libaxion_native.so` |
| macOS aarch64 | supporté | `libaxion_native.dylib` |
| macOS x86_64 | supporté | `libaxion_native.dylib` |
| Linux aarch64 | best effort | non bloquant |
| Autres | non supporté | `DISABLED` avec message clair |

## 34.3 CI/CD

```text
PR       lint + build + unitaires + intégration + gametest + benchs rapides
main     idem + T-970 bout en bout + stress court + matrice de compatibilité
nightly  stress complet + fuzzing (1 h/cible) + endurance 1 h + Miri
tag      release complète (toutes plateformes) + SBOM + signature + publication
```

**Jobs obligatoires :**

```text
lint-java              spotless + ArchUnit
lint-rust              cargo fmt --check + clippy -D warnings
lint-no-fiction        aucun TODO/FIXME/todo!()/unimplemented!()/placeholder
                       dans un module STABLE ou EXPERIMENTAL
lint-no-mod-names      INV-01 (exception listée : "rustforgex" dans C-76 et tests)
lint-no-global-pool    absence de commonPool / rayon global
lint-budgets           T-007 : chaque composant a budget, métriques, surcharge,
                       fallback
lint-units             vérification dimensionnelle : chaque formule normative du
                       document et du code porte son annotation d'unité, et les
                       tests T-xxxb correspondants existent et passent (11.5)
deps                   cargo-deny + SBOM
codegen-parity         T-005
det-vectors            T-820 : vecteurs d'or du noyau déterministe, 3 plateformes
build-natives          matrice de plateformes
test-unit              cargo nextest + JUnit
test-integration       Java<->Rust, Forge headless
test-gametest          serveur dédié
test-render            client automatisé, comparaison d'images
test-e2e               T-970 et T-971
test-compat            matrice 29.x, coexistence obligatoire
test-rustforgex        si RUSTFORGEX_JAR fourni, sinon SKIPPED_UNAVAILABLE
bench-quick            B-01, B-02, B-07, B-09, B-11, B-18, B-19, B-21, B-23
fuzz-short             10 min par cible sur les PR
docs                   génération de CONFIGURATION.md, API.md, NOTICE, rules.json
validate-jar           R-2341 et contrôles d'artefact
```

- R-2350 : échec de `lint-*`, `codegen-parity`, `det-vectors`, `deps`, `test-*` ou `validate-jar` **bloque** la fusion.
- R-2351 : régression > 20 % (p95) bloque jusqu'à justification documentée.
- R-2352 : `test-rustforgex` en `SKIPPED_UNAVAILABLE` ne bloque pas la fusion mais **bloque la publication d'une release déclarant la coexistence vérifiée**.

## 34.4 Versionnement et release

```text
Version du mod       : MAJOR.MINOR.PATCH
Version de l'API     : indépendante (axion-api)
Version du protocole : entier
Version de l'ABI     : entier
Version A3D          : major.minor
Schémas              : entier par schéma (definition, persistance, paquets,
                       champ de déformation)
Artefact             : axion-<version>-mc1.20.1-forge47.jar
```

```text
1. CHANGELOG   2. version figée   3. tag annoté   4. CI complète
5. génération : JAR, axion-api (jar + sources + javadoc), SBOM, SHA-256, NOTICE
6. validateJar + installation propre (34.6)
7. publication  8. archivage des benchmarks sous benchmarks/results/<version>/
```

- R-2360 : les notes de version indiquent l'état réel de la vérification de coexistence avec RUSTFORGE-X.
- R-2361 : aucune release ne contient de fonctionnalité `EXPERIMENTAL` activée par défaut.

## 34.5 Installation

```text
1. Minecraft 1.20.1 + Forge 47.x
2. déposer le JAR dans mods/
3. lancer, vérifier avec /axion status
```

Aucun runtime externe, aucune bibliothèque système à installer.

## 34.6 Test d'installation propre (obligatoire avant release)

```text
- machine ou conteneur vierge, aucune donnée AXION préexistante
- installation par le seul dépôt du JAR
- client : monde créé, assembly d'exemple spawnée, endommagée, déformée,
  sauvegardée, sortie
- serveur dédié : connexion d'un client, même scénario, second client vérifiant
  la déformation
- redémarrage : état restauré à l'identique (champ inclus)
- retrait du JAR : le monde se charge, aucune erreur bloquante
- réinstallation : assemblies et déformations retrouvées
```

**Tests.** T-710..T-714.

---
# PARTIE 35 : DÉCISIONS D'ARCHITECTURE (ADR)

Chaque ADR est repris dans `docs/decisions/ADR-xxx.md` avec contexte, options, décision, conséquences et date. Toutes sont **prises et gelées** pour la V1.0.

## ADR-001 — glTF 2.0 / GLB comme format d'échange principal
Spécification ouverte, export Blender natif, couverture complète, conteneur autonome, `extras` par node permettant de transporter toute la métadonnée AXION (déformation, structure, dommage). OBJ et STL supportés avec périmètre réduit et **génération automatique** des régions et du graphe structurel. FBX exclu (licence du SDK). Collada, USD, PLY hors périmètre, contournement par Blender.

## ADR-002 — Rapier3D comme moteur physique
`rapier3d` + `parry3d` (Apache-2.0). Écartés : moteur propre (coût), PhysX/Bullet/Jolt (build C++). `parry3d` fournit aussi le QuickHull utilisé par le refit de collider. Les types Rapier ne fuient jamais dans l'API publique, ce qui garde un remplacement possible.

## ADR-003 — Modèle de véhicule par raycast
Stabilité sur géométrie en blocs, coût constant, pas de tunneling, contrôle du modèle de pneu. Les roues par corps rigides restent hors périmètre V1.0.

## ADR-004 — JNI + DirectByteBuffer pour la frontière Java/Rust
Java 17 sans FFM stable ; zéro copie ; un lot par phase et par tick. Panama reste possible derrière `NativeBridge`.

## ADR-005 — Autorité serveur, sans déterminisme physique inter-plateformes
Le lockstep est inutile en autorité serveur ; l'exiger interdirait parallélisation et SIMD. Reproductibilité garantie **sur une même machine**. Complété par ADR-018 pour la chaîne de dommage.

## ADR-006 — Rendu piloté depuis Java sur le contexte GL de Minecraft
Tous les appels OpenGL depuis Java, sur le render thread ; Rust prépare les données. Le contexte appartient à la JVM ; le partager avec du code natif multiplierait les risques de corruption d'état et d'incompatibilité de pilotes.

## ADR-007 — Deux backends de rendu maintenus, équivalence fonctionnelle et non pixellaire
`NATIVE_GL` et `VANILLA_CONSUMER` livrés et testés. Les shaderpacks sont massivement utilisés ; un mod de rendu sans chemin compatible est inutilisable pour une grande partie des joueurs. Coût de maintenance doublé, accepté et budgété.

**Précision (révision 2.1).** L'équivalence exigée entre les deux backends est
**fonctionnelle et géométrique**, définie et bornée en 19.2bis : mêmes instances,
mêmes transforms, mêmes LOD, même géométrie déformée, mêmes pièces visibles,
silhouette sous tolérance déclarée. Les **capacités visuelles** diffèrent selon
une matrice publiée et exposée par l'API ; une capacité indisponible est
désactivée proprement et signalée. Aucune équivalence pixel à pixel n'est exigée
ni promise, et le basculement de backend ne touche jamais la simulation, la
persistance, le réseau ni le gameplay (R-1494).

## ADR-008 — PBR avec environnement synthétisé *(révisé en révision 2)*
**Décision.** PBR metallic-roughness complet (Cook-Torrance GGX + Smith + Fresnel), alimenté par une sonde d'environnement synthétisée à partir du ciel, du brouillard, du soleil, de la météo et du lightmap vanilla, plus clearcoat/sheen/anisotropie, parallax et tone mapping en shader.
**Ce qui a changé.** La révision 1 concluait « pas de PBR, Minecraft ne fournit pas d'irradiance ». C'était une limitation artificielle : l'irradiance peut être **synthétisée** de façon cohérente et peu coûteuse. Le modèle `VANILLA_COMPAT` reste le chemin du backend vanilla, où le shaderpack fournit l'éclairage.
**Conséquences.** Une sonde dynamique 32² × 6 avec préfiltrage, une LUT BRDF embarquée, un `lightmapFactor` qui accorde le résultat au monde. Coût gradué par niveau de qualité. Pipeline HDR de sortie hors périmètre (framebuffer LDR en 1.20.1).

## ADR-011 — Déformation continue par champ de lattice *(révisé en révision 2)*
**Décision.** Champ de déformation FFD trilinéaire par région, avec composantes **élastique** et **plastique**, complété par un résidu épars par sommet (Q-3+), un solveur masse-ressort sur les nœuds (Q-4), et des compléments déclaratifs (bones de déformation, morph targets). Les variantes de mesh deviennent le **fallback Q-0**, plus la solution principale.
**Ce qui a changé.** La révision 1 excluait la déformation continue au motif de son coût. C'était contraire à la vision : une voiture doit pouvoir être réellement cabossée. Le coût est résolu par le choix de représentation, pas par l'abandon : le nombre de degrés de liberté est celui du lattice (au plus 4096 nœuds), pas celui du maillage (jusqu'à 2 000 000 de sommets).
**Conséquences.** Stockage compact (3 octets par nœud), normales par gradient analytique en vertex shader, réplication par événements, persistance de quelques centaines d'octets par région, refit de collider budgété, aucune reconstruction de mesh. Un objet intact n'alloue aucun champ et n'exécute aucun pas de simulation de déformation ; un objet déformé paie un coût GPU par sommet, mesuré, budgété et gradué (14.11bis).

## ADR-012 — Solveur de particules unifié à trois modes d'autorité *(révisé)*
**Décision.** Un solveur XPBD unique pour tissus, cordes, câbles, filets et corps souples, avec `VISUAL` (client), `SERVER_SIMPLE` (proxy serveur + visuel client) et `SERVER_FULL` (EXPERIMENTAL).
**Ce qui a changé.** La révision 1 limitait le tissu à un effet purement visuel. Le mode `SERVER_SIMPLE` apporte l'effet gameplay au coût d'un proxy de quelques dizaines de particules, ce qui couvre remorquage, treuils et sangles sans mettre le TPS en danger.

## ADR-013 — Pas d'ECS tiers, structures SoA propres
Besoins restreints, contrôle du layout mémoire, une dépendance structurante en moins.

## ADR-014 — Job system dédié au-dessus de rayon, jamais le pool global
Le pool global est une ressource partagée du processus. Coexistence saine avec tout autre mod.

## ADR-015 — Indépendance vis-à-vis de RUSTFORGE-X, compatibilité par conception
Aucune dépendance, aucune déclaration `mods.toml`, détection par `ModList`, bridge réflexif optionnel, coexistence limitée au partage de ressources. Testée par un mod de simulation détenu par AXION, et avec l'artefact réel quand il est disponible.

## ADR-016 — Empreinte Mixin fermée et minimale
Quatre cibles, toutes dans le code Minecraft, toutes non bloquantes en cas d'échec.

## ADR-017 — Persistance du seul état non dérivable
Rien de reconstructible n'est sauvegardé, à l'exception du **champ plastique** qui est un état autoritatif irremplaçable. Taille proportionnelle au dommage réel, jamais à la complexité géométrique.

## ADR-018 — Réplication de la déformation par événements et reconstruction déterministe
**Décision.** Le serveur envoie des `ImpactDesc` compacts ; le client les rejoue avec un noyau déterministe partagé (C-16) et obtient le même champ quantifié, bit à bit. Une empreinte périodique détecte toute divergence, corrigée par un instantané compact de la seule région concernée.
**Options écartées.** Envoi du champ à chaque changement (coût réseau proportionnel à la géométrie et à la fréquence), envoi par sommet (prohibitif), calcul client libre (non autoritatif, trichable).
**Conséquences.** Le trafic est proportionnel au nombre d'impacts, pas à la complexité. Le noyau impose une discipline arithmétique (opérations IEEE-754 exactes, pas de transcendantes, FMA désactivée, ordre et largeur SIMD fixés), vérifiée par des vecteurs d'or sur chaque configuration de la **matrice de validation déterministe** (5.12bis). Hors matrice, ou en cas de divergence constatée, le mode `SNAPSHOT` prend le relais : la garantie de correction ne dépend jamais de H-14, seul le coût réseau varie.

**Portée de la garantie (révision 2.1).** « Bit-identique » signifie : identique
pour les triplets cible, chaînes de compilation et jeux d'instructions
**officiellement supportés et validés par les vecteurs de référence de la version
courante**. Aucune garantie n'est donnée sur une architecture ou un compilateur
non testés ; le comportement y est explicitement défini (bascule `SNAPSHOT`,
signalée), et AXION n'y refuse jamais de fonctionner (R-514, R-515).

## ADR-019 — Refit de collider par déformation des points d'enveloppe
**Décision.** Les colliders convexes sont refités en appliquant le champ à leurs points d'enveloppe conservés (≤ 256), puis en recalculant l'enveloppe convexe. Budgété à N refits par tick, avec seuil de déclenchement et écart toléré documenté.
**Options écartées.** Trimesh dynamique (instable et coûteux, interdit par INV-13), recalcul complet du compound (coût), aucune mise à jour (collision incohérente avec le visuel).
**Conséquences.** La collision suit la déformation avec un retard borné et mesuré. La physique reste stable car la forme reste convexe. Des variantes de collider déclarées servent de secours et de chemin `Q-0`.

## ADR-020 — Graphe structurel pour la rupture et le détachement
**Décision.** Un graphe acyclique de parts et de liaisons porteuses d'une capacité énergétique et de seuils de force ; la rupture d'une liaison peut déconnecter une composante, qui devient une assembly de débris héritant de sa déformation, de son usure et de ses décalques.
**Options écartées.** Fracture Voronoï dynamique (coût prohibitif, réseau impossible), détachement par simple seuil de santé (ne modélise ni la propagation ni les charges statiques).
**Conséquences.** La découpe en parts par l'auteur devient la granularité de destruction, ce qui donne une géométrie propre et sans coût de génération. Les liaisons non déclarées sont générées automatiquement.

## ADR-021 — Carte d'ombre dédiée aux objets AXION
**Décision.** Une passe de profondeur dans un FBO propre, couvrant les seules instances AXION, appliquée aux seuls objets AXION (auto-ombrage et inter-ombrage), plus une ombre de contact aux niveaux bas.
**Motif.** Ombrer le monde vanilla exigerait de modifier son shading : non additif et incompatible avec les shaderpacks. Une carte limitée à nos objets est peu coûteuse et apporte l'essentiel.
**Conséquences.** Désactivée en backend vanilla (le shaderpack fournit ses ombres) et au niveau `Q-0`.

## ADR-022 — Occlusion culling logiciel plutôt que requêtes GPU
**Décision.** Rastériseur de profondeur logiciel en Rust, SIMD, résolution graduée, conservatif.
**Motif.** Les requêtes GPU introduisent une latence d'au moins une frame, produisent du pop et s'intègrent mal au culling vanilla. Le rastériseur logiciel répond dans la même frame et se parallélise.
**Conséquences.** Budget dédié, désactivation propre, garantie de conservatisme testée.

## ADR-023 — Niveaux de qualité et gouverneur
**Décision.** Chaque sous-système coûteux est gradué de `Q-0` à `Q-4`, tous les niveaux étant implémentés et testés. Un gouverneur ajuste les niveaux à partir des budgets mesurés, avec hystérésis, journalisation de la cause, et respect absolu des niveaux forcés par l'utilisateur.
**Motif.** Remplacer définitivement la logique « c'est trop coûteux, donc on retire » par « c'est coûteux, donc on gradue ».
**Conséquences.** Côté serveur, seuls des paramètres explicitement sans effet sur la correction sont gouvernés.

## ADR-024 — Décalques en espace objet, suivant la déformation
**Décision.** Décalques projetés en espace objet, avec dé-déformation approchée du fragment avant le test d'appartenance, ce qui fait qu'une rayure reste sur la tôle même déformée. Atlas dédié, plafonds, éviction.
**Options écartées.** Décalques en espace monde (glissent sur l'objet), géométrie de décalque (coût), textures uniques par instance (mémoire).

## ADR-025 — Système d'attache entre assemblies
**Décision.** Un composant générique reliant deux sockets par un joint, avec types `RIGID`, `HITCH`, `ROPE`, `WINCH`, `MAGNET`, `SEATED`, transfert optionnel de dommage et de couple, graphe acyclique, persistance par identifiant.
**Motif.** Remorquage, attelage, modules de machines et équipements portés relèvent tous du même mécanisme ; les traiter séparément aurait produit des cas particuliers.

## ADR-026 — Chemins accélérés GL 4.x optionnels sur une base GL 3.3
**Décision.** Multi-draw indirect et compute shaders sont des chemins d'optimisation détectés à l'exécution ; le chemin GL 3.3 est obligatoire, complet et testé, et produit une image identique.
**Motif.** Minecraft n'exige que GL 3.2 ; exiger GL 4.3 exclurait une partie du parc. Refuser les chemins accélérés priverait le reste du parc d'un gain réel.

---

# PARTIE 36 : PÉRIMÈTRE V1.0 / V1.x / HORS PÉRIMÈTRE

## 36.1 V1.0 — obligatoire

```text
INFRASTRUCTURE
  mod Forge client + serveur dédié, bootstrap, natif, configuration,
  diagnostics, commandes, API publique versionnée, télémétrie, watchdog,
  budgets, niveaux de qualité, gouverneur, dégradation, noyau déterministe,
  tests, benchmarks, fuzzing, CI, release, documentation

ASSETS
  import GLB/glTF/OBJ/STL, validation, optimisation, LOD, décomposition convexe,
  points d'enveloppe, compilation des régions de déformation et du graphe
  structurel, format A3D, cache, textures, matériaux, atlas de décalques,
  definitions et registres data-driven, addon Blender, CLI

SIMULATION
  scene graph et états de node, sockets avec désalignement, corps rigides,
  colliders primitifs/convexes/compound/trimesh statique/heightfield,
  matériaux physiques à propriétés mécaniques complètes, collision monde par
  tuiles avec matériaux, flottabilité, requêtes spatiales, joints (7 types)
  avec grippage et rupture, événements

VÉHICULES
  roues par raycast, suspension, pneus déclaratifs, moteur, transmission,
  différentiels, direction, freinage, aérodynamique, télémétrie, entrées
  validées, couplage complet avec le dommage et la déformation

DOMMAGE
  solveur d'impacts énergétique, conversion des sources vanilla, distribution
  déclarative, zones et sous-zones, santé et étapes, propagation, dommage
  continu et thermique, actions déclaratives

DÉFORMATION CONTINUE
  champs élastique et plastique par région, 5 niveaux de qualité tous
  implémentés, résidus épars, solveur masse-ressort, bones et morphs
  déclaratifs, normales par gradient analytique, déformation GPU, LOD de
  déformation, refit de collider budgété, déchirure (EXPERIMENTAL),
  réplication par événements, persistance compacte, fallback Q-0

STRUCTURE
  graphe de liaisons, propagation, charges statiques, rupture, détachement,
  débris avec héritage de déformation, révélation des éléments internes

RÉPARATION
  5 niveaux, règles data-driven, restauration progressive, remplacement,
  rattachement, persistance de l'avancement

USURE ET SURFACE
  4 canaux quantifiés, profils déclaratifs, décalques en espace objet suivant
  la déformation, matériaux de dommage

ANIMATION
  squelettique, node, procédurale déclarative, joint-driven, déformation-driven,
  4 couches, couplage complet animation/physique/dégâts

PARTICULES
  solveur XPBD unifié, tissu, cordes, câbles, filets, corps souples,
  3 modes d'autorité, collisions proxy et monde, auto-collision et déchirure
  (EXPERIMENTAL), LOD de simulation

ATTACHES
  6 types, transfert de dommage et de couple, persistance, graphe acyclique

RENDU
  deux backends, format de vertex unique, PBR avec environnement synthétisé,
  clearcoat/sheen/anisotropie, parallax, décalques, ombres AXION,
  occlusion culling logiciel, tone mapping, SSR (EXPERIMENTAL), instancing,
  batching, multi-draw indirect optionnel, skinning et déformation GPU,
  compute optionnel, culling, LOD, transparence, debug renderer, overlay

RÉSEAU
  autorité serveur, snapshots delta quantifiés, priorités adaptatives,
  interpolation, prédiction du véhicule piloté sans plastique prédit,
  réconciliation, réplication de la déformation par impacts, empreintes,
  instantanés de resynchronisation, validation, rate limiting

PERSISTANCE
  état non dérivable, champ plastique, structure, usure, décalques marquants,
  attaches, réparations en cours, versionnement, migration, réversibilité

INTÉGRATION
  blocs 3D déformables, items 3D avec état de dommage, sièges, interactions

COMPATIBILITÉ
  Forge, vanilla, shaderpacks, mods de rendu, mods natifs, mods de bytecode,
  coexistence RUSTFORGE-X (détection, partage CPU, bridge, matrice de tests)
```

## 36.2 V1.x — extensions futures

```text
- machine à états d'animation (arbres de blend, conditions)
- import Collada et PLY
- roues par corps rigides (chenilles, pneus déformables)
- pathfinding vanilla prenant réellement en compte les assemblies
- éditeur en jeu de definitions et de régions
- streaming d'assets depuis le serveur
- support Fabric / NeoForge (l'abstraction C-01 le rend possible)
- support d'autres versions de Minecraft
- API de scripting sandboxée
- fracture procédurale précalculée (découpe automatique en parts à la compilation)
- déformation volumétrique multi-région couplée (propagation inter-régions
  physiquement fondée)
- ombres en cascade couvrant une zone étendue
```

## 36.3 Hors périmètre — exclu de la V1

| Élément | Motif technique |
|---|---|
| Import FBX natif | licence du SDK Autodesk incompatible avec Apache-2.0 ; réimplémentations libres incomplètes |
| Simulation FEM volumétrique complète | coût incompatible avec 20 Hz et des dizaines d'objets ; l'architecture par champ atteint l'objectif visuel à une fraction du coût |
| Fracture Voronoï dynamique | coût CPU, coût réseau, et impossibilité de répliquer ou de persister le résultat de façon compacte ; remplacée par la découpe en parts précompilée |
| Simulation de fluides | hors sujet du moteur, coût prohibitif, aucune intégration possible avec les fluides vanilla |
| Pipeline HDR de sortie et post-processing plein écran | framebuffer LDR en 1.20.1 ; tout post-processing modifierait l'image entière, donc le rendu vanilla, et entrerait en conflit avec les shaderpacks |
| Global illumination, ray tracing | hors des capacités de GL 3.3 et du budget d'un mod |
| Physique sur GPU | portabilité, complexité de synchronisation avec la logique serveur, gain incertain |
| Déterminisme physique bit-à-bit inter-plateformes | inutile en autorité serveur ; interdirait parallélisation et SIMD (le noyau de **déformation** est, lui, déterministe) |
| Remplacement du renderer de terrain ou de l'éclairage vanilla | non additif, incompatible avec les mods d'optimisation et de shaders |
| Modification de la collision de blocs vanilla | structurante pour tout le jeu et pour le pathfinding |
| Redistribution de Minecraft, Forge, mods ou assets tiers | interdit |
| Connexion réseau sortante, télémétrie distante | vie privée |

- R-2370 : chaque élément hors périmètre est répété dans la documentation utilisateur avec son motif.

## 36.4 Audit des limitations de la révision 1

| Limitation (révision 1) | Verdict | Traitement en révision 2 |
|---|---|---|
| Pas de déformation continue ; dégâts par variantes de mesh | **contraire à la vision, levée** | système complet de déformation continue (PARTIE 14), variantes reléguées au fallback `Q-0` |
| Pas de PBR (« Minecraft ne fournit pas d'irradiance ») | **limitation artificielle, levée** | PBR complet avec environnement synthétisé (ADR-008) |
| Pas d'ombres portées | **limitation artificielle, partiellement levée** | carte d'ombre dédiée aux objets AXION (ADR-021) ; ombrer le monde vanilla reste hors périmètre, motivé |
| Pas d'occlusion culling (« gain incertain ») | **limitation artificielle, levée** | rastériseur logiciel conservatif (ADR-022) |
| Cloth purement visuel, sans effet gameplay | **réductrice, levée** | trois modes d'autorité (ADR-012) |
| Cordes réduites à une contrainte de distance | **réductrice, levée** | contrainte **plus** chaîne de particules serveur en `SERVER_SIMPLE` |
| Pas de déchirure de tissu | **levée en EXPERIMENTAL** | rupture de contrainte implémentée, `Q-3`+ |
| Pas d'auto-collision de tissu | **levée en EXPERIMENTAL** | hachage spatial, `Q-3`+ |
| Pas de filets | **levée** | mode `SERVER_FULL` (EXPERIMENTAL, implémenté) |
| Dommage lié à une variable abstraite `health -= X` | **contraire à la vision, levée** | solveur d'impacts énergétique (C-41), chaîne complète |
| Pas de propriétés mécaniques de matériau | **levée** | modèle de matière complet (PARTIE 11) |
| Pas de sous-zones de dommage | **levée** | `parent_zone` (DM-11) |
| Pas d'éléments internes révélés | **levée** | `INTERNAL` + `REVEALED_ON_DAMAGE` |
| Pas de réparation graduée | **levée** | 5 niveaux data-driven (PARTIE 16) |
| Pas d'usure ni de décalques | **levée** | C-47, C-69, PARTIE 20 |
| Pas d'attaches entre assemblies | **levée** | C-48 (ADR-025) |
| Pas de bones de déformation ni de morph targets | **levée** | compléments déclaratifs (14.8) |
| Pas de multi-draw indirect ni de compute | **levée** | chemins accélérés optionnels (ADR-026) |
| Blocs AXION purement visuels | **levée partiellement** | blocs déformables et destructibles, `VoxelShape` déclarée par étape ; la collision reste vanilla, motivé |
| Items 3D sans état | **levée** | état de dommage et champ compact en NBT |
| Mobs vanilla ne contournent pas les assemblies | **conservée**, atténuation ajoutée | `pathfinding_blocker` EXPERIMENTAL (R-1930) ; modifier le pathfinding reste non additif |
| Flottabilité approchée par échantillonnage | **conservée**, motivée | un solveur de fluides est hors périmètre ; le modèle est documenté |
| Section de chunk non chargée traitée comme solide | **conservée**, motivée | choix conservateur ; charger un chunk serait un effet de bord inacceptable |
| Pas de tri intra-mesh des translucides | **conservée**, motivée | coût prohibitif ; recommandation d'auteur documentée |
| Client obligatoire pour rejoindre | **conservée**, motivée | des entités non représentables produiraient des fantômes |
| FBX, Collada, USD, PLY non importés | **conservée**, motivée | licence (FBX) et coût (autres) ; contournement Blender documenté |
| Pas de déterminisme physique inter-plateformes | **conservée**, motivée | inutile en autorité serveur ; le noyau de déformation est déterministe |
| Pas de HDR ni de post-processing | **conservée**, motivée | framebuffer LDR, conflit systématique avec les shaderpacks |

- R-2380 : aucune limitation n'est conservée au seul motif qu'elle figurait dans la révision précédente. Chacune est ré-justifiée ou levée.

---

# PARTIE 37 : JALONS ET DEFINITION OF DONE

## 37.1 Règles

- R-2390 : chaque jalon se termine par un **JAR installable et jouable**.
- R-2391 : chaque jalon **préserve intégralement** les fonctionnalités des précédents.
- R-2392 : chaque jalon a des critères d'acceptation vérifiables mécaniquement.
- R-2393 : aucune fonctionnalité `STABLE` ni `EXPERIMENTAL` n'est introduite sans tests, métriques, budget, fallback et documentation.

## 37.2 Jalons

### M0 — Squelette et frontière native
```text
C-01..C-05, C-10, C-11, C-13, C-14 (contrôle)
Livrable : chargement client et serveur, natif chargé, handshake ABI,
           /axion status, arrêt propre, DISABLED sûr.
Acceptance : JAR installable ; boot READY sur les plateformes ; natif absent ou
             ABI incompatible -> DISABLED, jeu jouable ; bilan d'allocations nul.
Tests : T-100..T-103, T-110..T-114, T-120..T-124, T-130..T-133, T-150..T-152
```

### M1 — Assets, noyau déterministe, jobs
```text
C-12, C-15, C-16, C-20, C-21, C-22, C-24, C-71
Livrable : compilation GLB/OBJ/STL -> A3D, inspection, noyau déterministe validé.
Acceptance : fixtures compilées, golden files stables ; asset corrompu refusé ;
             fuzzing 1 h sans incident ; vecteurs d'or déterministes verts sur
             les 3 plateformes de CI.
Tests : T-210..T-214, T-220..T-226, T-230..T-233, T-250..T-253, T-590, T-591,
        T-820..T-822, T-170..T-173
```

### M2 — Scene graph, cache, optimizer, entité, API
```text
C-23, C-25, C-27, C-30, C-50, C-70, C-72, C-74
Livrable : /axion spawn crée une AxionEntity persistante avec rendu de debug.
Acceptance : LOD générés, optimizer déterministe ; cache correct ; CLI identique
             au jeu ; API compile et documentée.
Tests : T-240..T-244, T-260..T-263, T-280..T-284, T-290..T-292, T-400..T-404,
        T-580, T-630..T-634
```

### M3 — Physique et premier rendu
```text
C-31, C-32, C-38, C-39, C-40, C-60..C-63, C-67, C-26
Livrable : un objet tombe, repose sur le sol, est rendu dans les deux backends.
Acceptance : chute libre à 1 % ; empilement stable 60 s ; aucune traversée du sol
             sur 10 min ; aucune erreur GL ; état restauré ; bascule sous shaderpack.
Tests : T-300..T-312, T-370..T-375, T-380..T-384, T-470..T-474, T-479, T-480,
        T-490..T-493, T-500..T-503, T-510..T-512, T-550, T-551
```

### M4 — Réseau, animation, culling/LOD, joints, persistance
```text
C-34, C-37, C-51, C-52, C-64, C-65, C-66
Livrable : multijoueur fonctionnel, objets animés, LODés, persistés.
Acceptance : bande passante sous plafond ; interpolation fluide à 200 ms et 20 %
             de perte ; joints conformes ; animation conforme glTF ; sauvegarde exacte.
Tests : T-330..T-334, T-360..T-368, T-410..T-425, T-430..T-437, T-520..T-524,
        T-530..T-533, T-540..T-542
```

### M5 — Véhicules, sièges, attaches, gouverneur, overlay
```text
C-33, C-48, C-53, C-73, C-77
Livrable : véhicule pilotable en multijoueur avec prédiction ; remorquage.
Acceptance : 10 tests véhicules ; montée/descente sûres ; entrées validées ;
             attaches persistées ; gouverneur baisse et remonte les niveaux avec
             cause affichée.
Tests : T-320..T-329, T-415..T-417, T-440..T-444, T-570, T-890..T-895,
        T-980..T-984
```

### M6 — Impacts et déformation continue *(jalon central)*
```text
C-28, C-41, C-42, C-45, C-68, module deformation complet
Livrable : un objet frappé se déforme réellement, visuellement et en collision,
           de façon persistante, à tous les niveaux de qualité.
Acceptance :
  - énergie d'impact cohérente avec la perte d'énergie cinétique (10 %)
  - profondeur monotone en fonction de la vitesse sur 5 vitesses
  - accumulation puis saturation sur impacts répétés
  - élastique sous seuil, plastique au-dessus
  - normales par gradient cohérentes (2 %)
  - assembly intacte : zéro octet d'arène DEFORM, zéro pas de simulation,
    variante de shader non déformée (14.11bis)
  - aucune allocation dans la boucle chaude
  - Q-0 à Q-4 tous fonctionnels et benchmarkés séparément (B-19)
  - refit budgété, collision cohérente à l'écart déclaré, jamais de trimesh
  - axion-cli deform identique au jeu
Tests : T-800..T-819, T-830..T-849, T-851..T-858, B-18, B-19, B-21
```

### M7 — Structure, rupture, détachement, réparation
```text
C-43, C-44, C-46
Livrable : les pièces cassent, se détachent en débris déformés, et se réparent.
Acceptance : rupture au seuil ; débris valides héritant de la déformation et de
             l'usure ; masse et compound mis à jour ; générations bornées ;
             réparation progressive et persistée ; révélation des internes.
Tests : T-850, T-859..T-869, T-880..T-889, B-22
```

### M8 — Particules unifiées
```text
C-36 complet (3 modes d'autorité)
Livrable : capes, drapeaux, cordes de remorquage, filets.
Acceptance : 8 tests de base + collisions monde, auto-collision, déchirure,
             modes d'autorité, plafonds et transitions sans scintillement.
Tests : T-350..T-357, T-930..T-945, B-06
```

### M9 — Rendu avancé
```text
C-69, C-80, C-81, C-82, C-83, C-75 (addon), C-47
Livrable : PBR, ombres, décalques, usure, occlusion, tone mapping, SSR
           expérimental, addon Blender complet.
Acceptance : PBR stable et cohérent avec le monde ; ombres sans scintillement ;
             occlusion conservative (0 faux positif sur 10 000 cas) ;
             tone mapping calibré à 2 LSB ; décalques suivant la déformation ;
             addon validant comme C-22/C-28 et prévisualisant via axion-cli.
Tests : T-870..T-879, T-900..T-929, T-600..T-602, T-940, T-941, B-25..B-29
```

### M10 — Blocs, items, bout en bout
```text
C-54, C-55, scénario T-970/T-971
Livrable : blocs et items 3D déformables ; scénario complet Blender -> réparation.
Acceptance : T-970 et T-971 verts en CI ; aucun code Java spécifique au contenu.
Tests : T-450..T-452, T-460..T-462, T-876, T-877, T-970, T-971, B-30
```

### M11 — Compatibilité, coexistence, durcissement
```text
C-76, axion-testmod-coexist, fuzzing complet, matrice de compatibilité
Acceptance : T-660..T-671 et T-676 inconditionnels ; T-672..T-675 si artefact
             disponible sinon SKIPPED_UNAVAILABLE ; fuzzing 10 M/cible sans
             incident ; matrice verte.
Tests : T-650..T-656, T-660..T-676, T-680..T-691
```

### M12 — Release 1.0.0
```text
Documentation complète, benchmarks archivés (B-01..B-30), SBOM, packaging.
Acceptance : checklist PARTIE 38 intégralement cochée ; build reproductible ;
             installation propre validée ; aucune fonctionnalité EXPERIMENTAL
             activée par défaut ; documentation générée sans divergence.
```

## 37.3 Definition of Done

**Composant terminé**
```text
[ ] fiche PARTIE 5 respectée
[ ] aucun TODO/FIXME/placeholder si STABLE ou EXPERIMENTAL
[ ] tests unitaires et d'intégration listés, tous verts
[ ] métriques exposées et vérifiées
[ ] budget déclaré, mesuré, avec comportement en surcharge
[ ] modes de défaillance implémentés et testés
[ ] fallback implémenté et testé
[ ] niveaux de qualité implémentés et testés le cas échéant
[ ] documentation à jour (fichier .md + Javadoc/rustdoc)
[ ] aucune allocation non comptée, aucun handle non libéré
[ ] conformité aux invariants INV-01..INV-19
```

**Fonctionnalité terminée**
```text
[ ] composants concernés terminés
[ ] scénario de gameplay écrit et vert
[ ] comportement client/serveur défini et testé
[ ] persistance et réseau couverts si applicable
[ ] budget mesuré et documenté ; dégradation testée
[ ] entrées de configuration documentées avec défaut et plage
```

**Jalon terminé**
```text
[ ] critères d'acceptation vérifiés mécaniquement
[ ] JAR installable et jouable produit par la CI
[ ] aucune régression sur les jalons précédents
[ ] benchmarks du périmètre exécutés et archivés
[ ] CHANGELOG mis à jour
```

**Release candidate**
```text
[ ] tous les jalons terminés
[ ] matrice de compatibilité exécutée
[ ] endurance 1 h sans fuite ; fuzzing complet sans incident
[ ] installation propre validée sur les plateformes supportées
[ ] SBOM, NOTICE, licences vérifiés
[ ] documentation complète, générée sans divergence
[ ] aucun défaut bloquant ou critique ouvert
```

## 37.4 Sévérités

| Sévérité | Définition | Effet |
|---|---|---|
| Bloquante | crash, corruption de monde, perte de données, faille | bloque la release |
| Critique | fonctionnalité majeure inutilisable, régression > 50 % | bloque la release |
| Majeure | fonctionnalité dégradée, contournement existant | bloque le jalon |
| Mineure | défaut cosmétique ou marginal | documenté, planifié |

---
# PARTIE 38 : CRITÈRES D'ACCEPTATION ET AUDIT FINAL

## 38.1 Checklist principale de la V1.0

```text
FONCTIONNEL — BASE
[ ] le JAR se charge sur Minecraft 1.20.1 + Forge 47.x, client et serveur dédié
[ ] un objet 3D physique complet est créé avec un GLB + un JSON, sans Java
[ ] cet objet tombe, repose, glisse, entre en collision et s'endort correctement
[ ] un véhicule à roues est pilotable en solo et en multijoueur
[ ] les animations squelettiques, de nodes, procédurales et joint-driven marchent
[ ] tissus, cordes, câbles et filets fonctionnent selon leur mode d'autorité
[ ] les blocs et items 3D fonctionnent
[ ] sièges, montées, descentes, interactions et attaches fonctionnent
[ ] les sockets sont exposés, désalignés par la déformation, invalidés à la perte
[ ] l'API publique permet de créer, endommager, déformer et réparer une assembly

FONCTIONNEL — DOMMAGE ET DÉFORMATION
[ ] un impact physique produit une énergie cohérente avec la physique réelle
[ ] un choc cabosse réellement et progressivement la géométrie visible
[ ] la déformation est locale, orientée, et dépend du matériau
[ ] sous le seuil plastique elle est élastique et revient ; au-dessus elle reste
[ ] des impacts répétés s'accumulent puis saturent
[ ] la déformation modifie la collision, avec un écart borné et mesuré
[ ] aucun collider dynamique n'est un trimesh, même après déformation
[ ] l'intégrité structurelle se dégrade, les liaisons rompent, les pièces se détachent
[ ] une pièce détachée devient un débris physique conservant sa déformation
[ ] les éléments internes deviennent visibles quand ce qui les couvre est détruit
[ ] l'usure (rayures, saleté, brûlure, rouille) s'accumule et se voit
[ ] la réparation restaure, à cinq niveaux, de façon progressive et persistée
[ ] tout cela fonctionne sur n'importe quel objet, sans code spécifique

FONCTIONNEL — RENDU
[ ] PBR cohérent avec le monde, métaux et vernis crédibles
[ ] normal, roughness, metallic, AO, emissive, parallax pris en compte
[ ] déformation rendue sans reconstruction de mesh, normales correctes
[ ] décalques suivant la déformation
[ ] ombres AXION et ombres de contact
[ ] occlusion culling conservatif, instancing, batching, LOD
[ ] les deux backends produisent la même géométrie déformée

QUALITÉ DE SERVICE
[ ] chaque sous-système a un budget mesuré, une surcharge définie, un fallback
[ ] les niveaux de qualité Q-0..Q-4 sont tous implémentés, testés et benchmarkés
[ ] le gouverneur baisse et remonte les niveaux avec hystérésis et cause affichée
[ ] la dégradation est réversible, journalisée et expliquée
[ ] le mode SAFE reste jouable et ne perd aucun impact
[ ] le mode DISABLED laisse le jeu et le monde intacts
[ ] aucun crash causé par un asset, une definition, un paquet ou un NBT hostile
[ ] aucune fuite (JVM, native, déformation, GPU) après les campagnes d'endurance
[ ] une assembly intacte n'alloue aucune mémoire de champ, n'exécute aucun pas
    de simulation de déformation et n'emprunte aucune variante de shader déformée
[ ] le coût GPU de la déformation d'un mesh déformé est mesuré et budgété (B-25)

INTÉGRITÉ
[ ] aucune écriture hors du NBT AXION et de <gameDir>/axion/
[ ] un monde créé avec AXION se charge sans AXION
[ ] retrait puis réinstallation restaure les assemblies et leur déformation
[ ] aucun comportement vanilla modifié

RÉSEAU
[ ] la déformation est répliquée par événements, sans donnée par sommet ni par nœud
[ ] le client reconstruit exactement le champ du serveur
[ ] une divergence est détectée par empreinte et corrigée par instantané
[ ] le client ne produit jamais de déformation plastique de sa propre initiative
[ ] le trafic est budgété et mesuré

COMPATIBILITÉ
[ ] rendu correct avec et sans shaderpack
[ ] démarrage et jeu avec mods d'optimisation de rendu, mods natifs, 100 mods
[ ] échec simulé de chaque Mixin -> démarrage et dégradation propre
[ ] coexistence RUSTFORGE-X simulée : tous les tests verts
[ ] coexistence RUSTFORGE-X réelle : verte ou explicitement non vérifiée

INDÉPENDANCE
[ ] aucun fichier, module ou dépendance de RUSTFORGE-X dans le dépôt
[ ] aucune déclaration de dépendance dans mods.toml
[ ] aucune classe de RUSTFORGE-X chargée ou référencée hors de C-76 (réflexif)
[ ] AXION identique avec integration.rustforgex.mode = off
[ ] aucune dépendance circulaire

PROCESSUS
[ ] build reproductible bit à bit depuis un dépôt propre
[ ] CI verte sur tous les jobs obligatoires, dont det-vectors et lint-budgets
[ ] tous les benchmarks B-01..B-30 exécutés et archivés
[ ] aucun chiffre non mesuré publié
[ ] SBOM, NOTICE et licences complets
[ ] documentation complète, générée sans divergence
[ ] aucune fonctionnalité EXPERIMENTAL activée par défaut
[ ] aucun TODO/FIXME/placeholder dans un module STABLE ou EXPERIMENTAL
[ ] scénario de bout en bout T-970 et son variant T-971 verts
```

## 38.2 Critères de correction (bloquants) — identifiants `AC-xx`

```text
AC-01  aucune mutation d'état Minecraft hors du thread autoritatif      (INV-03)
AC-02  aucun appel OpenGL hors du render thread                         (INV-12)
AC-03  aucune panic Rust traversant la frontière FFI                    (INV-05)
AC-04  aucun accès à un handle libéré                                   (INV-09)
AC-05  aucune allocation native non comptée                             (INV-08)
AC-06  aucune donnée AXION dans les fichiers de région du monde          (INV-10)
AC-07  aucune confiance accordée au client                              (INV-02)
AC-08  aucun nom de mod ou de contenu dans la logique du moteur          (INV-01)
AC-09  aucune dépendance à RUSTFORGE-X hors de C-76                     (INV-06)
AC-10  aucun JNIEnv attaché sur un worker natif                         (INV-07)
AC-11  aucun collider dynamique en trimesh, même déformé                (INV-13)
AC-12  noyau déterministe bit-identique client/serveur dans la matrice de
       validation ; hors matrice, bascule SNAPSHOT signalée              (INV-14)
AC-13  aucune donnée de déformation par sommet sur le réseau            (INV-15)
AC-14  assembly intacte : aucune mémoire de champ, aucun pas de simulation de
       déformation, aucune variante de shader déformée                  (INV-16)
AC-15  aucune déformation plastique créée par le client                 (INV-17)
AC-16  toute déformation bornée par max_disp et par le budget mémoire   (INV-18)
AC-17  aucun sous-système sans budget déclaré et mesuré                 (INV-19)
```

## 38.3 Critères de performance — identifiants `PF-xx`

```text
PF-01  coût des hooks à vide mesuré et sous budgets.idle_hook_ns
PF-02  appels FFI par tick mesurés et sous le seuil                     (INV-04)
PF-03  aucune allocation dans les boucles chaudes après échauffement,
       y compris la boucle de déformation
PF-04  scaling multithread mesuré et publié pour B-01, B-04, B-18, B-19
PF-05  budgets respectés, ou baisse de niveau puis dégradation, journalisées
PF-06  aucune régression > 20 % (p95) sur les benchmarks de référence
PF-07  tous les chiffres publiés proviennent de benchmarks/results/
PF-08  coût de la déformation mesuré et archivé pour chacun des 5 niveaux
PF-09  trafic réseau de déformation mesuré et proportionnel aux impacts,
       jamais à la complexité géométrique
PF-10  taille de persistance mesurée et proportionnelle au dommage réel
```

## 38.4 Critères d'extensibilité — identifiants `EX-xx`

```text
EX-01  un mod tiers crée un objet physique déformable complet sans modifier AXION
EX-02  un créateur sans compétence Java livre un véhicule cabossable
       (GLB + JSON dans un datapack/resourcepack)
EX-03  un GLB SANS AUCUNE ANNOTATION devient déformable et destructible
       grâce aux générations automatiques de C-28
EX-04  un mod tiers enregistre matériau, profil d'usure, règle de réparation,
       source de dégât, source procédurale, contrôleur, distributeur de dommage,
       noyau de déformation, hook de rendu, importer
EX-05  l'API publique est versionnée, documentée, binaire-compatible en 1.x
EX-06  un point d'extension défaillant est isolé et sa cause nommée
EX-07  un noyau de déformation tiers non déterministe bascule automatiquement
       sur l'envoi d'instantanés, sans casser le jeu
```

## 38.5 Audit final obligatoire — résultats

| # | Point d'audit | Résultat |
|---|---|---|
| 1 | Relecture intégrale | **fait** |
| 2 | Contradictions | **corrigées** — 38.6 |
| 3 | Dépendances | cohérentes, licences compatibles, alternatives évaluées ; graphe acyclique |
| 4 | Java / Rust | frontière exhaustive (4.1), aucun chevauchement |
| 5 | FFI | ABI, propriété, durée de vie, erreurs, panics, threading, nouveaux contrats de déformation (IF-04) |
| 6 | Client / serveur | responsabilités séparées, serveur sans code client, autorité serveur y compris pour le plastique et la structure |
| 7 | Rendu | cadre réel établi, PBR justifié, ombres, occlusion, décalques, déformation, deux backends, discipline d'état GL |
| 8 | Physique | générique, sans cas particulier, limites dures, déterminisme cadré, refit sans trimesh |
| 9 | Assets | pipeline complet, compilation de déformation et de structure, validation défensive, cache, fuzzing |
| 10 | Formats 3D | évalués et arbitrés ; FBX exclu avec motif juridique ; OBJ rendu déformable par génération automatique |
| 11 | Blender | workflow, `extras`, addon avec visualisation, validation partagée et aperçu d'impact via le même code |
| 12 | Networking | protocole, quantification, delta, priorités, réplication par événements, empreintes, instantanés, sécurité |
| 13 | Performance | budgets, niveaux de qualité, gouverneur, dégradation ; aucun chiffre inventé |
| 14 | Tests | pyramide, catalogue étendu, scénario de bout en bout obligatoire, interdiction de désactivation |
| 15 | Benchmarks | méthodologie, 30 benchmarks dont 13 nouveaux, mesure par niveau de qualité |
| 16 | Build | chaîne, plateformes, reproductibilité, JAR partiel bloqué, job de vecteurs déterministes |
| 17 | Sécurité | entrées hostiles dont impacts et instantanés, limites dures, `unsafe` confiné, confinement |
| 18 | Critères d'acceptation | par composant, par jalon, globaux, avec AC/PF/EX étendus |
| 19 | Périmètre V1.0 | fermé ; audit complet des limitations en 36.4 |
| 20 | Indépendance du projet | INV-06, R-1980, R-2330, ADR-015 |
| 21 | Compatibilité RUSTFORGE-X | PARTIE 27 dédiée, y compris section informative sur l'optimisation possible |
| 22 | Installation simultanée | testée (T-663..T-671, T-676 sans artefact ; T-672..T-675 avec) |
| 23 | Dépendance circulaire | impossible par construction |
| 24 | Absence de RUSTFORGE-X | fonctionnement complet, aucune dégradation |
| 25 | Présence de RUSTFORGE-X | fonctionnement complet ; seules des heuristiques de ressources changent, désactivables, et **aucune fonctionnalité n'est retirée** |
| 26 | Aucune référence à un composant inexistant | vérifié : C-01..C-83 tous définis et référencés |
| 27 | Aucun test référençant une fonctionnalité non spécifiée | vérifié : chaque plage T-xxx correspond à une partie spécifiée |
| 28 | Aucune fonctionnalité STABLE sans fallback | vérifié (fallbacks listés par fiche) |
| 29 | Aucun système sans budget | INV-19 + job `lint-budgets` |
| 30 | Aucune fonctionnalité réseau sans stratégie client/serveur | vérifié (2.7 et PARTIE 21) |
| 31 | Aucune fonctionnalité physique sans autorité définie | vérifié (2.7) |
| 32 | Aucune fonctionnalité de rendu sans stratégie de compatibilité | vérifié (19.2, 26.5) |
| 33 | Aucune fonctionnalité de persistance sans schéma | vérifié (PARTIE 22, `docs/schema/`) |
| 34 | Déformation : stratégie mémoire, CPU et GPU | vérifié (14.11, 14.6) |
| 35 | Dommage : synchronisation | vérifié (21.6) |
| 36 | Destruction : gestion des ressources | vérifié (15.4, 15.6) |
| 37 | Cohérence dimensionnelle de toutes les équations | vérifié : table normative 11.5, modèle d'indentation 13.3bis, aire de liaison 15.1bis, job CI `lint-units`, tests T-830b..h, T-806b..e, T-810b..c, T-840b |
| 38 | Portée des termes « coût nul », « identique », « déterministe », « autoritatif », « fallback » | bornée explicitement : 14.11bis (coût), 5.12bis (déterminisme), 19.2bis (équivalence de rendu), 17.2bis (autorité), 22.5 (retrait du mod) |
| 39 | Autorité en présence d'une simulation visuelle client plus fine | vérifié : 17.2bis, R-1373, R-1374, T-938b..f |
| 40 | Survie et récupérabilité des données après retrait du mod | vérifié : journal latéral 22.5.2, matrice 22.5.3, T-713b..f |

## 38.6 Contradictions détectées et résolutions

| # | Contradiction potentielle | Résolution |
|---|---|---|
| 1 | « Rust fait le rendu » vs contexte GL détenu par la JVM | ADR-006 : Rust prépare, Java soumet |
| 2 | Instancing/compute/indirect acquis vs GL 3.2 minimal | H-03, H-05 : chemins accélérés optionnels, base GL 3.3 obligatoire et testée |
| 3 | Pipeline GL dédié vs shaderpacks | ADR-007 : deux backends, bascule automatique |
| 4 | Physique serveur vs réactivité du pilote | prédiction du seul véhicule piloté, **sans plastique prédit** (R-1681) |
| 5 | Tout simuler vs budget de tick partagé | budgets, niveaux de qualité, gouverneur, mode SAFE jouable |
| 6 | Collision monde précise vs interdiction de charger des chunks | section non chargée = solide, conservateur et documenté |
| 7 | **Déformation continue vs coût** | ADR-011 : degrés de liberté du lattice, pas du maillage ; 5 niveaux ; coût de simulation et de mémoire nul si intact, coût GPU explicitement comptabilisé et budgété (14.11bis) |
| 8 | **Déformation visuelle vs stabilité physique** | P-14, INV-13, ADR-019 : refit convexe budgété, écart toléré déclaré |
| 9 | **Déformation vs réseau** | ADR-018 : réplication par événements, empreinte, instantané de secours |
| 10 | **Déformation vs persistance** | champ i8 quantifié compressé, jamais de géométrie |
| 11 | **Déformation vs skinning** | ordre figé skinning → déformation (R-1461), avec avertissement de validation |
| 12 | **Déformation vs LOD** | le plastique reste appliqué à toute distance (R-1250) |
| 13 | **Déformation vs déterminisme physique non exigé** | le déterminisme est exigé du seul noyau de déformation (fonction pure), pas de la physique (ADR-005 + ADR-018) |
| 14 | Déchirure vs absence de retopologie | 14.10 : séparation par déplacement et matériau de bord ; la vraie séparation passe par les parts précompilées |
| 15 | PBR vs éclairage non physique de Minecraft | ADR-008 : environnement synthétisé + `lightmapFactor` |
| 16 | Ombres vs non-altération du monde vanilla | ADR-021 : ombres appliquées aux seuls objets AXION |
| 17 | SSR vs LDR et shaderpacks | EXPERIMENTAL, Q-4, désactivé sous shaderpack, repli sur la sonde |
| 18 | Cloth avec effet gameplay vs coût serveur | ADR-012 : proxy `SERVER_SIMPLE` de quelques dizaines de particules |
| 19 | Datapacks puissants vs sécurité | ADR-009 : données déclaratives, listes fermées, aucune exécution |
| 20 | Un `EntityType` unique vs richesse du contenu | ADR-010 : contenu porté par la definition |
| 21 | Générique vs bibliothèque de matériaux fournie | la bibliothèque est du **contenu de datapack**, le moteur en ignore les noms |
| 22 | Extension tierce du noyau de déformation vs déterminisme | EX-07 : bascule automatique sur instantanés, avertissement nommant le mod |
| 23 | Gouverneur de qualité vs correction serveur | R-841 : liste fermée de paramètres sans effet sur la correction, testée (T-983) |
| 24 | Partage CPU avec RUSTFORGE-X vs non-réduction de fonctionnalités | R-2062 : le temps s'allonge, le gouverneur ajuste les niveaux ; rien n'est retiré |
| 25 | Test de coexistence obligatoire vs artefact tiers indisponible | 27.10 : mod de simulation toujours exécuté + test réel quand disponible, statut publié honnêtement |
| 26 | Scale non uniforme utile en rendu vs invalide en collision | R-120 |
| 27 | Compilation d'assets en jeu vs démarrage rapide | barrière bornée + `.a3d` précompilés recommandés |
| 28 | Blocs déformables vs collision vanilla structurante | R-722 : géométrie déformable, `VoxelShape` déclarée par étape |
| 29 | Débris récursifs vs risque d'explosion combinatoire | générations bornées, plafonds, éviction FIFO |
| 30 | Mode SAFE vs perte de dommage | R-1891 : impacts conservés et traités à débit réduit |
| 31 | **Énergie × aire pour une capacité de rupture** | corrigé : `fracture_energy` est une énergie **spécifique** en J/m² ; `capacity [J] = fracture_energy [J/m²] × A_link [m²]` (11.5, C-28) |
| 32 | **Intersection d'AABB décrite comme une aire** | corrigé : l'intersection est un volume ; l'aire de liaison est une **section** explicitement définie, ou une **intersection de projections** (15.1bis) |
| 33 | **Aire de contact en puissance 2/3 dimensionnellement fausse** | corrigé : modèle d'indentation plastique, `A = 2·sqrt(pi·R_eff·V_ind)` (13.3bis) — au passage, `powf` disparaît d'un chemin déterministe, ce qui levait aussi une contradiction avec R-510 |
| 34 | **`deformation_resistance` nommée « résistance » mais utilisée en multiplicateur** | corrigé : c'est un **diviseur** sans dimension (11.3, 14.4, T-810c) |
| 35 | **« Coût nul » de la déformation vs coût GPU réel du vertex shader** | corrigé : quatre coûts distingués en 14.11bis ; la garantie porte sur l'état, la mémoire et la simulation, jamais sur le rendu, qui est mesuré et budgété (B-25) |
| 36 | **Garantie déterministe formulée comme universelle** | corrigé : bornée à la matrice de validation ; hors matrice, mode `SNAPSHOT` explicite et signalé (5.12bis) |
| 37 | **Client plus détaillé que le serveur vs autorité** | corrigé : 17.2bis définit l'autorité par domaine, la reconstruction visuelle et l'interdiction absolue de rétroaction (T-938b..f) |
| 38 | **Chargement sans AXION vs préservation des données** | corrigé : le comportement vanilla est nommé, un journal latéral hors monde rend la perte récupérable, matrice de 6 cas (22.5) |
| 39 | **Équivalence des backends formulée comme visuelle** | corrigé : équivalence fonctionnelle et géométrique, matrice de capacités, désactivation propre et signalée (19.2bis) |

Aucune contradiction non résolue ne subsiste.

## 38.7 Registre des changements de la révision 2

```text
AJOUTÉS   C-16 noyau déterministe, C-28 compilateur de déformation et structure,
          C-41 solveur d'impacts, C-42 moteur de déformation, C-43 intégrité
          structurelle, C-44 rupture et détachement, C-45 refit de collider,
          C-46 réparation, C-47 usure de surface, C-48 attaches, C-68
          déformation GPU, C-69 décalques, C-77 gouverneur de qualité,
          C-80 ombres, C-81 sonde d'environnement, C-82 occlusion logicielle,
          C-83 effets intégrés
ÉTENDUS   C-22/C-23 (validation et LOD de déformation), C-31 (données de contact
          enrichies), C-32 (points d'enveloppe), C-33 (couplage dommage),
          C-34 (grippage), C-35 (modèle complet), C-36 (solveur unifié,
          3 autorités), C-37 (couplage), C-38 (matériaux de bloc), C-50
          (conversion des dégâts vanilla), C-51 (réplication de déformation),
          C-52 (persistance de champ), C-54/C-55 (déformables), C-61
          (déformation CPU), C-63 (variantes), C-64 (LOD de déformation),
          C-66 (ordre skinning/déformation), C-67 (overlays), C-74 (deform,
          replay), C-75 (visualisation, aperçu d'impact)
ÉTENDUS   DM-05 (matériaux avancés), DM-07 (modèle de matière complet),
 (données) DM-11 (parts, sous-zones, structure), DM-12 (régions et champs),
          DM-13 (impacts), DM-14 (liaisons), DM-15 (usure et décalques),
          DM-16 (particules), DM-17 (attaches), DM-18 (qualité et budgets),
          Vertex (region, def_w sans changer la taille)
AJOUTÉS   INV-13..INV-19
AJOUTÉS   ADR-018..ADR-026 ; ADR-008, ADR-011, ADR-012 révisés
AJOUTÉS   PARTIE 11 (matière), 13 (impacts), 14 (déformation), 15 (structure),
          16 (réparation), 20 (surfaces) ; PARTIE 17, 19, 21, 22, 23, 24, 25
          fortement étendues
AJOUTÉS   B-18..B-30 ; T-800..T-999 ; S-13..S-17 ; jobs CI det-vectors,
          lint-budgets, lint-no-global-pool, test-e2e
LEVÉES    toutes les limitations artificielles listées en 36.4
SUPPRIMÉ  rien
```

## 38.8 Registre des corrections de la révision 2.1

Cette révision ne corrige que des **incohérences internes**. Elle n'ajoute ni ne
retire aucune fonctionnalité, aucun composant, aucun jalon.

### Problèmes corrigés

| # | Problème | Nature | Correction |
|---|---|---|---|
| 1 | `capacity = break_energy [J] × aire [m²]` | dimension fausse | `break_energy` devient `fracture_energy` en **J/m²** ; `capacity [J] = fracture_energy × A_link [m²]` |
| 2 | « aire d'intersection des AABB » | une intersection de boîtes est un **volume** | méthode explicite 15.1bis : section du recouvrement (cas 1) ou intersection de projections (cas 2), avec bornes et priorité à la valeur déclarée |
| 3 | `A = (E / (stiffness · penetration_ref))^(2/3)` | dimension fausse (m^4/3) **et** `powf` interdit en chemin déterministe | modèle d'indentation plastique 13.3bis : `V = E/H`, `A = 2·sqrt(pi·R·V)`, en `+ - * / sqrt` seulement |
| 4 | `deformation_resistance` nommée « résistance », utilisée en multiplicateur | sémantique contradictoire | devient un **diviseur** sans dimension, borné [0.01, 100] |
| 5 | « coût nul » de la déformation | portée trop large : ignore le coût GPU par sommet | 14.11bis : quatre coûts distingués (état/simulation, mémoire, compilation, rendu GPU) ; la garantie porte sur les trois premiers |
| 6 | garantie déterministe formulée comme universelle | portée non bornée | 5.12bis : matrice de validation déterministe, `det_profile` échangé au handshake, bascule `SNAPSHOT` hors matrice, jamais de refus de fonctionner |
| 7 | client visuellement plus fin que le serveur | autorité insuffisamment explicite | 17.2bis : table d'autorité par domaine, reconstruction visuelle normative, interdiction absolue de rétroaction, dérive bornée et mesurée |
| 8 | « monde chargeable sans AXION » | vrai mais incomplet : vanilla peut perdre les entités inconnues | 22.5 : constat technique nommé, **journal latéral** hors monde, matrice de 6 cas, restauration idempotente |
| 9 | « les deux backends produisent la même image » | promesse intenable | 19.2bis : équivalence **fonctionnelle et géométrique**, matrice de capacités, désactivation propre et signalée |

### Sections ajoutées

```text
11.5     Cohérence dimensionnelle (table normative de toutes les grandeurs)
13.3bis  Modèle d'indentation plastique (hypothèses, formulation, bornes)
14.11bis Modèle de coût : portée exacte des garanties
15.1bis  Estimation de l'aire de liaison
17.2bis  Autorité et reconstruction visuelle
19.2bis  Portée de l'équivalence entre backends
5.12bis  Portée de la garantie déterministe (dans la fiche C-16)
22.5.1 à 22.5.3   Constat technique, journal latéral, matrice de comportement
38.8     Le présent registre
```

### Équations modifiées

```text
capacity, tensile, shear, torque        C-28 étape 11        -> dimensions correctes
A (aire de contact d'impact)            C-41 étape 4         -> modèle d'indentation
depth (profondeur de déformation)       14.4 étape 1         -> cohérent avec C-41
A_link, r_link                          15.1bis              -> nouvelles, définies
```

### Exigences ajoutées ou modifiées

```text
AJOUTÉES   R-1035..R-1038 (dimensions), R-1155..R-1158 (indentation),
           R-1296..R-1299 (coût), R-1315..R-1317 (aire de liaison),
           R-1373..R-1377 (autorité visuelle), R-1493..R-1496 (backends),
           R-514..R-517 (portée déterministe), R-1743..R-1749 (retrait du mod)
MODIFIÉES  R-510, R-511, R-513 (discipline arithmétique et matrice),
           R-740, R-1492 (équivalence de rendu), R-1242, R-1260, R-1294 (coût),
           R-1372 (autorité SERVER_SIMPLE), R-1740..R-1742 (retrait du mod)
```

### Invariants modifiés

```text
INV-14  portée bornée à la matrice de validation ; comportement hors matrice défini
INV-16  portée précisée : état, simulation et mémoire, jamais le rendu GPU
Aucun invariant supprimé ; aucun invariant affaibli sur le fond.
```

### Tests ajoutés

```text
T-830b..h  dimensions, pénétration nulle, faible, croissante, bornes, stabilité
           numérique, SHARP/BLUNT
T-840b     unités et plages physiques des matériaux
T-806b..e  dimensions de liaison, aire cas 1, aire cas 2, bornes et priorité
T-810b..c  cohérence C-41/C-42, deformation_resistance en diviseur
T-808b     libération du champ après réparation complète
T-820b..d  hors matrice, det_profile divergents, divergence injectée
T-938b..f  audit statique de non-rétroaction, divergence visuelle sans divergence
           gameplay, dérive plafonnée, gel visuel, correspondance précompilée
T-713b..f  restauration depuis le journal, journal absent ou corrompu,
           idempotence, definition absente, asset absent
T-491b..d  basculement sans effet sur simulation/persistance/réseau/gameplay,
           silhouette sous tolérance, matrice de capacités signalée
Aucun test supprimé ; T-491, T-808, T-901, T-910 reformulés.
```

### ADR modifiés

```text
ADR-007  précision : équivalence fonctionnelle et géométrique, non pixellaire
ADR-018  précision : portée de la garantie bit-identique
Aucun ADR annulé, aucune décision d'architecture renversée.
```

### Vérification de non-régression

```text
[x] aucune fonctionnalité supprimée
[x] aucun composant supprimé (68 composants, inchangés)
[x] aucune ambition fonctionnelle réduite
[x] aucune partie déplacée vers V2 ou vers V1.x
[x] aucune nouvelle dépendance obligatoire introduite
[x] aucune contradiction introduite
[x] unités physiques cohérentes (11.5, job CI lint-units)
[x] autorité serveur/client cohérente (17.2bis)
[x] persistance cohérente (22.5, journal latéral hors monde, INV-10 respecté)
[x] déterminisme correctement borné (5.12bis)
[x] fallback renderer correctement défini (19.2bis)
[x] R-1630 inchangée : AXION reste obligatoire côté client en réseau
[x] INV-10 inchangé : le journal est hors du monde
```

## 38.9 Registre des amendements postérieurs au gel

Le document reste gelé : un amendement ne s'y inscrit que ratifié, avec la décision qui le
porte. Il ajoute ce qui manquait à une exigence existante ; il ne retire ni ne reporte rien.

| # | Date | Décision | Amendement |
|---|---|---|---|
| A1 | 2026-10-04 | ADR-123, ratifié par le mainteneur | ANNEXE A.3, `[physics]` : `entity_push` et `entity_damage`, booléens, vrais par défaut — le réglage que R-614 exige (« l'effet d'une collision sur une entité vanilla … est configurable ») et que l'annexe ne portait pas |

---

---

# ANNEXES

## ANNEXE A.1 — Codes d'erreur

| Code | Domaine | Cause | Effet | Remède |
|---|---|---|---|---|
| `E-1001` | bootstrap | Forge hors plage | `DISABLED` | installer Forge 47.x supporté |
| `E-1002` | bootstrap | ABI incompatible | `DISABLED` | réinstaller le JAR complet |
| `E-1003` | natif | SHA-256 invalide | `DISABLED` | retélécharger |
| `E-1004` | natif | double initialisation | refusé | défaut interne |
| `E-1010` | forge | hook désactivé après 5 échecs | fonctionnalité réduite | consulter le log |
| `E-1050` | arrêt | thread non terminé | abandon | rapporter avec le dump |
| `E-2000` | runtime | panic capturée | opération annulée | rapporter |
| `E-2001` | runtime | handle invalide | refusé | défaut interne |
| `E-2002` | ffi | tampon invalide ou périmé | refusé | défaut interne |
| `E-2003` | runtime | ressource réclamée par le GC sans libération | fuite comptée | rapporter |
| `E-2004` | mémoire | budget natif dépassé | déchargement puis refus | augmenter le budget ou réduire le contenu |
| `E-2010` | runtime | quaternion non normalisable | rejeté | corriger l'asset |
| `E-2020` | physique | trimesh sur body dynamique | assembly refusée | utiliser `auto_convex` |
| `E-2030` | physique | état non fini | body restauré, sommeil forcé | signaler le scénario |
| `E-3001` | assets | asset requis indisponible au démarrage | definitions désactivées | vérifier le resource pack |
| `E-3002` | assets | URI externe ou chemin sortant | refus | embarquer les ressources |
| `E-3003` | assets | extension glTF requise non supportée | refus | réexporter |
| `E-3004` | assets | format d'image non supporté | texture refusée | convertir en PNG |
| `E-3005` | assets | source trop volumineuse | refus | réduire |
| `E-3006` | assets | texture > 4096² | refusée | réduire |
| `E-3007` | assets | section A3D corrompue | asset invalidé | vider le cache |
| `E-3008` | assets | version majeure A3D inconnue | refus | recompiler |
| `E-3009` | assets | annotation incohérente | refus | corriger les `extras` |
| `E-3010` | assets | collision d'identifiant | refus | renommer |
| `E-3020..E-3060` | assets | violations de validation | refus | corriger le modèle |
| `E-4001` | rendu | échec de compilation de shader | bascule vanilla | rapporter le log GL |
| `E-4002` | rendu | pool de pages de déformation saturé | instances non déformées cette frame | réduire la qualité |
| `E-5001` | réseau | paquet invalide | ignoré | rapporter si récurrent |
| `E-5002` | réseau | lot d'impacts invalide | ignoré, instantané demandé | rapporter si récurrent |
| `E-6001` | persistance | blob personnalisé trop grand | non écrit | réduire |
| `E-6002` | persistance | blob d'assembly au-delà du plafond | sous-échantillonnage puis abandon d'usure/décalques | augmenter le plafond |
| `E-7001` | definitions | definition invalide | refusée | corriger le JSON |
| `E-7002` | definitions | node physique sous parent animé | refusée | revoir la hiérarchie |
| `E-7003` | definitions | schéma inconnu | refusée | mettre à jour |
| `E-8001` | déformation | budget mémoire de déformation dépassé | nouvelle déformation refusée | augmenter le budget ou baisser la qualité |
| `E-8010` | déformation | région dégénérée | asset refusé | corriger la région |
| `E-8011` | structure | graphe structurel cyclique | asset refusé | corriger les liaisons |
| `E-8020` | dommage | impact non fini ou aberrant | rejeté | rapporter |
| `E-8030` | attaches | cycle d'attaches | refusé | revoir la chaîne |

## ANNEXE A.2 — Index des invariants

```text
INV-01  aucun nom de mod/contenu dans le moteur            (T-006, lint-no-mod-names)
INV-02  client jamais autoritatif                          (T-417, T-442, T-826)
INV-03  mutation Minecraft sur le thread autoritatif        (T-011)
INV-04  < 32 traversées FFI par tick                        (T-012)
INV-05  aucune panic traversant la FFI                      (T-013)
INV-06  aucune dépendance à RUSTFORGE-X hors C-76           (T-014)
INV-07  aucun JNIEnv sur un worker natif                    (T-015)
INV-08  toute allocation native comptée                     (T-016)
INV-09  handle libéré = handle invalidé                     (T-017)
INV-10  aucune donnée AXION dans les régions du monde       (T-018)
INV-11  retrait du mod = monde chargeable                   (T-435, T-713)
INV-12  aucun appel GL hors du render thread                (T-019)
INV-13  aucun collider dynamique en trimesh, même déformé   (T-855)
INV-14  noyau déterministe bit-identique DANS LA MATRICE
        de validation ; hors matrice -> mode SNAPSHOT      (T-820, T-820b, T-821)
INV-15  aucune donnée de déformation par sommet en réseau   (T-825)
INV-16  assembly intacte : ni mémoire de champ, ni simulation
        de déformation, ni variante de shader déformée      (T-808, T-808b)
INV-17  aucune déformation plastique côté client            (T-826)
INV-18  déformation bornée (amplitude et mémoire)           (T-809)
INV-19  aucun sous-système sans budget                      (T-007)
```

**Machines à états.** `SM-01` cycle de vie d'un asset (5.13) ; `SM-02` dégradation (25.6) ; `SM-03` états de node (9.2) ; états du runtime (`READY | DEGRADED | SAFE | POISONED | DISABLED`) ; cycle de vie de C-01 (5.1) ; étapes de part (13.3) ; niveaux de qualité (24.2).

## ANNEXE A.3 — Configuration complète (référence normative)

```toml
# axion-common.toml
[general]
enabled = true
maturity_allow_experimental = false

[sim]
fixed_dt = 0.0166667              # 0.0333333 | 0.0166667 | 0.0083333
max_substeps = 4                  # 1..8
simulation_radius = 128.0         # 16..512

[physics]
gravity = -9.81                   # -50..0
velocity_iterations = 4           # 2..16
position_iterations = 1           # 0..8
broadphase_cell_size = 2.0        # 0.5..16
contact_event_threshold = 0.5     # 0..100
max_events_per_tick = 4096        # 256..65536
entity_push = true
entity_damage = true

[damage]
enabled = true
max_impacts_per_tick = 512        # 32..8192
min_impact_energy = 5.0           # 0..1000
max_impact_energy = 5000000.0     # 1000..1e9
max_propagation_depth = 4         # 0..8
max_assemblies_per_tick = 64      # 4..1024
pending_queue_max = 4096
max_detach_per_tick = 4           # 0..64
max_debris = 96                   # 0..512
max_debris_generation = 2         # 0..4
debris_lifetime_s = 90            # 5..1200
debris_sleep_s = 5
persist_debris = false
explosion_sample_area = 0.5       # m² par point d'échantillonnage
default_sharp_factor = 8.0        # 1..64,  réduit R_eff (13.3bis)
default_blunt_factor = 2.0        # 1..16,  augmente R_eff
default_shear_ratio = 0.6         # 0..1,   shear = tensile * ce facteur

[struct]
max_gap = 0.05                    # m, 0..0.5 ; au-delà, aucune liaison générée
max_link_area = 4.0               # m², 0.01..64
auto_links = true

[deformation]
enabled = true
quality = "auto"                  # auto | off | low | medium | high | ultra
max_field_bytes_per_assembly = 262144
max_residual_vertices = 4096
collider_refit_threshold = 0.04   # m, 0.005..0.25
mass_update_threshold = 0.05      # fraction
elastic_release_s = 5.0
tear_enabled = false              # EXPERIMENTAL
tear_max_cells_per_region = 0.08  # fraction

[particles]
enabled = true
quality = "auto"
max_sets = 48                     # client ; serveur : max_sets_server
max_sets_server = 16
self_collide = false              # EXPERIMENTAL
tear = false                      # EXPERIMENTAL
visual_drift_max = 0.25           # m, 0.02..2 ; dérive visuelle maximale (17.2bis)
visual_freeze_ms = 500            # 100..5000 ; gel visuel sans état serveur

[budgets]
sim_ns_per_tick = 3000000
damage_ns_per_tick = 1000000
deformation_ns_per_tick = 1500000
particles_ns_per_tick = 1000000
render_prep_ns = 2000000
occlusion_ns = 800000
asset_ns_per_tick = 1000000
idle_hook_ns = 50000
submit_ns = 200000
native_mem_bytes = 536870912
deform_mem_bytes = 134217728
gpu_mem_bytes = 536870912
max_active_bodies = 2048
max_deformed_assemblies = 256
max_collider_refits_per_tick = 8
max_visible_instances = 512
max_triangles_frame = 3000000
max_decals_frame = 512
max_shadow_instances = 128

[jobs]
max_workers = 0                   # 0 = automatique

[assets]
max_source_bytes = 134217728
max_compiled_bytes = 268435456
max_deform_bytes = 4194304
max_compile_ms = 30000
cache_max_bytes = 2147483648
startup_timeout_s = 120
unload_delay_s = 60

[world]
tiles_per_tick = 8                # 1..64
tile_radius = 3                   # 1..8

[vehicle]
debris_ignore_s = 1.0
max_suspension_misalignment = 0.15

[attachment]
max_chain = 8

[net]
snapshot_rate_hz = 10             # 2..20
sync_radius = 96.0
interpolation_delay_ms = 100
extrapolation_max_ms = 250
snap_threshold = 2.0
prediction = true
prediction_max_ping = 400
max_bytes_per_second_per_player = 32768
max_packets_per_second = 40
max_impacts_per_packet = 64
digest_interval_ticks = 40
max_deform_snapshots_per_second = 4
max_deform_snapshots_per_second_fallback = 16   # mode SNAPSHOT (5.12bis)
divergence_tolerance = 3          # 1..32 ; bascule RECONSTRUCT -> SNAPSHOT

[persistence]
max_bytes_per_assembly = 65536
max_custom_bytes = 16384
max_persisted_decals = 16
max_load_ns_per_tick = 500000
journal_enabled = true            # journal latéral de récupération (22.5.2)
journal_ns_per_tick = 200000      # 0..2000000
journal_max_bytes = 268435456     # 16 MiB..8 GiB

[limits]
max_assemblies_per_dimension = 4096
max_spawn_per_command = 64

[watchdog]
job_timeout_ms = 5000

[modules]
vehicles = true ; joints = true ; damage = true ; deformation = true
structure = true ; repair = true ; wear = true ; particles = true
animation = true ; attachments = true ; blocks = true

[integration.rustforgex]
mode = "auto"                     # auto | off
cpu_share = "auto"                # auto | half | full | <entier>
bridge = true
hint_no_transform = true

[debug]
checksum_buffers = false
record_incidents = false
```

```toml
# axion-client.toml
[render]
backend = "auto"                  # auto | native | vanilla
max_distance = 128.0
lod_bias = 0                      # -2..3
instancing_threshold = 4
indirect = true                   # utilisé seulement si GL 4.3
compute = true                    # idem
max_skinned_instances = 64
max_palette_bones = 128
max_deform_pages_per_frame = 256
max_decals_per_assembly = 64
max_morph_targets_per_asset = 8
max_shader_variants = 64
vanilla_max_skinned_vertices = 50000
vanilla_max_deformed_vertices = 100000
vanilla_max_decals = 64
per_node_lightmap = false
item_max_triangles = 20000
shadow = "map"                    # map | contact | none
shadow_ground_quad = true
shadow_ns = 1500000
probe_interval_frames = 20
ssr_max_roughness = 0.25
max_occluders = 512
backend_silhouette_tolerance = 0.01   # 0..0.05 ; tolérance d'équivalence (19.2bis)

[quality]
deformation = "auto" ; particles = "auto" ; shadows = "auto" ; decals = "auto"
occlusion = "auto"   ; lighting  = "auto" ; skinning = "auto"
reflections = "auto" ; parallax  = "auto"

[overlay]
enabled = false
key = "unbound"

[debug]
gl = false
```

```toml
# axion-server.toml
[server]
allow_client_prediction = true
kick_on_input_abuse = true
allow_experimental_authority = false   # SERVER_FULL pour les particules
```

- R-2400 : cette annexe est **générée** depuis la source unique et vérifiée en CI ; une divergence entre code, documentation et annexe casse le build.

## ANNEXE A.4 — Sources procédurales (liste fermée V1.0)

```text
VÉHICULE      vehicle.speed | forward_speed | throttle | brake | steer | handbrake
              engine.rpm | load | running | transmission.gear | shifting
              wheel.<n>.spin_angle | steer_angle | compression | slip_long |
              slip_lat | on_ground | damage
CORPS         body.linear_speed | angular_speed | pitch | roll | yaw |
              on_ground | in_fluid
PART          part.<n>.health | integrity | stage | absorbed | jammed | revealed
DÉFORMATION   region.<n>.mean_disp | max_disp | max_strain | energy | version
              deform.<part>.mean_disp | max_disp | max_strain
STRUCTURE     link.<n>.integrity | broken
              structure.connected_parts_ratio
SURFACE       wear.<part>.scratch | soil | burn | rust
JOINT         joint.<n>.position | velocity | force | jammed
SOCKET        socket.<n>.misalignment
ATTACHE       attach.<n>.length | tension | attached
SIÈGE         seat.<n>.occupied
PARTICULES    particles.<n>.max_stretch | torn_ratio
TEMPS         time.seconds
VARIABLE      var.<n>   (définie par l'API ou par une action déclarative)
```

- R-2410 : l'ajout d'une source est une évolution de schéma versionnée.

## ANNEXE A.5 — Limitations connues de la V1.0

```text
- la déformation est un champ de lattice, pas une simulation FEM : elle est
  plausible et progressive, pas physiquement exacte
- la déchirure ne re-maille pas la géométrie ; la vraie séparation passe par les
  parts précompilées
- la collision suit la déformation avec un retard borné (seuil de refit)
- les mobs vanilla ne contournent pas les assemblies (atténuation EXPERIMENTAL)
- la collision des blocs AXION reste une VoxelShape déclarée
- la flottabilité est approchée par échantillonnage
- une section de chunk non chargée est traitée comme solide
- la physique n'est pas déterministe entre machines (le noyau de déformation l'est)
- le client doit posséder le mod pour rejoindre
- pas de pipeline HDR ni de post-processing plein écran
- SSR, déchirure, auto-collision, SERVER_FULL et le blocage de pathfinding sont
  EXPERIMENTAL : implémentés, testés, désactivés par défaut
- FBX, Collada, USD et PLY ne sont pas importés directement
- les performances dépendent du contenu ; seuls les budgets, les niveaux de
  qualité et la dégradation sont garantis, jamais un chiffre
- pas de support Fabric/NeoForge (l'abstraction C-01 le rend possible)
```

## ANNEXE A.6 — Glossaire complémentaire

| Terme | Définition |
|---|---|
| Assembly | instance runtime complète d'une definition |
| Part | pièce destructible : sous-arbre de nodes avec santé et intégrité |
| Région de déformation | volume paramétré (lattice) déformant la géométrie qu'il englobe |
| Champ | déplacements aux nœuds d'un lattice (plastique + élastique) |
| Élastique / plastique | temporaire / permanent |
| Liaison structurelle | arête du graphe de parts, avec capacité et seuils |
| Impact | événement physique enrichi (énergie, aire, masse effective) |
| Refit | recalcul d'un collider convexe après déformation |
| Débris | assembly issue d'un détachement |
| Usure | état de surface cumulatif à 4 canaux |
| Décalque | projection en espace objet d'une altération de surface |
| Sonde d'environnement | cubemap synthétisé servant d'IBL |
| Niveau de qualité | `Q-0` à `Q-4` |
| Gouverneur | composant ajustant les niveaux depuis les budgets mesurés |
| Noyau déterministe | opérations bit-identiques client/serveur dans la matrice de validation (5.12bis) |
| Empreinte de champ | hachage 64 bits détectant une divergence |
| Attache | liaison entre deux assemblies par sockets |

---

# FINAL V1.0 IMPLEMENTATION CONTRACT

## 1. Confirmations formelles

```text
1.  Le présent cahier des charges (révision 2) est GELÉ en V1.0.
2.  Il constitue la SOURCE DE VÉRITÉ UNIQUE du projet AXION ENGINE.
3.  Le projet est INDÉPENDANT de RUSTFORGE-X : dépôt, code, architecture,
    build, tests et releases propres.
4.  Le projet FONCTIONNE SANS RUSTFORGE-X, sans dégradation d'aucune sorte.
5.  Le projet DOIT FONCTIONNER avec RUSTFORGE-X installé simultanément.
6.  RUSTFORGE-X N'EST PAS une dépendance, ni obligatoire, ni optionnelle,
    ni déclarée dans mods.toml.
7.  Une INTÉGRATION OPTIONNELLE (C-76) peut être utilisée ; elle est réflexive,
    isolée, désactivable, et aucune fonctionnalité d'AXION n'en dépend.
8.  AUCUNE DÉPENDANCE CIRCULAIRE n'est autorisée ni possible par construction.
9.  L'agent de développement PEUT COMMENCER DIRECTEMENT au jalon M0.
10. AUCUNE NOUVELLE FONCTIONNALITÉ FONDAMENTALE n'est requise avant le démarrage.
11. TOUTE EXTENSION FUTURE est explicitement hors du périmètre V1.0 (PARTIE 36).
12. LES CRITÈRES D'ACCEPTATION de la PARTIE 38 constituent la définition de
    « terminé ».
13. LA DÉFORMATION CONTINUE DE GÉOMÉTRIE EST UNE FONCTIONNALITÉ V1.0
    OBLIGATOIRE, générique, et son absence rend la V1.0 non conforme.
```

## 2. Boucle de travail obligatoire

```text
READ SPEC -> INSPECT REPOSITORY -> PLAN -> IMPLEMENT -> BUILD -> TEST -> FIX
          -> BENCHMARK -> DOCUMENT -> PACKAGE -> RELEASE
```

```text
2.1  Avant d'écrire du code : lire la fiche du composant (PARTIE 5) et la partie
     dédiée s'il en a une.
2.2  Après chaque implémentation : builder ET exécuter les tests immédiatement.
2.3  Un travail n'est pas terminé tant que sa Definition of Done n'est pas cochée.
2.4  Ne jamais empiler plusieurs composants non testés.
2.5  Chaque jalon se termine par un JAR installable et jouable.
```

## 3. Interdictions absolues

```text
3.1  NE JAMAIS écrire TODO, FIXME, todo!(), unimplemented!(), "placeholder" ou
     une implémentation factice dans un module STABLE ou EXPERIMENTAL.
3.2  NE JAMAIS inventer un chiffre de performance.
3.3  NE JAMAIS écrire une branche conditionnelle sur un nom de mod, de véhicule
     ou de contenu dans le moteur.
3.4  NE JAMAIS SUPPRIMER OU RÉDUIRE UNE FONCTIONNALITÉ POUR RAISON DE COÛT.
     Ajouter un niveau de qualité, un budget, un LOD ou un fallback à la place.
3.5  NE JAMAIS muter l'état Minecraft hors du thread autoritatif.
3.6  NE JAMAIS émettre un appel OpenGL hors du render thread.
3.7  NE JAMAIS laisser une panic Rust traverser la frontière FFI.
3.8  NE JAMAIS faire un appel FFI par élément là où un lot est possible.
3.9  NE JAMAIS transformer un collider de body dynamique en trimesh.
3.10 NE JAMAIS transmettre de données de déformation par sommet ou par nœud
     en régime normal.
3.11 NE JAMAIS laisser le client produire de la déformation plastique autoritative.
3.12 NE JAMAIS utiliser une fonction transcendante ou un ordre d'itération
     instable dans le noyau déterministe.
3.13 NE JAMAIS faire confiance à une donnée venant du client, d'un asset, d'un
     datapack, d'un NBT ou du cache.
3.14 NE JAMAIS affaiblir un invariant INV-xx pour faire passer un test.
3.15 NE JAMAIS désactiver un test pour le faire passer.
3.16 NE JAMAIS déclarer une fonctionnalité STABLE ou EXPERIMENTAL sans ses tests,
     ses métriques, son budget, son fallback et sa documentation.
3.17 NE JAMAIS écrire de donnée AXION hors du NBT AXION et de <gameDir>/axion/.
3.18 NE JAMAIS ouvrir de connexion réseau sortante.
3.19 NE JAMAIS redistribuer Minecraft, Forge, un mod tiers ou un asset tiers.
3.20 NE JAMAIS ajouter une dépendance, un import ou un jar de RUSTFORGE-X.
3.21 NE JAMAIS supposer que RUSTFORGE-X est présent, absent, dans une version
     donnée, ou qu'il honore une indication d'AXION.
3.22 NE JAMAIS utiliser un pool de threads global partagé du processus.
3.23 NE JAMAIS générer de classe Java à l'exécution.
3.24 NE JAMAIS ajouter une cible Mixin hors du code Minecraft, ni rendre une
     injection obligatoire au démarrage.
3.25 NE JAMAIS bloquer le thread autoritatif sans deadline.
```

## 4. Obligations

```text
4.1  Tout composant expose ses métriques, son budget et son niveau de maturité.
4.2  Tout chemin optimisé a un fallback testé.
4.3  Toute fonctionnalité coûteuse a des niveaux de qualité tous implémentés.
4.4  Toute dégradation est journalisée, expliquée et réversible.
4.5  Toute donnée non prouvée est traitée comme le pire cas.
4.6  Tout commit cite ses identifiants : feat(C-42): ... [R-1220, T-810].
4.7  Toute option a défaut, plage, validation et documentation générée.
4.8  Tout nouveau code d'erreur est ajouté à l'ANNEXE A.1.
4.9  Toute structure persistée porte magic, version de schéma et CRC.
4.10 Toute allocation native est comptée dans un budget.
4.11 Toute divergence volontaire avec ce document exige un ADR daté.
4.12 Tout défaut corrigé donne lieu à un test de non-régression.
4.13 Toute release publie l'état réel de la vérification de coexistence.
4.14 Tout ajout au noyau déterministe est accompagné de vecteurs d'or.
```

## 5. Autonomie de décision

L'agent PEUT décider seul : structures internes non spécifiées, découpage en modules, noms internes, style, organisation des tests, micro-optimisations sans effet sémantique, ordre d'implémentation dans un jalon, bibliothèques Rust courantes sous réserve de licence, de SBOM et de non-contradiction avec un ADR.

L'agent NE DOIT PAS décider seul : modifier un contrat `IF-xx` ou l'ABI, modifier un `DM-xx` sans migration ni test de parité, modifier ou affaiblir un `INV-xx`, ajouter une dépendance structurante ou une bibliothèque native tierce, ajouter une cible Mixin, **réduire le périmètre V1.0**, modifier la licence, publier une release.

## 6. Gestion de l'incertitude

```text
6.1  Ambiguïté -> option la plus conservatrice pour la correction, + ADR.
6.2  Mesure manquante -> implémenter la mesure d'abord, jamais deviner.
6.3  Preuve de sûreté manquante -> ne pas activer, rester au comportement sûr.
6.4  Test instable -> défaut réel.
6.5  Gain non reproductible -> inexistant.
6.6  Hypothèse H-xx invalidée -> appliquer le repli prévu et documenter.
6.7  Fonctionnalité trop coûteuse -> ajouter un niveau de qualité, jamais la retirer.
6.8  Ne jamais demander « que dois-je coder maintenant ? » : la réponse se déduit
     du jalon courant (PARTIE 37) et de la Definition of Done.
```

## 7. Critère final

```text
Le travail est terminé lorsque, sur une machine vierge :

   git clone <repo>
   ./gradlew clean build

   produit axion-1.0.0-mc1.20.1-forge47.jar ;

   ce JAR se charge sur Minecraft 1.20.1 + Forge 47.x, client et serveur dédié ;
   un objet 3D physique, déformable et destructible est créé avec un seul GLB et
   un seul JSON, sans écrire de Java ;
   un véhicule est pilotable en multijoueur, se CABOSSE RÉELLEMENT à l'impact,
   perd des pièces qui deviennent des débris cabossés, révèle ses éléments
   internes, se raye, se salit, et se répare progressivement ;
   la déformation est visible chez tous les clients, identique au serveur,
   persiste après sauvegarde et rechargement, et se reflète dans la collision ;
   /axion status affiche budgets mesurés, niveaux de qualité et causes ;
   les benchmarks archivés existent pour B-01..B-30, dont la déformation à
   chacun de ses cinq niveaux ;
   le scénario de bout en bout T-970 et son variant T-971 sont verts ;
   la matrice de coexistence est verte, RUSTFORGE-X simulé et, s'il est
   disponible, RUSTFORGE-X réel ;
   la checklist de la PARTIE 38 est intégralement cochée ;
   et le retrait du JAR laisse le monde parfaitement chargeable et jouable.

Tant qu'un seul de ces points est faux, le projet n'est pas terminé.
```

---

**FIN DU CAHIER DES CHARGES AXION ENGINE V1.0 (RÉVISION 2.1) — FINAL / FROZEN**
