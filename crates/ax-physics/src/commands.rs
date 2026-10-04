//! Lecture du flux de commandes `SimIn` (IF-03, ADR-114) et application au
//! [`SimDriver`].
//!
//! `ax-physics` interdit `unsafe` : le flux est lu **champ à champ** en
//! little-endian, jamais par transtypage en place. C'est la couche FFI qui, elle,
//! lit les structures DM en place ; ici on décode, on valide, on applique.

use crate::body::ContactMaterial;
use crate::forces::FluidEnvironment;
use crate::proxies::{EntityProxy, ProxyShape};
use crate::sim::SimDriver;
use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::commands::{
    dimension_env_flags, entity_proxy_shape, opcode, ApplyForce, CommandStreamHeader,
    EntityProxyDesc, RemoveAssembly, RemoveWorldTile, SetDimensionEnv, SetEntityProxies,
    SetKinematic, SetObservers, SetWorldCollision, SetWorldFluid, SetWorldHeightfield,
    SimCommandHeader,
};
use ax_model::dm::handle::Handle;

/// Plafond de boîtes d'une tuile (collision ou fluide), donnée hostile bornée avant
/// allocation (R-901, ADR-117). C'est le seuil de R-641 : au-delà, la collision passe en
/// champ de hauteurs, jamais en boîtes.
const MAX_WORLD_TILE_BOXES: u32 = 4096;
/// Plafond des dimensions d'un champ de hauteurs de tuile (R-901, ADR-117) : au plus
/// `MAX_WORLD_HEIGHTFIELD_DIM²` hauteurs, borne large pour une section 16³.
const MAX_WORLD_HEIGHTFIELD_DIM: u32 = 64;
/// Plafond d'observateurs d'une dimension pour un tick (ADR-123 §2) : une limite de
/// validation, donnée hostile bornée avant allocation (R-901), pas une disposition.
const MAX_OBSERVERS: u32 = 1024;
/// Plafond de proxies d'entités d'une dimension pour un tick (ADR-123 §5) : limite de
/// validation, donnée hostile bornée avant allocation (R-901).
const MAX_ENTITY_PROXIES: u32 = 4096;

/// Bilan de l'application d'un flux de commandes.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct CommandOutcome {
    /// Commandes appliquées.
    pub applied: u32,
    /// Opcodes connus mais pas encore pris en charge (CREATE_ASSEMBLY — C-32 ;
    /// APPLY_FORCE — à intégrer à la boucle de forces).
    pub deferred: u32,
    /// Opcodes inconnus, ignorés (compatibilité avant).
    pub ignored: u32,
}

/// Ce qui empêche de lire le flux (donnée externe fautive, interdiction 3.13).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandError {
    /// Le flux se termine au milieu d'un en-tête ou d'un payload.
    Truncated,
    /// Version de protocole inconnue.
    UnsupportedSchema,
    /// `payload_len` ne correspond pas à la taille attendue de l'opcode connu.
    BadPayloadLength,
    /// Un champ porte une valeur hors de son domaine — une forme de proxy inconnue.
    InvalidField,
}

/// Lit `command_count` commandes du flux `bytes` et les applique au pilote.
///
/// # Errors
/// [`CommandError`] si le flux est tronqué, d'une version inconnue, ou porte un
/// payload de mauvaise taille pour un opcode connu.
pub fn apply_command_stream(
    driver: &mut SimDriver,
    bytes: &[u8],
    command_count: u32,
) -> Result<CommandOutcome, CommandError> {
    let mut outcome = CommandOutcome::default();
    for_each_command(bytes, command_count, |op, payload| {
        apply_one(driver, op, payload, &mut outcome)
    })?;
    Ok(outcome)
}

/// Extrait les payloads des commandes `CREATE_ASSEMBLY` d'un flux, pour la
/// frontière (ax-ffi) — seule à savoir décoder les colliders (dépendance
/// ax-asset). Le reste du flux est ensuite appliqué par [`apply_command_stream`],
/// qui reporte `CREATE_ASSEMBLY`.
///
/// Les payloads sont **copiés** (possédés) : une signature de fermeture à
/// lifetime universel ne peut pas rendre de tranches empruntées au flux.
///
/// # Errors
/// [`CommandError::Truncated`] / [`CommandError::UnsupportedSchema`] comme
/// [`apply_command_stream`].
pub fn create_assembly_payloads(
    bytes: &[u8],
    command_count: u32,
) -> Result<Vec<Vec<u8>>, CommandError> {
    let mut payloads = Vec::new();
    for_each_command(bytes, command_count, |op, payload| {
        if op == opcode::CREATE_ASSEMBLY {
            payloads.push(payload.to_vec());
        }
        Ok(())
    })?;
    Ok(payloads)
}

