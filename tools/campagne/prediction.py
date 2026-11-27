#!/usr/bin/env python3
"""Campagne T-415 / T-416 (ADR-168, 6e) : la prédiction sous latence injectée, par l'essai à deux processus.

Pour chaque réglage, un vrai serveur dédié (`runServer`, sous `AXION_ESSAI_PREDICTION=1`) pose le véhicule et y
assoit le premier joueur ; un vrai client (`runEssaiClientPrediction`) se connecte par le relais de latence de
développement, conduit par ses touches, puis écrit son rapport (`EssaiPrediction`, contenu de développement). Le
rapport porte les deux verdicts, que cette campagne relit :

- T-415, « prédiction : réponse immédiate du véhicule piloté » : le véhicule prédit bouge au plus un tick après
  l'appui, sans attendre l'aller-retour du serveur, et la prédiction tient pendant la seconde qui suit ;
- T-416, « réconciliation : correction douce » : des corrections sont rejouées, et aucune image ne dessine le
  véhicule plus de 5 cm au-delà du trajet que sa vitesse explique.

Un client simulé dans le serveur de GameTests ne peut pas avoir son monde prédit (un contexte natif par processus,
R-450, précision 6c d'ADR-168) : d'où deux processus, et une campagne hors de la CI, qui ouvre une fenêtre de jeu.

Avec --recharge, le serveur recharge les definitions pendant la conduite — le véhicule remplacé par un autre, deux fois
plus lourd — et le client juge ce qu'il en suit (verdict RECHARGE, ADR-175 D5).
Avec --gel, le serveur fige le véhicule en pleine conduite puis le rend, et le client juge sa prédiction (verdict GEL,
ADR-183 D3).

Avec --sol-mixte, les roues gauches passent un tronçon sur deux sur de la glace (ADR-180) : les verdicts T-415 et T-416
valent sur ce sol, et le rapport dit les sections à plusieurs matériaux que le client coupe.

Avec --energie (ADR-176), le véhicule est celui à démarreur et réservoir, sous la pluie ; le client met le contact
avant d'accélérer et juge en plus les verdicts ENERGIE — le contact prédit avant la réponse du serveur, la masse du
carburant dans le monde prédit — et MOUILLE — le mouillé que la prédiction lit. Seuls ces deux verdicts comptent :
T-415 et T-416 se jugent sur leur véhicule de référence, au sec ; plus lourd et sur route mouillée, celui-ci démarre
sous le seuil de 1 mm de T-415 au premier tick.

Usage : python tools/campagne/prediction.py [--reglage LATENCE:GIGUE ...]   (JAVA_HOME doit désigner un JDK 17)
Défaut : 100 ms par sens et 20 ms de gigue (un aller-retour de 200 ms au moins, celui de T-413), puis 50:20.
Le véhicule est celui de l'essai, `axion:test/vehicule`, sauf `AXION_ESSAI_VEHICULE` dans l'environnement.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

RACINE = Path(__file__).resolve().parents[2]
GRADLE = RACINE / ("gradlew.bat" if os.name == "nt" else "gradlew")
JOURNAL_SERVEUR = RACINE / ".runs/server/logs/latest.log"
ESSAI = RACINE / ".runs/essai-prediction"
RAPPORT = ESSAI / "rapport-prediction.txt"
SORTIES = RACINE / ".runs/campagne/prediction"

SERVEUR_PRET_S = 300
CLIENT_MAX_S = 900
SERVEUR_FIN_S = 60
VERDICTS = ("T-415", "T-416")


def arreter(processus: subprocess.Popen) -> None:
    """Arrête un lancement de Gradle et tout ce qu'il a lancé, s'il tourne encore."""
    if processus.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(processus.pid)], capture_output=True)
    else:
        processus.kill()
    processus.wait(timeout=60)


def essai(latence: int, gigue: int, film: bool = False, recharge: bool = False,
          sol_mixte: bool = False, energie: bool = False, gel: bool = False) -> dict[str, str]:
    """Joue un essai à deux processus ; rend le verdict de chaque test, ou lève RuntimeError."""
    SORTIES.mkdir(parents=True, exist_ok=True)
    defaut = "axion:test/vehicule_reservoir" if energie else "axion:test/vehicule"
    pose = os.environ.get("AXION_ESSAI_VEHICULE", defaut)
    vehicule = pose.rsplit("/", 1)[-1]
    nom = f"{vehicule}-{latence}-{gigue}" + ("-mixte" if sol_mixte else "")
    for fichier in (JOURNAL_SERVEUR, RAPPORT):
        fichier.unlink(missing_ok=True)
    # Les défauts du dépôt, pas une configuration générée par un lancement d'avant : un budget de rejeu de 2 ms, resté
    # d'un fichier du 2026-11-13, a faussé une campagne entière. Chaque côté régénère les siens au démarrage.
    for dossier in (RACINE / ".runs/server/config", ESSAI / "config"):
        for fichier in ("axion-common.toml", "axion-client.toml", "axion-server.toml"):
            (dossier / fichier).unlink(missing_ok=True)
    environnement = dict(os.environ, AXION_ESSAI_PREDICTION="1", AXION_ESSAI_VEHICULE=pose)
    environnement.pop("AXION_ESSAI_RECHARGE", None)
    if recharge:
        environnement["AXION_ESSAI_RECHARGE"] = "1"
    environnement.pop("AXION_ESSAI_SOL_MIXTE", None)
    if sol_mixte:
        environnement["AXION_ESSAI_SOL_MIXTE"] = "1"
    environnement.pop("AXION_ESSAI_PLUIE", None)
    if energie:
        environnement["AXION_ESSAI_PLUIE"] = "1"
    environnement.pop("AXION_ESSAI_GEL", None)
    if gel:
        environnement["AXION_ESSAI_GEL"] = "1"
    if film:
        # Le serveur d'un essai filmé met le temps au clair ; le client filme (Tournage, tools/film/encoder.py).
        environnement["AXION_ESSAI_FILM"] = "1"
        # Les images d'un film d'avant : le renommage des images en double s'y heurtait, et le client, ralenti, perdait
        # sa prédiction (2026-11-18).
        shutil.rmtree(ESSAI / "axion-film", ignore_errors=True)
    with open(SORTIES / f"serveur-{nom}.log", "w", encoding="utf-8") as sortie:
        serveur = subprocess.Popen([str(GRADLE), ":axion-mod:runServer"], cwd=RACINE, env=environnement,
                                   stdout=sortie, stderr=subprocess.STDOUT)
    try:
        debut = time.monotonic()
        while not (JOURNAL_SERVEUR.exists() and "Done (" in JOURNAL_SERVEUR.read_text("utf-8", "replace")):
            if serveur.poll() is not None or time.monotonic() - debut > SERVEUR_PRET_S:
                raise RuntimeError(f"serveur pas prêt (voir {SORTIES / f'serveur-{nom}.log'})")
            time.sleep(1)
        with open(SORTIES / f"client-{nom}.log", "w", encoding="utf-8") as sortie:
            commande = [str(GRADLE), ":axion-mod:runEssaiClientPrediction", f"-PessaiLatence={latence}",
                        f"-PessaiGigue={gigue}"] + (["-PessaiFilm"] if film else []) + (
                            ["-PessaiRecharge", "-PessaiProlonger=3"] if recharge else []) + (
                            ["-PessaiGel", "-PessaiProlonger=3"] if gel else []) + (
                            ["-PessaiBasculer=contact", "-PessaiBasculeTick=20"] if energie else [])
            client = subprocess.run(commande, cwd=RACINE, stdout=sortie, stderr=subprocess.STDOUT,
                                    timeout=CLIENT_MAX_S)
        if client.returncode != 0:
            raise RuntimeError(f"client sorti en {client.returncode} (voir {SORTIES / f'client-{nom}.log'})")
        # Le serveur d'essai s'arrête de lui-même quand son conducteur part.
        try:
            serveur.wait(timeout=SERVEUR_FIN_S)
        except subprocess.TimeoutExpired:
            pass
    finally:
        arreter(serveur)
    if not RAPPORT.exists():
        raise RuntimeError("aucun rapport : le client n'a pas conduit")
    lignes = RAPPORT.read_text("utf-8").splitlines()
    (SORTIES / f"rapport-{nom}.txt").write_text("\n".join(lignes) + "\n", encoding="utf-8")
    for releve in ("trajectoire-prediction.csv", "images-prediction.csv"):
        if (ESSAI / releve).exists():
            (SORTIES / f"{releve[:-4]}-{nom}.csv").write_bytes((ESSAI / releve).read_bytes())
    verdicts = {}
    attendus = (("RECHARGE",) if recharge else ("ENERGIE", "MOUILLE") if energie else ("GEL",) if gel
                else VERDICTS)
    for test in attendus:
        ligne = next((l for l in lignes if l.startswith(test + " : ")), None)
        if ligne is None:
            raise RuntimeError(f"{test} absent du rapport")
        verdicts[test] = ligne
    return verdicts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--reglage", action="append", metavar="LATENCE:GIGUE",
                        help="délai d'un sens et gigue du relais, en millisecondes (répétable)")
    parser.add_argument("--film", action="store_true",
                        help="filmer l'essai : le client tourne, tools/film/encoder.py fait le MP4")
    parser.add_argument("--recharge", action="store_true",
                        help="recharger les definitions pendant la conduite (ADR-175 D5) ; seul le verdict RECHARGE compte")
    parser.add_argument("--sol-mixte", action="store_true",
                        help="roues gauches sur la glace un tronçon sur deux (ADR-180)")
    parser.add_argument("--energie", action="store_true",
                        help="réservoir, démarreur et pluie : seuls les verdicts ENERGIE et MOUILLE comptent (ADR-176)")
    parser.add_argument("--gel", action="store_true",
                        help="figer le véhicule en pleine conduite (ADR-183 D3) ; seul le verdict GEL compte")
    arguments = parser.parse_args()
    reglages = [tuple(int(x) for x in r.split(":")) for r in (arguments.reglage or ["100:20", "50:20"])]
    echecs = 0
    for latence, gigue in reglages:
        print(f"--- {latence} ms par sens, gigue de 0 à {gigue} ms", flush=True)
        try:
            verdicts = essai(latence, gigue, arguments.film, arguments.recharge, arguments.sol_mixte,
                             arguments.energie, arguments.gel)
        except RuntimeError as faute:
            print(f"ÉCHEC : {faute}")
            echecs += 1
            continue
        for ligne in verdicts.values():
            print(ligne)
            echecs += 0 if " : réussi — " in ligne else 1
    print("campagne de la prédiction : " + ("réussie" if echecs == 0 else f"{echecs} échec(s)"))
    return 0 if echecs == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
