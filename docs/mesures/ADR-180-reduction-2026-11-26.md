# ADR-180 — le coût du sol réduit (2026-11-26)

Suite de [la mesure du 2026-11-25](ADR-180-sol-2026-11-25.md), à la demande de Killian : profiler, puis réduire.

Rejouer le profil : `python tools/campagne/mesure_sol.py --etiquette <nom> --profil` (JFR, un échantillon par
milliseconde, `tools/campagne/profil_sol.jfc`). Banc natif : `cargo test -p ax-physics --test vehicles --release --
--ignored --nocapture adr180_cout`.

## Le profil, avant réduction

2 875 échantillons pris dans la construction d'une tuile (code du commit `7bb3926a`), parts arrondies :

| part | ce qui la prenait |
|---|---|
| 39 % | compter les blocs sous leur nom en texte, calculé à chaque case |
| 16 % | chercher le bloc couvrant : lire le bloc du dessus et recalculer son nom, à chaque bloc plein |
| 10 % | compter les blocs de chaque matériau |
| 8 % | la fusion des boîtes, rejouée une fois par matériau |
| 6 % | la fusion de l'eau, même sans eau |

## Ce qui a changé

- Chaque état de bloc se résout une fois par tuile (son contact, s'il couvre) ; les noms en texte ne se calculent qu'à la
  fin, une fois par bloc.
- Le bloc du dessus se lit dans la section déjà lue, et « couvrant » se demande une fois par état.
- La fusion par matériau tient en un balayage, qui rend les mêmes boîtes dans le même ordre
  (`WorldTileGeometryTest.fusionParMateriauEgaleChaqueMateriauSeul`, 200 grilles tirées au sort).
- L'eau n'est fusionnée que s'il y en a.

**Les tuiles sont identiques au bit près** : l'ancienne et la nouvelle lecture, rejouées dans le même serveur sur les
1 296 sections des trois catégories (271 avec de l'eau), n'ont donné aucune différence de boîtes, de matériaux, de
palette, de rangs ni de fluides.

## Mesure au calme

- Même machine que la veille (AMD Ryzen 7 5800X, 32 Go, Windows 11), de 00:42 à 01:12, aucun autre Minecraft ni Gradle
  (accord entre sessions), `java.exe` absent au début.
- **Avant** : `76250c51`, un matériau par tuile (avant ADR-180). **Après** : ADR-180 réduit. Passes alternées, trois par
  version, mêmes réglages que la veille.

### Construction des tuiles (ms par section, étendue des trois passes)

| catégorie | avant ADR-180 | ADR-180 non réduit (25/11) | ADR-180 réduit |
|---|---|---|---|
| plaine, moyenne | 0,353 – 0,370 | 0,588 – 0,591 | **0,250 – 0,264** |
| plaine, p95 | 0,474 – 0,504 | 0,829 – 0,833 | **0,326 – 0,340** |
| mine, moyenne | 0,452 – 0,473 | 0,745 – 0,751 | **0,292 – 0,308** |
| mine, p95 | 0,493 – 0,515 | 0,839 – 0,846 | **0,339 – 0,353** |
| ville, moyenne | 0,176 – 0,259 | 0,229 – 0,231 | **0,129 – 0,136** |

Moyennes des passes, contre avant ADR-180 le même soir : plaine −29 %, mine −35 %. La ville (16 sections) est bruitée :
sa version d'avant donnait 0,157 à 0,161 la veille. La colonne du 25/11 vient d'une autre campagne, sur la même machine.

### Pas de simulation, terrain naturel (32 véhicules)

| version | pas natif moyen (ms), trois passes | colliders | CPU du thread serveur (ms/tick) |
|---|---|---|---|
| avant | 1,41 – 1,75 (moyenne 1,55) | 1 127 – 1 455 | 5,9 – 6,5 |
| réduit | 1,41 – 1,63 (moyenne 1,51) | 2 083 – 2 364 | 4,5 – 6,3 |

### Banc de CAP-53, monde plat

| véhicules | version | pas natif moyen (ms), trois passes | p95 (ms) | colliders |
|---|---|---|---|---|
| 32 | avant | 0,97 / 1,10 / 1,00 | 1,21 – 1,43 | 219 – 221 |
| 32 | réduit | 1,15 / 1,06 / 1,08 | 1,31 – 1,48 | 374 – 379 |
| 128 | avant | 3,61 / 4,05 / 4,02 | 3,98 – 5,23 | 547 – 578 |
| 128 | réduit | 4,42 / 3,80 / 3,92 | 4,49 – 5,60 | 841 – 852 |

20 TPS partout ; gouverneur au palier 3 à 128 véhicules dans les deux versions.

Le code natif n'a pas changé depuis la campagne de la veille : avec ses deux passes, soit cinq par version, la hausse sur
monde plat est de **14 % à 32 véhicules** (1,01 → 1,14 ms) et **11 % à 128** (3,86 → 4,27 ms), contre 20 % lus sur deux
passes. Les étendues se recouvrent.

## Le banc natif isolé ne reproduit pas la hausse

128 véhicules sur un sol plat de sections, au natif seul (sept passes alternées, médianes, lancées pendant qu'un autre
essai occupait la machine) : une boîte par section 0,592 ms par pas, trois colliders par section 0,608 ms, un composé de
trois boîtes 0,645 ms. Un châssis abaissé près du sol ne change rien. La cause de l'écart en jeu n'est donc pas établie ;
réunir les matériaux d'une tuile dans un seul collider ne la réduirait pas d'après ce banc, et n'a pas été fait.

## Ce que la mesure ne dit pas

- Une machine, une nuit ; trois passes par version.
- La réduction n'a pas été profilée à son tour : la répartition du temps restant n'est pas connue.
- Aucun client : la prédiction construit ses tuiles avec le même code, son gain n'est pas mesuré.
