# Code against every Minecraft version — a platform service, or a feature

**For an agent adding anything that touches Minecraft or a loader** — commands, permissions, entities,
containers, events, a new platform service — so that it builds and runs on all of them: Forge 1.7.10,
1.8.8–1.21.11, NeoForge 1.20.2–1.21.11, Fabric 1.14.4–1.21.11, one jar. The build itself:
[`CGUI_BUILD.md`](CGUI_BUILD.md). The invocable version of this doc is the `cross-version` skill.

## The rule

**`core` decides; a loader answers facts and does wiring.** Anything two loaders would both decide goes
in `core` (or CrystalGraphics `core`), is headless-testable, and names no Minecraft type. A loader module
only says how *its* Minecraft spells a fact or a registration. A decision made in one loader is one the
others got wrong, and nothing detects it (`AGENTS.md` § *A loader defines wiring*).

```
core (contract + logic, Java 25 → 8)      ←  the only place behaviour lives
  └─ seam: an interface, Minecraft-free
       ├─ runtime/mc/modern/common   one class, Forge + NeoForge + Fabric, 1.13.2–1.21.11, //? directives
       │    └─ forge / neoforge / fabric branches: registration only, forward into common
       ├─ runtime/mc/legacy/forge    Forge 1.8.9 · 1.10.2 · 1.12.2, directives in Game / ClientGame
       └─ runtime/mc/1710            Forge 1.7.10, no directives
```

---

## 1. Design the contract in `core`

- **Types that cross it are Minecraft-free**: JDK types, `core` types, `UUID`/`String` ids, value records.
  A player or entity is an **id**; where a loader must hand an object back through core, it is an opaque
  `Object` that only the same loader reads (`CgNetworkChannel.sendToPlayer(Object player, …)`).
- **Behaviour is written once, here**, and tested in `core/src/headlessTest` (no CrystalGraphics core,
  like a dedicated server) or `core/src/test`.
- **Say what absence means.** A server with no language mod, a client with no GL, a loader with no
  hook — pick the degraded answer in the contract, not at call sites.
- **Java API: 8.** `core` is compiled at 25 but runs on Java 8 for 1.7.10, legacy Forge and Forge ≤1.16.
  javac will not stop a Java 9+ call; jvmdg stubs many, not all. If in doubt, use the Java 8 spelling.

## 2. Pick the seam

| Kind | Use when | How | Examples |
|---|---|---|---|
| **Interface handed to a core object** | core constructs the thing that needs the answer | loader implements it, passes it in | `HostServices`, `fs.server.WorkspaceRoles`, `ServerSmoke.Host`, `HostSession.PaintHost` |
| **`CgService` slot** (open) | optional, or read from many places; has a sensible do-nothing value | declare `CgService.of("crystalgui:x", X.NONE)` beside the contract; loader `CgPlatform.provide(SLOT, impl)`; readers `CgPlatform.get(SLOT)` | `CgNetworkChannel.SERVICE`, `ScriptServices.SERVICE`, `Providers.Copies.SERVICE` |
| **`CgPlatformService`** (closed, CrystalGraphics) | the rendering framework cannot work without it | add an abstract method — **five implementations** must answer: `PlatformService1710`, `PlatformServiceLegacy`, `PlatformServiceModern`, the harness's `PlatformServiceHarness`, core's `TestPlatformService` | GL, input, sound, lifecycle |
| **A hook into `LifecycleCrystalGUI`** (modern) / the legacy and 1.7.10 lifecycles | "when X happens in the game, run Y" | add a static method to the lifecycle; every loader's event forwards into it | `serverStarting`, `playerJoined`, `onServerTick` |

Never a second static registry beside `CgPlatform`, never `ServiceLoader` from host code (ModLauncher 5
lists nothing inside a mod file — use `core.provider.Providers`).

## 3. Implement it per era

