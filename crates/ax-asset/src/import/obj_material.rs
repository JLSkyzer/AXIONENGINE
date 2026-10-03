//! Matériaux MTL → DM-05, textures → `TEXR` (C-21, C-26, ADR-122 §3).
//!
//! Le MTL décrit un modèle de Phong, que DM-05 ne connaît pas. La traduction
//! est celle d'ADR-122 §3 : `Kd` et `d` — à défaut `1 − Tr` — pour l'albedo,
//! translucide sous une opacité de 1 ; `Ke` pour l'émission ; `Ns` pour la
//! rugosité ; `map_Kd`, `norm` ou `map_Bump`/`bump`, et `map_Ke` pour les
//! textures, toutes en ressource (R-531) : le compilateur ne les lit pas, Java
//! les chargera des resource packs. L'extension PBR du format, `Pr` et `Pm`,
//! prime sur `Ns` quand elle est donnée.
//!
//! # Les options d'une instruction de texture
//!
//! `map_Kd -s 2 2 -o 0.5 0 bois.png` : le format place ses options **avant**
//! le fichier, et Blender en écrit — `-bm` pour la force d'une carte de relief.
//! `tobj` garde toute la fin de ligne comme nom de fichier ; les options sont
//! donc lues ici. `-s` et `-o` de `map_Kd` sont cuites dans `uv0` comme la
//! `KHR_texture_transform` de glTF, `-bm` devient `normal_scale`, `-clamp on`
//! l'échantillonneur ; les autres n'ont pas d'équivalent et sont averties.
//!
//! # De `Ns` à la rugosité
//!
//! L'équivalence de Walter et al. (2007) entre un exposant de Phong `n` et une
//! distribution de Beckmann donne `α = √(2 / (n + 2))`. DM-05 porte la
//! rugosité **perceptuelle** de glTF, dont `α` est le carré : la rugosité
//! retenue est donc `√α = (2 / (n + 2))^¼`. ADR-122 écrivait `√(2 / (n + 2))`,
//! soit `α` lui-même, ce qui rendait un OBJ plus brillant que le même modèle en
//! glTF ; la correction y est consignée.

use super::material::{
    neutral_material, ImageKey, TextureTableBuilder, UvMapping, DEFAULT_METALLIC, DEFAULT_ROUGHNESS,
};
use super::{check_relative_path, ImportError, ImportedAsset, ImportedMaterial};
use ax_model::dm::material::{blend_mode, texture_sampler, MaterialDesc};

/// Traduit une bibliothèque de matériaux en DM-05 et range ses textures.
///
/// Rend la projection de chaque matériau, dans l'ordre de la bibliothèque.
///
/// # Errors
///
/// [`ImportError::ExternalPath`] si une texture retenue sort du répertoire de
/// l'asset (R-531). Une instruction de texture illisible n'est pas une
/// erreur : elle est avertie, et son slot reste vide.
pub(super) fn convert_library(
    library: &[tobj::Material],
    asset: &mut ImportedAsset,
) -> Result<Vec<UvMapping>, ImportError> {
    let mut importer = Importer::default();
    let mut mappings = Vec::with_capacity(library.len());
    for material in library {
        let (desc, mapping) = importer.convert(material)?;
        asset.names.push(("matériau", material.name.clone()));
        asset.materials.push(ImportedMaterial {
            name: material.name.clone(),
            desc,
        });
        mappings.push(mapping);
    }
    let (textures, refusals) = importer.builder.finish();
    asset.textures = textures;
    asset.texture_refusals = refusals;
    asset.material_warnings.extend(importer.warnings);
    Ok(mappings)
}

/// État de la traduction : la table des textures en construction, et ce qui
/// a été dit.
#[derive(Default)]
struct Importer {
    builder: TextureTableBuilder,
    warnings: Vec<String>,
}

impl Importer {
    fn warn(&mut self, message: String) {
        self.warnings.push(message);
    }

