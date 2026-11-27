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


MOTIF_VALEURS = re.compile(r"carburant ([\d.]+), mouillé ([\d.]+), usure ([\d.]+)")
# L'écart admis entre ce que le serveur dit et ce que l'observateur lit : la quantification du snapshot et un tick.
TOLERANCES = (0.01, 0.05, 0.02)
MOTIF_MOTEUR = re.compile(r"moteur (-?[\d.]+) tr/min (-?[\d.]+) kW")
# Un moteur électrique, si le serveur en dit un : régime et puissance changent d'un tick à l'autre.
TOLERANCE_REGIME = 0.1
TOLERANCE_PUISSANCE_KW = 0.5


def valeurs_lues(releve: list[str], journal: list[str]) -> bool:
    """Compare ce que l'observateur a lu à la dernière valeur que le serveur a dite ; vrai dans les tolérances."""
    lu = next((m for ligne in releve if ligne.startswith("valeurs vues") and (m := MOTIF_VALEURS.search(ligne))), None)
    dit = [m for ligne in journal if "valeurs serveur" in ligne and (m := MOTIF_VALEURS.search(ligne))]
    if not lu or not dit:
        print("valeurs : absentes d'un côté")
        return False
    vus = [float(x) for x in lu.groups()]
    # Le serveur dit ses valeurs toutes les 20 ticks : la plus proche de ce que l'observateur a lu, à la fin.
    ecarts = min(([abs(float(a) - b) for a, b in zip(m.groups(), vus)] for m in dit), key=sum)
    juste = all(e <= t for e, t in zip(ecarts, TOLERANCES)) and vus[0] > 0.1 and vus[1] > 0.1 and vus[2] > 0.3
    print(f"valeurs lues par l'observateur : {vus} ; écarts au serveur {[round(e, 3) for e in ecarts]} :"
          f" {'justes' if juste else 'FAUSSES'}")
    return moteur_lu(releve, journal) and juste


def moteur_lu(releve: list[str], journal: list[str]) -> bool:
    """Sans moteur au serveur, vrai ; sinon, ce que l'observateur lit du moteur doit être près d'une valeur dite."""
    dit = [m for ligne in journal if "valeurs serveur" in ligne and (m := MOTIF_MOTEUR.search(ligne))]
    if not dit:
        return True
    lu = next((m for ligne in releve if ligne.startswith("valeurs vues") and (m := MOTIF_MOTEUR.search(ligne))), None)
    if not lu:
        print("moteur : absent chez l'observateur")
        return False
    regime, puissance = (float(x) for x in lu.groups())
    proche = min(dit, key=lambda m: abs(float(m.group(1)) - regime))
    attendu, attendue = (float(x) for x in proche.groups())
    juste = (regime > 100 and abs(regime - attendu) <= TOLERANCE_REGIME * max(attendu, 1.0)
             and abs(puissance - attendue) <= TOLERANCE_PUISSANCE_KW)
    print(f"moteur lu par l'observateur : {regime} tr/min, {puissance} kW ; au serveur {attendu} tr/min, {attendue} kW :"
          f" {'juste' if juste else 'FAUX'}")
    return juste


def essai(latence: int, film: bool, observateur: bool = False, assis: bool = False, gigue: int = 0,
          ascenseur: bool = False) -> bool:
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
        shutil.rmtree((OBSERVATEUR if observateur else ESSAI) / "axion-film", ignore_errors=True)
    environnement = dict(os.environ, AXION_ESSAI_PORTEUR="1")
    environnement.pop("AXION_ESSAI_PREDICTION", None)
    environnement.pop("AXION_ESSAI_PORTEUR_DEUX", None)
    environnement.pop("AXION_ESSAI_ASCENSEUR", None)
    if ascenseur:
        environnement["AXION_ESSAI_ASCENSEUR"] = "1"
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
            commande = [str(GRADLE), ":axion-mod:runEssaiClientObservateur"] + (["-PessaiFilm"] if film else [])
            regard = subprocess.Popen(commande, cwd=RACINE, stdout=sortie_regard, stderr=subprocess.STDOUT)
        journal_client = SORTIES / f"client-{latence}.log"
        with open(journal_client, "w", encoding="utf-8") as sortie:
            commande = [str(GRADLE), ":axion-mod:runEssaiClientPorteur", f"-PessaiLatence={latence}",
                        f"-PessaiGigue={gigue}"]
            # Avec un observateur, c'est lui qu'on filme : deux clients qui encodent à la fois se ralentiraient.
            commande += ["-PessaiFilm"] if film and not observateur else []
            commande += ["-PessaiAscenseur"] if ascenseur else []
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
        if os.environ.get("AXION_ESSAI_VALEURS") == "1":
            vu = valeurs_lues(releve, journal) and vu
    if assis:
        # Assis, le porté n'est pas porté : son rapport ne vaut pas, seul ce que l'autre voit compte.
        return vu
    return bool(lignes) and lignes[-1].startswith("VERT") and not fautes and part >= SUPPORT_MIN and vu


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--latence", type=int, default=100, metavar="MS", help="délai d'un sens du relais")
    parser.add_argument("--film", action="store_true",
                        help="filmer l'essai : le client (avec --observateur, l'observateur) tourne dans"
                             " .runs/essai-*/axion-film, tools/film/encoder.py fait le MP4")
    parser.add_argument("--observateur", action="store_true", help="un second client juge ce qu'il voit du porté")
    parser.add_argument("--ascenseur", action="store_true",
                        help="le plateau figé et mené vers le haut, le bas et de côté au lieu d'être conduit (ADR-183 D4)")
    parser.add_argument("--gigue", type=int, default=0, metavar="MS",
                        help="gigue du relais : chaque envoi attend de 0 à MS millisecondes de plus, au hasard")
    parser.add_argument("--retard", action="store_true",
                        help="relever le retard réel du porté face à celui du serveur ; puis tools/campagne/retard.py")
    parser.add_argument("--siege", metavar="VEHICULE",
                        help="asseoir le porté dans ce véhicule d'essai : seul le verdict de l'observateur compte")
    parser.add_argument("--valeurs", action="store_true",
                        help="avec --siege et un véhicule à réservoir : l'observateur doit lire le carburant, le mouillé et"
                             " l'usure que le serveur a posés")
    parser.add_argument("--place", default="conducteur",
                        help="avec --siege : la place (conducteur : la voiture reste ; une autre : le serveur la conduit)")
    arguments = parser.parse_args()
    try:
        if arguments.siege:
            os.environ["AXION_ESSAI_SIEGE"] = arguments.siege
            os.environ["AXION_ESSAI_PLACE"] = arguments.place
        if arguments.valeurs:
            os.environ["AXION_ESSAI_VALEURS"] = "1"
        if arguments.retard:
            os.environ["AXION_ESSAI_RETARD"] = "1"
        vert = essai(arguments.latence, arguments.film, arguments.observateur or bool(arguments.siege),
                     bool(arguments.siege), arguments.gigue, arguments.ascenseur)
    except (RuntimeError, subprocess.TimeoutExpired) as faute:
        print(f"ÉCHEC : {faute}")
        vert = False
    print("essai du portage : " + ("réussi" if vert else "en échec"))
    return 0 if vert else 1


if __name__ == "__main__":
    sys.exit(main())
