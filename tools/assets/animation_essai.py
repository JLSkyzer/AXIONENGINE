"""Génère le contenu d'essai de l'animation (ADR-155, tranche 4) : la glissière.

Un socle d'un bloc posé au sol, et un coulisseau d'un demi-bloc posé dessus. Chacun porte deux enfants :
``<nom>_vue`` (le maillage dessiné) et ``<nom>_collideur`` (le même maillage, collider ``auto_box``). Le
clip ``glisse`` translate le coulisseau d'un bloc en +x en une seconde, sans boucle : il tient ensuite sa
dernière pose. Son collider dépend d'un node animé, si bien que le serveur joue le clip (R-1430) ; le
centre de masse reste au-dessus du socle, et le corps ne bascule pas.

Le script n'utilise que la bibliothèque standard et ne tire aucun hasard : deux exécutions écrivent les
mêmes octets. Usage, depuis la racine du dépôt :

    python tools/assets/animation_essai.py
"""

import base64
import json
import struct
from pathlib import Path

RACINE = Path(__file__).resolve().parents[2]
CONTENU = RACINE / "java/axion-mod/src/devcontent/resources/data/axion/axion"
MODELES = CONTENU / "models/test/anime"
DEFINITIONS = CONTENU / "definitions/test/anime"

# Chaque face : normale, axe u, axe v, avec u × v = normale (sens trigonométrique vu de dehors).
FACES = [
    ((1, 0, 0), (0, 0, -1), (0, 1, 0)),
    ((-1, 0, 0), (0, 0, 1), (0, 1, 0)),
    ((0, 1, 0), (1, 0, 0), (0, 0, -1)),
    ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
    ((0, 0, 1), (1, 0, 0), (0, 1, 0)),
    ((0, 0, -1), (-1, 0, 0), (0, 1, 0)),
]

SOCLE = 0.5
COULISSEAU = 0.25
# Le coulisseau repose sur le socle ; le clip le pousse d'un bloc en +x.
REPOS = (0.0, 2 * SOCLE + COULISSEAU, 0.0)
COURSE = 1.0


def boite(demi):
    """Les sommets, normales et indices d'un cube centré sur son origine, de demi-côté ``demi``."""
    positions = []
    normales = []
    indices = []
    for normale, u, v in FACES:
        base = len(positions)
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            positions.append(tuple((normale[k] + su * u[k] + sv * v[k]) * demi for k in range(3)))
            normales.append(normale)
        indices += [base, base + 1, base + 2, base, base + 2, base + 3]
    return positions, normales, indices


class Tampon:
    """Le tampon unique du glTF, ses vues et ses accesseurs."""

    def __init__(self):
        self.donnees = bytearray()
        self.vues = []
        self.accesseurs = []

    def ajouter(self, octets, cible=None):
        while len(self.donnees) % 4:
            self.donnees += b"\x00"
        vue = {"buffer": 0, "byteOffset": len(self.donnees), "byteLength": len(octets)}
        if cible is not None:
            vue["target"] = cible
        self.donnees += octets
        self.vues.append(vue)
        return len(self.vues) - 1

    def accesseur(self, valeurs, type_, cible=None, bornes=False):
        format_ = {"SCALAR": "<f", "VEC3": "<3f"}[type_]
        octets = b"".join(struct.pack(format_, *(v if isinstance(v, tuple) else (v,))) for v in valeurs)
        accesseur = {"bufferView": self.ajouter(octets, cible), "componentType": 5126, "count": len(valeurs),
                     "type": type_}
        if bornes:
            colonnes = list(zip(*[v if isinstance(v, tuple) else (v,) for v in valeurs]))
            accesseur["min"] = [float(min(c)) for c in colonnes]
            accesseur["max"] = [float(max(c)) for c in colonnes]
        self.accesseurs.append(accesseur)
        return len(self.accesseurs) - 1

    def indices(self, valeurs):
        octets = b"".join(struct.pack("<H", v) for v in valeurs)
        self.accesseurs.append({"bufferView": self.ajouter(octets, 34963), "componentType": 5123,
                                "count": len(valeurs), "type": "SCALAR"})
        return len(self.accesseurs) - 1


def glissiere():
    tampon = Tampon()
    maillages = []
    for nom, demi in (("socle", SOCLE), ("coulisseau", COULISSEAU)):
        positions, normales, indices = boite(demi)
        position = tampon.accesseur([tuple(float(c) for c in p) for p in positions], "VEC3", 34962, bornes=True)
        normale = tampon.accesseur([tuple(float(c) for c in n) for n in normales], "VEC3", 34962)
        maillages.append({"name": nom, "primitives": [
            {"attributes": {"POSITION": position, "NORMAL": normale}, "indices": tampon.indices(indices)}]})
    collideur = {"axion": {"role": "collider", "shape": "auto_box"}}
    noeuds = [
        {"name": "socle", "children": [1, 2, 3]},
        {"name": "socle_vue", "mesh": 0, "translation": [0.0, SOCLE, 0.0]},
        {"name": "socle_collideur", "mesh": 0, "translation": [0.0, SOCLE, 0.0], "extras": collideur},
        {"name": "coulisseau", "translation": list(REPOS), "children": [4, 5]},
        {"name": "coulisseau_vue", "mesh": 1},
        {"name": "coulisseau_collideur", "mesh": 1, "extras": collideur},
    ]
    temps = tampon.accesseur([0.0, 1.0], "SCALAR", bornes=True)
    poses = tampon.accesseur([REPOS, (REPOS[0] + COURSE, REPOS[1], REPOS[2])], "VEC3")
    animations = [{
        "name": "glisse",
        "samplers": [{"input": temps, "output": poses, "interpolation": "LINEAR"}],
        "channels": [{"sampler": 0, "target": {"node": 3, "path": "translation"}}],
    }]
    return {
        "asset": {"version": "2.0", "generator": "AXION tools/assets/animation_essai.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": noeuds,
        "meshes": maillages,
        "animations": animations,
        "accessors": tampon.accesseurs,
        "bufferViews": tampon.vues,
        "buffers": [{"byteLength": len(tampon.donnees),
                     "uri": "data:application/octet-stream;base64," + base64.b64encode(bytes(tampon.donnees)).decode()}],
    }


def main():
    MODELES.mkdir(parents=True, exist_ok=True)
    DEFINITIONS.mkdir(parents=True, exist_ok=True)
    (MODELES / "glissiere.gltf").write_text(json.dumps(glissiere(), indent=1) + "\n", encoding="utf-8",
                                            newline="\n")
    definition = {"schema": 1, "asset": "axion:models/test/anime/glissiere.gltf", "kind": "rigid_object"}
    (DEFINITIONS / "glissiere.json").write_text(json.dumps(definition, indent=2) + "\n", encoding="utf-8",
                                                newline="\n")


if __name__ == "__main__":
    main()
