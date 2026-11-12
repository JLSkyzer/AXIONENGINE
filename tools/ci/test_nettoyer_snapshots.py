#!/usr/bin/env python3
"""Tests du tri de tools/ci/nettoyer_snapshots.py : quelles copies d'un SNAPSHOT partent, lesquelles restent."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from nettoyer_snapshots import a_supprimer  # noqa: E402


def copie(artefact: str, horodatage: str, numero: int) -> list[str]:
    """Les fichiers d'une copie publiée : le JAR, ses sources, sa javadoc, son POM et leurs sommes de contrôle."""
    base = f"{artefact}-1.0.0-{horodatage}-{numero}"
    noms = [base + ".jar", base + "-sources.jar", base + "-javadoc.jar", base + ".pom"]
    return noms + [nom + ext for nom in noms for ext in (".md5", ".sha1", ".sha256", ".sha512")]


class TriDesCopies(unittest.TestCase):

    def test_garde_les_plus_recentes_et_rend_les_autres_entieres(self):
        noms = ["maven-metadata.xml", "maven-metadata.xml.sha1"]
        for jour in range(1, 13):
            noms += copie("axion-api", f"202611{jour:02d}.120000", jour)
        partants = a_supprimer(noms, "axion-api", "1.0.0-SNAPSHOT", 10)
        # Les deux plus anciennes copies, chacune avec ses 20 fichiers.
        self.assertEqual(sorted(copie("axion-api", "20261101.120000", 1) + copie("axion-api", "20261102.120000", 2)),
                         partants)
        self.assertNotIn("maven-metadata.xml", partants)

    def test_rien_ne_part_sous_le_seuil(self):
        noms = copie("axion", "20261111.235959", 1)
        self.assertEqual([], a_supprimer(noms, "axion", "1.0.0-SNAPSHOT", 10))

    def test_le_numero_departage_deux_copies_du_meme_instant(self):
        noms = copie("axion", "20261112.000000", 1) + copie("axion", "20261112.000000", 2)
        self.assertEqual(sorted(copie("axion", "20261112.000000", 1)), a_supprimer(noms, "axion", "1.0.0-SNAPSHOT", 1))

    def test_un_autre_artefact_du_meme_dossier_n_est_jamais_rendu(self):
        # « axion » ne doit pas emporter les fichiers d'« axion-api » ou d'« axion-addon-template ».
        noms = copie("axion-api", "20261101.120000", 1) + copie("axion", "20261102.120000", 2)
        self.assertEqual([], a_supprimer(noms, "axion", "1.0.0-SNAPSHOT", 1))
        self.assertEqual([], [nom for nom in a_supprimer(noms, "axion", "1.0.0-SNAPSHOT", 0)
                              if nom.startswith("axion-api")])

    def test_un_zip_et_ses_sommes_partent_ensemble(self):
        def fichiers(numero: int) -> list[str]:
            base = f"axion-addon-template-1.0.0-2026111{numero}.100000-{numero}"
            return [base + ".zip", base + ".zip.sha1", base + ".pom"]

        self.assertEqual(sorted(fichiers(1)),
                         a_supprimer(fichiers(1) + fichiers(2), "axion-addon-template", "1.0.0-SNAPSHOT", 1))


if __name__ == "__main__":
    unittest.main()
