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
| `com.crystalgui.mc.platform` | `Lifecycle1201` — **the one class a loader talks to**: bootstrap, client init, the server and client ticks, player join/leave, overlay paint, and the mouse/key offers. Plus `CrystalGUI1201`, which holds the mod id and name |
| `com.crystalgui.mc.client` | The host: `CgUiScreen1201` (the viewport a desktop attaches to), `CgUiInput1201`, `CgUiHud1201`, `CgUiHostGl1201`, `CgUiKeybinds1201`, `ClientProbe1201` |
| `com.crystalgui.mc.net` | `Connections1201`, `Peer1201`, `WorkspaceHost1201` (where the served workspace is), and `ServerSmoke1201` |
| `com.crystalgui.mc.example` | `MachineExample1201` and its client half — the worked example, not engine code |

## Key Design Points

- **`Lifecycle1201` is the seam.** A loader subscribes its own events and forwards; every body on the
  far side is one call into this module. Anything a loader does beyond registering is in the wrong
  place — see the loader modules' own notes.
- **The transport is the exception.** Three loaders mean three networking APIs, so each builds its own
  `CgNetworkChannel` and passes it to `Lifecycle1201.bootstrap`.
- **No GL in constructors or static initialisers.** GL work waits for the first paint.
- **Mixin AP**: provided by `legacyForge`. Do not add a second `annotationProcessor` for Mixin here —
  it produces duplicate-AP SRG mapping errors.
- **`legacyForge`, not `neoForge`**: NeoForm 1.20.1 was never published, so
  `legacyForge { version = "1.20.1-47.2.0" }` is the only ModDevGradle path.
