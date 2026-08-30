# M5 — The three-tree engine core, as seven minor milestones

Detail for the M5 row in `plan_ui_rewrite.md` §2. Read this, not the row, before starting.

M5 is the first XL milestone and the only one that **adds without shipping**: it builds a second
engine beside the first — a node tree, a style pass over it, a box tree under it, and the four
services over both — in new packages, driven by the seam's own test suite, with the old engine
running untouched until M6 ports the widgets across. The row says that in one paragraph. This
document says what each part contains, what proves it, which decisions it settles, and in what
order, so that no minor milestone is started without knowing what "done" is.

Two things this document is not. It is not a re-argument of the audit: `plan_engine_core_audit.md`
§2–§11 are the evidence and §12 is the shape, and both are cited rather than repeated. And it is not
M6: nothing here ports a widget, a window or a stylesheet. The line between the two is that M5 ends
when a tree of plain nodes lays out in one pass, paints, hit-tests before it has painted, and passes
the seam suite unchanged — and M6 begins when the first `Button` is rebuilt on it.

---

## 0. Status — the minor milestones

The build order. Each is committed on its own, keeps every existing test green, and is sized against
the audit's numbers (§1 there: ~26,700 lines replaced, of which the engine's own core is `UIElement`
3,571 + `UIWindow` 1,707 + `UIInputHandler` 962 + `StyleEngine` 650).

| # | Minor milestone | Size | After | Proves | Status |
|---|---|---|---|---|---|
| 5.0 | The strangler line and the second engine's skeleton | S | — | The two engines cannot reach each other, and a test says so | **shipped 2026-08-30** — `8f6090fc` + harness `8f04e46` |
| 5.1 | The node tree | L | 5.0 | The seam suite passes on a tree that has never heard of `UIElement` | **shipped 2026-08-30** — see §4.1 notes |
| 5.2 | The style pass, re-hosted and scoped | M | 5.1 | The cascade is host-agnostic; scopes and `:root` inheritance work; the engine writes nothing into it | **shipped 2026-08-30** — see §4.2 notes |
| 5.3 | The box tree and one-pass layout | L | 5.2 | Layout of the gallery's trees runs to completion in **one** pass; hit-testing is correct before any paint | **shipped 2026-08-30** — see §4.3 notes |
| 5.4 | Paint and hit-test through boxes, in the harness | M | 5.3 | A fixed tree renders pixel-identically on both engines; the harness runs either | **shipped 2026-08-30** — see §4.4 notes |
| 5.5 | The services: input, focus, motion, lifecycle | L | 5.3 | The 38 focus rows and the 20 hit-test rows are tests, and pass; a frozen window costs nothing | **shipped 2026-08-30** — see §4.5 notes |
| 5.6 | Acceptance, the porting guide, the M6 handoff | S | 5.4, 5.5 | Every M5 acceptance criterion in one run; M6's first step is written down | **shipped 2026-08-30** — see §4.6 notes |

5.4 and 5.5 are independent of each other and both depend on 5.3. Everything else is a chain.

---

## 1. What M5 is gated on, and what the gates found

The row lists `after: M0 (S1, S2), D2–D4`. All three are settled; what they settled shapes this plan.

**S1 — the layout engine is ours and its measure path is honest.** `taffy/` is a vendored MIT fork
whose one change deletes the `isWrap ? NaN` branch, so a measure function is handed the *used* main
size under `flex-wrap: wrap`. That is what lets the box tree use Taffy's intrinsic-size protocol
(`setMeasureFunc`) instead of `UIText`'s post-layout recompute — which is the mechanism that made
`calculateLayout` a fixed-point loop. Twelve `TaffyTree` methods are all the old engine ever calls
(`newLeaf`, `addChild`, `insertChildAtIndex`, `removeChild`, `remove`, `containsNode`, `setMeasureFunc`,
`computeLayout`, `isDirty`, `markDirty`, `setLayoutChangeListener`, `disableRounding`), so the box tree
wraps the engine behind a surface that small.

**S2 — Shadow DOM in Java holds, and inheritance crosses the boundary.** `::part()` is a real
pseudo-element in the selector engine; a shadow scope holds outer rules out; the measured cost is one
extra rule-index lookup per shadow descendant. The finding that changes M5's node tree: **inherited
properties still reach into a shadow tree**, so a part is needed only for what must be addressed
*independently of the host*. `ui/shadow/ShadowRoot` is the prototype — built on the internal-child
flag it replaces, which is fine for a spike and exactly what 5.1 must not do.

**The seam.** `ui.dom.TreeSource` / `TreeObserver` / `NodeContract` — stable ids with a lifecycle
(`allocate`/`release`/`assignAt`/`resetIds`), light-DOM iteration, an edit-script observer, a contract
per node kind. Implemented today by `ElementTreeSource` over `UIElement`. Its acceptance suite is
`TreeSourceContractTest` (23 tests, seam-pure: *repoint `sourceOver` at 5.1 and change nothing else*),
`TreeObserverBehaviourTest` (13), and `MirrorIsEngineAgnosticTest` (7), which already runs the mirror
over a twelve-line `Node` class — so the networking half of M5 is a `TreeSource` and a `NodeMirror`
for the new tree and nothing more.

**D2 — strangler.** New packages beside the old ones; the old engine runnable until M6 ends. **D3 —
vendor and fix**, done. **D4 — CSS defaults at M6** for the *sheets*; §3 below records what that
means for the box tree written now.

---

## 2. Ground rules for every minor milestone

1. **The strangler line is a test, not a convention.** Nothing under the new packages may name
   `com.crystalgui.ui.UIElement`, `UIWindow`, `TopLayer`, `UIInputHandler` or `ui.elements.*`, and
   nothing under the old ones may name the new tree. Enforced as a bytecode scan of our own class
   files, the way `RunShellIsEngineNeutralTest` and `ExecutionNeedsNoGrammarTest` already do — a
   constant-pool reference is the real question, since a class that names a type at all can reach it.
   The scan is 5.0's first deliverable and every later milestone runs under it.
