//! Le builder Java des definitions (ADR-162 D3, D4, sous-tranche 2c), généré depuis le schéma publié.
//!
//! Le schéma de definition naît de `SchemaOne`, côté Java, qui le publie dans
//! `docs/schema/definition-<N>.json` ; un test Java vérifie que la publication est à jour. Ce module en tire
//! `DefinitionBuilder.java`, dans `axion-api` : le builder ne diverge jamais du JSON, et le test de parité
//! `parite_builder` échoue s'il a été modifié à la main ou n'a pas été régénéré.
//!
//! Les règles de nommage, qu'ADR-162 consigne :
//! - une classe imbriquée par objet du schéma ; son nom est celui de sa classe parente (rien pour la racine) suivi
//!   du nom du champ, au singulier pour un élément de tableau ou de table ;
//! - un setter par champ, en camelCase ; un mot réservé de Java reçoit un `_` final ;
//! - un tableau d'objets ou une table d'objets s'enrichit par une méthode au singulier, dont les premiers
//!   arguments sont les clés de table puis les champs obligatoires de l'élément ;
//! - le singulier vient de [`SINGULARS`] ; un pluriel inconnu fait échouer la génération plutôt que d'inventer.

use serde_json::Value;
use std::collections::BTreeSet;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};

/// Chemin du builder généré, relatif à la racine du dépôt.
pub const BUILDER_PATH: &str =
    "java/axion-api/src/main/java/dev/axion/api/content/DefinitionBuilder.java";

/// Dossier des schémas de definition publiés, relatif à la racine du dépôt.
pub const SCHEMA_DIR: &str = "docs/schema";

/// Les mots que Java réserve : un champ de ce nom reçoit un `_` final.
const JAVA_KEYWORDS: &[&str] = &[
    "abstract",
    "assert",
    "boolean",
    "break",
    "byte",
    "case",
    "catch",
    "char",
    "class",
    "const",
    "continue",
    "default",
    "do",
    "double",
    "else",
    "enum",
    "extends",
    "false",
    "final",
    "finally",
    "float",
    "for",
    "goto",
    "if",
    "implements",
    "import",
    "instanceof",
    "int",
    "interface",
    "long",
    "native",
    "new",
    "null",
    "package",
    "permits",
    "private",
    "protected",
    "public",
    "record",
    "return",
    "sealed",
    "short",
    "static",
    "strictfp",
    "super",
    "switch",
    "synchronized",
    "this",
    "throw",
    "throws",
    "transient",
    "true",
    "try",
    "var",
    "void",
    "volatile",
    "while",
    "yield",
];

/// Le singulier des tableaux et des tables d'objets, qui nomme leur méthode d'ajout et leur classe.
pub const SINGULARS: &[(&str, &str)] = &[
    ("animations", "animation"),
    ("axes", "axis"),
    ("bindings", "binding"),
    ("bodies", "body"),
    ("bones", "bone"),
    ("collider_variants", "collider_variant"),
    ("collision_proxies", "collision_proxy"),
    ("damage_zones", "damage_zone"),
    ("joints", "joint"),
    ("morphs", "morph"),
    ("particles", "particle"),
    ("parts", "part"),
    ("seats", "seat"),
    ("structural_links", "structural_link"),
    ("surfaces", "surface"),
    ("variables", "variable"),
    ("wheels", "wheel"),
];

/// Le nom des clés d'une table, dans l'ordre de ses niveaux ; `name` par défaut.
const TABLE_KEYS: &[(&str, &[&str])] = &[
    ("custom", &["key"]),
    ("on_detach", &["part"]),
    ("on_part_stage", &["part", "stage"]),
    ("set_variable", &["variable"]),
];

/// Le schéma publié le plus récent : sa version et son chemin.
///
/// # Errors
///
/// Si le dossier est illisible ou ne contient aucun `definition-<N>.json`.
pub fn latest_schema(root: &Path) -> Result<(u32, PathBuf), String> {
    let dir = root.join(SCHEMA_DIR);
    let entries =
        std::fs::read_dir(&dir).map_err(|e| format!("{} illisible : {e}", dir.display()))?;
    let mut best: Option<(u32, PathBuf)> = None;
    for entry in entries {
        let path = entry
            .map_err(|e| format!("{} illisible : {e}", dir.display()))?
            .path();
        let version = path
            .file_name()
            .and_then(|n| n.to_str())
            .and_then(|n| n.strip_prefix("definition-"))
            .and_then(|n| n.strip_suffix(".json"))
            .and_then(|n| n.parse::<u32>().ok());
        if let Some(version) = version {
            match &best {
                Some((b, _)) if *b >= version => {}
                _ => best = Some((version, path)),
            }
        }
    }
    best.ok_or_else(|| format!("aucun definition-<N>.json dans {}", dir.display()))
}

