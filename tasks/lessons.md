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

## 2026-09-11 | Une plage écrite dans une annexe n'est pas deux valeurs

T-023 vérifie que tout code `E-xxxx` cité dans le code figure à l'ANNEXE A.1.
Il en extrayait les codes par expression régulière — et lisait
`E-3020..E-3060` comme deux codes isolés. Les trente-neuf codes intermédiaires
passaient pour non documentés, alors que la ligne les déclare précisément
ensemble.

**Règle.** Un test qui lit une source figée doit lire sa **notation**, pas
seulement ses jetons. Une plage, un « et suivants », un renvoi : chacun demande
d'être compris, sinon le test refuse ce que la source autorise — et l'on finit
par contourner le test au lieu de le corriger.

## 2026-09-11 | Un événement de plateforme n'arrive pas quand on le croit

La découverte d'assets exigeait que le cycle de vie soit « en cours ». Forge
émet `AddReloadListenerEvent` AVANT `ServerStartingEvent` : la condition n'était
jamais vraie, et la découverte passait à côté à chaque démarrage sans qu'aucune
erreur ne le dise.

**Règle.** Avant de conditionner un traitement à une phase, vérifier dans un log
réel l'ordre des événements qui la produisent. L'ordre supposé est faux une fois
sur deux, et l'erreur est silencieuse : il ne se passe rien, ce qui ressemble à
« il n'y avait rien à faire ».

**Corollaire.** Un traitement qui réussit doit le dire, pas seulement échouer
bruyamment. Un silence ne distingue pas un travail réussi d'un travail qui n'a
pas eu lieu — c'est en ajoutant une ligne de compte rendu que le défaut est
apparu.

---

## 2026-09-11 | Un garde-fou à l'entrée ne garantit pas la sortie

`quantize_i8` et `dequantize_i8` refusaient tous les deux un pas non fini, nul ou
négatif, et le module promettait en toutes lettres de ne jamais rendre de valeur
non finie. Les deux mentaient.

`v / step` déborde quand le pas est subnormal : le quotient devient un infini,
que `clamp` ramène à sa borne **basse** — une valeur franchement positive
quantifiait donc en pas franchement négatif, c'est-à-dire en son opposé.
`dequantize_i8` rendait un infini dès que `128 × pas` n'entrait plus dans un
`f32`. Aucun des deux n'était atteignable par une entrée plausible, et aucun test
écrit à partir de la formule ne les voyait.

**Règle.** Quand une fonction promet quelque chose sur sa **sortie**, la vérifier
sur les extrêmes du **type**, pas sur ceux de l'usage attendu : subnormaux,
`f32::MAX`, `i8::MIN`, infinis, `NaN`, et les valeurs à un ULP d'une frontière.
Les deux défauts ci-dessus ont été trouvés en dressant cette liste pour les
vecteurs d'or — avant d'exécuter quoi que ce soit.

**Corollaire.** `i8::MIN` vaut `-128` quand la quantification n'émet que `-127`.
Un garde-fou en sortie se raisonne à partir de ce que le type admet, car la
valeur peut venir d'un fichier plutôt que du moteur.

---

## 2026-09-11 | Un fichier d'or ne tire pas ses entrées de ce qu'il fige

Le générateur des 10 000 vecteurs d'or de C-16 aurait pu tirer ses entrées avec
`DetRng`. Il aurait alors suffi de modifier `DetRng` pour que **toutes** les
lignes du fichier changent à la régénération suivante, et la différence entre
deux versions de l'artefact — la seule chose qui dise ce qui a bougé — serait
devenue illisible.

**Règle.** Les entrées d'un fichier de référence viennent d'une source
indépendante de ce qu'il fige : ici un xorshift écrit en clair dans le
générateur. La différence entre deux versions est alors exactement la liste des
sorties qui ont changé.

**Corollaire.** Le fichier porte la version dans son nom
(`kernel-v1.txt`). Incrémenter `DET_KERNEL_VERSION` sans régénérer laisse le
rejeu sans fichier, donc en échec : un noyau qui change ne peut pas rester sans
vecteurs à jour.

---

## 2026-09-11 | `write_text` de Python réécrit les fins de ligne sous Windows

Trois fichiers sont passés en CRLF sans que rien ne le signale, parce que
`pathlib.Path.write_text` applique la traduction de fins de ligne de la
plateforme. Le dépôt a `core.autocrlf=true`, donc `git status` ne montrait rien
d'anormal — la conversion se voyait seulement en lisant les octets.

