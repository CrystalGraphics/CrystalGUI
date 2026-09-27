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
| `build/libs/crystalgui-<v>.jar` | `singleJar` · `checkSingleJar` | the engine, the workbench and every loader host. **Requires `crystalgraphics-<v>.jar` beside it** |
| `build/libs/crystalgui-language-<v>.jar` | `languageJar` · `checkLanguageJar` | optional second mod (`crystalgui_language`): grammars, ECJ, Rhino. The host jar never names it |

Both install unchanged on every supported loader and version (the list, and the refused versions with
their reasons, are in `AGENTS.md` § *Running Minecraft*).

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
| `download/locations.json` | every runtime download address — see `AGENTS.md` § *Runtime downloads* |

**Two source sets per loader module**: `main` → the host jar, `lang` → the language jar. `:language` is
on `langCompileOnly` only, so a host class naming it is a compile error.

**Included by another build** (`gradle.parent != null`, e.g. RPG-Core): only `common`/`forge` 1.20.1,
no 1.7.10, no legacy tree, no harness unless `-Dcrystalgui.harness=true`.

## Commands

```bash
./gradlew checkAllTargets                                   # every node compiles -- before every commit
./gradlew singleJar languageJar checkSingleJar checkLanguageJar
./gradlew :core:headlessTest                                # server-side tests, no CrystalGraphics core
./gradlew :core:test --tests "<Class>"                      # name classes: `com.crystalgui.ui.*` never reports
./gradlew :runtime:mc:modern:<branch>:<version>:serverSmoke -PcgAcceptEula   # nodes with a dev run
./gradlew :runtime:mc:1710:serverSmoke
python runtime/mc/legacy/server_smoke.py --java <java8> 1.12.2 1.10.2 1.8.9  # legacy, on real servers
./gradlew prodSmoke -PcgTargets=<label>,<label>             # deploy both jars, boot real clients, capture
./gradlew prodSmoke -PcgNoDeploy -PcgBatch=8                # installed jars only; 8 clients at a time
./gradlew checkFootprint                                    # build outputs under the budget (700 MB)
```

| Flag | Effect |
|---|---|
| `-PcgAcceptEula` | writes `eula=true` into a smoke run's directory (the build never accepts it silently) |
| `-PcgNoLanguage` | leave the language mod out of a dev run or deploy — the degraded path |
| `-PcgRealNodes=`, `-PcgStubs=false` | real toolchains (CrystalGraphics' doc) |
| `-PcgSmokePort=` | when 25599 is taken |
| `-PcgJoin=host:port`, `-PcgProbe`, `-PcgProbeRole=watcher` | two-process runs and the connection probe |

## Publishing

`./gradlew publishToMavenLocal`, and the same in `CrystalGraphics/` for its artifacts. The mechanism is
`CrystalGraphics/singlejar-logic/README.md` § *Publishing*.

| Coordinate | What | Consumer |
|---|---|---|
| `com.crystalgui:core` | the engine — jar, `java8` copy, sources, javadoc | compiles against it |
| `com.crystalgui:taffy` | the layout engine (MIT) | comes with `core` |
| `com.crystalgui:crystalgui` | the shipped jar | runs it in a dev client, with `com.crystalgraphics:crystalgraphics` |
| `com.crystalgui:crystalgui-language` | the optional language mod | runs it, if wanted |

`core`'s metadata carries CrystalGraphics' `core` and `platform`, `taffy`, JOML 1.10.5, gson 2.2.4,
log4j-api 2.0-beta9 and two annotation packages — each the oldest any target ships. `language` is not
published: nothing outside CrystalGUI compiles against it.

## Verification, and what each check can see

| Check | Sees | Blind to |
|---|---|---|
| `checkAllTargets` | every node compiles against its Minecraft | anything at runtime |
| `headlessTest` | engine logic with no CrystalGraphics core — what a dedicated server has | loaders |
| `serverSmoke` (dev) | a real server boots, the stack comes up, no client-only class loaded | packaging, remapping, relocation |
| `server_smoke.py` | the same on the SHIPPED jars, legacy Forge | clients |
| `prodSmoke` | the shipped jars on real clients: load a world, open the editor, capture | behaviour past the first screen |

**Read every new target's capture.** "drew" has passed over garbled text. `desktop painted: false` in the
log fails the run; a capture alone proves nothing (a frame can be the previous screen's).

---

## Adding a Minecraft version

**CrystalGraphics first** — its `docs/BUILD.md` § *Adding a Minecraft version*, steps 0–3. Then here:

1. **`settings.gradle.kts`** — the version on the loader's branch in `modernNodes` (and `common` if absent);
   for legacy, the `branch("forge")` list. Must match CrystalGraphics' node for node.
2. **`runtime/mc/modern/<branch>/versions/<version>/gradle.properties`** — the same pins as
   CrystalGraphics' node (same `variant.minecraft`, `variant.packFormat`), plus the common node's.
3. **`//? if` directives** in `runtime/mc/modern/<branch>/src` (and `src/lang`). `checkAllTargets`.
4. **`stubs.zip`** — regenerate once, after both repos have the node (CrystalGraphics' doc, step 4);
   `checkStubEquivalence` on the new node in both repos; commit it with the CrystalGraphics node.
5. **`serverSmoke -PcgAcceptEula`** if the node has a dev run (toolchain table).
6. **A Prism instance**: named `Crystal <digits> <Loader>` (e.g. `Crystal 12111 NeoForge`), in its
   minor-version group in PrismLauncher's `instgroups.json`; the loader at the pinned version; its
   companions — **Fabric API** of that version (Fabric), **MixinBooter** (Forge 1.8–1.12.2), **UniMixins**
   (1.7.10), a **Java 8** runtime for Forge ≤1.16, legacy and 1.7.10. Add `prismInstance.<label>` to
   `local.properties`, and the label to `prismInstanceJoml` if below 1.19.3.
7. **`prodSmoke -PcgTargets=<new label>`** — the new target only, one run — and open its captures.
8. **`AGENTS.md`**: the node list under *Running Minecraft*, and a refused version's reason if one is refused.

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
- **Never commit the `gl-debug-harness` pointer** unless the harness change is the point.
