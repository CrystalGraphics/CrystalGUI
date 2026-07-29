# CrystalGUI — Working TODO

**This is the live plan.** Ordered, scoped, and maintained as work lands. Superseded the unordered
draft on 2026-07-29.

---

## Standing principles

**v0 target.** What exists today already works for most client-driven GUIs (CNPC-style: server hands
us raw data, we edit it client-side, we send it back). Nothing below is blocking that. Anything that
starts to feel like it's blocking v0 is mis-scoped — say so and re-plan.

**Keep CSS pure.** New style features stay as close to real web CSS as we can get without going
insane. No LDLib-`lss`-shaped inventions. The reason isn't only that no AI can read it — every
non-web concept is one we design, document, and defend forever, whereas the web version is already
solved and already searchable.

> **The one deliberate exception is drag** — see P2. HTML5 drag-and-drop is a genuinely bad API and
> the web itself has moved to pointer-events-based dragging. "Match the web" there means matching
> what the web actually *does*, not what its legacy API says.

**Update docs as we go**, not in a batch at the end. The three `docs/CGUI_*.md` plus root `AGENTS.md`
are the contract; a change that lands without its doc edit isn't done.

**Status legend** — `TODO` · `WIP` · `BLOCKED` · `DONE` · `CUT`

---

## The dependency spine

Five items on the original list (tooltips, moving windows, resizable, editor windows, window manager,
draggable panels) all bottleneck on the **same two missing primitives**. Building those first makes
every consumer dramatically cheaper; building them fifth means retrofitting five features.

```
P1 Top layer  ──┬──> tooltips, dropdowns, context menus, modals
                ├──> drag ghost ──> P2 drag protocol ──┬──> moving windows
                └──> floating editor panels            ├──> resizable elements
                                                       ├──> node graph wiring
                                                       └──> reorderable tabs
```

That ordering is not negotiable-by-taste; it falls out of the code. `UIElement.drawSubtree`
(`UIElement.java:1188`) paints depth-first, and `paintChildren` pushes ancestor scissor while
opacity/mask push FBO layers — so **nothing painted during the tree walk can escape its ancestors.**
And a drag ghost is by definition something that must render above everything, so drag depends on the
top layer, not the reverse.

---

# P0 — Restore the safety net

### 0.1 Fix the two red tests · `DONE` (2026-07-29)

**Outcome.** `:core:check` green — 390 `test` + 94 `headlessTest`, 0 failures, 0 skipped, nothing
`@Ignore`d. Neither was a logic bug; both were expectation-vs-source drift.

Root cause for three of the four: commit `61a604a "Transform"` introduced the `:focus` rule, the
universal `outline-offset`, the comment describing them, and the test asserting them **in one
commit, with no two agreeing**. It was never run.

| Symptom | Truth | Fix |
|---|---|---|
| ring width `1.5px` vs `1px` | comment's measured reasoning + test both said 1px | CSS → `1px` |
| offset `-1px` vs `ZERO` | `* { outline-offset: -1px }` is real and wanted | test → `-1px`; rewrote the CSS comment, which claimed the opposite |
| font `Minecraft.otf` vs `IBMPlexSans` | production self-consistent (property ↔ `CgUiPaintContext.DEFAULT_FONT_ASSET`) | test → IBM Plex |

Offset went the opposite way from width deliberately: dropping the `*` rule would have changed
*every* outline in the engine, and the test's own message ("no **outward** offset") is satisfied by an
inward one — the assertion was over-specified relative to its stated rationale. Both assertions are
now exact values, not loosened.

> ⚠️ **One unverified visual change**: the focus ring is now 1px rather than the 1.5px that had been
> rendering. Worth an eyeball in `cgui-gallery` / `cgui-textfield`. Reverting is a one-char CSS edit.

Both surviving couplings are now commented at the assertion site, since each had silently diverged
once already.

<details>
<summary>Original scoping</summary>

**Why.** The suite sits at 388/390. Two permanently-red tests mean "tests pass" stops being a signal
anyone reads, which is exactly when a real regression slips through. Both predate the paint-context /
`CgQuadRenderer` / `#pragma cg_use` work (verified by stashing), so this is pre-existing debt, not
fallout.