**Règle.** Pour modifier un fichier du dépôt en Python, lire et écrire en
**binaire** (`read_bytes` / `write_bytes`) et gérer les fins de ligne
explicitement : les normaliser en saut simple pour éditer, restituer le style
d'origine à l'écriture. `tasks/todo.md` et `tasks/lessons.md` sont en CRLF — le
premier avec BOM —, la plupart des autres fichiers en LF.

---

## 2026-09-11 | `gradlew` n'avait pas son bit d'exécution

Le dépôt a été initialisé sous Windows, où le bit d'exécution n'existe pas. Git
a donc enregistré `gradlew` en mode `100644`. Tant que le build ne tourne que
sur cette machine, rien ne le dit : `gradlew.bat` prend le relais. Le premier
runner Linux, lui, aurait répondu `./gradlew: Permission denied` — et l'erreur
n'aurait désigné ni le dépôt, ni Windows, ni le mode du fichier.

**Règle.** Avant d'ajouter un job de CI qui exécute un script du dépôt, vérifier
son mode : `git ls-files -s <script>` doit rendre `100755`. Se corrige par
`git update-index --chmod=+x <script>`, et le mode voyage alors dans le commit.

**Corollaire.** La même question se pose pour tout ce qu'un dépôt né sous
Windows exécute ailleurs : scripts d'outillage, crochets, entrypoints.

---

## 2026-09-11 | Un autotest qui se cherche dans sa propre définition ne prouve rien

`tools/ci/lint_no_fiction.py` s'exclut du balayage — il contient forcément les
motifs qu'il traque. Pour boucher ce trou, la première version vérifiait que
chaque expression régulière se reconnaissait dans le fichier. C'était creux : le
fichier contient le texte de l'expression, donc une expression **mal écrite** s'y
reconnaissait tout aussi bien. La mutation l'a montré — `\bFIXMEZZZ\b` passait au
vert.

**Règle.** Un autotest se juge sur des exemples écrits **indépendamment** de ce
qu'ils testent, et dans les deux sens : une liste de cas qui doivent être
attrapés, une liste de cas légitimes qui ne doivent pas l'être. La seconde n'est
pas un luxe : un motif trop large est pire qu'absent, parce qu'on finit par le
contourner.

**Corollaire.** C'est la liste des innocents qui a fait apparaître que
`\bplaceholder\b` ne reconnaissait pas `PLACEHOLDER_ROUGE` — la limite de mot
échoue devant un tiret bas. Le motif était trop étroit, et seule une mesure
extérieure pouvait le dire.

---

## 2026-09-11 | Un « ok » de `rtk git push` ne prouve pas que le commit est parti

Un second commit a été poussé, la sortie a affiché `ok`, et le dépôt distant est
resté sur le premier. Rien ne l'aurait dit : c'est la CI, restée sur l'ancien
commit, qui a trahi l'affaire — le correctif poussé n'y changeait rien puisqu'il
n'y était pas.

`rtk` compacte la sortie des commandes, et la compaction ne distingue pas
toujours ce qui a réussi de ce qui n'a rien fait. Le risque est propre à la règle
qu'on suit ici : « pousser dans la foulée de chaque commit » n'a de valeur que si
la poussée a eu lieu.

**Règle.** Après un push, constater l'état distant plutôt que lire la sortie :

```bash
git ls-remote origin <branche>
```

Le SHA rendu doit être celui de `git rev-parse HEAD`. En cas de doute, repasser
par `git push` sans `rtk` : sa sortie nomme explicitement l'avance de référence
(`d6e06b0..9fbd349`).

---

## 2026-09-11 | Un analyseur tiers panique sur ce qu'il ne modélise pas

La première campagne de fuzzing, dix minutes par cible, a trouvé deux paniques.
Aucune dans notre code, les deux dans des dépendances :

- `gltf` 1.4.1 : `read_indices()` fait `unreachable!()` dès que le
  `componentType` de l'accesseur d'indices n'est pas un entier non signé ;
- `tobj` 4.0.5 : le contrôle de bornes s'écrit `vn * 3 + 2 >= normal.len()`, et
  calcule le produit **avant** de comparer — un indice négatif hors bornes
  déborde.

Les deux sont atteignables depuis un pack de contenu, c'est-à-dire depuis un
fichier que le projet ne contrôle pas.

