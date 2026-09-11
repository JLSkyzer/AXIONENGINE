//! Engendre les vecteurs d'or du noyau déterministe (C-16, R-513).
//!
//! ```text
//! cargo run -p ax-det --example generate_golden
//! ```
//!
//! Le fichier produit est un **artefact versionné**, pas une sortie de build :
//! il est commité, relu en revue, et il ne change que lorsque quelqu'un décide
//! que le noyau doit calculer autre chose. C'est pourquoi cette génération est
//! une commande à part et non une étape des tests — un fichier que les tests
//! réécriraient d'eux-mêmes ne constaterait jamais rien.
//!
//! # Ce que le générateur choisit
//!
//! Les **entrées**, et elles seules. Les sorties viennent du noyau, par le même
//! chemin que le rejeu (`tests/support/cases.rs`).
//!
//! Les entrées ne sont pas tirées uniformément : un tirage uniforme sur `[-1, 1]`
//! ne rencontre jamais un subnormal, jamais un infini, jamais le demi-pas exact
//! où l'arrondi bascule — c'est-à-dire aucun des endroits où une divergence se
//! cache. Chaque opération reçoit donc une **tête systématique**, qui énumère
//! les valeurs remarquables et balaie son domaine de travail, puis une **queue
//! aléatoire** qui couvre le reste.

#[path = "../tests/support/cases.rs"]
mod cases;

use std::fmt::Write as _;
use std::path::PathBuf;

/// Nombre total de cas, imposé par R-513.
const TOTAL: usize = 10_000;

/// Source d'entrées du générateur — **indépendante du noyau**.
///
/// Un xorshift écrit ici en toutes lettres, et non [`ax_det::DetRng`]. La
/// raison n'est pas la défiance : c'est que les entrées doivent rester les mêmes
/// d'une version du noyau à l'autre. Si elles venaient du générateur qu'elles
/// servent à figer, régénérer après une modification de celui-ci changerait
/// **toutes** les lignes du fichier, et la différence entre deux versions
/// cesserait de se lire. Avec une source indépendante, cette différence est
/// exactement la liste des sorties qui ont bougé.
struct Source(u64);

impl Source {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// Tire un flottant selon un mélange de formes.
    ///
    /// Les quatre parts ne se valent pas et c'est voulu : le domaine ordinaire
    /// vérifie que le noyau calcule juste, le motif de bits quelconque vérifie
    /// qu'il ne s'effondre pas, et le voisinage du demi-pas vérifie qu'il
    /// tranche toujours du même côté.
    fn next_f32(&mut self) -> f32 {
        let draw = self.next();
        match draw & 3 {
            // Le domaine ordinaire d'un champ de déformation.
            0 => ((draw >> 8) as u32 as f32 / u32::MAX as f32) * 4.0 - 2.0,
            // Le domaine normalisé, celui de `falloff` et `smooth01`.
            1 => (draw >> 40) as u32 as f32 / 16_777_216.0,
            // Un motif de bits quelconque : exposants extrêmes, subnormaux,
            // infinis et NaN compris. Aucun tirage uniforme n'atteint ces
            // magnitudes, et le noyau doit pourtant y répondre.
            2 => f32::from_bits((draw >> 16) as u32),
            // Le voisinage immédiat d'un demi-pas, à un cran près : c'est
            // l'ULP qui décide de l'arrondi, pas la valeur.
            _ => {
                let k = ((draw >> 8) % 261) as i32 - 130;
                let base = k as f32 + 0.5;
                match (draw >> 40) & 3 {
                    0 => base,
                    1 => f32::from_bits(base.to_bits().wrapping_add(1)),
                    2 => f32::from_bits(base.to_bits().wrapping_sub(1)),
                    _ => k as f32,
                }
            }
        }
    }
}

