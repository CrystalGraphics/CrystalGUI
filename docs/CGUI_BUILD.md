# CrystalGUI — the build, and adding a Minecraft version

The one document for how this build is laid out, how to run and verify it, and how to add a Minecraft
version. **CrystalGraphics is the parent and owns the shared mechanism** — read
[`CrystalGraphics/docs/BUILD.md`](../CrystalGraphics/docs/BUILD.md) first for the node tree, the
toolchain table, the pins and stub mode; this file adds what is CrystalGUI's. Code that has to run on
every version: [`CGUI_CROSS_VERSION.md`](CGUI_CROSS_VERSION.md).

---

## What it produces

| Artifact | Task | Notes |
|---|---|---|
| `build/libs/crystalgui-<v>.jar` (~13 MB) | `singleJar` · `checkSingleJar` | the engine, the workbench and every loader host. **Requires `crystalgraphics-<v>.jar` beside it** |
| `build/libs/crystalgui-language-<v>.jar` (~50 MB) | `languageJar` · `checkLanguageJar` | optional second mod (`crystalgui_language`): tree-sitter grammars and natives, ECJ and Rhino per Java band, `language/`. The host jar never names it |

Both install unchanged on every supported loader and version (the list, and the refused versions with
their reasons, are in § *Supported versions* below).

## Requirements

- Everything CrystalGraphics needs (JDK 25, and JDK 21/17/8 installed; Gradle 9.5.1; config cache off).
- `git clone --recursive` — `taffy/`, `gl-debug-harness/` and `CrystalGraphics/` are submodules.
- For real clients: PrismLauncher, one instance per target, and `local.properties` (copy
  `local.properties.example`; forward slashes on Windows):

```properties
prismLauncherExe = C:/Users/you/AppData/Local/Programs/PrismLauncher/prismlauncher.exe
prismInstance.1201forge = C:/Users/you/AppData/Roaming/PrismLauncher/instances/Crystal 1201 Forge
prismInstanceJoml = 1710, 1165forge          # every label below Minecraft 1.19.3
```

## Layout

| Path | What |
|---|---|
| `core/`, `language/`, `taffy/` | the abstract modules: Java 25, each with a Java 8 copy for consumers below 25. `core` names no Minecraft, loader or LWJGL type (import guard) |
| `runtime/mc/1710/` | Forge 1.7.10 host (RetroFuturaGradle). A module of its own on purpose |
| `runtime/mc/modern/` | Stonecutter tree — `common`, `forge`, `neoforge`, `fabric` branches; a node per version, each depending on CrystalGraphics' node of the same version |
| `runtime/mc/legacy/` | Forge 1.8.9 / 1.10.2 / 1.12.2 tree |
| `runtime/mc/shared/`, `forge-bootstrap/`, `launchwrapper/` | Java 8 pieces merged once: mixin plugin, the Forge `@Mod` classes, LaunchWrapper helpers |
| `runtime/mc/modern/build-logic/` | convention plugins (`cg-java`, `cg-modern-loader`, `cg-modern-common`, `cg-legacy-loader`, `cg-single-jar`, `cg-descriptors`) and `cgbuildlogic/` (`ProdSmoke`, `uniminedDevRun`, `DevRunDowngrade`, `ShadowUtils`) |
| `gradle/module_integration/` | how CrystalGraphics is wired in: `composite.settings` (substitutions), `integration` (compile deps), `crystalgraphics-run` (ModDevGradle dev runs) |
| `download/locations.json` | every runtime download address — see `download/CLAUDE.md` |
| `samples/fieldnotes/` | **a build of its own**, not in this one: a CrystalGUI app shipped as one jar over three loaders on `singlejar { targets {} }` — the worked example for a platform-abstract project. Its README says how to build and drive it |

**Two source sets per loader module**: `main` → the host jar, `lang` → the language jar. `:language` is
on `langCompileOnly` only, so a host class naming it is a compile error. Where the host needs the other
jar, it publishes a seam it cannot name (`CgUiAutoTest.onFrame`).

