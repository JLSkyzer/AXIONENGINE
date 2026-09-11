//! Opérations déterministes scalaires (C-16).
//!
//! Chaque fonction de ce module est un **contrat sur des bits**, pas sur une
//! valeur mathématique. Deux expressions algébriquement égales ne produisent pas
//! les mêmes bits : `3x² - 2x³` et `x²(3 - 2x)` diffèrent au dernier bit sur
//! une infinité d'entrées. L'ordre des opérations écrit ici fait donc partie de
//! la spécification, au même titre que la formule.
//!
//! # Ce que ce module s'interdit
//!
//! - **Toute fonction de bibliothèque mathématique.** `round`, `floor`, `abs`
//!   sont exactement spécifiées par IEEE-754, mais leur implémentation passe
//!   par la libm de la plateforme sur certaines cibles. « Exactement spécifiée »
//!   et « identiquement implémentée partout » ne sont pas la même chose, et le
//!   noyau ne peut pas se permettre de le supposer. Seules `+ - * /`, `sqrt`,
//!   les comparaisons et les conversions entier-flottant sont employées.
//! - **Toute valeur non finie en sortie.** Un `NaN` ou un infini dans un champ
//!   de déformation contamine tout ce qu'il touche, et se propage par la
//!   réplication. Chaque fonction rend une valeur finie ou zéro.

/// Indique si une valeur est finie, sans inspecter ses bits.
///
/// `x - x` vaut zéro pour tout nombre fini, et `NaN` pour un `NaN` comme pour un
/// infini — `∞ - ∞` n'est pas défini. Une seule soustraction et une comparaison
/// suffisent donc, là où `is_finite` masque un motif de bits par une forme
/// compilée qui varie d'une cible à l'autre.
// `clippy::eq_op` voit une soustraction d'une expression par elle-même et
// conclut à une faute de frappe. C'en serait une sur un entier ; sur un
// flottant, c'est le test lui-même, et le seul qui n'ait besoin ni de `is_finite`
// ni d'un masque de bits.
#[allow(clippy::eq_op)]
#[must_use]
#[inline]
fn finite(x: f32) -> bool {
    x - x == 0.0
}

/// Borne une valeur entre deux limites.
///
/// **Une valeur non finie rend `lo`.** C'est un choix arbitraire, comme le
/// serait n'importe quel autre ; ce qui compte est qu'il soit défini et figé.
/// Ce qui n'est pas un nombre n'a pas sa place dans un champ, et la borne basse
/// est la plus conservatrice — sur un déplacement, elle ne déplace rien.
///
/// **Des bornes non finies ou inversées rendent `0`.** Rendre `lo` serait ici
/// rendre un `NaN` quand `lo` en est un, c'est-à-dire propager exactement ce que
/// cette fonction existe pour arrêter.
#[must_use]
pub fn clamp(v: f32, lo: f32, hi: f32) -> f32 {
    if !finite(lo) || !finite(hi) || lo > hi {
        return 0.0;
    }
    if !finite(v) {
        return lo;
    }
    if v < lo {
        return lo;
    }
    if v > hi {
        return hi;
    }
    v
}

/// Atténuation radiale, `(1 - x²)²` sur `[0, 1]`.
///
/// `x` est **borné à `[0, 1]`** avant évaluation. Une atténuation doit valoir
/// zéro au-delà de son rayon ; sans cette borne, `(1 - x²)²` remonte et un
/// impact lointain déformerait plus qu'un impact proche.
///
/// L'ordre est figé : `t = 1 - x·x`, puis `t·t`. Trois opérations, deux
/// arrondis.
#[must_use]
pub fn falloff(x: f32) -> f32 {
    let x = clamp(x, 0.0, 1.0);
    let t = 1.0 - x * x;
    t * t
}

