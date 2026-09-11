//! Vecteurs d'or (R-513) — encodage des cas et évaluation d'un cas.
//!
//! Ce module est partagé par le générateur (`examples/generate_golden.rs`) et
//! par le rejeu (`tests/golden.rs`). Les deux doivent lire le même fichier de la
//! même façon, et un fichier de vecteurs d'or dont le producteur et le
//! consommateur divergeraient ne vérifierait plus rien.
//!
//! # Pourquoi les bits et non les chiffres
//!
//! Un flottant s'écrit ici par son motif de bits. L'écrire en décimal ferait
//! dépendre le fichier du formateur : deux versions de la bibliothèque standard
//! peuvent choisir des représentations différentes d'une même valeur, et un
//! `NaN` ou un zéro négatif n'y survivrait pas du tout. Les bits sont la valeur.

#![allow(dead_code)]

use ax_det::{
    clamp, dequantize_i8, digest64, falloff, inv_sqrt, mix64, quantize_i8, smooth01, DetRng,
};

/// Les opérations couvertes et leur nombre d'arguments.
///
/// La table est le contrat de forme du fichier : le rejeu s'en sert pour
/// refuser une ligne mal formée plutôt que de l'interpréter de travers.
pub const OPERATIONS: &[(&str, usize)] = &[
    ("clamp", 3),
    ("falloff", 1),
    ("smooth01", 1),
    ("inv_sqrt", 1),
    ("quantize", 2),
    ("dequantize", 2),
    ("digest", 1),
    ("mix", 2),
    ("rng", 2),
    ("rngf", 2),
    ("rngb", 3),
    ("impact", 2),
];

/// Nombre d'arguments d'une opération, ou `None` si elle est inconnue.
#[must_use]
pub fn arity(op: &str) -> Option<usize> {
    OPERATIONS
        .iter()
        .find(|(name, _)| *name == op)
        .map(|(_, count)| *count)
}

/// Marque d'une suite d'octets vide.
///
/// Un champ vide entre deux tabulations se lirait, mais se relirait mal : une
/// tabulation en trop ou en moins deviendrait indétectable à l'œil.
pub const OCTETS_VIDES: &str = "-";

/// Encode un `f32` par ses bits.
#[must_use]
pub fn encode_f32(value: f32) -> String {
    format!("{:08X}", value.to_bits())
}

/// Encode un `u64` opaque.
#[must_use]
pub fn encode_u64(value: u64) -> String {
    format!("{value:016X}")
}

/// Encode une suite d'octets.
#[must_use]
pub fn encode_bytes(bytes: &[u8]) -> String {
    if bytes.is_empty() {
        return OCTETS_VIDES.to_string();
    }
    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        text.push_str(&format!("{byte:02X}"));
    }
    text
}

/// Décode un `f32` écrit par ses bits.
fn decode_f32(text: &str) -> f32 {
    f32::from_bits(
        u32::from_str_radix(text, 16)
            .unwrap_or_else(|_| panic!("bits de flottant illisibles : {text:?}")),
    )
}

/// Décode un `u64` écrit en hexadécimal.
fn decode_u64(text: &str) -> u64 {
    u64::from_str_radix(text, 16).unwrap_or_else(|_| panic!("entier 64 illisible : {text:?}"))
}

/// Décode un entier de comptage écrit en décimal.
fn decode_u32(text: &str) -> u32 {
    text.parse()
        .unwrap_or_else(|_| panic!("entier de comptage illisible : {text:?}"))
}

/// Décode un pas quantifié écrit en décimal.
fn decode_i8(text: &str) -> i8 {
    text.parse()
        .unwrap_or_else(|_| panic!("pas quantifié illisible : {text:?}"))
}

/// Décode une suite d'octets écrite en hexadécimal.
fn decode_bytes(text: &str) -> Vec<u8> {
    if text == OCTETS_VIDES {
        return Vec::new();
    }
    assert!(
        text.len().is_multiple_of(2),
        "suite d'octets de longueur impaire : {text:?}"
    );
    (0..text.len() / 2)
        .map(|index| {
            u8::from_str_radix(&text[index * 2..index * 2 + 2], 16)
                .unwrap_or_else(|_| panic!("octet illisible dans {text:?}"))
        })
        .collect()
}

