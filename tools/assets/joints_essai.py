"""Génère le contenu d'essai des joints (ADR-151 D10) : porte, pendule, chaîne et grille.

Chaque asset est un glTF texte dont chaque corps est un node racine, à sa pose de repos, portant deux
enfants : ``<corps>_vue`` (le maillage dessiné) et ``<corps>_collideur`` (le même maillage, collider
``auto_box``). Sa definition, au schéma 2, déclare les corps et les joints :

- ``porte`` : un montant fixe et un battant sur une charnière verticale, limitée à [0 ; 1,6] rad, qu'un
  servo ouvre à 1,2 rad, et qu'un demi-bloc de désalignement grippe tout à fait ;
- ``pendule`` : une masse au bout d'un bras invisible d'un bloc et demi, sur un pivot fixe, sans limite ;
- ``chaine`` : six maillons pendus en rotules sous une ancre fixe ;
- ``grille`` : neuf plaques soudées en carré, qui tombent d'un seul tenant.

Le script n'utilise que la bibliothèque standard et ne tire aucun hasard : deux exécutions écrivent
les mêmes octets. Usage, depuis la racine du dépôt :

    python tools/assets/joints_essai.py
"""

import base64
import json
import struct
from pathlib import Path

RACINE = Path(__file__).resolve().parents[2]
MODELES = RACINE / "java/axion-mod/src/devcontent/resources/data/axion/axion/models/test/joints"
DEFINITIONS = RACINE / "java/axion-mod/src/devcontent/resources/data/axion/axion/definitions/test/joints"

# Chaque face : normale, axe u, axe v, avec u × v = normale (sens trigonométrique vu de dehors).
FACES = [
    ((1, 0, 0), (0, 0, -1), (0, 1, 0)),
    ((-1, 0, 0), (0, 0, 1), (0, 1, 0)),
    ((0, 1, 0), (1, 0, 0), (0, 0, -1)),
    ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
    ((0, 0, 1), (1, 0, 0), (0, 1, 0)),
    ((0, 0, -1), (-1, 0, 0), (0, 1, 0)),
]


def boite(demi):
    """Les sommets, normales et indices d'une boîte centrée sur son origine, de demi-côtés ``demi``."""
    positions = []
    normales = []
    indices = []
    for normale, u, v in FACES:
        base = len(positions)
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            positions.append(tuple(
                (normale[k] + su * u[k] + sv * v[k]) * demi[k] for k in range(3)))
            normales.append(normale)
        indices += [base, base + 1, base + 2, base, base + 2, base + 3]
    return positions, normales, indices


def gltf(corps):
    """Le glTF d'un asset : ``corps`` est une liste de (nom, translation, demi-côtés)."""
    tailles = []
    for _, _, demi in corps:
        if demi not in tailles:
            tailles.append(demi)
    donnees = bytearray()
    accesseurs = []
    vues = []
    maillages = []
    for rang, demi in enumerate(tailles):
        positions, normales, indices = boite(demi)
        for valeurs, format_, cible in (
            (positions, "<3f", 34962),
            (normales, "<3f", 34962),
        ):
            debut = len(donnees)
            for valeur in valeurs:
                donnees += struct.pack(format_, *[float(c) for c in valeur])
            vues.append({"buffer": 0, "byteOffset": debut, "byteLength": len(donnees) - debut, "target": cible})
        debut = len(donnees)
        for indice in indices:
            donnees += struct.pack("<H", indice)
        vues.append({"buffer": 0, "byteOffset": debut, "byteLength": len(donnees) - debut, "target": 34963})
        while len(donnees) % 4:
            donnees += b"\x00"
        premier = len(accesseurs)
        accesseurs += [
            {"bufferView": 3 * rang, "componentType": 5126, "count": len(positions), "type": "VEC3",
             "min": [-c for c in demi], "max": list(demi)},
            {"bufferView": 3 * rang + 1, "componentType": 5126, "count": len(normales), "type": "VEC3"},
            {"bufferView": 3 * rang + 2, "componentType": 5123, "count": len(indices), "type": "SCALAR"},
        ]
        maillages.append({"name": f"boite_{rang}", "primitives": [
            {"attributes": {"POSITION": premier, "NORMAL": premier + 1}, "indices": premier + 2}]})
    noeuds = []
    racines = []
    for nom, translation, demi in corps:
        maillage = tailles.index(demi)
        racines.append(len(noeuds))
        noeuds.append({"name": nom, "translation": list(translation),
                       "children": [len(noeuds) + 1, len(noeuds) + 2]})
        noeuds.append({"name": f"{nom}_vue", "mesh": maillage})
        noeuds.append({"name": f"{nom}_collideur", "mesh": maillage,
                       "extras": {"axion": {"role": "collider", "shape": "auto_box"}}})
    return {
        "asset": {"version": "2.0", "generator": "AXION tools/assets/joints_essai.py"},
        "scene": 0,
        "scenes": [{"nodes": racines}],
        "nodes": noeuds,
        "meshes": maillages,
        "accessors": accesseurs,
        "bufferViews": vues,
        "buffers": [{"byteLength": len(donnees),
                     "uri": "data:application/octet-stream;base64," + base64.b64encode(bytes(donnees)).decode()}],
    }


