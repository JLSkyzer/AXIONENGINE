//! Annotations `axion` des `extras` glTF (PARTIE 8.2).
//!
//! Toutes les annotations vivent sous une clé unique `axion`. C'est **le seul
//! mécanisme d'annotation**, et il fonctionne sans l'addon Blender : un auteur
//! peut les écrire à la main dans les propriétés personnalisées.
//!
//! Deux règles gouvernent la lecture :
//!
//! - **R-911** : un extras inconnu est **conservé**, jamais interprété. Il part
//!   en section `EXTR` et l'API l'expose. Deviner ce qu'un auteur a voulu dire
//!   produirait un comportement que personne n'a demandé ;
//! - **R-913** : **aucune annotation n'est obligatoire.** Un GLB brut donne un
//!   asset complet ; les annotations contrôlent, elles n'activent pas. C'est
//!   pourquoi rien ici ne rend d'erreur sur une annotation absente.

use core::fmt;

/// Rôle d'un node, tel que la PARTIE 8.2 les énumère.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum NodeRole {
    /// Géométrie rendue. C'est le défaut.
    #[default]
    Mesh,
    /// Volume de collision.
    Collider,
    /// Roue.
    Wheel,
    /// Siège.
    Seat,
    /// Point d'attache nommé.
    Socket,
    /// Source de lumière.
    Light,
    /// Racine d'une part.
    PartRoot,
    /// Zone de dommage.
    DamageZone,
    /// Région de déformation.
    DeformRegion,
    /// Ancrage de tissu.
    ClothAnchor,
    /// Ensemble de particules.
    ParticleSet,
    /// Élément interne.
    Internal,
    /// Node non rendu.
    Hidden,
}

impl NodeRole {
    /// Traduit la valeur textuelle d'un `role`.
    ///
    /// Rend `None` sur un rôle inconnu. R-912 veut alors un avertissement et le
    /// défaut : c'est à l'appelant de le journaliser, ce module ne fait que
    /// lire.
    #[must_use]
    pub fn parse(value: &str) -> Option<Self> {
        Some(match value {
            "mesh" => NodeRole::Mesh,
            "collider" => NodeRole::Collider,
            "wheel" => NodeRole::Wheel,
            "seat" => NodeRole::Seat,
            "socket" => NodeRole::Socket,
            "light" => NodeRole::Light,
            "part_root" => NodeRole::PartRoot,
            "damage_zone" => NodeRole::DamageZone,
            "deform_region" => NodeRole::DeformRegion,
            "cloth_anchor" => NodeRole::ClothAnchor,
            "particle_set" => NodeRole::ParticleSet,
            "internal" => NodeRole::Internal,
            "hidden" => NodeRole::Hidden,
            _ => return None,
        })
    }

    /// Nom du rôle, tel que la convention l'écrit.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            NodeRole::Mesh => "mesh",
            NodeRole::Collider => "collider",
            NodeRole::Wheel => "wheel",
            NodeRole::Seat => "seat",
            NodeRole::Socket => "socket",
            NodeRole::Light => "light",
            NodeRole::PartRoot => "part_root",
            NodeRole::DamageZone => "damage_zone",
            NodeRole::DeformRegion => "deform_region",
            NodeRole::ClothAnchor => "cloth_anchor",
            NodeRole::ParticleSet => "particle_set",
            NodeRole::Internal => "internal",
            NodeRole::Hidden => "hidden",
        }
    }

    /// Indique si un node de ce rôle est rendu (R-910).
    ///
    /// Un collider, un socket, une zone de dommage, une région de déformation
    /// et un ancrage de tissu décrivent des volumes de travail : les afficher
    /// montrerait à l'utilisateur des boîtes que l'auteur n'a jamais voulu voir.
    #[must_use]
    pub const fn is_rendered(self) -> bool {
        !matches!(
            self,
            NodeRole::Collider
                | NodeRole::Socket
                | NodeRole::DamageZone
                | NodeRole::DeformRegion
                | NodeRole::ClothAnchor
                | NodeRole::Hidden
        )
    }
}

impl fmt::Display for NodeRole {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.name())
    }
}

