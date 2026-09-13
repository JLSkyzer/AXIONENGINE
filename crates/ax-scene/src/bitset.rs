//! Ensemble de bits de taille fixe.
//!
//! Un bit par node : sale, visible, révélé, modifié. Le graphe en tient
//! plusieurs par assembly, et un `Vec<bool>` coûterait huit fois la place pour
//! un parcours qui ne lit de toute façon qu'un bit à la fois.

/// Ensemble de `len` bits, tous indexés de `0` à `len - 1`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BitSet {
    words: Vec<u64>,
    len: usize,
}

impl BitSet {
    /// Bits par mot.
    const WORD: usize = 64;

    /// Crée un ensemble de `len` bits, tous à zéro.
    #[must_use]
    pub fn new(len: usize) -> Self {
        Self {
            words: vec![0; len.div_ceil(Self::WORD)],
            len,
        }
    }

    /// Crée un ensemble de `len` bits, tous à un.
    #[must_use]
    pub fn filled(len: usize) -> Self {
        let mut set = Self {
            words: vec![u64::MAX; len.div_ceil(Self::WORD)],
            len,
        };
        set.trim();
        set
    }

    /// Nombre de bits.
    #[must_use]
    pub fn len(&self) -> usize {
        self.len
    }

    /// Indique si l'ensemble ne compte aucun bit.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Indique si le bit `index` est posé. Hors bornes, il ne l'est pas.
    #[must_use]
    pub fn contains(&self, index: usize) -> bool {
        index < self.len && self.words[index / Self::WORD] & (1 << (index % Self::WORD)) != 0
    }

    /// Pose ou retire le bit `index`.
    ///
    /// # Panics
    ///
    /// Si `index` est hors bornes : l'appelant du graphe a validé ses index, et
    /// un bit posé hors de l'ensemble serait une écriture perdue en silence.
    pub fn set(&mut self, index: usize, value: bool) {
        assert!(
            index < self.len,
            "bit {index} hors d'un ensemble de {}",
            self.len
        );
        let mask = 1 << (index % Self::WORD);
        let word = &mut self.words[index / Self::WORD];
        if value {
            *word |= mask;
        } else {
            *word &= !mask;
        }
    }

    /// Pose le bit `index`. Voir [`Self::set`].
    pub fn insert(&mut self, index: usize) {
        self.set(index, true);
    }

    /// Retire le bit `index`. Voir [`Self::set`].
    pub fn remove(&mut self, index: usize) {
        self.set(index, false);
    }

    /// Retire tous les bits.
    pub fn clear(&mut self) {
        self.words.fill(0);
    }

    /// Nombre de bits posés.
    #[must_use]
    pub fn count(&self) -> usize {
        self.words
            .iter()
            .map(|word| word.count_ones() as usize)
            .sum()
    }

    /// Indique si aucun bit n'est posé.
    #[must_use]
    pub fn none(&self) -> bool {
        self.words.iter().all(|word| *word == 0)
    }

    /// Index des bits posés, en ordre croissant.
    pub fn iter(&self) -> impl Iterator<Item = usize> + '_ {
        self.words.iter().enumerate().flat_map(|(rank, &word)| {
            let mut rest = word;
            core::iter::from_fn(move || {
                if rest == 0 {
                    return None;
                }
                let bit = rest.trailing_zeros() as usize;
                rest &= rest - 1;
                Some(rank * Self::WORD + bit)
            })
        })
    }

    /// Remet à zéro les bits du dernier mot situés au-delà de `len`.
    ///
    /// Sans cela, [`Self::count`] et [`Self::none`] compteraient des bits qui
    /// n'appartiennent à aucun node.
    fn trim(&mut self) {
        let tail = self.len % Self::WORD;
        if tail != 0 {
            if let Some(last) = self.words.last_mut() {
                *last &= (1 << tail) - 1;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn un_ensemble_neuf_est_vide_et_un_ensemble_plein_ne_deborde_pas() {
        let vide = BitSet::new(130);
        assert_eq!(vide.len(), 130);
        assert!(vide.none());

        // 130 bits : deux mots pleins et deux bits de trop dans le troisième,
        // qui ne doivent pas être comptés.
        let plein = BitSet::filled(130);
        assert_eq!(plein.count(), 130);
        assert!(plein.contains(129));
        assert!(!plein.contains(130));
    }

    #[test]
    fn poser_retirer_et_parcourir() {
        let mut set = BitSet::new(200);
        for index in [0, 63, 64, 199] {
            set.insert(index);
        }
        set.remove(63);

        assert_eq!(set.iter().collect::<Vec<_>>(), [0, 64, 199]);
        assert_eq!(set.count(), 3);
        set.clear();
        assert!(set.none());
    }

    #[test]
    #[should_panic(expected = "hors d'un ensemble")]
    fn un_bit_hors_bornes_n_est_pas_ecrit_en_silence() {
        BitSet::new(8).insert(8);
    }
}
