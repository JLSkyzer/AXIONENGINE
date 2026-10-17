//! Les scènes sans `nodes`, que glTF 2.0 admet et que `gltf-json` refuse.
//!
//! glTF 2.0 ne rend obligatoire aucun membre d'une scène (`scene.schema.json` n'a
//! pas de `required`) : une scène vide n'a pas de `nodes`. L'exportateur de
//! Blender 5.2 en écrit une pour chaque scène du fichier où rien n'est exporté.
//! `gltf-json` 1.4.1 exige pourtant le membre — `Scene::nodes` n'a pas de défaut
//! serde — et refuse le document entier : « missing field `nodes` ».
//!
//! [`fill_missing_nodes`] rend le document où chaque scène sans `nodes` reçoit
//! `"nodes":[]`, que la bibliothèque lit comme la scène vide décrite par
//! l'auteur. Un analyseur minimal, comme ailleurs dans ce crate : la table 32.2
//! écarte un analyseur JSON du runtime (ADR-136), et ce qui est cherché ici tient
//! en une forme — les membres de premier niveau des objets de `scenes`.
//!
//! Il ne valide rien : sur un texte qu'il ne sait pas suivre, il rend `None`, et
//! c'est la bibliothèque qui dit pourquoi le document est refusé. Il n'est pas
//! récursif, pour qu'un document hostile très imbriqué ne lui coûte pas la pile.

/// Le document où chaque scène sans membre `nodes` reçoit `"nodes":[]`.
///
/// `None` si aucune scène n'en manque, ou si le texte n'a pas la forme attendue.
/// Une clé de scène qui porte un échappement compte comme `nodes` : décodée, elle
/// pourrait l'être, et un second membre `nodes` ferait refuser la scène.
pub(super) fn fill_missing_nodes(json: &str) -> Option<String> {
    let bytes = json.as_bytes();
    let root = skip_whitespace(bytes, 0);
    if bytes.get(root) != Some(&b'{') {
        return None;
    }

    // Position de chaque insertion — juste après l'accolade ouvrante de la scène
    // —, et si la scène est vide, auquel cas aucune virgule ne suit.
    let mut insertions = Vec::new();
    members(bytes, root, |key, value| {
        if key == b"scenes" && bytes.get(value) == Some(&b'[') {
            elements(bytes, value, |element| {
                if bytes.get(element) != Some(&b'{') {
                    return value_end(bytes, element);
                }
                let mut nodes = false;
                let (end, empty) = members(bytes, element, |key, value| {
                    nodes |= key == b"nodes" || key.contains(&b'\\');
                    value_end(bytes, value)
                })?;
                if !nodes {
                    insertions.push((element + 1, empty));
                }
                Some(end)
            })
        } else {
            value_end(bytes, value)
        }
    })?;
    if insertions.is_empty() {
        return None;
    }

    let mut filled = String::with_capacity(json.len() + insertions.len() * EMPTY_NODES_THEN.len());
    let mut from = 0;
    for (at, empty) in insertions {
        // `at` suit une accolade, ASCII : c'est une frontière de caractère.
        filled.push_str(&json[from..at]);
        filled.push_str(if empty { EMPTY_NODES } else { EMPTY_NODES_THEN });
        from = at;
    }
    filled.push_str(&json[from..]);
    Some(filled)
}

const EMPTY_NODES: &str = "\"nodes\":[]";
const EMPTY_NODES_THEN: &str = "\"nodes\":[],";

/// Parcourt les membres de l'objet ouvert en `open`.
///
/// `member` reçoit la clé, brute — sans ses guillemets, échappements compris —,
/// et le début de la valeur ; il rend la fin de la valeur. Rend la position qui
/// suit l'objet, et s'il était vide.
fn members(
    bytes: &[u8],
    open: usize,
    mut member: impl FnMut(&[u8], usize) -> Option<usize>,
) -> Option<(usize, bool)> {
    let mut at = skip_whitespace(bytes, open + 1);
    if bytes.get(at) == Some(&b'}') {
        return Some((at + 1, true));
    }
    loop {
        if bytes.get(at) != Some(&b'"') {
            return None;
        }
        let key_end = string_end(bytes, at)?;
        let key = &bytes[at + 1..key_end - 1];
        at = skip_whitespace(bytes, key_end);
        if bytes.get(at) != Some(&b':') {
            return None;
        }
        at = skip_whitespace(bytes, member(key, skip_whitespace(bytes, at + 1))?);
        match bytes.get(at)? {
            b',' => at = skip_whitespace(bytes, at + 1),
            b'}' => return Some((at + 1, false)),
            _ => return None,
        }
    }
}

/// Parcourt les éléments du tableau ouvert en `open`.
///
/// `element` reçoit le début de chaque élément et rend sa fin. Rend la position
/// qui suit le tableau.
fn elements(
    bytes: &[u8],
    open: usize,
    mut element: impl FnMut(usize) -> Option<usize>,
) -> Option<usize> {
    let mut at = skip_whitespace(bytes, open + 1);
    if bytes.get(at) == Some(&b']') {
        return Some(at + 1);
    }
    loop {
        at = skip_whitespace(bytes, element(at)?);
        match bytes.get(at)? {
            b',' => at = skip_whitespace(bytes, at + 1),
            b']' => return Some(at + 1),
            _ => return None,
        }
    }
}

