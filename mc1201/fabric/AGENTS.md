# mc1201/fabric — Agent Knowledge Base

## Target Versions

MC 1.20.1 / Fabric

## The loader is registration only

**Two entry points, and both are needed.** `fabric.mod.json` names them separately: `main` runs on
both sides, `client` only on a client, and they are different interfaces.

`CrystalGUI1201FabricCommon` is the `main` one and carries the `Network` transport and the `Events`
inner class, because a dedicated server needs the channel. `CrystalGUI1201Fabric` is the `client` one
and does nothing but call `Events.registerClient()` — the half that touches client-only Fabric APIs a
server must never load.

The engine's own render, reload and shutdown hooks are **not** here: CrystalGraphics ships as its own
mod and owns them. Everything this loader forwards to lives in `:mc1201:common`'s `Lifecycle1201`.

## Minecraft Source Location

Decompiled, Parchment-mapped sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `build/mc-src/java/` | MC 1.20.1 Java sources, decompiled by Loom via Vineflower, Parchment-mapped |
| `build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) |

Gitignored, not committed. Generate them with:

```bash
./gradlew :mc1201:fabric:extractMcSources
# or all three loader modules at once:
./gradlew extractAllMcSources
```

Expect several minutes on the first run.

Commonly referenced locations under `build/mc-src/java/`:

- `net/minecraft/client/Minecraft.java` — main game class
- `net/minecraft/client/renderer/` — rendering pipeline
- `net/minecraft/resources/` — resource location / pack system
- `net/minecraft/world/` — world/level logic

## Build

```bash
./gradlew :mc1201:fabric:compileJava
./gradlew :mc1201:fabric:shadowJar
./gradlew :mc1201:fabric:serverSmoke -PcgAcceptEula   # boots a dedicated server, asserts, stops
```

## Plugin

Uses `fabric-loom 1.16.2`. Version pins live in `build.gradle.kts` under the `mc1201.*` keys.
