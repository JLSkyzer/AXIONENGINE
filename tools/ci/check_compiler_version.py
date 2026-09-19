#!/usr/bin/env python3
"""check-compiler-version — R-562 : les deux COMPILER_VERSION avancent ensemble.

La fiche C-25 (PARTIE 5.18) veut que `COMPILER_VERSION` soit incrémentée à toute
modification de C-21, C-22, C-23 ou C-28 qui change la sortie, **et vérifiée en
CI**. La version vit à deux endroits, parce que deux acteurs en ont besoin
séparément :

- le compilateur natif (`ax_asset::compile::COMPILER_VERSION`) l'estampille dans
  l'en-tête A3D qu'il produit ;
- le mod (`dev.axion.asset.CompilerVersion.CURRENT`) la mêle à la clé de cache
  **avant** de compiler, pour décider si une entrée est à jour.

Si les deux dérivent, le cache reprend un asset produit par l'ancien
compilateur : la clé calculée côté Java ne change pas alors que la sortie du
compilateur, elle, a changé. Le défaut est silencieux — le cache rend le mauvais
asset sans erreur —, donc c'est la CI qui doit l'attraper, jamais un test en
jeu.

Ce contrôle lit la constante de chaque source et refuse tout écart. Il refuse
aussi une constante **introuvable** : un renommage qui la cacherait ferait
autrement passer le contrôle à vide, ce qui est le seul état qu'un garde-fou ne
peut pas se permettre.

Usage :

    python tools/ci/check_compiler_version.py

Rend 0 si les deux versions coïncident, 1 sinon (divergence ou constante
introuvable), en nommant ce qu'il a lu.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

RACINE = Path(__file__).resolve().parents[2]

RUST_SOURCE = RACINE / "crates" / "ax-asset" / "src" / "compile.rs"
JAVA_SOURCE = (
    RACINE
    / "java"
    / "axion-mod"
    / "src"
    / "main"
    / "java"
    / "dev"
    / "axion"
    / "asset"
    / "CompilerVersion.java"
)

# `pub const COMPILER_VERSION: u32 = 5;` — le littéral Rust admet des `_`.
RUST_MOTIF = re.compile(
    r"const\s+COMPILER_VERSION\s*:\s*u32\s*=\s*([0-9_]+)\s*;"
)
# `public static final int CURRENT = 5;`
JAVA_MOTIF = re.compile(
    r"static\s+final\s+int\s+CURRENT\s*=\s*([0-9_]+)\s*;"
)


def lire_version(source: Path, motif: re.Pattern[str], nom: str) -> int | None:
    """Rend la version déclarée dans `source`, ou None en la signalant."""
    try:
        texte = source.read_text(encoding="utf-8")
    except OSError as echec:
        print(f"check-compiler-version : {nom} illisible — {echec}", file=sys.stderr)
        return None
    trouve = motif.search(texte)
    if trouve is None:
        chemin = source.relative_to(RACINE)
        print(
            f"check-compiler-version : constante introuvable dans {chemin} "
            f"(motif {motif.pattern!r}) — a-t-elle été renommée ?",
            file=sys.stderr,
        )
        return None
    return int(trouve.group(1).replace("_", ""))


def main() -> int:
    rust = lire_version(RUST_SOURCE, RUST_MOTIF, "ax_asset::compile::COMPILER_VERSION")
    java = lire_version(JAVA_SOURCE, JAVA_MOTIF, "dev.axion.asset.CompilerVersion.CURRENT")

    if rust is None or java is None:
        return 1

    if rust != java:
        print(
            "check-compiler-version : R-562 violé — les versions divergent.\n"
            f"  ax_asset::compile::COMPILER_VERSION = {rust}\n"
            f"  dev.axion.asset.CompilerVersion.CURRENT = {java}\n"
            "Incrémenter les deux ensemble : la clé de cache doit porter la même "
            "version que le compilateur qui produit l'asset.",
            file=sys.stderr,
        )
        return 1

    print(f"check-compiler-version : COMPILER_VERSION = {rust} des deux côtés, R-562 respecté.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
