# TierTagger

A multi-loader Minecraft mod that displays player tiers from the [OuterTiers](https://outertiers.com) website right next to player names — both in the in-game **tab list** and as a **badge above each player's head**. Inspired by [tiertagger on Modrinth](https://modrinth.com/mod/tiertagger).

## Features

- **Tab-list badges** — coloured `[HT3]`-style badges next to every player name when you press Tab.
- **Nametag badges above heads** — the same badge floats above the player's head in-world (the signature feature of the original mod).
- **Pick your gamemode** — `overall`, `ogvanilla`, `vanilla`, `uhc`, `pot`, `nethop`, `smp`, `sword`, `axe`, `mace`, `speed`.
- **Fall-through to highest tier** — if a player is unranked in your selected gamemode, the badge automatically shows their highest tier across all modes (toggleable).
- **Peak-tier mode** — option to show the player's lifetime peak instead.
- **Profile screen** — view a player's full breakdown across every gamemode in a dedicated GUI screen (no third-party UI library required).
- **In-game settings screen** — change every option from a clean GUI (`/tiertagger config`).
- **Async fetching with TTL cache** — the game never stalls on network calls.
- **Configurable API base URL** — point the mod at your own OuterTiers backend.

## In-game commands

| Command                               | What it does                                          |
| ------------------------------------- | ----------------------------------------------------- |
| `/tiertagger`                         | Show the current config in chat                       |
| `/tiertagger config`                  | Open the in-game settings screen                      |
| `/tiertagger profile <player>`        | Open a player's full tier breakdown screen            |
| `/tiertagger mode <gamemode>`         | Switch the active gamemode                            |
| `/tiertagger toggle`                  | Show / hide tab-list badges                           |
| `/tiertagger nametag`                 | Show / hide the badge above player heads              |
| `/tiertagger fallthrough`             | Toggle automatic fall-through to the highest tier     |
| `/tiertagger peak`                    | Toggle showing the player's peak tier instead         |
| `/tiertagger refresh`                 | Clear the cache and re-fetch                          |
| `/tiertagger api <url>`               | Override the API base URL                             |

Config is persisted in `.minecraft/config/tiertagger.json`.

## Project layout

```
tiertagger/
├── common/        — pure-Java logic shared by both loaders (config, cache, scoring)
├── fabric/        — Fabric Loader entry, mixins, screens, command
└── neoforge/      — NeoForge entry, mixins, screens, command
```

## Supported loaders

| Loader   | How                                                                                        |
| -------- | ------------------------------------------------------------------------------------------ |
| Fabric   | Native — install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api). |
| **Quilt**| **Native — Quilt loads Fabric mods directly. Just install [Quilt Loader](https://quiltmc.org/) and [Quilted Fabric API](https://modrinth.com/mod/qsl), then drop in the Fabric build.** A `quilt.mod.json` is bundled so the mod is recognised as Quilt-aware. |
| NeoForge | Native — install [NeoForge](https://neoforged.net/) and use the NeoForge build.            |

> Forge (legacy, pre-NeoForge fork) and Bedrock are not supported.

## Downloads

Pre-built JARs for every supported MC version × loader combo are published on the [Releases page](https://github.com/blackdmega-wq/tiertagger/releases). Each release contains files like:

```
tiertagger-fabric-1.0.0-mc1.21.1.jar
tiertagger-fabric-1.0.0-mc1.21.5.jar
tiertagger-neoforge-1.0.0-mc1.21.1.jar
tiertagger-neoforge-1.0.0-mc1.21.5.jar
…
```

## Versions covered by CI

The release workflow attempts to build the following matrix on every tag push (`v*`). Combos that fail to build (e.g. when a particular Fabric API or NeoForge artifact isn't published yet) are skipped — the rest still ship.

| MC version | Fabric | NeoForge |
| ---------- | :----: | :------: |
| 1.21.1     |   ✓    |    ✓     |
| 1.21.2     |   ✓    |    ✓     |
| 1.21.3     |   ✓    |    ✓     |
| 1.21.4     |   ✓    |    ✓     |
| 1.21.5     |   ✓    |    ✓     |
| 1.21.6     |   ✓    |    ✓     |
| 1.21.7     |   ✓    |    ✓     |
| 1.21.8     |   ✓    |    ✓     |
| 1.21.9     |   ✓    |    ✓     |
| 1.21.10    |   ✓    |    ✓     |
| 1.21.11    |   ✓    |    ✓     |

To add or update a version, edit the `matrix.include` list in `.github/workflows/build-release.yml`.

## Building locally

Requirements: JDK 21 and Gradle 8.10 (or run `gradle wrapper` once to generate the wrapper).

```bash
# Build everything for the version pinned in gradle.properties:
gradle build

# Build only one loader:
gradle :fabric:build
gradle :neoforge:build

# Override the target version on the fly:
gradle :fabric:build -Pminecraft_version=1.21.4 -Pyarn_mappings=1.21.4+build.8 \
  -Ploader_version=0.16.10 -Pfabric_version=0.119.2+1.21.4

# Run a dev client:
gradle :fabric:runClient
gradle :neoforge:runClient
```

The compiled JARs land in `fabric/build/libs/` and `neoforge/build/libs/`.

## Releasing

1. Bump `mod_version` in `gradle.properties`.
2. Push a tag of the form `vX.Y.Z` (e.g. `v1.1.0`).
3. GitHub Actions builds every matrix entry in parallel and uploads all successful JARs to the release.

Or trigger the workflow manually from the Actions tab and pass a tag input.

## Installing

### Fabric / Quilt
1. Install [Fabric Loader](https://fabricmc.net/use/) (or [Quilt Loader](https://quiltmc.org/)).
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) (or [Quilted Fabric API](https://modrinth.com/mod/qsl)).
3. Drop the matching `tiertagger-fabric-<version>-mc<MC>.jar` into your `.minecraft/mods/` folder.

### NeoForge
1. Install [NeoForge](https://neoforged.net/) for your Minecraft version.
2. Drop `tiertagger-neoforge-<version>-mc<MC>.jar` into your `.minecraft/mods/` folder.

## License

MIT — see `LICENSE`.