- `TransformCssTest > theUserAgentSheetRingsWhateverHasFocus`
- `StyleSheetTest > fontSizeAndFontFamilyDefaultsAreInheritable`

**Done when.** `./gradlew :core:check` is green with no `@Ignore`. If either test encodes a behaviour
we no longer want, delete the test and say why in the commit — do not weaken the assertion to pass.

</details>

---

# P1 — Top layer (+ tooltips)

**The single highest-fan-out item on the list.** Smallest primitive, five consumers.

### 1.1 A real top layer · `TODO`

**Why.** There is no way to paint above everything. `paintOverlay` runs *inside* `drawSubtree`
(`UIElement.java:1250`), so it is subject to every ancestor's scissor, transform, opacity group and
FBO layer. A tooltip on an element inside an `overflow: hidden` scroller is clipped by the scroller
today. Confirmed: zero tooltip infrastructure exists in the codebase.

**What.** Follow the web's own answer — the **top layer** (`<dialog>`, the Popover API): a separate
painting pass that runs *after* the main tree, in screen space, unaffected by ancestor clip,
transform, or opacity.

**Where.**
- `UIWindow.paintFrame()` — a second `drawSubtree` pass after the root one, pose reset to
  `getRootTransform()`, `ScissorStack` reset.
- `UIWindow.getHoveredElement(x, y)` — **must consult the top layer first**, before the main tree.
  This is the part most likely to be got wrong and it fails as "the tooltip is visible but clicks
  land behind it."
- Promotion mechanism: CSS-shaped, not an ad-hoc Java API.

> **Taffy has no `fixed`.** `TaffyPosition` is exactly `{RELATIVE, ABSOLUTE}` (verified against
> `taffy-1.1.4.jar`), and `LayoutProperties.POSITION` maps 1:1 onto it. So `position: fixed` is not
> "half-built" — it is a keyword the layout engine has no concept of, and CrystalGUI has to own its
> semantics itself: parse it, hand Taffy `ABSOLUTE`, and implement viewport-anchoring plus top-layer
> promotion in the render/hit-test layer. That's the actual shape of this task and it's bigger than
> "add an enum constant." An alternative worth weighing first is a dedicated non-CSS promotion API
> (closer to the Popover API than to `position:`), which keeps the layout property honest about what
> Taffy can actually do.

**Open questions to resolve before coding.**
- Where does a promoted element's Taffy node live? Staying parented keeps layout/cascade/selectors
  working (inheritance is by tree position); painting elsewhere is then purely a render-order
  concern. That's almost certainly the right call — it's also what browsers do — but confirm against
  `registerElement` and `RuntimeCache.localToWorld` before committing.
- Focus and Tab order: a modal in the top layer should trap focus; a tooltip should never take it.
- Anchoring: a tooltip anchored to an element that scrolls or animates must track it. Recompute per
  frame from the anchor's `localToWorld` rather than caching a position.

**Done when.** A harness scene shows a tooltip escaping an `overflow: hidden` scroller, correct under
non-1.0 `uiScale` and under an ancestor `transform:`, and hit-testing prefers it.

### 1.2 General tooltip renderer · `TODO`

**Why.** Wanted on its own, and it's the natural first consumer that proves 1.1.

**What.** Renders at the very end of the frame. Two sources:
- **Ours** — CSS-styleable, positioned/flipped against screen edges.
- **Platform-delegated** — item slots are platform-unique, so an item's real MC tooltip has to be
  rendered by the loader. That's a new SPI method on the platform seam alongside
  `CgUiInputAdapter` / `UIClipboard` / `UISoundSystem`, not something `core/` can render itself.
  Design the seam now even though no loader implements it yet; the import guard will keep us honest.

**Done when.** Tooltip scene in the harness; delay/flip/screen-edge behaviour is CSS-driven, no pixel
values in Java (per `AGENTS.md` widget conventions).

---

# P2 — General drag protocol

### 2.1 Drag with payload + drop targets · `TODO`