2. **The old engine is touched in exactly three places**, each a seam extraction rather than a
   change of behaviour, each keeping its 2,144 `ui/` tests and 80 `style/` tests green: the event
   types' target (5.1, §3 D5.6), the cascade's host (5.2, §3 D5.2), and the harness's choice of engine
   (5.0). Anything else that seems to need an old-engine edit is a sign the new engine is leaning on
   it, and is refused.
3. **The engine writes nothing into the cascade.** The style pass has no API for it. Placement,
   stacking, visibility, opacity, culling and animation are box properties (audit §3, 46 files at
   `IMPORTANT` today). A minor milestone that finds itself wanting `importantPipeline` has found a
   missing box property, and adds that instead.
4. **Thread affinity is asserted at the node tree's boundary** — every mutation entry on `Node`,
   `ShadowRoot`, `Slot` and `Document` — via the existing `UiThread.require(what, treeOwner)`, keyed
   per document. Not in setters; there are no setters below the boundary.
5. **Lifecycle callbacks run after a mutation completes, never during it.** Rows 36, 48, 78 and 281
   are each a widget building inside a callback that fired mid-mutation. The node tree queues
   `connected`/`disconnected`/`frozen`/`thawed` and dispatches them when the mutation that caused them
   has finished, in document order.
6. **Test policy is D12.** Behaviour tests are the acceptance and are ported as they are; tests that
   assert *structure* (child lists, internal children, positional ids) are rewritten at the milestone
   that changes the structure. For M5 that is only the seam suite and the new engine's own tests —
   the 2,144 widget tests belong to M6.
7. **Each minor milestone is one or more commits with its tests, and the ledger is checked.** M5
   deletes nothing (D2), so its ledger entry is what each milestone makes *describe nothing* — rows
   marked for M8 rather than removed.

---

## 3. Decisions the row left open

Each is stated with a recommendation and the reason it has to be decided before its milestone rather
than during it. **Decisions taken 2026-08-30 as recommended unless the status column says otherwise.**

| # | Decision | Recommendation | Why it blocks | Status |
|---|---|---|---|---|
| D5.1 | **Package names.** The audit says `ui.dom` for the node tree and `ui.render` for the box tree; `ui/dom/` already holds the seam, and `com.crystalgui.render` is the paint backend | `com.crystalgui.ui.dom` (node tree **beside** the seam it implements; `ElementTreeSource` stays until M6), `com.crystalgui.ui.box` for the box tree — *not* `ui.render`, which would sit one segment from the backend it draws through and be confused with it in every import list; `com.crystalgui.ui.service` for input/focus/motion/lifecycle | Every class written from 5.0 on lives somewhere; renaming packages later touches every file | recommended |
| D5.2 | **Share or fork the cascade.** `style/` is ~11,400 lines and exactly **7 files (54 references)** name `UIElement`: `StyleEngine` 21, `PseudoClasses` 13, `TransitionEngine` 9, `GeneralGroup` 4, `ElementStyle` 3, `StyleSheet` 3, `HighlightStyle` 1. Everything else — properties, values, selectors, sheets, slots, the two-winner-map logic, easings — is already host-agnostic | **Share.** Extract a `Styleable` seam from those seven (identity for the rule index, the eight pseudo-class predicates `PseudoClasses` binds by method reference, parent for inheritance, the candidate store, a dirty-match hook) and have both `UIElement` and the new `Node` implement it. A fork copies 11,000 lines to change 54 references and then fixes every cascade bug twice | 5.2 cannot start without knowing whether it writes a new `StyleEngine` or re-hosts this one; the seam touches the old engine, which rule 2 caps | **done**: `Styleable` extracted; nine files retyped, not seven — `StyleProperty`'s listeners and both selector classes named the element too |
| D5.3 | **Node names.** D5 says registered namespaced names | A `Name` value (`namespace:local`, a `ResourceLocation` shape) declared by the class and registered once; a subclass inherits its supertype's name unless it declares its own — the `Dropdown`/`ToolWindowFrame` row from both directions. `crystalgui:button`, `crystalgui:machine` (from the `UiType` id). Selector type matching is on the name; the codec writes it | The rule index, the codec and the contract registry all key on it from 5.1 | recommended |
| D5.4 | **Attributes.** The audit lists `enabled`, `inert`, `hit-test`, `focus-policy`, "arbitrary data keys" | **Typed keys**: `Attribute<T>` constants (`Attribute.ENABLED`, `Attribute.INERT`, …) on a per-node map, plus string attributes for what the codec carries. Keymap and settings scope become attributes looked up through the tree the way `DataContext` already walks, which retires two fields on the node | The seam's `attributeChanged` and the codec's `a` entry read from it; the focus service reads `INERT` and `FOCUS_POLICY` from it | recommended |
| D5.5 | **Slots.** How much of the slot spec | Named slots and one default slot, `slot=` assignment by name, fallback children when nothing is assigned, `assignedSlot` on the node, `assignedNodes()` on the slot, `slotchange` as a lifecycle callback. **Not** `manual` slot assignment — nothing in the 54 composites needs it | Composed-tree iteration is written against it in 5.1 and the box tree walks it in 5.3 | recommended |
| D5.6 | **Events.** `ui.event` is 8 files (365 lines + `EventListenerGroup`) and every type holds a `UIElement target` | **Generalise in place** over an `EventTarget` interface (`UIElement` and `Node` both implement); listener groups keyed on it. Duplicating the eight types would leave the drag controller, the keymap and every handler written twice | 5.1's retargeting and 5.5's dispatch need the types; this is old-engine touch #1 and must be a pure retype | **done** (`e2019d35`): 21 readers cast, one field retyped, every lambda unchanged |
| D5.7 | **Scope model for stylesheets** | CSS `@scope`: a sheet is installed *for* a subtree (a document, a window, a shadow root) with a root and an optional lower boundary; scoping proximity ranks between specificity and source order, per the spec. M4's `ScopedSheets` selector rewrite becomes a scope with the window's root as its root, and its dual-form emission (root + descendants) disappears because a scope root matches itself | 5.2 writes the cascade ordering once; adding proximity later re-sorts every candidate comparison | **done**: `StyleEngine.addStylesheet(sheet, root)`, proximity on `StyleSlot` |
| D5.8 | **CSS defaults in the box tree** — D4 says "adopt at M6 while every UA sheet is being ported" | **The box tree is written CSS-correct from its first line**: `flex-direction: row`, `flex-shrink: 1`, `min-size: auto`, `align-content: stretch`, `box-sizing: border-box`. There is no old sheet under the new engine to break, so the divergence rows never exist there; M6's port pays the sheet cost D4 already budgeted. Deciding otherwise means writing the defaults twice | 5.3's `BoxStyle` defaults and every layout test after it | **done**: `BoxStyle` writes the engine's defaults for anything unset — and had to for margin, padding and border too, whose registry initial is `auto` |
| D5.9 | **One Taffy tree per document, or per host** | One per document; a host is a *parent choice* when the box is inserted into the Taffy tree, not a second tree. Promotion, owned attachment, tear-out and thumbnails are all "this box's Taffy parent is that box's" — one `insertChildAtIndex`. A per-host tree would reintroduce the two coordinate chains as two layout results | 5.3's `Box.host` and every world-matrix computation | **done**: one `TaffyTree` per `Document`; `Box.setHost` is a parent choice; a mirror is a second box, not a second tree |
| D5.10 | **What the first `Measurable` is** | A minimal `TextNode` — shaped text under a measure function, painted as a run — written in 5.3 and painted in 5.4. It is the only way to prove the measure protocol against real shaping (the `MeasureFuncUnderFlexWrapTest` shape), and it is the seed M6's `UIText` port grows from | Without a real measurable, one-pass layout is proven against boxes with explicit sizes, which proves nothing about the loop that was removed | **done**: `TextNode implements Measurable`, shaped through `CgTextLayout`; `Measurable.Fit` distinguishes the min-content ask, which the old measure function never saw |

