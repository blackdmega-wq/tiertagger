# TierTagger

A Minecraft mod that displays player tiers from the [OuterTiers](https://outertiers.com) website right next to player names in the in-game tab list. Inspired by [tiertagger on Modrinth](https://modrinth.com/mod/tiertagger).

When you open the player list (`Tab` by default), each player gets a coloured `[HT3]` style badge fetched live from the OuterTiers API.

## Features

- Live tier badges in the tab list, colour-coded by tier rank.
- Pick which gamemode to display: `overall`, `ogvanilla`, `vanilla`, `uhc`, `pot`, `nethop`, `smp`, `sword`, `axe`, `mace`, `speed`.
- Toggle peak-tier display.
- Async fetching with a per-player TTL cache (default 5 minutes), so the game never stalls.
- Configurable API base URL — point it at your own OuterTiers backend if you self-host.

## In-game commands

| Command                               | What it does                                          |
| ------------------------------------- | ----------------------------------------------------- |
| `/tiertagger`                         | Show current config                                   |
| `/tiertagger mode <gamemode>`         | Switch gamemode (e.g. `vanilla`, `uhc`, `nethop`)     |
| `/tiertagger toggle`                  | Show / hide tab badges                                |
| `/tiertagger peak`                    | Toggle showing the player's peak tier instead         |
| `/tiertagger refresh`                 | Clear cache and re-fetch                              |
| `/tiertagger api <url>`               | Override the API base URL                             |

Config is persisted in `.minecraft/config/tiertagger.json`.

## Project layout

```
tiertagger/
├── common/        — pure-Java logic shared by both loaders (config, cache, scoring)
├── fabric/        — Fabric Loader entry + mixin
└── neoforge/      — NeoForge entry + mixin
```

## Supported loaders & versions

The project is a multi-loader Gradle build. Pick the right toolchain & version pins in `gradle.properties`, then build the loader you want.

### Fabric

| MC version | Loader   | Yarn               | Fabric API           | Java |
| ---------- | -------- | ------------------ | -------------------- | ---- |
| 1.21.1     | 0.16.5   | 1.21.1+build.3     | 0.105.0+1.21.1       | 21   |
| 1.20.6     | 0.16.5   | 1.20.6+build.3     | 0.100.4+1.20.6       | 21   |
| 1.20.4     | 0.16.5   | 1.20.4+build.3     | 0.97.2+1.20.4        | 17   |
| 1.20.1     | 0.16.5   | 1.20.1+build.10    | 0.92.2+1.20.1        | 17   |
| 1.19.4     | 0.16.5   | 1.19.4+build.2     | 0.87.2+1.19.4        | 17   |
| 1.19.2     | 0.16.5   | 1.19.2+build.28    | 0.77.0+1.19.2        | 17   |
| 1.18.2     | 0.16.5   | 1.18.2+build.4     | 0.77.0+1.18.2        | 17   |

### NeoForge

| MC version | NeoForge version | Java |
| ---------- | ---------------- | ---- |
| 1.21.1     | 21.1.95          | 21   |
| 1.21.0     | 21.0.167         | 21   |
| 1.20.6     | 20.6.137         | 21   |
| 1.20.4     | 20.4.248         | 17   |

To switch versions: edit `gradle.properties` (`minecraft_version`, `yarn_mappings`, `loader_version`, `fabric_version`, `neoforge_version`, `java_version`) and rerun the build.

> Forge (legacy, pre-NeoForge fork) and Bedrock are not supported. NeoForge covers the modern Forge ecosystem from MC 1.20.4 onwards.

## Building

Requirements: JDK 17 or 21 (matching `java_version`) and Gradle 8.10+ (or generate the wrapper via `gradle wrapper`).

```bash
# Build everything for the version pinned in gradle.properties:
./gradlew build

# Build only one loader:
./gradlew :fabric:build
./gradlew :neoforge:build

# Run a dev client:
./gradlew :fabric:runClient
./gradlew :neoforge:runClient
```

The compiled jars land in `fabric/build/libs/` and `neoforge/build/libs/`.

## Installing

### Fabric
1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Drop `tiertagger-fabric-<version>.jar` into your `.minecraft/mods/` folder.

### NeoForge
1. Install [NeoForge](https://neoforged.net/) for your Minecraft version.
2. Drop `tiertagger-neoforge-<version>.jar` into your `.minecraft/mods/` folder.

## License

MIT — see `LICENSE`.
