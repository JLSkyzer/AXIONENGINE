#!/usr/bin/env python3
"""Campagne T-435 / T-713b (ADR-153 D8, 22.5.3 cas 1 et 3) : trois lancements réels d'un serveur Forge.

1. Avec AXION : un monde neuf, des assemblies posées dans un chunk chargé de force, leur NBT relevé,
   le monde sauvegardé. Le journal latéral reçoit leurs sauvegardes.
2. Avec Forge seul : AXION retiré, le monde se charge, se joue et se sauvegarde — Minecraft ignore les
   entités qu'il ne connaît pas, et les perd en réécrivant leur chunk (cas 1 de 22.5.3).
3. Avec AXION de nouveau : les assemblies dont l'entité a disparu sont restaurées depuis le journal, à leur
   position, avec leur NBT (cas 3). Chaque clé `axion:*` et la position sont comparées à la phase 1.

Sans joueur, rien n'est simulé (R-612) : l'état comparé est celui que l'entité persiste — definition,
schéma, orientation donnée à la création. La persistance de l'état simulé est prouvée par T-430 en
GameTest ; la campagne prouve ce qu'aucune GameTest ne peut : la perte réelle par Minecraft sans AXION.

Le serveur vit dans `run-campagne/serveur` (installé par l'installeur officiel de Forge 1.20.1-47.4.23,
CLUF accepté par Killian), le jar est celui de `./gradlew :axion-mod:validateJar`, le contenu celui que le
jar livre (`axion:example/*`).

Usage : python tools/campagne/journal_lateral.py   (JAVA_HOME doit désigner un JDK 17)
"""

from __future__ import annotations

import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

RACINE = Path(__file__).resolve().parents[2]
CAMPAGNE = RACINE / "run-campagne"
SERVEUR = CAMPAGNE / "serveur"
JAR = RACINE / "java/axion-mod/build/libs/axion-1.0.0-SNAPSHOT.jar"
FORGE_ARGS = "libraries/net/minecraftforge/forge/1.20.1-47.4.23/" + ("win_args.txt" if os.name == "nt" else "unix_args.txt")

# Les assemblies de la campagne : le contenu que livre le jar, chacune son UUID et une orientation de 45°
# autour de y, qu'une assembly perdue puis restaurée doit retrouver.
ROTATION = '"axion:rot":[0.0f,0.38268343f,0.0f,0.9238795f]'
ASSEMBLIES = [
    (1, "axion:example/crate", (4.5, -59.0, 4.5)),
    (2, "axion:example/barrier", (9.5, -59.0, 4.5)),
    (3, "axion:example/flag", (4.5, -59.0, 9.5)),
    (4, "axion:example/buggy", (11.5, -59.0, 11.5)),
    (5, "axion:example/winch_rope", (2.5, -59.0, 13.5)),
]

PROPRIETES = {
    "level-type": "minecraft\\:flat",
    "level-seed": "20261028",
    "generate-structures": "false",
    "difficulty": "peaceful",
    "spawn-monsters": "false",
    "spawn-animals": "false",
    "spawn-npcs": "false",
    "online-mode": "false",
    "server-port": "25599",
    "view-distance": "4",
    "simulation-distance": "4",
    "max-players": "1",
}

# Le contenu de démonstration que livre le jar ne se charge que sur demande (R-1790, ADR-134).
EXEMPLES = "-Daxion.content.examples=true"

PRESENTE = "has the following entity data:"
INTROUVABLE = "No entity was found"


def uuid_texte(n: int) -> str:
    return f"00000000-0000-0000-0000-{n:012x}"


