# The language stack — how analysis actually resolves a name

**For** changing why the editor colours, completes or reports an error differently on one host than another: the
wiring under `language/`.

> **Every tier has a legitimate absent state, and absence never throws.** No engine → grammar colouring; no grammar
> → core's word-list lexer; neither → plain text; a missing classpath → a compiler that answers *wrongly*. The
> question is which tier silently answered nothing.

## 1. The four questions, and who owns each

| Question | Owner | Notes |
|---|---|---|
| Which engine versions can this JVM run? | `EngineBand.detect()` | one read of `java.specification.version`: a JVM cannot load a newer class file |
| Where are that band's jars? | `EngineSource` | `EngineHost.defaultSource()`: configured dir → bundled → downloaded |
| What does a compile resolve against? | `ScriptNameEnvironment` | three tiers, §4 |
| What does the running game know? | `ScriptService`, over CrystalGraphics' `CgGameClasses` and `CgGameDirectory` | live bytes, mappings, the namespace probe, `cacheRoot()`. No loader registers anything |

No game (`ScriptService.live()` false) is a real deployment: the harness and every test run so, with no live bytes,
no mappings and a cache under `build/crystalgui-cache`. Tests fake a game with `FakeGame`.

## 1a. The language jar is a mod, and starts itself

`crystalgui-language-<version>.jar` is a mod of its own, id `crystalgui_language`, on every loader and version the
host jar supports. Being a mod is what makes it run: CrystalGraphics finds lifecycle listeners in the jars the loader
lists as mods.

| Loader | Descriptor (generated) | `@Mod` class (source) |
|---|---|---|
| 1.7.10, Forge 1.8.9–1.12.2 | `mcmod.info` | `runtime/mc/forge-stubs/src/lang/.../CrystalGUILangForge.java`, carrying both eras' `@Mod` |
| Forge 1.13.2–26.3 | `META-INF/mods.toml` | the same `CrystalGUILangForge` |
| NeoForge | `META-INF/neoforge.mods.toml` | `runtime/mc/forge-stubs/src/lang/.../CrystalGUILangNeoForge.java` |
| Fabric | `fabric.mod.json`, entrypoint lists empty | none |

The descriptors are generated, for this jar and the host jar alike:

1. **Declared** in `runtime/mc/modern/build-logic/src/main/kotlin/cg-descriptors.gradle.kts` as `cgLangDescriptor`
   (`ModDescriptor`: id, name, licence, the dependency on `crystalgui`, a variant per loader and version).
2. **Written** by `registerDescriptorTasks(cgLangDescriptor, "cgui-lang", name = "language", ...)` to
   `build/descriptors/language/`, with `META-INF/crystalgui_language/variants.json`.
3. **Merged** by `languageJar`; `checkLanguageJar` fails when any of them, or `META-INF/crystalgraphics/listeners`, is
   missing (`requiredEntries` in `cg-single-jar.gradle.kts`).

A variant names no entry class: the `@Mod` classes pick the running version's variant (`ForgeStart`,
`VariantBootstrap`), refuse an unsupported version, and construct nothing.

### How it starts

1. The loader lists `crystalgui_language` among its mods.
2. CrystalGraphics reads `META-INF/crystalgraphics/listeners` in every mod's root (`CgModLoader.roots()`) and its own
   classloader (`CgListenerDiscovery`). The language jar's says `common com.crystalgui.language.CgLanguageLifecycle`.
3. `CgLanguageLifecycle` starts the stack: on a client in `onLoad`, behind the loading screen; on a dedicated server
   in `onServerStarting`.

### What is easy to get wrong

- Removing the descriptor or the `@Mod` class removes the stack, silently.
- No code goes in the `@Mod` classes or a variant entry: what the stack needs from the game is a CrystalGraphics
  platform service; what it does at a game moment is `CgLanguageLifecycle`.
- The host jar never names this jar.

## 2. The band is opened once, in isolation

`EngineHost.shared(source)` opens one `EngineHost` per band, shared across languages. `EngineClassLoader` is a
**child-first** `URLClassLoader` over the band's jars, since other mods ship Rhino at versions we do not choose.

- **`PARENT_FIRST` is the whole design**: `java.`, `javax.`, `jdk.`, `sun.`, the `engine.bridge` package and
  `com.crystalgui.text.*` delegate upward; everything else is isolated. Anything host and engine both name must be on
  it, and nothing else should be.
- **The adapters load inside the band**: `EngineHost.withOwnClasses` appends this module's code source to the band's
  URLs. Without it an adapter loads from the parent and dies as `NoClassDefFoundError: org/mozilla/javascript/...`.
- **Statics do not cross**: a band-loaded class has its own copy of everything off `PARENT_FIRST`. So
  `ScriptNameEnvironment` names no `ScriptService` or `PlatformMappings`; the host composes `TypeBytes` and only
  `byte[]` crosses.

## 3. Where the engine jars come from

```java
EngineSource.firstOf(configuredSource(), bundledSource(), downloadedSource(progress))
```

The first non-empty answer wins, so an ordinary launch never touches the network.

- **configured**: `-Dcrystalgui.engines.dir`, set by dev runs from `:language:stageEngines`
- **bundled**: `assets/crystalgui/engines/<band>/` in the mod jar, extracted on first use
- **downloaded**: the band's jars as `download/locations.json` lists them, each checked against its pin

