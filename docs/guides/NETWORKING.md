# Réseau

AXION réplique ses assemblies par son propre canal, `axion:main`. Le format des messages est dans la PARTIE 21 du
cahier des charges et dans les décisions qui l'ont précisé ([ADR-150](../decisions/ADR-150.md),
[ADR-165](../decisions/ADR-165.md)). Ce guide dit ce qu'un administrateur de serveur doit savoir : ce qu'un client
doit avoir pour entrer, ce que le serveur lui envoie, ce qui se règle, et où lire ce qui s'est passé.

## Ce qu'un client doit avoir pour entrer

1. **AXION, dans la même version de protocole.** Forge compare la version du canal pendant la connexion (`AXION-9`
   aujourd'hui). Un client sans AXION, ou d'une autre version de protocole, est refusé par Forge, avec le message de
   Forge (R-1630). La version du protocole ne suit pas celle du mod : elle ne monte que lorsque le format change.
2. **Chaque addon de contenu du serveur, dans la même version.** Le serveur exige du client chaque mod propriétaire
   d'une entrée de son contenu — definitions, modèles, matériaux, groupes de collision, pneus, correspondances de
   blocs, remplacements compris —, dans la version exacte que Forge en donne (`mods.toml`). À un client qui en manque
   un, ou qui l'a dans une autre version, le serveur refuse l'entrée avant le monde, par un message traduit qui nomme
   chaque addon en cause :

   ```text
   AXION : ce serveur exige des addons que votre jeu n'a pas, ou pas dans la même version :
     - policemod 2.1.0 : absent
     - tracteurs 1.4.0 : vous avez la 1.3.2
   ```

   Pourquoi la version exacte : un modèle ou une extension peut changer d'une version à l'autre sans que rien ne le
   dise. Un client qui a **en plus** un addon que le serveur n'a pas entre : le serveur fait autorité, et ce contenu
   ne sert pas.
3. **Chaque contrôleur de véhicule que les fiches du serveur nomment.** Le mod qui l'enregistre est exigé au point 2,
   mais un mod peut n'enregistrer un contrôleur que selon sa configuration : le serveur annonce donc la liste de ceux
   que ses fiches nomment (`vehicle.controllers`), et refuse le client à qui l'un manque, en le nommant :

   ```text
   AXION : ce serveur utilise des contrôleurs de véhicule que votre jeu n'enregistre pas :
     - turbo:gestion
   ```

En solo, le joueur et le serveur intégré partagent les mêmes mods : il n'y a rien à refuser.

## Ce que le serveur envoie

À la connexion d'un client distant, le serveur envoie ses paramètres de simulation (`S2C_Handshake`), puis son
registre résolu (`S2C_Registry`, compressé) : matériaux physiques, groupes de collision, definitions et pneus, les
surcharges de l'administrateur appliquées. Il le renvoie aux joueurs connectés après chaque `/reload`.

Les modèles et les textures ne voyagent jamais : ils viennent des addons installés chez le client (R-1640) — d'où
l'exigence des addons ci-dessus. Un asset qu'un addon présent ne fournit pas, ou qui ne se compile pas, s'affiche par
l'asset de secours (`axion:builtin/missing`) : la pièce manquante se voit, et rien ne s'arrête.

Les correspondances bloc → matériau ne voyagent pas non plus : seul le serveur s'en sert aujourd'hui, pour le
contact des véhicules avec le terrain. Quand le client prédira le véhicule qu'il conduit, elles voyageront avec le
registre ([ADR-165](../decisions/ADR-165.md), précision 3c).

## Ce qui se règle

- **`axion-server.toml`** : la prédiction des clients, l'expulsion d'un client dont les entrées sortent des bornes,
  l'autorité expérimentale des particules. Certaines options de `axion-common.toml` sont imposées au client par le
  handshake : la colonne « serveur » de [`CONFIGURATION.md`](CONFIGURATION.md) les désigne.
- **`axion-server-overrides.toml`** : des multiplicateurs bornés sur les matériaux, les definitions et les pneus, et
  des correspondances de blocs, sans écrire de mod. Son format :
  [`CONFIGURATION.md`](CONFIGURATION.md#axion-server-overridestoml). Les clients reçoivent les valeurs surchargées
  avec le registre ; ils n'ont pas besoin du fichier.

## Ce que dit le journal du serveur

| Ligne | Quand |
|---|---|
| `AXION : addons de contenu exigés des clients (ADR-165) : {…}` | au démarrage : chaque addon exigé, avec sa version |
| `AXION : connexion de <joueur> refusée, addons manquants ou d'une autre version : …` | à chaque refus, avec chaque addon en cause |
| `AXION : surcharge appliquée — …` | au démarrage et à chaque `/reload` : la valeur avant et après |
| `AXION : surcharge refusée — …` | au démarrage et à chaque `/reload` : la cause du refus |
| `AXION : registre rechargé renvoyé à <n> joueur(s) distant(s)` | après un `/reload` |

`/axion status` résume l'état : la ligne `réseau` (version du protocole, taille du registre envoyé) et la ligne des
surcharges de l'administrateur (combien ont été appliquées, combien refusées).
