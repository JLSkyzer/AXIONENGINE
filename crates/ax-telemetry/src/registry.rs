//! Registre de métriques (R-500).

use core::fmt;
use core::sync::atomic::{AtomicU64, Ordering};
use std::collections::BTreeMap;

/// Ce qu'une métrique mesure.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum MetricKind {
    /// Une quantité qui ne fait que croître : opérations, erreurs, panics.
    Counter,
    /// Une valeur instantanée, qui monte et descend : mémoire, occupation.
    Gauge,
    /// Une durée, dont on retient le cumul, le nombre de mesures et le maximum.
    Duration,
}

impl MetricKind {
    /// Nom du type dans l'export.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            MetricKind::Counter => "counter",
            MetricKind::Gauge => "gauge",
            MetricKind::Duration => "duration",
        }
    }
}

/// Ce qui empêche d'enregistrer une métrique.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TelemetryError {
    /// Le nom ne suit pas la convention `axion.<domaine>.<mesure>`.
    InvalidName(String),
    /// Une métrique de ce nom existe déjà.
    Duplicate(String),
}

impl fmt::Display for TelemetryError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TelemetryError::InvalidName(name) => {
                write!(formatter, "nom de métrique invalide : {name}")
            }
            TelemetryError::Duplicate(name) => write!(formatter, "métrique déjà déclarée : {name}"),
        }
    }
}

impl std::error::Error for TelemetryError {}

/// Désignation d'une métrique enregistrée.
///
/// C'est un index, pas un nom : mesurer ne doit pas coûter une recherche par
/// chaîne. R-501 plafonne le coût de la télémétrie à 1 % du composant mesuré,
/// et une table de hachage consultée à chaque tick n'y tiendrait pas.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct MetricId(usize);

/// Compteurs d'une métrique.
///
/// Ils sont atomiques et manipulés en `Relaxed` : plusieurs workers les
/// alimentent, et une métrique n'ordonne rien — elle compte.
#[derive(Debug)]
struct Entry {
    name: String,
    kind: MetricKind,
    unit: &'static str,
    value: AtomicU64,
    count: AtomicU64,
    max: AtomicU64,
}

/// État d'une métrique à un instant donné.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetricSnapshot {
    /// Nom complet, `axion.<domaine>.<mesure>`.
    pub name: String,
    /// Ce que la métrique mesure.
    pub kind: MetricKind,
    /// Unité, telle que l'export l'écrit.
    pub unit: &'static str,
    /// Valeur : cumul pour un compteur ou une durée, valeur courante pour une
    /// jauge.
    pub value: u64,
    /// Nombre de mesures, pour une durée ; zéro pour les autres types.
    pub count: u64,
    /// Plus grande mesure, pour une durée ; zéro pour les autres types.
    pub max: u64,
}

impl MetricSnapshot {
    /// Moyenne des mesures d'une durée, ou `None` s'il n'y en a aucune.
    ///
    /// La moyenne n'est pas stockée : la déduire de deux compteurs exacts vaut
    /// mieux que d'entretenir une troisième valeur qui pourrait diverger.
    #[must_use]
    pub const fn mean(&self) -> Option<u64> {
        if self.count == 0 {
            None
        } else {
            Some(self.value / self.count)
        }
    }
}

/// Vérifie qu'un nom suit la convention du cahier des charges.
///
/// Les métriques y sont toutes écrites `axion.<domaine>.<mesure>`. Un nom hors
/// convention n'est pas corrigé : il est refusé. Une métrique mal nommée
/// resterait invisible de qui la cherche, ce qui est pire que son absence.
fn valid_name(name: &str) -> bool {
    let Some(rest) = name.strip_prefix("axion.") else {
        return false;
    };
    let segments: Vec<&str> = rest.split('.').collect();
    if segments.len() < 2 {
        return false;
    }
    segments.iter().all(|segment| {
        !segment.is_empty()
            && segment
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
    })
}

/// Registre en cours de constitution.
///
/// Les métriques se déclarent au démarrage, puis le registre est figé : après
/// [`TelemetryBuilder::build`], on mesure, on ne déclare plus. C'est ce qui
/// permet à [`MetricId`] d'être un simple index, et à l'export de produire
/// toujours les mêmes clés — un export dont les clés apparaissent en cours de
/// route est illisible pour qui le compare d'un jour à l'autre.
#[derive(Debug, Default)]
pub struct TelemetryBuilder {
    entries: Vec<Entry>,
    by_name: BTreeMap<String, MetricId>,
}

