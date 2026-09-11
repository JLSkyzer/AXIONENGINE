//! Empreinte de configuration déterministe et matrice de validation (5.12bis).

use crate::digest::digest64;
use crate::DET_KERNEL_VERSION;

/// Description textuelle de la configuration déterministe courante.
///
/// Elle réunit ce dont dépend un résultat bit-à-bit : la cible, la version du
/// noyau, le jeu d'instructions **effectivement compilé** et l'état de la
/// contraction en multiplication-addition fusionnée.
///
/// Le jeu d'instructions est lu par `cfg!(target_feature = …)`, donc tel que le
/// compilateur l'a vraiment employé — et non tel que le triplet le laisse
/// croire. Un binaire construit avec `-C target-cpu=native` sur une machine
/// récente n'a pas le même jeu qu'un binaire de base, et c'est exactement le
/// cas que cette empreinte doit distinguer.
#[must_use]
pub fn det_profile_string() -> String {
    let mut features: Vec<&str> = Vec::new();
    // Seules les fonctionnalités qui changent l'arithmétique flottante entrent
    // ici. Les autres — chiffrement, comptage de bits — n'ont aucun effet sur
    // un résultat, et les inclure ferait diverger deux configurations
    // équivalentes.
    if cfg!(target_feature = "sse2") {
        features.push("sse2");
    }
    if cfg!(target_feature = "avx") {
        features.push("avx");
    }
    if cfg!(target_feature = "avx2") {
        features.push("avx2");
    }
    if cfg!(target_feature = "fma") {
        features.push("fma");
    }
    if cfg!(target_feature = "neon") {
        features.push("neon");
    }

    format!(
        "axion-det/{version} {arch}-{os}-{env} [{features}]",
        version = DET_KERNEL_VERSION,
        arch = std::env::consts::ARCH,
        os = std::env::consts::OS,
        env = target_env(),
        features = features.join(",")
    )
}

/// Environnement de la cible, tel que le triplet le nomme.
const fn target_env() -> &'static str {
    if cfg!(target_env = "msvc") {
        "msvc"
    } else if cfg!(target_env = "gnu") {
        "gnu"
    } else if cfg!(target_env = "musl") {
        "musl"
    } else {
        // macOS n'a pas d'environnement dans son triplet.
        "none"
    }
}

/// Empreinte de configuration déterministe, échangée au handshake (5.12bis).
///
/// Deux extrémités qui la partagent rejouent les impacts localement ; deux
/// extrémités qui diffèrent basculent en `SNAPSHOT` **avant** qu'une divergence
/// n'ait l'occasion de se produire. C'est un repli préventif, et c'est ce qui
/// distingue cette empreinte d'un simple diagnostic : on ne la consulte pas
/// après coup, on décide avec.
#[must_use]
pub fn det_profile() -> u64 {
    digest64(det_profile_string().as_bytes())
}

/// Une entrée de la matrice de validation déterministe (5.12bis).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MatrixEntry {
    /// Architecture, telle que `std::env::consts::ARCH` la nomme.
    pub arch: &'static str,
    /// Système, tel que `std::env::consts::OS` le nomme.
    pub os: &'static str,
    /// Environnement du triplet.
    pub env: &'static str,
    /// Les vecteurs d'or de la version courante y ont-ils été **rejoués** ?
    ///
    /// R-516 : « une configuration non validée n'est jamais déclarée
    /// déterministe, **même si elle passe en pratique** ». Figurer dans la
    /// matrice du cahier des charges dit qu'AXION vise cette configuration ;
    /// seule une exécution archivée dit qu'elle tient.
    ///
    /// Une entrée à `false` bascule donc en `SNAPSHOT`, ce qui ne retire aucune
    /// fonctionnalité (R-514) et ne refuse rien (R-515) — là où la déclarer
    /// déterministe sans preuve promettrait une bit-exactitude que personne n'a
    /// constatée.
    ///
    /// Le registre des exécutions est
    /// [`docs/spec/MATRICE-DETERMINISTE.md`](../../../docs/spec/MATRICE-DETERMINISTE.md).
    pub validated: bool,
}