/// Interpolation lisse, `3x² - 2x³` sur `[0, 1]`.
///
/// `x` est **borné à `[0, 1]`** avant évaluation, ce que son nom annonce.
///
/// L'évaluation suit la forme de Horner : `x·x·(3 - 2·x)`. Elle est
/// algébriquement égale à `3x² - 2x³` et **n'en donne pas les mêmes bits** —
/// quatre opérations au lieu de six, donc deux arrondis de moins. Le cahier des
/// charges donne la formule, pas l'ordre ; celui-ci est arrêté ici et figé par
/// les vecteurs d'or.
#[must_use]
pub fn smooth01(x: f32) -> f32 {
    let x = clamp(x, 0.0, 1.0);
    x * x * (3.0 - 2.0 * x)
}

/// Inverse de la racine carrée, `1 / √x`.
///
/// **Aucune approximation rapide.** La racine carrée est l'une des cinq
/// opérations qu'IEEE-754 spécifie exactement, et la division aussi : le
/// résultat est donc identique partout où ces deux opérations le sont. Les
/// approximations du type `rsqrtss` ne le sont pas : leur précision est laissée
/// au fabricant.
///
/// Une entrée nulle, négative ou non finie rend `0` plutôt qu'un infini : un
/// infini dans un champ contamine tout ce qu'il touche.
// `!(x > 0)` et `x <= 0` ne sont pas la même chose, et c'est exactement pour
// cela que la première est écrite : sur un `NaN`, toute comparaison est fausse,
// donc `x <= 0` laisserait passer ce que `!(x > 0)` arrête. `clippy` propose
// `partial_cmp` ; il rendrait le rejet plus verbeux sans le rendre plus sûr.
#[allow(clippy::neg_cmp_op_on_partial_ord)]
#[must_use]
pub fn inv_sqrt(x: f32) -> f32 {
    if !finite(x) || !(x > 0.0) {
        return 0.0;
    }
    let root = sqrt(x);
    if !(root > 0.0) {
        return 0.0;
    }
    1.0 / root
}

/// Racine carrée, isolée pour que son unique emploi soit visible.
///
/// `f32::sqrt` se compile en l'instruction matérielle sur toutes les cibles de
/// la matrice, et IEEE-754 en impose l'arrondi correct. C'est la seule fonction
/// de la bibliothèque standard que le noyau emploie.
#[must_use]
#[inline]
fn sqrt(x: f32) -> f32 {
    x.sqrt()
}

/// Plus grande magnitude d'un pas quantifié (DM-12).
///
/// `127` et non `128` : la plage `i8` est asymétrique, et l'employer entièrement
/// rendrait un déplacement maximal négatif différent de son opposé positif. Un
/// champ où `+max` et `-max` ne se valent pas produirait une déformation qui
/// penche d'un côté.
pub const QUANT_MAX: i32 = 127;

/// Plus grande magnitude qu'un `i8` porte : `128`.
///
/// Distincte de [`QUANT_MAX`], et pour une raison : [`quantize_i8`] ne produit
/// jamais `-128`, mais [`dequantize_i8`] accepte n'importe quel `i8`, y compris
/// celui qu'une lecture de fichier aura rapporté. Un garde-fou sur une sortie
/// se raisonne à partir de ce que le type admet, pas de ce que le moteur
/// produit.
const I8_MAGNITUDE: f32 = 128.0;

