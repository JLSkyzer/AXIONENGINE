//! ADR-135 par l'ABI, comme Java le fera : le texte du `<modèle>.axion.json`
//! voisin passe tel quel, en chaîne CBOR, dans `options_cbor`, et la compilation
//! l'applique **au bit près** comme `ax-asset` l'applique pour `axion-cli`
//! (R-830). Une option refusée fait échouer le travail et son code se lit au
//! sondage ; un paramètre qui n'est pas une chaîne est refusé à la soumission.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::compile::{compile, source_hash, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::options::{parse, parse_bytes, AssetOptions, E_INVALID_OPTION, E_UNKNOWN_SCHEMA};
use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::config::{find, ConfigScope, ConfigValue};
use axion_native::abi::{
    axion_asset_compile, axion_asset_poll, axion_buffer_acquire, axion_buffer_release, axion_init,
    axion_shutdown, AxionBufferInfo, AXION_ASSET_COMPILED, AXION_ASSET_FAILED, AXION_ASSET_PENDING,
    AXION_E_CONFIG, AXION_E_INVALID_BUFFER, AXION_OK, AXION_SIDE_SERVER, MAX_OPTIONS_BYTES,
};

const ASSET_ID: u64 = 0x0A51_0C0B_0000_0135;
/// Format 2 : OBJ.
const OBJ: u32 = 2;
const SOURCE: &[u8] = b"v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
f 1 2 3
";
/// Des options qui changent la sortie à coup sûr : `scale` déplace chaque
/// sommet, `compress` change l'écriture des sections.
const OPTIONS: &str = r#"{ "schema": 1, "scale": 2.0, "compress": false }"#;

fn empty_info() -> AxionBufferInfo {
    AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    }
}

/// Une chaîne de texte CBOR sous sa forme la plus courte, comme `CborWriter`
/// l'écrit côté Java. Les octets sont pris tels quels, UTF-8 ou non.
fn cbor_text(text: &[u8]) -> Vec<u8> {
    let len = text.len();
    let mut out = if len <= 23 {
        vec![0x60 | u8::try_from(len).unwrap()]
    } else if let Ok(short) = u8::try_from(len) {
        vec![0x78, short]
    } else if let Ok(medium) = u16::try_from(len) {
        let mut head = vec![0x79];
        head.extend_from_slice(&medium.to_be_bytes());
        head
    } else {
        let mut head = vec![0x7a];
        head.extend_from_slice(&u32::try_from(len).unwrap().to_be_bytes());
        head
    };
    out.extend_from_slice(text);
    out
}

/// Soumet la compilation de [`SOURCE`], déjà dans `ASSET_IN`, avec ce paramètre
/// d'options ; rend l'identifiant du travail ou le code du refus.
fn submit(ctx: u64, options: &[u8]) -> Result<u32, i32> {
    let mut job: u32 = 0;
    // SAFETY: `options` couvre `options.len()` octets (un pointeur quelconque
    // quand il est vide, sa longueur nulle) ; `job` est une variable locale.
    let code = unsafe {
        axion_asset_compile(
            ctx,
            ASSET_ID,
            OBJ,
            SOURCE.len() as u64,
            options.as_ptr(),
            options.len(),
            &raw mut job,
        )
    };
    if code == AXION_OK {
        assert_ne!(job, 0);
        Ok(job)
    } else {
        Err(code)
    }
}

/// Sonde un travail jusqu'à son aboutissement, comme Java à chaque tick ; rend
/// l'A3D lu dans `ASSET_OUT`, ou le code de l'échec.
fn settle(ctx: u64, job: u32) -> Result<Vec<u8>, i32> {
    // Une échéance large plutôt qu'un nombre de sondages : la compilation tourne
    // sur le pool, et un compte fixe ne borne pas un temps (voir `cycle_abi`).
    let echeance = std::time::Instant::now() + std::time::Duration::from_secs(30);
    let mut status = AXION_ASSET_PENDING;
    let mut size: u64 = 0;
    let mut error: i32 = 0;
    while status == AXION_ASSET_PENDING && std::time::Instant::now() < echeance {
        // SAFETY: les trois pointeurs de sortie sont des variables locales.
        let code =
            unsafe { axion_asset_poll(ctx, job, &raw mut status, &raw mut size, &raw mut error) };
        assert_eq!(code, AXION_OK, "sondage refusé");
        std::thread::yield_now();
    }
    match status {
        AXION_ASSET_COMPILED => {
            let mut out = empty_info();
            // SAFETY: `out` est accessible en écriture.
            let code = unsafe {
                axion_buffer_acquire(ctx, BufferKind::AssetOut.as_u32(), 0, &raw mut out)
            };
            assert_eq!(code, AXION_OK);
            // SAFETY: le tampon appartient au contexte vivant et fait au moins
            // `out.capacity` octets.
            let view = unsafe {
                std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap())
            };
            let size = usize::try_from(size).unwrap();
            let compiled = view[HEADER_BYTES..HEADER_BYTES + size].to_vec();
            // SAFETY: aucun pointeur déréférencé.
            let code = unsafe { axion_buffer_release(ctx, out.kind, out.generation) };
            assert_eq!(code, AXION_OK);
            Ok(compiled)
        }
        AXION_ASSET_FAILED => Err(error),
        _ => panic!("compilation toujours en cours après l'échéance"),
    }
}