**Why.** `UIDragController` is 77 lines serving Slider/Scroller/SplitView — source-driven, one
listener, single active drag, and explicitly *"NOT general drag-and-drop (no drop-target concept, no
DRAG_ENTER/LEAVE)"* per its own javadoc. Moving windows, resizable edges, draggable editor panels,
node-graph wiring and reorderable tabs all need what it doesn't have.

**What.** Extend rather than replace — the existing local-space coordinate conversion is correct and
subtle (raw input is physical px, geometry is logical; deltas are differences of *separately
converted* endpoints because the transform carries translation). Keep all of that. Add:
- A **payload** object carried by the drag.
- **Drop targets**: registration, hit-testing, and `DRAG_ENTER` / `DRAG_OVER` / `DRAG_LEAVE` / `DROP`
  through the existing three-phase dispatch in `UIInputHandler.sendInputEvent`, so they capture and
  bubble like every other event.
- An opt-in **drag threshold** (callers currently roll their own).
- A **drag ghost** rendered in the top layer — the dependency on P1.

**Explicitly not.** HTML5 `dataTransfer` / `dragstart` / `dropEffect`. Pointer-based, like every
modern web app actually does it.

**Done when.** Existing Slider/Scroller/SplitView drags are untouched and still green
(`SliderDragTest`, `ScrollerDragTest`, `SplitViewDragTest`), plus a harness scene with a real
payload drag between two drop targets.

---

# P3 — Prove the v0 story

### 3.1 Two-session RPC soak in the harness · `TODO`

**Why.** `net/` + `serialization/` is ~1300 lines with six headless tests (`SessionHandshakeTest`,
`ServerBehaviourLoopTest`, `UIDescriptionCodecTest`, `WidgetStateRoundTripTest`, `UITreeObserverTest`,
`ContentHashTest`). The unit level is covered. What is *not* covered is two live sessions exchanging
real traffic over time — which is precisely the v0 shipping story.

**What.** Harness scene: `ServerUiSession` + `ClientUiSession` over `InMemoryTransport`, real widgets,
real RPC round-trips, state mutated on both ends, watching convergence over many frames. Cheap,
touches no Minecraft, and it's parallelizable with P1/P2 — good work to slot in as a break.

**Done when.** Scene registered in `SceneRegistry`; desync or dropped packets are visible on screen
rather than silently absorbed.

### 3.2 Decide the mc1201 question · `BLOCKED` — needs a call from you

**Why.** `mc1201/` has real code (`CgPlatformService1201`, per-loader entrypoints, event bridges,
mixins) but is commented out of `settings.gradle.kts` and does not compile from this build. **Nothing
on this list substitutes for it** — it is the only thing that ever validates the actual distribution
story end to end. It is also a multi-day job with a known classpath minefield (the `mods{}` /
`shadowJar` double-declaration rule in `CrystalGraphics/AGENTS.md`).

**Not scheduling this unilaterally** — it's a big commitment and a product call, not a technical one.
Flagging it so it stays visible rather than quietly becoming a surprise at ship time.

---

# P4 — Falls out of P1 + P2

### 4.1 Moving windows · `TODO`
Screen-space repositioning, correct across screen resizes. Mostly composition once drag exists.
`position: absolute` already parses.

### 4.2 Resizable windows / elements · `TODO`
Edge and corner grab handles on the new drag protocol. Reusable for a text area later. LDLib is a
reasonable reference for handle affordances only — not for API shape.

### 4.3 Basic window manager · `TODO`
Two candidate shapes, pick during 4.1:
- multiple absolutely-positioned "windows" inside one `UIWindow`, or
- a handler coordinating several real `UIWindow`s with an elevated drag/focus owner.

Lean toward the first — one Taffy tree, one style engine, one input handler, and z-ordering already
exists via `sortedChildren`. The second duplicates the whole runtime per window.

---

# P5 — Independent, slot anywhere

### 5.1 More platform abstractions · `TODO`
`ChatComponent`-equivalent, translatable-text service. Same seam pattern as `UISoundSystem` /
`UIClipboard`: interface in `core/`, `NOOP` default, loader registers the real one. Pairs naturally
with the tooltip SPI in 1.2.

