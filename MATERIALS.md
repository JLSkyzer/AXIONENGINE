# Matériaux physiques

Un matériau physique (DM-07, PARTIE 11) porte les propriétés de matière d'une surface : son
contact, sa masse, sa rigidité, ses seuils de rupture, son comportement, sa surface. C'est le seul
endroit où elles se déclarent (§11.1) : le moteur ne connaît aucun nom de matériau, la bibliothèque
d'AXION est un datapack comme un autre (R-1030). Le format est celui d'[ADR-145](docs/decisions/ADR-145.md).

## Déclarer un matériau

`data/<ns>/axion/physics_materials/<chemin>.json` déclare `<ns>:<chemin>`. Toutes les clés sont
facultatives ; ce sont les noms des champs de DM-07, plus `parent` :

```json
{
  "parent": "axion:steel",
  "density": 7800,
  "friction": 0.4,
  "flags": ["ductile"]
}
```

Un champ absent se prend à l'ancêtre le plus proche qui le déclare (R-1031) ; ce qu'aucun ancêtre
ne déclare prend la valeur de l'**acier générique** du moteur — celle d'un matériau qu'aucun fichier
ne déclare (R-1032). Sans `hardness_pressure` dans la chaîne, la valeur est `3 × yield_strength`
(relation de Tabor, R-1038), et le journal le dit une fois par matériau.

| Groupe | Champs | Unité, plage |
|---|---|---|
| Contact | `friction`, `restitution`, `rolling_friction` | [0, 2], [0, 1], ≥ 0 |
| | `friction_combine`, `restitution_combine` | `average`, `min`, `max`, `multiply` |
| Masse | `density` | kg/m³, > 0 |
| Rigidité | `stiffness`, `hardness_pressure`, `deformation_resistance` | Pa > 0 ; Pa [1e5, 1e11] ; [0,01, 100] |
| Seuils | `yield_strength`, `fracture_strength`, `max_strain`, `fracture_energy` | Pa > 0 ; Pa > 0 ; [0, 1] ; J/m² > 0 |
| Géométrie | `indenter_radius_ref`, `spread_factor`, `thickness_spread` | m > 0 ; [0,5, 8] ; [0, 20] |
| Comportement | `elasticity`, `plasticity`, `damping` | [0, 1], [0, 1], ≥ 0 |
| Dommage | `damage_multiplier`, `energy_absorption` | ≥ 0, [0, 1] |
| Surface | `hardness`, `scratch_resistance`, `burn_threshold`, `soil_rate` | [0, 1], [0, 1], J ≥ 0, ≥ 0 |
| Drapeaux | `flags` | `brittle`, `ductile`, `soft`, `rigid`, `flammable`, `non_deformable` |
| Effets | `sound_group`, `particle_group` | identifiants `<ns>:<chemin>` |

Une clé inconnue, une valeur hors de sa plage, un parent inconnu ou un héritage circulaire refusent
le matériau, nommé, au journal (R-580) — jamais un bornage silencieux.

## Associer un bloc à un matériau

`data/<ns>/axion/block_materials/<chemin>.json`, au format du §11.4 :

```json
{
  "schema": 1,
  "default": "axion:stone",
  "by_tag": { "minecraft:logs": "axion:wood" },
  "by_block": { "minecraft:ice": "axion:ice" }
}
```

Un bloc prend son entrée `by_block`, sinon la première entrée `by_tag` dont il porte l'étiquette —
dans l'ordre où le fichier les déclare : de la plus précise à la plus générale —, sinon `default`
(R-1040). Plusieurs fichiers se fusionnent dans l'ordre de leurs identifiants ; une entrée d'un
fichier plus tardif remplace la précédente, et le journal le dit. Chaque tuile de collision du
monde prend le contact de son matériau dominant (R-643).

## Au contact