    /// Un matériau MTL, traduit en DM-05 (ADR-122 §3).
    fn convert(
        &mut self,
        material: &tobj::Material,
    ) -> Result<(MaterialDesc, UvMapping), ImportError> {
        let label = format!("« {} »", material.name);
        let roughness = self.roughness(material, &label);
        let metallic = self
            .param(material, &label, "Pm")
            .unwrap_or(DEFAULT_METALLIC);
        let mut desc = neutral_material(&material.name, metallic, roughness);

        let diffuse = material.diffuse.unwrap_or([1.0; 3]);
        let alpha = self.alpha(material, &label);
        desc.albedo_factor = [diffuse[0], diffuse[1], diffuse[2], alpha];
        if alpha < 1.0 {
            desc.blend_mode = blend_mode::TRANSLUCENT;
        }
        if let Some(emissive) = material.emissive {
            desc.emissive_factor = emissive;
        }

        // `map_Kd` fixe la projection de `uv0` ; les autres cartes s'y comparent.
        let mut mapping = UvMapping::IDENTITY;
        if let Some(statement) = &material.diffuse_texture {
            if let Some(map) = self.map(&label, "map_Kd", statement, false)? {
                mapping = map.mapping();
                desc.albedo_tex = self.texture(&map);
            }
        }

        // `norm` est l'extension PBR du format et désigne une normal map sans
        // ambiguïté : elle prime sur `map_Bump` et `bump`, que `tobj` range au
        // même endroit.
        let normal = match (
            material.unknown_param.get("norm"),
            material.normal_texture.as_deref(),
        ) {
            (Some(norm), bump) => {
                if bump.is_some() {
                    self.warn(format!(
                        "matériau {label} : norm et map_Bump donnés, norm retenue"
                    ));
                }
                Some(("norm", norm.as_str()))
            }
            (None, Some(bump)) => Some(("map_Bump", bump)),
            (None, None) => None,
        };
        if let Some((keyword, statement)) = normal {
            if let Some(map) = self.map(&label, keyword, statement, true)? {
                self.compare(&label, keyword, map.mapping(), mapping);
                desc.normal_tex = self.texture(&map);
                if let Some(multiplier) = map.bump_multiplier {
                    desc.normal_scale = multiplier;
                }
            }
        }

        if let Some(statement) = material.unknown_param.get("map_Ke") {
            if let Some(map) = self.map(&label, "map_Ke", statement, false)? {
                self.compare(&label, "map_Ke", map.mapping(), mapping);
                desc.emissive_tex = self.texture(&map);
            }
        }

        self.unmapped(material, &label);
        Ok((desc, mapping))
    }

    /// Rugosité : `Pr` si l'extension PBR la donne, sinon celle que `Ns`
    /// implique, sinon la valeur par défaut du §6.4.
    fn roughness(&mut self, material: &tobj::Material, label: &str) -> f32 {
        if let Some(roughness) = self.param(material, label, "Pr") {
            return roughness;
        }
        match material.shininess {
            Some(exponent) if exponent.is_finite() => shininess_to_roughness(exponent),
            Some(exponent) => {
                self.warn(format!(
                    "matériau {label} : Ns {exponent} non fini, rugosité par défaut"
                ));
                DEFAULT_ROUGHNESS
            }
            None => DEFAULT_ROUGHNESS,
        }
    }

    /// Opacité : `d`, sinon `1 − Tr`, sinon opaque ; ramenée dans `[0, 1]`.
    fn alpha(&mut self, material: &tobj::Material, label: &str) -> f32 {
        let alpha = match material.dissolve {
            Some(dissolve) => dissolve,
            None => self
                .param(material, label, "Tr")
                .map_or(1.0, |transparency| 1.0 - transparency),
        };
        // Une valeur non finie reste telle : le contrôle de DM-05 la refusera,
        // en la nommant, plutôt qu'une opacité inventée ici.
        if alpha.is_finite() {
            alpha.clamp(0.0, 1.0)
        } else {
            alpha
        }
    }