/// Rend `DefinitionBuilder.java` depuis le texte d'un schéma publié.
///
/// # Errors
///
/// Si le schéma est illisible, porte une forme que le générateur ne sait pas écrire, ou un pluriel sans
/// singulier connu.
pub fn render_definition_builder(schema_text: &str, version: u32) -> Result<String, String> {
    let schema: Value =
        serde_json::from_str(schema_text).map_err(|e| format!("schéma illisible : {e}"))?;
    let constant = schema
        .pointer("/properties/schema/const")
        .and_then(Value::as_u64);
    if constant != Some(u64::from(version)) {
        return Err(format!(
            "le schéma {version} déclare la version {constant:?} dans son champ schema"
        ));
    }
    let kinds: Vec<String> = schema
        .pointer("/properties/kind/enum")
        .and_then(Value::as_array)
        .ok_or("le schéma n'énumère pas les genres (kind)")?
        .iter()
        .map(|k| k.as_str().map(str::to_owned).ok_or("genre non textuel"))
        .collect::<Result<_, _>>()?;
    let mut gen = Generator::default();
    gen.names.insert("DefinitionBuilder".to_owned());
    let root_methods = gen.methods("DefinitionBuilder", &schema, "", true)?;
    Ok(gen.finish(version, &kinds, &root_methods))
}

/// Un scalaire du schéma.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Scalar {
    Number,
    Integer,
    Boolean,
    Text,
    NullableText,
}

impl Scalar {
    fn java(self) -> &'static str {
        match self {
            Self::Number => "double",
            Self::Integer => "long",
            Self::Boolean => "boolean",
            Self::Text | Self::NullableText => "String",
        }
    }

    /// L'expression rangée pour un paramètre de ce type.
    fn stored(self, param: &str, key: &str) -> String {
        match self {
            Self::Text => format!("Objects.requireNonNull({param}, \"{key}\")"),
            _ => param.to_owned(),
        }
    }
}

/// La forme d'un champ du schéma, telle que le builder l'écrit.
enum Shape<'a> {
    Scalar(Scalar),
    Any,
    ScalarArray(Scalar),
    AnyArray,
    Rows(Scalar),
    Object(&'a Value),
    ObjectArray(&'a Value),
    Table(Box<Shape<'a>>),
}

fn is_text(v: &Value) -> bool {
    match v.get("type").and_then(Value::as_str) {
        Some(t) => t == "string",
        None => {
            v.get("pattern").is_some()
                || v.get("enum")
                    .and_then(Value::as_array)
                    .is_some_and(|e| e.iter().all(Value::is_string))
        }
    }
}

fn shape<'a>(v: &'a Value, path: &str) -> Result<Shape<'a>, String> {
    let o = v
        .as_object()
        .ok_or_else(|| format!("{path} : schéma qui n'est pas un objet"))?;
    if o.is_empty() {
        return Ok(Shape::Any);
    }
    if let Some(alternatives) = o.get("anyOf").and_then(Value::as_array) {
        let mut nullable = false;
        for alternative in alternatives {
            if alternative.get("type").and_then(Value::as_str) == Some("null") {
                nullable = true;
            } else if !is_text(alternative) {
                return Err(format!(
                    "{path} : anyOf qui n'est pas textuel, non pris en charge"
                ));
            }
        }
        return Ok(Shape::Scalar(if nullable {
            Scalar::NullableText
        } else {
            Scalar::Text
        }));
    }
    match o.get("type").and_then(Value::as_str) {
        Some("number") => Ok(Shape::Scalar(Scalar::Number)),
        Some("integer") => Ok(Shape::Scalar(Scalar::Integer)),
        Some("boolean") => Ok(Shape::Scalar(Scalar::Boolean)),
        Some("string") => Ok(Shape::Scalar(Scalar::Text)),
        Some("array") => {
            let items = o
                .get("items")
                .ok_or_else(|| format!("{path} : tableau sans items"))?;
            match shape(items, &format!("{path}[]"))? {
                Shape::Scalar(s) => Ok(Shape::ScalarArray(s)),
                Shape::Any => Ok(Shape::AnyArray),
                Shape::ScalarArray(s) => Ok(Shape::Rows(s)),
                Shape::Object(_) => Ok(Shape::ObjectArray(items)),
                _ => Err(format!(
                    "{path} : tableau de cette forme non pris en charge"
                )),
            }
        }
        Some("object") => {
            if o.contains_key("properties") {
                return Ok(Shape::Object(v));
            }
            match o.get("additionalProperties") {
                Some(ap) if ap.is_object() => {
                    Ok(Shape::Table(Box::new(shape(ap, &format!("{path}.*"))?)))
                }
                _ => Err(format!("{path} : objet sans propriétés ni table")),
            }
        }
        None if is_text(v) => Ok(Shape::Scalar(Scalar::Text)),
        other => Err(format!("{path} : type {other:?} non pris en charge")),
    }
}

