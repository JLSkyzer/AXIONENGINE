//! C-13 — arènes mémoire et comptage des allocations natives.
//!
//! AXION classe sa mémoire native en quatre arènes de durées de vie
//! distinctes ([`ArenaClass`]). Chacune porte un budget et un compteur : R-480
//! exige qu'**aucune allocation native n'échappe au comptage**, et INV-08 en
//! fait un invariant vérifié par T-016.
//!
//! [`PageArena`] est l'allocateur de blocs de taille fixe exigé par R-481 pour
//! l'arène `DEFORM` : des pages de 1 KiB et une liste libre. Les pages libérées
//! sont recyclées, et leur identifiant est invalidé par un compteur de
//! génération (INV-09) : réutiliser un [`PageId`] rendu est une erreur
//! détectée, jamais une lecture silencieuse de la mémoire d'autrui.
//!
//! # Ce que ce crate ne fait pas
//!
//! R-481 décrit une chaîne complète en cas de dépassement du budget de
//! déformation : compactage des champs saturés, puis éviction des assemblies
//! les plus lointaines, puis refus. Les deux premières étapes relèvent de C-42
//! (jalon M6) : elles supposent de connaître les champs et les assemblies, que
//! l'allocateur ignore par construction. Ici, le dépassement refuse
//! l'allocation avec [`MemoryError::BudgetExceeded`] ; C-42 branchera ses
//! stratégies de récupération avant ce refus.
//!
//! Exigences : R-480, R-481, R-482. Invariants : INV-08, INV-09.
//! Tests : T-016, T-180, T-181.

use core::fmt;

/// Taille d'une page de l'arène de déformation, en octets (R-481).
pub const DEFORM_PAGE_BYTES: usize = 1024;

/// Classe d'arène, définie par la durée de vie de ce qu'elle contient (C-13).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ArenaClass {
    /// Assets, monde physique, graphes structurels. Vit tant que le contexte vit.
    Persistent,
    /// Champs de déformation. Budget propre, défragmentable (R-481).
    Deform,
    /// Données de rendu, remises à zéro à chaque frame.
    Frame,
    /// Données de travail d'un job, remises à zéro en fin de job.
    Scratch,
}

impl ArenaClass {
    /// Les quatre classes, dans l'ordre où le cahier des charges les énumère.
    pub const ALL: [ArenaClass; 4] = [
        ArenaClass::Persistent,
        ArenaClass::Deform,
        ArenaClass::Frame,
        ArenaClass::Scratch,
    ];

    /// Nom court de la classe, tel qu'il apparaît dans les métriques et les logs.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            ArenaClass::Persistent => "PERSISTENT",
            ArenaClass::Deform => "DEFORM",
            ArenaClass::Frame => "FRAME",
            ArenaClass::Scratch => "SCRATCH",
        }
    }
}

impl fmt::Display for ArenaClass {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.name())
    }
}

/// Erreur d'allocation native.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryError {
    /// Le budget de l'arène est atteint : l'allocation est refusée.
    ///
    /// Code `E-2004` (domaine mémoire, ANNEXE A.1). L'appelant qui dispose
    /// d'une stratégie de récupération — compactage, éviction — l'applique
    /// avant de propager cette erreur.
    BudgetExceeded {
        /// Arène concernée.
        class: ArenaClass,
        /// Nombre d'octets demandés.
        requested: usize,
        /// Nombre d'octets déjà consommés.
        used: usize,
        /// Plafond de l'arène.
        limit: usize,
    },
    /// Un identifiant de page périmé a été présenté (INV-09).
    ///
    /// Code `E-2001` (handle invalide). La page a été libérée, et son slot
    /// éventuellement réattribué : la génération portée par l'identifiant ne
    /// correspond plus à celle du slot.
    StalePage,
}

impl MemoryError {
    /// Code d'erreur numérique de l'ANNEXE A.1.
    ///
    /// Les codes sont négatifs et rangés par domaine (DM-19) : `-2000..-2099`
    /// couvre le runtime, les handles et la mémoire.
    #[must_use]
    pub const fn code(self) -> i32 {
        match self {
            MemoryError::BudgetExceeded { .. } => -2004,
            MemoryError::StalePage => -2001,
        }
    }
}