    /// Un paramètre numérique que `tobj` laisse brut — `Pr`, `Pm`, `Tr`.
    fn param(&mut self, material: &tobj::Material, label: &str, key: &str) -> Option<f32> {
        let raw = material.unknown_param.get(key)?;
        let value = raw
            .split_whitespace()
            .next()
            .and_then(|word| word.parse::<f32>().ok());
        if value.is_none() {
            self.warn(format!(
                "matériau {label} : {key} « {raw} » illisible, ignoré"
            ));
        }
        value
    }

    /// Lit une instruction de texture ; `None`, avertie, si elle est illisible.
    ///
    /// # Errors
    ///
    /// [`ImportError::ExternalPath`] si son fichier sort du répertoire de
    /// l'asset (R-531) : ce n'est pas une erreur de l'auteur, c'est une
    /// tentative.
    fn map<'s>(
        &mut self,
        label: &str,
        keyword: &str,
        statement: &'s str,
        bump: bool,
    ) -> Result<Option<MapStatement<'s>>, ImportError> {
        let map = match parse_map(statement, bump) {
            Ok(map) => map,
            Err(reason) => {
                self.warn(format!(
                    "matériau {label} : {keyword} « {statement} » illisible ({reason}), \
                     texture ignorée"
                ));
                return Ok(None);
            }
        };
        check_relative_path(map.path)?;
        if !map.ignored.is_empty() {
            self.warn(format!(
                "matériau {label} : {keyword} — option(s) {} sans équivalent DM-05, \
                 ignorée(s)",
                map.ignored.join(", ")
            ));
        }
        Ok(Some(map))
    }

    /// L'entrée de `TEXR` d'une instruction de texture, en ressource.
    fn texture(&mut self, map: &MapStatement<'_>) -> u16 {
        let key = ImageKey::Path(map.path.to_owned());
        self.builder.add_resource(key.clone(), map.path, None);
        self.builder.texture(key, map.sampler())
    }

    /// Avertit d'une carte projetée autrement que `map_Kd`.
    fn compare(&mut self, label: &str, keyword: &str, own: UvMapping, reference: UvMapping) {
        if own != reference {
            self.warn(format!(
                "matériau {label} : {keyword} projetée autrement que map_Kd (-s, -o) — \
                 échantillonnée selon la projection de map_Kd, la seule que uv0 porte"
            ));
        }
    }

    /// Avertit des cartes que DM-05 n'a pas de slot pour porter.
    fn unmapped(&mut self, material: &tobj::Material, label: &str) {
        let mut ignored: Vec<&str> = [
            (material.ambient_texture.is_some(), "map_Ka"),
            (material.specular_texture.is_some(), "map_Ks"),
            (material.shininess_texture.is_some(), "map_Ns"),
            (material.dissolve_texture.is_some(), "map_d"),
        ]
        .into_iter()
        .filter_map(|(present, keyword)| present.then_some(keyword))
        .collect();
        // `unknown_param` est une table de hachage : son ordre change d'une
        // exécution à l'autre, et le message ne doit pas changer avec lui.
        let mut raw: Vec<&str> = material
            .unknown_param
            .keys()
            .map(String::as_str)
            .filter(|keyword| {
                (keyword.starts_with("map_") && *keyword != "map_Ke")
                    || matches!(*keyword, "refl" | "disp" | "decal")
            })
            .collect();
        raw.sort_unstable();
        ignored.extend(raw);
        if !ignored.is_empty() {
            self.warn(format!(
                "matériau {label} : {} sans slot DM-05, ignorée(s)",
                ignored.join(", ")
            ));
        }
    }
}

/// Rugosité perceptuelle de DM-05 pour un exposant de Phong : `(2 / (n + 2))^¼`.
///
/// Un exposant négatif n'a pas de sens ; il est lu comme nul, la surface la
/// plus rugueuse.
fn shininess_to_roughness(exponent: f32) -> f32 {
    (2.0 / (exponent.max(0.0) + 2.0)).sqrt().sqrt()
}

