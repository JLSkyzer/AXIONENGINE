# crates/

Le moteur natif Rust, une crate par composant du cahier des charges. Elles forment la bibliothèque native
chargée par le mod (`ax-ffi`).

| Crate | Composant |
|---|---|
| `ax-core` | C-10 : contexte natif, handles, états du runtime |
| `ax-math` | C-11 : types et conventions mathématiques |
| `ax-jobs` | C-12 : ordonnancement, annulation, échéances des travaux |
| `ax-mem` | C-13 : arènes mémoire, comptage des allocations |
| `ax-ffi` | C-14 : la bibliothèque native et ses points d'entrée JNI |
| `ax-telemetry` | C-15 : métriques, budgets mesurés, diagnostic |
| `ax-det` | C-16 : noyau déterministe et vecteurs d'or |
| `ax-asset` | C-20..C-25 : compilation, conteneur A3D, cache d'assets |
| `ax-model` | modèle de données, source unique de la configuration |
| `ax-scene` | C-30 : graphe de scène |
| `ax-physics` | C-31 : monde physique (Rapier) |
| `ax-vehicle` | C-33 : modèle de véhicule (suspension, pneus, motopropulseur) |
| `ax-anim` | C-37 : animation |
| `ax-render-prep` | C-64 : préparation du rendu (culling, LOD) |
| `ax-bench` | C-72 : harnais de benchmarks |
| `ax-cli` | C-74 : l'outil en ligne de commande `axion-cli` |
