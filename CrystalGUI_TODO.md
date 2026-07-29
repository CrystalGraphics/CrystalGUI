# CrystalGUI — Working TODO

**This is the live plan.** Ordered, scoped, and maintained as work lands. Superseded the unordered
draft on 2026-07-29.

## Where we are

| | Item | State |
|---|---|---|
| **P0** | Red tests | ✅ done |
| **P1** | Top layer · Tooltip | ✅ done · visually confirmed |
| **P2** | Pointer capture · drag protocol · payload/drop · ghost | ✅ done · visually confirmed |
| **P3** | RPC soak · mc1201 decision | ⏸ **deferred to last** (see P3) |
| **P4** | Dialog · CSS `resize` · DialogManager · 8-way handles · CSS `cursor` | ✅ done · visually confirmed |
| **P5** | ~~Platform abstractions~~ (deferred) · `TextElement` gaps · composite tab stops | ✅ **5.2 + 5.3 done** · visually confirmed (5.1 deferred) |
| **P6** | Editor windows · graph view | ⬜ all prerequisites now exist |

**Suite: 652** (558 `test` + 94 `headlessTest`), 0 failures, 0 skipped.
Last commits: `3bbcf55` DialogManager · `5981cef` handles+cursor · `72f32f7` test cleanup.

**Nothing is blocked on me.** With 5.2 and 5.3 landed, every engine-shaped item is done except the two
deliberately deferred ones (P3, 5.1). **P6 is next**, and 6.1 is the first item that genuinely needs a
re-plan before starting — it is five features wearing one bullet.

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

**Port from Chromium, don't reinvent.** Purity is about the *implementation*, not just the surface
syntax. Where a browser already solves one of these problems, go read how Blink actually does it —
the spec text for semantics, the Blink source for the mechanism — and adapt it to this codebase's
shapes (`UIElement`, Taffy, `RuntimeCache`, the three-phase dispatch). Fine-tuning a known-correct
algorithm beats deriving one.

Two consequences worth stating, because they're what makes this rule real rather than decorative:
- **Writing one of these from memory is not acceptable.** Recalled browser behaviour is exactly the
  thing this rule exists to prevent. Pull the spec and the source first; cite what you used.
- **Where we knowingly diverge, record it and why** — single-cursor input, no `::backdrop` yet, no
  nested browsing contexts. An undocumented divergence is a bug waiting to be "fixed" back.

> There is **no Chromium checkout** in this repo (`research_repos/` has Taffy, LDLib2 and MC 1.20.1
> only). Blink sources have to be fetched from `chromium.googlesource.com`, and the CSS/HTML specs
> from `drafts.csswg.org` / `html.spec.whatwg.org`. Budget a research step before each such task.

**Update docs as we go**, not in a batch at the end. The three `docs/CGUI_*.md` plus root `AGENTS.md`
are the contract; a change that lands without its doc edit isn't done.

**Read the checked-in sources instead of guessing.** `research_repos/` holds three reference trees
that `AGENTS.md` previously claimed were absent (corrected 2026-07-29):

| Path | What | Use it for |
|---|---|---|
| `research_repos/taffy/dev/vfyjxf/taffy/` | Taffy's actual Java sources | Layout semantics — containing blocks, absolute positioning, the flex-wrap cross-size bug `UIText` works around |
| `research_repos/LDLib2/` | LDLib2 checkout (`src/`, not `bin/`) | Widget prior art, `*.lss` themes |
| `research_repos/mc1201_sources/` | Extracted MC 1.20.1 tree | Anything platform-shaped (P3.2, P5.1) |

**Status legend** — `TODO` · `WIP` · `BLOCKED` · `DONE` · `CUT`

---

## The dependency spine

Five items on the original list (tooltips, moving windows, resizable, editor windows, window manager,
draggable panels) all bottleneck on the **same two missing primitives**. Building those first makes
every consumer dramatically cheaper; building them fifth means retrofitting five features.

```
P1 Top layer ✅ ─┬──> tooltips ✅
                 ├──> dropdowns, context menus, modals   (unbuilt, now cheap)
                 ├──> floating editor panels
                 └··> drag ghost ✅ ┐        (soft — ghost only)
                                    ▼
                  P2 drag protocol ✅ ──┬──> moving windows      (P4.1)
                                        ├──> resizable elements  (P4.2)
                                        ├──> node graph wiring   (6.2)
                                        └──> reorderable tabs
```

**Both primitives now exist.** Everything below them is composition rather than new mechanism —
which was the whole reason for building them first. P4 is unblocked.

That ordering fell out of the code, not taste. `UIElement.drawSubtree` paints depth-first;
`paintChildren` pushes ancestor scissor and opacity/mask push FBO layers — so **nothing painted
during the tree walk can escape its ancestors**, which is why a tooltip could not leave a scroller
and a drag ghost could not draw over one.

**The P1→P2 edge stayed soft** (dotted above): only the ghost needed the top layer, and the protocol
itself — payload, drop targets, boundary events, threshold — depended on none of it. P1 went first
for fan-out, not because P2 was blocked on it.

> **In hindsight the ordering paid off twice over**, in a way the plan didn't predict: building the
> primitives first meant their bugs surfaced against *two* consumers each rather than being baked
> into five features. Three of P2's four defects were latent engine bugs (hover leaking during drag,
> `setHitTest` not covering subtrees, `screenToLocal`'s coordinate frame being misread) that the top
> layer and the ghost merely made visible.

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

### 1.1 A real top layer · `DONE` (2026-07-29)

**Shipped.** `UIWindow.addToTopLayer` / `removeFromTopLayer` / `getTopLayer` (Blink's own
`Document::AddToTopLayer` naming), `UIElement.isInTopLayer()` + convenience promote/demote, a second
paint pass, and a separate hit-test walk. 15 tests in `TopLayerTest`. Suite green at 510.

**The plan said three divergences. There are four.** `RuntimeCache.getX()/getY()` accumulates from the
DOM parent *separately* from `localToWorld` — one feeds paint geometry, the other the matrix
`elementHitTest` inverts. Missing it would have double-counted every ancestor offset, sending a
tooltip on a nested element far off-screen while hit-testing stayed correct, or vice versa.

Two more things only implementing revealed:
- **`display: none` is required, not optional.** A tooltip lives as an internal child so the cascade
  reaches it — which means a *closed* one silently pads its anchor. Closed popovers are
  `display: none` on the web for exactly this reason, and it covers layout, paint and input at once.
- **Demotion runs during detach**, where `removeChildInternal` has already dropped the element from
  its parent's child list — so the sibling index is `-1` and there is no slot to restore to. Guarded.

<details>
<summary>Original scoping</summary>

**1.1 — original scoping**

**Why.** There is no way to paint above everything. `paintOverlay` runs *inside* `drawSubtree`
(`UIElement.java:1250`), so it is subject to every ancestor's scissor, transform, opacity group and
FBO layer. A tooltip on an element inside an `overflow: hidden` scroller is clipped by the scroller
today. Confirmed: zero tooltip infrastructure exists in the codebase.

**What.** Follow the web's own answer — the **top layer** (`<dialog>`, the Popover API): a promoted
element paints *after* the main tree, in screen space, unaffected by ancestor clip, transform, or
opacity.

> ### ⚠️ Correction — this is not "just render order"
>
> An earlier draft of this plan said a promoted element could stay parented and that painting it
> elsewhere was "purely a render-order concern." **That is wrong**, and it was the single biggest
> error in the plan. Verified against the code, a promoted element must diverge from its DOM parent
> in *three* independent places. Only the cascade stays put.

| Concern | Today | Must become | Evidence |
|---|---|---|---|
| **Taffy parent** | hard-coupled to DOM parent | reparent to **root** — containing block is the viewport | `UIWindow.registerElement:289` inserts into `element.getParent().taffyNodeId` |
| **`localToWorld`** | chains from DOM parent, **including its transform and scroll offset** | short-circuit to `getRootTransform()`, exactly like the root case | `UIElement.java:1736` (`old.set(parent…localToWorld.get())`), `:1742` (scroll) |
| **Hit-test entry** | reachable only by recursion from root | its own walk, rooted at the promoted element | `UIWindow.elementHitTest:422` gates child recursion on the parent's clip |
| **Cascade / selectors / inheritance** | by DOM parent | **unchanged** — this is the one thing that stays | `StyleEngine` matches on tree position |

The hit-test row is the sharp one. `elementHitTest` only recurses into children when
`!contentCanClipOut || isMouseOverContent(...)` — so a promoted element inside an `overflow: hidden`
scroller is unreachable from the root walk **precisely when the pointer is outside the scroller**,
which is exactly the tooltip case. "Consult the top layer first" isn't a reordering of the existing
walk; it's a second walk with its own entry points.

