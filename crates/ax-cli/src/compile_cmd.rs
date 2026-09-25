//! Commande `compile` : une source, un conteneur A3D.
//!
//! La compilation elle-même est celle du jeu : [`ax_asset::compile::compile`]
//! (R-830). Ce module ne fait que réunir ses entrées — plafonds tirés du
//! registre de configuration, identifiants dérivés de la source — et présenter
//! sa sortie.

use crate::CliError;
use ax_asset::collider::ColliderMode;
use ax_asset::compile::{compile, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::optimize::LodOptions;
use ax_model::config::{find, ConfigScope, ConfigValue};

pub use ax_asset::compile::CompiledAsset as CompiledOutput;

/// Base de l'empreinte FNV-1a 64 bits.
const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;

/// Facteur premier de FNV-1a 64 bits.
const FNV_PRIME: u64 = 0x0000_0100_0000_01b3;

/// Empreinte FNV-1a 64 bits d'une suite d'octets.
///
/// La même que celle des noms (DM-01) ; elle sert ici à donner à l'en-tête un
/// `source_hash` déterministe. Une source binaire — un `.glb`, un `.stl` — n'est
/// pas de l'UTF-8, d'où le hachage sur les octets bruts.
#[must_use]
pub fn source_hash(bytes: &[u8]) -> u64 {
    let mut hash = FNV_OFFSET;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}

/// Identifiant d'asset dérivé d'un nom de source.
///
/// Le jeu le reçoit de l'orchestrateur, qui le calcule depuis `namespace:path`.
/// La CLI n'a pas de namespace : elle prend l'empreinte du nom de fichier, ce
/// qui suffit à un `asset_id` qui n'est ici que de la métadonnée d'en-tête.
#[must_use]
pub fn asset_id_of(source_name: &str) -> u64 {
    source_hash(source_name.as_bytes())
}

/// Plafond de taille d'une source, lu dans le registre (`assets.max_source_bytes`).
///
/// Le registre est la source unique de cette valeur (R-533) : la coder en dur
/// ici en créerait une seconde, qui divergerait à la première modification.
#[must_use]
pub fn max_source_bytes() -> u64 {
    match find(ConfigScope::Common, "assets.max_source_bytes").map(|option| option.default) {
        Some(ConfigValue::Int(value)) if value >= 0 => value as u64,
        // La clé est déclarée en entier dans les défauts : son absence ou un
        // autre type serait un défaut interne d'`ax-model`, pas une entrée
        // utilisateur.
        _ => unreachable!("assets.max_source_bytes doit être un entier positif du registre"),
    }
}

/// Compose les options d'une compilation en ligne de commande.
#[must_use]
pub fn options(source_name: &str, source: &[u8], static_body: bool) -> CompileOptions {
    CompileOptions {
        asset_id: asset_id_of(source_name),
        source_hash: source_hash(source),
        limits: ImportLimits::new(max_source_bytes()),
        // R-160 : `TriMesh` et `Heightfield` ne sont interdits que sur un body
        // dynamique. `--static-body` sert à compiler la géométrie du monde.
        dynamic_body: !static_body,
        lod: LodOptions::DEFAULT,
        // La CLI ne réclame pas d'auto-collider : la génération suit les extras
        // de node de la source (C-32).
        collider_mode: ColliderMode::None,
    }
}

/// Compile une source déjà lue en conteneur A3D.
///
/// `resolve` fournit les fichiers voisins que la source référence (le `.mtl`
/// d'un OBJ, un buffer externe d'un glTF) ; il rend `None` pour ce qui est
/// absent ou hors de portée, ce que l'importeur traite comme un refus propre.
///
/// # Errors
///
/// [`CliError::Compile`] si la source est refusée à l'import, à la validation
/// ou à l'écriture (C-21..C-24).
pub fn compile_source(
    source: &[u8],
    format: SourceFormat,
    resolve: impl FnMut(&str) -> Option<Vec<u8>>,
    options: &CompileOptions,
) -> Result<CompiledOutput, CliError> {
    compile(source, format, options, resolve).map_err(CliError::Compile)
}

/// Décrit une compilation réussie, une ligne par fait.
#[must_use]
pub fn summary(compiled: &CompiledOutput, source_len: usize, output: &str) -> Vec<String> {
    let mut lines = vec![
        format!("compilé : {output}"),
        format!(
            "  {} octet(s) source → {} octet(s) A3D",
            source_len,
            compiled.bytes.len()
        ),
        format!(
            "  {} sommet(s), {} mesh(s)",
            compiled.vertex_count, compiled.mesh_count
        ),
    ];
    if let Some(bounds) = compiled.bounds {
        lines.push(format!(
            "  boîte englobante : [{:.3}, {:.3}, {:.3}] → [{:.3}, {:.3}, {:.3}]",
            bounds.min[0],
            bounds.min[1],
            bounds.min[2],
            bounds.max[0],
            bounds.max[1],
            bounds.max[2]
        ));
    }
    // R-912 : les avertissements se journalisent une fois. La CLI les montre à
    // l'auteur, qui est précisément qui peut les corriger.
    for warning in &compiled.warnings {
        lines.push(format!("  avertissement : {warning}"));
    }
    lines
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::scene::name_hash;

    const CUBE_OBJ: &str = "\
o cube
v -1.0 -1.0 -1.0
v  1.0 -1.0 -1.0
v  1.0  1.0 -1.0
v -1.0  1.0 -1.0
vt 0.0 0.0
vt 1.0 0.0
vt 1.0 1.0
vt 0.0 1.0
vn 0.0 0.0 -1.0
f 1/1/1 2/2/1 3/3/1
f 1/1/1 3/3/1 4/4/1
";

    #[test]
    fn t580_l_empreinte_de_source_est_fnv1a_64() {
        // La même fonction que les noms, vérifiée sur les mêmes vecteurs.
        assert_eq!(source_hash(b""), 0xcbf2_9ce4_8422_2325);
        assert_eq!(source_hash(b"a"), 0xaf63_dc4c_8601_ec8c);
        // Sur une chaîne, elle coïncide avec `name_hash`.
        assert_eq!(source_hash("cube.obj".as_bytes()), name_hash("cube.obj"));
    }

    #[test]
    fn t580_le_plafond_vient_du_registre() {
        // Valeur par défaut de `assets.max_source_bytes` (128 Mio).
        assert_eq!(max_source_bytes(), 134_217_728);
    }

    #[test]
    fn t580_compile_produit_un_conteneur_relisible() {
        let opts = options("cube.obj", CUBE_OBJ.as_bytes(), false);
        let compiled =
            compile_source(CUBE_OBJ.as_bytes(), SourceFormat::Obj, |_| None, &opts).unwrap();

        assert_eq!(compiled.vertex_count, 4);
        assert_eq!(compiled.mesh_count, 1);

        // Relisible par le lecteur A3D, avec l'en-tête qu'on lui a donné.
        let file =
            ax_asset::a3d::A3dFile::open(&compiled.bytes, ax_asset::a3d::A3dLimits::new(1 << 20))
                .expect("relecture");
        assert_eq!(file.header().asset_id, asset_id_of("cube.obj"));
        assert_eq!(file.header().source_hash, source_hash(CUBE_OBJ.as_bytes()));
    }

    #[test]
    fn t580_la_compilation_cli_est_deterministe() {
        // R-830, T-213 : deux compilations d'une même source donnent le même
        // conteneur, au bit près.
        let opts = options("cube.obj", CUBE_OBJ.as_bytes(), false);
        let une = compile_source(CUBE_OBJ.as_bytes(), SourceFormat::Obj, |_| None, &opts).unwrap();
        let deux = compile_source(CUBE_OBJ.as_bytes(), SourceFormat::Obj, |_| None, &opts).unwrap();
        assert_eq!(une.bytes, deux.bytes);
    }

    #[test]
    fn t580_une_source_illisible_est_refusee() {
        let opts = options("brisé.obj", b"ceci n'est pas un obj valide\x00\xff", false);
        // Un OBJ non UTF-8 est refusé à l'import, pas paniqué.
        let refus = compile_source(
            b"\xff\xfe pas de l'utf8",
            SourceFormat::Obj,
            |_| None,
            &opts,
        )
        .unwrap_err();
        assert!(matches!(refus, CliError::Compile(_)));
        assert_eq!(refus.exit_code(), 1);
    }
}
