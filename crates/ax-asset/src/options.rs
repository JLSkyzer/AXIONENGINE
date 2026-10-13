//! Options de compilation : le `<modèle>.axion.json` de la PARTIE 6.4 (ADR-135).
//!
//! **Un seul lecteur**, celui-ci, pour le jeu et pour `axion-cli` : le jeu en
//! reçoit le texte par `options_cbor` (IF-06), la CLI le lit sur le disque, et
//! tous deux l'interprètent ici (R-830, R-860). Le tokeniseur est celui de
//! `serde_json`, que la table 32.2 écarte du runtime : écart borné à ce seul
//! lecteur par l'ADR-136 ; tout autre JSON du jeu reste lu côté Java.
//!
//! Le fichier est du JSON strict — clé en double, contenu après la racine, BOM
//! ou UTF-8 invalide refusés —, et chaque clé est connue : une clé inconnue est
//! une faute (`E-7001`), comme `schema` absent ou autre que 1 (`E-7003`). Toutes
//! les fautes sont rendues en un seul refus, chacune à son chemin JSON
//! (`$.lod.ratios[2]`), dans l'ordre du document.
//!
//! Toute option a un défaut (R-870) : sans fichier, la compilation est celle
//! d'aujourd'hui, au bit près. `collision.mode` vaut `none` par défaut (ADR-135
//! point 4). Les options des systèmes à venir — `deformation` (C-28), `structure`
//! (C-43), `collision.resolution` (ADR-108) — sont validées, et signalées sans
//! effet.

use crate::collider::ColliderMode;
use crate::optimize::{LodOptions, MAX_LOD_LEVELS};
use core::fmt;
use serde::de::{self, Deserialize, Deserializer, MapAccess, SeqAccess, Visitor};

/// Version du format d'options que ce lecteur comprend.
pub const OPTIONS_SCHEMA: u64 = 1;

/// Code de l'ANNEXE A.1 d'une option invalide (comme une definition, R-580).
pub const E_INVALID_OPTION: i32 = -7001;

/// Code de l'ANNEXE A.1 d'un `schema` absent ou inconnu (comme R-1780).
pub const E_UNKNOWN_SCHEMA: i32 = -7003;

/// Plus grand nombre d'enveloppes d'une décomposition convexe (R-551).
pub const MAX_HULLS: u32 = crate::collider::DECOMP_MAX_HULLS;

/// Plus grande taille de texture admise (R-570).
pub const MAX_TEXTURE_SIZE: u32 = 4096;

/// Axe vertical de la source (R-871).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum UpAxis {
    /// Y en haut, la convention du moteur (R-461).
    #[default]
    Y,
    /// Z en haut, celle des exports Blender : rotation de −90° autour de X.
    Z,
}

/// Génération des normales (C-23, étape 2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum NormalsMode {
    /// Seules les normales absentes de la source sont générées.
    #[default]
    IfMissing,
    /// Toutes les normales sont recalculées, celles de l'auteur comprises.
    Always,
}

/// Bloc `collision`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CollisionOptions {
    /// Génération automatique de C-32, quand ni les extras ni la definition ne
    /// fournissent de collider ; `None` par défaut.
    pub mode: ColliderMode,
    /// Enveloppes au plus d'une décomposition convexe (R-551).
    pub max_hulls: u32,
    /// Résolution de la décomposition, si l'auteur l'a donnée. Sans effet tant
    /// qu'ADR-108 ne l'a pas mesurée.
    pub resolution: Option<u32>,
}

impl CollisionOptions {
    /// Un mode de génération automatique, aux bornes par défaut.
    #[must_use]
    pub const fn with_mode(mode: ColliderMode) -> Self {
        Self {
            mode,
            max_hulls: MAX_HULLS,
            resolution: None,
        }
    }
}

/// Bloc `materials` : le matériau d'une source qui n'en porte aucun.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MaterialDefaults {
    /// Rugosité, dans `[0, 1]`.
    pub roughness: f32,
    /// Métal, dans `[0, 1]`.
    pub metallic: f32,
}

/// D'où viennent les options d'un asset (ADR-135, point 5).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum OptionsOrigin {
    /// Aucune source : les défauts de R-870.
    #[default]
    Defaults,
    /// Le `<modèle>.axion.json` voisin de la source, ou celui que la CLI désigne.
    File,
    /// Le bloc `axion` des `extras` racine d'un glTF.
    GltfExtras,
}