/// Valeurs flottantes remarquables — celles où une divergence se cache.
///
/// Écrites par leurs bits : `0,1` n'a pas d'écriture décimale exacte, un zéro
/// négatif se confond avec un zéro positif, et un `NaN` n'a pas d'écriture du
/// tout. Les bits n'ont aucun de ces défauts.
const REMARQUABLES: &[u32] = &[
    0x0000_0000, // +0
    0x8000_0000, // -0
    0x0000_0001, // plus petit subnormal
    0x007F_FFFF, // plus grand subnormal
    0x0080_0000, // plus petit normal
    0x8080_0000, // son opposé
    0x3F80_0000, // 1
    0xBF80_0000, // -1
    0x3F00_0000, // 0,5
    0xBF00_0000, // -0,5
    0x3F7F_FFFF, // juste sous 1
    0x3F80_0001, // juste au-dessus de 1
    0x4000_0000, // 2
    0xC000_0000, // -2
    0x4048_0000, // 3
    0x3DCC_CCCD, // 0,1 au plus près
    0x3E99_999A, // 0,3 au plus près
    0x3F2A_AAAB, // 2/3 au plus près
    0x7F7F_FFFF, // f32::MAX
    0xFF7F_FFFF, // f32::MIN
    0x7F80_0000, // +∞
    0xFF80_0000, // -∞
    0x7FC0_0000, // NaN silencieux
    0xFFC0_0000, // NaN silencieux négatif
    0x7F80_0001, // NaN signalant
];

/// Bornes remarquables, pour le produit croisé de `clamp`.
///
/// Sept suffisent à produire les quarante-neuf paires qui comptent : bornes
/// égales, bornes inversées, borne non finie de chaque côté, et l'intervalle
/// le plus large que `f32` admette.
const BORNES: &[u32] = &[
    0x0000_0000, // 0
    0x3F80_0000, // 1
    0xBF80_0000, // -1
    0x7F7F_FFFF, // f32::MAX
    0x0080_0000, // plus petit normal
    0x7FC0_0000, // NaN
    0x7F80_0000, // +∞
];

/// Pas de quantification, du plausible au pathologique.
const PAS: &[f32] = &[
    0.001,
    0.01,
    0.1,
    0.5,
    1.0,
    2.0,
    10.0,
    f32::MIN_POSITIVE,
    1.0e-45,
    f32::MAX,
    0.0,
    -1.0,
    f32::NAN,
    f32::INFINITY,
];

/// Pas effectivement utilisables, pour le balayage des demi-pas.
const PAS_UTILISABLES: &[f32] = &[0.001, 0.01, 0.1, 0.5, 1.0, 2.0, 10.0, f32::MIN_POSITIVE];

/// Identifiants 64 bits remarquables.
const IDENTIFIANTS: &[u64] = &[
    0,
    1,
    2,
    0xFFFF_FFFF,
    0x1_0000_0000,
    0x8000_0000_0000_0000,
    0x0123_4567_89AB_CDEF,
    u64::MAX - 1,
    u64::MAX,
];

fn main() {
    let mut source = Source(0xA10E_0000_C16C_16C1);
    let mut lignes: Vec<String> = Vec::with_capacity(TOTAL);

    clamp_cases(&mut source, &mut lignes);
    unaire_domaine_normalise("falloff", &mut source, &mut lignes);
    unaire_domaine_normalise("smooth01", &mut source, &mut lignes);
    inv_sqrt_cases(&mut source, &mut lignes);
    quantize_cases(&mut source, &mut lignes);
    dequantize_cases(&mut source, &mut lignes);
    digest_cases(&mut source, &mut lignes);
    mix_cases(&mut source, &mut lignes);
    rng_cases(&mut source, &mut lignes);

    assert_eq!(
        lignes.len(),
        TOTAL,
        "R-513 impose {TOTAL} cas, {} engendrés",
        lignes.len()
    );

    let empreinte = cases::digest_lines(&lignes);
    let chemin = chemin_du_fichier();
    let mut contenu = entete(&empreinte);
    for ligne in &lignes {
        contenu.push_str(ligne);
        contenu.push('\n');
    }

    if let Some(parent) = chemin.parent() {
        std::fs::create_dir_all(parent).expect("création du répertoire des vecteurs d'or");
    }
    std::fs::write(&chemin, contenu).expect("écriture des vecteurs d'or");

    println!("{} cas écrits dans {}", lignes.len(), chemin.display());
    println!("empreinte : 0x{empreinte:016X}");
    println!("engendré sur : {}", ax_det::det_profile_string());
}

