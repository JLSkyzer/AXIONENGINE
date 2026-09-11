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
    } else if ax_det::is_matrix_target() {
        // Etat d'amorcage : la machine est une cible de la matrice, mais
        // personne n'y a encore rejoue les vecteurs d'or. R-516 refuse de la
        // declarer deterministe avant cette preuve, et c'est en jouant la suite
        // ici qu'on la produit.
        println!("matrice       : cible NON VALIDEE");
        println!();
        println!("Les vecteurs d'or n'ont pas encore ete rejoues sur cette");
        println!("configuration. R-516 : une configuration non validee n'est");
        println!("jamais declaree deterministe, meme si elle passe en pratique.");
        println!();
        println!("Pour la valider, dans cet ordre :");
        println!("  1. cargo test -p ax-det");
        println!("  2. reporter le resultat dans docs/spec/MATRICE-DETERMINISTE.md");
        println!("  3. basculer son drapeau `validated` dans VALIDATION_MATRIX");
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
