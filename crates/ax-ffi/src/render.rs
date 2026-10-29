//! Contexte de rendu (IF-05, ADR-154 D2) : hors de la session, sous son propre verrou.
//!
//! En solo, le serveur intégré et le client partagent la session native, et le pas physique
//! tient le verrou de session pendant `axion_sim_collect`. Une préparation de rendu qui le
//! prendrait attendrait le pas, et R-300 ne tiendrait pas. Ce qui sert au rendu vit donc ici :
//! les tampons `RENDER_IN`, `RENDER_OUT` et `RENDER_DEBUG`, l'emprise de chaque asset chargé, la
//! frame en vol.
//!
//! # Ordre des verrous
//!
//! Le verrou de session puis celui-ci, jamais l'inverse : le chargement et le déchargement d'un
//! asset publient son emprise ici en tenant la session, l'ouverture et la fermeture aussi. Le
//! rendu ne prend **jamais** le verrou de session : il n'attend jamais la simulation, et le seul
//! qui puisse lui disputer ce verrou-ci est un chargement d'asset, section courte et bornée.
//!
//! # Les étapes côté client (R-662)
//!
//! La préparation d'une frame est le pas du client : elle échantillonne l'animation de chaque
//! retenu animé (étape 2) et propage son graphe (étape 14), et rien d'autre — l'intégration
//! physique et la chaîne de dommage (étapes 3 à 12) n'y existent pas. Elle journalise les étapes
//! qu'elle exécute, que T-392 vérifie, et publie `axion.scene.propagate_ns` dans le registre de la
//! session, partagé : sans en prendre le verrou.
//!
//! L'évaluation tient dans l'échéance de la frame (R-300, T-368) : les retenus animés s'évaluent
//! dans l'ordre de leur `id`, à partir de celui qui suit le dernier évalué, au moins un par frame ;
//! passée l'échéance, les autres gardent la pose de leur dernière évaluation, et la frame se dit
//! dégradée. La suivante reprend où celle-ci s'est arrêtée : aucune instance ne reste figée.
//!
//! # Une frame en vol
//!
//! `RENDER_OUT` est unique : son contenu vaut jusqu'à `axion_render_release` (R-301). Une
//! préparation qui arrive avant la libération de la frame précédente la clôt implicitement et
//! la compte, comme `axion_sim_submit` clôt un cycle non collecté (R-282).

use crate::abi::{AXION_E_INVALID_BUFFER, AXION_E_INVALID_HANDLE};
use crate::animation::{AnimatedAsset, Instance, PoseTimes};
use ax_asset::optimize::MAX_LOD_LEVELS;
use ax_core::BufferPool;
use ax_jobs::{JobKind, JobSystem};
use ax_math::{DVec3, Quat, Vec3};
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::animation::{anim_action, AnimCommand, RenderAnimate};
use ax_model::dm::debug::{debug_flags, debug_frame, encode_debug_payload, overlay};
use ax_model::dm::handle::Handle;
use ax_model::dm::limits::MAX_NODES;
use ax_physics::{select_drafts, DebugDraft, Stage, StageLog};
use ax_render_prep::lod::{biased, projected_px, LodState};
use ax_render_prep::{cull_batched, Camera, Candidate, CullStats, Frustum, LocalBounds, Visible};
use ax_scene::SceneMetrics;
use ax_telemetry::Telemetry;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

/// Le contexte de rendu vivant, s'il y en a un.
static RENDER: Mutex<Option<RenderContext>> = Mutex::new(None);

/// Drapeau de `AxionRenderResult` : le résultat est partiel, l'échéance est passée (R-300).
pub const AXION_RENDER_DEGRADED: u32 = 1 << 0;

/// Drapeau de `AxionRenderResult` : la frame dépassait `budgets.max_visible_instances` ; elle est
/// descendue d'un niveau de détail et ses retenus les plus lointains ont été écartés (étape 6).
pub const AXION_RENDER_CAPPED: u32 = 1 << 1;

/// Frames sans être examiné au bout desquelles la décision d'un candidat est oubliée.
const FORGET_DECISION_AFTER: u64 = 64;

/// Candidats traités d'un seul tenant ; au-delà, les lots suivants partent sur les workers.
const LOT: usize = 1024;

/// Taille de l'en-tête de frame de `RENDER_OUT` (schéma 4) : `frame_id`, retenus, meshes, drapeaux,
/// matrices, puis les retenus skinnés laissés au repos par `render.max_skinned_instances` (R-791).
pub const OUT_HEADER: usize = 24;

/// Taille d'un enregistrement retenu (schéma 4, décision de Killian) : index du candidat, `id`, LOD,
/// distance, premier mesh et nombre de meshes dans la table qui suit les enregistrements, première
/// matrice — [`NO_MATRICES`] pour un retenu au repos —, première matrice de sa palette d'os —
/// [`NO_PALETTE`] sans palette.
pub const OUT_RECORD: usize = 32;

/// Première matrice d'un retenu qui n'en porte pas : il se dessine au repos.
pub const NO_MATRICES: u32 = u32::MAX;

/// Première matrice de palette d'un retenu qui n'en a pas : sans skin, sans animation, ou au-delà de
/// `render.max_skinned_instances` (R-791) — ses meshes skinnés se dessinent au repos.
pub const NO_PALETTE: u32 = u32::MAX;

/// Ce que la configuration fixe au contexte de rendu.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RenderLimits {
    /// `render.max_skinned_instances` : retenus skinnés par frame (R-791) ; les plus proches d'abord.
    pub max_skinned_instances: u32,
}

/// Flottants d'une matrice `mat4x3` de la table de `RENDER_OUT`, un par dessin d'un retenu animé.
pub const MATRIX_FLOATS: usize = 12;

/// En-tête de frame de `RENDER_IN` (schéma 2), entre l'en-tête du tampon et les candidats : le
/// tick de rendu (`f64`, l'horloge du serveur interpolée, ADR-155), puis huit octets réservés.
pub const IN_FRAME_HEADER: usize = 16;

