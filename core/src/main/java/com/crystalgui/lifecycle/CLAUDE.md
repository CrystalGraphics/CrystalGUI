# `lifecycle` — the GL context

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../AGENTS.md), which keeps the rules every session needs.

## GL context lifecycle — `lifecycle/`

CrystalGUI owns GL resources and caches of GL-derived objects, but is not part of CrystalGraphics and
so cannot be enumerated in `CgGraphicsLifecycle`'s own teardown. The seam is
`CgLifecycleListener` (CrystalGraphics, `lifecycle/`) — `onContextInit(w,h)` / `onFrame(frame)` /
`onContextDestroy()`, all default no-ops, registered via `CgEngineLifecycle.addListener(...)`.

**CrystalGUI registers exactly one**: `CgUiLifecycle`.

| Moment | What CrystalGUI does |
|---|---|
| `onPreInit` | `HostSession.install(CrystalEditor.KIND)`, the desktop on every Minecraft client, then its keys (`DesktopKeys`); no loader names either. Then `CgUiStart.ahead()`: style properties, widget kinds, the user-agent sheet, the built-in themes, the desktop record's codecs and the frame profiler's settings on a worker, the load task `crystalgui:start` — half a second to two the first UI paid on the render thread. The harness also starts it through `HarnessExtension.start()` and holds a scene's `init` behind it |
| `onContextInit` | The disposal GL gate |
| `onLoad` | Three load tasks, tracked and named on the loading screen. `crystalgui:paint` (`UiPaintWarmup`), held until `CgUiStart.done()`: the default font, then the shipped icons and the sheets' font families at every size they use, each on a worker (their glyphs generate behind the first frames). `crystalgui:desktop`: `HostSession.preload()` builds the desktop and runs its first cascade and layout, so the first open is a resume. `crystalgui:desktop-warm`, after the engine's programs: `HostSession.rehearseStep()` rehearses the desktop on CrystalGraphics' `SCREEN` stage (`CgRehearsal`, `DesktopRehearsal`) until the JIT settles, a few passes a frame within `CgWarmBudget`. The UI's pipelines are the precache's (`CgUiPaintContext.precache`) |
| `onFrame` | Drains queued GL disposals. Per-frame UI work is per-`UIDocument` (`frame`, and the `Animation` hooks it ticks), painted at CrystalGraphics' GUI stages, never here |
| `onContextDestroy` | `CgUiPaintContext.releaseAll()`, and nothing else |

> **`onContextInit` reaches late registrants.** A process the discovery missed registers at its first paint, after
> the context started; `addListener` delivers every moment it missed, so each fires **exactly once per context,
> regardless of registration time**.

> **`destroyContext()` fires only at game shutdown.** There is no destroy-then-init cycle in a running
> process, so there is no "next context" to protect, and **nothing needs invalidating merely because
> CrystalGraphics is about to free it** — font families, cached stylesheets, sprite packs and shared
> materials all die with the process. Do not add cache-clearing or material-invalidation to
> `onContextDestroy`; it is ceremony, not correctness.

What `onContextDestroy` legitimately does is release the one thing nobody else frees: the textures each
`CgUiPaintContext` requested from the graph (its frame target, surfaces, backdrop, icon rasters), which no registry
holds.

**`onContextDestroy` fires before CrystalGraphics frees anything** — the only window in which a listener can
release its own textures/renderers while the context is whole. Listeners dispatch in *reverse*
registration order.

> **Registration is CrystalGraphics' discovery**: `core/src/main/resources/META-INF/crystalgraphics/listeners`
> declares `CgUiLifecycle` a `client` listener, so the engine registers it on every client before its graphics start
> and no host names it; a dedicated server never loads it. `CgUiPaintContext`'s static initializer still calls the
> idempotent `register()`, for a process the discovery did not reach.

---

Also loaded with this folder:

@../../../../../../../CrystalGraphics/AGENTS.md
