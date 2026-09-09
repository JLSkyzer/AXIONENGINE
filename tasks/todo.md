# AXION ENGINE — plan de travail

Source de vérité : `cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md` (8270 lignes,
FINAL / FROZEN). Ne jamais le lire en entier : passer par
[l'index](../docs/spec/INDEX.md) et lire la plage de lignes utile.

Règles de travail : [docs/AGENT.md](../docs/AGENT.md).
Leçons apprises : [tasks/lessons.md](lessons.md) — à relire à chaque session.

---

## État courant

**Jalon en cours : M0 — Squelette et frontière native. M0.1 à M0.9 faits, M0.10 suivant.**

Ce qui est en place :

- [x] Arborescence conforme à la PARTIE 33 : `java/axion-api`, `java/axion-mod`,
      `crates/`, `docs/`, `tools/`, `tasks/`.
- [x] Build Gradle multi-projet vert. `./gradlew build` produit
      `java/axion-mod/build/libs/axion-1.0.0-SNAPSHOT.jar`, qui contient
      `dev/axion/**` API comprise, `META-INF/mods.toml` et `axion.mixins.json`.
- [x] `axion-api` publie aussi son propre artefact (R-1750) et ses sources.
- [x] Identité figée : `mod_id` = `axion`, paquet `dev.axion`, Apache-2.0.
- [x] Workspace Cargo et `rust-toolchain.toml` (rustc 1.94.0 épinglé, C-16).
- [x] `deny.toml` : allowlist de licences, RustSec bloquant, crates.io seul (R-2301).
- [x] Index du CDC : `docs/spec/INDEX.md` (452 sections) et
      `docs/spec/ID-MAP.tsv` (1331 identifiants).

- [x] Cinq crates natifs : `ax-math`, `ax-mem`, `ax-core`, `ax-ffi` (M0.1) et
      `ax-model` (M0.2). `cargo build --release -p ax-ffi` produit
      `axion_native.dll`.
- [x] Configuration data-driven (M0.2) : source unique de 160 options, dont
      dérivent `CONFIGURATION.md`, les TOML de référence et le schéma Java.
- [x] Chargeur de bibliothèque native (M0.3) : plateformes, empreinte,
      extraction, repli.
- [x] `cargo test --workspace` (17 blocs) et `./gradlew build` (34 tests JUnit)
      verts.

Ce qui n'existe pas encore : **le moteur**. Aucun point d'entrée dans la
bibliothèque native, donc aucun handshake ABI ; le bootstrap n'est pas câblé,
et il n'y a ni physique, ni entité, ni rendu. Les pièces de M0.2 et M0.3 sont
écrites et testées mais **pas encore reliées entre elles** : c'est M0.5 qui les
assemble, une fois la frontière FFI posée en M0.4.

---

## M0 — Squelette et frontière native

Fiche du jalon : `sed -n '7180,7190p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md`

Composants : C-01..C-05, C-10, C-11, C-13, C-14 (contrôle).
Tests : T-100..T-103, T-110..T-114, T-120..T-124, T-130..T-133, T-150..T-152.

Livrable : chargement client et serveur, natif chargé, handshake ABI,
`/axion status`, arrêt propre, mode `DISABLED` sûr.

Acceptance : JAR installable ; boot `READY` sur les plateformes ; natif absent
ou ABI incompatible donne `DISABLED` avec un jeu jouable ; bilan d'allocations nul.

Lire avant de commencer, dans l'ordre :

| Sujet | Plage de lignes du CDC |
|---|---|
| Frontière Java/Rust, ABI, FFI (PARTIE 4) | `1244,1454p` |
| C-01 Forge Integration | `1459,1495p` |
| C-02 Bootstrap | `1496,1514p` |
| C-03 Native Loader | `1515,1528p` |
| C-04 Configuration | `1529,1544p` |
| C-05 Diagnostics & Logging | `1545,1553p` |
| C-10 Native Core | `1554,1570p` |
| C-11 Math | `1571,1580p` |
| C-13 Memory / Arenas | `1593,1607p` |
| C-14 FFI Bridge | `1608,1615p` |
| Chaîne de build attendue | `6750,6800p` |
| Configuration de référence (ANNEXE A.3) | `7840,8045p` |

Tâches :

- [x] **M0.1** Crates `ax-math` (C-11), `ax-mem` (C-13), `ax-core` (C-10) et
      `ax-ffi` (C-14) créés, `members` repassé au glob. 22 tests verts, clippy
      sans avertissement, `cargo fmt` conforme, `axion_native.dll` produit.
      Couvre R-450, R-460..R-462, R-480..R-482, R-311, INV-09.
      Trois points sont volontairement reportés, chacun à son jalon :
      - le `Context` complet de C-10 (13 champs) s'assemblera quand `Config`,
        `QualityProfile`, `Telemetry`, `JobSystem` et `BufferPool` existeront ;
        aujourd'hui seuls la table de handles et le jeton d'unicité sont écrits ;
      - les deux premières étapes de R-481 — compactage des champs saturés puis
        éviction LRU des assemblies lointaines — relèvent de C-42 (M6) : elles
        supposent de connaître champs et assemblies, que l'allocateur ignore.
        `ax-mem` refuse avec `E-2004` ; C-42 branchera la récupération avant ce
        refus et traduira en `E-8001` ;
      - les codes d'erreur sont pour l'instant définis dans chaque crate.
        DM-19 les centralisera dans `ax-model` (M1), qui n'existe pas encore ;
        `ax-mem` et `ax-core` ne peuvent pas dépendre d'un crate absent.
- [ ] **M0.2** C-04 Configuration. **Fait, sauf l'encodage CBOR qui dépend de M0.4.**
      - [x] `ax-model` : les 160 options des trois fichiers déclarées une seule
            fois, avec défaut, domaine et description. Rendu TOML et
            `CONFIGURATION.md` générés depuis ce registre (R-430).
      - [x] Test de non-divergence avec l'ANNEXE A.3 du cahier des charges
            (R-2400) : il échoue sur une option oubliée comme sur un défaut mal
            recopié — vérifié par mutation dans les deux sens.
      - [x] `tools/codegen` génère la classe Java `ConfigSchema` et les trois
            fichiers TOML de référence embarqués dans le JAR, avec le test de
            parité T-005.
      - [x] Côté Java : `ConfigLoader` empile défauts, fichier et surcharges
            `-Daxion.*` en validant chaque valeur ; `ConfigValidation` refuse
            type erroné, hors-plage, hors-énumération et NaN. Une entrée
            refusée conserve la précédente et devient un diagnostic — le mod
            démarre toujours. 8 tests JUnit (T-130..T-133).
      - [x] Encodage CBOR vers `axion_init` : `CborWriter` côté Java, décodage
            et revalidation côté natif (fait en M0.4). Reste à l'appeler depuis
            le bootstrap, ce qui est M0.5.
      - [ ] Compléter `hot` (R-431) et `server_authoritative` (R-1830) : seules
            les options manifestement concernées sont marquées aujourd'hui —
            `debug`, `overlay` pour l'une, `sim` et `physics` pour l'autre. Les
            listes exactes se fixent avec le rechargement à chaud et le
            handshake, sans quoi elles seraient devinées.
- [x] **M0.3** C-03 Native Loader. 26 tests (T-120..T-124).
      - `NativePlatform` : les cinq plateformes de la table 34.2, détectées
        depuis les valeurs réellement rapportées par les JVM.
      - `NativeLoader` : empreinte SHA-256 obligatoire (E-1003), chemin
        versionné par empreinte, extraction atomique par fichier temporaire
        voisin, `System.load` sur chemin absolu (R-420).
      - Le fichier déjà extrait est **revérifié** avant réutilisation : son
        chemin porte l'empreinte attendue, ce qui ne dit rien de son contenu
        (interdiction 3.13).
      - Repli sur `java.io.tmpdir` quand un emplacement refuse l'écriture ou le
        chargement — lecture seule et `noexec` se traitent pareil (R-421) —
        puis échec propre, jamais une exception qui remonte.
      - `ResourceSource` et `NativeBinder` sont injectables : c'est ce qui rend
        le repli `noexec` et l'empreinte invalide réellement testables, sans
        bibliothèque native ni système de fichiers particulier.
      Reste lié : le branchement dans le bootstrap (M0.5) et l'empaquetage des
      binaires dans le JAR (M0.8). D'ici là, `RESOURCE_MISSING` est le cas
      nominal, et le mod le signale sans planter.
- [x] **M0.4** C-14 FFI Bridge et handshake ABI. La frontière fonctionne de
      bout en bout : un test Java charge la vraie bibliothèque, initialise,
      acquiert un tampon, écrit, libère et arrête.
      - ABI IF-01 : `axion_abi_version`, `axion_init`, `axion_shutdown`,
        `axion_last_error`. Contexte = `u64` opaque à motif, jamais un pointeur.
      - Tampons IF-02 : `axion_buffer_acquire` / `release`, treize kinds,
        en-tête de 32 octets little-endian, générations, croissance par
        doublement.
      - Chaque point d'entrée valide ses arguments et passe par `catch_unwind` ;
        une panic empoisonne le contexte et renvoie `E-2000` (INV-05).
      - `panic = "abort"` retiré du profil release : R-312 l'interdit, et il
        aurait rendu `catch_unwind` inopérant.
      - Pont JNI par `RegisterNatives` depuis `JNI_OnLoad`, pour que la
        bibliothèque n'exporte que `JNI_OnLoad` et six symboles `axion_*`
        (R-2020), vérifié par `tools/ffi/check_exports.py`.
      - `CborWriter` : encodeur maison validé contre les vecteurs de la RFC
        8949 — aucune dépendance shadée n'étant permise par `validateJar`.
      - `BufferKinds.java` généré depuis `ax-model`, avec test de parité.
      Reste lié : `axion_set_quality` attend `AxionQualityProfile`, que le
      cahier des charges ne décrit nulle part ; il viendra avec C-77 (M5).
      Ajouter une fonction n'est pas une rupture d'ABI.
- [x] **M0.5** C-02 Bootstrap. Machine à états
      `INIT -> CONFIG -> LOAD_NATIVE -> HANDSHAKE -> PROBE -> READY | DISABLED`,
      10 tests (T-110..T-114).
      - Aucune étape ne lève d'exception : tout échec entre la plateforme et
        l'acquisition des tampons conduit à `DISABLED` avec sa cause. Le mod se
        charge, le jeu reste jouable.
      - `general.enabled=false` désactive **sans même charger** la
        bibliothèque, ce qu'un test vérifie.
      - Calibration FFI mesurée sur 10 000 itérations, médiane retenue (R-330).
        La médiane et non la moyenne : une pause du ramasse-miettes suffirait à
        décaler celle-ci.
      - Un démarrage qui échoue après `axion_init` referme le contexte : rien
        ne reste ouvert derrière.
      - `NativeApi` est injectable, sans quoi ni le refus d'ABI ni un
        `axion_init` fautif ne seraient jamais testés — or ce sont eux qui
        garantissent que le jeu reste jouable.
      Reste lié : le bootstrap n'est appelé par personne tant que C-01 ne
      l'invoque pas (M0.6). Deux étapes de la fiche attendent leur composant —
      capacités GPU (M3) et profil de qualité initial de C-77 (M5) — ce qui ne
      change pas la séquence.
- [x] **M0.6** C-01 Forge Integration. 24 tests (T-020, T-100..T-103).
      - `PlatformAdapter` (IF-10) : tout ce qu'AXION demande à son hôte tient
        en neuf méthodes, et rien d'autre ne traverse.
      - `AxionRuntime` porte le cycle de vie **sans connaître Forge**, ce qui
        le rend testable sans démarrer le jeu.
      - `HookGuard` : rien ne remonte jamais d'un hook (R-400), et cinq échecs
        consécutifs le désactivent avec `E-1010`. Un succès remet le compteur à
        zéro : c'est la répétition qui condamne, pas l'incident.
      - `AxionForgeEntrypoint` : abonnements `HIGHEST` à l'ouverture, `LOWEST`
        à la fermeture, et le bus injecté par FML plutôt que pris dans un
        contexte statique déprécié.
      - **T-020 est désormais un test**, plus un contrôle shell : il lit les
        sources, nomme fichier et ligne, et vérifie sa propre pertinence — un
        `dev.axion.forge` qui n'importerait plus Forge rendrait l'isolation
        triviale.
      Reste lié : la boucle de simulation du tick attend C-40, et les hooks de
      dommage attendent C-50 (M2). Les événements de rendu viennent en M3.
- [x] **M0.7** C-05 Diagnostics et `/axion status`. 11 tests (T-022, T-023,
      T-140, T-141).
      - `StatusReport` se compose hors de toute API de plateforme : l'état, la
        cause d'un `DISABLED`, le coût FFI mesuré, les hooks désactivés. Aucune
        donnée de monde, de chat ou de joueur (R-442).
      - `/axion status` demande le niveau opérateur : la commande ne divulgue
        rien de personnel mais décrit l'installation, et c'est l'option
        conservatrice.
      - **T-022** vérifie qu'aucune connexion sortante n'existe, des deux côtés
        de la frontière, et qu'aucune dépendance réseau n'entre dans le
        workspace (R-440).
      - **T-023** vérifie que tout `E-xxxx` cité figure à l'ANNEXE A.1 (R-443).
        Il a immédiatement relevé deux codes inventés en M0.2 — voir ADR-102.
      Reste lié : le journal natif dans `<gameDir>/axion/logs/` avec rotation
      (R-441) attend la journalisation côté Rust, et `/axion diag dump` (R-442)
      vient avec le reste de C-71 en M1.
- [x] **M0.8** Chaîne de build (`gradle/natives.gradle`).
      - `codegen` échoue si le code généré a divergé du registre — vérifié par
        mutation.
      - `buildNatives` produit la bibliothèque et son empreinte SHA-256 ; une
        cible non hôte est ignorée avec un avertissement et le JAR marqué
        `partial` (R-2341). Un développeur sans chaîne Rust peut construire.
      - `packageNatives` passe par les ressources et non par le JAR seul :
        c'est la seule façon que `runClient` les trouve en développement.
      - `validateJar` vérifie `mods.toml` développé, l'absence de dépendance
        shadée, la présence d'une empreinte par bibliothèque, et **refuse un
        JAR `partial` en mode release** (`-Paxion.release`).
      - **R-2342 vérifié** : deux constructions donnent le même SHA-256, après
        retrait de l'horodatage de manifeste hérité du MDK.
      - Le test de la frontière charge désormais la bibliothèque **par le vrai
        chargeur, depuis les ressources** : extraction, empreinte, chargement
        et JNI sont exercés d'un bloc.
      Reste lié : la compilation croisée des quatre autres cibles suppose une
      chaîne complète par plateforme ; la CI les produira sur leurs machines
      (M11). `validateJar` contrôlera la LUT BRDF et les shaders en M9, et la
      table de symboles via `tools/ffi/check_exports.py` quand T-661 sera écrit.
- [x] **M0.9** Tests du jalon. 39 identifiants couverts, 80 tests Java et
      66 tests Rust verts.

      | Identifiants | Où |
      |---|---|
      | T-005 | `tools/codegen/tests/parite_java.rs` |
      | T-013, T-190, T-191 | `crates/ax-ffi` (`abi.rs`, `tests/cycle_abi.rs`), `NativeBridgeTest` |
      | T-014 | `RustforgexIsolationTest` |
      | T-016 | `crates/ax-mem`, `tests/cycle_abi.rs` |
      | T-017, T-150..T-152 | `crates/ax-core` |
      | T-020 | `ForgeIsolationTest` |
      | T-021 | `crates/ax-model/src/config` |
      | T-022 | `NoOutboundNetworkTest` |
      | T-023 | `ErrorCodesDocumentedTest` |
      | T-100..T-103 | `AxionRuntimeTest` |
      | T-110..T-114 | `AxionBootstrapTest` |
      | T-120..T-124 | `NativePlatformTest`, `NativeLoaderTest` |
      | T-130..T-133 | `ConfigLoaderTest` |
      | T-140, T-141 | `StatusReportTest` |
      | T-160..T-162 | `crates/ax-math` |
      | T-180, T-181 | `crates/ax-mem` |

      Ajouté en M0.9 : le bilan d'allocations à `axion_shutdown` (R-322)
      manquait — un tampon jamais relâché est maintenant signalé par `E-2003`,
      la session se fermant quand même. Vérifié par mutation. Et T-014, qui
      arrête à la première marche la pente menant à une dépendance envers
      RUSTFORGE-X (INV-06).

      Reportés, faute du composant qu'ils mesurent :
      - **T-012** (INV-04, moins de 32 traversées FFI par tick) — il n'y a pas
        encore de boucle de simulation à mesurer. M3/M4.
      - **T-004** (graphe de dépendances vérifié mécaniquement) — utile quand
        il y aura assez de modules pour qu'un cycle soit possible.
      - **T-006, T-007, T-011, T-015, T-018, T-019** — invariants portant sur
        des composants qui n'existent pas.
      - **T-710** (build reproductible) — vérifié à la main en M0.8, à
        automatiser avec le reste du packaging en M12.
- [ ] **M0.10** Vérifier l'acceptance de bout en bout : JAR installé sur un
      client et sur un serveur dédié réels, natif absent donnant un `DISABLED`
      jouable.

---

## Jalons suivants

Fiches complètes : `sed -n '7178,7317p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md`

- [ ] **M1** Assets, noyau déterministe, jobs — C-12, C-15, C-16, C-20, C-21, C-22, C-24, C-71
      · **C-16 : `[EFFORT MAX]`**, prévenir avant de commencer
- [ ] **M2** Scene graph, cache, optimizer, entité, API — C-23, C-25, C-27, C-30, C-50, C-70, C-72, C-74
- [ ] **M3** Physique et premier rendu — C-31, C-32, C-38, C-39, C-40, C-60..C-63, C-67, C-26
- [ ] **M4** Réseau, animation, culling/LOD, joints, persistance — C-34, C-37, C-51, C-52, C-64, C-65, C-66
- [ ] **M5** Véhicules, sièges, attaches, gouverneur, overlay — C-33, C-48, C-53, C-73, C-77
- [ ] **M6** Impacts et déformation continue *(jalon central)* — C-28, C-41, C-42, C-45, C-68
      · **`[EFFORT MAX]`** sur tout le jalon, prévenir avant de commencer
- [ ] **M7** Structure, rupture, détachement, réparation — C-43, C-44, C-46
- [ ] **M8** Particules unifiées — C-36
- [ ] **M9** Rendu avancé — C-69, C-80..C-83, C-75, C-47
- [ ] **M10** Blocs, items, bout en bout — C-54, C-55, T-970/T-971
- [ ] **M11** Compatibilité, coexistence, durcissement — C-76, testmod, fuzzing
- [ ] **M12** Release 1.0.0 — documentation, benchmarks B-01..B-30, SBOM, packaging

Règles de jalon (R-2390..R-2393) : chaque jalon se termine par un **JAR
installable et jouable**, préserve intégralement les jalons précédents, et voit
ses critères vérifiés **mécaniquement**.

---

## Dette et points ouverts

À traiter le moment venu, pas oubliés :

- [ ] **Nom de l'artefact de release.** Le critère final attend
      `axion-1.0.0-mc1.20.1-forge47.jar` ; le build produit aujourd'hui
      `axion-1.0.0-SNAPSHOT.jar`. À régler au packaging (M12).
- [ ] **Documentation racine.** La PARTIE 33 exige `ARCHITECTURE.md`,
      `BUILDING.md`, `INSTALLATION.md`, `CONFIGURATION.md`, `COMPATIBILITY.md`,
      `ASSETS.md`, `PHYSICS.md`, `DAMAGE.md`, `DEFORMATION.md`, `RENDERING.md`,
      `NETWORKING.md`, `API.md`, `BENCHMARKS.md`, `TROUBLESHOOTING.md`,
      `RELEASING.md`, `SECURITY.md`, `CONTRIBUTING.md`. Aucun n'existe. Les
      écrire au fil des jalons concernés, jamais en bloc à la fin, et **jamais
      vides** : un fichier vide vaut un placeholder.

      **Attention : `CONFIGURATION.md`, `API.md` et `NOTICE` ne s'écrivent pas
      à la main.** Le job CI `docs` les **génère** depuis la source unique
      (34.3), et R-430 fait échouer le build sur une option de configuration
      non documentée (T-021). Écrire ces trois fichiers à la main crée
      exactement la divergence que la checklist finale interdit
      (« documentation complète, générée sans divergence »).

      La documentation n'est pas une tâche de fin de projet : « documentation à
      jour (fichier .md + Javadoc/rustdoc) » est une case de la Definition of
      Done de **chaque** composant (PARTIE 37.3), et R-2172 refuse le statut
      `STABLE` à un composant sans sa documentation.

      Côté développeurs tiers, la release publie `axion-api` en jar + sources
      + **javadoc** (34.4). R-1754 : chaque méthode de l'API documente son
      effet, le thread autorisé, son coût et ses conditions d'échec.

      **La documentation se publie comme un site MkDocs Material**
      ([ADR-101](../docs/decisions/ADR-101.md)) : les `.md` restent la source
      unique versionnée, le site est un artefact de build régénéré depuis eux.
      Les conventions d'écriture — encadrés `!!! warning`, tableaux
      « nom, type, description, défaut », formules LaTeX, lien vers la page
      suivante — s'appliquent **dès le premier fichier écrit**, pour éviter une
      reprise complète en M12. L'organisation est par public (utilisation,
      création de contenu, référence technique, développement), pas par ordre
      alphabétique ; le tableau est dans l'ADR.
