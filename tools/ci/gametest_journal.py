#!/usr/bin/env python3
"""ADR-148 : l'avertissement de compilation de l'asset de test atteint le journal du jeu, une fois.

Le serveur de GameTests part d'un cache d'assets vide (`runGameTestServer` l'efface avant chaque
passage) : il compile `test/avertissement/triangle.gltf`, dont R-530 ignore une extension avec
avertissement, et la couche Forge en écrit la ligne dans `run-gametest/logs/latest.log`. Ce script
l'y compte : exactement une, puisque l'asset est compilé une fois.

La ligne est reconnue à ses fragments ASCII : l'encodage du journal varie selon la plateforme, et
un tiret cadratin ou un « é » mal décodé ne doit pas faire croire à une absence.

Usage : python tools/ci/gametest_journal.py [chemin du journal]
"""

from __future__ import annotations

import sys
from pathlib import Path

FRAGMENTS = (
    "AXION : axion:axion/models/test/avertissement/triangle.gltf",
    "avertissement : extension ignor",
    ": KHR_materials_variants",
)


def main(argv: list[str]) -> int:
    journal = Path(argv[1] if len(argv) > 1 else "run-gametest/logs/latest.log")
    if not journal.is_file():
        print(f"ADR-148 : journal absent : {journal}")
        return 1
    lignes = journal.read_text(encoding="utf-8", errors="replace").splitlines()
    fois = sum(1 for ligne in lignes if all(fragment in ligne for fragment in FRAGMENTS))
    print(f"ADR-148 : avertissement de l'asset de test au journal {fois} fois (attendu : 1)")
    return 0 if fois == 1 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
