//! Validation d'un asset compilé (C-22).
//!
//! La liste de contrôle est celle de la fiche 5.15, groupe par groupe :
//! structure, limites, géométrie, UV, normales, skin, physique, déformation,
//! graphe structurel, noms.
//!
//! # Deux fois, pas une
//!
//! R-540 veut une validation **stricte à la compilation** et **re-vérifiée au
//! chargement**. Ce n'est pas de la redondance : entre les deux, l'asset a
//! séjourné dans un cache sur disque, que rien n'empêche d'altérer. La seconde
//! passe est la défense en profondeur qui fait qu'un cache modifié ne devient
//! pas une lecture hors bornes.
//!
//! # Tout, pas la première
//!
//! La validation ne s'arrête pas à la première violation. Un auteur qui corrige
//! son modèle veut la liste, pas un défaut à la fois ; et une liste tronquée
//! donne l'impression d'avancer alors qu'on recommence.

mod error;
mod model;
mod repair;
mod rules;

pub use error::{Located, ValidationError, Violation};
pub use model::{AssetView, NamedEntry};
pub use repair::{recompute_normal, renormalize_weights, Repair, RepairLog};
pub use rules::validate;

/// Issue d'une validation.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct ValidationReport {
    /// Violations constatées, dans l'ordre où elles ont été trouvées.
    pub errors: Vec<ValidationError>,
}

impl ValidationReport {
    /// Indique si l'asset est accepté.
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.errors.is_empty()
    }

    /// Nombre de violations.
    #[must_use]
    pub fn len(&self) -> usize {
        self.errors.len()
    }

    /// Indique si le rapport est vide.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.errors.is_empty()
    }

    /// Code de l'ANNEXE A.1 de la première violation, ou `0` si aucune.
    ///
    /// La première, et non la plus grave : les violations n'ont pas de gravités
    /// relatives, et en inventer une reviendrait à décider que certaines
    /// comptent moins.
    #[must_use]
    pub fn code(&self) -> i32 {
        self.errors.first().map_or(0, ValidationError::code)
    }

    pub(crate) fn push(&mut self, violation: Violation, at: Located) {
        self.errors.push(ValidationError::new(violation, at));
    }
}
