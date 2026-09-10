# mc1201/common — Agent Knowledge Base

The MC 1.20.x code that is not a loader's. Compiled against MC 1.20.1 + MinecraftForge 47.2.0 via
`legacyForge` in `cg-mc1201-common.gradle.kts`, and consumed by `forge`, `neoforge` and `fabric`
through the `commonOutput` configuration.

**No loader type appears here.** A Forge, NeoForge or Fabric import in this module is a mistake — it
compiles against one loader and is used by three.

```bash
./gradlew :mc1201:common:compileJava
```

## Package Guide

| Package | What it contains |
|---|---|
| `com.crystalgui.mc.platform` | `LifecycleCrystalGUI` — **the one class a loader talks to**: bootstrap, client init, the server and client ticks, player join/leave, overlay paint, and the mouse/key offers. Plus `CrystalGUI`, which holds the mod id and name |
| `com.crystalgui.mc.modern.client` | The host: `CgUiScreen` (the viewport a desktop attaches to), `CgUiInput`, `CgUiHud`, `CgUiHostGl`, `CgUiKeybinds`, `ClientProbe` |
| `com.crystalgui.mc.net` | `Connections`, `Peer`, `WorkspaceHost` (where the served workspace is), and `ServerSmoke` |
| `com.crystalgui.mc.example` | `MachineExample` and its client half — the worked example, not engine code |

## Key Design Points

- **`LifecycleCrystalGUI` is the seam.** A loader subscribes its own events and forwards; every body on the
  far side is one call into this module. Anything a loader does beyond registering is in the wrong
  place — see the loader modules' own notes.
- **The transport is the exception.** Three loaders mean three networking APIs, so each builds its own
  `CgNetworkChannel` and passes it to `LifecycleCrystalGUI.bootstrap`.
- **`RenderGuiOverlayEvent` fires once per vanilla overlay ELEMENT** -- hotbar, crosshair, boss bar, chat and
  a dozen more. Painting the compositor from it laid it out and drew it about fifteen times a frame and put
  the game at ten fps. `RenderGuiEvent.Post` fires once; Fabric's `HudRenderCallback` already did.
- **One arm per hook.** A frame with a screen open fires the HUD hook AND the screen hook, so each paints only
  its own presentation (`paintHud` / `paintOverScreen`) -- otherwise the compositor is drawn twice.
- **No GL in constructors or static initialisers.** GL work waits for the first paint.
- **Mixin AP**: provided by `legacyForge`. Do not add a second `annotationProcessor` for Mixin here —
  it produces duplicate-AP SRG mapping errors.
- **`legacyForge`, not `neoForge`**: NeoForm 1.20.1 was never published, so
  `legacyForge { version = "1.20.1-47.2.0" }` is the only ModDevGradle path.