/// Une instruction de texture MTL, options lues.
#[derive(Debug, Clone, PartialEq)]
struct MapStatement<'s> {
    /// Fichier désigné, espaces intérieurs compris.
    path: &'s str,
    /// `-bm` : multiplicateur d'une carte de relief.
    bump_multiplier: Option<f32>,
    /// `-clamp on`.
    clamp: bool,
    /// `-o` : décalage de l'origine.
    offset: [f32; 2],
    /// `-s` : échelle.
    scale: [f32; 2],
    /// Options lues mais sans équivalent DM-05.
    ignored: Vec<&'s str>,
}

impl MapStatement<'_> {
    /// Projection de `-s` puis `-o`, dans l'espace du MTL.
    fn mapping(&self) -> UvMapping {
        UvMapping::from_transform(0, self.offset, 0.0, self.scale)
    }

    /// Le MTL ne déclare pas de filtrage : seul `-clamp` compte.
    fn sampler(&self) -> u8 {
        if self.clamp {
            texture_sampler::CLAMP_U | texture_sampler::CLAMP_V
        } else {
            texture_sampler::FILTER_UNDECLARED
        }
    }
}

/// Lit `[-option arguments…] fichier`.
///
/// Les options et leurs arguments sont ceux de la spécification MTL : `-o`,
/// `-s` et `-t` prennent d'une à trois valeurs, `-mm` deux, les autres une.
/// Une option inconnue rend l'instruction illisible plutôt que d'être prise
/// pour un nom de fichier. `-bm` n'a de sens que pour une carte de relief
/// (`bump`) ; ailleurs, elle est ignorée et dite.
fn parse_map(statement: &str, bump: bool) -> Result<MapStatement<'_>, String> {
    let mut map = MapStatement {
        path: "",
        bump_multiplier: None,
        clamp: false,
        offset: [0.0; 2],
        scale: [1.0; 2],
        ignored: Vec::new(),
    };
    let mut rest = statement.trim();
    while rest.starts_with('-') {
        let (option, after) = split_word(rest);
        rest = after;
        match option {
            "-clamp" => {
                let (value, after) = split_word(rest);
                map.clamp = match value {
                    "on" => true,
                    "off" => false,
                    _ => return Err(format!("-clamp attend on ou off, pas « {value} »")),
                };
                rest = after;
            }
            "-bm" => {
                let (value, after) = split_word(rest);
                let multiplier = value
                    .parse::<f32>()
                    .map_err(|_| format!("-bm attend un nombre, pas « {value} »"))?;
                if bump {
                    map.bump_multiplier = Some(multiplier);
                } else {
                    map.ignored.push(option);
                }
                rest = after;
            }
            "-o" | "-s" | "-t" => {
                let (values, after) = numbers(rest);
                let Some(first) = values.first().copied() else {
                    return Err(format!("{option} sans valeur"));
                };
                match option {
                    "-o" => map.offset = [first, values.get(1).copied().unwrap_or(0.0)],
                    "-s" => map.scale = [first, values.get(1).copied().unwrap_or(1.0)],
                    _ => map.ignored.push(option),
                }
                rest = after;
            }
            "-mm" => {
                rest = skip_words(rest, 2).ok_or_else(|| format!("{option} incomplète"))?;
                map.ignored.push(option);
            }
            "-blendu" | "-blendv" | "-cc" | "-boost" | "-texres" | "-imfchan" | "-type" => {
                rest = skip_words(rest, 1).ok_or_else(|| format!("{option} incomplète"))?;
                map.ignored.push(option);
            }
            _ => return Err(format!("option « {option} » inconnue")),
        }
    }
    if rest.is_empty() {
        return Err("fichier absent".to_owned());
    }
    map.path = rest;
    Ok(map)
}

