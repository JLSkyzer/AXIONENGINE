#!/usr/bin/env python3
"""lint-no-fiction — R-001 : aucune fiction dans un module STABLE ou EXPERIMENTAL.

La PARTIE 34.3 nomme ce job et dit ce qu'il refuse : les marqueurs d'un travail
annoncé et non fait, dans le code livré.

Aucun module d'AXION n'est déclaré autrement que STABLE ou EXPERIMENTAL
aujourd'hui : la règle porte donc sur tout le code. Ce n'est pas une commodité
de style. Un marqueur dans un module livré est une fonctionnalité annoncée qui
n'existe pas, et le cahier des charges interdit de réduire un périmètre — il
demande un niveau de qualité, un budget, un LOD ou un repli, jamais une note
remise à plus tard.

# Le cas particulier de ce fichier

Il contient forcément les motifs qu'il cherche : c'est lui qui les définit. Il
s'exclut donc du balayage — et ce trou est bouché par l'autre bout, par un
autotest qui vérifie que **chaque motif se déclenche sur sa propre définition**.
Un motif cassé par une modification maladroite fait échouer le lint au lieu de
le rendre silencieusement aveugle, ce qui est le seul défaut qu'un garde-fou ne
peut pas se permettre.

Usage :

    python tools/ci/lint_no_fiction.py

Rend 0 si le dépôt est propre, 1 sinon, en nommant chaque ligne fautive.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

MOTIFS: tuple[tuple[str, re.Pattern[str], str], ...] = (
    ("TODO", re.compile(r"\bTODO\b"), "un travail annoncé et non fait"),
    ("FIXME", re.compile(r"\bFIXME\b"), "un défaut connu et laissé en place"),
    ("todo!()", re.compile(r"\btodo!\s*\("), "une implémentation qui panique à l'appel"),
    (
        "unimplemented!()",
        re.compile(r"\bunimplemented!\s*\("),
        "une implémentation qui panique à l'appel",
    ),
    (
        # Sans limite de mot, volontairement : un vrai bouche-trou s'appelle
        # `PLACEHOLDER_ROUGE` ou `placeholderText` bien plus souvent qu'il ne
        # s'écrit tout seul, et `\b` ne reconnaîtrait ni l'un ni l'autre.
        "placeholder",
        re.compile(r"placeholder", re.IGNORECASE),
        "une valeur ou un type qui tient la place d'un vrai",
    ),
)

# Ce qui est balayé : le code livré et les outils qui le produisent.
#
# La documentation et les notes de travail en sont exclues, et pour une raison
# de fond : `tasks/todo.md` existe précisément pour porter ce qui reste à faire,
# et `docs/` explique des règles qu'on ne peut énoncer sans les nommer. Y
# interdire les mots rendrait le dépôt incapable de parler de sa propre
# discipline.
RACINES: tuple[str, ...] = (
    "crates",
    "java/axion-api/src",
    "java/axion-mod/src",
    "tools",
    "gradle",
)

EXTENSIONS: frozenset[str] = frozenset(
    {".rs", ".java", ".py", ".gradle", ".toml", ".json", ".mcmeta"}
)

# Les répertoires de sortie ne sont pas du code : les balayer reviendrait à
# juger le travail d'outils tiers, et à échouer selon ce qui traîne sur le
# disque. `golden` porte les vecteurs d'or, dont le contenu est engendré.
IGNORES: frozenset[str] = frozenset({"target", "build", ".git", "golden", "__pycache__"})

#: Ce fichier, seul exclu du balayage — voir l'autotest.
MOI = Path(__file__).resolve()


def fichiers(racine: Path):
    """Énumère les fichiers à examiner sous une racine."""
    if not racine.exists():
        return
    for chemin in sorted(racine.rglob("*")):
        if not chemin.is_file():
            continue
        if any(part in IGNORES for part in chemin.parts):
            continue
        if chemin.suffix not in EXTENSIONS:
            continue
        if chemin.resolve() == MOI:
            continue
        yield chemin


def examine(depot: Path) -> list[str]:
    """Rend la liste des lignes fautives, chacune décrite en une ligne."""
    fautes: list[str] = []
    for nom in RACINES:
        for chemin in fichiers(depot / nom):
            try:
                lignes = chemin.read_text(encoding="utf-8").splitlines()
            except UnicodeDecodeError:
                # Un fichier binaire portant une extension de source est une
                # anomalie en soi, mais ce lint-ci n'est pas celui qui la juge.
                continue
            relatif = chemin.relative_to(depot).as_posix()
            for numero, ligne in enumerate(lignes, start=1):
                for etiquette, motif, pourquoi in MOTIFS:
                    if motif.search(ligne):
                        fautes.append(
                            f"{relatif}:{numero} : {etiquette} — {pourquoi}\n"
                            f"    {ligne.strip()}"
                        )
    return fautes


#: Lignes fautives telles qu'on en écrit vraiment, une par motif.
#:
#: Écrites **indépendamment** des expressions régulières, et c'est tout
#: l'intérêt : vérifier qu'un motif se reconnaît dans sa propre définition ne
#: prouve rien, puisqu'une faute de frappe s'y reconnaîtrait tout aussi bien.
#: Ici, un motif mal écrit ne retrouve plus son exemple.
COUPABLES: tuple[tuple[str, str], ...] = (
    ("TODO", "    // TODO: revenir sur le cas des bornes inversees"),
    ("FIXME", "    /* FIXME: contournement en attendant M4 */"),
    ("todo!()", '    todo! ("champ de deformation")'),
    ("unimplemented!()", "    unimplemented!()"),
    ("placeholder", "    let couleur = PLACEHOLDER_ROUGE;"),
)

#: Lignes légitimes qu'aucun motif ne doit attraper.
#:
#: Un lint trop large est pire qu'absent : on finit par le contourner. Ces
#: lignes disent où s'arrête chaque motif.
INNOCENTS: tuple[str, ...] = (
    # Un renvoi au fichier qui porte ce qui reste a faire : c'est l'endroit
    # prevu pour, et le nommer n'est pas une fiction.
    "    // la suite est notee dans tasks/todo.md",
    # `unreachable!` declare une branche impossible, il n'annonce rien.
    '    _ => unreachable!("operation declaree mais non evaluee"),',
    # Un identifiant qui contient les lettres d'un motif sans en etre un.
    "    let fixation = ancre.resoudre();",
)


def autotest() -> list[str]:
    """Vérifie que les motifs attrapent ce qu'ils doivent, et rien d'autre.

    C'est la contrepartie de l'exclusion de ce fichier. Un motif que quelqu'un
    aurait cassé — une barre oblique en trop, un mot mal orthographié — ne
    trouverait plus rien nulle part, et le lint passerait au vert sans rien
    vérifier. Ici, il échoue.
    """
    griefs: list[str] = []

    par_etiquette = {etiquette: motif for etiquette, motif, _ in MOTIFS}
    for etiquette, exemple in COUPABLES:
        motif = par_etiquette.get(etiquette)
        if motif is None:
            griefs.append(f"le motif « {etiquette} » a disparu de la table")
        elif not motif.search(exemple):
            griefs.append(
                f"le motif « {etiquette} » ne reconnait plus : {exemple.strip()}"
            )

    for innocent in INNOCENTS:
        for etiquette, motif, _ in MOTIFS:
            if motif.search(innocent):
                griefs.append(
                    f"le motif « {etiquette} » attrape une ligne legitime : "
                    f"{innocent.strip()}"
                )

    return griefs


def main() -> int:
    depot = MOI.parents[2]

    griefs = autotest()
    if griefs:
        print("lint-no-fiction : les motifs ne tiennent plus.\n", file=sys.stderr)
        for grief in griefs:
            print(f"  {grief}", file=sys.stderr)
        return 1

    fautes = examine(depot)
    if not fautes:
        examines = sum(1 for nom in RACINES for _ in fichiers(depot / nom))
        print(
            f"lint-no-fiction : {examines} fichiers examines, "
            f"{len(MOTIFS)} motifs actifs, R-001 respecte."
        )
        return 0

    print(f"lint-no-fiction : R-001 viole, {len(fautes)} ligne(s).\n", file=sys.stderr)
    for faute in fautes:
        print(f"  {faute}", file=sys.stderr)
    print(
        "\nR-001 n'admet pas d'exception dans un module STABLE ou EXPERIMENTAL.\n"
        "Ce qui reste a faire se note dans tasks/todo.md, pas dans le code livre :\n"
        "un marqueur dans une source est une fonctionnalite annoncee qui n'existe\n"
        "pas, et personne ne le lit avant de s'en apercevoir autrement.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
