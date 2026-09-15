#!/usr/bin/env python3
"""Tests de tools/bench/bench.py.

Sans dependance : `python tools/bench/test_bench.py` execute tout et sort en
erreur au premier echec.
"""
from __future__ import annotations

import json
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import bench  # noqa: E402


def result(p95: int, *, harness: int = 1, benchmark: str = "B-02") -> dict:
    """Un resultat schema 2 bien forme, dont on force le p95."""
    return {
        "schema": 2,
        "benchmark": benchmark,
        "commit": "abc123",
        "date": "2026-09-14T00:00:00Z",
        "platform": {
            "os": "windows",
            "cpu": "CPU test",
            "cores": 8,
            "gpu": "",
            "driver": "",
            "jvm": "",
        },
        "config": {"harness_version": harness},
        "parameters": {},
        "runs": [{"samples_ns": [p95]}],
        "stats": {
            "p50_ns": p95,
            "p95_ns": p95,
            "p99_ns": p95,
            "min_ns": p95,
            "max_ns": p95,
            "stddev_ns": 0.0,
        },
    }


def t_validate_accepts_wellformed():
    assert bench.validate_result(result(100)) == []


def t_validate_rejects_missing_cpu():
    r = result(100)
    r["platform"]["cpu"] = "   "
    problems = bench.validate_result(r)
    assert any("platform.cpu" in p for p in problems), problems


def t_validate_rejects_missing_harness_version():
    r = result(100)
    del r["config"]["harness_version"]
    problems = bench.validate_result(r)
    assert any("harness_version" in p for p in problems), problems


def t_validate_rejects_empty_run():
    r = result(100)
    r["runs"].append({"samples_ns": []})
    problems = bench.validate_result(r)
    assert any("sans echantillon" in p for p in problems), problems


def t_validate_rejects_wrong_schema():
    r = result(100)
    r["schema"] = 1
    problems = bench.validate_result(r)
    assert any("schema" in p for p in problems), problems


def t_compare_stable_is_ok():
    verdict, ratio = bench.compare_p95(result(100), result(100))
    assert verdict == "ok" and abs(ratio) < 1e-9, (verdict, ratio)


def t_compare_improvement_is_ok_negative():
    verdict, ratio = bench.compare_p95(result(100), result(200))
    assert verdict == "ok" and ratio < 0.0, (verdict, ratio)


def t_compare_regression_beyond_20_percent():
    verdict, ratio = bench.compare_p95(result(130), result(100))
    assert verdict == "regressed" and abs(ratio - 0.30) < 1e-9, (verdict, ratio)


def t_compare_exactly_20_percent_is_ok():
    # Le seuil est strict : « superieure a 20 % ».
    verdict, _ = bench.compare_p95(result(120), result(100))
    assert verdict == "ok", verdict


def t_compare_different_harness_is_no_baseline():
    verdict, _ = bench.compare_p95(result(1000, harness=999), result(100, harness=1))
    assert verdict == "no-baseline", verdict


def t_compare_zero_baseline_is_no_baseline():
    verdict, _ = bench.compare_p95(result(100), result(0))
    assert verdict == "no-baseline", verdict


def _write(root: pathlib.Path, platform: str, name: str, data: dict) -> None:
    directory = root / data["benchmark"] / platform
    directory.mkdir(parents=True, exist_ok=True)
    (directory / name).write_text(json.dumps(data), encoding="utf-8")


def t_check_passes_on_valid_archive():
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        _write(root, "windows-cpu-test", "2026-01-01_a.json", result(100))
        assert bench.cmd_check(root) == 0


def t_check_fails_on_invalid_archive():
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        bad = result(100)
        del bad["config"]["harness_version"]
        _write(root, "windows-cpu-test", "2026-01-01_a.json", bad)
        assert bench.cmd_check(root) == 1


def t_compare_all_flags_regression_over_archive():
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        _write(root, "windows-cpu-test", "2026-01-01_a.json", result(100))
        _write(root, "windows-cpu-test", "2026-06-01_b.json", result(150))
        # Le plus recent (150) contre le precedent (100) : +50 %, bloque.
        assert bench.cmd_compare(root, None, None, every=True) == 1


def t_compare_all_ok_when_stable():
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        _write(root, "windows-cpu-test", "2026-01-01_a.json", result(100))
        _write(root, "windows-cpu-test", "2026-06-01_b.json", result(105))
        assert bench.cmd_compare(root, None, None, every=True) == 0


def t_summary_on_empty_root_is_zero():
    with tempfile.TemporaryDirectory() as tmp:
        assert bench.cmd_summary(pathlib.Path(tmp)) == 0


# --- Pont JMH -> schema 2 -------------------------------------------------------


def jmh_entry(
    name: str = "dev.axion.bench.B07FfiRoundtrip.emptyRoundtrip",
    raw: list | None = None,
    unit: str = "ns/op",
    params: dict | None = None,
) -> dict:
    """Une entree JSON de JMH synthetique, minimale mais fidele."""
    entry = {
        "jmhVersion": "1.37",
        "benchmark": name,
        "mode": "avgt",
        "jdkVersion": "21.0.8",
        "primaryMetric": {
            "scoreUnit": unit,
            "rawData": raw if raw is not None else [[5.1, 5.9, 6.2]],
        },
    }
    if params:
        entry["params"] = params
    return entry


def t_slugify_matches_axbench():
    assert bench.slugify("Intel(R) Core") == "intel-r--core"


