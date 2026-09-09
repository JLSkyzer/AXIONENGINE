#!/usr/bin/env python3
"""Verifie les symboles exportes par la bibliotheque native.

R-265 impose que tout symbole exporte porte le prefixe `axion_`, et R-2020 que
le binaire n'en exporte aucun autre : c'est ce qui garantit qu'AXION ne peut pas
entrer en collision avec la bibliotheque native d'un autre mod charge dans la
meme JVM.

Ce script est le controle local de cette regle ; T-661 l'automatisera en CI sur
les trois plateformes (M11).

Usage :
    python tools/ffi/check_exports.py [chemin/vers/la/bibliotheque]

Sans argument, il inspecte target/release/axion_native.dll.
"""
import pathlib
import struct
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

# JNI_OnLoad est impose par la specification JNI : la JVM l'appelle par nom au
# chargement. C'est la seule exception admise au prefixe.
ALLOWED_EXCEPTIONS = {"JNI_OnLoad", "JNI_OnUnload"}


def pe_exports(data):
    """Symboles exportes par un PE (.dll), sans dependance externe."""
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("signature PE absente")

    opt = pe + 24
    magic = struct.unpack_from("<H", data, opt)[0]
    # 0x20B = PE32+, dont les repertoires commencent plus loin.
    directories = opt + (112 if magic == 0x20B else 96)
    export_rva = struct.unpack_from("<I", data, directories)[0]
    if export_rva == 0:
        return []

    section_count = struct.unpack_from("<H", data, pe + 6)[0]
    first_section = opt + struct.unpack_from("<H", data, pe + 20)[0]
    sections = []
    for index in range(section_count):
        header = first_section + index * 40
        virtual_size = struct.unpack_from("<I", data, header + 8)[0]
        virtual_address = struct.unpack_from("<I", data, header + 12)[0]
        raw_offset = struct.unpack_from("<I", data, header + 20)[0]
        sections.append((virtual_address, virtual_size, raw_offset))

    def offset_of(rva):
        for virtual_address, virtual_size, raw_offset in sections:
            if virtual_address <= rva < virtual_address + max(virtual_size, 1):
                return raw_offset + (rva - virtual_address)
        raise ValueError(f"RVA hors sections : {rva:#x}")

    table = offset_of(export_rva)
    name_count = struct.unpack_from("<I", data, table + 24)[0]
    names_offset = offset_of(struct.unpack_from("<I", data, table + 32)[0])

    names = []
    for index in range(name_count):
        name_rva = struct.unpack_from("<I", data, names_offset + index * 4)[0]
        start = offset_of(name_rva)
        names.append(data[start:data.index(b"\0", start)].decode("ascii"))
    return names


def nm_exports(path):
    """Symboles exportes d'un binaire ELF ou Mach-O, via `nm`."""
    result = subprocess.run(
        ["nm", "--defined-only", "--extern-only", str(path)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("nm indisponible ou en echec : " + result.stderr.strip())
    names = []
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[1] in {"T", "D", "B", "S"}:
            names.append(parts[2].lstrip("_"))
    return names


def main():
    target = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else (
        ROOT / "target" / "release" / "axion_native.dll"
    )
    if not target.exists():
        print(f"bibliotheque introuvable : {target}", file=sys.stderr)
        print("la construire avec : cargo build --release -p ax-ffi", file=sys.stderr)
        return 2

    if target.suffix == ".dll":
        symbols = pe_exports(target.read_bytes())
    else:
        symbols = nm_exports(target)

    print(f"{len(symbols)} symbole(s) exporte(s) par {target.name} :")
    for name in sorted(symbols):
        print("  ", name)

    intrus = sorted(
        name for name in symbols
        if not name.startswith("axion_") and name not in ALLOWED_EXCEPTIONS
    )
    if intrus:
        print(file=sys.stderr)
        print("R-265 viole : symboles hors prefixe axion_ :", file=sys.stderr)
        for name in intrus:
            print("  ", name, file=sys.stderr)
        return 1

    print("R-265 respecte : aucun symbole hors prefixe.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
