//! C-10 — contexte natif, handles et états du runtime.
//!
//! Deux garanties structurantes vivent ici.
//!
//! [`HandleTable`] désigne les objets natifs par des [`Handle`] opaques plutôt
//! que par des pointeurs : R-450 interdit d'exposer un pointeur brut à travers
//! la frontière FFI. Les slots sont recyclés, et chaque recyclage incrémente
//! une génération, de sorte qu'un handle rendu est détecté comme périmé au lieu
//! de désigner silencieusement l'objet qui a pris sa place (INV-09).
//!
//! [`ContextGuard`] garantit l'unicité du contexte natif dans le processus :
//! une seconde initialisation est refusée avec `E-1004` (R-450).
//!
//! Exigences : R-450, R-311. Invariants : INV-09. Tests : T-150..T-152.

use core::fmt;
use core::marker::PhantomData;
use core::sync::atomic::{AtomicBool, Ordering};

/// État du runtime natif.
///
/// Les transitions sont décidées par le bootstrap (C-02) et par la machine de
/// dégradation SM-02 ; ce type ne fait que les nommer et porter la règle
/// d'acceptation des appels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RuntimeState {
    /// Fonctionnement nominal.
    Ready,
    /// Dégradé sous contrainte de budget : la simulation continue, à finesse
    /// réduite. La dégradation endort, diffère ou réduit la finesse ; elle ne
    /// supprime rien (R-1890).
    Degraded,
    /// Runtime actif, simulation et rendu minimaux. Déclenché par un
    /// dépassement persistant, une panic récupérée ou le watchdog. Reste
    /// jouable (R-1892).
    Safe,
    /// Un invariant interne n'a pas pu être restauré après une panic capturée.
    /// Le contexte refuse tout appel sauf l'arrêt et la lecture de la dernière
    /// erreur (R-311) ; Java bascule alors en [`RuntimeState::Disabled`].
    Poisoned,
    /// Runtime natif inutilisé. Les assemblies sont chargées inertes et leur
    /// NBT n'est pas modifié (R-410, INV-11).
    Disabled,
}

impl RuntimeState {
    /// Indique si le contexte accepte les appels ordinaires.
    ///
    /// R-311 : un contexte `POISONED` ne répond plus qu'à l'arrêt et à la
    /// lecture de la dernière erreur. Un contexte `DISABLED` n'est pas utilisé
    /// du tout.
    #[must_use]
    pub const fn accepts_calls(self) -> bool {
        matches!(
            self,
            RuntimeState::Ready | RuntimeState::Degraded | RuntimeState::Safe
        )
    }

    /// Indique si la simulation tourne, même à finesse réduite.
    #[must_use]
    pub const fn is_simulating(self) -> bool {
        matches!(self, RuntimeState::Ready | RuntimeState::Degraded)
    }

    /// Nom court, tel qu'il apparaît dans `/axion status` et les métriques.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            RuntimeState::Ready => "READY",
            RuntimeState::Degraded => "DEGRADED",
            RuntimeState::Safe => "SAFE",
            RuntimeState::Poisoned => "POISONED",
            RuntimeState::Disabled => "DISABLED",
        }
    }
}

impl fmt::Display for RuntimeState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.name())
    }
}

/// Erreur du cœur natif.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CoreError {
    /// Le contexte natif est déjà initialisé dans ce processus.
    ///
    /// Code `E-1004` (ANNEXE A.1). R-450 impose un contexte unique par
    /// processus.
    AlreadyInitialized,
    /// Le handle présenté ne désigne aucun objet vivant.
    ///
    /// Code `E-2001`. Soit l'objet a été libéré — le handle est alors périmé,
    /// même si son slot a été réattribué (INV-09) —, soit le handle n'a jamais
    /// été émis par cette table.
    InvalidHandle,
}

