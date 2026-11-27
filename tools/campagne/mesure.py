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

Avec --client, un vrai client (`runEssaiClientMesure`, `MesureClient`) se connecte, se tient en spectateur devant la
grille et relève la durée de chaque image sur les mêmes fenêtres ; le serveur lui envoie des véhicules jusqu'à 288 blocs
(portée de diffusion des entités à 300 %, distance de vue de 20 chunks), réglés le temps de la campagne.

Avec --portes K (CAP-14), chaque véhicule d'un palier unique porte K porte-armures ; avec --sol en plus, les mêmes sont
au sol à l'écart de toute assembly : la différence des deux est le coût du portage au serveur.

Usage : python tools/campagne/mesure.py [--paliers 0,1,8,32,64,128] [--client] [--portes K [--sol]]
(JAVA_HOME : un JDK 17)
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
CLIENT = RACINE / ".runs/essai-mesure"
RELEVE_CLIENT = CLIENT / "mesure-client.jsonl"
PROPRIETES = RACINE / ".runs/server/server.properties"
REGLAGES_CLIENT = {"entity-broadcast-range-percentage": "300", "view-distance": "20"}
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


def regler_serveur(valeurs: dict[str, str]) -> str | None:
    """Pose des réglages dans server.properties ; rend le contenu d'avant, None s'il n'existait pas."""
    avant = PROPRIETES.read_text("utf-8") if PROPRIETES.exists() else None
    lignes = [l for l in (avant or "").splitlines() if l.split("=", 1)[0] not in valeurs]
    lignes += [f"{cle}={valeur}" for cle, valeur in valeurs.items()]
    PROPRIETES.parent.mkdir(parents=True, exist_ok=True)
    PROPRIETES.write_text("\n".join(lignes) + "\n", encoding="utf-8")
    return avant


def rendre_serveur(avant: str | None) -> None:
    if avant is None:
        PROPRIETES.unlink(missing_ok=True)
    else:
        PROPRIETES.write_text(avant, encoding="utf-8")


def campagne(paliers: str, avec_client: bool = False, profil: bool = False, portes: int = 0,
             au_sol: bool = False) -> list[dict]:
    SORTIES.mkdir(parents=True, exist_ok=True)
    for fichier in (JOURNAL_SERVEUR, RELEVE_SERVEUR, RELEVE_CLIENT):
        fichier.unlink(missing_ok=True)
    for fichier in ("axion-common.toml", "axion-client.toml", "axion-server.toml"):
        (RACINE / ".runs/server/config" / fichier).unlink(missing_ok=True)
    shutil.rmtree(RACINE / ".runs/server/mesure-monde", ignore_errors=True)
    environnement = dict(os.environ, AXION_MESURE=paliers)
    for autre in ("AXION_ESSAI_PREDICTION", "AXION_ESSAI_PORTEUR"):
        environnement.pop(autre, None)
    journal = SORTIES / "serveur.log"
    environnement.pop("AXION_MESURE_CLIENT", None)
    environnement.pop("AXION_MESURE_PORTES", None)
    environnement.pop("AXION_MESURE_PORTES_SOL", None)
    environnement.pop("AXION_MESURE_PROFIL", None)
    if profil:
        environnement["AXION_MESURE_PROFIL"] = "1"
        # JFR n'ouvre pas son fichier dans un dossier absent.
        RELEVE_SERVEUR.parent.mkdir(parents=True, exist_ok=True)
    if portes:
        environnement["AXION_MESURE_PORTES"] = str(portes)
        if au_sol:
            environnement["AXION_MESURE_PORTES_SOL"] = "1"
    proprietes_avant = None
    client = None
    sortie_client = None
    if avec_client:
        environnement["AXION_MESURE_CLIENT"] = "1"
        proprietes_avant = regler_serveur(REGLAGES_CLIENT)
        (CLIENT / "config").mkdir(parents=True, exist_ok=True)
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
            if avec_client and client is None and "Done (" in texte:
                sortie_client = open(SORTIES / "client.log", "w", encoding="utf-8")
                commande = [str(GRADLE), ":axion-mod:runEssaiClientMesure"] + (["-PmesureProfil"] if profil else [])
                client = subprocess.Popen(commande, cwd=RACINE,
                                          stdout=sortie_client, stderr=subprocess.STDOUT)
            if client is not None and client.poll() is not None and "[mesure] terminee" not in texte:
                raise RuntimeError(f"client sorti avant la fin (voir {SORTIES / 'client.log'})")
            if "[mesure] terminee" in texte or serveur.poll() is not None:
                break
            if "Done (" not in texte and time.monotonic() - depart > SERVEUR_PRET_S:
                raise RuntimeError(f"serveur pas prêt (voir {journal})")
            if time.monotonic() - depart > DUREE_MAX_S:
                raise RuntimeError("mesure trop longue")
            time.sleep(0.5)
        try:
            if client is not None:
                client.wait(timeout=180)
            serveur.wait(timeout=120)
        except subprocess.TimeoutExpired:
            pass
    finally:
        if client is not None:
            arreter(client)
        if sortie_client is not None:
            sortie_client.close()
        arreter(serveur)
        if avec_client:
            rendre_serveur(proprietes_avant)
    if not RELEVE_SERVEUR.exists():
        raise RuntimeError(f"aucun relevé du serveur (voir {journal})")
    releves = [json.loads(ligne) for ligne in RELEVE_SERVEUR.read_text("utf-8").splitlines() if ligne.strip()]
    images = {}
    if avec_client:
        if not RELEVE_CLIENT.exists():
            raise RuntimeError(f"aucun relevé du client (voir {SORTIES / 'client.log'})")
        for ligne in RELEVE_CLIENT.read_text("utf-8").splitlines():
            if ligne.strip():
                releve = json.loads(ligne)
                images[releve["vehicules"]] = releve
    for releve in releves:
        releve["threads_cpu_s"] = familles.get(releve["vehicules"], {})
        if avec_client:
            releve["client"] = images.get(releve["vehicules"])
    return releves


