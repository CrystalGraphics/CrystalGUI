# The language stack — how analysis actually resolves a name

**For**: anyone about to change why the editor colours, completes, or reports an error differently on one
host than another. It is the wiring under `language/`, written because the layer degrades silently by
design and every failure in it therefore looks like something else.

> **The rule that makes this hard, stated once.** Every tier of this stack has a legitimate absent state,
> and absence never throws. No engine → grammar colouring. No grammar → core's word-list lexer. Neither →
> plain text. A missing classpath → a compiler that answers *wrongly* rather than not at all. So the
> question is almost never "what broke"; it is "which tier silently answered nothing, and why".

---

## 1. The four questions, and who owns each

| Question | Owner | Notes |
|---|---|---|
| Which engine versions can this JVM run? | `EngineBand.detect()` | One read of `java.specification.version`. Not a preference — a Java 8 JVM cannot load a newer class file, so the oldest band is the host's own ceiling |
| Where are that band's jars? | `EngineSource` | `EngineHost.defaultSource()` = configured dir → bundled in the jar → downloaded |
| What does a compile resolve against? | `ScriptNameEnvironment` | Three tiers, §4 |
| What does the running game know that `language/` cannot work out? | `ScriptService`, over CrystalGraphics' `CgGameClasses` and `CgGameDirectory` | Live bytes, mappings, namespace probe, **and `cacheRoot()`**. No loader registers anything |

No game (`ScriptService.live()` false) is a real deployment, not a test double: the harness and every unit test run
that way, with no live bytes, no mappings, and a cache under `build/crystalgui-cache`. Tests fake a game with
`FakeGame`, which provides CrystalGraphics' slots as a host would.

---

## 1a. The language jar is a mod, and starts itself

`crystalgui-language-<version>.jar` is a **mod of its own**, id `crystalgui_language`, on every loader and version
the host jar supports. It contains no per-loader code that does anything. Being a mod is what makes the stack run:
CrystalGraphics finds lifecycle listeners in the jars the loader lists as mods.

### What makes it a mod

A loader recognises a mod by its **descriptor**, and Forge-family loaders also need an **`@Mod` class**. The language
jar carries both. Only the `@Mod` classes are source files; the descriptors are generated.

| Loader | Descriptor (generated) | `@Mod` class (source) |
|---|---|---|
| 1.7.10, Forge 1.8.9–1.12.2 | `mcmod.info` | `runtime/mc/forge-stubs/src/lang/.../CrystalGUILangForge.java`, carrying both eras' `@Mod` |
| Forge 1.13.2–26.3 | `META-INF/mods.toml` | the same `CrystalGUILangForge` |
| NeoForge | `META-INF/neoforge.mods.toml` | `runtime/mc/forge-stubs/src/lang/.../CrystalGUILangNeoForge.java` |
| Fabric | `fabric.mod.json`, entrypoint lists empty | none: Fabric lists a mod from its descriptor alone |

**There is no `mods.toml` or `fabric.mod.json` in the source tree.** They are generated, for this jar and the host
jar alike:

1. **Declared** in `runtime/mc/modern/build-logic/src/main/kotlin/cg-descriptors.gradle.kts` as
   `val cgLangDescriptor = ModDescriptor(...)`: id, name, licence, the dependency on `crystalgui`, and one variant
   per loader and Minecraft version. The host's `cgDescriptor` is just above it.
