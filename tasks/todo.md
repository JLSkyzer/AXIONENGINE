# AXION ENGINE — plan de travail

Source de vérité : `cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md` (8270 lignes,
FINAL / FROZEN). Ne jamais le lire en entier : passer par
[l'index](../docs/spec/INDEX.md) et lire la plage de lignes utile.

Règles de travail : [docs/AGENT.md](../docs/AGENT.md).
Leçons apprises : [tasks/lessons.md](lessons.md) — à relire à chaque session.

---

## État courant

**Jalon M0 : terminé, Definition of Done prononcée le 2026-09-10.**
**Jalon en cours : M1 — Assets, noyau déterministe, jobs. C-12, C-15, C-21, C-22 et C-24 faits.**

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
- [ ] **C-16** Noyau déterministe. **`[EFFORT MAX]`**, prévenir avant de
      commencer. La dette « glam et déterminisme » se règle d'abord.
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
- [~] **C-20 — Orchestrateur.** Le natif est prêt et appelable ; **la partie
      Java reste**.
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

      **Ce qui reste, entièrement côté Java :** l'énumération des ressources à
      `AddReloadListenerEvent`, la clé `sha256(contenu || options ||
      COMPILER_VERSION)` — `MessageDigest` est là, aucune dépendance Rust n'est
      nécessaire —, la machine à états SM-01, le sondage à chaque tick sous
      `budgets.asset_ns_per_tick`, la barrière de démarrage de R-521, l'asset de
      secours `axion:builtin/missing` de R-522, et la recompilation des seuls
      assets dont la clé a changé (R-520).

      **Dette** : R-562 veut que `COMPILER_VERSION` soit incrémentée à toute
      modification de C-21, C-22, C-23 ou C-28 qui change la sortie,
      **vérifié en CI**. La constante existe ; le contrôle non.
- [ ] **C-25** Cache d'assets — clés, index, éviction LRU, hors du monde.
- [ ] **C-23** Optimizer — tangentes, cache de sommets, décomposition convexe (M2).
- [ ] **C-71** Commandes — `/axion status` existe déjà, ses autres branches
      arrivent avec les composants qu'elles pilotent.

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
- [ ] **Plateformes non vérifiées.** L'acceptance de M0 demande un boot
      `READY` « sur les plateformes ». Seule `windows-x86_64` a été lancée : ni
      Linux ni macOS n'ont de natif ici, la compilation croisée demandant une
      chaîne complète par cible. Le build refuse désormais de publier un JAR
      qui ne les porte pas toutes (R-2341), mais **la preuve d'exécution
      manque** tant que la CI des trois plateformes n'existe pas (PARTIE 34.3).
      À monter avant toute publication, et de toute façon avant M1 : les
      vecteurs d'or déterministes de C-16 s'y vérifient sur les trois.
- [ ] **Cibles Rust dans `rust-toolchain.toml`.** Les cinq cibles de la matrice
      déterministe y sont listées, ce qui fait télécharger cinq bibliothèques
      standard sur chaque poste. Si c'est trop lourd, ne garder que la cible
      hôte et laisser la CI ajouter les autres.
- [ ] **glam et déterminisme.** `glam` sélectionne des chemins SIMD selon la
      cible, et `Vec3A` est explicitement un type aligné SIMD. C-16 exige des
      résultats bit-identiques entre client et serveur sur la matrice de
      validation déterministe, sans contraction FMA ni réassociation. **Avant
      d'écrire le noyau déterministe (M1), vérifier quels types et quelles
      opérations de `glam` sont utilisables dedans**, et documenter le verdict :
      il est probable que le noyau doive s'en tenir à `f32` scalaire, `glam`
      restant réservé au reste du moteur.
