# Contrat de l'agent de développement

Ce document dit **comment travailler** sur AXION ENGINE. Il ne remplace pas le
cahier des charges et n'en recopie rien : le CDC est la source de vérité unique,
et une copie divergerait dès la première modification. Ce fichier donne les
pointeurs, les commandes et les règles de conduite.

---

## 1. La source de vérité

`cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md` — 8270 lignes, révision 2.1,
**FINAL / FROZEN**.

Il est trop gros pour tenir dans un contexte : ne jamais l'ouvrir en entier.

**Trouver une section :**

```bash
grep -n "PARTIE 14" docs/spec/INDEX.md
```

`docs/spec/INDEX.md` donne, pour chacune des 452 sections, sa plage de lignes.
On lit ensuite exactement cette plage :

```bash
sed -n '3586,3965p' cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md
```

**Trouver un identifiant** (`R-1220`, `C-42`, `T-808`, `INV-11`, `ADR-011`) :

```bash
grep -P '^C-42\t' docs/spec/ID-MAP.tsv
```

`docs/spec/ID-MAP.tsv` couvre 1331 identifiants et donne pour chacun sa ligne de
définition, son nombre d'occurrences et la section qui le porte. Les colonnes
sont `id, famille, ligne_def, ligne_1re, occurrences, section`.

Les deux fichiers sont **générés**. Après toute modification du CDC :

```bash
python tools/spec/spec_index.py
```

Les familles d'identifiants sont définies en PARTIE 0.4 : `R` exigence,
`C` composant, `IF` interface, `DM` modèle de données, `SM` machine à états,
`INV` invariant, `FM` mode de défaillance, `T` test, `B` benchmark,
`ADR` décision, `E` code d'erreur, `H` hypothèse, `Q` niveau de qualité.

---

## 2. La boucle de travail

Imposée par le CDC (`sed -n '8144,8158p' ...`) :

```
READ SPEC -> INSPECT REPOSITORY -> PLAN -> IMPLEMENT -> BUILD -> TEST -> FIX
          -> BENCHMARK -> DOCUMENT -> PACKAGE -> RELEASE
```

En pratique :

1. Lire la fiche du composant en PARTIE 5 **avant** d'écrire une ligne de code,
   et la partie dédiée s'il en a une.
2. Après chaque implémentation, builder **et** exécuter les tests immédiatement.
3. Ne jamais empiler plusieurs composants non testés.
4. Un travail n'est pas terminé tant que sa Definition of Done n'est pas cochée
   (`sed -n '7318,7364p' ...`).
5. Chaque jalon se termine par un JAR installable et jouable.

L'état d'avancement vit dans [`tasks/todo.md`](../tasks/todo.md), les pièges déjà
rencontrés dans [`tasks/lessons.md`](../tasks/lessons.md).

---

## 3. Commandes

```bash
./gradlew build
```

Compile les deux modules Java et produit
`java/axion-mod/build/libs/axion-1.0.0-SNAPSHOT.jar`.

```bash
./gradlew runClient
```

Lance un client de développement. `runServer`, `runData` et `runGameTestServer`
existent aussi.

```bash
cargo test --workspace --all-features
```

Tests de la partie native.

```bash
python tools/spec/spec_index.py
```

Régénère l'index du CDC.

```bash
cargo run -p ax-model --bin gen_config_docs
cargo run -p axion-codegen --bin gen_java_config
```

Régénèrent ce qui dérive de la source unique de configuration :
`CONFIGURATION.md`, les fichiers TOML de référence et la classe Java
`ConfigSchema`. Des tests échouent si l'un d'eux a divergé (R-430, T-005).

---

## 4. Ce qui est interdit

La liste normative complète est en `sed -n '8160,8198p' ...`. Les interdictions
qui se violent le plus facilement sans y penser :

- **Aucun `TODO`, `FIXME`, `todo!()`, `unimplemented!()`, stub ni implémentation
  factice** dans un module `STABLE` ou `EXPERIMENTAL` (R-001). Un module qui
  n'est pas prêt n'est pas déclaré prêt.
- **Aucun chiffre de performance inventé.** Une mesure manquante se mesure, elle
  ne se devine pas.
- **Aucune fonctionnalité retirée ou réduite pour raison de coût.** Ajouter un
  niveau de qualité, un budget, un LOD ou un repli — jamais supprimer.
- **Aucune branche conditionnelle sur un nom de mod, de véhicule ou de contenu**
  dans le moteur. Tout est data-driven.
- **Aucun test désactivé pour le faire passer**, aucun invariant affaibli pour
  faire passer un test.
- **Aucune mutation de l'état Minecraft hors du thread autoritatif**, aucun appel
  OpenGL hors du render thread.
- **Aucune panic Rust ne traverse la frontière FFI.**
- **Aucune dépendance, import ou jar de RUSTFORGE-X.** Le projet est
  indépendant ; seule la chaîne `"rustforgex"` de C-76 et des tests de
  coexistence peut apparaître.
- **Aucune connexion réseau sortante**, aucune écriture hors du NBT AXION et de
  `<gameDir>/axion/`.

## 5. Ce qui est obligatoire

Liste complète en `sed -n '8199,8217p' ...`. En particulier :

- Tout commit cite ses identifiants : `feat(C-42): ... [R-1220, T-810]`.
- Tout composant expose ses métriques, son budget et son niveau de maturité.
- Tout chemin optimisé a un repli testé.
- Tout défaut corrigé donne lieu à un test de non-régression.
- Toute divergence volontaire avec le CDC exige un ADR daté.
- Toute structure persistée porte un magic, une version de schéma et un CRC.

---

## 6. Décider seul, ou pas

**L'agent décide seul** : structures internes non spécifiées, découpage en
modules, noms internes, style, organisation des tests, micro-optimisations sans
effet sémantique, ordre d'implémentation à l'intérieur d'un jalon, bibliothèques
Rust courantes sous réserve de licence et de SBOM.

**L'agent ne décide pas seul** : modifier un contrat `IF-xx` ou l'ABI, modifier
un `DM-xx` sans migration ni test de parité, affaiblir un `INV-xx`, ajouter une
dépendance structurante ou une bibliothèque native tierce, ajouter une cible
Mixin, **réduire le périmètre V1.0**, modifier la licence, publier une release.

Face à une ambiguïté : prendre l'option la plus conservatrice pour la
correction, et écrire un ADR. Ne jamais demander « que dois-je coder
maintenant ? » : la réponse se déduit du jalon courant et de la Definition of
Done.

---

## 7. Décisions propres au dépôt

Les ADR du CDC sont numérotés ADR-001 à ADR-026. Les décisions prises au cours
de l'implémentation sont numérotées **à partir de ADR-100** dans
`docs/decisions/`, pour qu'aucune numérotation n'entre en collision.

| ADR | Sujet |
|---|---|
| [ADR-100](decisions/ADR-100.md) | Point d'entrée Forge séparé de `AxionMod` |
| [ADR-101](decisions/ADR-101.md) | Documentation publiée comme site MkDocs Material |
| [ADR-102](decisions/ADR-102.md) | Une configuration refusée est signalée par `E-2002` |