> ### `position: fixed` is *not* the top layer — they're two different things
>
> Worth getting right before writing any code, because conflating them would be the un-web-like
> choice, not the pure one:
> - **`position: fixed`** — containing block becomes the viewport. Still painted in normal stacking
>   order, still clipped by an ancestor's `overflow: hidden`.
> - **Top layer** (`<dialog>`, popover) — painted above everything, escapes ancestor clip, opacity,
>   and transform.
>
> Tooltips need the **top layer**. So build promotion as the primitive — a `promote()` / `popover`-
> shaped API — and treat `position: fixed` as a separate, later nicety. That is both smaller and more
> faithful to the web than inventing a `fixed` that secretly also promotes.
>
> (`TaffyPosition` is exactly `{RELATIVE, ABSOLUTE}`, verified against `taffy-1.1.4.jar`, so `fixed`
> is a keyword the layout engine has no concept of either way. Reparenting the Taffy node to the root
> *is* the "containing block = viewport" semantic — which is why the top layer gets it for free and
> `fixed` later becomes cheap rather than duplicated work.)

### Spec + Blink findings (researched 2026-07-29 — do not re-derive)

Sources: [CSS Position 4 §top-layer](https://drafts.csswg.org/css-position-4/#top-layer) ·
[Blink paint README](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/third_party/blink/renderer/core/paint/README.md)

| Spec says | Our design |
|---|---|
| "Top layer elements are rendered in the order they appear in the top layer; the last element in the top layer is rendered on top of everything else." | A `List<UIElement>`, painted front-to-back in insertion order. |
| Z-index is **irrelevant** among top-layer elements — they stack purely by order in the layer. | Do **not** consult `sortedChildren`/`z-index` for the top-layer pass. Confirms the earlier hunch. |
| "If its position property computes to fixed, its containing block is the viewport; otherwise, it's the initial containing block." | Both resolve to the root box here → **reparent the Taffy node to root**, exactly as the divergence table says. |
| "cannot be clipped by anything in the document, or obscured by anything except elements later in the top layer" | Immune to ancestor `overflow`/`opacity`/`mask`/`transform` — which is precisely the three-divergence list. |
| Each top-layer element paints its `::backdrop` stacking context first, then itself. | **Deliberate divergence:** no `::backdrop` — this engine has no pseudo-elements at all (internal children are the substitute). Record it; don't fake it. |

**Blink: "Hit testing is done in paint-order"** — Blink reuses the paint walk to *record* hit-test
data rather than maintaining a second traversal. So top-layer hit testing is the paint list walked
**backwards** (last painted = topmost = tested first), then fall through to the main tree. This is
the same paint-order/hit-order invariant `sortedChildren` already enforces, so it needs no new
concept — just a second entry point.

> ### 🔄 Reversal — promotion is **imperative** on the web, not CSS
>
> This plan previously said "promotion mechanism: CSS-shaped, not an ad-hoc Java API." **The web's own
> answer is the opposite.** You promote with `el.showPopover()` / `dialog.showModal()` — imperative
> DOM methods. The CSS `overlay: none | auto` property is *not* how you promote; the UA sets it at the
> `!important` origin as a side effect, purely so transitions can observe the promotion.
>
> So a `promote()` / `showPopover()`-shaped method on `UIElement` **is** the Chromium-faithful choice,
> not a compromise. Only add an `overlay`-like property if we later want to transition promotion.
>
> **Bonus: this dodges a name collision.** CrystalGUI already registers `overlay` (plus
> `overlay-fit`/`-origin`/`-position`) meaning *a drawable composited over the background* —
> `StylePropertyRegistry.java:39`, 8 usages across `default.css` and `ore.css`. That is a different
> thing from CSS's `overlay`. Since promotion is imperative, nothing needs renaming. If we ever do add
> the transition property, rename ours first — ours is closer to a second `background` layer than to
> anything called `overlay` on the web.

**Where.**
- `UIWindow.paintFrame():244` — a second pass after `ui.rootElement.drawSubtree(...)`, inside the
  same `beginFrame`/`endFrame`, with its own `pushPose` + `mulPoseMatrix(rootTransform)`.
  **No scissor reset needed**: `beginFrame` already calls `scissorStack.reset()` once
  (`CgUiPaintContext.java:267`) and pass 1's push/pop pairs are balanced — assert empty rather than
  reset, so an unbalanced pass 1 fails loudly instead of being silently papered over.
- `UIElement.paintChildren` — **must skip promoted children**, or they paint twice.
- `UIWindow.getHoveredElement` — second walk, promoted elements first, reverse promotion order.

**Open questions still genuinely open.**
- Focus and Tab order: a modal should trap focus; a tooltip should never take it. `UITreeTraversal`
  walks the DOM tree, which no longer matches paint order for promoted elements. The web's answer is
  in the HTML spec (`showModal()`'s focus-trapping + the inert subtree), **not** CSS Position 4 —
  fetch that before implementing modals. Not needed for 1.2, since tooltips never take focus.
- Anchoring: a tooltip whose anchor scrolls or animates must track it. Recompute per frame from the
  anchor's `localToWorld` rather than caching a position — cheap, and correct under transforms. The
  web equivalent is CSS Anchor Positioning; worth reading before designing our own placement rules.
- Light dismiss: `popover=auto` closes on outside-click/Escape and nests; `popover=manual` doesn't.
  Decide which we need for 1.2 — probably neither for a pure tooltip.

**Known divergences from the web** (record, don't silently drift):
- No `::backdrop` — no pseudo-elements exist in this engine at all.
- Single-cursor input model, so no multi-pointer top-layer interaction.
- No nested browsing contexts / iframes, so the spec's cross-document top-layer rules don't apply.

**Done when.** A harness scene shows a tooltip escaping an `overflow: hidden` scroller — painted
*and* hit-tested — correct at non-1.0 `uiScale` and under an ancestor `transform:`.

</details>

### 1.2 General tooltip renderer · `DONE` (2026-07-29) — visually confirmed in `cgui-gallery`,
### including after the ownership refactor

**Shipped.** `Tooltip` element (tag `tooltip`, registered so `tooltip { }` is a usable selector),
`UIElement.setTooltip(String)` with hover wiring, `default.css` rules, 11 tests in `TooltipTest`, and
a **Tooltip page in `cgui-gallery`** — hover a row inside the scroller to see it escape the clip.

Placement is below-anchor, flips above when there's no room, clamps horizontally — the useful subset
of CSS Anchor Positioning's `position-try-fallbacks`. **No pixel values in Java**: the gap is the
tooltip's own `margin-top` from `default.css`.

Two findings worth keeping:
- **Placement must read `localToWorld`, not the layout box.** `runtimeCache.getX()/getY()` are pure
  layout and know nothing about scrolling — a scroll container offsets its children inside the
  transform chain. Reading the box pins a tooltip to where its anchor would be if nothing had ever
  scrolled. (The plan did say this; the first implementation still got it wrong.)
- **Scrolling changes no Taffy layout**, so `onLayoutChanged` never fires for a pure scroll —
  scroll-following is carried entirely by the per-frame ticker. Both hooks are needed: the ticker for
  scroll, `onLayoutChanged` for the first frame (at `showFor` time the promoted node has never been
  laid out, so flip/clamp would decide against a zero-size box).

**Also fixed en route:** `core`'s `test` source set had no FreeType bindings dependency, so *any*
test that laid out non-empty text died with `NoClassDefFoundError` deep in `FontFamilyCache`. AGENTS.md
says this source set is the one that's supposed to have fonts — it was simply never wired. Text
shaping is pure CPU, so it works here; only atlas upload and drawing stay harness-only.

**Still open, deliberately:**
- **No show delay.** A delay is a timing value and timing values belong in the cascade — doing it
  properly means a real CSS property, which is its own task.
- **Platform-delegated tooltips not started.** Item slots are platform-unique, so an item's real MC
  tooltip must be drawn by the loader — a new SPI alongside `UIClipboard`/`UISoundSystem`. Deferred
  rather than designed blind: there is no loader in the build to validate the seam against, and P3.2
  is the item that unblocks it.
- `ore.css` has no `tooltip` rules, so it looks the same under both themes.

---

# P2 — General drag protocol

### 2.1 Drag with payload + drop targets · `DONE` (2026-07-29) — visually confirmed in `cgui-gallery`

**Shipped.** Pointer capture in `UIInputHandler`; `UIDragController` rebuilt on top of it with
payload, drop targeting, activation threshold, ghost and cancel path; `DragEvent`
(`Enter`/`Leave`/`Over`/`Drop`/`Cancel`) with listener groups on `UIElement`; a **Drag** page in
`cgui-gallery`. 30 new tests across `PointerCaptureTest` and `DragPayloadTest`; suite 561.

**Slider, Scroller and SplitView are untouched** and still green — the whole point of extending rather
than replacing. Their local-space coordinate conversion survived intact.

| Piece | Ported from | Note |
|---|---|---|
| Pointer capture | Pointer Events L3 | One field + one hit-test substitution. The spec's boundary rule falls out of its hit-testing rule for free. |
| Threshold | nothing — libraries only | No web property exists; caller-supplied physical px. |
| Drop targets | nothing — **ours** | No pointer-model spec for this. Capture routes *events*; the drag layer hit-tests separately. |
| Accept-by-`preventDefault` | HTML5 DnD's one good idea | Rejection is the default, re-read every frame. |

**Four bugs this work exposed or introduced, all fixed:**

| Bug | Where it came from |
|---|---|
| Dragging leaked `:hover` + enter/leave across every element crossed | **Pre-existing**; the P1 hover-chain fix amplified it. Capture fixes it. |
| Ghost rendered at the origin, tracking the cursor 1:1 | `screenToLocal` returns *absolute logical* coords, not an offset within the element |
| Drop targeting unreliable — bins lit only sometimes | `setHitTest(false)` skipped only the element, not its subtree; the ghost's text label stayed hittable |
| Drops fired for targets that never opted in | The `preventDefault()` contract was documented in two places and implemented in none |

> ⚠️ **Testing lesson, recorded because it cost four rounds.** Every one of the above shipped past a
> green suite, and *three* tests written for them still passed against the broken code — fixtures that
> dragged from an element at x=0 (where the absolute coordinate and the grab offset are numerically
> identical), a ghost that was never under the pointer, a single frame when the ghost is positioned
> *after* the drop hit test and so only lands under the cursor on the next one, and a fixture that
> never called `preventDefault()` and was therefore asserting the bug.
> **A test for a positional or protocol bug is not trusted until it has been proven to fail against
> the old code.** Every one of these now has been.

**Still open, deliberately:** no `gotpointercapture`/`lostpointercapture` events (the drag layer's
start/end signals cover it; adding a `PointerEvent` hierarchy is a bigger design call than P2 needed),
and no window-focus-loss cancel — there is no platform hook for it in this build.

<details>
<summary>Original scoping + research</summary>

**Why.** `UIDragController` was 77 lines serving Slider/Scroller/SplitView — source-driven, one
listener, single active drag, and explicitly *"NOT general drag-and-drop (no drop-target concept, no
DRAG_ENTER/LEAVE)"* per its own javadoc. Moving windows, resizable edges, draggable editor panels,
node-graph wiring and reorderable tabs all need what it didn't have.

### Spec findings (researched 2026-07-29 — do not re-derive)

Source: [Pointer Events Level 3](https://www.w3.org/TR/pointerevents3/). **Not** HTML5 DnD — see the
standing-principles note. The thing to port is **pointer capture**, which is the primitive every
pointer-based drag library is built on, and which `UIDragController` is a crude hand-rolled version of.

| Spec says | Our design |
|---|---|
| "the capturing target will substitute the normal hit testing result **as if the pointer is always over the capturing target**, and they MUST always be targeted at this element until capture is released" | `getHoveredElement` returns the capture target while captured. This is the whole primitive; drag is capture + payload on top. |
| "when an element receives the pointer capture all the following events for that pointer are **considered to be inside the boundary of the capturing element**" | Boundary events (enter/leave) must **not** fire to other elements while captured. |
| `gotpointercapture` / `lostpointercapture`, the latter fired "prior to any subsequent events for the pointer after capture was released" | Natural drag-start / drag-end signals, with defined ordering. |
| `pointercancel` fires on modal open, device loss, orientation change…, then `pointerout`, then `pointerleave`, and **implicitly releases capture** | The abort path — Escape, window focus loss. Gives cleanup a defined order instead of an ad-hoc reset. |
| Implicit capture on `pointerdown` for *direct manipulation* devices only | Mouse does not implicitly capture; ours is explicit. |

> **🐛 This exposes a bug in the drag we already have.** `UIInputHandler.endFrame` calls
> `fireAccumulatedMouseEvents()` unconditionally and nothing consults `isDragging()` — so during a
> Slider/Scroller/SplitView drag, hover diffing runs normally: `:hover` flickers on and enter/leave
> fire on every element the cursor crosses. Per spec none of that should happen while captured.
> **The P1 hover-chain fix amplified it**, since events now reach whole ancestor chains. Fixing this
> is the first piece of P2, not an extra.

> **Drop targets are ours to design — there is no web spec for them in the pointer model.** HTML5 DnD
> has `dragover`; pointer-based libraries instead hit-test themselves each move
> (`document.elementFromPoint`). So capture routes *events* to the drag source, while the drag layer
> separately hit-tests to find what's underneath. Say so in the code — an undocumented invention
> reads like a missing port.

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

</details>

---

# P3 — Prove the v0 story · `DEFERRED TO LAST` (2026-07-29)

> **Numbering kept, running order changed.** P4 → P5 → P6 now come first; P3 runs at the end.
> Deliberate call, not drift: P3 *validates* work rather than enabling any of it, so nothing
> downstream is waiting on it, and both items age well — the RPC soak gets more valuable once there
> are richer trees to send, and the mc1201 question is a product call that hasn't come due.
>
> The one thing this trades away: v0's shipping story stays unproven end-to-end for longer. Worth
> saying out loud so it's a choice rather than a surprise later.

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

# P4 — Falls out of P1 + P2 · `ALL DONE`

### 4.1 Moving windows · `DONE` (2026-07-29) — shipped as `Dialog`, visually confirmed

**There is no web precedent for this, and that is the finding.** Nothing in CSS moves an element by
pointer; `<dialog>` is not natively draggable, and every "draggable window" on the web is library code
over pointer events. So — exactly like drop targeting in P2 — this is **ours by design**, and the code
should say so rather than read like a half-remembered port.

### The container IS specified, even though moving isn't — it's `<dialog>`

Second research pass ([HTML §the-dialog-element](https://html.spec.whatwg.org/multipage/interactive-elements.html#the-dialog-element)).
Dragging a window is nobody's spec, but the *thing being dragged* is a well-specified element, and it
also answers the focus question P1.1 deferred to "when modals come up".

> ### 🔄 Correction to the first pass
> The earlier note said "top-layer promotion for the window itself." **That is only right for modal
> dialogs.** Per spec, `showModal()` adds to the top layer; plain `show()` does **not** — a modeless
> dialog stays in normal flow and normal stacking.
>
> Editor windows (P6.1) are modeless. Promoting them would make every floating panel outrank all
> ordinary content and make them unable to stack *among* it. So: **`position: absolute` + `z-index`
> for modeless windows, top layer only for modals.** Simpler, and more faithful than what I first
> wrote.

| Spec | Our design |
|---|---|
| `show()` modeless vs `showModal()` — "displays the dialog and makes it the top-most modal dialog", adding it to the top layer | Two entry points, only the modal one promotes. |
| A modal makes everything outside it **inert** — "cause the focused area of the document to become inert" | **We have no `inert` concept at all.** New primitive, needed only for modals — not for moving. Scope it separately. |
| Initial focus: `autofocus` → focus delegate (first focusable descendant) → the dialog itself; on close, focus returns to the previously focused element | Directly implementable — `UITreeTraversal.firstFocusableIn` already exists. Save/restore the focused element across open/close. |
| Escape fires a cancelable `cancel` event, then closes | Note the ordering hazard: **Escape already cancels a drag** (P2). Innermost interaction wins — a drag inside a dialog must eat Escape before the dialog does. |
| `closedby="any"` — light dismiss on outside click | Optional; decide per use. |
| `::backdrop` renders behind a modal | No pseudo-elements here — already recorded as a P1 divergence. |

**Naming.** Not `UIWindow` — that name is taken by the runtime/Document analogue and reusing it would
be genuinely confusing. `Dialog` is the web's own name for this element and carries the right
connotations.

**Moving itself composes from** P2's positional drag (zero threshold, so it tracks from the first
pixel) on a title-bar/handle child — the same shape `SplitView`'s divider already uses — writing
`left`/`top` at **`INLINE` origin**, matching the precedent `resize` just set. Consistent, and it
leaves an author's `!important` able to pin a window.

**Both open questions from the first pass now resolve:**
- *Capability or widget?* **Widget.** The rule that falls out of P4.2: if the web expresses it as a
  CSS property, make it ambient on `UIElement` (`overflow`, `resize`); if the web expresses it as an
  *element*, make it a widget (`<dialog>`). Moving has no CSS property and needs a handle, which is
  structure.
- *Screen resize?* **Clamp**, not proportional re-anchor. No spec covers it; clamping is what OS
  window managers do and it cannot drift a window somewhere the user never put it. Ours, so say so.

### 4.2 Resizable windows / elements · `DONE` (2026-07-29) — CSS `resize`, visually confirmed

**This one IS a web feature: the CSS `resize` property** (CSS Basic User Interface L4). A port, not an
invention — which makes it the higher-confidence half of P4.

Source: [CSS UI 4 §resize](https://www.w3.org/TR/css-ui-4/#resize)

| Spec says | Our design |
|---|---|
| `resize: none \| both \| horizontal \| vertical \| block \| inline` | Implement the first four. `block`/`inline` are writing-mode-relative and this engine has no writing modes, so they would be silent aliases — **record as a divergence** rather than fake them. |
| "the user agent sets the width and height properties to px unit length values … in the element's style attribute DOM, replacing existing property declaration(s), if any, **without `!important`**" | **`StyleOrigin.INLINE`, via the existing `StyleGroup.inlinePipeline`** — *not* `IMPORTANT`. This is the fidelity detail most likely to be got wrong: every other widget-driven geometry write here uses IMPORTANT, but that would let a user resize beat an author's `!important`, which the spec explicitly does not. |
| "must allow the user to resize … with no other constraints than what is imposed by min-width, max-width, min-height, and max-height" | Free — those properties already exist and Taffy clamps. No manual clamping, and none should be added. |
| "applies to elements that are **scroll containers**" | `isScrollContainer()` already exists. **But see below.** |
| Handle position/appearance unspecified | Ours: an internal `__resizer__` child, geometry and art from `default.css`, per the no-pixels-in-Java rule. |

> **The scroll-container restriction is worth diverging from, deliberately.** Part of it exists
> because the resizer is drawn *in the scrollbar corner* — an artifact of where the widget was put,
> not a semantic requirement, and "resize silently does nothing" is one of the web's genuinely
> annoying gotchas. We draw our own grabber, so that part buys us nothing.
>
> **Correction after seeing it run:** that was only half the story. The restriction *also* guarantees
> a resizable box contains its content — shrink an `overflow: visible` element below its content and
> the content spills out. Correct CSS, visually broken, and browsers never face it because `resize`
> implies a scroll container. The divergence is still right (clipping is one declaration;
> inexpressiveness is forever), but **anything resizable should normally also set `overflow`**. Not
> enforceable in the UA sheet — there is no "has resize set" selector — so it is documented on
> `Resize` instead.

**Composes from** P2's positional drag on the `__resizer__` handle. Edge handles (not just the corner)
are a superset the spec doesn't cover — add only if 4.1/4.3 actually want them.

### 4.3 Basic window manager · `DONE` (2026-07-29)

**Shipped.** `DialogManager` — stacking, activation and cascade placement for a set of `Dialog`s
sharing one container. 12 tests; gallery Dialog page rebuilt on it (three windows + a "new window"
button, replacing the hand-rolled z-counter it had).

**Shape 1 confirmed** — absolutely-positioned siblings in one `UIWindow`. Verified rather than
assumed: `sortedChildren` sorts z-descending *per parent*, stable, later-inserted-first on ties, and
painting walks it reversed — so each element is effectively its own stacking context, "raise to front"
is just "hold a higher z-index than your siblings", and hit-testing agrees with painting for free
because both read the same ordering. Shape 2 would have duplicated a Taffy tree, style engine and
input handler per window to express that.

Not a `UIElement`, following `CheckboxGroup`'s precedent: nothing to paint or lay out, so a node
would only add a box whose job is to not affect anything.

**Policy decisions, all ours** — raise-on-click uses the **capture phase** so clicking a button inside
a window still activates it; z is monotonic rather than renumbered (only relative order matters);
cascade placement offsets each new window and relies on `Dialog`'s own clamp to stop at the edge
rather than carrying wrap logic.

> ### Prior art check (prompted mid-task — worth having done)
> I had written "no web precedent", which is true, but I had **not** checked LDLib2. It does have
> some, and one claim of mine was wrong:
> - `WindowDragHelper.setDragMove(element, target, …)` is the **same design** independently arrived
>   at: a handle drags a target, writing `left`/`top`, snapshotting position at grab time. Good
>   validation of the shape, including the snapshot that avoids compounding deltas.
> - **LDLib has no window manager.** Its `Dialog` is a fixed `zIndex(1)` overlay and there is no
>   `bringToFront` anywhere in the repo. So stacking/activation here is a genuine addition, not a
>   reinvention.
> - **Worth stealing later:** `WindowDragHelper.ResizeHandle` offers all **8** handles (4 edges + 4
>   corners) with per-handle cursors. Our `resize` port follows CSS's single corner grabber; edge
>   handles are the superset P4.2 already flagged as "add only if 4.1/4.3 want them". They do.

---

### 4.4 8-way resize handles · `DONE` (2026-07-29)

Four edges + four corners, replacing CSS's single corner grabber. Not in the original plan — surfaced
by the P4.3 prior-art check against LDLib2's `WindowDragHelper.ResizeHandle`.

**Not a divergence**, contrary to what 4.2 assumed: CSS UI 4 mandates only "a bidirectional resizing
mechanism" and leaves the mechanism to the UA. Browsers ship one grabber because theirs lives in the
scrollbar gutter. Which handles exist follows the resizable axes, so `horizontal` gets two side edges
and no corners. A **leading edge moves the box as well as resizing it** — the case CSS avoids by only
offering bottom-right — via a new `UIElement.applyResizeOrigin` seam that `Dialog` overrides so its own
clamped position stays authoritative.

### 4.5 CSS `cursor` + native OS cursors · `DONE` (2026-07-29)

Also unplanned — asked for after the handles landed, because invisible edge handles are undiscoverable
without a cursor change.

A port of CSS UI 4 `cursor` (inherited; `auto` resolves to `text` over editable elements), resolved
from the hover diff so **pointer capture pins it for a whole drag for free**. Presentation sits behind
`UICursorService`, because LWJGL2 — the harness *and* MC 1.7.10 — has no standard cursors at all, unlike
GLFW. `CursorBitmaps` draws 32×32 arrows procedurally and lives in `core/` (pure pixel maths), so each
loader duplicates only a ~90-line adapter.

**Deliberately rejected:** drawing an icon over the OS arrow (LDLib's approach) — two cursors on screen.

> **Open, small:** `mc1710`'s copy of the adapter has never been compiled, since that module is excluded
> from the build. An LWJGL3/GLFW implementation is still unwritten and would be much shorter
> (`glfwCreateStandardCursor` covers the resize set with no bitmaps).

---

# P5 — Independent, slot anywhere

### 5.1 The platform seam sweep · `DEFERRED` (2026-07-29) — with P3

> **Deferred alongside P3**, and for the same reason: this is integration surface, not core foundation.
> Nothing in P5.2/5.3/P6 needs it, and it cannot be verified without a loader in the build anyway — so
> it naturally belongs next to P3.2 rather than ahead of the remaining engine work.

**Re-scoped 2026-07-29:** this was "ChatComponent + translatable service", but two platform items have
since been deferred *into* it from elsewhere. Doing them together is cheaper than three separate visits,
because they share one question — what the seam looks like and who registers it.

| Item | Came from | Notes |
|---|---|---|
| `ChatComponent`-equivalent, translatable text | original 5.1 | The reason this section existed. |
| **Platform-delegated tooltips** | deferred from 1.2 | An item's real MC tooltip must be drawn by the loader; item slots are platform-unique. Not designed yet, deliberately — there was no loader to validate against. |
| **LWJGL3/GLFW cursor service** | open from 4.5 | Much shorter than the LWJGL2 one: `glfwCreateStandardCursor` covers the resize set with no bitmaps. |

All follow the established shape: interface in `core/`, `NOOP` default, loader registers the real one —
now proven four times over (`CgUiInputAdapter`, `UIClipboard`, `UISoundSystem`, `UICursorService`).

> **The recurring caveat:** none of it can be *verified* without a loader in the build, which is P3.2 —
> the one item still waiting on a call from you. Designing a seam blind is how you get an interface
> nobody can implement, so the honest sequencing is either "accept unverified" or "do P3.2 first".

### 5.2 LDLib `TextElement` vs `UIText` · `DONE` (2026-07-30)

Read `research_repos/LDLib2/.../gui/ui/elements/TextElement.java` (358 lines) against our `UIText`.
**Three real gaps, one non-gap, and one thing we already do better.**

| LDLib has | We have | Verdict |
|---|---|---|
| `textAlignHorizontal` / `textAlignVertical` | **nothing** — only `text-offset-x/y`, which nudges glyphs by a fixed amount | ❗ **Real gap, and the biggest.** CSS `text-align` is the port. Vertical has no single CSS equivalent (the web uses flex/line-height), so that half needs a decision. |
| `TextWrap.NONE` | always wraps | ❗ **Real gap.** CSS `white-space: nowrap` / `text-wrap: nowrap`. Needed the moment a label must not reflow. |
| `TextWrap.ROLL` / `HOVER_ROLL` (marquee) | nothing | ⚠️ **Deliberately skip.** `<marquee>` is obsolete on the web and CSS has no replacement — because the web's answer to overflowing text is **`text-overflow: ellipsis`**, which is a real property, is more useful in a dense UI, and we also lack. Port that instead. |
| `textShadow` (works) | `text-shadow` **registered but unimplemented** | ❗ Already a known no-op in `StylePropertyRegistry`. Cheap to finish and now has a second reason to. |
| `adaptiveWidth`/`adaptiveHeight` as explicit flags | `selfSizesWidth`, auto-detected once | ✅ **Ours is better** — one fewer thing for a caller to get wrong, and it cannot disagree with reality. Their `recompute()` is otherwise near-identical to ours, including pushing size back at IMPORTANT origin and clearing it when not adaptive. Independent convergence on the same design. |
| `setText(Component)`, `setText(String, translate)` | — | Platform-shaped → belongs to **5.1** (deferred), not here. |
| `loadXml` | — | Their XML UI format. Not wanted. |

**Sized as four independent ports, in value order:**

1. **`text-align`** — the clear win. Real CSS, obvious gap, unblocks every centred label in a themed UI.
2. **`text-overflow: ellipsis`** — the correct answer to LDLib's marquee, and more useful than it.
3. **`white-space: nowrap`** — small, and pairs naturally with (2), since ellipsis only means anything
   when text does not wrap.
4. **Finish `text-shadow`** — already registered, currently a no-op that silently does nothing.

> **Deliberate omission recorded:** no marquee. Scrolling text is an animation of overflow, not a text
> property; if it is ever wanted it belongs on top of (2)/(3) rather than instead of them.

#### Outcome

All four shipped. New style types: `visual/text/TextAlign` (`leadingFraction()` — the fraction of
leftover space that goes before the line, so LEFT/CENTER/RIGHT is `0`/`0.5`/`1` and paint does one
multiply), `WhiteSpace` (`wraps()`), `TextOverflow`. All four properties inherit except
`text-overflow`, matching CSS.

Three implementation notes worth keeping:

- **`text-align` is block-level only.** Vertical alignment was deliberately *not* invented: `UIText`
  sizes to its content, so the containing flex box already aligns it — adding a `text-align-vertical`
  would mean two mechanisms for one result, and the web declined to add one for the same reason.
- **Ellipsis re-shapes, it does not drop glyphs.** Binary search over string prefixes + `"…"`, then
  re-shape. Truncating the shaped run would be wrong: shaping is not a per-character mapping, so
  ligatures and kerning at the cut change the width of what remains.
- **`nowrap` sets the wrap bound to 0, it does not widen the box.** `max-width` still caps the element,
  so the text overflows rather than growing it — which is exactly CSS, and exactly why `text-overflow`
  only means anything alongside `nowrap`.

`text-shadow` is now consumed: a second draw at +1px in a quarter-brightness copy of the colour (alpha
preserved). It was registered long enough ago that `AGENTS.md` listed it as a known no-op.

10 tests in `TextLayoutPropertiesTest`. **Two of my own assertions were wrong first time** — they
asserted *width* for `nowrap`, which `max-width` pins; the observable effect is *height*. Corrected to
assert the line count via height, which is what actually distinguishes the two modes.

### 5.3 Composite tab stops — the roving tabindex · `DONE` (2026-07-30)

**Re-scoped from "rework tab traversal to your preferences."** The actual target: Tab should move
between *components*, and arrow keys should move *within* one. Not generic DOM behaviour — browsers do
it natively only for radio groups — but it is a real, specified convention.

Source: [ARIA APG — Developing a Keyboard Interface](https://www.w3.org/WAI/ARIA/apg/practices/keyboard-interface/)

> "A primary keyboard navigation convention common across all platforms is that the tab and shift+tab
> keys move focus from one UI component to another while **other keys, primarily the arrow keys, move
> focus inside of components** that include multiple focusable elements."
>
> "the tab sequence should include **only one focusable element of a composite** UI component."

**The mechanism is the "roving tabindex":** the active child holds `tabindex="0"`, every sibling holds
`tabindex="-1"`; arrow keys move the 0 along and call `focus()`. Composites named by the APG:
radiogroup, tablist, listbox, menu/menubar, grid, tree/treegrid, toolbar, combobox.

#### What we already have, and what is missing

| | State |
|---|---|
| **Arrow navigation inside a composite** | ✅ **`TabView` already does it** — Left/Right/Up/Down are axis-aware (they respect `TabSide`), plus Home/End, bubble-phase so a focused child sees keys first, and focus moves *with* selection. A working precedent to generalise from, not a blank page. |
| **Single tab stop per composite** | ❌ **The whole gap.** `Tab extends Button`, so every tab is `FocusPolicy.CLICK` and individually in the tab sequence — Tab cycles through all N tabs instead of entering the tablist once and leaving it once. |
| `tabindex` as a concept | ❌ Absent. There is no ordering mechanism at all: `UITreeTraversal` walks pure DOM order. |
| `display: none` subtrees excluded | ✅ Already correct, and documented — `hasFocusableDescendant` short-circuits on a hidden root. Found via `TabView`'s inactive panes; **I suspected this was broken and it was not.** |

#### The design question to settle first

`tabindex` is an *integer* attribute on the web, and its positive values are widely considered a
misfeature (they reorder the whole document and are near-impossible to maintain). We already have
`FocusPolicy` as an enum. **Two candidate shapes:**

1. **Port `tabindex` as an int** — faithful, and gives arbitrary reordering for free. Also imports the
   misfeature, and `FocusPolicy` then overlaps it confusingly (`NONE` vs negative, `FOCUSABLE`/`CLICK`
   vs 0).
2. **Extend `FocusPolicy` with the one distinction the pattern needs** — something like
   `PROGRAMMATIC_ONLY` (focusable, reachable by arrows and `requestFocus`, *not* in the tab sequence),
   which is exactly `tabindex="-1"`. No integer ordering, no reordering misfeature.

**Leaning (2).** The roving-tabindex pattern only ever uses `0` and `-1`; positive values are a
separate feature nobody has asked for, and the APG itself never uses them. Recording the divergence is
cheaper than importing an attribute the web community regrets.

#### Outcome — option (2), as leaned

`FocusPolicy.CLICK_NOT_TABBABLE` is the whole of the new vocabulary: focusable by click, by
`requestFocus`, and by arrow keys; **skipped by Tab**. Queried through three predicates
(`isFocusable()`, `isTabbable()`, `focusesOnClick()`) rather than by `==`, which is what let the two
behaviours diverge without every call site having to enumerate constants.

**The load-bearing consequence: the tree walkers split in two.**

| Question | Predicate | Walkers | Asked by |
|---|---|---|---|
| May this hold focus at all? | `focusable()` | `firstFocusableIn` / `lastFocusableIn` | focus delegation (`Dialog.show()`), arrow keys, `requestFocus` |
| Is it in the Tab sequence? | `tabbable()` | `firstTabbableIn` / `lastTabbableIn` / `nextTabbable` / `previousTabbable` | Tab / Shift+Tab |

Getting this wrong in *either* direction is a real bug, which is why both pairs exist and both are
pinned by tests: gate Tab on `focusable()` and a composite is N stops again; gate a dialog's focus
delegate on `tabbable()` and it skips its own first control.

Two latent traps found while wiring it — both the same shape as P2's, where the new consumer merely
made an existing weakness visible:

- **`UIInputHandler` tested `getFocusPolicy() == FocusPolicy.CLICK`** for focus-on-click. Left alone,
  every tab would have gone dead to the *mouse* the instant it stopped being the selected one. Now
  `focusesOnClick()`. Proved by reverting the line and watching the test fail.
- **`selectTab(null)` is public**, so a first-tab fallback is mandatory. Without it a deselected strip
  has *zero* tab stops and the entire tablist vanishes from the keyboard — strictly worse than the
  N-stops problem the pattern set out to fix. APG has the same fallback for the same reason.

**Ownership moved to `TabView`, not `Tab`.** `TabView.updateTabStops()` assigns all N on every selection
*and* membership change; `Tab.setTabStop` is package-private. "Exactly one" is a strip-wide invariant,
identical in kind to selection itself, and a per-tab setter is precisely how it reaches zero or two.
Removing a tab restores it to ordinarily-tabbable, so a tab handed back and re-used elsewhere is not
silently keyboard-dead.

`hasFocusableDescendant` needed no change: it is keyed on `focusable()`, a superset of `tabbable()`, so
it stays a valid fast-path filter for both walkers and does not invalidate when only the stop moves.

16 tests in `CompositeTabStopTest`; **11 of them fail against the old semantics**, verified by
temporarily reverting `isTabbable()`.

#### Decisions taken on the open questions

- **Wrap-around: it already cycled**, and that stays. `UIInputHandler` falls back to
  `first/lastTabbableIn(root)` when the walk runs off the end. Correct here — a browser hands focus to
  its chrome, and we have none.
- **No reusable composite helper, and no `CheckboxGroup` change.** `CheckboxGroup` is a plain
  coordinator whose members can sit anywhere in the tree, and it has **no arrow-key handling at all**.
  Making it one tab stop without that would strand every unselected member with no way to reach it —
  shipping half the pattern is worse than shipping none. `TabView` is the only composite that has the
  arrow half today, so it is the only one that gets the Tab half. Revisit when a second composite
  genuinely earns it; a helper abstracted from one consumer would be guesswork.
- Focus trapping for modals still needs `inert`, which remains unbuilt (noted in P4.1). Same subsystem,
  still out of scope.

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

- **2026-07-30** — **Hygiene pass before committing.** One substantive find among five cosmetic ones:
  - **The ellipsis path re-shaped every frame, forever.** `measureEllipsised` builds a fresh
    `CgTextLayout` per probe, so unlike the wrapping path nothing memoised it — a truncating label re-ran
    its entire binary search each frame. Worse, the class javadoc explicitly claimed the opposite. Now
    memoised on `(text, family, contentWidth)`, reusing the reference-equality-on-family trick
    `ensureShaped` already relies on, and the javadoc says which path its reuse claim covers.
  - A local `CgTextRenderer text` **shadowed the `text` Property field**; `ELLIPSIS` and
    `ELLIPSIS_CODE_POINT` were two independent spellings of one character that could drift (the drift
    would mean coverage-checking a different glyph than the one drawn); `Tab`'s javadoc still claimed Tab
    owned the tab stop; `findFocusableElement` walks the *tabbable* chain now → `moveTabFocus`.
  - The wrapper-vs-`UIText` trap is now recorded on `TextOverflow` itself, where the next author will hit
    it — not just in the changelog.

- **2026-07-30** — **Two follow-ups from the visual pass**, both raised by the user after confirming the
  tab traversal behaves as intended.
  - **The dialog title now ellipsizes.** Asked whether truncation should be a default: on the web, no —
    `text-overflow: clip` is the initial value and no UA sheet ellipsizes generic text. But a title bar
    is *chrome*, with a close button a long title paints over, and every native window manager
    ellipsizes titles. Scoped to `dialog .__title-bar__ .__label__` in `default.css`, not made global.
  - The real fix in that rule is **`flex-shrink: 1; min-width: 0`**, not the ellipsis — this engine's
    Taffy default is `flex-shrink: 0`, so the label was keeping its full intrinsic width and pushing the
    close button off the bar. Ellipsis alone would have done nothing.
  - **Two false greens caught while pinning it**, both worth remembering: `DialogTest` never installed
    `StyleSheet.DEFAULT` (it is *not* automatic), so a CSS assertion there exercised no CSS; and a
    **closed dialog is `display: none`**, so every box measures 0 and "does it fit?" passes against
    `0 <= 0`. Both are now invariants in `AGENTS.md`. Confirmed the fixed test fails without the rule.
  - **Two real ellipsis bugs, both found on screen and neither catchable by the tests as written.** The
    root cause of that gap: truncation changes no geometry, so nothing in the layout tree reveals whether
    it fired. `UIText.displayedText()` now exists to answer it — and doubles as the API the
    "tooltip only when the label is truncated" pattern needs.
    1. **`text-overflow` does not inherit** (correctly — CSS UI 4), so setting it on a wrapper never
       reaches the `UIText`. `white-space` *does* inherit, so the nowrap half arrived and the row rendered
       as a plain clipped line that looked exactly like the row above it. My own gallery page had this bug.
    2. **The ellipsis glyph was invisible.** `MinecraftRegular.otf` has no U+2026, so a correctly
       shortened label drew a blank advance — indistinguishable from `clip`, with every measurement right.
       Now falls back to `...`, which is WebKit/Blink's own rule for exactly this.
  - **The title rule was also subtly wrong**, and the user caught it: `flex-shrink: 1` sizes the label
    from its own intrinsic width, so a title fitting by a fraction of a pixel truncated anyway and lost a
    whole character (`panel one` → `panel on`). Replaced with the web's canonical `flex: 1 1 0;
    min-width: 0` — the label is *what is left after the close button* and no longer depends on its own
    glyphs at all. The test now pins **both** halves, because asserting only "long titles truncate" called
    the broken version a pass.
  - **`text-shadow` now batches.** Both passes go inside one `beginBatch`/`endBatch`, so a shadowed
    label costs one draw call instead of two — `CgTextRenderer.draw()` auto-wraps each submit in its own
    batch otherwise, paying two material binds for the same atlas and shader. Wrapped in `try/finally`
    because an unclosed batch makes the *next* `beginBatch` throw, which would take down all subsequent
    text rather than one label.

- **2026-07-30** — **5.2 + 5.3 done.** Suite 647 (553 + 94). P5 is closed apart from the deliberately
  deferred 5.1; every engine-shaped item is now behind us and **P6 is next**.
  - **5.2 — four CSS text properties**, ported after reading LDLib's `TextElement` line by line:
    `text-align`, `white-space: nowrap`, `text-overflow: ellipsis`, and finally *consuming*
    `text-shadow`, which had been registered as a documented no-op. Marquee deliberately skipped —
    `<marquee>` is obsolete and ellipsis is the web's answer to the same problem.
  - Ellipsis **re-shapes a shortened string** rather than dropping shaped glyphs, because shaping is not
    a per-character mapping; truncating the run would change the width of what remains.
  - `nowrap` **does not widen the box** — `max-width` still caps it, so text overflows. Two of my own
    tests asserted width and were wrong; the observable difference is height.
  - **5.3 — the roving tabindex.** One new `FocusPolicy` constant (`CLICK_NOT_TABBABLE` = the web's
    `tabindex="-1"`) instead of an integer `tabindex`, as the plan had leaned. A `TabView` strip is now
    one Tab stop however many tabs it has, with arrows moving inside it — the arrow half already existed.
  - **The walkers split in two**, and that is the load-bearing part: `focusable()` for focus delegation
    and arrows, `tabbable()` for Tab. Getting it wrong in either direction is a bug, so both are pinned.
  - **Two latent traps, same shape as P2's** — the new consumer only made them visible.
    `UIInputHandler` tested `== FocusPolicy.CLICK` for click-focus, which would have made every
    unselected tab dead to the mouse; and `selectTab(null)` being public means a strip needs a
    first-tab fallback or it drops to *zero* tab stops and leaves the keyboard entirely.
  - **`TabView` owns the invariant, not `Tab`** — "exactly one tabbable tab" is strip-wide, exactly like
    selection, and a per-tab setter is how it reaches zero or two.
  - **`CheckboxGroup` deliberately left alone**: it has no arrow-key handling, so one tab stop would
    strand its other members. Half the pattern is worse than none.
  - 16 tests, **11 of which fail against the old semantics** (verified by reverting `isTabbable()`).

- **2026-07-29** — **CSS `cursor` + native OS cursors.** Suite 619. A port, plus the first real
  implementation behind a platform seam.
  - **`cursor` inherits**, initial `auto`, and `auto` is a *context rule* — "`text` over selectable or
    editable elements, `default` otherwise" — which lands on the existing `consumesTextInput()` rather
    than needing a new signal. Both facts came from the spec, not memory.
  - Full keyword set registered (all 30-odd), on the same reasoning as `text-shadow`: a stylesheet must
    be able to declare a standard property without a warning, and a curated subset hides its omissions.
  - Resolution is driven from the **hover diff**, not the property's change listener — the cursor is a
    function of where the pointer *is*. **Pointer capture pins it for a whole drag for free**, since
    hover already resolves to the capture target; a resize reverting to `default` when the pointer left
    the handle would look broken.
  - Pushed **on change only**: an implementation may allocate a native object per call.
  - **`UICursorService`** seam (NOOP default, same shape as `UIClipboard`/`UISoundSystem`), because the
    platforms differ sharply: LWJGL3/GLFW ships the standard resize set, LWJGL2 — the harness *and* MC
    1.7.10 — has no standard cursors at all and can only build them from pixel data.
  - **`Lwjgl2CursorService` + `CursorBitmaps` in the harness**, generating 32×32 arrows procedurally
    rather than shipping PNGs — the shapes are runs and triangles, and code keeps each hotspot next to
    the geometry defining it. Outline derived from the body so the two can't drift. Three LWJGL2 traps
    documented at the seam: bottom-up images, hotspot-Y-from-bottom (invisible on a symmetric arrow —
    only the hotspot would betray it), platform size limits, and the transparency capability bit.
  - Rejected the drawn-overlay alternative (LDLib's approach): drawing an icon *over* the OS arrow
    means two cursors on screen.
  - **Hygiene pass before commit found two real things:**
    1. The native-cursor cache was keyed by **CSS keyword, not by shape** — 18 keywords map onto 6
       pictures, so `ew-resize`/`col-resize`/`e-resize`/`w-resize` each allocated their own identical
       native OS handle. Now keyed by a `Shape` enum: 6 natives, not 17. Capability query hoisted to
       once per display too.
    2. A javadoc claimed "a still pointer costs nothing." **False** — resolution runs every frame from
       `endFrame()`; only the platform call is skipped. Corrected, and the per-frame cost is now
       justified rather than denied: a stationary pointer can still need a different cursor when the
       element under it changes (transition finishing, class toggling, content reflowing), which is the
       same reasoning that makes `beginFrame()` invalidate hover unconditionally.
  - **`CursorBitmaps` moved to `core/`.** It is integer pixel maths with no platform code, so it passes
    the import guard trivially — and every LWJGL2 loader needs the same shapes. Each loader now
    duplicates only the ~90-line adapter, not the artwork. Same split as the engine owning
    `default.css`: the pictures are ours, presenting them is the platform's.
  - **Copied to `mc1710`** as `com.crystalgui.platform.Lwjgl2CursorService`. ⚠️ That module is commented
    out of `settings.gradle.kts` and has no CrystalGUI integration, so **that file has never been
    compiled** — it is a byte-for-byte sibling of the harness copy, which *is* verified, so the logic is
    exercised; what is unverified is only whether the module can resolve `core` and LWJGL2. Wiring
    instructions are in its javadoc.
- **2026-07-29** — **8-way resize handles.** Suite 610. Four edges + four corners, taken from LDLib2's
  `WindowDragHelper.ResizeHandle` after the P4.3 prior-art check flagged it.
  - **Not a divergence, which I expected it to be.** Re-reading CSS UI 4: it says only that the UA
    "presents a bidirectional resizing mechanism" and never prescribes a single corner grabber.
    Browsers ship one because theirs is drawn in the scrollbar gutter with nowhere else to go. So the
    earlier note about diverging here was wrong in our favour.
  - Which handles exist follows the resizable axes: `horizontal` yields the two side edges and **no
    corners**, since a corner would imply a vertical resize the mode forbids.
  - **A leading edge is a move as well as a resize** — growing leftwards keeps the right edge still.
    That is why the web only offers bottom-right: it is the one handle that never repositions
    anything. New `UIElement.applyResizeOrigin` seam, which **`Dialog` overrides** — it keeps
    `left`/`top` in fields and re-clamps every frame, so a handle writing the property directly would
    have been silently reverted on the next tick.
  - Edges are invisible but grabbable (a background is not what makes something hittable); corners are
    tinted and sit above the edge strips they cross so a corner drag wins the hit test.
  - Three new origin tests verified to fail with the seam disabled before being trusted.
- **2026-07-29** — **P4.1 + P4.2 done, then hygiene-scanned.** Suite 592.
  - **P4.2 CSS `resize`** — ambient property, `__resizer__` handle, INLINE-origin size writes,
    min/max clamping left to Taffy. Two documented divergences (applies regardless of `overflow`; no
    `block`/`inline`).
  - **P4.1 `Dialog`** — modeless `<dialog>`, movable by its title bar, `__close__` button, spec focus
    delegation and focus-restore, clamped to its container.
  - **Three bugs found by running it, not by tests:**
    1. Text spilling a resized panel — *not* a bug (correct `overflow: visible`), but it corrected my
       research: the spec's scroll-container restriction also guarantees a resizable box contains its
       content, which I'd dismissed as purely a scrollbar-gutter artifact.
    2. Escape didn't close dialogs — because it **shouldn't**. `show()` establishes no close watcher,
       so browsers don't either. Removed, pinned with an assertion, close button added instead.
    3. Reopened dialogs snapped to (0,0) and looked undraggable — the clamp ticker runs before layout,
       so it read the zero-sized `display: none` box and wrote that back. Position is a field now.
  - A focus ring that appeared "from nowhere" was real focus, correctly restored on close — but
    unreachable by hand, since `FOCUSABLE` excludes click. Clicking the chrome now activates the window.
  - Hygiene scan: dead import dropped; docs updated (`AGENTS.md` widget table, registry count,
    internal classes, property list, package map; `CGUI_WIDGETS.md` §11 Dialog and §12 resize).

  > **Found but deliberately NOT fixed here:** the stub `CgUiInputAdapter` is duplicated across **24**
  > test classes, and most of them split it into a second `@Before` whose ordering JUnit 4 does not
  > guarantee — they pass on a name hash. `DialogTest` hit exactly this and NPE'd. A shared base class
  > fixes both at once (superclass `@Before` ordering *is* guaranteed), but it touches 18 files that
  > have nothing to do with P4. **Own commit.**
- **2026-07-29** — **P2 hygiene scan.** Suite 561. Three findings, one of them the worst kind:
  1. **The drop opt-in was documented in two places and implemented in none.** `DragEvent.Over`'s
     javadoc and `UIElement.onDragOver`'s both said `preventDefault()` accepts a drop —
     `isDefaultPrevented()` was never read, so every drop fired regardless. A false documented
     contract is worse than a missing feature, because callers build on it. Now implemented and
     re-read every frame (never latched — a target can stop accepting), with `isDropAccepted()`
     exposed and three tests.
  2. **`startDrag` while a drag was live overwrote the state outright** — the old listener never
     heard it ended, its target stayed highlighted, its ghost stayed promoted. Now cancels first, so
     the displaced drag gets the same defined teardown Escape gives it.
  3. **The enter/leave chain walk was written twice** — once for mouse, once for drag — and the
     *ordering is the contract*. Extracted to `UITreeTraversal.forEachEntered`/`forEachLeft`; two
     copies of a subtle order is how they drift apart.
  Docs: `AGENTS.md` input stack (pointer capture + the drag protocol), `DragEvent` rows in the events
  table, package map, and two new load-bearing invariants.
- **2026-07-29** — **P2 steps 1–4.** Suite 550. Pointer capture, drag migration, payload + drop
  targets, threshold, cancel path.
  - **Pointer capture (Pointer Events L3)** in `UIInputHandler`. One field and one hit-test
    substitution, not a second mechanism — because the hover cache resolves to the capture target for
    the whole capture, the spec's boundary rule ("considered to be inside the boundary of the
    capturing element") falls out of its hit-testing rule for free.
  - **Fixed the pre-existing bug** it exposed: dragging leaked `:hover` and enter/leave to every
    element the cursor crossed. Log for one drag across one element went from
    `[leave:source, enter:other, leave:other, enter:source]` to empty.
  - **`DragEvent`** (`Enter`/`Leave`/`Over`/`Drop`/`Cancel`) dispatched to what is *geometrically*
    under the pointer — which is why `UIWindow.getHoveredElement` was deliberately left free of
    capture substitution. Conflating the two would make every drop land on the thing being dragged.
  - Kept HTML5 DnD's one good idea: `preventDefault()` on `Over` is how a target accepts a drop, so an
    element that has never heard of dragging cannot silently become a drop target.
  - **Threshold** (physical px, caller-supplied, conventional default) so a click on a draggable
    element stays a click. Positional drags — Slider/Scroller/SplitView — keep zero threshold and are
    otherwise completely unchanged.
  - **Cancel path** on Escape, modelled on `pointercancel`: releases capture, tells the stranded drop
    target the drag left it, and calls `onDragCancel` rather than `onDragEnd`.
  - **Drag ghost** — a plain caller-owned `UIElement`, deliberately not a `DragGhost` widget: the
    input layer has no business importing `ui.elements`, and a ghost is just "an element that follows
    the cursor". Promoted into the top layer on activation (drag's one real P1 dependency),
    `display: none` while idle so it is safe to park inside the source, positioned by the **grab
    offset** rather than centred on the cursor.
  - **`Drag` page in `cgui-gallery`** — chips, bins, ghost, highlight-on-enter, Escape to cancel.
  - **Visual-pass fixes** (three, all found by looking at it rather than by tests):
    1. Ghost rendered at the origin and tracked the cursor 1:1 — `screenToLocal` returns *absolute
       logical* coordinates, not an offset within the element, so subtracting `startX` was wrong. Now
       uses a grab offset captured at drag start.
    2. Ghosts rendered in-flow before the first drag; the controller only learns about one at
       `setGhost` (mouse-down), so the scene's ghost class now starts `display: none`.
    3. **`setHitTest(false)` now applies to the whole subtree**, matching CSS `pointer-events: none`.
       It previously skipped only the element itself, and children are tested *before* the parent's
       flag — so a pointer-transparent container with content was transparent everywhere except where
       its content was. Every prior user of the flag was a childless leaf, which is why it hid. It
       surfaced as drop targets lighting up only sometimes.

  > **Testing lesson, recorded because it cost three rounds.** Every one of these shipped past a green
  > suite, and two of the tests written *for* them still passed against the broken code — one because
  > the fixture dragged from an element at x=0 (where the absolute coordinate and the grab offset are
  > numerically identical), the other because the ghost was never actually under the pointer, and
  > because a ghost is positioned *after* the drop hit test within a tick, so it only sits under the
  > cursor from the second frame. **A test for a positional bug must be proven to fail against the old
  > code before it is trusted.** Both now are.
- **2026-07-29** — **P1 review + ownership cleanup.** Suite 531. Structural, not cosmetic:
  - **`UIElement` imported `ui.elements.Tooltip`** — an inverted dependency; core DOM knowing about a
    widget, when every widget depends on `UIElement`. Replaced by `Tooltip.attach(anchor, text)`,
    which also **removed a real bug**: the old `setTooltip` attached a *second* pair of hover
    listeners on every set/clear/set cycle. `UIElement` now has zero references to `ui.elements`.
  - **Extracted `TopLayer`** from `UIWindow` (~130 lines). `UIWindow` is already the runtime
    god-object; the list, Taffy reparenting, paint pass and hit walk are one cohesive thing that
    belongs together and not there.
  - `addInternalChild`/`removeInternalChild` widened to public — consistent with `markAsInternal()`,
    which already was, and what lets a popover attach itself without new API on `UIElement`.
  - `UIText` now reads `max-width` from the **live Taffy style** rather than the cascade, so the wrap
    bound cannot drift out of agreement with the box that gets measured.
  - Per-frame allocation removed from the top-layer paint snapshot; two dead imports dropped.
  - Docs updated (the standing principle: a change without its doc edit isn't done) — `AGENTS.md`
    frame lifecycle, widget table, internal-class list, registry count, events table, package map,
    three new load-bearing invariants; `docs/CGUI_WIDGETS.md` §10 `Tooltip`.
- **2026-07-29** — **P1 visual pass.** Four bugs found by looking at it, three of them *engine* bugs
  that the top layer only happened to expose. Suite 519.
  1. **`mouseenter`/`mouseleave` fired on the hit target only, never on entered ancestors.** They
     don't *bubble*, but the DOM still dispatches one per element in the entered/left chain — so any
     container with children never heard about the pointer. `:hover` already walked that chain, so
     CSS and listeners disagreed about what "hovered" meant. Fixed in `UIInputHandler`; guarded by
     `HoverChainTest`. **This affected every container in the engine, not just tooltips.**
  2. **`UIText` treated self-sizing as unbounded**, so `max-width` could only clip the box while the
     glyphs spilled out. It's CSS shrink-to-fit: `width:auto` + `max-width` wraps at the max.
     `UITextMaxWidthTest`.
  3. **Edge-clamping looked broken but wasn't** — it was reasoning about a box that no longer matched
     what was drawn, downstream of (2). Fixed by fixing (2).
  4. `max-width` belongs on the tooltip's **label**, not the tooltip box — UIText wraps against a
     width it can see on itself.

  Not a bug: the gap under an anchored tooltip is the anchor's own leftover box height
  (`.scroll-row` is 22px around ~13px of top-aligned text) plus `margin-top`. The tooltip anchors to
  the border box, as the web does.

  Also: two synthetic tooltip-level wrapping tests were **deleted rather than made to pass** — they
  asserted through two layers of widget and failed for reasons unrelated to the wrap decision. The
  behaviour is pinned at `UIText`, where it actually lives.
- **2026-07-29** — **P1 implemented.** Top layer + tooltip, 26 new tests, suite green at 510
  (416 `test` + 94 `headlessTest`). Ported from the spec/Blink rather than derived: Blink's
  `AddToTopLayer` naming, insertion-order stacking with z-index ignored, paint-order hit testing,
  imperative promotion. What implementation added on top of the research:
  - a **fourth** divergence (`getX()/getY()`, separate from `localToWorld`)
  - `display: none` for closed tooltips, or a closed one pads its anchor
  - placement must read the transform chain, since scroll never touches the layout box
  - scroll-following needs the ticker; `onLayoutChanged` alone can't see a scroll
  - `core`'s `test` source set was missing the FreeType bindings, so no test could lay out text
  Next: **P2**, the drag protocol. Its hard prerequisite (a top layer for the ghost) now exists.
- **2026-07-29** — **Chromium-fidelity directive added** (see Standing principles) and P1.1
  researched against the actual spec + Blink docs rather than memory. Net effect:
  - **Reversal:** promotion is *imperative* on the web (`showPopover()` / `showModal()`), not a CSS
    property. The plan had said the opposite. A `promote()`-shaped Java API is now the faithful
    choice — and it sidesteps a collision with our existing `overlay` drawable property.
  - **Confirmed:** insertion-order stacking with z-index ignored; containing block = ICB → Taffy
    reparent to root; immunity to ancestor clip/opacity/transform. All three already matched.
  - **Confirmed by Blink:** "hit testing is done in paint-order" → top-layer hit test is just the
    paint list walked backwards. No new traversal concept needed.
  - Divergences recorded (no `::backdrop`, single cursor, no nested contexts). Focus-trapping deferred
    to the HTML spec, which is where it actually lives — not needed for tooltips.
- **2026-07-29** — **High-effort review of this plan.** Three substantive corrections, all verified
  against source rather than reasoned about:
  1. **P1.1's central claim was wrong.** Promotion is not "purely a render-order concern" — a
     promoted element must diverge from its DOM parent in three places (Taffy parent, `localToWorld`,
     hit-test entry), with only the cascade staying put. Table + evidence now in P1.1.
  2. **`position: fixed` ≠ top layer.** They are distinct in CSS; conflating them would have been the
     un-web-like choice. Top-layer promotion is now the primitive, `fixed` a later nicety.
  3. **`AGENTS.md` had three stale external-reference paths** — LDLib2 is at `research_repos/LDLib2`
     (not `../LDLib2`), and Taffy sources *and* an MC 1.20.1 tree are checked in despite the doc
     saying neither exists. Fixed there and surfaced in Standing principles here. The Taffy sources
     directly de-risk P1.
  
  Also softened the P1→P2 spine edge: only the drag *ghost* needs the top layer, so P2 is not
  hard-blocked.
- **2026-07-29** — **P0.1 done.** Suite green at 484 tests (390 + 94 headless), 0 failures.
  Three drifted expectations reconciled, all traced to one untested commit. Next up: **P1.1**, the
  top layer — starting with the `position: fixed` vs. dedicated-promotion-API decision, since Taffy
  has no `fixed` and that choice shapes everything downstream.
- **2026-07-29** — Replaced the unordered draft with this plan. Prior groundwork landed:
  `CgUiPaintContext` singleton, `CgLifecycleListener` SPI + `CgUiLifecycle`, full `CgBatchRenderer`
  → `CgQuadRenderer` migration, `quad()` builder, `#pragma cg_use quad`, root `AGENTS.md` rewrite,
  and a staleness audit of all three `docs/CGUI_*.md`.
