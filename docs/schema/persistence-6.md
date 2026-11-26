# AXION ENGINE — persistance, schéma 6

Forme publiée de ce qu'AXION écrit sur le disque (PARTIE 22) : le NBT d'une assembly au schéma 6 et le
journal latéral de récupération (22.5.2). Décidé par [ADR-153](../decisions/ADR-153.md), les variables gardées par
[ADR-163](../decisions/ADR-163.md) D2, les modificateurs posés par [ADR-167](../decisions/ADR-167.md) D7, les corps et
les joints rangés par nom par [ADR-175](../decisions/ADR-175.md) D1 ; ce document
en donne les octets. `PublishedPersistenceSchemaTest` et `PublishedJournalFormatTest` confrontent les
valeurs ci-dessous au code : un changement de format qui n'y est pas reporté fait échouer le build.

AXION n'écrit rien d'autre que le NBT de ses propres entités, l'unique `SavedData` `axion_registry` et
`<gameDir>/axion/` (R-1711, vérifié par T-437).

## 1. Le NBT d'une assembly

Toutes les clés d'AXION commencent par `axion:`. L'entité garde **toutes** celles qu'elle a lues et les
réécrit telles quelles : une clé qu'une version ultérieure aurait posée ne disparaît pas parce que cette
version-ci ne la comprend pas (R-1710). Les nombres d'un `byte[]` sont en little-endian.

```text
axion:v       int       6
axion:def     long      identifiant 64 bits de la definition
axion:pos     [double]  3, position monde du corps racine, en blocs
axion:rot     [float]   4, orientation du corps racine, quaternion (x, y, z, w)
axion:lvel    [float]   3, vitesse linéaire du corps racine, m/s
axion:avel    [float]   3, vitesse angulaire du corps racine, rad/s
axion:flags   int       bit 0 : endormie ; les autres réservés, écrits à 0, gardés
axion:bodies  byte[]    68 octets par corps secondaire
axion:joints  byte[]    8 octets par joint, dans l'ordre de la definition
axion:body_names  list  le nom du node racine de chaque corps de axion:bodies, dans le même ordre
axion:joint_names list  le nom de chaque joint de axion:joints, dans le même ordre
axion:custom  byte[]    table CBOR des données personnalisées, absente si elle est vide
axion:vars    compound  valeur de chaque variable gardée, par nom, absente sans elles
axion:params  list      modificateurs posés, dans l'ordre de leur pose, absente sans eux
axion:ctrls   list      contrôleurs de véhicule attachés par l'API, dans l'ordre de l'attache, absente sans eux
axion:rev     int       révision de la definition à laquelle l'état a été écrit (ADR-175 D3), 1 si absente
axion:sha     string    empreinte SHA-256 du contenu de cette definition, en hexadécimal
axion:energy  compound  contenu de chaque réserve d'énergie, par nom (ADR-176 D3, ADR-182), absente sans énergie
axion:wheels  compound  usure de chaque pneu, par nom de roue (ADR-176 D4), absente si aucun pneu n'est usé
```