/// Ce que le rendu sait d'un asset chargé.
#[derive(Debug, Clone)]
struct RenderAsset {
    generation: u32,
    bounds: Option<LocalBounds>,
    triangles: u32,
    /// Niveaux de détail ; 1 sans table utilisable.
    levels: u8,
    /// Le mesh de chaque dessin au repos, à chaque niveau.
    draw_meshes: Arc<[[u32; MAX_LOD_LEVELS]]>,
    /// Les clips de l'asset, s'il en a (ADR-155).
    animation: Option<Arc<AnimatedAsset>>,
}

/// Les compteurs du contexte de rendu.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RenderStats {
    /// Frames préparées.
    pub frames: u64,
    /// Frames rendues partielles, l'échéance passée.
    pub degraded: u64,
    /// Frames closes implicitement, faute de libération.
    pub unreleased: u64,
    /// Ce que le culling a décidé de la dernière frame.
    pub last: CullStats,
    /// Durée de la dernière préparation, en nanosecondes.
    pub last_prepare_ns: u64,
    /// Assets dont la table `LODM` a été refusée : ils se rendent au LOD 0.
    pub lod_refused: u64,
    /// Frames plafonnées par `budgets.max_visible_instances` (étape 6).
    pub capped: u64,
    /// Assets dont la table `ANIM` a été refusée : ils se rendent au repos (ADR-155).
    pub anim_refused: u64,
    /// Retenus animés de la dernière frame.
    pub animated: u32,
    /// Assemblies animées que le contexte garde : une par `id`, retirées par `FORGET` ou avec leur asset.
    pub instances: u32,
    /// Commandes d'animation appliquées.
    pub anim_commands: u64,
    /// Commandes d'animation refusées : asset absent ou périmé, clip ou couche inconnus,
    /// paramètre hors bornes, plus de 256 pistes (R-200).
    pub anim_rejected: u64,
    /// Retenus skinnés de la dernière frame, leur palette posée.
    pub skinned: u32,
    /// Retenus skinnés laissés au repos par `render.max_skinned_instances`, depuis l'ouverture (R-791).
    pub skinned_capped: u64,
    /// Retenus animés qui ont gardé la pose de leur dernière évaluation, l'échéance de leur frame passée,
    /// depuis l'ouverture (R-300, T-368).
    pub animation_deferred: u64,
}

/// Ce que rend une préparation : les champs d'`AxionRenderResult` que la tranche 1 remplit.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Prepared {
    /// Candidats retenus, déposés dans `RENDER_OUT`.
    pub instance_count: u32,
    /// Triangles des retenus, au LOD 0.
    pub triangle_estimate: u32,
    /// Octets de la table de matrices des retenus animés.
    pub palette_bytes: u32,
    /// Identifiant de la frame, à rendre par `axion_render_release`.
    pub frame_id: u32,
    /// Drapeaux, voir [`AXION_RENDER_DEGRADED`].
    pub flags: u32,
}

/// Où le contexte de rendu publie ce qu'il mesure : le registre de la session, partagé — ses
/// métriques s'écrivent sans verrou (C-15).
#[derive(Debug, Clone)]
pub struct RenderTelemetry {
    /// Le registre de la session.
    pub registry: Arc<Telemetry>,
    /// `axion.scene.propagate_ns` (C-30).
    pub scene: SceneMetrics,
}

impl RenderTelemetry {
    /// Un registre à lui seul, pour un contexte de rendu ouvert hors de toute session.
    ///
    /// # Panics
    ///
    /// Jamais : un registre neuf accepte la déclaration.
    #[must_use]
    pub fn standalone() -> Self {
        let mut builder = Telemetry::builder();
        let scene = SceneMetrics::register(&mut builder).expect("registre neuf");
        Self {
            registry: Arc::new(builder.build()),
            scene,
        }
    }
}

/// La pose qu'une frame a donnée à une instance animée retenue : celle de son candidat.
#[derive(Debug, Clone, Copy)]
struct AnimatedPose {
    id: u32,
    position: Vec3,
    rotation: Quat,
    scale: f32,
}

/// L'état de rendu d'une session.
#[derive(Debug)]
pub struct RenderContext {
    token: u64,
    poisoned: bool,
    buffers: BufferPool,
    /// Par index de handle ; partagée avec les workers d'une frame.
    assets: Arc<Vec<Option<RenderAsset>>>,
    jobs: Option<Arc<JobSystem>>,
    next_frame: u32,
    in_flight: Option<u32>,
    stats: RenderStats,
    /// Le niveau de chaque candidat d'une frame à l'autre (hystérésis, R-770).
    lod: LodState,
    /// La dernière décision de chaque candidat examiné — retenu ou non —, et la frame où elle a été
    /// prise : un candidat que l'échéance laisse sans examen la reprend (R-300, R-770).
    decisions: HashMap<u32, (bool, u64)>,
    /// L'animation de chaque assembly animée, par `id` de candidat, avec l'asset qu'elle anime.
    instances: HashMap<u32, (Handle, Instance)>,
    /// Les instances animées retenues par la dernière frame préparée, à la pose de leur candidat : les
    /// overlays de l'animation les tracent (ADR-155, tranche 6b).
    animated_poses: Vec<AnimatedPose>,
    /// Les étapes du pas que la dernière frame a exécutées, et leur durée (R-661, R-662).
    stages: StageLog,
    /// L'`id` du dernier retenu animé évalué : la frame suivante commence après lui (T-368).
    animation_cursor: Option<u32>,
    telemetry: RenderTelemetry,
    limits: RenderLimits,
}

fn recover<T>(result: Result<T, PoisonError<T>>) -> T {
    result.unwrap_or_else(PoisonError::into_inner)
}

fn slot() -> MutexGuard<'static, Option<RenderContext>> {
    recover(RENDER.lock())
}

/// Ouvre le contexte de rendu de la session `token`. Appelé par l'ouverture de la session, qui
/// tient son verrou ; `telemetry` est son registre, partagé.
pub(crate) fn open(
    token: u64,
    jobs: Option<Arc<JobSystem>>,
    limits: RenderLimits,
    telemetry: RenderTelemetry,
) {
    *slot() = Some(RenderContext {
        token,
        poisoned: false,
        buffers: BufferPool::new(),
        assets: Arc::new(Vec::new()),
        jobs,
        next_frame: 1,
        in_flight: None,
        stats: RenderStats::default(),
        lod: LodState::new(),
        decisions: HashMap::new(),
        instances: HashMap::new(),
        animated_poses: Vec::new(),
        stages: StageLog::default(),
        animation_cursor: None,
        telemetry,
        limits,
    });
}

