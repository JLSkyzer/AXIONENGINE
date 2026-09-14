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