impl CoreError {
    /// Code d'erreur numérique de l'ANNEXE A.1.
    ///
    /// Les codes sont négatifs et rangés par domaine (DM-19) : `-1000..-1099`
    /// pour le bootstrap et le natif, `-2000..-2099` pour le runtime et les
    /// handles.
    #[must_use]
    pub const fn code(self) -> i32 {
        match self {
            CoreError::AlreadyInitialized => -1004,
            CoreError::InvalidHandle => -2001,
        }
    }
}

impl fmt::Display for CoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CoreError::AlreadyInitialized => {
                f.write_str("contexte natif déjà initialisé dans ce processus")
            }
            CoreError::InvalidHandle => f.write_str("handle invalide ou périmé"),
        }
    }
}

impl core::error::Error for CoreError {}

/// Référence opaque vers un objet détenu par une [`HandleTable`].
///
/// Un handle est un couple `(index, génération)`, jamais un pointeur : R-450
/// interdit d'exposer un pointeur brut, et un entier traverse la frontière FFI
/// sans que la validité de la mémoire dépende du bon vouloir de l'appelant.
///
/// Le paramètre `T` n'est présent que pour empêcher de présenter le handle d'un
/// asset là où l'on attend celui d'une assembly ; il ne change pas la
/// représentation.
pub struct Handle<T> {
    index: u32,
    generation: u32,
    // `fn() -> T` plutôt que `T` : le handle ne détient rien, et cette forme
    // reste `Send` et `Sync` quel que soit `T`.
    _marker: PhantomData<fn() -> T>,
}

impl<T> Handle<T> {
    /// Rang du slot dans la table.
    #[must_use]
    pub const fn index(self) -> u32 {
        self.index
    }

    /// Génération du slot au moment de l'émission.
    #[must_use]
    pub const fn generation(self) -> u32 {
        self.generation
    }

    /// Représentation entière du handle, pour la traversée FFI.
    ///
    /// L'index occupe les 32 bits de poids faible, la génération les 32 bits de
    /// poids fort.
    #[must_use]
    pub const fn to_raw(self) -> u64 {
        ((self.generation as u64) << 32) | self.index as u64
    }

    /// Reconstruit un handle depuis sa représentation entière.
    ///
    /// Rien n'est validé ici : un handle forgé par l'appelant est simplement un
    /// handle qui sera rejeté par la table (`E-2001`). C'est précisément
    /// pourquoi les tables valident à chaque accès plutôt que de faire
    /// confiance.
    #[must_use]
    pub const fn from_raw(raw: u64) -> Self {
        Self {
            index: raw as u32,
            generation: (raw >> 32) as u32,
            _marker: PhantomData,
        }
    }
}

// Ces implémentations sont écrites à la main : les dérives ajouteraient une
// borne `T: Clone`, `T: Debug`… alors qu'un handle ne détient aucun `T`.
impl<T> Clone for Handle<T> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<T> Copy for Handle<T> {}

impl<T> PartialEq for Handle<T> {
    fn eq(&self, other: &Self) -> bool {
        self.index == other.index && self.generation == other.generation
    }
}

impl<T> Eq for Handle<T> {}

impl<T> core::hash::Hash for Handle<T> {
    fn hash<H: core::hash::Hasher>(&self, state: &mut H) {
        self.index.hash(state);
        self.generation.hash(state);
    }
}

impl<T> fmt::Debug for Handle<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Handle({}, gen {})", self.index, self.generation)
    }
}

/// Un slot de la table : sa valeur si elle est vivante, et sa génération.
#[derive(Debug)]
struct Slot<T> {
    value: Option<T>,
    generation: u32,
}

/// Table d'objets natifs adressés par [`Handle`] (R-450).
///
/// # Exemple
///
/// ```
/// use ax_core::HandleTable;
///
/// let mut table: HandleTable<String> = HandleTable::new();
/// let a = table.insert("moteur".to_owned());
/// assert_eq!(table.get(a).unwrap(), "moteur");
///
/// // Un handle rendu est invalidé, et le slot recyclé sous une nouvelle
/// // génération : l'ancien handle ne désigne pas le nouvel objet.
/// table.remove(a).unwrap();
/// let b = table.insert("châssis".to_owned());
/// assert_eq!(a.index(), b.index());
/// assert!(table.get(a).is_err());
/// assert_eq!(table.get(b).unwrap(), "châssis");
/// ```
#[derive(Debug)]
pub struct HandleTable<T> {
    slots: Vec<Slot<T>>,
    free_list: Vec<u32>,
    live: usize,
}