/// Le premier mot d'un texte, et le reste sans ses espaces de tête.
fn split_word(text: &str) -> (&str, &str) {
    let text = text.trim_start();
    match text.find(char::is_whitespace) {
        Some(end) => (&text[..end], text[end..].trim_start()),
        None => (text, ""),
    }
}

/// Saute `count` mots ; `None` s'il en manque.
fn skip_words(mut text: &str, count: usize) -> Option<&str> {
    for _ in 0..count {
        let (word, after) = split_word(text);
        if word.is_empty() {
            return None;
        }
        text = after;
    }
    Some(text)
}

/// Lit d'une à trois valeurs numériques en tête de texte.
fn numbers(mut text: &str) -> (Vec<f32>, &str) {
    let mut values = Vec::new();
    while values.len() < 3 {
        let (word, after) = split_word(text);
        match word.parse::<f32>() {
            Ok(value) if !word.is_empty() => {
                values.push(value);
                text = after;
            }
            _ => break,
        }
    }
    (values, text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t271_une_instruction_sans_option_designe_son_fichier() {
        let map = parse_map("textures/bois clair.png", false).expect("lisible");
        // Un nom de fichier peut porter des espaces : seul le début est lu
        // comme options.
        assert_eq!(map.path, "textures/bois clair.png");
        assert_eq!(map.mapping(), UvMapping::IDENTITY);
        assert_eq!(map.sampler(), texture_sampler::FILTER_UNDECLARED);
    }

    #[test]
    fn t271_les_options_de_blender_sont_lues_et_non_prises_pour_le_fichier() {
        // Ce que tobj rendait comme chemin : « -bm 0.500000 relief.png ».
        let relief = parse_map("-bm 0.500000 relief.png", true).expect("lisible");
        assert_eq!(relief.path, "relief.png");
        assert_eq!(relief.bump_multiplier, Some(0.5));

        let bois = parse_map("-s 2 4 1 -o 0.5 0.25 -clamp on bois.png", false).expect("lisible");
        assert_eq!(bois.path, "bois.png");
        assert_eq!(bois.scale, [2.0, 4.0]);
        assert_eq!(bois.offset, [0.5, 0.25]);
        assert_eq!(
            bois.sampler(),
            texture_sampler::CLAMP_U | texture_sampler::CLAMP_V
        );
        assert_eq!(
            bois.mapping(),
            UvMapping::from_transform(0, [0.5, 0.25], 0.0, [2.0, 4.0])
        );
    }

    #[test]
    fn t271_une_option_sans_equivalent_est_dite_une_option_inconnue_refusee() {
        let map = parse_map("-mm 0 1 -t 0.1 -blendu off -bm 2 tache.png", false).expect("lisible");
        assert_eq!(map.path, "tache.png");
        assert_eq!(map.ignored, ["-mm", "-t", "-blendu", "-bm"]);
        assert_eq!(map.bump_multiplier, None, "-bm hors d'une carte de relief");

        assert!(parse_map("-inventee 3 tache.png", false).is_err());
        assert!(parse_map("-clamp peut-etre tache.png", false).is_err());
        assert!(parse_map("-s", false).is_err());
        assert!(parse_map("-o 0.5 0.5", false).is_err(), "fichier absent");
    }

    #[test]
    fn t271_ns_donne_la_rugosite_perceptuelle() {
        // α = √(2 / (n + 2)) (Walter et al. 2007) ; DM-05 porte √α.
        assert_eq!(shininess_to_roughness(0.0), 1.0);
        let attendue = (2.0f32 / 1002.0).sqrt().sqrt();
        assert!((shininess_to_roughness(1000.0) - attendue).abs() < 1e-6);
        assert!((shininess_to_roughness(1000.0) - 0.211).abs() < 1e-3);
        assert_eq!(
            shininess_to_roughness(-5.0),
            1.0,
            "un exposant négatif est nul"
        );
    }
}