impl TelemetryBuilder {
    /// Crée un registre vide.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Déclare une métrique.
    ///
    /// # Errors
    ///
    /// [`TelemetryError::InvalidName`] si le nom ne suit pas la convention,
    /// [`TelemetryError::Duplicate`] s'il est déjà pris.
    pub fn register(
        &mut self,
        name: &str,
        kind: MetricKind,
        unit: &'static str,
    ) -> Result<MetricId, TelemetryError> {
        if !valid_name(name) {
            return Err(TelemetryError::InvalidName(name.to_owned()));
        }
        if self.by_name.contains_key(name) {
            return Err(TelemetryError::Duplicate(name.to_owned()));
        }

        let id = MetricId(self.entries.len());
        self.entries.push(Entry {
            name: name.to_owned(),
            kind,
            unit,
            value: AtomicU64::new(0),
            count: AtomicU64::new(0),
            max: AtomicU64::new(0),
        });
        self.by_name.insert(name.to_owned(), id);
        Ok(id)
    }

    /// Déclare un compteur.
    ///
    /// # Errors
    ///
    /// Voir [`TelemetryBuilder::register`].
    pub fn counter(&mut self, name: &str, unit: &'static str) -> Result<MetricId, TelemetryError> {
        self.register(name, MetricKind::Counter, unit)
    }

    /// Déclare une jauge.
    ///
    /// # Errors
    ///
    /// Voir [`TelemetryBuilder::register`].
    pub fn gauge(&mut self, name: &str, unit: &'static str) -> Result<MetricId, TelemetryError> {
        self.register(name, MetricKind::Gauge, unit)
    }

    /// Déclare une durée, en nanosecondes.
    ///
    /// # Errors
    ///
    /// Voir [`TelemetryBuilder::register`].
    pub fn duration(&mut self, name: &str) -> Result<MetricId, TelemetryError> {
        self.register(name, MetricKind::Duration, "ns")
    }

    /// Fige le registre.
    #[must_use]
    pub fn build(self) -> Telemetry {
        Telemetry {
            entries: self.entries,
            by_name: self.by_name,
        }
    }
}

/// Registre de métriques figé (C-15).
///
/// Toutes les mesures se font par `&self` : la télémétrie n'a jamais besoin
/// d'un accès exclusif, et l'exiger obligerait les composants à se synchroniser
/// pour compter.
#[derive(Debug)]
pub struct Telemetry {
    entries: Vec<Entry>,
    by_name: BTreeMap<String, MetricId>,
}

impl Telemetry {
    /// Commence la construction d'un registre.
    #[must_use]
    pub fn builder() -> TelemetryBuilder {
        TelemetryBuilder::new()
    }

    /// Nombre de métriques déclarées.
    #[must_use]
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Indique si aucune métrique n'est déclarée.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Retrouve une métrique par son nom.
    ///
    /// Réservé au diagnostic et aux tests : mesurer passe par le [`MetricId`]
    /// obtenu à la déclaration.
    #[must_use]
    pub fn id(&self, name: &str) -> Option<MetricId> {
        self.by_name.get(name).copied()
    }

    /// Ajoute à un compteur.
    pub fn add(&self, id: MetricId, amount: u64) {
        self.entries[id.0]
            .value
            .fetch_add(amount, Ordering::Relaxed);
    }

    /// Incrémente un compteur d'une unité.
    pub fn increment(&self, id: MetricId) {
        self.add(id, 1);
    }

    /// Fixe la valeur d'une jauge.
    pub fn set(&self, id: MetricId, value: u64) {
        self.entries[id.0].value.store(value, Ordering::Relaxed);
    }

    /// Enregistre une durée mesurée, en nanosecondes.
    ///
    /// Le maximum est tenu à jour sans verrou : deux mesures simultanées
    /// peuvent se croiser, la boucle rejoue alors avec la valeur qu'elle vient
    /// de lire.
    pub fn record(&self, id: MetricId, nanos: u64) {
        let entry = &self.entries[id.0];
        entry.value.fetch_add(nanos, Ordering::Relaxed);
        entry.count.fetch_add(1, Ordering::Relaxed);

        let mut observed = entry.max.load(Ordering::Relaxed);
        while nanos > observed {
            match entry.max.compare_exchange_weak(
                observed,
                nanos,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => break,
                Err(current) => observed = current,
            }
        }
    }

    /// État d'une métrique.
    #[must_use]
    pub fn snapshot(&self, id: MetricId) -> MetricSnapshot {
        let entry = &self.entries[id.0];
        MetricSnapshot {
            name: entry.name.clone(),
            kind: entry.kind,
            unit: entry.unit,
            value: entry.value.load(Ordering::Relaxed),
            count: entry.count.load(Ordering::Relaxed),
            max: entry.max.load(Ordering::Relaxed),
        }
    }