impl<T> HandleTable<T> {
    /// Crée une table vide.
    #[must_use]
    pub const fn new() -> Self {
        Self {
            slots: Vec::new(),
            free_list: Vec::new(),
            live: 0,
        }
    }

    /// Crée une table dont la capacité initiale évite les réallocations.
    #[must_use]
    pub fn with_capacity(capacity: usize) -> Self {
        Self {
            slots: Vec::with_capacity(capacity),
            free_list: Vec::new(),
            live: 0,
        }
    }

    /// Insère une valeur et renvoie son handle.
    ///
    /// # Panics
    ///
    /// Si la table dépasse `u32::MAX` slots, ce qui excède de plusieurs ordres
    /// de grandeur tout budget d'objets natifs.
    pub fn insert(&mut self, value: T) -> Handle<T> {
        self.live += 1;

        if let Some(index) = self.free_list.pop() {
            let slot = &mut self.slots[index as usize];
            slot.value = Some(value);
            return Handle {
                index,
                generation: slot.generation,
                _marker: PhantomData,
            };
        }

        let index = u32::try_from(self.slots.len()).expect("nombre de handles au-delà de u32");
        self.slots.push(Slot {
            value: Some(value),
            generation: 0,
        });
        Handle {
            index,
            generation: 0,
            _marker: PhantomData,
        }
    }

    /// Emprunte la valeur désignée par `handle`.
    ///
    /// # Erreurs
    ///
    /// [`CoreError::InvalidHandle`] si le handle est périmé ou inconnu.
    pub fn get(&self, handle: Handle<T>) -> Result<&T, CoreError> {
        self.slots
            .get(handle.index as usize)
            .filter(|slot| slot.generation == handle.generation)
            .and_then(|slot| slot.value.as_ref())
            .ok_or(CoreError::InvalidHandle)
    }

    /// Emprunte en écriture la valeur désignée par `handle`.
    ///
    /// # Erreurs
    ///
    /// [`CoreError::InvalidHandle`] si le handle est périmé ou inconnu.
    pub fn get_mut(&mut self, handle: Handle<T>) -> Result<&mut T, CoreError> {
        self.slots
            .get_mut(handle.index as usize)
            .filter(|slot| slot.generation == handle.generation)
            .and_then(|slot| slot.value.as_mut())
            .ok_or(CoreError::InvalidHandle)
    }

    /// Retire la valeur désignée par `handle` et invalide le handle (INV-09).
    ///
    /// # Erreurs
    ///
    /// [`CoreError::InvalidHandle`] si le handle est périmé ou inconnu.
    pub fn remove(&mut self, handle: Handle<T>) -> Result<T, CoreError> {
        let slot = self
            .slots
            .get_mut(handle.index as usize)
            .filter(|slot| slot.generation == handle.generation)
            .ok_or(CoreError::InvalidHandle)?;

        let value = slot.value.take().ok_or(CoreError::InvalidHandle)?;
        // La génération avance dès la libération : tout handle déjà émis pour
        // ce slot devient périmé, même si le slot est réattribué aussitôt.
        slot.generation = slot.generation.wrapping_add(1);
        self.free_list.push(handle.index);
        self.live -= 1;
        Ok(value)
    }

    /// Indique si `handle` désigne une valeur vivante.
    #[must_use]
    pub fn contains(&self, handle: Handle<T>) -> bool {
        self.get(handle).is_ok()
    }

    /// Nombre de valeurs vivantes.
    #[must_use]
    pub const fn len(&self) -> usize {
        self.live
    }