- **`axion:bodies`**, pour chaque corps autre que le corps racine : `node u32` (node racine du corps dans
  l'asset), position monde `f64[3]`, orientation `f32[4]` (x, y, z, w), vitesse linéaire `f32[3]`, vitesse
  angulaire `f32[3]`.
- **`axion:joints`**, pour chaque joint : `flags u8` (bit 0 : rompu ; les autres réservés), 3 octets
  réservés à 0, puis le désalignement `f32`, en blocs — le dernier que Java a commandé.
- **`axion:body_names`** et **`axion:joint_names`** (schéma 6, ADR-175 D1) : une liste de textes, un par élément des
  deux tableaux d'octets, dans leur ordre. C'est par eux que la lecture rapproche chaque corps et chaque joint de la
  definition chargée : le `node u32` d'un corps n'est plus qu'un repère, qu'une definition réordonnée rend faux.
  Absentes, ou d'un nombre différent de leurs éléments, elles ne se lisent pas : l'état est lu sans noms, comme au
  schéma 5.
- **Restauration partielle** (ADR-175 D2). Ce qui correspond par son nom est restauré. Un corps que la definition a
  de nouveau prend sa pose de repos, relative à la racine restaurée et à sa vitesse ; un joint nouveau repart intact.
  Ce que la sauvegarde gardait et que la definition n'a plus est perdu, et dit : un `E-7012` par assembly, une fois,
  qui nomme chaque corps et chaque joint perdu ou nouveau. L'état n'est jamais appliqué à un autre élément, ni
  abandonné en bloc tant que la racine existe.
- **`axion:pos`** est écrite à chaque sauvegarde. L'entité se place par son `Pos` vanilla ; `axion:pos`
  sert au journal, où il n'y a pas d'entité.
- **Lecture.** Un état incomplet ou illisible (longueur qui n'est pas un multiple de la taille d'un
  élément, valeur non finie, quaternion nul) n'est pas rendu : l'assembly repart de sa pose de repos, à
  la position de son entité. Une orientation unitaire au flottant près est rendue au bit près.
- **Chargement.** Toute assembly chargée est créée endormie (22.4), puis réveillée par la gestion
  d'activité ; ses vitesses lui sont rendues au réveil.

### `axion:atts`

Les attaches que l'assembly a posées (ADR-171 D7), une entrée par attache : `socket`, `kind`, `owner`, `length` (corde et
treuil), puis soit `anchor` (le bloc, trois entiers), soit `other` (UUID), `other_socket` et **`other_rev`** : la révision
de la definition de l'autre à laquelle `other_socket` est écrit (ADR-179). Au rétablissement, le nom suit les migrations de
l'autre depuis cette révision. Absente — une sauvegarde d'avant —, le nom se lit tel quel.

### `axion:rev` et `axion:sha`