fn pascal(snake: &str) -> String {
    snake
        .split('_')
        .filter(|w| !w.is_empty())
        .map(|w| {
            let mut c = w.chars();
            c.next().map_or_else(String::new, |f| {
                f.to_ascii_uppercase().to_string() + c.as_str()
            })
        })
        .collect()
}

fn camel(snake: &str) -> String {
    let p = pascal(snake);
    let mut c = p.chars();
    let name = c.next().map_or_else(String::new, |f| {
        f.to_ascii_lowercase().to_string() + c.as_str()
    });
    if JAVA_KEYWORDS.contains(&name.as_str()) {
        name + "_"
    } else {
        name
    }
}

fn singular(key: &str) -> Result<&str, String> {
    if let Some((_, s)) = SINGULARS.iter().find(|(p, _)| *p == key) {
        return Ok(s);
    }
    if key.ends_with('s') {
        return Err(format!(
            "« {key} » a l'air d'un pluriel sans singulier connu : l'ajouter à SINGULARS"
        ));
    }
    Ok(key)
}

fn table_keys(key: &str) -> &'static [&'static str] {
    TABLE_KEYS
        .iter()
        .find(|(k, _)| *k == key)
        .map_or(&["name"], |(_, keys)| keys)
}

/// La ligne de documentation d'un champ : son chemin, puis ce que le schéma en dit.
fn doc(path: &str, prop: &Value) -> String {
    let mut parts: Vec<String> = Vec::new();
    let enumerated = prop.get("enum").or_else(|| {
        prop.get("anyOf")
            .and_then(Value::as_array)
            .and_then(|alts| alts.iter().find_map(|a| a.get("enum")))
    });
    if let Some(values) = enumerated.and_then(Value::as_array) {
        let names: Vec<String> = values
            .iter()
            .filter_map(Value::as_str)
            .map(|v| format!("{{@code {v}}}"))
            .collect();
        parts.push(format!("parmi {}", names.join(", ")));
        if prop.get("anyOf").is_some_and(|a| {
            a.as_array()
                .is_some_and(|a| a.iter().any(|x| x.get("pattern").is_some()))
        }) {
            parts.push("ou un identifiant {@code <ns>:<chemin>}".to_owned());
        }
    }
    for (key, label) in [
        ("minimum", "au moins"),
        ("exclusiveMinimum", "plus de"),
        ("maximum", "au plus"),
        ("exclusiveMaximum", "moins de"),
    ] {
        if let Some(n) = prop.get(key) {
            parts.push(format!("{label} {n}"));
        }
    }
    let count = |n: &Value| match n.as_u64() {
        Some(1) => "1 élément".to_owned(),
        _ => format!("{n} éléments"),
    };
    match (prop.get("minItems"), prop.get("maxItems")) {
        (Some(a), Some(b)) if a == b => parts.push(count(a)),
        (a, b) => {
            if let Some(a) = a {
                parts.push(format!("au moins {}", count(a)));
            }
            if let Some(b) = b {
                parts.push(format!("au plus {}", count(b)));
            }
        }
    }
    let target = prop.get("items").unwrap_or(prop);
    if let Some(r) = target.get("x-axion-refers").and_then(Value::as_str) {
        parts.push(format!("nom ({r}) dans le modèle"));
    }
    if let Some(d) = target.get("x-axion-declares").and_then(Value::as_str) {
        parts.push(format!("déclare un nom ({d})"));
    }
    if prop
        .get("anyOf")
        .and_then(Value::as_array)
        .is_some_and(|alts| {
            alts.iter()
                .any(|a| a.get("type").and_then(Value::as_str) == Some("null"))
        })
    {
        parts.push("{@code null} : aucun".to_owned());
    }
    if parts.is_empty() {
        format!("{{@code {path}}}.")
    } else {
        format!("{{@code {path}}} : {}.", parts.join(" ; "))
    }
}

