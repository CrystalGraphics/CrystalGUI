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
CrystalGraphics' `FabricBootstrap` checks for either and refuses to start without one.

## No class of CrystalGUI's

The generated `fabric.mod.json` lists the mod with empty entrypoint lists, as the language mod's does: Fabric lists
a mod from its descriptor alone, and CrystalGraphics reads its lifecycle listeners from there
(`runtime/mc/CLAUDE.md`). A node's `main` holds nothing.

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
