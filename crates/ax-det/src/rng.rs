//! Générateur pseudo-aléatoire déterministe (C-16, R-512).

use crate::digest::mix64;

/// Multiplicateur de la récurrence linéaire congruentielle de PCG32.
const MULTIPLIER: u64 = 6_364_136_223_846_793_005;

/// Incrément de flux, fixé.
///
/// PCG permet à chaque flux d'avoir le sien ; AXION n'en emploie qu'un, et la
/// diversité vient de la graine. Un incrément variable serait un second axe à
/// figer, à documenter et à vérifier, pour une propriété dont personne n'a
/// besoin ici.
const INCREMENT: u64 = 1_442_695_040_888_963_407;

/// Générateur PCG32 à graine explicite (R-512).
///
/// **Aucun générateur global.** La graine vient toujours de données du monde —
/// l'identifiant d'une assembly et le numéro de séquence d'un impact — de sorte
/// que le client et le serveur, rejouant le même impact, tirent la même suite.
/// Un générateur partagé les ferait dépendre de l'ordre dans lequel d'autres
/// systèmes ont tiré, c'est-à-dire de tout.
///
/// L'état tient sur un `u64`, comme la fiche C-16 le donne.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DetRng(u64);

impl DetRng {
    /// Crée un générateur à partir d'une graine brute.
    ///
    /// L'état initial suit la préparation de référence de PCG : un pas à vide
    /// après avoir ajouté la graine. Sans lui, deux graines voisines
    /// produiraient des premières sorties voisines.
    #[must_use]
    pub fn new(seed: u64) -> Self {
        let mut rng = DetRng(0);
        rng.step();
        rng.0 = rng.0.wrapping_add(seed);
        rng.step();
        rng
    }

    /// Crée le générateur d'un impact (R-512).
    ///
    /// La graine dérive de `(assembly_uuid, impact.seq)` par l'empreinte du
    /// noyau : deux impacts d'une même assembly, ou un même numéro sur deux
    /// assemblies, ne partagent aucune suite.
    #[must_use]
    pub fn for_impact(assembly_uuid: u64, sequence: u64) -> Self {
        Self::new(mix64(assembly_uuid, sequence))
    }

    /// Fait avancer l'état.
    #[inline]
    fn step(&mut self) {
        self.0 = self.0.wrapping_mul(MULTIPLIER).wrapping_add(INCREMENT);
    }

    /// Tire les 32 prochains bits.
    ///
    /// La sortie dérive de l'état **précédent**, comme dans l'implémentation de
    /// référence de PCG : la tirer du nouvel état donnerait une suite décalée
    /// d'un cran, indétectable en test statistique et fatale en rejeu.
    pub fn next_u32(&mut self) -> u32 {
        let previous = self.0;
        self.step();

        let xorshifted = (((previous >> 18) ^ previous) >> 27) as u32;
        let rotation = (previous >> 59) as u32;
        xorshifted.rotate_right(rotation)
    }

    /// Tire les 64 prochains bits.
    ///
    /// Deux tirages de 32 bits, le premier en poids fort. L'ordre est figé :
    /// l'inverser donnerait une autre suite pour la même graine.
    pub fn next_u64(&mut self) -> u64 {
        let high = u64::from(self.next_u32());
        let low = u64::from(self.next_u32());
        (high << 32) | low
    }

    /// Tire un flottant dans `[0, 1)`.
    ///
    /// Les 24 bits de poids fort divisés par `2²⁴` : la division par une
    /// puissance de deux est exacte, et le résultat compte autant de valeurs
    /// distinctes qu'un `f32` en admet dans cet intervalle. Passer par une
    /// multiplication par une constante approchée introduirait un arrondi de
    /// plus, donc un point de divergence de plus.
    pub fn next_f32(&mut self) -> f32 {
        let bits = self.next_u32() >> 8;
        bits as f32 / 16_777_216.0
    }

    /// Tire un entier dans `[0, bound)`.
    ///
    /// Le rejet des valeurs de la zone haute évite le biais modulo. Il coûte un
    /// tirage de plus de temps en temps, et **le même** des deux côtés : c'est
    /// le rejet qui est déterministe, pas seulement la suite.
    ///
    /// Une borne nulle rend `0`.
    pub fn next_bounded(&mut self, bound: u32) -> u32 {
        if bound == 0 {
            return 0;
        }
        let threshold = bound.wrapping_neg() % bound;
        loop {
            let drawn = self.next_u32();
            if drawn >= threshold {
                return drawn % bound;
            }
        }
    }