/// Chemin du fichier, nommé par la version du noyau qu'il fige.
///
/// Le nom porte la version pour une raison de fond : incrémenter
/// `DET_KERNEL_VERSION` sans régénérer laisse le rejeu sans fichier, donc en
/// échec. Un noyau qui change ne peut pas rester sans vecteurs à jour.
fn chemin_du_fichier() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("golden")
        .join(format!("kernel-v{}.txt", ax_det::DET_KERNEL_VERSION))
}

/// En-tête du fichier : ce qu'il est, comment le lire, et quoi ne pas en faire.
fn entete(empreinte: &u64) -> String {
    let mut texte = String::new();
    let version = ax_det::DET_KERNEL_VERSION;
    let profil = ax_det::det_profile_string();

    texte.push_str(
        "# AXION ENGINE — vecteurs d'or du noyau déterministe.\n\
         #\n\
         # C-16, R-513 : dix mille cas d'entrée-sortie versionnés, rejoués sur chaque\n\
         # configuration de la matrice de validation (5.12bis). Une divergence sur une\n\
         # configuration de la matrice est un défaut BLOQUANT.\n\
         #\n\
         # NE PAS MODIFIER À LA MAIN, et ne pas régénérer pour faire passer un test.\n\
         # Un échec de rejeu dit que le noyau ne calcule plus ce qu'il calculait. Soit\n\
         # c'est un défaut, et il se corrige ; soit le changement est voulu, et\n\
         # DET_KERNEL_VERSION s'incrémente — ce qui fait basculer en SNAPSHOT les paires\n\
         # d'extrémités dépareillées au lieu de les laisser diverger en silence.\n\
         #\n\
         #     cargo run -p ax-det --example generate_golden\n\
         #\n\
         # Format : une ligne par cas, champs séparés par des tabulations.\n\
         #\n\
         #     <opération>  <arguments…>  <résultat>\n\
         #\n\
         # Les flottants et les empreintes s'écrivent par leurs bits, en hexadécimal :\n\
         # une écriture décimale dépendrait du formateur, et ne survivrait ni au zéro\n\
         # négatif ni au NaN. Les nombres qui comptent quelque chose — un nombre de\n\
         # tirages, une borne, un pas quantifié — s'écrivent en décimal.\n\
         #\n\
         #     clamp       v lo hi        → valeur bornée\n\
         #     falloff     x              → (1 - x²)² borné\n\
         #     smooth01    x              → x²(3 - 2x) borné\n\
         #     inv_sqrt    x              → 1/√x\n\
         #     quantize    v pas          → pas quantifié, en décimal\n\
         #     dequantize  q(déc.) pas    → valeur déquantifiée\n\
         #     digest      octets         → empreinte XXH64 (« - » : suite vide)\n\
         #     mix         a b            → mélange non commutatif\n\
         #     rng         état n(déc.)   → empreinte de n tirages de 32 bits\n\
         #     rngf        état n(déc.)   → empreinte de n tirages flottants\n\
         #     rngb        état b(déc.) n → empreinte de n tirages bornés par b\n\
         #     impact      assembly seq   → état obtenu par DetRng::for_impact\n\
         #\n\
         # « état » est un état brut repris par DetRng::from_state : il fige le pas et\n\
         # la fonction de sortie de PCG32. La préparation de graine, elle, est figée par\n\
         # « impact ». Les séparer fait dire à un échec laquelle des deux a bougé.\n\
         #\n",
    );

    let _ = writeln!(texte, "# kernel-version: {version}");
    let _ = writeln!(texte, "# cases: {TOTAL}");
    let _ = writeln!(texte, "# digest: 0x{empreinte:016X}");
    texte.push_str(
        "#\n\
         # La configuration ci-dessous est celle sur laquelle ces valeurs ont été\n\
         # établies. Elle est indicative : le rejeu ne la compare pas, puisque le\n\
         # fichier existe précisément pour être rejoué ailleurs.\n",
    );
    let _ = writeln!(texte, "# engendré sur: {profil}");
    texte.push_str("#\n");
    texte
}

/// Compose une ligne de cas, résultat compris.
fn pousser(lignes: &mut Vec<String>, op: &str, args: &[String]) {
    let refs: Vec<&str> = args.iter().map(String::as_str).collect();
    let resultat = cases::evaluate(op, &refs);
    let mut ligne = String::from(op);
    for arg in args {
        ligne.push('\t');
        ligne.push_str(arg);
    }
    ligne.push('\t');
    ligne.push_str(&resultat);
    lignes.push(ligne);
}

