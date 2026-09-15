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
import math
import pathlib
import re
import sys

SCHEMA_VERSION = 2
HARNESS_VERSION_KEY = "harness_version"
# R-2251 : au-dela de cette hausse relative du p95, la fusion est bloquee.
REGRESSION_TOLERANCE = 0.20

# Version du pont JMH -> schema 2. Distincte de celle du harnais Rust : les deux
# ne mesurent pas la meme chose (ADR-111). A incrementer si la conversion change
# de methode.
JMH_BRIDGE_VERSION = 1

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


# --- Pont JMH -> schema 2 -------------------------------------------------------


def slugify(text: str) -> str:
    """Minuscules alphanumeriques ASCII, tout autre caractere en tiret, sans
    tiret en bordure.

    Identique au slug du crate ax-bench, pour que les deux ecrivent au meme
    endroit et que `summary`/`compare` retrouvent les uns comme les autres.
    """
    out = [ch.lower() if ch.isascii() and ch.isalnum() else "-" for ch in text]
    return "".join(out).strip("-")


def compute_stats(samples: list[int]) -> dict:
    """Agregats d'echantillons (ns), meme methode qu'ax-bench.

    Centiles au rang le plus proche sur les echantillons tries, ecart-type de
    population. `samples` est suppose non vide.
    """
    ordered = sorted(samples)
    n = len(ordered)

    def percentile(p: int) -> int:
        rank = max(1, min(n, -(-p * n // 100)))  # ceil(p * n / 100)
        return ordered[rank - 1]

    mean = sum(ordered) / n
    variance = sum((x - mean) ** 2 for x in ordered) / n
    return {
        "p50_ns": percentile(50),
        "p95_ns": percentile(95),
        "p99_ns": percentile(99),
        "min_ns": ordered[0],
        "max_ns": ordered[-1],
        "stddev_ns": math.sqrt(variance),
    }


_UNIT_TO_NS = {"ns": 1.0, "us": 1_000.0, "ms": 1_000_000.0, "s": 1_000_000_000.0}


def unit_factor(score_unit: str) -> float:
    """Facteur convertissant une unite JMH (`ns/op`, `us/op`...) en nanosecondes."""
    unit = score_unit.split("/")[0].strip().lower()
    if unit not in _UNIT_TO_NS:
        raise ValueError(f"unite JMH non geree : {score_unit!r}")
    return _UNIT_TO_NS[unit]


def benchmark_id_from_jmh(full_name: str) -> str:
    """Deduit l'identifiant B-xx du nom qualifie d'un benchmark JMH.

    `dev.axion.bench.B07FfiRoundtrip.emptyRoundtrip` -> `B-07`. La convention est
    qu'une classe de benchmark commence par `Bnn` (PARTIE 30.2).
    """
    simple = full_name.split(".")[-2] if "." in full_name else full_name
    match = re.match(r"[Bb](\d+)", simple)
    if not match:
        raise ValueError(f"classe JMH sans prefixe Bnn : {simple!r} (attendu p. ex. B07...)")
    return f"B-{int(match.group(1)):02d}"


def jmh_to_results(
    jmh: list, *, commit: str, date: str, cpu: str, os_name: str, cores: int
) -> list:
    """Convertit une sortie JSON de JMH en resultats schema 2.

    Chaque benchmark JMH devient un resultat ; les echantillons bruts sont les
    valeurs par iteration de `rawData` (une execution par fork), converties en
    nanosecondes. La provenance que JMH ne connait pas — commit, date, machine —
    vient de l'appelant (R-2231).
    """
    results = []
    for entry in jmh:
        metric = entry["primaryMetric"]
        factor = unit_factor(metric["scoreUnit"])
        runs = []
        pooled: list[int] = []
        for fork in metric["rawData"]:
            samples = [round(value * factor) for value in fork]
            if samples:
                runs.append({"samples_ns": samples})
                pooled.extend(samples)
        if not pooled:
            continue
        results.append(
            {
                "schema": SCHEMA_VERSION,
                "benchmark": benchmark_id_from_jmh(entry["benchmark"]),
                "commit": commit,
                "date": date,
                "platform": {
                    "os": os_name,
                    "cpu": cpu,
                    "cores": cores,
                    "gpu": "",
                    "driver": "",
                    "jvm": entry.get("jdkVersion", ""),
                },
                "config": {
                    HARNESS_VERSION_KEY: JMH_BRIDGE_VERSION,
                    "harness": "jmh",
                    "jmh_version": entry.get("jmhVersion", ""),
                    "mode": entry.get("mode", ""),
                    "method": entry["benchmark"].split(".")[-1],
                },
                "parameters": entry.get("params") or {},
                "runs": runs,
                "stats": compute_stats(pooled),
            }
        )
    return results


def params_signature(result: dict) -> str:
    """Signature canonique des parametres d'un resultat.

    Deux resultats du meme benchmark ne se comparent que s'ils ont les memes
    parametres : un aller-retour vide et un transfert de lot partagent
    l'identifiant B-07 mais mesurent des choses differentes, et leurs parametres
    (`{}` d'un cote, `{elements: N}` de l'autre) les distinguent.
    """
    return json.dumps(result.get("parameters") or {}, sort_keys=True)


def params_slug(result: dict) -> str:
    """Slug des parametres, pour le nom de fichier ; vide si aucun parametre."""
    params = result.get("parameters") or {}
    if not params:
        return ""
    return slugify("-".join(f"{key}-{params[key]}" for key in sorted(params)))


def params_label(result: dict) -> str:
    """Libelle lisible des parametres, ou chaine vide si aucun."""
    params = result.get("parameters") or {}
    return ", ".join(f"{key}={params[key]}" for key in sorted(params))


def archive_result(root: pathlib.Path, result: dict) -> pathlib.Path:
    """Ecrit un resultat schema 2 sous la disposition d'ax-bench.

    `<root>/<benchmark>/<os-cpu>/<date>_<commit>[__<params>].json`. Le suffixe de
    parametres n'apparait que pour un benchmark parametre : sans lui, deux jeux
    de parametres du meme benchmark mesures au meme commit ecraseraient le meme
    fichier. Un resultat sans parametre garde le nom d'ax-bench.
    """
    platform = result["platform"]
    slug = slugify(f"{platform['os']}-{platform['cpu']}") or "inconnu"
    directory = root / result["benchmark"] / slug
    directory.mkdir(parents=True, exist_ok=True)
    stem = f"{slugify(result['date'])}_{slugify(result['commit'])[:12]}"
    suffix = params_slug(result)
    if suffix:
        stem = f"{stem}__{suffix}"
    path = directory / f"{stem}.json"
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return path


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
    groups: dict[tuple[str, str, str], list[tuple[pathlib.Path, dict]]] = {}
    for benchmark, platform, path in iter_results(root):
        try:
            result = load_result(path)
        except (OSError, json.JSONDecodeError):
            continue
        groups.setdefault((benchmark, platform, params_signature(result)), []).append(
            (path, result)
        )

    if not groups:
        print("bench summary : aucun resultat archive")
        return 0

    for (benchmark, platform, _sig), items in sorted(groups.items()):
        items.sort(key=lambda pair: pair[0].name)
        result = items[-1][1]
        label = f"{benchmark} / {platform}"
        extra = params_label(result)
        if extra:
            label = f"{label} / {extra}"
        stats = result.get("stats", {})
        print(
            f"{label} : p50={stats.get('p50_ns')} p95={stats.get('p95_ns')} "
            f"p99={stats.get('p99_ns')} ns "
            f"({len(items)} resultat(s), dernier {result.get('date')})"
        )
    return 0


def cmd_compare(root: pathlib.Path, benchmark: str | None, platform: str | None, every: bool) -> int:
    if not every and (not benchmark or not platform):
        print("bench compare : --benchmark et --platform requis (ou --all)", file=sys.stderr)
        return 2

    # Une serie se compare a elle-meme : meme benchmark, meme plateforme, memes
    # parametres. Deux facettes d'un B-xx (aller-retour vide, transfert de lot)
    # ne se comparent donc jamais l'une a l'autre.
    groups: dict[tuple[str, str, str], list[tuple[pathlib.Path, dict]]] = {}
    matched = False
    for bench, plat, path in iter_results(root):
        if not every and (bench, plat) != (benchmark, platform):
            continue
        matched = True
        try:
            result = load_result(path)
        except (OSError, json.JSONDecodeError) as error:
            print(f"{bench} / {plat} : illisible ({error})", file=sys.stderr)
            return 1
        groups.setdefault((bench, plat, params_signature(result)), []).append((path, result))

    if not every and not matched:
        print(f"bench compare : aucun resultat pour {benchmark} / {platform}", file=sys.stderr)
        return 2

    regressed = False
    compared = 0
    for (bench, plat, _sig), items in sorted(groups.items()):
        items.sort(key=lambda pair: pair[0].name)
        label = f"{bench} / {plat}"
        extra = params_label(items[-1][1])
        if extra:
            label = f"{label} / {extra}"
        if len(items) < 2:
            print(f"{label} : un seul resultat, rien a comparer")
            continue
        verdict, ratio = compare_p95(items[-1][1], items[-2][1])
        compared += 1
        if verdict == "no-baseline":
            print(f"{label} : pas de reference comparable")
        elif verdict == "ok":
            print(f"{label} : p95 {ratio * 100:+.1f} % vs precedent — ok")
        else:
            regressed = True
            print(f"{label} : p95 {ratio * 100:+.1f} % vs precedent — REGRESSION (> 20 %)")

    if regressed:
        print("bench compare : regression p95 superieure a 20 % (R-2251)", file=sys.stderr)
        return 1
    if compared == 0:
        print("bench compare : rien a comparer")
    return 0


def cmd_import_jmh(
    root: pathlib.Path,
    jmh_path: pathlib.Path,
    commit: str,
    date: str,
    cpu: str,
    os_name: str,
    cores: int,
) -> int:
    try:
        jmh = json.loads(pathlib.Path(jmh_path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"bench import-jmh : lecture impossible ({error})", file=sys.stderr)
        return 1
    try:
        results = jmh_to_results(
            jmh, commit=commit, date=date, cpu=cpu, os_name=os_name, cores=cores
        )
    except (KeyError, ValueError, TypeError) as error:
        print(f"bench import-jmh : conversion impossible ({error})", file=sys.stderr)
        return 1
    if not results:
        print("bench import-jmh : aucun benchmark converti", file=sys.stderr)
        return 1
    for result in results:
        problems = validate_result(result)
        if problems:
            print(
                f"bench import-jmh : resultat converti mal forme ({result['benchmark']})",
                file=sys.stderr,
            )
            for problem in problems:
                print(f"    - {problem}", file=sys.stderr)
            return 1
        path = archive_result(root, result)
        print(f"{result['benchmark']} : p95={result['stats']['p95_ns']} ns -> {path}")
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

    imp = sub.add_parser(
        "import-jmh",
        parents=[common],
        help="convertit une sortie JSON de JMH (-rf json) en resultats schema 2 archives",
    )
    imp.add_argument("jmh", type=pathlib.Path, help="fichier JSON produit par JMH")
    imp.add_argument("--commit", required=True, help="empreinte du commit mesure")
    imp.add_argument("--date", required=True, help="date ISO 8601 de la mesure")
    imp.add_argument("--cpu", required=True, help="modele de processeur (provenance)")
    imp.add_argument("--os", dest="os_name", required=True, help="systeme, p. ex. windows")
    imp.add_argument("--cores", type=int, required=True, help="nombre de coeurs logiques")

    args = parser.parse_args(argv)

    if args.command == "check":
        return cmd_check(args.root)
    if args.command == "summary":
        return cmd_summary(args.root)
    if args.command == "compare":
        return cmd_compare(args.root, args.benchmark, args.platform, args.all)
    if args.command == "import-jmh":
        return cmd_import_jmh(
            args.root, args.jmh, args.commit, args.date, args.cpu, args.os_name, args.cores
        )
    parser.error("commande inconnue")
    return 2


if __name__ == "__main__":
    sys.exit(main())