#[derive(Default)]
struct Generator {
    /// Les classes imbriquées déjà rendues, dans l'ordre de leur rencontre.
    classes: Vec<String>,
    /// Les noms de classes pris.
    names: BTreeSet<String>,
}

/// Un paramètre d'une méthode d'ajout : son type Java, son nom, la clé qu'il remplit et la forme rangée.
struct Param {
    java: &'static str,
    name: String,
    key: String,
    stored: String,
}

impl Generator {
    /// Rend une classe imbriquée et la garde ; rend son nom.
    fn class(&mut self, name: &str, obj: &Value, path: &str) -> Result<String, String> {
        if !self.names.insert(name.to_owned()) {
            return Err(format!("{path} : la classe {name} existe déjà"));
        }
        let body = self.methods(name, obj, path, false)?;
        let mut out = String::new();
        let _ = writeln!(out, "    /** {{@code {path}}}. */");
        let _ = writeln!(out, "    @Experimental");
        let _ = writeln!(out, "    public static final class {name} {{");
        let _ = writeln!(out);
        let _ = writeln!(out, "        private final Map<String, Object> values;");
        let _ = writeln!(out);
        let _ = writeln!(out, "        private {name}(Map<String, Object> values) {{");
        let _ = writeln!(out, "            this.values = values;");
        let _ = writeln!(out, "        }}");
        out.push_str(&body);
        let _ = writeln!(out, "    }}");
        self.classes.push(out);
        Ok(name.to_owned())
    }

