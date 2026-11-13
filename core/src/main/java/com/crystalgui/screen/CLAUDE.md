# `screen` — the client's one desktop

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below.

**`CgUiScreen` is the client's single entry point to the desktop**: static, as `CgUiRenderer` is, its state its own
static fields, which `install` sets up and `dispose` resets. `CgUiClientLifecycle` installs it at pre-init and ticks it at `onFrame`; a host, a key or a
tool opens, closes and reads it. No loader names any of it.

| File | Owns |
|---|---|
| `CgUiScreen` | the API and the state: `install`, `open`/`openApplication`/`close`, `show`/`closeScreen` (a game screen), `Mode`, `tick`, what a host reads, `run`/`execute`, hot keys and keys left; build (the document, its theme, storage, the viewport), the paint at `HUD` and `SCREEN` (one arm a frame, the rehearsal, a probe's replacement), shown and hidden, the close that plays the desktop off, the primary application's launch, dispose |
| `CgUiScreenInput` | the one input router, registered itself at the `SCREEN` input stage: hot keys, then the viewport; over another screen, presses by `HIT_REGIONS` (sequenced) or the live hit test, the keyboard following the last press |
| `ScreenKind`, `ScreenContext` | a game screen as a value (id, builder, background, pause, closing keys, threading) and what its builder is given: the document, the root that fills the window, `close`, `onClosed` |
| `GameScreen` | one shown kind: its own document and viewport, the `CgScreens.Owner` the host holds (pause, HUD, Escape), the error panel a fault becomes, the close request the tick carries out |
| `ServerScreens` | a server window asked for as `Presentation.SCREEN`, shown as a `ScreenKind` whose content is a portal to the connection's tree |
| `DesktopContent` | what the desktop frames and paints in each `Mode`, and its readouts (`ATTACHED`, `PINNED`, `HIT_REGIONS`) |
| `DesktopConnection` | the workspace and window mount on the client's connection, and the language, posted to the document when they move; the connection's own document (`openServer`, framed before the applications') holds every server window's tree, and `DesktopWindowMount` shows it in the shell by portal |
| `ApplicationDocuments` | the documents applications and the connection run in, one viewport each: opened at launch (`AppDocuments`), framed before the shell's on every host frame, ended on the render thread once the application quits; they composite nothing, the shell draws their clients' surfaces and their popups (`PopupLayer`, shown by the shell's `PopupPortal`) |
| `GameState` | the game's answers, read once a host frame on the render thread |
| `HitRegions`, `DesktopRehearsal` | presses answered from a frame; the throwaway desktop the loading screen rehearses |

## Rules

- **A game screen is `Mode.GAME_SCREEN`**: its own document, framed after the applications' and before the desktop's,
  which then draws only what is pinned (the same arm as `OVERLAY`). Input goes to a pinned window under the point first,
  then to the screen; its closing keys act only while no text field has focus. One is up at a time, and the host's
  `shown`/`hidden` bracket its life: the document is built at `show` and dropped at `hidden`.
- **A screen that faults is replaced by an error panel**, never left blank; a panel that faults too closes the screen.

- **The mode is decided once a host frame**, at the tick, and when the desktop's own screen comes or goes: never inside
  a query. It reads the newest frame's readouts, in every threading, so nothing here asks the tree.
- **The tick runs at the end of every host frame**, title screen included: the game read, a finished close, the
  connection posted, a pending launch retried, the next mode.
- **The document is touched on its thread only**: `CgUiScreen.execute`/`run`, through the viewport. What the document
  writes back for the render thread (`left`, `primary`) is volatile.
- **Nothing refuses the player's open but the loading screen.** A run that plays the gauntlet's connection test
  leaves `ui/openWindow` to it at the source (`WindowProtocol.clientHostStandsDown`, which the desktop's window mount
  honours), so the desktop opens as usual meanwhile.
- **Dependencies**: `screen` names `viewport` and `desktop`; neither names `screen`.