/// La matrice de validation déterministe de la V1.0.
///
/// Cinq configurations, et **seulement** elles. R-517 : « déterministe » et
/// « bit-identique » renvoient toujours à cette portée, jamais à une garantie
/// universelle.
///
/// Le drapeau [`MatrixEntry::validated`] distingue ce que le cahier des charges
/// **vise** de ce qu'une exécution a **constaté**. Les deux ne coïncident pas
/// aujourd'hui, et les confondre serait exactement ce que R-516 interdit.
pub const VALIDATION_MATRIX: [MatrixEntry; 5] = [
    MatrixEntry {
        arch: "x86_64",
        os: "windows",
        env: "msvc",
        validated: true,
    },
    MatrixEntry {
        arch: "x86_64",
        os: "linux",
        env: "gnu",
        validated: true,
    },
    MatrixEntry {
        arch: "aarch64",
        os: "macos",
        env: "none",
        validated: true,
    },
    MatrixEntry {
        arch: "x86_64",
        os: "macos",
        env: "none",
        validated: false,
    },
    MatrixEntry {
        // `linux-aarch64` est « best effort, non bloquant » en 34.2, et GitHub
        // ne fournit pas de runner ARM sur le plan de ce dépôt : les vecteurs
        // d'or n'y ont jamais été rejoués. Les deux axes le sont pourtant
        // séparément — même architecture que `aarch64-macos`, même système et
        // même libc que `x86_64-linux-gnu` —, et c'est précisément le
        // raisonnement que R-516 refuse : « même si elle passe en pratique ».
        arch: "aarch64",
        os: "linux",
        env: "gnu",
        validated: false,
    },
];

/// Indique si la configuration courante figure dans la matrice.
///
/// La cible ne suffit pas : la matrice impose aussi un **jeu d'instructions de
/// base, contraction fusionnée désactivée**. Un binaire construit pour la bonne
/// cible mais avec `-C target-cpu=native` n'en fait pas partie, et le dire est
/// tout l'intérêt de cette fonction.
///
/// Une entrée **non validée** rend `false`, même si la cible correspond :
/// R-516 réserve la déclaration de détermination aux configurations dont les
/// vecteurs d'or ont été rejoués et le résultat archivé.
///
/// R-515 : une configuration hors matrice ne fait **jamais** refuser AXION. Elle
/// fait basculer la réplication en `SNAPSHOT`, ce qui ne retire aucune
/// fonctionnalité (R-514).
#[must_use]
pub fn is_in_validation_matrix() -> bool {
    if cfg!(target_feature = "fma") {
        // La contraction fusionnée change le résultat d'un `a·b + c` sans
        // qu'aucune ligne de code ne bouge. La matrice l'exclut.
        return false;
    }
    if cfg!(target_feature = "avx") || cfg!(target_feature = "avx2") {
        // Au-delà du jeu de base, le compilateur vectorise autrement et l'ordre
        // des réductions change (R-511).
        return false;
    }

    let arch = std::env::consts::ARCH;
    let os = std::env::consts::OS;
    let env = target_env();
    VALIDATION_MATRIX
        .iter()
        .any(|entry| entry.validated && entry.arch == arch && entry.os == os && entry.env == env)
}

/// Mode de réplication qu'impose une paire d'empreintes (5.12bis).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReplicationMode {
    /// Les impacts sont rejoués localement. Le mode par défaut.
    Reconstruct,
    /// Le serveur envoie des instantanés de champ quantifiés.
    ///
    /// **Aucune fonctionnalité n'est retirée** (R-514) : la déformation, la
    /// structure, la persistance et le rendu sont identiques. Seul le coût
    /// réseau augmente.
    Snapshot,
}

