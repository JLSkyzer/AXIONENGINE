#!/usr/bin/env python3
"""Essai du portage en réseau (ADR-178, CAP-14), à deux processus : un serveur dédié et un client.

Le serveur (`runServer`, sous `AXION_ESSAI_PORTEUR=1`) pose un sol en altitude et un véhicule, y lâche le premier joueur,
en survie, puis conduit le véhicule en cercle pendant vingt secondes. Un vrai client (`runEssaiClientPorteur`) se connecte
par le relais de latence de développement, se laisse porter, mesure, écrit son rapport (`EssaiPorteur`, contenu de
développement) et s'arrête. La campagne relit :

- le rapport du client : la part des ticks où le joueur était porté, la dérive de sa place sur le véhicule, le trajet ;
- le journal du serveur : aucune correction « moved wrongly » ni « moved too quickly », aucune expulsion « en train de
  voler » — ce qu'un serveur en solo ne juge pas, ou trop brièvement —, et la part des ticks de conduite où le serveur a
  reconnu le support du joueur, au moins neuf sur dix : sans support, vanilla compte un joueur porté comme volant.

Il faut deux processus de jeu : la campagne est hors de la CI, et ouvre une fenêtre. Prendre le verrou des lancements
de Minecraft avant de la lancer, là où il y en a un.

Avec --observateur, un second client (`runEssaiClientObservateur`, `EssaiObservateur`) regarde le porté en spectateur
et juge ce qu'un autre joueur voit : ses jambes au repos, et sa place sur le véhicule.

Usage : python tools/campagne/portage.py [--latence MS] [--film] [--observateur]   (JAVA_HOME : un JDK 17)
Défaut : 100 ms par sens.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time

from prediction import GRADLE, JOURNAL_SERVEUR, RACINE, SERVEUR_FIN_S, SERVEUR_PRET_S, arreter

ESSAI = RACINE / ".runs/essai-porteur"
RAPPORT = ESSAI / "rapport-porteur.txt"
SORTIES = RACINE / ".runs/campagne/portage"
OBSERVATEUR = RACINE / ".runs/essai-observateur"
RAPPORT_OBSERVATEUR = OBSERVATEUR / "rapport-observateur.txt"

CLIENT_MAX_S = 900
# Ce que vanilla écrit quand il corrige ou expulse un joueur pour son mouvement.
FAUTES = ("moved wrongly", "moved too quickly", "floating too long")
# La part des ticks de conduite où le serveur doit reconnaître le support.
SUPPORT_MIN = 0.9


def essai(latence: int, film: bool, observateur: bool = False, assis: bool = False) -> bool:
    """Joue l'essai ; rend vrai si le client est vert et le serveur sans correction ni expulsion."""
    SORTIES.mkdir(parents=True, exist_ok=True)
    for fichier in (JOURNAL_SERVEUR, RAPPORT, RAPPORT_OBSERVATEUR):
        fichier.unlink(missing_ok=True)
    # Les défauts du dépôt, pas une configuration restée d'un lancement d'avant.
    for dossier in (RACINE / ".runs/server/config", ESSAI / "config", OBSERVATEUR / "config"):
        for fichier in ("axion-common.toml", "axion-client.toml", "axion-server.toml"):
            (dossier / fichier).unlink(missing_ok=True)
    shutil.rmtree(RACINE / ".runs/server/essai-porteur", ignore_errors=True)
    if film:
        shutil.rmtree(ESSAI / "axion-film", ignore_errors=True)
    environnement = dict(os.environ, AXION_ESSAI_PORTEUR="1")
    environnement.pop("AXION_ESSAI_PREDICTION", None)
    environnement.pop("AXION_ESSAI_PORTEUR_DEUX", None)
    if observateur:
        environnement["AXION_ESSAI_PORTEUR_DEUX"] = "1"
    regard = None
    sortie_regard = None
    journal_serveur = SORTIES / f"serveur-{latence}.log"
    with open(journal_serveur, "w", encoding="utf-8") as sortie:
        serveur = subprocess.Popen([str(GRADLE), ":axion-mod:runServer"], cwd=RACINE, env=environnement,
                                   stdout=sortie, stderr=subprocess.STDOUT)
    try:
        debut = time.monotonic()
        while not (JOURNAL_SERVEUR.exists() and "Done (" in JOURNAL_SERVEUR.read_text("utf-8", "replace")):
            if serveur.poll() is not None or time.monotonic() - debut > SERVEUR_PRET_S:
                raise RuntimeError(f"serveur pas prêt (voir {journal_serveur})")
            time.sleep(1)
        if observateur:
            sortie_regard = open(SORTIES / f"observateur-{latence}.log", "w", encoding="utf-8")
            regard = subprocess.Popen([str(GRADLE), ":axion-mod:runEssaiClientObservateur"], cwd=RACINE,
                                      stdout=sortie_regard, stderr=subprocess.STDOUT)
        journal_client = SORTIES / f"client-{latence}.log"
        with open(journal_client, "w", encoding="utf-8") as sortie:
            commande = [str(GRADLE), ":axion-mod:runEssaiClientPorteur", f"-PessaiLatence={latence}"]
            commande += ["-PessaiFilm"] if film else []
            client = subprocess.run(commande, cwd=RACINE, stdout=sortie, stderr=subprocess.STDOUT,
                                    timeout=CLIENT_MAX_S)
        if client.returncode != 0 and not assis:
            raise RuntimeError(f"client sorti en {client.returncode} (voir {journal_client})")
        # Le serveur d'essai s'arrête de lui-même quand son joueur part.
        try:
            if regard is not None:
                regard.wait(timeout=120)
            serveur.wait(timeout=SERVEUR_FIN_S)
        except subprocess.TimeoutExpired:
            pass
    finally:
        if regard is not None:
            arreter(regard)
        if sortie_regard is not None:
            sortie_regard.close()
        arreter(serveur)
    if not RAPPORT.exists():
        raise RuntimeError("aucun rapport : le client n'a rien mesuré")
    lignes = RAPPORT.read_text("utf-8").splitlines()
    journal = JOURNAL_SERVEUR.read_text("utf-8", "replace").splitlines()
    (SORTIES / f"rapport-{latence}.txt").write_text("\n".join(lignes) + "\n", encoding="utf-8")
    (SORTIES / f"journal-serveur-{latence}.log").write_text("\n".join(journal) + "\n", encoding="utf-8")
    for ligne in lignes:
        print(ligne)
    for ligne in journal:
        if "[porteur]" in ligne:
            print("serveur : " + ligne.split("[porteur]", 1)[1].strip())
    # Les fautes se comptent à partir du lâcher sur le véhicule : avant, un client encore en route vers la téléportation
    # de mise en place se fait dire « moved too quickly » de sa distance (constaté à deux joueurs, le 2026-11-26).
    lache = next((i for i, ligne in enumerate(journal) if "joueur lâché" in ligne), 0)
    fautes = [ligne for ligne in journal[lache:] if any(faute in ligne for faute in FAUTES)]
    for ligne in fautes[:10]:
        print("serveur, FAUTE : " + ligne)
    print(f"corrections et expulsions au journal du serveur : {len(fautes)}")
    reconnu = next((m for ligne in journal if (m := re.search(r"porté (\d+) ticks sur (\d+) de conduite", ligne))), None)
    part = int(reconnu.group(1)) / max(int(reconnu.group(2)), 1) if reconnu else 0.0
    print(f"support reconnu au serveur : {part:.0%} des ticks de conduite (au moins {SUPPORT_MIN:.0%})")
    vu = True
    if observateur:
        releve = RAPPORT_OBSERVATEUR.read_text("utf-8").splitlines() if RAPPORT_OBSERVATEUR.exists() else []
        for ligne in releve or ["ÉCHEC : aucun rapport de l'observateur"]:
            print("observateur : " + ligne)
        vu = bool(releve) and releve[-1].startswith("VERT")
    if assis:
        # Assis, le porté n'est pas porté : son rapport ne vaut pas, seul ce que l'autre voit compte.
        return vu
    return bool(lignes) and lignes[-1].startswith("VERT") and not fautes and part >= SUPPORT_MIN and vu


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--latence", type=int, default=100, metavar="MS", help="délai d'un sens du relais")
    parser.add_argument("--film", action="store_true",
                        help="filmer l'essai : le client tourne, tools/film/encoder.py fait le MP4")
    parser.add_argument("--observateur", action="store_true", help="un second client juge ce qu'il voit du porté")
    parser.add_argument("--siege", metavar="VEHICULE",
                        help="asseoir le porté dans ce véhicule d'essai : seul le verdict de l'observateur compte")
    parser.add_argument("--place", default="conducteur",
                        help="avec --siege : la place (conducteur : la voiture reste ; une autre : le serveur la conduit)")
    arguments = parser.parse_args()
    try:
        if arguments.siege:
            os.environ["AXION_ESSAI_SIEGE"] = arguments.siege
            os.environ["AXION_ESSAI_PLACE"] = arguments.place
        vert = essai(arguments.latence, arguments.film, arguments.observateur or bool(arguments.siege),
                     bool(arguments.siege))
    except (RuntimeError, subprocess.TimeoutExpired) as faute:
        print(f"ÉCHEC : {faute}")
        vert = False
    print("essai du portage : " + ("réussi" if vert else "en échec"))
    return 0 if vert else 1


if __name__ == "__main__":
    sys.exit(main())
