# runtime/mc/modern — one source tree, a node per Minecraft version

Adding a version, pins, toolchains and checks: [`CGUI_BUILD.md`](../../../docs/CGUI_BUILD.md).

A branched Stonecutter tree: `common`, `forge`, `neoforge` and `fabric` are branches, and each Minecraft
version a branch targets is a node, `:runtime:mc:modern:<branch>:<version>`. **How the tree works, how to
add a version and what will bite is CrystalGraphics' — read
[`CrystalGraphics/singlejar-logic/README.md`](../../../CrystalGraphics/singlejar-logic/README.md)
§ *Many Minecraft versions*.** This file holds only what is CrystalGUI's own.

Every node compiles from the committed stub database unless it is made real (a run task, the active
IDE node, `-PcgRealNodes`, `-PcgStubs=false`); a node added or re-pinned needs the database regenerated —
[`STUBS.md`](../../../CrystalGraphics/singlejar-logic/STUBS.md).

```bash
./gradlew checkAllTargets                                        # every node, every source set
./gradlew :runtime:mc:modern:forge:1.20.1:runClient              # a dev client (not every node has one)
./gradlew :runtime:mc:modern:neoforge:1.20.4:serverSmoke -PcgAcceptEula
./gradlew :runtime:mc:modern:<branch>:<version>:extractMcSources # that node's Minecraft, into versions/<version>/build/mc-src/
```

## What is CrystalGUI's

- **The versions are `singlejar { targets { } }` in `settings.gradle.kts`**, the same ranges as
  CrystalGraphics'. The composite substitutes CrystalGraphics' node of each version, so **a version added
  here must exist in CrystalGraphics first**.
- **A node depends on CrystalGraphics' node of its own version** — its common through the composite
  (`sameVersionNodeCoordinate`), and its loader through the dev run (`crystalgraphics-run`, handed the
  paths as `cgGraphicsNodes` by `cg-modern-loader`, since an applied script cannot import build-logic).
- **It calls CrystalGraphics' common node, never a copy of it** — `ResourceIds` is CrystalGraphics'.
  The thin jar relocates those references to where CrystalGraphics' thin jar for the same node ships
  its common (`graphicsNodeCommon` in `cg-modern-loader`), so the merged jars agree.
- **Two source sets per node**: `main` for the host jar and `lang` for `crystalgui_language`, which
  compiles against the same Minecraft.
- **Fabric excludes both CrystalGraphics groups from its runtime classpath** — the libraries' and the
  common node's (`com.crystalgraphics.mc.modern.common`) — because CrystalGraphics arrives there as a mod.
- **One entry class per loader serves every node**: `ForgeBootstrap` (`runtime/mc/forge-bootstrap`),
  `NeoForgeBootstrap`, `FabricBootstrap`. Each reads the jar's variant table and constructs the running
  version's `VariantEntry` — `CrystalGUIForge`, `CrystalGUINeoForge`, `CrystalGUIFabric`. A node class
  carries no `@Mod` and no `@EventBusSubscriber`: two variants bearing one would be two mods of one id.
