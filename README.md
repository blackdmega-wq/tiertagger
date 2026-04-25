# TierTagger

A multi-loader Minecraft mod that displays player tiers from the [OuterTiers](https://outertiers.com) website right next to player names — both in the in-game **tab list** and as a **badge above each player's head**. Inspired by [tiertagger on Modrinth](https://modrinth.com/mod/tiertagger).

## What's new in 1.7.7

- **Actually fixed the black screen on MC 1.21.5+.** The 1.7.6 release still crashed because `PlayerNametagMixin1215` declared its inject method with `Object`-typed render-pipeline parameters, and Mixin's descriptor validator rejects mismatching types *before* it even consults the `require=0` flag. Fix: the mixin is removed entirely — tab-list badges (the main feature) work as before, badges above player heads on 1.21.5+ are temporarily disabled until the runtime `WorldRenderEvents` replacement lands. No more `InvalidInjectionException` on launch.

## What's new in 1.7.6

- **Fixed black screen on Minecraft launch (MC 1.21.5+).** Mixin injectors now degrade gracefully when a vanilla method signature drifts between MC versions instead of failing the entire client init. The mod stays loaded, falling features just no-op with a single `WARN` line in `latest.log`.
- **Per-jar Minecraft version pinning.** Each per-MC jar's `fabric.mod.json` now declares the exact Minecraft version it was compiled against (e.g. `>=1.21.11 <=1.21.11`). This prevents Fabric from loading a 1.21.11-targeted jar on, say, 1.21.1 — which was the most common cause of "I installed the mod and now my game just shows a black screen".
- **Bullet-proof `onInitializeClient`.** Each subsystem (config, core, screens, keybinds, commands) is now isolated; a single failure no longer aborts the whole mod load.
- **Build heap restored to `-Xmx3G`** so CI builds for newer MC versions stop OOMing during Loom remap.

## What's new in 1.5.2

- **„Profile of the targeted player" keybind.** Look at any player within 64 blocks and press the new bind (default **J**) to open their full tier profile. Rebindable under *Options → Controls → Key Binds → TierTagger*, or "Not bound" to disable.

## What's new in 1.5.1

- **Configurable keybind** — under *Options → Controls → Key Binds → TierTagger* you can bind a key (default **K**) that opens the config screen instantly. Pick any key you like, or set it to "Not bound" to disable.

## What's new in 1.5.0

- **Open the config from Minecraft Options.** With Mod Menu installed (Fabric) or via the built-in Mods screen (NeoForge), TierTagger now appears under *Options → Mods → TierTagger → Configure*, no command required.
- **Fixed `/tiertagger config` and `/tiertagger profile <player>`.** They now reliably open the screen instead of being closed again by the chat-screen race that swallowed them in 1.4.x.
- **`/tiertagger compare <a> <b> [tierlist]`.** Optional last argument lets you narrow the comparison to one tier list — `mctiers`, `outertiers`, `pvptiers`, `subtiers`, or `all` (default).
- **Best-effort builds for MC 1.21.9 / 1.21.10 / 1.21.11.** Added to the build matrix as experimental rows; if they compile against the new vanilla API surface they ship in the release, otherwise the stable 1.21.1 – 1.21.8 builds still ship.

## Features

- **Tab-list badges** — coloured `[HT3]`-style badges next to every player name when you press Tab.
- **Nametag badges above heads** — the same badge floats above the player's head in-world (the signature feature of the original mod).
- **Pick your gamemode** — `overall`, `ogvanilla`, `vanilla`, `uhc`, `pot`, `nethop`, `smp`, `sword`, `axe`, `mace`, `speed`.
- **Fall-through to highest tier** — if a player is unranked in your selected gamemode, the badge automatically shows their highest tier across all modes (toggleable).
- **Peak-tier mode** — option to show the player's lifetime peak instead.
- **Profile screen** — view a player's full breakdown across every gamemode in a dedicated GUI screen (no third-party UI library required).
- **In-game settings screen** — change every option from a clean GUI (`/tiertagger config`).
- **Crash-safe rendering** — the cosmetic mixins are wrapped in defensive try/catch so the mod can never crash your game when you press Tab or render a nametag.
- **Async fetching with TTL cache** — the game never stalls on network calls.
- **Configurable badge format** — `bracket` (`[HT3]`), `plain` (`HT3`), or `short` (`H3`), with optional colour off.
- **Configurable API base URL** — point the mod at your own OuterTiers backend.

## In-game commands

| Command | What it does |
| --- | --- |
| `/tiertagger` | Show the current settings |
| `/tiertagger help` | List every command |
| `/tiertagger version` | Show the mod version |
| `/tiertagger status` | Show settings + cache TTL |
| `/tiertagger config` | Open the GUI settings screen |
| `/tiertagger profile <player>` | Open a player's tier breakdown |
| `/tiertagger lookup <player>` | Print a player's tiers in chat |
| `/tiertagger clear <player>` | Forget a single player from the cache |
| `/tiertagger mode <gamemode>` | Switch the active gamemode |
| `/tiertagger toggle` | Show / hide tab-list badges |
| `/tiertagger nametag` | Show / hide the badge above player heads |
| `/tiertagger fallthrough` | Toggle automatic fall-through to the highest tier |
| `/tiertagger peak` | Toggle showing peak tier instead |
| `/tiertagger color` | Toggle coloured badges |
| `/tiertagger format <bracket\|plain\|short>` | Change the badge format |
| `/tiertagger ttl <seconds>` | Set the cache TTL |
| `/tiertagger refresh` | Clear the cache and re-fetch |
| `/tiertagger reload` | Reload config from disk |
| `/tiertagger reset` | Reset config to defaults |
| `/tiertagger api <url>` | Override the API base URL |

Config is persisted in `.minecraft/config/tiertagger.json`.

## Project layout

```
tiertagger/
├── common/        — pure-Java logic shared by both loaders (config, cache, scoring)
├── fabric/        — Fabric Loader entry, mixins, screens, command
└── neoforge/      — NeoForge entry, mixins, screens, command
```

## Supported loaders

| Loader | How |
| --- | --- |
| Fabric | Native — install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api). |
| **Quilt** | **Native — Quilt loads Fabric mods directly. Just install [Quilt Loader](https://quiltmc.org/) and [Quilted Fabric API](https://modrinth.com/mod/qsl), then drop in the Fabric build.** A `quilt.mod.json` is bundled so the mod is recognised as Quilt-aware. |
| NeoForge | Native — install [NeoForge](https://neoforged.net/) and use the NeoForge build. |

## Pre-built jars

Every release tag (`v*`) automatically triggers a GitHub Actions [release workflow](.github/workflows/release.yml) that builds jars for **every supported MC version × loader combo** in parallel and uploads them to the matching [GitHub Release](https://github.com/blackdmega-wq/tiertagger/releases) — so you can grab a finished jar from the Releases page without having to run Gradle yourself.

The current release matrix covers:

- **Fabric** (also runs on Quilt): MC 1.21.1, 1.21.2, 1.21.3, 1.21.4
- **NeoForge**: MC 1.21.1, 1.21.4

To cut a release, push a tag:

```
git tag v1.2.0
git push origin v1.2.0
```

## Building locally

```
./gradlew :fabric:build       # produces fabric/build/libs/tiertagger-fabric-<ver>.jar
./gradlew :neoforge:build     # produces neoforge/build/libs/tiertagger-neoforge-<ver>.jar
```

You can override the target MC/loader versions on the command line — see `gradle.properties` for the available `-P` flags (`-Pminecraft_version=…`, `-Pyarn_mappings=…`, `-Pfabric_version=…`, `-Pneoforge_version=…`).

## License

MIT — see [LICENSE](LICENSE).
