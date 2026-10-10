# `lifecycle` — the GL context

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../AGENTS.md), which keeps the rules every session needs.

## GL context lifecycle — `lifecycle/`

CrystalGUI owns GL resources and caches of GL-derived objects, but is not part of CrystalGraphics and
so cannot be enumerated in `CgGraphicsLifecycle`'s own teardown. The seam is
`CgLifecycleListener` (CrystalGraphics, `gl/lifecycle/`) — `onInit(w,h)` / `onFrame(frame)` /
`onDestroy()`, all default no-ops, registered via `CgGraphicsLifecycle.addListener(...)`.

**CrystalGUI registers exactly one**: `CgUiLifecycle`.

| Moment | What CrystalGUI does |
|---|---|
| `onInit` | The disposal GL gate, and `CgUiPaintContext.warm`. The warm needs a host section, and a late registrant arrives from wherever it registered (26.x's GUI extraction is outside one), so outside a section it waits for the next `onFrame` |
| `onFrame` | Drains queued GL disposals, runs a deferred warm, then the `atFrameEnd` paints. Per-frame UI work is per-`UIDocument` (`frame`, and the `Animation` hooks it ticks), not global |
| `onDestroy` | `UiGpu.destroy()`, and nothing else |

> **`onInit` reaches late registrants.** CrystalGUI registers from a class initializer on the *first
> paint*, which is always after `CgGraphicsLifecycle.initContext()` has run — so a fire-only-during-init
> design would mean `onInit` never fires for CrystalGUI at all. `addListener` therefore delivers
> `onInit` immediately when a context is already live. The guarantee is **exactly once per context,
> regardless of registration time**. Without it the miss would be invisible while the hook is empty
> and appear as silently-skipped setup the moment it isn't.

> **`destroyContext()` fires only at game shutdown.** There is no destroy-then-init cycle in a running
> process, so there is no "next context" to protect, and **nothing needs invalidating merely because
> CrystalGraphics is about to free it** — font families, cached stylesheets, sprite packs and shared
> materials all die with the process. Do not add cache-clearing or material-invalidation to
> `onDestroy`; it is ceremony, not correctness.

What `onDestroy` legitimately does is release the one thing nobody else frees: `CgUiPaintContext`'s
layer FBO pool is built with `CgFrameBuffer.createOwned`, which bypasses `CgFrameBufferRegistry`, so
`deleteAll()` never reaches it.

**`onDestroy` fires before CrystalGraphics frees anything** — the only window in which a listener can
release its own FBOs/renderers while the context is whole. Listeners dispatch in *reverse*
registration order.

> **Registration is automatic** — a `static` initializer in `CgUiPaintContext` calls
> `CgUiLifecycle.register()`, so CrystalGUI wires itself as soon as that class comes into play. Class
> init runs once per classloader, so registration cannot repeat across a destroy/recreate cycle. A
> process that never paints never touches the class, which keeps a dedicated server free of
> CrystalGraphics. `register()` is idempotent and public for explicit use.

---

Also loaded with this folder:

@../../../../../../../CrystalGraphics/AGENTS.md
