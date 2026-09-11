//! T-821, T-822 — la reconstruction d'une séquence d'impacts (5.12bis).
//!
//! L'acceptance de C-16 est celle-ci : « la reconstruction client d'une
//! séquence de 1000 impacts produit exactement le champ du serveur, sans
//! instantané de resynchronisation ». Ce fichier la vérifie au niveau du noyau.
//!
//! # Ce qui est vérifié, et ce qui ne l'est pas
//!
//! Le champ de déformation appartient à M6 ; il n'existe pas encore. Ce qui
//! existe est ce dont il dépendra entièrement : la suite d'un [`DetRng`] semé
//! par `(assembly, seq)`, l'atténuation, la quantification et l'empreinte.
//!
//! Le modèle ci-dessous les compose comme M6 les composera — un impact touche
//! les nœuds d'un rayon, l'atténuation décroît avec la distance, les
//! déplacements s'accumulent en pas quantifiés — et **n'emploie que le noyau**.
//! Ce qu'il constate vaut donc pour le noyau, ni plus ni moins : si la
//! reconstruction de M6 diverge un jour alors que ce test passe, la cause sera
//! dans M6. C'est précisément ce qu'on veut pouvoir dire.

use ax_det::{
    clamp, dequantize_i8, digest_field, falloff, inv_sqrt, quantize_i8, smooth01, DetRng,
};

/// Nombre de nœuds du champ, un pour chaque composante d'un maillage modeste.
const NOEUDS: usize = 512;

/// Nombre d'impacts rejoués — celui de l'acceptance.
const IMPACTS: usize = 1000;

/// Pas de quantification du champ.
const PAS: f32 = 0.002;

/// Identifiant de l'assembly déformée.
const ASSEMBLY: u64 = 0x5A55_EE33_1177_C0DE;

/// Un impact : là où il frappe, avec quelle force, sur quel rayon.
///
/// Les trois viennent du générateur semé par `(assembly, seq)`, comme R-512 le
/// veut : le client les retrouve sans que le serveur ait à les envoyer.
struct Impact {
    centre: f32,
    force: f32,
    rayon: f32,
}

impl Impact {
    /// Reconstitue l'impact de numéro `sequence` — la même opération des deux
    /// côtés, à partir des mêmes deux nombres.
    fn reconstitue(assembly: u64, sequence: u64) -> Self {
        let mut rng = DetRng::for_impact(assembly, sequence);
        Self {
            centre: rng.next_f32() * NOEUDS as f32,
            // Une force signée : un impact creuse ou repousse.
            force: rng.next_f32() * 2.0 - 1.0,
            // Un rayon jamais nul, sans quoi l'impact ne toucherait rien.
            rayon: 1.0 + rng.next_f32() * 48.0,
        }
    }
}

/// Apport d'un impact à un nœud, dont on donne l'indice **absolu**.
///
/// Une seule écriture de la formule, employée par le rejeu séquentiel comme par
/// le rejeu réparti. Deux écritures pourraient dériver l'une de l'autre, et
/// T-822 constaterait alors que deux formules différentes donnent des résultats
/// différents — ce qui n'apprendrait rien.
///
/// L'indice est absolu et non local à une tranche : la distance au centre de
/// l'impact est une position dans le champ, et la faire dépendre du découpage
/// serait exactement la faute que T-822 cherche.
fn contribue(courant: i8, noeud: usize, impact: &Impact) -> i8 {
    let distance = noeud as f32 - impact.centre;
    // `inv_sqrt` plutôt qu'une racine, et le carré plutôt qu'une valeur
    // absolue : le noyau n'expose ni `sqrt` ni `abs`, et `|d| = d²·(1/√d²)`.
    // Le carré tient largement dans un `f32` pour `NOEUDS` nœuds.
    let carre = distance * distance;
    let normalise = if carre > 0.0 {
        (carre * inv_sqrt(carre)) / impact.rayon
    } else {
        0.0
    };

    let attenuation = falloff(normalise);
    if attenuation == 0.0 {
        return courant;
    }

    // Le lissage module la force selon la profondeur dans le rayon : c'est la
    // composition que M6 emploiera, et elle fait travailler les deux fonctions
    // ensemble plutôt que chacune de son côté.
    let poids = smooth01(1.0 - normalise);
    let apport = impact.force * attenuation * poids;

    let valeur = dequantize_i8(courant, PAS);
    quantize_i8(clamp(valeur + apport, -1.0, 1.0), PAS)
}

/// Rejoue la séquence entière sur un champ neuf, en un seul fil.
fn reconstruit_sequentiellement() -> Vec<i8> {
    let mut champ = vec![0i8; NOEUDS];
    for sequence in 0..IMPACTS as u64 {
        let impact = Impact::reconstitue(ASSEMBLY, sequence);
        for (noeud, valeur) in champ.iter_mut().enumerate() {
            *valeur = contribue(*valeur, noeud, &impact);
        }
    }
    champ
}

