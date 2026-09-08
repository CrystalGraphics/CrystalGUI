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
| What does this loader know that `language/` cannot work out? | `ScriptService` | Live bytes, mappings, namespace probe, **and `cacheRoot()`** |

`ScriptService.NONE` is a real deployment, not a test double: the harness, every unit test and a dedicated
server run with no platform registered.

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
included. `ScriptNameEnvironment` names no `ScriptServices` for this reason: it would read a registry the
host never wrote to, conclude there is no platform, and resolve entirely from files. *Everything works and
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
- **downloaded** — the band's `name|md5|url` manifest, fetched and verified

**Both of the last two begin by asking `ScriptService.cacheRoot()` where they may write.** That is the
non-obvious coupling in this stack: a host that registers no `ScriptService` does not merely lose scripting,
it loses the ability to extract *or* fetch an engine band. Registering one that answers `cacheRoot()` and
declines the rest is a legitimate and useful configuration.

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

| | plain JVM (harness, tests) | mc1710 | mc1201 |
|---|---|---|---|
| Classpath route that fires | `java.class.path` | `getSources()` | `java.class.path` |
| Platform classes | ECJ infers | `rt.jar` (Java 8) or inference | ECJ infers |
| `ScriptService` | `NONE` | `ScriptService1710` (live bytes, mappings) | `ScriptService1201` (`cacheRoot()` only) |
| Live bytes (tier 1) | none | LaunchWrapper | **none — `ByteSource.NONE`** |
| Compliance | 1.8 | 8 | **21** |

Two of those rows carry the whole difference in behaviour between the hosts, and §6 is why.

### Measured, 2026-09-08

Taken with temporary instruments in `HostClasspath.detect` and `EcjCompilation.fileSystemFor`, since
removed. Both are worth re-adding for an afternoon if this layer misbehaves again; neither belongs in a
shipped jar.

```
plain JVM : level=1.8 classpath=0   entries; jrt-fs=true; java.lang.Object=RESOLVED
mc1201    : level=21  classpath=125 entries; jrt-fs=true; java.lang.Object=RESOLVED
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
extract — and `ScriptService1201` answered `liveBytes()` by borrowing `ScriptService.NONE`'s classloader
view, which answers for **every loadable class, the JDK included**.

So `find()` asked tier 1 for `java/lang/Object`, got bytes, and returned them without consulting the
delegate. From compliance 9 ECJ resolves module-aware and **discards** an answer for `java.lang.Object`
that is not attributed to `java.base` — silently, as `projectModule()` documents for package chains. The
editor reported sixteen errors beginning `The type java.lang.Object cannot be resolved. It is indirectly
referenced from required .class files`, while the classpath behind it was intact and a direct `findType`
on the delegate answered `RESOLVED`.

Every other host hides it. The harness, every test and a dedicated server register no service, so `live`
is false and resolution goes straight to the delegate. mc1710 registers a real one but compiles at band 8,
where ECJ never enters module mode. **Only a modern band plus a registered platform reaches it**, which
1.20.x was the first host to be.

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
the 1.20.x dev runs; `ClientProbe1201` prints the analysed file's diagnostics as text, so the answer does
not have to be read off a screenshot.
