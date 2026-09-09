# AXION ENGINE — instructions de travail

Framework 3D, physique, déformation continue, animation et simulation pour
Minecraft 1.20.1 + Forge 47.x. Architecture Java + Rust. Licence Apache-2.0.

## À faire en début de session

1. Lire [`tasks/lessons.md`](tasks/lessons.md) — appliquer les leçons avant de
   toucher quoi que ce soit.
2. Lire [`tasks/todo.md`](tasks/todo.md) — état d'avancement et jalon courant.
3. Lire [`docs/AGENT.md`](docs/AGENT.md) — comment travailler ici.

## La source de vérité

`cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md`, 8270 lignes, **FINAL / FROZEN**.
Tout en découle : composants, exigences, tests, budgets, critères d'acceptation.

**Ne jamais le lire en entier.** Il ne tient pas dans un contexte, et le lire en
bloc gaspille la session. Deux index générés servent à n'en lire que l'utile :

```bash
grep -n "PARTIE 14" docs/spec/INDEX.md      # trouver la plage de lignes
sed -n '3586,3965p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md
grep -P '^C-42\t' docs/spec/ID-MAP.tsv      # trouver un identifiant
```

Régénérer après toute modification du CDC : `python tools/spec/spec_index.py`.

## Boucle de travail

`READ SPEC -> PLAN -> IMPLEMENT -> BUILD -> TEST -> FIX -> DOCUMENT`

Lire la fiche du composant en PARTIE 5 **avant** de coder. Builder et tester
**après chaque** implémentation. Ne jamais empiler plusieurs composants non
testés. Un travail n'est terminé que quand sa Definition of Done est cochée.

## Règles absolues

- Aucun `TODO`, `FIXME`, `todo!()`, `unimplemented!()`, stub ni implémentation
  factice dans un module déclaré `STABLE` ou `EXPERIMENTAL` (R-001).
- Aucun chiffre de performance inventé : une mesure manquante se mesure.
- Aucune fonctionnalité réduite ou retirée pour raison de coût — ajouter un
  niveau de qualité, un budget, un LOD ou un repli à la place.
- Aucune branche conditionnelle sur un nom de mod, de véhicule ou de contenu :
  le moteur est générique, le contenu est data-driven.
- Aucun test désactivé pour le faire passer, aucun invariant `INV-xx` affaibli.
- Aucune dépendance ni import de RUSTFORGE-X : le projet en est indépendant.
- Aucune classe hors de `dev.axion.forge` n'importe `net.minecraftforge.*`
  (R-401, T-020).

La liste complète des 25 interdictions et des 14 obligations est dans le CDC :
`sed -n '8160,8217p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md`.

## Commandes

```bash
./gradlew build
```

```bash
cargo test --workspace --all-features
```

`./gradlew runClient`, `runServer`, `runData`, `runGameTestServer` lancent le jeu
en développement.

## Commits

Le CDC impose de citer les identifiants concernés :

```
feat(C-42): champ de déformation plastique quantifié [R-1420, T-802]
```

Le dépôt n'a **aucun remote** : les commits restent locaux tant qu'il n'en a pas.
Ne jamais laisser croire qu'un travail est poussé.
