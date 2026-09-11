# Carte des crates

Quel crate porte quel composant, **dans l'état actuel du dépôt**. L'arborescence
complète de ce qui est prévu est figée par la PARTIE 33 du cahier des charges :

```bash
grep -n "PARTIE 33" docs/spec/INDEX.md
```

Cette page ne la recopie pas. Elle dit ce qui existe, ce qui reste à créer, et
elle est tenue à jour à chaque crate ajouté — c'est elle que le manifeste du
workspace désigne.

## Crates existants

| Crate | Composant | Rôle |
|---|---|---|
| `ax-core` | C-10 | Contexte natif, handles à génération, états du runtime |
| `ax-math` | C-11 | Types mathématiques (`glam`), origine flottante |
| `ax-mem` | C-13 | Arènes, pool de pages de déformation, comptage des allocations |
| `ax-jobs` | C-12 | Pool dédié, annulation, deadlines, granularité adaptative |
| `ax-det` | C-16 | Noyau déterministe scalaire, `DetRng`, empreinte de champ, matrice de validation, vecteurs d'or |
| `ax-telemetry` | C-15 | Registre de métriques, métriques de budget, export JSON |
| `ax-model` | DM-* | Structures du modèle, registre des budgets, configuration, génération Java |
| `ax-asset` | C-21, C-22, C-24 | Import de sources, validation, conteneur A3D |
| `ax-ffi` | C-14 | `cdylib`, ABI, points d'entrée JNI |
| `tools/codegen` | — | Génère le code Java depuis `ax-model` ; hors `crates/`, il ne fait pas partie de la bibliothèque livrée |

## Règles qui portent sur cette carte

- **Un seul `cdylib` :** `ax-ffi`. Tous les autres crates sont des `rlib`
  internes. Deux bibliothèques dynamiques signifieraient deux copies de l'état
  du moteur dans le processus.
- **`unsafe` confiné** à `ax-ffi` (frontière JNI) et `ax-mem` (arènes et pages),
  R-2120. Les autres crates le refusent par lint, avec la justification du choix
  entre `deny` et `forbid` dans leur `Cargo.toml`.
- **Aucun crate ne définit son propre type vecteur ou matrice** (R-460) : ils
  viennent tous de `ax-math`, donc de `glam`. `ax-det` n'en emploie aucun : le
  noyau déterministe est scalaire, et les chemins déterministes qui manipulent
  des vecteurs le font composante par composante ([ADR-104](../decisions/ADR-104.md)).
- **Aucune dépendance hors de la table 32.2** sans justification, licence
  compatible, alternative évaluée, entrée au `NOTICE` et ADR si elle est
  structurante (R-2300).

## Ce qui reste à créer

Les crates de la PARTIE 33 non encore ouverts le seront avec le composant qu'ils
portent, jamais en avance : un crate vide est un placeholder, et R-001 les
interdit. Les prochains, par ordre de jalon :

| Crate | Composant | Jalon |
|---|---|---|
| — | C-20..C-23, C-25 rejoignent `ax-asset` : orchestrateur, importers, validateur, cache | M1, M2 |
| `ax-scene` | C-30 — graphe de scène | M2 |
| `ax-physics` | C-31, C-32, C-38..C-40 | M3 |
