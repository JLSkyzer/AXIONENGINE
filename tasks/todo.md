# AXION ENGINE — plan de travail

Source de vérité : `cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md` (8270 lignes,
FINAL / FROZEN). Ne jamais le lire en entier : passer par
[l'index](../docs/spec/INDEX.md) et lire la plage de lignes utile.

Règles de travail : [docs/AGENT.md](../docs/AGENT.md).
Leçons apprises : [tasks/lessons.md](lessons.md) — à relire à chaque session.

---

## État courant

**Jalon M0 : terminé, Definition of Done prononcée le 2026-09-10.**
**Jalon M1 : terminé, Definition of Done prononcée le 2026-10-04** — voir
« Definition of Done de M1 », avant la section M3.
**Jalon en cours : M3 — physique et premier rendu.**

**CI disponible de nouveau depuis le 2026-10-03** : le dépôt est public, les jobs GitHub
Actions sont gratuits sur les quatre plateformes de la matrice (run 37147205392, vert). En
secours, `python tools/ci/local_ci.py` rejoue les jobs sur la machine hôte.

Ce qui est en place :

- [x] Arborescence conforme à la PARTIE 33 : `java/axion-api`, `java/axion-mod`,
      `crates/`, `docs/`, `tools/`, `tasks/`.
- [x] Build Gradle multi-projet vert. `./gradlew build` produit
      `java/axion-mod/build/libs/axion-1.0.0-SNAPSHOT.jar`, qui contient
      `dev/axion/**` API comprise, `META-INF/mods.toml` et `axion.mixins.json`.
- [x] `axion-api` publie aussi son propre artefact (R-1750) et ses sources.
- [x] Identité figée : `mod_id` = `axion`, paquet `dev.axion`, Apache-2.0.
- [x] **ABI 2** depuis [ADR-103](../docs/decisions/ADR-103.md) : `axion_init`
      reçoit le côté de démarrage, `axion_metrics_export` rend l'export JSON.
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
- [x] **M0.10** Acceptance. **Serveur dédié et client validés.**
      Sur `runServer`, log à l'appui, zéro erreur :
      - mod construit sur `forge-47`, bibliothèque native extraite et chargée
        depuis `run/axion/native/<sha256>/` après vérification d'empreinte ;
      - aller-retour FFI mesuré à 100 ns — cohérent avec l'hypothèse H-07, qui
        annonçait 20 à 100 ns, sans que rien n'ait été supposé ;
      - `/axion status` répond avec l'état, la phase, le coût mesuré et les
        diagnostics ;
      - arrêt propre : `RUNNING_SERVER -> STOPPING -> UNLOADED`, contexte natif
        fermé **code 0** — bilan d'allocations équilibré (R-322) ;
      - `general.enabled=false` : serveur démarré normalement, cause affichée,
        zéro erreur. Le jeu reste jouable, ce qu'exige l'acceptance du jalon.

      Trois défauts trouvés, qu'aucun test n'aurait montrés :
      - le constructeur du mod prenant `IEventBus` n'existe pas sur Forge 47 ;
      - `build/natives` est déjà pris par ForgeGradle ;
      - le bootstrap ne relâchait pas son tampon de contrôle — trouvé par le
        bilan R-322 dès sa première exécution réelle.

      **Client validé aussi**, log à l'appui, zéro erreur : mod chargé,
      natif extrait et chargé, aller-retour FFI mesuré, `/axion status`
      répond dans un monde solo, arrêt propre contexte fermé code 0. La seule
      exception du log vient de Realms, qui ne s'authentifie pas sur un compte
      de développement — vanilla, sans rapport avec AXION.

      **Quatrième défaut, trouvé dans le log du client** : quitter un monde
      solo pour revenir au menu principal émet `ServerStoppingEvent`, que le
      cycle de vie traitait comme la fin du processus. Le contexte natif était
      donc fermé, et AXION restait mort pour tout le reste de la session —
      silencieusement : le monde suivant affichait « inactif / phase UNLOADED »
      sans qu'aucune erreur ne soit journalisée. Corrigé :
      - `GameShuttingDownEvent` (émis par `Minecraft` comme par
        `DedicatedServer` sur Forge 47) marque la fin du processus ;
      - `ServerStoppingEvent` ne ferme le contexte que si le processus s'arrête
        ou si l'on est sur un serveur dédié ; sinon le cycle revient en
        `RUNNING_CLIENT`, transition que la machine à états prévoyait déjà ;
      - `RUNNING_CLIENT` était par ailleurs inatteignable : `onClientStarted`
        n'était branché nulle part. Le premier tick client y fait entrer.

      Correction vérifiée sur un client réel, log à l'appui : monde solo →
      `actif`, retour au menu → `RUNNING_SERVER -> RUNNING_CLIENT` sans
      fermeture du contexte, monde rechargé → `actif` de nouveau, fermeture du
      jeu → `contexte natif fermé, code 0`. Zéro erreur. Vérifiée aussi par
      mutation : forcer l'ancien comportement fait échouer le test.

      Les logs serveur sont conservés hors de `run/logs/`, que `runClient`
      archive en le remplaçant.

### Definition of Done de M0

Prononcée le 2026-09-10. Acceptance du CDC (PARTIE 37.2) :
`sed -n '7180,7189p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md`

- [x] **JAR installable.** `validateJar` : 62 entrées, `META-INF/mods.toml`,
      `axion.mixins.json`, une bibliothèque native avec son empreinte, aucune
      classe hors de `dev/axion/`, aucune dépendance shadée. Build reproductible
      (R-2342), horodatages normalisés.
- [x] **Boot `READY`.** Serveur dédié et client lancés pour de vrai : natif
      extrait et chargé après vérification SHA-256, handshake ABI, aller-retour
      FFI mesuré, `/axion status` complet. *Sur `windows-x86_64` seulement* —
      voir la dette « Plateformes non vérifiées ».
- [x] **Natif absent → `DISABLED`, jeu jouable.** Vérifié sur un lancement réel
      en retirant les natifs du classpath : `NATIVE_UNAVAILABLE :
      RESOURCE_MISSING`, serveur démarré et arrêté normalement, **zéro erreur**.
- [x] **ABI incompatible → `DISABLED`, jeu jouable.** Vérifié en construisant
      un natif portant réellement `AXION_ABI_VERSION = 2` : `ABI 2 côté natif,
      1 attendue — réinstaller le JAR complet`, aucun contexte ouvert, **zéro
      erreur**. Constante restaurée et natif reconstruit après coup.
- [x] **Bilan d'allocations nul.** `contexte natif fermé, code 0` à chaque
      arrêt, serveur comme client (R-322). C'est ce bilan qui a révélé le
      tampon de contrôle non relâché en M0.10.
- [x] **Tests du jalon présents et verts.** T-100..T-103, T-110..T-114,
      T-120..T-124, T-130..T-133, T-150..T-152 — vérifié **mécaniquement**
      (R-2392) par `MilestoneTestsCoveredTest`, qui lit la liste dans le CDC
      lui-même et échoue si l'un d'eux disparaît. Vérifié par mutation.
- [x] **Suites vertes.** 86 tests Java, 66 tests Rust, `cargo clippy` sans
      remarque.
- [x] **R-2341 rendu effectif.** Un JAR ne portant pas les quatre plateformes
      supportées se déclare `partial` en release et `validateJar` refuse de le
      publier. Le trou était réel : la cible attendue était la seule cible
      hôte, donc un artefact Windows seul passait pour complet.

Réserve, portée en dette et non masquée : « sur les plateformes » n'est tenu
que sur une seule. Rien d'autre du critère d'acceptance n'est en suspens.

---

## M1 — Assets, noyau déterministe, jobs

Fiche : `sed -n '7191,7199p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md`

- [x] **C-12 — Job System.** Crate `ax-jobs`, 33 tests.
      - pool **dédié** au-dessus de `rayon` (R-470). Le pool global est partagé
        par tout le processus, donc par les autres mods : le configurer
        reviendrait à décider pour eux, y soumettre son travail reviendrait à
        attendre derrière le leur. Vérifié **statiquement** — aucun test
        dynamique ne le pourrait, l'appel réussissant une fois par processus ;
      - dimensionnement R-471 et partage CPU de la PARTIE 27.4, avec les quatre
        formes de `cpu_share` que R-2060 exige (`full | half | auto | <n>`) ;
      - annulation **coopérative** et deadlines : un travail en dépassement est
        marqué, jamais tué, et son résultat reste repris au cycle suivant
        (R-472). Le temps passé en file n'est pas imputé au budget ;
      - granularité adaptative visant 100 µs par tâche, **mesurée** ; une mesure
        inexploitable est ignorée plutôt qu'extrapolée (R-473) ;
      - huit types de travaux, chacun rattaché à un budget de DM-18 et portant
        ses propres métriques de consommation et de dépassement (R-474, INV-19) ;
      - une panic dans un travail est capturée : elle ne traverse pas le pool,
        elle est comptée, et le travail suivant s'exécute normalement (INV-05).

      **Deux points ouverts, consignés et non masqués :**
      - `ANIM` et `CULL` s'imputent tous deux sur `budgets.render_prep_ns` :
        DM-18 ne déclare pas de budget d'animation, et sa structure `Budgets`
        traverse la frontière en `repr(C)` — lui ajouter un champ modifierait un
        modèle de données, ce qui ne se décide pas seul. Leurs métriques restent
        distinctes, donc leur consommation reste distinguable ;
      - **câblé au contexte natif** depuis [ADR-103](../docs/decisions/ADR-103.md) :
        `axion_init` reçoit le côté, le pool naît avec la session et meurt avec
        elle. Vérifié sur un serveur dédié réel — `axion.jobs.workers : 8`,
        c'est-à-dire le plafond serveur de R-471.
- [x] **C-15 — Télémétrie native.** Crate `ax-telemetry`, 21 tests, plus le
      registre des budgets dans `ax-model` et l'audit T-007.
      - registre de métriques figé après déclaration : mesurer coûte un
        `fetch_add` atomique sur une case désignée par index, sans recherche par
        nom ni verrou. Un nom hors convention `axion.<domaine>.<mesure>` est
        **refusé**, pas corrigé — une métrique mal nommée est invisible de qui
        la cherche, ce qui est pire que son absence ;
      - trois types : compteur, jauge, durée. Une durée retient cumul, nombre de
        mesures et maximum ; sa moyenne se déduit plutôt que de s'entretenir ;
      - **chaque** budget du registre porte sa métrique de consommation et sa
        métrique de dépassement, déclarées d'un bloc pour qu'aucun ne soit
        oublié (R-500, R-1850, INV-19). Vérifié sur le serveur : 42 métriques ;
      - export JSON versionné (R-502), écrit à la main — la table 32.2 écarte
        un analyseur JSON du runtime, le JSON étant lu côté Java par Gson.
        Rien n'est écrit si la capacité ne suffit pas : un JSON tronqué n'est
        pas un JSON ;
      - `/axion metrics` résume les métriques non nulles, `/axion metrics
        export` écrit le document dans `<gameDir>/axion/metrics-<horodatage>.json`.

      **Deux points ouverts :**
      - le coût de la télémétrie sous 1 % du composant mesuré (R-501) n'est
        **pas mesuré** : c'est le benchmark B-08, avec les autres en M12. La
        conception y tend, ce qui n'est pas la même chose ;
      - `axion.ffi.roundtrip_ns` (R-330) est mesuré au démarrage et affiché par
        `/axion status`, mais n'est pas encore une métrique du registre natif :
        il faudrait que Java pousse la valeur mesurée.
- [x] **C-16 — Noyau déterministe.** Crate `ax-det`, 62 tests, 10 000 vecteurs
      d'or. Mené à l'effort maximal, comme le marqueur l'exigeait.
      - **scalaire, et c'est la réponse à la dette « glam et déterminisme »**
        ([ADR-104](../docs/decisions/ADR-104.md)) : R-510 n'autorise que
        `+ - * / sqrt` et les fonctions de `det`, toutes scalaires, donc R-460
        et C-16 ne se contredisent jamais. Un chemin déterministe qui manipule
        un vecteur le traite composante par composante ;
      - **aucune fonction de libm**, pas même `round`, `abs` ou `is_finite` :
        « exactement spécifiée » et « identiquement implémentée partout » ne
        sont pas la même chose. `finite(x)` s'écrit `x - x == 0.0`, et
        l'arrondi demi-loin-de-zéro passe par la troncature entière ;
      - **`DetRng`**, PCG32 semée par `(assembly_uuid, impact.seq)` (R-512),
        sans générateur global, avec rejet sans biais modulo ;
      - **empreinte de champ** XXH64, version épinglée au correctif près et
        ancrée par un vecteur officiel : une empreinte qui changerait ferait
        diverger toutes les assemblies déjà répliquées ;
      - **matrice de validation** (5.12bis) et empreinte de configuration lue
        des `target_feature` réellement compilés, donc sensible à un
        `-C target-cpu=native` que le triplet ne trahirait pas ;
      - **repli à deux niveaux** : préventif au handshake (`negotiate`), curatif
        au seuil (`DivergenceTracker`, T-820d). Jamais un refus, jamais une
        fonctionnalité retirée (R-514, R-515) ;
      - **vecteurs d'or (R-513)** : 10 000 cas d'entrée-sortie versionnés dans
        `crates/ax-det/tests/golden/kernel-v1.txt`, engendrés par une source
        d'entrées **indépendante du noyau**, pour qu'une différence entre deux
        versions du fichier soit exactement la liste des sorties qui ont bougé.
        Tête systématique — produit croisé des valeurs remarquables, balayage
        dense du domaine, voisinage des demi-pas à l'ULP près — puis queue
        aléatoire. Vérifiés par mutation : réécrire `x·x·(3 - 2x)` en
        `3x² - 2x³`, algébriquement neutre et que tout autre test accepterait,
        fait diverger 510 cas d'un ULP ;
      - **T-820 sur le binaire produit** : la contraction en multiplication-
        addition fusionnée y est constatée absente, et non promise par un
        drapeau de compilation ;
      - **T-821, T-822** : mille impacts rejoués indépendamment donnent le même
        champ, et répartir les nœuds sur 1 à 16 fils n'y change rien.
      - Deux défauts trouvés en préparant le corpus pathologique, corrigés :
        `quantize_i8` quantifiait une valeur très positive en pas très
        **négatif** quand la division débordait — un pas subnormal suffisait —,
        et `dequantize_i8` rendait un infini pour un pas dont la plage ne tient
        pas dans un `f32`, ce que le module s'interdit explicitement.
- [x] **C-24 — Conteneur A3D.** Crate `ax-asset`, 36 tests, format de la
      PARTIE 7 écrit et relu octet pour octet.
      - en-tête de 64 octets, table de sections de 32 octets par entrée,
        sections alignées sur 16 (R-881), little-endian ;
      - **rien n'est cru sur parole** : le CRC d'en-tête couvre `total_size`,
        donc toutes les vérifications de bornes qui s'appuient dessus (R-900) ;
        chaque taille annoncée est plafonnée avant allocation (R-901) ; chaque
        charge utile est comparée à son CRC **avant** décompression (R-882), et
        la décompression est bornée par une taille connue d'avance (R-902) ;
      - un tag inconnu est ignoré proprement (R-880), une version mineure
        supérieure se lit (R-891), une majeure inconnue est refusée (R-890) ;
      - compression zstd par section, avec repli sur le stockage brut quand elle
        n'y gagne rien ; sortie **déterministe**, remplissage nul ;
      - chargement partiel par masque de sections (objectif 7.1) : un serveur
        dédié ne charge ni matériaux ni textures ;
      - **fixture versionnée** `tests/fixtures/a3d/v1.1-minimal.a3d` : le test
        la relit et vérifie que l'écrivain la reproduit octet pour octet. Un
        changement de format fait échouer ce test, ce qui pose la question de
        R-893 — version incrémentée, migration écrite, ancien fichier conservé —
        au bon moment.

      **Deux points relevés dans la spécification, tranchés et documentés :**
      - la PARTIE 7 écrit `u32 magic = 0x41_33_44_00 ("A3D ")`, deux notations
        qui ne coïncident pas en little-endian. Ce sont les **octets** qui font
        foi, comme pour l'en-tête des tampons partagés : un fichier A3D commence
        par `A3D ` lisible dans un éditeur hexadécimal ;
      - la table des sections nomme `PART_SET`, qui ne tient pas dans les quatre
        octets d'un tag. `PSET` est la forme retenue, `PART` étant déjà pris.

      **Ce que le fichier ne contient pas encore** : le *contenu* des sections.
      `NodeDesc`, `MeshDesc`, `Vertex`, `ColliderDesc` et les autres viennent
      avec les composants qui les produisent. Le conteneur les transporte sans
      les interpréter, ce qui est exactement son rôle.
- [x] **C-21 — Importers.** Les quatre formats : glTF, GLB, OBJ, STL.
      40 tests d'import plus 4 de bout en bout.
      - les trois règles qui encadrent une source s'appliquent avant tout :
        R-533 refuse une source trop volumineuse **sans lecture complète** —
        refuser après avoir lu 4 Gio ne protège de rien ; R-531 refuse tout
        chemin absolu ou remontant, antislash et lettre de lecteur compris ;
        R-532 **désigne** les images sans les décoder — un décodeur d'image est
        la plus large surface d'attaque qu'un format d'asset puisse offrir, et
        celui de Minecraft est déjà là et déjà audité ;
      - **STL** : binaire et ASCII, normale par facette reportée sur les
        sommets — la lecture fidèle du format, lisser déciderait à la place de
        l'auteur. Le dénombrement de triangles de l'en-tête est comparé à la
        taille réelle **avant** d'appeler l'analyseur : quatre octets annonçant
        quatre milliards de triangles sont le vecteur d'attaque le plus simple
        du format ;
      - **OBJ** : meshes, UV, normales, matériaux et `mtllib`. Les `mtllib`
        sont vérifiés sur le texte source, avant que l'analyseur ne résolve
        quoi que ce soit. Les quads sont triangulés ;
      - les coordonnées de texture **avant** normalisation sont conservées : ce
        sont elles que R-142 borne, et une fois quantifiées en `UNORM16` elles
        ne diraient plus rien. Le validateur les reçoit à part — c'est le
        paramètre que C-22 attendait déjà.

      - **glTF et GLB** : hiérarchie remise **en ordre topologique** — glTF
        n'en impose aucun, R-130 l'exige, et le parcours en largeur depuis les
        racines le donne sans tri après coup ; une primitive donne un mesh ;
        matériaux, squelettes plafonnés à 128 os, poids d'os requantifiés pour
        sommer exactement 255. Rien n'est converti : la convention de glTF —
        Y vers le haut, main droite, une unité pour un bloc — est déjà celle
        d'AXION, et une conversion silencieuse est ce qui fait qu'un modèle
        arrive à l'envers sans que personne ne sache où ;
      - **extensions (R-530)** : la politique est appliquée **avant** l'analyse,
        sur le JSON brut. La déléguer à la bibliothèque ferait dépendre ce
        qu'AXION accepte de ce qu'elle implémente — et elle n'en modélise que
        cinq des huit que R-530 déclare supportées. Un asset aurait été refusé
        pour la mauvaise raison ;
      - **annotations `axion` (PARTIE 8.2)** : rôles, part, matériau, LOD,
        groupe de déformation, profil d'usure. R-910 : un collider, un socket,
        une zone de dommage, une région ou un ancrage n'est jamais rendu.
        R-911 : ce qui n'est pas compris est **conservé** pour la section
        `EXTR`, jamais deviné. R-912 : un rôle inconnu avertit et retombe sur
        le défaut. R-913 : un GLB sans la moindre annotation produit un asset
        complet, ce qu'un test vérifie ;
      - le décodage base64 des URI `data:` est écrit ici : trente lignes ne
        justifient pas une dépendance de plus, et celle-ci se placerait sur un
        chemin qui lit des données hostiles.

      **Ce que l'import glTF ne fait pas encore** : les animations, et
      l'interprétation des annotations profondes — régions de déformation,
      liaisons structurelles, zones de dommage. Elles demandent de résoudre des
      noms, de calculer des OBB et de construire un graphe : c'est C-28, et
      leur JSON est conservé pour lui.

      **Non fait ici, et non oublié** : ni tangentes, ni décomposition convexe,
      ni optimisation de cache de sommets. C'est C-23, et les mélanger rendrait
      chacun invérifiable.
- [x] **C-22 — Validateur d'assets.** 42 tests d'acceptance, plus les DM
      transcrits dans `ax-model` avec leurs tests de disposition.
      - la liste de contrôle de la fiche 5.15, **groupe par groupe** :
        structure, limites, géométrie, UV, normales, skin, physique,
        déformation, graphe structurel, noms ;
      - chaque violation est **nommée et localisée** (R-541) — « sommet 148 372 »
        et non « poids non normalisés », qui n'aide personne sur un modèle de
        deux millions de sommets ;
      - la validation **ne s'arrête pas à la première** : un auteur qui corrige
        son modèle veut la liste, pas un défaut à la fois ;
      - réparations de R-542 en liste **fermée**, chacune journalisée. Des poids
        de somme nulle ne sont **pas** réparés : les répartir au hasard
        produirait un mouvement absurde plutôt qu'une erreur visible.

      **Trois points relevés en chemin :**
      - le contrôle des UV porte sur les valeurs **avant** normalisation, celles
        que R-142 borne à `[-8, 9]`. Une fois l'asset compilé, ce sont des
        `UNORM16` : la question ne se pose plus. Le validateur les reçoit donc
        à part, et la tranche est vide au chargement ;
      - il n'y a **pas** de détection de cycle dans la hiérarchie de nodes.
        R-130 exige l'ordre topologique, et l'exiger rend un cycle impossible :
        une détection séparée serait du code inatteignable. Le graphe de parts,
        lui, n'a pas cette contrainte et se parcourt ;
      - T-023 ne lisait que les codes d'erreur exacts de l'ANNEXE A.1, alors
        qu'elle écrit certaines familles comme des plages — `E-3020..E-3060`.
        Tout ce qui est entre les bornes passait pour non documenté. Corrigé.

      **Ce qui reste hors de portée pour l'instant** : la seconde passe de
      R-540, au chargement, demande de reconstruire une `AssetView` depuis les
      sections d'un A3D. Les sections ne portent pas encore leur contenu — c'est
      C-21 qui le produira.
- [x] **C-20 — Orchestrateur.** Complet, vérifié sur un serveur dédié réel.
      - **chaîne de compilation** : une source entre, un A3D sort — importer
        (C-21), valider (C-22), écrire (C-24). Le refus se produit au plus tôt,
        parce qu'à chaque étape franchie le coût du refus augmente. Sortie
        déterministe, ce qu'un test vérifie : sans cela la clé de cache ne
        dirait rien, deux compilations d'une même source produisant deux
        entrées ;
      - **IF-06 câblé** : `axion_asset_compile` lit la source dans `ASSET_IN`,
        soumet un travail `ASSET` au pool de C-12 et rend un identifiant ;
        `axion_asset_poll` sonde, dépose l'A3D dans `ASSET_OUT` et rend la
        taille. La compilation ne touche jamais le thread appelant (R-521), et
        aucun rappel ne remonte de Rust vers Java (INV-07) ;
      - la source est **copiée** hors du tampon partagé avant de partir sur un
        worker : le tampon peut être réalloué au tick suivant (R-270), et le
        worker travaillerait alors sur de la mémoire qui ne lui appartient plus ;
      - un travail dont le résultat a été repris est **oublié** : le redemander
        rend `E-2001`, ce qui vaut mieux qu'une seconde lecture d'un tampon qui
        a pu changer entre-temps.

      - **orchestrateur Java** : machine à états SM-01 avec transitions
        vérifiées — sauter un état sauterait ce qu'il garantit, la validation
        de C-22 pour ne citer qu'elle ; clé `sha256(contenu || options ||
        COMPILER_VERSION)`, SHA-256 et non une empreinte rapide parce qu'une
        collision ici ne produit pas une erreur mais **le mauvais asset**,
        silencieusement ; sondage borné par `budgets.asset_ns_per_tick`, le
        budget étant vérifié **entre** deux assets et jamais au milieu d'un —
        abandonner une soumission à moitié faite laisserait un travail en vol
        que personne ne sonderait plus, et le dépassement est rapporté ;
      - R-520 : seuls les assets dont la clé a changé repartent ; une source
        disparue quitte le registre, ce qui distingue un pack rechargé d'un pack
        qui grossit sans fin ; une source corrigée reprend sa chance ;
      - R-522 : un asset refusé est journalisé **une fois** — le répéter à
        chaque tick noierait tout le reste — et `resolve()` rend
        `axion:builtin/missing`. Le monde se charge, la pièce manquante se voit,
        rien ne s'arrête ;
      - `NativeAssetCompiler` relie l'orchestrateur à IF-06. Le tampon est
        **ré-acquis à chaque soumission** : R-270 autorise sa réallocation entre
        deux ticks, et une vue conservée écrirait dans de la mémoire qui ne lui
        appartient plus. Vérifié sur la vraie bibliothèque — un OBJ écrit depuis
        Java ressort compilé.

      - `ResourceAssetSource` lit les fichiers **au moment de l'énumération** :
        un `ResourceManager` est remplacé à chaque rechargement, et le garder
        pour lire plus tard reviendrait à lire dans un gestionnaire périmé.
        L'énumération a lieu dans la phase de préparation, hors du thread
        principal, R-521 interdisant de le bloquer ;
      - asset de secours `axion:builtin/missing` embarqué (R-522) : un cube
        d'un bloc, volontairement le plus simple possible — il doit compiler
        partout où AXION démarre, sans quoi le repli aurait besoin d'un repli ;
      - barrière de démarrage de R-521, et `/axion status` montre les assets
        prêts, refusés et leur total.

      **Vérifié sur un serveur dédié réel** : `1 asset(s) à compiler`,
      `1 asset(s) prêt(s) sur 1`, `contexte natif fermé, code 0`, zéro erreur.

      **Deux défauts que seul un lancement réel pouvait montrer :** la
      découverte exigeait une phase en cours alors que Forge émet le
      rechargement **avant** le démarrage du serveur — elle passait à côté à
      chaque fois, en silence ; et les tampons `ASSET_IN`/`ASSET_OUT` n'étaient
      jamais rendus, ce que R-322 a signalé au premier arrêt.

      **Dette levée (R-562).** Le contrôle CI existe :
      `tools/ci/check_compiler_version.py` refuse tout écart entre
      `ax_asset::compile::COMPILER_VERSION` et
      `dev.axion.asset.CompilerVersion.CURRENT`, ajouté au workflow comme job
      `compiler-version`. Il a trouvé une dérive **réelle** dès son écriture :
      le Rust était passé à 5 (C-23 A/B/C puis section `NODE`), le Java était
      resté à 1 — la clé de cache ne reflétait donc plus la version du
      compilateur, et un asset périmé aurait été repris en silence. Java remis
      à 5 ; les tests d'asset T-260..T-263 restent verts.
- [x] **C-25 — Cache d'assets.** Vérifié sur deux démarrages successifs d'un
      serveur dédié réel : le second ne compile rien.
      - entrées sous `<gameDir>/axion/cache/<2 hex>/<clé>.a3d`, **hors du
        monde** (R-563, INV-10) — un cache rangé dans une sauvegarde la ferait
        grossir de données reconstructibles et la rendrait non transportable ;
      - magic, version de schéma et CRC32C sur chaque entrée (R-560). Le fichier
        A3D a les siens, mais ils ne couvrent que ce qu'ils décrivent : une
        entrée tronquée par un disque plein resterait un en-tête A3D valide
        suivi de rien. Une entrée invalide est **supprimée**, pas réparée ;
      - éviction LRU sous `assets.cache_max_bytes` (R-561). Une entrée employée
        repasse en queue de file, sans quoi le cache évincerait précisément ce
        qui sert ;
      - écriture par fichier temporaire puis renommage : une entrée n'apparaît
        que complète ;
      - l'index n'est pas la vérité, les fichiers le sont. Un index corrompu ne
        coûte qu'un parcours d'arborescence au démarrage suivant.

      **La clé gagne un terme.** La fiche C-20 en énumère trois, la fiche C-25
      quatre — l'ABI en plus. C'est la seconde qui fait foi, puisque c'est elle
      qui spécifie le cache : sans ce terme, un asset resterait en cache après
      une mise à jour de la frontière qui en change la lecture.
- [ ] **C-23** Optimizer (M2). Placé entre C-22 et C-24, comme le veut le CDC
      (ligne 496) : il reçoit un asset validé, et sa sortie repasse la même
      liste de contrôle, sans exemption, avant d'être écrite.
      - [x] **Tranche A — fusion, normales, boîtes** (étapes 1, 2, 7).
            `crates/ax-asset/src/optimize/`. `COMPILER_VERSION` passe à 2.
            - fusion **par tri**, départagé par l'index d'origine : aucune
              `HashMap`, dont l'ordre est semé au hasard (R-553, T-243) ;
            - tolérances déclarées dans `merge.rs` : aucune sur la position,
              le pas de quantification sur le reste. Rapprocher deux positions
              pourrait aplatir un triangle que C-22 a accepté ;
            - normales pondérées par l'angle, arc tangente écrite avec les
              seules opérations IEEE exactement arrondies — pas de `libm` ;
            - boîte d'asset par la chaîne des nodes, arrondie vers l'extérieur.
              Le format A3D n'a pas de champ pour elle : elle est rendue dans
              `CompiledAsset::bounds`.
            - **normale absente ≠ normale nulle.** Les importeurs écrivaient
              `[0, 127, 0, 0]` pour une normale absente, indiscernable d'un `+Y`
              écrit par l'auteur. Ils la marquent désormais dans
              `ImportedAsset::missing_normals` ; C-22 l'exempte, C-23 la génère.
              Une normale écrite nulle (`vn 0 0 0`) est refusée.
            - **STL : un sommet par coin de facette.** `stl_io` fusionne les
              sommets par position, et la dernière facette écrasait la normale
              de ses voisines sur toute arête vive. La fusion de C-23 refond
              ensuite ceux qui sont identiques en tout.
      - [x] **Tranche B — tangentes et cache de sommets** (étapes 3 et 5).
            `COMPILER_VERSION` passe à 3.
            - `mikktspace` et `meshopt`, comme le retient la table 32.2.
              Déterministes **par plateforme** seulement (`acos`, C++) : les
              assets ne passent jamais par le réseau (R-1640), et tangentes et
              ordre des indices ne servent qu'au rendu. Les étapes 8 et 9, dont
              la physique dépend, devront l'être entre plateformes.
              `docs/decisions/ADR-106.md`, qui consigne aussi que `mikktspace`
              est sous MIT OR Apache-2.0 et non Zlib.
            - tangentes générées si le matériau porte une normal map (glTF
              `normalTexture`, MTL `map_Bump`, `bump`, `norm`). Le parallax n'a
              aucune source à la compilation : le slot `height` est résolu au
              rendu par C-26.
            - tangentes glTF `TANGENT` lues et conservées, ignorées sans
              `NORMAL` comme l'exige glTF 2.0.
            - mesh déplié en coins, puis fusion rejouée : seuls les sommets de
              couture en miroir restent dédoublés.
            - `NOTICE` : les codes embarqués (meshoptimizer, MikkTSpace)
              attribués explicitement par `gen_notice.py`.
            - un compilateur C++ est désormais requis au build natif.
      - [x] **Tranche C — LOD** (étape 6, R-550, T-801). `COMPILER_VERSION`
            passe à 4. Décisions dans `docs/decisions/ADR-107.md`.
            - simplificateur de meshoptimizer ; un LOD **partage la plage de
              sommets** de sa source et n'ajoute que des indices. R-550 tient
              par construction : chaque sommet d'un LOD est un sommet source.
            - bords et coutures : topologie préservée sans `LockBorder`, qui
              empêcherait de simplifier une coque ouverte ;
            - section `LODM` : `u32 levels`, `u32 row_count`, puis une ligne
              de meshes par mesh source. Écrite seulement si un LOD existe.
            - **`lod_mask` par défaut : tous les niveaux** (`ALL_LODS`). La
              valeur `1` aurait fait disparaître tout node sans annotation au
              premier LOD, contre R-913.
            - LOD d'auteur par les masques, sans convention de nom : un mesh
              est simplifié depuis le plus bas niveau où il est visible, ratios
              relatifs à ce niveau ;
            - triangles aplatis retirés avec la fonction d'aire de C-22 ;
              niveaux restants abandonnés, avec avertissement, si le plafond
              de 6 000 000 indices serait dépassé.
            - **Reste dû** : le chargeur serveur (C-40) devra ignorer les
              meshes de LOD de `GEOM` ; le lecteur de `LODM` (C-64, M4) devra
              la valider au chargement (R-540) ; les options `lod` de
              `.axion.json` ne sont pas lues (valeurs de la PARTIE 6.4).
      - [ ] **Tranche D — reportée en M3, avec C-32** (décision de Killian,
            2026-09-13). Décomposition convexe bornée et points d'enveloppe
            (étapes 8 et 9, R-551).
            - pourquoi : l'étape 8 ne traite que les colliders `auto_convex`,
              que seul C-32 (M3) produit ; l'import ne crée aucun
              `ColliderDesc`. L'acceptance de M2 ne l'exige pas, et `parry3d`
              entrera avec `rapier3d`, à la même version.
            - voie retenue : `parry3d` avec `enhanced-determinism` (`libm`
              logicielle, sans SIMD, `indexmap`) — les points d'enveloppe
              pilotent le refit des deux côtés et doivent être identiques
              entre plateformes (ADR-106, point 3).
            - **budget déterministe** : travail borné par des paramètres fixes,
              temps mesuré et signalé sans changer la sortie. Écart avec la
              lettre de R-551 consigné dans `docs/decisions/ADR-108.md`.
      - **Ouvert, hors C-23** : un mesh glTF à plusieurs primitives ne rattache
        que la première à son node (`bind_meshes`). Les suivantes ne sont
        portées par aucun node, et la boîte d'asset les ignore donc aussi.
        Tâche séparée proposée.
- [x] **C-30 — Scene Graph.** Nouveau crate `ax-scene`.
      - colonnes de la fiche en tableaux parallèles, en lecture seule : les
        écritures passent par `set_local`, `set_physics_world`, `detach`,
        `set_revealed`, qui tiennent le suivi des nodes sales ;
      - propagation linéaire en ordre topologique, sans récursion ; seuls les
        nodes sales et leurs descendants sont recalculés, `changed()` dit
        lesquels ont bougé ;
      - R-600 : un node `PHYSICS_DRIVEN` reçoit sa transform monde avant
        propagation ; si seul son parent bouge, le body garde sa place et seule
        la locale suit. R-931 refusé à la construction (`E-7002`) ;
      - R-930 : une source ne pilote que les nodes de son état ; un node
        `STATIC` est constant ;
      - détachement d'un sous-arbre (débris), visibilité par drapeaux, parents
        et révélation des nodes `INTERNAL` (R-952) ;
      - `axion.scene.propagate_ns` mesuré par graphe ; `SceneGraph` est `Send`,
        la répartition entre assemblies revient à l'ordonnanceur du tick.
      - **états de node numérotés** dans `ax-model` (`node_state`) : le CDC
        les nomme sans valeur ; ordre de priorité de R-930, `STATIC` = 0.
      - codes d'erreur pris dans l'ANNEXE A.1 sans en créer : `E-3021`,
        `E-3050`, `E-2010`, `E-7002`, `E-2001`, `E-2030`.
      - **Ouvert** : les transforms passent par `glam` en SIMD. Si le graphe
        entre dans un chemin répliqué entre plateformes (M4), il faudra
        trancher avec la question du handshake : `glam` en `scalar-math`, ou
        transforms calculées côté serveur seulement.
- [ ] **C-74 — CLI `axion-cli`** (M2). Nouveau crate `ax-cli`. Tranche des
      commandes que la chaîne d'assets d'aujourd'hui (C-21..C-24) permet.
      - [x] `compile <src> -o <out.a3d> [--static-body]` : appelle
            `ax_asset::compile::compile`, **exactement le code du jeu** (R-830) ;
            plafonds tirés du registre (`assets.max_source_bytes`), `asset_id`
            et `source_hash` en FNV-1a 64 (mêmes vecteurs que `name_hash`),
            compilation déterministe (T-213). Références voisines lues à côté de
            la source, remontées et chemins absolus refusés (`sibling_path`).
      - [x] `inspect <file.a3d>` : en-tête, sections, et table des nodes décodée
            (noms, ADR-110), par `A3dFile` + `decode_nodes`.
      - [x] analyse d'arguments écrite à la main (aucune dépendance ajoutée,
            R-2300) ; `help` ; mésusage en code 2, échec de traitement en 1.
      - logique dans la bibliothèque `ax_cli`, testée sans disque ; le binaire
            n'ajoute que les I/O. 20 tests (T-580), essai de bout en bout
            `compile`→`inspect` concluant. `cargo test --workspace` vert.
      - **Reste dû** (jalons suivants, avec leurs composants) : `deform` et
        `replay` (C-28, C-41, C-42, M6), `diff`, `lod` sur un `.a3d`,
        `validate`, `bench-asset`, et l'option `--options <json>` (lecture des
        options de `.axion.json`, qui exige un analyseur JSON).
- [ ] **C-70 — API publique `axion-api`** (M2). Module Gradle `axion-api`,
      publié seul, sans dépendance à l'implémentation ni au natif (R-1750) ;
      dépend des types Minecraft/JOML qu'il expose. `gradlew :axion-api:build`
      vert, lint R-001 vert.
      - [x] **Tranche 1 — surface d'assembly et de dommage** (§23.2). 40 types.
            Décision de Killian (2026-09-13) : ce que le §23.2 spécifie est
            transcrit fidèlement ; les types seulement nommés sont déclarés en
            interfaces `@Stable` vides, documentées « complétées par C-xx », pour
            que la façade compile sans rien inventer.
            - façade `AxionApi` (accès par `ServiceLoader`, sans point d'entrée
              mutant, R-1750) ; `Assembly`, `AssemblyService`, `DamageService`,
              `ImpactSpec` (+`Builder`, validé/borné R-1762), vues `PartView`,
              `StructureView`, `SurfaceView`, `DeformationView`, `Socket` ;
            - annotations `@Stable`/`@Experimental`/`@Internal` (R-1752, aucun
              `@Internal` employé) ; enums `PartStage`, `QualityLevel`,
              `ImpactFlag`, `RepairLevel` ; records `ApiVersion`, `Transform`,
              `DefinitionRef` ;
            - chaque méthode documente effet, thread, coût et échec (R-1754) ;
              nullabilité en prose (pas d'annotation `@Nullable` externe).
      - [x] **Tranche 2** : les 18 événements du §23.2 (`AxionEvent` +
            `AssemblySpawnEvent`…`DegradationEvent`, interfaces `@Stable`) et
            l'abonnement typé de `EventBus`
            (`subscribe(Class<E>, Consumer<E>)` rendant un `Subscription`
            révocable ; R-1760/R-1761 documentés). Comme en tranche 1
            (décision de Killian), les types seulement nommés sont minimaux ;
            leurs accesseurs s'ajoutent de façon compatible (R-1751) à mesure
            de leurs composants. `:axion-api:build` vert, lint R-001.
      - [ ] **Tranche 3** : à mesure de leurs composants, la surface des
            services seulement nommés (physics, deformation, attachments,
            vehicle, animation…) et les accesseurs des événements. Ajouts
            compatibles en 1.x (R-1751).
      - **Note** : la CLI/Gradle passent par un JDK ; seul un JDK 21 est
        provisionné (`~/.gradle/jdks`), employé comme lanceur.
- [x] **C-72 — Harnais de benchmarks** (M2). Crate `ax-bench`. La
      plupart des B-xx (physique, rendu, véhicules, animation, particules,
      déformation) visent des sous-systèmes de M3/M6 ; le harnais ne mesure
      pas ce qui n'existe pas et n'inscrit aucun benchmark vide (R-001).
      - [x] **Tranche 1 — fondation** : format de résultat schéma 2 (PARTIE
            30.4), capture de plateforme, statistiques normatives
            p50/p95/p99/min/max/écart-type (rang le plus proche, PARTIE 30.3),
            archivage sous `benchmarks/results/` refusant toute provenance
            incomplète (R-2230, R-2231) ; version du harnais dans `config`
            (ADR-111). Une seule dépendance, `serde_json`, déjà dans l'arbre
            via `tools/codegen` (NOTICE inchangé). 21 tests, clippy et
            cargo-deny verts.
      - [x] **Tranche 2** : `criterion` (R-2240, `default-features = false`)
            et les benches des composants mesurables en Rust pur — B-02
            (scene graph, propagation vs nombre de nodes, `iter_batched` sur
            un graphe tout-sale) et B-09 (compilation d'asset, cube et
            grilles générées). `cargo bench` exécute les deux ; vérifiés en
            mode `--test`. B-07 (aller-retour FFI) exige un JVM pour mesurer
            la traversée → benchmark JMH, reporté en tranche 3. criterion et
            son arbre validés par cargo-deny ; NOTICE régénéré (84 → 102
            paquets).
      - [x] **Tranche 3** : chaîne de non-régression Rust complète. Primitif
            de mesure schéma 2 (`measure`, méthodologie PARTIE 30.3, horloge
            injectable donc testable), comparateur p95 (`compare_p95`, seuil
            20 %, R-2251 ; jamais entre harnais différents, ADR-111),
            `latest_baseline`, module `cases` partagé entre benches et
            coureur, et binaire `bench-ci` qui mesure le sous-ensemble rapide
            (R-2250), archive si la provenance est complète (R-2231) et bloque
            sur régression. Boucle archive→comparaison vérifiée de bout en
            bout. 34 tests, clippy et fmt verts ; aucune dépendance externe
            nouvelle.
      - [x] **Tranche 4** : `tools/bench/` (PARTIE 33 : « harnais,
            agrégation, comparaison »). Outil Python `bench.py` — `check`
            (provenance R-2231 des résultats archivés), `summary`, `compare`
            (p95, seuil 20 %, R-2250/R-2251) — lisant le schéma 2 quel que
            soit le harnais producteur, avec la même comparaison que
            `ax-bench::regress` (ADR-111). 16 tests ; vérifié sur les
            résultats réels de `bench-ci`.
      - [x] **Tranche 5** : JMH côté Java (R-2240) en scope test (arbre figé,
            `src/test/java`, « tests seulement », jamais embarqué dans le
            JAR). `B07FfiRoundtrip` mesure l'aller-retour FFI vide via
            `NativeBridge.nativeAbiVersion()` (point d'entrée existant, sans
            toucher l'ABI) ; tâche Gradle `jmh` chargeant la lib native depuis
            `AXION_NATIVE_DIR` (variable d'env, robuste aux espaces du chemin
            du dépôt). Vérifié : compile, tourne (~5,6 ns/op sur le natif
            réel), tests existants intacts.
      - [x] **Tranche 6** : pont sortie JMH → schéma 2 (`bench.py
            import-jmh`). Convertit le JSON de JMH (`-rf json`) en résultats
            schéma 2 archivés sous la même disposition qu'`ax-bench` :
            identifiant `B-xx` déduit du nom de classe, échantillons des
            `rawData`, provenance (commit, date, machine) en argument
            (R-2231), stats au rang le plus proche comme le crate. JMH
            rejoint `summary`/`compare` : tous les harnais alimentent un seul
            format. 26 tests ; vérifié sur la vraie sortie JMH de B-07.
      - [x] **Tranche 7** : B-07 facette « coût par élément en lot »
            (`B07FfiBatch`). Mesure le transfert d'un lot de N éléments par le
            tampon partagé (init contexte → acquire SIM_IN → écriture →
            release), `@Param` sur N ; vérifié sur le natif réel (64→254 ns,
            1024→521 ns) et scale. `tools/bench` rendu param-aware :
            archivage suffixé par les paramètres, `summary`/`compare`
            regroupés par (benchmark, plateforme, paramètres) — les deux
            facettes de B-07 coexistent sans se comparer l'une à l'autre.
            30 tests ; import des deux facettes vérifié.
      - [x] **Tranche 8** : harnais en jeu — sous-commande `/axion bench
            <scénario>` (C-71/C-72) et scénario `ffi`. `BenchRunner` mesure
            l'aller-retour FFI **par lot** (temps d'un lot / taille), ce qui
            passe sous la résolution de `nanoTime` (~100 ns) et rejoint le
            ~5 ns de JMH B-07 — là où la calibration de démarrage (R-330,
            `nanoTime` par appel) bute sur le plancher du timer. Refuse si le
            natif n'est pas prêt. Compile, `BenchRunnerTest` vert, tests
            axion-mod intacts. Constat du test en jeu : `/axion status`
            affichait 100 ns par cet artefact de timer.
      - [x] **Tranche 9** : sortie schéma 2 du harnais en jeu — `/axion bench
            ffi --json <fichier>` écrit un résultat schéma 2 (`SchemaTwoWriter`,
            Gson, provenance en jeu : os, cpu via `PROCESSOR_IDENTIFIER`,
            cœurs, JVM ; stats au rang le plus proche) lisible par
            `tools/bench`. Le harnais est marqué `config.harness =
            axion-ingame`, et `tools/bench` rendu **conscient du harnais**
            (archivage + `summary`/`compare` groupent par harnais) : la
            mesure en jeu de B-07 (~8 ns) ne se compare jamais à celle de JMH
            (~5 ns). 34 tests Python, 2 tests Java ; compile et suite
            axion-mod vertes.
      - [ ] **Tranche 10+** : scénarios en jeu à monde déterministe + entrées
            scriptées (tick complet B-16, frame B-17…) ; benches restants à
            mesure que leurs composants (physique, rendu, déformation…)
            arrivent.
- [ ] **C-50 — Axion Entity** (M2). Livrable : `/axion spawn` crée une
      AxionEntity persistante avec rendu de debug. Décisions de Killian du
      2026-09-13 : **hitbox provisoire d'un bloc** jusqu'à M3 (R-702 avec l'AABB
      physique ; la boîte de l'asset n'atteint pas Java sans étendre l'ABI) ;
      **rendu de debug = hitbox vanilla (F3+B) + libellé** de la definition,
      affiché seulement quand les hitbox le sont (C-67 et ses overlays en M3).
      - [x] `EntityType` unique `axion:assembly` (`AxionEntities`), `AxionEntity`
            hors de `LivingEntity` et `VehicleEntity`, `tick()` sans physique
            (R-700, R-701).
      - [x] NBT de §22.2 : `axion:v` (2) et `axion:def` (empreinte FNV-1a 64 de
            l'identifiant, `DefinitionIds`, mêmes vecteurs que `name_hash` en
            Rust), toute autre clé `axion:*` recopiée à l'identique ; definition
            inconnue ou version autre que 2 → inerte et intacte (R-704, R-1710,
            `AssemblyBinding`) ; collision d'empreinte : les deux definitions
            refusées (`E-3010`).
      - [x] données d'apparition : definition et état inerte, chaîne bornée
            comme toute chaîne réseau (R-703, pour ce qui existe).
      - [x] `/axion spawn <definition> [pos] [nbt]` (construite comme
            `/summon`), `/axion remove <selector>` (AxionEntity seulement),
            plafond R-811 (`AssemblySpawns`), journalisation R-810.
      - [x] rendu client : libellé de la definition, « inerte » le cas échéant
            et « hitbox provisoire », seulement quand les hitbox vanilla sont
            affichées ; classe abonnée pour le client seul.
      - 11 tests JUnit (T-400..T-404), suite Java à 172.
      - **Non vérifié en jeu** : aucun lancement de serveur ni de client n'a
        encore montré l'entité, sa sauvegarde ni son libellé. À faire avant de
        cocher le composant, avec un datapack contenant une definition.
      - Reste dû : assembly native créée par C-40 (M3), R-702 AABB physique
        (M3), persistance complète C-52 (M4), R-705 dégâts → `ImpactDesc` (M6),
        état des parts et empreinte de déformation dans R-703 (M6-M7).
      - [x] **R-702 — emprise physique courante → hitbox** (ADR-120, ratifié le
            2026-10-02). Constat en jeu (T2c) : après une bascule, la hitbox 1×1 ancrée
            à l'origine du corps flotte d'un demi-bloc à côté du cube.
            - [x] **T-a** `[EFFORT MAX]` — `BodyBounds` (24 o, relatif à la position,
                  axes du monde) dans `ax-model` + test de disposition ; calcul dans
                  `ax-physics` depuis la **pose rapportée** (pas le cache des colliders,
                  que R-181 peut laisser en retard d'un pas), même parcours que les
                  états ; dépôt dans `SIM_OUT` après `BodyState[]`, `schema_version` 1
                  écrit par `axion_buffer_acquire` ; test d'ABI ; lecture Java (JNI réelle).
                  Fait (2026-10-02) : emprise calculée sous la seule rotation du corps
                  (aucune soustraction, précision gardée loin de l'origine) ; 12 tests Rust
                  (disposition, appariement, orientation, union, pose restaurée R-181,
                  précision, emprise incalculable, ABI de bout en bout), dont deux prouvés
                  discriminants (la variante cache + soustraction les fait échouer) ;
                  `SIM_OUT_SCHEMA` généré ; Java : `BodyBounds`, `CollectResult.bounds`,
                  lecture conditionnée au schéma, JNI réelle. cargo 703 verts, clippy/fmt
                  propres, Java 264 verts.
            - [x] **T-b** — `AxionEntity.makeBoundingBox()` depuis l'emprise, contrôle
                  (finie, min ≤ max, ±1024), pont client `VECTOR3` × 2 interpolé, libellé
                  « provisoire » retiré à la première emprise ; vérifié en jeu (F3+B).
                  **Observé en jeu (2026-10-02)** : la hitbox englobe le cube dans toutes
                  ses poses, sans décalage après bascule ; la visée l'accroche (libellé
                  de debug « — visée », la ligne « Targeted Entity » de F3 étant réservée
                  aux entités vivantes). Arrêt du natif « code 0 ». Java 266 verts.
            - Hors portée, nommé : corps statiques (boîte provisoire), grandes assemblies
              (marge de recherche d'entités vanilla : 2 blocs X/Z, 4 en Y).
- [ ] **C-27 — Definitions data-driven** (M2). Périmètre décidé par Killian le
      2026-09-13 : **definitions seules** ; transfert au natif et format
      `CompiledDefinition` reportés en M3, avec leur premier consommateur (pas
      d'extension d'IF-01 en M2) ; les cinq autres familles viennent avec leurs
      composants (M3 à M5) ; la synchronisation serveur → client avec C-51 (M4).
      Lecture du schéma 1 fixée par `docs/decisions/ADR-109.md` : le §23.4 est
      un exemple, sans défauts ni casse ni graphie de `kind` écrits.
      - [x] **Tranche 1 — chargement.** Paquet `dev.axion.definition`, sans
            Forge ; 15 tests JUnit (T-280, T-281), suite Java à 144.
            - JSON strict construit jeton par jeton : `JsonParser` de Gson est
              permissif (commentaires, clés sans guillemets, **dernière de deux
              clés identiques gardée en silence**). Refusés : clé en double,
              contenu après la racine, BOM, UTF-8 invalide, imbrication au-delà
              de 64, nombre hors de la plage d'un double ; nombres gardés exacts.
            - `schema` absent ou non entier `E-7001`, autre que 1 `E-7003` ;
              `asset` `<ns>:<chemin>` vérifié contre les modèles découverts ;
              `kind` en `snake_case`, `vehicle` refusé si `modules.vehicles`
              est désactivé.
            - refus individuel nommé avec chemin JSON (R-580) ; identifiant
              `<ns>:<chemin>` ; empreinte SHA-256 de la registry, indépendante
              de l'ordre des packs (pour R-1640).
            - écouteur de rechargement après celui des modèles ; la registry est
              remplacée d'un bloc.
            - **non vérifié en jeu** : le chargement réel sur un serveur reste à
              observer au prochain lancement.
      - [x] **Tranche 2 — structure et références internes.** 12 tests JUnit
            (T-282, T-283), suite Java à 156. Précisions 10 à 16 de l'ADR-109.
            - schéma décrit **comme une donnée** (`SchemaNode`, `SchemaOne`) :
              le même arbre servira à publier `definition-1.json` (tranche 4),
              au lieu d'une seconde transcription qui divergerait ;
            - clés inconnues refusées hors `custom` ; objets renvoyés ailleurs
              (`powertrain`, `particles`, `procedural`…) remplis selon leur
              section ; `on_part_disabled` exclu, faute d'emplacement écrit ;
            - listes fermées C-34, DM-11, DM-14, DM-16, C-53, R-1170, A.4 ;
              plages et règles **identiques au validateur d'asset** (masse de
              part, fractions, quatre capacités > 0, liaison entre parts
              distinctes) ;
            - plafonds R-190 et C-22 en `E-3050`, le reste en `E-7001` ;
            - toutes les fautes d'une definition rendues en un seul refus,
              dans l'ordre du document ;
            - références internes résolues (bodies, joints, roues, parts,
              liaisons, zones, sièges, sockets, animations, particules), cycles
              de zones parentes refusés ; sources de l'ANNEXE A.4 analysées et
              leurs noms résolus. Nodes, meshes et régions : forme seulement,
              résolution en tranche 3.
      - [x] **Tranche 3 — noms dans la section `NODE`** (`docs/decisions/ADR-110.md`).
            Périmètre révisé par Killian le 2026-09-13 : la **résolution des
            noms d'asset** désignés par une definition (nodes, meshes, régions,
            clips, os, colliders) se fera **côté natif en M3**, à la
            transmission de la definition. Un lecteur A3D Java aurait doublé un
            analyseur de données hostiles pour ne résoudre que les nodes, les
            autres catégories n'existant dans l'asset qu'en M3.
            - `NODE` : `u32 count`, `u32 names_size`, nodes sur **80 octets**
              (l'écrivain de M1 en écrivait 76, sans le remplissage de fin :
              illisible en place malgré R-881), références de noms, noms UTF-8 ;
            - nom et empreinte liés : encodeur et décodeur refusent une
              empreinte qui n'est pas celle du nom ; décodeur borné avant
              allocation (R-901), `E-3007` ; fuzzé par `a3d_reader` ;
            - `name_hash` (FNV-1a 64) déplacé dans `ax-model` ; nodes OBJ et
              STL enfin hachés ; positions des champs de `NodeDesc` figées ;
            - **défaut corrigé** : un node glTF sans nom entrait dans la règle
              d'unicité de C-22 sous le nom « node », si bien que deux nodes
              sans nom faisaient refuser un glTF valide ;
            - `COMPILER_VERSION` passe à 5. 456 tests Rust.
      - [x] **Tranche 4 — schéma publié** (R-1783). 5 tests JUnit (T-284),
            suite Java à 161. **La part M2 de C-27 est terminée** ; la case du
            composant reste ouverte pour ce qui attend M3 (ci-dessous).
            - `docs/schema/definition-1.json`, JSON Schema 2020-12, **rendu**
              depuis l'arbre du validateur (`SchemaExport`), jamais écrit à la
              main : un test échoue dès que le fichier en diffère. Régénérer :
              `./gradlew :axion-mod:test --tests
              dev.axion.definition.PublishedSchemaTest -Daxion.schema.update=true` ;
            - ce que JSON Schema n'exprime pas (unicité des noms, références,
              cycles, module `vehicles`) en annotations `x-axion-*`, vérifié par
              AXION ; expression des sources procédurales testée contre la
              grammaire du vérificateur ;
            - la CI juge **toute** definition `data/<ns>/axion/definitions/**`
              du dépôt (hors `build`, `target`, `run`…) avec le validateur. Le
              dépôt n'en contient aucune aujourd'hui : les cinq exemples de
              R-1790 (T-630..T-634) seront jugés dès leur ajout. Aucun
              validateur JSON Schema tiers ajouté (§32.4, ADR-109 point 17).
            - piège noté : `JsonObject.isEmpty()` n'existe pas dans le Gson de
              Minecraft 1.20.1 ; `size()` à la place.
      - Reste dû hors M2 : `CompiledDefinition` et fonction d'ABI (M3, ADR) ;
        résolution des noms d'asset (M3, natif) ;
        `physics_materials`, `collision_groups`, `block_materials` (M3),
        `wear_profiles`, `repair_rules` (M5) ; synchronisation (C-51, M4).
- [x] **C-71 — Commandes.** Les branches dont les composants existent.
      `/axion status`, `/axion metrics [export]`, `/axion assets list | info
      <chemin> | reload`, `/axion config get <clé>`. Vérifiées sur un serveur
      réel.
      - `assets list` groupe par état plutôt que d'énumérer : sur un pack
        fourni, la liste complète dépasse ce qu'un chat peut montrer, et c'est
        la répartition qui dit si quelque chose ne va pas. Les refusés sont
        nommés — ce sont eux qu'on cherche en tapant la commande ;
      - `assets info` dit l'état, le format, la clé et les deux tailles. Un
        chemin inconnu est **nommé** plutôt que rendu « introuvable » : sur un
        pack fourni, c'est presque toujours une faute de frappe ;
      - `assets reload` force la recompilation, cache compris, et remet à zéro
        le drapeau de journalisation — une recompilation demandée expressément
        ne doit pas rester muette sur ce qui cloche. Commande mutante, donc
        **journalisée avec son auteur** (R-810) ;
      - `config get` distingue une option inconnue d'une valeur vide : les deux
        se ressemblent, seule la première se corrige.

      **Les autres branches attendent leurs composants** : `spawn`, `sim`,
      `damage`, `deform`, `repair`, `attach`, `debug`, `render`, `bench`,
      `defs`, `diag dump` et `compat`. Les écrire maintenant produirait des
      commandes qui ne pilotent rien, ce que R-001 interdit.

      `compat` a désormais sa moitié locale : C-16 sait dire l'empreinte de
      configuration et l'appartenance à la matrice. Il lui manque l'autre
      moitié — l'empreinte **distante** et le mode négocié —, qui vient avec le
      handshake de M4. Ce que la commande dirait aujourd'hui d'un mode de
      réplication serait une supposition, et 5.12bis la veut affichée avec sa
      cause. Exposer `det_profile` par la frontière native se fait donc avec le
      composant qui le consomme, pas avant.

### Fuzzing de la chaîne d'assets (R-903, T-680..T-682)

- [x] **Quatre cibles et leur corpus versionné.** `fuzz/`, workspace à part :
      `cargo-fuzz` exige une chaîne `nightly` et un désinfecteur, et l'inclure
      au workspace principal ferait échouer `cargo test --workspace` sur la
      chaîne épinglée — celle dont la matrice déterministe de C-16 dépend.
      - `a3d_reader` — le nom vient de R-903, qui le donne explicitement ;
        l'arborescence de la PARTIE 33 écrit `a3d` dans une énumération
        abrégée, et l'exigence numérotée l'emporte. C'est la cible qui compte
        le plus : le lecteur A3D est le seul composant qui lit un fichier
        **fourni par un tiers** et en tire des tailles, des décalages et des
        longueurs de décompression ;
      - `gltf`, `obj`, `stl` pour les importeurs (T-680) ;
      - `packets`, `impacts`, `deform_snapshot` et `nbt` attendent leurs
        décodeurs, en M4 et M6. Une cible sans sujet passerait au vert sans
        rien chercher ;
      - **entrées prises brutes**, sans structure dérivée d'`arbitrary` : c'est
        ce qui fait qu'un vrai fichier déposé dans `corpus/<cible>/` est une
        graine telle quelle. Une entrée structurée lirait ses longueurs depuis
        la fin du tampon, et un `.gltf` n'y désignerait plus un document.
- [x] **Deux paniques trouvées dès la première campagne de contrôle**, toutes
      deux dans des analyseurs **tiers**, toutes deux atteignables depuis un
      pack de contenu, toutes deux corrigées par une vérification préalable dans
      notre code.
      - `gltf` 1.4.1 — `read_indices()` atteint un `unreachable!()` dès que le
        `componentType` de l'accesseur d'indices n'est ni `U8`, ni `U16`, ni
        `U32`. Un fichier déclarant `5122` (`SHORT`, signé) suffit, et un
        exportateur qui confond `5122` et `5123` en produit un que tous les
        autres champs rendent plausible. `import_gltf` vérifie désormais le type
        avant toute lecture — refuser est **conforme à glTF 2.0**, qui n'admet
        que des entiers non signés pour des indices ;
      - `tobj` 4.0.5 — `attempt to multiply with overflow` : le contrôle de
        bornes s'écrit `vn * 3 + 2 >= normal.len()`, le produit étant calculé
        **avant** la comparaison. Un indice négatif de `-21` avec une seule
        normale déclarée donne `1 - 21 = -20`, qui devient un `usize` immense.
        `import_obj` valide désormais chaque indice de face contre les comptes
        déclarés, à côté de la passe qui vérifie déjà les `mtllib` ;
      - les deux paniques étaient **contenues** — le pool de jobs et la
        frontière FFI les captent —, mais un asset qui doit être refusé se
        refuse ; il ne panique pas. R-903 ne tolère rien d'autre.
      - Les deux entrées fautives sont versées au corpus comme graines de
        régression, et chacune a son test nommé : le corpus dit « ne panique
        pas », le test dit « refuse pour la bonne raison ».
      - **Les deux sont des mutations de mes propres graines.** Le fuzzer est
        parti de `triangle.gltf` et de `mtllib-indices-negatifs.obj`, et a
        trouvé en moins de dix minutes. C'est la démonstration que R-903
        attendait d'un corpus versionné.
- [x] **Un filet au point de délégation**, après que corriger panique par
      panique se soit révélé sans fin. Deux campagnes de dix minutes ont donné
      **quatre** paniques, à quatre endroits, dans deux dépendances — et chaque
      correctif en a découvert un suivant.
      - `catch_parser_panic` enveloppe les trois analyseurs tiers et rend
        `ImportError::ParserPanicked`. Même code `E-3050` que les autres refus,
        aucun code inventé (ADR-102) ; c'est la **variante** qui distingue.
      - La distinction porte tout le dessin : en production l'asset est refusé
        proprement, et **en fuzzing les cibles échouent dessus**. Sans elle, le
        filet rendrait les paniques invisibles au fuzzer, et R-903 n'aurait plus
        de moyen de les constater. Le filet protège le joueur sans aveugler
        l'outil.
      - Les vérifications préalables restent : elles sont conformes au format,
        donnent un diagnostic précis, et évitent d'avoir à compter sur le filet.
        Mais reproduire chez nous tous les invariants internes de deux
        analyseurs reviendrait à les réécrire, et cette réécriture dériverait à
        la première mise à jour.
- [x] **Troisième panique de `gltf` 1.4.1**, trouvée par la campagne planifiée du
      2026-10-04 après 110 538 027 exécutions : `binary.rs:252`,
      `header.length as usize - Header::size_of()` déborde quand l'en-tête GLB déclare
      une longueur inférieure à 12 octets (entrée de 23 octets,
      `Z2xURkZ7FwAAAAAAAAAAAEpTT05leHQ=`). Retenue en production par
      `catch_parser_panic` ; la cible échoue dessus, comme prévu. **Corrigée à la racine** :
      `json_text` vérifie la version (2) et la longueur totale de l'en-tête GLB — entre
      les vingt octets des deux en-têtes et la taille du fichier — avant que `gltf` ne
      la lise. Graine `regression-glb-longueur-courte.glb` ; cinq tests nommés, dont
      l'entrée exacte et un GLB conforme toujours accepté ; vérifié par mutation : sans
      les gardes, trois tests échouent. Les sept GLB réels du contenu de développement
      annoncent tous la version 2 et leur taille exacte.
- [ ] **Trois bugs à rapporter en amont.** Les cinq paniques sont des défauts
      de bibliothèque, pas de leur usage :
      - `gltf-json` 1.4.1 indexe `root.accessors[…]` avec un indice venu du
        document, **sans vérifier la borne** — dans son propre code de
        validation. Seul cas des quatre qui n'a **pas** de correctif racine chez
        nous : le détecter exigerait de réanalyser le JSON avant la
        bibliothèque. Il est retenu par le filet et gardé par un test nommé ;
      - `gltf` 1.4.1, `binary.rs:252` : `Header::from_reader` ne valide que le nombre
        magique, puis `from_slice` soustrait 12 à la longueur annoncée sans garde ;
      - `tobj` 4.0.5, deux fois : `vn * 3 + 2 >= normal.len()` calcule le produit
        avant de comparer, et `parse_float3` fait `.try_into().unwrap()` sur un
        `Vec` qu'il vient de collecter — `Ka 0.0 0.0` suffit.
- [x] **Le corpus est rejoué sans `nightly`**, par
      `crates/ax-asset/tests/corpus_fuzzing.rs`, sur les quatre plateformes et à
      chaque exécution de la CI. Il vérifie deux choses qu'une campagne ne
      vérifie pas : qu'aucune graine ne fait paniquer un lecteur **maintenant**
      plutôt qu'à la prochaine campagne, et qu'au moins une graine par cible est
      encore **acceptée**. Une graine qu'un changement de code ferait refuser
      d'emblée cesse d'être un point de départ, et le fuzzer repartirait de rien
      sans que rien ne le dise. Vérifié par mutation : un octet ajouté à la
      graine glTF fait échouer le test.

### Intégration continue (PARTIE 34.3)

- [x] **Chaîne de CI.** `.github/workflows/ci.yml`, cinq jobs. La
      correspondance avec la liste de 34.3 est écrite dans l'en-tête du
      workflow, y compris pour les jobs absents et la raison de leur absence.
      - `lint-rust`, `lint-no-fiction`, `deps` et `build-and-test` tournent sur
        chaque push et chaque PR ; `build-and-test` enchaîne `gradlew build`,
        donc `codegen` (T-005), la suite JUnit — T-007, T-014, T-020..T-023,
        R-2392 — et `validateJar` ;
      - la **matrice de plateformes** ne tourne que sur `master` et sur
        déclenchement manuel. Le dépôt est privé : une minute macOS en compte
        dix, et la matrice complète coûte environ 250 minutes facturées quand
        les jobs Linux en coûtent quinze. Un `workflow_dispatch` permet de la
        décocher ;
      - elle couvre les quatre configurations de la matrice de validation
        déterministe que GitHub sait fournir, et y rejoue les vecteurs d'or
        (R-513). Linux aarch64 en est absent : « best effort » selon 34.2, et
        aucun runner ARM sur le plan de ce dépôt — un job qui attend un runner
        inexistant n'échoue pas, il attend ;
      - `lint-no-fiction` était le seul lint sans garde-fou : T-014 couvre déjà
        INV-01 côté Java **et** côté Rust, `pas_de_pool_global.rs` couvre R-470.
        Le script s'exclut du balayage — il contient les motifs qu'il traque —
        et bouche ce trou par un autotest à deux listes, écrites indépendamment
        des expressions régulières. Vérifié par mutation dans les deux sens.
- [x] **Matrice déterministe rejouée et archivée (R-516).** **Quatre
      configurations sur cinq** sont validées : `x86_64-windows-msvc`,
      `x86_64-linux-gnu`, `aarch64-macos-none` et `x86_64-macos-none`. Les mêmes
      10 000 cas produisent les **mêmes bits** sur trois systèmes, trois libc et
      **deux architectures** — un binaire ARM/NEON calcule au bit près ce que
      calcule un binaire x86-64/SSE2 sous MSVC, subnormaux compris. Registre :
      [`docs/spec/MATRICE-DETERMINISTE.md`](../docs/spec/MATRICE-DETERMINISTE.md).
      Seule `aarch64-unknown-linux-gnu` reste non validée, faute de runner ARM
      sur le plan de ce dépôt ; elle est « best effort » en 34.2.
      - `MatrixEntry` porte désormais un drapeau `validated`, et
        `is_in_validation_matrix()` ne rend `true` que pour une ligne rejouée.
        R-516 est explicite : « une configuration non validée n'est jamais
        déclarée déterministe, **même si elle passe en pratique** ». Le code
        déclarait `aarch64-unknown-linux-gnu` sans qu'elle ait jamais été
        rejouée ;
      - `x86_64-apple-darwin` n'a pas pu être rejouée : le job demandait
        `macos-13`, que GitHub a retiré de ses runners standard. Une étiquette
        retirée ne produit **aucune erreur** — le job reste en file jusqu'au
        délai de six heures. Quarante-six minutes sans runner, là où les trois
        autres plateformes finissaient en moins de huit. Diagnostiqué par une
        sonde jetable comparant les étiquettes côte à côte ; `macos-15-intel`
        obtient un runner en moins de trente secondes.
- [x] **Trois défauts que seule la CI pouvait révéler**, tous corrigés avant
      qu'elle ne tourne :
      - `gradlew` était en mode `100644`. Le dépôt étant né sous Windows, git
        n'a jamais enregistré son bit d'exécution ; `gradlew.bat` masquait le
        problème. Le premier runner Linux aurait répondu « Permission denied » ;
      - `cargo deny check` n'avait **jamais** été exécuté, faute d'outil
        installé et de CI. Il échouait sur `wildcards = "deny"` : les crates
        internes se référencent par chemin, sans version. Réglé par
        `allow-wildcard-paths`, qui exige des crates privés — d'où le
        `publish = false` du workspace, correct en soi puisque rien de tout
        cela n'a vocation à partir sur un registre ;
      - `repository` désignait `github.com/JLSkyzer/axion-engine`, qui n'existe
        pas. L'adresse fausse voyageait dans les métadonnées de chaque crate.


### Definition of Done de M1

Prononcée le 2026-10-04, sur décision de Killian. Acceptance du CDC (PARTIE 37.2) :
`sed -n '7191,7199p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md`

- [x] **Fixtures compilées, golden files stables.** T-590 relit le fichier de
      référence v1.1 et ceux des compilateurs 6 et 7 ; T-591 le reproduit à l'octet ;
      T-226 tient la chaîne bout à bout.
- [x] **Asset corrompu refusé.** T-253, vingt-deux tests : en-tête et magic, CRC vu
      avant toute décompression (R-882), fichier tronqué et section hors bornes
      (R-900), tailles annoncées bornées (R-901), décompression bornée (R-902),
      versions (R-890, R-891).
- [x] **Fuzzing 1 h sans incident.** `a3d_reader`, `obj`, `stl` : run 34648731184
      (2026-09-12). `gltf` : l'heure locale du 2026-09-12 ne couvrait plus l'import
      élargi par C-26 ; un contrôle de dix minutes (run 37154496478) y a trouvé une
      panique — indices déclarés `VEC4` —, corrigée par `b46900e` ; puis une heure
      sans incident sur le code corrigé, 89 511 527 exécutions (run 37155887128,
      2026-10-03). Campagne nocturne d'une heure par cible depuis le 2026-10-03.
- [x] **Vecteurs d'or verts sur les plateformes de CI.** `det-vectors` sur
      Windows x86_64, Linux x86_64, macOS ARM et macOS Intel, à chaque push depuis le
      passage du dépôt en public (run 37156055227 du commit `8c1aa2b`).
- [x] **Tests du jalon présents.** Vérifié **mécaniquement** : `MilestoneTestsCoveredTest`
      compte M1 (R-2392). Il ignorait la convention `fn tNNN_` des tests Rust — quinze
      identifiants paraissaient manquer, tous avaient leurs tests — ; il la lit
      désormais, et deux tests mal étiquetés ont été renommés (`b21f517`).
- [x] **Suites vertes.** CI GitHub verte sur la matrice ; CI locale verte.

Réserves, portées en dette et non masquées :

- **`aarch64-unknown-linux-gnu` jamais rejouée** (34.2, « best effort, non
  bloquant »). GitHub propose des runners ARM Linux aux dépôts publics : à vérifier,
  puis à ajouter à la matrice.
- **C-12** : la granularité adaptative (R-473) est testée sur des durées simulées,
  mais `Granularity` n'est pas branchée dans `JobSystem` ; le plafond de workers selon
  le côté (R-471) attend IF-01 pour être câblé à l'initialisation ;
  `t171_un_budget_nul_vaut_absence_de_deadline` contredit « tout travail a une
  deadline » — à examiner.
- **C-21** : R-530 et R-532 sont testés sous T-228 et T-272, numéros hors de la
  fiche ; « refusée sans lecture complète » (R-533) n'est prouvé que par le contrôle
  préalable de `mod.rs`.
- **C-24** : aucune migration réelle (format v1.1 unique) ; R-892 (invalidation du
  cache) et R-883 sans test ; la recompilation de R-882 et R-890 n'est pas testée au
  niveau du conteneur.

---

## M3 — Physique et premier rendu (en cours)

Démarré le 2026-09-19, **effort maximal** (décision de Killian). M3 a deux
moitiés : la **physique** (C-31 → C-32 → C-38 → C-39 → C-40) et le **premier
rendu** (C-60..C-63, C-26, C-67). On commence par C-31, fondation de la chaîne
physique.

**Déterminisme (ADR-005, §10.9, R-1020).** La physique n'est **pas** bit-exacte
entre machines : autorité serveur, `rapier` en f32 avec SIMD admis. L'exigence
est la reproductibilité **sur une même machine et un même binaire** : ordre
d'itération stable (jamais de `HashMap` non ordonnée dans un chemin de
simulation), fusion parallèle dans un ordre fixe, PRNG à graine explicite. La
chaîne de dommage bit-exacte (C-16) est un sujet distinct (ADR-018, M6).

**Frontière (R-1753, R-460).** Aucun type `rapier`/`nalgebra` n'est exposé : ils
restent internes à `ax-physics`, convertis vers les types AXION (glam) à la
frontière. `nalgebra` est admis pour ce seul crate (table 32.2, « non
substituable sans changer rapier »).

- [ ] **C-31 — Physics World** (M3, `[EFFORT MAX]`). Crate `ax-physics`,
      `rapier3d`+`parry3d`+`nalgebra` (ADR-002). Fiche 5.23, PARTIE 10.
      - [x] **Tranche 1 — fondation du monde (Rust pur, sans FFI).** Rien
            d'irréversible n'est gelé (aucune struct DM, aucun point d'entrée
            ABI) : tout est prouvable par test. Crate `ax-physics`
            (`rapier3d 0.35.3`, cargo-deny et NOTICE verts) ; `PhysicsConfig`
            validée (R-990), `PhysicsWorld` mono-thread à pas fixe clampé,
            corps STATIC/KINEMATIC/DYNAMIC + colliders primitifs, gravité par
            monde. 6 tests dont **même séquence → même pose** (R-1020) ;
            clippy `-D warnings` et lint R-001 verts. Écarts rapier 0.35 ↔
            CDC (BVH, solveur TGS-soft, sommeil angulaire) consignés en
            ADR-112. Types `glam` partagés sans conversion (R-460).
            - crate `ax-physics` + deps workspace (deny.toml, NOTICE, ADR) ;
            - `PhysicsConfig` : `sim.fixed_dt` ∈ {1/30,1/60,1/120} validé
              (R-990), `max_substeps`, gravité (R-611), itérations solveur
              (§10.5), seuils de sommeil (fiche) ;
            - `PhysicsWorld` : ensembles rapier (bodies, colliders, joints,
              pipeline, CCD, query) ; corps STATIC/KINEMATIC/DYNAMIC (§10.2)
              avec collider primitif — jeu complet des formes en tranche 2 ;
            - `step` : accumulateur à pas fixe **clampé**, aucune spirale de
              rattrapage (R-990) ; mono-thread d'abord (déterminisme garanti) ;
            - tests : chute sous gravité vers la position attendue ; **même
              séquence → transforms bit-identiques** (R-1020) ; `fixed_dt`
              refuse une valeur hors ensemble ; clamp de l'accumulateur.
      - [x] **Tranche 2a — catalogue des formes dynamiques (§10.3).**
            Primitives (Cuboid, Ball, Capsule, Cylinder, Cone), `ConvexHull`
            (4..256 points, seule refitable) et `Compound` (≤64 filles,
            récursif). `add_body` rend un `Result` et valide la forme avant
            tout ajout. `TriMesh`/`Heightfield` (statique/kinematic, R-971)
            différés à C-38, leur source de données : c'est là que R-970
            (INV-13) s'appliquera à du vrai terrain, sans dépendance `ndarray`
            prématurée. 14 tests, clippy et lint verts.
      - [x] **Tranche 2b — groupes et filtre de collision (§10.4, R-980).**
            Module `groups` : huit groupes réservés (`world`…`debug`) à bits
            fixes, `GroupRegistry` data-driven par nom (bits 8..31 à la
            demande), `CollisionGroups` traduit en `InteractionGroups` mode
            AND — la formule symétrique de §10.4. `set_collision_groups` pose
            le filtre sur le collider d'un corps. 5 tests unitaires + 2 tests
            de comportement (compatibles → repose, incompatibles → traverse).
            **Reste de §10.4** : l'exclusion intra-assembly (sauf déclaration)
            exige un tag d'assembly par collider et un `PhysicsHooks` ; elle
            va avec l'intégration des assemblies dans le monde (tranche 5).
      - [x] **Tranche 2c — gestion d'activité (CCD, rayon, plafond).** CCD
            déclarée par corps (`set_ccd_enabled`/`is_ccd_enabled`) ; rapier en
            tire les sous-pas, ses contacts spéculatifs jouant l'« automatique »
            de la fiche. `enforce_simulation_radius` endort — jamais ne supprime
            (R-612) — les corps hors du rayon ; `enforce_active_body_cap` endort
            le surplus au-delà du plafond, des plus éloignés aux plus anciens,
            déterministe (tri stable sur l'ordre d'itération) et rendant la
            liste pour journalisation (R-613). 6 tests dédiés.
      - [x] **Tranche 2d — infrastructure de forces + aéro (§10.6).**
            Profil de force par corps (`HashMap` consultée par clé, jamais
            itérée, R-1020), application par sous-pas dans `advance` (remise à
            zéro puis repose des forces `rapier`, gravité intacte). Gravité par
            corps (`set_gravity_scale`), vent de dimension (`set_wind`,
            `physics.wind`), traînée `−0.5·ρ·Cd·A·|v|·v` relative au vent
            (`set_drag`), requête `velocity`. 4 tests (chute freinée à vitesse
            terminale, vent poussant via la traînée, `gravity_scale` nul qui
            flotte, déterminisme avec forces).
      - [x] **Tranche 2e — portance (§10.6, R-1000).** `LiftSurface` déclarée
            par corps (point local, normale, aire, `Cl`) ; `F = 0.5·ρ·Cl·A·|v|²`
            au point de la surface (donc avec moment). Le §10.6 fixe la
            magnitude, pas la direction : choisie perpendiculaire à
            l'écoulement, selon la composante de la normale qui lui est
            orthogonale (documenté sur `LiftSurface`). 2 tests : une aile dans
            le vent porte, une aile de profil ne porte pas.
      - [x] **Tranche 2f — flottabilité (§10.6).** `FluidEnvironment` de
            dimension (surface plate, densité), optionnel. Volume immergé
            approché par la fraction des 8 coins de l'AABB sous la surface ;
            `F = −gravity·ρ·V` appliquée au centre de poussée (centroïde des
            coins immergés) → moment de redressement d'un bateau. La présence
            réelle de l'eau par bloc viendra de C-38. 2 tests : un fluide plus
            dense fait remonter, un corps plus dense coule.
      - [x] **Tranche 3a — structure DM figée + événements SLEEP/WAKE.**
            `PhysicsEvent` `#[repr(C)]` (§10.7) dans `ax-model::dm::physics`
            (foyer DM), avec `Handle` DM-01 `#[repr(C)]` et le module
            `event_kind` (0..12). **Disposition gelée : 76 octets, align 4,
            offsets verrouillés par test.** Transcrit AVEC son consommateur
            (règle de `dm/mod.rs`) : identité de corps (`set_body_identity`),
            transitions SLEEP/WAKE émises, lot borné avec compteur de pertes
            (R-1011, aucune perte silencieuse), `drain_events`. Décisions ABI
            en ADR-113. `ax-physics` dépend de `ax-model`. 4 tests +
            2 tests de disposition. **Cœur irréversible : layout figé.**
      - [x] **Tranche 3b — événements de contact CONTACT_START/END (R-615).**
            Colliders en `ActiveEvents::COLLISION_EVENTS`, `step_with_events`
            avec un `EventHandler` custom, reconstruction après le pas via
            `contact_pair`/`find_deepest_contact`. CONTACT_START peuplé :
            point monde, normale, impulsion, impulsion tangentielle, vitesse
            relative, **masse effective calculée** (terme linéaire
            `effective_inv_mass` + angulaire `|M√⁻¹·(r×n)|²`), matériaux ;
            CONTACT_END aux identités seules. Validé par un test d'impact réel
            (masse effective ≈ masse de la bille). 3 tests dont déterminisme.
      - [x] **Tranche 3b-ii — CONTACT_IMPULSE et seuil.** Un CONTACT_IMPULSE
            par paire de contact active et par sous-pas (contact le plus
            profond = impulsion max, agrégation par paire de R-1011), filtré
            par `contact_event_threshold` (R-1012, défaut 0.5 N·s) — ce qui
            écarte aussi le bruit des contacts au repos (`mg·dt` < seuil).
            `set_contact_event_threshold`. 2 tests (impact au-dessus du seuil,
            seuil élevé qui filtre tout). La voie « sous seuil → C-41 » (usure)
            attend C-41.
      - [x] **Tranche 3c-i — capteurs (SENSOR_ENTER/EXIT).** Drapeau capteur
            par corps (`set_sensor`) ; une paire capteur émet SENSOR_ENTER à
            l'entrée, SENSOR_EXIT à la sortie, aux identités seules (un capteur
            ne résout aucun contact). 1 test : une bille traverse un capteur.
      - [ ] **Tranche 3c-ii — liaisons et défaillances (gelé).** JOINT_BROKEN/
            JAMMED avec les joints (M4) ; ATTACH/DETACH avec les attaches (M5) ;
            CLAMPED/RECOVERED avec les modes de défaillance (FM-20/21/22).
      - [x] **Tranche 4 — IF-03, cycle de simulation (FFI/ABI, `[EFFORT MAX]`).**
            **À faire en session neuve, effort maximal.** C'est un contrat
            **IF-xx** (que l'agent ne fige pas seul) : à mener avec soin et,
            pour les layouts non fixés par la spec, un ADR + un test de
            disposition. Non testable en unité pour la partie Java — l'exercice
            se fait dans le jeu.

            **Acquis de l'étude (2026-09-20)** : IF-03 (§4.5) est un cycle
            **submit/collect asynchrone** : `axion_sim_submit(ctx, tick,
            command_count, impact_count)`, `axion_sim_collect(ctx, deadline_ns,
            *AxionCollectResult)`, `axion_sim_cancel(ctx)`. `AxionCollectResult`
            = {state_count (BodyState[]), event_count (PhysicsEvent[] +
            DamageEvent[]), deform_page_count, refit_count, detach_count,
            net_bytes, flags}. Les **kinds de tampons existent déjà** dans
            `ax-core::buffers` : `SimIn` (entrées : commands + impacts),
            `SimOut` (BodyState[]), `Events` (PhysicsEvent[]), `DeformOut`.
            `axion_sim_*` **ne sont pas encore écrits**. `BodyState`/`BodyDesc`
            = **DM-08** (§3.8), spécifiés mais **non transcrits**.

            **Sous-tranches proposées :**
            - [x] **4a — `BodyState` (DM-08) + packer.** `BodyState`
                  `#[repr(C)]` dans `ax-model::dm::physics` (80 octets, align 8,
                  offsets **verrouillés par test**) + module `body_state_flags`.
                  Transcrit AVEC son consommateur : `PhysicsWorld::body_states
                  (&FloatingOrigin)` sérialise les corps mobiles identifiés,
                  position monde `f64` recomposée par l'origine flottante,
                  flags SLEEPING + IN_FLUID (les autres avec leur source).
                  3 tests (composition monde, filtrage statiques/anonymes,
                  IN_FLUID). `BodyDesc` (entrée) viendra avec le traitement des
                  commandes en 4b, avec son consommateur.
            - [x] **4b — orchestration dans le `Session`** — **session neuve,
                  effort maximal**. Contrat d'entrée `SimIn` **ratifié dans
                  ADR-114** (flux de commandes opcode+longueur, extensible) :
                  s'écrire contre lui. Loger un
                  `PhysicsWorld` par dimension ; `submit` lit `SimIn`
                  (commands+impacts), planifie le pas sur le système de jobs
                  sans bloquer au-delà de `budgets.submit_ns` (R-280) ; `collect`
                  attend jusqu'à `deadline_ns`, remplit `SimOut`+`Events`, rend
                  `AxionCollectResult` (incomplet → R-281, jamais d'abandon
                  silencieux) ; `cancel` ; cycle non clos → R-282
                  (`axion.sim.unbalanced`). Pas fixe / accumulateur : R-283
                  (déjà dans `PhysicsWorld::advance`).
                  - [x] **4b-i — `SimDriver`** (ax-physics) : mondes par
                        dimension (BTreeMap déterministe, R-610), `advance_all`,
                        `collect_states`/`drain_events`. 3 tests.
                  - [x] **4b-ii — commandes `SimIn`** : structs ADR-114
                        (`ax-model::dm::commands`, layout figé + test) + parseur
                        **sûr** (champ à champ, sans `unsafe`) + routage
                        handle→corps + application (REMOVE_ASSEMBLY,
                        SET_KINEMATIC, APPLY_IMPULSE, SET_DIMENSION_ENV). 5 tests
                        (flux réels). CREATE_ASSEMBLY (C-32) et APPLY_FORCE
                        continu comptés **différés**, jamais des stubs.
                  - [x] **4b-iii — points d'entrée `ax-ffi`** :
                        `axion_sim_submit`/`collect`/`cancel`, enveloppe mince
                        lisant `SimIn` et remplissant `SimOut`/`Events`, sur le
                        patron async d'`asset_compile`/`poll` ; `AxionCollectResult`. **Fait** : test
                        d'intégration `sim_abi.rs` (cycle init -> SimIn ->
                        submit -> collect -> cancel -> shutdown, bilan
                        équilibré R-322) vert ; 9 tests ax-ffi verts.
            - [x] **4c — packing des tampons + garde-fous** : sérialiser
                  BodyState[] (position monde `f64` via l'origine flottante) et
                  PhysicsEvent[] avec en-têtes/CRC ; R-180 (clamp
                  `max_linear_vel`/`max_angular_vel` + flag CLAMPED, journalisé) ;
                  R-181 / FM-20 (position NaN ou hors monde → sommeil forcé,
                  `E-2030`, dernier état valide). **Fait** : R-180 (clamp
                  linéaire/angulaire par corps, défauts 300 m/s / 100 rad/s, flag
                  CLAMPED, journalisation débitée à une par corps et par minute) ;
                  R-181/FM-20 via la quarantaine de rapier pour le non-fini (NaN,
                  infini → sommeil forcé, E-2030, rollback rapier) et une borne de
                  coordonnée locale pour le hors-monde fini. 7 tests Rust. Le
                  packing suit la convention AssetOut (comptes via
                  AxionCollectResult, charge via write_payload) ; CRC sur écriture
                  reste optionnel sous debug.checksum_buffers (champ présent, 0 par
                  défaut).
            - [x] **4d — côté Java** `[EFFORT MAX]` : brancher IF-03 côté Java et
                  l'appliquer sur le thread autoritatif. Sous-tranches :
                  - [x] **4d-i — bindings JNI Rust** (`jni_bridge.rs`) :
                        `simSubmit`/`simCollect`/`simCancel` (NativeMethod + fns) ;
                        `simCollect` écrit `AxionCollectResult` (7×u32) dans un
                        `long[7]` (patron `assetPoll`). Durcissement R-491 de
                        `axion_sim_submit` : `BufferHeader::read` (magic/kind SimIn)
                        → `E-2002` si invalide ; longueur reste par paramètre.
                        Test signatures + rejet magic.
                  - [x] **4d-ii — `NativeBridge.java` + `NativeApi`** : 3 `native` +
                        wrappers publics `submit`/`collect`/`cancel`, constantes
                        (slots=7, drapeaux INCOMPLETE/DEGRADED), offsets d'en-tête
                        locaux (BufferKinds est généré) ; NativeApi inchangé —
                        la physique appelle NativeBridge directement, comme les assets.
                  - [x] **4d-iii — types + codecs Java** : `BodyState` (80 o) et
                        `PhysicsEvent` (76 o) décodés LE aux offsets figés ; writer
                        `SimIn` (patron `NativeAssetCompiler.submit`) ; lecteurs
                        `SimOut`/`Events` (acquérir cap 0, compte via
                        `AxionCollectResult`, release — R-322). Tests JUnit décodage.
                        Fait avec 4d-iv : décodeurs, writer, lecteurs, et `BodyStateTest` /
                        `PhysicsEventTest` (offsets figés) ; la case était restée ouverte
                        (constaté le 2026-10-03).
                  - [x] **4d-iv — intégration `onTick`** (thread autoritatif) :
                        `AxionRuntime.onTick` déroule submit→collect→lecture→release
                        après le pump d'assets ; n° de tick via `currentTick()`.
                  - [x] **4d-v — preuve** : étendre `NativeBridgeTest` (vraie `.dll`)
                        au cycle submit/collect/cancel + lecture ; `cargo build
                        --release -p ax-ffi` puis `:axion-mod:test`. Premier
                        `@GameTest` + tentative `runGameTestServer` (honnête si non
                        exécutable ici). **Fait** : NativeBridgeTest exerce le
                        cycle IF-03 sur la vraie .dll (submit/collect/cancel +
                        SET_DIMENSION_ENV, tampons équilibrés). Le GameTest physique
                        observable (spawn → tick → l'entité a bougé) est **différé à
                        C-32** : à 0 corps la physique n'a aucun effet en jeu.
      - [x] **Tranche 5 — intégration Java et proxies vanilla** `[EFFORT MAX]`
            ([ADR-123](../docs/decisions/ADR-123.md), ratifié le 2026-10-04 : protocole tel
            quel, réglage par amendement de l'annexe A.3, poussée et dégâts actifs). L'état des
            lieux du 2026-10-04 a trouvé R-612/R-613 jamais appliqués en jeu (un seul centre,
            aucun réveil), la configuration IF-01 ignorée par les mondes, la gravité jamais
            envoyée, la destruction de R-610 absente, FM-20 silencieux, les événements consommés
            par personne côté Java.
            - [x] **T5a — natif, sans contrat** : réglages IF-01 retenus (`sim.*`, `physics.*`,
                  `budgets.max_active_bodies`) et appliqués à tout monde ; environnement de
                  dimension persistant (gravité R-611) ; R-612/R-613 autour de plusieurs
                  observateurs, réveil au retour ; destruction R-610 sans assembly, tuile, fluide
                  ni observateur ; gouverneur FM-21 (§25.5, paliers physiques D1-D3 de SM-02,
                  drapeau `AXION_SIM_DEGRADED`, jauges) ; détecteur FM-22 (amortissement puis
                  sommeil) ; événements `RECOVERED` (2030) et `CLAMPED` (1/2/3). Tests t300..t303,
                  t305..t307 (existants étiquetés quand ils couvrent déjà l'exigence).
                  - [x] Réglages IF-01 retenus et appliqués, environnement de dimension
                        persistant (78dbe20, T-301).
                  - [x] R-612/R-613 autour des observateurs (`activity.rs`) : rayon mesuré dans
                        chaque dimension, plafond serveur compté sur toutes, ancienneté au rang
                        de création, réveil à portée dans la limite du plafond (marge d'une
                        section pour prendre la place d'un corps éveillé), vitesse suspendue puis
                        rendue ; `CLAMPED` 2 pour un sommeil de budget. Précisions consignées
                        dans ADR-123 §3. **Branché en jeu en T5c seulement**, avec l'envoi des
                        observateurs par Java : sans eux, tout corps dormirait. T-302, T-303 ;
                        12 mutations ciblées, toutes tuées par le test attendu.
                  - [x] Destruction R-610 en fin de `collect` : un monde sans corps, fluide ni
                        observateur du tick est détruit, son environnement gardé ; les
                        compteurs des mondes détruits restent dans les totaux (`WorldCounters`).
                        Jauge `axion.sim.worlds` ; au passage, `axion.sim.unbalanced` (R-282),
                        exigé par le CDC et jamais publié, l'est désormais. Branché dans le
                        cycle IF-03 dès maintenant : sans observateurs, il ne détruit que des
                        mondes vides. `begin_tick` sera branché au `submit` en T5b, avec
                        `SET_OBSERVERS` et son test. T-300 ; 7 mutations tuées.
                  - [x] Événements `RECOVERED` (2030) à chaque restauration et `CLAMPED` 1 au
                        débit du journal (T-306, tests FM-20/R-180 existants étiquetés et
                        complétés ; 4 mutations tuées).
                  - [x] R-615 corrigé (T-305) : `tangent_impulse` valait toujours 0 (frottement
                        « simplifié » de rapier, passé en `Coulomb`) et `relative_velocity` 0 au
                        choc (vitesses d'après la résolution, désormais relevées avant le pas).
                        Coût mesuré sans écart discernable (amendement d'ADR-112).
                  - [x] Gouverneur FM-21 (`governor.rs`) : p95 de fenêtres de 100 ticks, descente
                        après trois fenêtres au-dessus de `budgets.sim_ns_per_tick`, remontée après
                        30 s sous 60 % puis un palier par 10 s ; D1 solveur et sous-pas (chaque
                        monde, y compris ceux qui naissent), D2 rayon ×0,75, D3 plafond ÷2 avec
                        `CLAMPED` 2. Mesure d'`advance_all` dans `axion_sim_collect`, drapeau
                        `AXION_SIM_DEGRADED`, jauges `axion.sim.degradation_level` et
                        `axion.sim.p95_ns`. Journal des transitions par Java en T5c (R-1880).
                        T-307 ; 12 mutations tuées.
                  - [x] Détecteur FM-22 : fenêtre d'une seconde simulée, chemin et déplacement
                        net par corps éveillé en appui sur un autre corps dynamique ; palier 1
                        amortissement 5 du corps et de ses voisins, palier 2 sommeil du groupe et
                        amortissements rendus, calme rendu de même ; `CLAMPED` 3 à chaque palier.
                        T-307 ; 6 mutations tuées.
                  - [x] Étiquettes t300/t305/t306 sur les tests existants qui couvrent déjà.
            - [x] **T5b — contrat** : opcodes 11 `SET_OBSERVERS` et 12 `SET_ENTITY_PROXIES`
                  (structs DM, tests de disposition, décodage borné, application) ; proxies
                  `KINEMATIC` à vitesse, groupe `entity_proxy` ; événements de contact avec une
                  entité (§6) ; écriture Java (`SimCommandStream`).
                  - [x] Dispositions DM des opcodes 11 et 12 (`SetObservers`, `SetEntityProxies`,
                        `EntityProxyDesc`, tests de disposition) ; décodage borné de
                        `SET_OBSERVERS` (1024 au plus, longueur exacte) ; `begin_tick` au début
                        de `axion_sim_submit`, ticks sans commande compris. Test d'ABI : un joueur
                        garde le monde de sa dimension le temps de son tick. 5 mutations tuées.
                  - [x] `SET_ENTITY_PROXIES` : décodage borné (4096, longueur exacte, forme connue),
                        proxies cinématiques à vitesse (`proxies.rs`) remplacés à chaque tick au
                        début d'`axion_sim_collect`, groupe `entity_proxy` filtrant `assembly`,
                        forme changée en place ; identité de l'entité dans les contacts (§6), fin
                        de contact d'une entité partie comprise. Précisions dans ADR-123 §6.
                        T-304 (part native) ; 15 mutations tuées.
                  - [x] Écriture Java des deux opcodes (`SimCommandStream.setObservers`,
                        `setEntityProxies`, record `EntityProxy`), dispositions épinglées par
                        `SimCommandStreamTest` ; vérification croisée sur la vraie bibliothèque
                        (`NativeBridgeTest`) : flux accepté, contact nommant l'entité.
            - [x] **T5c — Java et Forge** : observateurs (joueurs non spectateurs) et proxies
                  (rayon d'influence déduit des vitesses) par tick ; consommateur d'événements
                  (R-1010) ; effets vanilla (poussée des non-joueurs, dégâts `axion:collision`
                  selon la courbe de chute) ; amendement de l'annexe A.3 (`[physics]
                  entity_push`, `entity_damage`) avec registre, fichiers générés, conformité et
                  index du CDC régénéré ; journal `E-2030` et des transitions de dégradation ;
                  T-304 ; essai en jeu (demander à Killian de lancer le jeu).
                  - [x] T5c-1 — amendement de l'annexe A.3 : `[physics] entity_push`,
                        `entity_damage` (défauts vrais, ratifiés), registre `ax-model`, fichiers
                        générés (TOML, `CONFIGURATION.md`, `ConfigSchema`), conformité R-2400,
                        registre d'amendement 38.9 du CDC, index régénéré (8281 lignes ; plages
                        citées par `CLAUDE.md` et `docs/AGENT.md` recalées et vérifiées).
                  - [x] T5c-2 — fournisseur Forge des joueurs (`SET_OBSERVERS`) et des proxies
                        (`SET_ENTITY_PROXIES`, rayon d'influence déduit des vitesses), par
                        dimension et par tick : `EntityPresenceBridge` (Forge) et
                        `EntityProxySelector` (règle pure, testée) ; vitesse des corps retenue par
                        `AssemblyRuntime`, vitesse des entités lue dans leur déplacement.
                  - [x] T5c-3 — natif : `manage_activity` branché dans `axion_sim_collect`, après
                        la synchronisation des proxies et avant le pas ; compteurs `axion.sim.slept_*`,
                        `woken` et ceux des `WorldCounters` (`events_dropped`, `velocity_clamps`,
                        `invalid_states`, `bounds_unavailable`) publiés. Test d'ABI : un corps sans
                        joueur s'endort et se réveille à son retour ; clamp et restauration
                        comptés. `NativeBridgeTest` déclare le joueur à chaque tick, comme le jeu.
                  - [x] T5c-4 — consommateur d'événements (R-1010) : couture `SimEventSink`,
                        après les états. Poussée des non-joueurs `Δv = Σ J·n / (m + m_eff)` —
                        précision d'ADR-123 §7 : `J / m` surestimait la vitesse rendue d'un
                        facteur `(m + m_eff) / m`, J venant d'un proxy de masse infinie —,
                        dégâts `axion:collision` (type et messages de mort en données), effet
                        d'une entité isolé (un gestionnaire tiers qui lève ne désactive pas le
                        tick). Journal des faits de simulation : `E-2030`, `CLAMPED`, paliers de
                        dégradation avec métrique, valeur et budget (R-1880), collect refusé
                        (R-281, jamais imprimé jusqu'ici) — une ligne par assembly, fait et
                        minute, huit par tick au plus ; palier dans `/axion status` ; codes
                        d'événements générés pour Java (`PhysicsEventCodes`, parité). Vérifié :
                        JUnit, vérification croisée sur la vraie bibliothèque (contact natif →
                        poussée vers le bas, approche = vitesse de chute), 16 mutations tuées.
                  - [x] T5c-5 — essai en jeu, Killian lançant le jeu. Joué le 2026-10-04 :
                        effets confirmés par Killian, aucune erreur au journal ; le
                        gouverneur est passé en DEGRADED_1 (p95 62,9 ms), expliqué par
                        C-38 T4. Rejoué à 23:00 après C-38 T4 : 285 colliders, p95 de
                        0,076 ms, mais encore un DEGRADED_1 au tick 800 (p95 4,13 ms), que
                        la durée murale seule n'explique pas. **Pas instrumenté le
                        2026-10-05** (ADR-123 §9, précisions) : pas mesuré et décomposé,
                        dépassements comptés (INV-19), temps CPU du thread sous
                        surveillance, journal des ticks lents. Rejoué le 2026-10-06 (5 min,
                        cubes dans un lac) : le pas **calcule** — 130 dépassements sur 131
                        « surtout en calcul », dans l'intégration —, cascade jusqu'à
                        DEGRADED_3, et un cube de la densité de l'eau qui en jaillit puis
                        rebondit sans fin. Causes : eau en une boîte par bloc, recherche
                        linéaire de toutes les boîtes, poussée sur le volume de l'AABB —
                        corrigées (ADR-117, précision « eau » ; banc `charge_eau` : pas
                        28,6 → 0,04 ms). À rejouer pour confirmer en jeu.
                        Rejoué le 2026-10-06 avec la trace (`/axion debug trace`, C-71) :
                        un cube lâché de 25 m dans l'océan tourne à 47 rad/s dès l'entrée
                        dans l'eau. Cause : la tuile de la section voisine, où il débordait,
                        arrivée 7 ticks trop tard — seule la section abri passait en tête de
                        file. Corrigé : ce que l'emprise d'un corps touche d'ici deux ticks
                        passe en tête, et la trace écrit chaque tuile (ADR-117, précision
                        « ordre des tuiles »). À rejouer, trace active.
                        Rejoué à 19:08 : entrée dans l'eau propre (0 rad/s), mais deux
                        défauts nouveaux, corrigés : un cube posé sur une marche du fond
                        prenait 186 kJ (coins d'AABB dans la roche comptés secs), et la file
                        des tuiles, corrompue par les ChunkEvent.Load du client, rebâtissait
                        cinq sections à chaque tick. À rejouer au coin (−16,0 ; 128,0), dont
                        le voisinage n'a jamais été bâti.
                        Rejoué à 19:41 (Killian : « ça m'a l'air patché ») : quatre tuiles
                        posées en urgence au coin (−16 ; 128) dès le lâcher, aucune section
                        rebâtie plus de deux fois, file revenue à 0, entrée dans l'eau à
                        0 rad/s, énergie au fond 1,5 kJ au plus (186 avant), aucun changement
                        de palier. Reste attendu, hors T5 : ni traînée ni amortissement dans
                        l'eau (definitions physiques, étape du ballon).
            - Hors portée, nommé : palier `SAFE` de SM-02 (sièges M5, C-77), `DegradationEvent`
              et `ContactEvent` (API d'événements de C-70), filtre intra-assembly multi-corps
              (joints, M4).

      - [ ] **C-32 — Collider Builder** (fiche 5.24, R-620..623, T-310..312).
            Produit les colliders (`ColliderDesc`, DM-06) à la compilation et les
            porte jusqu'à l'A3D (section PHYS). DM-06 figé, validation C-22 prête.
            Périmètre : **complet, PHYS inclus** ; gels COM/PHYS derrière un ADR
            ratifié. La session est en effort max.
            - [x] **T1 — transport** : `colliders: Vec<ColliderDesc>` + tableaux
                  annexes (points convexes, enfants de compound, points
                  d'enveloppe) sur `ImportedAsset`, câblés dans `compile.rs`
                  (`AssetView.colliders`) pour que la validation tourne sur du
                  réel. Réversible, testable.
                  **Fait** : `colliders: Vec<ColliderDesc>` sur `ImportedAsset`,
                  câblé dans `compile.rs` (généré après `optimize`, validé) ;
                  `CompiledAsset.collider_count` ; module `collider` avec
                  `ColliderMode` (source par definition) et le générateur
                  `auto_box` (repli). 5 tests (T-310). Reste en T2 : extras node.
            - [x] **T2 — sourcing + auto-génération** (R-620/621) : chaîne extras
                  **Fait** : extra `shape` lu (`NodeAnnotations`), requêtes
                  `ColliderRequest` par node `role=collider` (défaut auto_convex
                  → averti/ignoré tant que V-HACD n'existe pas), génération par
                  node depuis l'AABB du mesh (auto_box/auto_sphere), lien
                  `node.collider`, `collider.part` hérité du node. Reste :
                  auto_capsule/convex/compound + densité/no_refit des extras.
                  node → definition → `auto_box` (AABB) / `auto_sphere` / 
                  `auto_capsule` / `auto_compound` (enfants, R-621) → aucun.
                  `auto_convex` marqué pour V-HACD (étape C-23, différée). Extras
                  glTF : lire forme/densité/material/no_refit.
            - [x] **T3 — PHYS + pont runtime** `[EFFORT MAX]`, **ADR-115** (ratifié,
                  à ratifier) : sérialiser la section `PHYS` (header `collider_count`
                  + `ColliderDesc[]`, test de disposition, `COMPILER_VERSION++`) ;
                  lecteur runtime ; conversion `ColliderShape → ax_physics::Shape`
                  (Box/Ball ; formes indexées refusées) ; `CREATE_ASSEMBLY → add_body`
                  (Rust FFI + Java). Masse/COM : calculées par rapier depuis les
                  densités (R-622) ; surcharge déclarée via CREATE_ASSEMBLY. Gèle
                  PHYS **avec** son consommateur. Débloque le test physique en jeu.
                  Reprise (session neuve), étapes ordonnées, chacune buildée+testée :
                  1) ✅ `encode_colliders` + écriture `PHYS` dans `write_container`
                     (compile.rs) + test de disposition ; `COMPILER_VERSION` 5→6.
                  2) ✅ lecteur `PHYS` (`decode_colliders`) (a3d/read) + revalidation C-22 au chargement.
                  3) ✅ `ColliderShape → ax_physics::Shape` (Box/Ball ; indexées
                     refusées) dans un pont (ax-ffi ou ax-scene).
                  4) ✅ `CREATE_ASSEMBLY` : lire PHYS → add_body ; masse/COM déclarés
                     **Décision : Option A** (Java envoie PHYS). Pièces :
                     a) ✅ `CreateAssembly` (opcode 0, ADR-114) : payload = en-tête
                        repr(C) {handle, dimension, spawn WorldTransform, body_kind}
                        + octets PHYS à la suite. Struct + test dans ax-model/commands.
                     b) ✅ **traité dans ax-ffi** (seul à dépendre d'ax-asset ET
                        ax-physics) : décode PHYS (`decode_colliders`) → convertit
                        (`Shape::from_collider_shape`) → 1 corps (compound si N>1) →
                        `SimDriver::register_body`. NB : le parseur SimIn d'ax-physics
                        ne peut pas (pas de dep ax-asset).
                     c) ✅ Java : localiser la section PHYS dans l'A3D (petit lecteur
                        d'en-tête/table de sections) + envoyer via SimIn.
                     d) ✅ Test Rust d'intégration (PHYS → corps dans le SimDriver).
                     surchargent rapier. 5) ✅ test physique en jeu (spawn→tick→bouge).
            - R-622 masse/COM : le **calcul depuis les densités** est câblé au runtime (un
              collider rapier par ColliderDesc avec sa densité ; masse, COM et inertie
              en découlent — `BodyCollider`, `add_assembly`). Reste **différé** (avec
              son producteur) : la voie **déclarée** masse/COM pré-calculée + `BodyDesc`
              (DM-08, gel), qui attend le câblage des definitions physiques ; REFITTABLE/NO_REFIT (R-623, dépend des parts/régions) ;
              **toutes les formes de collider sont faites** : `auto_box`, `auto_sphere`,
              `auto_capsule`, `convex`, `auto_compound`, et la **décomposition VHACD
              `auto_convex`** (parry3d + enhanced-determinism, ADR-108/ADR-116 ; bornes
              32/64, repli enveloppe globale) ; + `density`/`no_refit` des extras
              (R-622/R-623). Restent, différés avec leur dépendance : la masse/COM
              **déclarée** (`BodyDesc`/definitions) et `REFITTABLE` par défaut
              (déformation M6) ; la résolution VHACD à mesurer en M3 (ADR-108).
            - **Ballon de test** (demandé par Killian le 2026-10-05, reporté par lui à
              l'étape qui le porte). Aujourd'hui, seules sa taille et sa masse sont
              possibles (`auto_sphere`, `density` des extras) : il ne rebondirait pas —
              le matériau de contact de toute assembly est neutre, l'index DM-07 de
              `ColliderDesc` n'étant pas résolu (`body_colliders`, `abi.rs`) — et
              roulerait sans fin, les amortissements et la CCD du bloc `physics` des
              definitions étant validés mais jamais transmis. À faire avec le câblage
              des definitions physiques : table DM-07, règle de combinaison des
              coefficients, amortissements ; puis la definition `axion:test/ballon` en
              devcontent. À vérifier au passage, signalé par une exploration et pas
              encore relu : `auto_sphere` prend la demi-diagonale de l'AABB du maillage
              (rayon × √3 pour une sphère), et le repli capsule → sphère qu'annonce
              `collider.rs` est refusé par la validation.
              Le même câblage freinera un corps dans l'eau : sans amortissement ni
              `Cd`·`A` déclarés (§10.6), un cube y garde vitesse et rotation — il
              touche le fond à 22 m/s et en repart (essai du 2026-10-06, ADR-117).

      - [x] **C-38 — World Collision Provider** (fiche 5.30, R-640..643) — **implémenté**,
            toutes tranches faites et vérifiées (unitaire natif + frontière + compilation
            Forge). Restent pour l'**acceptance M3** les GameTests en jeu **T-370..T-375**
            (runGameTestServer) — non écrits, portés par la porte d'acceptance du jalon.
            Java construit des tuiles de collision (sections 16³) via
            `Level.getBlockCollisions` → boîtes → **corps STATIC** natifs ; C-31/C-32
            l'attendent (TriMesh/Heightfield du monde, R-970/INV-13). Java + Rust +
            frontière. Découpage, chacune buildée+testée :
            - [x] **T1 — natif : magasin de tuiles** (sans Forge, testable). Une tuile =
                  corps STATIC portant N colliders `Cuboid` (un par boîte, pas le
                  Compound plafonné à 64 des assemblies) via `add_assembly`. `SimDriver` :
                  `set_world_tile(dim, section, boxes)` / `remove_world_tile` / compte.
                  Clé (dim, section [i32;3]) déterministe. Test : un corps dynamique
                  repose sur une tuile (ne la traverse pas).
            - [x] **T1b — Heightfield** (repli R-641 > 4096 boîtes) : variante
                  `ax_physics::Shape::Heightfield` (hauteurs ligne-major, champ
                  centré x-z) + `shared_shape_of` (parry `Array2` colonne-major) +
                  garde INV-13 (concave interdite sur dynamique, même enfouie dans
                  un compound). Setter `SimDriver::set_world_tile_heightfield`.
                  Tests : un corps repose sur un champ plat ; refus sur dynamique ;
                  validation des dimensions.
            - [x] **T1c — matériau de tuile** (R-643) : type runtime `ContactMaterial`
                  (friction, restitution — sous-ensemble contact de DM-07) porté par
                  `BodyCollider`, appliqué dans `add_assembly`, avec assainissement
                  (non fini → défaut, bornage [0,2]/[0,1]). Les setters de tuile prennent
                  le matériau dominant. Modes de combinaison + rolling_friction : câblage
                  DM-07 complet ultérieur. Tests : friction et rebond A/B changent le
                  comportement ; défaut neutre ; assainissement.
            - [x] **T1d — fluides = capteurs de flottabilité** (R-642) : `FluidVolume`
                  (boîtes d'eau localisées par section) dans `PhysicsWorld` + setters
                  `SimDriver::set_world_fluid_tile` / remove / count. Poussée d'Archimède
                  et traînée du fluide échantillonnées aux 8 coins de l'AABB, sur la part
                  immergée ; volumes localisés (pas de plan infini), priment sur la surface
                  plate de dimension. Tests : léger flotte, dense coule, volume localisé,
                  remplace/retire.
            - [x] **T2 — frontière `[EFFORT MAX]`, ADR-117 ratifié (2026-09-30)** :
                  5 opcodes (6–10) SET_WORLD_COLLISION / SET_WORLD_HEIGHTFIELD /
                  SET_WORLD_FLUID / REMOVE_WORLD_COLLISION / REMOVE_WORLD_FLUID transcrits
                  dans `dm/commands.rs` (structs `repr(C)` figées + tests de disposition),
                  décodés en ligne dans `apply_command_stream` (sans ax-asset) : en-tête
                  fixe + corps variable (boîtes/hauteurs), comptes bornés avant allocation
                  (4096 / 64²). Tests : décode+pose+retire les trois genres, corps
                  incohérent et dépassement de plafond refusés. Passe `submit` inchangée.
            - [x] **T3 — Java + Forge** (sous-tranches) :
                  - [x] **T3a** encodeur `SimCommandStream` des 5 opcodes de tuiles
                    (miroir ADR-117, testé offset par offset).
                  - [x] **T3b** `WorldTileGeometry` : adressage de section,
                    quantification 1/16, boîte section-relative, seuil R-641 (testé).
                  - [x] **T3c** `block_materials` data-driven (bloc > tag > défaut) +
                    ressource livrée `block_materials.json` (testé).
                  - [x] **T3d** `WorldTilePlanner` : voisinage désiré, pompe amortie
                    (`world.tiles_per_tick`), éviction par rayon (`world.tile_radius`),
                    invalidation, ordre déterministe (testé).
                  - [x] **T3e-cœur** couture `WorldCollisionSource` + orchestration
                    `WorldTileService` (plan → commandes), testé avec source factice.
                  - [x] **T3e-Forge** : `DimensionId` (clé de dimension FNV-1a, partagée
                    C-38/C-40), `ForgeWorldCollisionSource` (lecture du Level : boîtes,
                    fluides, matériau dominant, R-640 solide, R-641 champ de hauteurs),
                    couture `SimCommandProvider` + branchement dans `driveSimulation`,
                    `WorldTileBridge` (sections des AxionEntity → tuiles, invalidation
                    bloc/chunk, chargement `block_materials`), câblage démarrage/arrêt
                    serveur. Compilé contre Forge, tests du mod verts ; comportement
                    visible en attente de corps dynamiques (C-40).
            - [x] **T4 — tuiles en compounds compacts** — rouvre C-38 le 2026-10-04. Écart
                  à la fiche 5.30, étapes 2 (« liste compacte de boîtes ») et 3 (« compounds
                  statiques ») : une tuile porte **un collider par bloc**, soit 731 136
                  colliders pour trois cubes au sol, et rapier 0.35 refait tout l'arbre de
                  sa phase large à chaque retrait de collider. Mesuré par
                  `cargo run --release -p ax-physics --example charge_tuiles` (Ryzen 7
                  5800X, médianes d'`advance_all`) : régime établi 0,16 ms ; un proxy qui
                  apparaît 0,40 ms, retiré 19,9 ms ; une section neuve 4,5 ms, reconstruite
                  26 ms ; diffusion à 8 sections par tick p95 65,6 ms. D'où le p95 de
                  62,9 ms relevé en jeu par FM-21. À faire selon la fiche : un compound
                  statique par tuile (le plafond de 64 de §10.3 vaut pour les assemblies)
                  et des boîtes compactées (fusion des blocs pleins) ; mesurer avant et
                  après avec l'exemple.
                  - [x] **T4a — natif** : la tuile devient un corps statique portant des
                    compounds d'au plus 64 boîtes (§10.3 et étape 3 de la fiche, lus
                    ensemble), regroupées par voisinage le long d'une courbe de Morton ;
                    compteur de colliders (jauge `axion.sim.colliders`) ; précision
                    d'ADR-117. Mesuré, même entrée d'une boîte par bloc : proxy retiré
                    19,9 → 0,12 ms, section reconstruite 26 → 0,44 ms, diffusion p95
                    65,6 → 3,14 ms. Tests : 64 paquets pour une section pleine, cubes de
                    4³ blocs, un cube repose sur une tuile en compounds, déterminisme,
                    jauge lue à travers l'ABI.
                  - [x] **T4b — Java** : fusion des blocs pleins en boîtes maximales
                    (« liste compacte », étape 2), logique pure testée dans
                    `WorldTileGeometry` ; le seuil de R-641 porte sur le compte fusionné.
                    Blocs pleins jugés sur la forme lue (`Block.isShapeFullBlock`). Tests :
                    section pleine → 1 boîte, sol → 1 dalle, gradins → 4 boîtes, damier
                    → 2 048, couverture exacte sans chevauchement sur 200 grilles au
                    hasard. Mesuré (`charge_tuiles`, sol plat) : 731 136 boîtes → 224,
                    colliders 11 427 → 227, pose de huit sections 12,3 → 0,01 ms, tous les
                    pas sous 0,07 ms de p95.
      - [x] **C-39 — Spatial Queries** (fiche 5.31, R-650..652) — **implémenté**. Module
            `ax_physics::query` : `raycast`, `sweep`, `overlap` + versions par lot, filtres
            par groupe/masque, exclusion d'assembly, capteurs (`SpatialFilter`/`SensorMode`).
            Sur la `QueryPipeline` de rapier (broad-phase courant) ; lecture seule (R-650),
            géométrie du dernier pas = début de tick (R-651). Types de résultat AXION
            (glam), aucun type rapier exposé. Tests : rayon touche/manque, filtre
            d'exclusion, overlap, sweep, non-mutation, lot (couvre T-380..T-384). R-652
            (géométrie déformée) et T-856 : avec la déformation, en M6.
      - [x] **C-40 — Simulation Scheduler** (fiche 5.32, R-660..662) — **composant
            implémenté**. Module `ax_physics::scheduler` : ordre normatif du pas **figé**
            (`Stage`/`Stage::ORDER`, 15 étapes, R-660 ; variation = ADR), chaîne de dommage
            (7-12) une fois par tick, étapes client (R-662). `advance` exécute ses étapes
            dans l'ordre explicite (intégration 4, contacts 6 par sous-pas ; créneau réservé
            chaîne de dommage après le dernier sous-pas), métrique par étape (`StageDurations`,
            R-661, observationnelle). `SimMode` : le client n'intègre pas l'autoritaire
            (INV-17). Étapes 2,3,5,7-13 réservées (composants futurs). Tests : ordre R-660,
            chaîne 7-12, étapes client, serveur intègre/mesure, client skip.
            - [x] **Boucle entité (intégration C-40 ↔ C-50)** — câblée. `SimStateSink` +
                  `AssemblyRuntime` (Forge) : au spawn d'une entité liée → résolution PHYS de
                  l'asset + handle + `CREATE_ASSEMBLY` ; chaque tick, `driveSimulation` applique
                  les `BodyState` collectés → `entity.setPos` (n'ignore plus `result.bodies()`) ;
                  `REMOVE_ASSEMBLY` au despawn. `SimCommandStream.removeAssembly`/`merge`,
                  `AxionRuntime` multi-fournisseurs + puits. Contenu de test : `axion:test_cube`
                  (cube glTF à collider `auto_box`).
                  **Vérifié + OBSERVÉ EN JEU (2026-10-01)** : `/axion spawn axion:test_cube`
                  par Killian → le cube **tombe** (payoff atteint). Aussi : mécanisme natif
                  prouvé par composition (tests `sim.rs` + `create_assembly_abi` FFI), encodage
                  testé, contenu charge (cube compilé, 1 definition 0 refusée).
                  **Finitions (2026-10-01) — résolues :**
                  - [x] **Chute au ralenti (1/3 de la vitesse)** : la frontière FFI injectait
                    `SIM_TICK_DT = 1/60` dans l'accumulateur (R-283) à chaque tick serveur
                    (1/20 s) → un seul sous-pas au lieu de trois. Corrigé : temps réel du tick
                    (`SERVER_TICK_DT = 1/20`, 3 sous-pas). Vitesse réelle observée en jeu.
                    Commit 80081ac.
                  - [x] **Flottement d'~1 bloc au repos** : ne se reproduit plus (observé par
                    Killian). La cause suspectée (tuile de sol en retard) est par ailleurs
                    traitée par la priorité de tuile ci-dessous.
                  - [x] **Saccade / chute « bloc par bloc »** : `Entity.lerpTo` téléporte à
                    chaque paquet ; ajout d'une interpolation client sur `AxionEntity` (mémorise
                    la cible, y glisse en `steps` ticks) + `updateInterval(1)`. Chute observée
                    **quasi fluide** par Killian ; le lissage sous-tick et le rendu orienté
                    restent à C-60+. Commit a129aa1.
                  - [x] **Cube enfoncé sous les blocs au rechargement** : au redémarrage du
                    serveur le planificateur de tuiles repart froid ; le corps recréé tombait
                    avant que sa section soit reconstruite. `WorldTilePlanner` bâtit désormais en
                    priorité la section abritant une assembly (+ test). Commit a13e3e4.
                    **Rechargement observé en jeu (2026-10-01) : le cube reste posé.**
      - [ ] **Acceptance M3 — GameTests et scénarios** (§29.5, PARTIE 37.2). La porte du jalon :
            « un objet tombe, repose sur le sol, est rendu dans les deux backends », vérifiée
            mécaniquement (R-2392). Inventaire du 2026-10-06 : des 44 tests cités par M3, 11
            existent (T-300..T-307, T-310, T-311, T-550) ; T-308 et T-309 ne sont attribués par
            aucune fiche (C-31 s'arrête à T-307, C-32 commence à T-310).
            - [x] **A — socle** : Java du source set `devcontent` (jamais empaqueté, R-1790)
                  compilé contre le mod ; structure de test générée par un script ; premier
                  `@GameTest` : chute libre à 1 % près, puis repos sur le sol ;
                  `runGameTestServer` vert. **Fait le 2026-10-06** : `devcontentImplementation`,
                  gabarit `plancher` (`tools/gametest/structures.py`), `PhysiqueGameTests`,
                  `ObservateursDeTest` — sans joueur, R-612 endort tout corps, et le joueur
                  factice de Minecraft n'entre pas dans un monde Forge (canal réseau nul) ; job
                  `test-gametest` en CI. Le test a trouvé un défaut : l'accumulateur `f32` de
                  `PhysicsWorld::advance` perdait un sous-pas au premier tick, puis gardait ce
                  retard (chute 1,45 % trop courte à 2 s) — corrigé, tolérance d'un millième de
                  sous-pas (R-990).
            - [x] **B — scénarios déclaratifs** (§29.5) : JSON `structure`/`setup`/`asserts`/
                  `tolerance` joués en GameTest ; empilement stable 60 s, aucune traversée du sol
                  sur 10 min. **Fait le 2026-10-06** (ADR-124, proposé) :
                  `ScenarioGameTests` change chaque `data/axion/gametest/scenarios/<domaine>/
                  <nom>.json` en GameTest, validation stricte ; `physique/empilement_60s` et
                  `physique/aucune_traversee_10min` verts. Ils ont trouvé deux défauts, corrigés :
                  des tuiles périmées après un changement de bloc sans casse ni pose par une
                  entité (C-38 : mises à jour de voisins et game events de bloc, ADR-117) et le
                  monde de développement repris par le serveur de GameTests (`run-gametest/`,
                  effacé à chaque passage). « État restauré » n'est pas une sauvegarde suivie
                  d'un rechargement, comme lu d'abord : c'est T-472, l'état GL et le FBO
                  restaurés après chaque passe (§19.13), en tranche D. **Point ouvert** :
                  `empilement_60s` a échoué 2 fois sur 27 passages consignés — pile restée
                  debout, glissée de 33 puis 19 cm au tick 1200 —, aucune sur les dix passages
                  d'une chasse dédiée. Écartés : la pile native (stable), une tuile reposée sous
                  elle (2,9 cm au plus), le gouverneur FM-21 (aucun changement de palier dans le
                  passage fautif, pas physique dans son budget), la compilation fraîche des
                  assets (deux passages neufs verts). Le message d'échec porte désormais les
                  trajectoires : la prochaine occurrence dira quand la pile part.
            - [x] **C — T-370..T-375 et T-380..T-384** : attribution par exigence (comme ADR-123
                  §12 pour T-300..T-307), tests existants étiquetés, manquants écrits. **Fait le
                  2026-10-06** (ADR-125, proposé) : tests natifs préfixés `t37x_`/`t38x_`, classes
                  de test Java étiquetées en Javadoc ; écrits : T-381..T-384 (balayage et
                  recouvrement par lot, groupes, capteurs, monde du dernier pas) et, pour T-374, les
                  GameTests `CollisionDuMondeGameTests` — section non chargée rendue pleine sans
                  charger son chunk (garde retirée, le test échoue : vérifié), champ de hauteurs sur
                  de vrais escaliers. Trou comblé : l'avertissement de R-641 manquait. Inventaire :
                  22 des 44 tests de M3 existent ; restent T-312 (C-32), le rendu (tranche D) et
                  T-308/T-309, sans fiche. Sans test : la moitié Java de R-651, faute d'API de requête.
            - [ ] **D — rendu** : T-470..T-474, T-479, T-480, T-490..T-493, T-500..T-503,
                  T-510..T-512, T-551, avec C-60, C-62 et C-63. Critères de PARTIE 37.2 :
                  aucune erreur GL (T-471), état GL et FBO restaurés (T-472), bascule sous
                  shaderpack (T-480).
                  - [x] **D1 — banc de rendu client** (ADR-126, proposé) : client automatisé
                        (`runRenderTest`, contenu de développement), contrôle de l'état GL de R-1503
                        (`GlStateCheck`), job `test-render` (34.3) sous Xvfb et Mesa llvmpipe ;
                        T-470..T-474 sur le backend vanilla. **Vert en CI le 2026-10-10** (run
                        38008458150, 5 min) : T-470 0 pixel d'écart avec et sans AXION ; T-471 10 001
                        frames sans erreur GL ; T-472 0 état non restauré sur 20 652 passes ; T-474 cube
                        droit et tourné à 1 pixel de leur projection, faces ombrées, minuit plus sombre.
                        Il a trouvé un vrai défaut — la passe TRANSLUCENT laissait mélange et profondeur
                        coupés (Forge publie le stage avant que la couche ne se referme) : garde de
                        R-1502 dès le backend vanilla (ADR-126 §5) —, et trois hypothèses fausses sur sa
                        propre scène : réentrance de `createFreshLevel`, stabilité supposée d'après un
                        délai, lumière de bloc qui vacille au hasard. Lancé chez Killian : à sa demande.
                  - [ ] **D2 — C-62, ressources GPU** (fiche 5.49) : arènes de 16 Mio (VBO/EBO
                        partagés), allocations comptées et budget (R-750), libération différée de
                        3 frames, sur le render thread (R-751), reconstruction au changement de
                        resource pack (R-752) ; T-500..T-503 attribués par ADR-127 §8.
                  - [ ] **D3 — C-63, shaders** (fiche 5.50) : sources du JAR, variantes par
                        `#define` bornées (R-762), compilées sans à-coup et sans appel GL hors du
                        render thread (INV-12), cache binaire (R-760), échec → vanilla (R-761,
                        T-479) ; T-510..T-512 attribués par ADR-127 §8.
                  - [ ] **D4 — C-60, backend natif** (PARTIE 19) : ADR-127 (proposé, C-60/C-62/C-63). En
                        M3 les assemblies sont rigides : le natif peut dessiner la liste de repos déjà
                        transférée (`RestDraw`, ADR-119), transformée en Java, sans nouvelle surface FFI ;
                        IF-05 (`axion_render_prepare`, §4.7) viendrait en M4 avec C-64/C-65/C-66, qui en
                        ont besoin. **Si l'ADR retient une ABI dès M3 : `[EFFORT MAX]` et ratification de
                        Killian.** Puis upload au format du §19.4, `GlStateGuard` (R-1500..R-1505), passes
                        du §19.10, éclairage en attendant la sonde C-81 de M9 ; T-470..T-474 rejoués.
                  - [ ] **D5 — C-61 T4+ et bascules** : bascule à chaud (R-744, T-492),
                        invariants entre backends (T-491, T-491b..d), shaderpack (T-480, T-490) — ce
                        dernier suppose un mod de shaders en développement, à télécharger avec
                        l'accord de Killian.
                  - [ ] **D6 — T-551** : coût d'un overlay éteint, mesuré avec les métriques de C-72.
      - [ ] **C-61 — Backend VANILLA_CONSUMER** (fiche 5.48, PARTIE 19) — ouvre la chaîne de
            rendu, **vanilla d'abord** puis C-60 natif (ADR-118, ratifié 2026-10-01).
            - [x] **T1 — couture + preuve de passe** : `RenderBackend`, sélection pure
                  `BackendSelection` (R-1490/R-1491, 5 tests), passe centrale
                  `AxionRenderPass` @ `AFTER_ENTITIES` (R-1570), backend vanilla dessinant une
                  boîte à la position interpolée, sans GL direct (R-741). **Observé en jeu** :
                  la boîte s'affiche et tombe de façon fluide. Commit 18434fd.
            - [x] **T2 — vrai maillage** (ADR-119, ratifié 2026-10-01) :
                  - [x] **T2a** `[EFFORT MAX]` — natif : `axion_asset_load/geometry/unload`,
                        `decode_geometry`, liste de dessin au repos, arène PERSISTENT, bilan
                        d'arrêt ; pont JNI ; `NativeAssetLoader`, `GeometryTransfer`. Commit f27b216.
                  - [x] **T2b** (effort élevé, Java seul, aucun contrat touché) :
                        1. `AxionRuntime` : `definitions`/`assets` volatiles ; registry d'assets
                           publiée après `discover()` ; libérateurs natifs exécutés dans
                           `shutdown()` avant `nativeApi.close`.
                        2. `AssetRegistry` : publication des A3D utilisables dans une table
                           concurrente (`Published(assetId, key, a3d)`), retirée à l'échec, au
                           changement de clé, à la disparition, à `forceRecompile`.
                        3. `AssetLoader` (interface, comme `AssetCompiler`) implémentée par
                           `NativeAssetLoader`.
                        4. `MeshCache` (`dev.axion.render`, pur) : `get` du thread de rendu sans
                           blocage (table concurrente) ; chargement sur un thread de fond dédié ;
                           verrou natif + époque : chaque chargement vérifie époque/fermeture
                           sous verrou, un chargement supplanté rend son handle ; même clé
                           d'asset = même maillage (pas de rechargement) ; `releaseAll` (sortie
                           du monde, `ClientPlayerNetworkEvent.LoggingOut`) et `close`
                           (libérateur natif). Ordre Forge vérifié : quitter depuis un monde émet
                           `GameShuttingDownEvent` (stop) puis LoggingOut (clearLevel), puis
                           l'arrêt natif sur le thread serveur.
                        5. Backend vanilla : `entitySolid` (texture blanche `axion:textures/
                           misc/white.png`), `entityCutoutNoCull` si `DOUBLE_SIDED`, meshes
                           `TRANSPARENT` différés (T3) ; triangles en quads dégénérés ; modèle
                           mat4x3 → `Matrix4f`, normales par l'inverse-transposée, renormalisées ;
                           déterminant négatif → ordre inversé ; boîte de T1 tant que le maillage
                           n'est pas prêt (et en multijoueur).
                        6. `cube.gltf` : vrai cube 24 sommets / 36 indices, bas-centre
                           `[-0.5,0.5]×[0,1]×[-0.5,0.5]`, node visible + node collider
                           `auto_box`. Origine du corps = origine de l'asset (`body.position()`,
                           pas le centre de masse) : maillage, collider et hitbox alignés.
                        7. Vérifié : 258 tests Java verts (dont 10 du cache, 5 de publication,
                           1 d'ordre libération → fermeture) ; cube compilé par `axion-cli`
                           (24 sommets, boîte [-0.5,0,-0.5]→[0.5,1,0.5], 2 nodes, PHYS).
                           **Observé en jeu (2026-10-02)** : le cube blanc s'affiche et tombe
                           de façon fluide ; arrêt du natif « code 0 » (aucun handle oublié).
            - [x] **T2c — orientation du corps au client** (décisions de Killian, 2026-10-02 :
                  pont provisoire vanilla jusqu'à C-51/M4 ; rotation persistée) :
                  1. `AxionEntity` : donnée synchronisée `QUATERNION` posée par `applyStates`
                     depuis `BodyState.rotation` ; côté client, slerp en 3 pas comme la
                     position (`lerpTo`), puis entre deux ticks au rendu.
                  2. NBT : `axion:rot`, 4 flottants `(x,y,z,w)` (§22.2, R-461) ; relu au
                     chargement, et le corps est recréé avec cette orientation (plus
                     d'identité forcée dans `AssemblyRuntime`). Test : spawn incliné par NBT.
                  3. Rendu : `mulPose(rotation)` autour de l'origine du corps (bas-centre),
                     maillage et boîte de repli.
                  4. Remplacé tel quel par `S2C_Snapshot` (rotation smallest three, slerp,
                     PARTIE 21.5/21.7) en M4.
                  Vérifié : 261 tests Java verts (3 sur `axion:rot`). **Observé en jeu
                  (2026-10-02)** : cubes apparus inclinés par NBT (arête, coin), basculent au
                  contact et se posent à plat, rotation fluide. Persistance au rechargement
                  couverte par les tests seulement (un cube posé à plat n'en montre rien).
                  Constaté : la hitbox reste un carré 1×1 → R-702 (tranche suivante).
      - [x] **C-67 — overlay `colliders`** (ADR-121, ratifié le 2026-10-02) : voir la vraie
            forme du corps, tournée avec lui. `/axion debug colliders on|off`, commande client,
            mode développeur requis (`-Daxion.dev=true`).
            - [x] **T-a** `[EFFORT MAX]` — `DebugBody`/`DebugSegment` (24 o) + tests de
                  disposition ; tracé des colliders en repère du corps (parry `to_outline`,
                  vraies arêtes des convexes, composés, pose locale) ; tri par distance,
                  budget, omissions ; lecture seule prouvée ; `axion_debug_fill`, `DEBUG`
                  schéma 1, test d'ABI ; lecture Java (JNI réelle).
                  Fait (2026-10-02) : parry 0.30.2 ne compile ni le tracé du convexe ni
                  celui du champ de hauteurs — convexe par contour de faces (`edges()` en
                  donne 18 pour un cube, prouvé), champ de hauteurs par ses triangles de
                  collision. 19 tests Rust (disposition, formes, statiques, sommeil, tri,
                  budget, dimension inconnue, lecture seule bit à bit, ABI de bout en bout) ;
                  Java : `DebugGeometry`, `NativeDebugLoader`, JNI réelle. cargo 722 verts,
                  clippy/fmt propres, Java 270 verts.
            - [x] **T-b** — commande client, état des overlays, appel par tick, dessin à la
                  pose interpolée, index de handle = identifiant d'entité ; vérifié en jeu.
                  Fait (2026-10-02) : `AxionClientCommands` (racine client `axion` ne
                  portant que `debug`, refus hors mode développeur) ; `DevMode` ;
                  `DebugOverlays` (bit = rang du §31.4) ; `DebugOverlayRenderer` (un appel
                  natif par tick client, lignes vanilla posées par `AssemblyPlacement`,
                  la pose du maillage ; couleur selon le corps ; budget de 16 384
                  segments, valeur de conception non mesurée ; une erreur éteint les
                  overlays, pas la passe) ; `AssemblyRuntime.handleIndexOf` = identifiant
                  d'entité ; `runClient` passe `-Daxion.dev=true`. Java 285 verts (15
                  nouveaux : T-702, état des overlays, arbre de commandes, lignes émises).
                  **Observé en jeu (2026-10-02)** : les lignes suivent le cube qui
                  bascule ; `/axion spawn` toujours transmis au serveur ; arrêt du natif
                  « code 0 ». Non exercés en jeu : `off` et le passage au gris-bleu d'un
                  corps endormi (couverts par les tests).
            - Hors portée, nommé : 34 autres overlays, multijoueur, mesure T-551 (C-72).
            - [x] **T3 — stages de passes et matrice de capacités** (décision de Killian,
                  2026-10-02 : l'actionnable de T3+ d'abord, puis C-26) :
                  1. R-1570 : passe DEBUG (overlay `colliders`) au stage `AFTER_PARTICLES` —
                     elle était à `AFTER_ENTITIES` depuis C-67 T-b, écart corrigé ; l'opaque
                     reste à `AFTER_ENTITIES`.
                  2. R-1493 : matrice de capacités du §19.2bis par backend, en pur Java
                     (`RenderCapabilities`), en trois états — disponible, indisponible dans ce
                     backend (désactivée, avec son repli), pas encore livrée (avec le composant
                     qui l'apportera). Entrée de log unique au démarrage du backend ; section
                     « rendu » de `/axion status` (solo : backend du client ; serveur dédié :
                     sans objet).
                  Fait (2026-10-02) : `RenderCapabilities` (`dev.axion.render`, pur Java) ;
                  entrée de log au démarrage du backend ; `AxionRuntime.renderCapabilities`,
                  lue par `StatusReport` ; passes par stage dans `AxionRenderPass`. Java 292
                  verts (7 nouveaux). **Observé en jeu (2026-10-02)** : entrée de log au
                  chargement de chaque monde ; section « rendu » de `/axion status` (confirmée
                  par Killian) ; overlay `colliders` allumé et cubes spawnés, sans erreur au
                  journal.
            - [ ] **T4+ — rattaché aux composants qui le rendent possible** : R-742 (skinning
                  CPU avec C-66, déformation CPU en M6) ; R-743 (décalques, C-69) ; R-744,
                  T-490..T-492 et réévaluation de R-1490 au changement de resource pack et de
                  shaderpack (second backend, C-60) ; indicateur de capacités dans l'overlay
                  (C-73, M5) ; exposition API de R-1495 (`render()` absent de l'API du §23.2 :
                  ADR à rédiger) ; backend natif C-60 (son propre ADR).
      - [x] **C-50 — avertissement parasite au spawn** (constaté à l'essai de C-67 T-b) :
            chaque `/axion spawn` portant `axion:rot` journalise « assembly … inerte — NBT
            sans axion:v ni axion:def », alors que l'entité est liée juste après. La garde
            « entité fraîche de commande » de `readAdditionalSaveData` teste
            `stored.isEmpty()`, que `axion:rot` (T2c) contourne. Ne signaler que
            l'assembly qui entre dans le monde encore inerte.
            Fait (2026-10-02) : avertissement déplacé dans `onAddedToWorld` (côté serveur),
            que Forge appelle une fois par entité ajoutée, fraîche ou chargée du disque
            (bytecode lu) ; la raison d'inertie lue y est gardée. `/summon axion:assembly`
            sans données, que l'ancienne garde taisait, est désormais signalé (R-704).
            **Observé en jeu (2026-10-02)** : `/axion spawn` avec `axion:rot` ne signale
            plus rien ; `/summon axion:assembly` signale une fois ; à la réouverture du
            monde (session suivante), l'assembly invoquée le redit une fois et les cubes
            liés ne disent rien.
      - [x] **C-40/C-50 — risque : corps perdu quand un tronçon redevient visible avant son
            déchargement** (lu dans le bytecode de Forge 47.4.23, non observé en jeu) :
            `EntityLeaveLevelEvent` part à chaque fin de suivi (tronçon passé `HIDDEN`), mais
            `EntityJoinLevelEvent` seulement à l'ajout. Si le tronçon redevient visible avant
            `processUnloads`, la même entité est de nouveau suivie sans Join : `AssemblyRuntime`
            a retiré son corps et ne le recrée pas. À traiter : réconcilier (corps manquant
            pour une assembly suivie) plutôt que se fier à la paire Join/Leave. Voir la note
            BDC « Sur Forge 47 une entité entre au monde à son ajout mais en sort à la fin de
            son suivi ».
            Fait (2026-10-03) : `BodyLedger` (pur, `dev.axion.physics`) — une sortie sans retrait
            (`getRemovalReason() == null`, posée par `setRemoved` avant le gestionnaire : bytecode
            lu) met l'assembly de côté ; chaque tick, avant les commandes, celles que le monde suit
            de nouveau (`level.getEntity(id)`, qui ne lit que `visibleEntityStorage` : bytecode lu)
            reçoivent un corps, celles retirées depuis sont oubliées. 7 tests, trois mutations
            attrapées ; Java 400 verts. Le cas lui-même (un tick de fenêtre) ne se provoque pas à la
            main. En jeu (Killian) : spawn et chute, aller-retour au-delà de la distance de rendu,
            rapportés conformes ; le journal ne montre pas de rechargement du monde.
            CI verte : run 37153898062 du commit 886e012, neuf jobs, quatre plateformes.
      - [ ] **`/axion remove` a rendu « 0 assembly(s) retirée(s) »** (essai du 2026-10-03,
            23:02:37, 25 s après un spawn) : cible ne désignant pas le cube, ou cube disparu ?
            Non élucidé ; Killian : « on verra ça plus tard ». Rejouer avec
            `/axion remove @e[type=axion:assembly]` en regardant si le cube est encore là.
      - [x] **R-903 — le fuzzing ne tourne jamais** (constaté le 2026-10-02) : `fuzz.yml` ne se
            déclenche que sur pull request touchant `crates/ax-asset/**`, ou à la main ; tout
            est poussé sur `master`, donc aucune campagne depuis le 12/09, dont les trois
            derniers runs avaient échoué en 4 s. `fuzz/Cargo.lock` était périmé (sans
            `parry3d`) : rafraîchi avec T-a1 de C-26. À décider : déclenchement sur push, ou
            campagne manuelle régulière.
            Décidé par Killian le 2026-10-03 : chaque nuit. `schedule` à 02:17 UTC, une heure par
            cible comme le prévoit 34.3, gratuit depuis que le dépôt est public.
      - [x] **C-26 — textures et matériaux (client)** (ADR-122, ratifié le 2026-10-02) : MATL
            = DM-05 tel quel, TEXR (image + échantillonneur, EMBEDDED en PNG non décodé ou
            RESOURCE en chemin relatif), UV par mesh (`MeshDesc._pad` → `uv0_range`, R-142),
            primitives multiples (`NodeDesc._pad` → `mesh_count`), filtrage par défaut au plus
            proche ; `RenderType` propres (mipmaps R-570), passes translucide et émissive.
            - [x] **T-a** `[EFFORT MAX]` — format et frontière, en quatre sous-tranches :
                  - [x] **T-a1 — format** : `MaterialDesc` (DM-05, 96 o) et `TextureDesc`
                        (16 o) dans `ax-model`, contrôles et tests de disposition ; sections
                        `MATL`/`TEXR` (`ax-asset`, encodage et décodage, disposition inconnue
                        ignorée) ; `png` (signature et IHDR, sans décodage, R-532) ;
                        migration R-893 (`v1.1-matl-provisoire.a3d`, compilateur 6) ;
                        décodeurs ajoutés au fuzzing et au rejeu du corpus. Le compilateur
                        écrit encore l'ancien `MATL` : `COMPILER_VERSION` inchangé (6).
                        cargo 744 verts (22 nouveaux), Java 292 verts.
                  - [x] **T-a2 — import** : glTF/OBJ/STL → DM-05 et TEXR, selon le §3 et ses
                        « Précisions de T-a2 » : images embarquées (`bufferView`, `data:`) et
                        chemins, PNG seul et ≤ 4096 (texture refusée seule, E-3004/E-3006),
                        échantillonneurs, `KHR_texture_transform` de l'albedo cuite dans `uv0`,
                        `COLOR_0` et `VERTEX_COLOR`, clearcoat/sheen/anisotropy lus en JSON brut,
                        ORM empaqueté, matériau par défaut, `TRANSPARENT`/`DOUBLE_SIDED`
                        recopiés ; MTL : options de texture lues (`-bm`, `-s`, `-o`, `-clamp`),
                        `Ke`, `Pr`/`Pm`, `map_Ke`, rugosité `(2/(Ns+2))^¼` (correction du §3),
                        `v` retourné ; pertes dites dans `material_warnings` ; C-22 contrôle
                        DM-05, slots, index de matériau et drapeaux ; `MATL`/`TEXR` écrites ;
                        `COMPILER_VERSION` 7 des deux côtés ; `gltf_refs` contrôle `COLOR_n`
                        (`unreachable!()` de `read_colors`). cargo 781 verts (37 nouveaux, trois
                        mutations attrapées), Java 292 verts.
                        Constat pour T-a3 (exécuté) : un objet OBJ à plusieurs `usemtl` donne
                        plusieurs nodes de même nom, que C-22 refuse pour doublon — un node,
                        plusieurs meshes (`mesh_count`). Pour T-b : chemin `RESOURCE` pris tel
                        quel par Java, sans décodage `%`, revalidé (R-531).
                  - [x] **T-a3 — UV et primitives** : `uv0_range` par mesh (R-142) et
                        `mesh_count` (DM-03), selon les §4-5 et les « Précisions de T-a3 ».
                        `UvRange` dans `ax-model` (codage, quantification, décodage épinglé au
                        bit, Rust et Java) ; normalisation dans l'optimizer avant la fusion ;
                        plage de meshes parcourue par les bornes, les LOD, la liste de dessin (un
                        `RestDraw` par mesh), le graphe et les colliders ; glTF : toutes les
                        primitives d'un mesh, mesh sans primitive refusé ; OBJ : un objet
                        découpé par `usemtl` = un node ; C-22 et décodeur `GEOM` : plages de
                        meshes, `uv0_range`, sommets partagés sous une seule plage ; Java
                        `GeometryTransfer` décode chaque sommet dans sa plage ; `COMPILER_VERSION`
                        8 des deux côtés ; fixture R-893 du compilateur 7 (graine de fuzzing).
                  - [x] **T-a4 — frontière** : `axion_asset_load` (MATL | TEXR, demandées
                        ensemble), `axion_asset_materials`, `axion_asset_texture`, `ASSET_OUT`
                        schéma 1, pont JNI ; lecture Java contre la vraie JNI ; selon le §6 et
                        les « Précisions de T-a4 ». Disposition inconnue : `MATL` → table
                        vide, `TEXR` → slots vidés ; `TEXR` absente sous des slots → E-3007 ;
                        matériaux et table de `TEXR` imputés à PERSISTENT. Java :
                        `MaterialTransfer`, `NativeAssetLoader.materials`/`texture`,
                        `RENDER_SECTIONS` avec `MATL | TEXR`. cargo 821 verts (11 nouveaux,
                        cinq mutations attrapées), Java 298 verts, vraie JNI comprise.
                        Pour T-b : un mesh dont le matériau manque à la table (MATL ignoré)
                        prend le matériau par défaut, signalé une fois.
            - [x] **T-b** — client, selon le §7 d'ADR-122, en quatre sous-tranches :
                  - [x] **T-b1 — textures** : octets (FFI pour EMBEDDED ; `ResourceManager`
                        client pour RESOURCE, chemin résolu contre le répertoire du modèle et
                        revalidé, R-531), signature PNG (E-3004), `NativeImage.read`, côtés
                        ≤ 4096 (E-3006), variante CUTOUT (alpha binarisé au seuil
                        `alpha_cutoff / albedo.a`), mipmaps (`MipmapGenerator`) — hors du fil de
                        rendu ; téléversement sur le fil de rendu dans une `AbstractTexture`
                        dont `load` ne fait rien ; texture neutre et diagnostic unique en cas
                        d'échec ; libérations à la sortie du monde, au rechargement (R-752) et
                        à l'arrêt du natif, toujours sur le fil de rendu (R-751).
                        Fait : le cache charge l'asset et ses textures ensemble, et ne le sert
                        qu'une fois les textures téléversées ; « Précisions de T-b1 ». Java 323
                        verts (25 nouveaux, cinq mutations du cache attrapées) ; le collage
                        Minecraft n'est exercé qu'en jeu, avec T-b2.
                  - [x] **T-b2 — `RenderType` et couleurs** : `RenderType` propres, mémorisés
                        par (texture, mode, faces, filtrage) ; OPAQUE et CUTOUT texturés à
                        `AFTER_ENTITIES` ; couleur = facteur × couleur de sommet, ramenée en
                        gamma, une fois par (mesh, matériau) ; `UNLIT` et `FULLBRIGHT` en pleine
                        lumière ; matériau absent de la table → matériau par défaut, signalé
                        une fois.
                        Fait : `AxionRenderTypes` (composition vanilla, filtrage du
                        téléversement, sans contour), passes 1 puis 2 en lots par type,
                        apparences sur le thread de fond, frontière qui refuse les énumérations
                        inconnues, libération différée au remplacement ; « Précisions de T-b2 ».
                        Java 350 verts (27 nouveaux, dix-sept mutations attrapées) ; le collage
                        Minecraft n'est exercé qu'en jeu, avec T-b4.
                        CI verte : run 37144482783 du commit 8909683, qui porte T-b2 à T-b4 —
                        neuf jobs, les quatre plateformes de la matrice comprises (2026-10-03, après
                        le passage du dépôt en public ; les runs précédents avaient été refusés pour
                        facturation).
                  - [x] **T-b3 — translucide et émissive** : passe 4 à
                        `AFTER_TRANSLUCENT_BLOCKS`, triée par quad et vidée dans le
                        gestionnaire ; passe 5 (`eyes`, additive) ; matrice de capacités —
                        normal, ORM, height et damage sans effet en vanilla (R-1513, R-1493).
                        Plan, d'après le bytecode et les shaders lus le 2026-10-03 :
                        1. émissive d'un matériau CUTOUT : `eyes` ne rejette aucun texel et
                           brillerait dans les trous ; variante « masquée » — RVB de
                           l'émissive, ou blanc, × masque de l'albedo binarisé au seuil —,
                           préparée d'une requête à deux images ;
                        2. passe 5 à `AFTER_ENTITIES`, après les passes 1 et 2 : `eyes`,
                           additive, profondeur non écrite, couleur = facteur émissif en gamma
                           borné à 1 ; sur une surface translucide, dessinée avant elle
                           (R-1570), l'émission est atténuée par sa transparence — déclaré ;
                        3. passe 4 : `entity_translucent_cull` / `entity_translucent`, tri des
                           quads au téléversement ; meshes du plus loin au plus près (distance
                           au carré de leur centre, R-1580), lots consécutifs vidés dans le
                           gestionnaire ; `MAIN_TARGET` ne lie rien : sous « Fabulous », la
                           cible translucide reçoit le dessin ;
                        4. matrice : cartes normal, ORM, hauteur et dommage indisponibles en
                           vanilla, déclarées.
                        Fait : lecture des PNG par un flux — `read(byte[])` les copiait sur la
                        pile de LWJGL, 64 Kio, qu'une texture plus grosse faisait déborder, écart
                        de T-b1 —, émissive masquée, passe 5 `eyes`, passe 4 triée et vidée dans
                        le gestionnaire, matrice ; « Précisions de T-b3 ». Java 365 verts (15
                        nouveaux, onze mutations attrapées). CI verte, même run que T-b2.
                  - [x] **T-b4 — contenu de test, vérifié en jeu** : modèles faits dans
                        Blender (cube texturé embarqué, texture RESOURCE, vitre, grille
                        découpée, panneau émissif, mesh multi-matériau, sol répété, texture
                        PNG de plus de 64 Kio), gardés
                        dans le dépôt et rejoués à chaque étape.
                        Contenu fait le 2026-10-03 par le MCP de Blender, dans `src/devcontent`,
                        branché sur les seuls lancements de développement (R-1790) : sept modèles
                        compilés par `axion-cli`, definitions `axion:test/materiaux/*`, source
                        `src/devcontent/blender/materiaux_t-b4.blend`.
                        Vérifié en jeu le 2026-10-03 par Killian (client de développement, backend
                        VANILLA, 9 assets prêts sur 9, aucun diagnostic de texture ni de matériau au
                        journal) : les sept modèles conformes de jour ; de nuit, bandes et grille
                        lumineuses sans lueur dans les trous ; vitre transparente sous « Fabuleux » ;
                        F3 + T sans perte. Le collage Minecraft de T-b1 à T-b3 est ainsi exercé.
                        CI verte, même run que T-b2.
            - [x] **T-c** — atlas AXION des textures ≤ 256² (meshes dans `[0,1]`).
                  Plan (2026-10-03, d'après le code et le bytecode de `MipmapGenerator` lus) :
                  1. **Un atlas par chargement d'asset et par filtrage** (au plus proche, linéaire) : les
                     textures préparées, variantes comprises, de côtés ≤ 256, dont chaque mesh qui les
                     désigne a ses UV dans `[0,1]` ; au moins deux par groupe. Les autres restent
                     individuelles. Cycle de vie inchangé : la page est une texture de l'asset comme les
                     autres, enregistrée sous `…/<chargement>/atlas/<page>`, libérée avec lui ;
                  2. **Disposition** : marge de `2^L` texels (`L` = réglage des mipmaps) remplie selon
                     l'échantillonnage — texels enroulés en répétition, bord recopié en écrêtage —, ce
                     qui rend au bord le texel que la texture seule aurait rendu, à tous les niveaux ;
                     emplacements alignés et arrondis à `2^L` ; rangées par hauteur décroissante ;
                     largeur puissance de deux, côtés ≤ 4096 (R-570) ; débordement → page suivante ;
                  3. **Mipmaps par tuile** (`MipmapGenerator` sur la tuile margée, comme la texture
                     seule — Forge ne remplit pas un niveau au-delà de `log2(min(l, h))`, que la marge
                     garantit), recopiés niveau par niveau dans la page ;
                  4. **UV** : transformation affine par texture, appliquée à l'émission des sommets ;
                     l'asset sert une région (texture liée + transformation), un type de rendu par page ;
                  5. échec de composition → textures du groupe préparées une à une, signalé ;
                  6. tests sans Minecraft (disposition, marges, UV, éligibilité, cycle de vie, composition
                     sur tableaux) ; en jeu : panneau (trois tuiles linéaires) et un modèle d'essai
                     d'atlas fait dans Blender (petites textures contrastées, bords à 0 et 1).
                  Hors portée, nommé : atlas commun à plusieurs assets ; `max_texture_size`, option de
                  compilation que Java ne reçoit pas (format).
                  Fait : `AtlasLayout`, `AtlasTexels`, `AtlasPlan`, `TextureRegion` ; pipeline en deux
                  temps (décodage, puis texture seule ou page) ; « Précisions de T-c ». Java 393 verts
                  (28 nouveaux, douze mutations attrapées). Vérifié en jeu le 2026-10-03 par Killian,
                  contenu `axion:test/materiaux/atlas` (source `atlas_t-c.blend`) : pages de 256×96 et
                  256×160, texture répétée restée seule, panneau en page de 1024×288 ; bords intacts.
                  CI verte : run 37147205392 du commit 48c5f13, neuf jobs, quatre plateformes.
            - Hors portée, nommé : usage natif (C-60/C-63), multijoueur, `.mtl` et buffers
              externes en jeu (C-20/C-21), décalques (C-69), `TINTABLE`, usure (C-47),
              `max_texture_size`, `uv1`.

## Jalons suivants

Fiches complètes : `sed -n '7178,7317p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md`

- [x] **M1** Assets, noyau déterministe, jobs — C-12, C-15, C-16, C-20, C-21, C-22, C-24, C-71
      · les huit composants sont faits ; l'acceptance attend le fuzzing et la CI
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
- [ ] **Plateformes non vérifiées.** L'acceptance de M0 demande un boot
      `READY` « sur les plateformes ». Seule `windows-x86_64` a été lancée : ni
      Linux ni macOS n'ont de natif ici, la compilation croisée demandant une
      chaîne complète par cible. Le build refuse désormais de publier un JAR
      qui ne les porte pas toutes (R-2341), mais **la preuve d'exécution
      manque** tant que la CI des trois plateformes n'existe pas (PARTIE 34.3).
      À monter avant toute publication, et de toute façon avant M1 : les
      vecteurs d'or déterministes de C-16 s'y vérifient sur les trois.
- [x] **Cibles Rust dans `rust-toolchain.toml`.** Retirées. Rien ne compile en
      croisé — `natives.gradle` ne construit que la cible hôte — et la CI donne
      une machine par plateforme : les cinq cibles faisaient télécharger cinq
      bibliothèques standard partout pour n'en utiliser qu'une. La matrice reste
      déclarée là où elle sert, dans `VALIDATION_MATRIX`.
- [ ] **Campagne de fuzzing nocturne non programmée.** 34.3 prévoit une heure
      par cible chaque nuit. Quatre cibles font 240 minutes par nuit, soit
      7 200 par mois sur un dépôt privé qui en compte 2 000 : un `cron`
      épuiserait le quota en quatre nuits. Le workflow se déclenche donc à la
      main, et `fuzz-short` — dix minutes par cible — tourne sur les PR qui
      touchent la chaîne d'assets. À reprendre si le dépôt passe public ou
      change de plan.
- [ ] **glibc du binaire Linux.** 34.2 annonce « Linux x86_64 (glibc ≥ 2.28) ».
      La CI construit sur `ubuntu-22.04`, dont la glibc est 2.35 : le `.so`
      produit exige donc 2.35, pas 2.28, et l'annonce est en avance sur le fait.
      Se règle par un conteneur `manylinux_2_28` ou par `cargo-zigbuild`, à
      monter avant la première release.
- [x] **glam et déterminisme.** Réglé par
      [ADR-104](../docs/decisions/ADR-104.md) : aucun type de `glam` n'entre
      dans le noyau déterministe, parce que R-510 n'y autorise que des
      opérations scalaires. `ax-det` ne dépend que de `xxhash-rust`. Un chemin
      déterministe qui manipule un vecteur le décompose ; `glam` reste employé
      partout ailleurs, R-460 inchangé.
- [ ] **Le handshake ne reconstruira jamais entre deux plateformes.** À trancher
      en M4, quand le handshake de la PARTIE 21.3 sera écrit.

      5.12bis définit `det_profile` comme le hash de **(triplet cible,
      `DET_KERNEL_VERSION`, jeu d'instructions compilé, drapeaux flottants)**, et
      sa règle 1 n'accorde `RECONSTRUCT` qu'à deux empreintes **égales**. Le
      triplet en faisant partie, deux plateformes différentes ne s'accordent
      jamais : un client Windows sur un serveur Linux — le déploiement le plus
      courant de Minecraft — serait toujours en `SNAPSHOT`. `RECONSTRUCT` ne
      servirait qu'en solo, en LAN homogène, ou entre machines de même triplet.

      L'implémentation actuelle est **fidèle au cahier des charges** et ne doit
      pas être modifiée sans arbitrage : c'est une règle conservatrice, et
      R-514 garantit que `SNAPSHOT` ne retire aucune fonctionnalité.

      Mais la première exécution de la matrice a produit la mesure qui rend la
      question légitime : les mêmes vecteurs d'or passent bit pour bit sur
      `x86_64-windows-msvc`, `x86_64-linux-gnu` et `aarch64-macos-none`
      (voir [la matrice validée](../docs/spec/MATRICE-DETERMINISTE.md)). Ces
      configurations **s'accordent entre elles**, ce que leurs empreintes
      distinctes ne disent pas. Une relaxation — `RECONSTRUCT` dès que les deux
      extrémités sont dans la matrice **et** partagent `DET_KERNEL_VERSION` —
      serait couverte par les vecteurs d'or, mais c'est une déviation du CDC
      gelé : elle demande un ADR et la décision de l'utilisateur, pas une
      initiative.
- [x] **Acceptance de M1 : fuzzing une heure.** La fiche M1 demande « fuzzing
      1 h sans incident » sur la chaîne d'assets. Rien ne le lance aujourd'hui :
      ni cible `cargo-fuzz`, ni corpus. À monter avant de prononcer la
      Definition of Done de M1.

      **L'outillage existe et a servi** — cibles, corpus, workflow, et quatre
      défauts trouvés. Ce qui reste, pour prononcer la Definition of Done :

      - `a3d_reader`, `obj`, `stl` : **campagne d'une heure passée sans
        incident** le 2026-09-12
        ([run 34648731184](https://github.com/JLSkyzer/AXIONENGINE/actions/runs/34648731184)).
        861 281 634 exécutions sur `a3d_reader`, 95 909 008 sur `stl`,
        4 703 443 sur `obj` — ce dernier est plus lent par nature, chaque
        exécution analysant du texte, un `.mtl` et une triangulation ;
      - le corpus a grossi de 235, 3 476 et 23 635 entrées respectivement. Elles
        ne sont **pas versées telles quelles** : `cargo fuzz cmin` les réduirait
        à ce qui apporte de la couverture, et c'est ce résultat-là qu'on
        verserait. Les artefacts du run les portent 14 jours ;
      - **`gltf` : chemin durci, campagne d'une heure à mener.** AXION ne
        confie plus le jugement du document au validateur de `gltf-json`, qui
        indexait `root.accessors[…]` sans borne. Le document est désérialisé
        sans validation, et `import/gltf_refs.rs` vérifie **toutes** les
        références avant d'en déréférencer une seule — ce n'était pas
        optionnel : les accesseurs du crate déréférencent par
        `.nth(index).unwrap()`, donc s'en remettre à
        `from_slice_without_validation` seul aurait déplacé la panique au lieu
        de la retirer.

        Le balayage est exhaustif sur le document, et non limité à ce que
        l'import lit aujourd'hui : accessors, bufferViews, images, textures,
        materials, meshes et leurs morph targets, nodes, skins, scenes,
        animations. Se limiter à ce qu'on déréférence obligerait à revenir à
        chaque champ nouvellement lu.

        Le gain n'est pas que l'absence de panique : le refus **désigne le champ
        fautif** — « meshes[0].primitives[0].attributes désigne l'accesseur
        n°99, il n'y en a que 2 » — là où une panique retenue ne disait que
        « l'analyseur a paniqué ».

      T-680..T-682 demandent dix millions d'exécutions par cible, ce qui relève
      du jalon final et non de M1.
- [x] **Vecteurs d'or sur une seule plateforme.** Réglé : la CI les rejoue sur
      quatre des cinq configurations de la matrice, et le résultat est archivé
      dans [`docs/spec/MATRICE-DETERMINISTE.md`](../docs/spec/MATRICE-DETERMINISTE.md)
      comme R-516 l'exige.
- [ ] **`aarch64-unknown-linux-gnu` jamais rejouée.** Dernière case vide de la
      matrice. GitHub ne fournit pas de runner ARM Linux sur le plan de ce
      dépôt ; 34.2 la donne « best effort, non bloquant », et son `validated`
      reste à `false`, donc elle bascule en `SNAPSHOT`. À rejouer le jour où un
      runner ARM est disponible — machine personnelle, runner auto-hébergé, ou
      changement de plan.
- [ ] **`unsafe` hors de `ax-ffi` et `ax-mem` (R-2120).** `ax-core/src/buffers.rs`
      lève `unsafe_code` sur deux blocs (`as_mut_slice`, `as_slice`), alors que R-2120
      réserve `unsafe` à la frontière JNI et aux arènes. Vu le 2026-10-05 en logeant
      l'horloge CPU de thread dans `ax-ffi` ; aucun ADR ne le justifie. À trancher :
      déplacer ces vues dans `ax-ffi` ou `ax-mem`, ou consigner la dérogation.
- [ ] **Miri absent de la CI (R-2122).** Les crates qui contiennent du `unsafe`
      (`ax-ffi`, `ax-mem`, et `ax-core` ci-dessus) doivent passer leurs tests
      unitaires sous Miri ; aucun job ne le fait. Les tests qui appellent une
      fonction étrangère, comme l'horloge CPU de thread, seront à exclure sous Miri.
- [ ] **Métriques de budget : deux lectures d'une même valeur.** `BudgetMetrics`
      documente la consommation comme une jauge du dernier tick, mais
      `publish_metrics` y écrit le temps **cumulé** du pool de jobs pour les budgets
      de durée — sauf la simulation, mesurée pas à pas depuis le 2026-10-05. Et un
      budget nul vaut « sans plafond » pour `BudgetMetrics::report`, « intenable »
      pour le gouverneur FM-21 ; la configuration accepte 0 pour tous les budgets.
      À trancher avec le gouverneur multi-budgets (C-77).
