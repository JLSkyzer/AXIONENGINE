//! R-562 — `COMPILER_VERSION` est incrémentée à toute modification qui affecte la sortie du
//! compilateur ; vérifié en CI.
//!
//! La parité des deux constantes, Rust et Java (job `compiler-version`), ne dit pas si la sortie a
//! changé. Ce test compile un corpus fixe et compare l'empreinte de chaque conteneur A3D à celle que
//! `tests/golden/compiler-v{N}-{os}-{arch}.txt` fige pour la version courante : une sortie qui change
//! sans que la version change le fait échouer.
//!
//! Une empreinte par plateforme : la sortie n'est identique au bit près qu'à plateforme égale
//! (ADR-106, ratifié). Le corpus est construit ici, à partir d'entiers et de fractions exactes en
//! binaire, pour que l'**entrée** soit la même partout — seule la sortie peut différer.
//!
//! Pour figer les empreintes d'une nouvelle version : `AXION_R562_BLESS=1 cargo test -p ax-asset
//! --test compiler_fingerprints`, sur chaque plateforme. Un fichier absent fait échouer le test en
//! affichant le contenu attendu.

use ax_asset::compile::{compile, source_hash, CompileOptions, COMPILER_VERSION};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::options::AssetOptions;
use std::fmt::Write as _;
use std::path::PathBuf;

/// Côtés de la grille du corpus : assez de sommets pour le cache de sommets et les LOD.
const GRID: u32 = 24;

/// Hauteur d'un sommet de la grille : un multiple de 0,25, exact en binaire.
fn height(i: u32, j: u32) -> f32 {
    ((i * 7 + j * 13) % 11) as f32 * 0.25
}

/// Positions, UV et triangles de la grille, en valeurs exactes.
struct Grid {
    positions: Vec<[f32; 3]>,
    uvs: Vec<[f32; 2]>,
    triangles: Vec<[u32; 3]>,
}

fn grid() -> Grid {
    let mut positions = Vec::new();
    let mut uvs = Vec::new();
    for j in 0..=GRID {
        for i in 0..=GRID {
            positions.push([i as f32 * 0.5, height(i, j), j as f32 * 0.5]);
            uvs.push([i as f32 * 0.125, j as f32 * 0.125]);
        }
    }
    let row = GRID + 1;
    let mut triangles = Vec::new();
    for j in 0..GRID {
        for i in 0..GRID {
            let a = j * row + i;
            let b = a + 1;
            let c = a + row;
            let d = c + 1;
            triangles.push([a, c, b]);
            triangles.push([b, c, d]);
        }
    }
    Grid {
        positions,
        uvs,
        triangles,
    }
}

fn obj() -> Vec<u8> {
    let Grid {
        positions,
        uvs,
        triangles,
    } = grid();
    let mut text = String::from("mtllib grille.mtl\no grille\nusemtl sol\n");
    for p in &positions {
        writeln!(text, "v {} {} {}", p[0], p[1], p[2]).unwrap();
    }
    for uv in &uvs {
        writeln!(text, "vt {} {}", uv[0], uv[1]).unwrap();
    }
    for t in &triangles {
        let [a, b, c] = t.map(|index| index + 1);
        writeln!(text, "f {a}/{a} {b}/{b} {c}/{c}").unwrap();
    }
    text.into_bytes()
}

const MTL: &[u8] = b"newmtl sol\nKd 0.5 0.375 0.25\n";

fn stl() -> Vec<u8> {
    let Grid {
        positions,
        triangles,
        ..
    } = grid();
    let mut bytes = vec![0u8; 80];
    bytes.extend_from_slice(&(triangles.len() as u32).to_le_bytes());
    for t in &triangles {
        bytes.extend_from_slice(&[0u8; 12]);
        for index in t {
            for value in positions[*index as usize] {
                bytes.extend_from_slice(&value.to_le_bytes());
            }
        }
        bytes.extend_from_slice(&0u16.to_le_bytes());
    }
    bytes
}

