# Nexo Mod

The Fabric mod half of [Nexo Client](https://github.com/Lokifisch/nexo-client)(Currently Private) — a personal Minecraft client project that pairs this mod with a rebranded install/launch app, instead of a monolithic custom launcher. Targets **Minecraft 26.1.2** (Fabric).

> **Status:** alpha. See [Releases](../../releases) for builds.

## Features

- **LAN-over-internet tunneling** — share a singleplayer world with friends over the internet without port forwarding, via a QUIC relay. Uses [e4mc](https://github.com/vgskye/e4mc-minecraft-architectury)'s public relay by default; swappable via config. Cross-platform (Linux/Windows/macOS) native codec bundled into the jar.
- **Microsoft account sign-in** — browser-based OAuth device flow, no separate launcher account juggling. Encrypted (AES-256-GCM) multi-account storage with instant switching, offline-account support, and protection against logging out of whichever account actually launched the game.
- **Neon menu re-skin** — rounded, glowing black/neon buttons everywhere in the game; animated starfield or Matrix-rain menu backgrounds (configurable, with mouse parallax); a bundled modern font replacing vanilla's pixel font. Fully toggleable back to stock vanilla menus from in-game Nexo Settings.

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
- [Fix85/SelfNametag](https://github.com/Fix85/SelfNametag) — own-nametag visibility technique
- [Noto Sans](https://fonts.google.com/noto) (Google, SIL OFL) — bundled UI font

## License

MIT — see [`LICENSE`](LICENSE).
