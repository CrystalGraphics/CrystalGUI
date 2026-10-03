# Code against every Minecraft version — a platform service, or a feature

**For an agent adding anything that touches Minecraft or a loader** — commands, permissions, entities,
containers, events, a new platform service — so that it builds and runs on all of them: Forge 1.7.10,
1.8.8–26.3, NeoForge 1.20.2–26.3, Fabric 1.14.4–26.3, one jar. The build itself:
[`CGUI_BUILD.md`](CGUI_BUILD.md). The invocable version of this doc is the `cross-version` skill.

## The rule

**`core` decides; a loader answers facts and does wiring.** Anything two loaders would both decide goes
in `core` (or CrystalGraphics `core`), is headless-testable, and names no Minecraft type. A loader module
only says how *its* Minecraft spells a fact or a registration. A decision made in one loader is one the
others got wrong, and nothing detects it (`runtime/mc/CLAUDE.md`).

```
core (contract + logic, Java 25 → 8)      ←  the only place behaviour lives
  └─ seam: an interface, Minecraft-free
       ├─ runtime/mc/modern/common   one class, Forge + NeoForge + Fabric, 1.13.2–26.3, //? directives
       │    └─ forge / neoforge / fabric branches: registration only, forward into common
       ├─ runtime/mc/legacy/forge    Forge 1.8.9 · 1.10.2 · 1.12.2, directives in Game / ClientGame
       └─ runtime/mc/1710            Forge 1.7.10, no directives
```

---

## Where to look — in this order

**1. The nearest existing feature.** Something in the hosts already registers, listens or queries the way
you need to, with its version boundaries worked out. Copy its shape before reading any Minecraft API.

| You need | Copy |
|---|---|
| traffic between client and server | CrystalGraphics' `CgNetworkChannel` — `CrystalGraphicsForge.Network`, `CrystalGraphicsNeoForge.Network`, `CrystalGraphicsFabricCommon.Network`, `NetworkChannelLegacy`, `NetworkChannel1710` — and `CgNetwork`, the connections their lifecycles drive |
| a game event (start, stop, tick, join, leave) | `LifecycleCrystalGUI` and each loader's `Events` forwarding into it; `CrystalGUILegacy`; 1.7.10 `CommonProxy` |
| a permission or "who is this player" | `WorkspaceHostModern.McRoles`; legacy `Game.canSendCommands`; 1.7.10 `CgUiWorkspaceHost` |
| client input, HUD, a screen | `mc.modern.client` (`CgUiInput`, `CgUiHud`, `CgUiScreen`); `legacy/client`; `v1710/client` |
| a member renamed across versions | legacy `Game` / `client.ClientGame`; the controller's `replacements.string` |
| a hook with no loader event | CrystalGraphics' node mixins (Forge 1.21.3+ render hooks) |

```bash
grep -rn "RegisterCommandsEvent\|ServerStartingEvent" runtime/mc CrystalGraphics/runtime/mc --include=*.java
grep -n "//? if" runtime/mc/modern/forge/src/main/java/com/crystalgui/mc/forge/CrystalGUIForge.java   # its breaks
```

**2. The API on every node, in seconds: `mcapi.py`.** It reads `stubs.zip` — every node's Minecraft and
loader API — and answers with version runs, so one line says where a spelling holds and where it breaks.

```bash
python CrystalGraphics/singlejar-logic/mcapi.py PlayerList isOp
#   method isOp(GameProfile) boolean   common:1.13.2-1.21.8, forge:1.13.2-1.21.8, neoforge:1.20.2-1.21.8, ...
#   method isOp(NameAndId) boolean     common:1.21.10-1.21.11, forge:1.21.10-1.21.11, ...
python CrystalGraphics/singlejar-logic/mcapi.py RegisterCommandsEvent           # loader API too
python CrystalGraphics/singlejar-logic/mcapi.py find 'CommandRegistrationCallback|ServerStartingEvent$'
python CrystalGraphics/singlejar-logic/mcapi.py find Menu --in net/minecraft/world/inventory
```

- Names are what each node **compiles** against: Mojang's on the modern tree, MCP's on legacy
  (`net/minecraft/server/management/PlayerList` there — find it with `find`).
- **A `common` node 1.17.1–1.20.1 also sees Forge's API** (it is built on Forge's userdev). Never call it
  from common: the same code compiles for Fabric and NeoForge from nodes that lack it.
- **1.7.10 is not in the database.** Read the 1.7.10 host and, after a real build, the decompiled sources
  under `runtime/mc/1710/build/rfg/`.

