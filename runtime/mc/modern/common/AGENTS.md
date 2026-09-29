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
| `com.crystalgui.mc.modern.platform` | `LifecycleCrystalGUI` — **the one class a loader talks to**: bootstrap, client init, the server and client ticks, player join/leave, overlay paint, and the mouse/key offers. Plus `CrystalGUI`, which holds the mod id and name |
| `com.crystalgui.mc.modern.client` | The host: `CgUiScreen` (the viewport a desktop attaches to), `HostModern` (`HostServices`), `CgUiInput`, `CgUiHud`, `CgUiHostGl`, `CgUiKeybinds`, and `ClientGame` — the client state Minecraft moved (26.2 put the screen, the overlay and the HUD's hidden flag on `mc.gui`), spelled once |
| `com.crystalgui.mc.modern.net` | `Connections`, `Peer`, `WorkspaceHostModern` — where the served workspace is |
| `com.crystalgui.mc.modern.probe` | Every adapter over `core`'s probes: `CgUiAutoTest`, `ClientProbe`, `ConnectionProbeModern`, `ServerSmokeModern`. **`serverSmoke` enumerates this package as client-only**, the smoke itself excepted — so a probe added here is checked without anyone listing it |
| `com.crystalgui.mc.modern.example` | `MachineExampleModern` and its client half — a key and a tick over `app.machine.MachineExample`; the worked example, not engine code |
| `com.crystalgui.mc.modern.lang` (`src/lang`) | `ScriptServiceModern` — the language jar's half: Minecraft's bytes and names for scripts |

## Key design points

- **`LifecycleCrystalGUI` is the seam.** A loader subscribes its own events and forwards; every body on the
  far side is one call into this module. Anything a loader does beyond registering is in the wrong
  place.
- **The transport is the exception.** Three loaders mean three networking APIs, so each builds its own
  `CgNetworkChannel` and passes it to `LifecycleCrystalGUI.bootstrap`.
- **`RenderGuiOverlayEvent` fires once per vanilla overlay ELEMENT** — hotbar, crosshair, boss bar, chat and
  a dozen more. Painting the compositor from it laid it out and drew it about fifteen times a frame and put
  the game at ten fps. `RenderGuiEvent.Post` fires once; Fabric's `HudRenderCallback` already did.
- **One arm per hook.** A frame with a screen open fires the HUD hook AND the screen hook, so each paints only
  its own presentation (`paintHud` / `paintOverScreen`) — otherwise the compositor is drawn twice.
- **26.1+ paints at the frame end, not from its hooks.** 26.1 extracts the GUI before it renders the
  level, so a paint from `extractRenderState` or a HUD element lands under the world. Each arm there hands
  its paint to `CgUiLifecycle.atFrameEnd`, which runs it from CrystalGraphics' `onFrame`, after the GUI.
- **No GL in constructors or static initialisers.** GL work waits for the first paint.
- **Mixin AP comes from the toolchain.** A second `annotationProcessor` for Mixin produces duplicate-AP
  SRG mapping errors.