Chaque collider d'une assembly prend le matériau de sa **part** — celle dont le `root_node` le porte ;
sans part, ou d'une part qui n'en nomme pas, l'acier générique (ADR-149). Chaque tuile du monde prend
celui de son bloc dominant. Quand deux colliders se touchent :

- le **frottement** et la **restitution** de la paire se combinent par le mode de chacun ; si les deux
  modes diffèrent, **le plus fort l'emporte** : `max` > `multiply` > `min` > `average`. Une glace en
  `min` reste glissante sous tout ce qui n'exige pas `max` ; une balle en `max` sur la restitution
  rebondit sur n'importe quel sol ;
- le **frottement de roulement** freine la rotation d'un corps qui roule, sans jamais l'inverser ; il
  se combine comme le frottement. Tourner sur place autour de la normale du contact n'est pas rouler.

Un rechargement qui change un matériau atteint les corps et les tuiles déjà posés. Les autres groupes
servent à la déformation, à la rupture et à l'usure, avec leurs composants (M6 et suivants).

## La bibliothèque d'AXION

Dix-huit matériaux, dans `data/axion/axion/physics_materials/` : les onze de R-1030 et sept de
référence pour les blocs de Minecraft. Les valeurs y sont de trois natures :

- **sourcées** — mesurées et publiées, référence ci-dessous ;
- **dérivées** — calculées par AXION depuis des valeurs sourcées, la formule est dite ;
- **de conception** — sans équivalent physique mesurable, choisies et dites comme telles. Le
  frottement et la restitution en sont : ils ne se mesurent que pour un couple de surfaces, et le
  modèle les combine par paire (ADR-145, précision). Ceux des blocs reprennent les valeurs des tuiles
  d'avant ADR-145. L'élasticité et la plasticité suivent les termes du tableau 11.3 — « faible »
  0,2, « moyenne » 0,5, « élevée » 0,8, « ≈ 1 » 0,95, « ≈ 0 » 0.

Deux conventions, faute de mieux : sans limite d'élasticité distincte (matière fragile,
hyperélastique ou fibre), `yield_strength` vaut `fracture_strength` — la matière rompt sans
plastifier ; `max_strain` est borné à 1 par DM-07, et une matière qui s'allonge davantage
(caoutchouc, polypropylène) y est ramenée.

| Matériau | Nuance | Masse vol. (kg/m³) | Module (Pa) | Limite (Pa) | Rupture (Pa) | Allong. | Énergie (J/m²) |
|---|---|---|---|---|---|---|---|
| `steel` | acier ASTM A36 | 7850 [1] | 2,0e11 [1] | 2,5e8 [1] | 4,0e8 [1] traction | 0,20 [1] | 9,3e4 dérivé [2] |
| `aluminium` | 6061-T6 | 2700 [3] | 6,89e10 [3] | 2,76e8 [3] | 3,10e8 [3] traction | 0,12 [3] | 1,22e4 dérivé [3] |
| `plastic` | PP copolymère de pare-chocs | 910 [4] | 8,83e8 [4] flexion | 2,0e7 [4] | 4,5e7 [2] traction | 1 (> 2 [5]) | 1,0e4 dérivé [2] |
| `glass` | verre sodocalcique recuit | 2500 [6] | 7,2e10 [6] | = rupture | 4,1e7 [7] traction | 5,8e-4 dérivé | 7,9 dérivé [7] |
| `rubber` | caoutchouc naturel non chargé | 930 [8] | 1,33e6 dérivé [10] | = rupture | 2,16e7 [9] | 1 (7,11 [9]) | 1,2e4 [10] |
| `wood` | pin à l'encens, 12 %, le long du fil | 571 dérivé [11] | 1,23e10 [11] flexion | = rupture | 8,0e7 [11] traction, vert | 0,0065 dérivé | — |
| `stone` | granite | 2600 [2] | 6,6e10 [2] | = rupture | 1,7e8 [12] compression | 0,001 [2] | 34 dérivé [2] |
| `fabric` | fibre de coton | 1540 [13] fibre | 9,0e9 [13] milieu | = rupture | 4,4e8 [13] milieu | 0,075 [13] | — |
| `flesh` | muscle squelettique | 1050 [14] | 7,0e3 [15] indentation | — | — | — | — |
| `concrete` | béton C25/30 | 2450 dérivé [16] | 3,1e10 [17] | = rupture | 2,6e6 [17] traction | 8,4e-5 dérivé | 115 [18] (béton à 40 MPa) |
| `carbon_fiber` | T700S/époxy unidirectionnel | 1580 [19] | 1,34e11 [20] | = rupture | 2,86e9 [20] 0° | 0,0202 [20] | 1,1e4 dérivé [2][20] |
| `ice` | glace d'eau, vers −10 °C | 916,7 [21] | 9,5e9 [22] | = rupture | 2,0e6 [23] traction | 2,1e-4 dérivé | 4,2 dérivé [24] |
| `rope` | fibre polyamide 6 | 1140 [25] | 8,0e9 [25] | = rupture | 9,6e8 [25] | 0,20 [25] | — |
| `sand` | sable sec, apparente | 1556 [26] | — | — | — | — | — |
| `dirt` | sol, apparente sèche | 1450 [27] | — | — | — | — | — |
| `blue_ice` | hérite de `ice` | | | | | | |
| `slime` | hérite de `rubber` (bloc de jeu) | | | | | | |
| `honey` | bloc de jeu, contact seul | | | | | | |

