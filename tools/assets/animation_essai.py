"""Génère le contenu d'essai de l'animation (ADR-155) : la glissière (tranche 4) et le bras skinné (tranche 5).

La glissière : un socle d'un bloc posé au sol, et un coulisseau d'un demi-bloc posé dessus. Chacun porte
deux enfants : ``<nom>_vue`` (le maillage dessiné) et ``<nom>_collideur`` (le même maillage, collider
``auto_box``). Le clip ``glisse`` translate le coulisseau d'un bloc en +x en une seconde, sans boucle : il
tient ensuite sa dernière pose. Son collider dépend d'un node animé, si bien que le serveur joue le clip
(R-1430) ; le centre de masse reste au-dessus du socle, et le corps ne bascule pas.

Le bras skinné : une colonne de 0,4 × 2 × 0,4 sur un socle d'un demi-bloc de haut, sa peau liée à deux os,
``epaule`` au pied de la colonne et ``coude`` à mi-hauteur. Les sommets du pied suivent l'épaule, ceux du
milieu les deux à moitié, ceux du haut le coude. Le clip ``plier`` tourne le coude d'un quart de tour
autour de z en une seconde, sans boucle. Seul le socle porte un collider : l'assembly a un corps, donc des
snapshots, et le serveur ne joue pas l'animation, qu'aucun collider ne suit.

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

# Le bras skinné : demi-largeur de la colonne, hauteur du socle, pied de la colonne, longueur d'un segment.
BRAS = 0.2
SOCLE_BRAS = 0.25
PIED = 2 * SOCLE_BRAS
SEGMENT = 1.0
DEMI_RACINE = 0.70710678
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
        format_ = {"SCALAR": "<f", "VEC3": "<3f", "VEC4": "<4f"}[type_]
        octets = b"".join(struct.pack(format_, *(v if isinstance(v, tuple) else (v,))) for v in valeurs)
        accesseur = {"bufferView": self.ajouter(octets, cible), "componentType": 5126, "count": len(valeurs),
                     "type": type_}
        if bornes:
            colonnes = list(zip(*[v if isinstance(v, tuple) else (v,) for v in valeurs]))
            accesseur["min"] = [float(min(c)) for c in colonnes]
            accesseur["max"] = [float(max(c)) for c in colonnes]
        self.accesseurs.append(accesseur)
        return len(self.accesseurs) - 1

    def octets_vec4(self, valeurs):
        octets = b"".join(struct.pack("<4B", *v) for v in valeurs)
        self.accesseurs.append({"bufferView": self.ajouter(octets, 34962), "componentType": 5121,
                                "count": len(valeurs), "type": "VEC4"})
        return len(self.accesseurs) - 1

    def matrices(self, valeurs):
        octets = b"".join(struct.pack("<16f", *m) for m in valeurs)
        self.accesseurs.append({"bufferView": self.ajouter(octets), "componentType": 5126,
                                "count": len(valeurs), "type": "MAT4"})
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


def translation(y):
    """Une translation de ``y`` en y, colonne par colonne comme glTF."""
    return (1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, y, 0.0, 1.0)


def colonne():
    """La peau du bras : quatre faces de deux étages, et les deux bouts, os et poids de chaque sommet."""
    rangs = (PIED, PIED + SEGMENT, PIED + 2 * SEGMENT)
    # Os 0 : l'épaule, os 1 : le coude ; un sommet suit l'os de sa hauteur, à moitié au milieu.
    liens = {rangs[0]: ((0, 0, 0, 0), (1.0, 0.0, 0.0, 0.0)),
             rangs[1]: ((0, 1, 0, 0), (0.5, 0.5, 0.0, 0.0)),
             rangs[2]: ((1, 0, 0, 0), (1.0, 0.0, 0.0, 0.0))}
    positions, normales, os, poids, indices = [], [], [], [], []

    def quad(coins, normale):
        base = len(positions)
        for x, y, z in coins:
            positions.append((x, y, z))
            normales.append(normale)
            os.append(liens[y][0])
            poids.append(liens[y][1])
        indices.extend([base, base + 1, base + 2, base, base + 2, base + 3])

    b = BRAS
    for bas, haut in ((rangs[0], rangs[1]), (rangs[1], rangs[2])):
        quad([(b, bas, b), (b, bas, -b), (b, haut, -b), (b, haut, b)], (1.0, 0.0, 0.0))
        quad([(-b, bas, -b), (-b, bas, b), (-b, haut, b), (-b, haut, -b)], (-1.0, 0.0, 0.0))
        quad([(-b, bas, b), (b, bas, b), (b, haut, b), (-b, haut, b)], (0.0, 0.0, 1.0))
        quad([(b, bas, -b), (-b, bas, -b), (-b, haut, -b), (b, haut, -b)], (0.0, 0.0, -1.0))
    quad([(-b, rangs[2], b), (b, rangs[2], b), (b, rangs[2], -b), (-b, rangs[2], -b)], (0.0, 1.0, 0.0))
    quad([(-b, rangs[0], -b), (b, rangs[0], -b), (b, rangs[0], b), (-b, rangs[0], b)], (0.0, -1.0, 0.0))
    return positions, normales, os, poids, indices


def bras_skinne():
    tampon = Tampon()
    socle_pos, socle_norm, socle_idx = boite(0.5)
    socle_pos = [(x, y * SOCLE_BRAS / 0.5, z) for x, y, z in socle_pos]
    p = tampon.accesseur([tuple(float(c) for c in v) for v in socle_pos], "VEC3", 34962, bornes=True)
    n = tampon.accesseur([tuple(float(c) for c in v) for v in socle_norm], "VEC3", 34962)
    maillages = [{"name": "socle", "primitives": [
        {"attributes": {"POSITION": p, "NORMAL": n}, "indices": tampon.indices(socle_idx)}]}]
    positions, normales, os, poids, indices = colonne()
    p = tampon.accesseur(positions, "VEC3", 34962, bornes=True)
    n = tampon.accesseur(normales, "VEC3", 34962)
    j = tampon.octets_vec4(os)
    w = tampon.accesseur(poids, "VEC4", 34962)
    maillages.append({"name": "peau", "primitives": [
        {"attributes": {"POSITION": p, "NORMAL": n, "JOINTS_0": j, "WEIGHTS_0": w},
         "indices": tampon.indices(indices)}]})
    collideur = {"axion": {"role": "collider", "shape": "auto_box"}}
    noeuds = [
        {"name": "base", "children": [1, 2, 3, 4]},
        {"name": "socle_vue", "mesh": 0, "translation": [0.0, SOCLE_BRAS, 0.0]},
        {"name": "socle_collideur", "mesh": 0, "translation": [0.0, SOCLE_BRAS, 0.0], "extras": collideur},
        {"name": "bras", "mesh": 1, "skin": 0},
        {"name": "epaule", "translation": [0.0, PIED, 0.0], "children": [5]},
        {"name": "coude", "translation": [0.0, SEGMENT, 0.0]},
    ]
    liaisons = tampon.matrices([translation(-PIED), translation(-(PIED + SEGMENT))])
    temps = tampon.accesseur([0.0, 1.0], "SCALAR", bornes=True)
    rotations = tampon.accesseur([(0.0, 0.0, 0.0, 1.0), (0.0, 0.0, DEMI_RACINE, DEMI_RACINE)], "VEC4")
    return {
        "asset": {"version": "2.0", "generator": "AXION tools/assets/animation_essai.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": noeuds,
        "meshes": maillages,
        "skins": [{"joints": [4, 5], "inverseBindMatrices": liaisons}],
        "animations": [{
            "name": "plier",
            "samplers": [{"input": temps, "output": rotations, "interpolation": "LINEAR"}],
            "channels": [{"sampler": 0, "target": {"node": 5, "path": "rotation"}}],
        }],
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
    (MODELES / "bras_skinne.gltf").write_text(json.dumps(bras_skinne(), indent=1) + "\n", encoding="utf-8",
                                              newline="\n")
    definition = {"schema": 1, "asset": "axion:models/test/anime/bras_skinne.gltf", "kind": "rigid_object"}
    (DEFINITIONS / "bras_skinne.json").write_text(json.dumps(definition, indent=2) + "\n", encoding="utf-8",
                                                  newline="\n")


if __name__ == "__main__":
    main()
