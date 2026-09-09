# Journal des modifications

Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le
projet suit le [versionnement semantique](https://semver.org/lang/fr/).

Chaque jalon termine doit mettre ce fichier a jour : c'est une case de la
Definition of Done (PARTIE 37.3).

## [Non publie]

### Ajoute

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
