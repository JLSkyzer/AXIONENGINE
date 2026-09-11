//! Affiche la configuration déterministe de la machine courante (5.12bis).
//!
//! ```text
//! cargo run -p ax-det --example profil
//! ```
//!
//! Sert d'abord en CI, en tête du job de plateforme : quand les vecteurs d'or
//! divergent, la première question est toujours « sur quelle configuration ? ».
//! L'avoir dans le journal avant l'échec évite d'avoir à relancer pour le
//! savoir.
//!
//! Sert aussi localement, quand quelqu'un se demande pourquoi sa machine
//! bascule en `SNAPSHOT` : la ligne le dit.

fn main() {
    let description = ax_det::det_profile_string();
    let dans_la_matrice = ax_det::is_in_validation_matrix();

    println!("configuration : {description}");
    println!("empreinte     : 0x{:016X}", ax_det::det_profile());
    println!("noyau         : version {}", ax_det::DET_KERNEL_VERSION);

    if dans_la_matrice {
        println!("matrice       : oui — la reconstruction par evenements est garantie ici");
    } else {
        // Pas une erreur, et le code de sortie le dit : R-515 interdit qu'une
        // configuration hors matrice fasse refuser AXION. Elle bascule en
        // SNAPSHOT, ce qui ne retire aucune fonctionnalite (R-514).
        println!("matrice       : NON — la replication basculerait en SNAPSHOT");
        println!();
        println!("Les configurations de la matrice de validation :");
        for entree in ax_det::VALIDATION_MATRIX {
            println!("  {}-{}-{}", entree.arch, entree.os, entree.env);
        }
        println!();
        println!(
            "Le jeu de base est exige en plus de la cible : ni fma, ni avx.\n\
             Un binaire bati avec -C target-cpu=native n'en fait pas partie."
        );
    }
}