The last two write under `ScriptService.cacheRoot()`: the game's `crystalgui/cache`, or `build/crystalgui-cache`.

## 4. Resolution: three tiers, in this order

`ScriptNameEnvironment` answers each name in turn:

1. **`TypeBytes.readable`**: the live runtime, remapped. What is loaded is what executes, so it wins over a file.
2. **The classpath delegate**: ECJ's `FileSystem`, the JDK and every jar on the classpath that is not loaded.
3. **`TypeBytes.synthesized`**: a reflective stub, only where both said nothing.

Tier 2 is built in `EcjCompilation.fileSystemFor` (`-source`, `-target`, `-proc:none`, `-nowarn`, `-classpath`).
**Nothing names the platform classes** (no `--system`, `--release` or `-bootclasspath`): ECJ infers the JDK from the
running JVM, and does so with an empty classpath.

### What the classpath is assembled from — `HostClasspath.detect()`

| Route | Supplies | Where it works |
|---|---|---|
| `URLClassLoader.getURLs()` | app classpath | plain JVMs |
| reflective `getSources()`/`getURLs()` | loader-specific | 1.7.10 (`LaunchClassLoader`) |
| `java.class.path` | app classpath | plain JVMs, the harness, ModLauncher (1.20.1: 125 entries) |
| `ModuleLayer` locations (`file:` only) | mod jars, libraries | a modular host with no `java.class.path` |
| `java.home/lib/rt.jar` | platform classes | Java 8 only |

## 5. What each host actually supplies

| | plain JVM (harness, tests) | 1.7.10, Forge 1.8–1.12.2 | 1.13+ |
|---|---|---|---|
| Classpath route | `java.class.path` | `getSources()` | `java.class.path` |
| Platform classes | ECJ infers | `rt.jar` (Java 8) or inference | ECJ infers |
| Mapping (`ScriptService.mappings()`) | none | MCP stable, by version | Mojang's names joined to SRG or intermediary; MCP stable before 1.14.4 |
| Live bytes (tier 1, `CgGameClasses`) | none | LaunchWrapper's transformer chain (`GameClasses1710`, `GameClassesLegacy`) | the loader that will run the class (`GameClassesModern`) |
| Compliance | 1.8 | 8 | the running JVM's band |

From compliance 9 ECJ resolves module-aware, which is where §6 matters.

## 6. The live tier must never answer for the platform

From compliance 9, ECJ discards an answer for `java.lang.Object` not attributed to `java.base`, so a live tier that
answers for JDK classes makes every file fail with `The type java.lang.Object cannot be resolved`. Two rules:

1. **A platform name is the delegate's, always**: `find()` routes `java/`, `javax/`, `jdk/` and `sun/` past tier 1.
2. **A service with nothing live says so** with `ReadableView.ByteSource.NONE`, which `PlatformTypeBytes.of()` reads
   as no live tier. Never `ofClassLoader`: it reads the disk, which is what lies.

**When this layer misbehaves, try `-Dcrystalgui.language.noLiveBytes` first**: it turns tier 1 off with no code
change and tells "live" from "file-based". The modern dev runs forward it.

## 7. Mappings: on the modern tree every mapping is a JOIN

A script is authored in the readable namespace (Mojang's names); `MappingSet` translates at the boundary: in, so the
compiler sees readable members; out, so bytecode links against what the runtime declares.

| Runtime | Classes | Members | Mapping |
|---|---|---|---|
| any dev run | official | official | none |
| NeoForge 1.20.4 | official | official | none |
| Forge 1.20.1 | official | **SRG** | SRG → official |
| Fabric 1.20.1 | **intermediary** | **intermediary** | intermediary → official |

- **Forge's is a mixed namespace**: official classes, SRG members. MCPConfig's own "srg" classes
  (`net/minecraft/src/C_NNNN_`) run nowhere, so the class half of a Forge join is dropped:
  `MappingSet.withoutClassRenames()`, asked for by `MappingCoordinates.runtimeKeepsReadableClassNames()`. Fabric must
  not drop it.
- **Every published mapping leaves obf**, and nothing runs obf, so a mapping is always composed:

  ```java
  MappingSet srgToOfficial = obfToSrg.invert().then(obfToOfficial);
  ```

  `MappingCoordinates` says which half a file is (`readable(...)` / `runtime(...)`); `MappingCache` joins them. 1.7.10
  declares no runtime half.
- **`then` is a join**: `then(IDENTITY)` is `IDENTITY`, and an entry the second stage lacks is dropped, so obf names
  are never shown as readable ones.
- **Formats are chosen by content**, never file name: `McpCsvFormat` (1.7.10), `ProGuardFormat` (Mojang),
  `Tsrg2Format` (MCPConfig), `TinyV2Format` (Fabric).
- **Addresses**: Mojang's `client.txt` is found through the version manifest (`MojangMappings`), its URL and `sha1:`
  digest resolved at fetch time. MCPConfig and Fabric halves are entries inside archives; the archive's digest is
  checked.
- **Nobody outside `language/` fires the fetch**: the first `current()` applies a cached mapping on the calling
  thread and hands a download to `CgJob.executor`, so a server, a test and the harness fetch alike.
- **The loader is never named**: `ScriptService` probes the runtime (`net/minecraft/class_1937` exists only under
  intermediary; `getBlockState` is `m_8055_` on Forge, `method_8320` on Fabric) and picks the coordinates from the
  answer. The probe is also what stops a dev run fetching anything.
