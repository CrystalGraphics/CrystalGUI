# runtime/mc/modern/fabric — Agent Knowledge Base

## Target Versions

MC 1.15–1.21.11 / Fabric, a node per `versions/<version>`; 1.16 and 1.16.1 are refused, their only Fabric
API builds lacking `lifecycle-events-v1` or `networking-api-v1`. Below 1.16 Fabric API has no screen
events, so a pinned window draws no overlay over another mod's screen there.

## The loader is registration only

**Two entry points, and both are needed.** `fabric.mod.json` names them separately: `main` runs on
both sides, `client` only on a client, and they are different interfaces.

`CrystalGUIFabricCommon` is the `main` one and carries the `Network` transport and the `Events`
inner class, because a dedicated server needs the channel. `CrystalGUIFabric` is the `client` one
and does nothing but call `Events.registerClient()` — the half that touches client-only Fabric APIs a
server must never load.

The engine's own render, reload and shutdown hooks are **not** here: CrystalGraphics ships as its own
mod and owns them. Everything this loader forwards to lives in the common branch's `LifecycleCrystalGUI`.

## Input is GLFW's callbacks, chained

Fabric API has neither a character event nor any non-screen input event, so typing could not reach a
pinned window and HUD mode heard nothing. `Events.Input` installs four GLFW callbacks at
`CLIENT_STARTED`, each capturing the previous one by setting `null` first -- GLFW hands the old
callback to the SETTER and offers no getter. **Not forwarding is the cancellation**: what the overlay
consumes, Minecraft never sees. One path covers the HUD and a screen alike, so the `ScreenMouseEvents`
/ `ScreenKeyboardEvents` handlers were retired.

> **Characters go on `glfwSetCharModsCallback`, NOT `glfwSetCharCallback`.**
> `InputConstants.setupKeyboardCallbacks` installs Minecraft's on the mods variant, and GLFW fires
> **both** for one keystroke -- so hooking the plain one puts you beside Minecraft's handler instead of
> in front of it. Declining to forward then suppresses nothing, and the character lands in chat and in
> the focused editor at the same time. Key, mouse-button and scroll are the slots Minecraft uses, which
> is why only typing doubles and everything else looks correct.

## Minecraft Source Location

Decompiled, Parchment-mapped sources are extracted into two subdirectories:

| Path | Contents |
|---|---|
| `versions/1.20.1/build/mc-src/java/` | MC 1.20.1 Java sources, decompiled by Loom via Vineflower, Parchment-mapped |
| `versions/1.20.1/build/mc-src/resources/` | MC client assets (assets/, data/, *.json, *.mcmeta) |

Gitignored, not committed. Generate them with:

```bash
./gradlew :runtime:mc:modern:fabric:1.20.1:extractMcSources
# or all three loader modules at once:
./gradlew extractAllMcSources
```

Expect several minutes on the first run.

Commonly referenced locations under `versions/1.20.1/build/mc-src/java/`:

- `net/minecraft/client/Minecraft.java` — main game class
- `net/minecraft/client/renderer/` — rendering pipeline
- `net/minecraft/resources/` — resource location / pack system
- `net/minecraft/world/` — world/level logic

## Build

```bash
./gradlew :runtime:mc:modern:fabric:1.20.1:compileJava
./gradlew :runtime:mc:modern:fabric:1.20.1:shadowJar
./gradlew :runtime:mc:modern:fabric:1.20.1:serverSmoke -PcgAcceptEula   # boots a dedicated server, asserts, stops
```

## Plugin

Uses `fabric-loom 1.16.2`. Version pins are per node, in `versions/<version>/gradle.properties`
(`mc.version`, `fabric.loader`, `fabric.api`, `parchment.*`).
