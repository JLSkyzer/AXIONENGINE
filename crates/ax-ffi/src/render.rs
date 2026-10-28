//! Contexte de rendu (IF-05, ADR-154 D2) : hors de la session, sous son propre verrou.
//!
//! En solo, le serveur intégré et le client partagent la session native, et le pas physique
//! tient le verrou de session pendant `axion_sim_collect`. Une préparation de rendu qui le
//! prendrait attendrait le pas, et R-300 ne tiendrait pas. Ce qui sert au rendu vit donc ici :
//! les tampons `RENDER_IN` et `RENDER_OUT`, l'emprise de chaque asset chargé, la frame en vol.
//!
//! # Ordre des verrous
//!
//! Le verrou de session puis celui-ci, jamais l'inverse : le chargement et le déchargement d'un
//! asset publient son emprise ici en tenant la session, l'ouverture et la fermeture aussi. Le
//! rendu ne prend **jamais** le verrou de session : il n'attend jamais la simulation, et le seul
//! qui puisse lui disputer ce verrou-ci est un chargement d'asset, section courte et bornée.
//!
//! # Une frame en vol
//!
//! `RENDER_OUT` est unique : son contenu vaut jusqu'à `axion_render_release` (R-301). Une
//! préparation qui arrive avant la libération de la frame précédente la clôt implicitement et
//! la compte, comme `axion_sim_submit` clôt un cycle non collecté (R-282).

use crate::abi::{AXION_E_INVALID_BUFFER, AXION_E_INVALID_HANDLE};
use ax_asset::optimize::MAX_LOD_LEVELS;
use ax_core::BufferPool;
use ax_jobs::{JobKind, JobSystem};
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::handle::Handle;
use ax_render_prep::lod::{biased, projected_px, LodState};
use ax_render_prep::{cull_batched, Camera, Candidate, CullStats, Frustum, LocalBounds, Visible};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

/// Le contexte de rendu vivant, s'il y en a un.
static RENDER: Mutex<Option<RenderContext>> = Mutex::new(None);

/// Drapeau de `AxionRenderResult` : le résultat est partiel, l'échéance est passée (R-300).
pub const AXION_RENDER_DEGRADED: u32 = 1 << 0;

/// Candidats traités d'un seul tenant ; au-delà, les lots suivants partent sur les workers.
const LOT: usize = 1024;

/// Taille de l'en-tête de frame de `RENDER_OUT` : `frame_id`, retenus, meshes, drapeaux.
pub const OUT_HEADER: usize = 16;

/// Taille d'un enregistrement retenu (schéma 2) : index du candidat, `id`, LOD, distance, premier
/// mesh et nombre de meshes dans la table qui suit les enregistrements.
pub const OUT_RECORD: usize = 24;

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
}

/// Ce que rend une préparation : les champs d'`AxionRenderResult` que la tranche 1 remplit.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Prepared {
    /// Candidats retenus, déposés dans `RENDER_OUT`.
    pub instance_count: u32,
    /// Triangles des retenus, au LOD 0.
    pub triangle_estimate: u32,
    /// Identifiant de la frame, à rendre par `axion_render_release`.
    pub frame_id: u32,
    /// Drapeaux, voir [`AXION_RENDER_DEGRADED`].
    pub flags: u32,
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
}

fn recover<T>(result: Result<T, PoisonError<T>>) -> T {
    result.unwrap_or_else(PoisonError::into_inner)
}

fn slot() -> MutexGuard<'static, Option<RenderContext>> {
    recover(RENDER.lock())
}

/// Ouvre le contexte de rendu de la session `token`. Appelé par l'ouverture de la session, qui
/// tient son verrou.
pub(crate) fn open(token: u64, jobs: Option<Arc<JobSystem>>) {
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
}

