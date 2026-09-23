# runtime/mc/modern — one source tree, a node per Minecraft version

A branched Stonecutter tree: `common`, `forge`, `neoforge` and `fabric` are branches, and each Minecraft
version a branch targets is a node, `:runtime:mc:modern:<branch>:<version>`. **How the tree works, how to
add a version and what will bite is CrystalGraphics' — read
[`CrystalGraphics/singlejar-logic/README.md`](../../../CrystalGraphics/singlejar-logic/README.md)
§ *Many Minecraft versions*.** This file holds only what is CrystalGUI's own.

```bash
./gradlew checkAllTargets                                # every node, every source set
./gradlew :runtime:mc:modern:forge:1.20.1:runClient
./gradlew :runtime:mc:modern:neoforge:1.20.4:serverSmoke
```

## What is CrystalGUI's

- **The node table** is `modernNodes` in `settings.gradle.kts`. It is also what the composite reads to
  substitute CrystalGraphics' common node of each version, so **a version added here must exist in
  CrystalGraphics first**.
- **A node depends on CrystalGraphics' node of its own version** — its common through the composite
  (`sameVersionNodeCoordinate`), and its loader through the dev run (`crystalgraphics-run`, handed the
  paths as `cgGraphicsNodes` by `cg-modern-loader`, since an applied script cannot import build-logic).
- **Two source sets per node**: `main` for the host jar and `lang` for `crystalgui_language`, which
  compiles against the same Minecraft.
- **Fabric excludes both CrystalGraphics groups from its runtime classpath** — the libraries' and the
  common node's (`com.crystalgraphics.mc.modern.common`) — because CrystalGraphics arrives there as a mod.