La révision (`revision` d'une definition du schéma 7, 1 sans) et l'empreinte du contenu de la definition à laquelle
l'état a été écrit (ADR-175 D3), réécrites à chaque sauvegarde. À la lecture :

- un état d'une révision antérieure à celle de la definition est mené jusqu'à elle par ses `migrations`, dont le `from`
  va de la révision gardée à la courante : elles **renomment** ce que l'état nomme — les corps et l'état des nodes
  (`nodes`), les joints, les variables et leurs sièges, les sockets des attaches, les paramètres des modificateurs, les
  réserves d'énergie (`stores`, schéma 8) —, et rien d'autre ;
- un contenu qui a changé sans nouvelle révision, ou un état d'une révision plus récente, se restaure par nom (D2) et
  le dit une fois, en avertissement ;
- `axion:def` suit l'identifiant que la definition a pris : une definition qui en `replaces` une autre reprend ses
  assemblies sauvegardées, sauf si une definition porte encore l'ancien identifiant.

### `axion:vars`

À chaque variable de la definition déclarée `persist` (ADR-163 D2), son nom, une valeur `double`. Un booléen vaut 0
ou 1. À la lecture, une valeur que sa variable refuse désormais — la definition a changé ses bornes ou son type —
laisse la variable à son défaut, et se journalise ; jamais tronquée. Un nom que la definition ne déclare plus est
gardé tel quel (R-1710). Une entrée qui n'est pas un `double` est ignorée.

### `axion:params`

Les modificateurs posés sur les paramètres de l'assembly par l'API ou par `/axion params` (ADR-167 D7), dans l'ordre
de leur pose, actifs ou non : une liste de compounds `cause` (texte, `<espace de noms>:<chemin>`), `parameter`
(texte, le nom du paramètre), `operation` (texte : `add`, `multiply_base` ou `multiply_total`) et `amount` (`double`).
Les modificateurs qu'une definition déclare et ceux des contrôleurs ne sont pas gardés : ils se recalculent.

À la lecture, chaque modificateur se repose dans son ordre, par la règle de l'API : un NBT ne fait pas foi. Celui que
les bornes de la definition refusent désormais, ou qui vise un paramètre qu'elle n'a plus, reste posé mais inactif et
se journalise (`E-7011`) ; il n'est pas jeté. Une entrée illisible — un champ manquant, une opération inconnue, une
cause mal formée, une quantité non finie — est ignorée, et une cause de l'espace de noms `axion`, réservé au moteur,
écartée.

### `axion:energy`

Le contenu des réserves d'un véhicule dont la definition déclare `energy` (ADR-176 D3, ADR-182 D6) : un compound
`{ nom de la réserve : double }`, chacun dans l'unité de sa réserve, tel que le natif l'a rapporté. Au schéma de
definition 7, les noms sont `fuel` (litres) et `battery` (unités de charge) ; au schéma 8, ceux d'`energy.stores`, plus
`battery`. Une valeur non finie ou négative vaut zéro, et la capacité de la definition borne le reste : un NBT ne fait pas
foi. Une réserve que la sauvegarde n'a pas naît pleine ; celle que la definition n'a plus est gardée telle quelle à la
sauvegarde suivante (R-1710) et dite une fois au journal (`E-7012`). Les migrations de renommage des `stores` (ADR-175 D3)
renomment les clés. Une assembly relue avec cette clé envoie son énergie au natif (`SET_ENERGY`) avant son premier pas ;
sans elle, elle naît pleine. Le schéma NBT reste à 6 : la clé est optionnelle, et un build qui l'ignore la garde à la
réécriture.

### `axion:wheels`

L'usure du pneu de chaque roue d'un véhicule (ADR-176 D4, CAP-38) : un compound qui associe le **nom de la roue** — le
`node` de son entrée dans `wheels` — à un `double` de 0 à 1. Une roue dont le pneu est neuf n'y figure pas. Une valeur
absente, non finie ou négative vaut zéro, et au-delà de 1 elle vaut 1 : un NBT ne fait pas foi.

La lecture rapproche par **nom**, jamais par rang : une roue ajoutée, retirée ou réordonnée ne décale pas les autres. Une roue
nouvelle naît à 0 ; une entrée dont la definition n'a plus la roue est gardée telle quelle à la sauvegarde suivante (R-1710)
et dite une fois au journal (`E-7012`). Les migrations de renommage des `nodes` (ADR-175 D3) renomment ses clés. Une
assembly relue avec cette clé envoie l'usure au natif (`SET_WEAR`) avant son premier pas ; sans elle, ses pneus sont neufs.
Le schéma NBT reste à 6 : la clé est optionnelle, comme `axion:energy`.

### `axion:ctrls`

Les contrôleurs de véhicule que l'API a attachés à l'assembly (ADR-167 D7, D9), dans l'ordre de l'attache : une liste
de textes, chacun l'identifiant d'un contrôleur (`<espace de noms>:<chemin>`), huit au plus. Ceux que sa definition
nomme (`vehicle.controllers`) n'y sont pas : ils découlent d'elle.

À la lecture, un contrôleur qu'aucun mod n'enregistre plus reste attaché mais inactif, et se journalise (`E-7011`) :
son mod revenu, il reprend ; il n'est pas jeté (CAP-38). Une entrée illisible — pas un texte, un identifiant mal formé
ou de plus de 256 caractères, un doublon — est ignorée, et au-delà de huit le reste aussi.

### `axion:custom`

Une map CBOR de longueur définie : à chaque espace de noms de mod (`[a-z0-9_.-]+`, chaîne de texte), une
chaîne d'octets que le mod encode comme il veut ; AXION n'en lit pas le contenu (ADR-153 D7). Les clés
sont écrites triées, et la table n'est réencodée qu'après une écriture : relue puis sauvegardée sans
changement, elle garde ses octets. Taille plafonnée par `persistence.max_custom_bytes` (16 KiB par
défaut, `CONFIGURATION.md`) ; une table lue au-delà d'un plafond baissé depuis reste intacte. Une table
illisible est gardée telle quelle, en lecture seule.

### Versions

| `axion:v` | Ce qu'en fait AXION |
|---|---|
| absente, ou inférieure à 2 | entité inerte, octets intacts : aucune version d'AXION ne l'a écrite |
| 2 | migrée en 3 (T-434) : `axion:v` passe à 3, `axion:def`, `axion:rot` et toute autre clé gardées ; aucun état inventé, l'assembly repart de sa pose de repos, à vitesse nulle, endormie ; puis en 4 |
| 3 | migrée en 4 : `axion:v` passe à 4, toute clé gardée ; ses variables repartent de leur défaut ; puis en 5 |
| 4 | migrée en 5 : `axion:v` passe à 5, toute clé gardée ; aucun modificateur posé ; puis en 6 |
| 5 | migrée en 6 (ADR-175 D1) : `axion:v` passe à 6, toute clé gardée ; aucun nom inventé — la restauration nomme ses corps d'après l'asset chargé (le node de chacun) et rapproche ses joints par rang, seulement si leur nombre n'a pas changé, et le dit (`E-7012`) |
| 6 | lue |
| supérieure à 6 | entité inerte, octets réécrits à l'identique (T-433) |

Le schéma 2 ne portait que `axion:v`, `axion:def` et `axion:rot`.

### Clés réservées

Les autres clés de 22.2 — `axion:parts`, `axion:links`, `axion:deform`, `axion:wear`, `axion:decals`,
`axion:dangling`, `axion:attach`, `axion:repair`, `axion:power` — viennent avec leur composant, chacune
avec sa hausse de version et sa migration (ADR-153 D1). Jusque-là, une entité qui les porte les garde.

## 2. Le registre du monde : `axion_registry`

L'unique `SavedData` d'AXION, `data/axion_registry.dat` dans le dossier du monde. Vanilla le garde même
quand le monde est joué sans AXION. Il porte le jeton du journal latéral, une chaîne sous la clé
`journal_token` (§3.3). Son absence n'empêche rien : un jeton neuf est tiré.

## 3. Le journal latéral

### 3.1 Emplacement

```text
<gameDir>/axion/world/<worldId>/assemblies.<n>.journal   segments, lus dans l'ordre de n
<gameDir>/axion/world/<worldId>/monde.jeton              jeton du monde, texte UTF-8
<gameDir>/axion/world/<worldId>/restaurations.log        rapport des restaurations
```

`worldId` : les 16 premiers chiffres hexadécimaux (minuscules) du SHA-256 de la suite d'octets « chemin
canonique du dossier du niveau en UTF-8, un octet 0, graine en décimal ASCII ».

### 3.2 Segment

```text
en-tête         magic "AXJL" (4 octets ASCII), puis version u16 = 1
enregistrement  type u8 | longueur u32 | contenu | crc u32
```

- Le CRC est un CRC-32C du type, de la longueur et du contenu ; tous les nombres sont en little-endian.
- **Types** : `1 : PUT`, `2 : REMOVE`.
- **Contenu d'un `PUT`** : UUID (`i64` des bits de poids fort, puis `i64` des bits de poids faible),
  longueur `u16` puis octets UTF-8 de l'identifiant de dimension, position `f64[3]`, definition `i64`,
  longueur `u32` puis blob. Le blob est le NBT binaire non compressé (`NbtIo.write`) des clés `axion:*`
  de l'entité, tel que décrit au §1.
- **Contenu d'un `REMOVE`** : l'UUID seul, 16 octets.
- **Lecture.** Un `PUT` plus récent remplace le précédent de la même assembly ; un `REMOVE` l'efface. Un
  enregistrement au CRC faux est ignoré et compté ; une fin tronquée arrête la lecture du segment, ce qui
  précède reste lu. Un segment à l'en-tête illisible est renommé `.corrupt` et ne compte plus.
- **Écriture.** Une session écrit dans un segment neuf, créé à sa première écriture. Le compactage, au-delà
  d'un plancher de 1 048 576 octets, quand les enregistrements vivants tombent sous la moitié des octets,
  écrit les vivants dans `assemblies.<n>.journal.tmp`, le force sur le disque, le renomme, puis supprime
  les anciens segments. Au plafond `persistence.journal_max_bytes`, les entrées les plus anciennes sont
  évincées, chacune journalisée avec son UUID (R-1747).

### 3.3 Jeton

`monde.jeton` recopie le jeton de `axion_registry`. Au démarrage, un journal au jeton différent appartient à
un autre monde de même chemin et de même graine : son dossier est renommé `<worldId>.ecarte-<ms>`, signalé,
et rien n'en est restauré. Un journal sans jeton adopte celui du monde.

### 3.4 Rapport

Chaque restauration ajoute une ligne à `restaurations.log` :

```text
<instant ISO-8601> restaurée <uuid> definition <definition en hexadécimal> <dimension> <x> <y> <z>
```

Le journal n'est qu'un filet de sécurité : tant que l'entité existe, son NBT fait foi (R-1745). Les options
qui le règlent sont dans `CONFIGURATION.md`, section `[persistence]`.
