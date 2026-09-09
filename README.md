# AXION ENGINE

Framework 3D, physique, déformation, animation et simulation pour Minecraft
Forge. Moteur générique et entièrement data-driven : un objet physique,
déformable et destructible se crée avec un seul GLB et un seul JSON, sans écrire
de Java.

- **Minecraft** 1.20.1 · **Forge** 47.x · **Java** 17
- **Architecture** Java + Rust
- **Licence** Apache-2.0

## État

**Le projet démarre.** L'arborescence, la chaîne de build et l'outillage sont en
place ; le moteur, lui, n'est pas encore écrit. Le jalon courant est M0
(squelette et frontière native) — voir [`tasks/todo.md`](tasks/todo.md).

Le JAR produit aujourd'hui se compile et s'assemble, mais ne fait rien d'autre
que se déclarer auprès de Forge.

## Construire

Nécessite un JDK 17 et, pour la partie native, la toolchain Rust épinglée par
`rust-toolchain.toml`.

```bash
./gradlew build
```

L'artefact est produit dans `java/axion-mod/build/libs/`.

```bash
./gradlew runClient
```

Lance un client de développement. `runServer`, `runData` et `runGameTestServer`
sont également disponibles.

```bash
cargo test --workspace --all-features
```

Tests de la partie native.

## Organisation du dépôt

L'arborescence est figée par la PARTIE 33 du cahier des charges.

| Chemin | Rôle |
|---|---|
| `cdc/` | Cahier des charges V1.0, source de vérité unique du projet |
| `docs/spec/` | Index de navigation du cahier des charges, générés |
| `docs/decisions/` | Décisions d'architecture propres au dépôt (à partir d'ADR-100) |
| `docs/AGENT.md` | Contrat de l'agent de développement |
| `java/axion-api/` | C-70, API publique versionnée et publiée séparément |
| `java/axion-mod/` | Le mod Forge |
| `crates/` | Partie native Rust |
| `tools/spec/` | Génération de l'index du cahier des charges |
| `tasks/` | Plan de travail et leçons apprises |

## Documentation

Le cahier des charges fait 8270 lignes et n'est pas destiné à être lu d'un bloc.
`docs/spec/INDEX.md` donne la plage de lignes de chacune de ses 452 sections, et
`docs/spec/ID-MAP.tsv` localise chacun de ses 1331 identifiants normatifs.

```bash
python tools/spec/spec_index.py
```

régénère les deux.

## Licence et marques

Distribué sous licence Apache-2.0 — voir [`LICENSE`](LICENSE) et
[`NOTICE`](NOTICE).

AXION ENGINE ne redistribue ni Minecraft, ni Forge, ni aucun asset tiers.
Minecraft est une marque de Mojang AB ; ce projet n'est ni affilié à Mojang AB
ni approuvé par Mojang AB ou Microsoft.
