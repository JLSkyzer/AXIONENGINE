#!/usr/bin/env python3
"""Agregation et comparaison des resultats de benchmarks (C-72, tools/bench).

Ce module lit les resultats archives sous ``benchmarks/results/`` au format
schema 2 (PARTIE 30.4) et rend trois services, independants du harnais qui a
produit les fichiers (criterion, JMH, harnais en jeu) :

- ``check``   : verifie que chaque resultat archive porte la provenance exigee
                par R-2231 ; sort en erreur au premier fichier mal forme.
- ``summary`` : liste les resultats par benchmark et par plateforme, avec le
                p50/p95/p99 du plus recent.
- ``compare`` : compare le p95 des deux resultats les plus recents d'une meme
                plateforme ; une hausse de plus de 20 % bloque (R-2251).

La comparaison est volontairement identique a celle du crate ``ax-bench`` : meme
metrique (p95), meme seuil, et jamais entre deux versions du harnais (ADR-111).
Les chiffres servent a detecter une regression grossiere, pas a etre publies
(R-2252).

Usage :
    python tools/bench/bench.py check   [--root DIR]
    python tools/bench/bench.py summary [--root DIR]
    python tools/bench/bench.py compare [--root DIR] [--all | --benchmark B --platform P]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

SCHEMA_VERSION = 2
HARNESS_VERSION_KEY = "harness_version"
# R-2251 : au-dela de cette hausse relative du p95, la fusion est bloquee.
REGRESSION_TOLERANCE = 0.20

DEFAULT_ROOT = pathlib.Path("benchmarks/results")

# Champs de provenance requis par R-2231 (le GPU, le pilote et la JVM ne le sont
# pas : une mesure CPU tourne sur une machine qui a peut-etre un GPU sans le
# solliciter — voir crate ax-bench).
REQUIRED_STATS = ("p50_ns", "p95_ns", "p99_ns", "min_ns", "max_ns", "stddev_ns")


def validate_result(result: dict) -> list[str]:
    """Rend la liste des manques de provenance d'un resultat (R-2231).

    Une liste vide signifie que le resultat est bien forme.
    """
    problems: list[str] = []

    if result.get("schema") != SCHEMA_VERSION:
        problems.append(f"schema {result.get('schema')!r} au lieu de {SCHEMA_VERSION}")

    for field in ("benchmark", "commit", "date"):
        value = result.get(field)
        if not isinstance(value, str) or not value.strip():
            problems.append(f"champ {field} absent ou vide")

    platform = result.get("platform")
    if not isinstance(platform, dict):
        problems.append("platform absent")
    else:
        for field in ("os", "cpu"):
            value = platform.get(field)
            if not isinstance(value, str) or not value.strip():
                problems.append(f"platform.{field} absent ou vide")
        cores = platform.get("cores")
        if not isinstance(cores, int) or cores <= 0:
            problems.append("platform.cores absent ou nul")

    config = result.get("config")
    if not isinstance(config, dict) or HARNESS_VERSION_KEY not in config:
        problems.append(f"config.{HARNESS_VERSION_KEY} absent")

    runs = result.get("runs")
    if not isinstance(runs, list) or not runs:
        problems.append("aucune execution")
    else:
        for index, run in enumerate(runs):
            samples = run.get("samples_ns") if isinstance(run, dict) else None
            if not isinstance(samples, list) or not samples:
                problems.append(f"execution {index} sans echantillon")

    stats = result.get("stats")
    if not isinstance(stats, dict):
        problems.append("stats absent")
    else:
        for field in REQUIRED_STATS:
            if field not in stats:
                problems.append(f"stats.{field} absent")

    return problems


def load_result(path: pathlib.Path) -> dict:
    """Lit un resultat JSON. Leve si le fichier n'est pas du JSON."""
    return json.loads(path.read_text(encoding="utf-8"))


def iter_results(root: pathlib.Path):
    """Parcourt ``<root>/<benchmark>/<plateforme>/*.json``, tries par chemin.

    Rend des tuples ``(benchmark, platform, path)``. Un dossier absent rend une
    suite vide plutot qu'une erreur : archiver n'est pas obligatoire.
    """
    if not root.is_dir():
        return
    for benchmark_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        for platform_dir in sorted(p for p in benchmark_dir.iterdir() if p.is_dir()):
            for path in sorted(platform_dir.glob("*.json")):
                yield benchmark_dir.name, platform_dir.name, path


def compare_p95(current: dict, baseline: dict) -> tuple[str, float | None]:
    """Compare le p95 courant a celui d'une reference.

    Rend ``(verdict, ratio)`` ou ``verdict`` vaut ``"no-baseline"``, ``"ok"`` ou
    ``"regressed"``. La comparaison n'a lieu qu'entre mesures du meme harnais
    (ADR-111) ; un p95 de reference nul n'est pas comparable.
    """
    cur_h = current.get("config", {}).get(HARNESS_VERSION_KEY)
    base_h = baseline.get("config", {}).get(HARNESS_VERSION_KEY)
    if cur_h != base_h:
        return "no-baseline", None

    base = baseline.get("stats", {}).get("p95_ns", 0)
    cur = current.get("stats", {}).get("p95_ns", 0)
    if not base:
        return "no-baseline", None

    ratio = (cur - base) / base
    if ratio > REGRESSION_TOLERANCE:
        return "regressed", ratio
    return "ok", ratio


