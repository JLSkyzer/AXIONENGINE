//! Contexte natif et registre de session (C-10, C-14).
//!
//! L'ABI désigne le contexte par un `u64` et jamais par un pointeur : R-264
//! interdit de renvoyer un pointeur brut, et un entier ne permet pas à un
//! appelant fautif de déréférencer quoi que ce soit. Le jeton porte un motif
//! reconnaissable et un numéro de session, si bien qu'un jeton forgé, ou celui
//! d'une session déjà fermée, est rejeté au lieu de désigner le contexte
//! courant.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard, PoisonError};

use crate::asset_store::{AssetStore, LoadedAsset};
use ax_asset::a3d::A3dLimits;
use ax_asset::compile::{CompileError, CompiledAsset};
use ax_core::{BufferPool, ContextGuard, RuntimeState};
use ax_jobs::{JobBudgets, JobSystem, Side, WorkerPolicy};
use ax_jobs::{JobHandle, JobOutcome};
use ax_mem::{ArenaClass, ArenaCounter, MemoryError};
use ax_model::budgets::Budget;
use ax_model::buffer::BufferKind;
use ax_model::dm::handle::Handle;
use ax_physics::{SimDriver, SimSettings};
use ax_telemetry::{BudgetMetrics, MetricId, Telemetry};
use std::collections::HashMap;

/// Motif porté par les bits de poids fort d'un jeton de contexte.
///
/// « AXIO » en ASCII : un jeton nul, un pointeur recyclé ou un entier arbitraire
/// ne le portent pas, et sont rejetés avant toute autre vérification.
const TOKEN_TAG: u64 = 0x4158_494F_0000_0000;

/// Numéro de la prochaine session.
///
/// Il avance à chaque initialisation, y compris après un arrêt propre : le
/// jeton d'une session fermée ne peut donc jamais désigner la suivante.
static NEXT_SESSION: AtomicU64 = AtomicU64::new(1);

/// La session vivante, s'il y en a une.
static SESSION: Mutex<Option<Session>> = Mutex::new(None);

/// Motif du dernier refus survenu **avant** l'ouverture d'une session.
///
/// Une configuration refusée n'ouvre pas de session, et il n'y a donc nulle
/// part où ranger la cause. Sans cet emplacement, Java recevrait un code sans
/// message là où il en a le plus besoin — au démarrage, quand rien ne
/// fonctionne encore.
static INIT_ERROR: Mutex<Option<String>> = Mutex::new(None);

/// Retient le motif d'un refus survenu avant l'ouverture d'une session.
pub fn set_init_error(message: &str) {
    *recover(INIT_ERROR.lock()) = Some(message.to_owned());
}

/// Rend le motif du dernier refus survenu avant l'ouverture d'une session.
#[must_use]
pub fn init_error() -> Option<String> {
    recover(INIT_ERROR.lock()).clone()
}

/// Ce que la session mesure d'elle-même (C-15).
///
/// Les identifiants sont conservés à la déclaration : mesurer ne doit pas
/// coûter une recherche par nom (R-501).
#[derive(Debug)]
struct SessionMetrics {
    registry: Telemetry,
    budgets: BudgetMetrics,
    panics: MetricId,
    workers: MetricId,
    sim_unbalanced: MetricId,
    sim_worlds: MetricId,
}

