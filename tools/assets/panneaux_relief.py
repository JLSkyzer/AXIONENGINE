"""Génère les deux panneaux d'essai des cartes de matériau du backend natif (ADR-127 §5).

``relief.glb`` porte une normal map — des stries horizontales qui inclinent la normale vers le haut puis
vers le bas — et une ORM — moitié haute en métal lisse, moitié basse en diélectrique rugueux, sans
occlusion. ``plat.glb`` est le même panneau, de la même couleur, sans aucune carte. Sous le soleil de
midi, le relief fait alterner des bandes claires et sombres que le plat n'a pas : le banc de rendu
compare leurs luminances (ADR-127 §7).

Chaque panneau mesure 1 m de large, 1 m de haut et 10 cm d'épaisseur, posé par son bas ; ses faces
avant regardent +Z. Un nœud collideur ``auto_box`` lui donne son corps, comme au panneau émissif.

Le script n'utilise que la bibliothèque standard et ne tire aucun hasard : deux exécutions écrivent
les mêmes octets. Usage, depuis la racine du dépôt :

    python tools/assets/panneaux_relief.py
"""

import json
import math
import struct
import zlib
from pathlib import Path

RACINE = Path(__file__).resolve().parents[2]
MODELES = RACINE / "java/axion-mod/src/devcontent/resources/data/axion/axion/models/test/materiaux"
DEFINITIONS = RACINE / "java/axion-mod/src/devcontent/resources/data/axion/axion/definitions/test/materiaux"

TAILLE = 64
STRIES = 8
PENTE = 0.8

DEMI = (0.5, 0.5, 0.05)
CENTRE = (0.0, 0.5, 0.0)

# Chaque face : normale, axe u, axe v, avec u × v = normale (sens trigonométrique vu de dehors).
FACES = [
    ((1, 0, 0), (0, 0, -1), (0, 1, 0)),
    ((-1, 0, 0), (0, 0, 1), (0, 1, 0)),
    ((0, 1, 0), (1, 0, 0), (0, 0, -1)),
    ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
    ((0, 0, 1), (1, 0, 0), (0, 1, 0)),
    ((0, 0, -1), (-1, 0, 0), (0, 1, 0)),
]


