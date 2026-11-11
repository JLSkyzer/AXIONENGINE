# Installer tes outils

Trois outils suffisent, tous gratuits. Installe-les dans cet ordre.

## 1. Java 17

Minecraft 1.20.1 et Forge 47 tournent sur **Java 17**, ni plus ancien ni plus récent pour compiler un mod. Installe
un JDK 17 (le kit de développement, pas seulement de quoi lancer Java), par exemple **Eclipse Temurin 17** sur
[adoptium.net](https://adoptium.net/).

Vérifie dans un terminal :

```bash
java -version
```

La réponse doit commencer par `openjdk version "17`.

!!! question "Pourquoi exactement la 17 ?"
    Un mod est compilé pour une version de Java précise. Minecraft 1.20.1 attend la 17 : un mod compilé pour une
    version plus récente refuserait de se charger.

## 2. Un éditeur de code : IntelliJ IDEA

**IntelliJ IDEA** ([jetbrains.com/idea](https://www.jetbrains.com/idea/)), dans son édition gratuite, ouvre un
projet de mod tel quel : il comprend Gradle, l'outil qui construit le mod et lance le jeu, complète le code et
signale les erreurs pendant que tu tapes.

!!! question "Et Gradle, je l'installe ?"
    Non. Chaque projet de mod contient son propre lanceur de Gradle (`gradlew`), qui télécharge tout seul la bonne
    version au premier lancement, puis Minecraft et Forge.

## 3. Blender, pour les modèles 3D

**Blender** ([blender.org](https://www.blender.org/)) sert à créer la forme et les couleurs de tes objets. AXION lit
le format **glTF 2.0** : dans Blender, *Fichier › Exporter › glTF 2.0*, puis choisis `.glb` (un seul fichier, le
plus simple) ou `.gltf`.

!!! tip "Pas encore à l'aise avec Blender ?"
    Commence avec un cube : le premier addon n'a besoin que d'une forme simple. Tu affineras ensuite.

## C'est prêt

Avec Java 17, IntelliJ IDEA et Blender, tu as tout ce qu'il faut pour [ton premier addon](premier-addon.md).