**Règle.** Ce qu'une dépendance accepte en entrée n'est pas ce qu'elle
**modélise**. Avant de lui confier une donnée tierce, vérifier soi-même ce que
le format autorise — et se souvenir qu'un `Result` dans sa signature ne promet
rien sur les chemins où elle a écrit `unreachable!()`.

**Corollaire.** Les deux vérifications ajoutées sont **conformes au format**,
pas des contournements : glTF 2.0 n'admet que des indices non signés, et un
indice OBJ hors des comptes déclarés n'est valide sous aucune lecture. Une
vérification qui se justifie par la spécification survit à la mise à jour de la
dépendance ; un contournement qui se justifie par le bug d'une version, non.

**Corollaire.** Une panique contenue reste un défaut. Le pool de jobs et la
frontière FFI captent celles-ci, donc rien ne tombe — mais l'asset remonte une
panique opaque au lieu d'une erreur diagnosticable, et R-903 exige la tolérance
zéro. « Ça ne casse rien » n'est pas « c'est correct ».

---

## 2026-09-11 | Un fuzzer part des graines qu'on lui donne, et y revient

Les deux entrées fautives trouvées ci-dessus sont des **mutations de graines du
corpus** : `triangle.gltf` dont un `5123` est devenu `5122`, et
`mtllib-indices-negatifs.obj` dont un `-1` est devenu `-21`. Dix minutes par
cible ont suffi.

**Règle.** Un corpus de graines valides n'est pas une commodité, c'est ce qui
détermine la profondeur de la campagne. Partir de rien ferait passer l'essentiel
du temps à réinventer un en-tête que le format rejette.

**Corollaire.** Les graines gagnent à être **variées dans leurs pathologies**,
pas seulement valides. `mtllib-indices-negatifs.obj` a été écrit pour couvrir
des indices comptés depuis la fin — une forme légale et rarement testée. C'est
précisément celle que le fuzzer a poussée jusqu'au débordement.

**Corollaire.** Une entrée fautive devient une graine de régression **et** un
test nommé. Le corpus constate qu'on ne panique plus ; le test dit qu'on refuse
pour la bonne raison, et c'est lui qui échouera si la vérification est un jour
remplacée par un `catch_unwind`.

---

## 2026-09-12 | Couvrir une famille de défauts ne dit rien des autres

Le durcissement du chemin glTF a demandé **cinq** passages. À chaque fois, la
campagne butait sur une nature de défaut que le passage précédent n'avait pas
rendue visible :

1. indices hors bornes — `"POSITION": 99` ;
2. énumérations inconnues — `"mode": 99` ;
3. formes d'attribut — `POSITION` déclaré en `SCALAR` ;
4. champs obligatoires — une image sans `uri` ni `bufferView` ;
5. contraintes numériques — `"count": 0`, `byteStride` aberrant.

Après le premier passage, tout indiquait que c'était réglé : la campagne allait
plus loin, la couverture montait, et le raisonnement ne suggérait rien d'autre.
Elle butait dix minutes plus tard.

**Règle.** Devant un analyseur qui fait confiance à son entrée, ne jamais
conclure d'un correctif qu'il ferme le sujet. Relancer jusqu'à ce qu'une
campagne **longue** passe — ici vingt minutes, puis une heure. La couverture qui
monte à chaque tour (3069, 4282, 5414, 6168) est le signe qu'on atteint du code
neuf, pas qu'on a fini.

**Corollaire.** Chaque vérification ajoutée se justifie par la **spécification**,
jamais par le bug d'une version : glTF fixe les valeurs de ses énumérations, la
forme de chaque sémantique, les champs obligatoires d'une image, les bornes de
`count` et `byteStride`. Une vérification ainsi fondée survit à la mise à jour
de la dépendance ; un contournement, non.

**Corollaire.** Écrire la réciproque à chaque fois. Refuser en bloc aurait
rejeté des fichiers légaux — un attribut personnalisé `_BATCHID`, un
`byteStride` de 12, les trois types d'indices que glTF admet.

---

## 2026-09-12 | Un défaut qui ne plante pas en release est le plus dangereux

Deux des cinq familles ci-dessus **ne paniquent pas** dans le binaire livré :

- la forme d'attribut est gardée par un `debug_assert_eq!`, absent en release :
  le lecteur poursuit et rend une géométrie fausse, sans rien dire ;
- `count: 0` fait calculer `stride * (count - 1)`, qui **boucle** l'entier en
  release au lieu de déborder : l'accesseur rend silencieusement du vide.

Le fuzzer ne les a vues que parce que `cargo-fuzz` construit avec les assertions
de débogage et les contrôles de débordement actifs.

