//! Le groupe motopropulseur et la rotation des roues au sous-pas (12.5, ADR-159 D4, D6).
//!
//! Moteur, embrayage, boîte, différentiel, freins et pneus s'intègrent ensemble, en micro-pas d'au
//! plus [`MICRO_STEP`] : la roue est légère et prise entre deux masses lourdes, le moteur ramené par
//! la boîte et le véhicule par le pneu ; à 60 Hz, une intégration explicite ferait patiner le pneu
//! d'un glissement fictif. Le véhicule, lui, ne bouge qu'au sous-pas : chaque roue tient la vitesse
//! de son point de contact, que les pneus de son corps font évoluer ensemble, roue après roue, par la
//! translation et la rotation de ce corps (`n_i·n_j / M + (p_i × n_i)·I⁻¹·(p_j × n_j)`), en
//! Gauss-Seidel ; elle rend la force moyenne du sous-pas. Deux roues freinées retiennent
//! ainsi tout le véhicule, pas leur seule part de sa masse ; la force latérale d'un contact placé sous
//! le centre de masse compte le roulis qu'elle provoque.
//!
//! Aucune force n'inverse ce qu'elle combat : le frein ramène la roue à l'arrêt sans la faire
//! repartir, le pneu ne fait pas passer la roue ni le véhicule au-delà de la vitesse de l'autre,
//! l'embrayage n'inverse pas l'écart de vitesses entre moteur et boîte.

use crate::{brakes, differential, engine, sign, tire};
use ax_math::{Mat3, Vec3};
use ax_model::dm::scene::NONE_U16;
use ax_model::dm::vehicle::{transmission_kind, VehicleDesc, WheelDesc, MAX_WHEELS};

/// Durée visée d'un micro-pas, en secondes. Choix d'ADR-159 (tranche 3).
pub const MICRO_STEP: f32 = 1.0 / 720.0;

/// Couple que l'embrayage transmet au plus, en multiple du couple maximal de la courbe. Choix
/// d'ADR-159 (tranche 3) : de quoi ne jamais patiner engagé, sans tenir de valeur au CDC.
pub const CLUTCH_CAPACITY: f32 = 2.0;

/// Part de la plage de régime, au-dessus du ralenti, sur laquelle l'embrayage automatique s'engage
/// au démarrage. Choix d'ADR-159 (tranche 3).
pub const LAUNCH_BAND: f32 = 0.3;

/// Ralentissement d'une roue en l'air, en 1/s : la « traînée de rotation » de 12.2. Choix d'ADR-159.
pub const AIR_SPIN_DRAG: f32 = 0.5;

/// Le nombre de micro-pas d'un sous-pas de `dt` secondes.
#[must_use]
pub fn micro_steps(dt: f32) -> u32 {
    let steps = (dt / MICRO_STEP).ceil();
    if steps.is_finite() && steps >= 1.0 {
        steps as u32
    } else {
        1
    }
}