def ecrire(nom, corps, definition):
    MODELES.mkdir(parents=True, exist_ok=True)
    DEFINITIONS.mkdir(parents=True, exist_ok=True)
    (MODELES / f"{nom}.gltf").write_text(json.dumps(gltf(corps), indent=1) + "\n", encoding="utf-8", newline="\n")
    definition = {"schema": 2, "asset": f"axion:models/test/joints/{nom}.gltf", **definition}
    (DEFINITIONS / f"{nom}.json").write_text(json.dumps(definition, indent=2, ensure_ascii=False) + "\n",
                                            encoding="utf-8", newline="\n")


def porte():
    # Montant de 2 blocs de haut ; battant d'un bloc de large, sa tranche contre le montant.
    corps = [("montant", (0.0, 1.0, 0.0), (0.1, 1.0, 0.1)), ("battant", (0.6, 1.0, 0.0), (0.5, 1.0, 0.05))]
    ecrire("porte", corps, {
        "kind": "static",
        "bodies": [{"name": "montant", "root_node": "montant"}],
        "joints": [{
            "name": "gond", "type": "revolute", "a": "montant", "b_node": "battant",
            "anchor": [-0.5, 0.0, 0.0], "axis": [0, 1, 0], "limits": [0.0, 1.6],
            "motor": {"target": 1.2, "stiffness": 4000, "damping": 400, "max_force": 5000},
            "jam": {"curve": [[0.5, 0.0]], "friction": 50},
        }],
    })


def pendule():
    corps = [("pivot", (0.0, 3.0, 0.0), (0.1, 0.1, 0.1)), ("masse", (1.5, 3.0, 0.0), (0.2, 0.2, 0.2))]
    ecrire("pendule", corps, {
        "kind": "static",
        "bodies": [{"name": "pivot", "root_node": "pivot"}],
        "joints": [{
            "name": "axe", "type": "revolute", "a": "pivot", "b_node": "masse",
            "anchor": [-1.5, 0.0, 0.0], "axis": [0, 0, 1],
        }],
    })


MAILLONS = 6


def chaine():
    corps = [("ancre", (0.0, 6.0, 0.0), (0.15, 0.15, 0.15))]
    for rang in range(MAILLONS):
        corps.append((f"maillon_{rang}", (0.0, 5.6 - 0.45 * rang, 0.0), (0.05, 0.2, 0.05)))
    noms = [nom for nom, _, _ in corps]
    joints = []
    for rang in range(MAILLONS):
        joints.append({
            "name": f"rotule_{rang}", "type": "spherical", "a": noms[rang], "b_node": noms[rang + 1],
            "anchor": [0.0, 0.25 if rang == 0 else 0.225, 0.0],
        })
    ecrire("chaine", corps, {
        "kind": "static",
        "bodies": [{"name": nom, "root_node": nom} for nom in noms[:-1]],
        "joints": joints,
    })


COTE = 3


def grille():
    corps = []
    for i in range(COTE):
        for j in range(COTE):
            corps.append((f"plaque_{i}_{j}", (0.5 * i, 0.0, 0.5 * j), (0.24, 0.05, 0.24)))
    joints = []
    for i in range(COTE):
        for j in range(COTE):
            for di, dj in ((1, 0), (0, 1)):
                if i + di < COTE and j + dj < COTE:
                    joints.append({
                        "name": f"soudure_{i}_{j}_{i + di}_{j + dj}", "type": "fixed", "a": f"plaque_{i}_{j}",
                        "b_node": f"plaque_{i + di}_{j + dj}", "anchor": [-0.25 * di, 0.0, -0.25 * dj],
                    })
    ecrire("grille", corps, {
        "kind": "rigid_object",
        "bodies": [{"name": nom, "root_node": nom} for nom, _, _ in corps],
        "joints": joints,
    })


def main():
    porte()
    pendule()
    chaine()
    grille()


if __name__ == "__main__":
    main()