**Règle.** Le fuzzing vaut même quand rien ne « plante » en production. Un
`debug_assert!` ou un débordement d'entier signale une hypothèse violée ; qu'elle
soit silencieuse en release la rend pire, pas bénigne — le défaut devient une
donnée fausse au lieu d'un arrêt net.

**Corollaire.** Ne pas conclure « ça ne casse rien en release » d'une panique qui
n'apparaît qu'en debug. Lire ce que le code fait **sans** l'assertion : c'est
cela, le comportement livré.

---

## 2026-09-11 | Une porte de validation ne peut pas exiger ce qu'elle valide

R-516 veut qu'une configuration ne soit déclarée déterministe qu'après avoir
rejoué les vecteurs d'or. J'ai donc ajouté un drapeau `validated`, et fait
échouer `is_in_validation_matrix()` sans lui. Le test qui vérifie que la machine
courante appartient à la matrice s'appuyait sur cette même fonction : sur
`x86_64-apple-darwin`, en attente de sa première validation, il a échoué — et en
échouant, il a **arrêté la suite avant les vecteurs d'or**. La configuration ne
pouvait donc être validée que si elle l'était déjà.

**Règle.** Séparer la propriété **de la machine** du fait **du dépôt**. Ici,
`is_matrix_target()` — le triplet et le jeu d'instructions correspondent — et
`is_in_validation_matrix()` — quelqu'un l'a rejouée et archivée. La porte de
validation s'appuie sur la première, jamais sur la seconde.

**Corollaire.** Le symptôme trompait : le job s'appelait `det-vectors` et
échouait, ce qui ressemblait à une divergence de bits — un défaut bloquant. Il
n'y en avait aucune ; les vecteurs n'avaient simplement pas été joués. Avant de
conclure d'un job en échec à ce que son nom suggère, lire **quel test** a échoué.

---

## 2026-09-13 | Une valeur par défaut valide efface le signal d'absence

Les trois importeurs écrivaient `[0, 127, 0, 0]` quand la source ne portait pas
de normale, avec le commentaire « C-23 la calculera ». C-23 ne l'aurait jamais
pu : cette valeur est aussi un `+Y` parfaitement légitime, et une fois écrite,
plus rien ne distinguait « l'auteur n'a rien dit » de « l'auteur a dit +Y ». Le
validateur, lui, l'acceptait. L'intention était juste ; la représentation
rendait l'étape suivante impossible sans que rien ne le signale.

Même famille, trouvée en écrivant la fusion : le STL reportait la normale de
chaque facette sur des sommets que `stl_io` avait déjà partagés par position.
La dernière facette gagnait, et le test ne voyait qu'un triangle — jamais deux
facettes voisines.

**Règle.** Une absence que l'aval doit traiter se **marque**, elle ne se
remplace pas par une valeur valide. Soit une valeur que le validateur refuse
(`[0; 4]`), soit un marqueur à côté (`missing_normals`) — ici les deux, pour
que la normale écrite nulle reste refusée.

**Corollaire.** Un test d'importeur sur une seule primitive ne dit rien de ce qui
se passe à la jonction de deux. Tester au moins une arête partagée.

---

## 2026-09-13 | Gson est celui de Minecraft, pas le dernier publié

`SchemaExport` appelait `JsonObject.isEmpty()`. La compilation a échoué : Gson
n'est pas une dépendance d'AXION, c'est celui que Minecraft 1.20.1 fournit, et
sa version est antérieure à cette méthode. La documentation en ligne décrit la
dernière version, pas celle qui est sur le classpath.

**Règle.** Côté Java, une bibliothèque héritée de la plateforme (Gson,
NightConfig, Mixin) s'emploie avec l'API de **sa** version. Préférer les
méthodes anciennes et stables (`size()` plutôt que `isEmpty()`) et compiler
avant de conclure, plutôt que se fier à une documentation.

## 2026-09-15 | `git add tools/bench/` a emporté un `__pycache__/*.pyc` généré

Après avoir écrit un outil Python et l'avoir exécuté, `git add tools/bench/` a
stagé le dossier entier — y compris le `__pycache__/bench.cpython-314.pyc` que
l'interpréteur venait de produire. Le commit est parti avec, sur le remote,
avant que je le remarque : un fichier d'état local dans l'historique, ce que le
CLAUDE.md interdit explicitement.