/// Itère les `command_count` commandes du flux et appelle `visit(op, payload)`
/// sur chacune. Valide l'en-tête et les bornes (donnée externe, interdiction
/// 3.13).
fn for_each_command<F>(bytes: &[u8], command_count: u32, mut visit: F) -> Result<(), CommandError>
where
    F: FnMut(u32, &[u8]) -> Result<(), CommandError>,
{
    if bytes.len() < CommandStreamHeader::BYTES {
        return Err(CommandError::Truncated);
    }
    if read_u32(bytes, 0) != CommandStreamHeader::CURRENT_SCHEMA {
        return Err(CommandError::UnsupportedSchema);
    }

    let mut offset = CommandStreamHeader::BYTES;
    for _ in 0..command_count {
        if offset + SimCommandHeader::BYTES > bytes.len() {
            return Err(CommandError::Truncated);
        }
        let op = read_u32(bytes, offset);
        let payload_len = read_u32(bytes, offset + 4) as usize;
        let payload_offset = offset + SimCommandHeader::BYTES;
        if payload_offset + payload_len > bytes.len() {
            return Err(CommandError::Truncated);
        }
        visit(op, &bytes[payload_offset..payload_offset + payload_len])?;
        offset = payload_offset + align_up(payload_len, 8);
    }
    Ok(())
}

/// Applique une commande décodée, ou compte son report / son rejet.
fn apply_one(
    driver: &mut SimDriver,
    op: u32,
    payload: &[u8],
    outcome: &mut CommandOutcome,
) -> Result<(), CommandError> {
    match op {
        opcode::REMOVE_ASSEMBLY => {
            expect_len(payload, RemoveAssembly::BYTES)?;
            driver.apply_remove_assembly(read_handle(payload, 0));
            outcome.applied += 1;
        }
        opcode::SET_KINEMATIC => {
            expect_len(payload, SetKinematic::BYTES)?;
            let handle = read_handle(payload, 0);
            // WorldTransform : position [f64;3] à 8, rotation [f32;4] à 32.
            let position = DVec3::new(
                read_f64(payload, 8),
                read_f64(payload, 16),
                read_f64(payload, 24),
            );
            let rotation = Quat::from_xyzw(
                read_f32(payload, 32),
                read_f32(payload, 36),
                read_f32(payload, 40),
                read_f32(payload, 44),
            );
            driver.apply_set_kinematic(handle, position, rotation);
            outcome.applied += 1;
        }
        opcode::APPLY_IMPULSE => {
            expect_len(payload, ApplyForce::BYTES)?;
            let handle = read_handle(payload, 0);
            let vector = read_vec3(payload, 8);
            let point = read_vec3(payload, 20);
            let at_point = read_u32(payload, 32) != 0;
            driver.apply_impulse(handle, vector, point, at_point);
            outcome.applied += 1;
        }
        opcode::SET_DIMENSION_ENV => {
            expect_len(payload, SetDimensionEnv::BYTES)?;
            let dimension = read_u64(payload, 0);
            let gravity = read_vec3(payload, 8);
            let wind = read_vec3(payload, 20);
            let flags = read_u32(payload, 40);
            let fluid =
                (flags & dimension_env_flags::FLUID_PRESENT != 0).then(|| FluidEnvironment {
                    surface_y: read_f32(payload, 32),
                    density: read_f32(payload, 36),
                });
            driver.apply_dimension_env(dimension, gravity, wind, fluid);
            outcome.applied += 1;
        }
        // Tuiles de collision du monde (C-38, ADR-117) : payloads à corps variable,
        // décodés en place ici (aucune dépendance ax-asset, contrairement à
        // CREATE_ASSEMBLY). Les boîtes/hauteurs sont des f32 bruts ; le natif assainit.
        opcode::SET_WORLD_COLLISION => {
            apply_set_world_collision(driver, payload)?;
            outcome.applied += 1;
        }
        opcode::SET_WORLD_HEIGHTFIELD => {
            apply_set_world_heightfield(driver, payload)?;
            outcome.applied += 1;
        }
        opcode::SET_WORLD_FLUID => {
            apply_set_world_fluid(driver, payload)?;
            outcome.applied += 1;
        }
        opcode::REMOVE_WORLD_COLLISION => {
            let (dimension, section) = read_remove_world_tile(payload)?;
            driver.remove_world_tile(dimension, section);
            outcome.applied += 1;
        }
        opcode::REMOVE_WORLD_FLUID => {
            let (dimension, section) = read_remove_world_tile(payload)?;
            driver.remove_world_fluid_tile(dimension, section);
            outcome.applied += 1;
        }
        opcode::SET_OBSERVERS => {
            apply_set_observers(driver, payload)?;
            outcome.applied += 1;
        }
        opcode::SET_ENTITY_PROXIES => {
            apply_set_entity_proxies(driver, payload)?;
            outcome.applied += 1;
        }
        // Connus mais non traités *ici* : CREATE_ASSEMBLY est extrait et appliqué
        // par la frontière (ax-ffi, `create_assembly_payloads`), qui seule décode
        // les colliders ; APPLY_FORCE continu attend la boucle de forces. Les deux
        // sont comptés « reportés » du point de vue d'ax-physics.
        opcode::CREATE_ASSEMBLY | opcode::APPLY_FORCE => outcome.deferred += 1,
        // Inconnu : ignoré, comme les genres d'événements (ADR-113/114).
        _ => outcome.ignored += 1,
    }
    Ok(())
}