    /// Indique si la table ne contient aucune valeur vivante.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.live == 0
    }

    /// Itère sur les valeurs vivantes et leur handle.
    pub fn iter(&self) -> impl Iterator<Item = (Handle<T>, &T)> {
        self.slots.iter().enumerate().filter_map(|(index, slot)| {
            let value = slot.value.as_ref()?;
            let index = u32::try_from(index).ok()?;
            Some((
                Handle {
                    index,
                    generation: slot.generation,
                    _marker: PhantomData,
                },
                value,
            ))
        })
    }

    /// Vide la table et invalide tous les handles émis.
    pub fn clear(&mut self) {
        for (index, slot) in self.slots.iter_mut().enumerate() {
            if slot.value.take().is_some() {
                slot.generation = slot.generation.wrapping_add(1);
                self.free_list
                    .push(u32::try_from(index).expect("nombre de handles au-delà de u32"));
            }
        }
        self.live = 0;
    }
}

impl<T> Default for HandleTable<T> {
    fn default() -> Self {
        Self::new()
    }
}

/// Marque d'occupation du contexte natif, à l'échelle du processus.
static CONTEXT_HELD: AtomicBool = AtomicBool::new(false);

/// Jeton attestant qu'un seul contexte natif existe dans ce processus (R-450).
///
/// La libération du jeton rouvre la possibilité d'initialiser à nouveau, ce qui
/// permet à un arrêt puis un redémarrage propres de fonctionner — le cas d'un
/// serveur qui recharge le mod.
#[derive(Debug)]
pub struct ContextGuard {
    // Le champ empêche la construction du jeton hors de ce module.
    _private: (),
}

impl ContextGuard {
    /// Prend le jeton de contexte.
    ///
    /// # Erreurs
    ///
    /// [`CoreError::AlreadyInitialized`] (`E-1004`) si un contexte est déjà
    /// vivant dans ce processus.
    pub fn acquire() -> Result<Self, CoreError> {
        CONTEXT_HELD
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| Self { _private: () })
            .map_err(|_| CoreError::AlreadyInitialized)
    }

    /// Indique si un contexte est actuellement vivant dans ce processus.
    #[must_use]
    pub fn is_held() -> bool {
        CONTEXT_HELD.load(Ordering::Acquire)
    }
}

