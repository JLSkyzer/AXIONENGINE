//! `a3d_reader` — lecture d'un conteneur A3D depuis des octets hostiles.
//!
//! R-903 nomme cette cible explicitement, et pour une raison : le lecteur A3D
//! est le seul composant d'AXION qui lit un fichier **fourni par un tiers** et
//! en tire des tailles, des décalages et des longueurs de décompression. C'est
//! la surface d'attaque de la chaîne d'assets ; tout le reste travaille sur des
//! structures déjà validées.
//!
//! Ce que la cible cherche : une panique, un débordement, une allocation
//! démesurée, une boucle qui ne rend pas la main. **Une erreur n'est pas un
//! défaut** — refuser un fichier corrompu est précisément le travail de ce
//! lecteur, et 7.5 le lui demande. Le défaut serait de ne pas refuser, ou de
//! refuser en paniquant.

#![no_main]

use ax_asset::a3d::{
    decode_materials, decode_nodes, decode_textures, A3dFile, A3dLimits, SectionMask, SectionTag,
};
use libfuzzer_sys::fuzz_target;

/// Plafond employé pour le fuzzing.
///
/// Un mébioctet, comme les tests d'intégration, et non les 128 Mio du registre
/// de configuration (`assets.max_source_bytes`). Le code de vérification ne
/// branche pas sur la valeur : c'est la même comparaison, quel que soit le
/// plafond. Un plafond bas garde en revanche le fuzzer rapide, là où une en-tête
/// annonçant cent mébioctets ferait passer l'essentiel du temps dans
/// l'allocateur au lieu du lecteur.
const LIMITS: A3dLimits = A3dLimits::new(1 << 20);

fuzz_target!(|data: &[u8]| {
    let Ok(fichier) = A3dFile::open(data, LIMITS) else {
        // Un en-tête refusé est le cas nominal : rien à explorer plus loin.
        return;
    };

    // `open` ne lit que l'en-tête et la table des sections. Tout ce qui compte
    // vient après : les CRC des charges utiles, la décompression bornée
    // (R-902), et les bornes que R-900 fait reposer sur `total_size`.
    let _ = fichier.load(SectionMask::all());

    // Puis section par section, ce qui suit un autre chemin que le chargement
    // en masse et rencontre les tags que la table déclare — y compris ceux
    // qu'un fichier malveillant déclarerait deux fois.
    for entree in fichier.entries() {
        let _ = entree;
    }
    // Les dix-sept tags normatifs sont interrogés **tous**, y compris ceux que
    // le fichier ne déclare pas. Ne demander que ce que la table annonce
    // laisserait de côté le chemin du tag absent, et c'est celui où une table
    // mensongère fait le plus de dégâts. La liste vient de `NORMATIVE` plutôt
    // que d'être recopiée : une section ajoutée à la PARTIE 7 est fuzzée sans
    // que personne ait à y penser.
    for tag in SectionTag::NORMATIVE {
        let _ = fichier.has(tag);
        let _ = fichier.section(tag);
        let _ = fichier.stored_bytes(tag);
    }

    // Une section intègre n'est pas une section bien formée : la table des
    // nodes tire elle aussi des dénombrements, des décalages et des longueurs
    // de ses octets (ADR-110), et un CRC juste ne dit rien de leur cohérence.
    if let Ok(Some(nodes)) = fichier.section(SectionTag::NODE) {
        let _ = decode_nodes(&nodes);
    }
    // `MATL` et `TEXR` (ADR-122) tirent eux aussi des comptes, des décalages et
    // des tailles de leurs octets.
    if let Ok(Some(materiaux)) = fichier.section(SectionTag::MATL) {
        let _ = decode_materials(&materiaux);
    }
    if let Ok(Some(textures)) = fichier.section(SectionTag::TEXR) {
        let _ = decode_textures(&textures);
    }
});