/// `assets.max_source_bytes` tel que le registre le déclare : le plafond que la
/// CLI applique, et le jeu sous une configuration muette.
fn default_max_source_bytes() -> u64 {
    match find(ConfigScope::Common, "assets.max_source_bytes").map(|option| option.default) {
        Some(ConfigValue::Int(value)) => u64::try_from(value).unwrap(),
        other => panic!("assets.max_source_bytes : {other:?}"),
    }
}

/// Ce que `ax-asset` compile de la même source avec ces options, comme la CLI le
/// fait : son empreinte de source et son plafond (R-830). Seul `asset_id` vient
/// de l'appelant, qui le tient de l'orchestrateur.
fn reference(authored: AssetOptions) -> Vec<u8> {
    let options = CompileOptions {
        asset_id: ASSET_ID,
        source_hash: source_hash(SOURCE),
        limits: ImportLimits::new(default_max_source_bytes()),
        dynamic_body: true,
        authored,
    };
    compile(SOURCE, SourceFormat::Obj, &options, |_| None)
        .expect("compilation de référence refusée")
        .bytes
}

#[test]
fn adr135_les_options_traversent_la_frontiere_et_s_appliquent_comme_dans_la_cli() {
    let mut ctx: u64 = 0;
    // SAFETY: `ctx` est une variable locale accessible en écriture.
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "initialisation refusée");

    // Java écrit la source dans le tampon d'entrée.
    let mut asset_in = empty_info();
    // SAFETY: `asset_in` est accessible en écriture.
    let code = unsafe {
        axion_buffer_acquire(
            ctx,
            BufferKind::AssetIn.as_u32(),
            SOURCE.len() as u64,
            &raw mut asset_in,
        )
    };
    assert_eq!(code, AXION_OK);
    // SAFETY: le tampon appartient au contexte vivant et fait au moins
    // `asset_in.capacity` octets.
    let view = unsafe {
        std::slice::from_raw_parts_mut(asset_in.ptr, usize::try_from(asset_in.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + SOURCE.len()].copy_from_slice(SOURCE);

    // Sans fichier d'options : les défauts, la sortie d'avant ADR-135.
    // INV-08 : meshoptimizer et zstd, que la compilation emploie sur un worker du pool,
    // allouent par l'allocateur compteur, et rendent tout ce qu'ils ont pris.
    let foreign = ax_mem::foreign_allocations();
    let defaults = settle(ctx, submit(ctx, &[]).unwrap()).unwrap();
    assert!(
        ax_mem::foreign_allocations() > foreign,
        "la compilation n'a pas employé l'allocateur étranger"
    );
    assert_eq!(ax_mem::foreign_live_bytes(), 0, "bloc étranger non rendu");
    assert_eq!(defaults, reference(AssetOptions::DEFAULT));

    // Avec : appliquées au bit près comme la CLI les applique.
    let authored = parse(OPTIONS).unwrap().options;
    let expected = reference(authored);
    assert_ne!(expected, defaults, "les options doivent changer la sortie");
    let with_options = settle(ctx, submit(ctx, &cbor_text(OPTIONS.as_bytes())).unwrap()).unwrap();
    assert_eq!(with_options, expected);

    // Un texte de plus de 255 octets : longueur d'en-tête sur deux octets.
    let padded = format!("{OPTIONS}{}", " ".repeat(300));
    let cbor = cbor_text(padded.as_bytes());
    assert_eq!(cbor[0], 0x79);
    assert_eq!(settle(ctx, submit(ctx, &cbor).unwrap()).unwrap(), expected);

    // Une option refusée fait échouer le travail, son code se lit au sondage.
    for (text, code, cas) in [
        (
            &br#"{ "schema": 1, "scal": 2 }"#[..],
            E_INVALID_OPTION,
            "clé inconnue",
        ),
        (
            &br#"{ "schema": 2 }"#[..],
            E_UNKNOWN_SCHEMA,
            "schéma inconnu",
        ),
        (&[0xff, 0xfe][..], E_INVALID_OPTION, "UTF-8 invalide"),
    ] {
        // Le code de la CLI, pour le même fichier (R-830).
        assert_eq!(parse_bytes(text).unwrap_err().code(), code, "{cas} (CLI)");
        let job = submit(ctx, &cbor_text(text)).expect(cas);
        assert_eq!(settle(ctx, job), Err(code), "{cas}");
    }

    // Ce qui n'est pas une chaîne de texte, ou trop long, est refusé à la
    // soumission, sans travail lancé.
    for (cbor, cas) in [
        (vec![0x01], "entier"),
        (vec![0x42, b'{', b'}'], "chaîne d'octets"),
        (vec![0x62, b'{', b'}', 0x00], "octet après la chaîne"),
        (
            cbor_text(&vec![b' '; MAX_OPTIONS_BYTES]),
            "plus long que la borne",
        ),
    ] {
        assert_eq!(submit(ctx, &cbor), Err(AXION_E_CONFIG), "{cas}");
    }
    let mut job: u32 = 0;
    // SAFETY: précisément ce que la fonction doit détecter sans lire.
    let code = unsafe {
        axion_asset_compile(
            ctx,
            ASSET_ID,
            OBJ,
            SOURCE.len() as u64,
            std::ptr::null(),
            4,
            &raw mut job,
        )
    };
    assert_eq!(
        code, AXION_E_INVALID_BUFFER,
        "pointeur nul, longueur non nulle"
    );

    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(
            axion_buffer_release(ctx, BufferKind::AssetIn.as_u32(), asset_in.generation),
            AXION_OK
        );
        assert_eq!(axion_shutdown(ctx), AXION_OK, "bilan d'arrêt");
    }

    // R-533 en jeu comme en CLI : une source au-delà de `assets.max_source_bytes`
    // est refusée par le même code, avec le même code d'erreur.
    let mut config = Vec::new();
    ciborium::into_writer(
        &ciborium::Value::Map(vec![(
            ciborium::Value::Text("assets.max_source_bytes".to_owned()),
            ciborium::Value::Integer(16.into()),
        )]),
        &mut config,
    )
    .unwrap();
    // SAFETY: `config` couvre `config.len()` octets ; `ctx` est une variable locale.
    let code = unsafe {
        axion_init(
            config.as_ptr(),
            config.len(),
            AXION_SIDE_SERVER,
            &raw mut ctx,
        )
    };
    assert_eq!(code, AXION_OK, "réinitialisation refusée");
    let mut asset_in = empty_info();
    // SAFETY: `asset_in` est accessible en écriture.
    let code = unsafe {
        axion_buffer_acquire(
            ctx,
            BufferKind::AssetIn.as_u32(),
            SOURCE.len() as u64,
            &raw mut asset_in,
        )
    };
    assert_eq!(code, AXION_OK);
    // SAFETY: le tampon appartient au contexte vivant et fait au moins
    // `asset_in.capacity` octets.
    let view = unsafe {
        std::slice::from_raw_parts_mut(asset_in.ptr, usize::try_from(asset_in.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + SOURCE.len()].copy_from_slice(SOURCE);

    let cli = compile(
        SOURCE,
        SourceFormat::Obj,
        &CompileOptions {
            asset_id: ASSET_ID,
            source_hash: source_hash(SOURCE),
            limits: ImportLimits::new(16),
            dynamic_body: true,
            authored: AssetOptions::DEFAULT,
        },
        |_| None,
    )
    .expect_err("la CLI devait refuser une source de plus de 16 octets");
    assert_eq!(settle(ctx, submit(ctx, &[]).unwrap()), Err(cli.code()));
    assert_eq!(cli.code(), -3005, "E-3005 : source trop grande");

    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(
            axion_buffer_release(ctx, BufferKind::AssetIn.as_u32(), asset_in.generation),
            AXION_OK
        );
        assert_eq!(axion_shutdown(ctx), AXION_OK, "bilan d'arrêt");
    }
}