    /// Les méthodes d'une classe, un champ après l'autre, dans l'ordre alphabétique du schéma.
    fn methods(
        &mut self,
        class: &str,
        obj: &Value,
        path: &str,
        root: bool,
    ) -> Result<String, String> {
        let props = obj
            .get("properties")
            .and_then(Value::as_object)
            .ok_or_else(|| format!("{path} : objet sans propriétés"))?;
        let indent = if root { "    " } else { "        " };
        let prefix = if root { "" } else { class };
        let mut out = String::new();
        for (key, prop) in props {
            if root && matches!(key.as_str(), "schema" | "asset" | "kind") {
                continue;
            }
            let jpath = if path.is_empty() {
                key.clone()
            } else {
                format!("{path}.{key}")
            };
            let line = doc(&jpath, prop);
            let method = camel(key);
            let returned = RETURN_TAG.to_owned();
            match shape(prop, &jpath)? {
                Shape::Scalar(s) => {
                    let comment =
                        javadoc(indent, &line, &[param_tag("value", "la valeur"), returned]);
                    let stored = s.stored("value", key);
                    let _ = write!(
                        out,
                        "\n{comment}{indent}public {class} {method}({} value) {{\n{indent}    values.put(\"{key}\", {stored});\n{indent}    return this;\n{indent}}}\n",
                        s.java()
                    );
                }
                Shape::Any => out.push_str(&any_setters(indent, class, &method, &line, key, &[])),
                Shape::ScalarArray(s) => {
                    let comment = javadoc(
                        indent,
                        &line,
                        &[param_tag("items", "les valeurs, dans l'ordre"), returned],
                    );
                    let helper = array_helper(s);
                    let _ = write!(
                        out,
                        "\n{comment}{indent}public {class} {method}({}... items) {{\n{indent}    values.put(\"{key}\", {helper}(items));\n{indent}    return this;\n{indent}}}\n",
                        s.java()
                    );
                }
                Shape::AnyArray => {
                    let comment = javadoc(
                        indent,
                        &line,
                        &[
                            param_tag("items", "les valeurs, dans l'ordre ; toute valeur de JSON"),
                            returned,
                        ],
                    );
                    let _ = write!(
                        out,
                        "\n{comment}{indent}public {class} {method}(Object... items) {{\n{indent}    values.put(\"{key}\", new ArrayList<>(Arrays.asList(items)));\n{indent}    return this;\n{indent}}}\n"
                    );
                }
                Shape::Rows(s) => {
                    let rows_comment = javadoc(
                        indent,
                        &line,
                        &[param_tag("rows", "les lignes, une par élément"), returned],
                    );
                    if s != Scalar::Number {
                        return Err(format!("{jpath} : tableau de tableaux non numériques"));
                    }
                    let _ = write!(
                        out,
                        "\n{rows_comment}{indent}public {class} {method}(double[]... rows) {{\n{indent}    values.put(\"{key}\", rows(rows));\n{indent}    return this;\n{indent}}}\n"
                    );
                }
                Shape::Object(child) => {
                    let comment = javadoc(
                        indent,
                        &line,
                        &[
                            param_tag("config", "règle l'objet ; rappelée, elle complète le même"),
                            returned,
                        ],
                    );
                    let name = self.class(&format!("{prefix}{}", pascal(key)), child, &jpath)?;
                    let _ = write!(
                        out,
                        "\n{comment}{indent}public {class} {method}(Consumer<{name}> config) {{\n{indent}    Map<String, Object> nested = object(values, \"{key}\");\n{indent}    Objects.requireNonNull(config, \"config\").accept(new {name}(nested));\n{indent}    return this;\n{indent}}}\n"
                    );
                }
                Shape::ObjectArray(items) => {
                    let one = singular(key)?;
                    let name = self.class(
                        &format!("{prefix}{}", pascal(one)),
                        items,
                        &format!("{jpath}[]"),
                    )?;
                    let params = required(items, &format!("{jpath}[]"))?;
                    let adder = camel(one);
                    out.push_str(&adder_methods(
                        indent,
                        class,
                        &adder,
                        &line,
                        &name,
                        &[],
                        &params,
                        &format!("list(values, \"{key}\").add(item);"),
                        "Map<String, Object> item = new LinkedHashMap<>();",
                    ));
                }
                Shape::Table(inner) => {
                    let keys = table_keys(key);
                    let mut depth = 1;
                    let mut leaf: &Shape = &inner;
                    while let Shape::Table(next) = leaf {
                        depth += 1;
                        leaf = next.as_ref();
                    }
                    if keys.len() != depth {
                        return Err(format!(
                            "{jpath} : table de {depth} niveaux, {} clés nommées dans TABLE_KEYS",
                            keys.len()
                        ));
                    }
                    let one = singular(key)?;
                    let adder = camel(one);
                    let key_params: Vec<String> = keys.iter().copied().map(camel).collect();
                    let reach = path_call(key, &key_params[..keys.len() - 1]);
                    let last = &key_params[keys.len() - 1];
                    match leaf {
                        Shape::Object(child) => {
                            let name = self.class(
                                &format!("{prefix}{}", pascal(one)),
                                child,
                                &format!("{jpath}.*"),
                            )?;
                            let params = required(child, &format!("{jpath}.*"))?;
                            out.push_str(&adder_methods(
                                indent,
                                class,
                                &adder,
                                &line,
                                &name,
                                &key_params,
                                &params,
                                "",
                                &format!(
                                    "Map<String, Object> item = {};",
                                    path_call(key, &key_params)
                                ),
                            ));
                        }
                        Shape::Scalar(s) => {
                            let mut tags: Vec<String> = key_params
                                .iter()
                                .map(|k| param_tag(k, "clé de la table"))
                                .collect();
                            tags.push(param_tag("value", "la valeur"));
                            tags.push(returned);
                            let comment = javadoc(indent, &line, &tags);
                            let args: Vec<String> =
                                key_params.iter().map(|k| format!("String {k}")).collect();
                            let stored = s.stored("value", key);
                            let _ = write!(
                                out,
                                "\n{comment}{indent}public {class} {adder}({}, {} value) {{\n{indent}    Map<String, Object> table = {reach};\n{indent}    table.put(Objects.requireNonNull({last}, \"{last}\"), {stored});\n{indent}    return this;\n{indent}}}\n",
                                args.join(", "),
                                s.java()
                            );
                        }
                        Shape::Any => {
                            out.push_str(&any_setters(
                                indent,
                                class,
                                &adder,
                                &line,
                                key,
                                &key_params,
                            ));
                        }
                        _ => {
                            return Err(format!(
                                "{jpath} : table de cette forme non prise en charge"
                            ))
                        }
                    }
                }
            }
        }
        Ok(out)
    }

