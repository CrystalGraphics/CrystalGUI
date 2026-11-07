# runtime/mc/modern/common — Agent Knowledge Base

The modern-era code that is no loader's: the `common` branch of the Stonecutter tree, built once per
Minecraft version (`:runtime:mc:modern:common:<version>`). Every loader node compiles against the common
node of its own version through the `commonOutput` configuration, and `cg-modern-common.gradle.kts`
picks the toolchain from the node's pins (`CrystalGraphics/docs/BUILD.md` § *Nodes and toolchains*).

**No loader type appears here.** A Forge, NeoForge or Fabric import in this module is a mistake — it
compiles against one loader and is used by three.

## Package guide

| Package | What it contains |
|---|---|
| `com.crystalgui.mc.modern.platform` | `LifecycleCrystalGUI` — **the one class a loader talks to**: bootstrap, client init, the server and client ticks, player join/leave, overlay paint. Plus `CrystalGUI`, which holds the mod id and name |
| `com.crystalgui.mc.modern.client` | The host: `CgUiScreen` (the viewport a desktop attaches to), `HostModern` (`HostServices`) and `CgUiHud`. Input and keys are CrystalGraphics' (`CgInputStage.SCREEN`, `CgKeyBindings`). The client state Minecraft moved (`CgMinecraftClient`), the GL hand-over around a paint (`CgHostPaint`) and host key numbers (`HostKeys`) are CrystalGraphics' modern `platform` package, which this module uses |
| `com.crystalgui.mc.modern.net` | `Connections`, `Peer` |
| `com.crystalgui.mc.modern.probe` | Every adapter over `core`'s probes: `CgUiAutoTest`, `ClientProbe`, `ConnectionProbeModern`. **`serverSmoke` enumerates this package as client-only** (`ServerSmoke.Footprint`), so a probe added here is checked without anyone listing it |

## Key design points

- **`LifecycleCrystalGUI` is the seam.** A loader subscribes its own events and forwards; every body on the
  far side is one call into this module. Anything a loader does beyond registering is in the wrong
  place.
- **The transport is the exception.** Three loaders mean three networking APIs, so each builds its own
  `CgNetworkChannel` and passes it to `LifecycleCrystalGUI.bootstrap`.
- **The pinned windows paint on CrystalGraphics' `HUD` and `SCREEN` stages**, which its hosts fire once a frame
  (at the frame end from 26.1): `CgUiHud.attachStages` from `bootstrapClient`. No loader here hooks the HUD or a
  screen's render.
- **26.1+ paints our own screen's desktop at the frame end.** 26.1 extracts the GUI before it renders the
  level, so a paint from `extractRenderState` lands under the world; `CgUiScreen` hands it to
  `CgUiLifecycle.atFrameEnd`, which runs it from CrystalGraphics' `onFrame`, after the GUI.
- **No GL in constructors or static initialisers.** GL work waits for the first paint.
- **Mixin AP comes from the toolchain.** A second `annotationProcessor` for Mixin produces duplicate-AP
  SRG mapping errors.