/// Rejoue la séquence en répartissant les **nœuds** sur `workers` tranches.
///
/// Le découpage par nœud n'est pas un artifice de test : c'est ainsi que M6
/// paralléliserait. L'ordre des **impacts** reste séquentiel — ils
/// s'accumulent —, et c'est sur les **nœuds** que le travail se répartit,
/// chacun ne dépendant que de lui-même.
///
/// Chaque fil reconstitue les impacts pour son compte, au lieu de les recevoir.
/// C'est ce que fera un client, et cela vérifie au passage que
/// [`DetRng::for_impact`] rend la même chose quel que soit le fil qui l'appelle.
fn reconstruit_par_tranches(workers: usize) -> Vec<i8> {
    let mut champ = vec![0i8; NOEUDS];
    let taille = NOEUDS.div_ceil(workers);

    std::thread::scope(|scope| {
        for (rang, morceau) in champ.chunks_mut(taille).enumerate() {
            scope.spawn(move || {
                let debut = rang * taille;
                for sequence in 0..IMPACTS as u64 {
                    let impact = Impact::reconstitue(ASSEMBLY, sequence);
                    for (local, valeur) in morceau.iter_mut().enumerate() {
                        *valeur = contribue(*valeur, debut + local, &impact);
                    }
                }
            });
        }
    });

    champ
}

#[test]
fn t821_le_client_et_le_serveur_reconstruisent_le_meme_champ() {
    // Deux reconstructions indépendantes de la même séquence : c'est ce que
    // font le serveur qui simule et le client qui rejoue. Rien ne circule entre
    // les deux, sinon l'identifiant de l'assembly et les numéros de séquence.
    let serveur = reconstruit_sequentiellement();
    let client = reconstruit_sequentiellement();

    assert_eq!(serveur, client, "les deux champs diffèrent");
    assert_eq!(
        digest_field(&serveur),
        digest_field(&client),
        "même champ, empreintes différentes : l'empreinte ne suit pas le champ"
    );
}

#[test]
fn t821_le_champ_reconstruit_porte_reellement_une_deformation() {
    // Sans ce test, un champ resté nul passerait le précédent haut la main —
    // deux champs vides sont identiques. Mille impacts doivent avoir laissé une
    // trace, à des amplitudes variées.
    let champ = reconstruit_sequentiellement();

    let touches = champ.iter().filter(|pas| **pas != 0).count();
    assert!(
        touches > NOEUDS / 2,
        "{touches} nœuds déformés sur {NOEUDS} : la séquence n'a presque rien fait"
    );

    let amplitudes: std::collections::BTreeSet<i8> = champ.iter().copied().collect();
    assert!(
        amplitudes.len() > 32,
        "seulement {} amplitudes distinctes : le champ est trop plat pour \
         constater quoi que ce soit",
        amplitudes.len()
    );

    // Et il est signé des deux côtés : un impact creuse ou repousse.
    assert!(
        champ.iter().any(|pas| *pas > 0),
        "aucun déplacement positif"
    );
    assert!(
        champ.iter().any(|pas| *pas < 0),
        "aucun déplacement négatif"
    );
}

#[test]
fn t821_l_empreinte_du_champ_est_stable_d_un_rejeu_a_l_autre() {
    // Cent rejeux : une dépendance à un état résiduel — un générateur global,
    // un cache — se verrait au deuxième.
    let reference = digest_field(&reconstruit_sequentiellement());
    for rejeu in 0..100 {
        assert_eq!(
            digest_field(&reconstruit_sequentiellement()),
            reference,
            "divergence au rejeu {rejeu}"
        );
    }
}

#[test]
fn t821_une_sequence_tronquee_ne_donne_pas_le_meme_champ() {
    // La vérification réciproque : si l'empreinte ne distinguait pas 999
    // impacts de 1000, elle ne distinguerait rien du tout, et les tests
    // ci-dessus ne constateraient rien.
    let mut partiel = vec![0i8; NOEUDS];
    for sequence in 0..(IMPACTS as u64 - 1) {
        let impact = Impact::reconstitue(ASSEMBLY, sequence);
        for (noeud, valeur) in partiel.iter_mut().enumerate() {
            *valeur = contribue(*valeur, noeud, &impact);
        }
    }

    assert_ne!(
        digest_field(&partiel),
        digest_field(&reconstruit_sequentiellement()),
        "le millième impact ne change rien à l'empreinte"
    );
}

#[test]
fn t821_une_autre_assembly_donne_un_autre_champ() {
    // R-512 : la graine dérive de l'assembly autant que du numéro de séquence.
    // Deux assemblies frappées « pareil » ne se déforment pas pareil.
    let mut autre = vec![0i8; NOEUDS];
    for sequence in 0..IMPACTS as u64 {
        let impact = Impact::reconstitue(ASSEMBLY ^ 1, sequence);
        for (noeud, valeur) in autre.iter_mut().enumerate() {
            *valeur = contribue(*valeur, noeud, &impact);
        }
    }

    assert_ne!(
        digest_field(&autre),
        digest_field(&reconstruit_sequentiellement())
    );
}

#[test]
fn t822_le_champ_ne_depend_pas_du_nombre_de_workers() {
    // T-822. Le pool de jobs dimensionne ses workers d'après la machine
    // (R-471) : sans cette propriété, un serveur à 16 cœurs et un client à 4 ne
    // calculeraient pas le même champ, et la reconstruction serait impossible
    // entre deux machines différentes.
    let reference = reconstruit_sequentiellement();

    for workers in [1usize, 2, 3, 4, 7, 8, 16] {
        let obtenu = reconstruit_par_tranches(workers);
        assert_eq!(
            obtenu, reference,
            "le champ diffère sur {workers} tranches de nœuds"
        );
        assert_eq!(digest_field(&obtenu), digest_field(&reference));
    }
}
