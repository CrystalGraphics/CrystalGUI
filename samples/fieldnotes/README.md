# Field Notes — a CrystalGUI app, one jar, several loaders and versions

A worked example of a **platform-abstract project**: a Minecraft-free core, a Stonecutter tree of loader
nodes, and one merged jar that installs on every loader and version it targets. It is built on
CrystalGraphics' `singlejar-logic`, which is where the machinery and its reference live
([`CrystalGraphics/singlejar-logic/README.md`](../../CrystalGraphics/singlejar-logic/README.md)).

The mod opens one window saying which of its variants is running — "Fabric on Minecraft 1.20.4" — and
the identifier its Minecraft side built.

## Build it

```bash
# once, in CrystalGUI and in CrystalGraphics: what the core and the bootstrappers compile against
./gradlew :core:publishToMavenLocal && ./gradlew -p consumer-plugin publishToMavenLocal
./gradlew -p CrystalGraphics :runtime:mc:shared:publishToMavenLocal

cd samples/fieldnotes
./gradlew checkSingle          # build/libs/fieldnotes-1.0.0.jar, checked
./gradlew checkAllTargets      # every node compiled
```

No Minecraft toolchain is set up: every node compiles against singlejar-logic's stub database, which the
settings plugin finds beside the build logic. `-PcgStubs=false` builds every node for real.

## Run it in a dev client

```bash
./gradlew :runtime:mc:modern:fabric:1.20.1:runClient      # or forge:1.20.1, neoforge:1.21.1
```

The node becomes real for the run (its toolchain is set up the first time). Its mod is the node, its
common node and `core`, with CrystalGUI and CrystalGraphics beside it from Maven local. `-Dcrystalgui.*` on
the command line reaches the game: `-Dcrystalgui.autotest=true` opens the desktop, runs the Field Notes
step, photographs and quits.

## Run it on real clients

From CrystalGUI, with its Prism instances in `local.properties`:

```bash
./gradlew prodSmoke -PcgTargets=1201forge,1211neoforge,1204fabric \
    -PcgExtraMods=samples/fieldnotes/build/libs/fieldnotes-1.0.0.jar
```

The jar goes only into the targeted instances, and the next deploy takes it out again. Each client loads
a world, opens the desktop, and — through `AutoTest.onFrame` — launches Field Notes before its late
capture, so `build/prodSmoke/<target>-late.png` shows the window and the log says
`[fieldnotes] probe: window launched true`.

## The layout

```
settings.gradle.kts                  singlejar { targets { ... } } -- the versions, and nothing else
build.gradle.kts                     the descriptor and the merge, read off the tree
core/                                the application: CrystalGUI's API, no Minecraft, no loader
runtime/mc/modern/
  stonecutter.gradle.kts             which node an IDE edits
  node.gradle.kts                    every node: coordinates, Java, repositories
  loader.gradle.kts                  every loader node: its thin jar, what that may hold, its dev run
  common/                            names Minecraft, never a loader; one node per version
  forge/ neoforge/ fabric/           a bootstrapper, a variant entry, and the loader's rename
```

| Piece | Why it is shaped this way |
|---|---|
| `targets { forge("1.20.1"); neoforge("1.21.1"); fabric("1.20.1".."1.20.4") }` | A version is a line here. Its toolchain pins come from singlejar-logic's catalog; a node's directory is created when missing |
| `core` compiles once, ships once | It names CrystalGUI only (`com.crystalgui` plugin), so every loader runs the same classes |
| `common` per Minecraft version | `Game` calls Minecraft — and `Game.id` differs by a directive, `//? if >=1.21` |
| Everything a node ships moves to its own package | Five nodes, one jar: `mc.fabric.v1204.common.Game` beside `mc.forge.v1201.common.Game` |
| A bootstrapper per loader, at one name | What the loader constructs whatever version runs; it asks CrystalGraphics' variant selector (`com.crystalgraphics:mc-shared`) which variant to start |
| `com.crystalgui` on each loader node, naming its own target | Puts CrystalGUI and CrystalGraphics on that node's dev run |
| The rename per loader | Forge 1.20.1 runs SRG, Fabric intermediary, NeoForge Mojang's names: each thin jar is renamed to what its loader runs before the merge |
| `ApplicationKinds` in `META-INF/services` | How a jar puts an application in every desktop's launcher; the merge unions service files |

## Adding a version or a loader

A version: widen a range in `targets {}`. If the catalog has the node, that is all — the descriptor,
the merge and the checks follow the tree. If `checkAllTargets` fails, the API moved: add a directive
where it did.

A loader: a range for it in `targets {}`, a branch directory with the three files the others have, and
its entry in `build.gradle.kts`' `modernVariants` and `bootstrappers`.