    /// {@return l'état courant}
    ///
    /// Exposé pour la persistance : un impact interrompu doit pouvoir reprendre
    /// sa suite là où il l'a laissée.
    #[must_use]
    pub fn state(&self) -> u64 {
        self.0
    }

    /// Reprend un générateur à un état conservé.
    #[must_use]
    pub fn from_state(state: u64) -> Self {
        DetRng(state)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t820_deux_generateurs_de_meme_graine_tirent_la_meme_suite() {
        // C'est toute la propriété dont dépend le mode RECONSTRUCT.
        let mut first = DetRng::new(0x1234_5678_9ABC_DEF0);
        let mut second = DetRng::new(0x1234_5678_9ABC_DEF0);
        for index in 0..10_000 {
            assert_eq!(
                first.next_u32(),
                second.next_u32(),
                "divergence au tirage {index}"
            );
        }
    }

    #[test]
    fn t820_deux_graines_voisines_donnent_des_suites_distinctes() {
        // Sans le pas de préparation, les premières sorties seraient voisines.
        let mut first = DetRng::new(1);
        let mut second = DetRng::new(2);
        let mut identiques = 0;
        for _ in 0..64 {
            if first.next_u32() == second.next_u32() {
                identiques += 1;
            }
        }
        assert_eq!(identiques, 0, "{identiques} tirages en commun");
    }

    #[test]
    fn t820_la_graine_d_un_impact_distingue_assembly_et_sequence() {
        let a = DetRng::for_impact(1, 2);
        let b = DetRng::for_impact(2, 1);
        // Échanger l'assembly et le numéro ne doit pas rendre la même suite.
        assert_ne!(a, b);
        assert_ne!(DetRng::for_impact(1, 1), DetRng::for_impact(1, 2));
        assert_eq!(DetRng::for_impact(7, 9), DetRng::for_impact(7, 9));
    }

    #[test]
    fn t820_le_flottant_tire_reste_dans_zero_un() {
        let mut rng = DetRng::new(42);
        for _ in 0..100_000 {
            let value = rng.next_f32();
            assert!((0.0..1.0).contains(&value), "hors bornes : {value}");
        }
    }

    #[test]
    fn t820_le_flottant_tire_couvre_son_intervalle() {
        // Une répartition franchement inégale signalerait un décalage de bits.
        let mut rng = DetRng::new(7);
        let mut buckets = [0u32; 10];
        for _ in 0..100_000 {
            let index = (rng.next_f32() * 10.0) as usize;
            buckets[index.min(9)] += 1;
        }
        for (index, count) in buckets.iter().enumerate() {
            assert!(
                (8_000..12_000).contains(count),
                "case {index} : {count} tirages"
            );
        }
    }

    #[test]
    fn t820_l_entier_borne_reste_dans_ses_bornes() {
        let mut rng = DetRng::new(99);
        for bound in [1u32, 2, 3, 7, 64, 1000, u32::MAX] {
            for _ in 0..1000 {
                assert!(rng.next_bounded(bound) < bound, "borne {bound}");
            }
        }
        assert_eq!(rng.next_bounded(0), 0);
    }

    #[test]
    fn t820_l_entier_borne_est_sans_biais_modulo() {
        // Trois cases sur un tirage de 32 bits : le biais modulo se verrait
        // sans le rejet.
        let mut rng = DetRng::new(5);
        let mut buckets = [0u32; 3];
        for _ in 0..90_000 {
            buckets[rng.next_bounded(3) as usize] += 1;
        }
        for count in buckets {
            assert!((29_000..31_000).contains(&count), "{count} tirages");
        }
    }

    #[test]
    fn t820_un_generateur_reprend_a_son_etat() {
        // Un impact interrompu doit pouvoir reprendre sa suite.
        let mut rng = DetRng::new(123);
        for _ in 0..17 {
            rng.next_u32();
        }
        let state = rng.state();
        let attendu: Vec<u32> = (0..10).map(|_| rng.next_u32()).collect();

        let mut repris = DetRng::from_state(state);
        let obtenu: Vec<u32> = (0..10).map(|_| repris.next_u32()).collect();
        assert_eq!(attendu, obtenu);
    }

    #[test]
    fn t820_le_tirage_64_bits_compose_deux_tirages_32() {
        // L'ordre est figé : l'inverser donnerait une autre suite.
        let mut reference = DetRng::new(31);
        let high = u64::from(reference.next_u32());
        let low = u64::from(reference.next_u32());

        let mut rng = DetRng::new(31);
        assert_eq!(rng.next_u64(), (high << 32) | low);
    }
}
