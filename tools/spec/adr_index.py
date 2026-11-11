"""Génère docs/decisions/README.md : les ADR rangés par sujet (ADR-161 D4).

Le titre et la date de chaque ADR sont lus dans son en-tête ; le sujet vient de la table SUJETS
ci-dessous. Un ADR qu'elle ne classe pas tombe sous « À classer » : l'ajouter à la table.

Usage : python tools/spec/adr_index.py
"""

import re
from pathlib import Path

RACINE = Path(__file__).resolve().parents[2]
DOSSIER = RACINE / "docs" / "decisions"

SUJETS = [
    ("Capacités exigées et organisation", [160, 161]),
    ("Jalons et Definition of Done", [125, 128, 130, 137, 141, 144, 146]),
    ("Sièges et véhicules", [158, 159, 163]),
    ("Definitions et contenu", [109, 132, 134, 145, 156]),
    ("Physique", [112, 113, 114, 117, 120, 123, 147, 149, 151, 152]),
    ("Rendu", [118, 121, 127, 129, 133, 154, 157, 164]),
    ("Animation", [155]),
    ("Réseau et persistance", [150, 153]),
    ("Assets et compilation", [106, 107, 108, 110, 115, 119, 122, 135, 136, 139, 140]),
    ("API publique et enregistrement du contenu", [143, 162]),
    ("Noyau natif, mémoire et déterminisme", [104, 116, 138]),
    ("Socle, build, CI et outillage", [100, 101, 102, 103, 105, 111, 124, 126, 131, 142, 148]),
]


def lire(chemin):
    texte = chemin.read_text(encoding="utf-8")
    titre = texte.splitlines()[0].lstrip("# ").split(" — ", 1)[-1].strip()
    date = re.search(r"\*\*Date\*\* : (\d{4}-\d{2}-\d{2})", texte)
    statut = re.search(r"\*\*Statut\*\* : \**([^*,.;]+)", texte)
    return titre, date.group(1) if date else "", statut.group(1).strip() if statut else ""


def main():
    adr = {}
    for chemin in sorted(DOSSIER.glob("ADR-*.md")):
        adr[int(chemin.stem.split("-")[1])] = (chemin.name, *lire(chemin))
    lignes = [
        "# Décisions d'architecture (ADR)",
        "",
        "Un ADR fige une décision importante : son contexte, ce qui est choisi, ce qui est écarté et ce que ça change.",
        "Une décision de contrat (ABI, protocole, sauvegarde, API publique) passe par un ADR que Killian accepte avant",
        "le code. Les fichiers restent à plat, leurs chemins étant cités partout ; cet index les range par sujet,",
        "le plus récent en tête.",
        "",
        "Généré par `python tools/spec/adr_index.py` : ne pas éditer à la main.",
        "",
    ]
    classes = set()
    for sujet, numeros in SUJETS:
        lignes += [f"## {sujet}", "", "| ADR | Décision | Date | Statut |", "|---|---|---|---|"]
        for n in sorted(numeros, reverse=True):
            if n in adr:
                nom, titre, date, statut = adr[n]
                lignes.append(f"| [ADR-{n}]({nom}) | {titre} | {date} | {statut} |")
                classes.add(n)
        lignes.append("")
    reste = sorted(set(adr) - classes, reverse=True)
    if reste:
        lignes += ["## À classer", "", "| ADR | Décision | Date | Statut |", "|---|---|---|---|"]
        for n in reste:
            nom, titre, date, statut = adr[n]
            lignes.append(f"| [ADR-{n}]({nom}) | {titre} | {date} | {statut} |")
        lignes.append("")
    (DOSSIER / "README.md").write_text("\n".join(lignes), encoding="utf-8")
    print(f"docs/decisions/README.md : {len(adr)} ADR, {len(reste)} à classer")


if __name__ == "__main__":
    main()