**3. Behaviour, not just signatures.** Decompiled sources: a real node's `extractMcSources` →
`build/mc-src/java` (`-PcgRealNodes=<branch>:<version>`, minutes the first time);
`research_repos/mc1201_sources/` holds 1.20.1 with no setup. A loader's own behaviour: its sources jar in
`~/.gradle/caches/modules-2/`.

**Whether a hook reaches what is drawn, or happens when you think: the loader's own jar.** An event existing
is not an event mattering. Forge 26.1.1–26.2 post their camera-angle event *after* `renderLevel` has copied
the view, so no listener's angles reach the screen; Forge 26.3 posts it twice a frame. Neither shows in a
signature, and each cost a sweep to find by symptom. Every installed client's patched Minecraft is in
Prism's libraries, ready to disassemble — read the call order before writing the hook:

```bash
unzip -q -o "$PRISM/libraries/net/minecraftforge/forge/26.1.1-63.0.2/forge-26.1.1-63.0.2-client.jar" \
    'net/minecraft/client/renderer/GameRenderer*.class' -d /tmp/f
javap -c -p /tmp/f/net/minecraft/client/renderer/GameRenderer.class | grep -n "ComputeCameraAngles\|viewRotationMatrix\|setRotation"
```

Forge's client jar holds its patched classes; vanilla's differ only by the patches, so it answers for Fabric
too. NeoForge's are under `libraries/net/neoforged/`.

**4. Runtime names** — only for strings and mixin targets: a real node's `build/stubs/names.tsrg`
(Mojang → SRG) or Loom's mappings (→ intermediary). Compiled code never needs them.

**5. What each node compiles, branch by branch: `directives.py`.** After writing the directives, and before
calling a feature done on every node:

```bash
python CrystalGraphics/singlejar-logic/directives.py runtime/mc/modern world/ mixin/   # every chain in these
python CrystalGraphics/singlejar-logic/directives.py CrystalGraphics/runtime/mc/modern  # just the gaps
```

It evaluates every `//? if` chain for every node and lists the nodes **no** branch covers. An import or a
helper only some versions need is a fine gap; behaviour is a decision to write down (§ *Plan it*) — the
absent value, documented in the class, and a capability the host does not declare.

Never run `generateStubDatabase` to read the API — it rewrites `stubs.zip` from whatever is on disk.

## Plan it — the break table first

The costly mistake is discovering a version break at runtime: each costs a build and a `prodSmoke` cycle.
All of them are knowable from `mcapi.py` before writing a line.

