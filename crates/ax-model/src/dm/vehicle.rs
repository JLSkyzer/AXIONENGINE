//! La section véhicule de `DEFN` (C-33, PARTIE 12, ADR-159).
//!
//! Java a tranché le JSON : valeurs par défaut, règles de forme, profil de chaque pneu recopié
//! dans sa roue. Le natif ne garde ici que ce qu'il simule, et vérifie la forme de ce qu'il
//! reçoit ; un défaut y est celui de l'auteur du `DEFN`, Java, jamais d'une definition.
//!
//! # Disposition
//!
//! Little-endian, sans remplissage, à la suite des parts quand le drapeau `VEHICLE` est posé :
//!
//! ```text
//! en-tête                 108 o : roues, points de couple, rapports, surfaces en u16 ;
//!                                 drapeaux u16 ; transmission u8 ; différentiel u8 ;
//!                                 puis 24 f32 : moteur (4), transmission (5), différentiel (2),
//!                                 direction (5), freins (4), aéro (4)
//! WheelDesc[roues]         88 o
//! [f32; 2][points]          8 o : régime, couple
//! f32[rapports]             4 o
//! AeroSurfaceDesc[surfaces] 16 o
//! ```

use super::definition::DefnError;
use super::scene::NONE_U16;

/// Nombre maximal de roues d'une assembly (R-1052).
pub const MAX_WHEELS: usize = 32;

/// Drapeaux de [`WheelDesc`].
pub mod wheel_flags {
    /// La roue braque.
    pub const STEERED: u16 = 1 << 0;
    /// La roue reçoit le couple du moteur.
    pub const DRIVEN: u16 = 1 << 1;
    /// Le frein à main la serre.
    pub const HANDBRAKE: u16 = 1 << 2;
    /// Tous les drapeaux connus.
    pub const ALL: u16 = STEERED | DRIVEN | HANDBRAKE;
}

/// Drapeaux de [`VehicleDesc`].
pub mod vehicle_flags {
    /// La definition déclare un groupe motopropulseur ; sans lui, le couple vient de l'API (R-1082).
    pub const POWERTRAIN: u16 = 1 << 0;
    /// L'ABS est actif (R-1090).
    pub const ABS: u16 = 1 << 1;
    /// Tous les drapeaux connus.
    pub const ALL: u16 = POWERTRAIN | ABS;
}

/// Types de transmission (12.5, ADR-159 D6).
pub mod transmission_kind {
    /// Le rapport vient de l'entrée.
    pub const MANUAL: u8 = 0;
    /// Le rapport suit `auto_shift_*`.
    pub const AUTOMATIC: u8 = 1;
    /// Court-circuite la boîte (R-1080).
    pub const DIRECT: u8 = 2;
    /// Le dernier type connu.
    pub const LAST: u8 = DIRECT;
}

/// Types de différentiel (R-1081).
pub mod differential_kind {
    /// Différentiel libre.
    pub const OPEN: u8 = 0;
    /// Différentiel bloqué.
    pub const LOCKED: u8 = 1;
    /// Différentiel à glissement limité.
    pub const LIMITED_SLIP: u8 = 2;
    /// Le dernier type connu.
    pub const LAST: u8 = LIMITED_SLIP;
}

/// Une courbe de glissement normalisée, à quatre points (12.4).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TireCurve {
    /// Glissement où l'adhérence culmine.
    pub peak_slip: f32,
    /// Adhérence normalisée à ce pic.
    pub peak_value: f32,
    /// Glissement à partir duquel l'adhérence reste à `tail_value`.
    pub tail_slip: f32,
    /// Adhérence normalisée au-delà.
    pub tail_value: f32,
}

/// La suspension d'une roue (12.3), relative à la masse (R-1060).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SuspensionDesc {
    /// Longueur au repos, en mètres.
    pub rest_length: f32,
    /// Débattement, en mètres.
    pub max_travel: f32,
    /// Raideur relative à la masse.
    pub stiffness: f32,
    /// Amortissement en compression, relatif à la masse.
    pub damping_compression: f32,
    /// Amortissement en détente, relatif à la masse.
    pub damping_rebound: f32,
    /// Force maximale, en newtons.
    pub max_force: f32,
    /// Anti-roulis ; non nul seulement sur un essieu déclaré (R-1061).
    pub anti_roll: f32,
}