/// Quantifie une valeur en `i8`, par pas.
///
/// L'arrondi est **demi-loin-de-zéro** : `0,5` donne `1`, `-0,5` donne `-1`.
/// Il est calculé ici sans passer par `round`, à l'entier et à la comparaison :
/// la troncature vers zéro d'une conversion flottant-entier est exactement
/// spécifiée par Rust, alors que `roundf` passe par la libm sur certaines
/// cibles.
///
/// Une entrée non finie, ou un pas nul ou négatif, rend `0` : il n'y a pas de
/// quantification possible, et zéro est le déplacement qui ne déplace rien.
// Voir [`inv_sqrt`] : `!(step > 0)` arrête le `NaN` que `step <= 0` laisserait
// passer.
#[allow(clippy::neg_cmp_op_on_partial_ord)]
#[must_use]
pub fn quantize_i8(v: f32, step: f32) -> i8 {
    if !finite(step) || !(step > 0.0) || !finite(v) {
        return 0;
    }

    // La division est exacte au sens IEEE, mais elle **déborde** quand le pas
    // est minuscule et la valeur grande. Le quotient est alors un infini — pas
    // un `NaN` : le pas est non nul et la valeur finie — et son signe est celui
    // de la valeur, puisque le pas est positif.
    //
    // Le confier à `clamp` le ramènerait à la borne **basse**, qui rend `lo`
    // pour toute entrée non finie : une valeur franchement positive
    // quantifierait en pas franchement négatif, c'est-à-dire en son opposé.
    // Un débordement est une saturation, et se traite comme telle.
    let limit = QUANT_MAX as f32 + 1.0;
    let ratio = v / step;
    let scaled = if finite(ratio) {
        clamp(ratio, -limit, limit)
    } else if ratio > 0.0 {
        limit
    } else {
        -limit
    };

    // Troncature vers zéro, puis correction par la fraction. Les deux valeurs
    // sont petites : `truncated as f32` est exact, et la soustraction aussi.
    let truncated = scaled as i32;
    let fraction = scaled - truncated as f32;

    let rounded = if fraction >= 0.5 {
        truncated + 1
    } else if fraction <= -0.5 {
        truncated - 1
    } else {
        truncated
    };

    // Saturation à la plage symétrique. `Ord::clamp` et non celui de ce
    // module : on borne ici un entier, et la comparaison d'entiers n'a ni
    // arrondi ni valeur non comparable.
    rounded.clamp(-QUANT_MAX, QUANT_MAX) as i8
}