/// Ferme le contexte de rendu de la session `token` et rend ses tampons encore détenus :
/// nombre et octets, pour le bilan de l'arrêt (R-322).
pub(crate) fn close(token: u64) -> (usize, usize) {
    let mut guard = slot();
    match guard.as_ref() {
        Some(context) if context.token == token => {
            let held = (
                context.buffers.live_buffers(),
                context.buffers.total_bytes(),
            );
            *guard = None;
            held
        }
        _ => (0, 0),
    }
}

/// Exécute `action` sur le contexte de rendu de la session `token`.
///
/// # Errors
///
/// `E-2001` si le jeton ne désigne pas le contexte vivant, ou si la session est empoisonnée.
pub fn with<R>(token: u64, action: impl FnOnce(&mut RenderContext) -> R) -> Result<R, i32> {
    let mut guard = slot();
    match guard.as_mut() {
        Some(context) if context.token == token && !context.poisoned => Ok(action(context)),
        _ => Err(AXION_E_INVALID_HANDLE),
    }
}

/// Empoisonne le contexte de rendu avec sa session (R-310).
pub(crate) fn poison(token: u64) {
    if let Some(context) = slot().as_mut().filter(|context| context.token == token) {
        context.poisoned = true;
    }
}

/// Ce que le rendu publie d'un asset chargé.
pub(crate) struct Published<'a> {
    /// Sphère au repos.
    pub bounds: Option<LocalBounds>,
    /// Triangles au LOD 0.
    pub triangles: u32,
    /// Niveaux de détail.
    pub levels: u8,
    /// Le mesh de chaque dessin au repos, à chaque niveau.
    pub draw_meshes: &'a [[u32; MAX_LOD_LEVELS]],
    /// La table `LODM` a été refusée.
    pub lod_refused: bool,
    /// Les clips de l'asset.
    pub animation: Option<Arc<AnimatedAsset>>,
    /// La table `ANIM` a été refusée.
    pub anim_refused: bool,
}

/// Publie l'emprise d'un asset chargé, sous le handle qu'il vient de recevoir.
pub(crate) fn publish(token: u64, handle: Handle, published: Published<'_>) {
    let _ = with(token, |context| {
        if published.lod_refused {
            context.stats.lod_refused += 1;
        }
        if published.anim_refused {
            context.stats.anim_refused += 1;
        }
        let assets = Arc::make_mut(&mut context.assets);
        let index = handle.index as usize;
        if assets.len() <= index {
            assets.resize(index + 1, None);
        }
        assets[index] = Some(RenderAsset {
            generation: handle.generation,
            bounds: published.bounds,
            triangles: published.triangles,
            levels: published.levels,
            draw_meshes: published.draw_meshes.into(),
            animation: published.animation,
        });
    });
}

/// Retire l'emprise d'un asset déchargé.
pub(crate) fn withdraw(token: u64, handle: Handle) {
    let _ = with(token, |context| {
        let assets = Arc::make_mut(&mut context.assets);
        if let Some(entry) = assets.get_mut(handle.index as usize) {
            if entry
                .as_ref()
                .is_some_and(|asset| asset.generation == handle.generation)
            {
                *entry = None;
            }
        }
        // Une animation ne survit pas à son asset.
        context.instances.retain(|_, (asset, _)| *asset != handle);
    });
}

/// Le culling d'une tranche de candidats, `start` étant la place de son premier.
fn cull_lot(
    camera: &Camera,
    frustum: &Frustum,
    candidates: &[Candidate],
    start: usize,
    assets: &[Option<RenderAsset>],
) -> (Vec<Visible>, CullStats) {
    let mut visible = Vec::new();
    // Un index réemployé par un autre asset ne porte plus la génération du candidat : inconnu.
    let bounds = |candidate: &Candidate| {
        assets
            .get(candidate.asset as usize)
            .and_then(Option::as_ref)
            .filter(|a| a.generation == candidate.generation)
            .and_then(|a| a.bounds)
    };
    let stats = cull_batched(camera, frustum, candidates, bounds, &mut visible);
    for v in &mut visible {
        v.index += start as u32;
    }
    (visible, stats)
}

fn add(total: &mut CullStats, part: CullStats) {
    total.candidates += part.candidates;
    total.visible += part.visible;
    total.culled_distance += part.culled_distance;
    total.culled_frustum += part.culled_frustum;
    total.unknown_asset += part.unknown_asset;
}

impl RenderContext {
    /// Les tampons du rendu : `RENDER_IN` et `RENDER_OUT`.
    pub fn buffers(&mut self) -> &mut BufferPool {
        &mut self.buffers
    }

    /// Les compteurs.
    #[must_use]
    pub fn stats(&self) -> RenderStats {
        RenderStats {
            instances: u32::try_from(self.instances.len()).unwrap_or(u32::MAX),
            ..self.stats
        }
    }

    /// Lit les `count` candidats de `RENDER_IN`.
    /// Lit le tick de rendu et les candidats de `RENDER_IN` (schéma 2).
    fn read_candidates(&mut self, count: usize) -> Result<(f64, Vec<Candidate>), i32> {
        let raw = self
            .buffers
            .get_mut(BufferKind::RenderIn)
            .ok_or(AXION_E_INVALID_BUFFER)?
            .as_slice();
        // R-491 : l'en-tête est validé avant toute lecture d'un tampon fourni par Java.
        match BufferHeader::read(raw) {
            Ok(header) if header.kind == BufferKind::RenderIn => {}
            _ => return Err(AXION_E_INVALID_BUFFER),
        }
        let start = HEADER_BYTES + IN_FRAME_HEADER;
        let tick = raw
            .get(HEADER_BYTES..HEADER_BYTES + 8)
            .map(|bytes| f64::from_le_bytes(bytes.try_into().unwrap_or([0; 8])))
            .ok_or(AXION_E_INVALID_BUFFER)?;
        if count == 0 {
            return Ok((tick, Vec::new()));
        }
        let len = count
            .checked_mul(Candidate::BYTES)
            .ok_or(AXION_E_INVALID_BUFFER)?;
        let end = start.checked_add(len).ok_or(AXION_E_INVALID_BUFFER)?;
        let bytes = raw.get(start..end).ok_or(AXION_E_INVALID_BUFFER)?;
        let candidates = Candidate::read_all(bytes).map_err(|_| AXION_E_INVALID_BUFFER)?;
        Ok((tick, candidates))
    }

