# `widget` — the widgets

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../AGENTS.md), which keeps the rules every session needs.

## Widgets

> **Full reference: `docs/CGUI_WIDGETS.md`** — per-widget API, `::part()` names,
> pseudo-classes, and covering harness scene. Read it before writing a new widget.

| Widget | Tag | Harness scene |
|---|---|---|
| `Button` | `button` | `cgui-gallery` (Button page) |
| `Checkbox` | `checkbox` | `cgui-gallery` (Checkbox page) |
| `CheckboxGroup` | — (not a `UINode`) | `cgui-gallery` (Checkbox page) |
| `Switch` | `switch` | `cgui-gallery` (Switch page) |
| `Slider` | `slider` | `cgui-gallery` (Slider page) |
| `TextField` | `textfield` | `cgui-gallery` (TextField page) |
| `UIText` | `text` | `cgui-text`, `cgui-text-stress` |
| `EmptyState` | `emptystate` | `cgui-desktop` — any vacant panel |
| `FrameStatsOverlay` | `framestats` | `cgui-desktop` — F7 shows, F8 expands. The frame readout over `core.trace.FrameStats`: rate, spread, misses, a coloured sparkline of the window, and the SLOWEST frame's phase breakdown. `DesktopCommands` binds F7/F8 on every surface with a desktop, so the editor and a Minecraft screen get it too; `FrameStatsOverlay.toggleOn(document)` is the one call. The plate is `hit-transparent`, never `hit-test: false`: only the sparkline takes a press, and only once an application has registered `onOpenFrame` -- the Frame Profiler does, and a press opens the frame the bar was DRAWN for |
| `FrameStripTrack` | `framestrip` | `cgui-timeline` — one bar per frame over the whole ring. Buckets to the WORST frame in a column, never the mean, because averaging is what erases the spike the row exists to show. A violet tick under a bar marks a frame a garbage collection ran in — counted, since a young pause under 1 ms adds nothing to `gcMillis`. The wheel zooms the FRAME axis, Shift+wheel or a middle-button drag pans, a LEFT drag is a range; `onViewChanged`/`showView` keep every row on those columns in step |
| `FrameScrollbar` | `framescrollbar` | `cgui-desktop` scripted run — the strip's scrollbar: drag the thumb to scrub, drag either end to zoom (both grab even at full width), press the track to jump |
| `CounterTrack` | `countertrack` | `cgui-timeline` — one counter across the ring, on the strip's own columns. `CounterTrack.ABSENT` is a gap and not a zero |
| `SpanTrack` | `spantrack` | `cgui-timeline` — zones stacked by depth, one leaf. A zone's hue is a hash of its NAME, so it keeps its colour across frames and across runs |
| `Tooltip` | `tooltip` | `cgui-gallery` (Tooltip page) |
| `Dialog` | `dialog` | `cgui-gallery` (Dialog page, modal page) |
| `Popover` | `popover` | `cgui-gallery` (menus page) |
| `Menu` | `menu` | `cgui-gallery` (menus page) |
| `MenuItem` | `menuitem` | `cgui-gallery` (menus page) |
| `Dropdown` | `dropdown` | `cgui-gallery` (menus page) |
| `CanvasView` | `canvasview` | `cgui-gallery` (graph page) |
| `GraphView` | `graphview` | `cgui-gallery` (graph page) |
| `GraphNode` | `graphnode` | `cgui-gallery` (graph page) |
| `NodePort` | `nodeport` | `cgui-gallery` (graph page) |
| `Scroller` | `scroller` | `cgui-gallery` (Scroller page) |
| `ScrollerView` | `scrollerview` | `cgui-gallery` (Scroller page) |
| `SplitView` | `splitview` | `cgui-gallery` (SplitView page) |
| `TabView` | `tabview` | `cgui-gallery` (TabView page) |
| `Tab` | `tab` | `cgui-gallery` (TabView page) |
| `Desktop` | `desktop` | `cgui-desktop` — **nobody constructs one**; the document owns it, found with `Desktop.of(document)` |
| `WindowFrame` | `window` | `cgui-desktop` — opened with `desktop.addWindow(frame)` |
| `Taskbar` | `taskbar` | `cgui-desktop` — the `WindowRegistry`, rendered; built by `Desktop` |
| `LauncherButton` | `launcherbutton` | `cgui-desktop` — the start button, leftmost on the taskbar. Built by `Taskbar`; nobody constructs one |
| `Launcher` | `launcher` | `cgui-desktop` — **what CAN run**, beside the strip that shows what IS running: every `ApplicationKind` the desktop has installed, searched by name, id and `keywords()`. It names no application, which is what lets it live in `desktop` — a mod's `ApplicationKinds` service appears in it with no edit here |
| `WindowSwitcher` | — (not registered) | `cgui-desktop` — `Mod+Tab`; built by `Desktop`, nobody constructs one |

