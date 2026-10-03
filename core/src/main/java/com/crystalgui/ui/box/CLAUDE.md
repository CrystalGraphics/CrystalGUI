# `ui/box` — the box tree

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../../AGENTS.md), which keeps the rules every session needs.

## Stack 3: the box tree — `ui/box`

**Laid out ONCE, with no feedback into it.** The old engine looped `while (isLayoutDirty())` and let a
post-layout callback write style that dirtied it again; that is what made `UIText` settle in two or
three passes and what made a placement write land in the same frame. Here a pass that wants to move
something writes on the NEXT layout, which is why an unplaced popup is laid out off-screen rather than
drawn at its containing block's corner.

| Class | Owns |
|---|---|
| `BoxTree` | one `TaffyTree` per document, synced from the COMPOSED tree only on frames the node tree REPORTED a structure change, restyled by `ComputedStyle` identity, computed once, and composed top-down into world matrices |
| `Box` | geometry, hosting, mirrors, the ONE `localToWorld`, and `hitTest` — which inverts exactly that matrix, so a click lands on what will be drawn with no paint having happened |
| `BoxStyle` | `ComputedStyle` → Taffy, and the only place the project's defaults are stated |
| `Measurable` | `Constraints`/`Size`/`Fit` — the engine ASKS a node for a size instead of being told |
| `BoxPainter` | every box drawn in its OWN space, with the pose set from `localToWorld` |

### Three widths, and they are different questions

`width()` is the border box. `clientWidth()` is the padding box — what scrolls. `contentBoxWidth()` is
the content box, where text goes. And `contentWidth()` is the extent of what is INSIDE, which for a
widget with no child nodes is **zero** — a `TextField` that draws its own glyphs has none, so a scissor
taken from `contentWidth()` clips its text away entirely.

### `Box.x()` is PARENT-RELATIVE

The old runtime cache accumulated through every ancestor, so `a.getX() - b.getX()` was a legitimate way
to ask where `a` is relative to `b` for any pair. Here it is only meaningful when the two share a
parent, and it is silently wrong otherwise — wrong by an amount that depends on how deep in the tree
they are. `Box.centreIn(box, space)` and `Box.originIn(box, space)` are the conversion, through
`worldToLocal`, which also carries the intervening transforms and scrolls that a subtraction never did.

### The engine writes nothing into the cascade

`BoxStyle` READS `ComputedStyle`. Where the old engine pushed geometry back at `IMPORTANT` origin — 117
sites of it — the new one either asks (`Measurable`) or writes a compositor OVERRIDE on the box
(`setTransform`, `setOpacity`, `setTransformOrigin`), which the box tree reads and which is withdrawn
with a `null`. An animation slot is the cascade's channel and must be ENDED; an override is not and must
not be mixed with one.