/// Annotations lues sur un node.
///
/// Ce que ce type **n'interprète pas** : les régions de déformation, les
/// liaisons structurelles et les zones de dommage. Elles demandent de résoudre
/// des noms, de calculer des OBB et de construire un graphe — c'est C-28, et le
/// faire ici mélangerait la lecture d'un format avec la compilation d'un modèle.
/// Le JSON brut est conservé pour lui.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct NodeAnnotations {
    /// Rôle déclaré, ou le défaut.
    pub role: NodeRole,
    /// Nom de la part d'appartenance.
    pub part: Option<String>,
    /// Matériau physique, par son identifiant.
    pub material: Option<String>,
    /// Forme de collision réclamée sur un node `role=collider` (C-32) :
    /// `auto_box`, `auto_sphere`, `auto_capsule`, `auto_convex`, `auto_compound`.
    /// Brut ici ; interprété par C-32, qui avertit sur une forme non prise en
    /// charge (R-912).
    pub collider_shape: Option<String>,
    /// Masse volumique déclarée du collider, en kg/m³ (C-32, R-622). À défaut, le
    /// défaut du générateur s'applique ; la validation C-22 exige `> 0`.
    pub collider_density: Option<f32>,
    /// Le collider est-il figé au refit (`no_refit`, R-623) ? Un châssis structurel
    /// que la déformation ne doit pas réajuster.
    pub collider_no_refit: bool,
    /// Niveaux de détail où le node apparaît.
    pub lod: Vec<u8>,
    /// Groupe de sommets portant le poids de déformation.
    pub deform_weight_group: Option<String>,
    /// Profil d'usure.
    pub wear_profile: Option<String>,
    /// Annotation textuelle brute, conservée telle quelle (R-911).
    ///
    /// Elle part en section `EXTR`. Ce qui n'est pas compris ici n'est pas
    /// perdu : C-28 y lira ce qui le concerne, et l'API l'expose au reste.
    pub raw: Option<String>,
    /// Ce qui n'a pas pu être lu, pour journalisation (R-912).
    pub warnings: Vec<String>,
}

impl NodeAnnotations {
    /// Lit les annotations d'un objet `extras` glTF.
    ///
    /// L'objet est le JSON brut du node. Une clé `axion` absente rend les
    /// annotations par défaut, ce qui est le cas courant : R-913 veut qu'un GLB
    /// brut produise un asset complet.
    #[must_use]
    pub fn parse(extras: &str) -> Self {
        let mut annotations = Self {
            raw: Some(extras.to_owned()),
            ..Self::default()
        };

        let Some(axion) = object_value(extras, "axion") else {
            return annotations;
        };

        if let Some(role) = string_value(&axion, "role") {
            match NodeRole::parse(&role) {
                Some(parsed) => annotations.role = parsed,
                // R-912 : avertissement et défaut. Deviner reviendrait à
                // classer un node dans une catégorie que l'auteur n'a pas
                // choisie.
                None => annotations
                    .warnings
                    .push(format!("rôle « {role} » inconnu, « mesh » retenu")),
            }
        }
        annotations.part = string_value(&axion, "part");
        annotations.material = string_value(&axion, "material");
        annotations.collider_shape = string_value(&axion, "shape");
        annotations.collider_density = number_value(&axion, "density");
        annotations.collider_no_refit = bool_value(&axion, "no_refit").unwrap_or(false);
        annotations.deform_weight_group = string_value(&axion, "deform_weight_group");
        annotations.wear_profile = string_value(&axion, "wear_profile");
        annotations.lod = number_array(&axion, "lod");

        annotations
    }
}

/// Extrait la valeur d'une clé dont la valeur est un objet.
///
/// Un analyseur minimal, et volontairement : la table 32.2 écarte un analyseur
/// JSON du runtime, le JSON étant lu côté Java par Gson. Ce qui est cherché ici
/// tient en trois formes — une chaîne, un tableau de nombres, un objet — et le
/// reste est conservé brut pour qui saura le lire.
fn object_value(source: &str, key: &str) -> Option<String> {
    let start = find_key(source, key)?;
    let rest = &source[start..];
    let open = rest.find('{')?;

    let mut depth = 0usize;
    let mut in_string = false;
    let mut escaped = false;
    for (index, character) in rest[open..].char_indices() {
        if in_string {
            if escaped {
                escaped = false;
            } else if character == '\\' {
                escaped = true;
            } else if character == '"' {
                in_string = false;
            }
            continue;
        }
        match character {
            '"' => in_string = true,
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    return Some(rest[open..=open + index].to_owned());
                }
            }
            _ => {}
        }
    }
    None
}

