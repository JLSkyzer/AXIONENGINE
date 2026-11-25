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

Usage : python tools/campagne/portage.py [--latence MS] [--film]   (JAVA_HOME doit désigner un JDK 17)
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

CLIENT_MAX_S = 900
# Ce que vanilla écrit quand il corrige ou expulse un joueur pour son mouvement.
FAUTES = ("moved wrongly", "moved too quickly", "floating too long")
# La part des ticks de conduite où le serveur doit reconnaître le support.
SUPPORT_MIN = 0.9


def essai(latence: int, film: bool) -> bool:
    """Joue l'essai ; rend vrai si le client est vert et le serveur sans correction ni expulsion."""
    SORTIES.mkdir(parents=True, exist_ok=True)
    for fichier in (JOURNAL_SERVEUR, RAPPORT):
        fichier.unlink(missing_ok=True)
    # Les défauts du dépôt, pas une configuration restée d'un lancement d'avant.
    for dossier in (RACINE / ".runs/server/config", ESSAI / "config"):
        for fichier in ("axion-common.toml", "axion-client.toml", "axion-server.toml"):
            (dossier / fichier).unlink(missing_ok=True)
    shutil.rmtree(RACINE / ".runs/server/essai-porteur", ignore_errors=True)
    if film:
        shutil.rmtree(ESSAI / "axion-film", ignore_errors=True)
    environnement = dict(os.environ, AXION_ESSAI_PORTEUR="1")
    environnement.pop("AXION_ESSAI_PREDICTION", None)
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
        journal_client = SORTIES / f"client-{latence}.log"
        with open(journal_client, "w", encoding="utf-8") as sortie:
            commande = [str(GRADLE), ":axion-mod:runEssaiClientPorteur", f"-PessaiLatence={latence}"]
            commande += ["-PessaiFilm"] if film else []
            client = subprocess.run(commande, cwd=RACINE, stdout=sortie, stderr=subprocess.STDOUT,
                                    timeout=CLIENT_MAX_S)
        if client.returncode != 0:
            raise RuntimeError(f"client sorti en {client.returncode} (voir {journal_client})")
        # Le serveur d'essai s'arrête de lui-même quand son joueur part.
        try:
            serveur.wait(timeout=SERVEUR_FIN_S)
        except subprocess.TimeoutExpired:
            pass
    finally:
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
    fautes = [ligne for ligne in journal if any(faute in ligne for faute in FAUTES)]
    for ligne in fautes[:10]:
        print("serveur, FAUTE : " + ligne)
    print(f"corrections et expulsions au journal du serveur : {len(fautes)}")
    reconnu = next((m for ligne in journal if (m := re.search(r"porté (\d+) ticks sur (\d+) de conduite", ligne))), None)
    part = int(reconnu.group(1)) / max(int(reconnu.group(2)), 1) if reconnu else 0.0
    print(f"support reconnu au serveur : {part:.0%} des ticks de conduite (au moins {SUPPORT_MIN:.0%})")
    return bool(lignes) and lignes[-1].startswith("VERT") and not fautes and part >= SUPPORT_MIN


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--latence", type=int, default=100, metavar="MS", help="délai d'un sens du relais")
    parser.add_argument("--film", action="store_true",
                        help="filmer l'essai : le client tourne, tools/film/encoder.py fait le MP4")
    arguments = parser.parse_args()
    try:
        vert = essai(arguments.latence, arguments.film)
    except (RuntimeError, subprocess.TimeoutExpired) as faute:
        print(f"ÉCHEC : {faute}")
        vert = False
    print("essai du portage : " + ("réussi" if vert else "en échec"))
    return 0 if vert else 1


if __name__ == "__main__":
    sys.exit(main())