---

## 4. The minor milestones

### 5.0 — The strangler line and the second engine's skeleton · S · after: —

**Contents.**

- Packages created per D5.1: `ui.dom` (the node tree, beside the seam), `ui.box`, `ui.service`, each
  with a `package-info.java` stating what may and may not be imported.
- `EngineBoundaryTest` (headless): the bytecode scan of rule 2. Fails the build on any constant-pool
  reference from the new packages to `ui.UIElement`, `ui.UIWindow`, `ui.TopLayer`,
  `ui.input.UIInputHandler`, `ui.elements.*`, `ui.shadow.*` — and from `ui/` (root) into the new
  packages. Written before any class it could catch, so it is known to fail on a violation: its first
  version asserts against a deliberately planted import, then the plant is removed.
- The harness switch: `--engine=old|new` read by the scene base class, with the default `old`. In 5.0
  `new` boots an empty document and draws nothing; it exists so that 5.4 lands into a switch that
  every scene already honours rather than into a fork of the scene base.
- A headless `Document` fixture in the test source set — the equivalent of today's
  `UIWindow.updateWithoutPainting()`: style, tick, layout, no GL — as an empty class that 5.1–5.3
  fill in. Tests are written against it from 5.1 on.

**Touches the old engine:** the harness scene base (a flag). **Acceptance:** `EngineBoundaryTest`
green after the plant is removed; `runHarness --engine=new --mode=cgui-gallery` boots and exits
cleanly with nothing drawn; every existing test green. **Proves:** the two engines cannot reach each
other, and it is a test that says so.

### 5.1 — The node tree · L · after: 5.0

**Contents.** Audit §12.1, and nothing that belongs to §12.3.

- `Node`: parent, light children, `shadowRoot`, `assignedSlot`, `Name` (D5.3), id, classes, typed
  attributes (D5.4), the event listener groups (D5.6), a nullable `Box` reference, and the
  `Styleable` face 5.2 will fill. **No** geometry, Taffy id, world matrix, scroll offset, keymap,
  settings, network field or internal flag. Mutation entry points: `append`, `insertAt`, `remove`,
  `moveTo(parent, index)` — the last being the `moved` op the wire has carried since M2 and the old
  tree could not spell without `moveDescribedChildTo`.
- `ShadowRoot` (a node subtree that is not a light child; `delegatesFocus`; its own style scope) and
  `Slot` (D5.5), with `assignedNodes()`, fallback content, and `slotchange`.
- **Composed-tree iteration** (`composedChildren`, `composedParent`, a depth-first iterator over
  light + shadow via slots) as the walk layout, paint and hit-testing will read; **light-tree
  iteration** as what authors, the codec and the mirror see. `describedChildren`, `internal`,
  `markAsInternal`, `removeChildInternal` and `addDescribedChildAt` have no counterpart.
- **Retargeting**: `retarget(target, relativeTo)` per the spec's algorithm, used by events crossing a
  shadow boundary and by focus (`activeElement` seen from outside a host is the host).
- `Document`: the root, the id index, the observer slot, the frame-thread owner
  (`UiThread.require` at every mutation entry, rule 4), the lifecycle queue (rule 5) with
  `connected`/`disconnected`/`frozen`/`thawed` dispatched after the mutation, in document order.
- `NodeTreeSource implements TreeSource<Node>` natively: ids in a table on the source (as
  `ElementTreeSource` does today), light children as `childrenOf`, contracts by `Name` through
  `WidgetContracts`, the observer forwarded from the document. `NodeMirror<Node, T>` over the codec
  — description by name + attributes + light children, state by the node's contract.
- The codec seam: `UIDescriptionCodec` reads through `TreeSource.childrenOf` already (M0 §2.0.1); what
  5.1 adds is decoding into a `Node` by registered `Name`, behind the same `Codec` shape.

