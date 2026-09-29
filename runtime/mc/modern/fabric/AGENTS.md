# runtime/mc/modern/fabric — Agent Knowledge Base

## Target Versions

MC 1.14.4–26.2 / Fabric, a node per `versions/<version>` (26.1.2 also claims 26.1 and 26.1.1); 1.14-1.14.3, 1.16 and 1.16.1 are refused, their
only Fabric API builds lacking `lifecycle-events-v1` or `networking-api-v1`. Fabric API for 1.14 has no
HUD callback either, so there the HUD is a node mixin on `Gui.render` (`mixin/HudHook`, gated by
`CrystalGuiFabricMixins`). Below 1.16 Fabric API has no screen
events, so a pinned window draws no overlay over another mod's screen there. Fabric API for 26.1 renamed
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

## Toolchain, sources and checks

Every node is built by Loom; pins in the catalog (`CrystalGraphics/docs/BUILD.md` § *Nodes and
toolchains*).

```bash
./gradlew :runtime:mc:modern:fabric:<version>:extractMcSources              # Vineflower, into versions/<version>/build/mc-src/
./gradlew :runtime:mc:modern:fabric:<version>:serverSmoke -PcgAcceptEula
./gradlew :runtime:mc:modern:fabric:<version>:connectionProbe -PcgNoLanguage   # the dev client cannot load the language mod
```