/// Déquantifie un pas en valeur.
///
/// L'aller-retour n'est pas l'identité — c'est le propre d'une quantification —
/// mais il est **déterministe et monotone** : deux quantifications d'une même
/// valeur donnent le même pas, et un pas plus grand donne une valeur plus
/// grande.
///
/// Un pas dont la plage entière ne tient pas dans un `f32` est refusé au même
/// titre qu'un pas négatif, et pour tous ses pas d'un coup. Ne refuser que les
/// pas qui débordent ferait répondre la fonction tantôt par une valeur, tantôt
/// par zéro, selon l'amplitude — et la monotonie de l'aller-retour tomberait
/// avec.
// Voir [`inv_sqrt`] : `!(step > 0)` arrête le `NaN` que `step <= 0` laisserait
// passer.
#[allow(clippy::neg_cmp_op_on_partial_ord)]
#[must_use]
pub fn dequantize_i8(q: i8, step: f32) -> f32 {
    if !finite(step) || !(step > 0.0) {
        return 0.0;
    }
    if !finite(I8_MAGNITUDE * step) {
        return 0.0;
    }
    q as f32 * step
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t820_le_bornage_refuse_les_valeurs_non_finies() {
        // Ce qui n'est pas un nombre n'a pas sa place dans un champ.
        assert_eq!(clamp(f32::NAN, -1.0, 1.0), -1.0);
        assert_eq!(clamp(f32::INFINITY, -1.0, 1.0), -1.0);
        assert_eq!(clamp(f32::NEG_INFINITY, -1.0, 1.0), -1.0);
    }

    #[test]
    fn t820_le_bornage_refuse_des_bornes_impossibles() {
        // Inversées : les accepter produirait un résultat dépendant de l'ordre
        // des comparaisons.
        assert_eq!(clamp(0.5, 1.0, -1.0), 0.0);
        // Non finies : rendre `lo` reviendrait ici à rendre le NaN que cette
        // fonction existe précisément pour arrêter.
        assert_eq!(clamp(0.5, f32::NAN, 1.0), 0.0);
        assert_eq!(clamp(0.5, -1.0, f32::NAN), 0.0);
        assert_eq!(clamp(0.5, f32::NEG_INFINITY, 1.0), 0.0);
    }

    #[test]
    fn t820_aucune_sortie_n_est_non_finie() {
        // Le contrat du module, vérifié sur toutes les entrées pathologiques
        // qu'un champ peut rencontrer.
        let pathologiques = [
            f32::NAN,
            f32::INFINITY,
            f32::NEG_INFINITY,
            f32::MAX,
            f32::MIN,
            f32::MIN_POSITIVE,
            -f32::MIN_POSITIVE,
            0.0,
            -0.0,
            1.0e-45,
        ];
        for v in pathologiques {
            assert!(finite(clamp(v, -1.0, 1.0)), "clamp({v})");
            assert!(finite(falloff(v)), "falloff({v})");
            assert!(finite(smooth01(v)), "smooth01({v})");
            assert!(finite(inv_sqrt(v)), "inv_sqrt({v})");
            assert!(
                finite(dequantize_i8(quantize_i8(v, 0.01), 0.01)),
                "quantize({v})"
            );
        }
    }

    #[test]
    fn t820_le_bornage_est_inclusif_a_ses_bornes() {
        assert_eq!(clamp(-1.0, -1.0, 1.0), -1.0);
        assert_eq!(clamp(1.0, -1.0, 1.0), 1.0);
        assert_eq!(clamp(0.25, -1.0, 1.0), 0.25);
        assert_eq!(clamp(-2.0, -1.0, 1.0), -1.0);
        assert_eq!(clamp(2.0, -1.0, 1.0), 1.0);
    }

    #[test]
    fn t820_l_attenuation_vaut_un_au_centre_et_zero_au_bord() {
        assert_eq!(falloff(0.0), 1.0);
        assert_eq!(falloff(1.0), 0.0);
        // Au-delà du rayon, rien : sans le bornage, (1 - x²)² remonterait et un
        // impact lointain déformerait plus qu'un impact proche.
        assert_eq!(falloff(2.0), 0.0);
        assert_eq!(falloff(-1.0), 1.0);
        assert_eq!(falloff(f32::NAN), 1.0);
    }

    #[test]
    fn t820_l_attenuation_decroit() {
        let mut previous = falloff(0.0);
        for step in 1..=100 {
            let value = falloff(step as f32 / 100.0);
            assert!(value <= previous, "remontée à {step}");
            previous = value;
        }
    }

    #[test]
    fn t820_l_interpolation_lisse_va_de_zero_a_un() {
        assert_eq!(smooth01(0.0), 0.0);
        assert_eq!(smooth01(1.0), 1.0);
        assert_eq!(smooth01(-5.0), 0.0);
        assert_eq!(smooth01(5.0), 1.0);
        // Au milieu, exactement la moitié : 0,25 × (3 - 1) = 0,5.
        assert_eq!(smooth01(0.5), 0.5);
    }

    #[test]
    fn t820_l_ordre_d_evaluation_de_l_interpolation_est_celui_qui_est_fige() {
        // La forme développée est algébriquement égale et donne d'autres bits.
        // Ce test dit laquelle est la nôtre : si quelqu'un « simplifie » un
        // jour l'expression, les vecteurs d'or le verront, et celui-ci dira
        // pourquoi.
        for step in 0..=1000 {
            let x = step as f32 / 1000.0;
            let horner = x * x * (3.0 - 2.0 * x);
            assert_eq!(smooth01(x).to_bits(), horner.to_bits(), "x = {x}");
        }
    }

    #[test]
    fn t820_l_inverse_de_racine_refuse_ce_qui_n_a_pas_de_racine() {
        assert_eq!(inv_sqrt(0.0), 0.0);
        assert_eq!(inv_sqrt(-1.0), 0.0);
        assert_eq!(inv_sqrt(f32::NAN), 0.0);
        assert_eq!(inv_sqrt(f32::INFINITY), 0.0);
    }

    #[test]
    fn t820_l_inverse_de_racine_est_exact_sur_les_carres() {
        assert_eq!(inv_sqrt(1.0), 1.0);
        assert_eq!(inv_sqrt(4.0), 0.5);
        assert_eq!(inv_sqrt(0.25), 2.0);
        assert_eq!(inv_sqrt(16.0), 0.25);
    }

    #[test]
    fn t820_la_quantification_arrondit_demi_loin_de_zero() {
        // C'est la règle que DM-12 impose : 0,5 monte, -0,5 descend.
        assert_eq!(quantize_i8(0.5, 1.0), 1);
        assert_eq!(quantize_i8(-0.5, 1.0), -1);
        assert_eq!(quantize_i8(1.5, 1.0), 2);
        assert_eq!(quantize_i8(-1.5, 1.0), -2);
        assert_eq!(quantize_i8(0.4999999, 1.0), 0);
        assert_eq!(quantize_i8(-0.4999999, 1.0), 0);
    }

    #[test]
    fn t820_la_quantification_est_symetrique() {
        // 127 et non 128 : un champ où +max et -max ne se valent pas
        // produirait une déformation qui penche d'un côté.
        assert_eq!(quantize_i8(1000.0, 1.0), 127);
        assert_eq!(quantize_i8(-1000.0, 1.0), -127);
        for step in 0..=200 {
            let v = (step as f32 - 100.0) / 7.0;
            assert_eq!(
                quantize_i8(v, 0.5),
                -quantize_i8(-v, 0.5),
                "asymétrie en {v}"
            );
        }
    }

    #[test]
    fn t820_la_quantification_refuse_un_pas_impossible() {
        assert_eq!(quantize_i8(1.0, 0.0), 0);
        assert_eq!(quantize_i8(1.0, -1.0), 0);
        assert_eq!(quantize_i8(1.0, f32::NAN), 0);
        assert_eq!(quantize_i8(f32::NAN, 1.0), 0);
        assert_eq!(quantize_i8(f32::INFINITY, 1.0), 0);
    }

    #[test]
    fn t820_la_quantification_est_monotone() {
        let step = 0.01;
        let mut previous = quantize_i8(-2.0, step);
        for index in -199..=200 {
            let v = index as f32 / 100.0;
            let q = quantize_i8(v, step);
            assert!(q >= previous, "recul en {v} : {q} après {previous}");
            previous = q;
        }
    }

    #[test]
    fn t820_l_aller_retour_de_quantification_reste_dans_le_pas() {
        let step = 0.002;
        for index in -127..=127 {
            let value = dequantize_i8(index, step);
            assert_eq!(quantize_i8(value, step), index, "aller-retour en {index}");
        }
    }

    #[test]
    fn t820_la_dequantification_refuse_un_pas_impossible() {
        assert_eq!(dequantize_i8(10, 0.0), 0.0);
        assert_eq!(dequantize_i8(10, -1.0), 0.0);
    }

    #[test]
    fn t820_la_dequantification_refuse_un_pas_dont_la_plage_deborde() {
        // `127 × f32::MAX` est un infini, et un infini dans un champ contamine
        // tout ce qu'il touche. Le pas est refusé pour **tous** ses pas, pas
        // seulement pour ceux qui débordent : une réponse qui change de nature
        // d'un pas à l'autre casserait la monotonie de l'aller-retour.
        for q in [i8::MIN, -127, -1, 0, 1, 127] {
            assert_eq!(dequantize_i8(q, f32::MAX), 0.0, "pas maximal, q = {q}");
        }

        // Le garde-fou porte sur `128` et non sur `QUANT_MAX` : `quantize_i8`
        // ne produit jamais `-128`, mais une lecture de fichier le rapporte.
        let step = f32::MAX / 127.5;
        assert!(finite(127.0 * step), "le pas est utilisable jusqu'à 127");
        assert_eq!(dequantize_i8(-128, step), 0.0, "le pas déborde en -128");
        assert_eq!(dequantize_i8(127, step), 0.0, "et il est refusé partout");
    }

    #[test]
    fn t820_la_quantification_sature_du_bon_cote_quand_la_division_deborde() {
        // Un pas subnormal fait déborder `v / step` avant toute comparaison. Le
        // quotient est alors un infini, et le traiter comme « non fini » le
        // ramènerait à la borne **basse** : une valeur franchement positive
        // quantifierait en pas franchement négatif, c'est-à-dire en son
        // opposé.
        let step = 1.0e-45;
        assert_eq!(
            quantize_i8(1.0, step),
            127,
            "une valeur positive sature en +127"
        );
        assert_eq!(quantize_i8(-1.0, step), -127, "et une négative en -127");
        assert_eq!(quantize_i8(0.0, step), 0, "zéro reste zéro");

        // La symétrie tient encore : c'est elle que l'inversion brisait.
        assert_eq!(quantize_i8(f32::MAX, step), -quantize_i8(-f32::MAX, step));
    }
}
