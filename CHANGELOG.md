# Journal des modifications

Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le
projet suit le [versionnement semantique](https://semver.org/lang/fr/).

Chaque jalon termine doit mettre ce fichier a jour : c'est une case de la
Definition of Done (PARTIE 37.3).

## [Non publie]

### Ajoute

- M4 (en cours, Definition of Done non prononcee) — reseau, animation,
  culling et LOD, joints, persistance :
  - reseau (C-51, ADR-150) : canal `axion:main` exige des deux cotes, un
    client sans AXION etant refuse par Forge avec un message (R-1630) ;
    codec du protocole (entiers big-endian, varints canoniques, UTF-8
    strict) et fragmentation ordonnee en trames de 32 000 octets au plus,
    toute entree validee et bornee avant d'etre crue (R-1650, R-1651,
    R-1691) ;
    chaque client compile ses modeles chez lui, depuis ses resource packs
    (`assets/<ns>/axion/models`) puis depuis le `data/` de ses mods, avec
    son propre cache (`<gameDir>/axion/client-cache`) ; le rendu dessine
    ce que le client a compile, et un modele absent donne la boite de
    repli et un avertissement, une fois (R-1640, T-421) ;
    ABI 6 : l'empreinte deterministe de 5.12bis et une compression zstd des
    charges utiles reseau par le tampon NET_OUT, a decompression bornee avant
    toute allocation (R-1691) ;
    messages du handshake et du registre : le serveur annonce sa
    configuration de simulation, ses empreintes et son empreinte
    deterministe ; le client repond par la sienne, et le mode de
    replication de la deformation se negocie par connexion (T-820c) ; le
    registre (materiaux, groupes de collision, definitions) part compresse,
    et le client refuse celui dont les empreintes ne sont pas celles
    annoncees (T-420, T-422) ;
    chaque connexion recoit le handshake, et une connexion distante le
    registre du serveur : materiaux et groupes tels que lus, definitions
    acceptees ; le client le relit par les lecteurs du serveur, l'acquitte,
    et le serveur le renvoie en jeu s'il ne correspond plus ou apres un
    /reload qui le change ; le mode de replication se journalise a l'entree
    du joueur avec sa cause, et /axion status compte les connexions par
    mode (5.12bis) ; net.interpolation_delay_ms prend la borne de son champ
    u16 (0..65535) ;
    snapshots d'etat sur axion:main (21.5) : position au 1/1024 de bloc,
    rotation smallest three, vitesses et emprise quantifiees, en delta sur
    une base acquittee qu'ils nomment ; frequence par assembly (une
    endormie inchangee n'envoie rien), plafond de debit par joueur qui
    retarde et compte sans jamais omettre ; le suivi de position de
    Minecraft est tu, et l'apparition porte orientation et emprise : les
    donnees synchronisees du pont provisoire sont retirees (ADR-150 D2, D6) ;
    interpolation de 21.7 chez le client : Hermite, slerp au plus court,
    rendu net.interpolation_delay_ms derriere le temps du serveur estime
    (qui glisse plutot que de sauter), extrapolation bornee puis figement,
    correction douce ou teleportation ; les pauses d'un monde solo ne
    comptent pas ; le lissage en trois pas est retire (ADR-150 D7) ;
    harnais de joueurs simules dans le serveur de GameTests (ADR-150 D8) :
    vrais joueurs sur des connexions Netty embarquees, vrai decodeur et
    vraie interpolation ; T-410, T-411, T-418, T-422, T-423, T-424 en
    GameTest, T-425 en campagne (AXION_CAMPAGNE=t425) ; metriques reseau
    (R-500) et budget de debit par joueur (R-1850) ; limitation des trames
    du client, abus soutenu deconnecte (R-1690) ; overlay de debug network ;
  - joints (C-34, C-31, ADR-151) : une assembly porte plusieurs
    corps, un par node racine que sa definition declare (`bodies[]`), crees
    tous ou aucun ; ses corps ne se touchent pas entre eux (10.4) ; sommeil,
    garde-fous et gestion d'activite par assembly entiere, plafond de corps
    actifs compte en corps (R-613) ; ABI 7 : `CREATE_ASSEMBLY` porte les
    corps et la section NODE, `BodyState` le node de son corps ; l'entite
    suit le corps racine, son emprise couvre tous ses corps ; R-120 s'etend
    aux ancetres d'un node a collider ;
    joints natifs au banc de physique : sept types, moteur, friction
    interne, rupture en force, en couple et en energie, `JOINT_BROKEN` ;
    Rapier 0.35.3 vendorise et corrige (ADR-152) : moteur et friction
    resolus avec les axes bloques, effort de chaque joint publie ;
    `CREATE_ASSEMBLY` porte les joints de la definition et leurs courbes de
    grippage, juges par le natif ; le `b_node` d'un joint cree son corps ;
    `modules.joints = false` les ignore, avec un avertissement ; metrique
    `axion.sim.last_step_joints_ns`, citee par le journal des ticks lents ;
    grippage d'un joint (D6) : sa courbe resserre sa plage vers sa position
    et mene sa friction vers celle du grippage, `JOINT_JAMMED` a l'entree et a
    la sortie, un desalignement nul rend ses reglages d'origine au bit pres ;
    commandes `JAM_JOINT` et `BREAK_JOINT` par la frontiere, et l'etat de
    chaque joint (rompu, grippe, fraction de plage, force, couple) dans
    `SIM_OUT`, lu par Java ;
    descripteur d'etats 2 et protocole `AXION-2` : les snapshots portent les
    corps secondaires en poses relatives au corps racine et les bits rompu et
    grippe de chaque joint ; un client `AXION-1` est refuse a la connexion ;
    le client interpole les poses des corps secondaires avec celle de la
    racine, et rattache chaque node a son corps comme le natif ;
    chaque corps se dessine a sa pose, dans les deux backends ;
    overlays de debug `joints` et `limits`, a la couleur de l'etat du joint ;
    la racine fixe d'une assembly a plusieurs corps est rapportee dans `SIM_OUT`,
    et l'emprise de l'entite suit tous ses corps ;
    contenu d'essai des joints genere par `tools/assets/joints_essai.py` (porte,
    pendule, chaine, grille) et ses GameTests T-330, T-331, T-333 et T-334 ;
    S-05 au banc de physique : 500 joints en chaines et en grilles, aucune
    valeur non finie, deux executions identiques, temps du pas affiche ;
    corps et joints documentes pour les createurs de contenu (PHYSICS.md) ;
  - persistance (C-52, ADR-153), en cours : schema NBT 3, qui garde la
    position, les vitesses et le sommeil, chaque corps secondaire en
    position monde et l'etat de chaque joint ; migration explicite du
    schema 2 ; un schema posterieur reste inerte et intact ;
    ABI 8 : `CREATE_ASSEMBLY` rend l'etat sauvegarde (pose et vitesses de
    chaque corps, joints rompus ou grippes, sans evenement) et fait naitre
    endormie une assembly chargee, ses vitesses gelees jusqu'a son reveil ;
    drapeau `SUSPENDED` de `BodyState` ; correctif P4 de Rapier vendorise ;
    les creations d'assemblies avancent en file sous
    `persistence.max_load_ns_per_tick` (R-1730), d'une au moins par tick ;
    `CustomData` (T-432) : chaque mod garde ses octets sous son espace de
    noms dans `axion:custom`, table CBOR plafonnee par
    `persistence.max_custom_bytes` ; une table illisible reste intacte ;
    journal lateral (22.5.2) : chaque sauvegarde d'une assembly part, hors du
    thread autoritatif, dans `<gameDir>/axion/world/<worldId>/`, sous budget ;
    une assembly perdue par vanilla est restauree a sa position, endormie,
    une seule fois, et listee dans `restaurations.log` ; le journal d'un
    autre monde de meme chemin et de meme graine est mis de cote ;
    chaque passage des GameTests audite ses ecritures (T-437, R-1711) : JFR
    pour le Java, releve du dossier de jeu pour le reste, natif compris ;
