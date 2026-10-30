//! IF-01 — points d'entrée de l'ABI native.
//!
//! Chaque fonction exportée suit la même forme, imposée par R-490 :
//! `#[no_mangle] extern "C"`, un `catch_unwind`, la validation de **tous** ses
//! arguments, l'appel au crate métier, puis la conversion du résultat en `i32`.
//! Aucune logique de moteur ne vit ici.
//!
//! Conventions de l'ABI :
//!
//! - toute fonction renvoie un `i32` : `0` en succès, sinon un code négatif de
//!   l'ANNEXE A.1 ;
//! - le contexte est un `u64` opaque, jamais un pointeur (R-264) ;
//! - les chaînes sont en UTF-8 avec longueur explicite, jamais terminées par
//!   zéro (R-263) ;
//! - aucune structure `repr(Rust)` ne traverse (R-262) ;
//! - la donnée de masse passe par les tampons de IF-02, pas par les paramètres.

// Ce module est la frontière elle-même : y interdire `unsafe` reviendrait à
// interdire la frontière. Le lint reste `deny` pour tout le reste du crate, et
// chaque bloc ci-dessous porte sa justification `// SAFETY:`.
#![allow(unsafe_code)]

use std::panic::{catch_unwind, AssertUnwindSafe};

use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::config::{self, ConfigScope, ParsedValue};

use crate::assembly;
use crate::asset_store::{decode_render_asset, requested_sections};
use crate::context;
use crate::render;
use ax_asset::a3d::{announced_total_size, A3dLimits, HEADER_BYTES as A3D_HEADER_BYTES};
use ax_asset::compile::CompileOptions;
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::options::AssetOptions;
use ax_jobs::{CpuShare, JobBudgets, JobKind, Side, WorkerPolicy};
use ax_math::{DVec3, Quat, Vec3};
use ax_model::budgets::Budget;
use ax_model::dm::animation::AnimCommand;
use ax_model::dm::commands::{
    create_assembly_flags, opcode, restored_joint_flags, AnimateAssembly,
};
use ax_model::dm::debug::{debug_flags, encode_debug_payload, overlay};
use ax_model::dm::definition::{summary_flags, Definition, DefinitionRefusal, DefinitionSummary};
use ax_model::dm::handle::Handle;
use ax_model::dm::joints::JointState;
use ax_model::dm::physics::{BodyBounds, BodyState};
use ax_model::dm::scene::NONE_U32;
use ax_physics::{DegradationLevel, PhysicsConfig, SavedBody, SavedJoint, SimSettings, Stage};

/// Version de l'ABI.
///
/// Versionnée indépendamment du produit (R-261) : toute rupture de
/// compatibilité binaire l'incrémente. Java la compare à la sienne avant tout
/// autre appel et bascule en `DISABLED` sur écart (R-260, `E-1002`). 12 depuis la compilation des
/// definitions par le natif (ADR-156) : `axion_definition_compile`, `axion_definition_remove`, et
/// `PHYS`, `SKEL`, `ANIM` au masque d'`axion_asset_load`. 11 depuis les overlays de
/// l'animation (ADR-155, tranche 6b) : `axion_render_debug` et le tampon `RENDER_DEBUG` ; 10 depuis
/// l'animation (ADR-155) : `axion_render_animate`, le tick de rendu en tête de `RENDER_IN` (schéma 2)
/// et les matrices des retenus animés dans `RENDER_OUT` (schéma 3) ; 9 depuis le cycle de rendu IF-05
/// (ADR-154). 8 depuis l'état restauré
/// d'une assembly à sa création et le sommeil des assemblies chargées (ADR-153 D3, D4), après les
/// assemblies à plusieurs corps et leurs joints (7, ADR-151 D7), l'empreinte déterministe et la
/// compression des charges utiles réseau (6, ADR-150 D4, D5).
pub const AXION_ABI_VERSION: u32 = 12;

/// Succès.
pub const AXION_OK: i32 = 0;

/// Panic capturée à la frontière (`E-2000`).
pub const AXION_E_PANIC: i32 = -2000;

/// Contexte ou handle invalide (`E-2001`).
pub const AXION_E_INVALID_HANDLE: i32 = -2001;

/// Tampon ou pointeur invalide (`E-2002`).
pub const AXION_E_INVALID_BUFFER: i32 = -2002;

/// Ressource non libérée à l'arrêt (`E-2003`).
///
/// R-322 : le bilan des allocations est rendu à `axion_shutdown`. Un bilan non
/// nul n'empêche pas l'arrêt — il le signale.
pub const AXION_E_LEAK: i32 = -2003;

/// Configuration refusée.
///
/// L'ANNEXE A.1 n'attribue aucun code propre à la configuration ; `E-2002`
/// — « tampon invalide ou périmé », domaine ffi — est le seul qui couvre une
/// donnée externe refusée à la frontière. En inventer un nouveau reviendrait à
/// étendre l'annexe sans y toucher (obligation 4.8). Voir ADR-102.
pub const AXION_E_CONFIG: i32 = AXION_E_INVALID_BUFFER;

/// Côté client, tel qu'`axion_init` l'attend.
pub const AXION_SIDE_CLIENT: u32 = 0;

/// Côté serveur dédié, tel qu'`axion_init` l'attend.
pub const AXION_SIDE_SERVER: u32 = 1;

/// Compilation en cours : ni aboutie, ni échouée (SM-01, `QUEUED` ou
/// `COMPILING`).
pub const AXION_ASSET_PENDING: u32 = 0;

/// Compilation aboutie ; l'asset attend dans le tampon de sortie (SM-01,
/// `COMPILED`).
pub const AXION_ASSET_COMPILED: u32 = 1;

/// Compilation échouée (SM-01, `FAILED`).
pub const AXION_ASSET_FAILED: u32 = 2;

/// Compilation qui demande des fichiers voisins (ADR-139) : `ASSET_OUT` porte leurs noms, et
/// l'appelant renvoie la source avec eux. Pour SM-01, le travail continue (`COMPILING`).
pub const AXION_ASSET_NEEDS_FILES: u32 = 3;

/// Entrée de la table de fichiers voisins : le fichier est fourni (ADR-139).
pub const AXION_FILE_PRESENT: u32 = 0;

/// Entrée de la table de fichiers voisins : l'appelant ne l'a pas — la compilation fait comme
/// la CLI dont la lecture échoue (ADR-139).
pub const AXION_FILE_ABSENT: u32 = 1;

/// Plus grand nombre d'entrées d'une table de fichiers voisins.
pub const MAX_SIBLING_FILES: u32 = 256;

/// Plus long nom d'une entrée de la table, en octets.
pub const MAX_SIBLING_NAME_BYTES: u32 = 1024;

/// Exécute `action` en interceptant toute panic (R-310, INV-05).
///
/// Une panic est journalisée, comptée, et empoisonne le contexte quand il y en
/// a un : on ne sait pas où le travail s'est interrompu, et supposer l'état
/// restaurable serait le contraire de l'option conservatrice. `AXION_E_PANIC`
/// est renvoyé à Java, qui bascule en `DISABLED`.
///
/// `panic = "abort"` est interdit dans ce crate (R-312) : il ferait mourir le
/// processus avant toute capture.
fn shielded(token: Option<u64>, action: impl FnOnce() -> i32) -> i32 {
    match catch_unwind(AssertUnwindSafe(action)) {
        Ok(code) => code,
        Err(payload) => {
            let message = describe_panic(payload.as_ref());
            if let Some(token) = token {
                // L'accès est autorisé même si le contexte est déjà empoisonné :
                // il s'agit précisément de l'empoisonner.
                let _ = context::with(token, true, |session| session.record_panic(&message));
            }
            AXION_E_PANIC
        }
    }
}

/// Extrait un message lisible de la charge utile d'une panic.
fn describe_panic(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        format!("panic native : {message}")
    } else if let Some(message) = payload.downcast_ref::<String>() {
        format!("panic native : {message}")
    } else {
        "panic native sans message".to_owned()
    }
}

/// Renvoie la version de l'ABI de cette bibliothèque.
///
/// À appeler **avant toute autre fonction** (R-260). Ne dépend d'aucun
/// contexte : c'est ce qui permet de la comparer avant d'initialiser quoi que
/// ce soit.
#[no_mangle]
pub extern "C" fn axion_abi_version() -> i32 {
    // Le transtypage est sûr : la version reste très en deçà de i32::MAX, et
    // une valeur négative serait indiscernable d'un code d'erreur.
    AXION_ABI_VERSION as i32
}

/// Empreinte de configuration déterministe de cette bibliothèque (5.12bis), échangée au
/// handshake (ADR-150 D4).
#[repr(C)]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct AxionDetProfile {
    /// `det_profile` : hash du triplet cible, de `DET_KERNEL_VERSION`, du jeu d'instructions
    /// compilé et des drapeaux flottants.
    pub profile: u64,
    /// `DET_KERNEL_VERSION` du noyau déterministe (C-16).
    pub kernel_version: u32,
    /// 1 si la configuration figure, validée, dans la matrice de 5.12bis (R-516) ; 0 sinon.
    pub in_matrix: u32,
}

/// Rend l'empreinte de configuration déterministe (5.12bis), pour le handshake (ADR-150 D4).
///
/// Ne dépend d'aucun contexte, comme [`axion_abi_version`] : c'est une propriété du binaire,
/// que le serveur et le client lisent chacun chez eux avant de la comparer.
///
/// # Safety
///
/// `out` doit être accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_det_profile(out: *mut AxionDetProfile) -> i32 {
    shielded(None, || {
        if out.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        let profile = AxionDetProfile {
            profile: ax_det::det_profile(),
            kernel_version: ax_det::DET_KERNEL_VERSION,
            in_matrix: u32::from(ax_det::is_in_validation_matrix()),
        };
        // SAFETY: nullité écartée ci-dessus.
        unsafe { out.write(profile) };
        AXION_OK
    })
}

/// Initialise le contexte natif.
///
/// # Arguments
///
/// - `config_cbor` : configuration encodée en CBOR, une map de chemins d'option
///   vers leurs valeurs. Peut être nul si `len` vaut 0.
/// - `len` : longueur de `config_cbor`, en octets.
/// - `side` : [`AXION_SIDE_CLIENT`] ou [`AXION_SIDE_SERVER`].
/// - `out_ctx` : reçoit le jeton de contexte en cas de succès.
///
/// Le côté est un paramètre et non une option de configuration : ce n'est pas
/// un réglage, c'est une donnée de démarrage. R-471 en fait dépendre le plafond
/// de workers — quatre sur un client, huit sur un serveur dédié — et le déduire
/// des clés reçues créerait un couplage qui casserait le jour où les portées
/// bougeraient.
///
/// # Safety
///
/// `config_cbor` doit pointer sur `len` octets lisibles, et `out_ctx` sur un
/// `u64` accessible en écriture. Les deux sont validés dans la mesure du
/// possible — un pointeur nul est refusé —, mais aucune API ne permet de
/// vérifier qu'un pointeur non nul est valide.
#[no_mangle]
pub unsafe extern "C" fn axion_init(
    config_cbor: *const u8,
    len: usize,
    side: u32,
    out_ctx: *mut u64,
) -> i32 {
    shielded(None, || {
        if out_ctx.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        if config_cbor.is_null() && len != 0 {
            return AXION_E_INVALID_BUFFER;
        }
        let Some(side) = decode_side(side) else {
            // Un côté inconnu n'est pas deviné : en supposer un donnerait un
            // pool mal dimensionné sans que rien ne le signale.
            return AXION_E_CONFIG;
        };

        let config = if len == 0 {
            &[][..]
        } else {
            // SAFETY: le contrat de la fonction impose `len` octets lisibles à
            // partir de `config_cbor`, et la nullité vient d'être écartée.
            unsafe { std::slice::from_raw_parts(config_cbor, len) }
        };

        // La configuration est validée **avant** d'ouvrir quoi que ce soit :
        // elle dimensionne le pool de jobs, et l'ouvrir pour le refermer
        // aussitôt ferait naître puis mourir ses threads pour rien.
        let applied = match apply_config(config) {
            Ok(applied) => applied,
            Err(message) => {
                last_init_error(&message);
                return AXION_E_CONFIG;
            }
        };

        let workers = WorkerPolicy {
            cores: WorkerPolicy::available_cores(),
            side,
            max_workers: applied.max_workers,
            cpu_share: applied.cpu_share,
            // C-76 n'existe pas encore : rien ne détecte de tiers, et le
            // supposer présent réduirait le parallélisme sans raison.
            third_party_present: false,
        };

        let assets = context::AssetLimits {
            container: A3dLimits::new(applied.max_compiled_bytes),
            source: ImportLimits::new(applied.max_source_bytes),
            persistent_bytes: usize::try_from(applied.native_mem_bytes).unwrap_or(usize::MAX),
        };
        let token = match context::open(
            side,
            workers,
            applied.budgets,
            assets,
            applied.sim_settings(),
            render::RenderLimits {
                max_skinned_instances: applied.max_skinned_instances,
            },
        ) {
            Ok(token) => token,
            Err(code) => return code,
        };

        // SAFETY: `out_ctx` est non nul, et le contrat impose qu'il soit
        // accessible en écriture.
        unsafe { out_ctx.write(token) };
        AXION_OK
    })
}

/// Retient le motif d'un refus survenu avant l'ouverture de la session.
///
/// Il n'y a alors pas de session où le ranger. La configuration refusée est le
/// cas courant, et Java a besoin de la cause : le message est conservé le temps
/// que `axion_last_error` vienne le chercher, avec le jeton nul.
fn last_init_error(message: &str) {
    context::set_init_error(message);
}

/// Traduit le code de côté de l'ABI.
fn decode_side(side: u32) -> Option<Side> {
    match side {
        AXION_SIDE_CLIENT => Some(Side::Client),
        AXION_SIDE_SERVER => Some(Side::Server),
        _ => None,
    }
}

