# Leçons

Une ligne par piège rencontré, avec la règle qui évite de le revivre.
Format : `[date] | ce qui a mal tourné | règle`.

À relire au démarrage de chaque session, avant de toucher au code.

---

## Build et outillage

**[2026-09-09] | `processResources` a échoué sur `mods.toml` avec
`Unexpected input: '(' @ line 1` | Ne jamais écrire `${` dans un fichier traité
par `expand` de Gradle, commentaires compris.**

La tâche `processResources` passe `META-INF/mods.toml` et `pack.mcmeta` au
`SimpleTemplateEngine` de Groovy, qui évalue **toute** séquence `${...}` comme
du code, y compris dans une ligne de commentaire. Le commentaire
« les valeurs `${...}` sont développées au build » suffisait à casser le build.
Écrire « les valeurs entre accolades » ou échapper en `\${`.

**[2026-09-09] | `cargo metadata` a échoué avec `os error 123` sur
`crates\*\Cargo.toml` | Un workspace Cargo dont le glob de membres ne
correspond à aucun crate est invalide sous Windows.**

`members = ["crates/*"]` sur un dossier `crates/` vide fait échouer toute
commande cargo. Tant qu'aucun crate n'existe, écrire `members = []`, puis
repasser au glob dès le premier crate créé — le glob les reprend alors tous
automatiquement.

**[2026-09-09] | `rtk git commit -F -` a avorté sur « empty commit message » |
Ne pas passer de message de commit par l'entrée standard à travers `rtk`.**

Le wrapper `rtk` ne relaie pas stdin jusqu'à git. Écrire le message dans un
fichier temporaire du répertoire de travail temporaire de session, puis
`rtk git commit -F <fichier>`.

**[2026-09-09] | Un heredoc bash contenant beaucoup d'apostrophes françaises a
été coupé par `unexpected EOF while looking for matching quote` | Écrire les
fichiers de texte français avec l'outil d'écriture, pas avec un heredoc.**

Les apostrophes de « l'index », « n'existe », « d'allocations » finissent par
casser le quoting du wrapper de shell sur les gros contenus. Les heredocs
restent adaptés au code et aux fichiers courts sans apostrophes.

---

## Méthode

**[2026-09-09] | `axion.mixins.json` a été écrit avec
`"compatibilityLevel": "JAVA_17"`, alors que la base de connaissances documentait
déjà que Forge 47 plafonne à `JAVA_16` | Consulter `D:\BDC` **avant** d'écrire un
fichier de configuration Forge, pas après.**

Sur Forge 47, la version de Mixin embarquée plafonne à `JAVA_13` et son service
annonce `JAVA_16` ; déclarer `JAVA_17` produit un avertissement au lancement,
même si le mod est bien compilé pour Java 17. La version du langage et le niveau
de compatibilité de Mixin sont deux échelles indépendantes. Le fait était déjà
consigné dans `D:\BDC` — la relecture du coffre aurait évité l'aller-retour.
Voir `Minecraft/compatibilityLevel des mixins sur Forge 47`.

---

## Code

**[2026-09-09] | Le profil release portait `panic = "abort"`, que R-312 interdit
explicitement | Ne pas poser d'option de profil de compilation « par réflexe
d'optimisation » sans la confronter au cahier des charges.**

`abort` avait été mis en M0.1 avec `lto` et `codegen-units = 1`, comme un
réglage de performance banal. Mais chaque point d'entrée FFI doit être
enveloppé dans `catch_unwind` pour qu'aucune panic ne traverse la frontière
(INV-05) : avec `abort`, le processus meurt avant toute capture, et le jeu
crasherait au lieu de basculer en `DISABLED`. Le réglage annulait donc une
garantie centrale, sans rien casser de visible — aucun test ne l'aurait montré
avant le premier vrai panic en production.

**[2026-09-09] | La détection de plateforme classait macOS comme Windows :
`"darwin".contains("win")` est vrai | Ne jamais identifier un système par une
sous-chaîne courte sans vérifier l'ordre des tests.**

