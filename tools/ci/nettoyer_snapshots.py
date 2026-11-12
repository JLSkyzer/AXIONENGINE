#!/usr/bin/env python3
"""Ne garde, sur le dépôt Maven d'AXION, que les dernières versions de développement de chaque artefact.

Chaque poussée sur master publie une nouvelle copie horodatée de chaque artefact -SNAPSHOT (environ 21,5 Mo, mesuré
le 2026-11-12), et Reposilite n'en efface aucune. Décision de Killian (2026-11-12) : garder les 10 dernières. La CI
lance ce script juste après la publication (job publication-maven), avec le jeton qui publie : Reposilite 3.6.5
n'exige, pour supprimer un fichier, que la permission d'écriture sur son chemin (RepositoryService.deleteFile,
canModifyResource → WRITE). Les versions publiées (releases) ne sont jamais touchées.

    python tools/ci/nettoyer_snapshots.py --essai     # dit ce qui partirait, sans rien supprimer ni s'authentifier
    python tools/ci/nettoyer_snapshots.py             # supprime ; identifiants dans AXION_MAVEN_USERNAME / _PASSWORD

Une copie se reconnaît à son horodatage Maven : <artefact>-<version de base>-<AAAAMMJJ.HHMMSS>-<numéro>[...].
maven-metadata.xml n'est pas réécrit : Gradle résout un SNAPSHOT par sa dernière copie, que le script garde.
"""

import base64
import json
import os
import re
import sys
import urllib.request

DEPOT = "https://maven.eriniumlab.com"
GROUPE = "dev/axion"
ARTEFACTS = ("axion-api", "axion", "axion-addon-template")
GARDER = 10


def a_supprimer(noms: list[str], artefact: str, version: str, garder: int) -> list[str]:
    """Les fichiers des copies au-delà des `garder` plus récentes ; les autres fichiers ne sont jamais rendus."""
    base = re.escape(artefact + "-" + version.removesuffix("-SNAPSHOT") + "-")
    motif = re.compile("^" + base + r"(\d{8}\.\d{6})-(\d+)(?:[-.].*)?$")
    copies: dict[tuple[str, int], list[str]] = {}
    for nom in noms:
        trouve = motif.match(nom)
        if trouve:
            copies.setdefault((trouve.group(1), int(trouve.group(2))), []).append(nom)
    anciennes = sorted(copies, reverse=True)[garder:]
    return sorted(nom for copie in anciennes for nom in copies[copie])


# Cloudflare, devant le dépôt, refuse le client par défaut de Python (403) : un nom de client générique.
CLIENT = {"User-Agent": "axion-ci-nettoyage/1"}


def _json(url: str):
    with urllib.request.urlopen(urllib.request.Request(url, headers=CLIENT), timeout=30) as reponse:
        return json.load(reponse)


def main(argv: list[str]) -> int:
    essai = "--essai" in argv
    entete = None
    if not essai:
        nom = os.environ.get("AXION_MAVEN_USERNAME") or os.environ.get("ORG_GRADLE_PROJECT_axionUsername")
        secret = os.environ.get("AXION_MAVEN_PASSWORD") or os.environ.get("ORG_GRADLE_PROJECT_axionPassword")
        if not nom or not secret:
            print("identifiants absents (AXION_MAVEN_USERNAME / AXION_MAVEN_PASSWORD) : rien n'est supprimé")
            return 1
        entete = "Basic " + base64.b64encode(f"{nom}:{secret}".encode()).decode()
    echecs = 0
    for artefact in ARTEFACTS:
        try:
            versions = [f["name"] for f in _json(f"{DEPOT}/api/maven/details/snapshots/{GROUPE}/{artefact}")["files"]
                        if f["name"].endswith("-SNAPSHOT")]
        except Exception as erreur:  # un artefact pas encore publié n'est pas une erreur
            print(f"{artefact} : rien à nettoyer ({erreur})")
            continue
        for version in versions:
            dossier = f"snapshots/{GROUPE}/{artefact}/{version}"
            noms = [f["name"] for f in _json(f"{DEPOT}/api/maven/details/{dossier}")["files"]]
            partants = a_supprimer(noms, artefact, version, GARDER)
            print(f"{artefact} {version} : {len(noms)} fichier(s), {len(partants)} à supprimer")
            for nom in partants:
                if essai:
                    print(f"  (essai) {nom}")
                    continue
                requete = urllib.request.Request(f"{DEPOT}/{dossier}/{nom}", method="DELETE",
                                                 headers={"Authorization": entete, **CLIENT})
                try:
                    with urllib.request.urlopen(requete, timeout=30):
                        print(f"  supprimé {nom}")
                except Exception as erreur:
                    echecs += 1
                    print(f"  ÉCHEC {nom} : {erreur}")
    return 1 if echecs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