2. **Written** by `registerDescriptorTasks(cgLangDescriptor, "cgui-lang", name = "language", ...)` in the same script
   (CrystalGraphics' `singlejar-logic`, `ModDescriptor.kt`), to `build/descriptors/language/`: `mcmod.info`,
   `META-INF/mods.toml`, `META-INF/neoforge.mods.toml`, `fabric.mod.json`, `pack.mcmeta` and the variant table
   `META-INF/crystalgui_language/variants.json`.
3. **Merged** into the language jar's root by `languageJar`. `checkLanguageJar` fails the build when any of them, or
   `META-INF/crystalgraphics/listeners`, is missing (`requiredEntries` in `cg-single-jar.gradle.kts`).

A variant names no entry class. The Forge and NeoForge `@Mod` classes still pick the running version's variant
(`ForgeStart`, `VariantBootstrap`), which refuses a version the jar does not support, and then construct nothing.

### How it starts

1. The loader lists `crystalgui_language` among its mods, with its jar.
2. CrystalGraphics reads `META-INF/crystalgraphics/listeners` in every mod's root (`CgModLoader.roots()`, each era
   answering from its loader's mod list) and from its own classloader (`CgListenerDiscovery`). The language jar's says:

   ```
   common com.crystalgui.language.CgLanguageLifecycle
   ```

3. `CgLanguageLifecycle` starts the stack: on a client in `onLoad`, as a load task behind the loading
   screen, and on a dedicated server in `onServerStarting`.

### What is easy to get wrong

- **Removing the descriptor or the `@Mod` class removes the stack.** The jar would still be on the game's classpath,
  but on ModLauncher no mod root would list it and the listeners file would go unread, so nothing would start and
  nothing would say why.
- **No code goes in the `@Mod` classes or into a variant entry.** Anything the stack needs from the game is a
  CrystalGraphics platform service (`CgGameClasses`, `CgGameDirectory`), and anything it does at a game moment is
  `CgLanguageLifecycle`.
- **The host jar never names this jar.** Without it installed, no mod root holds the listeners file, and the editor
  colours from core's built-in word-list lexer.

---

## 2. The band is opened once, in isolation

`EngineHost.shared(source)` opens one `EngineHost` for the detected band and shares it across languages.
`EngineClassLoader` is a **child-first** `URLClassLoader` over the band's jars, and the child-first part is
not a preference either: several mods ship Rhino at versions we do not choose, and a flat mod classpath
resolves whichever it saw first.

**`PARENT_FIRST` is the whole design.** `java.`, `javax.`, `jdk.`, `sun.`, the `engine.bridge` package and
`com.crystalgui.text.*` delegate upward. Everything else is isolated. Two classes of the same name from two
loaders are different types, so anything host and engine both name must be on that list — and anything on
that list stops being isolated. Keeping it to one package is what keeps the trade honest.

**The adapters load inside the band**, not on the host: they name ECJ and Rhino types directly, so only a
loader that can see those can load them. `EngineHost.withOwnClasses` appends this module's own code source
to the band's URLs for exactly that reason. Without it `Class.forName` falls back to the parent, the parent
loads the adapter happily, and the first engine type it touches dies as
`NoClassDefFoundError: org/mozilla/javascript/ErrorReporter` — a message naming Rhino when the fault is
that this module could not find itself.

### The statics trap, which has already been paid for once

A class loaded by the band gets the band's **own copy** of anything not on `PARENT_FIRST`, statics
included. `ScriptNameEnvironment` names no `ScriptService` or `PlatformMappings` for this reason: it would read a
mapping nothing resolved, and resolve entirely from files. *Everything works and
nothing is live* — and a file-based answer is plausible, so nothing reports it. The host composes
`TypeBytes` and only `byte[]` crosses the gap.

---

## 3. Where the engine jars come from

```java
EngineSource.firstOf(configuredSource(), bundledSource(), downloadedSource(progress))
```

`firstOf` takes the first **non-empty** answer, so an ordinary launch never touches the network.

- **configured** — `-Dcrystalgui.engines.dir`, what a dev run sets from `:language:stageEngines`
- **bundled** — `assets/crystalgui/engines/<band>/` inside the mod jar, extracted on first use
- **downloaded** — the band's jars as `download/locations.json` lists them, each checked against the
  pin its own jar shipped with

**Both of the last two begin by asking `ScriptService.cacheRoot()` where they may write**: the game's
`crystalgui/cache`, or `build/crystalgui-cache` with no game.

---

## 4. Resolution: three tiers, in this order

`ScriptNameEnvironment` answers each name in turn:

1. **`TypeBytes.readable`** — the live runtime, remapped. What is loaded is what will execute, so where it
   and a file disagree it wins. This is why the environment is bytes rather than a directory: on a
   Minecraft host the disk view lies (obfuscated jars remapped as they load) and transformers add members
   no class file has.
2. **The classpath delegate** — ECJ's own `FileSystem`. **The JDK, and every jar on the script's classpath
   that is not loaded.**
3. **`TypeBytes.synthesized`** — a reflective stub, only where both said nothing. Erased of whatever
   reflection cannot see, so it must never pre-empt a real class file.

Tier 2 is built in `EcjCompilation.fileSystemFor`, which configures ECJ's batch `Main` with `-source`,
`-target`, `-proc:none`, `-nowarn` and `-classpath`. **Nothing names the platform classes**: no `--system`,
no `--release`, no `-bootclasspath`. ECJ is left to infer the JDK from the running JVM.

### What the classpath is assembled from — `HostClasspath.detect()`

Five routes, unioned, each contributing nothing when it does not apply:

| Route | Supplies | Where it works |
|---|---|---|
| `URLClassLoader.getURLs()` | app classpath | plain JVMs |
| reflective `getSources()`/`getURLs()` | loader-specific | 1.7.10 (`LaunchClassLoader`) |
| `java.class.path` | app classpath | plain JVMs; **the harness** |
| `ModuleLayer` locations (`file:` only) | mod jars, libraries | a modular host with no `java.class.path` — **not 1.20.x, which has one** |
| `java.home/lib/rt.jar` | platform classes | **Java 8 only** |

A JDK module's location is `jrt:/java.base`, which is not a classpath entry — so on Java 9+ **no route here
supplies the platform classes**, by design, because tier 2 was expected to infer them.

---

## 5. What each host actually supplies

| | plain JVM (harness, tests) | 1.7.10, Forge 1.8–1.12.2 | 1.13+ (measured on 1.20.1) |
|---|---|---|---|
| Classpath route that fires | `java.class.path` | `getSources()` | `java.class.path` |
| Platform classes | ECJ infers | `rt.jar` (Java 8) or inference | ECJ infers |
| Mapping (`ScriptService.mappings()`) | none | MCP stable, by version | Mojang's names joined to SRG or intermediary, MCP stable before 1.14.4 |
| Live bytes (tier 1, `CgGameClasses`) | none | LaunchWrapper's transformer chain (`GameClasses1710`, `GameClassesLegacy`) | the loader that will run the class (`GameClassesModern`) |
| Compliance | 1.8 | 8 | the running JVM's band (**21** on the measured client) |

The compliance row is what separates the hosts: from compliance 9 ECJ resolves module-aware, which is
where §6 bites. The modern column's service answered `cacheRoot()` alone when §6 was found; it has
supplied live bytes since.

### Measured, 2026-09-08

Taken with temporary instruments in `HostClasspath.detect` and `EcjCompilation.fileSystemFor`, since
removed. Both are worth re-adding for an afternoon if this layer misbehaves again; neither belongs in a
shipped jar.

```
plain JVM : level=1.8 classpath=0   entries; jrt-fs=true; java.lang.Object=RESOLVED
1.20.1    : level=21  classpath=125 entries; jrt-fs=true; java.lang.Object=RESOLVED
            classpath routes: urls=0 reflective=0 sysprop=125 modulelayer=0 javalib=0
```

- **A plain JVM resolves the JDK with a classpath of ZERO entries.** ECJ's inference genuinely supplies
  the platform classes, as tier 2's design assumes. Nothing has to name them on any host, so there is no
  `--system`, `--release` or `-bootclasspath` anywhere in this stack.
- **`sysprop=125`, `modulelayer=0`.** ModLauncher populates `java.class.path` after all. The module-layer
  route was added on the assumption it did not; it contributes nothing on any host in the build and is
  kept only for a modular host that genuinely publishes no classpath.

---

## 6. The live tier must never answer for the platform

**A service registered for `cacheRoot()` alone still turns the live tier on.** `PlatformTypeBytes.of()`
reads "a platform is registered" as "the runtime holds bytes worth preferring over the classpath". That is
true of a remapped 1.7.10 client and false of a service registered so an engine band knows where it may
extract — and `ScriptService` answered `liveBytes()` by borrowing `ScriptService.NONE`'s classloader
view, which answers for **every loadable class, the JDK included**.

So `find()` asked tier 1 for `java/lang/Object`, got bytes, and returned them without consulting the
delegate. From compliance 9 ECJ resolves module-aware and **discards** an answer for `java.lang.Object`
that is not attributed to `java.base` — silently, as `projectModule()` documents for package chains. The
editor reported sixteen errors beginning `The type java.lang.Object cannot be resolved. It is indirectly
referenced from required .class files`, while the classpath behind it was intact and a direct `findType`
on the delegate answered `RESOLVED`.

Every other host hides it. The harness, every test and a dedicated server register no service, so `live`
is false and resolution goes straight to the delegate. 1.7.10 registers a real one but compiles at band 8,
where ECJ never enters module mode. **Only a modern band plus a registered platform reaches it**, which
1.20.1 was the first host to be.

Two rules came out of it, and they are different rules:

1. **A platform name is the delegate's, always.** `find()` routes `java/`, `javax/`, `jdk/` and `sun/`
   past tier 1. The live tier exists for names the classpath sees wrongly — remapped members, bytes a
   transformer produced — and none of that reaches the JDK, on any host or band.
2. **A service with nothing live to offer says so**, with `ReadableView.ByteSource.NONE`, and
   `PlatformTypeBytes.of()` reads that as no live tier at all. Returning `ofClassLoader` instead is
   documented-wrong by that method's own javadoc: it reads what is on disk, which is the thing that lies.

### How it was found

Two temporary instruments reported health throughout, because both measured the **delegate** — which was
never the broken part. They were still worth the afternoon: they ruled the classpath and the JDK out, and
the contradiction between "this environment resolves `java.lang.Object`" and "the editor says it cannot"
is what pointed at the tier in front of it.

What settled it was **`-Dcrystalgui.language.noLiveBytes`**, which turns tier 1 off with no code change:
the same file went from sixteen errors to zero.

**When this layer misbehaves, reach for that flag first.** "Live" and "file-based" agree nearly
everywhere, so the one question worth asking early is which of the two is answering. It is forwarded by
the modern dev runs.

---

## 7. Mappings: on the modern tree every mapping is a JOIN

A script is authored in ONE namespace — the readable one, which is Mojang's official names — and
`MappingSet` translates at the boundary: in, so the compiler is shown readable members; out, so compiled
bytecode links against what the runtime actually declares.

### What each runtime speaks, measured from the build

A forge node has a `reobfJar` task and a neoforge node has none, which is the whole story:

| Runtime | Classes | Members | Mapping |
|---|---|---|---|
| any dev run | official | official | none |
| NeoForge 1.20.4 | official | official | none |
| Forge 1.20.1 | official | **SRG** | SRG → official |
| Fabric 1.20.1 | **intermediary** | **intermediary** | intermediary → official |

**Forge's is a mixed namespace** — official class names with SRG members. MCPConfig's own "srg"
namespace is *not* that mix: its classes are `net/minecraft/src/C_NNNN_`, a vocabulary nothing runs.

> **The class half of a Forge join has to be thrown away.** `enn net/minecraft/src/C_3391_ 3391` is
> what `joined.tsrg` says, so the raw composition keys every member under `C_3391_` and answers
> `runtimeClass(Minecraft)` with it — and `runtimeClass` is what rewrites a compiled script's class
> references, so the script links against a class no runtime has.
> `MappingSet.withoutClassRenames()` moves the owners into the readable namespace and drops the class
> table; `MappingCoordinates.runtimeKeepsReadableClassNames()` is how a target asks for it. **Fabric
> must not**, because `net/minecraft/class_1937` is genuinely what its runtime has.
>
> This was asserted the other way round for a whole feature, and the fixtures agreed because they were
> written from the assumption rather than from the file. Twenty-four green tests, and a mapping that
> could not link one line.

### No published artifact maps out of a namespace a runtime speaks

Mojang's `client.txt` is obf→official, MCPConfig's `joined.tsrg` is obf→srg, Fabric's `intermediary` is
obf→intermediary. Every one of them maps *away* from obf, and nothing runs obf. So the mapping is always
composed, never downloaded:

```java
MappingSet srgToOfficial = obfToSrg.invert().then(obfToOfficial);
```

`MappingCoordinates` says which half a file is (`readable(...)` / `runtime(...)`); `MappingCache` parses
each half on its own and joins them. 1.7.10 declares no runtime half and keeps the overlay behaviour it
has always had.

> **`then` is a join, not a monoid composition.** `then(IDENTITY)` is `IDENTITY`, not `this` — a second
> stage that knows nothing would otherwise leave every name pointing at the *obfuscated* namespace, and
> `m_8055_` would be shown to a script author as `a`. Runtime names shown as they are is a supported
> state; obf names dressed as readable ones is not. The same rule drops any single entry the second
> stage is missing.

### Four formats, chosen by CONTENT

`McpCsvFormat` (1.7.10), `ProGuardFormat` (Mojang), `Tsrg2Format` (MCPConfig), `TinyV2Format` (Fabric).
Never by file name — several publishers ship a `mappings.txt`.

### Where the addresses come from

Mojang's `client.txt` lives at a content-addressed URL discoverable only through the version manifest, so
`MappingCoordinates.Source` resolves a URL **and its digest** at fetch time rather than pinning a number
nobody could verify. `MojangMappings` does that lookup; the digest comes back tagged `sha1:`, which
`CgCacheFiles` checks alongside `gitblob:` and bare MD5. The runtime halves live inside archives
(MCPConfig a zip, Fabric a jar), so a file may name an entry to extract; the digest is verified on the
**archive**, and presence is the check on what came out of it.

### Nobody outside `language/` fires the fetch

The first `current()` probes, applies a cached mapping **on the calling thread** — so the first analysis
already has readable names rather than showing runtime ones and correcting itself — and hands a genuine
download to CrystalGraphics' job pool (`CgJob.executor`), which needs no frame or tick to drain it, so a
dedicated server, a test and the harness fetch alike. **No loader states anything: the game is read, never configured.**

### The loader is never named

`ScriptService` asks the runtime which namespace it is in — it reads `net/minecraft/class_1937`, a
class only an intermediary runtime has — and picks the probe and the coordinates from the answer. A flag
someone sets is a flag that will be wrong in exactly the environment nobody tests, and a future loader
shipping intermediary gets the right answer without being named.

> **The probe is what stops a dev run fetching anything.** `getBlockState` is declared under official
> names and is `m_8055_` on Forge and `method_8320` on Fabric, so one class read separates the runtimes
> that need no mapping from the two that do.
