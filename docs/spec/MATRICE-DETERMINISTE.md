# Matrice de validation déterministe — registre des exécutions

R-516 : *« l'ajout d'une configuration à la matrice exige : compilation,
exécution complète des vecteurs d'or, et **archivage du résultat**. Une
configuration non validée n'est jamais déclarée déterministe, même si elle passe
en pratique. »*

Ce fichier **est** cet archivage. Il ne redit pas ce que le cahier des charges
décide — la matrice de la V1.0 est figée en 5.12bis — il consigne ce qu'une
exécution a **constaté**, avec de quoi la retrouver.

Le drapeau `validated` de `VALIDATION_MATRIX`
([`crates/ax-det/src/profile.rs`](../../crates/ax-det/src/profile.rs)) suit ce
tableau, et `is_in_validation_matrix()` ne rend `true` que pour une ligne
validée. Une configuration visée mais non rejouée bascule donc en `SNAPSHOT`,
ce qui ne retire aucune fonctionnalité (R-514) et ne refuse rien (R-515).

## Ce qui a été rejoué

**Vecteurs d'or** : `crates/ax-det/tests/golden/kernel-v1.txt`, 10 000 cas,
empreinte `0xF9B9055B2A735248`, engendrés sur `x86_64-windows-msvc`.
**Noyau** : `DET_KERNEL_VERSION = 1`.
**Chaîne** : `rustc 1.94.0 (4a4ef493e 2026-03-02)`, épinglée par
`rust-toolchain.toml`, avec `-Cllvm-args=-fp-contract=off` et, sur x86-64,
`-Ctarget-feature=-fma` (`.cargo/config.toml`, R-510).

| Configuration | Empreinte `det_profile` | Vecteurs d'or | Validée |
|---|---|---|---|
| `axion-det/1 x86_64-windows-msvc [sse2]` | `0x65E1A6E0CBA5EDC0` | 10 000 / 10 000 | oui |
| `axion-det/1 x86_64-linux-gnu [sse2]` | `0x95D705D1B36391B3` | 10 000 / 10 000 | oui |
| `axion-det/1 aarch64-macos-none [neon]` | `0x21DCB8C01457AD72` | 10 000 / 10 000 | oui |
| `x86_64-apple-darwin` | — | **jamais rejoués** | **non** |
| `aarch64-unknown-linux-gnu` | — | **jamais rejoués** | **non** |

**Exécution** : [run 34619831725](https://github.com/JLSkyzer/AXIONENGINE/actions/runs/34619831725),
sur `7d1eac0380d3cbfdbc07faa15c64aa1fd7b2801e` (2026-09-11).

`x86_64-apple-darwin` n'a pas pu être rejouée ce jour-là : le job demandait
l'étiquette `macos-13`, que GitHub a retirée de ses runners standard. Une
étiquette retirée ne produit aucune erreur — le job est resté en file
quarante-six minutes sans qu'aucun runner ne lui soit attribué, pendant que les
trois autres plateformes finissaient en moins de huit. `ci.yml` demande
désormais `macos-15-intel`, qui obtient un runner en moins de trente secondes
(Core i7-8700B, `x86_64`, macOS 15.7.9). La ligne sera complétée à la première
exécution réussie, et son `validated` basculera **ensuite**.

### Bibliothèques produites au même passage

| Configuration | Fichier | SHA-256 |
|---|---|---|
| `windows-x86_64` | `axion_native.dll` | `995296b70df57e62ff0409a8e88136e7aac34cc7553437b5a70dc7ee99683d36` |
| `linux-x86_64` | `libaxion_native.so` | `2f12837683997b542c83558c1794f297d9d18caed4178094dd0e5ad3e569e254` |
| `macos-aarch64` | `libaxion_native.dylib` | `c37c8f56feebe7a33b1166d866769c966d95ee980370591637a3cba2f5619c4c` |

## Ce que ce tableau établit

Que les mêmes dix mille cas produisent les **mêmes bits** sur des systèmes, des
bibliothèques C et des **architectures** différentes. Le résultat le plus net est
`aarch64-macos-none [neon]` : un binaire ARM calcule au bit près ce que calcule
un binaire x86-64/SSE2 sous MSVC, subnormaux compris — le corpus en contient
délibérément, et ARM sait les rabattre à zéro quand son registre de contrôle le
demande. Il ne le fait pas ici.

C'est ce que le noyau scalaire promettait : `+ - * / sqrt` sont les cinq
opérations dont IEEE-754 impose l'arrondi correct, les conversions
entier-flottant ont une sémantique fixée par Rust, et tout le reste du noyau —
`digest64`, `DetRng` — est de l'arithmétique entière.

## Ce qu'il n'établit pas

**Aucune garantie universelle** (R-517). Le tableau vaut pour ces
configurations, cette version de `rustc`, ces drapeaux, et cette version du
noyau. Un binaire bâti avec `-C target-cpu=native` n'y figure pas, et
`det_profile` le distingue précisément parce que le jeu d'instructions y entre.

**Rien sur les deux configurations non rejouées.**

`aarch64-unknown-linux-gnu` est dans la matrice du cahier des charges, elle est
« best effort, non bloquant » en 34.2, et GitHub ne fournit pas de runner ARM
sur le plan de ce dépôt. Ses deux axes sont validés séparément — même
architecture que `aarch64-macos`, même système et même libc que
`x86_64-linux-gnu` — et c'est exactement le raisonnement que R-516 refuse.

`x86_64-apple-darwin` est un accident d'outillage, pas une impossibilité : le
runner existe, l'étiquette avait changé. Elle sera validée au prochain passage
de la matrice. En attendant, elle n'est pas déclarée déterministe, et un joueur
sur un Mac Intel jouera en `SNAPSHOT` — ce qui ne lui retire rien (R-514).

**Une conséquence à trancher en M4.** `det_profile` intègre le triplet cible, et
la règle 1 de 5.12bis n'accorde `RECONSTRUCT` qu'à deux empreintes **égales** :
deux plateformes différentes ne s'accordent donc jamais, et un client Windows sur
un serveur Linux serait toujours en `SNAPSHOT`. Le tableau ci-dessus montre
pourtant que ces deux configurations calculent la même chose. La question est
notée dans [`tasks/todo.md`](../../tasks/todo.md) ; elle demande un ADR, pas une
initiative.

## Tenir ce fichier à jour

Il n'est pas engendré : il se met à jour à la main, et c'est voulu — R-516 fait
de l'archivage un acte, pas un effet de bord.

**Ajouter une configuration.** La compiler, rejouer les vecteurs d'or dessus,
reporter ici l'empreinte et le résultat, puis seulement passer son `validated` à
`true` dans `VALIDATION_MATRIX`. Dans cet ordre : le drapeau reflète le tableau,
jamais l'inverse.

**Incrémenter `DET_KERNEL_VERSION`.** Les vecteurs d'or changent de fichier
(`kernel-v<N>.txt`), donc toutes les lignes de ce tableau perdent leur valeur :
elles attestaient d'un fichier qui n'est plus celui que le noyau produit. Repasser
chaque `validated` à `false`, rejouer, puis réarchiver.

**Changer de version majeure de `rustc`.** Même conséquence : 5.12bis attache la
matrice à la version exacte de la chaîne.
