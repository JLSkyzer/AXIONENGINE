"""Le retard réel d'un joueur porté, face à celui que le serveur lui prête (ADR-184).

Lit les relevés « [retard] » de l'essai du portage (`portage.py --retard`) : le serveur y dit, à chaque tick de
conduite, la position reçue du porté, le retard choisi, l'aller-retour mesuré et la pose gardée du véhicule ; le client,
la position qu'il envoie et sa place réelle dans le corps. Les deux se croisent par la position, exacte au bit près.

Le retard réel d'une position est celui, fractionnaire, de la pose gardée qui lui donne au serveur la place que le
client lui connaît. On y compare le retard choisi et quelques estimations de rechange, en blocs d'erreur sur la place.

Usage : python tools/campagne/retard.py [journal-serveur] [journal-client]
"""

import math
import re
import statistics
import sys
from pathlib import Path

SORTIES = Path(__file__).resolve().parents[2] / ".runs/campagne/portage"
# `net.interpolation_delay_ms` par défaut, en ticks : l'estimation du serveur est l'aller-retour plus ce délai.
INTERPOLATION = 2.0
# Une position se juge quand le véhicule avance : à l'arrêt, tout retard donne la même place.
VITESSE_MIN = 0.1
RETARD_MAX = 20.0
PAS = 0.02

NOMBRE = r"(-?[\d.]+(?:E-?\d+)?)"
RECU = re.compile(r"\[retard\] recu (\d+) " + " ".join([NOMBRE] * 3))
CHOISI = re.compile(r"\[retard\] choisi (\d+) (-?\d+) " + " ".join([NOMBRE] * 8))
ENVOYE = re.compile(r"\[retard\] envoye " + " ".join([NOMBRE] * 6))


def lire(serveur: Path, client: Path):
    recu, choisi, envoye = {}, {}, {}
    for ligne in serveur.read_text("utf-8", "replace").splitlines():
        if m := RECU.search(ligne):
            recu[int(m[1])] = tuple(float(v) for v in m.groups()[1:])
        elif m := CHOISI.search(ligne):
            valeurs = [float(v) for v in m.groups()[2:]]
            choisi[int(m[1])] = (int(m[2]), valeurs[0], valeurs[1:])
    for ligne in client.read_text("utf-8", "replace").splitlines():
        if m := ENVOYE.search(ligne):
            valeurs = tuple(float(v) for v in m.groups())
            envoye[valeurs[:3]] = valeurs[3:]
    return recu, choisi, envoye


def tourner(q, v):
    x, y, z, w = q
    tx = 2 * (y * v[2] - z * v[1])
    ty = 2 * (z * v[0] - x * v[2])
    tz = 2 * (x * v[1] - y * v[0])
    return (v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx))


def place(pose, point):
    """La place d'un point dans le repère d'une pose : la rotation inverse de l'écart (BodyFrame.toLocal)."""
    ecart = tuple(point[i] - pose[i] for i in range(3))
    x, y, z, w = pose[3:7]
    return tourner((-x, -y, -z, w), ecart)


def pose_au(choisi, tick, retard):
    """La pose gardée à un retard fractionnaire avant `tick`, ou None si elle n'a pas été relevée."""
    bas = math.floor(retard)
    part = retard - bas
    a = choisi.get(tick - bas)
    b = choisi.get(tick - bas - 1) if part > 0 else a
    if a is None or b is None:
        return None
    pa, pb = a[2], b[2]
    position = [pa[i] + part * (pb[i] - pa[i]) for i in range(3)]
    signe = 1.0 if sum(pa[i] * pb[i] for i in range(3, 7)) >= 0 else -1.0
    quat = [pa[i] + part * (signe * pb[i] - pa[i]) for i in range(3, 7)]
    norme = math.sqrt(sum(q * q for q in quat))
    return position + [q / norme for q in quat]


def erreur(choisi, tick, retard, point, vraie):
    pose = pose_au(choisi, tick, max(0.0, min(RETARD_MAX, retard)))
    return None if pose is None else math.dist(place(pose, point), vraie)


def analyser(recu, choisi, envoye):
    releves = []
    for tick in sorted(recu):
        point = recu[tick]
        if tick not in choisi or point not in envoye or tick - 1 not in choisi:
            continue
        vitesse = math.dist(choisi[tick][2][:3], choisi[tick - 1][2][:3])
        if vitesse < VITESSE_MIN:
            continue
        vraie = envoye[point]
        meilleur = None
        for pas in range(int(RETARD_MAX / PAS) + 1):
            e = erreur(choisi, tick, pas * PAS, point, vraie)
            if e is not None and (meilleur is None or e < meilleur[0]):
                meilleur = (e, pas * PAS)
        if meilleur is None:
            continue
        releves.append({"tick": tick, "vrai": meilleur[1], "reste": meilleur[0], "choisi": choisi[tick][0],
                        "aller_retour": choisi[tick][1], "vitesse": vitesse, "point": point, "vraie": vraie})
    return releves


def quantile(valeurs, q):
    triees = sorted(valeurs)
    return triees[min(len(triees) - 1, int(q * len(triees)))]


