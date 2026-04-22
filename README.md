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

## Supported Minecraft versions

The mod is a Fabric client mod targeting Java 21. It is built per Minecraft version by editing `gradle.properties`. Tested combinations:

| MC version | Loader   | Yarn               | Fabric API           |
| ---------- | -------- | ------------------ | -------------------- |
| 1.21.1     | 0.16.5   | 1.21.1+build.3     | 0.105.0+1.21.1       |
| 1.20.4     | 0.16.5   | 1.20.4+build.3     | 0.97.2+1.20.4        |
| 1.20.1     | 0.16.5   | 1.20.1+build.10    | 0.92.2+1.20.1        |

To build for a different version: edit the four `*_version` lines in `gradle.properties` and rerun the build.

> Note: this is a Fabric mod. Forge / NeoForge / Bedrock are not currently supported.

## Building

Requirements: JDK 21 and either Gradle 8.10+ or the Gradle wrapper.

```bash
# Generate the wrapper once (only needed if gradle-wrapper.jar is missing):
gradle wrapper --gradle-version 8.10

./gradlew build
```

The compiled mod jar lands in `build/libs/tiertagger-<version>.jar`.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Drop `tiertagger-<version>.jar` into your `.minecraft/mods/` folder.
4. Launch Minecraft, open the tab list, enjoy.

## Development run

```bash
./gradlew runClient
```

## License

MIT — see `LICENSE`.