**Règle.** Ne jamais `git add <dossier>/` quand le dossier a pu recevoir des
fichiers générés (bytecode, sorties de build, caches). Stager les fichiers
**nommés**, ou relire `git status --short` **avant** de committer et refuser
tout ce qui n'a pas été écrit à la main. Exécuter un script Python crée un
`__pycache__/` dans la foulée ; l'ignorer (`.gitignore`) ne protège que
l'avenir, pas le `git add` du même tour.

**Corollaire.** Le correctif : `git rm --cached` le fichier, ajouter la règle
`.gitignore`, committer la suppression. La règle d'ignore seule ne retire pas ce
qui est déjà suivi.

## 2026-09-15 | JMH resplit `-jvmArgs` sur les espaces, un chemin en pâtit

Le benchmark JMH B-07 devait charger la bibliothèque native depuis un dossier
passé au JVM forké par `-jvmArgsAppend "-Daxion.native.dir=<chemin>"`. Le run
échouait sur `ClassNotFoundException: Minecraft\AXIONENGINE\target\release` :
le chemin du dépôt contient un espace (`Mods Minecraft`), et JMH **découpe la
valeur de ses `-jvmArgs` sur les espaces** avant de la passer au fork. La
seconde moitié du chemin devenait un argument, pris pour une classe principale.
Le processus JMH rendait pourtant `0`, sans mesure : un échec silencieux au
niveau du build.

**Règle.** Ne jamais passer à JMH un `-jvmArgs` dont la valeur contient un
espace. Pour transmettre un chemin au JVM forké, une **variable
d'environnement** (`environment 'X', valeur` sur la tâche `JavaExec`) : le fork
en hérite, et une variable porte un espace sans être coupée. Le benchmark la lit
par `System.getenv`.

**Corollaire.** Un harnais qui rend `0` n'a pas forcément mesuré. Vérifier qu'un
run de benchmark produit bien une ligne de résultat (`Score`), pas seulement un
code de sortie nul — comme pour un push qui affiche `ok` sans avoir avancé le
remote.

## 2026-09-19 | Deux constantes censées être égales dérivent en silence

La fiche C-25 (R-562) veut que `COMPILER_VERSION` soit la même côté Rust
(`ax_asset::compile`) et côté Java (`dev.axion.asset.CompilerVersion.CURRENT`) :
la première l'estampille dans l'en-tête A3D, la seconde la mêle à la clé de
cache. Le Rust était passé à 5 au fil de C-23 (A/B/C) puis de la section
`NODE` ; le Java était resté à 1. Rien ne le disait — le cache restait
cohérent avec lui-même —, mais la clé ne reflétait plus la version réelle du
compilateur : un asset produit par un compilateur plus ancien aurait été
repris, et R-562 existe précisément pour l'empêcher.

**Règle.** Quand une exigence demande à deux valeurs d'avancer ensemble dans
deux fichiers, la seule garde qui tienne est un contrôle qui les **compare**,
branché en CI — pas une consigne dans un commentaire. La spec le disait
(« vérifié en CI ») ; tant que le contrôle n'existait pas, la consigne n'a rien
retenu. `tools/ci/check_compiler_version.py` le fait maintenant.

**Corollaire.** Un tel contrôle doit échouer aussi quand il ne **trouve pas**
la constante : un renommage la cacherait, et un contrôle qui ne lit plus rien
passe au vert en ne comparant rien. Le silence ne prouve pas l'égalité, il
prouve l'absence de mesure.

## 2026-09-19 | `convex_hull` de parry tolère les coplanaires, pas les confondus

Un test de C-31 supposait que `SharedShape::convex_hull` rendrait `None` pour
quatre points **coplanaires** (aucun volume). Faux : parry en fait une
enveloppe plate et rend `Some`. Il ne rend `None` que pour une entrée
réellement dégénérée — points confondus ou colinéaires, moins de trois points
distincts. Le test échouait en refusant ce que la bibliothèque accepte.

**Règle.** Avant d'affirmer dans un test qu'une bibliothèque **refuse** une
entrée limite, vérifier son vrai seuil de refus dans sa source, pas dans
l'intuition géométrique. Le contrat qu'on expose (ici `DegenerateConvexHull`)
doit refléter le comportement réel de la dépendance, sous peine d'un garde-fou
qui ment sur ce qu'il rejette. Même famille que les paniques de `gltf`/`tobj` :
ce qu'une dépendance accepte n'est pas ce qu'on suppose.

## 2026-09-19 | rapier 0.35 rattrape les corps rapides sans CCD (contacts spéculatifs)