/// Décode `SET_WORLD_COLLISION` (ADR-117) et pose la tuile de collision.
fn apply_set_world_collision(driver: &mut SimDriver, payload: &[u8]) -> Result<(), CommandError> {
    if payload.len() < SetWorldCollision::BYTES {
        return Err(CommandError::BadPayloadLength);
    }
    let dimension = read_u64(payload, 0);
    let section = read_section(payload, 8);
    let box_count = read_u32(payload, 20);
    let friction = read_f32(payload, 24);
    let restitution = read_f32(payload, 28);
    let boxes = read_boxes(payload, SetWorldCollision::BYTES, box_count)?;
    driver.set_world_tile(
        dimension,
        section,
        &boxes,
        ContactMaterial::sanitized(friction, restitution),
    );
    Ok(())
}

/// Décode `SET_WORLD_HEIGHTFIELD` (ADR-117) et pose la tuile champ de hauteurs.
fn apply_set_world_heightfield(driver: &mut SimDriver, payload: &[u8]) -> Result<(), CommandError> {
    if payload.len() < SetWorldHeightfield::BYTES {
        return Err(CommandError::BadPayloadLength);
    }
    let dimension = read_u64(payload, 0);
    let section = read_section(payload, 8);
    let rows = read_u32(payload, 20);
    let cols = read_u32(payload, 24);
    let friction = read_f32(payload, 28);
    let restitution = read_f32(payload, 32);
    let scale = [
        read_f32(payload, 36),
        read_f32(payload, 40),
        read_f32(payload, 44),
    ];
    // Bornes avant allocation (R-901) : 2 ≤ rows, cols ≤ plafond.
    if !(2..=MAX_WORLD_HEIGHTFIELD_DIM).contains(&rows)
        || !(2..=MAX_WORLD_HEIGHTFIELD_DIM).contains(&cols)
    {
        return Err(CommandError::BadPayloadLength);
    }
    let count = rows as usize * cols as usize;
    let body = SetWorldHeightfield::BYTES;
    if payload.len() != body + count * 4 {
        return Err(CommandError::BadPayloadLength);
    }
    let mut heights = Vec::with_capacity(count);
    for i in 0..count {
        heights.push(read_f32(payload, body + i * 4));
    }
    driver.set_world_tile_heightfield(
        dimension,
        section,
        rows,
        cols,
        heights,
        scale,
        ContactMaterial::sanitized(friction, restitution),
    );
    Ok(())
}

/// Décode `SET_WORLD_FLUID` (ADR-117) et pose les volumes de fluide.
fn apply_set_world_fluid(driver: &mut SimDriver, payload: &[u8]) -> Result<(), CommandError> {
    if payload.len() < SetWorldFluid::BYTES {
        return Err(CommandError::BadPayloadLength);
    }
    let dimension = read_u64(payload, 0);
    let section = read_section(payload, 8);
    let box_count = read_u32(payload, 20);
    let density = read_f32(payload, 24);
    let boxes = read_boxes(payload, SetWorldFluid::BYTES, box_count)?;
    driver.set_world_fluid_tile(dimension, section, &boxes, density);
    Ok(())
}

/// Décode `SET_OBSERVERS` (ADR-123 §2) : les positions monde des joueurs d'une dimension
/// pour ce tick. Borne le compte avant allocation (R-901) et exige la longueur exacte.
fn apply_set_observers(driver: &mut SimDriver, payload: &[u8]) -> Result<(), CommandError> {
    if payload.len() < SetObservers::BYTES {
        return Err(CommandError::BadPayloadLength);
    }
    let dimension = read_u64(payload, 0);
    let count = read_u32(payload, 8);
    if count > MAX_OBSERVERS {
        return Err(CommandError::BadPayloadLength);
    }
    let count = count as usize;
    if payload.len() != SetObservers::BYTES + count * SetObservers::POSITION_BYTES {
        return Err(CommandError::BadPayloadLength);
    }
    let positions: Vec<DVec3> = (0..count)
        .map(|i| {
            let at = SetObservers::BYTES + i * SetObservers::POSITION_BYTES;
            DVec3::new(
                read_f64(payload, at),
                read_f64(payload, at + 8),
                read_f64(payload, at + 16),
            )
        })
        .collect();
    driver.set_observers(dimension, &positions);
    Ok(())
}