impl fmt::Display for MemoryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            MemoryError::BudgetExceeded {
                class,
                requested,
                used,
                limit,
            } => write!(
                f,
                "budget de l'arène {class} dépassé : {requested} octets demandés, \
                 {used} déjà utilisés sur {limit}"
            ),
            MemoryError::StalePage => f.write_str("identifiant de page périmé"),
        }
    }
}

impl core::error::Error for MemoryError {}

/// Compteur d'allocations d'une arène, borné par un budget (R-480, INV-08).
///
/// Le compteur ne détient aucune mémoire : il enregistre ce qui est alloué et
/// relâché ailleurs. C'est ce qui lui permet de couvrir aussi bien les pages de
/// [`PageArena`] que des allocations faites par une bibliothèque tierce.
#[derive(Debug, Clone)]
pub struct ArenaCounter {
    class: ArenaClass,
    limit_bytes: usize,
    used_bytes: usize,
    peak_bytes: usize,
    allocations: u64,
    releases: u64,
}

impl ArenaCounter {
    /// Crée un compteur pour `class`, plafonné à `limit_bytes`.
    #[must_use]
    pub const fn new(class: ArenaClass, limit_bytes: usize) -> Self {
        Self {
            class,
            limit_bytes,
            used_bytes: 0,
            peak_bytes: 0,
            allocations: 0,
            releases: 0,
        }
    }

    /// Enregistre une allocation de `bytes` octets.
    ///
    /// # Erreurs
    ///
    /// [`MemoryError::BudgetExceeded`] si l'allocation ferait dépasser le
    /// budget. Le compteur reste alors inchangé : un refus ne consomme rien.
    pub fn acquire(&mut self, bytes: usize) -> Result<(), MemoryError> {
        let after = self.used_bytes.saturating_add(bytes);
        if after > self.limit_bytes {
            return Err(MemoryError::BudgetExceeded {
                class: self.class,
                requested: bytes,
                used: self.used_bytes,
                limit: self.limit_bytes,
            });
        }
        self.used_bytes = after;
        self.peak_bytes = self.peak_bytes.max(after);
        self.allocations += 1;
        Ok(())
    }

    /// Enregistre la libération de `bytes` octets.
    ///
    /// # Panics
    ///
    /// Si l'on relâche plus que ce qui est compté. Un tel écart signale un
    /// double `release`, ou une libération jamais enregistrée à l'acquisition :
    /// c'est un défaut du comptage lui-même, qu'INV-08 interdit de laisser
    /// passer silencieusement.
    pub fn release(&mut self, bytes: usize) {
        assert!(
            bytes <= self.used_bytes,
            "arène {} : libération de {bytes} octets alors que {} sont comptés",
            self.class,
            self.used_bytes
        );
        self.used_bytes -= bytes;
        self.releases += 1;
    }

    /// Arène couverte par ce compteur.
    #[must_use]
    pub const fn class(&self) -> ArenaClass {
        self.class
    }

    /// Octets actuellement comptés comme alloués.
    #[must_use]
    pub const fn used_bytes(&self) -> usize {
        self.used_bytes
    }

    /// Maximum d'octets simultanément comptés depuis la création.
    #[must_use]
    pub const fn peak_bytes(&self) -> usize {
        self.peak_bytes
    }

    /// Plafond de l'arène, en octets.
    #[must_use]
    pub const fn limit_bytes(&self) -> usize {
        self.limit_bytes
    }

    /// Nombre d'allocations enregistrées.
    #[must_use]
    pub const fn allocations(&self) -> u64 {
        self.allocations
    }

    /// Nombre de libérations enregistrées.
    #[must_use]
    pub const fn releases(&self) -> u64 {
        self.releases
    }

    /// Indique si tout ce qui a été alloué a été relâché.
    ///
    /// R-482 : un bilan non nul à l'arrêt est un échec de test (T-016).
    #[must_use]
    pub const fn is_balanced(&self) -> bool {
        self.used_bytes == 0 && self.allocations == self.releases
    }
}