Un test voulait prouver la CCD de C-31 en faisant traverser un mur fin à une
bille rapide **sans** CCD, puis en l'arrêtant **avec**. La bille ne traversait
jamais, même à ~0.3 m de déplacement par pas contre un mur de 4 cm : rapier
0.35 emploie des **contacts spéculatifs** qui prédisent le contact dans une
marge et arrêtent les corps rapides avant le tunneling, CCD ou non.

**Règle.** Ne pas éprouver une option par un comportement que la dépendance
assure déjà par un autre mécanisme : on finit par tuner une scène de plus en
plus extrême contre la robustesse du moteur. Tester ce que **notre** code
contrôle — ici la pose du drapeau (`is_ccd_enabled` round-trip) et l'absence
de régression de la simulation — et laisser l'anti-traversée au contrat de
rapier, qui le teste chez lui.

## 2026-09-20 | `effective_world_inv_inertia` de rapier est la *racine* de l'inverse

Pour calculer la masse effective au contact (R-615), il faut le terme
angulaire `(r×n)·I⁻¹·(r×n)`. Le champ public `RigidBodyMassProps::
effective_world_inv_inertia` a un nom qui laisse croire à l'inverse de
l'inertie, mais sa doc et le code de `effective_angular_inertia()` le montrent :
c'est la **racine** de cet inverse (S, avec S·S = I⁻¹). La méthode
`effective_angular_inertia()`, elle, **inverse** cette racine et rend l'inertie
non inversée. Deux pièges de nom opposés dans la même structure.

**Règle.** Le terme angulaire se calcule `|S·(r×n)|²` (`mul_vec` puis
`length_squared`), pas `(r×n)·M·(r×n)` avec la méthode. Vérifié par un test
d'impact : au contact bas d'une sphère `r×n = 0`, donc la masse effective doit
valoir exactement la masse du corps — un repère numérique qui tranche entre
les deux lectures possibles.

**Corollaire.** Quand le nom d'un champ d'une dépendance et sa doc semblent se
contredire, lire le code qui le produit ou le consomme, et se donner un cas
analytique (ici `r×n = 0`) qui distingue les interprétations.

## 2026-09-20 | rapier 0.35 neutralise déjà le non-fini (quarantaine) | garde-fou FM-20/R-181

**Ce qui a coûté.** Écrit un garde-fou R-181 qui, après le pas, détectait une
pose/vitesse NaN pour la restaurer. Le test à impulsion NaN échouait
(`invalid_state_count == 0`) : la bille ne bougeait pas et sa vitesse revenait
à zéro. rapier 0.35 a un module `quarantine` (pipeline) qui détecte le non-fini
(NaN, infini) à deux points de contrôle, ramène la pose au dernier état valide,
annule vitesses et forces, **désactive** le corps (`set_enabled(false)`) et le
reporte via `PhysicsWorld::quarantine().bodies()` (vidé à chaque pas). Il
contient aussi la propagation par contacts/CCD. Un garde-fou maison ne voit donc
jamais le NaN : rapier l'a neutralisé avant.

