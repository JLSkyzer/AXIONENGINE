//! T-590 — R-893 : le format A3D ne change pas par accident.
//!
//! R-893 veut qu'une évolution du format s'accompagne d'un test de migration et
//! d'un fichier d'exemple de l'ancienne version. Le voici pour la version 1.1,
//! la première : le fichier est **versionné dans le dépôt**, et ce test le relit
//! puis vérifie que l'écrivain le reproduit encore octet pour octet.
//!
//! Un changement du format fait donc échouer ce test. Ce n'est pas un obstacle
//! à franchir : c'est la question « la version a-t-elle été incrémentée, la
//! migration écrite, et l'ancien fichier conservé ? » posée au bon moment.

use ax_asset::a3d::{A3dFile, A3dLimits, A3dWriter, SectionTag, VERSION_MAJOR, VERSION_MINOR};

/// Fichier de référence, tel qu'il est versionné.
const FIXTURE: &[u8] = include_bytes!("fixtures/a3d/v1.1-minimal.a3d");

const LIMITS: A3dLimits = A3dLimits::new(1 << 20);

/// Reconstruit le fichier de référence.
///
/// Toute valeur y est fixe : un horodatage ou un identifiant tiré au hasard
/// rendrait la comparaison impossible, et c'est cette comparaison qui fait tout
/// l'intérêt du fichier.
fn reference() -> Vec<u8> {
    let mut writer = A3dWriter::new(0x0102_0304_0506_0708, 0x1112_1314_1516_1718, 1);
    writer
        .section(SectionTag::NODE, b"racine\0chassis\0roue_avant_gauche\0")
        .expect("NODE");
    writer
        .compressed_section(SectionTag::GEOM, &vec![0x5A; 4096])
        .expect("GEOM");
    writer
        .section(SectionTag::META, b"axion:test/minimal")
        .expect("META");
    writer.finish().expect("écriture")
}

#[test]
fn t590_le_fichier_de_reference_se_relit() {
    let file = A3dFile::open(FIXTURE, LIMITS).expect("fichier de référence illisible");

    assert_eq!(file.header().version_major, VERSION_MAJOR);
    assert_eq!(file.header().version_minor, VERSION_MINOR);
    assert_eq!(file.header().asset_id, 0x0102_0304_0506_0708);
    assert_eq!(file.header().source_hash, 0x1112_1314_1516_1718);
    assert_eq!(file.header().compiler_version, 1);
    assert_eq!(file.header().section_count, 3);

    assert_eq!(
        file.section(SectionTag::NODE).expect("NODE"),
        Some(b"racine\0chassis\0roue_avant_gauche\0".to_vec())
    );
    assert_eq!(
        file.section(SectionTag::GEOM).expect("GEOM"),
        Some(vec![0x5A; 4096])
    );
    assert_eq!(
        file.section(SectionTag::META).expect("META"),
        Some(b"axion:test/minimal".to_vec())
    );
}

#[test]
fn t591_l_ecrivain_reproduit_le_fichier_de_reference() {
    let produit = reference();

    assert_eq!(
        produit.len(),
        FIXTURE.len(),
        "la taille du format a changé : {} octets contre {}",
        produit.len(),
        FIXTURE.len()
    );

    if produit != FIXTURE {
        let divergence = produit
            .iter()
            .zip(FIXTURE)
            .position(|(left, right)| left != right)
            .unwrap_or(0);
        panic!(
            "le format A3D a changé, première divergence à l'octet {divergence} \
             (attendu {:#04x}, produit {:#04x}). Si le changement est voulu : \
             incrémenter la version, écrire la migration, conserver l'ancien \
             fichier — R-893.",
            FIXTURE[divergence], produit[divergence]
        );
    }
}
