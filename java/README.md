# java/

Le code Java d'AXION ENGINE, en deux modules Gradle.

| Module | Rôle |
|---|---|
| [`axion-api/`](axion-api/) | L'API publique (C-70) : ce qu'un mod Java voit d'AXION. Sans dépendance au moteur ni au natif (R-1750), versionnée à part (R-1751). |
| [`axion-mod/`](axion-mod/) | Le mod Forge : le moteur côté Java, la frontière avec le natif, le rendu, le réseau, la persistance. |

Dans `axion-mod/src/` : `main/` le code livré, `test/` les tests unitaires, `devcontent/` le contenu et les
GameTests de développement, jamais empaquetés.
