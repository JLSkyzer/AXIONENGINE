//! IF-02 — anneaux de transfert entre Java et le natif.
//!
//! Rust possède les tampons ; Java n'en détient que des vues
//! (`DirectByteBuffer`) et ne les libère jamais (R-320). Un tampon peut être
//! réalloué entre deux ticks : sa **génération** change alors, et Java doit
//! ré-acquérir. L'usage d'une vue périmée est refusé plutôt que suivi (R-270,
//! `E-2002`).
//!
//! # Alignement
//!
//! Les charges utiles contiennent des `f32`, des `f64` et des matrices : le
//! stockage est alloué en `u128`, dont l'alignement de 16 octets convient à
//! tout ce qui transitera, SIMD compris. Un `Vec<u8>` n'aurait qu'un alignement
//! de 1, et une lecture de `f64` sur une adresse impaire est au mieux lente, au
//! pire fautive selon l'architecture.
//!
//! # Durée de vie des pointeurs
//!
//! Une réallocation libère l'ancien bloc : le pointeur que Java détenait
//! devient invalide. C'est précisément ce que la génération signale, et
//! pourquoi le protocole impose d'acquérir avant d'écrire, à chaque tick.
//!
//! Exigences : R-270, R-271, R-320.

use ax_model::buffer::{BufferKind, HEADER_BYTES};

/// Unité d'allocation du stockage, choisie pour son alignement de 16 octets.
type Chunk = u128;

const CHUNK_BYTES: usize = core::mem::size_of::<Chunk>();

/// Un tampon partagé avec Java.
#[derive(Debug)]
pub struct SharedBuffer {
    storage: Vec<Chunk>,
    capacity: usize,
    generation: u32,
}

impl SharedBuffer {
    /// Alloue un tampon d'au moins `capacity` octets.
    fn with_capacity(capacity: usize, generation: u32) -> Self {
        let chunks = capacity.div_ceil(CHUNK_BYTES).max(1);
        Self {
            storage: vec![0; chunks],
            capacity: chunks * CHUNK_BYTES,
            generation,
        }
    }

    /// Capacité utilisable, en octets.
    ///
    /// Peut dépasser ce qui a été demandé : l'allocation est arrondie à l'unité
    /// de stockage.
    #[must_use]
    pub const fn capacity(&self) -> usize {
        self.capacity
    }

    /// Génération courante.
    #[must_use]
    pub const fn generation(&self) -> u32 {
        self.generation
    }

    /// Adresse du premier octet, telle qu'elle est transmise à Java.
    ///
    /// Le pointeur reste valide jusqu'à la prochaine réallocation de ce kind,
    /// que la génération signale.
    #[must_use]
    pub fn as_ptr(&mut self) -> *mut u8 {
        self.storage.as_mut_ptr().cast::<u8>()
    }

    /// Vue en écriture sur le contenu.
    #[must_use]
    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        let capacity = self.capacity;
        // SAFETY: le stockage fait exactement `capacity` octets contigus, et
        // `u8` n'impose aucun alignement.
        #[allow(unsafe_code)]
        unsafe {
            core::slice::from_raw_parts_mut(self.storage.as_mut_ptr().cast::<u8>(), capacity)
        }
    }

    /// Vue en lecture sur le contenu.
    #[must_use]
    pub fn as_slice(&self) -> &[u8] {
        // SAFETY: mêmes garanties que `as_mut_slice`.
        #[allow(unsafe_code)]
        unsafe {
            core::slice::from_raw_parts(self.storage.as_ptr().cast::<u8>(), self.capacity)
        }
    }
}

/// Description d'un tampon, telle qu'elle traverse la frontière.
///
/// Correspond au `AxionBufferInfo` de IF-02.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BufferInfo {
    /// Adresse du premier octet.
    pub ptr: *mut u8,
    /// Capacité utilisable, en octets.
    pub capacity: u64,
    /// Nature du tampon.
    pub kind: u32,
    /// Génération : Java ré-acquiert dès qu'elle change (R-270).
    pub generation: u32,
}

/// Ensemble des tampons d'une session, un par kind.
#[derive(Debug, Default)]
pub struct BufferPool {
    slots: [Option<SharedBuffer>; BufferKind::ALL.len()],
    reallocations: u64,
}