/// Les options de compilation d'un asset (PARTIE 6.4) : celles que la
/// compilation applique. `deformation` et `structure` sont validées par le
/// lecteur, puis écartées : sans effet avant C-28 et C-43, qui ajouteront ici
/// leurs champs.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AssetOptions {
    /// Échelle uniforme de l'asset entier.
    pub scale: f32,
    /// Axe vertical de la source.
    pub up_axis: UpAxis,
    /// Génération des tangentes (C-23, étape 3).
    pub generate_tangents: bool,
    /// Génération des normales.
    pub generate_normals: NormalsMode,
    /// Niveaux de détail.
    pub lod: LodOptions,
    /// Génération automatique de colliders.
    pub collision: CollisionOptions,
    /// Matériau par défaut.
    pub materials: MaterialDefaults,
    /// Sections compressées.
    pub compress: bool,
    /// Taille de texture au-delà de laquelle une texture embarquée est signalée.
    pub max_texture_size: u32,
    /// D'où elles viennent : un fichier l'emporte sur le bloc des extras d'un glTF.
    pub origin: OptionsOrigin,
}

impl Default for AssetOptions {
    fn default() -> Self {
        Self::DEFAULT
    }
}

impl AssetOptions {
    /// Les défauts de R-870 : ceux de la compilation d'aujourd'hui.
    pub const DEFAULT: Self = Self {
        scale: 1.0,
        up_axis: UpAxis::Y,
        generate_tangents: true,
        generate_normals: NormalsMode::IfMissing,
        lod: LodOptions::DEFAULT,
        collision: CollisionOptions::with_mode(ColliderMode::None),
        materials: MaterialDefaults {
            roughness: crate::import::DEFAULT_ROUGHNESS,
            metallic: crate::import::DEFAULT_METALLIC,
        },
        compress: true,
        max_texture_size: 2048,
        origin: OptionsOrigin::Defaults,
    };

    /// Les défauts, avec un mode de génération automatique de colliders.
    #[must_use]
    pub const fn with_collider_mode(mode: ColliderMode) -> Self {
        let mut options = Self::DEFAULT;
        options.collision.mode = mode;
        options
    }
}

/// Un fichier d'options lu : ses valeurs, et ce qu'il faut en dire à l'auteur.
#[derive(Debug, Clone, PartialEq)]
pub struct ParsedOptions {
    /// Les options, défauts compris pour ce que le fichier ne dit pas.
    pub options: AssetOptions,
    /// Avertissements : options validées mais sans effet aujourd'hui.
    pub warnings: Vec<String>,
}

/// Une faute d'un fichier d'options, à son chemin JSON.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OptionFault {
    /// Code de l'ANNEXE A.1 ([`E_INVALID_OPTION`] ou [`E_UNKNOWN_SCHEMA`]).
    pub code: i32,
    /// Chemin JSON, `$` pour la racine.
    pub path: String,
    /// Ce qui ne va pas.
    pub message: String,
}

/// Un fichier d'options refusé : toutes ses fautes, dans l'ordre du document.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OptionsError {
    /// Les fautes ; jamais vide.
    pub faults: Vec<OptionFault>,
}

impl OptionsError {
    fn single(code: i32, path: &str, message: impl Into<String>) -> Self {
        Self {
            faults: vec![OptionFault {
                code,
                path: path.to_owned(),
                message: message.into(),
            }],
        }
    }

    /// Code de l'ANNEXE A.1 : celui de la première faute.
    #[must_use]
    pub fn code(&self) -> i32 {
        self.faults
            .first()
            .map_or(E_INVALID_OPTION, |fault| fault.code)
    }
}

impl fmt::Display for OptionsError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        for (rank, fault) in self.faults.iter().enumerate() {
            if rank > 0 {
                formatter.write_str(" ; ")?;
            }
            write!(
                formatter,
                "E-{} {} : {}",
                -fault.code, fault.path, fault.message
            )?;
        }
        Ok(())
    }
}

impl std::error::Error for OptionsError {}

/// Lit un fichier d'options à partir de ses octets.
///
/// # Errors
///
/// [`OptionsError`] si les octets ne sont pas de l'UTF-8, si le texte n'est pas
/// du JSON strict, ou si une option est inconnue ou invalide.
pub fn parse_bytes(bytes: &[u8]) -> Result<ParsedOptions, OptionsError> {
    let text = core::str::from_utf8(bytes).map_err(|_| {
        OptionsError::single(E_INVALID_OPTION, "$", "texte qui n'est pas de l'UTF-8")
    })?;
    parse(text)
}

/// Lit un fichier d'options.
///
/// # Errors
///
/// [`OptionsError`] si le texte n'est pas du JSON strict, ou si une option est
/// inconnue ou invalide.
pub fn parse(text: &str) -> Result<ParsedOptions, OptionsError> {
    parse_from(text, OptionsOrigin::File, "$")
}

/// Lit le bloc `axion` des `extras` racine d'un glTF, tel que [`gltf_root_block`]
/// l'a extrait : les mêmes règles qu'un fichier, ses fautes situées sous
/// `$.extras.axion`.
///
/// # Errors
///
/// [`OptionsError`] comme [`parse`].
pub fn parse_gltf_block(text: &str) -> Result<ParsedOptions, OptionsError> {
    parse_from(text, OptionsOrigin::GltfExtras, "$.extras.axion")
}