/// Décode `SET_ENTITY_PROXIES` (ADR-123 §5) : les proxies des entités vanilla d'une
/// dimension pour ce tick. Borne le compte avant allocation (R-901), exige la longueur
/// exacte et une forme connue ; un proxy aux valeurs inutilisables passe ici et sera
/// ignoré par le monde.
fn apply_set_entity_proxies(driver: &mut SimDriver, payload: &[u8]) -> Result<(), CommandError> {
    if payload.len() < SetEntityProxies::BYTES {
        return Err(CommandError::BadPayloadLength);
    }
    let dimension = read_u64(payload, 0);
    let count = read_u32(payload, 8);
    if count > MAX_ENTITY_PROXIES {
        return Err(CommandError::BadPayloadLength);
    }
    let count = count as usize;
    if payload.len() != SetEntityProxies::BYTES + count * EntityProxyDesc::BYTES {
        return Err(CommandError::BadPayloadLength);
    }
    let mut proxies = Vec::with_capacity(count);
    for i in 0..count {
        let at = SetEntityProxies::BYTES + i * EntityProxyDesc::BYTES;
        let shape = match payload[at + 52] {
            entity_proxy_shape::BOX => ProxyShape::Box,
            entity_proxy_shape::CAPSULE => ProxyShape::Capsule,
            _ => return Err(CommandError::InvalidField),
        };
        proxies.push(EntityProxy {
            entity: read_u32(payload, at + 48),
            center: DVec3::new(
                read_f64(payload, at),
                read_f64(payload, at + 8),
                read_f64(payload, at + 16),
            ),
            half_extents: [
                read_f32(payload, at + 24),
                read_f32(payload, at + 28),
                read_f32(payload, at + 32),
            ],
            velocity: read_vec3(payload, at + 36),
            shape,
        });
    }
    driver.set_entity_proxies(dimension, proxies);
    Ok(())
}

/// Décode le payload partagé des retraits (`RemoveWorldTile`, ADR-117).
fn read_remove_world_tile(payload: &[u8]) -> Result<(u64, [i32; 3]), CommandError> {
    expect_len(payload, RemoveWorldTile::BYTES)?;
    Ok((read_u64(payload, 0), read_section(payload, 8)))
}

/// Lit `box_count` boîtes `[f32;6]` après l'en-tête, en vérifiant la borne (R-901) et la
/// longueur **exacte** du corps (ADR-117). Un compte nul rend une liste vide — le natif
/// y retire alors la tuile.
fn read_boxes(
    payload: &[u8],
    header: usize,
    box_count: u32,
) -> Result<Vec<[f32; 6]>, CommandError> {
    if box_count > MAX_WORLD_TILE_BOXES {
        return Err(CommandError::BadPayloadLength);
    }
    let count = box_count as usize;
    if payload.len() != header + count * 24 {
        return Err(CommandError::BadPayloadLength);
    }
    let mut boxes = Vec::with_capacity(count);
    for i in 0..count {
        let at = header + i * 24;
        boxes.push([
            read_f32(payload, at),
            read_f32(payload, at + 4),
            read_f32(payload, at + 8),
            read_f32(payload, at + 12),
            read_f32(payload, at + 16),
            read_f32(payload, at + 20),
        ]);
    }
    Ok(boxes)
}

fn expect_len(payload: &[u8], expected: usize) -> Result<(), CommandError> {
    if payload.len() == expected {
        Ok(())
    } else {
        Err(CommandError::BadPayloadLength)
    }
}

fn align_up(value: usize, alignment: usize) -> usize {
    (value + alignment - 1) & !(alignment - 1)
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(
        bytes[offset..offset + 4]
            .try_into()
            .expect("borne vérifiée"),
    )
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("borne vérifiée"),
    )
}

fn read_f32(bytes: &[u8], offset: usize) -> f32 {
    f32::from_le_bytes(
        bytes[offset..offset + 4]
            .try_into()
            .expect("borne vérifiée"),
    )
}

fn read_f64(bytes: &[u8], offset: usize) -> f64 {
    f64::from_le_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("borne vérifiée"),
    )
}

fn read_vec3(bytes: &[u8], offset: usize) -> Vec3 {
    Vec3::new(
        read_f32(bytes, offset),
        read_f32(bytes, offset + 4),
        read_f32(bytes, offset + 8),
    )
}

fn read_handle(bytes: &[u8], offset: usize) -> Handle {
    Handle::new(read_u32(bytes, offset), read_u32(bytes, offset + 4))
}