/// Une roue pour un sous-pas : ce que le rayon a trouvé, ce qui la freine, et ce qu'elle rend.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct WheelDrive {
    /// Le rayon touche le sol.
    pub grounded: bool,
    /// Charge, en newtons.
    pub load: f32,
    /// Coefficient d'adhérence du matériau touché (R-1070).
    pub surface: f32,
    /// Vitesse du point de contact par rapport au sol au début du sous-pas, le long de la roue, en
    /// m/s ; la gravité du sous-pas comprise.
    pub ground_speed: f32,
    /// Vitesse du point de contact au début du sous-pas, vers la droite de la roue, en m/s ; la
    /// gravité du sous-pas comprise.
    pub lateral_speed: f32,
    /// Glissement latéral, en radians, tenu sur le sous-pas.
    pub slip_lat: f32,
    /// L'avant de la roue dans le plan de contact, unitaire.
    pub tangent: Vec3,
    /// Sa droite dans le plan de contact, unitaire.
    pub right: Vec3,
    /// Le point de contact, relatif au centre de masse du corps qui porte la roue.
    pub offset: Vec3,
    /// La masse de ce corps, en kg.
    pub carrier_mass: f32,
    /// L'inverse de son tenseur d'inertie, dans le repère des vecteurs ci-dessus.
    pub carrier_inv_inertia: Mat3,
    /// Le rang de la première roue de son corps : les roues d'un même corps partagent son mouvement.
    pub carrier: u16,
    /// Couple de la pédale et des correcteurs, en N·m : l'ABS peut le relâcher.
    pub brake: f32,
    /// Couple du frein à main et de la résistance au roulement, en N·m : jamais relâché.
    pub hold: f32,
    /// Part du couple de l'arbre, voir [`differential::shares`].
    pub share: f32,
    /// Couple moteur posé sur la roue elle-même, en N·m, positif vers l'avant (R-1082, ADR-167 D9) : celui d'un
    /// contrôleur de véhicule. Il s'ajoute à la part de l'arbre ; sans `powertrain`, c'est tout le couple de la roue.
    pub torque: f32,
    /// Groupe de couplage du différentiel, voir [`differential::groups`].
    pub group: u16,
    /// Vitesse de rotation, en rad/s, positive en marche avant : l'état de la roue.
    pub spin: f32,
    /// Force longitudinale moyenne du sous-pas sur le véhicule, en N, positive vers l'avant de la roue.
    pub force_long: f32,
    /// Force latérale moyenne du sous-pas sur le véhicule, en N, positive vers la droite de la roue.
    pub force_lat: f32,
    /// Glissement longitudinal au dernier micro-pas.
    pub slip_long: f32,
}

/// Ce que la boîte et le conducteur commandent pour un sous-pas.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DriveCommand {
    /// Rapport total, pont compris, signé ; 0 : rien ne passe.
    pub ratio: f32,
    /// Accélérateur, dans [0, 1], correcteurs compris.
    pub throttle: f32,
    /// Embrayage demandé, dans [0, 1] : 1 débraye.
    pub clutch: f32,
    /// Seuil de l'ABS, s'il est actif.
    pub abs: Option<f32>,
}

/// Avance moteur et roues d'un sous-pas de `dt`. `engine_speed` est la vitesse du moteur, en rad/s ;
/// `wheels` suit l'ordre de `desc.wheels`. Rend `force_long` et `force_lat` moyennés sur le sous-pas.
pub fn step(
    desc: &VehicleDesc,
    command: &DriveCommand,
    engine_speed: &mut f32,
    wheels: &mut [WheelDrive],
    dt: f32,
) {
    let steps = micro_steps(dt);
    let h = dt / steps as f32;
    let e = &desc.engine;
    let direct = desc.transmission.kind == transmission_kind::DIRECT;
    let idle = e.idle_rpm / engine::RPM_PER_RAD_S;
    let max = e.max_rpm / engine::RPM_PER_RAD_S;
    // Une boîte directe n'a pas de ralenti à tenir : le moteur suit les roues jusqu'à l'arrêt.
    let floor = if direct { 0.0 } else { idle };
    let band = (LAUNCH_BAND * (max - idle)).max(f32::EPSILON);
    let capacity = CLUTCH_CAPACITY * engine::peak_torque(&desc.torque_curve).max(e.braking_torque);
    let ratio = command.ratio;
    let efficiency = desc.transmission.efficiency;
    let engine_inertia = e.inertia.max(f32::EPSILON);
    // L'inverse de l'inertie des roues ramenée au moteur : 1 / I_t = r² η Σ part² / I_roue.
    let reflected = ratio
        * ratio
        * efficiency
        * wheels
            .iter()
            .zip(&desc.wheels)
            .map(|(w, spec)| w.share * w.share / inertia(spec))
            .sum::<f32>();
    let rate = differential::coupling(&desc.differential, steps);
    for wheel in wheels.iter_mut() {
        wheel.force_long = 0.0;
        wheel.force_lat = 0.0;
    }
    // Ce dont la vitesse et la vitesse angulaire de chaque corps ont changé depuis le début du
    // sous-pas, au rang de sa première roue.
    let mut carried = [Motion::default(); MAX_WHEELS];
    for _ in 0..steps {
        let carrier: f32 = wheels.iter().map(|w| w.share * w.spin).sum();
        let transmitted = ratio * carrier;
        let delta = *engine_speed - transmitted;
        let clutch_torque = if ratio == 0.0 || reflected <= 0.0 {
            0.0
        } else {
            let engaged = if direct {
                1.0
            } else {
                // Au clavier, l'embrayage reste automatique (D6) : engagé dès que la boîte tourne au
                // moins au ralenti, il patine en dessous selon le régime, et le moteur ne cale pas.
                let automatic = if transmitted.abs() >= idle {
                    1.0
                } else {
                    ((*engine_speed - idle) / band).clamp(0.0, 1.0)
                };
                automatic.min(1.0 - command.clutch.clamp(0.0, 1.0))
            };
            // Pas plus que ce qui annulerait l'écart en un micro-pas, roues libres.
            let bound = delta.abs() / (h * (1.0 / engine_inertia + reflected));
            sign(delta) * (engaged * capacity).min(bound)
        };
        let torque = engine::torque(e, &desc.torque_curve, *engine_speed, command.throttle);
        *engine_speed =
            (*engine_speed + (torque - clutch_torque) * h / engine_inertia).clamp(floor, max);
        let shaft = clutch_torque * ratio * efficiency;
        // Gauss-Seidel : chaque roue voit ce que les précédentes ont déjà fait à son corps.
        for (wheel, spec) in wheels.iter_mut().zip(&desc.wheels) {
            let body = usize::from(wheel.carrier).min(MAX_WHEELS - 1);
            let drive = shaft * wheel.share + wheel.torque;
            integrate(wheel, spec, drive, command.abs, h, &mut carried[body]);
        }
        if rate > 0.0 {
            couple(wheels, rate);
        }
    }
    // La force latérale, en deux demi-passes de sens opposés : aucune ne fait franchir à un contact la
    // vitesse latérale nulle, le mouvement que son corps a déjà reçu compris.
    let mut wanted = [0.0f32; MAX_WHEELS];
    for (want, wheel) in wanted.iter_mut().zip(wheels.iter_mut()) {
        *want = 0.5 * wheel.force_lat;
        wheel.force_long /= dt;
        wheel.force_lat = 0.0;
    }
    for half in 0..2 {
        for rank in 0..wheels.len() {
            let index = if half == 0 {
                rank
            } else {
                wheels.len() - 1 - rank
            };
            let wheel = &mut wheels[index];
            let body = usize::from(wheel.carrier).min(MAX_WHEELS - 1);
            let impulse = lateral_impulse(wheel, wanted[index], &carried[body]);
            carried[body].push(wheel, wheel.right, impulse);
            wheel.force_lat += impulse / dt;
        }
    }
}