/// Évalue un cas et rend son résultat encodé.
///
/// C'est l'unique point où le noyau est appelé, pour le générateur comme pour
/// le rejeu : les deux passent forcément par le même chemin, et une différence
/// entre eux est impossible par construction.
///
/// # Panics
///
/// Si l'opération est inconnue, si le nombre d'arguments ne correspond pas, ou
/// si un argument est illisible. Ces trois cas sont des fichiers corrompus, et
/// un fichier corrompu doit arrêter le rejeu plutôt que de le laisser conclure.
#[must_use]
pub fn evaluate(op: &str, args: &[&str]) -> String {
    let attendu = arity(op).unwrap_or_else(|| panic!("opération inconnue : {op:?}"));
    assert_eq!(
        args.len(),
        attendu,
        "{op} prend {attendu} arguments, {} fournis",
        args.len()
    );

    match op {
        "clamp" => encode_f32(clamp(
            decode_f32(args[0]),
            decode_f32(args[1]),
            decode_f32(args[2]),
        )),
        "falloff" => encode_f32(falloff(decode_f32(args[0]))),
        "smooth01" => encode_f32(smooth01(decode_f32(args[0]))),
        "inv_sqrt" => encode_f32(inv_sqrt(decode_f32(args[0]))),
        "quantize" => format!("{}", quantize_i8(decode_f32(args[0]), decode_f32(args[1]))),
        "dequantize" => encode_f32(dequantize_i8(decode_i8(args[0]), decode_f32(args[1]))),
        "digest" => encode_u64(digest64(&decode_bytes(args[0]))),
        "mix" => encode_u64(mix64(decode_u64(args[0]), decode_u64(args[1]))),

        // Les suites tirées sont résumées par leur empreinte : mille tirages
        // tiennent en seize caractères, et une divergence sur l'un d'eux se
        // voit tout autant.
        "rng" => {
            let mut rng = DetRng::from_state(decode_u64(args[0]));
            let mut bytes = Vec::new();
            for _ in 0..decode_u32(args[1]) {
                bytes.extend_from_slice(&rng.next_u32().to_le_bytes());
            }
            encode_u64(digest64(&bytes))
        }
        "rngf" => {
            let mut rng = DetRng::from_state(decode_u64(args[0]));
            let mut bytes = Vec::new();
            for _ in 0..decode_u32(args[1]) {
                bytes.extend_from_slice(&rng.next_f32().to_bits().to_le_bytes());
            }
            encode_u64(digest64(&bytes))
        }
        "rngb" => {
            let mut rng = DetRng::from_state(decode_u64(args[0]));
            let bound = decode_u32(args[1]);
            let mut bytes = Vec::new();
            for _ in 0..decode_u32(args[2]) {
                bytes.extend_from_slice(&rng.next_bounded(bound).to_le_bytes());
            }
            encode_u64(digest64(&bytes))
        }

        // R-512 : la graine d'un impact dérive de `(assembly_uuid, seq)`. C'est
        // l'état obtenu qui est figé, et non la première sortie : un décalage
        // d'un cran dans la préparation ne se verrait pas autrement.
        "impact" => {
            encode_u64(DetRng::for_impact(decode_u64(args[0]), decode_u64(args[1])).state())
        }

        _ => unreachable!("opération {op} déclarée mais non évaluée"),
    }
}

/// Empreinte d'un ensemble de lignes de cas.
///
/// Elle porte sur les lignes reconstruites et jointes par un saut de ligne seul,
/// jamais sur les octets du fichier : une copie de travail configurée en fins de
/// ligne Windows donnerait sinon une autre empreinte que la même copie sous
/// Linux, et le fichier cesserait d'être comparable d'une configuration de la
/// matrice à l'autre — ce qui est précisément ce qu'il existe pour permettre.
#[must_use]
pub fn digest_lines(lines: &[String]) -> u64 {
    let mut bytes = Vec::new();
    for line in lines {
        bytes.extend_from_slice(line.as_bytes());
        bytes.push(b'\n');
    }
    digest64(&bytes)
}