    /// Applique les commandes d'animation de `bytes` (`axion_render_animate`, ABI 10) : chacune un
    /// [`RenderAnimate`], une [`AnimCommand`], puis ses nodes de masque. Le tampon est validé entier
    /// avant que la première ne s'applique. Une commande sur un asset absent, périmé ou sans clips,
    /// ou que l'animation refuse, est comptée et ne change rien ; `FORGET` oublie l'instance.
    ///
    /// # Errors
    ///
    /// `E-2002` si le tampon est tronqué ou annonce un masque démesuré : rien n'est appliqué.
    pub fn animate(&mut self, bytes: &[u8]) -> Result<u32, i32> {
        const RECORD: usize = RenderAnimate::BYTES + AnimCommand::BYTES;
        let mut parsed = Vec::new();
        let mut at = 0;
        while at < bytes.len() {
            let record = bytes.get(at..at + RECORD).ok_or(AXION_E_INVALID_BUFFER)?;
            let u32_at = |offset: usize| {
                u32::from_le_bytes(record[offset..offset + 4].try_into().unwrap_or([0; 4]))
            };
            let f32_at = |offset: usize| f32::from_bits(u32_at(offset));
            let id = u32_at(0);
            let asset = Handle::new(u32_at(8), u32_at(12));
            let c = RenderAnimate::BYTES;
            let command = AnimCommand {
                tick: f64::from_le_bytes(record[c..c + 8].try_into().unwrap_or([0; 8])),
                action: u32_at(c + 8),
                layer: u32_at(c + 12),
                clip: u32_at(c + 16),
                weight: f32_at(c + 20),
                fade: f32_at(c + 24),
                speed: f32_at(c + 28),
                mask_count: u32_at(c + 32),
                origin: f32_at(c + 36),
            };
            let count = command.mask_count as usize;
            if count > MAX_NODES {
                return Err(AXION_E_INVALID_BUFFER);
            }
            let mask_end = at + RECORD + count * 4;
            let mask: Vec<u32> = bytes
                .get(at + RECORD..mask_end)
                .ok_or(AXION_E_INVALID_BUFFER)?
                .chunks_exact(4)
                .map(|word| u32::from_le_bytes([word[0], word[1], word[2], word[3]]))
                .collect();
            parsed.push((id, asset, command, mask));
            at = mask_end;
        }
        let mut applied = 0;
        for (id, handle, command, mask) in parsed {
            if command.action == anim_action::FORGET {
                self.instances.remove(&id);
                applied += 1;
                continue;
            }
            let animation = self
                .assets
                .get(handle.index as usize)
                .and_then(Option::as_ref)
                .filter(|asset| asset.generation == handle.generation)
                .and_then(|asset| asset.animation.clone());
            let Some(animation) = animation else {
                self.stats.anim_rejected += 1;
                continue;
            };
            let entry = self
                .instances
                .entry(id)
                .or_insert_with(|| (handle, Instance::new(&animation)));
            if entry.0 != handle {
                // L'assembly a changé d'asset : son animation repart de zéro.
                *entry = (handle, Instance::new(&animation));
            }
            match entry.1.apply(&animation, &command, &mask) {
                Ok(()) => {
                    self.stats.anim_commands += 1;
                    applied += 1;
                }
                Err(_) => self.stats.anim_rejected += 1,
            }
        }
        Ok(applied)
    }

