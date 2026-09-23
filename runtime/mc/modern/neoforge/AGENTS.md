# runtime/mc/modern/neoforge — Agent Knowledge Base

## Target Versions

**MC 1.20.4 / NeoForge 20.4.x** — despite the `runtime/mc/modern/` directory name.
NeoForge published no stable 1.20.1 series; 20.4.x is the earliest, and the directory name is
kept so the three loaders sit together.

## The loader is registration only

One `@Mod` class. Its `Events` inner class registers every listener on `NeoForge.EVENT_BUS`
from the constructor, and its `Network` inner class is the payload-based transport.

The engine's own render, reload and shutdown hooks are **not** here: CrystalGraphics ships as its own
mod and owns them. Everything this loader forwards to lives in the common branch's `LifecycleCrystalGUI`.

## Minecraft Source Location

Decompiled, Parchment-mapped sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `versions/1.20.4/build/mc-src/java/` | NeoForge + Mojang Java sources, Parchment-mapped |
| `versions/1.20.4/build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) |

Gitignored, not committed. Generate them with:

```bash
./gradlew :runtime:mc:modern:neoforge:1.20.4:extractMcSources
# or all three loader modules at once:
./gradlew extractAllMcSources
```

Expect several minutes on the first run.

Commonly referenced locations under `versions/1.20.4/build/mc-src/java/`:

- `net/minecraft/client/Minecraft.java` — main game class
- `net/minecraft/client/renderer/` — rendering pipeline
- `net/minecraft/resources/` — resource location / pack system
- `net/minecraft/world/` — world/level logic

## Build

```bash
./gradlew :runtime:mc:modern:neoforge:1.20.4:compileJava
./gradlew :runtime:mc:modern:neoforge:1.20.4:shadowJar
./gradlew :runtime:mc:modern:neoforge:1.20.4:serverSmoke -PcgAcceptEula   # boots a dedicated server, asserts, stops
```

## Plugin

Uses `net.neoforged.moddev` (ModDevGradle). Version pins are per node, in
`versions/<version>/gradle.properties` (`mc.version`, `neoforge.version`, `parchment.*`, `asm`).