Les énergies de rupture dérivées le sont de la ténacité, `Gc = K_IC² / E` (contraintes planes) ;
les déformations à la rupture dérivées, de `σ_rupture / E`. Le contact des blocs (frottement,
restitution), de conception : glace 0,05, glace bleue 0,02, pierre 0,6, bois 0,7, laine (`fabric`)
0,9, sable 0,7, terre 0,65, slime 0,8 et 0,8 de rebond, miel 1,4.

**Lacunes connues**, à combler avant que la M6 ne les consomme : un tiret dans le tableau signale
une grandeur qu'aucune source fiable n'a donnée ; le matériau en hérite alors l'acier générique.
Sont concernés : l'énergie de rupture du bois, du coton et de la corde ; la limite, la rupture,
l'allongement et l'énergie de rupture de la chair ; la mécanique du sable et de la terre, matières
granulaires. L'acier générique du moteur reprend les valeurs sourcées de `steel` (voir
`GenericSteel.java`).

### Sources

Consultées le 2026-10-16.

1. AZoM, « ASTM A36 Mild/Low Carbon Steel » — https://www.azom.com/article.aspx?ArticleID=6117
2. Roylance, *Mechanics of Materials*, annexe 7.1, propriétés des matériaux (MIT, LibreTexts) — https://eng.libretexts.org/Bookshelves/Mechanical_Engineering/Mechanics_of_Materials_(Roylance)/07%3A_Appendices/7.01%3A_Material_Properties — ténacité d'un AISI 1020 pour l'acier ; PP, granite et CFRP génériques
3. Fiche ASM/MatWeb de l'aluminium 6061-T6, reproduite par Quickparts — https://quickparts.com/wp-content/uploads/2024/05/Aluminum-6061.pdf
4. SCG Chemicals, PP P765J (pare-chocs) — https://www.scgchemicals.com/en/products-grade/p765j
5. LyondellBasell, Hifax TKC 461X — https://lyondellbasell.com/en/polymers/p/Hifax%20TKC%20461X%20NA%20BLACK/1b885114-45c2-49a5-9ed6-44ab6408f4f7
6. Continental Trade, propriétés du verre sodocalcique — https://www.continentaltrade.com.pl/en/download/WhfvdEYg1jSqsBXM5x9dRJnJfIPvp1gO/soda-lime-glass-properties.pdf
7. MakeItFrom, « Annealed Soda-Lime Glass » — https://www.makeitfrom.com/material-properties/Annealed-Soda-Lime-Glass
8. C. Janin, *Techniques de l'Ingénieur* AM8015, caoutchouc naturel — https://www.techniques-ingenieur.fr/en/resources/article/ti100/natural-rubber-am8015/natural-rubber-properties-5
9. *Scientific Reports* 6:22810 (2016), tableau 2, témoin NR — https://pmc.ncbi.nlm.nih.gov/articles/PMC4780003/
10. Rivlin et Thomas, *J. Polym. Sci.* 10:291 (1953) — https://www.imechanica.org/sites/default/files/1953%20Rupture%20of%20rubber%20characteristic%20energy%20for%20tearing.pdf — module dérivé de C1, C2 (E ≈ 6 (C1 + C2), incompressible)
11. *Wood Handbook* FPL-GTR-190, chapitre 5, tableaux 5-3a, 5-7, 5-10 — https://research.fs.usda.gov/download/treesearch/37427.pdf — masse volumique dérivée de la densité relative 0,51
12. Davarpanah et al., *Studia Geotechnica et Mechanica* 41 (2019), granite de Mórágy — https://dbc.wroc.pl//Content/109987/sgem_2019_1_03.pdf
13. Célino et al., *Frontiers in Chemistry* 2014, tableau 1 — https://pmc.ncbi.nlm.nih.gov/articles/PMC3982556/
14. NIST, coefficients d'atténuation X, tableau 2 (ICRU-44) — https://pml.nist.gov/PhysRefData/XrayMassCoef/tab2.html
15. McKee et al., *Tissue Engineering B* 2011 — https://pmc.ncbi.nlm.nih.gov/articles/PMC3099446/
16. EN 1991-1-1, annexe A, citée par *New Steel Construction* (2009) — https://www.newsteelconstruction.com/wp/wp-json/wp/v2/posts/6057 — 24 kN/m³, converti avec g = 9,81
17. EN 1992-1-1, tableau 3.1, supports d'atelier JRC (Walraven, 2008) — https://www.eurocodes.fi/wp-content/uploads/1992/sahkoinen1992/EN-1992.pdf
18. Siregar, *IJTech* 2017, tableau 1 — https://ijtech.eng.ui.ac.id/download/article/587
19. Hexcel, HexPly 8552 (stratifié AS4/8552) — https://hexcel.com/wp-content/uploads/2025/12/HexPly_8552_eu_DataSheet1.pdf
20. Toray, fiche T700S — https://www.toraycma.com/wp-content/uploads/T700S-Data-Sheet.pdf
21. IAPWS R10-06(2009), tableau 6 — https://iapws.org/documents/release/Ice-2009.download
22. Elvin, *Mechanics of Materials* 22 (1996) — https://www.bsee.gov/sites/bsee.gov/files/tap-technical-assessment-program/196ab.pdf
23. Gold, *Journal of Glaciology* 19(81), 1977 — https://www.cambridge.org/core/services/aop-cambridge-core/content/view/E52D1E4F1D36E3EF10B49A40A3BC37CC
24. Liu et Miller, *Journal of Glaciology* 22(86), 1979 — https://www.cambridge.org/core/services/aop-cambridge-core/content/view/058A5E818BC536BAF359F9FEDE823D5C/S0022143000014118a.pdf/fracture-toughness-of-fresh-water-ice.pdf
25. Weller, EUROMECH 607 (2019), d'après McKenna et al., *Handbook of Fibre Rope Technology* (2004) — https://rd-technologiques.ifremer.fr/content/download/136107/file/Session%204%20Weller.pdf
26. ELE International, « Test Sand #20-30 » (ASTM C778) — https://www.ele.com/Datasheet/test-sand-/test-sand-_20-30
27. Oklahoma State University, PSS-2287 (2024) — https://extension.okstate.edu/fact-sheets/basics-of-soil-bulk-density.html
