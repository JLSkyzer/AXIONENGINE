# rapier3d 0.35.3, corrigé pour AXION ENGINE

Ce répertoire est le paquet `rapier3d` 0.35.3 tel que publié sur crates.io (dimforge, Apache-2.0 : voir
`LICENSE`), branché sur le workspace par `[patch.crates-io]` dans le `Cargo.toml` racine. La décision et
ses mesures sont dans [ADR-152](../../docs/decisions/ADR-152.md).

Chaque fichier modifié porte en tête une mention `Modified for AXION ENGINE (ADR-152, ...)`, et chaque
ajout dans le code est marqué `AXION`.

## Les correctifs

| Correctif | Fichier | Effet |
|---|---|---|
| P0 | `src/dynamics/joint/generic_joint.rs` | `supports_simd_constraints` rend faux : tous les joints impulsionnels prennent la voie scalaire. |
| P1 | `src/dynamics/solver/joint_constraint/joint_velocity_constraint.rs` | Dans `JointConstraint::<Real, 1>::update`, un seul groupe d'orthogonalisation : axes bloqués, moteurs, frictions, butées. |
| P2 | `generic_joint.rs`, `joint_velocity_constraint.rs`, `src/dynamics/solver/contact_constraint/contact_constraints_set.rs` | `GenericJoint::friction`, `friction_axes`, `set_friction`, `GenericJointBuilder::friction` ; une ligne de friction par axe libre non couplé, comptée par `joint_data_num_constraints`. |
| P3 | `src/dynamics/joint/impulse_joint/impulse_joint.rs`, `impulse_joint_set.rs`, `src/dynamics/solver/joint_constraint/joint_constraint_builder.rs`, `joint_constraints_set.rs` | `ImpulseJoint::applied_held` et `applied_driven` : l'impulsion appliquée au second corps sur le pas, sommée sur les sous-pas internes, linéaire puis angulaire autour de l'ancrage. |

## Monter de version

1. Copier le nouveau paquet publié à la place de celui-ci (`~/.cargo/registry/src/*/rapier3d-<version>`).
2. Réappliquer P0 à P3 : la différence avec le paquet d'origine se lit en comparant ce répertoire à
   `rapier3d-0.35.3`.
3. Rejouer les tests de Rapier, depuis ce répertoire :
   `cargo test --release --features enhanced-determinism`.
4. Rejouer `cargo test -p ax-physics --test joints` (T-330 à T-334) et S-05.
5. Mettre à jour ce fichier et ADR-152.