    fn finish(self, version: u32, kinds: &[String], root_methods: &str) -> String {
        let mut out = String::new();
        let _ = write!(
            out,
            "// Fichier généré par `cargo run -p axion-codegen --bin gen_definition_builder` depuis\n\
             // docs/schema/definition-{version}.json (ADR-162 D4) : ne pas le modifier à la main. Le test de parité\n\
             // parite_builder échoue s'il diverge du schéma.\n\
             package dev.axion.api.content;\n\n\
             import dev.axion.api.Experimental;\n\
             import java.util.ArrayList;\n\
             import java.util.Arrays;\n\
             import java.util.LinkedHashMap;\n\
             import java.util.List;\n\
             import java.util.Map;\n\
             import java.util.Objects;\n\
             import java.util.function.Consumer;\n\n\
             /**\n\
             \x20* Le builder d'une definition (ADR-162 D3, D4) : une méthode par champ du schéma {version}, dont il est généré.\n\
             \x20*\n\
             \x20* <p>Il produit le même document qu'un fichier JSON : {{@link #build()}} rend un {{@link DefinitionDocument}}\n\
             \x20* que {{@link ContentRegistrar#register}} enregistre, que le même schéma valide au chargement et dont\n\
             \x20* l'empreinte est celle d'un fichier de même contenu. Le builder ne vérifie que les types ; les bornes, les\n\
             \x20* valeurs permises et les noms du modèle se vérifient au chargement, comme pour un fichier.\n\
             \x20*\n\
             \x20* <pre>{{@code\n\
             \x20* DefinitionBuilder.vehicle(\"monmod:models/patrouille.glb\")\n\
             \x20*         .physics(p -> p.mass(1900.0))\n\
             \x20*         .wheel(\"roue_av_g\", w -> w.radius(0.53).steering(true))\n\
             \x20*         .build();\n\
             \x20* }}</pre>\n\
             \x20*\n\
             \x20* <p>Un objet imbriqué se règle par une lambda ; la rappeler complète le même objet. Un élément de tableau ou de\n\
             \x20* table s'ajoute par une méthode au singulier, dont les premiers arguments sont ses clés et ses champs\n\
             \x20* obligatoires. Un champ dont le nom est un mot réservé de Java prend un {{@code _}} final.\n\
             \x20*/\n\
             @Experimental\n\
             public final class DefinitionBuilder {{\n\n\
             \x20   /** La version du schéma que ce builder écrit dans le champ {{@code schema}}. */\n\
             \x20   public static final int SCHEMA = {version};\n\n\
             \x20   private final Map<String, Object> values = new LinkedHashMap<>();\n\n\
             \x20   private DefinitionBuilder(String kind, String asset) {{\n\
             \x20       values.put(\"schema\", (long) SCHEMA);\n\
             \x20       values.put(\"asset\", Objects.requireNonNull(asset, \"asset\"));\n\
             \x20       values.put(\"kind\", Objects.requireNonNull(kind, \"kind\"));\n\
             \x20   }}\n\n\
             \x20   /**\n\
             \x20    * {{@return une definition de ce genre, sur ce modèle}} Le genre est l'une des valeurs de {{@code kind}}.\n\
             \x20    *\n\
             \x20    * @param kind genre d'assembly\n\
             \x20    * @param asset modèle, {{@code <ns>:models/<chemin>.glb}}\n\
             \x20    */\n\
             \x20   public static DefinitionBuilder of(String kind, String asset) {{\n\
             \x20       return new DefinitionBuilder(kind, asset);\n\
             \x20   }}\n"
        );
        for kind in kinds {
            let _ = write!(
                out,
                "\n    /**\n     * {{@return une definition de genre {{@code {kind}}}, sur ce modèle}}\n     *\n     * @param asset modèle, {{@code <ns>:models/<chemin>.glb}}\n     */\n    public static DefinitionBuilder {}(String asset) {{\n        return new DefinitionBuilder(\"{kind}\", asset);\n    }}\n",
                camel(kind)
            );
        }
        out.push_str(
            "\n    /** {@return le document, copie figée de ce qui a été réglé} */\n    public DefinitionDocument build() {\n        return DefinitionDocument.of(values);\n    }\n",
        );
        out.push_str(root_methods);
        out.push_str(HELPERS);
        for class in &self.classes {
            out.push('\n');
            out.push_str(class);
        }
        out.push_str("}\n");
        out
    }
}