/// La position qui suit la valeur commençant en `at`.
///
/// Un objet ou un tableau se saute en comptant ses ouvrantes et ses fermantes
/// hors des chaînes, sans vérifier qu'elles s'apparient : le document est jugé
/// par la bibliothèque, pas ici.
fn value_end(bytes: &[u8], at: usize) -> Option<usize> {
    match *bytes.get(at)? {
        b'"' => string_end(bytes, at),
        b'{' | b'[' => {
            let mut depth = 0usize;
            let mut index = at;
            while let Some(&byte) = bytes.get(index) {
                match byte {
                    b'"' => {
                        index = string_end(bytes, index)?;
                        continue;
                    }
                    b'{' | b'[' => depth += 1,
                    b'}' | b']' => {
                        depth -= 1;
                        if depth == 0 {
                            return Some(index + 1);
                        }
                    }
                    _ => {}
                }
                index += 1;
            }
            None
        }
        // Un nombre, `true`, `false` ou `null` : jusqu'au prochain séparateur.
        _ => {
            let length = bytes[at..]
                .iter()
                .position(|&byte| matches!(byte, b',' | b'}' | b']') || is_whitespace(byte))
                .unwrap_or(bytes.len() - at);
            (length > 0).then_some(at + length)
        }
    }
}

/// La position qui suit la chaîne dont le guillemet ouvrant est en `open`.
///
/// Les octets d'un caractère UTF-8 hors ASCII valent tous 0x80 ou plus : aucun
/// n'est pris pour un guillemet ou une barre oblique inverse.
fn string_end(bytes: &[u8], open: usize) -> Option<usize> {
    let mut index = open + 1;
    loop {
        match *bytes.get(index)? {
            b'\\' => index += 2,
            b'"' => return Some(index + 1),
            _ => index += 1,
        }
    }
}

fn skip_whitespace(bytes: &[u8], mut at: usize) -> usize {
    while bytes.get(at).copied().is_some_and(is_whitespace) {
        at += 1;
    }
    at
}

/// Les quatre blancs de JSON (RFC 8259, §2).
fn is_whitespace(byte: u8) -> bool {
    matches!(byte, b' ' | b'\t' | b'\n' | b'\r')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn une_scene_sans_nodes_recoit_un_tableau_vide() {
        assert_eq!(
            fill_missing_nodes(r#"{"scenes":[{"nodes":[0]},{"name":"vide"}]}"#).as_deref(),
            Some(r#"{"scenes":[{"nodes":[0]},{"nodes":[],"name":"vide"}]}"#)
        );
    }

    #[test]
    fn une_scene_vide_recoit_un_tableau_vide_sans_virgule() {
        assert_eq!(
            fill_missing_nodes("{ \"scenes\" : [ { } , {\"nodes\":[1]} ] }").as_deref(),
            Some("{ \"scenes\" : [ {\"nodes\":[] } , {\"nodes\":[1]} ] }")
        );
    }

    #[test]
    fn un_document_dont_chaque_scene_a_ses_nodes_n_est_pas_touche() {
        for json in [
            r#"{"scenes":[{"nodes":[0]}]}"#,
            r#"{"asset":{"version":"2.0"}}"#,
            r#"{"scenes":[]}"#,
            r#"{}"#,
        ] {
            assert_eq!(fill_missing_nodes(json), None, "{json}");
        }
    }

    #[test]
    fn seuls_les_membres_de_premier_niveau_de_la_scene_comptent() {
        // Un `nodes` dans les extras de la scène n'est pas le sien ; un `scenes` dans les
        // extras de la racine n'est pas le tableau des scènes.
        assert_eq!(
            fill_missing_nodes(
                r#"{"extras":{"scenes":[{}]},"scenes":[{"extras":{"nodes":[0]},"name":"a"}]}"#
            )
            .as_deref(),
            Some(
                r#"{"extras":{"scenes":[{}]},"scenes":[{"nodes":[],"extras":{"nodes":[0]},"name":"a"}]}"#
            )
        );
    }

    #[test]
    fn une_chaine_qui_contient_des_delimiteurs_ne_trompe_pas_le_parcours() {
        assert_eq!(
            fill_missing_nodes(r#"{"a":"}],\"{[","scenes":[{"name":"x\"}]"}]}"#).as_deref(),
            Some(r#"{"a":"}],\"{[","scenes":[{"nodes":[],"name":"x\"}]"}]}"#)
        );
    }

    #[test]
    fn une_cle_echappee_compte_comme_nodes() {
        // `"no\u0064es"` vaut `"nodes"` une fois décodée : ajouter un membre en ferait deux.
        assert_eq!(
            fill_missing_nodes(r#"{"scenes":[{"no\u0064es":[0]}]}"#),
            None
        );
    }

    #[test]
    fn un_texte_illisible_est_laisse_a_la_bibliotheque() {
        for json in [
            "",
            "[]",
            "\u{feff}{\"scenes\":[{}]}",
            r#"{"scenes":[{}"#,
            r#"{"scenes":[{"name"}]}"#,
            r#"{"scenes":[{"name":}]}"#,
            r#"{"scenes":[{}],"#,
            r#"{"scenes":[{} {}]}"#,
            r#"{"scenes":[{"name":"x"#,
            "{\"scenes\":[[[[[[[[",
        ] {
            assert_eq!(fill_missing_nodes(json), None, "{json:?}");
        }
    }

    #[test]
    fn un_texte_non_ascii_reste_intact_autour_de_l_insertion() {
        assert_eq!(
            fill_missing_nodes(r#"{"scenes":[{"name":"scène é"}],"x":"ü"}"#).as_deref(),
            Some(r#"{"scenes":[{"nodes":[],"name":"scène é"}],"x":"ü"}"#)
        );
    }
}