- M3 (Definition of Done prononcee le 2026-10-17, ADR-146) — physique et premier rendu :
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
  - ordre du pas de simulation journalise et verifie contre R-660 a chaque tick
    en debug (T-390), duree des etapes 1 et 15 exposee (T-391, ADR-144) ;
  - budget GPU tenu par paliers journalises avec leur cause, descente
    immediate et remontee avec hysteresis (R-750, T-484), jauge
    `axion.render.gpu_degradation_level` ; `/axion metrics export client` pour
    un client relie a un serveur distant (ADR-144) ;
  - banc de rendu : transparence ordonnee (T-476), aucune texture d'AXION
    laissee sur le GPU par une bascule de backend (T-481), ressources
    reconstruites par un vrai changement de resource pack (T-483) ;
  - requetes spatiales lancees de plusieurs threads a la fois : le monde se
    partage (`Sync`) et rend ce qu'il rend en serie (R-650, T-384) ;
  - metriques de R-500 des tuiles du monde (C-38, `axion.world.*`) : tuiles
    construites et liberees, replis en champ de hauteurs (R-641) et sections
    non chargees (R-640), file, tuiles tenues, duree de construction par tick ;
  - un `/reload` ou un `/axion assets reload` atteint les assemblies qui
    apparaissent ensuite : la boucle de l'entite lisait les registres du
    demarrage du serveur ;
  - temps GPU des passes opaque et translucide du backend natif, par
    horodatages GPU lus sans attendre le GPU (`axion.render.gpu_opaque_ns`,
    `axion.render.gpu_translucent_ns`, ADR-146 §2) ;
  - `collision.resolution` prend effet (ADR-147) : le nombre de voxels de la
    grille de la decomposition convexe, 100 000 par defaut, de 10 000 a
    1 000 000, choisis sur mesure ; `COMPILER_VERSION` 14 ;
  - six overlays de debug de plus (C-67, amendement A1 d'ADR-121) : `aabb`,
    `bodies`, `contacts`, `normals`, `world_tiles`, `center_of_mass`, par
    `/axion debug <overlay> on|off` ; charge `DEBUG` au schema 2, une plage par
    overlay et repere ;
  - evenements physiques au-dela du plafond `physics.max_events_per_tick`
    (R-1011) : un evenement se fond dans le premier de meme identite deja au
    lot, qui garde l'impulsion la plus forte, au lieu d'etre jete ; celui qui
    n'y trouve pas son identite est perdu ; l'un et l'autre sont comptes
    (`axion.sim.events_aggregated`, `axion.sim.events_dropped`) ; une impulsion
    de contact par paire et par tick, la plus forte des sous-pas (ADR-146) ;
  - les avertissements d'une compilation d'asset (R-530, R-912, colliders de
    C-32...) atteignent le journal du jeu, une fois par compilation, et un
    refus y dit son motif, pas seulement son code ; les refus et avertissements
    d'un rechargement aussi, que seul `/axion status` montrait (ADR-148,
    `axion_asset_diagnostics` ajoutee a IF-06, ABI 4) ; metrique
    `axion.asset.warnings` ;
  - materiau physique des parts d'assembly jusqu'au natif, second temps de
    DM-07 (ADR-149) : table des materiaux de la session (SET_PHYSICS_MATERIALS,
    index stables, un rechargement atteint les corps et tuiles poses), index
    de la part sur chaque collider, index du materiau dominant sur chaque tuile
    (ABI 5, schema des commandes 2) ; modes de combinaison du solveur, le plus
    fort l'emporte ; materiau du collider dans les evenements ; frottement de
    roulement qui freine sans jamais inverser ; ballon de test modele dans
    Blender, qui rebondit la ou l'acier generique ne rebondit pas ;
  - invariants verifies mecaniquement : T-006 (INV-01), T-011 (INV-03),
    T-012 (INV-04), T-015 (INV-07), T-019 (INV-12) ;
  - un collider `convex` ne garde que les sommets de son enveloppe : ni
    doublon d'un export a normales plates, ni point interieur (R-161) ;
    `COMPILER_VERSION` 11 ;
  - `auto_capsule` sur un mesh trapu rend la sphere de son rayon : la capsule de
    segment nul qu'il produisait faisait refuser l'asset par la validation ;
    `COMPILER_VERSION` 12 ;
  - decomposition convexe aux bornes de R-551 et R-161 (ADR-108, tranche D) :
    une enveloppe de plus de 64 sommets est reduite au lieu d'etre ecartee - une
    sphere en auto_convex n'avait aucun collider -, une enveloppe globale de plus
    de 256 points reduite au lieu d'etre refusee, par un echantillonnage
    deterministe ; la duree d'une decomposition est mesuree et avertie au-dela
    de 5 s, sans effet sur le resultat ; l'asset entier prend auto_capsule,
    convex et auto_convex dans son repere ; `COMPILER_VERSION` 13 ;
  - materiaux physiques data-driven, premier temps de DM-07 (ADR-145) : registre
    `axion:physics_material` (`data/<ns>/axion/physics_materials`), heritage par
    `parent` champ par champ, acier generique du moteur pour ce qu'aucun ancetre
    ne declare (R-1032), unites et plages de DM-07 jugees (T-840b), relation de
    Tabor (R-1038) ; bibliotheque de dix-huit materiaux, grandeurs physiques
    sourcees (MATERIALS.md) ; mappage bloc -> materiau au format du
    paragraphe 11.4 (`data/<ns>/axion/block_materials`), fusion par identifiant,
    priorite des etiquettes par ordre de declaration (T-841), qui remplace la
    ressource fixe des tuiles ; une definition qui nomme un materiau non declare
    avertit (R-1032) ;
  - groupes de collision tiers declares par les packs
    (`data/<ns>/axion/collision_groups`, registre `axion:collision_group`,
    ADR-132 A1) : un fichier, un groupe et son masque par defaut, bits 8 a 31
    dans l'ordre des identifiants, 24 au plus, chaque refus nomme ; le schema 1
    publie admet un identifiant `<ns>:<chemin>` dans `collision_group` et
    `collides_with`, et une definition qui nomme un groupe inconnu est refusee
    avec son chemin.
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

