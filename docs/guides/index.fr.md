# AXION ENGINE

Physique, déformation et rendu 3D pour **Minecraft 1.20.1** et **Forge 47**. AXION est un moteur générique : il ne
connaît aucun contenu par son nom. Les véhicules, objets et machines viennent de mods qui les déclarent par l'API
publique `axion-api`, en Java ou par des fichiers JSON que le mod charge.

!!! warning "En développement"
    AXION est au jalon **M5** (véhicules). L'API est marquée `@Experimental` jusqu'à la fin de ce jalon : elle peut
    encore changer. Rien n'est encore publié pour les joueurs.

## Par où commencer

<div class="grid cards" markdown>

-   **Développer un addon**

    ---

    Déclarer des véhicules et des objets depuis votre mod, en JSON ou en Java, et les recharger sans redémarrer.

    [:octicons-arrow-right-24: Premier addon](addons/premier-addon.md)

-   **Jouer et héberger**

    ---

    Les options de configuration, ce qu'un serveur exige de ses clients et ce qu'un administrateur règle.

    [:octicons-arrow-right-24: Configuration](CONFIGURATION.md) · [Réseau](NETWORKING.md)

-   **Comprendre le moteur**

    ---

    Comment AXION simule, rend et choisit ses matériaux physiques.

    [:octicons-arrow-right-24: Physique](PHYSICS.md) · [Rendu](RENDERING.md) · [Matériaux](MATERIALS.md)

</div>

## Le code et les décisions

Le code, le cahier des charges et les décisions d'architecture sont sur
[GitHub](https://github.com/JLSkyzer/AXIONENGINE), sous licence Apache-2.0. Cette documentation est construite depuis
les mêmes fichiers que le code : elle décrit toujours la version qui l'accompagne.
