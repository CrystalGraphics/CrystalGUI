# `core.cursor` — deciding a cursor, and presenting one

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../../AGENTS.md), which keeps the rules every session needs.

> **The cursor is split, and the split is the point.** *Deciding* one is CrystalGUI's — the `cursor`
> property, its inheritance, the `auto` rule, `Input`'s gesture override, and `CursorBitmaps.artFor`,
> the single keyword→picture table. *Presenting* one is a toolkit's, and lives in CrystalGraphics'
> tier-1 `runtime/lwjgl/2`/`runtime/lwjgl/3` modules as `CgCursorService`, which takes a
> `CgCursorService.Image` — a name, some ARGB pixels, a hotspot — and has never heard of a keyword.
>
> **`CursorService` is a class with one static method, and nothing registers anything.** It resolves the
> keyword and hands the picture over; a host names no cursor service, no adapter, and neither LWJGL
> module. It was an interface plus a `CgService` slot from when each loader wrote its own adapter, which
> left a one-method interface with one implementation nobody filled.
>
> **A new cursor still touches nothing but the table.** An adapter caches natives by the image's *name*
> and enumerates no keywords of its own. That table used to be copied into each adapter and the copies
> drifted — `slide-arrow` reached the two LWJGL2 ones and not GLFW, `crosshair` the reverse. The only
> table an adapter owns is the set of shapes *its own toolkit* ships natively, keyed on the same name.