class Serveur:
    """Un lancement du serveur : sa sortie, lue ligne à ligne, et sa console, pour les commandes."""

    def __init__(self, journal: Path, jvm: list[str]):
        java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        self.lignes: list[str] = []
        self.file: queue.Queue[str] = queue.Queue()
        self.journal = journal.open("w", encoding="utf-8")
        self.processus = subprocess.Popen(
            [str(java), "-Dfile.encoding=UTF-8", *jvm, "@user_jvm_args.txt", "@" + FORGE_ARGS, "nogui"],
            cwd=SERVEUR, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=self._lire, daemon=True).start()

    def _lire(self) -> None:
        for brut in self.processus.stdout:
            ligne = brut.decode("utf-8", errors="replace").rstrip()
            self.journal.write(ligne + "\n")
            self.journal.flush()
            self.lignes.append(ligne)
            self.file.put(ligne)

    def attendre(self, motifs: tuple[str, ...], delai: float) -> str:
        """{@return la première ligne qui suit et contient l'un des motifs} Échoue au-delà du délai."""
        fin = time.monotonic() + delai
        while time.monotonic() < fin:
            try:
                ligne = self.file.get(timeout=max(0.1, fin - time.monotonic()))
            except queue.Empty:
                break
            if any(motif in ligne for motif in motifs):
                return ligne
        raise RuntimeError(f"aucun de {motifs} vu en {delai:.0f} s")

    def commande(self, texte: str, reponses: tuple[str, ...] = (), delai: float = 30) -> str:
        self.processus.stdin.write((texte + "\n").encode("utf-8"))
        self.processus.stdin.flush()
        return self.attendre(reponses, delai) if reponses else ""

    def arreter(self) -> int:
        self.commande("stop")
        code = self.processus.wait(timeout=180)
        self.journal.close()
        return code


def valeurs_axion(snbt: str) -> dict[str, str]:
    """Les clés `axion:*` et `Pos` d'un NBT d'entité affiché par `/data get entity`, en texte."""
    valeurs: dict[str, str] = {}
    for cle in re.finditer(r'(?:"(axion:[a-z_]+)"|\b(Pos)): ', snbt):
        debut = cle.end()
        profondeur, indice = 0, debut
        while indice < len(snbt):
            car = snbt[indice]
            if car in "[{":
                profondeur += 1
            elif car in "]}":
                if profondeur == 0:
                    break
                profondeur -= 1
            elif car == "," and profondeur == 0:
                break
            elif car == '"':
                indice = snbt.index('"', indice + 1)
            indice += 1
        valeurs[cle.group(1) or cle.group(2)] = snbt[debut:indice].strip()
    return valeurs


def releve(serveur: Serveur) -> dict[int, dict[str, str] | None]:
    """Le NBT de chaque assembly de la campagne, ou {@code None} si elle n'est pas dans le monde."""
    etats: dict[int, dict[str, str] | None] = {}
    for n, _, _ in ASSEMBLIES:
        ligne = serveur.commande(f"data get entity {uuid_texte(n)}", (PRESENTE, INTROUVABLE), 10)
        etats[n] = valeurs_axion(ligne.split(PRESENTE, 1)[1]) if PRESENTE in ligne else None
    return etats


def preparer() -> None:
    for dossier in ("world", "axion", "mods", "logs"):
        shutil.rmtree(SERVEUR / dossier, ignore_errors=True)
    (SERVEUR / "mods").mkdir()
    proprietes = "\n".join(f"{cle}={valeur}" for cle, valeur in PROPRIETES.items())
    (SERVEUR / "server.properties").write_text(proprietes + "\n", encoding="utf-8")


def demarrer(phase: str, jvm: list[str] | None = None) -> Serveur:
    serveur = Serveur(CAMPAGNE / f"{phase}.log", jvm or [])
    serveur.attendre(("Done (",), 300)
    return serveur