/// État interne d'une session native.
#[derive(Debug)]
pub struct Session {
    token: u64,
    state: RuntimeState,
    last_error: Option<String>,
    panics: u64,
    buffers: BufferPool,
    /// Côté sur lequel tourne le runtime : il ne change pas la nature des
    /// travaux, seulement le plafond de workers par défaut (R-471).
    side: Side,
    /// Pool de jobs de la session (C-12). Ses threads vivent aussi longtemps
    /// qu'elle : les créer par travail coûterait plus cher que le travail.
    jobs: Option<JobSystem>,
    metrics: SessionMetrics,
    /// Compilations d'assets en vol, par identifiant de travail (IF-06).
    ///
    /// Les résultats y restent jusqu'à ce que Java vienne les chercher : R-472
    /// veut qu'un travail en dépassement soit repris au cycle suivant, et
    /// INV-07 interdit à Rust de rappeler Java pour le prévenir.
    asset_jobs: HashMap<u32, AssetJob>,
    /// Identifiant du prochain travail d'asset.
    next_asset_job: u32,
    /// Cycle de simulation (C-31, IF-03) : un monde physique par dimension.
    physics: SimDriver,
    /// Un `axion_sim_submit` est en cours, en attente de son `collect` (R-282).
    sim_pending: bool,
    /// Cycles `submit` clos implicitement faute de `collect` (R-282).
    sim_unbalanced: u64,
    /// Assets chargés (IF-06, ADR-119) : Rust les possède, Java n'en tient que
    /// des handles (§4.10).
    assets: AssetStore,
    /// Comptage de l'arène `PERSISTENT`, où s'imputent les assets chargés
    /// (C-13, R-480) ; plafonnée par `budgets.native_mem_bytes`.
    persistent: ArenaCounter,
    /// Plafonds de lecture des conteneurs A3D (`assets.max_compiled_bytes`,
    /// R-901).
    a3d_limits: A3dLimits,
    /// Détenu pour la durée de la session : c'est lui qui garantit l'unicité du
    /// contexte dans le processus (R-450).
    _guard: ContextGuard,
}

/// Plafonds des assets, connus dès l'ouverture d'une session (ADR-119).
#[derive(Debug, Clone, Copy)]
pub struct AssetLimits {
    /// Plafond d'un conteneur A3D comme d'une section (`assets.max_compiled_bytes`).
    pub container: A3dLimits,
    /// Plafond de l'arène `PERSISTENT`, en octets (`budgets.native_mem_bytes`).
    pub persistent_bytes: usize,
}

/// Une compilation d'asset, en vol ou terminée.
#[derive(Debug)]
pub enum AssetJob {
    /// Le travail est soumis ; son résultat n'a pas encore été repris.
    Running(JobHandle<Result<CompiledAsset, CompileError>>),
    /// Le travail a abouti ; l'asset attend d'être lu.
    Done(CompiledAsset),
    /// Le travail a échoué ; le code attend d'être lu.
    Failed(i32),
}

impl Session {
    /// État courant du runtime.
    #[must_use]
    pub fn state(&self) -> RuntimeState {
        self.state
    }

    /// Nombre de panics capturées depuis le démarrage.
    ///
    /// Alimente la métrique `axion.native.panics` (R-310).
    #[must_use]
    pub fn panics(&self) -> u64 {
        self.panics
    }

    /// Dernier message d'erreur, s'il y en a un.
    #[must_use]
    pub fn last_error(&self) -> Option<&str> {
        self.last_error.as_deref()
    }

    /// Enregistre un message d'erreur, qui remplace le précédent.
    pub fn set_last_error(&mut self, message: impl Into<String>) {
        self.last_error = Some(message.into());
    }

    /// Tampons de transfert de la session (IF-02).
    #[must_use]
    pub fn buffers(&mut self) -> &mut BufferPool {
        &mut self.buffers
    }

    /// Côté sur lequel tourne le runtime.
    #[must_use]
    pub fn side(&self) -> Side {
        self.side
    }

    /// Pilote du cycle de simulation (C-31, IF-03).
    #[must_use]
    pub fn physics(&mut self) -> &mut SimDriver {
        &mut self.physics
    }

    /// Indique si un `submit` attend son `collect`.
    #[must_use]
    pub fn sim_pending(&self) -> bool {
        self.sim_pending
    }

    /// Ouvre un cycle de simulation. Si un cycle était déjà ouvert sans avoir été
    /// collecté, il est clos implicitement et compté (R-282).
    pub fn open_sim_cycle(&mut self) {
        if self.sim_pending {
            self.sim_unbalanced += 1;
        }
        self.sim_pending = true;
    }

