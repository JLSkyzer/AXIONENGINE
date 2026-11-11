# AXION ENGINE — persistance, schéma 3

Forme publiée de ce qu'AXION écrit sur le disque (PARTIE 22) : le NBT d'une assembly au schéma 3 et le
journal latéral de récupération (22.5.2). Décidé par [ADR-153](../decisions/ADR-153.md) ; ce document en
donne les octets. `PublishedPersistenceSchemaTest` et `PublishedJournalFormatTest` confrontent les
valeurs ci-dessous au code : un changement de format qui n'y est pas reporté fait échouer le build.

AXION n'écrit rien d'autre que le NBT de ses propres entités, l'unique `SavedData` `axion_registry` et
`<gameDir>/axion/` (R-1711, vérifié par T-437).

## 1. Le NBT d'une assembly

Toutes les clés d'AXION commencent par `axion:`. L'entité garde **toutes** celles qu'elle a lues et les
réécrit telles quelles : une clé qu'une version ultérieure aurait posée ne disparaît pas parce que cette
version-ci ne la comprend pas (R-1710). Les nombres d'un `byte[]` sont en little-endian.

```text
axion:v       int       3
axion:def     long      identifiant 64 bits de la definition
axion:pos     [double]  3, position monde du corps racine, en blocs
axion:rot     [float]   4, orientation du corps racine, quaternion (x, y, z, w)
axion:lvel    [float]   3, vitesse linéaire du corps racine, m/s
axion:avel    [float]   3, vitesse angulaire du corps racine, rad/s
axion:flags   int       bit 0 : endormie ; les autres réservés, écrits à 0, gardés
axion:bodies  byte[]    68 octets par corps secondaire
axion:joints  byte[]    8 octets par joint, dans l'ordre de la definition
axion:custom  byte[]    table CBOR des données personnalisées, absente si elle est vide
```

- **`axion:bodies`**, pour chaque corps autre que le corps racine : `node u32` (node racine du corps dans
  l'asset), position monde `f64[3]`, orientation `f32[4]` (x, y, z, w), vitesse linéaire `f32[3]`, vitesse
  angulaire `f32[3]`.
- **`axion:joints`**, pour chaque joint : `flags u8` (bit 0 : rompu ; les autres réservés), 3 octets
  réservés à 0, puis le désalignement `f32`, en blocs — le dernier que Java a commandé.
- **`axion:pos`** est écrite à chaque sauvegarde. L'entité se place par son `Pos` vanilla ; `axion:pos`
  sert au journal, où il n'y a pas d'entité.
- **Lecture.** Un état incomplet ou illisible (longueur qui n'est pas un multiple de la taille d'un
  élément, valeur non finie, quaternion nul) n'est pas rendu : l'assembly repart de sa pose de repos, à
  la position de son entité. Une orientation unitaire au flottant près est rendue au bit près.
- **Chargement.** Toute assembly chargée est créée endormie (22.4), puis réveillée par la gestion
  d'activité ; ses vitesses lui sont rendues au réveil.

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
| 2 | migrée en 3 (T-434) : `axion:v` passe à 3, `axion:def`, `axion:rot` et toute autre clé gardées ; aucun état inventé, l'assembly repart de sa pose de repos, à vitesse nulle, endormie |
| 3 | lue |
| supérieure à 3 | entité inerte, octets réécrits à l'identique (T-433) |

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