/// `clamp` : le produit croisé complet des valeurs et des paires de bornes.
///
/// 25 valeurs × 7 bornes basses × 7 bornes hautes = 1 225 cas, puis 275 tirés.
/// Le produit croisé est exhaustif parce qu'il tient : toutes les paires de
/// bornes remarquables rencontrent toutes les valeurs remarquables, y compris
/// les paires inversées et les bornes qui ne sont pas des nombres.
fn clamp_cases(source: &mut Source, lignes: &mut Vec<String>) {
    for hi in BORNES {
        for lo in BORNES {
            for v in REMARQUABLES {
                pousser(
                    lignes,
                    "clamp",
                    &[format!("{v:08X}"), format!("{lo:08X}"), format!("{hi:08X}")],
                );
            }
        }
    }
    for _ in 0..275 {
        pousser(
            lignes,
            "clamp",
            &[
                cases::encode_f32(source.next_f32()),
                cases::encode_f32(source.next_f32()),
                cases::encode_f32(source.next_f32()),
            ],
        );
    }
}

/// `falloff` et `smooth01` : les remarquables, un balayage dense de `[0, 1]`
/// débordant de part et d'autre, puis des tirages.
///
/// Le balayage déborde volontairement : les deux fonctions bornent leur entrée,
/// et c'est aux abords exacts de `0` et de `1` que le bornage se voit ou ne se
/// voit pas.
fn unaire_domaine_normalise(op: &str, source: &mut Source, lignes: &mut Vec<String>) {
    for bits in REMARQUABLES {
        pousser(lignes, op, &[format!("{bits:08X}")]);
    }
    for index in 0..1000 {
        let x = (index as f32 - 100.0) / 800.0;
        pousser(lignes, op, &[cases::encode_f32(x)]);
    }
    for _ in 0..475 {
        pousser(lignes, op, &[cases::encode_f32(source.next_f32())]);
    }
}

/// `inv_sqrt` : un balayage dans l'espace des bits.
///
/// Linéaire en bits, donc logarithmique en valeur : mille cas parcourent tous
/// les exposants de `f32`, des subnormaux à l'infini. Un balayage linéaire en
/// valeur passerait mille fois dans le même exposant et jamais dans les autres,
/// alors que c'est l'exposant qui décide de ce que `sqrt` doit arrondir.
fn inv_sqrt_cases(source: &mut Source, lignes: &mut Vec<String>) {
    for bits in REMARQUABLES {
        pousser(lignes, "inv_sqrt", &[format!("{bits:08X}")]);
    }
    let pas = 0x7F80_0000u32 / 1000;
    for index in 0..1000u32 {
        pousser(lignes, "inv_sqrt", &[format!("{:08X}", index * pas + 1)]);
    }
    for _ in 0..475 {
        pousser(lignes, "inv_sqrt", &[cases::encode_f32(source.next_f32())]);
    }
}

/// `quantize` : les remarquables contre tous les pas, puis les demi-pas.
///
/// Le balayage des demi-pas est le cœur de l'affaire. `v = (k + ½)·pas` est
/// l'endroit exact où l'arrondi demi-loin-de-zéro tranche, et les deux crans qui
/// l'entourent disent de quel côté. Une implémentation qui passerait par `round`
/// plutôt que par la troncature s'y trahirait.
fn quantize_cases(source: &mut Source, lignes: &mut Vec<String>) {
    for pas in PAS {
        for v in REMARQUABLES.iter().take(25) {
            pousser(
                lignes,
                "quantize",
                &[format!("{v:08X}"), cases::encode_f32(*pas)],
            );
        }
    }
    for pas in PAS_UTILISABLES {
        for k in -20..=20 {
            let base = (k as f32 + 0.5) * pas;
            for voisin in [
                base,
                f32::from_bits(base.to_bits().wrapping_add(1)),
                f32::from_bits(base.to_bits().wrapping_sub(1)),
            ] {
                pousser(
                    lignes,
                    "quantize",
                    &[cases::encode_f32(voisin), cases::encode_f32(*pas)],
                );
            }
        }
    }
    for _ in 0..166 {
        let pas = PAS[(source.next() as usize) % PAS.len()];
        pousser(
            lignes,
            "quantize",
            &[cases::encode_f32(source.next_f32()), cases::encode_f32(pas)],
        );
    }
}

