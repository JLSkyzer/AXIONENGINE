# Débuter avec AXION

Tu veux ajouter tes propres objets ou véhicules à Minecraft avec AXION ? Ce parcours est fait pour toi, même si tu
n'as jamais écrit de mod. Chaque page explique **ce que tu fais** et **pourquoi tu le fais** : à la fin, tu sauras
lire le reste de la documentation sans te perdre.

!!! tip "Pas besoin d'être développeur"
    Pour ajouter un objet, tu n'écris presque pas de Java : tu ranges un modèle 3D et un petit fichier de texte (JSON)
    dans un mod tout prêt. Le Java vient plus tard, quand tu voudras aller plus loin.

## Le parcours

| Étape | Ce que tu y apprends | Temps |
|---|---|---|
| [1. Comprendre AXION](comprendre.md) | Ce qu'est un mod, un addon, une definition — et comment ils s'emboîtent | 10 min |
| [2. Installer tes outils](outils.md) | Java 17, un éditeur de code, Blender pour les modèles 3D | 20 min |
| [3. Ton premier addon](premier-addon.md) | Déclarer une caisse en JSON puis en Java, et la recharger sans redémarrer | 30 min |
| [4. Tester ton addon](tester.md) | Vérifier ton contenu sans lancer le jeu, puis dans un vrai serveur | 15 min |
| [5. Versions](versions.md) | Dire quelle version d'AXION il te faut, et ce que voit un joueur qui ne l'a pas | 5 min |
| [6. Régler un véhicule](regler.md) | Changer le moteur, les freins ou le poids d'un seul véhicule, comme un kit d'amélioration | 15 min |
| [Glossaire](glossaire.md) | Les mots d'AXION, expliqués simplement | à garder sous la main |

Les temps sont indicatifs : prends celui qu'il te faut.

!!! info "Un mot souligné en pointillé ?"
    Survole-le : une bulle explique ce qu'il veut dire. Tous ces mots sont aussi dans le [glossaire](glossaire.md).

## Si tu bloques

Quand quelque chose ne marche pas, le **journal du jeu** dit presque toujours pourquoi : AXION y écrit chaque erreur,
avec le mod, le fichier et la ligne en cause. Le journal est dans `logs/latest.log`, dans le dossier du jeu.
