# Décisions d'architecture (ADR)

Un ADR fige une décision importante : son contexte, ce qui est choisi, ce qui est écarté et ce que ça change.
Une décision de contrat (ABI, protocole, sauvegarde, API publique) passe par un ADR que Killian accepte avant
le code. Les fichiers restent à plat, leurs chemins étant cités partout ; cet index les range par sujet,
le plus récent en tête.

Généré par `python tools/spec/adr_index.py` : ne pas éditer à la main.

## Capacités exigées et organisation

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-161](ADR-161.md) | L'organisation du dépôt | 2026-11-10 | accepté |
| [ADR-160](ADR-160.md) | Les capacités que le CDC ne prévoyait pas : inscrites, jamais oubliées | 2026-11-10 | accepté |

## Jalons et Definition of Done

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-146](ADR-146.md) | La Definition of Done de M3 se prononce sur un périmètre borné, composant par composant | 2026-10-17 | accepté |
| [ADR-144](ADR-144.md) | DoD de M3 : niveaux de qualité renvoyés, export cliente des métriques, tests hors liste attribués au jalon de leur composant | 2026-10-16 | accepté |
| [ADR-141](ADR-141.md) | Tests de M2 que le CDC ne décrit que par plages : T-240..T-244, T-280..T-284, T-400..T-404 | 2026-10-15 | accepté |
| [ADR-137](ADR-137.md) | La Definition of Done de M2 se prononce sur un périmètre borné, composant par composant | 2026-10-15 | accepté |
| [ADR-130](ADR-130.md) | T-310..T-312 attribués ; T-312 demande DM-08 sur la frontière (masse et centre de masse déclarés, R-622) et le marquage REFITTABLE (R-623) | 2026-10-12 | accepté |
| [ADR-128](ADR-128.md) | Tests de M3 qui ne peuvent pas se jouer en M3 : T-493, l'overlay de T-491d, T-308 et T-309 | 2026-10-10 | accepté |
| [ADR-125](ADR-125.md) | Attribution des tests T-370..T-375 (C-38) et T-380..T-384 (C-39) | 2026-10-06 | accepté |

## Sièges et véhicules

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-168](ADR-168.md) | La prédiction du véhicule piloté : monde prédit, corrections, rejeu | 2026-11-12 | accepté |
| [ADR-167](ADR-167.md) | Les paramètres d'une instance : réglage à chaud, causes composées, contrôleurs de véhicule | 2026-11-12 | accepté |
| [ADR-163](ADR-163.md) | Ce qui s'allume et bouge : état du véhicule, variables, touches libres, canaux pilotés | 2026-11-11 | accepté |
| [ADR-159](ADR-159.md) | Les véhicules : roues par rayon, motopropulseur, réplication et prédiction | 2026-11-09 | accepté |
| [ADR-158](ADR-158.md) | Les sièges, la montée et le canal des entrées du client | 2026-11-05 | accepté |

## Definitions et contenu

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-156](ADR-156.md) | Étapes 4 et 5 de C-27 : `CompiledDefinition` compilée par le natif, registre de session, ABI 12 | 2026-10-30 | accepté |
| [ADR-145](ADR-145.md) | Matériaux physiques et mappage bloc → matériau : le format des données de pack | 2026-10-16 | accepté |
| [ADR-134](ADR-134.md) | T-630..T-634 en M2 : les cinq definitions de démonstration existent, se chargent et se valident ; leur fonctionnement reste T-970 | 2026-10-13 | accepté |
| [ADR-132](ADR-132.md) | Les groupes de collision d'une definition passent par les `ColliderDesc` de `PHYS` | 2026-10-13 | accepté |
| [ADR-109](ADR-109.md) | Lecture du schéma de definition 1 | 2026-09-13 | accepté
- |

