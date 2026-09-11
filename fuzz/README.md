# Fuzzing de la chaîne d'assets

R-903 : *fuzzing continu en CI (`cargo-fuzz`, cible `a3d_reader`), corpus
versionné, **tolérance zéro**.*

Les lecteurs d'assets sont la seule partie d'AXION qui lit des fichiers fournis
par des tiers et en tire des tailles, des décalages et des longueurs de
décompression. Tout le reste du moteur travaille sur des structures déjà
validées. C'est donc ici que le fuzzing a le plus à dire.

**Une erreur n'est pas un défaut.** Refuser un fichier corrompu est le travail de
ces lecteurs, et la PARTIE 7.5 le leur demande. Le défaut serait de paniquer, de
déborder, d'allouer d'après une taille annoncée sans la vérifier, ou de ne pas
rendre la main.

## Cibles

| Cible | Ce qu'elle soumet | Test |
|---|---|---|
| `a3d_reader` | `A3dFile::open`, puis le chargement de toutes les sections | T-681 |
| `gltf` | `import_gltf`, document et ressource résolue | T-680 |
| `obj` | `import_obj`, maillage et bibliothèque de matériaux | T-680 |
| `stl` | `import_stl`, formes binaire et ASCII | T-680 |

Le nom `a3d_reader` vient de R-903, qui le donne explicitement ; l'arborescence
de la PARTIE 33 écrit `a3d` dans une énumération séparée par des virgules, forme
manifestement abrégée. L'exigence numérotée l'emporte.

`packets`, `impacts`, `deform_snapshot` et `nbt` figurent dans cette
arborescence et **n'existent pas encore** : leurs décodeurs viennent avec M4 et
M6. Une cible de fuzzing sans sujet ne trouverait rien, ce qui est pire que pas
de cible — elle passerait au vert.

## Lancer une campagne

`cargo-fuzz` exige une chaîne `nightly` et un désinfecteur d'adresses, et ne
tourne pas sous Windows. La chaîne épinglée par `rust-toolchain.toml` reste
celle qui construit tout ce qui est livré.

```bash
rustup toolchain install nightly --profile minimal
cargo install cargo-fuzz --locked

# Un passage de contrôle, dix minutes
cargo +nightly fuzz run a3d_reader -- -max_total_time=600 -timeout=10

# Rejouer une entrée fautive
cargo +nightly fuzz run a3d_reader fuzz/artifacts/a3d_reader/crash-<empreinte>
```

En CI, le workflow `Fuzzing` fait la même chose sur les quatre cibles en
parallèle. Son entrée `minutes_par_cible` vaut `10` par défaut ; l'acceptance de
M1 demande `60`.

## Corpus

`corpus/<cible>/`, versionné comme R-903 l'exige. Un fuzzer parti de rien passe
l'essentiel de sa campagne à réinventer un en-tête valide ; parti d'un fichier
correct, il explore ce qui est derrière.

| Graine | Provenance |
|---|---|
| `a3d_reader/v1.1-minimal.a3d` | copie de `crates/ax-asset/tests/fixtures/a3d/` |
| `gltf/triangle.gltf` | même document que le helper `triangle()` de `tests/gltf_import.rs` : trois positions et trois indices dans un `data:` URI |
| `obj/cube-de-repli.obj` | copie de l'asset de secours `axion:builtin/missing` (R-522) |
| `obj/mtllib-indices-negatifs.obj` | écrit pour la circonstance : un `mtllib`, et des indices comptés depuis la fin, que la spécification OBJ autorise |
| `stl/triangle-binaire.stl` | écrit pour la circonstance : en-tête de 80 octets, compte sur 32 bits, un triangle |
| `stl/triangle-ascii.stl` | idem, forme textuelle |

Les entrées sont prises **brutes** par les cibles, sans passer par une structure
dérivée d'`arbitrary` : c'est ce qui fait qu'un vrai fichier déposé ici est une
graine telle quelle. Une entrée structurée lirait ses longueurs depuis la fin du
tampon, et un `.gltf` n'y désignerait plus un document.

**Le corpus enrichi par une campagne n'est pas commité automatiquement.** Une
heure de fuzzing produit des milliers d'entrées, et un corpus qu'on laisse
grossir sans regard cesse d'être relisible. La CI le publie en artefact ;
`cargo fuzz cmin <cible>` le réduit à ce qui apporte de la couverture, et c'est
ce résultat-là qu'on verse.

## Le corpus est aussi rejoué sans nightly

`crates/ax-asset/tests/corpus_fuzzing.rs` rejoue chaque graine par le même
chemin que sa cible, sur la chaîne épinglée et sur les quatre plateformes de la
matrice. Il vérifie deux choses qu'une campagne ne vérifie pas :

- qu'aucune graine ne fait paniquer un lecteur, à **chaque** exécution de la CI
  et non à la prochaine campagne ;
- qu'au moins une graine par cible est encore **acceptée**. Une graine qu'un
  changement de code ferait refuser d'emblée cesse d'être un point de départ, et
  le fuzzer repartirait de rien sans que rien ne le dise. C'est le défaut le plus
  discret d'un corpus versionné.