impl Drop for ContextGuard {
    fn drop(&mut self) {
        CONTEXT_HELD.store(false, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // L'unicité du contexte est un état global au processus : son test vit
    // seul dans tests/context_unicite.rs, un binaire séparé. Deux tests
    // parallèles qui prennent le même jeton mesureraient les effets l'un de
    // l'autre.

    /// T-150 — un handle désigne la valeur insérée, et une valeur retirée
    /// n'est plus atteignable.
    #[test]
    fn insertion_lecture_retrait() {
        let mut table: HandleTable<u32> = HandleTable::new();
        assert!(table.is_empty());

        let a = table.insert(10);
        let b = table.insert(20);
        assert_eq!(table.len(), 2);
        assert_eq!(*table.get(a).unwrap(), 10);
        assert_eq!(*table.get(b).unwrap(), 20);

        *table.get_mut(a).unwrap() = 11;
        assert_eq!(*table.get(a).unwrap(), 11);

        assert_eq!(table.remove(a).unwrap(), 11);
        assert_eq!(table.len(), 1);
        assert_eq!(table.get(a).unwrap_err(), CoreError::InvalidHandle);
        assert_eq!(*table.get(b).unwrap(), 20);
    }

    /// T-151 / INV-09 — un slot recyclé porte une nouvelle génération : le
    /// handle rendu ne désigne jamais l'objet qui a pris sa place (R-450).
    #[test]
    fn slot_recycle_invalide_l_ancien_handle() {
        let mut table: HandleTable<&'static str> = HandleTable::new();

        let ancien = table.insert("roue avant gauche");
        table.remove(ancien).unwrap();

        let nouveau = table.insert("roue arrière droite");
        assert_eq!(ancien.index(), nouveau.index(), "slot non recyclé");
        assert_ne!(ancien.generation(), nouveau.generation());

        assert_eq!(table.get(ancien).unwrap_err(), CoreError::InvalidHandle);
        assert!(!table.contains(ancien));
        assert_eq!(*table.get(nouveau).unwrap(), "roue arrière droite");

        // Un double retrait est refusé, pas silencieusement toléré.
        assert_eq!(table.remove(ancien).unwrap_err(), CoreError::InvalidHandle);
    }

    /// T-152 — un handle forgé ou issu d'une autre table est rejeté ; c'est la
    /// contrepartie de l'interdiction faite à la FFI de faire confiance à
    /// l'appelant.
    #[test]
    fn handle_forge_est_rejete() {
        let mut table: HandleTable<u8> = HandleTable::new();
        let valide = table.insert(1);

        let hors_bornes: Handle<u8> = Handle::from_raw(u64::from(u32::MAX));
        assert_eq!(
            table.get(hors_bornes).unwrap_err(),
            CoreError::InvalidHandle
        );

        // Bon index, mauvaise génération.
        let mauvaise_generation: Handle<u8> =
            Handle::from_raw((7u64 << 32) | u64::from(valide.index()));
        assert_eq!(
            table.get(mauvaise_generation).unwrap_err(),
            CoreError::InvalidHandle
        );

        // L'aller-retour par la représentation entière préserve le handle.
        assert_eq!(Handle::<u8>::from_raw(valide.to_raw()), valide);
        assert_eq!(*table.get(valide).unwrap(), 1);
    }

    /// `clear` invalide tous les handles et remet la table à vide.
    #[test]
    fn clear_invalide_tous_les_handles() {
        let mut table: HandleTable<u32> = HandleTable::new();
        let handles: Vec<Handle<u32>> = (0..5).map(|v| table.insert(v)).collect();

        table.clear();

        assert!(table.is_empty());
        for handle in handles {
            assert_eq!(table.get(handle).unwrap_err(), CoreError::InvalidHandle);
        }
        assert_eq!(table.iter().count(), 0);
    }

    /// L'itération ne voit que les valeurs vivantes.
    #[test]
    fn iteration_sur_les_valeurs_vivantes() {
        let mut table: HandleTable<u32> = HandleTable::new();
        let a = table.insert(1);
        let _b = table.insert(2);
        let c = table.insert(3);
        table.remove(a).unwrap();

        let mut vus: Vec<u32> = table.iter().map(|(_, v)| *v).collect();
        vus.sort_unstable();
        assert_eq!(vus, [2, 3]);

        // Les handles rendus par l'itération sont utilisables.
        let via_iter: Vec<u32> = table.iter().map(|(h, _)| *table.get(h).unwrap()).collect();
        assert_eq!(via_iter.len(), 2);
        assert!(table.contains(c));
    }

    /// R-311 — un contexte `POISONED` n'accepte plus d'appel ordinaire, et
    /// `DISABLED` non plus.
    #[test]
    fn etats_du_runtime() {
        assert!(RuntimeState::Ready.accepts_calls());
        assert!(RuntimeState::Degraded.accepts_calls());
        assert!(RuntimeState::Safe.accepts_calls());
        assert!(!RuntimeState::Poisoned.accepts_calls());
        assert!(!RuntimeState::Disabled.accepts_calls());

        assert!(RuntimeState::Ready.is_simulating());
        assert!(RuntimeState::Degraded.is_simulating());
        // En mode SAFE, seules les assemblies pilotées par un joueur sont
        // simulées : ce n'est plus la simulation nominale.
        assert!(!RuntimeState::Safe.is_simulating());

        assert_eq!(RuntimeState::Poisoned.name(), "POISONED");
    }

    /// Les codes d'erreur sont ceux de l'ANNEXE A.1, dans les plages de DM-19.
    #[test]
    fn codes_d_erreur_conformes() {
        assert_eq!(CoreError::AlreadyInitialized.code(), -1004);
        assert_eq!(CoreError::InvalidHandle.code(), -2001);
    }
}
