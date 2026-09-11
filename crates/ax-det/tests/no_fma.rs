//! T-820 — R-510 : le binaire produit ne contracte pas en multiplication-addition fusionnée.
//!
//! R-510 demande des drapeaux de compilation : `-C llvm-args=-fp-contract=off`
//! et `-C target-feature=-fma`. Un drapeau est une **promesse** ; ce test est
//! une **constatation**. Il interroge le binaire réellement produit, et c'est la
//! seule chose qui protège du jour où quelqu'un ajoutera `-C
//! target-cpu=native` pour gagner trois pour cent.
//!
//! Pourquoi cela compte : `a·b + c` fusionné n'arrondit qu'une fois, non
//! fusionné deux fois. Les deux résultats diffèrent sur une infinité d'entrées.
//! Un client qui contracte et un serveur qui ne contracte pas calculent des
//! champs différents à partir des mêmes impacts — sans qu'aucune ligne de code
//! ne diffère, et sans qu'aucun test ordinaire ne le voie.

use ax_det::DetRng;
use std::hint::black_box;

/// Rend un flottant fini et de magnitude raisonnable, tiré du générateur.
fn sample(rng: &mut DetRng) -> f32 {
    // Des valeurs autour de l'unité : c'est là que la double précision de
    // l'accumulation fusionnée se distingue le plus nettement, et c'est aussi
    // l'ordre de grandeur des champs de déformation.
    (rng.next_f32() * 4.0) - 2.0
}

#[test]
fn t820_le_binaire_ne_contracte_pas_en_fusionnee() {
    let mut rng = DetRng::new(0x00FA_0FFA_0FFA_0FFA);
    let mut distincts = 0;

    for _ in 0..200_000 {
        let a = black_box(sample(&mut rng));
        let b = black_box(sample(&mut rng));
        let c = black_box(sample(&mut rng));

        // `mul_add` est fusionnée par contrat : un seul arrondi, que le
        // matériel le fasse ou que la bibliothèque l'émule.
        let fused = a.mul_add(b, c);
        // Cette expression-ci doit rester non fusionnée. Si le compilateur la
        // contracte, les deux valeurs coïncident **toujours**.
        let separate = black_box(a) * black_box(b) + black_box(c);

        if fused.to_bits() != separate.to_bits() {
            distincts += 1;
        }
    }

    assert!(
        distincts > 0,
        "aucune des 200 000 comparaisons ne distingue le calcul fusionné du \
         calcul séparé : le compilateur contracte, et deux extrémités bâties \
         avec des drapeaux différents ne calculeront pas le même champ"
    );
}

// `clippy` note que l'assertion porte sur une constante. C'est le sujet : la
// constante est la configuration de compilation du binaire, et c'est elle qu'on
// veut constater. Une assertion dont la valeur dépendrait de l'exécution ne
// dirait rien de la façon dont ce binaire a été bâti.
#[allow(clippy::assertions_on_constants)]
#[test]
fn t820_la_fonctionnalite_fma_n_est_pas_activee() {
    // Le pendant statique du test précédent, sur les cibles x86 où `fma` est le
    // nom d'une fonctionnalité. Il ne remplace pas la constatation : il la
    // devance, avec un message qui dit quoi faire.
    assert!(
        !cfg!(target_feature = "fma"),
        "la fonctionnalité `fma` est activée : la matrice de validation \
         déterministe l'exclut (5.12bis). Retirer `-C target-cpu=native` ou \
         ajouter `-C target-feature=-fma`."
    );
}

#[test]
fn t820_une_configuration_hors_matrice_se_declare_telle() {
    // La cohérence des deux : si `fma` était activée, la configuration devrait
    // se déclarer hors matrice — et basculer en SNAPSHOT plutôt que de
    // prétendre reconstruire.
    if cfg!(target_feature = "fma") || cfg!(target_feature = "avx") {
        assert!(
            !ax_det::is_in_validation_matrix(),
            "configuration au-delà du jeu de base déclarée dans la matrice : {}",
            ax_det::det_profile_string()
        );
    }
}

#[test]
fn t822_le_resultat_ne_depend_pas_du_nombre_de_fils() {
    // T-822 : le noyau est sans état partagé. Le même calcul, mené depuis
    // plusieurs fils, rend les mêmes bits — sans quoi le nombre de workers du
    // pool de jobs suffirait à faire diverger deux extrémités.
    let reference: Vec<u32> = (0..1000)
        .map(|index| {
            let x = index as f32 / 1000.0;
            ax_det::falloff(x).to_bits() ^ ax_det::smooth01(x).to_bits()
        })
        .collect();

    std::thread::scope(|scope| {
        for _ in 0..8 {
            let reference = &reference;
            scope.spawn(move || {
                for (index, attendu) in reference.iter().enumerate() {
                    let x = index as f32 / 1000.0;
                    let obtenu = ax_det::falloff(x).to_bits() ^ ax_det::smooth01(x).to_bits();
                    assert_eq!(obtenu, *attendu, "divergence entre fils en {x}");
                }
            });
        }
    });
}