| Era | Where | Entry points it hangs off |
|---|---|---|
| Modern, shared | `runtime/mc/modern/common/src/main/java/com/crystalgui/mc/modern/…` | `platform/LifecycleCrystalGUI` — the one class loaders talk to |
| Modern, per loader | `runtime/mc/modern/{forge,neoforge,fabric}/src/main/java/com/crystalgui/mc/<loader>/` | `CrystalGUIForge`, `CrystalGUINeoForge`, `CrystalGUIFabricCommon` (+ client entries): events and channels, each body one forward |
| Legacy Forge | `runtime/mc/legacy/forge/src/main/java/com/crystalgui/mc/legacy/` | `CrystalGUILegacy`, `CrystalGUILegacyClient`; renamed members through `Game` / `client.ClientGame` |
| 1.7.10 | `runtime/mc/1710/src/main/java/com/crystalgui/mc/v1710/` | `CrystalGUI` (`@Mod`), `CommonProxy`, `ClientProxy` |
| Language mod | the same trees' `src/lang` | its own entry classes; never named from `main` |

CrystalGraphics has the same shape (`runtime/mc/modern/common/…/PlatformServiceModern`, `…/legacy/…`,
`…/1710/…`). **CrystalGraphics first** if the contract is its.

Worked examples to copy — each is the complete pattern:

- **A transport** — `CgNetworkChannel` (core `net.wire`): `CrystalGUIForge.Network`,
  `CrystalGUINeoForge`, `CrystalGUIFabricCommon`, `legacy/net/NetworkChannelLegacy`,
  `v1710/net/NetworkChannel1710`; logic (framing, routing, sessions) all in core.
- **A permission** — `WorkspaceRoles.isOperator(actorId)`: `WorkspaceHostModern.McRoles` (one class,
  directives for `isOp(GameProfile)` → `isOp(NameAndId)` at 1.21.9), legacy `CgUiWorkspaceHost` via
  `Game.canSendCommands`, 1.7.10 `CgUiWorkspaceHost`.

## 4. Writing version-dependent code

```java
//? if >=1.21.9 {
/*return server.getPlayerList().isOp(new NameAndId(player.getGameProfile()));
*///?} else {
return server.getPlayerList().isOp(player.getGameProfile());
//?}
```

- **Sources are at the ACTIVE node's spelling** (`stonecutter active "1.20.1"` in
  `runtime/mc/modern/stonecutter.gradle.kts`); every other branch of a directive sits in `/* */`. Edit
  the commented branches as carefully as the live one — they compile on other nodes. Directives also
  work on imports, fields and whole methods; `elif` and `>=1.14.4 <1.18` ranges exist.
- **Edit only `<branch>/src`**, never `versions/<v>/build/generated/stonecutter/`. Do not switch the
  active node; if you must, switch back before committing.
- **A rename used everywhere** is a `replacements.string` in the controller, not a thousand directives —
  and its target must never appear in the sources.
- **Several renamed members** — put them behind one accessor per side (legacy `Game`, `client.ClientGame`)
  so the rest of the host reads the same on every version.
- **Finding the API of a version** without setting it up: `stubs.zip` is text inside. Each block in
  `api/<package>.sig` is headed `in <set>`; `sets.txt` says which nodes a set covers.

  ```bash
  unzip -p CrystalGraphics/singlejar-logic/stubs.zip sets.txt | head
  unzip -p CrystalGraphics/singlejar-logic/stubs.zip api/net.minecraft.server.sig \
      | grep "^in \|^class net/minecraft/server/players/PlayerList \| isOp " | grep -B1 "isOp\|PlayerList"
  ```

  Decompiled sources: a real node's `extractMcSources` (`build/mc-src`). Mojang → SRG names: a node's
  `build/stubs/names.tsrg`. `research_repos/mc1201_sources/` holds 1.20.1. **Never run
  `generateStubDatabase` to read the API** — it rewrites `stubs.zip` from whatever listings are on disk.
- **Survey before booting**: find every version where the API breaks and write all the directives in one
  pass; one `prodSmoke` cycle per fix is the expensive way.

## 5. Client and server

- **A dedicated server must never load a client class.** Client-only code lives in a `client` package
  (`mc.modern.client`, `legacy.client`, `v1710.client`); `serverSmoke` fails if any is loaded.
- **A method reference resolves when its enclosing method runs**, so `if (client) register(X::onGui)`
  still loads `X`'s parameter types on a server. Keep client subscriptions in a class the server never
  touches (NeoForge `ClientBus`).
