//! T-304, part native : proxies des entités vanilla (R-614, ADR-123 §5 et §6).
//!
//! Chaque tick, comme le fera la frontière : `begin_tick`, déclaration des proxies,
//! `sync_entity_proxies`, puis le pas.

use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_physics::{
    event_data, event_kind, BodyCollider, BodyId, BodyKind, CollisionGroups, ContactMaterial,
    EntityProxy, Handle, PhysicsEvent, ProxyShape, ReservedGroup, Shape, SimDriver, NO_MATERIAL,
};

/// La bille d'essai : handle (7, 1).
const BILLE: Handle = Handle::new(7, 1);

/// Un pilote dont la dimension 0 est sans gravité : rien n'y bouge que ce qu'on lance.
fn pilote() -> SimDriver {
    let mut driver = SimDriver::new();
    driver.apply_dimension_env(0, Vec3::ZERO, Vec3::ZERO, None);
    driver
}

/// Une bille d'une demi-tonne environ (rayon 0,5, densité 1000) à `x`.
fn bille(driver: &mut SimDriver, x: f64) -> BodyId {
    driver
        .create_assembly(
            0,
            BILLE,
            WorldTransform {
                position: [x, 0.0, 0.0],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            BodyKind::Dynamic,
            &[BodyCollider {
                shape: Shape::Ball { radius: 0.5 },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .expect("une bille est valide")
}

fn lancer(driver: &mut SimDriver, body: BodyId, vitesse: f32) {
    let world = driver.world_mut(0).unwrap();
    let masse = world.body_mass(body).unwrap();
    world.apply_impulse(
        body,
        Vec3::new(vitesse * masse, 0.0, 0.0),
        Vec3::ZERO,
        false,
    );
}

/// Une entité-boîte de 1 × 2 × 1 blocs centrée en `x`.
fn entite(entity: u32, x: f64, vitesse: f32) -> EntityProxy {
    EntityProxy {
        entity,
        center: DVec3::new(x, 0.0, 0.0),
        half_extents: [0.5, 1.0, 0.5],
        velocity: Vec3::new(vitesse, 0.0, 0.0),
        shape: ProxyShape::Box,
        mass: 0.0,
    }
}

/// Un tick : les proxies déclarés pour la dimension 0, synchronisés, puis le pas ; rend
/// les événements du tick.
fn tick(driver: &mut SimDriver, proxies: Vec<EntityProxy>) -> Vec<PhysicsEvent> {
    driver.begin_tick();
    driver.set_entity_proxies(0, proxies);
    driver.sync_entity_proxies();
    driver.advance_all(1.0 / 20.0);
    driver.drain_events()
}

fn du_genre(events: &[PhysicsEvent], kind: u32) -> Vec<PhysicsEvent> {
    events
        .iter()
        .copied()
        .filter(|event| event.kind == kind)
        .collect()
}

fn vitesse_x(driver: &mut SimDriver, body: BodyId) -> f32 {
    driver.world_mut(0).unwrap().velocity(body).unwrap().x
}

#[test]
fn t304_une_assembly_heurte_une_entite_qui_s_identifie_dans_le_contact() {
    // ADR-123 §6 : l'assembly en `a`, l'entité en `b` — génération 0 —, node et matériau
    // nuls, et le bit CONTACT_OTHER_ENTITY ; la normale va de l'assembly vers l'entité.
    let mut driver = pilote();
    let corps = bille(&mut driver, 0.0);
    lancer(&mut driver, corps, 5.0);
    let mut debuts = Vec::new();
    for _ in 0..20 {
        debuts.extend(du_genre(
            &tick(&mut driver, vec![entite(42, 3.0, 0.0)]),
            event_kind::CONTACT_START,
        ));
        if !debuts.is_empty() {
            break;
        }
    }

    assert_eq!(debuts.len(), 1, "{debuts:?}");
    let choc = debuts[0];
    assert_eq!(choc.assembly_a, BILLE);
    assert_eq!(choc.assembly_b, Handle::new(42, 0));
    assert!(
        choc.assembly_b.is_absent(),
        "génération 0 : pas une assembly"
    );
    // L'entité n'a ni node ni matériau (ADR-149 D5).
    assert_eq!((choc.node_b, choc.material_b), (0, NO_MATERIAL));
    assert_ne!(choc.data & event_data::CONTACT_OTHER_ENTITY, 0);
    assert!(
        choc.normal[0] > 0.9,
        "normale vers l'entité : {:?}",
        choc.normal
    );
    assert!(
        (4.5..5.5).contains(&choc.relative_velocity),
        "vitesse d'approche : {}",
        choc.relative_velocity
    );
    // Masse infinie (§10.2) : la bille s'arrête contre l'entité.
    assert!(vitesse_x(&mut driver, corps) < 0.5);
}

#[test]
fn adr178_d6_un_proxy_de_masse_finie_ralentit_l_assembly_sans_l_arreter() {
    // La bille (environ 520 kg) heurte à 5 m/s un proxy de 80 kg : elle garde l'essentiel de son élan, au lieu de
    // s'arrêter comme contre un mur (t304, masse infinie).
    let mut driver = pilote();
    let corps = bille(&mut driver, 0.0);
    lancer(&mut driver, corps, 5.0);
    let mut touche = false;
    for _ in 0..20 {
        let joueur = EntityProxy {
            mass: 80.0,
            ..entite(42, 3.0, 0.0)
        };
        if !du_genre(&tick(&mut driver, vec![joueur]), event_kind::CONTACT_START).is_empty() {
            touche = true;
            break;
        }
    }
    assert!(touche, "la bille atteint le proxy");
    assert!(
        vitesse_x(&mut driver, corps) > 2.0,
        "elle garde son élan : {}",
        vitesse_x(&mut driver, corps)
    );
}

#[test]
fn adr178_d6_un_proxy_qui_change_de_masse_se_refait_et_reste_un() {
    let mut driver = pilote();
    tick(&mut driver, vec![entite(7, 3.0, 0.0)]);
    let finie = EntityProxy {
        mass: 80.0,
        ..entite(7, 3.0, 0.0)
    };
    tick(&mut driver, vec![finie]);
    tick(&mut driver, vec![entite(7, 3.0, 0.0)]);
    assert_eq!(driver.world_mut(0).unwrap().entity_proxy_count(), 1);
}

#[test]
fn t304_une_entite_en_mouvement_porte_sa_vitesse_dans_le_contact() {
    // Proxy à vitesse : né immobile, puis redéclaré chaque tick là où l'entité est arrivée
    // et à la vitesse qu'elle a prise, il heurte la bille au repos à 4 m/s et l'emporte.
    let mut driver = pilote();
    let corps = bille(&mut driver, 0.0);
    tick(&mut driver, vec![entite(9, 3.0, 0.0)]);
    let mut debuts = Vec::new();
    for t in 0..20 {
        let x = 3.0 - 4.0 * f64::from(t) / 20.0;
        debuts.extend(du_genre(
            &tick(&mut driver, vec![entite(9, x, -4.0)]),
            event_kind::CONTACT_START,
        ));
        if !debuts.is_empty() {
            break;
        }
    }

    assert_eq!(debuts.len(), 1, "{debuts:?}");
    assert_eq!(debuts[0].assembly_b, Handle::new(9, 0));
    assert!(
        (3.5..4.5).contains(&debuts[0].relative_velocity),
        "vitesse d'approche : {}",
        debuts[0].relative_velocity
    );
    assert!(
        vitesse_x(&mut driver, corps) < -3.0,
        "la bille est emportée"
    );
}

#[test]
fn t304_les_proxies_d_une_dimension_sont_remplaces_a_chaque_tick() {
    let mut driver = pilote();
    bille(&mut driver, 100.0);
    let compte = |driver: &mut SimDriver| driver.world_mut(0).unwrap().entity_proxy_count();

    tick(&mut driver, vec![entite(1, 0.0, 0.0), entite(2, 5.0, 0.0)]);
    assert_eq!(compte(&mut driver), 2);
    tick(&mut driver, vec![entite(2, 5.0, 0.0)]);
    assert_eq!(
        compte(&mut driver),
        1,
        "l'entité absente du flux perd son proxy"
    );
    tick(&mut driver, vec![]);
    assert_eq!(compte(&mut driver), 0);
    // Une entité déclarée deux fois n'a qu'un proxy ; une entité plate n'en a pas.
    let mut plate = entite(4, 9.0, 0.0);
    plate.half_extents[1] = 0.0;
    tick(
        &mut driver,
        vec![entite(3, 0.0, 0.0), entite(3, 1.0, 0.0), plate],
    );
    assert_eq!(compte(&mut driver), 1);
    // Une dimension sans monde n'en reçoit pas pour autant.
    driver.begin_tick();
    driver.set_entity_proxies(5, vec![entite(6, 0.0, 0.0)]);
    driver.sync_entity_proxies();
    assert!(driver.world_mut(5).is_none());
    assert_eq!(
        compte(&mut driver),
        0,
        "sans déclaration ce tick, plus aucun"
    );
}

#[test]
fn t304_un_proxy_ne_heurte_que_le_groupe_assembly() {
    // R-980 : `entity_proxy` ne filtre que `assembly` ; un débris le traverse.
    let mut driver = pilote();
    let corps = bille(&mut driver, 0.0);
    driver.world_mut(0).unwrap().set_collision_groups(
        corps,
        CollisionGroups::from_indices(&[ReservedGroup::Debris.bit()], &[0, 1, 2, 3, 4, 5, 6, 7]),
    );
    lancer(&mut driver, corps, 5.0);
    let mut events = Vec::new();
    for _ in 0..20 {
        events.extend(tick(&mut driver, vec![entite(42, 3.0, 0.0)]));
    }

    assert!(du_genre(&events, event_kind::CONTACT_START).is_empty());
    assert!((vitesse_x(&mut driver, corps) - 5.0).abs() < 1e-3);
}

#[test]
fn t304_un_proxy_redeclare_garde_ses_contacts() {
    // Le corps d'une entité redéclarée est reposé, pas recréé — y compris quand sa forme
    // change : son contact ne recommence pas à chaque tick.
    let mut driver = pilote();
    let corps = bille(&mut driver, 0.0);
    lancer(&mut driver, corps, 2.0);
    let mut debuts = 0;
    let mut fins = 0;
    for t in 0..40u8 {
        let mut proxy = entite(42, 2.0, 0.0);
        proxy.half_extents[1] = 1.0 + f32::from(t % 2) * 0.1;
        let events = tick(&mut driver, vec![proxy]);
        debuts += du_genre(&events, event_kind::CONTACT_START).len();
        fins += du_genre(&events, event_kind::CONTACT_END).len();
    }

    assert_eq!((debuts, fins), (1, 0));
}

#[test]
fn t304_la_fin_du_contact_d_une_entite_partie_porte_encore_son_entite() {
    let mut driver = pilote();
    let corps = bille(&mut driver, 0.0);
    lancer(&mut driver, corps, 2.0);
    let mut debuts = 0;
    for _ in 0..40 {
        debuts += du_genre(
            &tick(&mut driver, vec![entite(42, 2.0, 0.0)]),
            event_kind::CONTACT_START,
        )
        .len();
    }
    assert_eq!(debuts, 1, "la bille repose contre l'entité");

    // L'entité n'est plus déclarée : son proxy part, la fin du contact la nomme encore.
    let fins = du_genre(&tick(&mut driver, vec![]), event_kind::CONTACT_END);

    assert_eq!(fins.len(), 1, "{fins:?}");
    assert_eq!(fins[0].assembly_a, BILLE);
    assert_eq!(fins[0].assembly_b, Handle::new(42, 0));
    assert_ne!(fins[0].data & event_data::CONTACT_OTHER_ENTITY, 0);
}

#[test]
fn t304_une_entite_declaree_deux_fois_garde_sa_premiere_declaration() {
    // Loin de la bille la première fois, contre elle la seconde : seule la première compte.
    let mut driver = pilote();
    bille(&mut driver, 0.0);
    let mut debuts = 0;
    for _ in 0..5 {
        debuts += du_genre(
            &tick(&mut driver, vec![entite(3, 10.0, 0.0), entite(3, 1.0, 0.0)]),
            event_kind::CONTACT_START,
        )
        .len();
    }

    assert_eq!(debuts, 0);
    assert_eq!(driver.world_mut(0).unwrap().entity_proxy_count(), 1);
}

/// Une bille posée sur le dessus d'une entité-boîte, sous la pesanteur, lancée de côté pour que le contact frotte.
fn bille_posee_sur_entite() -> SimDriver {
    let mut driver = SimDriver::new();
    driver.apply_dimension_env(0, Vec3::new(0.0, -9.81, 0.0), Vec3::ZERO, None);
    let corps = bille(&mut driver, 0.0);
    driver.begin_tick();
    let mut dessous = entite(42, 0.0, 0.0);
    dessous.center = DVec3::new(0.0, -1.5, 0.0);
    driver.set_entity_proxies(0, vec![dessous]);
    driver.sync_entity_proxies();
    lancer(&mut driver, corps, 0.5);
    driver
}

#[test]
fn t304_une_poussee_continue_rapporte_tout_ce_que_l_entite_porte_sur_le_tick() {
    // ADR-123 §7 : la bille (523,6 kg) pèse sur l'entité à chaque sous-pas. Le tick de trois sous-pas rapporte ses
    // trois impulsions, m·g·3/60 ≈ 257 N·s, et non celle d'un seul sous-pas.
    let mut driver = bille_posee_sur_entite();
    let mut dernier = Vec::new();
    for _ in 0..8 {
        driver.advance_all(3.0 / 60.0);
        dernier = du_genre(&driver.drain_events(), event_kind::CONTACT_IMPULSE);
    }

    assert_eq!(dernier.len(), 1, "{dernier:?}");
    let poids = core::f32::consts::PI / 6.0 * 1000.0 * 9.81 * 3.0 / 60.0;
    assert!(
        (dernier[0].impulse - poids).abs() < 0.05 * poids,
        "impulsion du tick : {} N·s, attendu {poids}",
        dernier[0].impulse
    );
}

#[test]
fn t304_les_impulsions_d_une_entite_s_additionnent_sur_les_sous_pas_le_detail_du_plus_fort() {
    // ADR-146, précision du 2026-11-26 : toujours un CONTACT_IMPULSE par paire et par tick ; avec une entité, ses
    // impulsions, normale et tangentielle, sont la somme de celles des sous-pas, et le reste est le détail du plus
    // fort. Le témoin avance d'un sous-pas à la fois sur la même scène.
    let mut temoin = bille_posee_sur_entite();
    let mut driver = bille_posee_sur_entite();
    let mut ticks_a_plusieurs_sous_pas = 0;
    let mut tangentielle = 0.0;
    for tick in 0..8 {
        let mut par_sous_pas = Vec::new();
        for _ in 0..3 {
            temoin.advance_all(1.0 / 60.0);
            par_sous_pas.extend(du_genre(
                &temoin.drain_events(),
                event_kind::CONTACT_IMPULSE,
            ));
        }
        driver.advance_all(3.0 / 60.0);
        let impulsions = du_genre(&driver.drain_events(), event_kind::CONTACT_IMPULSE);

        let attendu = par_sous_pas
            .iter()
            .copied()
            .reduce(|fort, suivant| {
                if suivant.impulse > fort.impulse {
                    suivant
                } else {
                    fort
                }
            })
            .map(|fort| PhysicsEvent {
                impulse: par_sous_pas.iter().map(|e| e.impulse).sum(),
                tangent_impulse: par_sous_pas.iter().map(|e| e.tangent_impulse).sum(),
                ..fort
            });
        assert_eq!(
            impulsions,
            attendu.into_iter().collect::<Vec<_>>(),
            "tick {tick} : sous-pas {par_sous_pas:?}"
        );
        if par_sous_pas.len() > 1 {
            ticks_a_plusieurs_sous_pas += 1;
        }
        tangentielle += impulsions.iter().map(|e| e.tangent_impulse).sum::<f32>();
    }
    // Sans plusieurs sous-pas au contact, la somme ne se distinguerait pas du plus fort ; sans frottement, la somme
    // tangentielle ne serait pas éprouvée.
    assert!(ticks_a_plusieurs_sous_pas > 0);
    assert!(tangentielle > 0.0, "la scène doit frotter");
}

/// Le premier `CONTACT_START` d'une bille lancée à 5 m/s vers une entité en x = 3.
fn premier_choc(driver: &mut SimDriver) -> PhysicsEvent {
    for _ in 0..20 {
        let debuts = du_genre(
            &tick(driver, vec![entite(42, 3.0, 0.0)]),
            event_kind::CONTACT_START,
        );
        if let Some(choc) = debuts.first() {
            return *choc;
        }
    }
    panic!("aucun choc en une seconde");
}

#[test]
fn t304_l_assembly_reste_en_a_quel_que_soit_l_ordre_des_corps() {
    // Que le proxy naisse après la bille ou avant elle, rapier peut ranger la paire dans un
    // sens ou dans l'autre : l'événement, lui, garde l'assembly en `a`, l'entité en `b`, et
    // la normale de l'assembly vers l'entité.
    let mut apres = pilote();
    let corps = bille(&mut apres, 0.0);
    lancer(&mut apres, corps, 5.0);
    let choc_apres = premier_choc(&mut apres);

    let mut avant = pilote();
    tick(&mut avant, vec![entite(42, 3.0, 0.0)]);
    let corps = bille(&mut avant, 0.0);
    lancer(&mut avant, corps, 5.0);
    let choc_avant = premier_choc(&mut avant);

    for choc in [choc_apres, choc_avant] {
        assert_eq!(
            (choc.assembly_a, choc.assembly_b),
            (BILLE, Handle::new(42, 0))
        );
        assert!(choc.normal[0] > 0.9, "{:?}", choc.normal);
        assert_ne!(choc.data & event_data::CONTACT_OTHER_ENTITY, 0);
    }
}

#[test]
fn t304_une_entite_qui_change_de_forme_la_change_en_place() {
    // Une entité qui grandit jusqu'à la bille au repos la touche : sa nouvelle forme est
    // celle du proxy existant, sans nouveau proxy.
    let mut driver = pilote();
    bille(&mut driver, 0.0);
    let mut petite = entite(42, 2.0, 0.0);
    petite.half_extents[0] = 0.5;
    assert!(du_genre(&tick(&mut driver, vec![petite]), event_kind::CONTACT_START).is_empty());

    let mut grande = petite;
    grande.half_extents[0] = 1.6;
    let mut debuts = Vec::new();
    for _ in 0..5 {
        debuts.extend(du_genre(
            &tick(&mut driver, vec![grande]),
            event_kind::CONTACT_START,
        ));
    }

    assert_eq!(debuts.len(), 1, "{debuts:?}");
    assert_eq!(debuts[0].assembly_b, Handle::new(42, 0));
    assert_eq!(driver.world_mut(0).unwrap().entity_proxy_count(), 1);
}
