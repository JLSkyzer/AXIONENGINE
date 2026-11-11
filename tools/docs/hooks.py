"""Greffon MkDocs du site de la documentation (ADR-101).

Les guides vivent dans docs/guides et pointent, par des liens relatifs, vers le reste du dépôt : les décisions, les
schémas, les outils. Ces liens marchent sur GitHub ; le site, lui, ne publie que les guides. Chaque lien relatif qui
sort de docs/guides vers un fichier existant du dépôt est donc réécrit vers ce fichier sur GitHub. Un lien qui ne
mène à rien reste tel quel : le mode strict de MkDocs le signale, et il se corrige dans le guide.

Dans une ligne de tableau, une portée de code qui contient une barre échappée (« \\| », comme le générateur de
CONFIGURATION.md en écrit) devient du HTML où la barre est une entité : GitHub et le site l'affichent alors de même.
"""

import re
from pathlib import Path
from urllib.parse import unquote

RACINE = Path(__file__).resolve().parents[2]
DEPOT = "https://github.com/JLSkyzer/AXIONENGINE/blob/master/"

# Un lien Markdown : [texte](cible), la cible sans espace ; ni adresse complète, ni ancre seule.
LIEN = re.compile(r"\]\((?!https?://|mailto:|#)([^)\s]+)\)")

# Une portée de code qui contient une barre échappée. GitHub y lit « \| » comme une barre qui ne coupe pas la
# cellule ; Python-Markdown y laisse la barre oblique inverse.
CODE_A_BARRE = re.compile(r"`([^`]*\\\|[^`]*)`")


def _code_html(code: re.Match) -> str:
    texte = code.group(1).replace("&", "&amp;").replace("<", "&lt;").replace("\\|", "&#124;")
    return "<code>" + texte + "</code>"


def _barres(ligne: str) -> str:
    return CODE_A_BARRE.sub(_code_html, ligne) if ligne.lstrip().startswith("|") else ligne


def on_page_markdown(markdown, page, config, files):
    guides = Path(config["docs_dir"]).resolve()
    dossier = (guides / page.file.src_uri).parent

    def reecrire(lien: re.Match) -> str:
        cible, _, ancre = lien.group(1).partition("#")
        chemin = (dossier / unquote(cible)).resolve()
        if chemin.is_relative_to(guides) or not chemin.is_relative_to(RACINE) or not chemin.exists():
            return lien.group(0)
        adresse = DEPOT + chemin.relative_to(RACINE).as_posix() + ("#" + ancre if ancre else "")
        return "](" + adresse + ")"

    markdown = "\n".join(_barres(ligne) for ligne in markdown.split("\n"))
    return LIEN.sub(reecrire, markdown)