/// Le buffer glTF de la grille : positions, UV, puis indices `u16`.
fn gltf_buffer() -> (Vec<u8>, usize, usize, usize) {
    let Grid {
        positions,
        uvs,
        triangles,
    } = grid();
    let mut bytes = Vec::new();
    for p in &positions {
        for value in p {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
    }
    let uv_offset = bytes.len();
    for uv in &uvs {
        for value in uv {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
    }
    let index_offset = bytes.len();
    for t in &triangles {
        for index in t {
            bytes.extend_from_slice(&(*index as u16).to_le_bytes());
        }
    }
    let end = bytes.len();
    (bytes, uv_offset, index_offset, end)
}

/// Le JSON glTF de la grille : un matériau à carte de normales, dont l'image n'est qu'un chemin
/// (R-532) mais qui fait générer les tangentes MikkTSpace.
fn gltf_json(buffer_uri: Option<&str>, root_extras: &str) -> String {
    let (bytes, uv_offset, index_offset, end) = gltf_buffer();
    let count = (GRID + 1) * (GRID + 1);
    let index_count = GRID * GRID * 6;
    let max_height = (0..=GRID)
        .flat_map(|j| (0..=GRID).map(move |i| height(i, j)))
        .fold(0.0f32, f32::max);
    let uri = buffer_uri.map_or(String::new(), |uri| format!(r#","uri":"{uri}""#));
    format!(
        concat!(
            r#"{{"asset":{{"version":"2.0"}},"scene":0,"scenes":[{{"nodes":[0]}}],"#,
            r#""nodes":[{{"name":"grille","mesh":0}}],"#,
            r#""meshes":[{{"primitives":[{{"attributes":{{"POSITION":0,"TEXCOORD_0":1}},"#,
            r#""indices":2,"material":0}}]}}],"#,
            r#""materials":[{{"name":"sol","normalTexture":{{"index":0}},"#,
            r#""pbrMetallicRoughness":{{"baseColorFactor":[0.5,0.375,0.25,1.0]}}}}],"#,
            r#""textures":[{{"source":0}}],"images":[{{"uri":"normale.png"}}],"#,
            r#""accessors":[{{"bufferView":0,"componentType":5126,"count":{count},"type":"VEC3","#,
            r#""min":[0.0,0.0,0.0],"max":[{side},{max_height},{side}]}},"#,
            r#"{{"bufferView":1,"componentType":5126,"count":{count},"type":"VEC2"}},"#,
            r#"{{"bufferView":2,"componentType":5123,"count":{index_count},"type":"SCALAR"}}],"#,
            r#""bufferViews":[{{"buffer":0,"byteOffset":0,"byteLength":{uv_offset}}},"#,
            r#"{{"buffer":0,"byteOffset":{uv_offset},"byteLength":{uv_length}}},"#,
            r#"{{"buffer":0,"byteOffset":{index_offset},"byteLength":{index_length}}}],"#,
            r#""buffers":[{{"byteLength":{byte_length}{uri}}}],"#,
            r#""extras":{root_extras}}}"#,
        ),
        count = count,
        side = GRID as f32 * 0.5,
        max_height = max_height,
        index_count = index_count,
        uv_offset = uv_offset,
        uv_length = index_offset - uv_offset,
        index_offset = index_offset,
        index_length = end - index_offset,
        byte_length = bytes.len(),
        uri = uri,
        root_extras = root_extras,
    )
}

fn gltf(root_extras: &str) -> Vec<u8> {
    let (bytes, ..) = gltf_buffer();
    let uri = format!("data:application/octet-stream;base64,{}", base64(&bytes));
    gltf_json(Some(&uri), root_extras).into_bytes()
}

fn glb() -> Vec<u8> {
    let (mut bin, ..) = gltf_buffer();
    let mut json = gltf_json(None, "{}").into_bytes();
    while !json.len().is_multiple_of(4) {
        json.push(b' ');
    }
    while !bin.len().is_multiple_of(4) {
        bin.push(0);
    }
    let total = 12 + 8 + json.len() + 8 + bin.len();
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(b"glTF");
    out.extend_from_slice(&2u32.to_le_bytes());
    out.extend_from_slice(&(total as u32).to_le_bytes());
    out.extend_from_slice(&(json.len() as u32).to_le_bytes());
    out.extend_from_slice(b"JSON");
    out.extend_from_slice(&json);
    out.extend_from_slice(&(bin.len() as u32).to_le_bytes());
    out.extend_from_slice(b"BIN\0");
    out.extend_from_slice(&bin);
    out
}

/// glTF dont l'unique node porte `role=collider`, `shape=convex` : un tétraèdre, positions f32
/// puis indices u16.
const GLTF_CONVEX: &str = concat!(
    r#"{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"#,
    r#""nodes":[{"name":"coque","mesh":0,"extras":{"axion":"#,
    r#"{"role":"collider","shape":"convex"}}}],"#,
    r#""meshes":[{"name":"tetra","primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"#,
    r#""accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3","#,
    r#""min":[0.0,0.0,0.0],"max":[1.0,1.0,1.0]},"#,
    r#"{"bufferView":1,"componentType":5123,"count":12,"type":"SCALAR"}],"#,
    r#""bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48},"#,
    r#"{"buffer":0,"byteOffset":48,"byteLength":24}],"#,
    r#""buffers":[{"byteLength":72,"uri":"data:application/octet-stream;base64,"#,
    "AAAAAAAAAAAAAAAAAACAPwAAAAAAAAAAAAAAAAAAgD8AAAAAAAAAAAAAAAAAAIA/AAACAAEAAAABAAMAAAADAAIAAQACAAMA",
    r#""}]}"#,
);

fn base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let b = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let n = (u32::from(b[0]) << 16) | (u32::from(b[1]) << 8) | u32::from(b[2]);
        for (k, shift) in [18u32, 12, 6, 0].into_iter().enumerate() {
            if k <= chunk.len() {
                out.push(ALPHABET[((n >> shift) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

/// Le corpus : nom, format, octets de la source.
fn corpus() -> Vec<(&'static str, SourceFormat, Vec<u8>)> {
    vec![
        ("grille.obj", SourceFormat::Obj, obj()),
        ("grille.stl", SourceFormat::Stl, stl()),
        ("grille.gltf", SourceFormat::Gltf, gltf("{}")),
        (
            "grille-x2.gltf",
            SourceFormat::Gltf,
            gltf(r#"{"axion":{"schema":1,"scale":2}}"#),
        ),
        ("grille.glb", SourceFormat::Glb, glb()),
        (
            "coque.gltf",
            SourceFormat::Gltf,
            GLTF_CONVEX.as_bytes().to_vec(),
        ),
    ]
}

/// Les empreintes du corpus, une ligne par source : nom, octets, FNV-1a 64 de l'A3D.
fn fingerprints() -> String {
    let mut out = String::new();
    for (name, format, source) in corpus() {
        let options = CompileOptions {
            asset_id: source_hash(name.as_bytes()),
            source_hash: source_hash(&source),
            limits: ImportLimits::new(1 << 24),
            dynamic_body: true,
            authored: AssetOptions::DEFAULT,
        };
        let compiled = compile(&source, format, &options, |reference| {
            (reference == "grille.mtl").then(|| MTL.to_vec())
        })
        .unwrap_or_else(|error| panic!("{name} : compilation refusée — {error}"));
        writeln!(
            out,
            "{name} {} {:016x}",
            compiled.bytes.len(),
            source_hash(&compiled.bytes)
        )
        .unwrap();
    }
    out
}

fn platform() -> String {
    format!("{}-{}", std::env::consts::OS, std::env::consts::ARCH)
}

fn golden() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("golden")
        .join(format!("compiler-v{COMPILER_VERSION}-{}.txt", platform()))
}

/// Retire l'en-tête de commentaires d'un fichier d'empreintes.
fn body(text: &str) -> String {
    text.lines()
        .filter(|line| !line.starts_with('#') && !line.trim().is_empty())
        .map(|line| format!("{line}\n"))
        .collect()
}

#[test]
fn r562_la_sortie_du_compilateur_ne_change_pas_sans_que_compiler_version_change() {
    let obtained = fingerprints();
    let path = golden();
    let header = format!(
        "# R-562 : empreintes de la sortie du compilateur d'assets, COMPILER_VERSION {COMPILER_VERSION}, \
         plateforme {}.\n# Une ligne par source du corpus de `compiler_fingerprints.rs` : nom, octets, \
         FNV-1a 64 du conteneur A3D.\n",
        platform()
    );

    if std::env::var_os("AXION_R562_BLESS").is_some() {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, format!("{header}{obtained}")).unwrap();
        eprintln!("empreintes figées dans {}", path.display());
        return;
    }

    let Ok(stored) = std::fs::read_to_string(&path) else {
        panic!(
            "aucune empreinte de référence pour COMPILER_VERSION {COMPILER_VERSION} sur {} : {}\n\
             Si la sortie du compilateur a changé, COMPILER_VERSION a dû être incrémentée des deux \
             côtés (R-562) ; figer alors les empreintes de chaque plateforme avec AXION_R562_BLESS=1.\n\
             Contenu attendu :\n----- début -----\n{header}{obtained}----- fin -----",
            platform(),
            path.display()
        );
    };
    let stored = body(&stored);
    if stored != obtained {
        let mut differences = String::new();
        for (expected, got) in stored.lines().zip(obtained.lines()) {
            if expected != got {
                writeln!(differences, "  figé   : {expected}\n  obtenu : {got}").unwrap();
            }
        }
        panic!(
            "la sortie du compilateur a changé sans que COMPILER_VERSION ({COMPILER_VERSION}) change \
             (R-562), sur {} :\n{differences}Incrémenter COMPILER_VERSION en Rust et en Java, puis figer \
             les empreintes de chaque plateforme avec AXION_R562_BLESS=1.\nContenu obtenu :\n\
             ----- début -----\n{header}{obtained}----- fin -----",
            platform()
        );
    }
}