`os.name` vaut `Darwin` sur certaines JVM macOS. Un test `contains("win")` placé
avant celui de macOS l'attrape, et la plateforme était refusée — aucun binaire
Windows ARM n'existant. Corrigé en testant macOS d'abord et Windows par
`startsWith("windows")`.

Le test l'a attrapé parce que la table des cas contenait `Darwin` en plus de
`Mac OS X` : **une table de plateformes ne vaut que par les valeurs réellement
rapportées par les JVM**, pas par celles qu'on suppose.

**[2026-09-10] | Un test d'intégration importait `ax_ffi::` et ne compilait pas |
Le crate `ax-ffi` produit une bibliothèque nommée `axion_native` : c'est ce nom
qu'on importe, pas celui du paquet.**

`[lib] name = "axion_native"` est imposé par R-420, qui exige un nom de
bibliothèque propre au projet. Le nom du paquet Cargo et celui de la
bibliothèque sont alors deux choses distinctes, et c'est le second que voient
`use` et le linker.

**[2026-09-10] | Un fichier Java généré est sorti avec neuf espaces
d'indentation parasites sur chaque ligne | Dans un générateur de code, assembler
des lignes plutôt qu'un littéral à continuations `\`.**

Les continuations de ligne d'un littéral Rust recopient l'indentation du source
dans la chaîne produite. Assembler un `Vec<String>` puis le joindre n'a pas ce
défaut, et se relit mieux. Le fichier compilait quand même : seule l'inspection
visuelle l'a montré — d'où l'intérêt de relire une sortie générée au moins une
fois.

**[2026-09-10] | Les codes d'erreur `E-1005` et `E-1006` avaient été inventés en
M0.2 ; T-023 les a trouvés dès son écriture | Ne jamais attribuer un identifiant
normatif que le cahier des charges ne définit pas.**

L'obligation 4.8 veut que tout nouveau code figure à l'ANNEXE A.1. Le cahier des
charges étant gelé, cette voie est fermée : un code inventé produit un message
qu'aucun utilisateur ne peut rechercher et qu'aucun support ne sait expliquer.
La conduite juste est de réutiliser un code existant s'il est sémantiquement
exact, sinon de n'en attribuer aucun — et d'écrire un ADR
(`docs/decisions/ADR-102.md`).

La leçon vaut au-delà des codes d'erreur : `R-`, `T-`, `INV-` et les autres
familles se citent, elles ne s'inventent pas.

**[2026-09-10] | `validateJar` signalait une empreinte manquante alors que le
fichier `.sha256` était bien dans le JAR | En Groovy, `List<String>.contains(GString)`
est toujours faux.**

`entries.contains("${lib}.sha256")` interpole en `GString`, dont `equals` avec une
`String` renvoie faux. Le test échouait donc sur un artefact correct. Concaténer
— `lib + '.sha256'` — ou appeler `.toString()`. Le piège vaut pour toute
comparaison, pas seulement `contains`.

**[2026-09-10] | Un test JUnit a échoué sur « Failed to delete temp directory »
après avoir chargé la bibliothèque native | Une bibliothèque chargée par
`System.load` reste verrouillée par la JVM jusqu'à sa fin.**

Sous Windows, son fichier ne peut plus être supprimé, et `@TempDir` échoue en
tentant de vider le répertoire. Utiliser `@TempDir(cleanup = CleanupMode.NEVER)`
pour un test qui charge réellement, et un binder simulé partout ailleurs.

C'est aussi la raison d'être du chemin versionné par empreinte
(`<racine>/axion/native/<sha256>/`) : une mise à jour n'a jamais à écraser un
fichier que la JVM tient peut-être encore ouvert.

**[2026-09-10] | Deux constructions du même code produisaient deux JAR
différents | Le manifeste portait un `Implementation-Timestamp`, hérité du
modèle MDK.**

R-2342 impose un build reproductible. Une date d'assemblage suffit à rendre deux
artefacts du même code différents, donc à priver toute empreinte publiée de sa
valeur. Retiré, avec `preserveFileTimestamps = false` et
`reproducibleFileOrder = true` sur les archives. La vérification tient en deux
constructions et une comparaison de SHA-256.