    /// Ferme le cycle de simulation courant.
    pub fn close_sim_cycle(&mut self) {
        self.sim_pending = false;
    }

    /// Nombre de cycles clos implicitement faute de `collect` (R-282).
    #[must_use]
    pub fn sim_unbalanced(&self) -> u64 {
        self.sim_unbalanced
    }

    /// Pool de jobs de la session, s'il a pu être créé.
    ///
    /// Son absence n'est pas une panne : R-2062 veut qu'un parallélisme
    /// indisponible allonge le calcul, pas qu'il retire une fonctionnalité. Le
    /// travail se fera alors sur le thread appelant.
    #[must_use]
    pub fn jobs(&self) -> Option<&JobSystem> {
        self.jobs.as_ref()
    }

    /// Produit l'export JSON des métriques (R-502).
    ///
    /// Les compteurs du système de jobs y sont reportés juste avant : ils
    /// vivent dans le pool, et les recopier en continu coûterait un travail
    /// permanent pour une lecture occasionnelle.
    #[must_use]
    pub fn metrics_json(&self) -> String {
        self.publish_metrics();
        ax_telemetry::to_json(&self.metrics.registry)
    }

    /// Reporte dans le registre ce que les autres composants ont mesuré.
    fn publish_metrics(&self) {
        let metrics = &self.metrics;
        metrics.registry.set(metrics.panics, self.panics);
        metrics
            .registry
            .set(metrics.sim_unbalanced, self.sim_unbalanced);
        metrics
            .registry
            .set(metrics.sim_worlds, self.physics.dimension_count() as u64);

        let Some(jobs) = self.jobs.as_ref() else {
            return;
        };
        metrics.registry.set(metrics.workers, jobs.workers() as u64);

        // La consommation d'un budget est la somme de ce qu'y ont passé les
        // types de travaux qui s'y imputent ; ses dépassements, la somme des
        // leurs.
        for budget in Budget::ALL {
            if !budget.is_duration() {
                continue;
            }
            let elapsed = jobs.metrics().budget_elapsed_nanos(budget);
            let overruns = jobs.metrics().budget_overruns(budget);
            metrics
                .registry
                .set(metrics.budgets.consumed_id(budget), elapsed);
            metrics
                .registry
                .set(metrics.budgets.overruns_id(budget), overruns);
        }
    }

    /// Retient une compilation d'asset et rend son identifiant (IF-06).
    ///
    /// L'identifiant repart à un pour chaque session et ne se réutilise jamais
    /// en son sein : un jeton périmé désigne alors « rien », plutôt que la
    /// compilation d'un autre asset.
    pub fn register_asset_job(
        &mut self,
        handle: JobHandle<Result<CompiledAsset, CompileError>>,
    ) -> u32 {
        let id = self.next_asset_job;
        self.next_asset_job = self.next_asset_job.wrapping_add(1).max(1);
        self.asset_jobs.insert(id, AssetJob::Running(handle));
        id
    }

    /// Fait avancer une compilation et rend son état.
    ///
    /// Reprendre le résultat du travail au plus tôt libère le worker ; le
    /// garder ici jusqu'à ce que Java vienne le chercher respecte INV-07, qui
    /// interdit à Rust de rappeler Java.
    pub fn poll_asset_job(&mut self, id: u32) -> Option<&AssetJob> {
        let entry = self.asset_jobs.get_mut(&id)?;
        if let AssetJob::Running(handle) = entry {
            if let Some(result) = handle.poll() {
                *entry = match result.outcome {
                    JobOutcome::Done(Ok(asset)) => AssetJob::Done(asset),
                    JobOutcome::Done(Err(error)) => AssetJob::Failed(error.code()),
                    // Un travail annulé ou paniqué ne rend pas d'asset : il est
                    // rapporté comme un échec, ce qu'il est du point de vue de
                    // l'appelant.
                    JobOutcome::Cancelled | JobOutcome::Panicked => {
                        AssetJob::Failed(crate::abi::AXION_E_PANIC)
                    }
                };
            }
        }
        self.asset_jobs.get(&id)
    }