/// Publie l'emprise d'un asset chargé, sous le handle qu'il vient de recevoir.
pub(crate) fn publish(token: u64, handle: Handle, published: Published<'_>) {
    let _ = with(token, |context| {
        if published.lod_refused {
            context.stats.lod_refused += 1;
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
        self.stats
    }

    /// Lit les `count` candidats de `RENDER_IN`.
    fn read_candidates(&mut self, count: usize) -> Result<Vec<Candidate>, i32> {
        if count == 0 {
            return Ok(Vec::new());
        }
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
        let len = count
            .checked_mul(Candidate::BYTES)
            .ok_or(AXION_E_INVALID_BUFFER)?;
        let end = HEADER_BYTES
            .checked_add(len)
            .ok_or(AXION_E_INVALID_BUFFER)?;
        let bytes = raw.get(HEADER_BYTES..end).ok_or(AXION_E_INVALID_BUFFER)?;
        Candidate::read_all(bytes).map_err(|_| AXION_E_INVALID_BUFFER)
    }

    /// Prépare une frame : lit les candidats de `RENDER_IN`, les culle, dépose les retenus dans
    /// `RENDER_OUT`. Les lots au-delà du premier partent sur les workers ; un lot qui n'a pas
    /// rendu son résultat à l'échéance est gardé entier, sans test — rien ne disparaît — et la
    /// frame est marquée [`AXION_RENDER_DEGRADED`].
    ///
    /// # Errors
    ///
    /// `E-2002` si la caméra, l'en-tête de `RENDER_IN` ou un candidat est invalide, ou si
    /// `RENDER_OUT` ne peut pas recevoir le résultat.
    pub fn prepare(&mut self, camera_desc: &[u8], budget_ns: u64) -> Result<Prepared, i32> {
        let started = Instant::now();
        let deadline = started + Duration::from_nanos(budget_ns);
        let camera = Camera::from_bytes(camera_desc).map_err(|_| AXION_E_INVALID_BUFFER)?;
        let candidates = Arc::new(self.read_candidates(camera.candidate_count as usize)?);
        let frustum = camera.frustum();
        let assets = Arc::clone(&self.assets);
        self.lod.begin_frame();

        let mut flags = 0;
        let mut stats = CullStats::default();
        // Les candidats d'un lot gardé sans test : ils reprennent leur niveau d'avant.
        let mut partial: Vec<u32> = Vec::new();
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
                None => Some(cull_lot(
                    &camera,
                    &frustum,
                    &candidates[start..end],
                    start,
                    &assets,
                )),
            };
            if let Some((lot, part)) = done {
                visible.extend(lot);
                add(&mut stats, part);
            } else {
                // Échéance passée : le lot est gardé entier, sans test. Rien ne disparaît.
                flags |= AXION_RENDER_DEGRADED;
                for candidate in &candidates[start..end] {
                    partial.push(candidate.id);
                }
                for (offset, candidate) in candidates[start..end].iter().enumerate() {
                    stats.candidates += 1;
                    stats.visible += 1;
                    visible.push(Visible {
                        index: (start + offset) as u32,
                        id: candidate.id,
                        distance: candidate.position.length(),
                    });
                }
            }
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
        let mut triangles = 0u32;
        for v in &visible {
            let candidate = &candidates[v.index as usize];
            let asset = assets
                .get(candidate.asset as usize)
                .and_then(Option::as_ref)
                .filter(|a| a.generation == candidate.generation);
            let level = match asset {
                None => 0,
                Some(a) if partial.contains(&candidate.id) => {
                    match self.lod.remembered(candidate.id) {
                        Some(previous) => biased(previous, camera.lod_bias, a.levels),
                        // Un candidat nouveau d'un lot partiel : le niveau le plus grossier (D3).
                        None => a.levels.max(1) - 1,
                    }
                }
                Some(a) => {
                    let radius = a.bounds.map_or(0.0, |b| b.radius * candidate.scale);
                    let px = projected_px(&camera, radius, v.distance);
                    self.lod.level(candidate.id, px, a.levels, camera.lod_bias)
                }
            };
            let first = u32::try_from(meshes.len()).map_err(|_| AXION_E_INVALID_BUFFER)?;
            if let Some(a) = asset {
                triangles = triangles.saturating_add(a.triangles);
                meshes.extend(a.draw_meshes.iter().map(|row| row[usize::from(level)]));
            }
            let count = u32::try_from(meshes.len()).map_err(|_| AXION_E_INVALID_BUFFER)? - first;
            for value in [v.index, v.id, u32::from(level)] {
                records.extend_from_slice(&value.to_le_bytes());
            }
            records.extend_from_slice(&v.distance.to_le_bytes());
            records.extend_from_slice(&first.to_le_bytes());
            records.extend_from_slice(&count.to_le_bytes());
        }
        let mesh_total = u32::try_from(meshes.len()).map_err(|_| AXION_E_INVALID_BUFFER)?;
        let mut out = Vec::with_capacity(OUT_HEADER + records.len() + meshes.len() * 4);
        for value in [frame_id, instance_count, mesh_total, flags] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&records);
        for mesh in &meshes {
            out.extend_from_slice(&mesh.to_le_bytes());
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
        self.stats.last_prepare_ns =
            u64::try_from(started.elapsed().as_nanos()).unwrap_or(u64::MAX);
        Ok(Prepared {
            instance_count,
            triangle_estimate: triangles,
            frame_id,
            flags,
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
