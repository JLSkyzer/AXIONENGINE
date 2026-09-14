# Benchmarks — AXION ENGINE (C-72)

Ce dossier recueille les **résultats** de benchmarks générés par le harnais
C-72. La spécification est la PARTIE 30 du cahier des charges ; ce fichier ne la
recopie pas, il dit où vivent les mesures et comment on les lit.

## Position de principe (R-2230)

Aucun chiffre de performance ne se publie — README, notes de version, sorties de
`/axion`, documentation — sans un fichier de résultats **généré par le harnais**
et rangé sous [`results/`](results/). Un nombre sans fichier n'a pas sa place
dans le dépôt.

C'est pourquoi ce dossier ne contient, à ce stade, **aucun résultat** : les
chiffres publiés proviennent de mesures sur matériel de référence documenté
(R-2252), pas d'une exécution sur une machine de développement quelconque.

## Le harnais

- **Micro-benchmarks Rust** : `criterion` (R-2240), dans les `benches/` des
  crates mesurés.
- **Micro-benchmarks Java** : `JMH` (R-2240).
- **Harnais en jeu** : monde déterministe, entrées scriptées, sortie JSON.

Tous produisent le même **format de résultat**, le schéma 2, dont la fondation
commune — modèle, capture de plateforme, statistiques, archivage — est le crate
[`ax-bench`](../crates/ax-bench). Ce crate refuse d'archiver un résultat dont la
provenance est incomplète (R-2231).

## Format de résultat (schéma 2, PARTIE 30.4)

Chaque résultat porte : numéro de schéma, identifiant du benchmark (`B-01`…,
PARTIE 30.2), commit, date, plateforme (OS, CPU, cœurs, GPU, pilote, JVM),
configuration (dont la version du harnais, voir `docs/decisions/ADR-111.md`, et
les niveaux de qualité le cas échéant), paramètres du cas, **données brutes par
itération**, et les statistiques agrégées : p50, p95, p99, min, max, écart-type
(jamais la moyenne seule, PARTIE 30.3).

## Rangement

Le harnais écrit sous :

```text
results/<benchmark>/<plateforme>/<date>_<commit>.json
```

Le regroupement par benchmark puis par plateforme permet à la non-régression
(R-2250) de retrouver le dernier résultat d'une même plateforme ; le préfixe de
date rend l'ordre lexicographique chronologique.

## Ce qui est mesurable aujourd'hui

Les benchmarks des sous-systèmes déjà écrits sont branchés au fil des jalons.
Ceux qui visent la physique, le rendu, les véhicules, l'animation, les
particules ou la déformation attendent l'existence de ces composants : le
harnais ne mesure pas ce qui n'existe pas encore, et n'inscrit aucun benchmark
vide.
