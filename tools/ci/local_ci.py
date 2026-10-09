#!/usr/bin/env python3
"""CI locale — rejoue sur cette machine les jobs de `.github/workflows/ci.yml`.

GitHub Actions peut manquer : depuis le 2026-10-03, les jobs du dépôt sont
refusés pour une raison de facturation, sans qu'une seule étape s'exécute. Ce
script exécute les mêmes commandes, dans le même ordre, et rend le même verdict :
0 si tout passe, 1 sinon.

Ce qu'il ne remplace pas : la matrice de plateformes. Il ne rejoue que la
configuration de la machine hôte — det-vectors (R-513) et build-natives (34.2)
n'y sont vérifiés que pour elle. Les autres cibles de la matrice restent à la
charge de la CI distante.

Autre différence, voulue : il vérifie l'arbre de travail tel qu'il est,
modifications non commitées comprises, là où la CI vérifie un commit. Un
avertissement le rappelle quand l'arbre suivi n'est pas propre.

Usage :

    python tools/ci/local_ci.py                 # tous les jobs
    python tools/ci/local_ci.py lint-rust deps  # seulement ceux-là
    python tools/ci/local_ci.py --list          # les jobs et leurs étapes

Gradle demande un JDK 17 : `JAVA_HOME` est transmis tel quel.

Chaque étape écrit sa sortie dans `target/ci-local/<job>--<étape>.log` ; la fin
du journal d'une étape en échec est recopiée à l'écran. Comme la CI
(`fail-fast: false`), un job en échec n'arrête pas les autres ; à l'intérieur
d'un job, la première étape en échec arrête les suivantes.
"""

from __future__ import annotations

import argparse
import os
import platform
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LOGS = REPO / "target" / "ci-local"
PYTHON = sys.executable
GRADLEW = str(REPO / ("gradlew.bat" if os.name == "nt" else "gradlew"))

# Un avertissement de compilation est un défaut qui n'a pas encore coûté (ci.yml).
ENV = dict(os.environ, CARGO_TERM_COLOR="never", RUSTDOCFLAGS="-D warnings")

# (job, [(étape, commande)]) — mêmes noms et même ordre que ci.yml.
JOBS: list[tuple[str, list[tuple[str, list[str]]]]] = [
    ("lint-rust", [
        ("cargo fmt --check", ["cargo", "fmt", "--all", "--", "--check"]),
        ("clippy -D warnings", ["cargo", "clippy", "--workspace", "--all-targets",
                                "--all-features", "--", "-D", "warnings"]),
    ]),
    ("lint-no-fiction", [
        ("R-001", [PYTHON, "tools/ci/lint_no_fiction.py"]),
    ]),
    ("compiler-version", [
        ("R-562", [PYTHON, "tools/ci/check_compiler_version.py"]),
    ]),
    ("deps", [
        ("cargo deny check", ["cargo", "deny", "check"]),
        ("NOTICE regenere", [PYTHON, "tools/deps/gen_notice.py"]),
        ("NOTICE a jour", ["git", "diff", "--exit-code", "--", "NOTICE"]),
    ]),
    ("build-and-test", [
        ("test-unit natif", ["cargo", "test", "--workspace", "--all-features"]),
        # `--rerun` sur les tests : ils lisent `crates/`, le CDC et les sources par
        # des propriétés système que Gradle ne compte pas parmi leurs entrées, et un
        # `UP-TO-DATE` laisserait passer une modification de ces fichiers sans les
        # rejouer. La CI distante, partant d'un clone neuf, les rejoue toujours.
        ("codegen-parity, JUnit et validate-jar",
         [GRADLEW, ":axion-mod:test", "--rerun", "build", "--no-daemon", "--stacktrace"]),
    ]),
    ("test-gametest", [
        ("GameTests (serveur dedie)", [GRADLEW, "runGameTestServer", "--no-daemon", "--stacktrace"]),
    ]),
    ("test-render", [
        ("banc de rendu (client automatise)", [GRADLEW, "runRenderTest", "--no-daemon", "--stacktrace"]),
    ]),
    ("platform-hote", [
        ("configuration deterministe", ["cargo", "run", "--quiet", "-p", "ax-det",
                                        "--example", "profil"]),
        ("det-vectors (T-820, R-513)", ["cargo", "test", "-p", "ax-det"]),
        ("build-natives (34.2)", ["cargo", "build", "--release", "-p", "ax-ffi"]),
    ]),
]