### Conventions — all enforced in code

- **A widget that says its structure is fixed refuses public children.** `refusePublicChildren()` in
  the constructor every other one CHAINS THROUGH — on the no-arg one a `new Dialog("title")` never makes
  the declaration at all. It is a promise a widget makes about itself, NOT something derived from
  whether it has a default slot: an unslotted light child is the web's ordinary state and is legal,
  which three tests pin outright. Give a widget a named accessor for its content instead of opening the
  tree.
- **Structure is a SHADOW TREE, and a caller's content needs a SLOT.** `attachShadow()` plus
  `appendStructural`, with each part carrying a `part` name a theme reaches through `::part(name)`.
  A widget that takes content must give its slot a home, or a light child of it is in no composed tree
  at all — no box, no paint, no promotion, and nothing anywhere reporting a problem.

  **A widget may host a shadow tree only if nothing reaches THROUGH its structure**, because `::part()`
  has no spelling for a part under a part, a tag under a part, or a nested widget's part. Measured over
  the shipped sheets: **23 widgets can, 21 cannot, and 220 rules have no `::part()` spelling at all** —
  `colorselector` alone has 51. A subclass cannot un-shadow its parent, so decide the base class first.

  The part names in use are the old `__double-underscore__` classes with the wrapper removed —
  `__mark__` became `mark`, `__thumb__` became `thumb`. The sheets still carry both spellings: a class
  rule for what is still a light child, and a `::part()` twin for what is not.
- **No sizes, no timings, no colours in Java.** Widgets write structure and state; `default.css` gives
  functional geometry, `ore.css` gives appearance. `Switch`'s knob animation is a CSS `transition` on
  `flex-grow`, not a Java tween. **If you are typing a pixel value into a widget, it belongs in
  `default.css`.**
- **Per-frame work is an `Animation` hook OWNED by a node**, not a ticker a widget registers and can
  never unregister. It stops when the node leaves the tree, which is what the old one-way registration
  could not guarantee — a hidden window's ticker carried on invisibly. Anything that reads GEOMETRY
  uses `afterLayout` instead: an ordinary hook runs BEFORE this frame's layout. A hook started on demand
  is held in a field and registered with `everyIfAbsent`/`afterLayoutIfAbsent`, never behind a `ticking` flag.
- **New pseudo-class = override a getter.** See `PseudoClasses` above.

### `UIText` — asked, not told

**It implements `Measurable`.** Given a width, how tall are you: one pass, nothing written back.

That is worth stating because the class it replaced could not be asked, and **about four hundred of its
lines existed only because of that** — each a defect with a real invariant behind it. `selfSizesWidth`
was latched once from whether the box measured zero on the first post-attachment pass, which is a race
against an ancestor's not-yet-converged layout, held for the element's life: it latched `false` on a
graph node's title against a placeholder width and truncated it for good. `forceSelfSizeWidth()` and
`neverSelfSizeWidth()` were the two escape hatches for callers who knew the answer and had no way to
state it. And `invalidateMeasurement()` carried the deadlock it is named for, where withdrawing the
pushed size made the box resolve to zero, and zero-in-zero-out is not a geometry change, so nothing
ever asked again.

Min-content and max-content are questions the engine asks per layout now, so there is no latch to
pre-empt and no loop to make terminate. **Taffy asks for BOTH**, and answering the minimum with one
unbroken line pins a text leaf's minimum at its whole line — `Measurable.Fit` carries the question and
the minimum wraps at 1px.

What replaces the old engine's four static property listeners is one `computedChanged` hook, and it
must watch `font-weight` and `font-style` as well as size and family: synthesis is per SPAN, so a bold
label resolves the same `CgFontFamily` instance a regular one does and the paragraph's own "has the
family changed" check answers no.

`text-overflow: ellipsis` truncates the **string** and re-shapes, never drops glyphs from the shaped run
— shaping is not a per-character mapping, so cutting the glyph array splits clusters. The ellipsis is
`…` when the **primary** font can draw U+2026 and `...` when it cannot — Blink's rule
(`LineTruncator::ComputeEllipsisText` asks `PrimaryFont()` alone). It asked the whole stack until system
fallback made every stack able to draw one, in a face other than the label's: the bundled
`MinecraftRegular.otf` has no U+2026, and its labels end in three of its own periods. `displayedText()`
returns what will actually be painted — the only observable evidence that truncation fired.

It retains a `CgShapedParagraph`, rebuilt only when the text or the resolved `CgFontFamily` instance
actually changes — never on a resize. Reference equality on the family is correct because
`FontFamilyCache.resolve` caches by `(stack, targetPx)`.
