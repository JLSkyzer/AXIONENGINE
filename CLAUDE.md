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

## Choix du modèle

Le modèle se choisit **à l'ouverture d'une session** et ne change plus jusqu'à
sa fin : basculer en cours de route force la relecture de toute la session.

**Par défaut : Sonnet, effort élevé.** L'essentiel du travail est spécifié ligne
par ligne par le cahier des charges. L'effort élevé n'est pas optionnel : ce
projet interdit les raccourcis — pas de placeholder, pas de périmètre réduit
« pour raison de coût » — et c'est vers là que dérive un modèle pressé.

**Ouvrir une session Opus dédiée pour :**

- M0.4 — frontière FFI, ABI, mémoire partagée et anneaux de transfert
- C-16 — noyau déterministe (bit-exactitude, FMA, ordre d'itération)
- M6 — déformation continue
- tout crash natif : SIGSEGV, corruption mémoire, comportement indéfini

Ces choix se paient longtemps et leurs erreurs sont silencieuses. Le critère
général : **si un test peut dire que c'est faux, Sonnet suffit ; si l'erreur est
silencieuse ou coûteuse à défaire, c'est Opus.** Il recouvre la liste du CDC de
ce qu'un agent ne décide pas seul — contrat `IF-xx`, ABI, `DM-xx`, `INV-xx`,
dépendance structurante, cible Mixin.

**Si un bug résiste après trois tentatives, ne pas basculer en cours de
session.** Consigner l'état dans `tasks/todo.md` et les pistes écartées dans
`tasks/lessons.md`, fermer la session, en rouvrir une en Opus. Le contexte du
projet vit dans ces fichiers, pas dans le fil : une session neuve reprend en
trois lectures.

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