def cmd_check(root: pathlib.Path) -> int:
    count = 0
    failed = 0
    for _benchmark, _platform, path in iter_results(root):
        count += 1
        try:
            result = load_result(path)
        except (OSError, json.JSONDecodeError) as error:
            print(f"illisible : {path} ({error})", file=sys.stderr)
            failed += 1
            continue
        problems = validate_result(result)
        if problems:
            failed += 1
            print(f"invalide : {path}", file=sys.stderr)
            for problem in problems:
                print(f"    - {problem}", file=sys.stderr)
    if failed:
        print(f"bench check : {failed}/{count} resultat(s) mal forme(s) (R-2231)", file=sys.stderr)
        return 1
    print(f"bench check : {count} resultat(s) archive(s), tous conformes (R-2231)")
    return 0


def cmd_summary(root: pathlib.Path) -> int:
    by_group: dict[tuple[str, str], list[pathlib.Path]] = {}
    for benchmark, platform, path in iter_results(root):
        by_group.setdefault((benchmark, platform), []).append(path)

    if not by_group:
        print("bench summary : aucun resultat archive")
        return 0

    for (benchmark, platform), paths in sorted(by_group.items()):
        latest = paths[-1]
        try:
            result = load_result(latest)
        except (OSError, json.JSONDecodeError) as error:
            print(f"{benchmark} / {platform} : illisible ({error})")
            continue
        stats = result.get("stats", {})
        print(
            f"{benchmark} / {platform} : "
            f"p50={stats.get('p50_ns')} p95={stats.get('p95_ns')} "
            f"p99={stats.get('p99_ns')} ns "
            f"({len(paths)} resultat(s), dernier {result.get('date')})"
        )
    return 0


def cmd_compare(root: pathlib.Path, benchmark: str | None, platform: str | None, every: bool) -> int:
    groups: dict[tuple[str, str], list[pathlib.Path]] = {}
    for bench, plat, path in iter_results(root):
        groups.setdefault((bench, plat), []).append(path)

    if not every:
        if not benchmark or not platform:
            print("bench compare : --benchmark et --platform requis (ou --all)", file=sys.stderr)
            return 2
        selected = {k: v for k, v in groups.items() if k == (benchmark, platform)}
        if not selected:
            print(f"bench compare : aucun resultat pour {benchmark} / {platform}", file=sys.stderr)
            return 2
        groups = selected

    regressed = False
    compared = 0
    for (bench, plat), paths in sorted(groups.items()):
        if len(paths) < 2:
            print(f"{bench} / {plat} : un seul resultat, rien a comparer")
            continue
        try:
            current = load_result(paths[-1])
            baseline = load_result(paths[-2])
        except (OSError, json.JSONDecodeError) as error:
            print(f"{bench} / {plat} : illisible ({error})", file=sys.stderr)
            return 1
        verdict, ratio = compare_p95(current, baseline)
        compared += 1
        if verdict == "no-baseline":
            print(f"{bench} / {plat} : pas de reference comparable")
        elif verdict == "ok":
            print(f"{bench} / {plat} : p95 {ratio * 100:+.1f} % vs precedent — ok")
        else:
            regressed = True
            print(f"{bench} / {plat} : p95 {ratio * 100:+.1f} % vs precedent — REGRESSION (> 20 %)")

    if regressed:
        print("bench compare : regression p95 superieure a 20 % (R-2251)", file=sys.stderr)
        return 1
    if compared == 0:
        print("bench compare : rien a comparer")
    return 0


def main(argv: list[str] | None = None) -> int:
    # `--root` est commun a toutes les sous-commandes et se place apres elles
    # (`bench.py check --root DIR`), la ou on l'attend.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "--root", type=pathlib.Path, default=DEFAULT_ROOT, help="dossier des resultats archives"
    )

    parser = argparse.ArgumentParser(description="Agregation et comparaison des benchmarks (C-72).")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("check", parents=[common], help="verifie la provenance des resultats archives (R-2231)")
    sub.add_parser("summary", parents=[common], help="liste les resultats par benchmark et plateforme")

    compare = sub.add_parser(
        "compare", parents=[common], help="compare les deux resultats les plus recents (R-2250, R-2251)"
    )
    compare.add_argument("--all", action="store_true", help="compare toutes les paires benchmark/plateforme")
    compare.add_argument("--benchmark", help="identifiant du benchmark, p. ex. B-02")
    compare.add_argument("--platform", help="slug de plateforme, p. ex. windows-cpu-test")

    args = parser.parse_args(argv)

    if args.command == "check":
        return cmd_check(args.root)
    if args.command == "summary":
        return cmd_summary(args.root)
    if args.command == "compare":
        return cmd_compare(args.root, args.benchmark, args.platform, args.all)
    parser.error("commande inconnue")
    return 2


if __name__ == "__main__":
    sys.exit(main())