def rapport(releves: list[dict], materiel: str, avant: int) -> str:
    lignes = [f"# Mesure du serveur (CAP-53), {datetime.datetime.now():%Y-%m-%d %H:%M}", "",
              f"Machine : {materiel}. Processus `java.exe` présents avant le lancement : {avant}.",
              "Serveur dédié de développement"
              + (", un client spectateur connecté" if any(r.get("client") for r in releves) else ", sans joueur")
              + ", véhicules d'essai conduits en cercle ; fenêtre de 600 ticks"
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
    for r in releves:
        if r.get("portes_par_vehicule"):
            lignes.append(f"- porte-armures : {r['portes_par_vehicule']} par véhicule, "
                          + ("au sol, à l'écart" if r["au_sol"] else "lâchés dessus")
                          + f" ; portés en moyenne sur la fenêtre : {r['portes_moyens']}")
    clients = [r["client"] for r in releves if r.get("client")]
    if clients:
        c0 = clients[0]
        lignes += ["", "## Le client", "",
                   f"Un client de développement, spectateur en retrait de la grille, fenêtre de {c0['largeur']}×"
                   f"{c0['hauteur']}, synchronisation verticale coupée, fréquence illimitée, distance de rendu de 20"
                   f" chunks, interface cachée, sur la même machine que le serveur. Carte : {c0['carte']}.", "",
                   "| véhicules | connus du client | images/s | image moyenne (ms) | p50 | p95 | p99 | max | tas (Mo) |"
                   " tick moyen / p95 (ms) | images > 15 ms (dont avec un tick) | images avec un tick |",
                   "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for c in clients:
            lignes.append("| {v} | {k} | {fps:.0f} | {m:.2f} | {p50:.2f} | {p95:.2f} | {p99:.2f} | {mx:.1f} |"
                          " {tas} | {tm:.2f} / {tp:.2f} | {l} ({lt}) | {at} |".format(
                                            tm=c["tick_ms_moyen"], tp=c["tick_ms_p95"], l=c["lentes"],
                                            lt=c["lentes_avec_tick"], at=c["images_avec_tick"],
                                            v=c["vehicules"], k=c["connues"], fps=c["images"] / c["secondes"],
                                            m=c["image_ms_moyenne"], p50=c["image_ms_p50"], p95=c["image_ms_p95"],
                                            p99=c["image_ms_p99"], mx=c["image_ms_max"], tas=c["tas_mo"]))
    return "\n".join(lignes) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--paliers", default="0,1,8,32,64,128", help="nombres de véhicules, séparés par des virgules")
    parser.add_argument("--client", action="store_true", help="un vrai client relève la durée de ses images")
    parser.add_argument("--profil", action="store_true",
                        help="enregistrer des profils JFR du serveur (.runs/server/mesure/profil.jfr) et, avec"
                             " --client, du client (.runs/essai-mesure/profil.jfr)")
    parser.add_argument("--portes", type=int, default=0, help="porte-armures par véhicule (CAP-14), un seul palier")
    parser.add_argument("--sol", action="store_true", help="avec --portes : les mêmes au sol, à l'écart, pour témoin")
    arguments = parser.parse_args()
    if arguments.portes and "," in arguments.paliers:
        parser.error("--portes va avec un seul palier")
    materiel = machine()
    avant = autres_java()
    try:
        releves = campagne(arguments.paliers, arguments.client, arguments.profil, arguments.portes, arguments.sol)
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