/// Ce dont le mouvement d'un corps a changé depuis le début du sous-pas.
#[derive(Debug, Clone, Copy, Default)]
struct Motion {
    /// Vitesse, en m/s.
    linear: Vec3,
    /// Vitesse angulaire, en rad/s.
    angular: Vec3,
}

impl Motion {
    /// Ce dont la vitesse du point de contact d'une roue a changé, le long de `direction`.
    fn along(&self, wheel: &WheelDrive, direction: Vec3) -> f32 {
        direction.dot(self.linear) + wheel.offset.cross(direction).dot(self.angular)
    }

    /// Une impulsion `impulse`, en N·s, le long de `direction` au point de contact d'une roue.
    fn push(&mut self, wheel: &WheelDrive, direction: Vec3, impulse: f32) {
        self.linear += direction * (impulse / wheel.carrier_mass.max(f32::EPSILON));
        self.angular += wheel.carrier_inv_inertia * wheel.offset.cross(direction) * impulse;
    }
}

/// L'inverse de la masse qu'un corps oppose à une impulsion le long de `direction` au point de contact
/// d'une roue : sa translation et sa rotation.
fn inverse_mass(wheel: &WheelDrive, direction: Vec3) -> f32 {
    let arm = wheel.offset.cross(direction);
    direction.length_squared() / wheel.carrier_mass.max(f32::EPSILON)
        + arm.dot(wheel.carrier_inv_inertia * arm)
}

/// L'impulsion latérale d'une roue pour une demi-passe, en N·s : `wanted`, la moitié de celle que le
/// pneu donne sur le sous-pas, sans faire franchir à son contact la vitesse latérale nulle. Elle ne
/// pousse jamais dans le sens où le contact glisse déjà.
fn lateral_impulse(wheel: &WheelDrive, wanted: f32, carried: &Motion) -> f32 {
    if !wheel.grounded {
        return 0.0;
    }
    let speed = wheel.lateral_speed + carried.along(wheel, wheel.right);
    let inverse = inverse_mass(wheel, wheel.right);
    if wanted * speed >= 0.0 || inverse <= 0.0 {
        return 0.0;
    }
    sign(wanted) * wanted.abs().min(speed.abs() / inverse)
}

