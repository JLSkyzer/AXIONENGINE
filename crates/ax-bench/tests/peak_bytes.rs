//! Le pic de mémoire d'une charge (ADR-146 §4) : celui qu'elle tient au plus haut, au-dessus
//! de son départ — pas la somme de ses allocations, ni ce qui reste à la fin.

use ax_bench::peak_bytes::{peak, PeakBytes};
use std::hint::black_box;

#[global_allocator]
static ALLOCATOR: PeakBytes = PeakBytes::new();

const MIO: usize = 1 << 20;

#[test]
fn le_pic_est_le_plus_haut_tenu_a_la_fois() {
    // Trois blocs de 1 Mio l'un après l'autre, jamais deux à la fois : le pic est d'un bloc.
    let ((), successifs) = peak(|| {
        for _ in 0..3 {
            drop(black_box(vec![1u8; MIO]));
        }
    });
    assert!(
        (MIO as u64..MIO as u64 + 4096).contains(&successifs),
        "pic de {successifs} octets"
    );

    // Les trois tenus ensemble : le pic est de trois blocs.
    let ((), ensemble) = peak(|| {
        let blocs: Vec<Vec<u8>> = (0..3).map(|_| black_box(vec![1u8; MIO])).collect();
        drop(black_box(blocs));
    });
    assert!(
        (3 * MIO as u64..3 * MIO as u64 + 4096).contains(&ensemble),
        "pic de {ensemble} octets"
    );
}

#[test]
fn le_pic_part_de_ce_que_le_thread_tenait_deja() {
    let deja = black_box(vec![1u8; 4 * MIO]);
    let ((), pic) = peak(|| drop(black_box(vec![1u8; MIO])));
    assert!(
        pic < 2 * MIO as u64,
        "les 4 Mio tenus avant ne comptent pas : pic de {pic} octets"
    );
    drop(deja);
}