/// Une roue.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WheelDesc {
    /// Rang de la référence `NODE` de la roue.
    pub node: u32,
    /// Drapeaux, voir [`wheel_flags`].
    pub flags: u16,
    /// Rang de son essieu, [`NONE_U16`] sans essieu.
    pub axle: u16,
    /// Rayon, en mètres.
    pub radius: f32,
    /// Largeur, en mètres.
    pub width: f32,
    /// Masse, en kg : elle donne l'inertie de la roue.
    pub mass: f32,
    /// Suspension.
    pub suspension: SuspensionDesc,
    /// Coefficient longitudinal du pneu.
    pub mu_long: f32,
    /// Coefficient latéral du pneu.
    pub mu_lat: f32,
    /// Courbe longitudinale.
    pub long: TireCurve,
    /// Courbe latérale.
    pub lat: TireCurve,
}

impl WheelDesc {
    /// Taille d'une roue, en octets.
    pub const BYTES: usize = 88;
}

/// Le moteur (12.5).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EngineDesc {
    /// Régime de ralenti, en tr/min.
    pub idle_rpm: f32,
    /// Régime maximal, en tr/min.
    pub max_rpm: f32,
    /// Inertie, en kg·m².
    pub inertia: f32,
    /// Couple de frein moteur, en N·m.
    pub braking_torque: f32,
}

/// La transmission (12.5).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TransmissionDesc {
    /// Type, voir [`transmission_kind`].
    pub kind: u8,
    /// Rapport de pont.
    pub final_drive: f32,
    /// Durée d'un changement de rapport, en secondes.
    pub shift_time: f32,
    /// Régime de passage au rapport supérieur.
    pub auto_shift_up_rpm: f32,
    /// Régime de passage au rapport inférieur.
    pub auto_shift_down_rpm: f32,
    /// Rendement, dans ]0, 1].
    pub efficiency: f32,
}

/// Le différentiel (R-1081).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DifferentialDesc {
    /// Type, voir [`differential_kind`].
    pub kind: u8,
    /// Part du couple vers l'avant, dans [0, 1] ; `0` sans différentiel central.
    pub front_rear_split: f32,
    /// Taux de blocage, dans [0, 1].
    pub lock: f32,
}

/// La direction (12.6, ADR-159 D7).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SteeringDesc {
    /// Angle maximal, en radians.
    pub max_angle: f32,
    /// Vitesse de braquage, en fraction par seconde.
    pub speed: f32,
    /// Vitesse de retour, en fraction par seconde.
    pub return_speed: f32,
    /// Réduction du braquage avec la vitesse, dans [0, 1].
    pub speed_sensitivity: f32,
    /// Géométrie d'Ackermann, dans [0, 1].
    pub ackermann: f32,
}

/// Les freins (12.6).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BrakesDesc {
    /// Couple maximal à l'avant, en N·m.
    pub max_torque_front: f32,
    /// Couple maximal à l'arrière, en N·m.
    pub max_torque_rear: f32,
    /// Couple du frein à main, en N·m.
    pub handbrake_torque: f32,
    /// Glissement où l'ABS relâche.
    pub abs_slip_threshold: f32,
}

/// L'aérodynamique (12.7).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AeroDesc {
    /// Coefficient de traînée.
    pub drag_coefficient: f32,
    /// Surface frontale, en m².
    pub frontal_area: f32,
    /// Appui à l'avant.
    pub downforce_front: f32,
    /// Appui à l'arrière.
    pub downforce_rear: f32,
}

/// Une surface portante.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AeroSurfaceDesc {
    /// Rang de la référence `NODE` de la surface.
    pub node: u32,
    /// Aire, en m².
    pub area: f32,
    /// Coefficient de portance.
    pub cl: f32,
    /// Coefficient de traînée.
    pub cd: f32,
}

impl AeroSurfaceDesc {
    /// Taille d'une surface, en octets.
    pub const BYTES: usize = 16;
}

