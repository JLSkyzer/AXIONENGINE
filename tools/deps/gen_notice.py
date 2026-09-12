#!/usr/bin/env python3
"""Genere le fichier NOTICE depuis l'arbre de dependances reel.

R-2290 : le NOTICE est genere et verifie en integration continue ; il doit
lister l'integralite des dependances, de leurs licences et des attributions
requises. R-2300 ajoute que toute dependance nouvelle y figure.

Ecrire cette liste a la main serait la garantie qu'elle diverge : une
dependance transitive apparait sans qu'on la choisisse, et elle doit tout de
meme figurer dans les attributions.

Le script verifie aussi que chaque licence est couverte par l'allowlist de
deny.toml (R-2301) et sort en erreur sinon.

Usage :
    python tools/deps/gen_notice.py            genere NOTICE
    python tools/deps/gen_notice.py --check    verifie sans ecrire
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
NOTICE = ROOT / "NOTICE"
DENY = ROOT / "deny.toml"

# Crates du dépôt : ils ne sont pas des dépendances tierces.
WORKSPACE_PREFIXES = ("ax-", "axion-")

HEADER = """AXION ENGINE
Copyright 2026 JLSkyzer

Ce produit inclut des logiciels developpes dans le cadre du projet AXION ENGINE,
distribues sous licence Apache-2.0 (voir le fichier LICENSE).

--------------------------------------------------------------------------------
Fichier genere
--------------------------------------------------------------------------------

Ne pas modifier a la main. Regenerer avec :

    python tools/deps/gen_notice.py

La liste ci-dessous est extraite de l'arbre de dependances reel (cargo
metadata), transitives comprises : une dependance qu'on n'a pas choisie doit
figurer dans les attributions au meme titre que les autres (R-2290).
"""

FOOTER = """
--------------------------------------------------------------------------------
Dependances Java
--------------------------------------------------------------------------------

Aucune dependance tierce n'est embarquee. Le module ne depend que de la
plateforme, fournie par l'utilisateur :

  Minecraft, Minecraft Forge   non redistribues
  Mixin (via Forge)            MIT
  Gson, NightConfig (via la plateforme)

--------------------------------------------------------------------------------
Plateforme, non redistribuee
--------------------------------------------------------------------------------

AXION ENGINE ne redistribue ni Minecraft, ni Minecraft Forge, ni leurs assets,
ni aucun mod tiers, ni aucun asset dont la licence ne le permet pas (R-2291).
Ces composants sont fournis par l'utilisateur final.

Minecraft est une marque de Mojang AB. Ce projet n'est ni affilie a Mojang AB,
ni approuve par Mojang AB ou Microsoft.
"""


# Code tiers embarque par un crate, dont l'attribution ne se lit pas dans les
# metadonnees cargo : le crate y declare sa propre licence, pas celle du code
# qu'il compile ou dont il derive (ADR-106).
EMBEDDED = {
    "meshopt": "meshoptimizer (C++), Copyright (c) 2016-2025 Arseny Kapoulkine, "
    "licence MIT",
    "mikktspace": "derive de MikkTSpace, Copyright (C) 2011 Morten S. Mikkelsen, "
    "licence zlib",
}


def allowed_licenses():
    """Licences autorisees, lues dans deny.toml (source unique, R-2301)."""
    text = DENY.read_text(encoding="utf-8")
    block = re.search(r"^allow = \[(.*?)\]", text, re.S | re.M)
    if not block:
        raise SystemExit("allowlist introuvable dans deny.toml")
    return {value for value in re.findall(r'"([^"]+)"', block.group(1))}


def is_covered(expression, allowed):
    """Indique si une expression SPDX est couverte par l'allowlist.

    Une expression « A OR B » suffit si l'une des deux est permise ; une
    expression « A AND B » exige les deux. C'est la lecture conservatrice.
    """
    if not expression:
        return False
    # Les metadonnees anciennes ecrivent « A/B » la ou SPDX ecrit « A OR B ».
    # Cargo accepte les deux, et des crates repandus utilisent encore la
    # premiere forme.
    expression = expression.replace("/", " OR ")
    tokens = [t.strip(" ()") for t in re.split(r"\bAND\b", expression)]
    for token in tokens:
        options = [o.strip(" ()") for o in re.split(r"\bOR\b", token)]
        if not any(option in allowed for option in options):
            return False
    return True


def third_party_packages():
    result = subprocess.run(
        ["cargo", "metadata", "--format-version", "1", "--all-features"],
        capture_output=True,
        text=True,
        cwd=ROOT,
        check=False,
    )
    if result.returncode != 0:
        raise SystemExit("cargo metadata a echoue :\n" + result.stderr)

    packages = json.loads(result.stdout)["packages"]
    out = []
    for package in packages:
        name = package["name"]
        if name.startswith(WORKSPACE_PREFIXES):
            continue
        out.append(
            {
                "name": name,
                "version": package["version"],
                "license": package.get("license") or "",
                "repository": package.get("repository") or "",
            }
        )
    # Trie par nom puis version : deux versions d'un meme crate peuvent
    # coexister, et les deux doivent etre attribuees.
    return sorted(out, key=lambda p: (p["name"], p["version"]))


def render(packages):
    lines = [HEADER, ""]
    lines.append("-" * 80)
    lines.append("Dependances Rust")
    lines.append("-" * 80)
    lines.append("")
    lines.append(f"{len(packages)} paquets, transitives comprises.")
    lines.append("")
    for package in packages:
        lines.append(f"  {package['name']} {package['version']}")
        lines.append(f"    Licence : {package['license'] or 'non declaree'}")
        if package["repository"]:
            lines.append(f"    Source  : {package['repository']}")
        if package["name"] in EMBEDDED:
            lines.append(f"    Inclut  : {EMBEDDED[package['name']]}")
        lines.append("")
    lines.append(FOOTER.strip())
    lines.append("")
    return "\n".join(lines)


def main():
    check_only = "--check" in sys.argv
    packages = third_party_packages()
    allowed = allowed_licenses()

    refuses = [p for p in packages if not is_covered(p["license"], allowed)]
    if refuses:
        print("R-2301 viole : licences hors allowlist de deny.toml", file=sys.stderr)
        for package in refuses:
            print(
                f"  {package['name']} {package['version']} : "
                f"{package['license'] or 'non declaree'}",
                file=sys.stderr,
            )
        return 1

    rendered = render(packages)
    if check_only:
        current = NOTICE.read_text(encoding="utf-8") if NOTICE.exists() else ""
        if current.replace("\r\n", "\n") != rendered:
            print(
                "NOTICE a diverge de l'arbre de dependances — le regenerer avec :\n"
                "  python tools/deps/gen_notice.py",
                file=sys.stderr,
            )
            return 1
        print(f"NOTICE a jour : {len(packages)} paquets, licences conformes.")
        return 0

    NOTICE.write_text(rendered, encoding="utf-8")
    print(f"NOTICE genere : {len(packages)} paquets, licences conformes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