## Physique

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-152](ADR-152.md) | Rapier 0.35.3 vendorisé et corrigé : moteurs et friction résolus avec les axes bloqués, impulsion publiée par joint | 2026-10-22 | accepté |
| [ADR-151](ADR-151.md) | Les joints de M4 : une assembly porte plusieurs corps, reliés par les joints impulsionnels de Rapier ; schéma de definition 2, ABI 7 | 2026-10-22 | accepté |
| [ADR-149](ADR-149.md) | Le matériau physique passe la frontière : une table DM-07 par la file de commandes, un index par collider et par tuile | 2026-10-17 | accepté |
| [ADR-147](ADR-147.md) | `collision.resolution` est un nombre de voxels : 100 000 par défaut, de 10 000 à 1 000 000 | 2026-10-17 | accepté |
| [ADR-123](ADR-123.md) | Intégration du monde physique (C-31 tranche 5) : observateurs, proxies vanilla, dégradation et défaillances | 2026-10-04 | accepté |
| [ADR-120](ADR-120.md) | Emprise physique courante des corps, pour la hitbox vanilla (R-702) | 2026-10-02 | accepté |
| [ADR-117](ADR-117.md) | Opcodes de tuiles de collision du monde sur `SimIn` (IF-03) | 2026-09-30 | accepté |
| [ADR-114](ADR-114.md) | Protocole de commande de `SimIn` (IF-03) | 2026-09-20 | accepté |
| [ADR-113](ADR-113.md) | Événements physiques : foyer DM, handle de frontière, et valeurs de genre | 2026-09-19 | accepté
- |
| [ADR-112](ADR-112.md) | Rapier 0.35 : broad phase BVH, solveur TGS-soft, et la correspondance avec la PARTIE 10 | 2026-09-19 | accepté
- |

## Rendu

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-164](ADR-164.md) | Le filtrage anisotrope des textures d'AXION | 2026-11-11 | accepté |
| [ADR-157](ADR-157.md) | L'emprise exacte des assemblies articulées et animées au culling | 2026-11-05 | accepté |
| [ADR-154](ADR-154.md) | Culling, LOD et instancing de M4 : IF-05, contexte de rendu séparé, ABI 9 | 2026-10-28 | accepté |
| [ADR-133](ADR-133.md) | Métriques de rendu, mémoire native et textures : ce qui reste à compter | 2026-10-13 | accepté |
| [ADR-129](ADR-129.md) | Le backend vanilla n'est imposé que par un shaderpack en usage, et le choix se refait quand le pack change | 2026-10-12 | accepté |
| [ADR-127](ADR-127.md) | Backend NATIVE_GL en M3 : données, ressources GPU, shaders et passes (C-60, C-62, C-63) | 2026-10-10 | accepté |
| [ADR-121](ADR-121.md) | Géométrie de debug native et premier overlay : `colliders` (C-67) | 2026-10-02 | accepté |
| [ADR-118](ADR-118.md) | Couture de rendu : abstraction de backend, passe `RenderLevelStageEvent`, acheminement du maillage (GEOM) au client | 2026-10-01 | accepté |

## Animation

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-155](ADR-155.md) | L'animation de M4 : format SKEL/ANIM, `ax-anim`, graphe vivant, skinning et palettes | 2026-10-29 | accepté |

## Réseau et persistance

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-153](ADR-153.md) | La persistance de M4 : schéma NBT 3, état restauré à la création (ABI 8), journal latéral, `CustomData` | 2026-10-25 | accepté |
| [ADR-150](ADR-150.md) | Le réseau de M4 : l'entité de Minecraft porte la visibilité, le canal d'AXION porte l'état ; handshake à la connexion, modèles compilés chez chaque client | 2026-10-18 | accepté |