def estimations(releves):
    """Des façons de prêter un retard : chacune rend la liste des retards, relevé par relevé."""
    decalage = statistics.median(r["vrai"] - r["aller_retour"] - INTERPOLATION for r in releves)
    lisses = {}
    for gain in (0.1, 0.03):
        courant, suite = None, []
        for r in releves:
            courant = r["aller_retour"] if courant is None else courant + gain * (r["aller_retour"] - courant)
            suite.append(courant + INTERPOLATION + decalage)
        lisses[gain] = suite
    return {
        "choisi par le serveur": [r["choisi"] for r in releves],
        "aller-retour, sans arrondi": [r["aller_retour"] + INTERPOLATION + decalage for r in releves],
        "aller-retour lissé, gain 0,1": lisses[0.1],
        "aller-retour lissé, gain 0,03": lisses[0.03],
        "constant (médiane du vrai)": [statistics.median(r["vrai"] for r in releves)] * len(releves),
    }


def continuite(choisi, releves, poids, marche=False):
    """L'erreur d'un serveur qui prêterait, à chaque position, le retard qui garde la place la plus proche de la
    précédente, rappelé vers l'aller-retour lissé avec ce poids ; avec `marche`, le même relevé où le porté va et vient le
    long du véhicule (0,2 bloc par tick, sur 2 blocs), ses positions refaites avec le retard réel."""
    avant, lisse, erreurs = None, None, []
    for r in releves:
        tick, point, vraie = r["tick"], r["point"], r["vraie"]
        if marche:
            pas = -1.0 + abs(((tick * 0.2) % 4.0) - 2.0)
            vraie = (vraie[0], vraie[1], vraie[2] + pas)
            pose = pose_au(choisi, tick, r["vrai"])
            tourne = tourner(pose[3:7], vraie)
            point = tuple(pose[i] + tourne[i] for i in range(3))
        estime = r["aller_retour"] + INTERPOLATION - 0.5
        lisse = estime if lisse is None else lisse + 0.05 * (estime - lisse)
        meilleur = None
        for n in range(-150, 151):
            retard = lisse + n * PAS
            pose = pose_au(choisi, tick, max(0.0, min(RETARD_MAX, retard)))
            if pose is None:
                continue
            p = place(pose, point)
            cout = poids * (retard - lisse) ** 2 + (0.0 if avant is None else math.dist(p, avant) ** 2)
            if meilleur is None or cout < meilleur[0]:
                meilleur = (cout, p)
        if meilleur is None:
            continue
        avant = meilleur[1]
        erreurs.append(math.dist(avant, vraie))
    return erreurs


def main() -> int:
    arguments = [Path(a) for a in sys.argv[1:]]
    serveur = arguments[0] if arguments else SORTIES / "journal-serveur-100.log"
    client = arguments[1] if len(arguments) > 1 else SORTIES / "client-100.log"
    recu, choisi, envoye = lire(serveur, client)
    releves = analyser(recu, choisi, envoye)
    print(f"reçues {len(recu)}, envoyées {len(envoye)}, croisées en mouvement {len(releves)}")
    if not releves:
        print("rien à comparer")
        return 1
    choisi_poses = choisi
    for nom, cle in (("retard réel", "vrai"), ("retard choisi", "choisi"), ("aller-retour", "aller_retour")):
        serie = [r[cle] for r in releves]
        sauts = [abs(b - a) for a, b in zip(serie, serie[1:])]
        print(f"{nom:>14} : moyenne {statistics.fmean(serie):.2f}, écart-type {statistics.pstdev(serie):.2f}, de"
              f" {min(serie):.2f} à {max(serie):.2f}, saut le plus grand {max(sauts, default=0):.2f} tick")
    restes = [r["reste"] for r in releves]
    print(f"reste de l'ajustement : médiane {statistics.median(restes):.3f}, au plus {max(restes):.3f} bloc")
    print("erreur sur la place, en blocs (moyenne / 95 % / au plus) ; variation = écart entre la plus petite et la plus"
          " grande erreur signée le long du corps")
    for nom, serie in estimations(releves).items():
        erreurs, le_long = [], []
        for r, retard in zip(releves, serie):
            pose = pose_au(choisi_poses, r["tick"], max(0.0, min(RETARD_MAX, retard)))
            if pose is None:
                continue
            p = place(pose, r["point"])
            erreurs.append(math.dist(p, r["vraie"]))
            le_long.append(p[2] - r["vraie"][2])
        print(f"{nom:>32} : {statistics.fmean(erreurs):.3f} / {quantile(erreurs, 0.95):.3f} / {max(erreurs):.3f} ;"
              f" variation {max(le_long) - min(le_long):.3f}")
    print("garder la place constante, sans nouveau message (95 % / au plus), porté immobile puis qui marche :")
    for poids in (0.001, 0.01, 0.05):
        immobile, marchant = (continuite(choisi_poses, releves, poids, marche) for marche in (False, True))
        print(f"{'rappel ' + str(poids):>32} : {quantile(immobile, 0.95):.3f} / {max(immobile):.3f} ; marche"
              f" {quantile(marchant, 0.95):.3f} / {max(marchant):.3f}")
    print("tick  réel  choisi  aller-retour  vitesse")
    for r in releves[::10]:
        print(f"{r['tick']:>5} {r['vrai']:5.2f} {r['choisi']:6d} {r['aller_retour']:12.2f} {r['vitesse']:8.3f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