- [ ] **`docs/decisions/`.** Le CDC veut une copie des ADR-001..ADR-026, qui ne
      vivent aujourd'hui que dans le CDC (`sed -n '6891,7004p' ...`). Les ADR
      propres au dépôt commencent à ADR-100 pour éviter toute collision.
- [ ] **Cibles Rust dans `rust-toolchain.toml`.** Les cinq cibles de la matrice
      déterministe y sont listées, ce qui fait télécharger cinq bibliothèques
      standard sur chaque poste. Si c'est trop lourd, ne garder que la cible
      hôte et laisser la CI ajouter les autres.
- [ ] **Dépôt sans remote.** `git remote -v` est vide : les commits restent
      locaux. Rien n'est poussé tant qu'un remote n'est pas configuré.

- [ ] **glam et déterminisme.** `glam` sélectionne des chemins SIMD selon la
      cible, et `Vec3A` est explicitement un type aligné SIMD. C-16 exige des
      résultats bit-identiques entre client et serveur sur la matrice de
      validation déterministe, sans contraction FMA ni réassociation. **Avant
      d'écrire le noyau déterministe (M1), vérifier quels types et quelles
      opérations de `glam` sont utilisables dedans**, et documenter le verdict :
      il est probable que le noyau doive s'en tenir à `f32` scalaire, `glam`
      restant réservé au reste du moteur.
