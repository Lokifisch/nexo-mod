# Nexo Mod — Roadmap

Status: **Phase 1 scaffold exists and builds.** Loader and MC version are
locked (below); mod injection (Phase 5) was built first in `Client/`, then
Phase 1 (Fabric/Gradle project scaffold) actually got stood up once a real
feature needed it. See root `CLAUDE.md` for the project-wide constraints
this roadmap operates under — most importantly: **nothing in `Mod/` may be
copied, adapted, or derived from `Essential-Mod/`.** Every feature
described below is built from public, general knowledge of how these
systems typically work (the same way the Microsoft OAuth device-code flow
was already implemented in `Client/` from public documentation, not by
reading Essential's source).

**Toolchain note (2026-07-11):** Minecraft 26.1+ ships unobfuscated — no
Yarn or official Mojang mappings exist or are needed for it, and Loom runs
in non-remapping mode. Gradle/Loom setup and version pins
(`loader 0.19.3`, `loom 1.17-SNAPSHOT`, `fabric-api 0.154.2+26.1.2`,
Java 25) are proven working — confirmed via a real `./gradlew build` and
`./gradlew runClient` (reached deep into `Minecraft.<init>` with `nexomod`
loaded, mixins applied, and its resources parsed cleanly; the only crash
hit was an unrelated missing system library — `libflite.so`, used for the
Narrator accessibility feature — a gap in this dev machine's Loom run
environment specifically, not something the real launcher hits).

**First real feature shipped:** a small first-party badge (the Nexo logo,
via a custom bitmap-font glyph) prepended to your own name in the tab
list (`PlayerTabOverlay`) and your own nametag
(`LivingEntityRenderer#extractRenderState`) — see
`Mod/src/main/java/dev/nexoclient/nexomod/`. Scoped to your own name only:
showing it on *other* players who also have Nexo Mod needs a network
protocol to broadcast who has it installed, which doesn't exist yet (see
Phase 2).

---

## Phase 0 — Foundational decisions — DECIDED

### 0.1 Mod loader — **Fabric**

| Option | Fit |
|---|---|
| **Fabric** (chosen) | Lightweight, fastest to update to new MC versions, huge QoL-mod ecosystem (Sodium/Lithium/etc.), simplest API surface. This is what nearly every "client-style" mod (cosmetics + HUD + QoL, as opposed to content/gameplay mods) targets first. |
| NeoForge | Heavier, better suited to content-heavy mods (new items/blocks/dimensions). Slower historically to reach day-one MC updates. Not a great fit for what we're building. |
| Forge | Legacy, actively being succeeded by NeoForge. No reason to start here in a new project. |

Single loader target for v1. Multi-loader (via Architectury or parallel
builds) is a realistic Phase 6+ stretch goal once the core mod is stable,
not a v1 requirement.

### 0.2 Minecraft version — **26.1.2**

Single version supported at v1, matching the instance already set up for
testing (`Fabric 26.1.2`). Fabric's update cadence makes chasing latest
realistic for a solo/small project later; supporting a version range
multiplies testing surface for little payoff until the mod has real users
asking for it.

### 0.3 Account/backend architecture — the big one

The original ask was "a login system similar to Essential." Essential-style
clients (Essential, LabyMod, etc.) all follow the same public, well-known
shape:

1. Client proves Minecraft ownership via the same Microsoft OAuth2
   device-code flow the vanilla launcher uses (device code → poll → Xbox
   Live token → XSTS token → Minecraft Services token → profile). `Client/`
   already implements exactly this flow in Rust
   (`packages/app-lib/src/state/minecraft_auth.rs`) — useful as an
   architectural reference for the *shape* of the flow, not code to port
   (the Mod runs in-JVM, so this gets reimplemented in Java/Kotlin from the
   same public Microsoft/Xbox API docs).
2. Client sends that verified token to **the mod's own backend**, which
   double-checks it server-side and mints a Nexo-specific session (JWT or
   similar) tied to the player's UUID.
3. That backend is what makes cosmetics/social features *shared* — e.g.
   another player with Nexo Mod installed can see your equipped cape
   because both clients talk to the same backend to resolve "what is UUID
   X wearing right now."

Step 3 is the part worth pausing on: **it requires running and maintaining
a real backend service** (hosting, a database, uptime, eventually
moderation if anything is user-uploaded). That's a standing commitment,
not a one-time build. Three ways to go:

- **A — No backend (recommended for v1).** Cosmetics are bundled in the
  mod jar itself; every installed client has the same shared set and
  renders them locally. "Login" in v1 becomes purely cosmetic/identity
  (show your MC profile in a Nexo UI panel, no server round-trip). Zero
  hosting cost, zero moderation surface, ships fastest, and still
  delivers the "feels like a client" polish. Cosmetics only visible to
  other Nexo users is *not* possible under this option, since there's no
  shared state to sync.
- **B — Minimal backend from day one.** Small REST service (could live
  cheaply on a VPS or a serverless platform) doing just: session issuance
  + "who owns which cosmetics" + a lookup endpoint clients poll for nearby
  players. Real infra, but scoped tight. Makes cosmetics visible
  cross-player.
