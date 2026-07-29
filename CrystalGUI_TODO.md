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

# P4 — Falls out of P1 + P2

### 4.1 Moving windows · `TODO`
Screen-space repositioning, correct across screen resizes. Mostly composition once drag exists.
`position: absolute` already parses (`TaffyPosition.ABSOLUTE`) and `ScrollerView` already uses it, so
there's a working precedent. This is the natural place to decide whether `position: fixed` is worth
adding for real — see the P1.1 note on why it is *not* the same thing as top-layer promotion.

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
**Genuinely unsized**, but no longer blocked — LDLib2 *is* checked in, at `research_repos/LDLib2`
(not `../LDLib2`, which `AGENTS.md` wrongly claimed and which does not exist; fixed 2026-07-29).
`TextElement` and a `TextElement$TextStyle` are confirmed present. Search `src/`, not `bin/` — the
latter holds compiled `.class` files. Deliverable is a short comparison note, not code; do it before
committing to any `UIText` work.

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
