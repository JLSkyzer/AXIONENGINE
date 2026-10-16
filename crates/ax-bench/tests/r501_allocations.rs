//! R-501, ADR-133 A1 : les allocations des charges de B-08, comptées sous
//! [`AllocationEvents`] installé comme allocateur global de ce binaire de test.
//!
//! Le compte est le facteur du calcul que la machine ne bruite pas : il doit voir chaque
//! allocation d'une charge au compte connu, et redonner le même nombre d'une exécution à
//! l'autre.

use ax_bench::alloc_events::{count, AllocationEvents};
use ax_bench::cases::memory;

#[global_allocator]
static ALLOCATOR: AllocationEvents = AllocationEvents::new();

#[test]
fn t_r501_le_compteur_voit_chaque_allocation_de_small_allocations() {
    let ((), allocations) = count(memory::small_allocations);
    assert_eq!(
        allocations,
        u64::try_from(memory::SMALL_ALLOCATIONS).unwrap(),
        "small_allocations fait une allocation par bloc"
    );
}

#[test]
fn t_r501_le_compte_de_la_charge_physique_est_reproductible() {
    // Une première exécution règle ce qui ne s'alloue qu'une fois par processus ; B-08 mesure
    // après échauffement, le compte aussi.
    memory::physics_ticks();
    let ((), first) = count(memory::physics_ticks);
    let ((), second) = count(memory::physics_ticks);
    assert!(
        first > 0,
        "la charge physique alloue : construction du monde au moins"
    );
    assert_eq!(first, second, "le compte d'une même charge ne varie pas");

    // Les pas seuls, monde déjà construit : une part du tout.
    let mut world = memory::physics_world();
    let ((), steps) = count(|| memory::physics_steps(&mut world));
    assert!(
        steps < first,
        "{steps} allocations sur les pas, {first} en tout"
    );
    println!(
        "allocations : physics_ticks {first}, dont {steps} sur les {} pas",
        memory::TICKS
    );
}