    /// Oublie une compilation dont le résultat a été lu.
    pub fn forget_asset_job(&mut self, id: u32) {
        self.asset_jobs.remove(&id);
    }

    /// Plafonds de lecture des conteneurs A3D (R-901).
    #[must_use]
    pub fn a3d_limits(&self) -> A3dLimits {
        self.a3d_limits
    }

    /// Range un asset chargé et rend son handle, en imputant ses octets à
    /// l'arène `PERSISTENT` (R-480).
    ///
    /// # Errors
    ///
    /// [`MemoryError::BudgetExceeded`] (`E-2004`) si l'arène est pleine ; rien
    /// n'est alors rangé, ni compté.
    pub fn insert_asset(&mut self, asset: LoadedAsset) -> Result<Handle, MemoryError> {
        self.persistent.acquire(asset.resident_bytes())?;
        Ok(self.assets.insert(asset))
    }

    /// Dépose dans `ASSET_OUT` le transfert de géométrie d'un asset chargé
    /// (ADR-119 §3) et rend la taille de la charge utile.
    ///
    /// # Errors
    ///
    /// `E-2001` si le handle est périmé ; `E-2002` si l'asset n'a pas été chargé
    /// avec `NODE | GEOM`, ou si le tampon ne peut être écrit.
    pub fn write_geometry_transfer(&mut self, handle: Handle) -> Result<u64, i32> {
        let asset = self
            .assets
            .get(handle)
            .ok_or(crate::abi::AXION_E_INVALID_HANDLE)?;
        let transfer = asset
            .geometry_transfer()
            .ok_or(crate::abi::AXION_E_INVALID_BUFFER)?;
        self.buffers
            .write_payload(BufferKind::AssetOut, transfer)
            .ok_or(crate::abi::AXION_E_INVALID_BUFFER)
    }

    /// Dépose dans `ASSET_OUT` la table des matériaux et des textures d'un asset
    /// chargé (ADR-122 §6) et rend la taille de la charge utile.
    ///
    /// # Errors
    ///
    /// `E-2001` si le handle est périmé ; `E-2002` si l'asset n'a pas été chargé
    /// avec `MATL | TEXR`, ou si le tampon ne peut être écrit.
    pub fn write_material_transfer(&mut self, handle: Handle) -> Result<u64, i32> {
        let asset = self
            .assets
            .get(handle)
            .ok_or(crate::abi::AXION_E_INVALID_HANDLE)?;
        let transfer = asset
            .material_transfer()
            .ok_or(crate::abi::AXION_E_INVALID_BUFFER)?;
        self.buffers
            .write_payload(BufferKind::AssetOut, transfer)
            .ok_or(crate::abi::AXION_E_INVALID_BUFFER)
    }

    /// Dépose dans `ASSET_OUT` les octets PNG de la texture embarquée de rang
    /// `texture` d'un asset chargé (ADR-122 §6) et rend leur nombre.
    ///
    /// # Errors
    ///
    /// `E-2001` si le handle est périmé ; `E-2002` si la texture est hors de la
    /// table — `TEXR` non chargée comprise —, si elle n'est pas embarquée, ou si
    /// le tampon ne peut être écrit.
    pub fn write_embedded_texture(&mut self, handle: Handle, texture: u32) -> Result<u64, i32> {
        let asset = self
            .assets
            .get(handle)
            .ok_or(crate::abi::AXION_E_INVALID_HANDLE)?;
        let png = asset
            .embedded_texture(texture)
            .ok_or(crate::abi::AXION_E_INVALID_BUFFER)?;
        self.buffers
            .write_payload(BufferKind::AssetOut, png)
            .ok_or(crate::abi::AXION_E_INVALID_BUFFER)
    }

