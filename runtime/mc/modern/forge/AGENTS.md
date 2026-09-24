# runtime/mc/modern/forge — Agent Knowledge Base

## Target Versions

MC 1.15–1.21.11 / MinecraftForge 29–61 — a node each for 1.14.4, 1.15.2 (also 1.15, 1.15.1), 1.16.5 (also 1.16.1-1.16.4), 1.17.1, 1.18.2 (also 1.18, 1.18.1), 1.19.2
(also 1.19, 1.19.1), 1.19.3, 1.19.4, 1.20.1, 1.20.2, 1.20.4, 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6
(also 1.21.7), 1.21.8, 1.21.10 (also 1.21.9) and 1.21.11, pinned in `versions/<version>/gradle.properties`.
Forge 1.21 is refused (Forge 51 has no HUD event); Forge published nothing for 1.17, 1.20.5 or 1.21.2.

**Forge 28-36 (1.14.4-1.16.x)** are built by Unimined, run on Java 8, and differ once more: `fml.network`,
`fml.event.server`, `CrashReportExtender`, and key registration through `DeferredWorkQueue`.

**Three API eras below 1.19**, each a directive in `CrystalGUIForge`: Forge 37 (1.17.1) names screen
events `GuiScreenEvent.*Event` and keeps servers and networking in `fmlserverevents`/`fmllegacy`;
Forge 38-40 (1.18.x) name them `ScreenEvent.*Event`; both register keys through `ClientRegistry` in
client setup and paint the HUD on `RenderGameOverlayEvent.Post` for `ElementType.ALL`.

**Forge 56-57 (1.21.6-1.21.7) have no HUD event either**, and there the HUD is a node mixin on
`Gui.render` (`mixin/HudHook`, gated by `CrystalGuiForgeMixins` in `runtime/mc/shared`). From 1.21.6 Forge
is EventBus 7: every subscription in `CrystalGUIForge` has a `>=1.21.6` twin.

## The loader is registration only

One `@Mod` class. Its `Events` inner class holds the three `@Mod.EventBusSubscriber` buses --
MOD for key mappings, FORGE for both sides, FORGE+CLIENT for input and paint -- and its `Network`
inner class is the `SimpleChannel` transport.

The engine's own render, reload and shutdown hooks are **not** here: CrystalGraphics ships as its own
mod and owns them. Everything this loader forwards to lives in the common branch's `LifecycleCrystalGUI`.

## Minecraft Source Location

Decompiled, Parchment-mapped sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `versions/1.20.1/build/mc-src/java/` | MinecraftForge + Mojang Java sources, Parchment-mapped |
| `versions/1.20.1/build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) |

Gitignored, not committed. Generate them with:

```bash
./gradlew :runtime:mc:modern:forge:1.20.1:extractMcSources
# or all three loader modules at once:
./gradlew extractAllMcSources
```

Expect several minutes on the first run.

Commonly referenced locations under `versions/1.20.1/build/mc-src/java/`:

- `net/minecraft/client/Minecraft.java` — main game class
- `net/minecraft/client/renderer/` — rendering pipeline
- `net/minecraft/resources/` — resource location / pack system
- `net/minecraftforge/client/` — Forge client hooks and extensions

## Build

```bash
./gradlew :runtime:mc:modern:forge:1.20.1:compileJava
./gradlew :runtime:mc:modern:forge:1.20.1:shadowJar
./gradlew :runtime:mc:modern:forge:1.20.1:serverSmoke -PcgAcceptEula   # boots a dedicated server, asserts, stops
```

## Plugin

Uses `net.neoforged.moddev.legacyforge` (ModDevGradle legacyForge), which covers MinecraftForge
1.17-1.20.1 and is Gradle 9 + JDK 25 compatible. Version pins are per node, in
`versions/<version>/gradle.properties` (`mc.version`, `forge.version`, `parchment.*`).
From 1.20.2 legacyForge sets up nothing, so those nodes pin `neoform.version` too and are built
from parts: NeoForm's Minecraft, Forge's jars compileOnly, no dev run, and `SrgReobfJar` below 1.20.6.
See CrystalGraphics' `singlejar-logic/README.md` § Many Minecraft versions.
