# tools/bench — agrégation et comparaison des benchmarks (C-72)

Ce dossier tient le versant **agrégation et comparaison** du harnais C-72
(PARTIE 33). Il lit les résultats archivés sous
[`benchmarks/results/`](../../benchmarks/README.md) au format schéma 2
(PARTIE 30.4) et raisonne dessus **sans savoir quel harnais les a produits** :
criterion (crate `ax-bench`), JMH ou le harnais en jeu écrivent le même format,
et cet outil les traite tous de la même façon.

C'est le pendant multi-résultats du binaire `bench-ci` (crate `ax-bench`) : là où
`bench-ci` mesure et compare au sein d'une exécution, `bench.py` raisonne sur
l'archive entière, tous producteurs confondus.

## Usage

```bash
python tools/bench/bench.py check   [--root DIR]
python tools/bench/bench.py summary [--root DIR]
python tools/bench/bench.py compare [--root DIR] [--all | --benchmark B --platform P]
```

`--root` vaut `benchmarks/results` par défaut.

- **`check`** — vérifie que chaque résultat archivé porte la provenance exigée
  par R-2231 (schéma, benchmark, commit, date, OS, CPU, cœurs, version du
  harnais, échantillons bruts). Sort en erreur au premier fichier mal formé :
  un résultat sans provenance n'est pas reproductible, donc pas citable
  (R-2230). À passer en CI sur tout résultat committé.

- **`summary`** — liste les résultats par benchmark et par plateforme, avec le
  p50/p95/p99 du plus récent.

- **`compare`** — compare le p95 des deux résultats les plus récents d'une même
  plateforme. Une hausse de plus de 20 % bloque (R-2251). `--all` balaie toutes
  les paires benchmark/plateforme de l'archive.

## Cohérence avec le crate `ax-bench`

La comparaison est **identique** à celle de `ax-bench::regress` : même métrique
(p95), même seuil de 20 %, et jamais entre deux versions du harnais (ADR-111,
clé `config.harness_version`). Un même couple de résultats reçoit le même verdict
des deux côtés.

## Ce qui n'est pas ici

Les chiffres publiés proviennent de mesures sur matériel de référence documenté
(R-2252) ; cet outil sert à détecter une régression grossière, pas à publier. Il
ne mesure rien lui-même : la mesure est le rôle des harnais (criterion, JMH,
harnais en jeu), cet outil ne fait que lire et comparer ce qu'ils archivent.
