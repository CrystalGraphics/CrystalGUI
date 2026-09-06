# mc1201/forge — Agent Knowledge Base

## Target Versions

MC 1.20.1 / MinecraftForge 47.x

## The loader is registration only

One `@Mod` class. Its `Events` inner class holds the three `@Mod.EventBusSubscriber` buses --
MOD for key mappings, FORGE for both sides, FORGE+CLIENT for input and paint -- and its `Network`
inner class is the `SimpleChannel` transport.

The engine's own render, reload and shutdown hooks are **not** here: CrystalGraphics ships as its own
mod and owns them. Everything this loader forwards to lives in `:mc1201:common`'s `Lifecycle1201`.

## Minecraft Source Location

Decompiled, Parchment-mapped sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `build/mc-src/java/` | MinecraftForge + Mojang Java sources, Parchment-mapped |
| `build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) |

Gitignored, not committed. Generate them with:

```bash
./gradlew :mc1201:forge:extractMcSources
# or all three loader modules at once:
./gradlew extractAllMcSources
```

Expect several minutes on the first run.

Commonly referenced locations under `build/mc-src/java/`:

- `net/minecraft/client/Minecraft.java` — main game class
- `net/minecraft/client/renderer/` — rendering pipeline
- `net/minecraft/resources/` — resource location / pack system
- `net/minecraftforge/client/` — Forge client hooks and extensions

## Build

```bash
./gradlew :mc1201:forge:compileJava
./gradlew :mc1201:forge:shadowJar
./gradlew :mc1201:forge:serverSmoke -PcgAcceptEula   # boots a dedicated server, asserts, stops
```

## Plugin

Uses `net.neoforged.moddev.legacyforge` (ModDevGradle legacyForge), which covers MinecraftForge
1.17-1.20.1 and is Gradle 9 + JDK 25 compatible. Version pins live in `build.gradle.kts` under the
`mc1201.forge` / `mc1201.parchment.*` keys.
