# AXION ENGINE

Framework 3D, physique, déformation, animation et simulation pour Minecraft Forge. Moteur générique : il ne connaît
aucun contenu par son nom. Les véhicules, objets et machines sont apportés par des mods Java qui les enregistrent par
l'API publique `axion-api`, en code ou par un JSON optionnel que le mod charge
([ADR-160 D9](docs/decisions/ADR-160.md)).

- **Minecraft** 1.20.1 · **Forge** 47.x · **Java** 17
- **Architecture** Java + Rust (bibliothèque native)
- **Licence** Apache-2.0

## État

Jalons M0 à M4 prononcés. **M5 en cours** : véhicules, sièges, attaches, gouverneur, overlay. Une voiture se conduit
au clavier en jeu (tranche 3 d'[ADR-159](docs/decisions/ADR-159.md)). La suite et ce qui bloque chaque jalon :
[`tasks/todo.md`](tasks/todo.md) et l'annexe A.7 du cahier des charges.

## Plan du dépôt

| Chemin | Ce qu'on y trouve |
|---|---|
| [`java/`](java/) | le code Java : `axion-api` (l'API publique) et `axion-mod` (le mod Forge) |
| [`crates/`](crates/) | le moteur natif Rust, une crate par composant |
| [`docs/`](docs/) | le cahier des charges, les décisions (ADR), les guides, les schémas |
| [`tools/`](tools/) | les scripts : CI locale, index de la spec, films, campagnes, génération |
| [`tasks/`](tasks/) | le plan de travail (`todo.md`) et les leçons apprises (`lessons.md`) |
| [`fuzz/`](fuzz/), [`benchmarks/`](benchmarks/) | le fuzzing de la chaîne d'assets, les résultats de benchmarks |
| [`third_party/`](third_party/) | les dépendances copiées et corrigées (Rapier) |
| `build.gradle`, `settings.gradle`, `gradle/`, `Cargo.toml`, … | le build, à la racine où Gradle et Cargo le cherchent |
| `CLAUDE.md`, `AGENTS.md` | les consignes des agents de développement |
| `.runs/` | ignoré par Git : un dossier de jeu par tâche (`client`, `gametest`, `film`, `rendertest`…) |
| `local/` | ignoré par Git : contenu personnel, hors du dépôt public |

L'arborescence est décrite par la PARTIE 33 du cahier des charges et par [ADR-161](docs/decisions/ADR-161.md).

## Construire et tester

Il faut un JDK 17 et, pour la partie native, la toolchain Rust épinglée par `rust-toolchain.toml`.

```bash
./gradlew build
```

```bash
cargo test --workspace --all-features
```

```bash
python tools/ci/local_ci.py
```

`./gradlew runClient` lance un client de développement (dans `.runs/client`) ; `runServer`, `runGameTestServer` et
`runData` sont aussi disponibles.

## Documentation

- [Le cahier des charges](docs/spec/) : la source de vérité. Il ne se lit pas d'un bloc : `docs/spec/INDEX.md` donne
  la plage de lignes de chaque section, `docs/spec/ID-MAP.tsv` situe chaque identifiant.
- [Les décisions](docs/decisions/README.md), rangées par sujet.
- [Les guides](docs/guides/) : physique, rendu, matériaux, configuration.

## Licence et marques

Distribué sous licence Apache-2.0 : voir [`LICENSE`](LICENSE) et [`NOTICE`](NOTICE).

AXION ENGINE ne redistribue ni Minecraft, ni Forge, ni aucun asset tiers. Minecraft est une marque de Mojang AB ; ce
projet n'est ni affilié à Mojang AB ni approuvé par Mojang AB ou Microsoft.