def main() -> int:
    if not JAR.is_file():
        print(f"jar absent : {JAR} — lancer ./gradlew :axion-mod:validateJar")
        return 2
    rapport: list[str] = [f"Campagne T-435/T-713b du {datetime.now().isoformat(timespec='seconds')}"]
    verdicts: list[bool] = []

    def constat(ok: bool, texte: str) -> None:
        verdicts.append(ok)
        rapport.append(("OK    " if ok else "ÉCHEC ") + texte)
        print(rapport[-1], flush=True)

    preparer()
    shutil.copy2(JAR, SERVEUR / "mods" / JAR.name)

    # Phase 1 : avec AXION, et le contenu de démonstration qu'il livre (`content.examples`, faux par défaut).
    serveur = demarrer("phase-1-avec-axion", [EXEMPLES])
    serveur.commande("forceload add 0 0 15 15", ("Marked", "force loaded"), 30)
    for n, definition, (x, y, z) in ASSEMBLIES:
        nbt = "{UUID:[I;0,0,0,%d],%s}" % (n, ROTATION)
        serveur.commande(f"axion spawn {definition} {x} {y} {z} {nbt}")
        # La réponse nomme la definition, créée ou refusée ; le journal de simulation écrit aussi « AXION : ».
        ligne = serveur.attendre(("AXION :",), 30)
        while definition not in ligne:
            ligne = serveur.attendre(("AXION :",), 30)
        rapport.append(f"      phase 1, {definition} : {ligne.split('AXION :', 1)[1].strip()}")
    time.sleep(5)
    avant = releve(serveur)
    serveur.commande("save-all flush", ("Saved the game",), 120)
    code = serveur.arreter()
    presentes = [n for n, etat in avant.items() if etat]
    constat(len(presentes) == len(ASSEMBLIES) and code == 0,
            f"phase 1 : {len(presentes)}/{len(ASSEMBLIES)} assemblies dans le monde, NBT relevé, serveur arrêté ({code})")
    journaux = sorted((SERVEUR / "axion" / "world").glob("*/assemblies.*.journal"))
    constat(bool(journaux), "phase 1 : journal latéral écrit (" + ", ".join(p.name for p in journaux) + ")")
    if not presentes or not journaux:
        # Rien à perdre ni à restaurer : les phases suivantes ne prouveraient rien.
        return conclure(rapport, verdicts + [False])

    # Phase 2 : Forge seul — Minecraft perd les entités qu'il ne connaît pas.
    hors = CAMPAGNE / "hors-mods"
    hors.mkdir(exist_ok=True)
    shutil.move(str(SERVEUR / "mods" / JAR.name), str(hors / JAR.name))
    serveur = demarrer("phase-2-forge-seul", ["-Dfml.queryResult=confirm"])
    time.sleep(5)
    sans_axion = releve(serveur)
    serveur.commande("save-all flush", ("Saved the game",), 120)
    code = serveur.arreter()
    perdues = [n for n, etat in sans_axion.items() if etat is None]
    constat(perdues == presentes, f"phase 2 : sans AXION, {len(perdues)} assembly(s) introuvable(s) (attendu {len(presentes)})")
    constat(code == 0, f"phase 2 : le monde s'est chargé, joué et sauvegardé sans AXION, serveur arrêté ({code}) (R-1740)")
    nommees = sum(1 for ligne in serveur.lignes if "axion:assembly" in ligne)
    rapport.append(f"      phase 2 : {nommees} ligne(s) du journal du serveur nomment axion:assembly")
    constat((SERVEUR / "world" / "data" / "axion_registry.dat").is_file(),
            "phase 2 : data/axion_registry.dat a survécu au monde joué sans AXION")

    # Phase 3 : avec AXION de nouveau — restauration depuis le journal latéral.
    shutil.move(str(hors / JAR.name), str(SERVEUR / "mods" / JAR.name))
    serveur = demarrer("phase-3-avec-axion", [EXEMPLES])
    restaurations = 0
    fin = time.monotonic() + 60
    while restaurations < len(presentes) and time.monotonic() < fin:
        try:
            serveur.attendre(("depuis le journal lat",), max(1.0, fin - time.monotonic()))
            restaurations += 1
        except RuntimeError:
            break
    apres = releve(serveur)
    serveur.commande("save-all flush", ("Saved the game",), 120)
    code = serveur.arreter()
    constat(restaurations == len(presentes) and code == 0,
            f"phase 3 : {restaurations} restauration(s) au journal du serveur (attendu {len(presentes)}), serveur arrêté ({code})")
    for n, definition, _ in ASSEMBLIES:
        if n not in presentes:
            continue
        identique = apres.get(n) is not None and apres[n] == avant[n]
        constat(identique, f"phase 3 : {definition} ({uuid_texte(n)}) rendue à l'identique — "
                + ", ".join(sorted(avant[n])))
        if not identique:
            rapport.append(f"      avant : {avant[n]}")
            rapport.append(f"      après : {apres.get(n)}")
    rapports = sorted((SERVEUR / "axion" / "world").glob("*/restaurations.log"))
    lignes = sum(len(p.read_text(encoding="utf-8").splitlines()) for p in rapports)
    constat(lignes == len(presentes), f"phase 3 : restaurations.log liste {lignes} restauration(s)")
    return conclure(rapport, verdicts)


def conclure(rapport: list[str], verdicts: list[bool]) -> int:
    succes = all(verdicts)
    rapport.append("VERDICT : " + ("campagne verte" if succes else "campagne ROUGE"))
    chemin = CAMPAGNE / f"rapport-{datetime.now().strftime('%Y%m%d-%H%M%S')}.txt"
    chemin.write_text("\n".join(rapport) + "\n", encoding="utf-8")
    print(rapport[-1], "—", chemin)
    return 0 if succes else 1


if __name__ == "__main__":
    sys.exit(main())