## Assets et compilation

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-140](ADR-140.md) | Un travail de compilation que Java cesse d'attendre s'abandonne au natif (IF-06) | 2026-10-15 | accepté |
| [ADR-139](ADR-139.md) | Le jeu fournit au compilateur les fichiers voisins qu'il demande (R-830, IF-06, ABI 4) | 2026-10-15 | accepté |
| [ADR-136](ADR-136.md) | Écart à la table 32.2 : `serde_json` lit les options de compilation dans le runtime | 2026-10-14 | accepté |
| [ADR-135](ADR-135.md) | Options de compilation (`.axion.json`, PARTIE 6.4) : un seul lecteur, pour le jeu et la CLI | 2026-10-13 | accepté |
| [ADR-122](ADR-122.md) | Matériaux et textures (C-26) : sections MATL et TEXR, transfert au client, rendu vanilla | 2026-10-02 | accepté |
| [ADR-119](ADR-119.md) | Chargement d'asset natif (IF-06) et transfert de géométrie au client | 2026-10-01 | accepté |
| [ADR-115](ADR-115.md) | Section `PHYS` et pont collider → corps runtime | 2026-09-25 | accepté
- |
| [ADR-110](ADR-110.md) | Section `NODE` : nodes sur 80 octets et table des noms | 2026-09-13 | accepté
- |
| [ADR-108](ADR-108.md) | Le budget de la décomposition convexe est un budget de travail, pas d'horloge | 2026-09-13 | accepté |
| [ADR-107](ADR-107.md) | LOD : sommets partagés, section `LODM`, et visibilité des nodes par niveau | 2026-09-13 | accepté |
| [ADR-106](ADR-106.md) | Tangentes et cache de sommets : les bibliothèques de la table 32.2, et ce que R-553 en exige | 2026-09-13 | accepté |

## API publique et enregistrement du contenu

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-166](ADR-166.md) | La version d'API qu'un addon exige, le kit de GameTest et le validateur hors jeu | 2026-11-12 | accepté |
| [ADR-165](ADR-165.md) | Les addons exigés à la connexion, et ce qu'un administrateur règle sans code | 2026-11-11 | accepté |
| [ADR-162](ADR-162.md) | L'API d'enregistrement du contenu : un mod Java déclare ce qu'AXION connaît | 2026-11-10 | accepté |
| [ADR-143](ADR-143.md) | Un type exigé par une signature du §23.2, sans membre ni fournisseur, n'est pas un bouchon (R-001) | 2026-10-16 | accepté |

## Noyau natif, mémoire et déterminisme

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-138](ADR-138.md) | Les bibliothèques C de la chaîne d'assets allouent par l'allocateur compteur (INV-08) | 2026-10-15 | accepté |
| [ADR-116](ADR-116.md) | `enhanced-determinism` de parry est activé pour tout le workspace | 2026-09-27 | accepté
- |
| [ADR-104](ADR-104.md) | Le noyau déterministe est scalaire et n'emploie pas glam | 2026-09-11 | accepté
- |

## Socle, build, CI et outillage

| ADR | Décision | Date | Statut |
|---|---|---|---|
| [ADR-148](ADR-148.md) | Les messages d'une compilation remontent au journal du jeu par une fonction ajoutée à IF-06 | 2026-10-17 | accepté |
| [ADR-142](ADR-142.md) | `bench-quick` en CI : non bloquant le temps de mesurer le bruit, puis bloquant (R-2250, R-2251) | 2026-10-16 | accepté |
| [ADR-131](ADR-131.md) | Les benchmarks du périmètre de M3 sont exécutés et archivés en M12 | 2026-10-12 | accepté |
| [ADR-126](ADR-126.md) | Banc de rendu client : les tests de rendu de M3 vérifiés mécaniquement | 2026-10-10 | accepté |
| [ADR-124](ADR-124.md) | Scénarios de gameplay déclaratifs (§29.5) : format et exécution | 2026-10-06 | accepté |
| [ADR-111](ADR-111.md) | Version du harnais dans `config`, faute de champ au schéma 2 | 2026-09-14 | accepté
- |
| [ADR-105](ADR-105.md) | `xxhash-rust` est sous Boost 1.0, et non sous BSD-2-Clause | 2026-09-11 | accepté
- |
| [ADR-103](ADR-103.md) | IF-01 gagne le côté de démarrage et l'export des métriques | 2026-09-10 | accepté
- |
| [ADR-102](ADR-102.md) | Une configuration refusée est signalée par `E-2002` | 2026-09-10 | accepté
- |
| [ADR-101](ADR-101.md) | Documentation publiée comme site MkDocs Material | 2026-09-09 | accepté
- |
| [ADR-100](ADR-100.md) | Point d'entrée Forge séparé de la classe `AxionMod` | 2026-09-09 | accepté
- |