/// `dequantize` : les 256 valeurs d'un `i8`, contre plusieurs pas.
///
/// Les 256, et non les 254 que `quantize_i8` produit : `-128` arrive par une
/// lecture de fichier, pas par le moteur, et c'est justement celui dont la
/// magnitude déborde en premier.
fn dequantize_cases(source: &mut Source, lignes: &mut Vec<String>) {
    for index in 0..768usize {
        let q = (index % 256) as i32 - 128;
        let pas = PAS[(index / 256) % PAS.len()];
        pousser(
            lignes,
            "dequantize",
            &[format!("{q}"), cases::encode_f32(pas)],
        );
    }
    for _ in 0..232 {
        let draw = source.next();
        let q = (draw & 0xFF) as i32 - 128;
        let pas = if draw & 0x100 == 0 {
            PAS[((draw >> 16) as usize) % PAS.len()]
        } else {
            source.next_f32()
        };
        pousser(
            lignes,
            "dequantize",
            &[format!("{q}"), cases::encode_f32(pas)],
        );
    }
}

/// `digest` : toutes les longueurs de 0 à 64, puis des suites tirées.
///
/// Les longueurs contiguës comptent plus que le contenu : XXH64 traite les
/// octets par blocs de 32 et termine par une queue, et c'est au passage d'un
/// bloc à l'autre qu'une implémentation se distingue d'une autre.
fn digest_cases(source: &mut Source, lignes: &mut Vec<String>) {
    for longueur in 0..=64usize {
        let octets: Vec<u8> = (0..longueur).map(|index| (index * 7 + 1) as u8).collect();
        pousser(lignes, "digest", &[cases::encode_bytes(&octets)]);
    }
    for _ in 0..435 {
        let longueur = (source.next() % 65) as usize;
        let octets: Vec<u8> = (0..longueur).map(|_| (source.next() >> 24) as u8).collect();
        pousser(lignes, "digest", &[cases::encode_bytes(&octets)]);
    }
}

/// `mix` : le produit croisé des identifiants remarquables, puis des tirages.
fn mix_cases(source: &mut Source, lignes: &mut Vec<String>) {
    for first in IDENTIFIANTS {
        for second in IDENTIFIANTS {
            pousser(
                lignes,
                "mix",
                &[cases::encode_u64(*first), cases::encode_u64(*second)],
            );
        }
    }
    for _ in 0..419 {
        pousser(
            lignes,
            "mix",
            &[
                cases::encode_u64(source.next()),
                cases::encode_u64(source.next()),
            ],
        );
    }
}

/// Les quatre familles du générateur : suites brutes, flottantes, bornées, et
/// préparation de graine.
fn rng_cases(source: &mut Source, lignes: &mut Vec<String>) {
    for index in 0..250usize {
        let etat = match IDENTIFIANTS.get(index) {
            Some(identifiant) => *identifiant,
            None => source.next(),
        };
        // Des longueurs variées, dont zéro : une suite vide a une empreinte, et
        // c'est celle de la suite vide.
        let nombre = (index % 65) as u32;
        pousser(
            lignes,
            "rng",
            &[cases::encode_u64(etat), format!("{nombre}")],
        );
    }
    for index in 0..100usize {
        pousser(
            lignes,
            "rngf",
            &[cases::encode_u64(source.next()), format!("{}", index % 33)],
        );
    }
    // Les bornes couvrent zéro — qui ne tire rien —, les puissances de deux, et
    // celles qui ne divisent pas `2³²`, seules à faire jouer le rejet.
    let bornes = [0u32, 1, 2, 3, 7, 10, 64, 100, 1000, u32::MAX];
    for index in 0..100usize {
        pousser(
            lignes,
            "rngb",
            &[
                cases::encode_u64(source.next()),
                format!("{}", bornes[index % bornes.len()]),
                format!("{}", index % 17),
            ],
        );
    }
    for index in 0..50usize {
        let (assembly, sequence) = match IDENTIFIANTS.get(index) {
            Some(identifiant) => (*identifiant, index as u64),
            None => (source.next(), source.next()),
        };
        pousser(
            lignes,
            "impact",
            &[cases::encode_u64(assembly), cases::encode_u64(sequence)],
        );
    }
}