TAIL = 40

# Jobs qui ouvrent une fenêtre de jeu sur la machine : ils ne partent que nommés
# (ADR-126 — on ne lance pas le jeu chez quelqu'un sans le lui demander). Sans eux,
# le résumé les dit non lancés, et la CI locale n'est verte que « hors » eux.
ON_REQUEST = {"test-render"}


def slug(text: str) -> str:
    return "".join(c if c.isalnum() else "-" for c in text).strip("-").lower()


def run_step(job: str, step: str, command: list[str]) -> tuple[bool, float, Path]:
    log = LOGS / f"{job}--{slug(step)}.log"
    start = time.monotonic()
    with log.open("w", encoding="utf-8", errors="replace") as out:
        out.write(f"$ {' '.join(command)}\n\n")
        out.flush()
        try:
            code = subprocess.run(command, cwd=REPO, env=ENV, stdout=out,
                                  stderr=subprocess.STDOUT).returncode
        except FileNotFoundError as error:
            out.write(f"commande introuvable : {error}\n")
            code = 127
    return code == 0, time.monotonic() - start, log


def tail(log: Path) -> str:
    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
    return "\n".join("    " + line for line in lines[-TAIL:])


def warn_if_dirty() -> None:
    status = subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"],
                            cwd=REPO, capture_output=True, text=True).stdout
    if status.strip():
        print("attention : l'arbre suivi a des modifications non commitées ; elles sont "
              "vérifiées avec le reste, la CI distante ne les verrait pas.\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("jobs", nargs="*", help="jobs à exécuter (tous par défaut)")
    parser.add_argument("--list", action="store_true", help="lister les jobs et leurs étapes")
    args = parser.parse_args()

    known = [name for name, _ in JOBS]
    if args.list:
        for name, steps in JOBS:
            print(name)
            for step, command in steps:
                print(f"  {step} : {' '.join(command)}")
        return 0
    unknown = [name for name in args.jobs if name not in known]
    if unknown:
        print(f"job inconnu : {', '.join(unknown)} ; connus : {', '.join(known)}")
        return 2

    LOGS.mkdir(parents=True, exist_ok=True)
    print(f"CI locale — {platform.system()} {platform.machine()}, journaux dans {LOGS}\n")
    warn_if_dirty()

    results: list[tuple[str, str, bool | None, float]] = []
    not_launched: list[str] = []
    for name, steps in JOBS:
        if args.jobs and name not in args.jobs:
            continue
        if not args.jobs and name in ON_REQUEST:
            not_launched.append(name)
            continue
        failed = False
        for step, command in steps:
            if failed:
                results.append((name, step, None, 0.0))
                continue
            print(f"[{name}] {step} ...", flush=True)
            ok, seconds, log = run_step(name, step, command)
            results.append((name, step, ok, seconds))
            if not ok:
                failed = True
                print(f"[{name}] {step} : ÉCHEC ({seconds:.0f} s), fin de {log.name} :")
                print(tail(log))

    print("\nRésumé")
    for name, step, ok, seconds in results:
        verdict = "sautée" if ok is None else ("ok" if ok else "ÉCHEC")
        print(f"  {verdict:7} {name} / {step}" + ("" if ok is None else f" ({seconds:.0f} s)"))
    for name in not_launched:
        print(f"  non lancé {name} — ouvre une fenêtre de jeu : python tools/ci/local_ci.py {name}")
    passed = all(ok is True for _, _, ok, _ in results)
    outside = f" hors {', '.join(not_launched)}" if not_launched else ""
    print("\nCI locale : " + ("verte" + outside if passed else "ROUGE")
          + " — matrice de plateformes non couverte hors de l'hôte.")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