/// Extrait la valeur d'une clé dont la valeur est une chaîne.
fn string_value(source: &str, key: &str) -> Option<String> {
    let start = find_key(source, key)?;
    let rest = source[start..].trim_start();
    let mut characters = rest.char_indices();
    if characters.next()?.1 != '"' {
        return None;
    }

    let mut value = String::new();
    let mut escaped = false;
    for (_, character) in characters {
        if escaped {
            value.push(match character {
                'n' => '\n',
                't' => '\t',
                'r' => '\r',
                other => other,
            });
            escaped = false;
            continue;
        }
        match character {
            '\\' => escaped = true,
            '"' => return Some(value),
            other => value.push(other),
        }
    }
    None
}

/// Extrait la valeur d'une clé dont la valeur est un tableau de nombres.
fn number_array(source: &str, key: &str) -> Vec<u8> {
    let Some(start) = find_key(source, key) else {
        return Vec::new();
    };
    let rest = source[start..].trim_start();
    if !rest.starts_with('[') {
        return Vec::new();
    }
    let Some(end) = rest.find(']') else {
        return Vec::new();
    };

    rest[1..end]
        .split(',')
        .filter_map(|item| item.trim().parse::<u8>().ok())
        .collect()
}

/// Extrait la valeur d'une clé dont la valeur est un nombre scalaire.
///
/// Lit le jeton jusqu'au prochain séparateur (`,`, `}`, `]` ou espace) et le
/// convertit ; rend `None` si la clé est absente ou le jeton non numérique.
fn number_value(source: &str, key: &str) -> Option<f32> {
    let start = find_key(source, key)?;
    let rest = source[start..].trim_start();
    let end = rest
        .find(|c: char| c == ',' || c == '}' || c == ']' || c.is_whitespace())
        .unwrap_or(rest.len());
    rest[..end].parse::<f32>().ok()
}

/// Extrait la valeur d'une clé dont la valeur est un booléen JSON.
fn bool_value(source: &str, key: &str) -> Option<bool> {
    let start = find_key(source, key)?;
    let rest = source[start..].trim_start();
    if rest.starts_with("true") {
        Some(true)
    } else if rest.starts_with("false") {
        Some(false)
    } else {
        None
    }
}

/// Position juste après le deux-points d'une clé, au premier niveau.
fn find_key(source: &str, key: &str) -> Option<usize> {
    let needle = format!("\"{key}\"");
    let at = source.find(&needle)?;
    let after = &source[at + needle.len()..];
    let colon = after.find(':')?;
    Some(at + needle.len() + colon + 1)
}

#[cfg(test)]
mod tests {
    use super::*;