/// Les champs obligatoires d'un objet, devenus paramètres d'une méthode d'ajout.
fn required(obj: &Value, path: &str) -> Result<Vec<Param>, String> {
    let mut params = Vec::new();
    let names = obj
        .get("required")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    for name in names {
        let key = name
            .as_str()
            .ok_or_else(|| format!("{path} : required non textuel"))?;
        let prop = obj
            .pointer(&format!("/properties/{key}"))
            .ok_or_else(|| format!("{path} : {key} requis mais absent des propriétés"))?;
        let Shape::Scalar(s) = shape(prop, &format!("{path}.{key}"))? else {
            return Err(format!(
                "{path}.{key} : champ obligatoire qui n'est pas un scalaire"
            ));
        };
        let param = camel(key);
        params.push(Param {
            java: s.java(),
            stored: s.stored(&param, key),
            name: param,
            key: key.to_owned(),
        });
    }
    Ok(params)
}

/// Les deux méthodes d'ajout d'un élément : avec et sans lambda de réglage.
#[allow(clippy::too_many_arguments)]
fn adder_methods(
    indent: &str,
    class: &str,
    adder: &str,
    line: &str,
    child: &str,
    keys: &[String],
    params: &[Param],
    attach: &str,
    create: &str,
) -> String {
    let mut signature: Vec<String> = keys.iter().map(|k| format!("String {k}")).collect();
    signature.extend(params.iter().map(|p| format!("{} {}", p.java, p.name)));
    let names: Vec<String> = keys
        .iter()
        .cloned()
        .chain(params.iter().map(|p| p.name.clone()))
        .collect();
    let mut tags: Vec<String> = keys
        .iter()
        .map(|k| param_tag(k, "clé de la table"))
        .collect();
    tags.extend(params.iter().map(|p| {
        param_tag(
            &p.name,
            &format!("le champ obligatoire {{@code {}}}", p.key),
        )
    }));
    let without = {
        let mut t = tags.clone();
        t.push(RETURN_TAG.to_owned());
        javadoc(indent, line, &t)
    };
    tags.push(param_tag(
        "config",
        if attach.is_empty() {
            "règle l'élément ; rappelée avec les mêmes clés, elle complète le même"
        } else {
            "règle l'élément, ajouté à la suite des précédents"
        },
    ));
    tags.push(RETURN_TAG.to_owned());
    let comment = javadoc(indent, line, &tags);
    let mut out = String::new();
    let with = if signature.is_empty() {
        format!("Consumer<{child}> config")
    } else {
        format!("{}, Consumer<{child}> config", signature.join(", "))
    };
    let _ = write!(
        out,
        "\n{comment}{indent}public {class} {adder}({with}) {{\n{indent}    {create}\n"
    );
    for p in params {
        let _ = writeln!(out, "{indent}    item.put(\"{}\", {});", p.key, p.stored);
    }
    let _ = writeln!(
        out,
        "{indent}    Objects.requireNonNull(config, \"config\").accept(new {child}(item));"
    );
    if !attach.is_empty() {
        let _ = writeln!(out, "{indent}    {attach}");
    }
    let _ = writeln!(out, "{indent}    return this;\n{indent}}}");
    if !signature.is_empty() {
        let _ = write!(
            out,
            "\n{without}{indent}public {class} {adder}({}) {{\n{indent}    return {adder}({}, item -> {{ }});\n{indent}}}\n",
            signature.join(", "),
            names.join(", ")
        );
    }
    out
}

/// Les setters d'un champ qui admet toute valeur : booléen, nombre, texte, et toute valeur de JSON.
fn any_setters(
    indent: &str,
    class: &str,
    method: &str,
    line: &str,
    key: &str,
    keys: &[String],
) -> String {
    let (target, args) = if keys.is_empty() {
        (format!("values.put(\"{key}\", "), String::new())
    } else {
        let reach = path_call(key, &keys[..keys.len() - 1]);
        let last = &keys[keys.len() - 1];
        let args: Vec<String> = keys.iter().map(|k| format!("String {k}, ")).collect();
        (
            format!("{reach}.put(Objects.requireNonNull({last}, \"{last}\"), "),
            args.concat(),
        )
    };
    let mut out = String::new();
    for (java, extra) in [
        ("boolean", ""),
        ("double", ""),
        ("String", ""),
        (
            "Object",
            " Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.",
        ),
    ] {
        let mut tags: Vec<String> = keys
            .iter()
            .map(|k| param_tag(k, "clé de la table"))
            .collect();
        tags.push(param_tag("value", "la valeur"));
        tags.push(RETURN_TAG.to_owned());
        let comment = javadoc(indent, &format!("{line}{extra}"), &tags);
        let _ = write!(
            out,
            "\n{comment}{indent}public {class} {method}({args}{java} value) {{\n{indent}    {target}value);\n{indent}    return this;\n{indent}}}\n"
        );
    }
    out
}

