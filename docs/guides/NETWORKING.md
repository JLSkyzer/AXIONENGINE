# Réseau

AXION réplique ses assemblies par son propre canal, `axion:main`. Le format des messages est dans la PARTIE 21 du
cahier des charges et dans les décisions qui l'ont précisé ([ADR-150](../decisions/ADR-150.md),
[ADR-165](../decisions/ADR-165.md)). Ce guide dit ce qu'un administrateur de serveur doit savoir : ce qu'un client
doit avoir pour entrer, ce que le serveur lui envoie, ce qui se règle, et où lire ce qui s'est passé.

## Ce qu'un client doit avoir pour entrer

1. **AXION, dans la même version de protocole.** Forge compare la version du canal pendant la connexion (`AXION-10`
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

Les correspondances bloc → matériau voyagent avec le registre : le client qui prédit son véhicule en a besoin pour
le contact avec le terrain ([ADR-168](../decisions/ADR-168.md), précision 6d-1).

Les **événements de contenu** — un klaxon, une portière qui claque — partent après le snapshot du même tick
(`S2C_Event`, protocole 13, [ADR-174](../decisions/ADR-174.md)) : aux joueurs qui suivent l'assembly, ou à ses seuls
occupants, selon la definition. Un joueur en reçoit au plus `net.events_per_player_tick` par tick (16) ; au-delà, ils
ne partent pas, et le journal du serveur le dit une fois par seconde.

### Les variables par siège

Au schéma 7, une variable déclare `"scope": "seat"` pour avoir **une valeur par siège** — la radio de chaque place, la
liseuse du passager ([ADR-174](../decisions/ADR-174.md) D2). La definition doit avoir des sièges.

```json
"variables": { "radio": { "type": "int", "min": 0, "max": 9, "scope": "seat", "write": "seat", "persist": true } }
```

- **L'écrire** : une commande liée d'un siège écrit la valeur de ce siège ; une interaction `set_variable`, celle du
  siège de qui clique — debout, elle est refusée ; l'API, `variables().set("radio", "passager", 3)`.
- **La lire** : `var.radio` lit la valeur du siège qu'occupe le joueur local (son défaut s'il n'est pas assis) ;
  `var.radio.passager` lit un siège nommé, ce que voit tout le monde. Un déclencheur nomme toujours le siège :
  `var:radio.passager`. L'API lit par `variables().get("radio", "passager")` ; `get("radio")` est refusé.
- **Ce qui part** : chaque joueur reçoit la valeur de chaque siège, selon `replicate` (descripteur 11). Elle se
  sauvegarde par nom de siège si elle est `persist`. Elle ne se prédit pas : le conducteur la voit changer au snapshot.
- **Ce qui la refuse** : un paramètre réglé, un treuil, une action d'étape — rien n'y situe un siège.

## La prédiction du véhicule piloté

Un joueur qui conduit un véhicule, sur un serveur distant, le voit répondre à ses touches tout de suite : son client
simule ce véhicule dans un monde à lui, pendant que le serveur, seul juge, simule le vrai
([ADR-168](../decisions/ADR-168.md)). À chaque tick où le véhicule avance, le serveur renvoie au conducteur l'état qui
fait foi (`S2C_Correction`) ; le client le compare à ce qu'il avait prédit, et rejoue ses entrées depuis cet état
quand ils diffèrent. La correction porte aussi l'état de ce qui est attaché au véhicule, une remorque tirée à la corde
par exemple, que le client prédit avec lui : la remorque répond tout de suite, et se dessine au même instant que la
voiture ([ADR-171](../decisions/ADR-171.md)). La reprise se lisse à l'écran en 200 ms, au lieu de sauter. Le client n'envoie que ses entrées,
jamais un état : il ne peut rien imposer au serveur (INV-02).

La prédiction ne tourne pas :

- en solo, ni chez l'hôte d'un monde ouvert : la latence y est nulle, rien à prédire ;
- si le joueur la coupe (`net.prediction`), ou si le serveur ne la permet pas (`server.allow_client_prediction`) ;
- au-delà de `net.prediction_max_ping` (400 ms) de latence : elle revient sous 90 % de ce seuil
  (`net.prediction_reenable_ratio`), pour ne pas basculer d'un état à l'autre au seuil même ;
- si plus de `net.prediction_max_corrections` des 100 dernières corrections l'ont démentie : elle se retente d'elle-même
  100 corrections plus tard.

Coupée, le véhicule s'affiche comme les autres, interpolé ; le passage d'un affichage à l'autre se lisse aussi. Le
client règle lui-même la file de ses entrées au serveur (protocole 10) : il la tient au plus juste, une entrée par tick,
pour n'ajouter aucune latence.

Le journal du client le dit, une fois par changement :

| Ligne | Quand |
|---|---|
| `AXION : prédiction : monde prédit ouvert pour <véhicule> (entité <n>)` | le joueur prend le volant d'un véhicule prédit |
| `AXION : prédiction coupée — <cause>` | elle se coupe, ou sa cause change : la latence, le taux de corrections, un réglage |
| `AXION : prédiction rétablie` | la cause a cessé |
| `AXION : prédiction : monde prédit fermé` | le joueur descend, ou la prédiction se coupe |

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