1. **Contract and test in `core`** (§1) — what the feature does, as if Minecraft did not exist.
2. **List every touch point** the hosts need: each registration, event, query and client hook.
3. **Break table** — run `mcapi.py` on each and write it down:

   | Touch point | Spelling | Nodes |
   |---|---|---|
   | register commands | `RegisterCommandsEvent.getDispatcher()` (Forge) | forge:1.16.5-26.2 |
   | | `FMLServerStartingEvent.getCommandDispatcher()` | forge:1.13.2-1.15.2 |
   | | `RegisterCommandsEvent` (NeoForge's) | neoforge:1.20.2-26.2 |
   | | `CommandRegistrationCallback` v1 → v2 (Fabric) | v1 fabric:1.14.4-1.18.2 · v2 fabric:1.19.2-26.2 |
   | | `ICommand` in the server-starting event | legacy, 1.7.10 |
   | is op | `isOp(GameProfile)` → `isOp(NameAndId)` | break at 1.21.9 |

   Each distinct spelling is a directive branch; each run boundary is its predicate. Where a node has
   **no** spelling, decide now: implement another way, or degrade to the contract's absent value — and
   write the reason down. Never drop a version silently.

   **Add a behaviour column for anything whose timing matters**: *where the value lands relative to its
   consumer*. A camera hook — before or after the frame's view is taken (Forge 26.1.1–26.2: after)? A
   polled state — does it outlive the poll (a lightning bolt can live less than one client tick when the
   server catches up)? An enum — does a later version add a constant (1.21.11's `GraphicsPreset.CUSTOM`)?
   An attribute — dimensional or positional (1.21.11's `water_evaporates` throws without a position)? Each
   is a `javap` or a source read now, against a sweep later.
4. **Write the probe now, with the contract** (§7) — which node proves which row of the table, and what
   each host declares it delivers. Nodes with no dev run (legacy, Forge ≥1.20.2, NeoForge 1.20.2/1.20.3)
   are proven only by an installed client, so their rows need a probe check from the start.
5. **Write each era in one pass** from the table: `modern/common` (the active node's spelling live, the
   others in `/* */`), then the three loader branches' registration, then legacy, then 1.7.10.
6. **Compile everything** (`checkAllTargets`) and fix by **family** — one directive fixes every node in a
   run; a failure list sorted by node points at the run. Then `directives.py` over the touched files: no
   node may fall through a chain you did not decide on.
7. **Runtime, cheapest first** (§8). A failure there that the break table did not predict belongs in the
   table, and in `CGUI_INVARIANTS.md` if it is not a spelling.

Costs to plan around: `checkAllTargets` a minute or two (stubbed); `serverSmoke` about a minute per node;
`prodSmoke` about two minutes per batch of 4 clients; a node made real for sources, minutes the first time.

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

- **A transport** — CrystalGraphics' `CgNetworkChannel` (platform slot): `CrystalGraphicsForge.Network`,
  `CrystalGraphicsNeoForge.Network`, `CrystalGraphicsFabricCommon.Network`, `NetworkChannelLegacy`,
  `NetworkChannel1710`, each with a lifecycle forwarding joins, ticks and connects into `CgNetwork`;
  framing, routing and connections all in CrystalGraphics core, sessions in CrystalGUI's.
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
- **Which spelling holds where**: the break table (§ *Plan it*), from `mcapi.py`.

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

## 7. Prove it — a probe that is right the first time

A runtime check of every node is a probe (`AutoTest` drives it in `prodSmoke` and in dev runs; the world
seams have `CgWorldProbe`, `-Dcrystalgraphics.worldprobe=true`). Its design decides whether verification
converges or goes round in circles: the world seams took eight sweeps, and most of their failures were the
probe's, not the hosts'. Before the first run:

1. **Every check is a cross-check of two independent sources** — the entity query against the host's
   camera, the ground scan against a raycast, the sun against the day time — never one value against a
   constant you assumed.
2. **Measure invariants, not conventions.** A camera offset is the rotation *between* the views before and
   after — an angle and an axis — not a world yaw read off a matrix: one host turns about the view's
   vertical, another the world's, and both are right; reading world yaw passed the first and failed the
   second for a player looking down. Apply one change per measurement (yaw, then roll), so each is one
   rotation. Before blaming a host for a failed check, ask whether the check's assumption holds there
   (1.7.10's player stands at `posY - yOffset`, its `posY` at eye level).
3. **Declare, then skip.** A host declares what its hook *visibly* delivers (`CgHostCamera.capabilities()`,
   `CgWorldEvents.declare`), and the probe skips the rest with a reason. A node that cannot deliver says so
   in its declaration and its javadoc — it does not fail, and it does not declare a hook that changes
   nothing seen.
4. **Assume sweep conditions, not a dev run's.** Several clients at once: an unfocused window pauses on
   lost focus and stops its server (turn that off — `CgWorldStimulus.keepRunning`); the world may still be
   loading when the player appears (wait it out); the integrated server falls behind and catches up in
   bursts; the server corrects the client's game time once a second (a window under a few seconds can step
   backwards); an explosion throws what it hits (match within tens of blocks, not a few).
5. **Events are hooked, never polled for.** A poll — per frame or per tick — misses anything shorter than
   its period, and under a catching-up server a bolt's whole life, or a mob's death and removal, fits
   between two client ticks. Report on the packet path (a mixin on the handler) or as the entity joins or
   leaves the client level; a poll only supplements.
6. **Every check that can fail for two reasons logs a line telling them apart** — "an entity at the bolt on
   the client: true" (it arrived, the reporting missed it) against false (it never arrived). Add it
   *before* the next run. **A rerun with no new diagnostic is the circle.**
7. **Information lines never contain `: false`** — `prodSmoke` reads `<name>: false` as a failed check.
   Write `<name> is <value>`.

## 8. Verify — in this order

1. `./gradlew checkAllTargets` — every node compiles (stub mode; minutes).
2. The logic's tests: `:core:headlessTest` / `:core:test --tests "<Class>"`.
3. `serverSmoke -PcgAcceptEula` on one node per toolchain: `forge:1.16.5` (Unimined, Java 8),
   `forge:1.20.1` (legacyForge), `neoforge:1.20.4`, `fabric:1.20.1`, `neoforge:1.21.11`, plus
   `:runtime:mc:1710:serverSmoke` and `runtime/mc/legacy/server_smoke.py` for legacy.
4. `./gradlew singleJar languageJar checkSingleJar checkLanguageJar`.
5. **One** `./gradlew prodSmoke` — the sweep, its default: one client per Minecraft major and the first
   and last of 1.19, 1.20 and 1.21, those of 1.20 and 1.21 on all three loaders — twenty-four, four at a
   time — and open the
   captures. Never every instance for a routine check; `CGUI_BUILD.md` § *A wide check is the sweep*. Forge ≥1.20.2 and NeoForge 1.20.2/1.20.3 have no dev run; prodSmoke is their
   only runtime check.
6. A feature a smoke does not exercise (a command, an entity) needs its own probe or a manual run on
   the oldest and newest node of each loader — say which, in the commit.

**Getting there in one pass:**

- **Dev runs first, on every node with a new code path** — not only the oldest and newest — and read each
  probe line before the next run. Quirks that look like bugs: a Fabric dev run reads CrystalGraphics' jar
  while the build is configured, so **the first run after a change tests the old code — run Fabric twice**;
  a NeoForge dev run applies none of CrystalGraphics' mixins (no `variants.json` outside the merged jar), so
  mixin-backed checks fail there and mean nothing; NeoForge dev wraps every GPU texture
  (`ValidationGpuTexture` — `LifecycleModern.hostTexture` unwraps it).
- **Nodes with no dev run get a targeted `prodSmoke -PcgTargets=<labels>`** before the sweep: legacy
  (1.8.9, 1.10.2, 1.12.2), Forge ≥1.20.2, NeoForge 1.20.2/1.20.3.
- **One sweep, read whole.** Group every failure by family, fix them all, then confirm with
  `-PcgTargets=` the failing labels — not another thirty clients.
- **Prism is shared.** Check for running `javaw` and ask any other session before claiming it:
  `prodSmoke` deploys into every instance and arms the targets, so two runs test each other's jars.
  Cancelling one does not stop its Gradle client — find its PID and stop it, or its batches keep
  launching. With IntelliJ open, `-PcgBatch=3`: four clients and the build ran the machine out of memory.

**A failure prodSmoke or a player finds is fixed in that node's dev run, not in Prism.** Reproduce it
with `runClient -Dcrystalgui.autotest=true ...` (or `runServer`/`serverSmoke`), iterate there, and run
`prodSmoke` once more at the end. A cycle through the shipped jar is ten minutes; a dev client is two.
`CGUI_BUILD.md` § *A failure on an installed client is fixed in a dev run* has the command.

## 9. Traps

- **Strings are never remapped** — reflection by member name, `@Inject(method = "...")` without a
  refmap, `getMethod("...")` resolve on NeoForge and fail on Forge <1.20.6 (SRG) and Fabric
  (intermediary). Call Minecraft in compiled code.
- **Mixins are the last resort**, and a mixin into a method Forge's patches add can compile against
  vanilla and fail only in game; use `require = 1`.
- **A node mixin applies only where its node pins a plugin** (`variant.mixinPlugin` in
  `versions/<v>/gradle.properties`), and only the names that plugin lists. A node without one silently runs
  none — NeoForge 26.x reported no explosions until it pinned `CrystalGraphicsNeoForgeEventMixins`. A plugin
  may list a mixin whose body a directive empties on some nodes; the class must still exist.
- **A loader event fires for both levels in single player.** Forge's and NeoForge's entity, level and tick
  events reach the integrated server's levels in the same JVM: filter `getLevel().isClientSide()`.
- **Map an enum from what it sets, not from its constants** — a version adds one (`GraphicsPreset.CUSTOM`),
  and a constant-by-constant mapping answers nothing for it.
- **1.7.10's `/summon` spawns a lightning bolt the client is never sent**; `addWeatherEffect` is what
  reaches it. Legacy and 1.7.10 tick events are on `FMLCommonHandler.instance().bus()`.
- **A bootstrapper names no Minecraft class** — one copy serves every node of its loader.
- **No JOML below 1.19.3** at runtime unless the companion jar is installed.
- **Forge 1.15's ServiceLoader sees nothing** in a mod file — discover through `Providers`.
- **A `CgService` absent-value is never null**; a contract with no sensible absence belongs in
  `CgPlatformService`.
- **`core` may import no `net.minecraft`, `cpw.mods`, `net.minecraftforge` or `org.lwjgl`** — the import
  guard fails the build.
- **Two jars, two packages**: a class shared by the host and language jars must live in one of them,
  or it is a split package on Forge/NeoForge.