fn parse_from(
    text: &str,
    origin: OptionsOrigin,
    root: &str,
) -> Result<ParsedOptions, OptionsError> {
    if text.starts_with('\u{feff}') {
        return Err(OptionsError::single(
            E_INVALID_OPTION,
            root,
            "marque d'ordre des octets (BOM) en tête : JSON strict attendu",
        ));
    }
    let value: Json = serde_json::from_str(text).map_err(|error| {
        OptionsError::single(E_INVALID_OPTION, root, format!("JSON invalide : {error}"))
    })?;
    let mut reader = Reader::default();
    let mut options = reader.root(&value);
    options.origin = origin;
    if reader.faults.is_empty() {
        Ok(ParsedOptions {
            options,
            warnings: reader.warnings,
        })
    } else {
        let mut faults = reader.faults;
        if root != "$" {
            for fault in &mut faults {
                fault.path = fault.path.replacen('$', root, 1);
            }
        }
        Err(OptionsError { faults })
    }
}

/// Le texte du bloc `axion` des `extras` racine d'un glTF, s'il y en a un
/// (PARTIE 6.4).
///
/// Les `extras` servent aussi à d'autres outils : seul le membre `axion` est lu,
/// le reste n'est pas jugé (R-911). Des extras qui ne sont pas un objet n'en
/// portent pas ; un membre `axion` en double est refusé.
///
/// # Errors
///
/// [`OptionsError`] si les extras ne sont pas du JSON, ou portent deux membres
/// `axion`.
pub fn gltf_root_block(extras: &str) -> Result<Option<String>, OptionsError> {
    serde_json::from_str::<RootExtras>(extras)
        .map(|extras| extras.0)
        .map_err(|error| {
            OptionsError::single(
                E_INVALID_OPTION,
                "$.extras",
                format!("extras illisibles : {error}"),
            )
        })
}

/// Le membre `axion` d'extras glTF, brut.
struct RootExtras(Option<String>);

impl<'de> Deserialize<'de> for RootExtras {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        deserializer.deserialize_any(RootExtrasVisitor)
    }
}

struct RootExtrasVisitor;

impl<'de> Visitor<'de> for RootExtrasVisitor {
    type Value = RootExtras;

    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("les extras d'un glTF")
    }

    fn visit_unit<E>(self) -> Result<RootExtras, E> {
        Ok(RootExtras(None))
    }

    fn visit_bool<E>(self, _: bool) -> Result<RootExtras, E> {
        Ok(RootExtras(None))
    }

    fn visit_i64<E>(self, _: i64) -> Result<RootExtras, E> {
        Ok(RootExtras(None))
    }

    fn visit_u64<E>(self, _: u64) -> Result<RootExtras, E> {
        Ok(RootExtras(None))
    }

    fn visit_f64<E>(self, _: f64) -> Result<RootExtras, E> {
        Ok(RootExtras(None))
    }

    fn visit_str<E>(self, _: &str) -> Result<RootExtras, E> {
        Ok(RootExtras(None))
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<RootExtras, A::Error> {
        while seq.next_element::<de::IgnoredAny>()?.is_some() {}
        Ok(RootExtras(None))
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<RootExtras, A::Error> {
        let mut block = None;
        while let Some(key) = map.next_key::<String>()? {
            if key == "axion" {
                let raw = map.next_value::<Box<serde_json::value::RawValue>>()?;
                if block.replace(raw.get().to_owned()).is_some() {
                    return Err(de::Error::custom("membre « axion » en double"));
                }
            } else {
                map.next_value::<de::IgnoredAny>()?;
            }
        }
        Ok(RootExtras(block))
    }
}

/// Une valeur JSON lue strictement : une clé en double est refusée, ce que
/// `serde_json::Value` laisserait passer en gardant la dernière.
#[derive(Debug, Clone, PartialEq)]
enum Json {
    Null,
    Bool(bool),
    Number(f64),
    String(String),
    Array(Vec<Json>),
    Object(Vec<(String, Json)>),
}

impl Json {
    fn kind(&self) -> &'static str {
        match self {
            Json::Null => "null",
            Json::Bool(_) => "un booléen",
            Json::Number(_) => "un nombre",
            Json::String(_) => "une chaîne",
            Json::Array(_) => "un tableau",
            Json::Object(_) => "un objet",
        }
    }
}

impl<'de> Deserialize<'de> for Json {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        deserializer.deserialize_any(JsonVisitor)
    }
}

struct JsonVisitor;

