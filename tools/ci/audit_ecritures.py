#!/usr/bin/env python3
"""T-437 (R-1711, ADR-153 D8) : audit des fichiers écrits pendant une session de GameTests.

R-1711 : AXION n'écrit rien en dehors du NBT de ses entités, de l'unique SavedData `axion_registry` et de
`<gameDir>/axion/` ; `config/axion-*.toml`, qu'écrit le système de configuration de Forge, est admis
(lecture de R-1711 tranchée par Killian). L'audit a deux volets, que `runGameTestServer` enchaîne.

1. Les écritures Java, par Java Flight Recorder : la JVM du serveur enregistre chaque `jdk.FileWrite`, avec
   son chemin et sa pile (`audit_ecritures.jfc`), que `AuditEcrituresJfr.java` relit en flux, piles
   entières. Une écriture est d'AXION si la classe `dev.axion` la plus proche de l'écriture vient du code
   livré (`build/classes/java/main`). Écrite par AXION lui-même, elle doit viser `<gameDir>/axion/` ou
   `<gameDir>/config/axion-*.toml`, où que soit le fichier. Écrite par Minecraft ou Forge appelés par
   AXION — la déconnexion d'un joueur abusif (R-1690) sauvegarde ses données —, elle doit viser un
   emplacement de Minecraft. Une ligne de log, journalisation entre les deux, va au journal du jeu.
2. Le dossier de jeu, relevé avant et après la session : tout fichier créé, modifié ou supprimé hors des
   emplacements de Minecraft, de Forge et d'AXION est une violation. Ce volet voit aussi ce qu'écrit la
   bibliothèque native, que JFR ne voit pas ; il ne sait pas, en revanche, qui a écrit dans un fichier de
   Minecraft — INV-10 a son propre test (T-018).

Un audit qui ne voit aucune écriture d'AXION échoue : AXION écrit au moins sa bibliothèque native et son
journal latéral, et un audit aveugle ne prouverait rien.

Usage :
    python tools/ci/audit_ecritures.py releve <dossier de jeu> <relevé.json>
    python tools/ci/audit_ecritures.py verifier <dossier de jeu> <relevé.json> <enregistrement.jfr> \\
        <classes du code livré> <java d'un JDK 11 ou plus>
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


def volet_jfr(dossier: Path, enregistrement: Path, classes: Path, java: str) -> tuple[list[str], Counter[str]]:
    if not enregistrement.is_file():
        return [f"JFR : enregistrement absent : {enregistrement}"], Counter()
    lecteur = Path(__file__).with_name("AuditEcrituresJfr.java")
    sortie = subprocess.run([java, str(lecteur), str(enregistrement)], capture_output=True, check=False)
    if sortie.returncode != 0:
        return [f"JFR : lecture impossible : {sortie.stderr.decode(errors='replace').strip()}"], Counter()
    livrees = classes_livrees(classes)
    racine_axion = normal(str(dossier / "axion")) + os.sep
    config = normal(str(dossier / "config"))
    violations: list[str] = []
    bilan: Counter[str] = Counter()
    noms = {"journalisation": "journal du jeu, par la journalisation", "sans-axion": "sans classe d'AXION",
            "sans-axion-tronquee": "sans classe d'AXION (pile tronquée)"}
    for ligne in sortie.stdout.decode("utf-8", errors="replace").splitlines():
        nombre, categorie, classe, chemin = ligne.split("\t", 3)
        if categorie in noms:
            bilan[noms[categorie]] += int(nombre)
            continue
        if classe not in livrees:
            bilan["tests (contenu de développement)"] += int(nombre)
            continue
        # Un chemin relatif l'est au dossier du serveur, pas à celui de l'audit.
        cible = normal(chemin if os.path.isabs(chemin) else str(dossier / chemin))
        if categorie == "minecraft-pour-axion":
            relatif = Path(os.path.relpath(cible, normal(str(dossier)))).as_posix()
            if permis(relatif, MINECRAFT):
                bilan["Minecraft, appelé par AXION"] += int(nombre)
            else:
                violations.append(f"JFR : {classe} fait écrire Minecraft hors de ses emplacements : {chemin} ({nombre} fois)")
        elif cible.startswith(racine_axion) or (
                os.path.dirname(cible) == config and re.fullmatch(r"axion-[a-z]+\.toml", os.path.basename(cible))):
            bilan["AXION, emplacements permis"] += int(nombre)
        else:
            violations.append(f"JFR : {classe} écrit hors des emplacements permis : {chemin} ({nombre} fois)")
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
    dossier, releve, enregistrement, classes, java = Path(argv[2]), Path(argv[3]), Path(argv[4]), Path(argv[5]), argv[6]
    avant = json.loads(releve.read_text(encoding="utf-8"))
    violations_dossier, bilan_dossier = volet_dossier(dossier, avant)
    violations_jfr, bilan_jfr = volet_jfr(dossier, enregistrement, classes, java)
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