/// Identifiant d'une page allouée dans une [`PageArena`].
///
/// L'identifiant porte la génération du slot au moment de l'allocation.
/// Présenter un identifiant dont la page a été libérée est détecté et refusé
/// (INV-09), y compris après réattribution du slot.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct PageId {
    index: u32,
    generation: u32,
}

impl PageId {
    /// Rang du slot dans l'arène.
    #[must_use]
    pub const fn index(self) -> u32 {
        self.index
    }

    /// Génération du slot au moment de l'allocation.
    #[must_use]
    pub const fn generation(self) -> u32 {
        self.generation
    }
}

/// Un slot de l'arène : sa page, sa génération, son état.
#[derive(Debug)]
struct Slot {
    bytes: Box<[u8]>,
    generation: u32,
    live: bool,
}

/// Allocateur de blocs de taille fixe avec liste libre (R-481).
///
/// Les pages libérées ne sont pas rendues au système : elles retournent dans la
/// liste libre et sont recyclées, ce qui borne le nombre d'allocations système
/// au pic d'occupation plutôt qu'au nombre total de demandes.
///
/// # Exemple
///
/// ```
/// use ax_mem::{ArenaClass, PageArena, DEFORM_PAGE_BYTES};
///
/// // Deux pages de budget.
/// let mut arena = PageArena::new(ArenaClass::Deform, DEFORM_PAGE_BYTES, 2 * DEFORM_PAGE_BYTES);
///
/// let a = arena.allocate().unwrap();
/// arena.page_mut(a).unwrap()[0] = 42;
/// assert_eq!(arena.page(a).unwrap()[0], 42);
///
/// // Le budget est atteint après deux pages.
/// let b = arena.allocate().unwrap();
/// assert!(arena.allocate().is_err());
///
/// // Une page rendue est recyclée, et son identifiant devient invalide.
/// arena.release(a).unwrap();
/// assert!(arena.page(a).is_err());
/// let c = arena.allocate().unwrap();
/// assert_eq!(c.index(), a.index());
///
/// arena.release(b).unwrap();
/// arena.release(c).unwrap();
/// assert!(arena.counter().is_balanced());
/// ```
#[derive(Debug)]
pub struct PageArena {
    page_bytes: usize,
    slots: Vec<Slot>,
    free_list: Vec<u32>,
    counter: ArenaCounter,
}

impl PageArena {
    /// Crée une arène de pages de `page_bytes` octets, plafonnée à
    /// `limit_bytes`.
    ///
    /// # Panics
    ///
    /// Si `page_bytes` est nul : une arène de pages vides n'a pas de sens et
    /// rendrait le comptage d'octets inexploitable.
    #[must_use]
    pub fn new(class: ArenaClass, page_bytes: usize, limit_bytes: usize) -> Self {
        assert!(page_bytes > 0, "la taille de page doit être non nulle");
        Self {
            page_bytes,
            slots: Vec::new(),
            free_list: Vec::new(),
            counter: ArenaCounter::new(class, limit_bytes),
        }
    }

    /// Alloue une page, remise à zéro.
    ///
    /// Une page recyclée est effacée avant d'être rendue : le contenu d'un
    /// champ de déformation libéré ne peut pas réapparaître dans un autre.
    ///
    /// # Erreurs
    ///
    /// [`MemoryError::BudgetExceeded`] si le budget de l'arène est atteint.
    pub fn allocate(&mut self) -> Result<PageId, MemoryError> {
        self.counter.acquire(self.page_bytes)?;

        if let Some(index) = self.free_list.pop() {
            let slot = &mut self.slots[index as usize];
            slot.bytes.fill(0);
            slot.live = true;
            return Ok(PageId {
                index,
                generation: slot.generation,
            });
        }

        let index = u32::try_from(self.slots.len()).expect("nombre de pages au-delà de u32");
        self.slots.push(Slot {
            bytes: vec![0u8; self.page_bytes].into_boxed_slice(),
            generation: 0,
            live: true,
        });
        Ok(PageId {
            index,
            generation: 0,
        })
    }