    /// Rend un asset chargé et libère ses octets ; faux si le handle est périmé,
    /// sans effet de bord (R-110).
    pub fn remove_asset(&mut self, handle: Handle) -> bool {
        match self.assets.remove(handle) {
            Some(asset) => {
                self.persistent.release(asset.resident_bytes());
                true
            }
            None => false,
        }
    }

    /// Enregistre une panic capturée et empoisonne le contexte.
    ///
    /// R-310 : la panic est comptée, et le contexte passe en `POISONED` dès
    /// lors que l'invariant interne n'est pas restaurable. Une panic traversant
    /// un point d'entrée signifie précisément qu'on ne sait pas dans quel état
    /// le travail s'est interrompu : le cas restaurable est l'exception, pas la
    /// règle, et le supposer serait le contraire de l'option conservatrice.
    pub fn record_panic(&mut self, message: impl Into<String>) {
        self.panics += 1;
        self.state = RuntimeState::Poisoned;
        self.last_error = Some(message.into());
    }
}

/// Empoisonnement du verrou de session.
///
/// Un `Mutex` empoisonné signale qu'un thread a paniqué en le tenant. On
/// récupère l'accès plutôt que de propager : la session porte déjà son propre
/// état `POISONED`, et refuser l'accès ici empêcherait `axion_shutdown` de
/// libérer quoi que ce soit — alors que R-311 exige qu'il reste possible.
fn recover<T>(result: Result<T, PoisonError<T>>) -> T {
    match result {
        Ok(value) => value,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn sessions() -> MutexGuard<'static, Option<Session>> {
    recover(SESSION.lock())
}

/// Ouvre une session et renvoie son jeton.
///
/// # Erreurs
///
/// Renvoie le code `E-1004` si une session est déjà ouverte dans ce processus
/// (R-450).
pub fn open(
    side: Side,
    workers: WorkerPolicy,
    budgets: JobBudgets,
    assets: AssetLimits,
    physics: SimSettings,
) -> Result<u64, i32> {
    let mut slot = sessions();
    if slot.is_some() {
        return Err(ax_core::CoreError::AlreadyInitialized.code());
    }
    // Le jeton d'unicité est pris avant toute autre chose : si un autre
    // composant détient déjà le contexte, l'ouverture échoue ici.
    let guard = ContextGuard::acquire().map_err(|error| error.code())?;

    let metrics = build_metrics();

    // Un pool qui refuse de naître ne fait pas échouer l'ouverture : R-2062
    // veut que le calcul soit plus long, pas absent. La cause est consignée.
    let (jobs, jobs_error) = match JobSystem::new(&workers, budgets) {
        Ok(system) => (Some(system), None),
        Err(error) => (None, Some(error.to_string())),
    };

    let session = NEXT_SESSION.fetch_add(1, Ordering::Relaxed);
    let token = TOKEN_TAG | session;
    *slot = Some(Session {
        token,
        state: RuntimeState::Ready,
        last_error: jobs_error,
        panics: 0,
        buffers: BufferPool::new(),
        side,
        jobs,
        metrics,
        asset_jobs: HashMap::new(),
        next_asset_job: 1,
        physics: SimDriver::with_settings(physics),
        sim_pending: false,
        sim_unbalanced: 0,
        assets: AssetStore::new(),
        persistent: ArenaCounter::new(ArenaClass::Persistent, assets.persistent_bytes),
        a3d_limits: assets.container,
        _guard: guard,
    });
    Ok(token)
}

/// Déclare les métriques de la session (C-15).
///
/// Les métriques de budget sont déclarées d'un bloc, pour tous les budgets du
/// registre : c'est ce qu'exige INV-19, et les déclarer une par une laisserait
/// la porte ouverte à l'oubli.
///
/// La déclaration ne peut échouer qu'en cas de nom invalide ou de doublon, deux
/// fautes de programmation que les tests attrapent ; l'échec est donc fatal ici
/// plutôt que propagé jusqu'à un appelant qui n'en pourrait rien faire.
fn build_metrics() -> SessionMetrics {
    let mut builder = Telemetry::builder();
    let budgets = BudgetMetrics::register(&mut builder).expect("métriques de budget");
    // R-310 : chaque panic capturée incrémente `axion.native.panics`.
    let panics = builder
        .gauge("axion.native.panics", "count")
        .expect("métrique de panics");
    // R-2060 : le nombre de workers retenu est visible, pas seulement décidé.
    let workers = builder
        .gauge("axion.jobs.workers", "count")
        .expect("métrique de workers");
    // R-282 : un `submit` sans `collect` clôt le cycle et se compte ici.
    let sim_unbalanced = builder
        .counter("axion.sim.unbalanced", "count")
        .expect("métrique de cycles déséquilibrés");
    // R-610 : les mondes physiques naissent au besoin et meurent sans objet ; leur
    // nombre se voit.
    let sim_worlds = builder
        .gauge("axion.sim.worlds", "count")
        .expect("métrique de mondes physiques");

    SessionMetrics {
        registry: builder.build(),
        budgets,
        panics,
        workers,
        sim_unbalanced,
        sim_worlds,
    }
}

/// Bilan des allocations au moment de l'arrêt (R-322).
///
/// Un bilan non nul ne signale pas une fuite mémoire — la session possède ses
/// tampons et les relâche en se fermant — mais un déséquilibre entre
/// acquisitions et libérations, c'est-à-dire un défaut d'usage de l'ABI. Le
/// taire reviendrait à laisser ce défaut grandir jusqu'à devenir une vraie
/// fuite quand les durées de vie se compliqueront.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AllocationBalance {
    /// Tampons encore détenus à la fermeture.
    pub live_buffers: usize,
    /// Octets correspondants.
    pub live_bytes: usize,
    /// Assets encore chargés à la fermeture : un handle que Java n'a pas rendu
    /// (R-321).
    pub live_assets: usize,
    /// Octets encore comptés dans l'arène `PERSISTENT` (INV-08).
    pub persistent_bytes: usize,
}