**[2026-09-10] | Le mod ne se chargeait plus : `NoSuchMethodException:
AxionForgeEntrypoint.<init>()` | Sur Forge 47, le bus d'événements ne s'injecte
pas dans le constructeur du mod.**

`FMLJavaModLoadingContext.get().getModEventBus()` est marqué déprécié, et j'avais
« corrigé » l'avertissement en déclarant un constructeur prenant `IEventBus` —
forme valable sur des versions ultérieures, absente de Forge 47. Le mod
échouait au chargement, ce qu'aucun test n'aurait montré : seul un lancement
réel le révèle.

**Un avertissement de dépréciation n'autorise pas à employer une API absente de
la version visée.**

**[2026-09-10] | Gradle a refusé le build : deux tâches se disputaient
`build/natives` | ForgeGradle y extrait déjà les natifs de LWJGL avec sa propre
tâche `extractNatives`.**

Le répertoire d'AXION s'appelle désormais `build/axion-natives`. Gradle a
raison de refuser : sans dépendance déclarée, l'ordre des deux tâches n'est pas
garanti, et le résultat aurait pu être correct un jour sur deux.

**[2026-09-10] | Le premier démarrage réel du serveur a signalé `E-2003` à
l'arrêt | Le bootstrap acquérait un tampon de contrôle sans jamais le rendre.**

Le bilan d'allocations de R-322, écrit une heure plus tôt, a trouvé son premier
défaut dès sa première exécution en conditions réelles. Le tampon est maintenant
relâché après vérification, et un test de non-régression le couvre. Le protocole
veut de toute façon qu'on acquière à chaque tick plutôt que de conserver une vue.

---

## Structure du projet

**[2026-09-09] | Le MDK Forge a été généré avec `mod_id=axionengine` et
`fr.eriniumgroup.axionengine`, alors que le CDC impose `axion` et `dev.axion` |
Vérifier l'identité du mod contre le CDC avant d'écrire la moindre classe.**

Le `mod_id` entre dans les espaces de noms `assets/`, `data/`, les
`ResourceLocation` et les clés NBT persistées : le changer après coup casse les
mondes existants. Corrigé avant tout code — voir `docs/decisions/ADR-100.md`
pour la tension R-401 / PARTIE 33 sur le point d'entrée.

## 2026-09-10 | Un événement d'arrêt ne dit pas toujours que le processus s'arrête

`ServerStoppingEvent` était traité comme la fin du jeu. Sur un client, il n'est
que la fin d'un monde solo : le contexte natif était fermé au retour au menu
principal, et AXION restait mort pour toute la session, sans erreur ni message.

**Règle.** Sur un client, tout ce qui touche au serveur décrit une *session*,
pas le processus. La fin du processus, c'est `GameShuttingDownEvent`, émis des
deux côtés — et Forge l'émet **après** `ServerStoppingEvent` sur un serveur
dédié, l'inverse de ce que laisse croire la lecture du bytecode de
`DedicatedServer.stopServer`. Vérifier l'ordre réel dans un log, pas le déduire.

**Corollaire.** Les états d'un cycle de vie qu'aucun événement n'atteint sont
des états morts : `RUNNING_CLIENT` existait depuis M0.1 sans que rien n'y mène.
Chercher, pour chaque état déclaré, l'événement qui y fait entrer.

## 2026-09-10 | Une structure qui traverse la frontière n'est pas un registre

La structure `Budgets` de DM-18 avait l'air d'être la liste des budgets. Elle en
porte dix-sept sur vingt : c'est le sous-ensemble qui traverse la frontière avec
le profil de qualité, pas le registre. Trois budgets seraient restés hors audit.

**Règle.** Avant de prendre une structure du cahier des charges pour une liste
de référence, chercher la **table** qui l'énumère — ici la PARTIE 25.2. Une
structure sert un transport ; une table sert un inventaire. Écrire l'audit dans
les deux sens le montre tout de suite : « tout élément de la liste est dans le
code » ne suffit pas, il faut aussi « tout élément du code est dans la liste ».

**Corollaire.** Deux listes qu'on écrit soi-même ne se valident pas l'une
l'autre. Le test qui compte est celui qui compare le code à la source figée.