impl<'de> Visitor<'de> for JsonVisitor {
    type Value = Json;

    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("une valeur JSON")
    }

    fn visit_unit<E>(self) -> Result<Json, E> {
        Ok(Json::Null)
    }

    fn visit_bool<E>(self, value: bool) -> Result<Json, E> {
        Ok(Json::Bool(value))
    }

    fn visit_i64<E>(self, value: i64) -> Result<Json, E> {
        Ok(Json::Number(value as f64))
    }

    fn visit_u64<E>(self, value: u64) -> Result<Json, E> {
        Ok(Json::Number(value as f64))
    }

    fn visit_f64<E>(self, value: f64) -> Result<Json, E> {
        Ok(Json::Number(value))
    }

    fn visit_str<E>(self, value: &str) -> Result<Json, E> {
        Ok(Json::String(value.to_owned()))
    }

    fn visit_string<E>(self, value: String) -> Result<Json, E> {
        Ok(Json::String(value))
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Json, A::Error> {
        let mut items = Vec::new();
        while let Some(item) = seq.next_element::<Json>()? {
            items.push(item);
        }
        Ok(Json::Array(items))
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Json, A::Error> {
        let mut entries: Vec<(String, Json)> = Vec::new();
        while let Some(key) = map.next_key::<String>()? {
            if entries.iter().any(|(seen, _)| *seen == key) {
                return Err(de::Error::custom(format!("clé « {key} » en double")));
            }
            let value = map.next_value::<Json>()?;
            entries.push((key, value));
        }
        Ok(Json::Object(entries))
    }
}

/// Parcours du document : valeurs lues, fautes et avertissements recueillis.
#[derive(Default)]
struct Reader {
    faults: Vec<OptionFault>,
    warnings: Vec<String>,
}

impl Reader {
    fn fault(&mut self, path: &str, message: impl Into<String>) {
        self.faults.push(OptionFault {
            code: E_INVALID_OPTION,
            path: path.to_owned(),
            message: message.into(),
        });
    }

    fn unknown(&mut self, path: &str, admitted: &[&str]) {
        self.fault(
            path,
            format!("clé inconnue ; admises : {}", admitted.join(", ")),
        );
    }

    fn object<'a>(&mut self, value: &'a Json, path: &str) -> Option<&'a [(String, Json)]> {
        match value {
            Json::Object(entries) => Some(entries),
            other => {
                self.fault(path, format!("un objet attendu, {} trouvé", other.kind()));
                None
            }
        }
    }

    fn boolean(&mut self, value: &Json, path: &str) -> Option<bool> {
        match value {
            Json::Bool(value) => Some(*value),
            other => {
                self.fault(path, format!("un booléen attendu, {} trouvé", other.kind()));
                None
            }
        }
    }

    fn text<'a>(&mut self, value: &'a Json, path: &str) -> Option<&'a str> {
        match value {
            Json::String(value) => Some(value),
            other => {
                self.fault(
                    path,
                    format!("une chaîne attendue, {} trouvé", other.kind()),
                );
                None
            }
        }
    }

    /// Un nombre fini dans `[min, max]`, rendu en `f32`.
    fn number(&mut self, value: &Json, path: &str, min: f64, max: f64) -> Option<f32> {
        match value {
            Json::Number(number) if number.is_finite() && *number >= min && *number <= max => {
                Some(*number as f32)
            }
            Json::Number(number) => {
                self.fault(path, format!("{number} hors de [{min}, {max}]"));
                None
            }
            other => {
                self.fault(path, format!("un nombre attendu, {} trouvé", other.kind()));
                None
            }
        }
    }

    /// Un nombre strictement positif qui reste positif et fini en `f32`.
    fn positive(&mut self, value: &Json, path: &str) -> Option<f32> {
        match value {
            Json::Number(number) if *number > 0.0 && *number <= f64::from(f32::MAX) => {
                let narrowed = *number as f32;
                if narrowed > 0.0 {
                    Some(narrowed)
                } else {
                    self.fault(path, format!("{number} : trop petit pour un f32"));
                    None
                }
            }
            Json::Number(number) => {
                self.fault(path, format!("{number} : strictement positif attendu"));
                None
            }
            other => {
                self.fault(path, format!("un nombre attendu, {} trouvé", other.kind()));
                None
            }
        }
    }

    /// Un entier dans `[min, max]`.
    fn integer(&mut self, value: &Json, path: &str, min: u32, max: u32) -> Option<u32> {
        match value {
            Json::Number(number)
                if number.fract() == 0.0
                    && *number >= f64::from(min)
                    && *number <= f64::from(max) =>
            {
                Some(*number as u32)
            }
            Json::Number(number) => {
                self.fault(path, format!("{number} : entier de {min} à {max} attendu"));
                None
            }
            other => {
                self.fault(path, format!("un entier attendu, {} trouvé", other.kind()));
                None
            }
        }
    }

    fn root(&mut self, value: &Json) -> AssetOptions {
        let mut options = AssetOptions::default();
        let Some(entries) = self.object(value, "$") else {
            return options;
        };
        const KEYS: [&str; 12] = [
            "schema",
            "scale",
            "up_axis",
            "generate_tangents",
            "generate_normals",
            "lod",
            "collision",
            "deformation",
            "structure",
            "materials",
            "compress",
            "max_texture_size",
        ];
        if !entries.iter().any(|(key, _)| key == "schema") {
            self.faults.push(OptionFault {
                code: E_UNKNOWN_SCHEMA,
                path: "$.schema".to_owned(),
                message: format!("absent ; la version {OPTIONS_SCHEMA} est attendue"),
            });
        }
        for (key, value) in entries {
            let path = format!("$.{key}");
            match key.as_str() {
                "schema" => self.schema(value, &path),
                "scale" => {
                    if let Some(scale) = self.positive(value, &path) {
                        options.scale = scale;
                    }
                }
                "up_axis" => match self.text(value, &path) {
                    Some("y") => options.up_axis = UpAxis::Y,
                    Some("z") => options.up_axis = UpAxis::Z,
                    Some(other) => self.fault(&path, format!("« {other} » ; admis : y, z")),
                    None => {}
                },
                "generate_tangents" => {
                    if let Some(flag) = self.boolean(value, &path) {
                        options.generate_tangents = flag;
                    }
                }
                "generate_normals" => match self.text(value, &path) {
                    Some("if_missing") => options.generate_normals = NormalsMode::IfMissing,
                    Some("always") => options.generate_normals = NormalsMode::Always,
                    Some(other) => {
                        self.fault(&path, format!("« {other} » ; admis : if_missing, always"));
                    }
                    None => {}
                },
                "lod" => self.lod(value, &path, &mut options.lod),
                "collision" => self.collision(value, &path, &mut options.collision),
                "deformation" => {
                    self.deformation(value, &path);
                    self.warnings.push(
                        "options « deformation » validées, sans effet avant la déformation \
                         (C-28, M6)"
                            .to_owned(),
                    );
                }
                "structure" => {
                    self.structure(value, &path);
                    self.warnings.push(
                        "options « structure » validées, sans effet avant la structure \
                         (C-43, M7)"
                            .to_owned(),
                    );
                }
                "materials" => self.materials(value, &path, &mut options.materials),
                "compress" => {
                    if let Some(flag) = self.boolean(value, &path) {
                        options.compress = flag;
                    }
                }
                "max_texture_size" => {
                    if let Some(size) = self.integer(value, &path, 1, MAX_TEXTURE_SIZE) {
                        options.max_texture_size = size;
                    }
                }
                _ => self.unknown(&path, &KEYS),
            }
        }
        options
    }

    fn schema(&mut self, value: &Json, path: &str) {
        let found = match value {
            Json::Number(number) if *number == OPTIONS_SCHEMA as f64 => return,
            Json::Number(number) => number.to_string(),
            other => other.kind().to_owned(),
        };
        self.faults.push(OptionFault {
            code: E_UNKNOWN_SCHEMA,
            path: path.to_owned(),
            message: format!(
                "version inconnue ({found}) ; seule la version {OPTIONS_SCHEMA} est lue"
            ),
        });
    }

    fn lod(&mut self, value: &Json, path: &str, lod: &mut LodOptions) {
        const KEYS: [&str; 3] = ["enabled", "ratios", "error_target"];
        let Some(entries) = self.object(value, path) else {
            return;
        };
        for (key, value) in entries {
            let path = format!("{path}.{key}");
            match key.as_str() {
                "enabled" => {
                    if let Some(flag) = self.boolean(value, &path) {
                        lod.enabled = flag;
                    }
                }
                "ratios" => self.ratios(value, &path, lod),
                "error_target" => {
                    if let Some(target) = self.number(value, &path, 0.0, f64::from(f32::MAX)) {
                        lod.error_target = target;
                    }
                }
                _ => self.unknown(&path, &KEYS),
            }
        }
    }

    /// De 1 à [`MAX_LOD_LEVELS`] ratios dans `]0, 1]`, non croissants, le premier
    /// à 1 : le LOD 0 est la source.
    fn ratios(&mut self, value: &Json, path: &str, lod: &mut LodOptions) {
        let Json::Array(items) = value else {
            self.fault(path, format!("un tableau attendu, {} trouvé", value.kind()));
            return;
        };
        if items.is_empty() || items.len() > MAX_LOD_LEVELS {
            self.fault(
                path,
                format!(
                    "de 1 à {MAX_LOD_LEVELS} ratios attendus, {} trouvés",
                    items.len()
                ),
            );
            return;
        }
        let faults = self.faults.len();
        let mut ratios = [0.0f32; MAX_LOD_LEVELS];
        for (index, item) in items.iter().enumerate() {
            let item_path = format!("{path}[{index}]");
            if let Some(ratio) = self.positive(item, &item_path) {
                if ratio > 1.0 {
                    self.fault(&item_path, format!("{ratio} hors de ]0, 1]"));
                } else if index == 0 && ratio != 1.0 {
                    self.fault(&item_path, "le premier ratio est celui du LOD 0 : 1");
                } else if index > 0 && ratio > ratios[index - 1] {
                    self.fault(
                        &item_path,
                        "les ratios ne croissent pas d'un niveau au suivant",
                    );
                }
                ratios[index] = ratio;
            }
        }
        if self.faults.len() == faults {
            lod.levels = items.len() as u8;
            lod.ratios = ratios;
        }
    }

    fn collision(&mut self, value: &Json, path: &str, collision: &mut CollisionOptions) {
        const KEYS: [&str; 3] = ["mode", "max_hulls", "resolution"];
        let Some(entries) = self.object(value, path) else {
            return;
        };
        for (key, value) in entries {
            let path = format!("{path}.{key}");
            match key.as_str() {
                "mode" => {
                    if let Some(mode) = self.text(value, &path) {
                        match collider_mode(mode) {
                            Some(parsed) => collision.mode = parsed,
                            None => self.fault(
                                &path,
                                format!(
                                    "« {mode} » ; admis : none, auto_box, auto_sphere, \
                                     auto_capsule, auto_convex, auto_compound"
                                ),
                            ),
                        }
                    }
                }
                "max_hulls" => {
                    if let Some(hulls) = self.integer(value, &path, 1, MAX_HULLS) {
                        collision.max_hulls = hulls;
                    }
                }
                "resolution" => {
                    if let Some(resolution) = self.integer(value, &path, 1, u32::MAX) {
                        collision.resolution = Some(resolution);
                        self.warnings.push(
                            "option « collision.resolution » validée, sans effet tant que la \
                             résolution de la décomposition reste à mesurer (ADR-108)"
                                .to_owned(),
                        );
                    }
                }
                _ => self.unknown(&path, &KEYS),
            }
        }
    }

    fn materials(&mut self, value: &Json, path: &str, materials: &mut MaterialDefaults) {
        const KEYS: [&str; 2] = ["default_roughness", "default_metallic"];
        let Some(entries) = self.object(value, path) else {
            return;
        };
        for (key, value) in entries {
            let path = format!("{path}.{key}");
            match key.as_str() {
                "default_roughness" => {
                    if let Some(roughness) = self.number(value, &path, 0.0, 1.0) {
                        materials.roughness = roughness;
                    }
                }
                "default_metallic" => {
                    if let Some(metallic) = self.number(value, &path, 0.0, 1.0) {
                        materials.metallic = metallic;
                    }
                }
                _ => self.unknown(&path, &KEYS),
            }
        }
    }

    fn deformation(&mut self, value: &Json, path: &str) {
        const KEYS: [&str; 7] = [
            "enabled",
            "auto_regions",
            "target_cell_size",
            "max_resolution",
            "default_material",
            "rigid_margin",
            "anchor_borders",
        ];
        let Some(entries) = self.object(value, path) else {
            return;
        };
        for (key, value) in entries {
            let path = format!("{path}.{key}");
            match key.as_str() {
                "enabled" | "auto_regions" | "anchor_borders" => {
                    self.boolean(value, &path);
                }
                "target_cell_size" => {
                    self.positive(value, &path);
                }
                "rigid_margin" => {
                    self.number(value, &path, 0.0, f64::from(f32::MAX));
                }
                "max_resolution" => self.max_resolution(value, &path),
                "default_material" => {
                    if let Some(material) = self.text(value, &path) {
                        if !is_resource_id(material) {
                            self.fault(
                                &path,
                                format!("« {material} » : identifiant espace:chemin attendu"),
                            );
                        }
                    }
                }
                _ => self.unknown(&path, &KEYS),
            }
        }
    }

    fn max_resolution(&mut self, value: &Json, path: &str) {
        let Json::Array(items) = value else {
            self.fault(
                path,
                format!(
                    "un tableau de trois entiers attendu, {} trouvé",
                    value.kind()
                ),
            );
            return;
        };
        if items.len() != 3 {
            self.fault(
                path,
                format!("trois entiers attendus, {} trouvés", items.len()),
            );
            return;
        }
        for (index, item) in items.iter().enumerate() {
            self.integer(item, &format!("{path}[{index}]"), 1, u32::MAX);
        }
    }

    fn structure(&mut self, value: &Json, path: &str) {
        const KEYS: [&str; 2] = ["auto_links", "default_link"];
        let Some(entries) = self.object(value, path) else {
            return;
        };
        for (key, value) in entries {
            let path = format!("{path}.{key}");
            match key.as_str() {
                "auto_links" => {
                    self.boolean(value, &path);
                }
                "default_link" => {
                    if let Some(link) = self.text(value, &path) {
                        if !is_snake_case(link) {
                            self.fault(&path, format!("« {link} » : nom en snake_case attendu"));
                        }
                    }
                }
                _ => self.unknown(&path, &KEYS),
            }
        }
    }
}