impl AllocationBalance {
    /// Indique si tout ce qui a été acquis a été relâché.
    #[must_use]
    pub const fn is_balanced(&self) -> bool {
        self.live_buffers == 0
            && self.live_bytes == 0
            && self.live_assets == 0
            && self.persistent_bytes == 0
    }
}

/// Ferme la session désignée et rend son bilan d'allocations.
///
/// La session est fermée dans tous les cas : un bilan non nul se signale, il
/// n'empêche pas l'arrêt.
///
/// # Erreurs
///
/// `E-2001` si le jeton ne désigne pas la session vivante.
pub fn close(token: u64) -> Result<AllocationBalance, i32> {
    let mut slot = sessions();
    match slot.as_mut() {
        Some(session) if session.token == token => {
            let balance = AllocationBalance {
                live_buffers: session.buffers.live_buffers(),
                live_bytes: session.buffers.total_bytes(),
                live_assets: session.assets.live(),
                persistent_bytes: session.persistent.used_bytes(),
            };
            *slot = None;
            Ok(balance)
        }
        _ => Err(ax_core::CoreError::InvalidHandle.code()),
    }
}

/// Indique si une session est ouverte.
#[must_use]
pub fn is_open() -> bool {
    sessions().is_some()
}

/// Exécute `action` sur la session désignée, si le jeton est valide.
///
/// `allow_poisoned` autorise l'accès à un contexte `POISONED`, que R-311
/// réserve à `axion_shutdown` et `axion_last_error`.
///
/// # Erreurs
///
/// `E-2001` si le jeton est inconnu, ou si le contexte est empoisonné et que
/// l'appel n'en fait pas partie.
pub fn with<R>(
    token: u64,
    allow_poisoned: bool,
    action: impl FnOnce(&mut Session) -> R,
) -> Result<R, i32> {
    let mut slot = sessions();
    let session = match slot.as_mut() {
        Some(session) if session.token == token => session,
        _ => return Err(ax_core::CoreError::InvalidHandle.code()),
    };
    if !allow_poisoned && !session.state.accepts_calls() {
        return Err(ax_core::CoreError::InvalidHandle.code());
    }
    Ok(action(session))
}

