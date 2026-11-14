# Networking

AXION replicates its assemblies over its own channel, `axion:main`. The message format is in PART 21 of the
specification and in the decisions that refined it ([ADR-150](../decisions/ADR-150.md),
[ADR-165](../decisions/ADR-165.md)). This guide says what a server administrator needs to know: what a client
must have to join, what the server sends it, what can be tuned, and where to read what happened.

## What a client must have to join

1. **AXION, with the same protocol version.** Forge compares the channel version during login (`AXION-10`
   today). A client without AXION, or with another protocol version, is refused by Forge, with Forge's message
   (R-1630). The protocol version does not follow the mod's: it only goes up when the format changes.
2. **Each of the server's content addons, in the same version.** The server requires from the client every mod
   that owns an entry of its content — definitions, models, materials, collision groups, tyres, block mappings,
   replacements included — in the exact version Forge gives for it (`mods.toml`). A client that lacks one, or has
   it in another version, is refused entry before the world, with a translated message that names each addon
   involved:

   ```text
   AXION: this server requires add-ons that your game lacks, or has in another version:
     - policemod 2.1.0: missing
     - tracteurs 1.4.0: you have 1.3.2
   ```

   Why the exact version: a model or an extension can change from one version to the next without anything
   saying so. A client that **also** has an addon the server does not have gets in: the server is authoritative,
   and that content is not used.
3. **Every vehicle controller the server's sheets name.** The mod registering it is required by point 2, but a mod
   may register a controller only depending on its configuration: so the server announces the list of those its
   sheets name (`vehicle.controllers`), and refuses a client lacking one, naming it:

   ```text
   AXION: this server uses vehicle controllers that your game does not register:
     - turbo:gestion
   ```

In single player, the player and the integrated server share the same mods: there is nothing to refuse.

## What the server sends

When a remote client connects, the server sends its simulation parameters (`S2C_Handshake`), then its resolved
registry (`S2C_Registry`, compressed): physical materials, collision groups, definitions and tyres, with the
administrator's overrides applied. It sends it again to connected players after each `/reload`.

Models and textures never travel: they come from the addons installed on the client (R-1640) — hence the addon
requirement above. An asset that an installed addon does not provide, or that does not compile, is shown with the
fallback asset (`axion:builtin/missing`): the missing part is visible, and nothing stops.

Block → material mappings do not travel either: only the server uses them today, for vehicle contact with the
ground. When the client predicts the vehicle it drives, they will travel with the registry
([ADR-165](../decisions/ADR-165.md), clarification 3c).

## What can be tuned

- **`axion-server.toml`**: client prediction, kicking a client whose inputs go out of bounds, the experimental
  particle authority. Some options of `axion-common.toml` are imposed on the client by the handshake: the "server"
  column of [`CONFIGURATION.md`](CONFIGURATION.md) marks them.
- **`axion-server-overrides.toml`**: bounded multipliers on materials, definitions and tyres, and block
  mappings, without writing a mod. Its format:
  [`CONFIGURATION.md`](CONFIGURATION.md#axion-server-overridestoml). Clients receive the overridden values with
  the registry; they do not need the file.

## What the server log says

| Line (AXION's logs are in French) | When |
|---|---|
| `AXION : addons de contenu exigés des clients (ADR-165) : {…}` | at startup: each required addon, with its version |
| `AXION : connexion de <joueur> refusée, addons manquants ou d'une autre version : …` | on each refusal, with each addon involved |
| `AXION : surcharge appliquée — …` | at startup and on each `/reload`: the value before and after |
| `AXION : surcharge refusée — …` | at startup and on each `/reload`: the reason for the refusal |
| `AXION : registre rechargé renvoyé à <n> joueur(s) distant(s)` | after a `/reload` |

`/axion status` sums up the state: the `réseau` line (protocol version, size of the registry sent) and the line
for the administrator's overrides (how many were applied, how many refused).