/// Le mode de génération automatique de C-32 d'un nom, `none` compris. `convex`
/// n'en est pas : c'est une forme que l'auteur déclare sur un node.
fn collider_mode(name: &str) -> Option<ColliderMode> {
    match name {
        "none" => Some(ColliderMode::None),
        "auto_box" => Some(ColliderMode::AutoBox),
        "auto_sphere" => Some(ColliderMode::AutoSphere),
        "auto_capsule" => Some(ColliderMode::AutoCapsule),
        "auto_convex" => Some(ColliderMode::AutoConvex),
        "auto_compound" => Some(ColliderMode::Compound),
        _ => None,
    }
}

/// `[a-z0-9_]+`, sans tiret bas en tête ni en fin.
fn is_snake_case(text: &str) -> bool {
    !text.is_empty()
        && !text.starts_with('_')
        && !text.ends_with('_')
        && text
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
}

/// `espace:chemin`, aux caractères d'un `ResourceLocation` de Minecraft.
fn is_resource_id(text: &str) -> bool {
    let Some((namespace, path)) = text.split_once(':') else {
        return false;
    };
    let allowed = |byte: u8, extra: &[u8]| {
        byte.is_ascii_lowercase()
            || byte.is_ascii_digit()
            || b"_.-".contains(&byte)
            || extra.contains(&byte)
    };
    !namespace.is_empty()
        && !path.is_empty()
        && namespace.bytes().all(|byte| allowed(byte, b""))
        && path.bytes().all(|byte| allowed(byte, b"/"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// L'exemple de la PARTIE 6.4, tel quel.
    const EXEMPLE_6_4: &str = r#"{
  "schema": 1,
  "scale": 1.0, "up_axis": "y",
  "generate_tangents": true, "generate_normals": "if_missing",
  "lod": { "enabled": true, "ratios": [1.0, 0.5, 0.25, 0.1], "error_target": 0.02 },
  "collision": { "mode": "auto_convex", "max_hulls": 24, "resolution": 100000 },
  "deformation": {
    "enabled": true,
    "auto_regions": true,
    "target_cell_size": 0.12,
    "max_resolution": [12, 8, 12],
    "default_material": "axion:steel",
    "rigid_margin": 0.05,
    "anchor_borders": true
  },
  "structure": { "auto_links": true, "default_link": "weld" },
  "materials": { "default_roughness": 0.6, "default_metallic": 0.0 },
  "compress": true, "max_texture_size": 2048
}"#;

    fn fautes(text: &str) -> Vec<(i32, String)> {
        parse(text)
            .expect_err("refus attendu")
            .faults
            .into_iter()
            .map(|fault| (fault.code, fault.path))
            .collect()
    }

    #[test]
    fn options_les_defauts_sont_ceux_de_la_compilation_d_aujourd_hui() {
        let options = AssetOptions::default();
        assert_eq!(options.lod, LodOptions::DEFAULT);
        assert_eq!(
            options.collision.mode,
            ColliderMode::None,
            "ADR-135 point 4"
        );
        assert_eq!(options.collision.max_hulls, MAX_HULLS);
        assert_eq!(options.scale, 1.0);
        assert_eq!(options.up_axis, UpAxis::Y);
        assert!(options.compress && options.generate_tangents);
        // Un fichier qui ne dit que sa version rend exactement les défauts.
        let lu = parse(r#"{ "schema": 1 }"#).unwrap();
        assert_eq!(
            lu.options,
            AssetOptions {
                origin: OptionsOrigin::File,
                ..options
            }
        );
        assert!(lu.warnings.is_empty());
    }

    #[test]
    fn options_l_exemple_de_la_partie_6_4_se_lit() {
        let lu = parse(EXEMPLE_6_4).expect("l'exemple du cahier des charges est valide");
        assert_eq!(lu.options.collision.mode, ColliderMode::AutoConvex);
        assert_eq!(lu.options.collision.max_hulls, 24);
        assert_eq!(lu.options.collision.resolution, Some(100_000));
        assert_eq!(lu.options.lod, LodOptions::DEFAULT);
        // Trois options sans effet aujourd'hui, chacune signalée.
        assert_eq!(lu.warnings.len(), 3, "{:?}", lu.warnings);
    }

    #[test]
    fn options_les_valeurs_donnees_remplacent_les_defauts() {
        let lu = parse(
            r#"{ "schema": 1, "scale": 0.5, "up_axis": "z", "generate_normals": "always",
                 "generate_tangents": false, "compress": false, "max_texture_size": 512,
                 "lod": { "ratios": [1, 0.3] },
                 "materials": { "default_roughness": 0.2, "default_metallic": 1 } }"#,
        )
        .unwrap();
        let options = lu.options;
        assert_eq!(options.scale, 0.5);
        assert_eq!(options.up_axis, UpAxis::Z);
        assert_eq!(options.generate_normals, NormalsMode::Always);
        assert!(!options.generate_tangents && !options.compress);
        assert_eq!(options.max_texture_size, 512);
        assert_eq!(options.lod.levels, 2);
        assert_eq!(&options.lod.ratios[..2], &[1.0, 0.3]);
        assert_eq!(options.materials.roughness, 0.2);
        assert_eq!(options.materials.metallic, 1.0);
    }

    #[test]
    fn options_schema_absent_ou_inconnu_est_refuse() {
        assert_eq!(
            fautes("{}"),
            vec![(E_UNKNOWN_SCHEMA, "$.schema".to_owned())]
        );
        assert_eq!(
            fautes(r#"{ "schema": 2 }"#),
            vec![(E_UNKNOWN_SCHEMA, "$.schema".to_owned())]
        );
        assert_eq!(
            fautes(r#"{ "schema": "1" }"#),
            vec![(E_UNKNOWN_SCHEMA, "$.schema".to_owned())]
        );
    }

    #[test]
    fn options_une_cle_inconnue_est_refusee_a_son_chemin() {
        assert_eq!(
            fautes(r#"{ "schema": 1, "scal": 2 }"#),
            vec![(E_INVALID_OPTION, "$.scal".to_owned())]
        );
        assert_eq!(
            fautes(r#"{ "schema": 1, "lod": { "ratio": [1] } }"#),
            vec![(E_INVALID_OPTION, "$.lod.ratio".to_owned())]
        );
    }

    #[test]
    fn options_toutes_les_fautes_sont_rendues_dans_l_ordre_du_document() {
        assert_eq!(
            fautes(
                r#"{ "schema": 1, "scale": -1, "up_axis": "x",
                     "lod": { "ratios": [1, 0.5, 0.75] },
                     "collision": { "mode": "auto_cube", "max_hulls": 33 },
                     "materials": { "default_roughness": 1.5 },
                     "max_texture_size": 8192 }"#
            ),
            vec![
                (E_INVALID_OPTION, "$.scale".to_owned()),
                (E_INVALID_OPTION, "$.up_axis".to_owned()),
                (E_INVALID_OPTION, "$.lod.ratios[2]".to_owned()),
                (E_INVALID_OPTION, "$.collision.mode".to_owned()),
                (E_INVALID_OPTION, "$.collision.max_hulls".to_owned()),
                (E_INVALID_OPTION, "$.materials.default_roughness".to_owned()),
                (E_INVALID_OPTION, "$.max_texture_size".to_owned()),
            ]
        );
    }

    #[test]
    fn options_le_json_est_lu_strictement() {
        for (texte, cas) in [
            (
                r#"{ "schema": 1, "scale": 1, "scale": 2 }"#,
                "clé en double",
            ),
            (r#"{ "schema": 1 } { }"#, "contenu après la racine"),
            ("\u{feff}{ \"schema\": 1 }", "BOM"),
            (r#"{ "schema": 1, }"#, "virgule finale"),
            (r#"{ schema: 1 }"#, "clé sans guillemets"),
            ("[1, 2]", "racine qui n'est pas un objet"),
        ] {
            assert!(parse(texte).is_err(), "{cas} accepté");
        }
        assert!(
            parse_bytes(&[b'{', 0xff, b'}']).is_err(),
            "UTF-8 invalide accepté"
        );
    }

    #[test]
    fn options_les_options_des_systemes_a_venir_sont_validees() {
        assert_eq!(
            fautes(
                r#"{ "schema": 1,
                     "deformation": { "default_material": "acier", "max_resolution": [12, 0, 12] },
                     "structure": { "default_link": "Soudure" } }"#
            ),
            vec![
                (
                    E_INVALID_OPTION,
                    "$.deformation.default_material".to_owned()
                ),
                (
                    E_INVALID_OPTION,
                    "$.deformation.max_resolution[1]".to_owned()
                ),
                (E_INVALID_OPTION, "$.structure.default_link".to_owned()),
            ]
        );
    }

    #[test]
    fn options_le_bloc_axion_des_extras_gltf_se_lit_comme_un_fichier() {
        let bloc = gltf_root_block(
            r#"{ "blender": { "version": 4 }, "axion": { "schema": 1, "scale": 2 } }"#,
        )
        .unwrap()
        .expect("bloc présent");
        let lu = parse_gltf_block(&bloc).unwrap();
        assert_eq!(lu.options.scale, 2.0);
        assert_eq!(lu.options.origin, OptionsOrigin::GltfExtras);
        // Ses fautes se situent dans les extras.
        let refus = parse_gltf_block(r#"{ "schema": 1, "scal": 2 }"#).unwrap_err();
        assert_eq!(refus.faults[0].path, "$.extras.axion.scal");
    }

    #[test]
    fn options_des_extras_sans_bloc_axion_n_en_donnent_pas() {
        for extras in [r#"{ "blender": 1 }"#, "[1, 2]", r#""texte""#, "3", "null"] {
            assert_eq!(gltf_root_block(extras).unwrap(), None, "{extras}");
        }
        assert!(gltf_root_block(r#"{ "axion": {}, "axion": {} }"#).is_err());
    }

    #[test]
    fn options_le_refus_se_lit_avec_ses_codes() {
        let refus = parse(r#"{ "schema": 1, "scal": 2 }"#).unwrap_err();
        assert_eq!(refus.code(), E_INVALID_OPTION);
        assert!(
            refus
                .to_string()
                .starts_with("E-7001 $.scal : clé inconnue"),
            "{refus}"
        );
    }
}
