# core/render — Draw-List UI Render Architecture

> Root guide: [`AGENTS.md`](../../../../../../AGENTS.md)

## What This Package Is

The **authoritative UI rendering submission model** for CrystalGUI.

It uses a single painter's-order draw list that records commands during DOM traversal and replays them
sequentially through the CrystalGraphics batch backend.

## Architecture Overview

```
UIContainer (frame orchestration)
├── CgUiBatchSlots       → Map<CgVertexFormat, CgBatchRenderer> with stable slot indices
├── CgUiDrawList         → packed int[] command pool + CgUiDrawState[] refs
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
    → for each command: scissor transition → draw-state apply → drawUploadedRange()
    → cleanup + finish all slots
```

## Ownership Model

```
CgUiPaintContext
├── CgUiDrawList (owns packed command pool)
│   ├── int[] cmdPool         — CMD_STRIDE * MAX_COMMANDS packed ints
│   ├── CgUiDrawState[] refs  — parallel draw-state reference array
│   └── hot-path merge cache  — lastDrawState, lastBatchSlot, lastScissor*
├── CgUiBatchSlots
│   ├── Map<CgVertexFormat, CgBatchRenderer> renderersByFormat
│   ├── CgBatchRenderer[] indexedRenderers  — for slot-index replay access
│   └── Map<CgVertexFormat, Integer> slotIndices — format → slot index
└── ScissorStack
    └── CgScissorRect[] pool  — preallocated, mutable, never reallocated
       (CgScissorRect lives in CrystalGraphics api/state/)

CgUiDrawState (command-local resolved state)
├── CgRenderState renderState  — reusable, shared
├── CgTextureBinding textureOverride  — nullable, for dynamic per-command textures
└── float textPxRange  — Float.NaN when unused

CgUiDrawListExecutor (stateless replay)
└── takes CgUiDrawList + CgUiBatchSlots + Matrix4f projection as input
```

## File Map

| File | Role |
|------|------|
| `CgUiDrawList.java` | Packed `int[]` command pool with hot-path merge. **The core data structure.** |
| `CgUiDrawState.java` | Command-local resolved draw state wrapping `CgRenderState` + overrides |
| `CgUiPaintContext.java` | Recording-side paint surface with high-level and low-level APIs |
| `CgUiDrawListExecutor.java` | Stateless sequential replay: upload → commands → cleanup |
| `CgUiBatchSlots.java` | `Map<CgVertexFormat, CgBatchRenderer>` with stable slot indices for packed command pool |
| `ScissorStack.java` | Allocation-free scissor stack, dual-mode (logical / GL-apply) |

## GC / Hot-Path Rules (Non-Negotiable)

These invariants must be preserved in all modifications to this package:

1. **Packed `int[]` command storage** — `CgUiDrawList.cmdPool` is a fixed-capacity array, not an object graph.
2. **No per-command heap allocation** — during recording or replay. Zero GC pressure in the frame hot path.
3. **Pooled `CgScissorRect` objects** — `ScissorStack` uses preallocated `CgScissorRect[]`. Never `new CgScissorRect()` in hot path.
4. **Merge detection: reference identity for draw state, field equality for scissor** — `drawState == lastDrawState` and `scissorX == lastScissorX && ...`.
5. **Text draw states are cached** — never created per glyph or per frame. Built per `(shader, atlas page, pxRange)`.
6. **Recording and replay are strictly non-overlapping** — `beginRecord()` → all recording → `endRecord()` → `execute()`.
7. **Staging cannot grow after `uploadPendingVertices()`** — enforced by `CgBatchRenderer`.

## Recording / Replay Boundary

### Recording phase (allowed)
- Select draw state via `setDrawState()`
- Reserve quads, write vertices
- Push/pop scissor logically
- Emit text through `CgTextEmissionTarget`

### Recording phase (forbidden)
- GL state mutation
- Upload/replay calls
- Staging growth after upload begins

### Replay phase (allowed)
- Upload per slot once
- Exact scissor transitions
- Draw-state apply/clear
- `drawUploadedRange()` calls

### Replay phase (forbidden)
- Adding new commands
- Adding vertices
- Growing staging

## Key Design Decisions

- **Merge uses `drawState == lastDrawState` (reference identity)** — not `equals()`. All `CgUiDrawState`
  instances in hot paths must be prebuilt and cached. This keeps merge as cheap as possible.

- **Scissor merge uses field equality** — because `ScissorStack` reuses pooled rects, reference equality
  would be incorrect across mutations.

- **One global draw list per `UIContainer`** — painter's order is maintained by DOM traversal order.
  The renderer makes no independent z-order decisions.

- **Text emission target** — `CgUiPaintContext.textEmissionTarget()` returns a `CgTextEmissionTarget`
  adapter (`DrawListEmissionTarget`) that bridges CrystalGraphics' text renderer to draw-list recording.
  One element's text emission is contiguous in the draw list.

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

- Do NOT construct `CgUiDrawState` in element `draw()` hot paths. Use cached prebuilt instances.
- Do NOT use `equals()` for draw-state merge in `CgUiDrawList`. Use `==`.
- Do NOT call `CgBatchRenderer.vertex()` or `CgBatchRenderer.flush()` during replay.
- Do NOT issue GL calls during the recording phase.
- Do NOT interleave other draw-list commands into a single text emission call.
- Do NOT replace the packed `int[]` command pool with object-per-command storage.
- Do NOT assume scissor reference equality. Always use field comparison for merge.

## Future Optimization Lanes (Not Yet Implemented)

- Multi-texture batching
- Primitive uber-shader
- Splitter/channels for complex widgets
- Cached retained surfaces for expensive static subtrees
- Stencil-based advanced clipping
- Delta-apply draw state optimization (currently full apply/clear per state transition)