/// Ferme le contexte natif.
///
/// Reste appelable sur un contexte empoisonné : c'est l'une des deux seules
/// fonctions que R-311 y autorise, sans quoi rien ne pourrait plus être libéré.
///
/// # Safety
///
/// Aucun paramètre n'est déréférencé ; la fonction est `unsafe` par symétrie
/// avec le reste de l'ABI et parce qu'elle invalide un jeton encore détenu par
/// l'appelant.
#[no_mangle]
pub unsafe extern "C" fn axion_shutdown(ctx: u64) -> i32 {
    shielded(Some(ctx), || match context::close(ctx) {
        // R-322 : la session est fermée dans tous les cas ; un déséquilibre
        // entre acquisitions et libérations est rendu à l'appelant, qui le
        // journalise. C'est ce que T-016 vérifie.
        Ok(balance) if balance.is_balanced() => AXION_OK,
        Ok(_) => AXION_E_LEAK,
        Err(code) => code,
    })
}

/// Copie le dernier message d'erreur du contexte.
///
/// Le message est en UTF-8 **sans zéro terminal** (R-263) ; `out_len` reçoit le
/// nombre d'octets écrits. Si la capacité ne suffit pas, le message est tronqué
/// sur une frontière de caractère : Java reçoit toujours de l'UTF-8 valide,
/// quitte à ce qu'il soit incomplet. Un message de diagnostic tronqué reste
/// exploitable ; un message invalide ne l'est pas.
///
/// Reste appelable sur un contexte empoisonné (R-311) — c'est même là qu'elle
/// sert le plus.
///
/// # Safety
///
/// `out_utf8` doit pointer sur `cap` octets accessibles en écriture, et
/// `out_len` sur un `usize` accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_last_error(
    ctx: u64,
    out_utf8: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> i32 {
    shielded(Some(ctx), || {
        if out_len.is_null() || (out_utf8.is_null() && cap != 0) {
            return AXION_E_INVALID_BUFFER;
        }

        // Le jeton nul désigne le refus survenu avant qu'une session n'existe.
        // C'est le cas d'une configuration refusée, où le message compte le
        // plus : sans lui, Java n'aurait qu'un code.
        if ctx == 0 {
            let message = context::init_error().unwrap_or_default();
            let end = utf8_boundary(&message, cap);
            let bytes = &message.as_bytes()[..end];
            if !bytes.is_empty() {
                // SAFETY: `out_utf8` est non nul dès que `cap` l'est, et
                // `bytes` ne dépasse pas `cap` par construction.
                unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_utf8, bytes.len()) };
            }
            // SAFETY: nullité écartée ci-dessus.
            unsafe { out_len.write(bytes.len()) };
            return AXION_OK;
        }

        let copied = context::with(ctx, true, |session| {
            let message = session.last_error().unwrap_or("");
            let end = utf8_boundary(message, cap);
            let bytes = &message.as_bytes()[..end];
            if !bytes.is_empty() {
                // SAFETY: `out_utf8` est non nul dès que `cap` l'est, et
                // `bytes` ne dépasse pas `cap` par construction.
                unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_utf8, bytes.len()) };
            }
            bytes.len()
        });

        match copied {
            Ok(written) => {
                // SAFETY: nullité écartée ci-dessus.
                unsafe { out_len.write(written) };
                AXION_OK
            }
            Err(code) => code,
        }
    })
}

/// Copie l'export JSON des métriques (R-502).
///
/// `out_len` reçoit **toujours** la longueur complète du document, que la
/// capacité ait suffi ou non. Un appelant qui la trouve supérieure à `cap`
/// rappelle avec un tampon de cette taille, et reçoit ce document-là : la session
/// le garde, si bien qu'une seconde tentative suffit toujours, alors même que
/// l'export décrit la mémoire vivante (ADR-133 D2). Une capacité nulle demande la
/// longueur d'un document frais.
///
/// Contrairement à [`axion_last_error`], rien n'est écrit quand la capacité ne
/// suffit pas : un message de diagnostic tronqué reste lisible, un document
/// JSON tronqué ne l'est pas — il ne se distinguerait même pas d'un document
/// complet décrivant autre chose.
///
/// # Safety
///
/// `out_utf8` doit pointer sur `cap` octets accessibles en écriture, et
/// `out_len` sur un `usize` accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_metrics_export(
    ctx: u64,
    out_utf8: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> i32 {
    shielded(Some(ctx), || {
        if out_len.is_null() || (out_utf8.is_null() && cap != 0) {
            return AXION_E_INVALID_BUFFER;
        }

        let export = match context::with(ctx, false, |session| session.metrics_export(cap)) {
            Ok(export) => export,
            Err(code) => return code,
        };

        match export {
            context::MetricsExport::Write(json) => {
                let bytes = json.as_bytes();
                // SAFETY: nullité écartée ci-dessus.
                unsafe { out_len.write(bytes.len()) };
                // SAFETY: `out_utf8` est non nul dès que `cap` l'est, et la session
                // n'écrit qu'un document qui tient dans `cap`.
                unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_utf8, bytes.len()) };
            }
            context::MetricsExport::TooSmall(len) => {
                // SAFETY: nullité écartée ci-dessus.
                unsafe { out_len.write(len) };
            }
        }
        AXION_OK
    })
}

/// Plus grand texte d'options qu'`axion_asset_compile` accepte, en octets de son
/// CBOR. Un fichier d'options tient en quelques centaines d'octets ; la borne
/// n'est là que pour qu'aucune entrée ne fasse allouer sans limite (R-901).
pub const MAX_OPTIONS_BYTES: usize = 64 * 1024;

/// Les octets d'un paramètre d'options : une chaîne de texte CBOR (type majeur
/// 3, longueur définie), et rien après elle.
///
/// Leur UTF-8 n'est **pas** vérifié ici, et c'est voulu : le lecteur d'options
/// le juge, et refuse un texte invalide par `E-7001`, au chemin `$` — ce que
/// `axion-cli` dit du même fichier (ADR-135 points 1 et 2, R-830). Pour RFC 8949,
/// une telle chaîne est bien formée mais invalide (§3.1), et c'est au protocole
/// de dire ce qu'en font ses décodeurs (§5.3) : celui-ci la remet au lecteur.
fn options_text_of(cbor: &[u8]) -> Option<&[u8]> {
    const MAJOR_TEXT: u8 = 3;
    let (&head, rest) = cbor.split_first()?;
    if head >> 5 != MAJOR_TEXT {
        return None;
    }
    let (len, body) = match head & 0x1f {
        short @ 0..=23 => (u64::from(short), rest),
        24 => (u64::from(*rest.first()?), rest.get(1..)?),
        25 => (
            u64::from(u16::from_be_bytes(rest.get(..2)?.try_into().ok()?)),
            rest.get(2..)?,
        ),
        26 => (
            u64::from(u32::from_be_bytes(rest.get(..4)?.try_into().ok()?)),
            rest.get(4..)?,
        ),
        27 => (
            u64::from_be_bytes(rest.get(..8)?.try_into().ok()?),
            rest.get(8..)?,
        ),
        // 28..=30 sont réservés ; 31 est une longueur indéfinie, que le texte
        // d'un fichier n'a aucune raison d'employer.
        _ => return None,
    };
    (usize::try_from(len).ok()? == body.len()).then_some(body)
}

/// Les fichiers voisins d'une soumission, par nom normalisé : `Some` présent, `None` absent.
type SiblingFiles = std::collections::BTreeMap<String, Option<Vec<u8>>>;

/// Lit la table des fichiers voisins qui suit la source dans `ASSET_IN` (ADR-139).
///
/// `None` si elle est mal formée : bornes dépassées, nom vide, trop long, non UTF-8 ou pas sous
/// sa forme normale, doublon, drapeau inconnu, octets d'une entrée absente, ou reste après la
/// dernière entrée. Une table vide (`files_len` nul) ne porte aucun fichier.
fn parse_sibling_table(table: &[u8]) -> Option<SiblingFiles> {
    let mut files = SiblingFiles::new();
    if table.is_empty() {
        return Some(files);
    }
    let mut rest = table;
    let count = take_u32(&mut rest)?;
    if count > MAX_SIBLING_FILES {
        return None;
    }
    for _ in 0..count {
        let name_len = take_u32(&mut rest)?;
        let flags = take_u32(&mut rest)?;
        let data_len = usize::try_from(take_u64(&mut rest)?).ok()?;
        if name_len == 0 || name_len > MAX_SIBLING_NAME_BYTES {
            return None;
        }
        let name = core::str::from_utf8(take(&mut rest, name_len as usize)?).ok()?;
        if ax_asset::import::normalize_reference(name).as_deref() != Some(name) {
            return None;
        }
        let data = take(&mut rest, data_len)?;
        let value = match flags {
            AXION_FILE_PRESENT => Some(data.to_vec()),
            AXION_FILE_ABSENT if data.is_empty() => None,
            _ => return None,
        };
        if files.insert(name.to_owned(), value).is_some() {
            return None;
        }
    }
    rest.is_empty().then_some(files)
}

/// Prend `len` octets en tête de `rest`.
fn take<'a>(rest: &mut &'a [u8], len: usize) -> Option<&'a [u8]> {
    if rest.len() < len {
        return None;
    }
    let (head, tail) = rest.split_at(len);
    *rest = tail;
    Some(head)
}

fn take_u32(rest: &mut &[u8]) -> Option<u32> {
    Some(u32::from_le_bytes(take(rest, 4)?.try_into().ok()?))
}

fn take_u64(rest: &mut &[u8]) -> Option<u64> {
    Some(u64::from_le_bytes(take(rest, 8)?.try_into().ok()?))
}

/// Une liste de chaînes : `u32` du nombre, puis par chaîne `u32` de sa longueur et ses octets
/// UTF-8, petit-boutiste — les noms de `NEEDS_FILES` (ADR-139), les lignes d'une compilation
/// (ADR-148).
fn encode_names(names: &[String]) -> Vec<u8> {
    let mut out = Vec::with_capacity(4 + names.iter().map(|name| 4 + name.len()).sum::<usize>());
    out.extend_from_slice(&u32::try_from(names.len()).unwrap_or(u32::MAX).to_le_bytes());
    for name in names {
        out.extend_from_slice(&u32::try_from(name.len()).unwrap_or(u32::MAX).to_le_bytes());
        out.extend_from_slice(name.as_bytes());
    }
    out
}