def png(largeur, hauteur, rgb):
    """Un PNG RVB 8 bits, sans entrelacement, filtre nul."""
    lignes = b"".join(b"\x00" + rgb[y * largeur * 3:(y + 1) * largeur * 3] for y in range(hauteur))

    def bloc(nom, donnees):
        return struct.pack(">I", len(donnees)) + nom + donnees + struct.pack(">I", zlib.crc32(nom + donnees))

    entete = struct.pack(">IIBBBBB", largeur, hauteur, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + bloc(b"IHDR", entete) + bloc(b"IDAT", zlib.compress(lignes, 9)) + bloc(b"IEND", b"")


def carte_de_normales():
    """Stries horizontales : la normale de l'espace tangent penche vers +v puis vers -v."""
    rgb = bytearray()
    for y in range(TAILLE):
        v = (y + 0.5) / TAILLE
        pente = PENTE * math.sin(2.0 * math.pi * STRIES * v)
        longueur = math.sqrt(pente * pente + 1.0)
        n = (0.0, pente / longueur, 1.0 / longueur)
        pixel = bytes(round((c * 0.5 + 0.5) * 255) for c in n)
        rgb += pixel * TAILLE
    return png(TAILLE, TAILLE, bytes(rgb))


def carte_orm():
    """Occlusion nulle (R à 255) ; moitié haute métal lisse, moitié basse diélectrique rugueux."""
    rgb = bytearray()
    for y in range(TAILLE):
        haut = y < TAILLE // 2
        pixel = bytes((255, round(0.25 * 255) if haut else round(0.8 * 255), 255 if haut else 0))
        rgb += pixel * TAILLE
    return png(TAILLE, TAILLE, bytes(rgb))


def boite():
    """Positions, normales, UV et indices d'une boîte, quatre sommets par face."""
    positions, normales, uv, indices = [], [], [], []
    for n, u, v in FACES:
        axe_n = next(i for i in range(3) if n[i])
        axe_u = next(i for i in range(3) if u[i])
        axe_v = next(i for i in range(3) if v[i])
        centre = [CENTRE[i] + n[i] * DEMI[axe_n] for i in range(3)]
        premier = len(positions)
        for s, t in ((0, 0), (1, 0), (1, 1), (0, 1)):
            p = [centre[i] + (2 * s - 1) * u[i] * DEMI[axe_u] + (2 * t - 1) * v[i] * DEMI[axe_v] for i in range(3)]
            positions.append(p)
            normales.append(list(n))
            uv.append([float(s), float(1 - t)])
        indices += [premier, premier + 1, premier + 2, premier, premier + 2, premier + 3]
    return positions, normales, uv, indices


def glb(nom, materiau, images):
    """Assemble un GLB : la boîte, son collideur auto_box, le matériau et ses images."""
    positions, normales, uv, indices = boite()
    binaire = bytearray()
    vues = []

    def vue(octets, cible=None):
        while len(binaire) % 4:
            binaire.append(0)
        entree = {"buffer": 0, "byteOffset": len(binaire), "byteLength": len(octets)}
        if cible is not None:
            entree["target"] = cible
        binaire.extend(octets)
        vues.append(entree)
        return len(vues) - 1

    v_pos = vue(b"".join(struct.pack("<3f", *p) for p in positions), 34962)
    v_nor = vue(b"".join(struct.pack("<3f", *n) for n in normales), 34962)
    v_uv = vue(b"".join(struct.pack("<2f", *t) for t in uv), 34962)
    v_ind = vue(b"".join(struct.pack("<H", i) for i in indices), 34963)
    accessoires = [
        {"bufferView": v_pos, "componentType": 5126, "count": len(positions), "type": "VEC3",
         "min": [min(p[i] for p in positions) for i in range(3)],
         "max": [max(p[i] for p in positions) for i in range(3)]},
        {"bufferView": v_nor, "componentType": 5126, "count": len(normales), "type": "VEC3"},
        {"bufferView": v_uv, "componentType": 5126, "count": len(uv), "type": "VEC2"},
        {"bufferView": v_ind, "componentType": 5123, "count": len(indices), "type": "SCALAR"},
    ]
    doc = {
        "asset": {"generator": "AXION tools/assets/panneaux_relief.py", "version": "2.0"},
        "scene": 0,
        "scenes": [{"name": "Scene", "nodes": [0, 1]}],
        "nodes": [
            {"extras": {"axion": {"role": "collider", "shape": "auto_box"}}, "mesh": 0, "name": "collideur"},
            {"mesh": 0, "name": "panneau"},
        ],
        "meshes": [{"name": "panneau", "primitives": [
            {"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "indices": 3, "material": 0}]}],
        "materials": [materiau],
        "accessors": accessoires,
    }
    if images:
        doc["images"] = [{"bufferView": vue(octets), "mimeType": "image/png", "name": nom_image}
                         for nom_image, octets in images]
        doc["samplers"] = [{"magFilter": 9729, "minFilter": 9987}]
        doc["textures"] = [{"sampler": 0, "source": i} for i in range(len(images))]
    while len(binaire) % 4:
        binaire.append(0)
    doc["bufferViews"] = vues
    doc["buffers"] = [{"byteLength": len(binaire)}]
    texte = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    while len(texte) % 4:
        texte += b" "
    total = 12 + 8 + len(texte) + 8 + len(binaire)
    sortie = struct.pack("<4sII", b"glTF", 2, total)
    sortie += struct.pack("<I4s", len(texte), b"JSON") + texte
    sortie += struct.pack("<I4s", len(binaire), b"BIN\x00") + bytes(binaire)
    (MODELES / f"{nom}.glb").write_bytes(sortie)
    definition = {"schema": 1, "asset": f"axion:models/test/materiaux/{nom}.glb", "kind": "rigid_object"}
    (DEFINITIONS / f"{nom}.json").write_text(json.dumps(definition, indent=2) + "\n", encoding="utf-8", newline="\n")


def main():
    gris = [0.8, 0.8, 0.8, 1.0]
    glb("relief", {
        "name": "MAT-relief",
        "pbrMetallicRoughness": {
            "baseColorFactor": gris, "metallicFactor": 1.0, "roughnessFactor": 1.0,
            "metallicRoughnessTexture": {"index": 1},
        },
        "normalTexture": {"index": 0, "scale": 1.0},
        "occlusionTexture": {"index": 1, "strength": 1.0},
    }, [("normales", carte_de_normales()), ("orm", carte_orm())])
    glb("plat", {
        "name": "MAT-plat",
        "pbrMetallicRoughness": {"baseColorFactor": gris, "metallicFactor": 0.0, "roughnessFactor": 0.8},
    }, [])
    print("relief.glb et plat.glb écrits dans", MODELES.relative_to(RACINE))


if __name__ == "__main__":
    main()
