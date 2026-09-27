---
name: cross-version
description: Add a feature, platform service or Minecraft-facing code (commands, permissions, entities, containers, events, networking, a new service) to CrystalGUI/CrystalGraphics so it builds and runs on EVERY supported Minecraft version and loader (Forge 1.7.10, 1.8.8-1.21.11, NeoForge 1.20.2-1.21.11, Fabric 1.14.4-1.21.11) without breaking any. Use whenever a change touches runtime/mc/**, a loader, a platform seam, or needs Minecraft API.
---

# Cross-version change

You are changing code that must work on ~80 Minecraft nodes from one jar. Follow these steps in order;
do not skip the verification. Reference: `docs/CGUI_CROSS_VERSION.md` (how) and `docs/CGUI_BUILD.md`
(build, nodes, smoke tests). Read both before step 2 if you have not this session.

## 1. Pin down the contract (no Minecraft yet)

- Write down: what behaviour, which side (server / client / both), which loaders and versions it must
  reach, and what happens when it is absent (dedicated server, no language mod, no GL).
- Decide the owner: rendering/GL/input/text → **CrystalGraphics**; everything else → **CrystalGUI**.
  If the contract is CrystalGraphics', do every step there first.

## 2. Put the behaviour in `core`

- The logic lives in `core` (or CrystalGraphics `core`) and names **no** Minecraft, loader or LWJGL type
  (the import guard enforces it). Players/entities cross as ids; an object a loader must get back is an
  opaque `Object`.
- Java 8 API only in anything that runs in game (core runs on Java 8 for 1.7.10, legacy Forge, Forge ≤1.16).
- Tests: `core/src/headlessTest` for server-side logic, `core/src/test` otherwise. Run them scoped:
  `./gradlew :core:test --tests "<Class>"`.

## 3. Choose the seam

| Need | Seam |
|---|---|
| core constructs the consumer | an interface passed in (`HostServices`, `WorkspaceRoles` style) |
| optional, read from many places | a `CgService` slot: `CgService.of("crystalgui:x", X.NONE)`, `CgPlatform.provide/get` |
| the renderer cannot work without it | a `CgPlatformService` method — implement in all FIVE: `PlatformService1710`, `PlatformServiceLegacy`, `PlatformServiceModern`, `PlatformServiceHarness`, `TestPlatformService` |
| "when the game does X" | a method on `LifecycleCrystalGUI` (modern) + the legacy/1.7.10 lifecycles, called from every loader's event |

Reuse before adding: `CgNetworkChannel` for any traffic, `net.window` (`Networked`) for any UI a server
drives, `LifecycleCrystalGUI` hooks for ticks and joins.

## 4. Implement per era — every one

| Era | Where |
|---|---|
| Modern shared (Forge+NeoForge+Fabric 1.13.2-1.21.11) | `runtime/mc/modern/common/src/main/java/com/crystalgui/mc/modern/` |
| Modern registration | `runtime/mc/modern/{forge,neoforge,fabric}/src/...` entry classes — one-line forwards only |
| Legacy Forge 1.8.9/1.10.2/1.12.2 | `runtime/mc/legacy/forge/src/...`, renamed members via `Game` / `client.ClientGame` |
| 1.7.10 | `runtime/mc/1710/src/...` |
| Language mod half | the same trees' `src/lang` |

Rules while writing:
- Stonecutter directives (`//? if >=1.20.2 {` … `//?} else {` … `//?}`) only in `<branch>/src`. Sources are
  at the active node's spelling (1.20.1); other branches sit in `/* */` and must be edited just as
  carefully. **Never switch the active node** in a commit.
- Look up each version's API instead of guessing: `unzip -p CrystalGraphics/singlejar-logic/stubs.zip
  api/<package>.sig | grep ...` (blocks tagged `in <set>`, see `sets.txt`). Survey every break first,
  then write all directives in one pass.
- Client-only code in a `client` package; never reachable from a server code path, including through a
  method reference in a shared method.
- Network handlers hop to the main thread before touching state.
- No reflection or strings naming Minecraft members (not remapped). Mixins only when no event exists,
  with `require = 1`.
- A decision in a loader file is a bug: move it to core.

## 5. Verify — report each result

1. `./gradlew checkAllTargets` — must be green (0 errors). CrystalGraphics too if touched: `-p CrystalGraphics`.
2. Scoped tests for the logic.
3. `serverSmoke -PcgAcceptEula` on `forge:1.16.5`, `forge:1.20.1`, `neoforge:1.20.4`, `fabric:1.20.1`,
   `neoforge:1.21.11` (`:runtime:mc:modern:<loader>:<version>:serverSmoke`), plus `:runtime:mc:1710:serverSmoke`.
   Run long ones in the background; a server that reaches "Done" without a `RESULT` line is hung.
4. `./gradlew singleJar languageJar checkSingleJar checkLanguageJar`.
5. One era-sweep `prodSmoke` (needs `local.properties`; skip with a note if absent):
   `./gradlew prodSmoke -PcgBatch=8 -PcgTargets=1710,188forge,1122forge,1132forge,1152forge,1165forge,1144fabric,1171forge,1201forge,1201fabric,1204forge,1203neoforge,1204neoforge,12111forge,12111neoforge,12111fabric`
   then open the new captures in `build/prodSmoke/` and grep each instance's `logs/latest.log` for errors.
6. If no smoke exercises the feature, test it on the oldest and newest node of each loader and say which.

Report: what ran, what passed, anything skipped and why. Do not claim a version works that nothing ran on.