/// Lance la compilation d'un asset (IF-06).
///
/// La source est lue dans le tampon `ASSET_IN`, où Java l'a écrite : R-313
/// interdit à une fonction FFI d'allouer côté Java, et faire traverser un
/// pointeur de plus n'apporterait rien qu'un pointeur de plus à valider.
///
/// La compilation est **asynchrone** (R-521) : elle part sur le pool de jobs,
/// et l'appelant sonde `axion_asset_poll`. Aucun rappel de Rust vers Java
/// (INV-07).
///
/// `files_len` octets suivent la source dans `ASSET_IN` : la **table des fichiers voisins**
/// que la source référence (ADR-139) — vide à la première soumission. Le travail cherche
/// chaque référence dans la table, sous sa forme normale
/// ([`ax_asset::import::normalize_reference`], la règle de `axion-cli`) ; s'il en manque, il
/// les nomme toutes et le sondage rend [`AXION_ASSET_NEEDS_FILES`] : l'appelant renvoie alors
/// la source avec elles, présentes ou marquées absentes. Une table mal formée est refusée
/// tout de suite (`AXION_E_INVALID_BUFFER`).
///
/// `options_cbor` porte le texte du `<modèle>.axion.json` voisin de la source,
/// tel quel, comme **chaîne CBOR** ; `options_len` nul s'il n'y en a pas
/// (ADR-135). Le texte est lu par le lecteur d'options d'`ax-asset`, celui que
/// `axion-cli` emploie aussi (R-830), dans le travail même : une option refusée
/// fait échouer la compilation, et son code (`E-7001`, `E-7003`) se lit au
/// sondage. Un paramètre qui n'est pas une chaîne, ou plus long que
/// [`MAX_OPTIONS_BYTES`], est refusé tout de suite (`AXION_E_CONFIG`).
///
/// # Safety
///
/// `options_cbor` doit pointer sur `options_len` octets lisibles, et
/// `out_job_id` sur un `u32` accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_asset_compile(
    ctx: u64,
    asset_id: u64,
    source_format: u32,
    source_len: u64,
    files_len: u64,
    options_cbor: *const u8,
    options_len: usize,
    out_job_id: *mut u32,
) -> i32 {
    shielded(Some(ctx), || {
        if out_job_id.is_null() || (options_cbor.is_null() && options_len != 0) {
            return AXION_E_INVALID_BUFFER;
        }
        let Some(format) = decode_source_format(source_format) else {
            return AXION_E_CONFIG;
        };
        let options_text = if options_len == 0 {
            None
        } else {
            // Borné avant tout décodage (R-901).
            if options_len > MAX_OPTIONS_BYTES {
                return AXION_E_CONFIG;
            }
            // SAFETY: `options_cbor` est non nul dès que `options_len` l'est
            // (vérifié ci-dessus), et le contrat garantit `options_len` octets
            // lisibles.
            let cbor = unsafe { std::slice::from_raw_parts(options_cbor, options_len) };
            // Copié : le worker lit après le retour de l'appel, quand le tableau
            // de l'appelant n'est plus garanti.
            match options_text_of(cbor) {
                Some(text) => Some(text.to_vec()),
                None => return AXION_E_CONFIG,
            }
        };

        let submitted = context::with(ctx, false, |session| {
            // La source est copiée hors du tampon partagé : celui-ci peut être
            // réalloué au tick suivant (R-270), et le worker travaillerait
            // alors sur de la mémoire qui ne lui appartient plus.
            let Some(total) = source_len.checked_add(files_len) else {
                return Err(AXION_E_INVALID_BUFFER);
            };
            let Some(payload) = session.buffers().payload(BufferKind::AssetIn, total) else {
                return Err(AXION_E_INVALID_BUFFER);
            };
            let Ok(split) = usize::try_from(source_len) else {
                return Err(AXION_E_INVALID_BUFFER);
            };
            let (source, table) = payload.split_at(split);
            let Some(siblings) = parse_sibling_table(table) else {
                return Err(AXION_E_INVALID_BUFFER);
            };
            let source = source.to_vec();

            // Le plafond et l'empreinte de la CLI, par le même code : la même
            // source compile à l'identique des deux côtés (R-830), et R-533 tient
            // en jeu comme hors jeu.
            let options = CompileOptions {
                asset_id,
                source_hash: ax_asset::compile::source_hash(&source),
                limits: session.import_limits(),
                dynamic_body: true,
                // Les défauts, sauf si le `.axion.json` en donne d'autres : lus
                // dans le travail, ci-dessous. Le mode de collider de la
                // definition (C-32) n'y voyage pas encore.
                authored: AssetOptions::DEFAULT,
            };

            let Some(jobs) = session.jobs() else {
                // Sans pool, la compilation se ferait sur le thread appelant,
                // ce que R-521 interdit. C'est un refus, pas un repli.
                return Err(AXION_E_INVALID_HANDLE);
            };
            let handle = jobs.submit(JobKind::Asset, move |_| {
                let mut options = options;
                let mut notes = Vec::new();
                if let Some(text) = &options_text {
                    let parsed = ax_asset::options::parse_bytes(text)
                        .map_err(ax_asset::compile::CompileError::Options)?;
                    options.authored = parsed.options;
                    notes = parsed.warnings;
                }
                // ADR-139 : une référence se cherche sous sa forme normale, celle que la
                // CLI joint au répertoire de la source. Présente, ses octets ; absente, rien,
                // comme une lecture qui échoue ; inconnue, elle est notée et reçoit un contenu
                // vide provisoire, pour que l'importeur demande tout le reste dans ce passage.
                let mut requested = std::collections::BTreeSet::new();
                let compiled = ax_asset::compile::compile(&source, format, &options, |reference| {
                    let name = ax_asset::import::normalize_reference(reference)?;
                    match siblings.get(&name) {
                        Some(Some(bytes)) => Some(bytes.clone()),
                        Some(None) => None,
                        None => {
                            requested.insert(name);
                            Some(Vec::new())
                        }
                    }
                });
                if !requested.is_empty() {
                    return Ok(context::CompileOutcome::NeedsFiles(
                        requested.into_iter().collect(),
                    ));
                }
                let mut compiled = compiled?;
                compiled.warnings.splice(0..0, notes);
                Ok(context::CompileOutcome::Compiled(compiled))
            });
            Ok(handle)
        });

        match submitted {
            Ok(Ok(handle)) => {
                let id = context::with(ctx, false, move |session| {
                    session.register_asset_job(handle)
                });
                match id {
                    Ok(id) => {
                        // SAFETY: nullité écartée ci-dessus.
                        unsafe { out_job_id.write(id) };
                        AXION_OK
                    }
                    Err(code) => code,
                }
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Sonde une compilation lancée (IF-06).
///
/// `out_status` reçoit [`AXION_ASSET_PENDING`], [`AXION_ASSET_COMPILED`],
/// [`AXION_ASSET_FAILED`] ou [`AXION_ASSET_NEEDS_FILES`]. Sur succès, l'asset est
/// déposé dans le tampon `ASSET_OUT` et `out_size` reçoit sa taille ; une demande
/// de fichiers voisins y dépose leurs noms (ADR-139) ; sur échec, `out_error`
/// reçoit le code de l'ANNEXE A.1.
///
/// Un travail dont le résultat a été repris n'est plus sondable : le redemander rend
/// `E-2001`, ce qui vaut mieux que de rendre deux fois un asset dont le tampon
/// a pu changer entre-temps. Compilé ou échoué, il ne garde que ses lignes — ses
/// avertissements, ou le message de son échec —, que [`axion_asset_diagnostics`]
/// rend (ADR-148).
///
/// # Safety
///
/// Les trois pointeurs de sortie doivent être accessibles en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_asset_poll(
    ctx: u64,
    job_id: u32,
    out_status: *mut u32,
    out_size: *mut u64,
    out_error: *mut i32,
) -> i32 {
    shielded(Some(ctx), || {
        if out_status.is_null() || out_size.is_null() || out_error.is_null() {
            return AXION_E_INVALID_BUFFER;
        }

        let outcome = context::with(ctx, false, |session| {
            let Some(job) = session.poll_asset_job(job_id) else {
                return Err(AXION_E_INVALID_HANDLE);
            };
            match job {
                context::AssetJob::Running(_) => Ok((AXION_ASSET_PENDING, 0u64, AXION_OK)),
                // Repris par un sondage précédent : seules ses lignes attendent (ADR-148).
                context::AssetJob::Diagnostics(_) => Err(AXION_E_INVALID_HANDLE),
                context::AssetJob::Failed { code, message } => {
                    let code = *code;
                    let message = message.clone();
                    session.keep_asset_diagnostics(job_id, vec![message]);
                    Ok((AXION_ASSET_FAILED, 0, code))
                }
                context::AssetJob::NeedsFiles(names) => {
                    let list = encode_names(names);
                    let written = session.buffers().write_payload(BufferKind::AssetOut, &list);
                    session.forget_asset_job(job_id);
                    match written {
                        Some(size) => Ok((AXION_ASSET_NEEDS_FILES, size, AXION_OK)),
                        None => Ok((AXION_ASSET_FAILED, 0, AXION_E_INVALID_BUFFER)),
                    }
                }
                context::AssetJob::Done(asset) => {
                    let bytes = asset.bytes.clone();
                    let warnings = asset.warnings.clone();
                    let written = session
                        .buffers()
                        .write_payload(BufferKind::AssetOut, &bytes);
                    match written {
                        Some(size) => {
                            session.keep_asset_diagnostics(job_id, warnings);
                            Ok((AXION_ASSET_COMPILED, size, AXION_OK))
                        }
                        None => {
                            let message = format!(
                                "l'asset compilé ({} octets) ne tient pas dans ASSET_OUT",
                                bytes.len()
                            );
                            session.keep_asset_diagnostics(job_id, vec![message]);
                            Ok((AXION_ASSET_FAILED, 0, AXION_E_INVALID_BUFFER))
                        }
                    }
                }
            }
        });

        match outcome {
            Ok(Ok((status, size, error))) => {
                // SAFETY: nullité écartée ci-dessus.
                unsafe {
                    out_status.write(status);
                    out_size.write(size);
                    out_error.write(error);
                }
                AXION_OK
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Rend les lignes d'une compilation que le sondage a terminée (IF-06, ADR-148) : ses
/// avertissements si elle a abouti, le message de son échec sinon.
///
/// Les lignes sont déposées dans le tampon `ASSET_OUT` au format de la liste
/// `NEEDS_FILES` (ADR-139) — `u32` du nombre, puis par ligne `u32` de sa longueur et ses
/// octets UTF-8 (R-263), petit-boutiste —, `out_size` reçoit leur taille, et la session
/// les oublie. Sans ligne, `out_size` reçoit 0 et `ASSET_OUT` n'est pas touché : il garde
/// l'asset que le sondage y a déposé.
///
/// Rend `E-2001` si `job_id` ne retient pas de lignes — travail jamais sondé, en cours,
/// déjà lu ou abandonné — et `E-2002` si `ASSET_OUT` ne peut pas les contenir, les lignes
/// étant alors oubliées. L'appel ne bloque jamais (R-280).
///
/// # Safety
///
/// `out_size` doit être accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_asset_diagnostics(ctx: u64, job_id: u32, out_size: *mut u64) -> i32 {
    shielded(Some(ctx), || {
        if out_size.is_null() {
            return AXION_E_INVALID_BUFFER;
        }

        let outcome = context::with(ctx, false, |session| {
            let Some(lines) = session.take_asset_diagnostics(job_id) else {
                return Err(AXION_E_INVALID_HANDLE);
            };
            if lines.is_empty() {
                return Ok(0);
            }
            session
                .buffers()
                .write_payload(BufferKind::AssetOut, &encode_names(&lines))
                .ok_or(AXION_E_INVALID_BUFFER)
        });

        match outcome {
            Ok(Ok(size)) => {
                // SAFETY: nullité écartée ci-dessus.
                unsafe { out_size.write(size) };
                AXION_OK
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Plus grande charge utile réseau que la compression et la décompression acceptent, en octets.
///
/// Au-delà de toute charge de M4 — un registre de definitions et de matériaux compressé tient en
/// quelques dizaines de Kio. Elle borne ce qu'une borne d'appelant trop large ferait allouer : une
/// trame qui déclare plus est refusée sans allocation (R-1691).
pub const AXION_NET_MAX_PAYLOAD: u64 = 64 * 1024 * 1024;

/// Compresse en zstd une charge utile réseau (C-51, ADR-150 D5).
///
/// L'entrée est lue dans `NET_OUT`, où Java l'a écrite : `input_len` octets après l'en-tête, R-313
/// interdisant à une fonction FFI d'allouer côté Java. La trame compressée la remplace dans
/// `NET_OUT`, sa taille dans `out_size` ; le tampon peut avoir été agrandi, et sa génération
/// avancée (R-270) : Java réacquiert sa vue avant de lire.
///
/// Rend `E-2002` si l'entrée sort du tampon ou dépasse [`AXION_NET_MAX_PAYLOAD`], ou si libzstd
/// refuse. L'appel ne bloque jamais (R-280).
///
/// # Safety
///
/// `out_size` doit être accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_net_compress(ctx: u64, input_len: u64, out_size: *mut u64) -> i32 {
    shielded(Some(ctx), || {
        if out_size.is_null() || input_len > AXION_NET_MAX_PAYLOAD {
            return AXION_E_INVALID_BUFFER;
        }
        let outcome = context::with(ctx, false, |session| {
            // L'entrée est copiée avant d'écrire la sortie au même endroit.
            let input = session
                .buffers()
                .payload(BufferKind::NetOut, input_len)
                .ok_or(AXION_E_INVALID_BUFFER)?
                .to_vec();
            let frame =
                ax_asset::compression::compress(&input, ax_asset::compression::NETWORK_LEVEL)
                    .ok_or(AXION_E_INVALID_BUFFER)?;
            session
                .buffers()
                .write_payload(BufferKind::NetOut, &frame)
                .ok_or(AXION_E_INVALID_BUFFER)
        });
        // SAFETY: `out_size` non nul, écarté ci-dessus ; accessible en écriture, contrat de l'appel.
        unsafe { write_net_size(outcome, out_size) }
    })
}

/// Décompresse une trame zstd reçue du réseau (C-51, ADR-150 D5), sans dépasser `max_output`.
///
/// La trame est lue dans `NET_OUT`, où Java l'a écrite (`input_len` octets) ; le contenu la
/// remplace, sa taille dans `out_size`. La taille que la trame déclare est comparée à
/// `max_output` — ramené à [`AXION_NET_MAX_PAYLOAD`] — **avant** toute allocation (R-1691), et une
/// trame dont le contenu ne fait pas exactement cette taille est refusée.
///
/// Rend `E-2002` si la trame sort du tampon, n'est pas une trame zstd, ne déclare pas sa taille,
/// déclare plus que la borne ou ment sur elle. L'appel ne bloque jamais (R-280).
///
/// # Safety
///
/// `out_size` doit être accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_net_decompress(
    ctx: u64,
    input_len: u64,
    max_output: u64,
    out_size: *mut u64,
) -> i32 {
    shielded(Some(ctx), || {
        // La sortie est bornée ; l'entrée l'est par le tampon que Java a acquis, et une trame
        // plus longue que son contenu borné est refusée par la taille qu'elle déclare.
        if out_size.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        let Ok(bound) = usize::try_from(max_output.min(AXION_NET_MAX_PAYLOAD)) else {
            return AXION_E_INVALID_BUFFER;
        };
        let outcome = context::with(ctx, false, |session| {
            let frame = session
                .buffers()
                .payload(BufferKind::NetOut, input_len)
                .ok_or(AXION_E_INVALID_BUFFER)?
                .to_vec();
            let content = ax_asset::compression::decompress_bounded(&frame, bound)
                .ok_or(AXION_E_INVALID_BUFFER)?;
            session
                .buffers()
                .write_payload(BufferKind::NetOut, &content)
                .ok_or(AXION_E_INVALID_BUFFER)
        });
        // SAFETY: `out_size` non nul, écarté ci-dessus ; accessible en écriture, contrat de l'appel.
        unsafe { write_net_size(outcome, out_size) }
    })
}

/// Rend le code d'une opération sur `NET_OUT`, et sa taille de sortie en cas de succès.
///
/// # Safety
///
/// `out_size` doit être non nul et accessible en écriture.
unsafe fn write_net_size(outcome: Result<Result<u64, i32>, i32>, out_size: *mut u64) -> i32 {
    match outcome {
        Ok(Ok(size)) => {
            // SAFETY: contrat de la fonction.
            unsafe { out_size.write(size) };
            AXION_OK
        }
        Ok(Err(code)) | Err(code) => code,
    }
}

/// Abandonne une compilation que l'appelant n'attend plus (IF-06, ADR-140).
///
/// Le travail quitte la table de la session, quel que soit son état : en file,
/// il ne calcule rien ; en cours, il va au bout et son résultat est libéré à la
/// fin du calcul ; fini, son résultat est libéré tout de suite — et, repris par un
/// sondage, les lignes qu'il garde pour [`axion_asset_diagnostics`] (ADR-148). L'appel
/// ne bloque jamais (R-280).
///
/// Rend `E-2001` si `job_id` ne désigne aucun travail retenu — jamais émis, repris par
/// un sondage dont les lignes ont été lues, ou déjà abandonné —, comme
/// [`axion_asset_poll`].
///
/// # Safety
/// Aucun pointeur n'est déréférencé ; `unsafe` par symétrie avec le reste de
/// l'ABI.
#[no_mangle]
pub unsafe extern "C" fn axion_asset_cancel(ctx: u64, job_id: u32) -> i32 {
    shielded(Some(ctx), || {
        match context::with(ctx, false, |session| session.cancel_asset_job(job_id)) {
            Ok(true) => AXION_OK,
            Ok(false) => AXION_E_INVALID_HANDLE,
            Err(code) => code,
        }
    })
}

/// Bit de la section `NODE` dans un masque de sections (IF-06, ADR-119) : le
/// rang du tag dans la table normative de la PARTIE 7.
pub const AXION_SECTION_NODE: u32 = 1 << 0;

/// Bit de la section `GEOM` dans un masque de sections (IF-06, ADR-119).
pub const AXION_SECTION_GEOM: u32 = 1 << 1;

/// Bit de la section `MATL` dans un masque de sections (IF-06, ADR-122).
pub const AXION_SECTION_MATL: u32 = 1 << 2;

/// Bit de la section `TEXR` dans un masque de sections (IF-06, ADR-122).
pub const AXION_SECTION_TEXR: u32 = 1 << 3;

/// Bit de la section `PHYS` dans un masque de sections (IF-06, ADR-156 D3) : les colliders qu'une
/// definition compile.
pub const AXION_SECTION_PHYS: u32 = 1 << 4;

/// Bit de la section `SKEL` dans un masque de sections (IF-06, ADR-156 D3) : les os que les
/// références d'une definition désignent.
pub const AXION_SECTION_SKEL: u32 = 1 << 5;

/// Bit de la section `ANIM` dans un masque de sections (IF-06, ADR-156 D3) : les clips que les
/// références d'une definition désignent, et que le serveur joue (R-1430).
pub const AXION_SECTION_ANIM: u32 = 1 << 6;

/// Charge un asset compilé et rend son handle (IF-06, ADR-119, ADR-122).
///
/// Le conteneur A3D a été écrit par Java dans la charge utile du tampon
/// `ASSET_IN` ; il se délimite lui-même par le `total_size` de son en-tête (§7.2)
/// — la signature du cahier des charges n'a pas de longueur, et n'en a pas
/// besoin. Le natif **copie** le conteneur hors du tampon partagé sous le verrou
/// de session, le **décode hors verrou** — un gros asset ne fige pas le cycle de
/// simulation —, puis range le résultat sous verrou, imputé à l'arène
/// `PERSISTENT` (R-480).
///
/// `sections_mask` porte le bit *i* pour le *i*-ème tag de la table de la PARTIE
/// 7 ; sont pris en charge [`AXION_SECTION_NODE`], [`AXION_SECTION_GEOM`],
/// [`AXION_SECTION_MATL`] et [`AXION_SECTION_TEXR`], ces deux derniers
/// ensemble, et, pour compiler une definition, [`AXION_SECTION_PHYS`] avec
/// `NODE`, [`AXION_SECTION_SKEL`] et [`AXION_SECTION_ANIM`] avec `PHYS`. Un
/// masque vide, portant un autre bit, ou qui ne respecte pas ces liens est
/// refusé (`E-2002`) : un chargement qui « réussirait » sans rien charger ferait
/// croire le contraire, et les slots des matériaux désignent la table des
/// textures.
///
/// Chaque appel crée un handle indépendant, que Java possède et doit rendre par
/// [`axion_asset_unload`] (R-321) ; un handle non rendu est signalé à l'arrêt
/// (`E-2003`).
///
/// # Safety
///
/// `out` doit pointer sur un [`Handle`] accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_asset_load(
    ctx: u64,
    asset_id: u64,
    sections_mask: u32,
    out: *mut Handle,
) -> i32 {
    shielded(Some(ctx), || {
        if out.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        let Some(sections) = requested_sections(sections_mask) else {
            return AXION_E_INVALID_BUFFER;
        };

        // Copie sous verrou, bornée par ce qu'annonce le conteneur et par le
        // plafond de la configuration (R-901) : après elle, le tampon peut être
        // réalloué sans que le décodage en souffre.
        let copied = context::with(ctx, false, |session| {
            let limits = session.a3d_limits();
            let Some(buffer) = session.buffers().get_mut(BufferKind::AssetIn) else {
                return Err(AXION_E_INVALID_BUFFER);
            };
            let payload = buffer.as_slice().get(HEADER_BYTES..).unwrap_or(&[]);
            let take = match announced_total_size(payload) {
                Some(total) => usize::try_from(total.min(limits.max_bytes()))
                    .unwrap_or(usize::MAX)
                    .min(payload.len()),
                // Pas un A3D : l'en-tête seul suffit à le dire, rien de plus n'est
                // copié.
                None => payload.len().min(A3D_HEADER_BYTES),
            };
            Ok((payload[..take].to_vec(), limits))
        });
        let (container, limits) = match copied {
            Ok(Ok(copy)) => copy,
            Ok(Err(code)) | Err(code) => return code,
        };

        // Décodage hors verrou : décompression, contrôles et pose de repos.
        let asset = match decode_render_asset(&container, asset_id, sections, limits) {
            Ok(asset) => asset,
            Err(error) => {
                let message = format!("axion_asset_load {asset_id:#018x} : {error}");
                let _ = context::with(ctx, false, |session| session.set_last_error(message));
                return error.code();
            }
        };

        let stored = context::with(ctx, false, |session| match session.insert_asset(asset) {
            Ok(handle) => Ok(handle),
            Err(error) => {
                session.set_last_error(format!("axion_asset_load {asset_id:#018x} : {error}"));
                Err(error.code())
            }
        });
        match stored {
            Ok(Ok(handle)) => {
                // SAFETY: nullité écartée ci-dessus ; le contrat impose que `out`
                // soit accessible en écriture.
                unsafe { out.write(handle) };
                AXION_OK
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Dépose dans `ASSET_OUT` la géométrie d'un asset chargé et sa liste de dessin
/// au repos (ajout à IF-06, ADR-119 §3) ; `out_size` reçoit la taille de la
/// charge utile.
///
/// La charge utile est rebâtie depuis l'asset résident à chaque appel : Java
/// peut la redemander sans recharger l'asset, par exemple pour reconstruire ses
/// ressources GPU après un changement de resource pack (R-752).
///
/// Rend `E-2001` si le handle est périmé, `E-2002` si l'asset n'a pas été chargé
/// avec `NODE | GEOM`.
///
/// # Safety
///
/// `out_size` doit pointer sur un `u64` accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_asset_geometry(ctx: u64, handle: Handle, out_size: *mut u64) -> i32 {
    shielded(Some(ctx), || {
        if out_size.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        match context::with(ctx, false, |session| {
            session.write_geometry_transfer(handle)
        }) {
            Ok(Ok(size)) => {
                // SAFETY: nullité écartée ci-dessus.
                unsafe { out_size.write(size) };
                AXION_OK
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Dépose dans `ASSET_OUT` la table des matériaux et des textures d'un asset
/// chargé avec `MATL | TEXR` (ajout à IF-06, ADR-122 §6) ; `out_size` reçoit la
/// taille de la charge utile.
///
/// La charge porte un en-tête `{material_count, texture_count, path_bytes, 0}`,
/// les `MaterialDesc` (DM-05), les `TextureDesc`, puis les chemins des textures
/// de ressource — disposition décrite par
/// [`encode_material_transfer`](ax_model::dm::render::encode_material_transfer).
/// Tout y a été contrôlé au chargement : comptes sous les plafonds de C-22,
/// slots dans la table ou vides, énumérations connues, facteurs finis,
/// `alpha_cutoff` dans `[0, 1]`, chemins dans `paths`.
///
/// Rend `E-2001` si le handle est périmé, `E-2002` si l'asset n'a pas été chargé
/// avec `MATL | TEXR`.
///
/// # Safety
///
/// `out_size` doit pointer sur un `u64` accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_asset_materials(
    ctx: u64,
    handle: Handle,
    out_size: *mut u64,
) -> i32 {
    shielded(Some(ctx), || {
        if out_size.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        match context::with(ctx, false, |session| {
            session.write_material_transfer(handle)
        }) {
            Ok(Ok(size)) => {
                // SAFETY: nullité écartée ci-dessus.
                unsafe { out_size.write(size) };
                AXION_OK
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Dépose dans `ASSET_OUT` les octets PNG de la texture embarquée de rang
/// `texture` d'un asset chargé (ajout à IF-06, ADR-122 §6) ; `out_size` reçoit
/// leur nombre.
///
/// Une texture par appel : une table de 128 textures de 4096² ne transite pas
/// d'un bloc. Les octets sont ceux du fichier, non décodés (R-532) — le natif
/// n'en a lu que la signature et l'`IHDR`.
///
/// Rend `E-2001` si le handle est périmé, `E-2002` si la texture est hors de la
/// table — l'asset chargé sans `TEXR` n'en a pas — ou n'est pas embarquée.
///
/// # Safety
///
/// `out_size` doit pointer sur un `u64` accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_asset_texture(
    ctx: u64,
    handle: Handle,
    texture: u32,
    out_size: *mut u64,
) -> i32 {
    shielded(Some(ctx), || {
        if out_size.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        match context::with(ctx, false, |session| {
            session.write_embedded_texture(handle, texture)
        }) {
            Ok(Ok(size)) => {
                // SAFETY: nullité écartée ci-dessus.
                unsafe { out_size.write(size) };
                AXION_OK
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Rend un asset chargé (IF-06) : ses octets quittent l'arène `PERSISTENT` et
/// son handle devient périmé (R-110). Un handle déjà périmé rend `E-2001`, sans
/// effet de bord.
///
/// # Safety
///
/// Aucun pointeur n'est déréférencé ; la fonction est `unsafe` par symétrie avec
/// le reste de l'ABI.
#[no_mangle]
pub unsafe extern "C" fn axion_asset_unload(ctx: u64, handle: Handle) -> i32 {
    shielded(Some(ctx), || {
        match context::with(ctx, false, |session| session.remove_asset(handle)) {
            Ok(true) => AXION_OK,
            Ok(false) => AXION_E_INVALID_HANDLE,
            Err(code) => code,
        }
    })
}

/// Compile une definition contre un asset chargé (étape 4 de C-27, ADR-156 D2, D4), et l'inscrit au
/// registre de la session sous son empreinte `definition` (étape 5).
///
/// Le `DEFN` est lu dans `ASSET_IN`, où Java l'a écrit : `defn_len` octets après l'en-tête, R-313
/// interdisant à une fonction FFI d'allouer côté Java. L'asset est celui de `asset`, chargé avec
/// `NODE | PHYS` — et `SKEL`, `ANIM` s'il en a. Les deux sont copiés sous le verrou de session, la
/// compilation se fait **hors verrou** — elle ne fige pas le cycle de simulation —, et le résultat
/// est inscrit sous verrou, à la place du précédent.
///
/// `ASSET_OUT` reçoit la réponse, `out_size` sa taille :
///
/// - en cas de succès, un [`DefinitionSummary`] : le node racine de chaque corps, le nombre de
///   joints, l'animation serveur, les avertissements (R-930) ;
/// - en cas de refus, un [`DefinitionRefusal`] : le rang de la référence fautive et le motif. La
///   definition quitte alors le registre, et l'appel rend le code du refus : `E-7001` (R-580),
///   `E-3050` (R-190) ou `E-7002` (R-931).
///
/// Rend `E-2001` si le handle est périmé, `E-2002` si l'asset n'a pas été chargé avec `PHYS`, si le
/// `DEFN` sort du tampon ou se lit mal — un défaut de Java, jamais de la definition, que le dernier
/// message d'erreur décrit —, ou si `ASSET_OUT` ne peut recevoir la réponse ; le registre est alors
/// inchangé. L'appel ne bloque jamais (R-280).
///
/// # Safety
///
/// `out_size` doit être accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_definition_compile(
    ctx: u64,
    definition: u64,
    asset: Handle,
    defn_len: u64,
    out_size: *mut u64,
) -> i32 {
    shielded(Some(ctx), || {
        if out_size.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        let copied = context::with(ctx, false, |session| {
            let bytes = session
                .buffers()
                .payload(BufferKind::AssetIn, defn_len)
                .ok_or(AXION_E_INVALID_BUFFER)?
                .to_vec();
            Ok((bytes, session.definition_sections(asset)?))
        });
        let (bytes, sections) = match copied {
            Ok(Ok(copy)) => copy,
            Ok(Err(code)) | Err(code) => return code,
        };
        let parsed = match Definition::read_le(&bytes) {
            Ok(parsed) => parsed,
            Err(error) => {
                let message = format!(
                    "axion_definition_compile {definition:#018x} : DEFN illisible ({error:?})"
                );
                let _ = context::with(ctx, false, |session| session.set_last_error(message));
                return AXION_E_INVALID_BUFFER;
            }
        };

        // Hors verrou : la résolution des noms et le rangement des colliders.
        let mut payload = Vec::new();
        let (code, compiled) = match crate::definition::compile(&parsed, &sections.view()) {
            Ok(compiled) => {
                DefinitionSummary {
                    roots: compiled.roots.clone(),
                    joint_count: u32::try_from(compiled.joints.len()).unwrap_or(u32::MAX),
                    flags: if compiled.animation.is_some() {
                        summary_flags::SERVER_ANIMATED
                    } else {
                        0
                    },
                    warnings: compiled.warnings.clone(),
                }
                .write_le(&mut payload);
                (AXION_OK, Some(compiled))
            }
            Err(refusal) => {
                DefinitionRefusal {
                    code: refusal.code,
                    reference: refusal.reference.unwrap_or(NONE_U32),
                    message: refusal.message,
                }
                .write_le(&mut payload);
                (refusal.code, None)
            }
        };

        let written = context::with(ctx, false, |session| {
            let size = session
                .buffers()
                .write_payload(BufferKind::AssetOut, &payload)
                .ok_or(AXION_E_INVALID_BUFFER)?;
            match compiled {
                Some(compiled) => session.register_definition(definition, compiled),
                None => {
                    session.remove_definition(definition);
                }
            }
            Ok(size)
        });
        match written {
            Ok(Ok(size)) => {
                // SAFETY: nullité écartée ci-dessus ; accessible en écriture, contrat de l'appel.
                unsafe { out_size.write(size) };
                code
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Retire une definition du registre de la session (ADR-156 D4) : celle qu'un rechargement a fait
/// disparaître. Une assembly déjà créée garde ce qu'elle en a pris. Rend `E-2001` si le registre ne
/// la tient pas, sans effet de bord.
///
/// # Safety
///
/// Aucun pointeur n'est déréférencé ; la fonction est `unsafe` par symétrie avec le reste de l'ABI.
#[no_mangle]
pub unsafe extern "C" fn axion_definition_remove(ctx: u64, definition: u64) -> i32 {
    shielded(Some(ctx), || {
        match context::with(ctx, false, |session| session.remove_definition(definition)) {
            Ok(true) => AXION_OK,
            Ok(false) => AXION_E_INVALID_HANDLE,
            Err(code) => code,
        }
    })
}

/// Overlay `colliders` (C-67, ADR-121) : bit 0 du masque, rang de l'overlay dans
/// la liste du §31.4.
pub const AXION_OVERLAY_COLLIDERS: u64 = overlay::COLLIDERS;
/// Overlay `aabb` (amendement A1 d'ADR-121) : bit 1.
pub const AXION_OVERLAY_AABB: u64 = overlay::AABB;
/// Overlay `bodies` (A1) : bit 2.
pub const AXION_OVERLAY_BODIES: u64 = overlay::BODIES;
/// Overlay `contacts` (A1) : bit 3.
pub const AXION_OVERLAY_CONTACTS: u64 = overlay::CONTACTS;
/// Overlay `normals` (A1) : bit 4.
pub const AXION_OVERLAY_NORMALS: u64 = overlay::NORMALS;
/// Overlay `joints` (ADR-151 D7) : bit 5.
pub const AXION_OVERLAY_JOINTS: u64 = overlay::JOINTS;
/// Overlay `limits` (ADR-151 D7) : bit 6.
pub const AXION_OVERLAY_LIMITS: u64 = overlay::LIMITS;
/// Overlay `world_tiles` (A1) : bit 30.
pub const AXION_OVERLAY_WORLD_TILES: u64 = overlay::WORLD_TILES;
/// Overlay `center_of_mass` (A1) : bit 31.
pub const AXION_OVERLAY_CENTER_OF_MASS: u64 = overlay::CENTER_OF_MASS;
/// Overlay `bones` (ADR-155, tranche 6b) : bit 10, tracé par [`axion_render_debug`].
pub const AXION_OVERLAY_BONES: u64 = overlay::BONES;
/// Overlay `nodes` (ADR-155, tranche 6b) : bit 11, tracé par [`axion_render_debug`].
pub const AXION_OVERLAY_NODES: u64 = overlay::NODES;
/// Overlay `pivots` (ADR-155, tranche 6b) : bit 12, tracé par [`axion_render_debug`].
pub const AXION_OVERLAY_PIVOTS: u64 = overlay::PIVOTS;

/// Overlays dont cette version sait produire la géométrie.
const SUPPORTED_OVERLAYS: u64 = AXION_OVERLAY_COLLIDERS
    | AXION_OVERLAY_AABB
    | AXION_OVERLAY_BODIES
    | AXION_OVERLAY_CONTACTS
    | AXION_OVERLAY_NORMALS
    | AXION_OVERLAY_JOINTS
    | AXION_OVERLAY_LIMITS
    | AXION_OVERLAY_WORLD_TILES
    | AXION_OVERLAY_CENTER_OF_MASS;

/// Dépose dans `DEBUG` la géométrie des overlays demandés (C-67, ADR-121 et son
/// amendement A1) : pour les corps d'assembly et les tuiles de `dimension`, les
/// plus proches de la caméra d'abord, au plus `max_segments` segments, chaque plage
/// dans son repère. `out_size` reçoit la taille de la charge utile (schéma 2 de
/// `DEBUG`).
///
/// Lecture seule : le monde physique n'est pas modifié. Appelée seulement quand
/// un overlay est allumé (R-800) ; un masque vide ou portant un overlay que cette
/// version ne trace pas est refusé `E-2002` — un appel qui « réussit » sans rien
/// produire ferait croire à un overlay tracé. Une caméra non finie aussi.
///
/// # Safety
///
/// `out_size` doit pointer sur un `u64` accessible en écriture.
#[no_mangle]
#[allow(clippy::too_many_arguments)] // signature d'ABI fixée par ADR-121
pub unsafe extern "C" fn axion_debug_fill(
    ctx: u64,
    overlay_mask: u64,
    dimension: u64,
    camera_x: f64,
    camera_y: f64,
    camera_z: f64,
    max_segments: u32,
    out_size: *mut u64,
) -> i32 {
    shielded(Some(ctx), || {
        if out_size.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        if overlay_mask == 0 || overlay_mask & !SUPPORTED_OVERLAYS != 0 {
            return AXION_E_INVALID_BUFFER;
        }
        let camera = DVec3::new(camera_x, camera_y, camera_z);
        if !camera.is_finite() {
            return AXION_E_INVALID_BUFFER;
        }
        let written = context::with(ctx, false, |session| {
            let debug =
                session
                    .physics()
                    .debug_overlays(dimension, camera, overlay_mask, max_segments);
            let flags = if debug.omitted_ranges > 0 {
                debug_flags::TRUNCATED
            } else {
                0
            };
            let payload =
                encode_debug_payload(&debug.ranges, &debug.segments, flags, debug.omitted_ranges)?;
            session.buffers().write_payload(BufferKind::Debug, &payload)
        });
        match written {
            Ok(Some(size)) => {
                // SAFETY: nullité écartée ci-dessus ; le contrat impose que
                // `out_size` soit accessible en écriture.
                unsafe { out_size.write(size) };
                AXION_OK
            }
            Ok(None) => AXION_E_INVALID_BUFFER,
            Err(code) => code,
        }
    })
}

/// Traduit le code de format de source de l'ABI.
fn decode_source_format(raw: u32) -> Option<SourceFormat> {
    match raw {
        0 => Some(SourceFormat::Glb),
        1 => Some(SourceFormat::Gltf),
        2 => Some(SourceFormat::Obj),
        3 => Some(SourceFormat::Stl),
        _ => None,
    }
}

/// Description d'un tampon telle qu'elle traverse la frontière (IF-02).
///
/// `repr(C)` est obligatoire : R-262 interdit qu'une structure `repr(Rust)`
/// traverse, sa disposition n'étant garantie par rien.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct AxionBufferInfo {
    /// Adresse du premier octet du tampon.
    pub ptr: *mut u8,
    /// Capacité utilisable, en octets.
    pub capacity: u64,
    /// Nature du tampon.
    pub kind: u32,
    /// Génération : Java ré-acquiert dès qu'elle change (R-270).
    pub generation: u32,
}

/// Acquiert un tampon de transfert.
///
/// `min_capacity` s'entend en octets de charge utile : la place de l'en-tête
/// est réservée en plus. Le tampon existant est réutilisé s'il suffit ; sinon
/// il est remplacé et la génération avance, ce qui invalide la vue que Java
/// détenait (R-270).
///
/// # Safety
///
/// `out` doit pointer sur un [`AxionBufferInfo`] accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_buffer_acquire(
    ctx: u64,
    kind: u32,
    min_capacity: u64,
    out: *mut AxionBufferInfo,
) -> i32 {
    shielded(Some(ctx), || {
        if out.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        // Un kind venu de Java est une donnée externe : il est traduit, jamais
        // utilisé comme indice tel quel (interdiction 3.13).
        let Some(kind) = BufferKind::from_u32(kind) else {
            return AXION_E_INVALID_BUFFER;
        };

        // Les tampons du rendu vivent hors de la session (ADR-154 D2) : leur acquisition ne
        // prend pas le verrou que tient le pas physique.
        let acquire = |buffers: &mut ax_core::BufferPool| {
            let info = buffers.acquire(kind, min_capacity);
            // L'en-tête est posé dès l'acquisition : Java y lit la génération
            // sans second appel, et un tampon fraîchement acquis n'est jamais
            // dans un état que `BufferHeader::read` refuserait. La version de
            // schéma est celle du kind (R-262) : elle dit à Java comment lire la
            // charge — pour SIM_OUT, si les emprises suivent les états (ADR-120).
            if let Some(buffer) = buffers.get_mut(kind) {
                BufferHeader {
                    kind,
                    generation: info.generation,
                    schema_version: kind.schema_version(),
                    payload_len: 0,
                    element_count: 0,
                    crc32c: 0,
                }
                .write(buffer.as_mut_slice());
            }
            info
        };
        let info = if is_render_kind(kind) {
            render::with(ctx, |render| acquire(render.buffers()))
        } else {
            context::with(ctx, false, |session| acquire(session.buffers()))
        };
        match info {
            Ok(info) => {
                // SAFETY: `out` est non nul, et le contrat impose qu'il soit
                // accessible en écriture.
                unsafe {
                    out.write(AxionBufferInfo {
                        ptr: info.ptr,
                        capacity: info.capacity,
                        kind: info.kind,
                        generation: info.generation,
                    });
                }
                AXION_OK
            }
            Err(code) => code,
        }
    })
}

/// Libère un tampon de transfert.
///
/// La génération doit être celle du tampon courant : une génération périmée
/// signale que l'appelant raisonne sur un tampon qui n'existe plus, et l'appel
/// est refusé avec `E-2002` plutôt que de libérer le mauvais (R-270).
///
/// # Safety
///
/// Aucun paramètre n'est déréférencé ; la fonction est `unsafe` par symétrie
/// avec le reste de l'ABI.
#[no_mangle]
pub unsafe extern "C" fn axion_buffer_release(ctx: u64, kind: u32, generation: u32) -> i32 {
    shielded(Some(ctx), || {
        let Some(kind) = BufferKind::from_u32(kind) else {
            return AXION_E_INVALID_BUFFER;
        };
        let released = if is_render_kind(kind) {
            render::with(ctx, |render| render.buffers().release(kind, generation))
        } else {
            context::with(ctx, false, |session| {
                session.buffers().release(kind, generation)
            })
        };
        match released {
            Ok(true) => AXION_OK,
            Ok(false) => AXION_E_INVALID_BUFFER,
            Err(code) => code,
        }
    })
}

/// Vrai pour les tampons du contexte de rendu (ADR-154 D2, ADR-155 tranche 6b).
const fn is_render_kind(kind: BufferKind) -> bool {
    matches!(
        kind,
        BufferKind::RenderIn | BufferKind::RenderOut | BufferKind::RenderDebug
    )
}

/// Résultat d'un [`axion_render_prepare`] (IF-05, §4.7), champ pour champ.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct AxionRenderResult {
    /// Candidats retenus, déposés dans `RENDER_OUT`.
    pub instance_count: u32,
    /// Groupes de dessin (C-65) ; 0 avant le regroupement.
    pub draw_group_count: u32,
    /// Octets de palettes (animation) ; 0 avant elle.
    pub palette_bytes: u32,
    /// Pages de déformation (M6) ; 0 avant elle.
    pub deform_page_count: u32,
    /// Décalques ; 0 avant eux.
    pub decal_count: u32,
    /// Triangles des retenus, au LOD 0.
    pub triangle_estimate: u32,
    /// Identifiant de la frame, à rendre par [`axion_render_release`].
    pub frame_id: u32,
    /// Drapeaux, voir [`AXION_RENDER_DEGRADED`].
    pub flags: u32,
}

pub use crate::render::{AXION_RENDER_CAPPED, AXION_RENDER_DEGRADED};

/// Prépare une frame de rendu (IF-05, ADR-154 D3).
///
/// Lit `AxionCameraDesc` (112 octets) puis les candidats que Java a déposés dans `RENDER_IN`,
/// les culle, et dépose les retenus dans `RENDER_OUT`, valides jusqu'à
/// [`axion_render_release`] (R-301). Ne prend jamais le verrou de session : elle n'attend pas
/// un pas physique (ADR-154 D2). Au-delà de `budget_ns`, le résultat est partiel et porte
/// [`AXION_RENDER_DEGRADED`] (R-300). `partial_tick` est reçu sans être lu : Java interpole
/// les poses avant de les remettre.
///
/// # Safety
///
/// `cam` doit pointer sur 112 octets lisibles, `out` sur un [`AxionRenderResult`] accessible en
/// écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_render_prepare(
    ctx: u64,
    cam: *const u8,
    _partial_tick: f32,
    budget_ns: u64,
    out: *mut AxionRenderResult,
) -> i32 {
    shielded(Some(ctx), || {
        if cam.is_null() || out.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        // SAFETY: le contrat impose 112 octets lisibles à partir de `cam`, non nul.
        let camera = unsafe { std::slice::from_raw_parts(cam, ax_render_prep::Camera::BYTES) };
        match render::with(ctx, |render| render.prepare(camera, budget_ns)) {
            Ok(Ok(prepared)) => {
                // SAFETY: `out` est non nul, et le contrat impose qu'il soit accessible en
                // écriture.
                unsafe {
                    out.write(AxionRenderResult {
                        instance_count: prepared.instance_count,
                        triangle_estimate: prepared.triangle_estimate,
                        palette_bytes: prepared.palette_bytes,
                        frame_id: prepared.frame_id,
                        flags: prepared.flags,
                        ..AxionRenderResult::default()
                    });
                }
                AXION_OK
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Applique des commandes d'animation au contexte de rendu (ADR-155, tranche 3 ; ABI 10).
///
/// `bytes` porte une suite de commandes, chacune un `RenderAnimate` (16 octets), une `AnimCommand`
/// (40 octets) et ses nodes de masque. Java les pousse quand l'état d'animation d'une assembly
/// change ; `axion_render_prepare` évalue ensuite chaque assembly animée au tick de rendu. Ne prend
/// jamais le verrou de session (ADR-154 D2). `applied`, s'il n'est pas nul, reçoit le nombre de
/// commandes appliquées ; une commande refusée ne change rien et est comptée au bilan du rendu.
///
/// # Safety
///
/// `bytes` doit pointer sur `len` octets lisibles (nul si `len` vaut 0), `applied` être nul ou
/// accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_render_animate(
    ctx: u64,
    bytes: *const u8,
    len: usize,
    applied: *mut u32,
) -> i32 {
    shielded(Some(ctx), || {
        let commands = if len == 0 {
            &[][..]
        } else if bytes.is_null() {
            return AXION_E_INVALID_BUFFER;
        } else {
            // SAFETY: le contrat impose `len` octets lisibles à partir de `bytes`, non nul.
            unsafe { std::slice::from_raw_parts(bytes, len) }
        };
        match render::with(ctx, |render| render.animate(commands)) {
            Ok(Ok(count)) => {
                if !applied.is_null() {
                    // SAFETY: non nul, et le contrat l'impose accessible en écriture.
                    unsafe { applied.write(count) };
                }
                AXION_OK
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Overlays que le contexte de rendu sait tracer (ADR-155, tranche 6b).
const RENDER_OVERLAYS: u64 = AXION_OVERLAY_BONES | AXION_OVERLAY_NODES | AXION_OVERLAY_PIVOTS;

/// Dépose dans `RENDER_DEBUG` la géométrie des overlays de l'animation demandés — `bones`, `nodes`,
/// `pivots` — pour les instances animées que la dernière frame préparée a retenues, à la pose qu'elle
/// leur a donnée (ADR-155, tranche 6b, décisions de Killian). Schéma 2 de `DEBUG` : repère d'origine,
/// relatif à la caméra de cette frame, chaque plage au handle `ABSENT` ; les plus proches d'abord, au
/// plus `max_segments` segments. `out_size` reçoit la taille de la charge utile.
///
/// Sous le verrou du rendu seul, jamais celui de la session (ADR-154 D2). À n'appeler que lorsqu'un
/// de ces overlays est allumé (R-2280) ; un masque vide, ou qui porte un overlay que le rendu ne trace
/// pas, est refusé `E-2002`.
///
/// # Safety
///
/// `out_size` doit pointer sur un `u64` accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_render_debug(
    ctx: u64,
    overlay_mask: u64,
    max_segments: u32,
    out_size: *mut u64,
) -> i32 {
    shielded(Some(ctx), || {
        if out_size.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        if overlay_mask == 0 || overlay_mask & !RENDER_OVERLAYS != 0 {
            return AXION_E_INVALID_BUFFER;
        }
        match render::with(ctx, |render| render.debug(overlay_mask, max_segments)) {
            Ok(Ok(size)) => {
                // SAFETY: nullité écartée ci-dessus ; le contrat impose que `out_size` soit
                // accessible en écriture.
                unsafe { out_size.write(size) };
                AXION_OK
            }
            Ok(Err(code)) | Err(code) => code,
        }
    })
}

/// Libère la frame `frame_id` de rendu (R-301) ; `E-2001` si elle n'est pas celle en vol.
///
/// # Safety
///
/// Aucun pointeur n'est déréférencé ; `unsafe` par symétrie avec le reste de l'ABI.
#[no_mangle]
pub unsafe extern "C" fn axion_render_release(ctx: u64, frame_id: u32) -> i32 {
    shielded(Some(ctx), || {
        match render::with(ctx, |render| render.release(frame_id)) {
            Ok(true) => AXION_OK,
            Ok(false) => AXION_E_INVALID_HANDLE,
            Err(code) => code,
        }
    })
}

/// Résultat d'un [`axion_sim_collect`] (IF-03).
#[repr(C)]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct AxionCollectResult {
    /// Nombre de `BodyState` déposés dans `SimOut`.
    pub state_count: u32,
    /// Nombre de `PhysicsEvent` (+ `DamageEvent`, M6) déposés dans `Events`.
    pub event_count: u32,
    /// Pages de champ de déformation modifiées (M6).
    pub deform_page_count: u32,
    /// Colliders refités ce tick (M6).
    pub refit_count: u32,
    /// Détachements décidés (M7).
    pub detach_count: u32,
    /// Octets prêts à émettre sur le réseau (M4).
    pub net_bytes: u32,
    /// Drapeaux, voir [`AXION_SIM_INCOMPLETE`] et [`AXION_SIM_DEGRADED`].
    pub flags: u32,
}

/// Drapeau de collect : tout n'a pas été produit dans le délai (R-281).
pub const AXION_SIM_INCOMPLETE: u32 = 1 << 0;
/// Drapeau de collect : la simulation tourne en qualité dégradée.
pub const AXION_SIM_DEGRADED: u32 = 1 << 1;

/// Durée réelle d'un tick serveur (Minecraft : 1/20 s), injectée telle quelle
/// dans l'accumulateur à pas fixe (R-283). Celui-ci exécute alors autant de
/// sous-pas de `sim.fixed_dt` que le temps écoulé le permet — 3 au défaut
/// (1/20 ÷ 1/60) — clampés à `sim.max_substeps`, sans spirale de rattrapage.
///
/// Injecter ici `sim.fixed_dt` neutralisait l'accumulateur (un seul sous-pas par
/// tick) et faisait tourner la simulation à `fixed_dt ÷ (1/20)` de la vitesse
/// réelle — 1/3 au défaut, soit une chute au ralenti.
const SERVER_TICK_DT: f32 = 1.0 / 20.0;

/// Soumet les entrées d'un tick de simulation (IF-03).
///
/// Lit `command_count` commandes du tampon `SimIn` (protocole ADR-114) et les
/// applique. Un cycle non collecté est clos implicitement et compté (R-282). Les
/// impacts (M6) ne sont pas encore lus : `impact_count` doit valoir 0.
///
/// # Safety
/// Aucun pointeur n'est déréférencé ; `unsafe` par symétrie avec le reste de
/// l'ABI.
#[no_mangle]
pub unsafe extern "C" fn axion_sim_submit(
    ctx: u64,
    tick: u64,
    command_count: u32,
    _impact_count: u32,
) -> i32 {
    shielded(Some(ctx), || {
        match context::with(ctx, false, |session| {
            // R-280 : le submit se mesure contre `budgets.submit_ns` (INV-19).
            let started = std::time::Instant::now();
            // L'horloge de l'animation serveur : le tick de jeu que Java soumet (ADR-155).
            session.set_sim_tick(tick);
            let code = submit_commands(session, command_count);
            session.record_submit(elapsed_ns(started));
            code
        }) {
            Ok(code) | Err(code) => code,
        }
    })
}

/// Le travail d'`axion_sim_submit` : ouvre le cycle et applique les commandes du tick.
fn submit_commands(session: &mut context::Session, command_count: u32) -> i32 {
    session.open_sim_cycle();
    // L'état par tick du pilote (observateurs, ADR-123 §2) repart de zéro : seules
    // les commandes de ce tick le rempliront — un tick sans commande n'en a aucun.
    session.physics().begin_tick();
    if command_count == 0 {
        return AXION_OK;
    }
    // La tranche de commandes est copiée hors du tampon partagé, qui peut
    // être réalloué (R-270) ; le parseur travaille sur cette copie.
    //
    // R-491 : l'en-tête est validé (magic, kind) avant toute lecture d'un
    // tampon fourni par Java. La longueur, elle, voyage par paramètre
    // (`command_count`) et non dans `payload_len` — convention IF-02, où
    // le natif pré-remplit l'en-tête à l'acquisition et Java n'écrit que la
    // charge : on lit donc le tail, borné ensuite par le parseur.
    let bytes = match session.buffers().get_mut(BufferKind::SimIn) {
        Some(buffer) => {
            let raw = buffer.as_slice();
            match BufferHeader::read(raw) {
                Ok(header) if header.kind == BufferKind::SimIn => {
                    raw.get(HEADER_BYTES..).unwrap_or(&[]).to_vec()
                }
                _ => return AXION_E_INVALID_BUFFER,
            }
        }
        None => return AXION_E_INVALID_BUFFER,
    };
    // CREATE_ASSEMBLY dépend d'ax-asset (décodage de la section PHYS) : la
    // frontière — seule à lier ax-asset et ax-physics — l'extrait et
    // l'applique ici, avant le reste du flux (qu'ax-physics reporte).
    let assemblies = match ax_physics::create_assembly_payloads(&bytes, command_count) {
        Ok(payloads) => payloads,
        Err(_) => return AXION_E_INVALID_BUFFER,
    };
    for payload in &assemblies {
        apply_create_assembly(session, payload);
    }
    // ADR-155 : les commandes d'animation, après les créations du même flux.
    let Ok(animations) = ax_physics::payloads_of(&bytes, command_count, opcode::ANIMATE_ASSEMBLY)
    else {
        return AXION_E_INVALID_BUFFER;
    };
    for payload in &animations {
        apply_animate_assembly(session, payload);
    }
    let Ok(removed) = ax_physics::payloads_of(&bytes, command_count, opcode::REMOVE_ASSEMBLY)
    else {
        return AXION_E_INVALID_BUFFER;
    };
    for payload in &removed {
        if let Some(handle) = read_handle_at(payload, 0) {
            session.forget_animation(handle);
        }
    }
    match ax_physics::apply_command_stream(session.physics(), &bytes, command_count) {
        Ok(_) => AXION_OK,
        Err(_) => AXION_E_INVALID_BUFFER,
    }
}

/// Le handle `{index, generation}` à la position `at` d'un payload, s'il y tient.
fn read_handle_at(payload: &[u8], at: usize) -> Option<Handle> {
    let word = |at: usize| {
        payload
            .get(at..at + 4)
            .map(|bytes| u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    };
    Some(Handle::new(word(at)?, word(at + 4)?))
}

/// Applique un `ANIMATE_ASSEMBLY` (ADR-155) : `AnimateAssembly`, puis les nodes de son masque. Un
/// payload tronqué, ou qui annonce un masque démesuré, est ignoré.
fn apply_animate_assembly(session: &mut context::Session, payload: &[u8]) {
    let Some(handle) = read_handle_at(payload, 0) else {
        return;
    };
    let Some(body) = payload.get(8..AnimateAssembly::BYTES) else {
        return;
    };
    let u32_at =
        |at: usize| u32::from_le_bytes([body[at], body[at + 1], body[at + 2], body[at + 3]]);
    let command = AnimCommand {
        tick: f64::from_le_bytes(body[0..8].try_into().unwrap_or([0; 8])),
        action: u32_at(8),
        layer: u32_at(12),
        clip: u32_at(16),
        weight: f32::from_bits(u32_at(20)),
        fade: f32::from_bits(u32_at(24)),
        speed: f32::from_bits(u32_at(28)),
        mask_count: u32_at(32),
        origin: f32::from_bits(u32_at(36)),
    };
    let count = command.mask_count as usize;
    if count > ax_model::dm::limits::MAX_NODES {
        return;
    }
    let Some(nodes) = payload.get(AnimateAssembly::BYTES..AnimateAssembly::BYTES + count * 4)
    else {
        return;
    };
    let mask: Vec<u32> = nodes
        .chunks_exact(4)
        .map(|word| u32::from_le_bytes([word[0], word[1], word[2], word[3]]))
        .collect();
    session.animate(handle, &command, &mask);
}

/// Applique un `CREATE_ASSEMBLY` (ADR-151 D7) : le module `assembly` décode l'en-tête, les
/// corps et les sections `NODE` et `PHYS`, range chaque collider dans le corps de son node,
/// puis le pilote crée tous les corps ou aucun.
///
/// Un payload trop court, une description invalide, un corps d'un type inattendu, une
/// section illisible, une forme non convertible, une échelle non uniforme sur la chaîne
/// d'un collider, des groupes discordants ou un joint fautif font **abandonner cette
/// assembly** sans créer de corps — jamais de panique ni d'assembly à moitié formée. La
/// commande a déjà été lue par `create_assembly_payloads`, qui a validé les bornes du flux.
fn apply_create_assembly(session: &mut context::Session, payload: &[u8]) {
    let Some(mut request) = assembly::decode(payload) else {
        return;
    };
    let animation = request.animation.take();
    let physics = session.physics();
    let header = request.header;
    let Some(created) = physics.create_assembly_bodies(
        header.dimension,
        header.handle,
        header.spawn,
        &request.bodies,
        &request.joints,
        request.groups,
        header.assembly_mass,
        header.flags & create_assembly_flags::SELF_COLLISION != 0,
    ) else {
        return;
    };
    // ADR-153 D3 : l'état que la sauvegarde a gardé, rendu avant tout pas, sans événement.
    if let Some(restored) = &request.restored {
        let saved: Vec<SavedBody> = restored
            .bodies
            .iter()
            .map(|body| {
                let rotation = Quat::from_array(body.rotation);
                SavedBody {
                    position: DVec3::from_array(body.position),
                    // Unitaire au flottant près, elle est gardée au bit près.
                    rotation: if rotation.is_normalized() {
                        rotation
                    } else {
                        rotation.normalize()
                    },
                    linear: Vec3::from_array(body.lin_vel),
                    angular: Vec3::from_array(body.ang_vel),
                }
            })
            .collect();
        let joints: Vec<SavedJoint> = restored
            .joints
            .iter()
            .map(|joint| SavedJoint {
                broken: joint.flags & restored_joint_flags::BROKEN != 0,
                misalignment: joint.misalignment,
            })
            .collect();
        physics.restore_assembly(header.dimension, header.handle, &created, &saved, &joints);
    }
    // 22.4, ADR-153 D4 : une assembly chargée naît endormie, ses vitesses gelées.
    if header.flags & create_assembly_flags::ASLEEP != 0 {
        physics.suspend_assembly(header.dimension, created[0]);
    }
    // R-1430 : le serveur ne joue l'animation que si un collider en dépend.
    if let Some(animation) = animation {
        session.adopt_animation(header.handle, animation);
    }
}

/// Durée écoulée depuis `start`, en nanosecondes, saturée.
fn elapsed_ns(start: std::time::Instant) -> u64 {
    u64::try_from(start.elapsed().as_nanos()).unwrap_or(u64::MAX)
}

/// Récolte le résultat d'un tick (IF-03) : avance la simulation, dépose les
/// `BodyState` puis leurs `BodyBounds` dans `SimOut` (schéma 1, ADR-120) et les
/// `PhysicsEvent` dans `Events`, détruit les mondes devenus sans objet (R-610) et
/// remplit `out`.
///
/// États et emprises forment **un seul lot** (R-250) : `BodyBounds[i]`, à
/// l'offset `80 × state_count + 24 × i` de la charge, est l'emprise du corps de
/// `BodyState[i]`.
///
/// # Safety
/// `out` doit être non nul et accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_sim_collect(
    ctx: u64,
    _deadline_ns: u64,
    out: *mut AxionCollectResult,
) -> i32 {
    shielded(Some(ctx), || {
        if out.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        let result = match context::with(ctx, false, |session| {
            // Fin de l'étape 1 (C-40) : ce qui prépare le pas avec les entrées du tick.
            let commands = std::time::Instant::now();
            // Les proxies d'entités déclarés ce tick prennent corps avant le pas (ADR-123 §5).
            session.physics().sync_entity_proxies();
            // R-612/R-613 autour des joueurs du tick, avant le pas : un corps hors du rayon de
            // tout joueur ne bouge pas ce tick (ADR-123 §3). Les comptes vont aux métriques.
            let activity = session.physics().manage_activity();
            session.record_activity(activity);
            session.record_stage(Stage::Commands, elapsed_ns(commands));
            // Étape 2 (R-660) : l'animation qui repose les colliders qui en dépendent (R-1430).
            let animation = std::time::Instant::now();
            session.sample_animations();
            session.record_stage(Stage::Animation, elapsed_ns(animation));
            // Le pas, mesuré ; FM-21 décide sur sa durée (§25.5).
            session.step_simulation(SERVER_TICK_DT);
            // Étape 15 (C-40) : la récolte des états et des événements, écrits pour Java.
            let serialization = std::time::Instant::now();
            let reports = session.physics().collect_reports();
            let states = &reports.states;
            // Appariement garanti par construction (un même parcours) ; Java
            // lit les emprises au rang des états.
            debug_assert_eq!(states.len(), reports.bounds.len());
            let events = session.physics().drain_events();
            let mut serialization_ns = elapsed_ns(serialization);
            // R-610 : la récolte faite, les mondes devenus sans objet sont détruits — hors de
            // l'étape 15, qui reprend ensuite pour l'écriture.
            session.physics().release_idle_dimensions();

            let writing = std::time::Instant::now();
            let mut sim_out_bytes = Vec::with_capacity(
                states.len() * (BodyState::BYTES + BodyBounds::BYTES)
                    + JointState::SECTION_HEADER_BYTES
                    + reports.joints.len() * JointState::BYTES,
            );
            for state in states {
                state.write_le(&mut sim_out_bytes);
            }
            for bounds in &reports.bounds {
                bounds.write_le(&mut sim_out_bytes);
            }
            // ADR-151 D7 : les états des joints suivent, précédés de leur nombre — l'en-tête du
            // tampon, que l'acquisition par Java réécrit, ne le porte pas.
            JointState::write_section(&reports.joints, &mut sim_out_bytes);
            let mut events_bytes = Vec::new();
            for event in &events {
                event.write_le(&mut events_bytes);
            }
            session
                .buffers()
                .write_payload(BufferKind::SimOut, &sim_out_bytes);
            session
                .buffers()
                .write_payload(BufferKind::Events, &events_bytes);
            serialization_ns = serialization_ns.saturating_add(elapsed_ns(writing));
            session.finish_sim_stages(serialization_ns);
            session.close_sim_cycle();

            let degraded = session.physics().degradation_level() != DegradationLevel::Normal;
            AxionCollectResult {
                state_count: u32::try_from(states.len()).unwrap_or(u32::MAX),
                event_count: u32::try_from(events.len()).unwrap_or(u32::MAX),
                flags: if degraded { AXION_SIM_DEGRADED } else { 0 },
                ..AxionCollectResult::default()
            }
        }) {
            Ok(result) => result,
            Err(code) => return code,
        };
        // SAFETY: `out` non nul (vérifié), accessible en écriture par contrat.
        unsafe { out.write(result) };
        AXION_OK
    })
}

/// Annule le cycle de simulation courant (IF-03) : les entrées soumises sont
/// abandonnées, rien n'est avancé.
///
/// # Safety
/// Aucun pointeur n'est déréférencé ; `unsafe` par symétrie avec le reste de
/// l'ABI.
#[no_mangle]
pub unsafe extern "C" fn axion_sim_cancel(ctx: u64) -> i32 {
    shielded(Some(ctx), || {
        match context::with(ctx, false, |session| session.close_sim_cycle()) {
            Ok(()) => AXION_OK,
            Err(code) => code,
        }
    })
}

/// Plus grand indice `<= cap` qui tombe sur une frontière de caractère UTF-8.
fn utf8_boundary(text: &str, cap: usize) -> usize {
    if cap >= text.len() {
        return text.len();
    }
    let mut end = cap;
    while end > 0 && !text.is_char_boundary(end) {
        end -= 1;
    }
    end
}

/// Décode et valide la configuration reçue de Java.
///
/// Le CBOR vient de l'extérieur : rien n'y est cru sur parole (interdiction
/// 3.13). La map doit ne contenir que des chemins déclarés, chacun avec une
/// valeur du bon type et dans son domaine — les mêmes règles que celles
/// appliquées côté Java, vérifiées une seconde fois ici parce que la frontière
/// ne fait pas confiance à son appelant.
fn apply_config(cbor: &[u8]) -> Result<AppliedConfig, String> {
    let mut applied = AppliedConfig::from_defaults();
    if cbor.is_empty() {
        return Ok(applied);
    }

    let value: ciborium::Value = ciborium::from_reader(cbor)
        .map_err(|error| format!("configuration CBOR illisible : {error}"))?;

    let entries = value
        .as_map()
        .ok_or_else(|| "configuration CBOR : une map est attendue".to_owned())?;

    for (key, raw) in entries {
        let path = key
            .as_text()
            .ok_or_else(|| "configuration CBOR : clé non textuelle".to_owned())?;

        let parsed = to_parsed(raw)
            .ok_or_else(|| format!("configuration CBOR : valeur non supportée pour {path}"))?;

        let option = ConfigScope::ALL
            .into_iter()
            .find_map(|scope| config::find(scope, path))
            .ok_or_else(|| format!("option de configuration inconnue : {path}"))?;

        option
            .validate_parsed(&parsed)
            .map_err(|error| error.to_string())?;
        applied.accepted += 1;
        applied.accept(path, &parsed);
    }
    Ok(applied)
}

/// Ce que la configuration reçue dit au runtime natif.
///
/// Toutes les options sont validées ; seules celles-ci sont **retenues**, parce
/// que la frontière s'en sert tout de suite. Le reste appartient aux composants
/// qui les liront quand ils existeront.
#[derive(Debug, Clone, Copy)]
struct AppliedConfig {
    /// Nombre d'options validées. Une configuration muette n'en compte aucune :
    /// les défauts s'appliquent sans avoir été transmis.
    accepted: usize,
    max_workers: u32,
    cpu_share: CpuShare,
    budgets: JobBudgets,
    /// `assets.max_compiled_bytes` : plafond d'un conteneur A3D chargé (R-901).
    max_compiled_bytes: u64,
    /// `assets.max_source_bytes` : plafond d'une source à compiler (R-533).
    max_source_bytes: u64,
    /// `budgets.native_mem_bytes` : plafond de l'arène `PERSISTENT` (R-480).
    native_mem_bytes: u64,
    /// `render.max_skinned_instances` : retenus skinnés par frame (R-791).
    max_skinned_instances: u32,
    /// Options du monde physique (ADR-123 §1), réunies en [`SimSettings`] à l'ouverture.
    physics: PhysicsOptions,
}

/// Options du monde physique telles que reçues, avant leur réunion en [`SimSettings`] : le
/// pas fixe et les sous-pas ne se valident qu'ensemble (R-990).
#[derive(Debug, Clone, Copy)]
struct PhysicsOptions {
    fixed_dt: f64,
    max_substeps: u32,
    simulation_radius: f32,
    gravity: f32,
    velocity_iterations: u32,
    position_iterations: u32,
    contact_event_threshold: f32,
    max_events_per_tick: u32,
    max_active_bodies: u32,
}

/// Chemins des options du monde physique retenues par la frontière (ADR-123 §1).
const PHYSICS_PATHS: [&str; 9] = [
    "sim.fixed_dt",
    "sim.max_substeps",
    "sim.simulation_radius",
    "physics.gravity",
    "physics.velocity_iterations",
    "physics.position_iterations",
    "physics.contact_event_threshold",
    "physics.max_events_per_tick",
    "budgets.max_active_bodies",
];

/// Chemin de l'option qui plafonne un conteneur A3D chargé (ADR-119).
const MAX_COMPILED_BYTES_PATH: &str = "assets.max_compiled_bytes";

/// Chemin de l'option qui plafonne une source à compiler (R-533, R-830).
const MAX_SOURCE_BYTES_PATH: &str = "assets.max_source_bytes";

/// Chemin de l'option qui plafonne la mémoire native (ADR-119).
const NATIVE_MEM_BYTES_PATH: &str = "budgets.native_mem_bytes";

/// Chemin de l'option qui plafonne les retenus skinnés d'une frame (R-791).
const MAX_SKINNED_INSTANCES_PATH: &str = "render.max_skinned_instances";

impl AppliedConfig {
    /// Part des valeurs par défaut du registre.
    ///
    /// Elles ne sont pas recopiées ici : elles sont **lues** dans `ax-model`,
    /// source unique (R-430). Une configuration muette sur une option doit
    /// produire exactement ce que produirait le fichier de référence.
    fn from_defaults() -> Self {
        let mut applied = Self {
            accepted: 0,
            max_workers: 0,
            cpu_share: CpuShare::Auto,
            budgets: JobBudgets::new(),
            max_compiled_bytes: 0,
            max_source_bytes: 0,
            native_mem_bytes: 0,
            max_skinned_instances: 0,
            // Écrasés ci-dessous par les défauts du registre, source unique (R-430).
            physics: PhysicsOptions {
                fixed_dt: 0.0,
                max_substeps: 0,
                simulation_radius: 0.0,
                gravity: 0.0,
                velocity_iterations: 0,
                position_iterations: 0,
                contact_event_threshold: 0.0,
                max_events_per_tick: 0,
                max_active_bodies: 0,
            },
        };
        for path in Self::interesting_paths() {
            if let Some(option) = ConfigScope::ALL
                .into_iter()
                .find_map(|scope| config::find(scope, path))
            {
                applied.accept(path, &default_as_parsed(&option.default));
            }
        }
        applied
    }

    /// Chemins que la frontière retient.
    fn interesting_paths() -> Vec<&'static str> {
        let mut paths = vec![
            config::MAX_WORKERS_PATH,
            config::CPU_SHARE_PATH,
            MAX_COMPILED_BYTES_PATH,
            MAX_SOURCE_BYTES_PATH,
            NATIVE_MEM_BYTES_PATH,
            MAX_SKINNED_INSTANCES_PATH,
        ];
        paths.extend(PHYSICS_PATHS);
        paths.extend(
            Budget::ALL
                .iter()
                .filter(|budget| budget.is_duration())
                .map(|budget| budget.config_path()),
        );
        paths
    }

    /// Réunit les options du monde physique en réglages du pilote (ADR-123 §1).
    ///
    /// Un pas fixe que la validation a laissé passer mais qui ne désigne aucun pas de R-990
    /// ne peut venir que d'une divergence du registre : les défauts de la fiche prennent
    /// alors le relais plutôt que d'ouvrir une session sans monde.
    fn sim_settings(&self) -> SimSettings {
        let options = self.physics;
        let mut physics = PhysicsConfig::from_configured(options.fixed_dt, options.max_substeps)
            .unwrap_or_default();
        physics.gravity = Vec3::new(0.0, options.gravity, 0.0);
        physics.velocity_iterations = options.velocity_iterations;
        physics.position_iterations = options.position_iterations;
        SimSettings {
            physics,
            contact_event_threshold: options.contact_event_threshold,
            max_events_per_tick: options.max_events_per_tick as usize,
            simulation_radius: options.simulation_radius,
            max_active_bodies: options.max_active_bodies as usize,
            sim_budget_ns: self.budgets.get(Budget::SimNsPerTick),
        }
    }

    /// Retient une option du monde physique ; rend vrai si `path` en est une.
    fn accept_physics(&mut self, path: &str, parsed: &ParsedValue) -> bool {
        let options = &mut self.physics;
        match (path, parsed) {
            ("sim.fixed_dt", ParsedValue::Float(value)) => options.fixed_dt = *value,
            ("sim.simulation_radius", ParsedValue::Float(value)) => {
                options.simulation_radius = *value as f32;
            }
            ("physics.gravity", ParsedValue::Float(value)) => options.gravity = *value as f32,
            ("physics.contact_event_threshold", ParsedValue::Float(value)) => {
                options.contact_event_threshold = *value as f32;
            }
            ("sim.max_substeps", ParsedValue::Int(value)) => {
                options.max_substeps = u32::try_from(*value).unwrap_or(0);
            }
            ("physics.velocity_iterations", ParsedValue::Int(value)) => {
                options.velocity_iterations = u32::try_from(*value).unwrap_or(0);
            }
            ("physics.position_iterations", ParsedValue::Int(value)) => {
                options.position_iterations = u32::try_from(*value).unwrap_or(0);
            }
            ("physics.max_events_per_tick", ParsedValue::Int(value)) => {
                options.max_events_per_tick = u32::try_from(*value).unwrap_or(0);
            }
            ("budgets.max_active_bodies", ParsedValue::Int(value)) => {
                options.max_active_bodies = u32::try_from(*value).unwrap_or(0);
            }
            _ => return PHYSICS_PATHS.contains(&path),
        }
        true
    }

    /// Retient une valeur, si elle en fait partie.
    fn accept(&mut self, path: &str, parsed: &ParsedValue) {
        if self.accept_physics(path, parsed) {
            return;
        }
        if path == MAX_COMPILED_BYTES_PATH
            || path == MAX_SOURCE_BYTES_PATH
            || path == NATIVE_MEM_BYTES_PATH
        {
            if let ParsedValue::Int(value) = parsed {
                let value = u64::try_from(*value).unwrap_or(0);
                match path {
                    MAX_COMPILED_BYTES_PATH => self.max_compiled_bytes = value,
                    MAX_SOURCE_BYTES_PATH => self.max_source_bytes = value,
                    _ => self.native_mem_bytes = value,
                }
            }
            return;
        }
        if path == MAX_SKINNED_INSTANCES_PATH {
            if let ParsedValue::Int(value) = parsed {
                self.max_skinned_instances = u32::try_from(*value).unwrap_or(0);
            }
            return;
        }
        if path == config::MAX_WORKERS_PATH {
            if let ParsedValue::Int(value) = parsed {
                self.max_workers = u32::try_from(*value).unwrap_or(0);
            }
            return;
        }
        if path == config::CPU_SHARE_PATH {
            match parsed {
                ParsedValue::Str(value) => {
                    if let Some(share) = CpuShare::parse(value) {
                        self.cpu_share = share;
                    }
                }
                // La forme entière de R-2060 : un nombre de threads imposé.
                ParsedValue::Int(value) => {
                    self.cpu_share = CpuShare::Fixed(u32::try_from(*value).unwrap_or(0));
                }
                _ => {}
            }
            return;
        }
        if let Some(budget) = Budget::ALL
            .iter()
            .find(|budget| budget.is_duration() && budget.config_path() == path)
        {
            if let ParsedValue::Int(value) = parsed {
                self.budgets
                    .set(*budget, u64::try_from(*value).unwrap_or(0));
            }
        }
    }
}

/// Rend une valeur par défaut sous la forme que la validation manipule.
fn default_as_parsed(value: &config::ConfigValue) -> ParsedValue {
    match value {
        config::ConfigValue::Bool(inner) => ParsedValue::Bool(*inner),
        config::ConfigValue::Int(inner) => ParsedValue::Int(*inner),
        config::ConfigValue::Float(inner) => ParsedValue::Float(*inner),
        config::ConfigValue::Str(inner) => ParsedValue::Str((*inner).to_owned()),
    }
}

/// Convertit une valeur CBOR en valeur de configuration.
///
/// Seuls les quatre types du modèle sont acceptés ; tout le reste — tableau,
/// map imbriquée, octets, `null` — est refusé plutôt que réinterprété.
fn to_parsed(value: &ciborium::Value) -> Option<ParsedValue> {
    Some(match value {
        ciborium::Value::Bool(v) => ParsedValue::Bool(*v),
        ciborium::Value::Integer(v) => ParsedValue::Int(i128::from(*v).try_into().ok()?),
        ciborium::Value::Float(v) => ParsedValue::Float(*v),
        ciborium::Value::Text(v) => ParsedValue::Str(v.clone()),
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// La version d'ABI est positive : une valeur négative serait indiscernable
    /// d'un code d'erreur.
    #[test]
    fn version_d_abi_positive() {
        assert!(axion_abi_version() > 0);
        assert_eq!(axion_abi_version(), AXION_ABI_VERSION as i32);
    }

    /// ADR-119 : le bit *i* du masque désigne le *i*-ème tag de la PARTIE 7.
    /// Java envoie ces valeurs ; les dériver ailleurs les ferait diverger.
    #[test]
    fn les_bits_de_section_sont_ceux_de_la_partie_7() {
        use crate::asset_store::supported_sections;
        use ax_asset::a3d::{SectionMask, SectionTag};
        for (tag, bit) in [
            (SectionTag::NODE, AXION_SECTION_NODE),
            (SectionTag::GEOM, AXION_SECTION_GEOM),
            (SectionTag::MATL, AXION_SECTION_MATL),
            (SectionTag::TEXR, AXION_SECTION_TEXR),
            (SectionTag::PHYS, AXION_SECTION_PHYS),
            (SectionTag::SKEL, AXION_SECTION_SKEL),
            (SectionTag::ANIM, AXION_SECTION_ANIM),
        ] {
            assert_eq!(SectionMask::of(&[tag]).bits(), bit, "{tag}");
        }
        assert_eq!(
            supported_sections().bits(),
            AXION_SECTION_NODE
                | AXION_SECTION_GEOM
                | AXION_SECTION_MATL
                | AXION_SECTION_TEXR
                | AXION_SECTION_PHYS
                | AXION_SECTION_SKEL
                | AXION_SECTION_ANIM
        );
    }

    /// Un chemin renommé dans le registre ferait retomber le plafond à zéro sans
    /// rien dire — et tout chargement serait refusé. Le contrôle doit échouer
    /// s'il ne **trouve** plus l'option, pas seulement si elle diffère.
    #[test]
    fn les_plafonds_d_asset_sont_lus_dans_le_registre() {
        for path in [MAX_COMPILED_BYTES_PATH, NATIVE_MEM_BYTES_PATH] {
            assert!(
                ConfigScope::ALL
                    .into_iter()
                    .any(|scope| config::find(scope, path).is_some()),
                "{path} absent du registre"
            );
        }
        let applied = AppliedConfig::from_defaults();
        assert!(applied.max_compiled_bytes > 0);
        assert!(applied.native_mem_bytes > 0);
    }

    /// La troncature tombe toujours sur une frontière de caractère : un message
    /// coupé au milieu d'un « é » ne serait plus de l'UTF-8 valide.
    #[test]
    fn troncature_sur_frontiere_utf8() {
        let text = "déformation";
        // « é » occupe deux octets : couper à 2 doit reculer à 1.
        assert_eq!(utf8_boundary(text, 2), 1);
        assert_eq!(utf8_boundary(text, 3), 3);
        assert_eq!(utf8_boundary(text, 0), 0);
        assert_eq!(utf8_boundary(text, 999), text.len());

        for cap in 0..=text.len() + 4 {
            let end = utf8_boundary(text, cap);
            assert!(end <= cap.min(text.len()));
            assert!(
                std::str::from_utf8(&text.as_bytes()[..end]).is_ok(),
                "troncature invalide à {cap}"
            );
        }
    }

    /// Une configuration vide est acceptée : tous les défauts s'appliquent.
    #[test]
    fn configuration_vide_acceptee() {
        assert_eq!(apply_config(&[]).unwrap().accepted, 0);
    }

    fn encode(pairs: &[(&str, ciborium::Value)]) -> Vec<u8> {
        let map = ciborium::Value::Map(
            pairs
                .iter()
                .map(|(k, v)| (ciborium::Value::Text((*k).to_owned()), v.clone()))
                .collect(),
        );
        let mut out = Vec::new();
        ciborium::into_writer(&map, &mut out).unwrap();
        out
    }

    /// Une configuration valide est acceptée, toutes portées confondues.
    #[test]
    fn configuration_valide_acceptee() {
        let cbor = encode(&[
            ("general.enabled", ciborium::Value::Bool(false)),
            ("sim.max_substeps", ciborium::Value::Integer(6.into())),
            ("physics.gravity", ciborium::Value::Float(-12.0)),
            (
                "deformation.quality",
                ciborium::Value::Text("high".to_owned()),
            ),
            // Portée client, dans la même map.
            (
                "render.backend",
                ciborium::Value::Text("vanilla".to_owned()),
            ),
        ]);

        assert_eq!(apply_config(&cbor).unwrap().accepted, 5);
    }

    /// ADR-123 §1 : les options du monde physique reçues par IF-01 deviennent les réglages
    /// du pilote — et une configuration muette rend exactement les défauts de l'annexe.
    #[test]
    fn t301_les_options_physiques_deviennent_les_reglages_du_pilote() {
        let cbor = encode(&[
            ("sim.fixed_dt", ciborium::Value::Float(0.0333333)),
            ("sim.max_substeps", ciborium::Value::Integer(6.into())),
            ("sim.simulation_radius", ciborium::Value::Float(64.0)),
            ("physics.gravity", ciborium::Value::Float(-12.0)),
            (
                "physics.velocity_iterations",
                ciborium::Value::Integer(8.into()),
            ),
            (
                "physics.position_iterations",
                ciborium::Value::Integer(2.into()),
            ),
            (
                "physics.contact_event_threshold",
                ciborium::Value::Float(2.5),
            ),
            (
                "physics.max_events_per_tick",
                ciborium::Value::Integer(1024.into()),
            ),
            (
                "budgets.max_active_bodies",
                ciborium::Value::Integer(512.into()),
            ),
            (
                "budgets.sim_ns_per_tick",
                ciborium::Value::Integer(5_000_000.into()),
            ),
        ]);
        let settings = apply_config(&cbor).unwrap().sim_settings();

        assert_eq!(
            settings.physics.fixed_dt(),
            1.0 / 30.0,
            "0.0333333 désigne 1/30"
        );
        assert_eq!(settings.physics.max_substeps(), 6);
        assert_eq!(settings.physics.gravity, Vec3::new(0.0, -12.0, 0.0));
        assert_eq!(settings.physics.velocity_iterations, 8);
        assert_eq!(settings.physics.position_iterations, 2);
        assert_eq!(settings.contact_event_threshold, 2.5);
        assert_eq!(settings.max_events_per_tick, 1024);
        assert_eq!(settings.simulation_radius, 64.0);
        assert_eq!(settings.max_active_bodies, 512);
        assert_eq!(settings.sim_budget_ns, 5_000_000);

        // Muette : les défauts du registre, qui sont ceux de l'annexe.
        assert_eq!(
            apply_config(&[]).unwrap().sim_settings(),
            SimSettings::default()
        );
    }

    /// Rien de ce qui vient de Java n'est cru sur parole : chemin inconnu,
    /// hors-plage, mauvais type et valeur non représentable sont tous refusés.
    #[test]
    fn configuration_invalide_refusee() {
        let cas: Vec<(&str, Vec<u8>)> = vec![
            (
                "chemin inconnu",
                encode(&[("sim.pas_une_option", ciborium::Value::Integer(1.into()))]),
            ),
            (
                "hors plage",
                encode(&[("sim.max_substeps", ciborium::Value::Integer(99.into()))]),
            ),
            (
                "mauvais type",
                encode(&[("sim.max_substeps", ciborium::Value::Bool(true))]),
            ),
            (
                "type CBOR non supporté",
                encode(&[("sim.max_substeps", ciborium::Value::Null)]),
            ),
            (
                "entier hors des bornes d'un i64",
                encode(&[(
                    "sim.max_substeps",
                    ciborium::Value::Integer(
                        i128::from(i64::MAX).saturating_add(1).try_into().unwrap(),
                    ),
                )]),
            ),
        ];

        for (nom, cbor) in cas {
            assert!(apply_config(&cbor).is_err(), "{nom} : accepté à tort");
        }

        // Une map dont les clés ne sont pas textuelles, et un CBOR qui n'est
        // pas une map du tout.
        let mut mauvaise_cle = Vec::new();
        ciborium::into_writer(
            &ciborium::Value::Map(vec![(
                ciborium::Value::Integer(1.into()),
                ciborium::Value::Bool(true),
            )]),
            &mut mauvaise_cle,
        )
        .unwrap();
        assert!(apply_config(&mauvaise_cle).is_err());

        let mut pas_une_map = Vec::new();
        ciborium::into_writer(&ciborium::Value::Bool(true), &mut pas_une_map).unwrap();
        assert!(apply_config(&pas_une_map).is_err());

        // Des octets qui ne sont pas du CBOR valide.
        assert!(apply_config(&[0xFF, 0xFF, 0xFF]).is_err());
    }

    /// T-013 — le bouclier transforme une panic en code d'erreur, sans la
    /// laisser traverser (INV-05).
    #[test]
    fn une_panic_ne_traverse_pas_la_frontiere() {
        let code = shielded(None, || panic!("échec simulé"));
        assert_eq!(code, AXION_E_PANIC);

        // Le cas nominal passe sans surcoût observable.
        assert_eq!(shielded(None, || AXION_OK), AXION_OK);
    }

    /// Un pointeur de sortie nul est refusé, jamais déréférencé.
    #[test]
    fn pointeurs_nuls_refuses() {
        // SAFETY: précisément ce que la fonction doit détecter sans lire.
        unsafe {
            assert_eq!(
                axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, std::ptr::null_mut()),
                AXION_E_INVALID_BUFFER
            );
            let mut ctx = 0u64;
            assert_eq!(
                axion_init(std::ptr::null(), 4, AXION_SIDE_SERVER, &raw mut ctx),
                AXION_E_INVALID_BUFFER
            );
            assert_eq!(
                axion_last_error(0, std::ptr::null_mut(), 8, std::ptr::null_mut()),
                AXION_E_INVALID_BUFFER
            );
        }
    }

    /// ADR-135 : l'en-tête d'une chaîne de texte se lit sous toutes ses
    /// longueurs, telles qu'un encodeur conforme les écrit — ici `ciborium`,
    /// témoin indépendant du décodeur.
    #[test]
    fn les_options_se_lisent_sous_toutes_les_longueurs_d_en_tete() {
        for len in [0usize, 1, 23, 24, 255, 256, 65_535, 65_536] {
            let text = "x".repeat(len);
            let mut cbor = Vec::new();
            ciborium::into_writer(&ciborium::Value::Text(text.clone()), &mut cbor).unwrap();
            assert_eq!(
                options_text_of(&cbor),
                Some(text.as_bytes()),
                "longueur {len}"
            );
        }
    }

    /// Ce qui n'est pas exactement une chaîne de texte est refusé ; un texte
    /// qui n'est pas de l'UTF-8 passe, pour être refusé par le lecteur d'options
    /// comme la CLI le refuse (`E-7001`).
    #[test]
    fn un_parametre_d_options_qui_n_est_pas_une_chaine_est_refuse() {
        for (cbor, cas) in [
            (&[][..], "vide"),
            (&[0x01][..], "entier"),
            (&[0x42, b'{', b'}'][..], "chaîne d'octets"),
            (&[0x62, b'{', b'}', 0x00][..], "octet après la chaîne"),
            (&[0x63, b'{', b'}'][..], "chaîne tronquée"),
            (&[0x78][..], "longueur absente"),
            (&[0x79, 0x00][..], "longueur tronquée"),
            (&[0x7c, 0x00][..], "longueur réservée"),
            (&[0x7f, 0x62, b'{', b'}', 0xff][..], "longueur indéfinie"),
        ] {
            assert_eq!(options_text_of(cbor), None, "{cas}");
        }
        assert_eq!(
            options_text_of(&[0x62, 0xff, 0xfe]),
            Some(&[0xff, 0xfe][..])
        );
    }
}