    const EXTRAS: &str = r#"{
      "axion": {
        "role": "collider",
        "shape": "auto_convex",
        "part": "fender_fl",
        "material": "axion:steel",
        "deform_weight_group": "axion_deform",
        "wear_profile": "axion:painted_steel",
        "lod": [0, 1, 2],
        "deform_region": { "name": "fender_fl", "resolution": [8, 6, 8] }
      }
    }"#;

    #[test]
    fn t227_les_annotations_connues_sont_lues() {
        let annotations = NodeAnnotations::parse(EXTRAS);

        assert_eq!(annotations.role, NodeRole::Collider);
        assert_eq!(annotations.collider_shape.as_deref(), Some("auto_convex"));
        assert_eq!(annotations.part.as_deref(), Some("fender_fl"));
        assert_eq!(annotations.material.as_deref(), Some("axion:steel"));
        assert_eq!(
            annotations.deform_weight_group.as_deref(),
            Some("axion_deform")
        );
        assert_eq!(
            annotations.wear_profile.as_deref(),
            Some("axion:painted_steel")
        );
        assert_eq!(annotations.lod, vec![0, 1, 2]);
        assert!(annotations.warnings.is_empty());
        // `EXTRAS` ne déclare ni densité ni no_refit : défauts.
        assert_eq!(annotations.collider_density, None);
        assert!(!annotations.collider_no_refit);
    }

    #[test]
    fn t227_la_densite_et_no_refit_du_collider_sont_lus() {
        let extras = r#"{ "axion": {
            "role": "collider", "shape": "auto_box",
            "density": 7850.0, "no_refit": true
        } }"#;
        let annotations = NodeAnnotations::parse(extras);
        assert_eq!(annotations.collider_density, Some(7850.0));
        assert!(annotations.collider_no_refit);

        // Une densité entière (sans point) se lit aussi ; no_refit absent → faux.
        let entier = NodeAnnotations::parse(r#"{"axion":{"role":"collider","density":1000}}"#);
        assert_eq!(entier.collider_density, Some(1000.0));
        assert!(!entier.collider_no_refit);
    }

    #[test]
    fn t227_ce_qui_n_est_pas_interprete_est_conserve() {
        // R-911 : conservé, jamais interprété. La région de déformation
        // demande de résoudre des noms et de calculer une OBB — c'est C-28.
        let annotations = NodeAnnotations::parse(EXTRAS);
        let raw = annotations.raw.expect("brut perdu");
        assert!(raw.contains("deform_region"), "{raw}");
        assert!(raw.contains("resolution"), "{raw}");
    }

    #[test]
    fn t227_un_role_inconnu_donne_le_defaut_et_un_avertissement() {
        // R-912 : avertissement et défaut. Deviner classerait le node dans une
        // catégorie que l'auteur n'a pas choisie.
        let annotations = NodeAnnotations::parse(r#"{"axion": {"role": "teleporteur"}}"#);

        assert_eq!(annotations.role, NodeRole::Mesh);
        assert_eq!(annotations.warnings.len(), 1);
        assert!(annotations.warnings[0].contains("teleporteur"));
    }

    #[test]
    fn t227_un_asset_sans_annotation_est_complet() {
        // R-913 : aucune annotation n'est obligatoire.
        let annotations = NodeAnnotations::parse("{}");

        assert_eq!(annotations.role, NodeRole::Mesh);
        assert!(annotations.part.is_none());
        assert!(annotations.lod.is_empty());
        assert!(annotations.warnings.is_empty());
    }

    #[test]
    fn t227_les_extras_d_un_autre_mod_ne_sont_pas_lus() {
        let annotations = NodeAnnotations::parse(r#"{"autreMod": {"role": "collider"}}"#);

        // La clé `axion` est le seul mécanisme d'annotation : lire celle d'un
        // voisin reviendrait à interpréter ses données.
        assert_eq!(annotations.role, NodeRole::Mesh);
        assert!(annotations.raw.is_some());
    }

    #[test]
    fn t227_les_roles_non_rendus_sont_ceux_de_r910() {
        for role in [
            NodeRole::Collider,
            NodeRole::Socket,
            NodeRole::DamageZone,
            NodeRole::DeformRegion,
            NodeRole::ClothAnchor,
            NodeRole::Hidden,
        ] {
            assert!(!role.is_rendered(), "{role} rendu à tort");
        }
        for role in [
            NodeRole::Mesh,
            NodeRole::Wheel,
            NodeRole::Seat,
            NodeRole::Light,
            NodeRole::PartRoot,
            NodeRole::Internal,
            NodeRole::ParticleSet,
        ] {
            assert!(role.is_rendered(), "{role} masqué à tort");
        }
    }

    #[test]
    fn t227_tous_les_roles_de_la_convention_sont_reconnus() {
        let attendus = [
            "mesh",
            "collider",
            "wheel",
            "seat",
            "socket",
            "light",
            "part_root",
            "damage_zone",
            "deform_region",
            "cloth_anchor",
            "particle_set",
            "internal",
            "hidden",
        ];
        for nom in attendus {
            let role = NodeRole::parse(nom).unwrap_or_else(|| panic!("{nom} non reconnu"));
            assert_eq!(role.name(), nom);
        }
        assert_eq!(attendus.len(), 13);
    }

    #[test]
    fn t227_une_valeur_du_mauvais_type_ne_fait_pas_echouer_la_lecture() {
        // R-912 : le défaut, jamais un refus — sauf incohérence de l'asset, que
        // le validateur constatera.
        let annotations = NodeAnnotations::parse(r#"{"axion": {"role": 42, "lod": "non"}}"#);
        assert_eq!(annotations.role, NodeRole::Mesh);
        assert!(annotations.lod.is_empty());
    }

    #[test]
    fn t227_une_chaine_echappee_se_lit() {
        let annotations = NodeAnnotations::parse(r#"{"axion": {"part": "aile \"avant\" gauche"}}"#);
        assert_eq!(annotations.part.as_deref(), Some("aile \"avant\" gauche"));
    }

    #[test]
    fn t227_une_accolade_dans_une_chaine_ne_ferme_pas_l_objet() {
        let annotations =
            NodeAnnotations::parse(r#"{"axion": {"part": "}", "material": "acier"}}"#);
        // Sans suivi des chaînes, l'objet se fermerait sur l'accolade citée et
        // le matériau serait perdu.
        assert_eq!(annotations.material.as_deref(), Some("acier"));
    }
}
