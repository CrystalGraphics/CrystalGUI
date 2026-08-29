# CrystalGUI engine core — audit, and the three-tree design it argues for

**Status: research, 2026-08-28. Nothing here is implemented.** This is the engine-core audit that
`plan_ui_network_audit.md` §8.4 said must precede committing to its "Tier 3" — the aggressive
rewrite. It covers `ui/` (the node class, the window engine, input, events, the top layer),
`style/` (cascade, transitions, the Taffy seam), `render/` (paint context), and the two things built
directly on them whose structure the core dictates: the desktop compositor and the composite widget
model. It does **not** re-audit networking (that document), the text/editor/language stacks (untouched
by anything here), or CrystalGraphics.

Method as before: read the seams, measure, classify, then design against production engines. Where a
claim in §8 of the previous audit turned out to be over-stated, this document corrects it in §0.

---

## 0. Correcting the record first

§8.1 said *"292 invariant rows is what a design produces when one structure is asked to do several
jobs."* Read one at a time, the rows classify like this:

| Domain | Rows | Nature |
|---|---|---|
| Language / build / script sandbox | ~90 | Facts about ECJ, Rhino, class loaders, Gradle, FML. **Untouched by any UI rewrite** |
| Chrome & workbench product behaviour (menus, search, trees, lists, commands) | ~45 | Product decisions — "filtering reveals; highlighting does not". An architecture does not remove these; they are the specification |
| Desktop compositor | ~65 | About half are **structural** (hide-as-detach, promotion, owner/owned, animation drivers, the internal flag on resume); the rest are window-manager UX decisions (snap zones, restore-on-drag, GNOME's timings) that belong in any compositor |
| Editor / text / diagnostics | ~28 | Domain rules; untouched |
| Networking | ~13 | The previous audit |
| **Engine core** — tree, style, layout, input, paint, threads | **~55** | The subject of this document |

So the honest number is **~55 engine-core rows plus ~30 structural compositor rows ≈ 85**, not 292.
Eighty-five is still what §8.2 said it was — the residue of a structure that resolves job conflicts by
rule — and the eight causes named there hold. But two hundred of those rows are *good documentation
of decisions*, and a rewrite that promised to make them go away would be lying.

A second correction, in the other direction: the engine core is **smaller than it looks**. The code
that a Tier-3 rewrite *replaces* is ~26,700 lines (`ui/` root + input + events + tree: 6,305;
`style/`: 11,435; `render/`: 8,971). The code it *ports* — widgets, desktop, workbench, dock, chrome,
editor widgets — is ~76,700 lines. The rewrite is a quarter of the surface; the port is three
quarters. That ratio decides the plan in §12.

---

## 1. Measurements

| | |
|---|---|
| `UIElement` | 3,308 lines; 22 concern sections; section headers no longer match their contents (`getScrollWidth` under *Top layer*, `getTransform` under *Scrolling*, `setHitTest` under *Focus*) — organic growth, not design |
| Fields on a node | Taffy node id · parent · id · focus policy · hitTest · inert · popover invoker · enabled/pressed/focused/focus-visible/hovered · internal flag · session-persistent · **network id · reported events · tree observer** · scroll offsets · scroll-exempt · user-sized axes · resize mode · **keymap · settings** · target scroll · font-relative flag · cached x/y — plus children, style, events, runtime cache |
| Composite widgets (`acceptsPublicChildren() == false`) | 54 |
| `addInternalChild` call sites | 208 |
| `MeasureFunc` overrides | **0** — only the base returns `null`. No widget uses the layout engine's intrinsic-size protocol |
| Files writing through the cascade at `IMPORTANT` | 46 — `opacity` ×15, `left` ×6, `z-index` ×4, `flex-grow` ×3, `display` ×3, `min-width` ×2, `width`, `width%`, `height`, `top`, `padding-top`, `transform`, `overlay`, `font-size`, `background-color` |
| Places that special-case a promoted element | `taffyChildIndex`, `paintChildren` (×2), `localToWorld`, `originOfContainingBlockX/Y`, `resizeContainingBlock`, `TopLayer.hitTest`, `UIWindow.getHoveredElement`, `TopLayer` Taffy reparent — plus `position: absolute` written at `IMPORTANT` by the promotion itself |
| Motion mechanisms | 5: `TransitionEngine` (cascade, `ANIMATION` origin), `WindowAnimation` (transform+opacity), `WindowGeometryAnimation` (layout), smooth scroll, and 18 `UIFrameTicker` implementors |
| Coordinate systems an element has | 2: the layout chain (`getWindowX/Y`) and the transform chain (`localToWorld`) — rows 169 and 182 each record a popup placed with the wrong one |
| Layout settling | `UIWindow.calculateLayout` loops `while (isLayoutDirty())` up to `MAX_LAYOUT_PASSES`, because `onLayoutChanged` callbacks write `IMPORTANT` candidates that re-dirty the tree — layout is a fixed-point iteration over the cascade |
| Thread guard | `UiThread.markCurrent()`/`isCurrent()` exist; asserted **nowhere** |
| Tests | 162 widget/ui tests, 176 other unit tests, 87 headless test classes — the net for a port |

---

## 2. One tree, four jobs — proven

The claim was that the DOM tree is also the layout tree, the paint order and the hit-test order, and
that the engine special-cases every place they must disagree. The evidence:

- **Layout.** Every node owns a Taffy node id and its `TaffyStyle`; `UIWindow.registerElement`
  creates the Taffy node on attach and inserts it under the parent's node at `taffyChildIndex()` —
  which **skips promoted siblings**, because a promoted node's Taffy node has been moved under the
  root. The DOM index and the layout index are different numbers the node computes on demand.
- **Paint.** `drawSubtree` is a method on the node; `paintChildren` walks `sortedChildren` (DOM
  children sorted by z-index, equal-z later-inserted-first) and `continue`s on `inTopLayer`; then
  `TopLayer.paint` draws the promoted list after the whole main tree.
- **Hit-testing.** `UIWindow.getHoveredElement` asks `TopLayer.hitTest` first, then walks the DOM;
  `localToWorld`'s calculator has an `element.inTopLayer` branch that seeds from the root transform,
  and `originOfContainingBlockX/Y` has another.
- **Position.** `originOfContainingBlockX` resolves the containing block as *the root if promoted,
  else the DOM parent* — "a promoted element's containing block is the root" is implemented as an
  `if` in the node's coordinate getter, and `resizeContainingBlock()` repeats it.
- **The paint pass writes the hit-test cache.** `drawSubtreeTransformed` calls
  `reconcileWorldMatrix(pose)` unless `ctx.mirroring()`: hit-testing walks a matrix that painting
  stored. That is why `CgUiPaintContext.mirrored` exists ("paint this, but do not learn anything from
  it") and why the thumbnail row in the invariants table was a bug — **hit-testing is only correct
  after a paint**, which is a dependency no other engine has.
- **Two coordinate chains.** `getWindowX/Y` walks layout positions; `localToWorld` walks transforms
  with `uiScale` baked in. They agree only when nothing is transformed and after a paint. Two rows
  record popups placed with the wrong one; `AnchoredPlacement` reads one chain and writes the other.

`TopLayer` is, structurally, a **portal to the root implemented as five special cases** plus an
`IMPORTANT`-origin `position: absolute`. Everything that needed a second host — a window's owned
dialog (`attachOwned`, a *reparent* to avoid promotion), the pinned band, a torn-out fragment
(`Detached`), a thumbnail (`mirrored`) — had to invent its own mechanism because there is no general
"place this box under that host" operation.

**What the references do.** Blink builds a separate `LayoutObject` tree from the flat DOM after style
resolution; `display: none` nodes have a DOM node and **no layout object**; paint walks the layout
tree into display items; hit-testing walks the layout tree, never the DOM
([Blink layout README](https://chromium.googlesource.com/chromium/src/+/master/third_party/blink/renderer/core/layout/README.md),
[BlinkNG](https://developer.chrome.com/docs/chromium/blinkng)). Flutter keeps a widget tree, a
persistent element tree that owns identity and state, and a render-object tree that owns layout,
paint and hit-testing — "constraints go down, sizes go up" in one O(n) pass
([architectural overview](https://docs.flutter.dev/resources/architectural-overview)). WPF's logical
tree owns property inheritance, resources, names and routed events; its visual tree owns rendering,
opacity, transforms, `IsEnabled` propagation and hit-testing
([Trees in WPF](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/trees-in-wpf)). Three
independent engines drew the same line.

---

## 3. The cascade is the engine's only mutable box model

The previous audit called this "the cascade as a geometry channel". The census sharpens it. Of the
46 files writing at `IMPORTANT` origin, the geometry-feedback cases (`UIText` width/height,
`ProgressBar` fill width) are the minority. The majority are the **engine's own decisions**: culling
writes `opacity: 0`; promotion writes `position: absolute`; `Desktop.raise` writes `z-index`;
`AnchoredPlacement` and window placement write `left`/`top`; visibility toggles write `display`;
window animation once wrote `transform`/`opacity` through the cascade until row 27 moved it to a
timeline. There is no other place to put a box property: **the cascade is the only mutable model of
a box the engine has**, so every engine-owned fact about position, stacking, visibility and opacity
has to be dressed up as an author's style candidate at an origin high enough to win.

Consequences that the rows record: a theme cannot beat a widget's size; a `!important` an author
writes collides with the engine's own (`TopLayer.remove` says so: "a caller's own `!important`
position would also be dropped — there is nothing to tell them apart"); a value written from Java
"lands at INLINE and no stylesheet rule could ever move it again" (stated in four widgets' comments
and the CSS file); the window animation had four silent failure modes running through the cascade
(row 27); `moveInlineAsDefault()` exists to *re-origin* candidates after the fact.

**Intrinsic sizing.** No widget uses `MeasureFunc`. `UIText` documents why: Taffy 1.1.4's Java port
passes `NaN` for an item's resolved width in the flex-wrap cross-size path
(`FlexboxComputer.java:1469`), so a measured leaf wraps at the wrong width under any wrapping
ancestor. The workaround — re-layout after layout, pushing the measured size back as an `IMPORTANT`
candidate, and loop until it settles — became the architecture: `calculateLayout` is a fixed-point
iteration with a pass cap, and `FlexShrinkOverflowTest`-class rows are what a settled-but-wrong
iteration looks like. The dependency defect is real and fixable (the port's source is checked in;
the Rust upstream reworked measure functions in 0.4/0.5 — [changelog](https://github.com/DioxusLabs/taffy/blob/main/CHANGELOG.md));
building an engine around it was the error.

**What the references do.** Intrinsic size is a *layout-phase protocol*: Flutter's `performLayout`
with constraints, Blink's `ComputeIntrinsicLogicalWidths`, Compose's measure/place with intrinsic
measurements ([Compose phases](https://developer.android.com/develop/ui/compose/phases)). And
engine-owned box state is **not style**: WPF keeps `RenderTransform`, `Opacity`, `ZIndex` as visual
properties distinct from the styled logical tree; Blink's `ComputedStyle` is an immutable *output* of
the cascade and layout/paint state lives on the `LayoutObject` and its paint properties. Nobody
writes `position: absolute` into the cascade to promote a dialog.

---

## 4. Encapsulation by a flag

54 composites, 208 `addInternalChild` sites, one boolean. The flag's semantics are spread across
the node class and everything that walks it: `markAsInternal` recurses; `removeChild` silently
refuses an internal child and returns a boolean nobody checks; `clearAllChildren` skips them; the
codec skips them; `describedChildren` filters them; focus, hit-testing and selector matching walk
straight through them; theming reaches them by a `__double-underscore__` class convention that
"substitutes for pseudo-elements". Ten rows are direct consequences — including the one that took
three separate incidents (`addInternalChild(container)` marks everything the container already had)
and the one that made the compositor unable to hide windows after a resume.

The workbench's own chrome (`WindowChrome.setContent` *moving* a menu bar into a caption and
restoring its internal-child flag on the way back) is the flag's failure written large: content and
structure are one tree, so moving a part of one into another means moving a node and remembering
what its flag was.

**What the references do.** Shadow DOM: a composite's parts live in a **shadow tree** under a shadow
root; user content is **slotted**; layout and paint run over the **flat tree**; events crossing the
boundary are **retargeted** to the host; `::part()` exposes named parts for theming; the shadow tree
is its own **focus navigation scope** and a host may `delegatesFocus`
([Shadow DOM](https://www.w3.org/TR/2016/WD-shadow-dom-20160610/), [focus](https://blog.whatwg.org/focusing-on-focus)).
WPF's `ControlTemplate` puts a control's visual structure in the visual tree only — the logical tree
never sees it ([WPF trees](https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/trees-in-wpf)).
JavaFX's `Control`/`Skin` split is the same idea in Java: the control is the API and state, the
`SkinBase` builds "a scene graph of nodes to represent the skin"
([SkinBase](https://docs.oracle.com/javase/8/javafx/api/javafx/scene/control/SkinBase.html)).

Every one of the ten internal-flag rows is the absence of that boundary. So are the codec's
"internals are never serialized" rule, the network audit's "internal children are numbered but
never addressed", `WindowChrome`, and at least three of the focus rows ("a focusable container is a
wall" is `delegatesFocus`; "click-focus lands on the frame before dispatch" is retargeting; "a list
restoring focus must never take it from a control inside a row" is a focus scope).

---

## 5. Style engine — mostly right, and where it is not

The cascade core is sound: origins with `ANIMATION` above `IMPORTANT`, two winner maps so
transitions can be retargeted, `replaceOrPutCandidate` no-oping on unchanged values, rules indexed
by id/class/type (`StyleSheet.candidatesFor`), `appliedByElement` kept across a detach in a
`WeakHashMap`, `::highlight()` done as the spec's custom highlight API. Findings:

| # | Finding | Sev | Reference |
|---|---|---|---|
| S1 | **No scoping.** One flat ordered sheet list per window; re-adding appends at highest priority; ids and classes are document-wide (networking Y1/Y2 are this) | D | CSS `@scope` (root + lower boundary, scoping proximity in the cascade — [MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@scope)); shadow-tree style scoping |
| S2 | **The UA sheet opens with `* { font-size: 10 }`**, so `font-size` never effectively inherits (row 104), `em` is usable only on the element itself (row 105), and `FONT_SIZE` carries a listener that forces a second re-match (row 106, `rematch` runs twice when font-relative) | D | Every UA sheet sets `font-size` on `:root`/`html` and lets it inherit |
| S3 | An unknown pseudo-class **poisons the whole sheet** (row 225) | C | CSS: an invalid selector invalidates its rule, never the sheet |
| S4 | `::before`/`::after` refused; internal children are "the substitute" — which is §4 | D | `::part()` + shadow trees |
| S5 | `ElementStyle.candidates` is a **public** map read directly by `InlineStyleCodec` and others | D | encapsulate; expose queries by origin |
| S6 | `getComputed` answers `null` for a property nothing wrote (row 84) — initial values are not candidates | C | computed style always has a value |
| S7 | Rematch re-evaluates every candidate rule for a dirty element with no invalidation sets — fine at UI sizes; the error-stripe row shows what a class churn per frame costs | P | Blink invalidation sets ([style invalidation](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/core/css/style-invalidation.md)); optional |
| S8 | `TaffyBridge.DEFAULT_TAFFY_STYLE` diverges from CSS on five defaults — deliberate, and the source of the 13 flex-trap rows every session re-learns | D | Keep or drop, but decide once; a rewrite is the moment to adopt CSS defaults and pay the port cost |
| S9 | `StyleValue` parse exceptions degrade to `null` silently (by design) — but `putCandidate` then *warns and refuses* a null, so a malformed declaration logs twice and applies nothing | minor | |

---

## 6. Input and focus

`UIInputHandler` is the raw sink, the three-phase dispatcher, hover, focus, capture, drag, keyboard
activation and the keymap resolver, in one 962-line class. Findings:

| # | Finding | Sev | Reference |
|---|---|---|---|
| I1 | **Desktop concerns are hard-coded rungs in the generic key path**: `window.routeKeyToWindowSwitcher`, the keyboard move/size mode, then the close-watcher cascade, then the keymap, then dispatch. Each new modal gesture is a new rung (rows 54, 62) | D | A **mode stack** (drag, switcher, move/size, modal grab are modes) that the handler consults, owned by whoever installs the mode — GNOME's modal grab, X11's grab model |
| I2 | `stopPropagation()` is `stopImmediatePropagation` within a phase (row 202); widgets subscribe first because they subscribe in constructors, so a later subscriber never runs | C | DOM semantics |
| I3 | Click-focus walks up to the first `focusesOnClick()` ancestor — which is the `WindowFrame` — so "focus is already in this window" is true before dispatch (row 45) | C | retargeting + `delegatesFocus` (§4) |
| I4 | Two walker families (`focusable()` vs `tabbable()`), modality enforced at four points, Tab scoped by walking (rows 3, 77, 92, 204, 210) | D | one focus algorithm over **focus navigation scopes** (document, shadow root, dialog) |
| I5 | Hit-testing correct only after a paint (§2) | C | hit-test the render tree |
| I6 | Keymap resolves *after* dispatch on unconsumed events — right — but a widget that consumes a chord it has no use for silently denies it (rows 41, 237); every widget carries a "yield list" | D | browsers: modified chords go to the UA before content unless content calls `preventDefault` — invert the default for chords |
| I7 | Pointer capture, drag threshold, ghost, drop targets by `preventDefault` — sound; the ghost API needed a helper because three rules were invisible in its signature (row 205) | ✔ | |

---

## 7. Lifecycle

| # | Finding | Sev | Reference |
|---|---|---|---|
| L1 | **Hide is detach.** `WindowFrame.hide()` → `layer.removeChild(this)`; `unregisterElement` captures session state, pops four stacks, destroys the Taffy subtree; `show()` reattaches and rebuilds it. Rows 2, 5, 6, 9, 56, 60–61, 76 are the cost: geometry must be captured before the detach, tickers must notice they left, the stylesheet record must survive, the internal flag must not be re-marked, owned windows must be remembered | D | Page Lifecycle **frozen** state: the document stays, its timers stop ([Page Lifecycle API](https://developer.chrome.com/docs/web-platform/page-lifecycle-api)); bfcache. A retained window should be *frozen in place*, its render subtree unmounted, its node tree untouched |
| L2 | `UIFrameTicker` registration is one-way "by design"; the contract that a ticker returns `false` after leaving the tree is enforced by nothing | D | the scheduler owns tickers and drops them on detach/freeze |
| L3 | `registerCommands` runs from the instance initialiser (row 281); `onWindowChanged` is not a moment to build in (rows 36, 48, 78); "build children in the constructor" is a rule three widgets learned separately | D | a `connected`/`disconnected` lifecycle pair with a defined order (custom elements' `connectedCallback`) |
| L4 | `Disposer` is a second ownership tree beside the node tree | D | one lifecycle |

---

## 8. Motion

Five mechanisms, and rows 26–40 are the record of discovering, one silent failure at a time, that a
compositor animation cannot run through the cascade: `transition` is resolved in the same pass as
the property it governs; an `INLINE` cleanup value outranks the next start; `transform-origin` is not
interpolable; completion is only pollable; `IDENTITY` is an empty function list that snaps. The answer
that emerged — `WindowAnimation`: from, to, duration, curve, per-frame tick, completion — is exactly
`CABasicAnimation`/`ValueAnimator`, and it writes at `ANIMATION` origin **because that is the only
place a box property can be written**. §3 again.

Recommendation: one animation service over the render tree (Web Animations' timeline model: a
timeline writes render-node properties, the cascade owns rest states), with transitions as one client
of it, and `UIFrameTicker` as the scheduler's per-frame hook rather than a widget interface.

---

## 9. Threading

`UiThread` is a marker: `markCurrent()` from `advanceFrame`, `isCurrent()` read by `UiBudget`, and no
assertion anywhere a node is mutated. Row 235 is the shape of every failure this permits: a setter
that looks free reaches `invalidateStyleMatch()`, which writes a `HashSet` the frame thread is
iterating, and the exception names `advanceFrame`. Swing checks the EDT, Android throws
`CalledFromWrongThreadException` from `View.checkThread()`, Blink `DCHECK`s the sequence. The fix is
one line per mutation entry point and one exception type; the rewrite is the moment to put it at the
node tree's boundary rather than in 200 setters.

---

## 10. Paint

`CgUiPaintContext` as a singleton with immediate `quad()`/`curve()` submission, layer FBOs for
opacity and masks, and a scissor stack is sound and fast. Two structural notes: the paint pass must
never be skipped by a method (row 236) because scissor balance is procedural; and the hit cache is
written by paint (§2). A display-list model (Blink's display items, Flutter's layer tree) makes both
structural — paint *records*, the engine replays, and geometry never depends on having painted —
but it is the least urgent part of this document and the render context can stay as the backend of
whatever records into it.

---

## 11. Widget identity

`tagName()` is an exact-class lookup in `ElementRegistry` (row 7); a `UiType`'s tag is the lowercased
simple class name (networking N1); `ElementRegistry.bootstrapBuiltins` is a hand-maintained list of
23 that goes stale (its own comment says so). Custom elements require a hyphen precisely to force a
namespace; Minecraft registries are `ResourceLocation`s. A node's name should be a registered,
namespaced string the class declares, and a subclass should inherit its supertype's name unless it
declares its own.

---

## 12. The three-tree design

What follows is the shape, drawn against Blink, Flutter and WPF, sized to this engine.

### 12.1 Node tree — `ui.dom`

A `Node` is **identity, attributes, children, shadow root, events**. Nothing else.

- Fields: parent, children (light DOM), `shadowRoot`, `assignedSlot`, name (registered, namespaced),
  id, classes, attributes (`enabled`, `inert`, `hit-test`, `focus-policy`, arbitrary data keys), the
  event listener groups, and a `Box` reference (nullable — `display: none` has none).
- No geometry, no Taffy id, no world matrix, no scroll offset, no keymap/settings scope (those become
  *attributes* looked up through the tree the way `DataContext` already walks), no network fields
  (the mirror observes from outside — networking audit §4.3).
- **Shadow DOM for composites.** A composite builds its parts into a shadow root; content goes into
  `<slot>`s. `Tab.content()`, `WindowFrame.content()`, `ScrollerView`'s viewport, `Dialog`'s body are
  slots. Theming reaches parts by `::part(name)` — the `__name__` convention becomes the part name,
  which is a mechanical port of every `ua/*.css` selector. Events crossing a shadow boundary are
  retargeted to the host; a shadow root is a focus navigation scope with `delegatesFocus`.
- **Composed tree** (light + shadow, via slots) is what layout, paint and hit-testing read; the
  **light tree** is what authors, the codec and the mirror see. `describedChildren`, `internal`,
  `markAsInternal`, `removeChildInternal`, `addDescribedChild` and the codec's skip all disappear.
- Lifecycle: `connected`/`disconnected`/`frozen`/`thawed`, dispatched in a defined order after the
  tree mutation completes — never during it (rows 36, 48, 78, 281).
- Thread affinity asserted at every mutation entry of the node tree.

### 12.2 Style pass — `style`

The cascade stays: origins, specificity, source order, the two winner maps, transitions at
`ANIMATION`, `::highlight()`. It gains **scopes** (a sheet is installed *for* a subtree — a window,
a shadow root — with `@scope` semantics and proximity in the cascade), drops the universal
`font-size` in favour of `:root` inheritance, invalidates a rule rather than a sheet on a bad
selector, and produces an immutable `ComputedStyle` per node per pass. What it **stops** doing is
carrying engine state: no `IMPORTANT` writes from widgets or the engine. Geometry feedback goes
through the layout protocol; placement, stacking, culling and animation go to the `Box`.

### 12.3 Render tree — `ui.render`

A `Box` is created for every composed-tree node whose computed `display` is not `none`, in composed
order, and owns everything the node used to own that is about *being on screen*:

- the Taffy node and its style, the layout result, `x/y/width/height`, the scroll offset, the
  transform and its origin, opacity, z-index, the paint order of its children, the world matrices —
  computed from the box tree, **never written by paint**;
- **a host**: normally the parent box; for a promoted element the root; for an owned dialog the owner
  window's overlay box; for a torn-out fragment the frame it was put in; for a thumbnail a second
  box drawing the same node's subtree. Promotion, owned attachment, tear-out, previews and pinned
  bands are all *"this box's host is that box"* — one operation, no special cases, no `mirrored`
  flag, no `Detached`;
- **intrinsic sizing** as a layout-protocol method (`measure(constraints)`), implemented by `UIText`
  and anything content-sized, wired to the layout engine's measure function. `calculateLayout` runs
  once, not to a fixed point;
- the paint entry (`paint(ctx)` on the box, calling the node's `paintSelf`/`paintOverlay` hooks) and
  the hit-test entry (`hitTest(x, y)` over the box tree in reverse paint order).

The **layout engine** is a decision of its own: fix the Taffy port's measure path (its source is
checked in; upstream fixed the equivalent), vendor it, or replace it. Recommendation: vendor and
fix — Taffy's flex/grid coverage is real and the port exists; a rewrite is the moment to also drop
the five default divergences (§5 S8) or keep them knowingly.

### 12.4 Input, focus, motion, lifecycle as services over the trees

- **Input** hit-tests the box tree and dispatches on the composed node tree with retargeting; the
  handler holds a **mode stack** (drag, switcher, move/size, modal grab) instead of hard-coded rungs;
  `stopPropagation` gets DOM semantics; modified chords default to the keymap unless a widget claims
  them.
- **Focus**: one algorithm over focus navigation scopes (document, shadow root, dialog, window),
  `delegatesFocus` for composites, roving `tabindex` kept as the one enum, modality as a scope
  property rather than four enforcement points.
- **Motion**: one animation service writing box properties on a timeline; transitions are its
  cascade-facing client.
- **Lifecycle**: retained windows are **frozen**, not detached — the node tree stays, the box
  subtree is dropped, tickers stop, session state needs no capture. Eviction destroys.
- **Mirror** (networking): observes the node tree's light DOM — stable ids, attribute and style
  deltas — exactly as the previous audit designed, now with nothing on the node to bolt onto.

### 12.5 What the compositor and workbench become

`WindowFrame` is a node with a shadow root (caption, controls, resizers, overlay slot) and a content
slot; z-order and raise are box properties; owned dialogs are boxes hosted in the frame's overlay
box; minimise freezes; thumbnails are second boxes; tear-out moves a node between slots. The
`Desktop`'s bands (content < windows < pinned < top layer) are hosts. The workbench's dock and tool
windows keep their managers and placement records — those are product logic — and lose the
reparent-and-remember-the-flag mechanics, because a `ViewContainer` moves between slots as a node.

---

## 13. Port plan and cost

The rewrite replaces ~26,700 lines and ports ~76,700. The order is dictated by what each layer
depends on, and by keeping the old engine runnable until the port is complete.

| Step | Scope | Verifies with |
|---|---|---|
| 1 | `ui.dom` node tree with shadow roots, slots, composed-tree iteration, retargeting, lifecycle callbacks, thread assertion — **no rendering yet** | New unit tests; the codec ported to light-DOM iteration; headless suite |
| 2 | Style pass over the node tree: cascade unchanged, scopes added, `ComputedStyle` output, universal `font-size` gone | `StyleGovernanceTest`, cascade tests, `FontFaceTest` |
| 3 | `ui.render` box tree: creation from composed tree, hosts, Taffy under the box, measure protocol, one-pass layout, paint and hit-test over boxes | Widget tests through a headless `UIWindow`; the harness gallery |
| 4 | Input/focus/motion/lifecycle services over the two trees | The 38 focus rows become the acceptance list |
| 5 | **Leaf widgets** (Button, Checkbox, Switch, Slider, ProgressBar, UIText, TextField) as node + shadow skin | Their 162 widget tests, ported |
| 6 | **Composites** (ScrollerView, SplitView, TabView, Dialog, Popover/Menu/Dropdown, Tooltip, ListView/TreeView/TableView, GraphView, CanvasView, TextEditor's host) | Same |
| 7 | **Desktop**: WindowFrame as shadow + slot, hosts for owned/pinned/top, freeze instead of detach, animation service | `cgui-desktop` scene; the ~30 structural compositor rows as the acceptance list |
| 8 | **Workbench, dock, chrome, editor widgets** — behaviour-preserving port | Their tests; the editor scenes |
| 9 | Networking rewrite (previous audit §4) as the mirror over `ui.dom` | Its own plan |
| 10 | Delete the old `ui/`, `style/` glue, `Detached`, `TopLayer`, `mirrored`, the internal flag | The invariants table shrinks by the rows that no longer describe anything |

Costs stated plainly: months; every widget's constructor is touched (208 internal-child sites become
skin builders); every `ua/*.css` selector with a `__part__` becomes `::part()`; every `IMPORTANT`
write becomes a box call or a measure method; the harness and every test that asserts *structure*
(child lists, internal children) is rewritten, while tests that assert *behaviour* survive.

What it does **not** fix, so nobody expects it to: the ~200 product/domain rows; Taffy's semantics
where they are kept; the text, editor and language stacks; CrystalGraphics' boundary; the 1.7.10
host's own rules.

---

## 14. Verdict

The previous audit's Tier 3 stands, with §0's corrections: the structural residue is ~85 rows, not
292, and the rewrite is a quarter of the UI code with a three-quarter port on top. The eight causes
are confirmed by reading rather than inferred: one tree doing four jobs (§2), the cascade as the
only box model (§3), encapsulation by a flag (§4), unscoped style with a universal `font-size` (§5),
desktop rungs inside generic input and focus by walking (§6), hide-as-detach (§7), five motion
mechanisms (§8), an unasserted thread rule (§9). Each has a production answer and they compose into
one design (§12). If the aggressive tier is taken, §13 is the order; if it is not, Tier 2's three
prerequisites (networking off the node, scoped style, thread assertion) are the parts of §12 that
can be done in place and make the eventual port smaller.

---

## 15. References

- Blink layout README — DOM vs layout tree, `display: none` has no `LayoutObject`:
  https://chromium.googlesource.com/chromium/src/+/master/third_party/blink/renderer/core/layout/README.md
- Blink DOM README — composed/flat tree from shadow trees:
  https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/core/dom/README.md
- RenderingNG / BlinkNG — style and layout-tree construction as separate phases:
  https://developer.chrome.com/docs/chromium/blinkng
- Blink style invalidation: https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/core/css/style-invalidation.md
- Flutter architectural overview — widget/element/render trees, constraints down / sizes up:
  https://docs.flutter.dev/resources/architectural-overview
- Jetpack Compose phases — composition / layout / drawing, intrinsics:
  https://developer.android.com/develop/ui/compose/phases
- WPF logical vs visual tree: https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/trees-in-wpf
- JavaFX `SkinBase`: https://docs.oracle.com/javase/8/javafx/api/javafx/scene/control/SkinBase.html
- Shadow DOM spec (flat tree, slots, retargeting): https://www.w3.org/TR/2016/WD-shadow-dom-20160610/ ·
  focus scopes and `delegatesFocus`: https://blog.whatwg.org/focusing-on-focus ·
  https://github.com/WICG/webcomponents/blob/gh-pages/proposals/ShadowRoot-delegatesFocus-Proposal.md
- CSS `@scope`: https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@scope ·
  CSS scoping and shadow encapsulation: https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Scoping
- Page Lifecycle API (frozen): https://developer.chrome.com/docs/web-platform/page-lifecycle-api
- Taffy changelog (measure-function rework): https://github.com/DioxusLabs/taffy/blob/main/CHANGELOG.md ·
  measure example: https://github.com/DioxusLabs/taffy/blob/main/examples/measure.rs
- Swing single-thread rule: https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html
