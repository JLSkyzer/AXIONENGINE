#!/usr/bin/env python3
"""Genere l'index de navigation du cahier des charges AXION ENGINE.

Le CDC fait ~8300 lignes : il ne peut jamais etre charge en entier dans le
contexte d'un agent. Ce script produit deux artefacts qui permettent de lire
uniquement la plage de lignes utile :

  docs/spec/INDEX.md    plan complet, chaque section avec sa plage de lignes
  docs/spec/ID-MAP.tsv  chaque identifiant normatif -> ligne de definition

Usage : python tools/spec/spec_index.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "cdc" / "AXIONENGINE_Cahier_des_Charges_v1.0.md"
OUT_INDEX = ROOT / "docs" / "spec" / "INDEX.md"
OUT_IDMAP = ROOT / "docs" / "spec" / "ID-MAP.tsv"

# Familles d'identifiants normatifs (PARTIE 0.4 du CDC).
ID_PATTERNS = {
    "R": r"\bR-\d{3,4}\b",
    "C": r"\bC-\d{2}\b",
    "IF": r"\bIF-\d{2}\b",
    "DM": r"\bDM-\d{2}\b",
    "SM": r"\bSM-\d{2}\b",
    "INV": r"\bINV-\d{2}\b",
    "FM": r"\bFM-\d{2}\b",
    "RISK": r"\bRISK-\d{2}\b",
    "T": r"\bT-\d{3}\b",
    "S": r"\bS-\d{2}\b",
    "B": r"\bB-\d{2}\b",
    "ADR": r"\bADR-\d{3}\b",
    "E": r"\bE-\d{4}\b",
    "P": r"\bP-\d{2}\b",
    "H": r"\bH-\d{2}\b",
    "Q": r"\bQ-[0-4]\b",
    "AC": r"\bAC-\d{2}\b",
    "PF": r"\bPF-\d{2}\b",
    "EX": r"\bEX-\d{2}\b",
}


def build_def_patterns(pattern):
    """Formes de *definition* d'un identifiant, de la plus forte a la plus faible.

    Le meilleur score l'emporte, ce qui fait pointer C-42 vers sa fiche
    "## 5.34 C-42 : Deformation Engine" plutot que vers l'inventaire 2.2.

      3  titre de section       "## 5.34 C-42 : Deformation Engine"
      2  entree de bloc texte   "T-808  assembly intacte : ..."
      1  puce ou cellule        "- R-1220 : ..." / "| INV-01 | ... |"
    """
    body = pattern.replace(r"\b", "")
    return (
        (3, re.compile(r"^#{1,4}\s+[\d.]*\s*" + body)),
        (2, re.compile(r"^" + body + r"[a-z]?\s{2,}\S")),
        (1, re.compile(r"^\s*(?:[-*]\s*\*{0,2}|\|\s*\*{0,2})" + body)),
    )


def main():
    if not SPEC.exists():
        print("CDC introuvable : " + str(SPEC), file=sys.stderr)
        return 1
    lines = SPEC.read_text(encoding="utf-8").splitlines()
    n = len(lines)

    # --- 1. Plan : titres et plages de lignes -----------------------------
    heads = []  # (niveau, titre, ligne_debut)
    in_fence = False
    for i, raw in enumerate(lines, start=1):
        if raw.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = re.match(r"^(#{1,3})\s+(.*\S)\s*$", raw)
        if m:
            heads.append((len(m.group(1)), m.group(2), i))

    # Une section finit juste avant le prochain titre de niveau inferieur ou egal.
    spans = []
    for idx, (lvl, title, start) in enumerate(heads):
        end = n
        for lvl2, _title2, start2 in heads[idx + 1:]:
            if lvl2 <= lvl:
                end = start2 - 1
                break
        spans.append((lvl, title, start, end))

    def section_of(line_no):
        """Titre de la section la plus profonde contenant cette ligne."""
        best = ""
        for _lvl, title, start, end in spans:
            if start <= line_no <= end:
                best = title
        return best

    rel = SPEC.relative_to(ROOT).as_posix()
    out = [
        "# Index du cahier des charges AXION ENGINE",
        "",
        "Genere par `tools/spec/spec_index.py`. Source : `" + rel + "` (" + str(n) + " lignes).",
        "",
        "Le CDC est **gele (FROZEN)** et constitue la source de verite unique.",
        "Il ne doit jamais etre lu en entier : lire la plage de lignes de la",
        "section utile, par exemple",
        "",
        "```bash",
        "sed -n '3586,3965p' " + rel,
        "```",
        "",
        "Pour retrouver la definition d'un identifiant (`R-1220`, `C-42`, `T-808`) :",
        "",
        "```bash",
        "grep -P '^C-42\t' docs/spec/ID-MAP.tsv",
        "```",
        "",
        "| Lignes | Etendue | Section |",
        "|---|---|---|",
    ]
    for lvl, title, start, end in spans:
        indent = "&nbsp;&nbsp;&nbsp;&nbsp;" * (lvl - 1)
        out.append("| `" + str(start) + "," + str(end) + "p` | "
                   + str(end - start + 1) + " | " + indent + title + " |")
    out.append("")
    OUT_INDEX.write_text("\n".join(out), encoding="utf-8")

    # --- 2. Carte des identifiants ----------------------------------------
    found = {}  # id -> {family, def, score, first, count}
    for family, pattern in ID_PATTERNS.items():
        rx = re.compile(pattern)
        defs = build_def_patterns(pattern)
        for i, raw in enumerate(lines, start=1):
            idents = rx.findall(raw)
            if not idents:
                continue
            score = 0
            for weight, drx in defs:
                if drx.match(raw):
                    score = weight
                    break
            for ident in idents:
                e = found.setdefault(ident, {
                    "family": family, "def": None, "score": 0,
                    "first": i, "count": 0,
                })
                e["count"] += 1
                # Un identifiant cite au milieu d'une ligne n'est pas defini par
                # elle : seul le premier identifiant de la ligne peut l'etre.
                if score > e["score"] and ident == idents[0]:
                    e["score"] = score
                    e["def"] = i

    def sort_key(ident):
        fam, _, num = ident.partition("-")
        return (fam, int(num) if num.isdigit() else 0)

    rows = ["id\tfamille\tligne_def\tligne_1re\toccurrences\tsection"]
    for ident in sorted(found, key=sort_key):
        e = found[ident]
        anchor = e["def"] or e["first"]
        rows.append("\t".join([
            ident, e["family"], str(e["def"] or ""), str(e["first"]),
            str(e["count"]), section_of(anchor),
        ]))
    OUT_IDMAP.write_text("\n".join(rows) + "\n", encoding="utf-8")

    print(str(OUT_INDEX.relative_to(ROOT)) + " : " + str(len(spans)) + " sections")
    print(str(OUT_IDMAP.relative_to(ROOT)) + " : " + str(len(found)) + " identifiants")
    return 0


if __name__ == "__main__":
    sys.exit(main())