- **Work arrives on the network thread**; the UI tree is the frame thread's. Enqueue onto the main
  thread before touching anything (`ctx.enqueueWork`, `consumerMainThread`, `server.execute`).

## 6. Domains — what varies, and where it goes

| Domain | Core owns | Varies per era (the host's job) |
|---|---|---|
| **Networking** | protocol, framing, sessions (`net.*`) | nothing new: reuse `CgNetworkChannel`. Do not register a channel per feature |
| **Permissions** | who may do what, as rules over an actor id | "is op / permission level" — `PlayerList.isOp` and its argument type; legacy `canSendCommands`; single-player owner |
| **Commands** | the command tree as data + handlers taking a Minecraft-free source (id, name, reply) | Brigadier from 1.13, registered on Forge/NeoForge's command-registration event (Forge 1.13–1.15 used the server-starting event) and Fabric's command API (v1, then v2 from 1.19); `ICommand`/`CommandBase` in `FMLServerStartingEvent` on legacy and 1.7.10 |
| **Entities** | behaviour as data and rules, keyed by id | registration (Forge/NeoForge deferred registers, Fabric registries; registries moved in 1.19.3 and builders changed again later); spawning; renderers in the client package; sync through our wire rather than data trackers where possible |
| **Containers / menus** | the UI and its state — prefer `net.window` (`Networked`, `ServerWindow`), which needs no menu type at all | only for real slot inventories: menu/container type registration and opening, which differ per loader and changed payload encoding in 1.20.5 |
| **Ticks, lifecycle, joins** | what happens | which event fires it — add a `LifecycleCrystalGUI` method and forward from each loader |
| **Rendering, input, GL** | CrystalGraphics | loader events, or node mixins where no event exists (Forge 1.21.3+) — CrystalGraphics' `AGENTS.md` |

## 7. Verify — in this order

1. `./gradlew checkAllTargets` — every node compiles (stub mode; minutes).
2. The logic's tests: `:core:headlessTest` / `:core:test --tests "<Class>"`.
3. `serverSmoke -PcgAcceptEula` on one node per toolchain: `forge:1.16.5` (Unimined, Java 8),
   `forge:1.20.1` (legacyForge), `neoforge:1.20.4`, `fabric:1.20.1`, `neoforge:1.21.11`, plus
   `:runtime:mc:1710:serverSmoke` and `runtime/mc/legacy/server_smoke.py` for legacy.
4. `./gradlew singleJar languageJar checkSingleJar checkLanguageJar`.
5. **One** `prodSmoke` across the eras (8 at a time):
   `-PcgTargets=1710,188forge,1122forge,1132forge,1152forge,1165forge,1144fabric,1171forge,1201forge,1201fabric,1204forge,1203neoforge,1204neoforge,12111forge,12111neoforge,12111fabric -PcgBatch=8`
   — and open the captures. Forge ≥1.20.2 and NeoForge 1.20.2/1.20.3 have no dev run; prodSmoke is their
   only runtime check.
6. A feature a smoke does not exercise (a command, an entity) needs its own probe or a manual run on
   the oldest and newest node of each loader — say which, in the commit.

## 8. Traps

- **Strings are never remapped** — reflection by member name, `@Inject(method = "...")` without a
  refmap, `getMethod("...")` resolve on NeoForge and fail on Forge <1.20.6 (SRG) and Fabric
  (intermediary). Call Minecraft in compiled code.
- **Mixins are the last resort**, and a mixin into a method Forge's patches add can compile against
  vanilla and fail only in game; use `require = 1`.
- **A bootstrapper names no Minecraft class** — one copy serves every node of its loader.
- **No JOML below 1.19.3** at runtime unless the companion jar is installed.
- **Forge 1.15's ServiceLoader sees nothing** in a mod file — discover through `Providers`.
- **A `CgService` absent-value is never null**; a contract with no sensible absence belongs in
  `CgPlatformService`.
- **`core` may import no `net.minecraft`, `cpw.mods`, `net.minecraftforge` or `org.lwjgl`** — the import
  guard fails the build.
- **Two jars, two packages**: a class shared by the host and language jars must live in one of them,
  or it is a split package on Forge/NeoForge.
