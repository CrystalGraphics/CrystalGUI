---
name: cross-version
description: Add a feature, platform service or Minecraft-facing code (commands, permissions, entities, containers, events, networking, a new service) to CrystalGUI/CrystalGraphics so it builds and runs on EVERY supported Minecraft version and loader (Forge 1.7.10, 1.8.8-1.21.11, NeoForge 1.20.2-1.21.11, Fabric 1.14.4-1.21.11) without breaking any. Use whenever a change touches runtime/mc/**, a loader, a platform seam, or needs Minecraft API.
---

# Cross-version change

One jar, ~80 Minecraft nodes. Work in this order; the expensive mistake is finding a version break at
runtime, and every break is findable up front. Reference: `docs/CGUI_CROSS_VERSION.md` (how, with
examples) and `docs/CGUI_BUILD.md` (build, nodes, smoke tests).

## 1. Contract first, in `core` — no Minecraft

- Write down the behaviour, the side (server / client / both), and the **absent value**: what happens on
  a dedicated server, without the language mod, or on a version that cannot support it.
- Owner: rendering/GL/input/text → **CrystalGraphics** (do every step there first); anything else →
  **CrystalGUI**.
- Logic goes in `core`, names no Minecraft/loader/LWJGL type (import guard), crosses as ids and value
  records, uses the Java 8 API (it runs on Java 8 for 1.7.10, legacy Forge, Forge ≤1.16). Test it:
  `core/src/headlessTest` or `:core:test --tests "<Class>"`.

## 2. Look before writing — in this order

1. **The nearest existing feature** — copy its shape and its version boundaries:
   network → `CgNetworkChannel` impls (`CrystalGUIForge.Network`, `CrystalGUINeoForge`,
   `CrystalGUIFabricCommon`, `NetworkChannelLegacy`, `NetworkChannel1710`); game events →
   `LifecycleCrystalGUI` + each loader's `Events`; permissions → `WorkspaceHostModern.McRoles`, legacy
   `Game`; client input/HUD → `mc.modern.client`. `grep -n "//? if" <that file>` shows its breaks.
2. **Every node's API, in seconds**:
   ```bash
   python CrystalGraphics/singlejar-logic/mcapi.py <Class> [memberRegex]    # spellings + version runs
   python CrystalGraphics/singlejar-logic/mcapi.py find '<regex>' [--in net/minecraft/...]
   ```
   Modern names are Mojang's; legacy nodes use MCP names (find them with `find`); 1.7.10 is not in it
   (read its host and `runtime/mc/1710/build/rfg/` sources). A `common` node 1.17.1-1.20.1 also sees
   Forge's API — never call loader API from common.
3. **Behaviour**, when signatures are not enough: a real node's `extractMcSources` (`build/mc-src`), or
   `research_repos/mc1201_sources/`.

Never run `generateStubDatabase` to read APIs; it rewrites `stubs.zip`.

## 3. Break table — before any host code

For every touch point (registration, event, query, client hook) run `mcapi.py` and write a table:
touch point → each spelling → the node runs it holds on. **Show it to the user or in your notes before
implementing.** Each spelling is one directive branch, each run boundary its predicate. A node with no
spelling gets an explicit decision: another route, or the absent value, with the reason. Never drop a
version silently.

## 4. Choose the seam

| Need | Seam |
|---|---|
| core constructs the consumer | an interface passed in (`HostServices`, `WorkspaceRoles`) |
| optional, read from many places | `CgService.of("crystalgui:x", X.NONE)`; `CgPlatform.provide` / `get` |
| the renderer cannot work without it | a `CgPlatformService` method — all FIVE implement it: `PlatformService1710`, `PlatformServiceLegacy`, `PlatformServiceModern`, `PlatformServiceHarness`, `TestPlatformService` |
| "when the game does X" | a `LifecycleCrystalGUI` method (+ legacy/1.7.10 lifecycles), called from every loader's event |

Reuse before adding: `CgNetworkChannel` for traffic, `net.window` (`Networked`) for server-driven UI.

## 5. Implement every era, in one pass, from the table

| Era | Where |
|---|---|
| Modern shared (Forge+NeoForge+Fabric 1.13.2-1.21.11) | `runtime/mc/modern/common/src/main/java/com/crystalgui/mc/modern/` |
| Modern registration | `runtime/mc/modern/{forge,neoforge,fabric}/src/...` entry classes — one-line forwards |
| Legacy Forge 1.8.9/1.10.2/1.12.2 | `runtime/mc/legacy/forge/src/...`; renamed members via `Game` / `client.ClientGame` |
| 1.7.10 | `runtime/mc/1710/src/...` |
| Language-mod half | the same trees' `src/lang` |

- Directives only in `<branch>/src`. Sources are at the active node's spelling (1.20.1); other branches
  sit in `/* */` — edit them as carefully. **Never commit a switched active node.**
- Client-only code in a `client` package, unreachable from server paths (method references included).
- Network handlers hop to the main thread first.
- No reflection or strings naming Minecraft members (not remapped). Mixins only without an event, `require = 1`.
- A decision in a loader file is a bug: move it to core.

## 6. Verify — cheapest first, report each result

1. `./gradlew checkAllTargets` (and `-p CrystalGraphics` if touched). Fix by family: one directive
   fixes a whole run.
2. The core tests.
3. `serverSmoke -PcgAcceptEula` on `forge:1.16.5`, `forge:1.20.1`, `neoforge:1.20.4`, `fabric:1.20.1`,
   `neoforge:1.21.11`, and `:runtime:mc:1710:serverSmoke`. Run in the background; "Done" with no
   `RESULT` line means hung.
4. `./gradlew singleJar languageJar checkSingleJar checkLanguageJar`.
5. One sweep `prodSmoke` (needs `local.properties`; say so if absent): `./gradlew prodSmoke` with no
   `-PcgTargets` — one client per Minecraft major plus the first and last of 1.19/1.20/1.21, those of
   1.20 and 1.21 on all three loaders: twenty-four, four at a time. Never every instance (`-PcgTargets=all`, 104) for a routine check. Open the captures in
   `build/prodSmoke/`, grep each instance's `logs/latest.log` (`fml-client-latest.log` below 1.13) for errors.
6. Anything no smoke exercises: run it on the oldest and newest node of each loader, and say which.

**Something crashes or misbehaves on a Prism client?** Do not iterate through `prodSmoke` (~10 min a
cycle). Reproduce it in that node's dev run — `:runtime:mc:modern:<loader>:<version>:runClient
-Dcrystalgui.autotest=true "-Dcrystalgui.autotest.world=*" ...` (every `-Dcrystalgui.*` is forwarded;
copy a save into `runs/client/saves/`), or `runServer`/`serverSmoke` — fix it there, then confirm with
one `prodSmoke`. `docs/CGUI_BUILD.md` § *A failure on an installed client is fixed in a dev run*.

Report what ran, what passed, what was skipped and why. Never claim a version works that nothing ran on.
A runtime surprise the break table missed goes into the table and, if it is not a spelling, into
`docs/CGUI_INVARIANTS.md`.