/// La section véhicule d'un `DEFN`.
#[derive(Debug, Clone, PartialEq)]
pub struct VehicleDesc {
    /// Drapeaux, voir [`vehicle_flags`].
    pub flags: u16,
    /// Moteur.
    pub engine: EngineDesc,
    /// Transmission.
    pub transmission: TransmissionDesc,
    /// Différentiel.
    pub differential: DifferentialDesc,
    /// Direction.
    pub steering: SteeringDesc,
    /// Freins.
    pub brakes: BrakesDesc,
    /// Aérodynamique.
    pub aero: AeroDesc,
    /// Les roues, dans l'ordre de la definition.
    pub wheels: Vec<WheelDesc>,
    /// La courbe de couple : régime, couple.
    pub torque_curve: Vec<[f32; 2]>,
    /// Les rapports, dans l'ordre de la definition.
    pub gears: Vec<f32>,
    /// Les surfaces portantes.
    pub surfaces: Vec<AeroSurfaceDesc>,
}

/// Lecteur séquentiel, little-endian.
struct Cursor<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl Cursor<'_> {
    fn take<const N: usize>(&mut self) -> Result<[u8; N], DefnError> {
        let end = self.at.checked_add(N).ok_or(DefnError::Truncated)?;
        let slice = self.bytes.get(self.at..end).ok_or(DefnError::Truncated)?;
        self.at = end;
        Ok(slice.try_into().expect("borne"))
    }

    fn u8(&mut self) -> Result<u8, DefnError> {
        Ok(self.take::<1>()?[0])
    }

    fn u16(&mut self) -> Result<u16, DefnError> {
        Ok(u16::from_le_bytes(self.take()?))
    }

    fn u32(&mut self) -> Result<u32, DefnError> {
        Ok(u32::from_le_bytes(self.take()?))
    }

    /// Un `f32` fini : aucun champ de la section n'admet autre chose.
    fn f32(&mut self, field: &'static str) -> Result<f32, DefnError> {
        let value = f32::from_le_bytes(self.take()?);
        if value.is_finite() {
            Ok(value)
        } else {
            Err(DefnError::Field(field))
        }
    }

    fn curve(&mut self) -> Result<TireCurve, DefnError> {
        const FIELD: &str = "wheels.tire";
        let curve = TireCurve {
            peak_slip: self.f32(FIELD)?,
            peak_value: self.f32(FIELD)?,
            tail_slip: self.f32(FIELD)?,
            tail_value: self.f32(FIELD)?,
        };
        let sound = curve.peak_slip > 0.0
            && curve.tail_slip > curve.peak_slip
            && curve.peak_value > 0.0
            && curve.tail_value >= 0.0;
        sound.then_some(curve).ok_or(DefnError::Field(FIELD))
    }

    fn wheel(&mut self) -> Result<WheelDesc, DefnError> {
        const FIELD: &str = "wheels.suspension";
        let node = self.u32()?;
        let flags = self.u16()?;
        let axle = self.u16()?;
        let radius = self.f32("wheels.radius")?;
        let width = self.f32("wheels.width")?;
        let mass = self.f32("wheels.mass")?;
        let suspension = SuspensionDesc {
            rest_length: self.f32(FIELD)?,
            max_travel: self.f32(FIELD)?,
            stiffness: self.f32(FIELD)?,
            damping_compression: self.f32(FIELD)?,
            damping_rebound: self.f32(FIELD)?,
            max_force: self.f32(FIELD)?,
            anti_roll: self.f32(FIELD)?,
        };
        let mu_long = self.f32("wheels.tire")?;
        let mu_lat = self.f32("wheels.tire")?;
        let long = self.curve()?;
        let lat = self.curve()?;
        if flags & !wheel_flags::ALL != 0 {
            return Err(DefnError::Field("wheels.flags"));
        }
        if !(radius > 0.0 && width >= 0.0 && mass > 0.0) {
            return Err(DefnError::Field("wheels.radius"));
        }
        let s = &suspension;
        let sound = s.rest_length >= 0.0
            && s.max_travel > 0.0
            && s.stiffness >= 0.0
            && s.damping_compression >= 0.0
            && s.damping_rebound >= 0.0
            && s.max_force > 0.0
            && s.anti_roll >= 0.0;
        if !sound {
            return Err(DefnError::Field(FIELD));
        }
        if !(mu_long > 0.0 && mu_lat > 0.0) {
            return Err(DefnError::Field("wheels.tire"));
        }
        Ok(WheelDesc {
            node,
            flags,
            axle,
            radius,
            width,
            mass,
            suspension,
            mu_long,
            mu_lat,
            long,
            lat,
        })
    }
}

