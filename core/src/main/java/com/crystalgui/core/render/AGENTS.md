# core/render — Draw-List UI Render Architecture (V3.2)

> Root guide: [`AGENTS.md`](../../../../../../AGENTS.md)

## What This Package Is

The **authoritative UI rendering submission model** for CrystalGUI.

It uses a single painter's-order draw list that records commands during DOM traversal and replays them
sequentially through the CrystalGraphics batch backend.

## Architecture Overview

```
UIContainer (frame orchestration)
├── CgUiBatchSlots       → Map<CgVertexFormat, CgBatchRenderer> with stable slot indices
├── CgUiDrawList         → packed int[] command pool + CgRenderState[] refs + text side arrays
├── CgUiPaintContext     → paint surface for UI traversal (recording side)
├── CgUiDrawListExecutor → sequential replay (upload → draw → cleanup)
└── ScissorStack         → allocation-free nested clip regions

Recording phase (DOM traversal):
  UIElement.draw(CgUiPaintContext)
    → setDrawState() + vertex() + recordCommand()
    → fillRect() / drawImage() / drawText() / strokeRect()
    → pushScissor() / popScissor()

Replay phase (CgUiDrawListExecutor):
  upload all batch slots once
    → for each command: scissor transition → render-state apply → drawUploadedRange()
    → cleanup + finish all slots
```

## Ownership Model

```
CgUiPaintContext
├── CgUiDrawList (owns packed command pool)
│   ├── int[] cmdPool          — CMD_STRIDE * MAX_COMMANDS packed ints
│   ├── CgRenderState[] refs   — parallel render-state reference array
│   ├── int[] textTextureIds   — parallel text texture ID array (CMD_KIND_TEXT only)
│   ├── float[] textPxRanges   — parallel text pxRange array (CMD_KIND_TEXT only)
│   └── hot-path merge cache   — lastRenderState, lastCmdKind, lastBatchSlot, lastScissor*, lastTextTexture*, lastTextPxRange
├── CgUiBatchSlots
│   ├── Map<CgVertexFormat, CgBatchRenderer> renderersByFormat
│   ├── CgBatchRenderer[] indexedRenderers  — for slot-index replay access
│   └── Map<CgVertexFormat, Integer> slotIndices — format → slot index
└── ScissorStack
    └── CgScissorRect[] pool  — preallocated, mutable, never reallocated
       (CgScissorRect lives in CrystalGraphics api/state/)

CgUiDrawListExecutor (stateless replay)
└── takes CgUiDrawList + CgUiBatchSlots + Matrix4f projection as input
```

## File Map

| File | Role |
|------|------|
| `CgUiDrawList.java` | Packed `int[]` command pool with hot-path merge, typed `CMD_KIND_SOLID`/`CMD_KIND_TEXT` discriminant, and parallel text arrays. **The core data structure.** |
| `CgUiPaintContext.java` | Recording-side paint surface with high-level and low-level APIs. Contains `DrawListTextSink` inner class implementing `CgTextQuadSink`. |
| `CgUiDrawListExecutor.java` | Stateless sequential replay: upload → commands → cleanup. Branches on `cmdKind` for text vs. non-text state transitions. |
| `CgUiBatchSlots.java` | `Map<CgVertexFormat, CgBatchRenderer>` with stable slot indices for packed command pool |
| `ScissorStack.java` | Allocation-free scissor stack, dual-mode (logical / GL-apply) |

## GC / Hot-Path Rules (Non-Negotiable)

These invariants must be preserved in all modifications to this package:

1. **Packed `int[]` command storage** — `CgUiDrawList.cmdPool` is a fixed-capacity array, not an object graph.
2. **No per-command heap allocation** — during recording or replay. Zero GC pressure in the frame hot path.
3. **Pooled `CgScissorRect` objects** — `ScissorStack` uses preallocated `CgScissorRect[]`. Never `new CgScissorRect()` in hot path.
4. **Merge detection: reference identity for render state, field equality for scissor and text fields** — `renderState == lastRenderState` and `scissorX == lastScissorX && ...`. Text commands additionally compare `textTextureId` and `pxRange`.
5. **`CgRenderState` instances are pre-built statics** — BITMAP_LAYER_STATE, MSDF_LAYER_STATE, etc. in `CgTextRenderer`. The draw-list stores references. Text fields are primitives in parallel arrays.
6. **Recording and replay are strictly non-overlapping** — `beginRecord()` → all recording → `endRecord()` → `execute()`.
7. **Staging cannot grow after `uploadPendingVertices()`** — enforced by `CgBatchRenderer`.

