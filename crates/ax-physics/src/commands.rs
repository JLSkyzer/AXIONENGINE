//! Lecture du flux de commandes `SimIn` (IF-03, ADR-114) et application au
//! [`SimDriver`].
//!
//! `ax-physics` interdit `unsafe` : le flux est lu **champ à champ** en
//! little-endian, jamais par transtypage en place. C'est la couche FFI qui, elle,
//! lit les structures DM en place ; ici on décode, on valide, on applique.

use crate::forces::FluidEnvironment;
use crate::sim::SimDriver;
use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::commands::{
    dimension_env_flags, opcode, ApplyForce, CommandStreamHeader, RemoveAssembly, SetDimensionEnv,
    SetKinematic, SimCommandHeader,
};
use ax_model::dm::handle::Handle;

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
    if bytes.len() < CommandStreamHeader::BYTES {
        return Err(CommandError::Truncated);
    }
    if read_u32(bytes, 0) != CommandStreamHeader::CURRENT_SCHEMA {
        return Err(CommandError::UnsupportedSchema);
    }

    let mut offset = CommandStreamHeader::BYTES;
    let mut outcome = CommandOutcome::default();
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
        let payload = &bytes[payload_offset..payload_offset + payload_len];
        apply_one(driver, op, payload, &mut outcome)?;
        offset = payload_offset + align_up(payload_len, 8);
    }
    Ok(outcome)
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
            let fluid = (flags & dimension_env_flags::FLUID_PRESENT != 0).then(|| FluidEnvironment {
                surface_y: read_f32(payload, 32),
                density: read_f32(payload, 36),
            });
            driver.apply_dimension_env(dimension, gravity, wind, fluid);
            outcome.applied += 1;
        }
        // Connus mais reportés : CREATE_ASSEMBLY attend C-32, APPLY_FORCE continu
        // attend son intégration à la boucle de forces.
        opcode::CREATE_ASSEMBLY | opcode::APPLY_FORCE => outcome.deferred += 1,
        // Inconnu : ignoré, comme les genres d'événements (ADR-113/114).
        _ => outcome.ignored += 1,
    }
    Ok(())
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
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().expect("borne vérifiée"))
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(bytes[offset..offset + 8].try_into().expect("borne vérifiée"))
}

fn read_f32(bytes: &[u8], offset: usize) -> f32 {
    f32::from_le_bytes(bytes[offset..offset + 4].try_into().expect("borne vérifiée"))
}

fn read_f64(bytes: &[u8], offset: usize) -> f64 {
    f64::from_le_bytes(bytes[offset..offset + 8].try_into().expect("borne vérifiée"))
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
            .add_body(BodyKind::Dynamic, Vec3::new(0.0, 5.0, 0.0), Quat::IDENTITY, Shape::Ball { radius: 0.5 })
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
        assert_eq!(outcome, CommandOutcome { applied: 1, deferred: 0, ignored: 0 });
        assert_eq!(driver.dimension_count(), 1);
        let world = driver.world_mut(0).unwrap();
        assert_eq!(world.wind(), Vec3::new(3.0, 0.0, 0.0));
        assert!(world.fluid().is_some_and(|f| f.surface_y == 10.0 && f.density == 2.0));
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
        assert!(velocity.y > 5.0, "l'impulsion accélère vers le haut : {}", velocity.y);
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

        assert_eq!(apply_command_stream(&mut driver, &bytes, 1).unwrap().applied, 1);
        assert_eq!(driver.world_mut(0).unwrap().body_count(), 0);
    }

    #[test]
    fn opcodes_inconnu_et_differe_sont_comptes() {
        let mut driver = SimDriver::new();
        let mut bytes = stream();
        push(&mut bytes, opcode::CREATE_ASSEMBLY, &[0u8; 8]); // différé (C-32)
        push(&mut bytes, 9999, &[0u8; 8]); // inconnu
        let outcome = apply_command_stream(&mut driver, &bytes, 2).unwrap();
        assert_eq!(outcome, CommandOutcome { applied: 0, deferred: 1, ignored: 1 });
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
}
