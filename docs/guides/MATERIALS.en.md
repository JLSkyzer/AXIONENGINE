# Physical materials

A physical material (DM-07, PART 11) carries the material properties of a surface: its contact, its mass,
its stiffness, its fracture thresholds, its behaviour, its surface. It is the only place where they are
declared (§11.1): the engine knows no material by name, and AXION's library is content it registers like an
addon (R-1030, ADR-162). The format is the one from [ADR-145](../decisions/ADR-145.md).

## Declaring a material

`axion/physics_materials/<path>.json`, in the folder a mod registers, declares `<modId>:<path>`. All keys are
optional; they are the names of the DM-07 fields, plus `parent`:

```json
{
  "parent": "axion:steel",
  "density": 7800,
  "friction": 0.4,
  "flags": ["ductile"]
}
```

A missing field is taken from the closest ancestor that declares it (R-1031); what no ancestor
declares takes the value of the engine's **generic steel** — the value of a material that no file
declares (R-1032). Without `hardness_pressure` in the chain, the value is `3 × yield_strength`
(Tabor's relation, R-1038), and the log says so once per material.

| Group | Fields | Unit, range |
|---|---|---|
| Contact | `friction`, `restitution`, `rolling_friction` | [0, 2], [0, 1], ≥ 0 |
| | `friction_combine`, `restitution_combine` | `average`, `min`, `max`, `multiply` |
| Mass | `density` | kg/m³, > 0 |
| Stiffness | `stiffness`, `hardness_pressure`, `deformation_resistance` | Pa > 0; Pa [1e5, 1e11]; [0.01, 100] |
| Thresholds | `yield_strength`, `fracture_strength`, `max_strain`, `fracture_energy` | Pa > 0; Pa > 0; [0, 1]; J/m² > 0 |
| Geometry | `indenter_radius_ref`, `spread_factor`, `thickness_spread` | m > 0; [0.5, 8]; [0, 20] |
| Behaviour | `elasticity`, `plasticity`, `damping` | [0, 1], [0, 1], ≥ 0 |
| Damage | `damage_multiplier`, `energy_absorption` | ≥ 0, [0, 1] |
| Surface | `hardness`, `scratch_resistance`, `burn_threshold`, `soil_rate` | [0, 1], [0, 1], J ≥ 0, ≥ 0 |
| Flags | `flags` | `brittle`, `ductile`, `soft`, `rigid`, `flammable`, `non_deformable` |
| Effects | `sound_group`, `particle_group` | `<ns>:<path>` identifiers |

An unknown key, a value out of its range, an unknown parent or circular inheritance rejects the
material, by name, in the log (R-580) — never a silent clamp.

## Mapping a block to a material

`axion/block_materials/<path>.json`, in the folder a mod registers, in the format of §11.4:

```json
{
  "schema": 1,
  "default": "axion:stone",
  "by_tag": { "minecraft:logs": "axion:wood" },
  "by_block": { "minecraft:ice": "axion:ice" }
}
```

A block takes its `by_block` entry, otherwise the first `by_tag` entry whose tag it has — in the
order the file declares them: from the most specific to the most general —, otherwise `default`
(R-1040). Several files are merged in the order of their identifiers; an entry from a later file
replaces the earlier one, and the log says so. Each collision tile of the world takes the contact of
its dominant material (R-643).

## On contact

Each collider of an assembly takes the material of its **part** — the one whose `root_node` carries it;
with no part, or with a part that names none, generic steel (ADR-149). Each world tile takes the material
of its dominant block. When two colliders touch:

- the pair's **friction** and **restitution** combine by each one's mode; if the two modes differ,
  **the stronger one wins**: `max` > `multiply` > `min` > `average`. Ice set to `min` stays slippery under
  anything that does not demand `max`; a ball set to `max` on restitution bounces on any ground;
- **rolling friction** slows the rotation of a rolling body, without ever reversing it; it combines
  like friction. Spinning in place around the contact normal is not rolling.

A reload that changes a material reaches bodies and tiles already in place. The other groups are used by
deformation, fracture and wear, with their components (M6 and later).

## AXION's library

Eighteen materials, in `content/examples/axion/physics_materials/` (under `axion/` at the root of the JAR): the
eleven of R-1030 and seven reference ones for Minecraft blocks. Their values are of three kinds:

- **sourced** — measured and published, reference below;
- **derived** — calculated by AXION from sourced values, the formula is given;
- **by design** — with no measurable physical equivalent, chosen and stated as such. Friction and
  restitution are among them: they can only be measured for a pair of surfaces, and the model combines
  them per pair (ADR-145, clarification). Those of the blocks reuse the tile values from before ADR-145.
  Elasticity and plasticity follow the terms of table 11.3 — "low" 0.2, "medium" 0.5, "high" 0.8,
  "≈ 1" 0.95, "≈ 0" 0.

Two conventions, for lack of anything better: with no distinct yield strength (brittle, hyperelastic
or fibre material), `yield_strength` equals `fracture_strength` — the material breaks without
yielding; `max_strain` is capped at 1 by DM-07, and a material that stretches further (rubber,
polypropylene) is brought back to it.

| Material | Grade | Density (kg/m³) | Modulus (Pa) | Yield (Pa) | Fracture (Pa) | Elong. | Energy (J/m²) |
|---|---|---|---|---|---|---|---|
| `steel` | ASTM A36 steel | 7850 [1] | 2.0e11 [1] | 2.5e8 [1] | 4.0e8 [1] tensile | 0.20 [1] | 9.3e4 derived [2] |
| `aluminium` | 6061-T6 | 2700 [3] | 6.89e10 [3] | 2.76e8 [3] | 3.10e8 [3] tensile | 0.12 [3] | 1.22e4 derived [3] |
| `plastic` | bumper PP copolymer | 910 [4] | 8.83e8 [4] flexural | 2.0e7 [4] | 4.5e7 [2] tensile | 1 (> 2 [5]) | 1.0e4 derived [2] |
| `glass` | annealed soda-lime glass | 2500 [6] | 7.2e10 [6] | = fracture | 4.1e7 [7] tensile | 5.8e-4 derived | 7.9 derived [7] |
| `rubber` | unfilled natural rubber | 930 [8] | 1.33e6 derived [10] | = fracture | 2.16e7 [9] | 1 (7.11 [9]) | 1.2e4 [10] |
| `wood` | loblolly pine, 12%, along the grain | 571 derived [11] | 1.23e10 [11] flexural | = fracture | 8.0e7 [11] tensile, green | 0.0065 derived | — |
| `stone` | granite | 2600 [2] | 6.6e10 [2] | = fracture | 1.7e8 [12] compressive | 0.001 [2] | 34 derived [2] |
| `fabric` | cotton fibre | 1540 [13] fibre | 9.0e9 [13] mid-range | = fracture | 4.4e8 [13] mid-range | 0.075 [13] | — |
| `flesh` | skeletal muscle | 1050 [14] | 7.0e3 [15] indentation | — | — | — | — |
| `concrete` | C25/30 concrete | 2450 derived [16] | 3.1e10 [17] | = fracture | 2.6e6 [17] tensile | 8.4e-5 derived | 115 [18] (40 MPa concrete) |
| `carbon_fiber` | unidirectional T700S/epoxy | 1580 [19] | 1.34e11 [20] | = fracture | 2.86e9 [20] 0° | 0.0202 [20] | 1.1e4 derived [2][20] |
| `ice` | water ice, around −10 °C | 916.7 [21] | 9.5e9 [22] | = fracture | 2.0e6 [23] tensile | 2.1e-4 derived | 4.2 derived [24] |
| `rope` | polyamide 6 fibre | 1140 [25] | 8.0e9 [25] | = fracture | 9.6e8 [25] | 0.20 [25] | — |
| `sand` | dry sand, bulk | 1556 [26] | — | — | — | — | — |
| `dirt` | soil, dry bulk | 1450 [27] | — | — | — | — | — |
| `blue_ice` | inherits from `ice` | | | | | | |
| `slime` | inherits from `rubber` (game block) | | | | | | |
| `honey` | game block, contact only | | | | | | |

Derived fracture energies come from fracture toughness, `Gc = K_IC² / E` (plane stress);
derived strains at fracture, from `σ_fracture / E`. Block contact (friction,
restitution), by design: ice 0.05, blue ice 0.02, stone 0.6, wood 0.7, wool (`fabric`)
0.9, sand 0.7, dirt 0.65, slime 0.8 with 0.8 bounce, honey 1.4.

**Known gaps**, to fill before M6 uses them: a dash in the table marks a quantity that no reliable
source has given; the material then inherits it from generic steel. This covers: the fracture energy
of wood, cotton and rope; the yield strength, fracture strength, elongation and fracture energy of
flesh; the mechanics of sand and dirt, which are granular materials. The engine's generic steel reuses
the sourced values of `steel` (see `GenericSteel.java`).

### Sources

Accessed on 2026-10-16.

1. AZoM, "ASTM A36 Mild/Low Carbon Steel" — https://www.azom.com/article.aspx?ArticleID=6117
2. Roylance, *Mechanics of Materials*, appendix 7.1, material properties (MIT, LibreTexts) — https://eng.libretexts.org/Bookshelves/Mechanical_Engineering/Mechanics_of_Materials_(Roylance)/07%3A_Appendices/7.01%3A_Material_Properties — fracture toughness of an AISI 1020 for steel; generic PP, granite and CFRP
3. ASM/MatWeb data sheet for 6061-T6 aluminium, reproduced by Quickparts — https://quickparts.com/wp-content/uploads/2024/05/Aluminum-6061.pdf
4. SCG Chemicals, PP P765J (bumpers) — https://www.scgchemicals.com/en/products-grade/p765j
5. LyondellBasell, Hifax TKC 461X — https://lyondellbasell.com/en/polymers/p/Hifax%20TKC%20461X%20NA%20BLACK/1b885114-45c2-49a5-9ed6-44ab6408f4f7
6. Continental Trade, soda-lime glass properties — https://www.continentaltrade.com.pl/en/download/WhfvdEYg1jSqsBXM5x9dRJnJfIPvp1gO/soda-lime-glass-properties.pdf
7. MakeItFrom, "Annealed Soda-Lime Glass" — https://www.makeitfrom.com/material-properties/Annealed-Soda-Lime-Glass
8. C. Janin, *Techniques de l'Ingénieur* AM8015, natural rubber — https://www.techniques-ingenieur.fr/en/resources/article/ti100/natural-rubber-am8015/natural-rubber-properties-5
9. *Scientific Reports* 6:22810 (2016), table 2, NR control — https://pmc.ncbi.nlm.nih.gov/articles/PMC4780003/
10. Rivlin and Thomas, *J. Polym. Sci.* 10:291 (1953) — https://www.imechanica.org/sites/default/files/1953%20Rupture%20of%20rubber%20characteristic%20energy%20for%20tearing.pdf — modulus derived from C1, C2 (E ≈ 6 (C1 + C2), incompressible)
11. *Wood Handbook* FPL-GTR-190, chapter 5, tables 5-3a, 5-7, 5-10 — https://research.fs.usda.gov/download/treesearch/37427.pdf — density derived from the specific gravity of 0.51
12. Davarpanah et al., *Studia Geotechnica et Mechanica* 41 (2019), Mórágy granite — https://dbc.wroc.pl//Content/109987/sgem_2019_1_03.pdf
13. Célino et al., *Frontiers in Chemistry* 2014, table 1 — https://pmc.ncbi.nlm.nih.gov/articles/PMC3982556/
14. NIST, X-ray attenuation coefficients, table 2 (ICRU-44) — https://pml.nist.gov/PhysRefData/XrayMassCoef/tab2.html
15. McKee et al., *Tissue Engineering B* 2011 — https://pmc.ncbi.nlm.nih.gov/articles/PMC3099446/
16. EN 1991-1-1, annex A, cited by *New Steel Construction* (2009) — https://www.newsteelconstruction.com/wp/wp-json/wp/v2/posts/6057 — 24 kN/m³, converted with g = 9.81
17. EN 1992-1-1, table 3.1, JRC workshop slides (Walraven, 2008) — https://www.eurocodes.fi/wp-content/uploads/1992/sahkoinen1992/EN-1992.pdf
18. Siregar, *IJTech* 2017, table 1 — https://ijtech.eng.ui.ac.id/download/article/587
19. Hexcel, HexPly 8552 (AS4/8552 laminate) — https://hexcel.com/wp-content/uploads/2025/12/HexPly_8552_eu_DataSheet1.pdf
20. Toray, T700S data sheet — https://www.toraycma.com/wp-content/uploads/T700S-Data-Sheet.pdf
21. IAPWS R10-06(2009), table 6 — https://iapws.org/documents/release/Ice-2009.download
22. Elvin, *Mechanics of Materials* 22 (1996) — https://www.bsee.gov/sites/bsee.gov/files/tap-technical-assessment-program/196ab.pdf
23. Gold, *Journal of Glaciology* 19(81), 1977 — https://www.cambridge.org/core/services/aop-cambridge-core/content/view/E52D1E4F1D36E3EF10B49A40A3BC37CC
24. Liu and Miller, *Journal of Glaciology* 22(86), 1979 — https://www.cambridge.org/core/services/aop-cambridge-core/content/view/058A5E818BC536BAF359F9FEDE823D5C/S0022143000014118a.pdf/fracture-toughness-of-fresh-water-ice.pdf
25. Weller, EUROMECH 607 (2019), based on McKenna et al., *Handbook of Fibre Rope Technology* (2004) — https://rd-technologiques.ifremer.fr/content/download/136107/file/Session%204%20Weller.pdf
26. ELE International, "Test Sand #20-30" (ASTM C778) — https://www.ele.com/Datasheet/test-sand-/test-sand-_20-30
27. Oklahoma State University, PSS-2287 (2024) — https://extension.okstate.edu/fact-sheets/basics-of-soil-bulk-density.html
