# Nexo Mod

The Fabric mod half of [Nexo Client](https://github.com/Lokifisch/nexo-client) — a personal Minecraft client project that pairs this mod with a native launcher, instead of a monolithic custom launcher. Targets **Minecraft 26.1.2** (Fabric).

> **Status:** alpha. See [Releases](../../releases) for builds.

## Features

- **LAN-over-internet tunneling** — share a singleplayer world with friends over the internet without port forwarding, via a QUIC relay. Uses [e4mc](https://github.com/vgskye/e4mc-minecraft-architectury)'s public relay by default; swappable via config. Cross-platform (Linux/Windows/macOS) native codec bundled into the jar.
- **Microsoft account sign-in** — browser-based OAuth device flow, no separate launcher account juggling. Multi-account storage with instant switching, offline-account support, and protection against logging out of whichever account actually launched the game. The store is AES-256-GCM encrypted under a key **derived from this machine's hardware** (CPU, mainboard, and GPU identity) rather than one written to disk — so a config folder copied into a shared modpack can't be decrypted anywhere but the machine it came from.
- **Position obscuring** — a set of anti-doxxing measures for streaming and public servers, under Nexo Settings → Position Obscuring. Presets are None / Full / Custom:
  - *Obscure F3 Coordinates* — shifts the X/Z shown on the debug screen by a random 3,000–700,000 blocks, re-rolled every time you join a world or server.
  - *Match Block Rotation* — random block textures (grass, stone, sand…) normally pick their variant from your real position, so a screenshot can be brute-forced back to real coordinates. This re-seeds them from the fake position so the visible pattern agrees with what F3 shows.
  - *Hide Bedrock Pattern* — the bedrock mix at the bottom of the world is generated from the world seed, so a screenshot of it can also give away coordinates. This renders everything at Y -60 and below as solid bedrock. Purely visual: mining, collision, and everything server-side are unaffected, and there's a [Sodium](https://modrinth.com/mod/sodium)-specific path so it works with Sodium's meshing pipeline too.
  - Independently, on servers with more than 50 players, the first F3 press while coordinates are *not* obscured shows a warning instead of the debug screen; press F3 again within 10 seconds to open it anyway.
- **Discord Rich Presence** — shows the world or server you're actually in (not merely "playing Minecraft", which is all the launcher could know on its own). On multiplayer, the activity carries a join secret so friends see a Join button; clicking it works if their own Nexo Mod is already running. Toggleable from Nexo Settings.
- **Neon menu re-skin** — rounded, glowing black/neon buttons everywhere in the game; animated starfield or Matrix-rain menu backgrounds (configurable colour and density, with mouse parallax); a bundled modern font replacing vanilla's pixel font. Applies to every menu-style screen — including ones added by other mods — not just vanilla's own. Menus and font toggle independently, so you can keep the neon buttons and vanilla's font or vice versa, and both revert fully to stock from in-game Nexo Settings → Appearance.
- **Macros** — bind chat commands/messages to keys, with send-all, cycle, random, repeat-while-held, and type-without-sending modes, plus a few placeholders (`%myname%`, `%pos%`, `%x%`/`%y%`/`%z%`, `%clipboard%`). Configured from in-game Nexo Settings → Macros.
- **Chat archive and auto-filter** — every line you see can be written to a local database (off by default) and searched later by text, server, sender and time window, from Nexo Settings → Chat or a keybind. A separate pattern list matches incoming chat and either hides or highlights it; a broken or missing filter shows chat unchanged rather than swallowing it. Both are backed by the native core, so they simply don't appear on platforms it isn't built for.
- **Quick server switching** — a short list of favourite servers you can jump between without going back through the pause menu and the multiplayer list. Reachable from Nexo Settings → Quick Servers or from a keybind while you're in a world; it disconnects the way the pause menu's Disconnect button does and then connects.
- **Clean-screenshot toggle** — one keybind that takes everything Nexo draws off the screen at once: badges, the inventory watermark, the neon re-skin. In-memory only, so a restart never leaves you looking at a mod that seems uninstalled, and there's no resource reload.

### Nexo+ (full jar only)

The mod ships as two jars from one source tree: `nexomod` and `nexomod-light`. The light jar contains nothing that supplies information or automation the vanilla game doesn't — no code, no strings, and its own build of the native core without the features below. It is the one to hand a server admin. The two jars refuse to load together.

- **Sound radar** — turns positioned sound packets into a bearing on a ring around the crosshair, colour-coded by category, fading with age and distance, optionally labelled with the same subtitle text vanilla uses. Configurable range and per-category filtering.
- **Armor HUD** — armour pieces and off-hand item down the right edge with remaining durability, and a warning colour below a threshold you set.
- **Client-side time and weather** — pin what time of day and what weather your client *draws*. Purely visual: the server keeps its own clock and its own weather, nothing is sent, and the day count keeps advancing so the moon phase still changes.
- **Chunk memory** — records a terrain outline for every chunk the server sends you, in the native store, and can ask asynchronously how much it remembers around you.
- **Ghost mode** — the clean-screenshot toggle plus the overlays only this jar draws (sound radar, armour HUD, bedrock outlines). It shares the same switch rather than adding a second one, so no render path can be left behind.
- **State-triggered macros** — rules that run one of your macros when something changes: a tool about to break, low health or hunger, a full inventory. Each fires once on the transition, has a cooldown, and never fires while a screen is open. A `TOOL_LOW` rule can also select the healthiest matching hotbar slot — the same thing the scroll wheel does. Nothing here clicks, swings, or uses an item for you.
- **Bedrock hole finder** — finds gaps sealed inside the world's bedrock layers (Overworld floor, Nether floor, Nether roof) in the chunks you have loaded, and outlines every enclosed block through the terrain as one merged, colour-cycling shape. It works by flood-filling through anything that *isn't* bedrock and keeping only regions that close off within a size range you set with two sliders — a per-column test can't tell a real gap from the four-deep dents world generation leaves roughly twenty times per chunk. No world seed needed, so it also finds openings other players broke. A find is announced once ever, per world and dimension, via chat, a toast, and a chime (each independently toggleable); coordinates can be withheld for streaming, and are withheld automatically while Position Obscuring is hiding your F3 coordinates. While the finder is on, the block texture vanilla paints over the screen when your head is inside a block is suppressed, so the outlines stay visible while you dig in the layer.

## Requirements

- Minecraft 26.1.2
- [Fabric Loader](https://fabricmc.net/) 0.19.3+
- Java 25+

## Building from source

```sh
./gradlew build
```

The built jar lands in `build/libs/`. Drop it into your instance's `mods/` folder alongside Fabric API.

## Releases

[Nexo Client](https://github.com/Lokifisch/nexo-client)'s in-app installer fetches releases directly from this repo's GitHub Releases API, so every release must publish two assets:

- the built jar (`nexomod-<version>.jar`)
- `manifest.json`, declaring what it targets:
  ```json
  {
  	"minecraft_version": "26.1.2",
  	"loader": "fabric",
  	"mod_version": "0.1.0"
  }
  ```

The installer reads `manifest.json` to decide compatibility — it never assumes a fixed target version, so this must stay accurate for every release.

## Third-party code

This mod adapts real, working code from a couple of MIT-licensed open-source projects rather than reimplementing their protocols from scratch — see [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) for exactly what was ported from where and full license text:

- [vgskye/e4mc-minecraft-architectury](https://github.com/vgskye/e4mc-minecraft-architectury) — LAN tunnel relay client
- [axieum/authme](https://github.com/axieum/authme) — Microsoft OAuth sign-in flow
- [JnCrMx/discord-game-sdk4j](https://github.com/JnCrMx/discord-game-sdk4j) (MIT) — pure-Java implementation of Discord's local RPC protocol, used as a dependency (not vendored) for Rich Presence
- [Fix85/SelfNametag](https://github.com/Fix85/SelfNametag) — own-nametag visibility technique
- [Noto Sans](https://fonts.google.com/noto) (Google, SIL OFL) — bundled UI font

## License

MIT — see [`LICENSE`](LICENSE).
