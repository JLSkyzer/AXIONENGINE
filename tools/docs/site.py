#!/usr/bin/env python3
"""Construit ou sert le site de la documentation (ADR-101), depuis les guides de docs/guides.

    python tools/docs/site.py build    # le site dans target/site, en mode strict
    python tools/docs/site.py serve    # aperçu sur http://127.0.0.1:8765, rechargé à chaque modification

MkDocs et ses greffons s'installent dans target/docs-venv, aux versions épinglées de tools/docs/requirements.txt ;
l'environnement se recrée quand ce fichier change.
"""

import hashlib
import subprocess
import sys
from pathlib import Path

RACINE = Path(__file__).resolve().parents[2]
VERSIONS = RACINE / "tools" / "docs" / "requirements.txt"
ENV = RACINE / "target" / "docs-venv"
TEMOIN = ENV / "versions.sha256"
ADRESSE = "127.0.0.1:8765"


def python_de_l_env() -> Path:
    windows = ENV / "Scripts" / "python.exe"
    return windows if windows.exists() else ENV / "bin" / "python"


def preparer() -> Path:
    """{@return le Python de l'environnement, créé ou mis à jour si les versions épinglées ont changé}"""
    empreinte = hashlib.sha256(VERSIONS.read_bytes()).hexdigest()
    if not python_de_l_env().exists():
        subprocess.run([sys.executable, "-m", "venv", str(ENV)], check=True)
    if not TEMOIN.exists() or TEMOIN.read_text(encoding="ascii").strip() != empreinte:
        subprocess.run([str(python_de_l_env()), "-m", "pip", "install", "--quiet", "--disable-pip-version-check",
                        "-r", str(VERSIONS)], check=True)
        TEMOIN.write_text(empreinte, encoding="ascii")
    return python_de_l_env()


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[1] not in ("build", "serve"):
        print(__doc__)
        return 2
    python = preparer()
    commande = [str(python), "-m", "mkdocs", argv[1], "-f", str(RACINE / "mkdocs.yml")]
    if argv[1] == "serve":
        commande += ["-a", ADRESSE]
    return subprocess.run(commande, cwd=RACINE).returncode


if __name__ == "__main__":
    sys.exit(main(sys.argv))
