"""Assemble les images d'un film du banc en MP4 H.264 à 60 images par seconde (Windows, sans ffmpeg).

    python tools/film/encoder.py <dossier du film>

Le dossier contient `images/f*.png` et `termine.json` ; `film.mp4` y est écrit, puis relu : des images
extraites à intervalles réguliers vont dans `controle/`. Nécessite le SDK Windows pour la compilation.
"""
import json
import subprocess
import sys
from pathlib import Path

ICI = Path(__file__).resolve().parent
RACINE = ICI.parent.parent
FW = Path(r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319")
UNION = Path(r"C:\Program Files (x86)\Windows Kits\10\UnionMetadata\10.0.26100.0\Windows.winmd")
EXE = RACINE / "build" / "film" / "MfEncoder.exe"
FPS = 60
LARGEUR, HAUTEUR = 1920, 1080
DEBIT = 16_000_000


def compiler():
    if EXE.exists() and EXE.stat().st_mtime > (ICI / "MfEncoder.cs").stat().st_mtime:
        return
    EXE.parent.mkdir(parents=True, exist_ok=True)
    refs = ["System.Runtime.WindowsRuntime.dll", "System.Runtime.dll", "System.Threading.Tasks.dll", "System.IO.dll"]
    subprocess.run(
        [str(FW / "csc.exe"), "/nologo", f"/out:{EXE}"] + [f"/r:{FW / r}" for r in refs] + [f"/r:{UNION}", str(ICI / "MfEncoder.cs")],
        check=True,
    )


def main(dossier):
    bilan = json.loads((dossier / "termine.json").read_text(encoding="utf-8"))
    if bilan.get("echec"):
        sys.exit(f"le tournage a échoué : {bilan['echec']}")
    compiler()
    film = (dossier / "film.mp4").resolve()
    print(subprocess.run(
        [str(EXE), str((dossier / "images").resolve()), str(film), str(FPS), str(LARGEUR), str(HAUTEUR), str(DEBIT)],
        check=True, capture_output=True, text=True).stdout.strip())
    duree = bilan["images"] / FPS
    instants = [f"{duree * k / 12:.2f}" for k in range(1, 12)]
    print(subprocess.run(
        [str(EXE), "--verifier", str(film), str((dossier / "controle").resolve())] + instants,
        check=True, capture_output=True, text=True).stdout.strip())
    print(f"{bilan['images']} images dont {bilan['uniques']} uniques : {film}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