## Command Kind Model

Each draw command carries a typed discriminant in `OFF_FLAGS`:

- `CMD_KIND_SOLID = 0` — non-text commands (fillRect, strokeRect, drawImage, etc.). Render state is applied directly with projection.
- `CMD_KIND_TEXT = 1` — text commands. Carry `textTextureId` and `textPxRange` in parallel side arrays. The executor applies ephemeral shader bindings (`u_modelview` identity, optional `u_pxRange`) before `renderState.apply(projection, texId)`.

## Recording / Replay Boundary

### Recording phase (allowed)
- Select draw state via `setDrawState()`
- Reserve quads, write vertices
- Push/pop scissor logically
- Emit text through `CgTextQuadSink` (via `DrawListTextSink` adapter)

### Recording phase (forbidden)
- GL state mutation
- Upload/replay calls
- Staging growth after upload begins

### Replay phase (allowed)
- Upload per slot once
- Exact scissor transitions
- Render-state apply/clear
- `drawUploadedRange()` calls

### Replay phase (forbidden)
- Adding new commands
- Adding vertices
- Growing staging

## Key Design Decisions

- **Merge uses `renderState == lastRenderState` (reference identity)** — not `equals()`. All `CgRenderState`
  instances in hot paths are prebuilt static finals. This keeps merge as cheap as possible.

- **Scissor merge uses field equality** — because `ScissorStack` reuses pooled rects, reference equality
  would be incorrect across mutations.

- **One global draw list per `UIContainer`** — painter's order is maintained by DOM traversal order.
  The renderer makes no independent z-order decisions.

- **Text emission via `CgTextQuadSink`** — `CgUiPaintContext` drawText() methods create a `DrawListTextSink`
  adapter that implements `CgTextQuadSink`. The adapter tracks vertex cursors internally and flushes
  pending vertices as text draw commands on batch transitions. One element's text emission is contiguous
  in the draw list.

- **`CgUiDrawCommand` is conceptual only** — the plan's §3.4 describes a "conceptual struct", but
  the runtime representation is the packed `int[]` pool. No separate command class exists.

- **V1 uses a single batch slot** — `CgUiBatchSlots.single(POS2_UV2_COL4UB, ...)`. Multi-slot
  architecture is preserved but only one slot is active.

- **`CgScissorRect` lives in CrystalGraphics** (`api/state/`), not CrystalGUI. The stack
  and pool ownership remains in CrystalGUI, but the rect type belongs to Cg.

- **`CgUiBatchSlots` uses `Map<CgVertexFormat, CgBatchRenderer>`** as the primary lookup.
  Slot indices are stable integers computed at construction time for compact packed command storage.

## ScissorStack Dual-Mode Usage

### During recording (logical only)
```java
paintContext.pushScissor(x, y, w, h);  // intersect + store, no GL
// ... draw children ...
paintContext.popScissor();              // pop, no GL
```

### During replay (GL apply)
```java
scissorStack.push(sx, sy, sw, sh);
scissorStack.applyCurrentGl();         // GL11.glEnable + glScissor
// ... draw ...
scissorStack.disableGl();              // GL11.glDisable
```

## Common Agent Mistakes to Avoid

- Do NOT construct `CgRenderState` in element `draw()` hot paths. Use cached prebuilt instances.
- Do NOT use `equals()` for render-state merge in `CgUiDrawList`. Use `==`.
- Do NOT call `CgBatchRenderer.vertex()` or `CgBatchRenderer.flush()` during replay.
- Do NOT issue GL calls during the recording phase.
- Do NOT interleave other draw-list commands into a single text emission call.
- Do NOT replace the packed `int[]` command pool with object-per-command storage.
- Do NOT assume scissor reference equality. Always use field comparison for merge.
- Do NOT re-introduce a `CgUiDrawState` wrapper or equivalent under another name. Render state + typed command kind + parallel text arrays is the authoritative model.

## Future Optimization Lanes (Not Yet Implemented)

- Multi-texture batching
- Primitive uber-shader
- Splitter/channels for complex widgets
- Cached retained surfaces for expensive static subtrees
- Stencil-based advanced clipping
- Delta-apply draw state optimization (currently full apply/clear per state transition)