    /// Rend une page à la liste libre et invalide son identifiant (INV-09).
    ///
    /// # Erreurs
    ///
    /// [`MemoryError::StalePage`] si la page a déjà été rendue, ou si
    /// l'identifiant provient d'une génération antérieure du slot.
    pub fn release(&mut self, id: PageId) -> Result<(), MemoryError> {
        let slot = self.live_slot_mut(id)?;
        slot.live = false;
        // La génération avance à la libération : tout identifiant déjà émis
        // devient périmé, y compris si le slot est réattribué juste après.
        slot.generation = slot.generation.wrapping_add(1);
        self.free_list.push(id.index);
        self.counter.release(self.page_bytes);
        Ok(())
    }

    /// Accès en lecture au contenu d'une page.
    ///
    /// # Erreurs
    ///
    /// [`MemoryError::StalePage`] si l'identifiant est périmé.
    pub fn page(&self, id: PageId) -> Result<&[u8], MemoryError> {
        let slot = self
            .slots
            .get(id.index as usize)
            .filter(|slot| slot.live && slot.generation == id.generation)
            .ok_or(MemoryError::StalePage)?;
        Ok(&slot.bytes)
    }

    /// Accès en écriture au contenu d'une page.
    ///
    /// # Erreurs
    ///
    /// [`MemoryError::StalePage`] si l'identifiant est périmé.
    pub fn page_mut(&mut self, id: PageId) -> Result<&mut [u8], MemoryError> {
        let slot = self.live_slot_mut(id)?;
        Ok(&mut slot.bytes)
    }

    /// Libère toutes les pages vivantes d'un coup.
    ///
    /// C'est l'opération de remise à zéro des arènes `FRAME` et `SCRATCH`, dont
    /// le contenu ne survit pas à une frame ou à un job.
    pub fn reset(&mut self) {
        for (index, slot) in self.slots.iter_mut().enumerate() {
            if slot.live {
                slot.live = false;
                slot.generation = slot.generation.wrapping_add(1);
                self.free_list
                    .push(u32::try_from(index).expect("nombre de pages au-delà de u32"));
                self.counter.release(self.page_bytes);
            }
        }
    }

    /// Compteur de l'arène, pour les métriques et le bilan de fin de vie.
    #[must_use]
    pub const fn counter(&self) -> &ArenaCounter {
        &self.counter
    }

    /// Taille d'une page, en octets.
    #[must_use]
    pub const fn page_bytes(&self) -> usize {
        self.page_bytes
    }

    /// Nombre de pages actuellement allouées.
    #[must_use]
    pub fn live_pages(&self) -> usize {
        self.slots.len() - self.free_list.len()
    }

    /// Nombre de pages détenues par l'arène, vivantes ou recyclables.
    ///
    /// C'est le pic d'occupation : il mesure ce que l'arène a réellement
    /// demandé au système.
    #[must_use]
    pub fn reserved_pages(&self) -> usize {
        self.slots.len()
    }