/// L'inertie d'une roue, en kg·m² : un disque plein.
fn inertia(spec: &WheelDesc) -> f32 {
    0.5 * spec.mass * spec.radius * spec.radius
}

/// Un micro-pas d'une roue : couple d'entraînement, pneu, puis frein, dans la même intégration.
/// `carried` est ce dont le mouvement de son corps a déjà changé dans ce sous-pas ; sa force s'y
/// ajoute.
fn integrate(
    wheel: &mut WheelDrive,
    spec: &WheelDesc,
    drive: f32,
    abs: Option<f32>,
    h: f32,
    carried: &mut Motion,
) {
    let inertia = inertia(spec);
    let surface_speed = wheel.spin * spec.radius;
    let mut brake = wheel.brake;
    // La masse que le corps oppose à une force sur cette roue : sa translation et sa rotation.
    let body_mass = 1.0 / inverse_mass(wheel, wheel.tangent).max(f32::EPSILON);
    let (f_long, f_lat) = if wheel.grounded {
        let ground_speed = wheel.ground_speed + carried.along(wheel, wheel.tangent);
        let slip_speed = surface_speed - ground_speed;
        let slip = tire::slip_long(surface_speed, ground_speed);
        wheel.slip_long = slip;
        if abs.is_some_and(|threshold| brakes::abs_releases(threshold, surface_speed, ground_speed))
        {
            brake = 0.0;
        }
        let forces = tire::forces(spec, wheel.surface, wheel.load, slip, wheel.slip_lat);
        // ADR-159 D4 : la force ne fait franchir à aucun des deux la vitesse de l'autre. Une roue
        // libre ne transmet que ce que sa propre inertie retient ; tenue par son frein, elle transmet
        // en plus le couple du frein, moins ce que le corps recule pendant ce temps, jusqu'à arrêter
        // sur elle le corps qui la porte.
        let wheel_mass = 0.5 * spec.mass;
        let reduced = wheel_mass * body_mass / (wheel_mass + body_mass);
        let held = brake + wheel.hold;
        let bound = (reduced * slip_speed.abs() / h + held / spec.radius * reduced / wheel_mass)
            .min(body_mass * slip_speed.abs() / h);
        (forces.long.clamp(-bound, bound), forces.lat)
    } else {
        wheel.slip_long = 0.0;
        wheel.spin *= (1.0 - AIR_SPIN_DRAG * h).max(0.0);
        (0.0, 0.0)
    };
    let free = wheel.spin + (drive - f_long * spec.radius) * h / inertia;
    // Le frein ramène la roue vers l'arrêt, sans la faire repartir dans l'autre sens.
    let stop = (brake + wheel.hold) * h / inertia;
    wheel.spin = if free.abs() <= stop {
        0.0
    } else {
        free - stop * sign(free)
    };
    if wheel.grounded {
        carried.push(wheel, wheel.tangent, f_long * h);
    }
    wheel.force_long += f_long * h;
    wheel.force_lat += f_lat * h;
}