- **C — Defer the backend, build toward it.** Ship A now, but design the
  cosmetic system's data model so a backend can be slotted in later
  without a rewrite (local cosmetic selection becomes "my own override" on
  top of a future "what does the backend say" layer).

**Recommendation: A now, structured as C** — ship without a backend, but
don't paint ourselves into a corner if you want shared cosmetics later.

---

## Phase 1 — Core mod scaffold

Goal: a mod that builds, loads, and does nothing interesting yet, but
proves the whole pipeline end to end.

- Fabric mod project (Gradle + Fabric Loom), `fabric.mod.json` metadata,
  dev-run environment.
- Marked **client-only** (`"environment": "client"`) — no server-side
  install requirement, matching the "works like a client" goal. Playing on
  a vanilla server with Nexo Mod installed should just work; the mod
  should never require the server to also have it.
- Minimal config system (JSON/TOML file on disk) + a bare in-game config
  screen (likely via Cloth Config or a small custom screen).
- One trivial client-side hook (e.g. a debug HUD line) to prove
  mixins/events fire correctly in dev and in a real launched instance.
- Version metadata exposed in a way Phase 5's installer can query later
  ("what Nexo Mod build targets MC 1.21.x").

## Phase 2 — Login system

- Microsoft OAuth2 device-code flow, implemented client-side in Java
  against Microsoft's public identity endpoints (same public flow as
  Phase 0.3, #1).
- Secure local token storage (OS keychain where available, encrypted file
  fallback — same problem `Client/`'s Rust side already solved once, worth
  reviewing for the storage *approach*, not the code).
- In-game sign-in UI: a panel (title screen or a Nexo menu) showing
  sign-in state, MC profile (name/skin head), sign-out.
- If Phase 0.3 chose B: also stand up the minimal backend and wire session
  exchange. If A: this phase ends at "shows you're signed in," no network
  service beyond Microsoft's own APIs.

## Phase 3 — UI framework + first client features

Foundational because cosmetics and settings both build on it.

- A small internal UI toolkit: reusable screen/widget base, a HUD overlay
  layer that other features register into.
- 2-3 genuinely useful QoL features to prove the pattern and give the mod
  something to actually offer at this stage — candidates to pick from
  (all public, common "client mod" features, not Essential-specific):
  keystrokes HUD, a zoom key, coordinates/FPS overlay, toggleable
  cinematic/freecam camera. Pick 2-3, not all, for v1.
- Settings screen wired to Phase 1's config system.

## Phase 4 — Cosmetics system

- Rendering pipeline: player-render mixins/layers for attachable cosmetics
  (capes, back bling, hats, etc.), a cosmetic-selection screen.
- v1 (Option A): cosmetics bundled in the jar, selection is purely local —
  you see your own equipped cosmetics; other Nexo users only see theirs
  unless Phase 0.3 later moves to B/C.
- If/when a backend exists: extend with per-account ownership and a
  lightweight "what is nearby player X wearing" poll so equipped cosmetics
  become visible to other Nexo Mod users too. Flagged here as the
  natural Phase 4 extension, not committed to for v1.

## Phase 5 — Mod injection (installer integration, lives in `Client/`)

This is the "easy install" half of the pitch and where `Client/` and
`Mod/` actually meet.

- `Client/`'s content-install pipeline (`packages/app-lib/src/install`,
  `state/instances/content.rs`, the `apply_content_install`/
  `apply_content_update` commands) already knows how to sync arbitrary
  content into an instance. Nexo Mod becomes a first-party content source
  through that same machinery rather than a bespoke new system — install
  = "make sure Fabric Loader is present at the right version, then drop
  Nexo Mod's jar in, like any other content install."
- Version resolution: given an instance's MC version, pick the matching
  Nexo Mod build (using the metadata exposed back in Phase 1).
- Update flow: keep installed copies current as new Nexo Mod versions
  ship.
- Stretch: a "Play with Nexo" one-click path — create/select an instance,
  ensure Fabric + Nexo Mod are present, launch — as a shortcut on top of
  the above rather than a separate mechanism.

## Phase 6 — Polish, testing, distribution

- Cross-platform pass (Windows/macOS/Linux), and a real multiplayer
  compatibility check — confirm the mod doesn't misbehave on servers that
  don't have it, and doesn't trip common anti-cheat heuristics (worth
  explicitly testing, since client-side rendering/HUD mods sometimes do).
- Publish the jar via Modrinth's own project hosting so `Client/`'s
  installer can fetch it exactly like third-party content — no bespoke
  distribution channel needed.
- Versioning/changelog convention tied to the MC-version support window
  from Phase 0.2.

---

## Suggested v1 cut

If the goal is "have something real to show/use" rather than "build
everything above at once": **Fabric, latest MC version, Option A (no
backend), Phases 1 → 3 → 2 → 4(v1) → 5**, deferring Phase 6 polish and any
backend work until the core loop (install → launch → look/feel like a
client) is proven end to end.

## Open questions for you

1. Confirm Fabric + latest MC version (Phase 0.1/0.2), or override.
2. Confirm Option A (no backend) for v1 cosmetics/login, or say if shared
   cross-player cosmetics matter enough to take on backend hosting now.
3. Which 2-3 QoL features from Phase 3 you actually want first — this is
   otherwise the most "pick anything" part of the plan.
