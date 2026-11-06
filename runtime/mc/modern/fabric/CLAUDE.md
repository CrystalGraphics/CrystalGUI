# runtime/mc/modern/fabric — Agent Knowledge Base

## Target Versions

MC 1.14.4–26.3 / Fabric, a node per `versions/<version>` (26.1.2 also claims 26.1 and 26.1.1); 1.14-1.14.3, 1.16 and 1.16.1 are refused, their
only Fabric API builds lacking `lifecycle-events-v1` or `networking-api-v1`. The HUD and screen hooks are
CrystalGraphics' (`CgRenderStage.HUD`, `SCREEN`); below 1.16 Fabric API has no screen events, so a pinned window
draws no overlay over another mod's screen there. Fabric API for 26.1 renamed
`KeyBindingHelper` to `KeyMappingHelper`, replaced `HudRenderCallback` with `HudElementRegistry` and
`ScreenEvents.afterRender` with `afterExtract`, and names its payload registries by direction.

**Fabric API is required and is not in `fabric.mod.json`**: its id was `fabric` through 1.17, `fabric-api`
providing `fabric` through 1.21.11, and `fabric-api` alone from 26.1, so no one id holds on every version.
`FabricBootstrap` checks for either and refuses to start without one.

## The loader is registration only

**Two entry points, and both are needed**: `main` runs on both sides, `client` only on a client.
`fabric.mod.json` names one class for both, `FabricBootstrap`, which hands off by the running version —
Fabric constructs every entry point its descriptor names, so naming the variants there would construct
one compiled against a Minecraft that is not running.

`CrystalGUIFabricCommon` is the `main` one and carries the `Network` transport and the `Events`
inner class, because a dedicated server needs the channel. `CrystalGUIFabric` is the `client` one
and does nothing but call `Events.registerClient()` — the half that touches client-only Fabric APIs a
server must never load.

The engine's own render, reload and shutdown hooks are **not** here: CrystalGraphics ships as its own
mod and owns them. Everything this loader forwards to lives in the common branch's `LifecycleCrystalGUI`.

## Input is CrystalGraphics'

Fabric API has neither a character event nor any non-screen input event, so CrystalGraphics' Fabric node chains the
window's callbacks (SDL's event filter from 26.3) and fires `CgInputStage.SCREEN` (`ScreenInputFabric`, and
`CrystalGraphics/docs/INPUT.md`). This loader registers nothing for input: the desktop is a listener on that stage.

## Toolchain, sources and checks

Every node is built by Loom; pins in the catalog (`CrystalGraphics/docs/BUILD.md` § *Nodes and
toolchains*).

```bash
./gradlew :runtime:mc:modern:fabric:<version>:extractMcSources              # Vineflower, into versions/<version>/build/mc-src/
./gradlew :runtime:mc:modern:fabric:<version>:serverSmoke -PcgAcceptEula
./gradlew :runtime:mc:modern:fabric:<version>:connectionProbe -PcgNoLanguage   # the dev client cannot load the language mod
```
