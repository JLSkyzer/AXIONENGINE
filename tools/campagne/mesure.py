#!/usr/bin/env python3
"""CAP-53 : la mesure du parallélisme et des cœurs côté serveur, par paliers de véhicules.

Un serveur dédié (`runServer`, sous `AXION_MESURE=<paliers>`), sans joueur, pose des véhicules d'essai, les conduit en
cercle, et relève à chaque palier (`MesurePerformance`, contenu de développement) : le temps CPU du thread du serveur et
du processus, le pas de la simulation native et ses étapes, le palier du gouverneur, la vitesse des véhicules. La
campagne relève en plus, entre les deux marques de chaque fenêtre, le temps CPU de **chaque thread du processus** avec son
nom (Windows) : c'est ce qui distingue le pool natif d'AXION (`axion-job-*`) du reste.

Les threads se relèvent du dehors, une à trois secondes après chaque marque : le serveur garde le même palier encore
200 ticks après la fenêtre, pour que le relevé de fin ne tombe ni dans le palier suivant ni dans l'arrêt.

Les chiffres ne valent que pour la machine et la date du rapport, et seulement si rien d'autre ne tourne : prendre le
verrou des lancements de Minecraft avant, là où il y en a un.

Usage : python tools/campagne/mesure.py [--paliers 0,1,8,32,64,128]   (JAVA_HOME doit désigner un JDK 17)
Rapport : .runs/campagne/mesure/rapport.md et rapport.json
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import time

from prediction import GRADLE, JOURNAL_SERVEUR, RACINE, SERVEUR_PRET_S, arreter

SORTIES = RACINE / ".runs/campagne/mesure"
RELEVE_SERVEUR = RACINE / ".runs/server/mesure/rapport.jsonl"
DUREE_MAX_S = 1800

# Le temps CPU et le nom de chaque thread d'un processus ; Windows seulement.
THREADS_PS = r"""
Add-Type -Namespace Ax -Name K -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern System.IntPtr OpenThread(uint a, bool b, uint c);
[DllImport("kernel32.dll")] public static extern bool CloseHandle(System.IntPtr h);
[DllImport("kernel32.dll")] public static extern int GetThreadDescription(System.IntPtr h, out System.IntPtr d);
'@
$p = Get-Process -Id PIDICI
foreach ($t in $p.Threads) {
  $nom = ''
  $h = [Ax.K]::OpenThread(0x0800, $false, [uint32]$t.Id)
  if ($h -ne [IntPtr]::Zero) {
    $d = [IntPtr]::Zero
    if ([Ax.K]::GetThreadDescription($h, [ref]$d) -ge 0 -and $d -ne [IntPtr]::Zero) {
      $nom = [Runtime.InteropServices.Marshal]::PtrToStringUni($d)
    }
    [void][Ax.K]::CloseHandle($h)
  }
  try { $c = $t.TotalProcessorTime.TotalSeconds } catch { $c = 0 }
  "{0}`t{1}`t{2}" -f $t.Id, $c.ToString([Globalization.CultureInfo]::InvariantCulture), $nom
}
"""


def threads(pid: int) -> dict[int, tuple[float, str]]:
    if os.name != "nt":
        return {}
    sortie = subprocess.run(["powershell", "-NoProfile", "-Command", THREADS_PS.replace("PIDICI", str(pid))],
                            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60).stdout
    releve = {}
    for ligne in sortie.splitlines():
        champs = ligne.split("\t")
        if len(champs) >= 2 and champs[0].isdigit():
            releve[int(champs[0])] = (float(champs[1]), champs[2] if len(champs) > 2 else "")
    return releve


def groupes(debut: dict, fin: dict) -> dict[str, float]:
    """Le temps CPU de la fenêtre, par famille de threads ; un thread né pendant elle compte entier."""
    total: dict[str, float] = {}
    for tid, (temps, nom) in fin.items():
        passe = temps - debut.get(tid, (0.0, ""))[0]
        famille = re.sub(r"[-# ]*\d+$", "", nom).strip() or "(sans nom)"
        total[famille] = total.get(famille, 0.0) + max(0.0, passe)
    return total


def machine() -> str:
    if os.name != "nt":
        return "machine non relevée"
    commande = ("$c = Get-CimInstance Win32_Processor | Select-Object -First 1; "
                "$m = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory; "
                "'{0} ; {1} coeurs, {2} threads ; {3:N0} Go' -f $c.Name.Trim(), $c.NumberOfCores,"
                " $c.NumberOfLogicalProcessors, ($m / 1GB)")
    return subprocess.run(["powershell", "-NoProfile", "-Command", commande], capture_output=True, text=True,
                          errors="replace", timeout=60).stdout.strip()


def autres_java() -> int:
    if os.name != "nt":
        return -1
    sortie = subprocess.run(["tasklist", "/FI", "IMAGENAME eq java.exe", "/NH"], capture_output=True, text=True,
                            errors="replace").stdout
    return sum(1 for ligne in sortie.splitlines() if ligne.lower().startswith("java.exe"))


def campagne(paliers: str) -> list[dict]:
    SORTIES.mkdir(parents=True, exist_ok=True)
    for fichier in (JOURNAL_SERVEUR, RELEVE_SERVEUR):
        fichier.unlink(missing_ok=True)
    for fichier in ("axion-common.toml", "axion-client.toml", "axion-server.toml"):
        (RACINE / ".runs/server/config" / fichier).unlink(missing_ok=True)
    shutil.rmtree(RACINE / ".runs/server/mesure-monde", ignore_errors=True)
    environnement = dict(os.environ, AXION_MESURE=paliers)
    for autre in ("AXION_ESSAI_PREDICTION", "AXION_ESSAI_PORTEUR"):
        environnement.pop(autre, None)
    journal = SORTIES / "serveur.log"
    familles: dict[int, dict[str, float]] = {}
    with open(journal, "w", encoding="utf-8") as sortie:
        serveur = subprocess.Popen([str(GRADLE), ":axion-mod:runServer"], cwd=RACINE, env=environnement,
                                   stdout=sortie, stderr=subprocess.STDOUT)
    try:
        depart = time.monotonic()
        lu = 0
        debuts: dict[int, dict] = {}
        pids: dict[int, int] = {}
        while True:
            texte = JOURNAL_SERVEUR.read_text("utf-8", "replace") if JOURNAL_SERVEUR.exists() else ""
            nouveau, lu = texte[lu:], len(texte)
            for m in re.finditer(r"\[mesure\] (debut|fin) (\d+)(?: pid (\d+))?", nouveau):
                palier = int(m.group(2))
                if m.group(1) == "debut":
                    pids[palier] = int(m.group(3))
                    debuts[palier] = threads(pids[palier])
                    print(f"palier {palier} : fenêtre ouverte", flush=True)
                elif palier in debuts:
                    familles[palier] = groupes(debuts[palier], threads(pids[palier]))
                    print(f"palier {palier} : fenêtre relevée", flush=True)
            if "[mesure] terminee" in texte or serveur.poll() is not None:
                break
            if "Done (" not in texte and time.monotonic() - depart > SERVEUR_PRET_S:
                raise RuntimeError(f"serveur pas prêt (voir {journal})")
            if time.monotonic() - depart > DUREE_MAX_S:
                raise RuntimeError("mesure trop longue")
            time.sleep(0.5)
        try:
            serveur.wait(timeout=120)
        except subprocess.TimeoutExpired:
            pass
    finally:
        arreter(serveur)
    if not RELEVE_SERVEUR.exists():
        raise RuntimeError(f"aucun relevé du serveur (voir {journal})")
    releves = [json.loads(ligne) for ligne in RELEVE_SERVEUR.read_text("utf-8").splitlines() if ligne.strip()]
    for releve in releves:
        releve["threads_cpu_s"] = familles.get(releve["vehicules"], {})
    return releves


def rapport(releves: list[dict], materiel: str, avant: int) -> str:
    lignes = [f"# Mesure du serveur (CAP-53), {datetime.datetime.now():%Y-%m-%d %H:%M}", "",
              f"Machine : {materiel}. Processus `java.exe` présents avant le lancement : {avant}.",
              "Serveur dédié de développement, sans joueur, véhicules d'essai conduits en cercle ; fenêtre de 600 ticks"
              " après 600 d'établissement (le temps que la compilation Java à la volée se calme). Valeurs de cette machine seulement.", "",
              "| véhicules | TPS | CPU thread serveur (ms/tick) | pas natif moyen / p95 (ms) | contacts (ms) |"
              " intégration (ms) | cœurs utilisés (processus) | part du thread serveur | gouverneur | vitesse (m/s) |",
              "|---|---|---|---|---|---|---|---|---|---|"]
    for r in releves:
        lignes.append("| {v} | {tps:.1f} | {srv:.2f} | {pas:.2f} / {p95:.2f} | {c:.2f} | {i:.2f} | {coeurs:.2f} |"
                      " {part:.0%} | {g} | {vit:.1f} |".format(
                          v=r["vehicules"], tps=r["ticks"] / r["murale_s"],
                          srv=1000 * r["cpu_thread_serveur_s"] / r["ticks"], pas=r["pas_ms_moyen"],
                          p95=r["pas_ms_p95"], c=r["contacts_ms"], i=r["integration_ms"],
                          coeurs=r["cpu_processus_s"] / r["murale_s"],
                          part=r["cpu_thread_serveur_s"] / max(r["cpu_processus_s"], 1e-9),
                          g=r["palier_gouverneur"], vit=r["vitesse_moyenne_m_s"]))
    lignes += ["", "## Temps CPU par famille de threads, en secondes sur la fenêtre", ""]
    for r in releves:
        fortes = sorted(r["threads_cpu_s"].items(), key=lambda x: -x[1])[:8]
        axion = sum(v for k, v in r["threads_cpu_s"].items() if k.startswith("axion"))
        lignes.append(f"- **{r['vehicules']} véhicules** (fenêtre de {r['murale_s']:.1f} s) : "
                      + " ; ".join(f"`{k}` {v:.2f}" for k, v in fortes) + f" ; pool natif d'AXION {axion:.3f}")
    return "\n".join(lignes) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--paliers", default="0,1,8,32,64,128", help="nombres de véhicules, séparés par des virgules")
    arguments = parser.parse_args()
    materiel = machine()
    avant = autres_java()
    try:
        releves = campagne(arguments.paliers)
    except (RuntimeError, subprocess.TimeoutExpired) as faute:
        print(f"ÉCHEC : {faute}")
        return 1
    texte = rapport(releves, materiel, avant)
    (SORTIES / "rapport.md").write_text(texte, encoding="utf-8")
    (SORTIES / "rapport.json").write_text(json.dumps({"machine": materiel, "releves": releves}, ensure_ascii=False,
                                                     indent=1), encoding="utf-8")
    print(texte)
    attendus = len(arguments.paliers.split(","))
    return 0 if len(releves) == attendus else 1


if __name__ == "__main__":
    sys.exit(main())
