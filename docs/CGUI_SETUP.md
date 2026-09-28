# Setting up a project on CrystalGUI

How to build a mod that uses CrystalGUI: what to apply, what to declare, how to run it. Using the API
once it compiles is [`CGUI_BUILDING_UIS.md`](CGUI_BUILDING_UIS.md); building CrystalGUI itself is
[`CGUI_BUILD.md`](CGUI_BUILD.md).

---

## Which setup

| Your mod ships | Setup |
|---|---|
| one jar per Minecraft version, built by your usual toolchain | [**One version**](#one-version): the `com.crystalgui` plugin |
| one jar for several loaders and versions | [**Many versions**](#many-versions): singlejar-logic's `targets {}` |

Either way the player installs CrystalGUI and CrystalGraphics as mods of their own; yours depends on them
and bundles neither.

## Requirements

- JDK 25 installed. **Gradle runs on it** when you build against a CrystalGUI checkout or on
  singlejar-logic: `toolchainVersion=25` in `gradle/gradle-daemon-jvm.properties`. Your mod still compiles
  for its own Minecraft's Java.
- Everything is published to one Maven, `https://dl.cloudsmith.io/public/crystalgraphics/crystalgraphics/maven/`.
  Your settings name it for the plugins; the plugin adds it for everything else, exclusive to the
  `com.crystalgui` and `com.crystalgraphics` groups.
- **An unreleased build**: from a clone of [CrystalGUI](https://github.com/CrystalGraphics/CrystalGUI)
  (`git clone --recursive`), `./gradlew publishToMavenLocal` and `./gradlew -p CrystalGraphics
  publishToMavenLocal`; then `crystalgui.mavenLocal = true` in your `gradle.properties` puts Maven local
  first. Or build against the clone itself: [Against a CrystalGUI checkout](#against-a-crystalgui-checkout).

| Coordinate | What |
|---|---|
| `com.crystalgui:core` | the API — sources and javadoc included, and a Java 8 copy Gradle picks below Java 25 |
| `com.crystalgui:crystalgui`, `:crystalgui-language` | the shipped mods, for a dev run |
| `com.crystalgraphics:crystalgraphics`, `:crystalgraphics-joml` | the renderer mod, and JOML for Minecraft below 1.19.3 |
| `com.crystalgraphics:mc-shared` | the variant selector a many-version mod's bootstrappers call |

---

## One version

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://dl.cloudsmith.io/public/crystalgraphics/crystalgraphics/maven/")
        gradlePluginPortal()
    }
}

// build.gradle.kts
plugins {
    id("net.neoforged.moddev.legacyforge") version "2.0.141"   // your toolchain, unchanged
    id("com.crystalgui") version "1.0.0"
}
crystalgui {
    minecraft("1.20.1", "forge")   // forge, neoforge or fabric; forge covers 1.7.10 and 1.8-1.12.2 too
    language()                     // optional: the scripting mod on the dev run as well
    testing()                      // optional: the engine on testImplementation, runnable headless
}
```

The API lands on `compileOnly`; both mods land on your dev run through your toolchain's own remapping, so
the run loads the jars players install. Your descriptor declares the dependency — the plugin writes none:

```toml
# META-INF/mods.toml -- neoforge.mods.toml says type = "required" instead of mandatory
[[dependencies.yourmod]]
modId = "crystalgui"
mandatory = true
versionRange = "[1.0.0,)"
ordering = "AFTER"
side = "BOTH"
```

```json
"depends": { "crystalgui": ">=1.0.0", "crystalgraphics": ">=1.0.0" }
```

| Toolchain | Mods reach the run through | Verified |
|---|---|---|
| ModDevGradle `legacyForge` (Forge 1.17–1.20.1) | its SRG remapping | Forge 1.20.1 dev client |
| ModDevGradle `neoForge` | the runtime classpath | NeoForge 1.21.1 dev client |
| Loom | `modLocalRuntime` | Fabric 1.20.1 dev client |
| ForgeGradle, RetroFuturaGradle | `fg.deobf`, `rfg.deobf` | not yet run |
| Unimined | — | refused: `minecraft(...)` fails and says so |

- On Fabric, Fabric API must be on the run: CrystalGUI requires it (`modLocalRuntime` at least).
- A module with no Minecraft in it applies the plugin and declares nothing: it gets the API.
- `check` runs `checkCrystalGuiApi`: fails on a reference to `com.crystalgui.mc` or `com.crystalgraphics.mc`
  (the per-loader hosts; `com.crystalgraphics.mc.shared` is allowed) and on a bundled class of either mod.
- Every `run*` task checks first that both jars have a variant for your target, and names the versions
  they do run on when not.
- A run reads assets from your `src/main/resources` ahead of the jars, so a stylesheet edit shows on F3+T.

### Against a CrystalGUI checkout

For an unreleased CrystalGUI: the settings plugin builds it from a local clone, and an edit there reaches
your next run.

```kotlin
// settings.gradle.kts
plugins { id("com.crystalgui.settings") version "1.0.0" }
crystalgui {
    minecraft("1.20.1", "forge")       // every project with a toolchain takes this target
    checkout("../CrystalGUI")          // -Pcrystalgui.checkout=<path> overrides it
    harness { mode = "rpg-console" }   // optional: ./gradlew runHarness, the GL harness on your classes
}

// build.gradle.kts
plugins {
    id("net.neoforged.moddev.legacyforge") version "2.0.141"
    id("com.crystalgui")               // no version: settings already loaded it
}
```

- Modern targets only; 1.7.10 and Forge 1.8–1.12.2 take CrystalGUI from Maven.
- While in use, the checkout's `build/libs/crystalgui-*.jar` holds your target's variant alone; its own
  `singleJar` rebuilds the full jar.

---

## Many versions

One jar that installs on every loader and version you target. **Copy
[`samples/fieldnotes`](../samples/fieldnotes/README.md)** — a complete project of this shape, driven on
Forge, NeoForge and Fabric — and rename. The machinery is CrystalGraphics' `singlejar-logic`
([its README](../CrystalGraphics/singlejar-logic/README.md) is the reference).

```
settings.gradle.kts            the versions you ship
build.gradle.kts               the descriptor and the merge
core/                          your mod: CrystalGUI's API, no Minecraft, no loader
runtime/mc/modern/
  node.gradle.kts, loader.gradle.kts   what every node and every loader node does
  common/                      names Minecraft, never a loader; one node per version
  forge/ neoforge/ fabric/     a bootstrapper, a variant entry, the loader's rename
```

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("<path>/CrystalGraphics/singlejar-logic") }
plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("com.crystalgraphics.singlejar")
}
singlejar {
    targets {
        forge("1.20.1")
        neoforge("1.21.1")
        fabric("1.20.1".."1.20.4")     // every node whose range touches it
    }
}
```

A version is a line there: its node, toolchain pins and stub come from singlejar-logic's catalog.

```bash
./gradlew checkSingle            # build/libs/<mod>-<version>.jar, checked; no Minecraft toolchain needed
./gradlew checkAllTargets        # every node compiled -- before every commit
./gradlew :runtime:mc:modern:fabric:1.20.1:runClient   # a dev client; that node builds real
./gradlew singleJar -PcgStubs=false                    # every node through its real toolchain
```

| Rule | Why |
|---|---|
| Each loader has one **bootstrapper** at a fixed name, naming no Minecraft class | The loader constructs it whatever version runs; it asks `VariantBootstrap` (or `ForgeStart`) which variant to start |
| Variant entries implement `VariantEntry` and carry no `@Mod` | Two annotated variants are two mods of one id |
| Each node's classes are relocated into its own package (`nodePackage`), the bootstrapper excepted | Several nodes share one jar |
| The thin jar excludes `DEV_DESCRIPTORS` | The merge writes the descriptors once |
| A Maven library a node compiles against is a `nodeLibrary(...)` | Otherwise the stub check takes it for the toolchain's |
| Each loader node applies `com.crystalgui` and names its own target | That is what puts CrystalGUI and CrystalGraphics on its dev run |
| A Fabric node puts Fabric API on its run (`modLocalRuntime`, its `fabric.api` pin) | Both mods require it; the dev client refuses to start without it |
| Call Minecraft in compiled code, never by string | A remapper renames references, not strings |

Dev runs exist for ModDevGradle and Loom nodes: NeoForge, Forge to 1.20.1, Fabric. Forge from 1.20.2 and
NeoForge 20.2/20.3 have none; drive the shipped jar there.

### Running the jar on real clients

From a CrystalGUI clone with Prism instances in `local.properties` (see [`CGUI_BUILD.md`](CGUI_BUILD.md)):

```bash
./gradlew prodSmoke -PcgTargets=1201forge,1211neoforge,1204fabric -PcgExtraMods=<path to your jar>
```

Each client loads a world, opens the desktop and photographs it into `build/prodSmoke/`. Your jar goes
into the targeted instances only, and the next deploy removes it. Register an `AutoTest.onFrame` step to
put your own window in the late capture, as the sample does.