### 5.2 Scope LDLib `TextElement` vs `UIText` · `TODO`
**Genuinely unsized.** Needs a read of `../LDLib2` before it can be planned, and it may turn out to be
nothing. Deliverable is a short comparison note, not code. Do this before committing to any `UIText`
work.

### 5.3 Rework tab traversal · `TODO`
Small and self-contained (`UITreeTraversal`). Good filler between big items — not worth a planned
slot of its own.

---

# P6 — Downstream applications

### 6.1 Editor windows · `TODO`
Not one item — resource view, action history, draggable panels, custom `tab` elements with
open/close affordances, and a `Configurator` interface (annotation-driven or otherwise, concept
borrowed from LDLib). Sits on top of P1, P2 and P4. **Re-plan this into real tasks when we get
here**; do not start from this bullet.

### 6.2 General graph view · `TODO`
The grand goal's actual substrate — this is what the node-based shader graph gets built in. Wants
P1, P2 and 6.1 underneath it. Big enough to need its own design doc when it comes up.

---

# Deferred / cut

| Item | Call |
|---|---|
| **UI Editor** | `CUT` as a task. It consumes literally everything else — it's an *outcome*, not something to schedule. |
| **General "scene" view** | `CUT` for now. Too vague to plan, and it reads as a CrystalGraphics-shaped concern rather than a CrystalGUI one. Re-raise with a concrete use case. |
| **"Clean up code / perf"** | Not a bullet — it's continuous. Its one concrete instance is P0.1. Anything else needs a specific measurement before it becomes a task. |

---

# Changelog

- **2026-07-29** — **Re-applied a batch of unpushed UI work on top of remote**, after a reset made
  remote the source of truth. Suite green at **495 tests, 0 failures** (up from 484). Nothing in the
  renderer was touched — `ctx.fillRect`'s signature survived the `CgQuadRenderer` migration, so this
  sat entirely above it.
  - **TextField caret and selection** are now `ascender + descender`, not the full line box. The
    `lineGap` a line box carries is leading *between* lines, and this field is single-line — including
    it made both 12px on 10px of ink and left them overhanging the sprite's bevel. Measured in
    `cgui-textfield`: both exactly 10 logical px.
  - **The selection now paints only while focused.** It previously drew whenever `hasSelection()`, so a
    blurred field kept a live-looking highlight. The range is deliberately *not* cleared on blur.
  - **`line-height: normal`** — CSS's real initial value, carried as a `Float.NaN` sentinel so the
    property keeps its codec. Note it is a **no-op for MinecraftRegular**, whose declared line box is
    exactly 12px; the lineGap removal above is what actually fixed the overrun.
  - **`:focus-visible`** with a `FocusSource` (`KEYBOARD`/`POINTER`/`PROGRAMMATIC`) and the standard
    text-input carve-out, plus `PseudoClasses.lookup`'s `-`→`_` mapping. Both sheets moved their rings
    onto it; theme opt-outs stay on the broader `:focus`.
  - **`border-radius` white-box bug fixed** — `resolveRoundedFill(EMPTY)` returned opaque white, so any
    radius on a backgroundless element painted a slab. Regression rows are back in `cgui-gallery`.
  - **Ore anti-bleed block** consolidated at the top of `ore.css`, grouped by property. Dropped
    `:focus { outline: auto }`, which was dead: `auto` parses as a width, `LengthPercent.parse` returns
    null, and the slot is rejected. Real `outline-style: auto` remains unimplemented.
- **2026-07-29** — **P0.1 done.** Suite green at 484 tests (390 + 94 headless), 0 failures.
  Three drifted expectations reconciled, all traced to one untested commit. Next up: **P1.1**, the
  top layer — starting with the `position: fixed` vs. dedicated-promotion-API decision, since Taffy
  has no `fixed` and that choice shapes everything downstream.
- **2026-07-29** — Replaced the unordered draft with this plan. Prior groundwork landed:
  `CgUiPaintContext` singleton, `CgLifecycleListener` SPI + `CgUiLifecycle`, full `CgBatchRenderer`
  → `CgQuadRenderer` migration, `quad()` builder, `#pragma cg_use quad`, root `AGENTS.md` rewrite,
  and a staleness audit of all three `docs/CGUI_*.md`.