/// Rapproche la vitesse de chaque roue motrice de la moyenne de son groupe, de la part `rate` de
/// l'écart : leur somme est conservée.
fn couple(wheels: &mut [WheelDrive], rate: f32) {
    for first in 0..wheels.len() {
        let group = wheels[first].group;
        if group == NONE_U16 || wheels[..first].iter().any(|w| w.group == group) {
            continue;
        }
        let (sum, count) = wheels[first..]
            .iter()
            .filter(|w| w.group == group)
            .fold((0.0, 0.0), |(s, n), w| (s + w.spin, n + 1.0));
        let mean = sum / count;
        for wheel in wheels[first..].iter_mut().filter(|w| w.group == group) {
            wheel.spin += (mean - wheel.spin) * rate;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tests_support::vehicule;
    use ax_model::dm::vehicle::{differential_kind, vehicle_flags};

    const DT: f32 = 1.0 / 60.0;
    const GRAVITE: f32 = 9.81;
    /// Masse du véhicule d'essai, en kg.
    const MASSE: f32 = 1600.0;

    /// Une roue au sol, chargée de son quart du véhicule.
    fn au_sol() -> WheelDrive {
        WheelDrive {
            grounded: true,
            load: MASSE / 4.0 * GRAVITE,
            surface: 1.0,
            tangent: Vec3::NEG_Z,
            right: Vec3::X,
            carrier_mass: MASSE,
            carrier_inv_inertia: Mat3::ZERO,
            carrier: 0,
            group: NONE_U16,
            ..WheelDrive::default()
        }
    }

    /// Le véhicule d'essai, roues arrière motrices.
    fn quatre_roues() -> [WheelDrive; 4] {
        let mut roues = [au_sol(); 4];
        roues[2].share = 0.5;
        roues[3].share = 0.5;
        roues
    }

    fn commande(ratio: f32, throttle: f32) -> DriveCommand {
        DriveCommand {
            ratio,
            throttle,
            clutch: 0.0,
            abs: None,
        }
    }

    /// Un sous-pas où le véhicule avance : chaque roue repart de la vitesse moyenne de son point de
    /// contact, que les quatre forces font évoluer ensemble.
    fn rouler(
        v: &VehicleDesc,
        c: &DriveCommand,
        moteur: &mut f32,
        roues: &mut [WheelDrive; 4],
        vitesse: &mut f32,
    ) {
        for roue in roues.iter_mut() {
            roue.ground_speed = *vitesse;
        }
        step(v, c, moteur, roues, DT);
        *vitesse += roues.iter().map(|r| r.force_long).sum::<f32>() * DT / MASSE;
    }

    #[test]
    fn le_micro_pas_divise_le_sous_pas() {
        assert_eq!(micro_steps(DT), 12);
        assert_eq!(micro_steps(1.0 / 20.0), 36);
        assert_eq!(micro_steps(0.0), 1);
        assert_eq!(micro_steps(f32::NAN), 1);
    }

    #[test]
    fn adr159_d6_le_moteur_ne_cale_pas_et_ne_passe_pas_son_regime_maximal() {
        let v = vehicule();
        let mut moteur = 0.0;
        let mut roues = quatre_roues();
        step(&v, &commande(0.0, 0.0), &mut moteur, &mut roues, DT);
        assert!(
            (moteur * engine::RPM_PER_RAD_S - 800.0).abs() < 1.0,
            "au ralenti"
        );
        for _ in 0..600 {
            step(&v, &commande(0.0, 1.0), &mut moteur, &mut roues, DT);
        }
        assert!(
            (moteur * engine::RPM_PER_RAD_S - 7000.0).abs() < 1.0,
            "coupé au régime maximal"
        );
        // Première engagée, roues tenues à l'arrêt par le frein : il ne cale pas.
        for roue in roues.iter_mut() {
            roue.hold = 1.0e5;
        }
        for _ in 0..120 {
            step(&v, &commande(13.0, 0.0), &mut moteur, &mut roues, DT);
        }
        assert!(moteur * engine::RPM_PER_RAD_S >= 799.0);
        assert!(roues.iter().all(|r| r.spin == 0.0));
    }

    #[test]
    fn le_couple_passe_par_l_embrayage_et_fait_avancer() {
        let v = vehicule();
        let mut moteur = 800.0 / engine::RPM_PER_RAD_S;
        let mut roues = quatre_roues();
        let mut vitesse = 0.0;
        let premiere = 3.5 * 3.7;
        // À mi-accélérateur : en première, le plein couple dépasserait l'adhérence de l'arrière.
        for _ in 0..120 {
            rouler(
                &v,
                &commande(premiere, 0.5),
                &mut moteur,
                &mut roues,
                &mut vitesse,
            );
        }
        assert!(vitesse > 2.0, "il démarre : {vitesse}");
        // L'embrayage est engagé : le moteur tourne à la vitesse que la boîte lui impose, à l'écart près
        // qui fait passer son couple sous la borne de non-inversion, `T · h · (1/I_moteur + 1/I_roues)` :
        // environ 7 rad/s ici, 6 % (calcul).
        let impose = premiere * 0.5 * (roues[2].spin + roues[3].spin);
        assert!(
            (moteur - impose).abs() < 0.1 * impose,
            "moteur {moteur}, boîte {impose}"
        );
        assert!(roues[0].spin > 0.0, "les roues libres suivent le sol");
        // Glissement de la roue motrice : celui d'un pneu qui tire, sous son pic.
        assert!(
            roues[2].slip_long > 0.0 && roues[2].slip_long < 0.12,
            "{}",
            roues[2].slip_long
        );
    }

    #[test]
    fn adr159_d4_le_frein_ramene_la_roue_a_l_arret_sans_l_inverser() {
        let v = vehicule();
        let mut moteur = 0.0;
        let mut roue = [au_sol()];
        roue[0].spin = 2.0;
        roue[0].ground_speed = 0.0;
        roue[0].grounded = false;
        roue[0].brake = 3000.0;
        let mut v1 = v.clone();
        v1.wheels.truncate(1);
        step(&v1, &commande(0.0, 0.0), &mut moteur, &mut roue, DT);
        assert_eq!(roue[0].spin, 0.0);
        step(&v1, &commande(0.0, 0.0), &mut moteur, &mut roue, DT);
        assert_eq!(roue[0].spin, 0.0, "il ne repart pas");
    }

    #[test]
    fn adr159_d4_les_roues_freinees_tiennent_tout_le_vehicule_contre_la_gravite() {
        // Sur une pente, la gravité du sous-pas amène 0,03 m/s ; les deux roues arrière, freinées,
        // doivent la reprendre pour tout le véhicule, les roues avant restant libres.
        let v = vehicule();
        let mut moteur = 0.0;
        let mut roues = [au_sol(); 4];
        for roue in roues.iter_mut() {
            roue.ground_speed = 0.03;
        }
        roues[2].hold = 4000.0;
        roues[3].hold = 4000.0;
        step(&v, &commande(0.0, 0.0), &mut moteur, &mut roues, DT);
        assert_eq!((roues[2].spin, roues[3].spin), (0.0, 0.0));
        let tenu: f32 = roues.iter().map(|r| r.force_long).sum();
        let attendu = -0.03 * MASSE / DT;
        assert!(
            (tenu - attendu).abs() < 0.01 * attendu.abs(),
            "{tenu} au lieu de {attendu}"
        );
    }

    #[test]
    fn adr159_d4_une_roue_freinee_tient_son_corps_contre_la_gravite() {
        // Une roue seule, freinée, qui porte tout son corps sur une pente : le pneu reprend toute la
        // vitesse que la gravité du sous-pas lui amène, la roue restant à l'arrêt.
        let mut v = vehicule();
        v.wheels.truncate(1);
        let mut moteur = 0.0;
        let mut roue = [au_sol()];
        roue[0].load = MASSE * GRAVITE;
        roue[0].hold = 4000.0;
        roue[0].ground_speed = 0.03;
        step(&v, &commande(0.0, 0.0), &mut moteur, &mut roue, DT);
        assert_eq!(roue[0].spin, 0.0);
        let attendu = -0.03 * MASSE / DT;
        assert!(
            (roue[0].force_long - attendu).abs() < 0.01 * attendu.abs(),
            "{} au lieu de {attendu}",
            roue[0].force_long
        );
        // Sans frein, la roue libre ne retient presque rien : elle se met à rouler.
        let mut libre = [au_sol()];
        libre[0].ground_speed = 0.03;
        step(&v, &commande(0.0, 0.0), &mut moteur, &mut libre, DT);
        assert!(libre[0].force_long.abs() < 0.1 * attendu.abs());
        assert!(libre[0].spin > 0.0);
    }

    #[test]
    fn r1090_l_abs_garde_la_roue_sous_son_seuil() {
        let v = vehicule();
        let freiner = |abs: Option<f32>| {
            let mut moteur = 800.0 / engine::RPM_PER_RAD_S;
            let mut roues = [au_sol(); 4];
            let mut vitesse = 20.0;
            for roue in roues.iter_mut() {
                roue.spin = 20.0 / 0.35;
                roue.brake = 5000.0;
            }
            let c = DriveCommand {
                abs,
                ..commande(0.0, 0.0)
            };
            let mut pire: f32 = 0.0;
            for _ in 0..60 {
                rouler(&v, &c, &mut moteur, &mut roues, &mut vitesse);
                pire = pire.min(roues[0].slip_long);
            }
            (vitesse, pire)
        };
        let (bloque, glissement_bloque) = freiner(None);
        let (abs, glissement_abs) = freiner(Some(0.15));
        assert!(
            glissement_bloque < -0.9,
            "roue bloquée : {glissement_bloque}"
        );
        assert!(glissement_abs > -0.3, "ABS : {glissement_abs}");
        assert!(abs < bloque, "l'ABS freine mieux : {abs} contre {bloque}");
    }

    #[test]
    fn deux_roues_symetriques_tirent_autant_l_une_que_l_autre() {
        // Deux roues motrices de part et d'autre d'un corps qui tourne : l'ordre où le pas les prend ne
        // laisse entre elles que l'écart d'un micro-pas de Gauss-Seidel, `f·h·K`, sous 0,1 %.
        let v = vehicule();
        let mut moteur = 2000.0 / engine::RPM_PER_RAD_S;
        let mut roues = quatre_roues();
        let inertie = Mat3::from_diagonal(Vec3::new(1.0 / 900.0, 1.0 / 1400.0, 1.0 / 500.0));
        for (roue, (x, z)) in
            roues
                .iter_mut()
                .zip([(-0.8, -1.4), (0.8, -1.4), (-0.8, 1.4), (0.8, 1.4)])
        {
            roue.offset = Vec3::new(x, -0.5, z);
            roue.carrier_inv_inertia = inertie;
            roue.ground_speed = 5.0;
            roue.spin = 5.0 / 0.35;
        }
        step(&v, &commande(13.0, 1.0), &mut moteur, &mut roues, DT);
        let (gauche, droite) = (roues[2].force_long, roues[3].force_long);
        assert!(gauche > 100.0, "elles tirent : {gauche}");
        assert!(
            (gauche - droite).abs() < 1e-3 * gauche,
            "gauche {gauche}, droite {droite}"
        );
    }

    #[test]
    fn t328_bloque_la_roue_en_l_air_ne_prend_pas_tout_le_couple() {
        let pousser = |kind: u8| {
            let mut v = vehicule();
            v.differential.kind = kind;
            let mut moteur = 800.0 / engine::RPM_PER_RAD_S;
            let mut roues = quatre_roues();
            roues[3].grounded = false;
            for (roue, groupe) in roues.iter_mut().zip([NONE_U16, NONE_U16, 0, 0]) {
                roue.group = groupe;
            }
            let mut vitesse = 0.0;
            for _ in 0..60 {
                rouler(
                    &v,
                    &commande(13.0, 1.0),
                    &mut moteur,
                    &mut roues,
                    &mut vitesse,
                );
            }
            (vitesse, roues[3].spin)
        };
        let (libre, en_l_air_libre) = pousser(differential_kind::OPEN);
        let (bloque, en_l_air_bloque) = pousser(differential_kind::LOCKED);
        assert!(bloque > 2.0 * libre, "bloqué {bloque}, libre {libre}");
        assert!(en_l_air_bloque < en_l_air_libre);
    }

    #[test]
    fn la_boite_directe_suit_les_roues_jusqu_a_l_arret() {
        let mut v = vehicule();
        v.transmission.kind = transmission_kind::DIRECT;
        v.flags = vehicle_flags::POWERTRAIN;
        // Un moteur électrique : son couple ne s'annule pas à l'arrêt.
        v.torque_curve = vec![[0.0, 200.0], [6000.0, 120.0]];
        let mut moteur = 0.0;
        let mut roues = quatre_roues();
        let mut vitesse = 0.0;
        for _ in 0..30 {
            rouler(
                &v,
                &commande(3.7, 0.0),
                &mut moteur,
                &mut roues,
                &mut vitesse,
            );
        }
        assert!(
            moteur.abs() < 1e-3 && vitesse.abs() < 1e-3,
            "rien ne bouge sans accélérateur"
        );
        for _ in 0..60 {
            rouler(
                &v,
                &commande(3.7, 1.0),
                &mut moteur,
                &mut roues,
                &mut vitesse,
            );
        }
        assert!(vitesse > 1.0);
    }
}