    fn live_slot_mut(&mut self, id: PageId) -> Result<&mut Slot, MemoryError> {
        self.slots
            .get_mut(id.index as usize)
            .filter(|slot| slot.live && slot.generation == id.generation)
            .ok_or(MemoryError::StalePage)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn deform_arena(pages: usize) -> PageArena {
        PageArena::new(
            ArenaClass::Deform,
            DEFORM_PAGE_BYTES,
            pages * DEFORM_PAGE_BYTES,
        )
    }

    /// Les quatre classes du cahier des charges sont présentes et nommées.
    #[test]
    fn les_quatre_classes_d_arene() {
        let names: Vec<&str> = ArenaClass::ALL.iter().map(|c| c.name()).collect();
        assert_eq!(names, ["PERSISTENT", "DEFORM", "FRAME", "SCRATCH"]);
    }

    /// T-180 — toute allocation est comptée, et le compteur suit les
    /// libérations (R-480, INV-08).
    #[test]
    fn toute_allocation_est_comptee() {
        let mut arena = deform_arena(4);
        assert_eq!(arena.counter().used_bytes(), 0);

        let a = arena.allocate().unwrap();
        let b = arena.allocate().unwrap();
        assert_eq!(arena.counter().used_bytes(), 2 * DEFORM_PAGE_BYTES);
        assert_eq!(arena.counter().allocations(), 2);
        assert_eq!(arena.live_pages(), 2);

        arena.release(a).unwrap();
        assert_eq!(arena.counter().used_bytes(), DEFORM_PAGE_BYTES);
        assert_eq!(arena.live_pages(), 1);

        arena.release(b).unwrap();
        assert_eq!(arena.counter().used_bytes(), 0);
        assert_eq!(arena.counter().peak_bytes(), 2 * DEFORM_PAGE_BYTES);
    }

    /// T-181 — le dépassement de budget refuse l'allocation sans rien
    /// consommer, avec le code de l'ANNEXE A.1 (R-481).
    #[test]
    fn depassement_de_budget_refuse_sans_consommer() {
        let mut arena = deform_arena(1);
        let page = arena.allocate().unwrap();

        let err = arena.allocate().unwrap_err();
        assert_eq!(err.code(), -2004);
        assert!(matches!(
            err,
            MemoryError::BudgetExceeded {
                class: ArenaClass::Deform,
                ..
            }
        ));
        // Un refus ne consomme rien et ne compte pas comme une allocation.
        assert_eq!(arena.counter().used_bytes(), DEFORM_PAGE_BYTES);
        assert_eq!(arena.counter().allocations(), 1);

        // Une fois la place rendue, l'allocation repasse.
        arena.release(page).unwrap();
        assert!(arena.allocate().is_ok());
    }

    /// INV-09 — un identifiant de page libérée est invalidé, y compris après
    /// réattribution du slot à une nouvelle page.
    #[test]
    fn identifiant_de_page_liberee_est_invalide() {
        let mut arena = deform_arena(2);
        let a = arena.allocate().unwrap();
        arena.page_mut(a).unwrap()[0] = 0xAB;

        arena.release(a).unwrap();
        assert_eq!(arena.page(a).unwrap_err(), MemoryError::StalePage);
        assert_eq!(arena.release(a).unwrap_err(), MemoryError::StalePage);

        // Le slot est recyclé : même rang, génération différente, contenu
        // effacé.
        let b = arena.allocate().unwrap();
        assert_eq!(b.index(), a.index());
        assert_ne!(b.generation(), a.generation());
        assert_eq!(arena.page(b).unwrap()[0], 0, "page recyclée non effacée");
        assert_eq!(arena.page(a).unwrap_err(), MemoryError::StalePage);
    }

    /// Une page recyclée ne provoque pas de nouvelle allocation système : la
    /// liste libre borne les réservations au pic d'occupation (R-481).
    #[test]
    fn la_liste_libre_recycle_les_pages() {
        let mut arena = deform_arena(8);
        for _ in 0..20 {
            let page = arena.allocate().unwrap();
            arena.release(page).unwrap();
        }
        assert_eq!(arena.reserved_pages(), 1);
        assert_eq!(arena.counter().allocations(), 20);
    }

    /// La remise à zéro d'une arène `FRAME` ou `SCRATCH` libère tout et laisse
    /// un bilan nul.
    #[test]
    fn reset_libere_toutes_les_pages() {
        let mut arena = PageArena::new(ArenaClass::Frame, 256, 4096);
        let pages: Vec<PageId> = (0..5).map(|_| arena.allocate().unwrap()).collect();

        arena.reset();

        assert_eq!(arena.live_pages(), 0);
        assert!(arena.counter().is_balanced());
        for page in pages {
            assert_eq!(arena.page(page).unwrap_err(), MemoryError::StalePage);
        }
    }

    /// T-016 / R-482 — le bilan doit être nul en fin de vie ; il ne l'est pas
    /// tant qu'une page reste vivante.
    #[test]
    fn bilan_nul_en_fin_de_vie() {
        let mut arena = deform_arena(4);
        assert!(arena.counter().is_balanced(), "arène neuve déséquilibrée");

        let page = arena.allocate().unwrap();
        assert!(!arena.counter().is_balanced(), "fuite non détectée");

        arena.release(page).unwrap();
        assert!(arena.counter().is_balanced());
    }
}
