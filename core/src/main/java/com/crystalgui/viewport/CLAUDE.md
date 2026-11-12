# `viewport` — one document on screen

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below.

**`CgUiViewport` is how a host runs a document**: it owns the document's thread (the document's one
`UIDocument.Owner`), feeds it input, frames it at a GUI stage and shows what it recorded. A host opens one on a built
document, frames it from a renderer at a GUI stage (`viewport.frame(stage, content)`, which takes the stage's size and
target and `CgFrameClock`'s time), and sends its input and anything else that touches the tree through it. The same
host code runs in every threading.

| Threading | Where the document runs | Flag |
|---|---|---|
| `SEQUENCED` (default) | a sequence that records while the render thread presents the last commit | — |
| `LOCKSTEP` | a sequence, every entry waited for | `-Dcrystalgui.ui.async=false -Dcrystalgui.ui.sequence=true` |
| `INLINE` | the host's thread | `-Dcrystalgui.ui.async=false` |

| File | Owns |
|---|---|
| `CgUiViewport` | the API: `open`, `run`/`ask`/`execute`, input, `frame`, `readout`, the frame counters, `close`; `Content` (what is framed) and `Threading` |
| `DocumentThread` | one strategy per threading, and the document's `Owner`: how each entry and the frame reach the document. **The viewport never branches on the mode** |
| `CommitQueue` | sequenced only: at most one frame in flight and two waiting, a slow recording chaining the next, the hang report |
| `CompositorMotion` | sequenced only: follow-pointer, compositor animations, reveal holding |
| `UiCommit` | one recorded frame, its readouts, and what moves in it |
| `Readout`, `Readouts` | typed keys the host reads of the frame on screen; the viewport fills `TEXT_INPUT_AREA` and `POINTER_CAPTURED` |

- **Every threading commits.** A readout reads the newest commit in each, so a host never asks the tree. A host
  that needs a fact about a frame declares a `Readout` and fills it from its `Content.readouts`.
- **The viewport opens, seals or aborts the paint context's frame**: a `Content` frames and paints, never
  `recordFrame`/`seal`. A frame that throws a `RuntimeException` or `LinkageError` is logged, handed to
  `Content.failed`, and commits nothing to draw.
- **Sequenced, an input event is answered "taken" before it is dispatched**; a key the document leaves comes back
  from `pollUnhandledKey` a frame later, and `Input` answers held keys from the events offered.
- **A capture or script counts `presentedFrames()`**, not frame numbers: sequenced presents nothing until the first
  commit. Four scenes pin `Threading.INLINE` because they measure or photograph exact frames: `cgui-text-stress`,
  `cgui-timeline`, `cgui-text-gamma`, `cgui-visual-layers`.
- **`ui.dom` names no viewport**: the document knows only its `Owner`. A host reaches the document's thread with
  `document.execute(work)` or the viewport's own entries.
