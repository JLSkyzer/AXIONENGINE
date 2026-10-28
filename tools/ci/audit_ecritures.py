#!/usr/bin/env python3
"""T-437 (R-1711, ADR-153 D8) : audit des fichiers écrits pendant une session de GameTests.

R-1711 : AXION n'écrit rien en dehors du NBT de ses entités, de l'unique SavedData `axion_registry` et de
`<gameDir>/axion/` ; `config/axion-*.toml`, qu'écrit le système de configuration de Forge, est admis
(lecture de R-1711 tranchée par Killian). L'audit a deux volets, que `runGameTestServer` enchaîne.

1. Les écritures Java, par Java Flight Recorder : la JVM du serveur enregistre chaque `jdk.FileWrite`, avec
   son chemin et sa pile (`audit_ecritures.jfc`). Une écriture est d'AXION si la classe `dev.axion` la plus
   proche de l'écriture vient du code livré (`build/classes/java/main`) et qu'aucun cadre de journalisation
   ne s'intercale — une ligne de log va au journal du jeu, pas aux données d'AXION. Elle doit viser
   `<gameDir>/axion/` ou `<gameDir>/config/axion-*.toml`, où que soit le fichier.
2. Le dossier de jeu, relevé avant et après la session : tout fichier créé, modifié ou supprimé hors des
   emplacements de Minecraft, de Forge et d'AXION est une violation. Ce volet voit aussi ce qu'écrit la
   bibliothèque native, que JFR ne voit pas ; il ne sait pas, en revanche, qui a écrit dans un fichier de
   Minecraft — INV-10 a son propre test (T-018).

Un audit qui ne voit aucune écriture d'AXION échoue : AXION écrit au moins sa bibliothèque native et son
journal latéral, et un audit aveugle ne prouverait rien.

Usage :
    python tools/ci/audit_ecritures.py releve <dossier de jeu> <relevé.json>
    python tools/ci/audit_ecritures.py verifier <dossier de jeu> <relevé.json> <enregistrement.jfr> \\
        <classes du code livré> <outil jfr>
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

# Ce qu'AXION peut écrire, relativement au dossier de jeu (R-1711).
AXION_PERMIS = [re.compile(motif) for motif in (
    r"axion/.+",
    r"config/axion-[a-z]+\.toml",
    r"world/data/axion_registry\.dat",
)]

# Ce qu'écrivent Minecraft et Forge pendant une session de GameTests.
MINECRAFT = [re.compile(motif) for motif in (
    r"world/(DIM-?1/)?(region|entities|poi)/r\.-?\d+\.-?\d+\.mca",
    r"world/(DIM-?1/)?data/(?!axion)[^/]+\.dat",
    r"world/(level\.dat|level\.dat_old|session\.lock)",
    r"world/(playerdata|stats|advancements)/[^/]+",
    r"world/serverconfig/(?!axion)[^/]+",
    r"logs/.+",
    r"crash-reports/.+",
    r"(usernamecache|usercache|ops|whitelist|banned-players|banned-ips)\.json",
    r"server\.properties",
    r"eula\.txt",
    r"config/(?!axion-)[^/]+",
    r"defaultconfigs/.+",
)]

# Cadres de journalisation : une écriture qu'ils font est une ligne du journal du jeu.
JOURNALISATION = ("org.apache.logging.", "org.slf4j.", "com.mojang.logging.")


def relever(dossier: Path) -> dict[str, list[int]]:
    """{chemin relatif : [taille, mtime en ns]} de chaque fichier du dossier."""
    releve: dict[str, list[int]] = {}
    for racine, _, fichiers in os.walk(dossier):
        for nom in fichiers:
            chemin = Path(racine) / nom
            try:
                etat = chemin.stat()
            except OSError:
                continue  # supprimé pendant le relevé
            releve[chemin.relative_to(dossier).as_posix()] = [etat.st_size, etat.st_mtime_ns]
    return releve


def permis(relatif: str, motifs: list[re.Pattern[str]]) -> bool:
    return any(motif.fullmatch(relatif) for motif in motifs)


def classes_livrees(dossier: Path) -> set[str]:
    """Les noms binaires des classes du code livré, `dev.axion.forge.JournalBridge$1` par exemple."""
    return {fichier.relative_to(dossier).with_suffix("").as_posix().replace("/", ".")
            for fichier in dossier.rglob("*.class")}


def normal(chemin: str) -> str:
    return os.path.normcase(os.path.abspath(chemin))


def volet_dossier(dossier: Path, avant: dict[str, list[int]]) -> tuple[list[str], Counter[str]]:
    apres = relever(dossier)
    touches = [rel for rel, etat in apres.items() if avant.get(rel) != etat]
    supprimes = [rel for rel in avant if rel not in apres]
    violations: list[str] = []
    bilan: Counter[str] = Counter()
    for rel, verbe in [(rel, "écrit") for rel in touches] + [(rel, "supprimé") for rel in supprimes]:
        if permis(rel, AXION_PERMIS):
            bilan["AXION, emplacements permis"] += 1
        elif permis(rel, MINECRAFT):
            bilan["Minecraft et Forge"] += 1
        else:
            violations.append(f"dossier : {verbe} hors des emplacements connus : {rel}")
    return violations, bilan


def volet_jfr(dossier: Path, enregistrement: Path, classes: Path, jfr: str) -> tuple[list[str], Counter[str]]:
    if not enregistrement.is_file():
        return [f"JFR : enregistrement absent : {enregistrement}"], Counter()
    sortie = subprocess.run([jfr, "print", "--json", "--events", "jdk.FileWrite", str(enregistrement)],
                            capture_output=True, check=False)
    if sortie.returncode != 0:
        return [f"JFR : {jfr} print a échoué : {sortie.stderr.decode(errors='replace').strip()}"], Counter()
    evenements = json.loads(sortie.stdout.decode("utf-8", errors="replace"))["recording"]["events"]
    livrees = classes_livrees(classes)
    racine_axion = normal(str(dossier / "axion")) + os.sep
    config = normal(str(dossier / "config"))
    violations: list[str] = []
    bilan: Counter[str] = Counter()
    for evenement in evenements:
        valeurs = evenement["values"]
        chemin = valeurs.get("path")
        if not chemin:
            continue  # sortie standard ou descripteur sans chemin
        pile = valeurs.get("stackTrace") or {}
        cadres = [cadre["method"]["type"]["name"].replace("/", ".") for cadre in pile.get("frames", [])]
        indice = next((i for i, nom in enumerate(cadres) if nom.startswith("dev.axion.")), None)
        if indice is None:
            bilan["sans classe d'AXION" + (" (pile tronquée)" if pile.get("truncated") else "")] += 1
            continue
        if any(nom.startswith(JOURNALISATION) for nom in cadres[:indice]):
            bilan["journal du jeu, par la journalisation"] += 1
            continue
        if cadres[indice] not in livrees:
            bilan["tests (contenu de développement)"] += 1
            continue
        cible = normal(chemin)
        nom = os.path.basename(cible)
        if cible.startswith(racine_axion) or (
                os.path.dirname(cible) == config and re.fullmatch(r"axion-[a-z]+\.toml", nom)):
            bilan["AXION, emplacements permis"] += 1
        else:
            violations.append(f"JFR : {cadres[indice]} écrit hors des emplacements permis : {chemin}")
    return violations, bilan


def main(argv: list[str]) -> int:
    # Lancé par Gradle sous Windows, la sortie est en cp1252 : un caractère hors de la page ne doit pas
    # faire tomber l'audit au moment de dire son verdict.
    sys.stdout.reconfigure(errors="replace")
    if len(argv) == 4 and argv[1] == "releve":
        Path(argv[3]).parent.mkdir(parents=True, exist_ok=True)
        Path(argv[3]).write_text(json.dumps(relever(Path(argv[2]))), encoding="utf-8")
        return 0
    if len(argv) != 7 or argv[1] != "verifier":
        print(__doc__)
        return 2
    dossier, releve, enregistrement, classes, jfr = Path(argv[2]), Path(argv[3]), Path(argv[4]), Path(argv[5]), argv[6]
    avant = json.loads(releve.read_text(encoding="utf-8"))
    violations_dossier, bilan_dossier = volet_dossier(dossier, avant)
    violations_jfr, bilan_jfr = volet_jfr(dossier, enregistrement, classes, jfr)
    violations = violations_jfr + violations_dossier
    if not violations_jfr and bilan_jfr["AXION, emplacements permis"] == 0:
        violations.append("JFR : aucune écriture d'AXION vue — l'audit est aveugle")
    print("T-437 : écritures Java (JFR) — " + ", ".join(f"{cle} : {n}" for cle, n in sorted(bilan_jfr.items())))
    print("T-437 : dossier de jeu — " + ", ".join(f"{cle} : {n}" for cle, n in sorted(bilan_dossier.items())))
    for violation in violations:
        print(f"T-437 : VIOLATION — {violation}")
    print(f"T-437 : {'aucune violation' if not violations else f'{len(violations)} violation(s)'} (R-1711)")
    return 0 if not violations else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