/// Réinitialise l'état global.
///
/// Réservé aux tests : les points d'entrée d'une même bibliothèque partagent le
/// registre, et un test qui ouvre une session doit pouvoir la refermer même
/// après un échec.
#[cfg(test)]
pub(crate) fn reset_for_tests() {
    *sessions() = None;
}

#[cfg(test)]
mod tests {
    /// Ouvre une session de test : un seul worker, aucun budget.
    ///
    /// Les tests de ce module portent sur le cycle de vie du contexte, pas sur
    /// le dimensionnement du pool ; un pool minimal suffit et reste rapide.
    fn open_for_test() -> Result<u64, i32> {
        let policy = WorkerPolicy {
            cores: 4,
            side: Side::Server,
            max_workers: 1,
            cpu_share: ax_jobs::CpuShare::Fixed(1),
            third_party_present: false,
        };
        let assets = AssetLimits {
            container: A3dLimits::new(1 << 24),
            persistent_bytes: 1 << 26,
        };
        open(
            Side::Server,
            policy,
            JobBudgets::new(),
            assets,
            SimSettings::default(),
        )
    }

    use super::*;

    /// Le registre est un état global au processus : les tests qui l'ouvrent
    /// et le ferment ne peuvent pas s'exécuter en parallèle sans se marcher
    /// dessus. Un seul test les enchaîne donc, dans un ordre maîtrisé.
    #[test]
    fn cycle_de_vie_d_une_session() {
        reset_for_tests();
        assert!(!is_open());

        let token = open_for_test().expect("première ouverture refusée");
        assert!(is_open());
        // Le jeton porte le motif : ni zéro, ni un petit entier.
        assert_eq!(token & 0xFFFF_FFFF_0000_0000, TOKEN_TAG);
        assert_ne!(token, 0);

        // R-450 : une seconde ouverture est refusée avec E-1004.
        assert_eq!(open_for_test().unwrap_err(), -1004);

        // Un jeton forgé ne désigne rien.
        for forge in [0, 1, token ^ 1, TOKEN_TAG] {
            assert_eq!(
                with(forge, false, |_| ()).unwrap_err(),
                -2001,
                "jeton {forge:#x} accepté à tort"
            );
        }

        with(token, false, |session| {
            assert_eq!(session.state(), RuntimeState::Ready);
            session.set_last_error("essai");
        })
        .unwrap();
        with(token, false, |session| {
            assert_eq!(session.last_error(), Some("essai"));
        })
        .unwrap();

        // R-311 : un contexte empoisonné refuse les appels ordinaires, mais
        // reste joignable pour l'arrêt et la lecture d'erreur.
        with(token, false, |session| {
            session.record_panic("panic simulée")
        })
        .unwrap();
        assert_eq!(with(token, false, |_| ()).unwrap_err(), -2001);
        with(token, true, |session| {
            assert_eq!(session.state(), RuntimeState::Poisoned);
            assert_eq!(session.panics(), 1);
            assert_eq!(session.last_error(), Some("panic simulée"));
        })
        .unwrap();

        // La fermeture n'accepte que le bon jeton.
        assert_eq!(close(token ^ 0xFF).unwrap_err(), -2001);
        close(token).expect("fermeture refusée");
        assert!(!is_open());

        // Le jeton d'une session fermée ne désigne plus rien, et la session
        // suivante en reçoit un différent.
        assert_eq!(with(token, true, |_| ()).unwrap_err(), -2001);
        let next = open_for_test().expect("réouverture refusée");
        assert_ne!(next, token, "jeton réutilisé d'une session à l'autre");
        close(next).unwrap();
    }
}
