"""Gabarits des GameTests d'AXION (§29.5) : fichiers de structure de Minecraft 1.20.1.

Un gabarit est un NBT compressé (`data/axion/structures/<nom>.nbt`) : sa taille, sa palette et
ses blocs. Ceux d'ici se décrivent en quelques lignes ; les générer plutôt que les dessiner en
jeu les garde lisibles et reproductibles.

Usage : python tools/gametest/structures.py
"""

import gzip
import struct
from pathlib import Path

# Version de données de Minecraft 1.20.1, celle qu'écrit le jeu dans ses structures.
DATA_VERSION = 3465

SORTIE = Path(__file__).resolve().parents[2] / "java/axion-mod/src/devcontent/resources/data/axion/structures"


def _chaine(texte):
    octets = texte.encode("utf-8")
    return struct.pack(">H", len(octets)) + octets


def _charge(valeur):
    """Rend (type NBT, charge) d'une valeur Python : int, str, dict ou liste typée."""
    if isinstance(valeur, bool):
        raise TypeError("pas de booléen en NBT")
    if isinstance(valeur, int):
        return 3, struct.pack(">i", valeur)
    if isinstance(valeur, str):
        return 8, _chaine(valeur)
    if isinstance(valeur, dict):
        corps = b"".join(bytes([t]) + _chaine(cle) + c for cle, (t, c) in ((k, _charge(v)) for k, v in valeur.items()))
        return 10, corps + b"\x00"
    if isinstance(valeur, list):
        if not valeur:
            return 9, bytes([0]) + struct.pack(">i", 0)
        types_charges = [_charge(v) for v in valeur]
        type_element = types_charges[0][0]
        assert all(t == type_element for t, _ in types_charges), "liste NBT hétérogène"
        return 9, bytes([type_element]) + struct.pack(">i", len(valeur)) + b"".join(c for _, c in types_charges)
    raise TypeError(type(valeur))


def ecrire(nom, taille, palette, blocs):
    """Écrit le gabarit `nom` : `blocs` est une liste de ((x, y, z), indice de palette)."""
    racine = {
        "DataVersion": DATA_VERSION,
        "size": list(taille),
        "palette": [{"Name": p} for p in palette],
        "blocks": [{"pos": list(pos), "state": etat} for pos, etat in blocs],
        "entities": [],
    }
    t, charge = _charge(racine)
    SORTIE.mkdir(parents=True, exist_ok=True)
    chemin = SORTIE / f"{nom}.nbt"
    # mtime fixé : un même gabarit donne les mêmes octets, d'une génération à l'autre.
    with open(chemin, "wb") as f, gzip.GzipFile(fileobj=f, mode="wb", mtime=0) as z:
        z.write(bytes([t]) + _chaine("") + charge)
    print(f"{chemin.relative_to(SORTIE.parents[5])} : {taille[0]}×{taille[1]}×{taille[2]}, {len(blocs)} bloc(s)")


def plancher():
    """Un sol de pierre de 8 × 8 et 25 blocs d'air au-dessus : de quoi tomber plus de 20 m."""
    ecrire("plancher", (8, 26, 8), ["minecraft:stone"], [((x, 0, z), 0) for x in range(8) for z in range(8)])


if __name__ == "__main__":
    plancher()