**Touches the old engine:** `ui.event` retyped over `EventTarget` (D5.6) — old tests unchanged.
**Acceptance:** `TreeSourceContractTest` with `sourceOver` repointed and **nothing else changed**
(23); `TreeObserverBehaviourTest` ported to run over both sources (13 ×2); `MirrorIsEngineAgnosticTest`
gains a run over `NodeTreeSource` beside its twelve-line fixture; new `NodeTreeTest`: composed
iteration through nested shadow roots and slots, default and named slot assignment, fallback content
appearing and disappearing, retargeting across two boundaries, `moveTo` reported as one `moved`,
lifecycle order (a child's `connected` after its parent's, `disconnected` before), a mutation from
inside a callback refused by the queue, a mutation from another thread refused by the assertion.
**Proves:** the seam suite passes on a tree that has never heard of `UIElement`, which is the
milestone's whole claim about networking.

#### 5.0 and 5.1 — what shipped, and where the plan was wrong

**5.0** shipped as written, with one honest deviation: the headless `Document` fixture came with 5.1
and the class it wraps, because an empty class is not a fixture. The harness `--engine` flag is
committed on the harness's own branch (`8f04e46`); master's submodule pointer was **not** moved,
because the checked-out harness has diverged from the recorded commit onto a line whose slot scene
needs a core branch master does not have — moving the pointer would have made master's harness
uncompilable against master's core. Reconciling the two lines is the user's, and 5.4 needs it done.

**5.1** shipped `ui.dom`'s `Name`, `Attribute`, `Node`, `ShadowRoot`, `Slot`, `Document`, `NodeRegistry`,
`NodeTreeSource`, and `net.mirror.DomNodeMirror`. Three things the plan said differently:

- *"Repoint `sourceOver` and change nothing else"* was not literally possible: the M0 suite built
  `UIElement`s directly. The honest version is `TreeSourceContract<N>` — the assertions verbatim,
  driven through a nine-method `Fixture` — with `TreeSourceContractTest` (elements) and
  `NodeTreeSourceContractTest` (nodes) as its two subclasses. Scaffolding is an internal child on one
  and shadow content on the other, which is the whole difference. The two tests needing a *widget*
  (a contract that reports; an element refusing to report what it cannot) stay with the old engine
  until M6.
- `TreeObserverBehaviourTest` was not generalised: nine of its thirteen tests exercise widget state
  (`Checkbox.setChecked`, `Slider.setValue`), which no node has yet. Its structural third is the shared
  contract's edit-script half. `MirrorIsEngineAgnosticTest` kept its twelve-line fixture and
  `MirrorOverNodeTreeTest` is the run over the real tree — seven tests, including that a shadow tree
  never travels and that a change inside one produces no traffic.