**Java levels are CrystalGraphics'** (its `docs/BUILD.md` § *Layout*). Never lower an abstract module's Java
to suit a consumer — and javac does not check the API: a Java 9+ call fails on a Java 8 instance unless
jvmdg stubs it.

**Included by another build** (`gradle.parent != null`): only the loader and common node of ONE target —
the node claiming what a consumer's `com.crystalgui.settings` names in the `singlejar.checkout.target`
system property, else `forge` 1.20.1 — with no 1.7.10, no legacy tree, no `consumer-plugin`, and no harness
unless `-Dcrystalgui.harness=true`. Its `singleJar` then carries that target's variant alone; the 1.7.10
entries in the pipeline are conditional on `has1710` for exactly this. CrystalGraphics' `targets {}`
resolves the same property, so both builds pick the same node.

## Commands

```bash
./gradlew :taffy:test                                       # the vendored layout engine's own regression tests
./gradlew :core:compileJava                                 # enforces the Minecraft/Forge/LWJGL import guard
./gradlew :runtime:mc:1710:compileJava                      # not in :core:check -- what a deletion from core/ breaks silently
./gradlew checkAllTargets                                   # every node compiles -- before every commit
./gradlew singleJar languageJar checkSingleJar checkLanguageJar
./gradlew deploySingleJars                                  # both, plus CrystalGraphics', into every Prism instance
./gradlew :core:headlessTest                                # server-side tests, no CrystalGraphics core
./gradlew :core:trackedTest                                 # every shipped shader and keyword variant, linked as on Vulkan
./gradlew :core:test --tests "<Class>"                      # name classes: `com.crystalgui.ui.*` never reports
./gradlew :runtime:mc:modern:<branch>:<version>:serverSmoke -PcgAcceptEula   # nodes with a dev run
./gradlew :runtime:mc:1710:serverSmoke
python runtime/mc/legacy/server_smoke.py --java <java8> 1.12.2 1.10.2 1.8.9  # legacy, on real servers
./gradlew prodSmoke                                         # THE SWEEP: 30 clients, one per major -- the wide check
./gradlew prodSmoke -PcgTargets=<label>,<label>             # just these; deploys both jars first
./gradlew prodSmoke -PcgNoDeploy                            # installed jars only; 4 clients at a time (-PcgBatch=<n>;
                                                            # 8 at once crashed a workstation)
./gradlew prodSmoke -PcgTargets=all                         # every instance (104): not a routine check
./gradlew checkFootprint                                    # build outputs under the budget (700 MB)
```