/// Décide du mode de réplication au handshake (5.12bis).
///
/// Le repli est **préventif** : il suffit qu'une extrémité soit hors matrice,
/// ou que les deux empreintes diffèrent, pour que la reconstruction soit
/// écartée. Attendre une divergence pour basculer reviendrait à laisser le
/// premier désaccord se produire, et un champ divergent se voit à l'écran avant
/// de se voir dans un journal.
#[must_use]
pub fn negotiate(
    local_profile: u64,
    remote_profile: u64,
    local_in_matrix: bool,
    remote_in_matrix: bool,
) -> ReplicationMode {
    if local_profile == remote_profile && local_in_matrix && remote_in_matrix {
        ReplicationMode::Reconstruct
    } else {
        ReplicationMode::Snapshot
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t820_l_empreinte_est_stable_dans_un_meme_binaire() {
        let first = det_profile();
        for _ in 0..100 {
            assert_eq!(det_profile(), first);
        }
    }

    #[test]
    fn t820_l_empreinte_nomme_ce_dont_elle_depend() {
        let description = det_profile_string();
        assert!(
            description.contains(&DET_KERNEL_VERSION.to_string()),
            "{description}"
        );
        assert!(
            description.contains(std::env::consts::ARCH),
            "{description}"
        );
        assert!(description.contains(std::env::consts::OS), "{description}");
        // La description est lisible : c'est elle qu'un joueur verra dans
        // `/axion compat` quand il voudra savoir pourquoi il est en SNAPSHOT.
        assert!(description.starts_with("axion-det/"), "{description}");
    }

    #[test]
    fn t820b_une_configuration_hors_matrice_bascule_en_snapshot() {
        // R-515 : jamais un refus, toujours un repli.
        assert_eq!(
            negotiate(1, 1, true, false),
            ReplicationMode::Snapshot,
            "une extrémité hors matrice"
        );
        assert_eq!(negotiate(1, 1, false, true), ReplicationMode::Snapshot);
        assert_eq!(negotiate(1, 1, false, false), ReplicationMode::Snapshot);
    }

    #[test]
    fn t820c_des_empreintes_differentes_basculent_des_le_handshake() {
        // Attendre une divergence reviendrait à laisser le premier désaccord se
        // produire, et il se voit à l'écran avant de se voir dans un journal.
        assert_eq!(negotiate(1, 2, true, true), ReplicationMode::Snapshot);
    }

    #[test]
    fn t820_deux_extremites_identiques_et_dans_la_matrice_reconstruisent() {
        assert_eq!(negotiate(42, 42, true, true), ReplicationMode::Reconstruct);
    }

    #[test]
    fn t820_une_configuration_non_validee_ne_se_declare_pas_deterministe() {
        // R-516 : la validation se constate, elle ne se déduit pas. Ce test
        // existe pour que retirer le drapeau — ou l'ignorer dans le filtre —
        // casse quelque chose de nommé, plutôt que de rendre silencieusement
        // déterministe une configuration que personne n'a rejouée.
        let non_validees: Vec<&MatrixEntry> =
            VALIDATION_MATRIX.iter().filter(|e| !e.validated).collect();

        for entree in &non_validees {
            let courante = entree.arch == std::env::consts::ARCH
                && entree.os == std::env::consts::OS
                && entree.env == target_env();
            assert!(
                !courante || !is_in_validation_matrix(),
                "{entree:?} n'est pas validée et se déclare pourtant déterministe"
            );
        }

        // Et la réciproque, qui dit que le drapeau sert vraiment à quelque
        // chose : `aarch64-unknown-linux-gnu` figure dans la matrice du cahier
        // des charges sans avoir jamais été rejouée ici.
        assert!(
            non_validees
                .iter()
                .any(|e| e.arch == "aarch64" && e.os == "linux" && e.env == "gnu"),
            "linux-aarch64 déclarée validée : l'archivage exigé par R-516 doit \
             alors exister dans docs/spec/MATRICE-DETERMINISTE.md"
        );
    }

    #[test]
    fn t820_la_matrice_est_celle_de_la_version_1_0() {
        assert_eq!(VALIDATION_MATRIX.len(), 5);
        // Aucune entrée en double : une configuration y figurerait deux fois
        // sans que rien ne le dise.
        for (index, entry) in VALIDATION_MATRIX.iter().enumerate() {
            for other in &VALIDATION_MATRIX[index + 1..] {
                assert_ne!(entry, other, "{entry:?} en double");
            }
        }
    }

    #[test]
    fn t820_la_configuration_de_test_est_dans_la_matrice() {
        // Ce test n'affirme rien d'universel : il dit que la machine qui joue
        // la suite fait partie de la matrice. Sur une machine hors matrice, il
        // échouerait — et ce serait la bonne réponse, puisque les vecteurs d'or
        // n'y valent alors rien.
        assert!(
            is_in_validation_matrix(),
            "configuration hors matrice : {}",
            det_profile_string()
        );
    }
}
