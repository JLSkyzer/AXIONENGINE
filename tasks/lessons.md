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

## Structure du projet

**[2026-09-09] | Le MDK Forge a été généré avec `mod_id=axionengine` et
`fr.eriniumgroup.axionengine`, alors que le CDC impose `axion` et `dev.axion` |
Vérifier l'identité du mod contre le CDC avant d'écrire la moindre classe.**

Le `mod_id` entre dans les espaces de noms `assets/`, `data/`, les
`ResourceLocation` et les clés NBT persistées : le changer après coup casse les
mondes existants. Corrigé avant tout code — voir `docs/decisions/ADR-100.md`
pour la tension R-401 / PARTIE 33 sur le point d'entrée.