| Flag | Effect |
|---|---|
| `-PcgAcceptEula` | writes `eula=true` into a smoke run's directory (the build never accepts it silently) |
| `-PcgNoLanguage` | leave the language mod out of a dev run or deploy — the degraded path |
| `-PcgRealNodes=`, `-PcgStubs=false` | real toolchains (CrystalGraphics' doc) |
| `-PcgSmokePort=` | when 25599 is taken |
| `-PcgJoin=host:port`, `-PcgProbe`, `-PcgProbeRole=watcher` | two-process runs and the connection probe |
| `-PcgExtraMods=<jar>,…` | deploy another mod beside ours — into the `-PcgTargets` instances only, and removed by the next deploy |

## Releasing

**One button: Actions → Release → Run workflow** (`.github/workflows/release.yml`). It runs from `master`
on GitHub, never from a local checkout. From a terminal:

```bash
gh workflow run release.yml -R CrystalGraphics/CrystalGUI --ref master -f bump=patch      # patch | minor | major | as-is
gh workflow run release.yml -R CrystalGraphics/CrystalGUI --ref master -f version=0.0.2   # exactly this
gh run watch -R CrystalGraphics/CrystalGUI                                                 # follow it
```

In order:

1. CrystalGraphics moves to its `master`. When that commit is not a released version, it is released first:
   a patch version, published, committed, tagged and pushed, with a GitHub release.
2. CrystalGUI's `modVersion` is set; `apiCheck`, `checkSingleJar` and `checkLanguageJar` run; everything,
   the consumer plugins included, is published to Cloudsmith.
3. Only then: a commit with the version and the submodule pointer, the tag `v<version>`, the push, and the
   GitHub release with both jars.

| Secret | Set on | Holds |
|---|---|---|
| `CLOUDSMITH_USERNAME` | the organization, shared with CrystalGUI and CrystalGraphics | the Cloudsmith service account's **slug**, not its display name |
| `CLOUDSMITH_PASSWORD` | the same | that service account's API key |
| `CRYSTALGRAPHICS_TOKEN` | CrystalGUI | a fine-grained token, Contents: read and write on CrystalGraphics, for step 1. It expires |

Which account owns what, and how to rotate each: `operations/release.md` in CrystalPlans (maintainers).

| A run fails at | What it left | Then |
|---|---|---|
| any step before *Build, check and publish* | nothing | fix on `master`, run again |
| *Build, check and publish*, in the build or a check | nothing | fix on `master`, run again |
| the same step, `401 Unauthorized` on a `PUT` | nothing: the first upload was refused | the two Cloudsmith secrets are wrong — a stray space in either is enough |
| the same step, after some uploads | part of the version on Cloudsmith | delete that version's packages on Cloudsmith, or release the next version |
| *Commit, tag and push* or *GitHub release* | the version published, untagged | tag the release commit `v<version>` by hand and push it |

A version that went out broken is taken back with **Retract** (`.github/workflows/retract.yml`): it deletes
that version's `com.crystalgui` packages on Cloudsmith, its GitHub release and its tag, and leaves the release
commit. Then release the same version again with `-f version=<version>`.

```bash
gh workflow run retract.yml -R CrystalGraphics/CrystalGUI --ref master -f version=0.0.1
```

What a Linux runner needs that a Windows checkout hides:

- `gradlew` committed executable — `git update-index --chmod=+x gradlew`. At `100644` the run dies with
  `./gradlew: Permission denied`.
- Zulu JDKs: RetroFuturaGradle (1.7.10) asks for Azul's by vendor.
- The JDKs handed to Gradle through `org.gradle.java.installations.fromEnv`, since it does not look where
  `setup-java` installs them.

| Command | Publishes to |
|---|---|
| `./gradlew publish` with `CLOUDSMITH_USERNAME`/`CLOUDSMITH_PASSWORD` set | Cloudsmith, `cloudsmith.repository` in `gradle.properties` |
| `./gradlew publish` without them, or `publishToMavenLocal` | Maven local, for a consumer testing an unreleased build |

Consumers read `https://dl.cloudsmith.io/public/crystalgraphics/crystalgraphics/maven/`
([`CGUI_SETUP.md`](CGUI_SETUP.md)). The mechanism is `CrystalGraphics/singlejar-logic/README.md` § *Publishing*.

| Coordinate | What | Consumer |
|---|---|---|
| `com.crystalgui:core` | the engine — jar, `java8` copy, sources, javadoc | compiles against it |
| `com.crystalgui:taffy` | the layout engine (MIT) | comes with `core` |
| `com.crystalgui:crystalgui` | the shipped jar | runs it in a dev client, with `com.crystalgraphics:crystalgraphics` |
| `com.crystalgui:crystalgui-language` | the optional language mod | runs it, if wanted |

**The API is checked on every build.** Each library's public declarations are committed as
`api/<artifact>.api`; `apiCheck`, part of `check`, fails when one is gone or changed and the major
version has not moved. The file is the API as last RELEASED, so additions never fail. At a release, or
after a deliberate major break, `./gradlew apiDump` rewrites it — commit the diff with the change.

The consumer plugins `com.crystalgui` and `com.crystalgui.settings` publish with them (`consumer-plugin/`,
an included build, Java so any consumer Gradle loads it). Their use is `CGUI_SETUP.md`.

`core`'s metadata carries CrystalGraphics' `core` and `platform`, `taffy`, JOML 1.10.5, gson 2.2.4,
log4j-api 2.0-beta9 and two annotation packages — each the oldest any target ships. `language` is not
published: nothing outside CrystalGUI compiles against it.

## Running Minecraft

A loader module is the only thing that sees what crosses the loader seam — networking, the workspace
over a wire, platform services, class loading on a server. `headlessTest` reaches no loader and the
harness is a client by design.

```bash
./gradlew :runtime:mc:modern:<branch>:<version>:serverSmoke -PcgAcceptEula   # boot a server, assert, stop
./gradlew :runtime:mc:1710:serverSmoke
./gradlew :runtime:mc:modern:<branch>:<version>:runClient                    # a dev client
./gradlew :runtime:mc:1710:runClient -PcgProbe -PcgJoin=localhost:25565      # the connection probe, two processes
./gradlew :runtime:mc:modern:<branch>:<version>:connectionProbe              # the same, driven to a verdict file
./gradlew prodSmoke                                                          # THE SWEEP: the shipped jars on 30 real clients
./gradlew prodSmoke -PcgTargets=<label>,<label>                             # just these
```

Every flag, which nodes have a dev run, and how to read a run: § *Commands* above and § *Verification*
below.

## Verification, and what each check can see

| Check | Sees | Blind to |
|---|---|---|
| `checkAllTargets` | every node compiles against its Minecraft | anything at runtime |
| `headlessTest` | engine logic with no CrystalGraphics core — what a dedicated server has | loaders |
| `trackedTest` | every shipped shader and keyword variant compiled to SPIR-V and linked, as on Vulkan | a driver: what a pipeline asks of the device is the harness's audit, `--device=vulkan` |
| `serverSmoke` (dev) | a real server boots, the stack comes up, no client-only class loaded | packaging, remapping, relocation |
| `server_smoke.py` | the same on the SHIPPED jars, legacy Forge | clients |
| `prodSmoke` | the shipped jars on real clients: load a world, open the editor, capture | behaviour past the first screen |

**`serverSmoke` first** for anything that is a runtime property — a client-only class constructed on a
server, a service built eagerly.

**A probe that never ran is not a pass.** The driven tasks delete their verdict file first and require it
after; its first line is the verdict.

**Read every new target's capture.** "drew" has passed over garbled text. `desktop painted: false` in the
log fails the run; a capture alone proves nothing (a frame can be the previous screen's).

### A failure on an installed client is fixed in a dev run

**When a Prism client crashes or misbehaves, reproduce it in that node's dev run and iterate there** —
never by rebuilding and redeploying through `prodSmoke`, which costs about ten minutes a cycle where a
dev client costs two. `prodSmoke` then confirms the fix in the shipped jar once, at the end. It stays the
only witness for what a dev run cannot see: relocation, remapping, downgrading and the merged descriptors.

```bash
# the same unattended run prodSmoke arms, on the dev client; every -Dcrystalgui.* / -Dcrystalgraphics.* is forwarded
./gradlew :runtime:mc:modern:forge:1.17.1:runClient -Dcrystalgui.autotest=true "-Dcrystalgui.autotest.world=*"
# ...plus a probe: -Dcrystalgui.autotest.script=Probe.java, -Dcrystalgui.autotest.complete=true
# ...and where the captures go: -Dcrystalgui.autotest.out=build/devSmoke/1171forge.png
# 26.2+: which API Minecraft renders through, and its Vulkan validation layer over ours too:
#   -PcgGraphics=vulkan -PcgVulkanValidation    (NeoForge: earlyWindowControl = false in the run's config/fml.toml)
```

A world comes from `runs/client/saves/` — copy the instance's save there. A server-side fault goes to
`runServer` or `serverSmoke`. Forge ≥1.20.2 and NeoForge 1.20.2/1.20.3 have no dev run; there
`prodSmoke -PcgNoDeploy -PcgTargets=<one>` is the loop.

### A wide check is the sweep, not every instance

**Checking across versions means one client per Minecraft major, never all of them.** `./gradlew prodSmoke`
with no `-PcgTargets` runs the sweep, `prodSmokeSweep` in the root build: 1.7.10, 1.8.9, 1.10.2, 1.12.2,
1.13.2, 1.14.4, 1.15.2, 1.16.5, 1.17.1, 1.18.2, and the first and last of each major with many minors —
1.19 and 1.19.4, 1.20.1 and 1.20.6, 1.21.4 and 1.21.11, 26.1 and 26.2 — with every 1.20, 1.21 and 26 one
on all three loaders (NeoForge's first is 1.20.2, and Forge's first 26.x is 26.1.1). Thirty clients, four
at a time, oldest first — 1.7.10, 1.8.9, 1.10.2 and 1.12.2 lead and the three 26.2s close — about twenty
minutes with the language probes. Every instance is over a hundred clients and an hour, and
eight at a time took the workstation down; `-PcgTargets=all` is there for the rare release that needs it.
A new version is checked by its own label (`-PcgTargets=<label>`), and a line that gains a minor keeps
its first and last in the sweep.

---

## Supported versions

A node per (loader, Minecraft version); a node claims the versions it was booted on (`variant.minecraft`
in the pin catalog).

| Loader | Versions | Not supported, and why |
|---|---|---|
| Forge | 1.7.10 · 1.8.8–1.12.2 (legacy tree) · 1.13.2–1.21.11 · 26.1.1–26.3 | 1.8 (no MixinBooter boots it) · 1.21 (Forge 51 has no HUD event) · 26.1 (Forge 62 fails in Minecraft's own bootstrap, before any mod loads) · never published: 1.14, 1.14.1, 1.16, 1.17, 1.20.5, 1.21.2 |
| NeoForge | 1.20.2–1.21.11 · 26.1–26.3 | nothing for 1.20.1; 1.21.2, 1.21.6, 1.21.7, 1.21.9, 26.1, 26.1.1 and 26.3 run its only builds, betas |
| Fabric | 1.14.4–1.21.11 · 26.1–26.3 | 1.14–1.14.3, 1.16, 1.16.1, 1.21.9 — their only Fabric APIs lack a module the hosts use |

- **Java 8** runs Forge 1.13–1.16, legacy Forge and 1.7.10, dev runs included (`uniminedDevRun` swaps in
  the Java 8 copies). Below 1.17 the nodes are built by Loom and Unimined, above by ModDevGradle.
- **26.x is Java 25 and unobfuscated**: every loader runs Mojang's names, so a Fabric node from 26.1 ships
  as compiled, with no intermediary. On 26.2 Blaze3D may run on Vulkan, and CrystalGraphics then draws
  through its own Vulkan device hosted on Minecraft's (`Blaze3dVulkanHost`); a dev client picks the API
  with `-PcgGraphics=vulkan|opengl`. 26.3 under Vulkan still stands down (`CgGraphicsLifecycle.standDown`).
- **26.3 windows through SDL3, and ships no GLFW**: its keys are SDL scancodes and its mouse buttons SDL's.
  A 26.3 node registers CrystalGraphics' `runtime/lwjgl/sdl` services instead of the GLFW ones, a host
  names a key through `CgUiInput.hostKey`, and Fabric's input chain is SDL's event filter. Blaze3D's GPU
  layer moved to `com.mojang.renderpearl`, a `replacements.string` in both Stonecutter scripts.
- **Below 1.19.3 Minecraft ships no JOML**, and those instances take CrystalGraphics' `crystalgraphics-joml`
  companion (`prismInstanceJoml` in `local.properties`).
- **Forge 1.13.2 and 1.14.2–1.14.3 compile against Mojang names carried back from 1.14.4**, since Mojang
  published none; their scripts resolve through MCP's.
- The per-loader node lists: `runtime/mc/modern/{forge,neoforge,fabric}/CLAUDE.md`.

## Adding a Minecraft version

**CrystalGraphics first** — its `docs/BUILD.md` § *Adding a Minecraft version*, steps 0–3. Then here:

1. **`targets {}` in `settings.gradle.kts`** — the same ranges as CrystalGraphics', so the new node is here
   too. The pins are the shared catalog's, already written in CrystalGraphics' step 2; nothing per node
   here unless it ships a mixin (`variant.mixinPlugin` in `runtime/mc/modern/<branch>/versions/<version>/gradle.properties`).
2. **`//? if` directives** in `runtime/mc/modern/<branch>/src` (and `src/lang`). `checkAllTargets`.
3. **`stubs.zip`** — regenerate once, after both repos have the node (CrystalGraphics' doc, step 4);
   `checkStubEquivalence` on the new node in both repos; commit it with the CrystalGraphics node.
4. **`serverSmoke -PcgAcceptEula`** if the node has a dev run (toolchain table).
5. **A Prism instance**: named `Crystal <digits> <Loader>` (e.g. `Crystal 12111 NeoForge`), in its
   minor-version group in PrismLauncher's `instgroups.json`; the loader at the pinned version; its
   companions — **Fabric API** of that version (Fabric), **MixinBooter** (Forge 1.8–1.12.2), **UniMixins**
   (1.7.10), a **Java 8** runtime for Forge ≤1.16, legacy and 1.7.10. Add `prismInstance.<label>` to
   `local.properties`, and the label to `prismInstanceJoml` if below 1.19.3.
6. **`prodSmoke -PcgTargets=<new label> -PcgSmokeProps=crystalgui.autotest.complete=true`** — the new
   target only, one run — and open its captures. Its log's `a game receiver — N rows` is the scripting
   check: 0 rows means the version's names never resolved. A running Forge or Fabric fetches its
   mappings by its own version — Mojang's `client.txt` from 1.14.4, MCPConfig's `joined.tsrg`, Fabric's
   intermediary — each PINNED per version in `download/locations.json`, digest from its publisher, which
   is what lets a second host or the mirror serve it. `checkDownloadLocations` (in `check`) fails a node
   whose own version is not pinned, and `verifyScriptingCoverage` (online, run by the Release workflow)
   every release in its range. A version Mojang published nothing for takes MCP's names from the tables
   in `ScriptServiceModern.mcpStable` and `ScriptServiceLegacy.MCP_STABLE`, which both checks read.
7. **Docs**: the range in § *Supported versions* above and its one-line summary in `AGENTS.md` (and a refused version's reason), and the
   node list in that loader branch's `AGENTS.md` — in both repos.

Nothing else is edited: descriptors, variant tables, thin-jar lists and `requiredEntries` follow the tree.
A new **loader** or **entry class** is different: entry-class names are strings in
`cg-descriptors.gradle.kts` and in `checkSingleJar`'s `requiredEntries`, and the two must agree.

---

## Caveats that bite

- **Order is CrystalGraphics → CrystalGUI.** A CrystalGUI node whose CrystalGraphics node is missing
  fails composite substitution.
- **Refuse a version rather than half-support it**: Forge 1.21 (no HUD event), Fabric 1.16/1.16.1/1.21.9
  (no Fabric API with the modules the hosts use) were refused, and say why in `AGENTS.md`.
- **The common node calls CrystalGraphics' common node** (`ResourceIds`), never a copy. The thin jar
  relocates those references to where CrystalGraphics ships its common for the same node.
- **Fabric's dev run gets CrystalGraphics as a mod jar**, read while the build is configured: the first run
  of a new node can lack it (the build warns); run again.
- **ModDevGradle's dev classpath ignores `runtimeClasspath`**: mods come from `MOD_CLASSES`
  (`crystalgraphics-run`), libraries from `additionalRuntimeClasspath` (`runtimeOnly` from 1.21.10). A
  source set added to `mods {}` but not to that script is silently absent.
- **Forge 1.13–1.16 dev runs are Java 8** (`uniminedDevRun`): classes of ours newer than Java 8 are
  swapped for jvmdg copies, each mod lists its resources first (that FML reads `mods.toml` from the first
  root), and a class that names Minecraft must be inside a mod, never a library.
- **ModLauncher 5 (Forge 1.15) lists no resource inside a mod file**: `ServiceLoader` finds nothing;
  discovery goes through `core.provider.Providers`, whose copies that host fills.
- **Logs**: 1.7.10 stamps its log an hour behind the clock (match by file time); 1.8.9 writes
  `logs/fml-client-latest.log`; ModLauncher 9+ clients keep stderr lines out of `latest.log`.
- **Nodes compile from `CrystalGraphics/singlejar-logic/stubs.zip` by default.** Code changes never touch
  it; a node added or re-pinned means regenerating it. A run task makes its node real.
- **Switching the active Stonecutter node rewrites `src/` in place.** Switch back before committing.
- **Never commit the `gl-debug-harness` pointer** unless the harness change is the point.