/// Le `@return` de chaque réglage : la classe elle-même, pour enchaîner.
const RETURN_TAG: &str = "@return ce réglage, pour enchaîner";

/// La balise d'un paramètre.
fn param_tag(name: &str, text: &str) -> String {
    format!("@param {name} {text}")
}

/// Une javadoc complète, que `doclint:all` accepte : le texte, puis ses balises, chacun replié en 120 colonnes.
fn javadoc(indent: &str, text: &str, tags: &[String]) -> String {
    let mut out = format!("{indent}/**\n");
    wrap(&mut out, indent, text);
    if !tags.is_empty() {
        let _ = writeln!(out, "{indent} *");
        for tag in tags {
            wrap(&mut out, indent, tag);
        }
    }
    let _ = writeln!(out, "{indent} */");
    out
}

/// Replie un paragraphe de javadoc en lignes de 120 colonnes au plus ; une balise `{@…}` reste d'un seul tenant.
fn wrap(out: &mut String, indent: &str, text: &str) {
    let mut words: Vec<String> = Vec::new();
    for piece in text.split(' ') {
        match words.last_mut() {
            Some(last) if last.starts_with("{@") && !last.contains('}') => {
                last.push(' ');
                last.push_str(piece);
            }
            _ => words.push(piece.to_owned()),
        }
    }
    let mut current = String::new();
    for word in &words {
        let width = indent.chars().count() + 3 + current.chars().count() + 1 + word.chars().count();
        if !current.is_empty() && width > 120 {
            let _ = writeln!(out, "{indent} * {current}");
            current.clear();
        }
        if !current.is_empty() {
            current.push(' ');
        }
        current.push_str(word);
    }
    if !current.is_empty() {
        let _ = writeln!(out, "{indent} * {current}");
    }
}

/// L'appel qui atteint, créées au besoin, la table d'un champ puis ses sous-tables, une clé par niveau.
fn path_call(key: &str, keys: &[String]) -> String {
    if keys.is_empty() {
        format!("object(values, \"{key}\")")
    } else {
        format!("path(values, \"{key}\", {})", keys.join(", "))
    }
}

fn array_helper(s: Scalar) -> &'static str {
    match s {
        Scalar::Number => "doubles",
        Scalar::Integer => "longs",
        Scalar::Boolean => "booleans",
        Scalar::Text | Scalar::NullableText => "texts",
    }
}

/// Les aides communes, privées : un objet ou une liste sous une clé, créés au premier besoin, et la copie des
/// tableaux.
const HELPERS: &str = r#"
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.computeIfAbsent(key, k -> new LinkedHashMap<String, Object>());
    }

    private static Map<String, Object> path(Map<String, Object> values, String key, String... keys) {
        Map<String, Object> level = object(values, key);
        for (String sub : keys) {
            level = object(level, Objects.requireNonNull(sub, "clé"));
        }
        return level;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> values, String key) {
        return (List<Object>) values.computeIfAbsent(key, k -> new ArrayList<Object>());
    }

    private static List<Object> doubles(double[] items) {
        List<Object> out = new ArrayList<>(items.length);
        for (double item : items) {
            out.add(item);
        }
        return out;
    }

    private static List<Object> longs(long[] items) {
        List<Object> out = new ArrayList<>(items.length);
        for (long item : items) {
            out.add(item);
        }
        return out;
    }

    private static List<Object> booleans(boolean[] items) {
        List<Object> out = new ArrayList<>(items.length);
        for (boolean item : items) {
            out.add(item);
        }
        return out;
    }

    private static List<Object> texts(String[] items) {
        List<Object> out = new ArrayList<>(items.length);
        for (String item : items) {
            out.add(Objects.requireNonNull(item, "élément"));
        }
        return out;
    }

    private static List<Object> rows(double[][] rows) {
        List<Object> out = new ArrayList<>(rows.length);
        for (double[] row : rows) {
            out.add(doubles(Objects.requireNonNull(row, "ligne")));
        }
        return out;
    }
"#;