fn put(out: &mut Vec<u8>, values: &[f32]) {
    for value in values {
        out.extend_from_slice(&value.to_le_bytes());
    }
}

fn put_curve(out: &mut Vec<u8>, curve: &TireCurve) {
    put(
        out,
        &[
            curve.peak_slip,
            curve.peak_value,
            curve.tail_slip,
            curve.tail_value,
        ],
    );
}

impl VehicleDesc {
    /// Taille de l'en-tête de la section, en octets.
    pub const HEADER_BYTES: usize = 108;

    /// Écrit la section en little-endian à la fin de `out`.
    ///
    /// # Panics
    ///
    /// Si une table dépasse ce que son champ peut dire.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        let count = |len: usize| u16::try_from(len).expect("au plus 65 535 entrées");
        for value in [
            count(self.wheels.len()),
            count(self.torque_curve.len()),
            count(self.gears.len()),
            count(self.surfaces.len()),
            self.flags,
        ] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.push(self.transmission.kind);
        out.push(self.differential.kind);
        let (e, t, d, s, b, a) = (
            &self.engine,
            &self.transmission,
            &self.differential,
            &self.steering,
            &self.brakes,
            &self.aero,
        );
        put(
            out,
            &[
                e.idle_rpm,
                e.max_rpm,
                e.inertia,
                e.braking_torque,
                t.final_drive,
                t.shift_time,
                t.auto_shift_up_rpm,
                t.auto_shift_down_rpm,
                t.efficiency,
                d.front_rear_split,
                d.lock,
                s.max_angle,
                s.speed,
                s.return_speed,
                s.speed_sensitivity,
                s.ackermann,
                b.max_torque_front,
                b.max_torque_rear,
                b.handbrake_torque,
                b.abs_slip_threshold,
                a.drag_coefficient,
                a.frontal_area,
                a.downforce_front,
                a.downforce_rear,
            ],
        );
        for wheel in &self.wheels {
            out.extend_from_slice(&wheel.node.to_le_bytes());
            out.extend_from_slice(&wheel.flags.to_le_bytes());
            out.extend_from_slice(&wheel.axle.to_le_bytes());
            let s = &wheel.suspension;
            put(
                out,
                &[
                    wheel.radius,
                    wheel.width,
                    wheel.mass,
                    s.rest_length,
                    s.max_travel,
                    s.stiffness,
                    s.damping_compression,
                    s.damping_rebound,
                    s.max_force,
                    s.anti_roll,
                    wheel.mu_long,
                    wheel.mu_lat,
                ],
            );
            put_curve(out, &wheel.long);
            put_curve(out, &wheel.lat);
        }
        for point in &self.torque_curve {
            put(out, point);
        }
        put(out, &self.gears);
        for surface in &self.surfaces {
            out.extend_from_slice(&surface.node.to_le_bytes());
            put(out, &[surface.area, surface.cl, surface.cd]);
        }
    }

    /// Lit la section au début de `bytes` et vérifie sa forme ; rend aussi sa taille.
    /// `is_node` dit si un rang désigne une référence `NODE`.
    ///
    /// # Errors
    ///
    /// [`DefnError`], au premier écart.
    pub fn read_le(
        bytes: &[u8],
        is_node: impl Fn(u32) -> bool,
    ) -> Result<(Self, usize), DefnError> {
        let mut c = Cursor { bytes, at: 0 };
        let wheels = usize::from(c.u16()?);
        let points = usize::from(c.u16()?);
        let gears = usize::from(c.u16()?);
        let surfaces = usize::from(c.u16()?);
        let flags = c.u16()?;
        let transmission_type = c.u8()?;
        let differential_type = c.u8()?;
        if !(1..=MAX_WHEELS).contains(&wheels) {
            return Err(DefnError::Field("wheels"));
        }
        if flags & !vehicle_flags::ALL != 0 {
            return Err(DefnError::Field("vehicle.flags"));
        }
        if transmission_type > transmission_kind::LAST {
            return Err(DefnError::Field("transmission.type"));
        }
        if differential_type > differential_kind::LAST {
            return Err(DefnError::Field("differential.type"));
        }
        let engine = EngineDesc {
            idle_rpm: c.f32("engine")?,
            max_rpm: c.f32("engine")?,
            inertia: c.f32("engine")?,
            braking_torque: c.f32("engine")?,
        };
        let transmission = TransmissionDesc {
            kind: transmission_type,
            final_drive: c.f32("transmission")?,
            shift_time: c.f32("transmission")?,
            auto_shift_up_rpm: c.f32("transmission")?,
            auto_shift_down_rpm: c.f32("transmission")?,
            efficiency: c.f32("transmission")?,
        };
        let differential = DifferentialDesc {
            kind: differential_type,
            front_rear_split: c.f32("differential")?,
            lock: c.f32("differential")?,
        };
        let steering = SteeringDesc {
            max_angle: c.f32("steering")?,
            speed: c.f32("steering")?,
            return_speed: c.f32("steering")?,
            speed_sensitivity: c.f32("steering")?,
            ackermann: c.f32("steering")?,
        };
        let brakes = BrakesDesc {
            max_torque_front: c.f32("brakes")?,
            max_torque_rear: c.f32("brakes")?,
            handbrake_torque: c.f32("brakes")?,
            abs_slip_threshold: c.f32("brakes")?,
        };
        let aero = AeroDesc {
            drag_coefficient: c.f32("aero")?,
            frontal_area: c.f32("aero")?,
            downforce_front: c.f32("aero")?,
            downforce_rear: c.f32("aero")?,
        };

        let mut wheel_table = Vec::with_capacity(wheels);
        for _ in 0..wheels {
            let wheel = c.wheel()?;
            if !is_node(wheel.node) {
                return Err(DefnError::Field("wheels.node"));
            }
            if wheel.axle != NONE_U16 && usize::from(wheel.axle) >= wheels {
                return Err(DefnError::Field("wheels.axle"));
            }
            wheel_table.push(wheel);
        }
        let mut torque_curve = Vec::with_capacity(points);
        for _ in 0..points {
            torque_curve.push([c.f32("engine.torque_curve")?, c.f32("engine.torque_curve")?]);
        }
        if flags & vehicle_flags::POWERTRAIN != 0
            && (torque_curve.len() < 2 || torque_curve.windows(2).any(|p| p[1][0] <= p[0][0]))
        {
            return Err(DefnError::Field("engine.torque_curve"));
        }
        let mut gear_table = Vec::with_capacity(gears);
        for _ in 0..gears {
            gear_table.push(c.f32("transmission.gears")?);
        }
        let mut surface_table = Vec::with_capacity(surfaces);
        for _ in 0..surfaces {
            let surface = AeroSurfaceDesc {
                node: c.u32()?,
                area: c.f32("aero.surfaces")?,
                cl: c.f32("aero.surfaces")?,
                cd: c.f32("aero.surfaces")?,
            };
            if !is_node(surface.node) {
                return Err(DefnError::Field("aero.surfaces.node"));
            }
            surface_table.push(surface);
        }
        let at = c.at;
        Ok((
            Self {
                flags,
                engine,
                transmission,
                differential,
                steering,
                brakes,
                aero,
                wheels: wheel_table,
                torque_curve,
                gears: gear_table,
                surfaces: surface_table,
            },
            at,
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn courbe() -> TireCurve {
        TireCurve {
            peak_slip: 0.12,
            peak_value: 1.0,
            tail_slip: 0.5,
            tail_value: 0.75,
        }
    }

    fn roue(node: u32) -> WheelDesc {
        WheelDesc {
            node,
            flags: wheel_flags::STEERED | wheel_flags::HANDBRAKE,
            axle: 0,
            radius: 0.36,
            width: 0.24,
            mass: 20.0,
            suspension: SuspensionDesc {
                rest_length: 0.35,
                max_travel: 0.25,
                stiffness: 30.0,
                damping_compression: 2.3,
                damping_rebound: 2.8,
                max_force: 60_000.0,
                anti_roll: 0.5,
            },
            mu_long: 1.0,
            mu_lat: 0.9,
            long: courbe(),
            lat: courbe(),
        }
    }

    fn vehicule() -> VehicleDesc {
        VehicleDesc {
            flags: vehicle_flags::POWERTRAIN,
            engine: EngineDesc {
                idle_rpm: 800.0,
                max_rpm: 7000.0,
                inertia: 0.3,
                braking_torque: 25.0,
            },
            transmission: TransmissionDesc {
                kind: transmission_kind::AUTOMATIC,
                final_drive: 3.7,
                shift_time: 0.25,
                auto_shift_up_rpm: 6200.0,
                auto_shift_down_rpm: 2200.0,
                efficiency: 0.92,
            },
            differential: DifferentialDesc {
                kind: differential_kind::LIMITED_SLIP,
                front_rear_split: 0.4,
                lock: 0.3,
            },
            steering: SteeringDesc {
                max_angle: 0.61,
                speed: 3.0,
                return_speed: 4.0,
                speed_sensitivity: 0.6,
                ackermann: 1.0,
            },
            brakes: BrakesDesc {
                max_torque_front: 3000.0,
                max_torque_rear: 1800.0,
                handbrake_torque: 4000.0,
                abs_slip_threshold: 0.15,
            },
            aero: AeroDesc {
                drag_coefficient: 0.32,
                frontal_area: 2.2,
                downforce_front: 1.0,
                downforce_rear: 2.0,
            },
            wheels: vec![roue(1), roue(2)],
            torque_curve: vec![[0.0, 0.0], [3000.0, 260.0]],
            gears: vec![-3.2, 0.0, 3.5],
            surfaces: vec![AeroSurfaceDesc {
                node: 3,
                area: 1.2,
                cl: 0.8,
                cd: 0.05,
            }],
        }
    }

    fn octets(vehicule: &VehicleDesc) -> Vec<u8> {
        let mut out = Vec::new();
        vehicule.write_le(&mut out);
        out
    }

    fn lire(bytes: &[u8]) -> Result<(VehicleDesc, usize), DefnError> {
        VehicleDesc::read_le(bytes, |rang| rang < 8)
    }

    #[test]
    fn la_section_se_relit_a_l_identique_et_sa_taille_est_celle_annoncee() {
        let bytes = octets(&vehicule());
        assert_eq!(
            bytes.len(),
            VehicleDesc::HEADER_BYTES
                + 2 * WheelDesc::BYTES
                + 2 * 8
                + 3 * 4
                + AeroSurfaceDesc::BYTES
        );
        assert_eq!(lire(&bytes), Ok((vehicule(), bytes.len())));
    }

    #[test]
    fn une_section_coupee_est_tronquee() {
        let bytes = octets(&vehicule());
        assert_eq!(lire(&bytes[..bytes.len() - 1]), Err(DefnError::Truncated));
    }

    #[test]
    fn r1052_de_une_a_trente_deux_roues() {
        let mut v = vehicule();
        v.wheels = vec![roue(1); 33];
        assert_eq!(lire(&octets(&v)), Err(DefnError::Field("wheels")));
        v.wheels = vec![roue(1); 32];
        assert!(lire(&octets(&v)).is_ok());
    }

    #[test]
    fn une_forme_fausse_est_refusee_par_son_champ() {
        type Faussaire = fn(&mut VehicleDesc);
        let cas: [(Faussaire, &str); 8] = [
            (|v| v.wheels[0].node = 99, "wheels.node"),
            (|v| v.wheels[0].axle = 2, "wheels.axle"),
            (|v| v.wheels[0].flags = 1 << 7, "wheels.flags"),
            (|v| v.wheels[0].radius = 0.0, "wheels.radius"),
            (
                |v| v.wheels[0].suspension.max_travel = 0.0,
                "wheels.suspension",
            ),
            (|v| v.wheels[0].long.tail_slip = 0.01, "wheels.tire"),
            (|v| v.engine.max_rpm = f32::NAN, "engine"),
            (|v| v.torque_curve[1][0] = 0.0, "engine.torque_curve"),
        ];
        for (fausser, champ) in cas {
            let mut v = vehicule();
            fausser(&mut v);
            assert_eq!(lire(&octets(&v)), Err(DefnError::Field(champ)));
        }
    }
}
