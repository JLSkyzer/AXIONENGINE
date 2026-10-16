# Journal des modifications

Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le
projet suit le [versionnement semantique](https://semver.org/lang/fr/).

Chaque jalon termine doit mettre ce fichier a jour : c'est une case de la
Definition of Done (PARTIE 37.3).

## [Non publie]

### Ajoute

- M3 (en cours, Definition of Done non prononcee) — physique et premier rendu :
  - `ax-physics` (C-31, rapier3d) : formes dynamiques (§10.3), groupes de
    collision par nom avec tuiles du monde dans `world` (R-980, ADR-132),
    sommeil, rayon de simulation et plafond de corps actifs, CCD declaree ou
    automatique au-dela de |v|.dt > 0,5 x plus petite dimension, forces
    environnementales (trainee, portance, flottabilite, R-1000), evenements de
    sommeil, de contact, d'impulsion et de capteur, garde-fous R-180/R-181 et
    gouverneur de degradation (R-1880) ;
  - cycle de simulation IF-03 par la frontiere (submit, collect, SIM_OUT,
    EVENTS), ABI 3 avec la description DM-08 du corps jointe a
    CREATE_ASSEMBLY (ADR-130) ;
  - collision du monde (C-38), requetes spatiales (C-39), ordonnanceur du tick
    (C-40), entite d'assembly et commandes /axion spawn et remove (C-50),
    proxies des entites vanilla et effets de collision sur elles (ADR-123) ;
  - rendu : backend natif OpenGL 3.3 (C-60) avec variantes de shaders a la
    demande et cache binaire (T-904, T-905), backend vanilla (C-61) impose
    sous un shaderpack en usage (ADR-129), arenes GPU et budget LRU (C-62),
    textures et materiaux (C-26, ADR-122), overlay `colliders` (C-67, ADR-121) ;
  - acceptance : GameTests de chute libre, d'empilement et d'absence de
    traversee sur un monde fige (ADR-124), banc de rendu automatise sous
    vanilla, natif et shaderpack (ADR-126) ;
  - invariants verifies mecaniquement : T-006 (INV-01), T-011 (INV-03),
    T-012 (INV-04), T-019 (INV-12).
- M2 (Definition of Done prononcee le 2026-10-16, ADR-137) — graphe de scene, cache,
  optimizer, entite, API :
  - optimizer d'assets (C-23) : fusion des sommets, normales et tangentes
    MikkTSpace, cache de sommets, LOD en section `LODM`, boites englobantes,
    decomposition convexe par node (ADR-108) ; sections compressees par zstd
    au niveau 9 (R-552) ;
  - cache des assets compiles (C-25), cle a quatre termes, plafond tenu sur
    disque (R-561), metriques `axion.asset.cache.*` (R-500), desactivable en
    mode developpeur (`-Daxion.dev.asset_cache=false`, 31.3) ; graphe de scene
    `ax-scene` (C-30) ;
  - metriques de R-500 : la chaine d'assets par etape (`axion.asset.*`), les
    definitions (`axion.definition.*`) et l'entite (`axion.entity.*`) ;
  - definitions data-driven (C-27) : JSON strict, schema 1 publie, refus
    individuel nomme ; definitions de demonstration derriere `content.examples`
    (amendement A2 de l'annexe A.3) ;
  - les cinq exemples de R-1790 (`crate`, `buggy`, `flag`, `barrier`,
    `winch_rope`) : modeles faits dans Blender, decoupes et annotes pour les
    systemes a venir (parts, colliders, roues, siege, sockets, ancrages de
    tissu), et leurs definitions au schema 1 ; T-630..T-634 (ADR-134),
    `validateJar` exige les cinq, seules ;
  - API publique `axion-api` (C-70) : surface du paragraphe 23.2 et ses 18
    evenements, sans fournisseur enregistre a ce stade ;
  - harnais de benchmarks `ax-bench` (C-72), job CI `bench-quick` compare au
    dernier resultat de la meme machine (R-2250, non bloquant le temps d'en
    mesurer le bruit, ADR-142) ; CLI `axion-cli` (C-74) :
    compile, inspect, diff, validate, lod et bench-asset, et options de
    compilation `.axion.json` lues par le meme code que le jeu (ADR-135).
  - compilation en jeu identique a celle de la CLI (R-830) : plafond
    `assets.max_source_bytes` applique, `source_hash` commun, fichiers voisins
    (`.mtl`, buffers glTF externes) demandes puis fournis par la frontiere,
    ABI 4 (ADR-139) ; une compilation abandonnee par Java est oubliee par le
    natif, `axion_asset_cancel` (ADR-140) ;
  - allocations de meshoptimizer et de zstd comptees par l'allocateur du
    moteur (INV-08, ADR-138).
- M1 (Definition of Done prononcee le 2026-10-04) — assets, noyau
  deterministe, jobs :
  - `ax-jobs` (C-12) : pool dedie, jobs types annulables a echeance ;
    `ax-telemetry` (C-15) : metriques, budgets et export JSON ;
  - `ax-det` (C-16) : noyau deterministe, 10 000 vecteurs d'or rejoues sur les
    quatre plateformes de la CI (R-516) ;
  - chaine d'assets : orchestrateur (C-20), importeurs glTF, GLB, OBJ et STL
    (C-21), validateur (C-22), conteneur A3D (C-24) ;
  - commandes disponibles (C-71) ;
  - fuzzing de la chaine d'assets sur quatre cibles (R-903), campagne nocturne
    d'une heure par cible ; integration continue sur la matrice de plateformes.
- M0.10 — acceptance sur serveur dedie : chargement, bibliotheque native
  verifiee et chargee, /axion status, arret propre a bilan nul, et mode
  DISABLED laissant le serveur jouable. Zero erreur au journal.
- M0.9 — bilan d'allocations a l'arret (R-322) : un tampon jamais relache est
  signale par E-2003, la session se fermant quand meme. Ajout de T-014, qui
  verifie qu'aucun systeme ne reference RUSTFORGE-X hors de C-76 (INV-06).
- M0.8 — chaine de build native (34.1) : codegen verifie, buildNatives produit
  la bibliotheque et son empreinte, packageNatives la place dans les ressources,
  validateJar controle l'artefact et refuse un JAR partial a la publication.
  Build reproductible verifie (R-2341, R-2342).
- M0.7 — diagnostics et commande /axion status (C-05, C-71) : etat du runtime,
  cause d'une desactivation, cout FFI mesure et hooks desactives. Deux
  verifications d'architecture s'ajoutent : aucune connexion reseau sortante
  (T-022, R-440) et tout code d'erreur documente en ANNEXE A.1 (T-023, R-443).
- M0.6 — integration Forge (C-01) : adaptateur de plateforme, cycle de vie
  independant de Forge, garde de hook qui absorbe tout et se desactive apres
  cinq echecs consecutifs. T-020 verifie mecaniquement que seul
  dev.axion.forge importe net.minecraftforge (R-400, R-401, E-1010).
- M0.5 — sequence de demarrage (C-02) : configuration, chargement du natif,
  handshake ABI, calibration FFI mesuree et acquisition des tampons. Tout echec
  conduit a DISABLED avec sa cause, sans exception ni jeu injouable (R-410).
- M0.4 — frontiere Java/Rust operationnelle (C-14) : ABI versionnee, contexte
  opaque, tampons de transfert a generations, pont JNI par RegisterNatives, et
  encodeur CBOR cote Java. Un test charge la bibliotheque reelle et exerce le
  cycle complet (IF-01, IF-02, R-260..R-265, R-310..R-313).
- M0.3 — chargeur de bibliotheque native (C-03) : detection des cinq
  plateformes supportees, empreinte SHA-256 obligatoire, chemin versionne par
  empreinte, extraction atomique, repli sur le repertoire temporaire et echec
  propre en mode DISABLED (R-420, R-421).
- M0.2 — configuration data-driven de bout en bout (C-04) :
  - `ax-model` : source unique des 160 options des trois fichiers TOML, avec
    defaut, domaine et description ;
  - `CONFIGURATION.md`, les fichiers TOML de reference et la classe Java
    `ConfigSchema` en sont **generes** (R-430) ;
  - un test compare le registre a l'ANNEXE A.3 du cahier des charges (R-2400),
    un autre verifie la parite du code Java genere (T-005) ;
  - cote Java, `ConfigLoader` empile defauts, fichier et surcharges
    `-Daxion.*`, en validant chaque valeur : une entree refusee conserve la
    precedente et devient un diagnostic, jamais un echec de demarrage.
- M0.1 — quatre premiers crates natifs :
  - `ax-math` (C-11) : conventions du repere et origine flottante (R-460..R-462) ;
  - `ax-mem` (C-13) : quatre classes d'arene, comptage des allocations et
    allocateur de pages de 1 KiB a liste libre (R-480..R-482) ;
  - `ax-core` (C-10) : table de handles a generations et unicite du contexte
    natif (R-450, R-311) ;
  - `ax-ffi` (C-14) : bibliotheque dynamique `axion_native`.
- Arborescence du depot conforme a la PARTIE 33 : build Gradle multi-projet
  (`:axion-api`, `:axion-mod`) et workspace Cargo.
- Index de navigation du cahier des charges (`docs/spec/`), genere par
  `tools/spec/spec_index.py`.
- Contrat de l'agent de developpement (`docs/AGENT.md`).

### Modifie

- Identite du mod alignee sur le cahier des charges : `mod_id` `axion`,
  paquet Java `dev.axion`, licence Apache-2.0.

### Supprime

- Contenu d'exemple du MDK Forge (bloc, objet et onglet creatif de
  demonstration), etranger au moteur et contraire a l'interdiction 3.3.
