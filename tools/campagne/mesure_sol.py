#!/usr/bin/env python3
"""ADR-180 D5 : le coût des tuiles du sol, mesuré sur un serveur dédié et un monde généré (graine fixée).

`MesureSol` (contenu de développement) construit les tuiles de la surface, du sous-sol et d'une ville bâtie, puis fait
rouler 32 véhicules sur le terrain. Pour comparer deux versions, rejouer l'outil dans un second arbre (`git worktree`)
de la version d'avant, où l'on copie `MesureSol.java` et le bloc `AXION_MESURE_SOL` de `build.gradle` : `--racine`
désigne l'arbre. Le réglage du serveur (monde normal, graine) est posé pour la mesure et rendu ensuite.

Les chiffres ne valent que pour la machine et la date, et si rien d'autre ne tourne : prendre le verrou des lancements de
Minecraft avant, là où il y en a un.

Avec --profil, le serveur s'enregistre aussi en JFR (un échantillon par milliseconde, `profil_sol.jfc`) ; les
chiffres d'une passe profilée ne se comparent pas à ceux d'une passe simple.

Usage : python tools/campagne/mesure_sol.py [--racine DOSSIER] [--etiquette NOM] [--profil]   (JAVA_HOME : JDK 17)
Rapport : .runs/campagne/sol/rapport-<etiquette>.jsonl (dans cet arbre-ci)
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from prediction import RACINE, SERVEUR_PRET_S, arreter

SORTIES = RACINE / ".runs/campagne/sol"
DUREE_MAX_S = 1500
GRAINE = "424242"


def regler(serveur: Path) -> bytes | None:
    """Pose le monde normal à graine fixée ; rend le fichier d'avant, ou None s'il n'y en avait pas."""
    serveur.mkdir(parents=True, exist_ok=True)
    proprietes = serveur / "server.properties"
    avant = proprietes.read_bytes() if proprietes.exists() else None
    reference = RACINE / ".runs/server"
    if avant is None:
        proprietes.write_bytes((reference / "server.properties").read_bytes())
    if not (serveur / "eula.txt").exists():
        shutil.copy(reference / "eula.txt", serveur / "eula.txt")
    lignes = proprietes.read_text("utf-8").splitlines()
    voulu = {"level-type": "minecraft\\:normal", "level-seed": GRAINE}
    lignes = [ligne for ligne in lignes if ligne.split("=", 1)[0] not in voulu]
    lignes += [f"{cle}={valeur}" for cle, valeur in voulu.items()]
    proprietes.write_text("\n".join(lignes) + "\n", "utf-8")
    return avant


def mesurer(arbre: Path, etiquette: str, profil: bool = False) -> Path:
    SORTIES.mkdir(parents=True, exist_ok=True)
    serveur_dir = arbre / ".runs/server"
    releve = serveur_dir / "mesure-sol/rapport.jsonl"
    journal = serveur_dir / "logs/latest.log"
    for fichier in (releve, journal, releve.with_name("histogramme.txt")):
        fichier.unlink(missing_ok=True)
    for fichier in ("axion-common.toml", "axion-client.toml", "axion-server.toml"):
        (serveur_dir / "config" / fichier).unlink(missing_ok=True)
    shutil.rmtree(serveur_dir / "mesure-sol-monde", ignore_errors=True)
    avant = regler(serveur_dir)
    gradle = arbre / ("gradlew.bat" if os.name == "nt" else "gradlew")
    enregistrement = serveur_dir / "mesure-sol/profil.jfr"
    enregistrement.parent.mkdir(parents=True, exist_ok=True)
    enregistrement.unlink(missing_ok=True)
    environnement = dict(os.environ, AXION_MESURE_SOL="1")
    environnement.pop("AXION_MESURE_SOL_PROFIL", None)
    if profil:
        environnement["AXION_MESURE_SOL_PROFIL"] = "1"
    for autre in ("AXION_MESURE", "AXION_ESSAI_PREDICTION", "AXION_ESSAI_PORTEUR"):
        environnement.pop(autre, None)
    sortie_serveur = SORTIES / f"serveur-{etiquette}.log"
    try:
        with open(sortie_serveur, "w", encoding="utf-8") as sortie:
            serveur = subprocess.Popen([str(gradle), ":axion-mod:runServer"], cwd=arbre, env=environnement,
                                       stdout=sortie, stderr=subprocess.STDOUT)
        try:
            debut = time.monotonic()
            while serveur.poll() is None:
                texte = journal.read_text("utf-8", "replace") if journal.exists() else ""
                if "[mesure sol] terminee" in texte:
                    break
                if "Done (" not in texte and time.monotonic() - debut > SERVEUR_PRET_S + 600:
                    raise RuntimeError(f"serveur pas prêt (voir {sortie_serveur})")
                if time.monotonic() - debut > DUREE_MAX_S:
                    raise RuntimeError("mesure trop longue")
                time.sleep(1)
            try:
                serveur.wait(timeout=120)
            except subprocess.TimeoutExpired:
                pass
        finally:
            arreter(serveur)
    finally:
        proprietes = serveur_dir / "server.properties"
        if avant is None:
            proprietes.unlink(missing_ok=True)
        else:
            proprietes.write_bytes(avant)
    if not releve.exists():
        raise RuntimeError(f"aucun relevé (voir {sortie_serveur})")
    rapport = SORTIES / f"rapport-{etiquette}.jsonl"
    shutil.copy(releve, rapport)
    histogramme = releve.with_name("histogramme.txt")
    if histogramme.exists():
        shutil.copy(histogramme, SORTIES / f"histogramme-{etiquette}.txt")
    if profil:
        if not enregistrement.exists():
            raise RuntimeError("aucun enregistrement JFR")
        shutil.copy(enregistrement, SORTIES / f"profil-{etiquette}.jfr")
    return rapport


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--racine", type=Path, default=RACINE, help="l'arbre à mesurer")
    parser.add_argument("--etiquette", default="actuel")
    parser.add_argument("--profil", action="store_true", help="enregistrer le serveur en JFR")
    arguments = parser.parse_args()
    try:
        rapport = mesurer(arguments.racine.resolve(), arguments.etiquette, arguments.profil)
    except (RuntimeError, OSError) as faute:
        print(f"ÉCHEC : {faute}")
        return 1
    print(rapport.read_text("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