    /// Prépare une frame : lit les candidats de `RENDER_IN`, les culle, dépose les retenus dans
    /// `RENDER_OUT`. Le premier lot se fait toujours ; les suivants partent sur les workers, ou se font
    /// ici sans eux, tant que l'échéance n'est pas passée. Un candidat d'un lot sans résultat à
    /// l'échéance reprend sa décision de la frame d'avant — retenu à son niveau, ou écarté — et un
    /// candidat jamais examiné est retenu au niveau le plus grossier : rien ne scintille ; la frame
    /// est marquée [`AXION_RENDER_DEGRADED`]. Au-delà de `max_instances`, toute la frame descend d'un
    /// niveau et les retenus les plus lointains sont écartés ([`AXION_RENDER_CAPPED`]).
    ///
    /// # Errors
    ///
    /// `E-2002` si la caméra, l'en-tête de `RENDER_IN` ou un candidat est invalide, ou si
    /// `RENDER_OUT` ne peut pas recevoir le résultat.
    pub fn prepare(&mut self, camera_desc: &[u8], budget_ns: u64) -> Result<Prepared, i32> {
        let started = Instant::now();
        let deadline = started + Duration::from_nanos(budget_ns);
        let camera = Camera::from_bytes(camera_desc).map_err(|_| AXION_E_INVALID_BUFFER)?;
        let (tick, candidates) = self.read_candidates(camera.candidate_count as usize)?;
        let candidates = Arc::new(candidates);
        let frustum = camera.frustum();
        let assets = Arc::clone(&self.assets);
        self.lod.begin_frame();

        let mut flags = 0;
        let mut stats = CullStats::default();
        // Les candidats d'un lot sans examen, retenus : ils reprennent leur niveau d'avant.
        let mut partial: Vec<u32> = Vec::new();
        // Les lots examinés, pour retenir la décision de chacun de leurs candidats.
        let mut examined: Vec<std::ops::Range<usize>> = Vec::new();
        let mut visible = Vec::new();
        let starts: Vec<usize> = (0..candidates.len()).step_by(LOT).collect();
        // Les lots au-delà du premier partent sur les workers ; sans pool, ils se font ici.
        let mut pending = Vec::new();
        for &start in starts.iter().skip(1) {
            let job = self.jobs.as_ref().map(|jobs| {
                let (candidates, assets) = (Arc::clone(&candidates), Arc::clone(&assets));
                jobs.submit(JobKind::Cull, move |_| {
                    let end = (start + LOT).min(candidates.len());
                    cull_lot(&camera, &frustum, &candidates[start..end], start, &assets)
                })
            });
            pending.push((start, job));
        }
        if !candidates.is_empty() {
            let end = LOT.min(candidates.len());
            let (lot, part) = cull_lot(&camera, &frustum, &candidates[..end], 0, &assets);
            visible.extend(lot);
            add(&mut stats, part);
        }
        for (start, job) in pending {
            let end = (start + LOT).min(candidates.len());
            let done = match job {
                Some(handle) => handle
                    .wait(deadline.saturating_duration_since(Instant::now()))
                    .and_then(|result| result.value()),
                None => (Instant::now() < deadline)
                    .then(|| cull_lot(&camera, &frustum, &candidates[start..end], start, &assets)),
            };
            if let Some((lot, part)) = done {
                visible.extend(lot);
                add(&mut stats, part);
                examined.push(start..end);
            } else {
                // Échéance passée : chaque candidat du lot reprend sa décision d'avant ; jamais
                // examiné, il est retenu. Rien ne disparaît, rien ne scintille.
                flags |= AXION_RENDER_DEGRADED;
                for (offset, candidate) in candidates[start..end].iter().enumerate() {
                    stats.candidates += 1;
                    if self
                        .decisions
                        .get(&candidate.id)
                        .is_some_and(|&(kept, _)| !kept)
                    {
                        continue;
                    }
                    stats.visible += 1;
                    partial.push(candidate.id);
                    visible.push(Visible {
                        index: (start + offset) as u32,
                        id: candidate.id,
                        distance: candidate.position.length(),
                    });
                }
            }
        }
        if !candidates.is_empty() {
            examined.push(0..LOT.min(candidates.len()));
        }
        // La décision de chaque candidat examiné est retenue pour une frame que l'échéance couperait.
        let frame = self.stats.frames;
        let kept: HashSet<u32> = visible.iter().map(|v| v.id).collect();
        for range in examined {
            for candidate in &candidates[range] {
                self.decisions
                    .insert(candidate.id, (kept.contains(&candidate.id), frame));
            }
        }
        self.decisions
            .retain(|_, &mut (_, seen)| frame - seen <= FORGET_DECISION_AFTER);

        // Étape 6 : au-delà du plafond, toute la frame descend d'un niveau, et les retenus les plus
        // lointains sont écartés — par distance, puis par `id` —, l'ordre des candidats gardé.
        let mut bias = camera.lod_bias;
        let cap = camera.max_instances as usize;
        if cap > 0 && visible.len() > cap {
            flags |= AXION_RENDER_CAPPED;
            self.stats.capped += 1;
            bias = bias.saturating_add(1);
            let mut ranked: Vec<usize> = (0..visible.len()).collect();
            ranked.sort_by(|&a, &b| {
                visible[a]
                    .distance
                    .total_cmp(&visible[b].distance)
                    .then(visible[a].id.cmp(&visible[b].id))
            });
            let mut keep = vec![false; visible.len()];
            for &rank in &ranked[..cap] {
                keep[rank] = true;
            }
            let mut rank = 0;
            visible.retain(|_| {
                rank += 1;
                keep[rank - 1]
            });
        }

        if self.in_flight.take().is_some() {
            self.stats.unreleased += 1;
        }
        let frame_id = self.next_frame;
        self.next_frame = self.next_frame.wrapping_add(1).max(1);
        let instance_count = u32::try_from(visible.len()).map_err(|_| AXION_E_INVALID_BUFFER)?;
        // Étape 4 : le niveau de chaque retenu, puis le mesh de chacun de ses dessins à ce niveau
        // (décision de Killian : `RENDER_OUT` porte le mesh de chaque dessin).
        let mut records = Vec::with_capacity(visible.len() * OUT_RECORD);
        let mut meshes: Vec<u32> = Vec::new();
        let mut matrices: Vec<f32> = Vec::new();
        let mut animated = 0u32;
        self.animated_poses.clear();
        // Étapes 2 et 14 (R-662) : chaque retenu animé s'évalue au tick de la frame, dans l'ordre de son
        // `id` à partir de celui qui suit le dernier évalué, tant que l'échéance le permet (R-300) — au
        // moins un par frame. Au-delà, il garde la pose de sa dernière évaluation : rien ne saute ni ne
        // disparaît, la frame se dit dégradée, et la suivante reprend où celle-ci s'est arrêtée (T-368).
        self.stages.reset();
        let mut steps = PoseTimes::default();
        let mut order: Vec<(u32, usize)> = visible
            .iter()
            .filter(|v| self.animates(&candidates[v.index as usize]))
            .map(|v| (v.id, v.index as usize))
            .collect();
        order.sort_unstable_by_key(|&(id, _)| id);
        let start = self
            .animation_cursor
            .map_or(0, |last| order.partition_point(|&(id, _)| id <= last));
        let mut evaluated = 0usize;
        for step in 0..order.len() {
            if step > 0 && Instant::now() >= deadline {
                break;
            }
            let (id, index) = order[(start + step) % order.len()];
            let Some(animation) = assets
                .get(candidates[index].asset as usize)
                .and_then(Option::as_ref)
                .and_then(|a| a.animation.as_ref())
            else {
                continue;
            };
            let Some((_, instance)) = self.instances.get_mut(&id) else {
                continue;
            };
            let times = instance.evaluate(animation, tick);
            self.telemetry
                .scene
                .record(&self.telemetry.registry, times.propagate_ns);
            steps.animation_ns = steps.animation_ns.saturating_add(times.animation_ns);
            steps.propagate_ns = steps.propagate_ns.saturating_add(times.propagate_ns);
            self.animation_cursor = Some(id);
            evaluated += 1;
        }
        if evaluated > 0 {
            self.stages.record(Stage::Animation, steps.animation_ns);
            self.stages.record(Stage::SceneGraph, steps.propagate_ns);
        }
        let deferred = order.len() - evaluated;
        if deferred > 0 {
            flags |= AXION_RENDER_DEGRADED;
            self.stats.animation_deferred += deferred as u64;
        }
        let mut triangles = 0u32;
        // R-791 : une palette aux `render.max_skinned_instances` retenus skinnés les plus proches ; les
        // autres se dessinent au repos, et sont comptés.
        let mut skinnable: Vec<(f32, u32, usize)> = visible
            .iter()
            .enumerate()
            .filter(|(_, v)| self.skinnable(&candidates[v.index as usize]))
            .map(|(rank, v)| (v.distance, v.id, rank))
            .collect();
        skinnable.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)));
        let allowed = (self.limits.max_skinned_instances as usize).min(skinnable.len());
        let mut palettes = vec![false; visible.len()];
        for &(_, _, rank) in &skinnable[..allowed] {
            palettes[rank] = true;
        }
        let skinned_capped = u32::try_from(skinnable.len() - allowed).unwrap_or(u32::MAX);
        let mut skinned = 0u32;
        for (rank, v) in visible.iter().enumerate() {
            let candidate = &candidates[v.index as usize];
            let asset = assets
                .get(candidate.asset as usize)
                .and_then(Option::as_ref)
                .filter(|a| a.generation == candidate.generation);
            let level = match asset {
                None => 0,
                Some(a) if partial.contains(&candidate.id) => {
                    match self.lod.remembered(candidate.id) {
                        Some(previous) => biased(previous, bias, a.levels),
                        // Un candidat nouveau d'un lot partiel : le niveau le plus grossier (D3).
                        None => a.levels.max(1) - 1,
                    }
                }
                Some(a) => {
                    let radius = a.bounds.map_or(0.0, |b| b.radius * candidate.scale);
                    let px = projected_px(&camera, radius, v.distance);
                    self.lod.level(candidate.id, px, a.levels, bias)
                }
            };
            let first = u32::try_from(meshes.len()).map_err(|_| AXION_E_INVALID_BUFFER)?;
            if let Some(a) = asset {
                triangles = triangles.saturating_add(a.triangles);
                meshes.extend(a.draw_meshes.iter().map(|row| row[usize::from(level)]));
            }
            let count = u32::try_from(meshes.len()).map_err(|_| AXION_E_INVALID_BUFFER)? - first;
            // L'animation d'un retenu qui en a une, sur l'asset qu'il dessine : une matrice par dessin.
            let mut matrix_first = NO_MATRICES;
            let mut palette_first = NO_PALETTE;
            let animation = asset.and_then(|a| a.animation.as_ref());
            if let (Some(animation), Some((handle, instance))) =
                (animation, self.instances.get(&candidate.id))
            {
                let current = Handle::new(candidate.asset, candidate.generation);
                if *handle == current && animation.draw_count() == count as usize {
                    matrix_first = u32::try_from(matrices.len() / MATRIX_FLOATS)
                        .map_err(|_| AXION_E_INVALID_BUFFER)?;
                    instance.draw_matrices(animation, &mut matrices);
                    animated += 1;
                    self.animated_poses.push(AnimatedPose {
                        id: candidate.id,
                        position: candidate.position,
                        rotation: candidate.rotation,
                        scale: candidate.scale,
                    });
                    // La palette suit les matrices des dessins, de la même pose.
                    if palettes[rank] {
                        palette_first = u32::try_from(matrices.len() / MATRIX_FLOATS)
                            .map_err(|_| AXION_E_INVALID_BUFFER)?;
                        instance.palette(animation, &mut matrices);
                        skinned += 1;
                    }
                }
            }
            for value in [v.index, v.id, u32::from(level)] {
                records.extend_from_slice(&value.to_le_bytes());
            }
            records.extend_from_slice(&v.distance.to_le_bytes());
            records.extend_from_slice(&first.to_le_bytes());
            records.extend_from_slice(&count.to_le_bytes());
            records.extend_from_slice(&matrix_first.to_le_bytes());
            records.extend_from_slice(&palette_first.to_le_bytes());
        }
        let mesh_total = u32::try_from(meshes.len()).map_err(|_| AXION_E_INVALID_BUFFER)?;
        let matrix_total =
            u32::try_from(matrices.len() / MATRIX_FLOATS).map_err(|_| AXION_E_INVALID_BUFFER)?;
        let mut out =
            Vec::with_capacity(OUT_HEADER + records.len() + meshes.len() * 4 + matrices.len() * 4);
        for value in [
            frame_id,
            instance_count,
            mesh_total,
            flags,
            matrix_total,
            skinned_capped,
        ] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&records);
        for mesh in &meshes {
            out.extend_from_slice(&mesh.to_le_bytes());
        }
        for value in &matrices {
            out.extend_from_slice(&value.to_le_bytes());
        }
        self.buffers
            .write_payload(BufferKind::RenderOut, &out)
            .ok_or(AXION_E_INVALID_BUFFER)?;
        if let Some(buffer) = self.buffers.get_mut(BufferKind::RenderOut) {
            BufferHeader {
                kind: BufferKind::RenderOut,
                generation: buffer.generation(),
                schema_version: BufferKind::RenderOut.schema_version(),
                payload_len: out.len() as u64,
                element_count: instance_count,
                crc32c: 0,
            }
            .write(buffer.as_mut_slice());
        }
        self.in_flight = Some(frame_id);
        self.stats.frames += 1;
        if flags & AXION_RENDER_DEGRADED != 0 {
            self.stats.degraded += 1;
        }
        self.stats.last = stats;
        self.stats.animated = animated;
        self.stats.skinned = skinned;
        self.stats.skinned_capped += u64::from(skinned_capped);
        self.stats.last_prepare_ns =
            u64::try_from(started.elapsed().as_nanos()).unwrap_or(u64::MAX);
        Ok(Prepared {
            instance_count,
            triangle_estimate: triangles,
            palette_bytes: matrix_total.saturating_mul((MATRIX_FLOATS * 4) as u32),
            frame_id,
            flags,
        })
    }

    /// Les étapes du pas que la dernière frame préparée a exécutées, dans leur ordre (R-662, T-392) :
    /// l'animation (2) et le graphe (14) quand elle a retenu une instance animée, rien sinon.
    #[must_use]
    pub fn stages(&self) -> &[Stage] {
        self.stages.executed()
    }

    /// La durée d'une étape à la dernière frame préparée, en nanosecondes ; 0 si elle n'a pas couru.
    #[must_use]
    pub fn stage_duration(&self, stage: Stage) -> u64 {
        self.stages.duration(stage)
    }

    /// Dépose dans `RENDER_DEBUG` les segments des overlays de l'animation demandés — `bones`, `nodes`,
    /// `pivots` — pour les instances animées que la dernière frame préparée a retenues, à la pose qu'elle
    /// leur a donnée (ADR-155, tranche 6b). Repère d'origine, relatif à la caméra de cette frame ;
    /// chaque plage au handle `ABSENT` (décision de Killian). Les plus proches d'abord, au plus
    /// `max_segments` segments : une plage qui ne tient pas est omise et comptée (schéma 2 de `DEBUG`).
    /// Rend la taille de la charge utile.
    ///
    /// # Errors
    ///
    /// `AXION_E_INVALID_BUFFER` si la charge ne s'encode pas ou ne se dépose pas.
    pub fn debug(&mut self, mask: u64, max_segments: u32) -> Result<u64, i32> {
        let mut drafts = Vec::new();
        for pose in &self.animated_poses {
            let Some((handle, instance)) = self.instances.get(&pose.id) else {
                continue;
            };
            let Some(animation) = self
                .assets
                .get(handle.index as usize)
                .and_then(Option::as_ref)
                .filter(|a| a.generation == handle.generation)
                .and_then(|a| a.animation.as_ref())
            else {
                continue;
            };
            let place =
                |point: Vec3| (pose.position + pose.rotation * (point * pose.scale)).to_array();
            for bit in [overlay::BONES, overlay::NODES, overlay::PIVOTS] {
                if mask & bit == 0 {
                    continue;
                }
                let mut segments = Vec::new();
                match bit {
                    overlay::BONES => instance.bone_segments(animation, place, &mut segments),
                    overlay::NODES => instance.node_segments(place, &mut segments),
                    _ => instance.pivot_segments(animation, place, &mut segments),
                }
                drafts.push(DebugDraft {
                    handle: Handle::ABSENT,
                    overlay: overlay::rank(bit),
                    frame: debug_frame::ORIGIN,
                    flags: 0,
                    anchor: pose.position.as_dvec3(),
                    segments,
                });
            }
        }
        let selection = select_drafts(drafts, DVec3::ZERO, max_segments);
        let flags = if selection.omitted_ranges > 0 {
            debug_flags::TRUNCATED
        } else {
            0
        };
        let payload = encode_debug_payload(
            &selection.ranges,
            &selection.segments,
            flags,
            selection.omitted_ranges,
        )
        .ok_or(AXION_E_INVALID_BUFFER)?;
        self.buffers
            .write_payload(BufferKind::RenderDebug, &payload)
            .ok_or(AXION_E_INVALID_BUFFER)
    }

    /// Indique si un candidat est une instance animée de l'asset qu'il dessine : l'asset est chargé et
    /// animé, une matrice par dessin, et l'assembly a son animation sur lui.
    fn animates(&self, candidate: &Candidate) -> bool {
        let current = Handle::new(candidate.asset, candidate.generation);
        self.assets
            .get(candidate.asset as usize)
            .and_then(Option::as_ref)
            .filter(|a| a.generation == candidate.generation)
            .is_some_and(|a| {
                a.animation
                    .as_ref()
                    .is_some_and(|animation| animation.draw_count() == a.draw_meshes.len())
                    && self
                        .instances
                        .get(&candidate.id)
                        .is_some_and(|(handle, _)| *handle == current)
            })
    }

    /// Indique si un candidat recevrait une palette : son asset est chargé, skinné et animé, et son
    /// assembly a une animation sur cet asset.
    fn skinnable(&self, candidate: &Candidate) -> bool {
        let current = Handle::new(candidate.asset, candidate.generation);
        self.assets
            .get(candidate.asset as usize)
            .and_then(Option::as_ref)
            .filter(|a| a.generation == candidate.generation)
            .and_then(|a| a.animation.as_ref())
            .is_some_and(|animation| {
                animation.bone_count() > 0
                    && self
                        .instances
                        .get(&candidate.id)
                        .is_some_and(|(handle, _)| *handle == current)
            })
    }

    /// Libère la frame `frame_id` ; faux si elle n'est pas celle en vol.
    pub fn release(&mut self, frame_id: u32) -> bool {
        if self.in_flight == Some(frame_id) {
            self.in_flight = None;
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    //! T-523 (plafond de la frame) et T-524 (échéance) : un contexte sans workers, où l'échéance se
    //! juge entre deux lots sur le thread appelant — sans course, donc déterministe.

    use super::*;
    use ax_math::{Mat4, Quat, Vec3};

    /// Un budget qui ne coupe rien : mille secondes.
    const LARGE: u64 = 1_000_000_000_000;

    /// Un asset sphère unité, à quatre niveaux, un dessin dont le mesh du niveau `n` est `n`.
    fn context() -> RenderContext {
        let assets = vec![Some(RenderAsset {
            generation: 1,
            bounds: Some(LocalBounds {
                center: Vec3::ZERO,
                radius: 1.0,
            }),
            triangles: 12,
            levels: 4,
            draw_meshes: vec![[0, 1, 2, 3, 3, 3, 3, 3]].into(),
            animation: None,
        })];
        RenderContext {
            token: 1,
            poisoned: false,
            buffers: BufferPool::new(),
            assets: Arc::new(assets),
            jobs: None,
            next_frame: 1,
            in_flight: None,
            stats: RenderStats::default(),
            lod: LodState::new(),
            decisions: HashMap::new(),
            instances: HashMap::new(),
            animated_poses: Vec::new(),
            stages: StageLog::default(),
            animation_cursor: None,
            telemetry: RenderTelemetry::standalone(),
            limits: RenderLimits {
                max_skinned_instances: 64,
            },
        }
    }

    fn camera(count: usize, max_instances: u32) -> [u8; Camera::BYTES] {
        let f = 1.0 / (70f32.to_radians() / 2.0).tan();
        let mut m = [0f32; 16];
        m[0] = f / (16.0 / 9.0);
        m[5] = f;
        m[10] = -1.0;
        m[11] = -1.0;
        m[14] = -0.1;
        Camera {
            projection: Mat4::from_cols_array(&m),
            view_rotation: Quat::IDENTITY,
            viewport_width: 1920,
            viewport_height: 1080,
            max_distance: 512.0,
            lod_bias: 0,
            quality: 0,
            candidate_count: count as u32,
            max_instances,
        }
        .to_bytes()
    }

    fn write(context: &mut RenderContext, candidates: &[Candidate]) {
        let len = (IN_FRAME_HEADER + candidates.len() * Candidate::BYTES) as u64;
        let info = context.buffers.acquire(BufferKind::RenderIn, len);
        let buffer = context.buffers.get_mut(BufferKind::RenderIn).unwrap();
        let bytes = buffer.as_mut_slice();
        BufferHeader {
            kind: BufferKind::RenderIn,
            generation: info.generation,
            schema_version: 2,
            payload_len: 0,
            element_count: 0,
            crc32c: 0,
        }
        .write(bytes);
        for (i, c) in candidates.iter().enumerate() {
            let at = HEADER_BYTES + IN_FRAME_HEADER + i * Candidate::BYTES;
            bytes[at..at + Candidate::BYTES].copy_from_slice(&c.to_bytes());
        }
    }

    fn candidate(id: u32, position: Vec3) -> Candidate {
        Candidate {
            id,
            asset: 0,
            generation: 1,
            flags: 0,
            position,
            scale: 1.0,
            rotation: Quat::IDENTITY,
        }
    }

    /// Les retenus de la dernière frame : `(id, niveau)`.
    fn retained(context: &mut RenderContext, count: u32) -> Vec<(u32, u32)> {
        let out = context.buffers.get_mut(BufferKind::RenderOut).unwrap();
        let bytes = out.as_slice();
        let word = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
        (0..count as usize)
            .map(|i| {
                let record = HEADER_BYTES + OUT_HEADER + i * OUT_RECORD;
                (word(record + 4), word(record + 8))
            })
            .collect()
    }

    #[test]
    fn t523_au_dela_du_plafond_les_plus_lointains_sont_ecartes_et_la_frame_descend_d_un_niveau() {
        let mut context = context();
        // Cinq candidats devant, dans le désordre des distances ; deux à égalité de distance.
        let candidates = [
            candidate(1, Vec3::new(0.0, 0.0, -40.0)),
            candidate(2, Vec3::new(0.0, 0.0, -10.0)),
            candidate(3, Vec3::new(0.0, 0.0, -30.0)),
            candidate(5, Vec3::new(0.0, 0.0, -20.0)),
            candidate(4, Vec3::new(0.0, 0.0, -20.0)),
        ];
        write(&mut context, &candidates);
        let free = context.prepare(&camera(5, 0), LARGE).unwrap();
        assert_eq!(free.flags, 0);
        let levels: HashMap<u32, u32> = retained(&mut context, free.instance_count)
            .into_iter()
            .collect();

        let capped = context.prepare(&camera(5, 3), LARGE).unwrap();
        assert_eq!(capped.flags, AXION_RENDER_CAPPED);
        assert_eq!(capped.instance_count, 3);
        let kept = retained(&mut context, capped.instance_count);
        // Les trois plus proches — 10, puis 20 deux fois, départagés par `id` : 2, 4, 5 —, dans
        // l'ordre des candidats.
        assert_eq!(
            kept.iter().map(|&(id, _)| id).collect::<Vec<_>>(),
            [2, 5, 4]
        );
        for (id, level) in kept {
            assert_eq!(
                level,
                (levels[&id] + 1).min(3),
                "le candidat {id} descend d'un niveau"
            );
        }
        assert_eq!(context.stats().capped, 1);
        // Sous le plafond, rien ne change.
        let under = context.prepare(&camera(5, 5), LARGE).unwrap();
        assert_eq!((under.flags, under.instance_count), (0, 5));
    }

    #[test]
    fn t524_a_l_echeance_un_candidat_reprend_sa_decision_d_avant() {
        let mut context = context();
        // Trois lots : le premier se fait toujours, les deux autres seront coupés par l'échéance.
        let devant = |id: u32| candidate(id, Vec3::new(0.0, 0.0, -10.0 - (id % 50) as f32));
        let derriere = |id: u32| candidate(id, Vec3::new(0.0, 0.0, 10.0 + (id % 50) as f32));
        let count = 3 * LOT;
        // Frame 1, sans échéance : les pairs devant, les impairs derrière.
        let first: Vec<Candidate> = (0..count as u32)
            .map(|id| {
                if id % 2 == 0 {
                    devant(id)
                } else {
                    derriere(id)
                }
            })
            .collect();
        write(&mut context, &first);
        let complete = context.prepare(&camera(count, 0), LARGE).unwrap();
        assert_eq!(complete.flags, 0);
        assert_eq!(complete.instance_count as usize, count / 2);

        // Frame 2, échéance nulle : tout a changé de côté, et deux candidats sont nouveaux.
        let mut second: Vec<Candidate> = (0..count as u32)
            .map(|id| {
                if id % 2 == 0 {
                    derriere(id)
                } else {
                    devant(id)
                }
            })
            .collect();
        second[2 * LOT] = candidate(90_000, Vec3::new(0.0, 0.0, 10.0));
        second[2 * LOT + 1] = candidate(90_001, Vec3::new(0.0, 0.0, -10.0));
        write(&mut context, &second);
        let rushed = context.prepare(&camera(count, 0), 0).unwrap();
        assert_eq!(rushed.flags, AXION_RENDER_DEGRADED);
        let kept = retained(&mut context, rushed.instance_count);
        let ids: HashSet<u32> = kept.iter().map(|&(id, _)| id).collect();
        // Le premier lot est examiné : ses impairs, passés devant, sont retenus, ses pairs non.
        assert!((0..LOT as u32).all(|id| ids.contains(&id) == (id % 2 == 1)));
        // Les lots coupés reprennent la frame 1 : leurs pairs restent retenus, leurs impairs écartés,
        // quel que soit le côté où ils sont passés.
        for id in LOT as u32..count as u32 {
            if id != 2 * LOT as u32 && id != 2 * LOT as u32 + 1 {
                assert_eq!(ids.contains(&id), id % 2 == 0, "candidat {id}");
            }
        }
        // Jamais examinés : retenus tous deux, au niveau le plus grossier, même derrière la caméra.
        for nouveau in [90_000, 90_001] {
            let level = kept.iter().find(|&&(id, _)| id == nouveau).map(|&(_, l)| l);
            assert_eq!(level, Some(3), "candidat nouveau {nouveau}");
        }
        assert_eq!(context.stats().degraded, 1);
    }
}