- There is no `Lifecycle` interface; the four callbacks are protected hooks on `Node` (custom
  elements' shape), because an interface would have made them public. And the composed walk is
  `composedSubtree()`, inclusive of its start, which is what a layout or paint pass wants.

Two findings for 5.2 and 5.3. **An insertion still names every node, parents first** (M2's rule),
reported from each node to its own effective observer — the first implementation reported the graft
root only, and the contract suite caught it. And **a move across a shadow boundary is a removal or an
insertion as the light tree saw it**, never a `moved`: the mirror describes light children only, so
what left the described tree left it.

### 5.2 — The style pass, re-hosted and scoped · M · after: 5.1

**Contents.** Audit §12.2 and §5.

- The `Styleable` seam extracted from the seven files (D5.2); `UIElement` implements it by
  delegation to what it already has; `Node` implements it natively. `PseudoClasses` binds to the
  seam's eight predicates instead of `UIElement::`.
- `NodeStyle` (the candidate store per node — `ElementStyle`'s two-winner-map logic behind the seam,
  not copied) and **`ComputedStyle`**: an immutable value produced per node per pass, every property
  answered (S6 — initial values are values), which is what paint and the box tree read. `getComputed`
  returning `null` for an unwritten property has no counterpart.
- **Scopes** (D5.7): `StyleScope` installed on a document, a subtree root or a shadow root; proximity
  in the cascade between specificity and order; a shadow root's sheets reach its tree and nothing
  outside, outer sheets reach its parts only through `::part()` (S2's mechanism, now the scope's).
- `:root { font-size }` inheriting (S2 of the audit: the universal `font-size` is what made `em`
  unusable on wrappers and forced the second rematch); the `FONT_SIZE` re-match listener retired on
  the new host.
- A bad selector invalidates its **rule** (S3), never the sheet.
- `TransitionEngine` re-hosted through the seam: transitions still write at `ANIMATION` origin and
  the diff still runs against `realSlots` — a `ComputedStyle` is produced per pass, so an in-flight
  transition is simply the pass's answer that tick.
- `::highlight()` carried across unchanged, as a side table keyed on the seam's identity.
- **No engine-write API.** There is no `importantPipeline` on `NodeStyle`; an author's inline style
  is the highest origin a caller can reach.

**Touches the old engine:** the seven files, retyped over `Styleable` — old-engine touch #2, and the
80 `style/` tests plus `StyleGovernanceTest` and `FontFaceTest` are the guard. **Acceptance:** the
cascade tests parameterised to run over **both** hosts; new `StyleScopeTest` (proximity beats source
order and loses to specificity; a lower boundary stops a rule; a shadow root's sheet is invisible
outside it; `::part` reaches in); `RootFontSizeTest` (`em` on a wrapper resolves against an inherited
size); `BadSelectorTest` (one broken rule, the rest of the sheet applied); `ComputedStyleTest` (no
property answers null; the value is immutable across the pass). **Proves:** the cascade is
host-agnostic, and the engine has no way to write into it.

#### 5.2 — what shipped, and where the plan was wrong

Shipped: `style.Styleable` (the seam), `style.ComputedStyle`, scopes with proximity, `:root`, the
per-rule catch, `ElementStyle`/`StyleEngine`/`TransitionEngine`/`PseudoClasses`/both selectors/
`StyleSheet`/`StyleSlot` re-hosted over the seam, `UIElement implements Styleable` by mostly already
existing, `Node implements Styleable` with a store, interaction state and a `PART` attribute, and
`Document.styles()`. `NodeStylePassTest` is the acceptance: twelve tests over the node host, and the
old engine's 80 style tests as the guard for the retype. Three corrections to the plan:

- **Nine files, not seven.** The census counted files naming `UIElement` in `style/` root, `sheet`
  and `transition`; `StyleProperty`'s listener interface and both selector classes name it too. The
  listener type was left as it is — `computedChanged` on the seam is where the old engine runs its
  listeners and the node tree does not, so `StyleProperty.notifyListeners(UIElement …)` is called by
  exactly one class and goes with it at M6.
- **The dirty set is drained parents first, and until settled.** Found by the `em` test: a hash set
  can match a child before its parent, so an `em` resolved against a font size the parent had not
  computed. Depth order is what a top-down recalc is, and a match that dirties descendants (a
  font-size change) is re-drained in the same pass, bounded at eight rounds. Both engines get this.
- **`ComputedStyle` is cached with the parent's snapshot as part of the key**, so an inherited value
  that moved above is seen below without a walk down — the alternative was invalidating a subtree per
  change, which is the cost the cache exists to avoid.

And one theme test changed its mind: `poisonedCssIsRefusedAtRegistration` pinned S3's defect (an
unknown pseudo-class refusing the whole theme); it now asserts the opposite, because a bad selector
costs its rule and nothing else, on both engines.

### 5.3 — The box tree and one-pass layout · L · after: 5.2

**Contents.** Audit §12.3, D5.8–D5.10.

- `Box`: the Taffy node id and its style (derived from `ComputedStyle` by one `BoxStyle` mapper —
  `TaffyBridge`'s listener-per-property has no counterpart), the layout result (`x/y/width/height`),
  the scroll offset, transform and origin, opacity, z-index, the paint order of its children (z
  descending, later-inserted-first on ties, as today), and the world matrices **computed from the
  box tree when layout completes — never written by paint.**
- `BoxTree`: created from the composed tree for every node whose computed `display` is not `none`,
  in composed order; kept in sync by the node tree's lifecycle callbacks (a `connected` node gets a
  box on the next pass; a `display: none` node loses its box and its subtree's). One Taffy tree per
  document (D5.9).
- **Hosts**: `Box.host` is the box whose Taffy child this box is. Default the composed parent; the
  root for a promoted box; an owner's overlay box for an owned dialog; a frame's content box for a
  torn-out fragment; a second box drawing the same node's subtree for a thumbnail. One
  `setHost(box)` — `TopLayer`'s five special cases, `attachOwned`'s reparent, `Detached` and
  `mirrored` have no counterpart. The containing block is the host, always.
- **The measure protocol**: `Measurable.measure(constraints) -> size` on a node's skin, wired to
  Taffy's `setMeasureFunc`; `TextNode` (D5.10) as the first implementor, shaping through the same
  `FontFamilyCache` and `CgShapedParagraph` the old `UIText` uses.
- **One-pass layout**: `Document.layout()` calls `computeLayout` once. There is no `while
  (isLayoutDirty())`, no `MAX_LAYOUT_PASSES`, and nothing a layout callback can write that re-dirties
  the tree. Geometry feedback that used to go through `IMPORTANT` candidates (`UIText`,
  `ProgressBar`) goes through `measure`.
- **Hit-testing over the box tree** in reverse paint order, from the world matrices layout computed:
  correct before anything has painted, and identical for a thumbnail's second box because it is a
  second box. `getWindowX/Y` and `localToWorld` collapse to one chain.
- The `Box` API the services will need: `scrollTo`, `setTransform`, `setOpacity`, `setZIndex`,
  `setHost`, `markLayoutDirty`, `hitTest(x, y)`, `worldToLocal`/`localToWorld`.

**Touches the old engine:** no. **Acceptance:** `OnePassLayoutTest` — the gallery's trees, rebuilt
as plain nodes with the same styles, lay out with `computeLayout` called **exactly once** and produce
the sizes the old engine settles to (the metric of the whole milestone); `MeasureThroughTaffyTest` —
a `TextNode` under `flex-wrap: wrap` measures at the used width (the S1 shape, now the engine's own
path); `HitTestBeforePaintTest` — a box tree that has never painted answers hit-tests correctly, with
a transform, with a scroll offset, and for a second box hosting the same subtree; `HostTest` —
promotion, owned attachment, tear-out and thumbnail as four calls to one method, containing block
following the host; `DisplayNoneHasNoBoxTest`. **Proves:** layout runs in one pass on real trees, and
hit-testing does not depend on having painted.

#### 5.3 — what shipped, and where the plan was wrong

Shipped: `ui.box` — `Box` (geometry, host, scroll, z/opacity/transform overrides, `localToWorld`,
`hitTest`), `BoxTree` (one Taffy tree per document; sync on a REPORTED structure change; restyle
by `ComputedStyle` identity; `computeLayout` once; read; compose), `BoxStyle` (the one mapper),
`Measurable` (+`Constraints`, `Size`, `Fit`), `TextNode` (the first measurable, shaped through
`CgTextLayout`), `Document.boxes()/layout()/update()/addStructureListener()`, `Node.box()` and a
`structureChanged()` call at every mutator. Twenty-one tests: `OnePassLayoutTest` (five trees on
both engines, geometry identical to 0.01px, one pass, no walk on an unchanged frame),
`HitTestBeforePaintTest`, `HostTest` (promotion, owned slot, stacking order, mirrors, `display:
none`, cross-tree refusal), `MeasureThroughTaffyTest` (a text leaf under `wrap` measured at 100 in
a 200 row, box as tall as the text wrapped at that width). `EngineBoundaryTest` now admits
`TaffyBridge` — its value conversions are what `BoxStyle` reuses; the listener path stays old-engine.
Four corrections to the plan:

- **The registry's initial values are not CSS's, and it is not only the five bridge defaults.**
  `MARGIN_*`, `PADDING_*` and `BORDER_*` all carry `LengthPercentageAuto.AUTO` as their initial —
  the old engine never noticed because its listener fires only for a candidate, so the bridge's own
  `ZERO` stood. Read through `ComputedStyle.get`, an unset margin came back `auto`, and an auto
  margin on an absolutely positioned box CENTRES it in its free space: every popup, panel and
  thumbnail in the first run landed somewhere plausible and wrong (a panel asked for `left: 200`
  laid out at 450). `BoxStyle` states CSS's initial for every one of them. D5.8's list was the
  bridge's divergences; the registry's are a second list, and the mapper is where both are settled.
- **The document's box is a BLOCK container, not a flex row.** CSS defaults made every node a flex
  row, the root included, and a root that is a flex row stretches each child to the viewport's
  height. The root is `display: block` unless a sheet says otherwise — which is what `<html>` is.
- **A measure function is asked two different questions, and the old one answered both the same
  way.** Taffy asks for min-content (the widest thing that cannot break) as well as max-content, and
  `MeasureFunc`'s `AvailableSpace` carries which. Answering min-content with one unbroken line makes
  a text leaf's minimum its whole line, so it can never shrink below it. `Measurable.Constraints`
  carries the `Fit`; `TextNode` wraps at 1px for it.
- **`Document.structureChanged()` could not be named that** — `Document extends Node` and the
  node's own hook is final. `fireStructureChanged` on the document, `structureChanged` on the node.

### 5.4 — Paint and hit-test through boxes, in the harness · M · after: 5.3

**Contents.** Audit §10 — the backend stays; the box tree records into it.

- `Box.paint(ctx)`: scissor from the box, opacity and mask through the existing layer-FBO path,
  `PoseStack` from the box's transform, then the node skin's `paintSelf`/`paintOverlay`/`paintOutline`
  hooks against `ComputedStyle`, children in paint order, the top-layer hosts last. Through the same
  `CgUiPaintContext` singleton — nothing in `render/` changes; its three references to `UIElement`
  are javadoc.
- The drawables (`CgUiQuad`, `CgUiRoundedRect`, `CgUiSprite`, `CgUiGradient`, `CgUiSvg`, `CgUiGlass`)
  bound from `ComputedStyle` as they are bound from `ElementStyle` today.
- `TextNode.paint` through `CgTextRenderer`, so 5.3's measurable is also the first thing on screen.
- The harness on `--engine=new`: `CgUiEngineParityScene` builds one fixed tree (nested boxes with
  backgrounds, borders, radii, an opacity layer, an `overflow: hidden` clip, a scroll offset, a
  transform, a text run, a promoted box and a thumbnail box of the same subtree) on **both** engines
  side by side and writes two PNGs; a headless test compares them within a tolerance. The old
  `cgui-snapshot-probe` established that a readback finds what six screenshots do not.
- `mirrored` has no counterpart: a thumbnail is a second box, and paint writes nothing that
  hit-testing reads.

**Touches the old engine:** no. **Acceptance:** `EngineParityTest` over the two PNGs; the harness
switch honoured by every UI scene (an old-engine scene on `--engine=new` says so and exits rather
than drawing the old tree); the scissor-balance assertion holding across the new paint pass.
**Proves:** the same picture from a tree that never wrote a matrix during paint, and a harness that
can run either engine — the row's stated condition.

#### 5.4 — what shipped, and where the plan was wrong

Shipped: `BoxPainter` — the pass over the box tree (background/rounded wrap/mask/overlay/outline
ported against `ComputedStyle`, layer-FBO opacity and mask through the same paint context, a square
clip as a scissor in box-local space), `Node.paintContent`/`paintDecoration` (the skin hooks; the
box model is the painter's), `TextNode.paintContent` through `CgTextRenderer` (5.3's measurable is
the first thing on screen), `BoxTree.paint(ctx)`/`Document.paint(ctx)`, the harness's
`cgui-engine-parity` scene (one spec + one stylesheet built on BOTH engines, alternating every 2s,
PNGs on frames 4/5), and `EngineParityTest` comparing the PNGs within tolerance — skipped with a
message when no GL run has written them, gating on the ENVIRONMENT and never the answer. Notes:

- **Each box paints with the pose set to `base × localToWorld`** — the matrix layout composed, so
  the picture and the hit test read one definition of where a box is, and `reconcileWorldMatrix`
  has no counterpart. A mirror paints exactly like any hosted box; `ctx.mirrored` is unused here.
- **The harness's `crystalgui` branch is entangled with core's `native-content-slots` branch**
  (`CgUiSlotScene`/`PlatformServiceHarness` import `ui.elements.slot`, which master's core does not
  have), so `:gl-debug-harness:compileJava` does not run against this tree. The parity scene was
  compiled in isolation with `javac` against core's classes to prove it is correct; running the
  scene and `EngineParityTest`'s comparison wait for the submodule reconciliation.
- **Promotion and a thumbnail are not in the parity scene** — the old engine's top-layer and
  `mirrored` paths would need scene-side plumbing of their own; hosting and mirrors are covered by
  `HostTest`'s hit-testing, and the visual half joins the scene at 5.6.

### 5.5 — The services: input, focus, motion, lifecycle · L · after: 5.3

**Contents.** Audit §12.4, §6–§9.

- **Input** (`ui.service.Input`): the raw `CgSystemInput` sink; hit-test the box tree; dispatch on the
  composed node tree with retargeting; three phases with **DOM** `stopPropagation` (a later listener
  on the same target still runs; `stopImmediatePropagation` exists and is the thing `TextEditor`'s
  row was about); pointer capture; the drag controller ported as a mode; a **mode stack** — drag,
  switcher, keyboard move/size, modal grab — consulted before dispatch, owned by whoever pushes the
  mode, replacing the hard-coded rungs of `consumeKeyboardEvent`; modified chords go to the keymap
  **before** content unless the target claims them (I6), so the yield lists disappear.
- **Focus** (`ui.service.Focus`): one algorithm over **focus navigation scopes** — document, shadow
  root (with `delegatesFocus`), dialog, window — with `FocusPolicy` kept as the one enum; `:focus`
  and `:focus-visible` as pseudo-class state on the node; modality as a property of a scope rather
  than four enforcement points; click-focus retargeted, so "focus is already in this window" is asked
  of the composed tree and the frame-itself exception has nothing to except.
- **Motion** (`ui.service.Animation`): one timeline service writing box properties — transform,
  opacity, scroll, and layout properties for a geometry animation — with `TransitionEngine` as its
  cascade-facing client and `WindowAnimation`'s from/to/duration/curve/completion shape as its API.
  The scheduler owns per-frame hooks and drops them on freeze; `UIFrameTicker` as a widget interface
  has no counterpart.
- **Lifecycle** (`ui.service.Lifecycle`): `freeze(node)` drops the box subtree, stops the subtree's
  hooks and marks it `frozen`; the node tree stays, session state is not captured because nothing
  is lost; `thaw` rebuilds the boxes on the next pass; `destroy` disconnects. Hide-as-detach has no
  counterpart, and neither do the eight rows that are its cost.
- The headless `Document` fixture gains `consumeMouseEvent`/`consumeKeyboardEvent` so the focus and
  hit-test rows can be driven at a point, which is the only way most of them can be seen.

**Touches the old engine:** no. **Acceptance:** the **38 focus rows and the 20 hit-test rows of
`AGENTS.md`**, each rewritten as a named test against the new services (the audit's own acceptance
list — e.g. *a focusable container is a wall* becomes `delegatesFocus`; *click-focus lands on the frame
before dispatch* becomes retargeting; *a press in content decides focus itself* becomes a scope
question); `ModeStackTest` (a drag eats Escape before a switcher, which eats it before a modal, with no
rung in the handler naming any of them); `StopPropagationTest` (DOM semantics, with the old
within-a-phase behaviour as the counter-assertion); `FreezeTest` (a frozen subtree paints nothing,
matches no selector, keeps its scroll offset and its text without any capture, and costs no ticks).
**Proves:** the rows are tests, and a retained window is frozen rather than detached.

#### 5.5 — what shipped, what is deliberately different, and what is NOT ported

Shipped: `ui.service` — `Input` (the platform sink, the hit test, three-phase dispatch over the
composed tree with retargeting, pointer capture, press/click detail, keyboard activation, the
cursor's `auto` rule, and the `Chords` keymap seam), `Mode` + the stack, `Drag` as a mode, `Focus`
(one owner, one traversal, one inertness predicate, scopes, modality, `delegatesFocus`),
`Animation` (timelines + node-owned per-frame hooks), `Lifecycle` (freeze/thaw/destroy),
`Document.frame(delta, w, h)`, `Box.scrollIntoView`, and `Node`'s interaction state, focus policy,
`consumesTextInput`, `claimsChord` and scroll. **58 tests**, each named for the invariant row it
pins. Headless went 1634 → 1691, all green.

**Five deliberate divergences**, each mandated by the audit: DOM `stopPropagation` (the old
within-a-phase behaviour is the documented `TextEditor` defect, and `StopPropagationTest` asserts
both); modified chords resolving BEFORE content unless the target claims them, so the yield lists
disappear; inertness as one predicate rather than four enforcement points; focus delegation through
`delegatesFocus` rather than a focusable container being a wall; and per-frame hooks OWNED by a node
rather than one-way `UIFrameTicker` registration.

**Three real bugs the row-tests found**, none of which a behavioural sketch would have caught:

- **A modal blocks the scope CONTAINING it, not its own.** A dialog is a focus scope itself, so
  asking `scopeOf(modal)` answered the dialog — which contains nothing outside it, so nothing
  anywhere was ever blocked and the document stayed hittable under an open modal.
- **A skipped box is not a CANDIDATE; its children still are.** Returning null on skip made a modal
  unreachable the instant it blocked the document it sits in — the pointer could not reach the one
  thing it was still allowed to touch. `hit-test` is the property that IS subtree-wide.
- **Opening a modal changes what is hittable with no pointer movement and no frame**, so the hover
  cache has to be told. Without it a press arriving between the modal opening and the next frame is
  answered from a hit resolved when nothing was blocked.

**Not ported, and named so it is not mistaken for done:**

| Deferred | Why |
|---|---|
| Close watchers, light dismiss, `Dialog.pulse` on a blocked press | All three are widget-layer (`Popover`, `Dialog`), and Escape's cascade needs those widgets to exist. M6. |
| `TransitionEngine` as the motion service's cascade-facing client | M6, when the cascade's clients move. |
| The drag GHOST | `DragGhost` is a `ui.elements` widget. M6. |
| `scrollExempt` children in the painter | A 5.4 gap: a scrollbar would scroll away with its content. |

`Document` deliberately does NOT implement the platform sink itself — `Input` does, and a host or a
test reaches it through `document.input()`. Giving the document a second identity as a raw event
sink is exactly what `UIWindow` avoided, and for the same reason.

### 5.6 — Acceptance, the porting guide, the M6 handoff · S · after: 5.4, 5.5

**Contents.**

- The M5 acceptance run, as one Gradle invocation the row names: the seam suite unchanged, the
  boundary scan, both engines in the harness, one-pass layout on the gallery trees, hit-test before
  paint, the focus and hit-test rows, engine parity.
- **The porting guide** — a section in `docs/CGUI_WIDGETS.md` (or its M6 successor) written from the
  new engine's actual API: how a widget becomes a node + skin (constructor builds the shadow tree;
  `__part__` becomes `part=`); how a `__part__` selector becomes `::part()`; how each of the fifteen
  `IMPORTANT`-write shapes becomes a box call or a `measure`; how a `UIFrameTicker` becomes a
  scheduler hook; how `markAsInternal` sites become slots. Written by porting **one** widget on paper
  against the guide and fixing the guide, not the widget — M6's first step is then the same widget for
  real.
- The invariants ledger: every `AGENTS.md` row M5 makes describe nothing is **marked** `(M5: no
  counterpart)` rather than deleted, so M8 has a list and readers of the old engine still have their
  rows.
- `plan_ui_rewrite.md` §2's M5 row updated with what shipped and what it found, the way M0's was.

**Touches the old engine:** no. **Acceptance:** the run green; the guide reviewed against one widget.
**Proves:** M5 is done by its own definition, and M6 has a first step.

#### 5.6 — what shipped, and the one deviation

Shipped: **`./gradlew :core:m5Acceptance`** (98 tests over 14 classes — the seam suite on both
trees, the boundary scan, the box tree, the services, one-pass layout on both engines, and the
engine-parity comparison, which SKIPS with instructions when no GL run has written its PNGs);
`docs/CGUI_ENGINE_PORTING.md`; twelve `AGENTS.md` rows marked `(M5: no counterpart.)` with one
explanation at the head of the table rather than twelve parentheticals; and
`plan_ui_rewrite.md`'s M5 row updated the way M0's was.

- **The guide is its own file, not a section of `docs/CGUI_WIDGETS.md`.** That document describes
  widgets that outlive the port; a guide whose entire subject is the difference between two engines
  rots the moment one of them goes. It is indexed from `AGENTS.md` and deleted whole at M8.
- **The paper port changed the guide five times**, which is what the exercise is for. The sharpest:
  the registry's factory is a `Supplier<? extends Node>`, so a widget whose only constructor takes
  its text does not compile as `Button::new` and the codec has nothing to build it with — the same
  no-arg requirement `ElementRegistry` always had, in a place nobody would look for it.
- **The rows are MARKED, never deleted.** The old engine ships until M8 and its readers still need
  every one of them; M8 needs the list of what it may take with it.
- The acceptance run is two `Test` tasks rather than one because M5's tests genuinely span two
  source sets — the box tree's two-engine comparison and the shaped-text measure need fonts and CSS,
  and everything else must keep proving it does not.

---

## 5. Dependency view

```
5.0 skeleton + boundary scan
 └─ 5.1 node tree ── seam suite repointed
     └─ 5.2 style pass ── Styleable seam, scopes, ComputedStyle
         └─ 5.3 box tree ── one-pass layout, hosts, measure, hit-test before paint
             ├─ 5.4 paint + harness parity
             └─ 5.5 input / focus / motion / lifecycle
                 └─ 5.6 acceptance + porting guide   (after 5.4 too)
```

5.4 and 5.5 can be worked in either order; neither reads the other. Nothing in M6 starts before 5.6.

---

## 6. What M5 does not do

Stated so that nobody expects it. No widget is ported — the leaf widgets are M6 step 5, and until
then the new engine draws boxes and text runs only. No `WindowFrame`, no desktop, no workbench. No
`ua/*.css` is touched: D4's sheet port and the `__part__` → `::part()` rewrite are M6. Nothing is
deleted: `ElementTreeSource`, `TopLayer`, `mirrored`, the internal flag and `UIFrameTicker` all stay
until M6's cutover, which is D2. `mc1201` stays out (D10). And the display-list model the audit's
§10 mentions is not built: the box tree records into the immediate-mode context as it is, and can
record into a display list later without changing the tree.

---

## 7. What "done" measures

| Metric | Today | M5 end |
|---|---|---|
| `computeLayout` calls to settle the gallery's trees | up to `MAX_LAYOUT_PASSES` (fixed-point over the cascade) | **1** |
| Hit-test correct before the first paint | no (`reconcileWorldMatrix` runs in paint) | yes |
| Coordinate chains per element | 2 (`getWindowX/Y`, `localToWorld`) | 1 |
| Places special-casing a promoted element | 9 + one `IMPORTANT` write | 0 (a host) |
| Engine writes into the cascade | 46 files at `IMPORTANT` | 0 (no API) |
| Motion mechanisms | 5 | 1 service + transitions as its client |
| Seam suite | passes on `UIElement` | passes on `Node`, unchanged |
| Focus / hit-test invariant rows that are tests | 0 | 58 |
| Constant-pool references across the strangler line | untested | 0, asserted |
| Engines the harness can run | 1 | 2 |

---

## 8. Risks, in the order they would be found

1. **One-pass layout with a real measurable is the claim most likely to be wrong in a way that looks
   right.** If `TextNode`'s measure under a wrapping ancestor needs a second pass for *any* gallery
   tree, the fix is in `taffy/` (as S1's was) and not in a loop — the loop is what M5 exists to remove.
   5.3's acceptance counts calls for that reason.
2. **The `Styleable` extraction is the one old-engine change with a large blast radius** (2,144 +
   80 tests). It is done as a retype with no behaviour change and committed on its own before any
   new host uses it, so a regression is attributable to the retype alone.
3. **Composed-tree iteration cost.** Every layout and paint walk crosses slots. S2 measured one extra
   index lookup for `::part`; 5.1 measures the composed walk against the light walk on the gallery's
   node count and records the number, so M6 is not surprised.
4. **The focus rows are 38 opinions, and porting them as tests will disagree with some of them.**
   Where a row records a workaround for the old structure (the frame-itself exception, the four
   modality points), the test asserts the *behaviour* the row protected, not the mechanism — D12.
5. **`ComputedStyle` immutability against transitions and `::highlight`.** Both write per pass; a
   pass produces a new value. The risk is allocation per node per frame, and the answer if it shows
   is a per-pass arena, not mutability.