    /// État de toutes les métriques, dans l'ordre de déclaration.
    #[must_use]
    pub fn snapshots(&self) -> Vec<MetricSnapshot> {
        (0..self.entries.len())
            .map(|index| self.snapshot(MetricId(index)))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t200_un_compteur_cumule() {
        let mut builder = Telemetry::builder();
        let panics = builder
            .counter("axion.native.panics", "count")
            .expect("déclaration");
        let telemetry = builder.build();

        telemetry.increment(panics);
        telemetry.increment(panics);
        telemetry.add(panics, 3);

        let snapshot = telemetry.snapshot(panics);
        assert_eq!(snapshot.value, 5);
        assert_eq!(snapshot.kind, MetricKind::Counter);
        assert_eq!(snapshot.name, "axion.native.panics");
    }

    #[test]
    fn t200_une_jauge_remplace() {
        let mut builder = Telemetry::builder();
        let memoire = builder
            .gauge("axion.mem.native_bytes", "bytes")
            .expect("déclaration");
        let telemetry = builder.build();

        telemetry.set(memoire, 4_096);
        telemetry.set(memoire, 1_024);

        assert_eq!(telemetry.snapshot(memoire).value, 1_024);
    }

    #[test]
    fn t200_une_duree_retient_cumul_nombre_et_maximum() {
        let mut builder = Telemetry::builder();
        let tick = builder.duration("axion.sim.tick_ns").expect("déclaration");
        let telemetry = builder.build();

        telemetry.record(tick, 100);
        telemetry.record(tick, 300);
        telemetry.record(tick, 200);

        let snapshot = telemetry.snapshot(tick);
        assert_eq!(snapshot.value, 600);
        assert_eq!(snapshot.count, 3);
        assert_eq!(snapshot.max, 300);
        assert_eq!(snapshot.mean(), Some(200));
        assert_eq!(snapshot.unit, "ns");
    }

    #[test]
    fn t200_une_duree_sans_mesure_n_a_pas_de_moyenne() {
        let mut builder = Telemetry::builder();
        let tick = builder.duration("axion.sim.tick_ns").expect("déclaration");
        let telemetry = builder.build();

        // Inventer zéro serait annoncer une mesure qui n'a pas eu lieu.
        assert_eq!(telemetry.snapshot(tick).mean(), None);
    }

    #[test]
    fn t200_un_nom_hors_convention_est_refuse() {
        let mut builder = Telemetry::builder();

        for nom in [
            "panics",              // sans préfixe
            "axion.panics",        // un seul segment
            "axion.Native.panics", // majuscule
            "axion.native.",       // segment vide
            "axion.native-panics", // tiret
        ] {
            assert_eq!(
                builder.counter(nom, "count"),
                Err(TelemetryError::InvalidName(nom.to_owned())),
                "{nom} accepté"
            );
        }

        assert!(builder.counter("axion.native.panics", "count").is_ok());
    }

    #[test]
    fn t200_un_doublon_est_refuse() {
        let mut builder = Telemetry::builder();
        assert!(builder.counter("axion.native.panics", "count").is_ok());

        // Deux métriques d'un même nom rendraient l'export ambigu, et la
        // seconde déclaration écraserait silencieusement la première.
        assert_eq!(
            builder.counter("axion.native.panics", "count"),
            Err(TelemetryError::Duplicate("axion.native.panics".to_owned()))
        );
    }

    #[test]
    fn t200_les_metriques_se_retrouvent_par_leur_nom() {
        let mut builder = Telemetry::builder();
        let declare = builder
            .counter("axion.ffi.calls", "count")
            .expect("déclaration");
        let telemetry = builder.build();

        assert_eq!(telemetry.id("axion.ffi.calls"), Some(declare));
        assert_eq!(telemetry.id("axion.ffi.absente"), None);
        assert_eq!(telemetry.len(), 1);
        assert!(!telemetry.is_empty());
    }

    #[test]
    fn t200_les_mesures_concurrentes_ne_se_perdent_pas() {
        let mut builder = Telemetry::builder();
        let calls = builder
            .counter("axion.ffi.calls", "count")
            .expect("déclaration");
        let tick = builder.duration("axion.sim.tick_ns").expect("déclaration");
        let telemetry = std::sync::Arc::new(builder.build());

        // Huit threads mesurent en même temps : c'est le cas courant, les
        // workers du pool de jobs alimentant les mêmes métriques.
        std::thread::scope(|scope| {
            for thread in 0..8_u64 {
                let telemetry = std::sync::Arc::clone(&telemetry);
                scope.spawn(move || {
                    for step in 0..1_000_u64 {
                        telemetry.increment(calls);
                        telemetry.record(tick, thread * 1_000 + step);
                    }
                });
            }
        });

        assert_eq!(telemetry.snapshot(calls).value, 8_000);
        assert_eq!(telemetry.snapshot(tick).count, 8_000);
        assert_eq!(telemetry.snapshot(tick).max, 7_999);
    }

    #[test]
    fn t200_l_ordre_de_declaration_est_celui_de_l_export() {
        let mut builder = Telemetry::builder();
        builder
            .counter("axion.b.second", "count")
            .expect("déclaration");
        builder
            .counter("axion.a.premier", "count")
            .expect("déclaration");
        let telemetry = builder.build();

        let noms: Vec<String> = telemetry
            .snapshots()
            .into_iter()
            .map(|snapshot| snapshot.name)
            .collect();
        assert_eq!(noms, ["axion.b.second", "axion.a.premier"]);
    }
}