**Règle.** Pour FM-20/R-181, consommer `quarantine().bodies()` après le pas
(compter l'E-2030, convertir la désactivation en sommeil forcé) au lieu de
redétecter. La quarantaine ne couvre **que** le non-fini : le fini-hors-monde
reste à la charge du moteur (borne de coordonnée locale).

**Corollaire.** rapier plafonne aussi la vitesse linéaire à
`normalized_max_linear_velocity` (défaut **400 m/s**) avant intégration : un
corps dynamique ne peut donc pas sortir du monde en un pas, et R-180 (clamp à
300 m/s) s'applique **après** le pas, par-dessus ce plafond global. Un cas de
garde-fou qui ne peut être atteint par la dynamique se teste en **plaçant** le
corps dans l'état fautif (spawn hors borne), pas en l'y poussant.

## 2026-09-23 | `cargo fmt` jamais vérifié → format cassé accumulé, masqué par une CI bloquée | vérifier fmt avant commit

**Ce qui a coûté.** Plusieurs commits de C-31 (4b-iii → 4d) poussés sans
`cargo fmt --all -- --check`. La CI, bloquée pour une raison de **facturation**
GitHub (jobs jamais démarrés, tous en échec), ne les a jamais rattrapés : le
format a divergé sur 10 fichiers ax-ffi/ax-physics, invisible tant que la CI
était rouge pour une autre cause.

**Règle.** Lancer `cargo fmt --all -- --check` avant chaque commit Rust, au même
titre que `clippy -D warnings` et les tests — la toolchain est épinglée
(`rust-toolchain.toml`, 1.94.0), donc le rustfmt local produit exactement ce que
la CI exige. Un vert local **partiel** (tests + clippy sur un crate) n'est pas
une CI verte.

**Corollaire.** Une CI rouge en continu peut masquer des régressions de qualité
(format, lint) qu'on ne voit qu'au déblocage. Devant des échecs Actions, lire la
cause réelle (`gh run view <id>`) avant de conclure : ici « job not started —
account payments failed / spending limit », soit un problème de compte, pas de
code.


## 2026-09-25 | Le bootstrap de `gradlew` réclame un JAVA_HOME, même en toolchain auto

**Ce qui a coûté.** `./gradlew` (Bash comme PowerShell) échoue d'emblée :
« JAVA_HOME is not set and no 'java' command could be found ». Aucun JDK n'est
sur le PATH de cette machine, et le projet s'appuie sur l'auto-provisioning de
toolchain Gradle — mais ce mécanisme sert la *compilation*, pas le lancement de
la JVM Gradle elle-même, qui a besoin d'un JDK pour démarrer.

**Règle.** Avant tout `gradlew` ici, pointer JAVA_HOME vers le JDK 17 que Gradle
a déjà provisionné, sous `~/.gradle/jdks/eclipse_adoptium-17-amd64-windows/`
(dossier `jdk-17.0.20.1+1`). En PowerShell : `$env:JAVA_HOME = "<...ce JDK...>"`
puis `.\gradlew.bat`. Un JDK 21 provisionné est aussi présent ; Forge 1.20.1
veut du 17.

## 2026-09-25 | Message de commit multi-ligne accentué : `-F`, jamais un here-string PowerShell

**Ce qui a coûté.** `git commit -m @'...'@` (here-string PowerShell) passé à
l'outil Bash, qui est du POSIX sh : la syntaxe n'existe pas, le message a été
découpé sur les espaces (« pathspec 'validée' did not match ») et les
parenthèses ont cassé la ligne.

**Règle.** Pour un message multi-ligne, a fortiori en français, l'écrire dans un
fichier (scratchpad) et `git commit -F <fichier>`. Le heredoc Bash `<<'EOF'`
convient aussi ; `-F` reste le plus sûr avec accents et parenthèses.


## 2026-09-27 | Un item « différé » a souvent un ADR qui l'a différé — le lire avant de le construire

**Ce qui a coûté.** Sur « go C-32 masse/COM », j'ai failli construire la masse/COM
pré-calculée puis la surcharge déclarée. Deux fois, la relecture a montré que la
voie était **bloquée en amont** (pas d'effort en cause) : ADR-115, *ratifié*,
avait déjà tranché — masse/COM **calculées au runtime par rapier depuis les
densités** (ce que j'ai livré), la surcharge **déclarée** confiée à CREATE_ASSEMBLY
*avec sa source* (les definitions, non câblées), et le **pré-calcul BodyDesc**
renvoyé à un ADR distinct « si un besoin émerge ». Construire l'un des deux
maintenant, c'était du transport avant son producteur — l'anti-pattern qu'ADR-115
proscrit lui-même.

**Règle.** Avant de traiter un item de la liste « Différé », relire l'ADR (ou la
note) qui l'a différé : il dit en général **pourquoi** et **à quelle condition**
le reprendre. Un producteur/consommateur absent est une dépendance, pas un manque
d'effort — passer en effort max ne le lève pas. Choisir alors un item **débloqué**
(ici : colliders ConvexHull, dont ADR-115 §1 avait pré-autorisé l'annexe PHYS).

## 2026-10-01 | Ne jamais écrire « vérifié en jeu » avant de l'avoir observé

**Ce qui a coûté.** Le message de commit de la boucle entité (901dea3) affirmait
« comportement vérifié en jeu » alors que rien n'avait été lancé — et que c'était de
toute façon invérifiable sans definition+asset à colliders (0 definition livrée). Une
correction publique a été nécessaire (commit vide 491e0e4).

**Règle.** « vérifié en jeu » / « testé » ne s'écrit **qu'après** avoir observé le
résultat (log lu, écran, assertion passée). Compilation + tests unitaires ≠ vérifié en
jeu : le dire explicitement (« compile + testé unitairement ; comportement en jeu à
confirmer ») tant que l'observation n'a pas eu lieu.

## 2026-10-01 | Un accumulateur à pas fixe se nourrit du temps réel écoulé, pas de son pas

**Ce qui a coûté.** La frontière FFI passait `SIM_TICK_DT = 1/60` à `advance_all` à chaque
tick serveur, alors qu'un tick Minecraft dure 1/20 s. L'accumulateur de R-283 (pas fixe
`sim.fixed_dt` = 1/60, clampé à `max_substeps`) n'exécutait donc qu'un sous-pas par tick au
lieu de trois : la simulation avançait à 1/3 de la vitesse réelle. Chute au ralenti, perçue
en jeu comme du lag/saccade, qu'aucun test unitaire ne voyait — ils appellent `advance_all`
avec leur propre dt.

**Règle.** Un accumulateur à pas fixe reçoit le **temps réel écoulé** depuis le dernier
appel, jamais la taille de son pas. Sur un serveur à cadence fixe (Minecraft, 20 Hz), c'est
la période du tick, 1/20 s ; l'accumulateur en déduit le nombre de sous-pas. Injecter le
pas fixe lui-même neutralise l'accumulateur (un seul sous-pas, jamais de rattrapage) et
divise la vitesse par `réel / fixe`. La présence d'un clamp anti-spirale est justement
l'indice qu'on attend du temps réel, pas un pas constant.

**Corollaire.** Une erreur de vitesse de simulation reste invisible en test unitaire quand
les tests fixent eux-mêmes le dt ; elle ne se révèle qu'en jeu, ou par un test d'intégration
mesurant la distance de chute par seconde réelle. Nommer la constante d'après ce qu'elle est
(`SERVER_TICK_DT`) plutôt que d'après le pas de sim évite la confusion sémantique qui a
produit le bug.

## 2026-10-01 | Un corps recréé au chargement coule si son sol n'est pas bâti en priorité

**Ce qui a coûté.** Au retour dans un monde solo (déconnexion/reconnexion = redémarrage du
serveur intégré), le cube d'assembly apparaissait enfoncé sous les blocs. Le planificateur
de tuiles de collision (C-38) repart de zéro à chaque démarrage : `onChunkLoad` invalide
toutes les sections chargées, puis la reconstruction est amortie à `tiles_per_tick`. La
section portant le corps était reconstruite quand son tour venait dans ce backlog, mais
`AssemblyRuntime.onJoin` recrée le corps dynamique **aussitôt** — il tombait donc sans sol
plusieurs ticks. Au premier spawn de la même session, les tuiles autour du joueur étaient
déjà chargées : pas de trou, donc bug invisible jusqu'au rechargement.

**Règle.** Quand un consommateur est créé aussitôt (un corps physique) mais que son
producteur (le sol de collision) est produit de façon amortie et repart froid à chaque
démarrage, prioriser explicitement ce dont le consommateur a besoin au tick même de sa
création. `WorldTilePlanner` vide désormais en tête de file les sections **abritant** une
assembly, à budget constant : le sol existe au tick où le corps est créé.

**Corollaire.** Un défaut de séquencement au démarrage à froid ne se reproduit pas en régime
établi. Le tester, c'est repartir de zéro (nouveau planificateur, backlog plein) et vérifier
que la ressource critique sort en tête — pas observer l'état stable.

## 2026-10-02 | Un indicateur de jeu peut ignorer la classe d'objet testée — vérifier sa source avant d'en faire un test

**Ce qui a mal tourné.** Pour vérifier la visée d'une assembly (R-702), j'ai demandé de lire la
ligne « Targeted Entity » de l'écran F3. Elle est restée vide, et la visée a été déclarée
cassée — à tort : `GameRenderer.pick` ne renseigne `crosshairPickEntity`, qui alimente cette
ligne, que pour une `LivingEntity` ou un `ItemFrame` (bytecode lu). Une `AxionEntity` n'est ni
l'un ni l'autre (R-700) : le test ne pouvait que « échouer ». Le « Targeted Block » du même
écran vient d'un rayon propre à F3, qui ignore les entités : il ne prouve rien non plus.

**Cause.** Un indicateur choisi sur sa promesse (« entité visée ») sans vérifier ce qu'il lit
réellement ni pour quels types il le lit.

**Règle.** Avant de donner un indicateur comme critère de vérification, lire ce qui le
renseigne et vérifier qu'il couvre le type testé. À défaut, exposer la grandeur vraie soi-même
— ici `Minecraft.hitResult`, affichée dans le libellé de debug (« — visée »).