### Corrige

- Configuration (C-04) : `persistence.journal_max_bytes` borne a 16 MiB..8 GiB,
  comme au cahier des charges ; le schema acceptait toute valeur positive.
- Dependances (C-24, R-2301) : `zstd-sys` 2.1.2, `zstd` 0.14.3 et `zstd-safe`
  8.2.0, pour RUSTSEC-2026-0391, -0398, -0403 et -0410. libzstd reste en
  1.5.7 : 23 assets de developpement compiles a l'identique, au bit pres.
- Physique (C-31, ADR-153) : une assembly a racine fixe (porte, pendule,
  chaine) n'etait jamais endormie hors de portee des joueurs (R-612) : la
  gestion d'activite ne relevait que les racines dynamiques. Un joint insere
  ne reveille plus ses corps deja eveilles.
- Joints (C-34, ADR-151 D2) : `physics.self_collision` part enfin avec
  l'assembly, que Java envoyait toujours sans collisions internes, et
  `bodies[].mass` fixe la masse de son corps, au lieu d'etre ignore.
- Physique (C-31, ADR-151) : les colliders d'un node sont poses par la
  chaine des transformations de l'asset, echelle comprise. Le natif les
  posait sans la pose de leur node : dans un asset dont un node porteur de
  collider n'est pas a l'origine, les colliders ne se trouvaient pas sous
  les meshes.
- Import glTF (C-21) : une scene sans `nodes`, que glTF 2.0 admet, ne fait
  plus refuser le document. `gltf-json` 1.4.1 exigeait le membre ;
  l'exportateur de Blender en ecrit une pour chaque scene du fichier ou rien
  n'est exporte. Un document que la bibliotheque accepte est lu comme avant.
