//! T-630..T-634, moitié native — les modèles des cinq definitions de démonstration (R-1790)
//! compilent par la chaîne d'assets du jeu, puis se chargent (ADR-134, option A).
//!
//! L'autre moitié, la definition elle-même (embarquée, filtrée par `content.examples`, schéma 1
//! sans faute), est `ExampleDefinitionsTest` côté Java. Celle-ci tourne ici parce que `cargo test`
//! tourne partout, alors qu'un test JUnit qui exigerait la bibliothèque native serait sauté là où
//! elle n'est pas encore construite.
//!
//! La compilation est celle du jeu (R-830) : `compile_source` appelle `ax_asset::compile::compile`,
//! `validate_container` relit le conteneur comme le chargement du jeu. Au-delà, chaque test vérifie
//! que le modèle est découpé et annoté pour les systèmes qu'il exercera (ADR-134) : rôles de node
//! (roues, siège, sockets, ancrages de tissu), colliders, et nodes de travail jamais rendus (R-910).

use ax_asset::a3d::{decode_nodes, A3dFile, A3dLimits, SectionTag};
use ax_asset::import::SourceFormat;
use ax_cli::{compile_source, max_compiled_bytes, options, validate_container};
use ax_model::dm::scene::node_flags;
use std::path::PathBuf;

/// Un node du conteneur compilé : son nom et ses drapeaux.
struct Node {
    name: String,
    flags: u32,
}

/// Ce que la compilation d'un exemple a produit.
struct Compiled {
    colliders: usize,
    nodes: Vec<Node>,
}

impl Compiled {
    /// Les nodes qui portent un drapeau, par nom, dans l'ordre du conteneur.
    fn with(&self, flag: u32) -> Vec<&str> {
        self.nodes
            .iter()
            .filter(|node| node.flags & flag != 0)
            .map(|node| node.name.as_str())
            .collect()
    }

    /// Les drapeaux d'un node nommé.
    fn flags(&self, name: &str) -> u32 {
        self.nodes
            .iter()
            .find(|node| node.name == name)
            .unwrap_or_else(|| panic!("node « {name} » absent du modèle"))
            .flags
    }

    fn visible(&self, name: &str) -> bool {
        self.flags(name) & node_flags::VISIBLE != 0
    }
}

/// Le modèle embarqué d'un exemple, tel que le JAR le porte.
fn model(name: &str) -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../java/axion-mod/src/main/resources/data/axion/axion/models/example")
        .join(format!("{name}.glb"));
    std::fs::read(&path).unwrap_or_else(|error| panic!("{} : {error}", path.display()))
}

/// Compile un exemple comme le jeu, recharge le conteneur, et en relit les nodes.
fn compile(name: &str) -> Compiled {
    let source = model(name);
    let options = options(&format!("{name}.glb"), &source, false);
    // Un GLB porte tout : aucun fichier voisin à résoudre.
    let compiled = compile_source(&source, SourceFormat::Glb, |_| None, &options)
        .unwrap_or_else(|error| panic!("{name} : compilation refusée — {error}"));
    assert!(
        compiled.warnings.is_empty(),
        "{name} : un exemple compile sans avertissement — {:?}",
        compiled.warnings
    );

    let limits = A3dLimits::new(max_compiled_bytes());
    validate_container(&compiled.bytes, limits)
        .unwrap_or_else(|error| panic!("{name} : le conteneur ne se chargerait pas — {error}"));

    let file = A3dFile::open(&compiled.bytes, limits).expect("conteneur");
    let section = file
        .section(SectionTag::NODE)
        .expect("section NODE lisible")
        .expect("section NODE présente");
    let table = decode_nodes(&section).expect("nodes");
    let nodes = table
        .nodes
        .iter()
        .zip(table.names)
        .map(|(node, name)| Node {
            name,
            flags: node.flags,
        })
        .collect();
    Compiled {
        colliders: compiled.collider_count,
        nodes,
    }
}

#[test]
fn t630_crate_compile_et_se_charge() {
    let crate_ = compile("crate");
    assert_eq!(crate_.colliders, 1);
    assert!(crate_.visible("caisse") && crate_.visible("couvercle"));
    assert!(
        !crate_.visible("caisse_collider"),
        "R-910 : un collider n'est pas rendu"
    );
}

#[test]
fn t631_buggy_compile_et_se_charge() {
    let buggy = compile("buggy");
    assert_eq!(buggy.colliders, 1);
    assert_eq!(
        buggy.with(node_flags::WHEEL),
        [
            "roue_arriere_droite",
            "roue_arriere_gauche",
            "roue_avant_droite",
            "roue_avant_gauche"
        ]
    );
    assert_eq!(buggy.with(node_flags::SEAT), ["siege_conducteur"]);
    assert_eq!(buggy.with(node_flags::SOCKET), ["attelage"]);
    for part in ["chassis", "carrosserie", "capot", "volant"] {
        assert!(buggy.visible(part), "{part} est rendu");
    }
    assert!(
        !buggy.visible("attelage"),
        "R-910 : un socket n'est pas rendu"
    );
    assert!(!buggy.visible("chassis_collider"));
}

#[test]
fn t632_flag_compile_et_se_charge() {
    let flag = compile("flag");
    assert_eq!(flag.colliders, 2);
    assert_eq!(
        flag.with(node_flags::CLOTH_ANCHOR),
        ["ancrage_bas", "ancrage_haut"]
    );
    for anchor in ["ancrage_bas", "ancrage_haut"] {
        assert!(!flag.visible(anchor), "R-910 : un ancrage n'est pas rendu");
    }
    assert!(flag.visible("drapeau") && flag.visible("mat") && flag.visible("socle_beton"));
}

#[test]
fn t633_barrier_compile_et_se_charge() {
    let barrier = compile("barrier");
    assert_eq!(barrier.colliders, 3, "un collider par part");
    for part in ["glissiere", "poteau_gauche", "poteau_droit"] {
        assert!(barrier.visible(part), "{part} est rendu");
        assert!(!barrier.visible(&format!("{part}_collider")));
    }
}

#[test]
fn t634_winch_rope_compile_et_se_charge() {
    let winch = compile("winch_rope");
    assert_eq!(winch.colliders, 1);
    assert_eq!(winch.with(node_flags::SOCKET), ["sortie_cable"]);
    assert!(
        !winch.visible("sortie_cable"),
        "R-910 : un socket n'est pas rendu"
    );
    for part in ["socle", "tambour", "cable_enroule", "manivelle", "crochet"] {
        assert!(winch.visible(part), "{part} est rendu");
    }
}
