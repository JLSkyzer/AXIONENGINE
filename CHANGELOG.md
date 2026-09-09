# Journal des modifications

Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le
projet suit le [versionnement semantique](https://semver.org/lang/fr/).

Chaque jalon termine doit mettre ce fichier a jour : c'est une case de la
Definition of Done (PARTIE 37.3).

## [Non publie]

### Ajoute

- Arborescence du depot conforme a la PARTIE 33 : build Gradle multi-projet
  (`:axion-api`, `:axion-mod`) et workspace Cargo.
- Index de navigation du cahier des charges (`docs/spec/`), genere par
  `tools/spec/spec_index.py`.
- Contrat de l'agent de developpement (`docs/AGENT.md`).

### Modifie

- Identite du mod alignee sur le cahier des charges : `mod_id` `axion`,
  paquet Java `dev.axion`, licence Apache-2.0.

### Supprime

- Contenu d'exemple du MDK Forge (bloc, objet et onglet creatif de
  demonstration), etranger au moteur et contraire a l'interdiction 3.3.