impl BufferPool {
    /// Crée un pool vide : rien n'est alloué avant la première demande.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    fn index(kind: BufferKind) -> usize {
        // Les kinds sont numérotés à partir de 1, zéro étant réservé aux
        // valeurs non initialisées.
        kind.as_u32() as usize - 1
    }

    /// Acquiert un tampon d'au moins `min_capacity` octets pour `kind`.
    ///
    /// La capacité demandée s'entend **en plus** de l'en-tête : l'appelant
    /// décrit sa charge utile, et le pool réserve la place de l'en-tête de 32
    /// octets qui la précède.
    ///
    /// Le tampon existant est conservé s'il est assez grand ; sinon il est
    /// remplacé et la génération avance, ce qui invalide la vue que Java
    /// détenait (R-270).
    pub fn acquire(&mut self, kind: BufferKind, min_capacity: u64) -> BufferInfo {
        let needed =
            HEADER_BYTES.saturating_add(usize::try_from(min_capacity).unwrap_or(usize::MAX));
        let slot = Self::index(kind);

        let reuse = self.slots[slot]
            .as_ref()
            .is_some_and(|buffer| buffer.capacity >= needed);

        if !reuse {
            let generation = match &self.slots[slot] {
                // La génération avance à chaque réallocation, sans jamais
                // repartir de zéro : une vue périmée reste détectable.
                Some(previous) => previous.generation.wrapping_add(1),
                None => 1,
            };
            if self.slots[slot].is_some() {
                self.reallocations += 1;
            }
            // On croît par doublement plutôt qu'au plus juste : sans cela, une
            // charge qui augmente d'un élément par tick réallouerait à chaque
            // tick, et invaliderait la vue de Java aussi souvent.
            let previous_capacity = self.slots[slot]
                .as_ref()
                .map_or(0, |buffer| buffer.capacity);
            let capacity = needed.max(previous_capacity.saturating_mul(2));
            self.slots[slot] = Some(SharedBuffer::with_capacity(capacity, generation));
        }

        let buffer = self.slots[slot].as_mut().expect("tampon fraîchement posé");
        BufferInfo {
            ptr: buffer.as_ptr(),
            capacity: buffer.capacity as u64,
            kind: kind.as_u32(),
            generation: buffer.generation,
        }
    }

    /// Libère le tampon de `kind`, si la génération correspond.
    ///
    /// Une génération périmée est refusée : elle signale que l'appelant
    /// raisonne sur un tampon qui n'existe plus (R-270).
    pub fn release(&mut self, kind: BufferKind, generation: u32) -> bool {
        let slot = Self::index(kind);
        match &self.slots[slot] {
            Some(buffer) if buffer.generation == generation => {
                self.slots[slot] = None;
                true
            }
            _ => false,
        }
    }

    /// Emprunte le tampon de `kind`, s'il est alloué.
    #[must_use]
    pub fn get_mut(&mut self, kind: BufferKind) -> Option<&mut SharedBuffer> {
        self.slots[Self::index(kind)].as_mut()
    }

    /// Emprunte la charge utile d'un tampon, en-tête exclu.
    ///
    /// Rend `None` si le tampon n'existe pas ou si la longueur demandée dépasse
    /// ce qu'il contient : une charge utile plus longue que son tampon est
    /// exactement ce que R-491 fait refuser.
    #[must_use]
    pub fn payload(&self, kind: BufferKind, len: u64) -> Option<&[u8]> {
        let buffer = self.slots[Self::index(kind)].as_ref()?;
        let start = HEADER_BYTES;
        let end = start.checked_add(usize::try_from(len).ok()?)?;
        buffer.as_slice().get(start..end)
    }

    /// Écrit une charge utile dans un tampon, en l'acquérant si besoin.
    ///
    /// Rend le nombre d'octets écrits. Le tampon est agrandi si nécessaire, ce
    /// qui fait avancer sa génération et invalide la vue que Java détenait
    /// (R-270) — c'est précisément pour cela qu'elle est réacquise à chaque
    /// tick.
    pub fn write_payload(&mut self, kind: BufferKind, bytes: &[u8]) -> Option<u64> {
        let len = u64::try_from(bytes.len()).ok()?;
        self.acquire(kind, len);

        let buffer = self.slots[Self::index(kind)].as_mut()?;
        let slice = buffer.as_mut_slice();
        let end = HEADER_BYTES.checked_add(bytes.len())?;
        slice.get_mut(HEADER_BYTES..end)?.copy_from_slice(bytes);
        Some(len)
    }

    /// Vérifie qu'une vue présentée par l'appelant est encore valide.
    #[must_use]
    pub fn is_current(&self, kind: BufferKind, generation: u32) -> bool {
        self.slots[Self::index(kind)]
            .as_ref()
            .is_some_and(|buffer| buffer.generation == generation)
    }

    /// Nombre total d'octets détenus par le pool.
    ///
    /// Alimente le budget de mémoire native : R-480 exige qu'aucune allocation
    /// n'échappe au comptage (INV-08).
    #[must_use]
    pub fn total_bytes(&self) -> usize {
        self.slots
            .iter()
            .filter_map(|slot| slot.as_ref())
            .map(SharedBuffer::capacity)
            .sum()
    }

    /// Nombre de réallocations depuis la création.
    ///
    /// Une valeur qui grimpe en régime établi signale un dimensionnement de
    /// lot mal choisi, pas une fatalité.
    #[must_use]
    pub const fn reallocations(&self) -> u64 {
        self.reallocations
    }

    /// Nombre de tampons encore detenus.
    #[must_use]
    pub fn live_buffers(&self) -> usize {
        self.slots.iter().filter(|slot| slot.is_some()).count()
    }

    /// Libère tous les tampons.
    pub fn clear(&mut self) {
        for slot in &mut self.slots {
            *slot = None;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn premiere_acquisition_alloue_et_aligne() {
        let mut pool = BufferPool::new();
        let info = pool.acquire(BufferKind::SimOut, 100);

        assert_eq!(info.kind, BufferKind::SimOut.as_u32());
        assert_eq!(info.generation, 1, "la première génération n'est pas nulle");
        assert!(!info.ptr.is_null());
        // La place de l'en-tête est réservée en plus de la charge utile.
        assert!(info.capacity >= 100 + HEADER_BYTES as u64);
        // L'alignement doit convenir aux f64 et au SIMD.
        assert_eq!(info.ptr as usize % 16, 0, "tampon mal aligné");
    }

    #[test]
    fn un_tampon_assez_grand_est_reutilise() {
        let mut pool = BufferPool::new();
        let first = pool.acquire(BufferKind::SimIn, 1024);
        let second = pool.acquire(BufferKind::SimIn, 512);

        // Même génération : la vue de Java reste valide, il n'a rien à refaire.
        assert_eq!(first.generation, second.generation);
        assert_eq!(first.ptr, second.ptr);
        assert_eq!(pool.reallocations(), 0);
    }

    #[test]
    fn une_demande_plus_grande_realloue_et_change_de_generation() {
        let mut pool = BufferPool::new();
        let first = pool.acquire(BufferKind::Events, 64);
        let second = pool.acquire(BufferKind::Events, 100_000);

        assert_ne!(first.generation, second.generation, "génération inchangée");
        assert!(second.capacity >= 100_000);
        assert_eq!(pool.reallocations(), 1);
        // R-270 : la vue précédente est périmée et doit être refusée.
        assert!(!pool.is_current(BufferKind::Events, first.generation));
        assert!(pool.is_current(BufferKind::Events, second.generation));
    }

    #[test]
    fn la_croissance_par_doublement_evite_les_reallocations_en_chaine() {
        let mut pool = BufferPool::new();
        pool.acquire(BufferKind::RenderOut, 1024);

        // Une charge qui croît d'un élément par tick ne doit pas réallouer à
        // chaque tick, sinon Java ré-acquiert sans arrêt.
        for extra in 1..64 {
            pool.acquire(BufferKind::RenderOut, 1024 + extra);
        }
        assert_eq!(pool.reallocations(), 1, "réallocations en chaîne");
    }

    #[test]
    fn la_liberation_exige_la_bonne_generation() {
        let mut pool = BufferPool::new();
        let info = pool.acquire(BufferKind::Persist, 32);

        assert!(!pool.release(BufferKind::Persist, info.generation ^ 0xFF));
        assert!(pool.release(BufferKind::Persist, info.generation));
        // Une seconde libération ne trouve plus rien.
        assert!(!pool.release(BufferKind::Persist, info.generation));
        assert_eq!(pool.total_bytes(), 0);
    }

    #[test]
    fn les_kinds_ne_partagent_pas_leur_stockage() {
        let mut pool = BufferPool::new();
        let sim = pool.acquire(BufferKind::SimIn, 64);
        let render = pool.acquire(BufferKind::RenderOut, 64);

        assert_ne!(sim.ptr, render.ptr);

        pool.get_mut(BufferKind::SimIn).unwrap().as_mut_slice()[0] = 0xAA;
        pool.get_mut(BufferKind::RenderOut).unwrap().as_mut_slice()[0] = 0xBB;

        assert_eq!(pool.get_mut(BufferKind::SimIn).unwrap().as_slice()[0], 0xAA);
        assert_eq!(
            pool.get_mut(BufferKind::RenderOut).unwrap().as_slice()[0],
            0xBB
        );
        assert!(pool.total_bytes() > 0);
    }

    #[test]
    fn le_contenu_ecrit_se_relit() {
        let mut pool = BufferPool::new();
        pool.acquire(BufferKind::DeformOut, 256);

        let buffer = pool.get_mut(BufferKind::DeformOut).unwrap();
        buffer.as_mut_slice()[..4].copy_from_slice(b"AXNB");
        assert_eq!(&buffer.as_slice()[..4], b"AXNB");

        // Un tampon neuf est remis à zéro : aucun résidu d'une charge
        // précédente ne peut être lu comme une donnée courante.
        pool.clear();
        pool.acquire(BufferKind::DeformOut, 256);
        assert_eq!(
            pool.get_mut(BufferKind::DeformOut).unwrap().as_slice()[0],
            0
        );
    }

    #[test]
    fn chaque_kind_a_son_slot() {
        let mut pool = BufferPool::new();
        for kind in BufferKind::ALL {
            let info = pool.acquire(kind, 16);
            assert_eq!(info.kind, kind.as_u32());
            assert!(pool.is_current(kind, info.generation), "{kind} non suivi");
        }
        assert!(pool.total_bytes() > 0);
        pool.clear();
        assert_eq!(pool.total_bytes(), 0);
    }
}