def t_compute_stats_nearest_rank():
    s = bench.compute_stats(list(range(1, 101)))
    assert (s["p50_ns"], s["p95_ns"], s["p99_ns"], s["min_ns"], s["max_ns"]) == (
        50,
        95,
        99,
        1,
        100,
    )


def t_compute_stats_population_stddev():
    s = bench.compute_stats([2, 4, 4, 4, 5, 5, 7, 9])
    assert abs(s["stddev_ns"] - 2.0) < 1e-9


def t_benchmark_id_from_jmh():
    assert (
        bench.benchmark_id_from_jmh("dev.axion.bench.B07FfiRoundtrip.emptyRoundtrip") == "B-07"
    )


def t_benchmark_id_rejects_non_bnn():
    try:
        bench.benchmark_id_from_jmh("dev.axion.bench.FooBench.run")
        assert False, "aurait du lever"
    except ValueError:
        pass


def t_unit_factor():
    assert bench.unit_factor("ns/op") == 1.0
    assert bench.unit_factor("us/op") == 1_000.0
    assert bench.unit_factor("ms/op") == 1_000_000.0


def t_unit_factor_rejects_unknown():
    try:
        bench.unit_factor("furlong/op")
        assert False, "aurait du lever"
    except ValueError:
        pass


def t_jmh_to_results_is_valid_schema2():
    results = bench.jmh_to_results(
        [jmh_entry()],
        commit="abc123",
        date="2026-09-15T00:00:00Z",
        cpu="CPU test",
        os_name="windows",
        cores=8,
    )
    assert len(results) == 1
    r = results[0]
    assert r["benchmark"] == "B-07"
    assert r["config"]["harness"] == "jmh"
    assert r["runs"][0]["samples_ns"] == [5, 6, 6]
    assert bench.validate_result(r) == []


def t_jmh_to_results_converts_units():
    results = bench.jmh_to_results(
        [jmh_entry(raw=[[1.0, 2.0]], unit="us/op")],
        commit="c",
        date="d",
        cpu="cpu",
        os_name="os",
        cores=1,
    )
    assert results[0]["runs"][0]["samples_ns"] == [1000, 2000]


def t_import_jmh_writes_valid_archive():
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp) / "results"
        jmh_file = pathlib.Path(tmp) / "jmh.json"
        jmh_file.write_text(json.dumps([jmh_entry()]), encoding="utf-8")
        rc = bench.cmd_import_jmh(
            root, jmh_file, "abc123", "2026-09-15T00:00:00Z", "CPU test", "windows", 8
        )
        assert rc == 0
        files = list(root.rglob("*.json"))
        assert len(files) == 1, files
        result = json.loads(files[0].read_text(encoding="utf-8"))
        assert result["benchmark"] == "B-07"
        assert bench.validate_result(result) == []
        assert bench.cmd_check(root) == 0


# --- Benchmarks parametres ------------------------------------------------------


def t_archive_result_suffixes_params():
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        parametre = result(100)
        parametre["parameters"] = {"elements": "64"}
        with_suffix = bench.archive_result(root, parametre)
        assert "__elements-64" in with_suffix.name, with_suffix.name

        sans = result(100)
        sans["parameters"] = {}
        without = bench.archive_result(root, sans)
        assert "__" not in without.name, without.name


def t_compare_separates_by_params():
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        a = result(100)
        a["parameters"] = {"elements": "64"}
        b = result(1000)
        b["parameters"] = {"elements": "256"}
        bench.archive_result(root, a)
        bench.archive_result(root, b)
        # Deux jeux de parametres, un resultat chacun : aucune comparaison, donc
        # aucune fausse regression malgre 100 vs 1000.
        assert bench.cmd_compare(root, None, None, every=True) == 0


def t_compare_within_same_params_detects_regression():
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        old = result(100)
        old["parameters"] = {"elements": "64"}
        old["date"] = "2026-01-01T00:00:00Z"
        new = result(150)
        new["parameters"] = {"elements": "64"}
        new["date"] = "2026-06-01T00:00:00Z"
        bench.archive_result(root, old)
        bench.archive_result(root, new)
        assert bench.cmd_compare(root, None, None, every=True) == 1


def t_import_jmh_facets_coexist():
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp) / "results"
        jmh_file = pathlib.Path(tmp) / "jmh.json"
        entries = [
            jmh_entry(name="dev.axion.bench.B07FfiRoundtrip.emptyRoundtrip"),
            jmh_entry(name="dev.axion.bench.B07FfiBatch.batchTransfer", params={"elements": "64"}),
        ]
        jmh_file.write_text(json.dumps(entries), encoding="utf-8")
        rc = bench.cmd_import_jmh(
            root, jmh_file, "abc", "2026-09-16T00:00:00Z", "cpu", "windows", 8
        )
        assert rc == 0
        # Les deux facettes partagent l'identifiant B-07 mais s'archivent a part.
        files = list(root.rglob("*.json"))
        assert len(files) == 2, files
        assert bench.cmd_check(root) == 0


def run() -> int:
    tests = [value for name, value in sorted(globals().items()) if name.startswith("t_")]
    failures = 0
    for test in tests:
        try:
            test()
            print(f"ok   {test.__name__}")
        except AssertionError as error:
            failures += 1
            print(f"FAIL {test.__name__} : {error}")
    print(f"\n{len(tests) - failures}/{len(tests)} tests passes")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(run())