/// Lit un index de section `[i32; 3]` (petit-boutiste) à `offset`.
fn read_section(bytes: &[u8], offset: usize) -> [i32; 3] {
    [
        read_u32(bytes, offset) as i32,
        read_u32(bytes, offset + 4) as i32,
        read_u32(bytes, offset + 8) as i32,
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::body::{BodyId, BodyKind, Shape};
    use crate::config::PhysicsConfig;
    use crate::sim::SimDriver;
    use ax_math::{FloatingOrigin, Vec3};

    fn config() -> PhysicsConfig {
        PhysicsConfig::new(1.0 / 60.0, 4).unwrap()
    }

    /// Amorce un flux : l'en-tête de version courante.
    fn stream() -> Vec<u8> {
        let mut bytes = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes
    }

    /// Ajoute une commande, payload rembourré à 8 octets.
    fn push(bytes: &mut Vec<u8>, op: u32, payload: &[u8]) {
        bytes.extend_from_slice(&op.to_le_bytes());
        bytes.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        bytes.extend_from_slice(payload);
        while !bytes.len().is_multiple_of(8) {
            bytes.push(0);
        }
    }

    fn f32s(values: &[f32]) -> Vec<u8> {
        values.iter().flat_map(|v| v.to_le_bytes()).collect()
    }

    /// Crée une dimension et y pose un corps dynamique routable ; rend son id.
    fn body_in(driver: &mut SimDriver, dimension: u64, handle: Handle) -> BodyId {
        let body = driver
            .world_or_create(dimension, config(), FloatingOrigin::new(DVec3::ZERO))
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(0.0, 5.0, 0.0),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.5 },
            )
            .unwrap();
        driver.register_body(dimension, handle, body, 0, 0);
        body
    }

    #[test]
    fn set_dimension_env_cree_et_regle() {
        let mut driver = SimDriver::new();
        let mut payload = 0u64.to_le_bytes().to_vec(); // dimension 0
        payload.extend(f32s(&[0.0, -5.0, 0.0])); // gravity
        payload.extend(f32s(&[3.0, 0.0, 0.0])); // wind
        payload.extend(f32s(&[10.0, 2.0])); // fluid_surface, fluid_density
        payload.extend_from_slice(&dimension_env_flags::FLUID_PRESENT.to_le_bytes()); // flags
        payload.resize(SetDimensionEnv::BYTES, 0); // remplissage final
        let mut bytes = stream();
        push(&mut bytes, opcode::SET_DIMENSION_ENV, &payload);

        let outcome = apply_command_stream(&mut driver, &bytes, 1).unwrap();
        assert_eq!(
            outcome,
            CommandOutcome {
                applied: 1,
                deferred: 0,
                ignored: 0
            }
        );
        assert_eq!(driver.dimension_count(), 1);
        let world = driver.world_mut(0).unwrap();
        assert_eq!(world.wind(), Vec3::new(3.0, 0.0, 0.0));
        assert!(world
            .fluid()
            .is_some_and(|f| f.surface_y == 10.0 && f.density == 2.0));
    }

    #[test]
    fn apply_impulse_change_la_vitesse() {
        let mut driver = SimDriver::new();
        let handle = Handle::new(5, 1);
        let ball = body_in(&mut driver, 0, handle);
        let mut payload = handle.index.to_le_bytes().to_vec();
        payload.extend_from_slice(&handle.generation.to_le_bytes());
        payload.extend(f32s(&[0.0, 10.0, 0.0])); // vector (vers le haut)
        payload.extend(f32s(&[0.0, 0.0, 0.0])); // point
        payload.extend_from_slice(&0u32.to_le_bytes()); // at_point = faux
        assert_eq!(payload.len(), ApplyForce::BYTES);
        let mut bytes = stream();
        push(&mut bytes, opcode::APPLY_IMPULSE, &payload);

        apply_command_stream(&mut driver, &bytes, 1).unwrap();
        let velocity = driver.world_mut(0).unwrap().velocity(ball).unwrap();
        assert!(
            velocity.y > 5.0,
            "l'impulsion accélère vers le haut : {}",
            velocity.y
        );
    }

    #[test]
    fn remove_assembly_retire_le_corps() {
        let mut driver = SimDriver::new();
        let handle = Handle::new(9, 1);
        body_in(&mut driver, 0, handle);
        assert_eq!(driver.world_mut(0).unwrap().body_count(), 1);
        let mut payload = handle.index.to_le_bytes().to_vec();
        payload.extend_from_slice(&handle.generation.to_le_bytes());
        let mut bytes = stream();
        push(&mut bytes, opcode::REMOVE_ASSEMBLY, &payload);

        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1)
                .unwrap()
                .applied,
            1
        );
        assert_eq!(driver.world_mut(0).unwrap().body_count(), 0);
    }

    #[test]
    fn opcodes_inconnu_et_differe_sont_comptes() {
        let mut driver = SimDriver::new();
        let mut bytes = stream();
        push(&mut bytes, opcode::CREATE_ASSEMBLY, &[0u8; 8]); // différé (C-32)
        push(&mut bytes, 9999, &[0u8; 8]); // inconnu
        let outcome = apply_command_stream(&mut driver, &bytes, 2).unwrap();
        assert_eq!(
            outcome,
            CommandOutcome {
                applied: 0,
                deferred: 1,
                ignored: 1
            }
        );
    }

    #[test]
    fn un_flux_fautif_est_refuse() {
        let mut driver = SimDriver::new();
        // Mauvaise version.
        let mut bad_schema = 99u32.to_le_bytes().to_vec();
        bad_schema.extend_from_slice(&0u32.to_le_bytes());
        assert_eq!(
            apply_command_stream(&mut driver, &bad_schema, 0),
            Err(CommandError::UnsupportedSchema)
        );
        // Tronqué : annonce une commande, mais pas de contenu.
        assert_eq!(
            apply_command_stream(&mut driver, &stream(), 1),
            Err(CommandError::Truncated)
        );
        // Mauvaise taille de payload pour un opcode connu.
        let mut bytes = stream();
        push(&mut bytes, opcode::REMOVE_ASSEMBLY, &[0u8; 4]); // 4 au lieu de 8
        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1),
            Err(CommandError::BadPayloadLength)
        );
    }

    /// Sérialise un index de section `[i32; 3]`.
    fn section_bytes(section: [i32; 3]) -> Vec<u8> {
        section.iter().flat_map(|v| v.to_le_bytes()).collect()
    }

    #[test]
    fn set_world_collision_pose_et_retire() {
        let mut driver = SimDriver::new();
        // En-tête (32) : dim 0, section [0,0,0], box_count 1, friction 0.5, restitution 0.
        let mut payload = 0u64.to_le_bytes().to_vec();
        payload.extend(section_bytes([0, 0, 0]));
        payload.extend(1u32.to_le_bytes());
        payload.extend(f32s(&[0.5, 0.0]));
        // Corps : une dalle 16×1×16.
        payload.extend(f32s(&[0.0, 0.0, 0.0, 16.0, 1.0, 16.0]));
        assert_eq!(payload.len(), SetWorldCollision::BYTES + 24);
        let mut bytes = stream();
        push(&mut bytes, opcode::SET_WORLD_COLLISION, &payload);
        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1)
                .unwrap()
                .applied,
            1
        );
        assert_eq!(driver.world_tile_count(), 1);

        // Retrait explicite.
        let mut rm = 0u64.to_le_bytes().to_vec();
        rm.extend(section_bytes([0, 0, 0]));
        rm.extend(0u32.to_le_bytes()); // _pad
        assert_eq!(rm.len(), RemoveWorldTile::BYTES);
        let mut bytes = stream();
        push(&mut bytes, opcode::REMOVE_WORLD_COLLISION, &rm);
        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1)
                .unwrap()
                .applied,
            1
        );
        assert_eq!(driver.world_tile_count(), 0);
    }

    #[test]
    fn set_world_heightfield_pose() {
        let mut driver = SimDriver::new();
        let mut payload = 0u64.to_le_bytes().to_vec();
        payload.extend(section_bytes([0, 0, 0]));
        payload.extend(2u32.to_le_bytes()); // rows
        payload.extend(2u32.to_le_bytes()); // cols
        payload.extend(f32s(&[0.5, 0.0])); // friction, restitution
        payload.extend(f32s(&[16.0, 1.0, 16.0])); // scale
        assert_eq!(payload.len(), SetWorldHeightfield::BYTES);
        payload.extend(f32s(&[1.0, 1.0, 1.0, 1.0])); // 2×2 hauteurs
        let mut bytes = stream();
        push(&mut bytes, opcode::SET_WORLD_HEIGHTFIELD, &payload);
        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1)
                .unwrap()
                .applied,
            1
        );
        assert_eq!(driver.world_tile_count(), 1);
    }

    #[test]
    fn set_world_fluid_pose_et_retire() {
        let mut driver = SimDriver::new();
        let mut payload = 0u64.to_le_bytes().to_vec();
        payload.extend(section_bytes([0, 0, 0]));
        payload.extend(1u32.to_le_bytes()); // box_count
        payload.extend(f32s(&[1000.0])); // density
        payload.extend(0u32.to_le_bytes()); // _pad
        payload.extend(f32s(&[0.0, 1.0, 0.0, 16.0, 9.0, 16.0]));
        assert_eq!(payload.len(), SetWorldFluid::BYTES + 24);
        let mut bytes = stream();
        push(&mut bytes, opcode::SET_WORLD_FLUID, &payload);
        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1)
                .unwrap()
                .applied,
            1
        );
        assert_eq!(driver.world_fluid_tile_count(), 1);

        let mut rm = 0u64.to_le_bytes().to_vec();
        rm.extend(section_bytes([0, 0, 0]));
        rm.extend(0u32.to_le_bytes());
        let mut bytes = stream();
        push(&mut bytes, opcode::REMOVE_WORLD_FLUID, &rm);
        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1)
                .unwrap()
                .applied,
            1
        );
        assert_eq!(driver.world_fluid_tile_count(), 0);
    }

    #[test]
    fn une_tuile_a_corps_incoherent_est_refusee() {
        let mut driver = SimDriver::new();
        // box_count = 2, mais une seule boîte fournie : longueur du corps incohérente.
        let mut payload = 0u64.to_le_bytes().to_vec();
        payload.extend(section_bytes([0, 0, 0]));
        payload.extend(2u32.to_le_bytes());
        payload.extend(f32s(&[0.5, 0.0]));
        payload.extend(f32s(&[0.0, 0.0, 0.0, 1.0, 1.0, 1.0])); // 1 boîte
        let mut bytes = stream();
        push(&mut bytes, opcode::SET_WORLD_COLLISION, &payload);
        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1),
            Err(CommandError::BadPayloadLength)
        );
        assert_eq!(driver.world_tile_count(), 0);
    }

    #[test]
    fn une_tuile_au_dela_du_plafond_est_refusee() {
        let mut driver = SimDriver::new();
        // box_count annoncé au-delà du plafond : refus avant toute allocation.
        let mut payload = 0u64.to_le_bytes().to_vec();
        payload.extend(section_bytes([0, 0, 0]));
        payload.extend((MAX_WORLD_TILE_BOXES + 1).to_le_bytes());
        payload.extend(f32s(&[0.5, 0.0]));
        let mut bytes = stream();
        push(&mut bytes, opcode::SET_WORLD_COLLISION, &payload);
        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1),
            Err(CommandError::BadPayloadLength)
        );
        assert_eq!(driver.world_tile_count(), 0);
    }

    fn positions_vec(count: usize) -> Vec<[f64; 3]> {
        vec![[0.0; 3]; count]
    }

    /// Payload `SET_OBSERVERS` : en-tête puis positions.
    fn observers_payload(dimension: u64, count: u32, positions: &[[f64; 3]]) -> Vec<u8> {
        let mut payload = dimension.to_le_bytes().to_vec();
        payload.extend_from_slice(&count.to_le_bytes());
        payload.extend_from_slice(&0u32.to_le_bytes());
        for position in positions {
            payload.extend(position.iter().flat_map(|v| v.to_le_bytes()));
        }
        payload
    }

    #[test]
    fn set_observers_declare_les_joueurs_de_la_dimension() {
        // ADR-123 §2 : les positions monde, dans l'ordre, pour la dimension visée seule.
        let mut driver = SimDriver::new();
        let mut bytes = stream();
        let positions = [[1.5, 64.0, -3.25], [1.0e6, 70.0, 2.0e5]];
        push(
            &mut bytes,
            opcode::SET_OBSERVERS,
            &observers_payload(7, 2, &positions),
        );

        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1)
                .unwrap()
                .applied,
            1
        );
        let declared: Vec<[f64; 3]> = driver
            .observers(7)
            .iter()
            .map(|position| position.to_array())
            .collect();
        assert_eq!(declared, positions);
        assert!(driver.observers(0).is_empty());
        assert_eq!(
            driver.dimension_count(),
            0,
            "un joueur seul n'ouvre pas de monde"
        );
    }

    #[test]
    fn set_observers_borne_le_compte_et_exige_la_longueur_exacte() {
        let mut driver = SimDriver::new();
        // Au plafond, accepté ; une position de plus, refusé — même de longueur exacte.
        let positions = vec![[0.0; 3]; MAX_OBSERVERS as usize + 1];
        let mut plein = stream();
        push(
            &mut plein,
            opcode::SET_OBSERVERS,
            &observers_payload(0, MAX_OBSERVERS, &positions[1..]),
        );
        assert!(apply_command_stream(&mut driver, &plein, 1).is_ok());
        assert_eq!(driver.observers(0).len(), MAX_OBSERVERS as usize);
        driver.begin_tick();
        let mut trop = stream();
        push(
            &mut trop,
            opcode::SET_OBSERVERS,
            &observers_payload(0, MAX_OBSERVERS + 1, &positions),
        );
        assert_eq!(
            apply_command_stream(&mut driver, &trop, 1),
            Err(CommandError::BadPayloadLength)
        );
        // Un compte qui ne dit pas la longueur réelle, en moins comme en trop : refusé.
        for (count, positions) in [(2, 1), (1, 2)] {
            let mut menteur = stream();
            push(
                &mut menteur,
                opcode::SET_OBSERVERS,
                &observers_payload(0, count, &positions_vec(positions)),
            );
            assert_eq!(
                apply_command_stream(&mut driver, &menteur, 1),
                Err(CommandError::BadPayloadLength),
                "compte {count}, {positions} position(s)"
            );
        }
        assert!(driver.observers(0).is_empty());
    }

    /// Un `EntityProxyDesc` sur la frontière (56 octets).
    fn proxy_desc(
        entity: u32,
        center: [f64; 3],
        half: [f32; 3],
        speed: [f32; 3],
        shape: u8,
    ) -> Vec<u8> {
        let mut bytes: Vec<u8> = center.iter().flat_map(|v| v.to_le_bytes()).collect();
        bytes.extend(f32s(&half));
        bytes.extend(f32s(&speed));
        bytes.extend_from_slice(&entity.to_le_bytes());
        bytes.push(shape);
        bytes.extend_from_slice(&[0u8; 3]);
        assert_eq!(bytes.len(), EntityProxyDesc::BYTES);
        bytes
    }

    /// Payload `SET_ENTITY_PROXIES` : en-tête puis descripteurs.
    fn proxies_payload(dimension: u64, count: u32, descs: &[Vec<u8>]) -> Vec<u8> {
        let mut payload = dimension.to_le_bytes().to_vec();
        payload.extend_from_slice(&count.to_le_bytes());
        payload.extend_from_slice(&0u32.to_le_bytes());
        for desc in descs {
            payload.extend_from_slice(desc);
        }
        payload
    }

    fn un_proxy(entity: u32) -> Vec<u8> {
        proxy_desc(
            entity,
            [0.0; 3],
            [0.5; 3],
            [0.0; 3],
            entity_proxy_shape::BOX,
        )
    }

    #[test]
    fn set_entity_proxies_declare_les_entites_de_la_dimension() {
        // ADR-123 §5 : chaque champ du descripteur, à sa place.
        let mut driver = SimDriver::new();
        let mut bytes = stream();
        let descs = [
            proxy_desc(
                42,
                [10.5, 64.9, -3.5],
                [0.3, 0.9, 0.3],
                [1.0, -2.0, 0.5],
                entity_proxy_shape::CAPSULE,
            ),
            proxy_desc(
                7,
                [1.0e6, 70.0, 2.0e5],
                [0.45, 0.25, 0.7],
                [0.0; 3],
                entity_proxy_shape::BOX,
            ),
        ];
        push(
            &mut bytes,
            opcode::SET_ENTITY_PROXIES,
            &proxies_payload(3, 2, &descs),
        );

        assert_eq!(
            apply_command_stream(&mut driver, &bytes, 1)
                .unwrap()
                .applied,
            1
        );
        assert_eq!(
            driver.entity_proxies(3),
            [
                EntityProxy {
                    entity: 42,
                    center: DVec3::new(10.5, 64.9, -3.5),
                    half_extents: [0.3, 0.9, 0.3],
                    velocity: Vec3::new(1.0, -2.0, 0.5),
                    shape: ProxyShape::Capsule,
                },
                EntityProxy {
                    entity: 7,
                    center: DVec3::new(1.0e6, 70.0, 2.0e5),
                    half_extents: [0.45, 0.25, 0.7],
                    velocity: Vec3::ZERO,
                    shape: ProxyShape::Box,
                },
            ]
        );
        assert!(driver.entity_proxies(0).is_empty());
    }

    #[test]
    fn set_entity_proxies_borne_le_compte_la_longueur_et_la_forme() {
        let mut driver = SimDriver::new();
        // Au plafond, accepté ; un de plus, refusé — même de longueur exacte.
        let descs: Vec<Vec<u8>> = (0..=MAX_ENTITY_PROXIES).map(un_proxy).collect();
        let mut plein = stream();
        push(
            &mut plein,
            opcode::SET_ENTITY_PROXIES,
            &proxies_payload(0, MAX_ENTITY_PROXIES, &descs[1..]),
        );
        assert!(apply_command_stream(&mut driver, &plein, 1).is_ok());
        assert_eq!(driver.entity_proxies(0).len(), MAX_ENTITY_PROXIES as usize);
        let mut trop = stream();
        push(
            &mut trop,
            opcode::SET_ENTITY_PROXIES,
            &proxies_payload(0, MAX_ENTITY_PROXIES + 1, &descs),
        );
        assert_eq!(
            apply_command_stream(&mut driver, &trop, 1),
            Err(CommandError::BadPayloadLength)
        );
        // Un compte qui ne dit pas la longueur réelle, en moins comme en trop.
        for (count, given) in [(2u32, 1usize), (1, 2)] {
            let mut menteur = stream();
            push(
                &mut menteur,
                opcode::SET_ENTITY_PROXIES,
                &proxies_payload(0, count, &descs[..given]),
            );
            assert_eq!(
                apply_command_stream(&mut driver, &menteur, 1),
                Err(CommandError::BadPayloadLength),
                "compte {count}, {given} descripteur(s)"
            );
        }
        // Une forme inconnue.
        let mut informe = stream();
        push(
            &mut informe,
            opcode::SET_ENTITY_PROXIES,
            &proxies_payload(0, 1, &[proxy_desc(1, [0.0; 3], [0.5; 3], [0.0; 3], 2)]),
        );
        assert_eq!(
            apply_command_stream(&mut driver, &informe, 1),
            Err(CommandError::InvalidField)
        );
    }
}
