# The UI rewrite — one plan over both audits

**Status: M0–M6 shipped (M6 on 2026-09-03). M7 is next, and is interleaved with `plan_fs_rewrite.md`'s F0–F7 into one flow — see M7 §7.A for the order.** This knits `plan_ui_network_audit.md` (the wire,
sessions, contracts, presentation) and `plan_engine_core_audit.md` (the three-tree engine) into one
ordered set of milestones. Each audit stays the reference for *why*; this document is the reference
for *what, in which order, gated by what*. Where the two audits' step lists disagreed on order, this
resolves it, and says how.

---

## 0. The knitting problem, and the seam that solves it

The network audit's first step is "the mirror with stable ids, on today's engine". The engine audit's
ninth step is "the networking rewrite as the mirror over the new node tree". Taken literally that
writes the mirror twice, and the second time is after months of engine work — so either the live
networking defects (identity changes never sent, forged events, one viewer silencing all) wait for
the engine, or the mirror is thrown away.

The resolution is a **seam**: the mirror observes a *tree contract*, not a class. Define that
contract first — stable node identity, ordered children, attribute/inline-style/state change
notifications, a contract per node kind — implement it *minimally* on today's `UIElement` (which is
Tier 2's "networking off the node"), build the mirror against it once, and have the new `ui.dom`
node tree implement the same contract natively when it lands. The mirror is written once; the
engine swap underneath it is a port of the seam's implementation, not of the mirror. Everything
else in both plans orders itself around that seam.

Two more principles this plan enforces:

- **Every milestone ships something usable in game and deletes something.** The user's condition is
  no bloat; a rewrite that only adds until a distant cutover is how two engines end up living side by
  side forever. The deletion ledger (§4) is checked per milestone.
- **Prove the mechanism before the design.** Two spikes (M0) are cheap and each could change the
  plan: the Taffy measure-function fix and the Shadow-DOM-in-Java prototype.

---

## 1. Decisions — **taken 2026-08-28: every recommendation below was accepted as written.**

D3 remains contingent on spike S1 by its own terms. The table is kept as the record of the reasoning.

| # | Decision | Recommendation | Why it blocks |
|---|---|---|---|
| D1 | **Seam-first interleave** (this plan) vs network-first-then-engine vs engine-first | Seam-first | The other two write the mirror twice or delay the live fixes by months |
| D2 | **Package strategy**: new `com.crystalgui.dom` / `render2`-style packages beside the old `ui/` during the port, old deleted at the end (strangler) — vs in-place mutation of `UIElement` | Strangler, with the old engine runnable until M6 ends | 76k lines port; in-place would leave the engine unrunnable for the whole duration |
| D3 | **Layout engine**: vendor the Taffy Java port into the repo and fix its measure path — vs replace with our own flex/grid | Vendor and fix, **contingent on spike S1** | If the fix is not tractable, the box tree's measure protocol has no engine under it |
| D4 | **Taffy's five CSS-default divergences** (`flex-direction: column`, `flex-shrink: 0`, `min-size: 0`, `align-content: flex-start`; `border-box` matches Taffy and stays) | Adopt CSS defaults at M6 while every UA sheet is being ported anyway, with a governance test that flags every element relying on the old default | Deciding at M6 rather than M5 means the box tree is written CSS-correct and the port pays once; deciding never means the 13 flex-trap rows stay forever |
| D5 | **Element naming**: registered namespaced names (`crystalgui-machine`, derived from the `UiType` id; widgets `crystalgui-button`) vs today's simple class names | Namespaced, with a client-side type → class manifest | N1/N2; also what makes two mods' `EnginePanel` not collide |
| D6 | **Identity scheme on the wire**: described elements only, server counter after open, live snapshot carrying ids for late viewers | As stated (Appendix A) | Fixed before the seam is written, since the seam exposes it |
| D7 | **Contracts**: static `State<T>`/`Event<T>` constants on the widget class registered beside its name, engine derives read/write/report/validate — vs annotations vs codegen | Static constants + registry (no build step, Java 8 bytecode, readable in a class file) | Every widget touches it twice (M1 on old widgets, M6 on new) so the shape must be settled |
| D8 | **Close veto**: `requestClose` → client asks content → answer; eviction is `RETENTION`, never `CLIENT` | As stated | Changes `WindowMount` and `ServerWindow.CloseReason` — public surface |
| D9 | **Rename `layout(M)` → `build(M)`; collapse `bound()`+`client()`** | Yes, at M3, in one commit with the panel migration | Public authoring surface; do once |
| D10 | **Hosts in scope**: 1.7.10 only during the rewrite; `mc1201` stays out | 1.7.10 only | Every milestone's in-game check is `runClient`/`serverSmoke` |
| D11 | **Template language** over the description format | No — door left open, not in this plan | Scope |
| D12 | **Test policy**: behaviour tests survive and are the acceptance; structure-asserting tests (child lists, internal children, positional ids) are rewritten at the milestone that changes the structure | As stated | Sets expectations for the 162 + 176 + 87 tests |

---

## 2. Milestones

Dependencies are listed as `after:`. Sizes are relative (S/M/L/XL), not dates.

### M0 — Spikes and the seam contract · ~~S~~ M · after: D1–D7 · **SHIPPED 2026-08-29**

> **Sized S, delivered M, and the overrun was findings rather than scope creep.** Both spikes turned
> up something the plan did not know, and the seam could not be added *beside* the engine: deleting
> `networkId` forces both sessions onto the source, which is what proves the seam carries load instead
> of sitting decoratively next to it. Read §2.0 below before M1.

#### 2.0 What M0 actually found

**S1 — the Taffy fix is real, and the defect was twice as large as recorded.** The invariant row said
a measured leaf wraps at the wrong width under `flex-wrap: wrap`. True, and measured: on a 200px row
of two `flex-grow: 1` text leaves, `nowrap` tells the measure function **100** and `wrap` told it
**200**, so the leaf reports a height for a width it was never given — its *width* stays correct, so
the symptom is clipped text and nothing that looks like a layout fault.

The second half was not known. The `isWrap ? NaN` branch was added **for** aspect-ratio items, and it
was destroying them: four of five AR shapes in a wrapping row came out **zero-high** where `nowrap`
gave the right answer. A narrowing fix ("suppress only for AR items") was written first and rejected
on that measurement. **Deleting the branch** is what three independent things agree on — CSS Flexbox
§9.4 step 7 measures with the *used* main size, upstream Rust Taffy passes it unconditionally, and
after deletion every single-line shape matches `nowrap` while the genuinely two-line one is unchanged.

**D3 is therefore settled: vendor and fix.** `taffy/` is a module, MIT, with `MODIFICATIONS.md` as the
statement of changes. The package stays `dev.vfyjxf.taffy` because `mc1710` already relocates it —
which is also what makes owning a fork safe, since a stock copy in another mod lands in a different
package instead of silently winning and taking the fix away. 165 call sites needed no edit.

Two things worth knowing before M5 plans around this engine: the mod jar is **half fastutil** (12,808
relocated entries, ~22MB of 48MB, for seven types) which is now a cheap win available at any time; and
`UIText`'s post-layout recompute is now a *choice* rather than a workaround — its javadoc said the
defect was "not something fixable without forking a third-party Maven dependency", and that is no
longer true. Retiring the recompute belongs with the box tree, not beside a layout fix.

**S2 — the Shadow-DOM shape holds, and the cost is one extra index lookup.** `::part(name)` is now a
real pseudo-element in the selector engine, and a shadow scope genuinely holds outer rules out: `* { }`,
`text { }` and `shadowbutton text { }` all fail to reach a shadow descendant while reaching straight
into a stock `Button`. The measured cost is that a `::part` rule is indexed under the **host's** type
and classes, so cascading a shadow descendant asks the rule index twice.

One finding changes how M6 ports the UA sheet: **inherited properties still cross the boundary**, as
they do on the web. So anything a widget wants to inherit needs no `part` at all, and only what must
be addressed *independently of the host* does — which is a much smaller set than the current
`__double-underscore__` census suggests. Focus retargeting composes through nesting and is what keeps
a `DataContext` walk from starting inside a widget's internals.

#### 2.0.1 The ledger, corrected

The M0 row of §4 was written before the work and got two of its four entries wrong. What was actually
deleted, and what was not:

| Planned | Actual | Why |
|---|---|---|
| `UIElement.networkId` | **deleted** | The number lives in `ElementTreeSource`'s table. Both sessions now address elements through a source they own |
| `UIElement.observer` | **the `UITreeObserver` interface deleted**; the field retyped to `TreeObserver<UIElement>` | The *state* could not leave the node: a mutation has to reach an observer without anything walking the tree, and the propagated-field pattern is what makes a grafted subtree report itself. What did change is that the field is the seam's and the stream is an **edit script** — insert-with-index, and a reparent is a `moved` |
| `UIElement.reportedEvents` | **not deleted** — surfaced through `NodeContract` | They are still per-*instance*: `ServerUiSession.on` adds them to individual elements and the client reads them back off the wire per element. Making them a per-**kind** declaration is exactly M1's contract work, and doing it here would have been M1 in M0's commit |
| `UIElement.describedChildren*` | **not deleted** — surfaced through `TreeSource.childrenOf` / `NodeContract` | Same shape: the *position* moved and the implementation delegates, so M1 changes one file rather than re-plumbing every consumer |

Deleted anyway, unplanned: `UITreeObserver` (the whole interface), `UITreeObserverTest` (ported to
`TreeObserverBehaviourTest`), and `NetworkIds.find`'s linear tree walk — now a map lookup.

#### 2.0.2 What shipped

| Artefact | Where |
|---|---|
| The vendored layout engine + its fix | `taffy/` — a **git submodule**, [`CrystalGraphics/taffy-java`](https://github.com/CrystalGraphics/taffy-java), whose history is deliberately two commits: pristine 1.1.4, then our change, so `git log -p` IS the diff against upstream. Plus `MODIFICATIONS.md` and `TaffyWrapMeasureTest` |
| The fix proven through the whole stack | `core/src/test/.../MeasureFuncUnderFlexWrapTest` — a real `MeasureFunc`, real font shaping, real window |
| `::part` in the cascade | `style/selector/CompoundSelector`, `style/StyleEngine` |
| The shadow prototype + its measurements | `ui/shadow/`, `ShadowEncapsulationTest`, harness scene `cgui-shadow-parts` |
| **The seam** | `ui/dom/` — `TreeSource`, `TreeObserver`, `NodeContract`, `ElementTreeSource` |
| The seam's acceptance suite | `TreeSourceContractTest` (headless, seam-pure — **repoint `sourceOver` at M5 and change nothing else**) |
| The thread assertion | `UiThread.require(what, treeOwner)`, `FrameThreadOwnershipTest` |

**Verified:** 1127 core tests + the full headless suite green (two failures pre-date this work and
reproduce against the original published Taffy artifact); `:mc1710:compileJava`; `:mc1710:shadowJar`
with the fork relocated and no unrelocated `dev/vfyjxf`; `:mc1710:serverSmoke` — all seven assertions,
including the headless description round-trip through the rewired sessions.

#### 2.0.3 The original M0 specification

Two throwaway probes and one interface.

- **S1 Taffy measure fix.** In the checked-in port, fix the flex-wrap cross-size `NaN` path
  (`FlexboxComputer.java:1469` per `UIText`'s note) and prove it with `UIText` on a real measure
  function under a `flex-wrap: wrap` ancestor in the harness. Outcome decides D3. If it fails, the
  box tree (M5) needs a different engine and the plan's size changes.
- **S2 Shadow-DOM-in-Java.** One composite (`Button`: label + icons) as node + shadow root + `::part`
  styling, rendered in the harness beside the old one, with focus retargeting and a `::part(label)`
  rule applied. Outcome decides whether the M5/M6 shape is right before 54 composites are ported.
- **The seam.** `ui.dom.TreeSource` (name provisional): stable node identity (`nodeId`, allocated by
  the observer, not stored positionally), light-DOM child iteration, an observer with
  `inserted/removed/moved/attributeChanged/inlineStyleChanged/stateChanged`, and `contractOf(node)`.
  Implemented on `UIElement` by moving `networkId`, `reportedEvents`, `observer`,
  `describedChildren*` **off the node** into an adapter — Tier 2 (a). Thread assertion at the same
  entry points — Tier 2 (c).

**Ships:** nothing user-visible. **Deletes:** the three networking fields on `UIElement`.
**Accepts:** S1 and S2 measured in the harness; the seam has a headless test suite that the old
engine passes and the new one (M5) must pass unchanged.

*(Kept verbatim as written on 2026-08-28. §2.0.1 records where it was wrong.)*

### M1 — Contracts on the widgets · M · after: M0 · **SHIPPED 2026-08-29**

#### What M1 delivered, and where it went beyond the spec

The spec said "all twelve state-carrying widgets". The port covers **all 87 widget classes**: 28
contracted, 59 explicitly local-only with a written reason, and the coverage test walks the classes so
there is no way to add an 88th without answering the question.

| | |
|---|---|
| The contract types | `ui/contract/` — `WidgetContract`, `State`, `Event`, `StateType`/`StateTypes`, `RatePolicy`, `WidgetContracts` |
| The twelve, ported | `Button` `Checkbox` `ColorSelector` `Dropdown` `ProgressBar` `Slider` `SplitView` `Switch` `Tab` `TabView` `TextField` `UIText` — every hand-written `writeState`/`readState` pair deleted |
| Beyond the twelve | `SearchField` `Dialog` `MenuItem` `Tooltip` `Popover` `Menu`, and **ten config controls** through one `ConfigControlContracts` factory — the largest group that could carry nothing, and the widgets a served settings panel is made of |
| The census | `ui/elements/WidgetCensus` — 59 classes, each with a reason falling under one of four headings |
| Acceptance | `WidgetContractCoverageTest` (6 assertions), `WidgetContractRoundTripTest` (18) |

#### The five that could not report now can

`Dropdown` (`select`), `TabView` (`select`), `ColorSelector` (`change`), `SplitView` (`value`), and
`Tab` — which gets `closeRequested` rather than the `selected` the spec named, because a tab has no
selection signal of its own (selection belongs to the strip, and `TabView.SELECT` reports it with the
index) while a close request is a thing a tab genuinely owns, and is the veto path M4 needs.

#### Three things the port found

- **`addReportedEvent` accepted anything.** A session could ask any element for any string; the request
  was recorded, written into the description, and the client's wiring hit a `default` arm that logged
  *"which this client cannot observe"* and carried on. It now refuses a kind the contract does not
  declare, so a widget that cannot report something can no longer be asked to.
- **"Can report" and "was asked to report" are different questions**, and collapsing them makes every
  client report everything its widgets are capable of. `getReportableEvents()` is the contract's;
  `getReportedEvents()` is the per-instance subset a session subscribed to. The latter is *still*
  per-instance, and M2 is where it stops being — the encoder that writes it is a context-free
  `Codec<UIElement>`, so today the element is the only place both halves can reach.
- **A slot needs a real getter.** Three slots were written with stub getters because the widget had a
  setter and no reader — which makes the state **write-only**: settable by a server, never written to
  the wire, looking declared and doing nothing. `MenuItem.isCheckable`/`getAccelerator` and
  `SearchField.getPlaceholder`/`isNotFound` exist because the coverage test found them.

#### Deliberately not done, with the reason

`ListView`, `TableView`, `TreeView` and `ArrayControl` are local-only, because a collection's contract
is its **rows** and rows have to be a *stream* — a count and a template from the server, `rows{from,to}`
from the client as it scrolls. That needs the mirror underneath it, so it is M7. A contract carrying
only the selection would describe a list whose contents never arrive, which is worse than saying
nothing.

#### The original M1 specification



`WidgetContract`: `State<T>` and `Event<T>` constants with type, default, validation and default
rate policy, registered with the element's namespaced name (D5, D7). The engine derives
`writeState`/`readState`, the description fields, report wiring and server-side validation from it.
All twelve state-carrying widgets get contracts; the five that could not report get their events
(`Dropdown.SELECTED`, `TabView.SELECTED`, `ColorSelector.CHANGED`, `SplitView.RESIZED`,
`Tab.SELECTED`); the missing kinds (`key`, `pointer`, `wheel`, `focus`/`blur`, `drag`/`drop`,
`contextMenu`, `closeRequested`) are defined, wired on the widgets that opt in.

**Ships:** no wire change yet; the old sessions read contracts through a shim so nothing breaks.
**Deletes:** every hand-written `writeState`/`readState` on the twelve widgets; `addReportedEvent`.
**Accepts:** a coverage test enumerates every `ui.elements.**` class and fails on one that is neither
contracted nor marked `LocalOnly` with a reason; contract round-trip tests per widget.

### M2 — The mirror · L · after: M0, M1 · **SHIPPED 2026-08-29**

#### What M2 delivered

| | |
|---|---|
| Stable ids (D6) | `ElementTreeSource` owns the table; `UIElement.networkId` is gone, so nothing can number an element positionally any more |
| The edit script | `net/TreeOps` — `insert`/`remove`/`move`, `ui/treeOps` replacing `ui/treeDelta`, `EnvelopeCodec.VERSION` 1 → 2 |
| The deltas that never travelled | attributes (`a`) and inline style (`y`) beside state (`s`), on both halves |
| Late viewers | `UIDescriptionCodec.encodeLive`/`decodeLive` — a reshaped window serves a description with ids written into it |
| Integrity | per-`insert` `base`+`count`, and `expectedElementCount` recomputed on every reshape |
| The mirror itself | `net/mirror/` — `ServerTreeMirror<N,T>`, `ClientTreeMirror<N,T>`, the `NodeMirror<N,T>` seam, `ElementNodeMirror`, `TreeOps` |
| Acceptance | `MirrorIdentityTest` (11), `MirrorIsEngineAgnosticTest` (7), `TreeOpsTest` |

#### The seam was widened, because it was missing the half a mirror needs

`TreeSource` declared identity's READING (`idOf`/`peekId`/`byId`) and not its LIFECYCLE, so a mirror —
whose whole job is to allocate a number when a subtree arrives and let it go when it leaves — had
nowhere to stand but the concrete class. `allocate`/`release`/`assignAt`/`resetIds` are now on the
interface, with `assignInDocumentOrder` and `describedCount` as defaults derived from `childrenOf`.
`ElementTreeSource` lost three methods nothing called.

#### Three defects the acceptance suite found, all of them silent

- **Identity never reached the client at all.** `onIdentityDirty` fired, the session collected the
  element into `dirtyIdentity`, and the flush **cleared that set without encoding it**. So
  `setEnabled(false)` on a live window was correct on the server, absent on the client, and produced no
  error anywhere; the same hole swallowed every class change and every inline style write. Encoding it
  was half the fix — the client had no branch to apply it either, so both ends were written blind.
- **A subtree added and removed in one tick still shipped a ghost insert.** An id is allocated at
  *flush* time, not at insert time, so that ops can hand a whole subtree one contiguous `base`+`count`
  rather than an id per node. `removed()` tested "does this have an id?" *above* the coalescing scan,
  answered no for a just-inserted element, and returned — leaving the `insert` standing with no
  `remove` to follow it. The client dutifully builds the subtree, so it shows as a row that outlives
  whatever briefly created it.
- **A late viewer was refused after any reshape.** `elementCount` was computed once at `open()`, so a
  window that had since gained a row described more elements than the count it was still quoting, and
  the joining client refused the whole window rather than one row of it.

#### Three deviations from the spec, each deliberate

- **`dirtyIdentity` was kept, not deleted.** The spec listed it under *Deletes*, on the reading that it
  was dead weight. It was the opposite: the set was right and the *flush* was wrong, so deleting it
  would have removed the record of what needed sending rather than the bug. It now encodes.
- **The mirror was EXTRACTED, and the sessions keep their names.** This was first recorded here as a
  deviation — *"the rename touches every call site for no behavioural gain"* — which misread the spec:
  `ServerMirror`/`ClientMirror` was an **extraction**, not a rename, and the extraction is what makes
  M5 cheap. Written inline, `ServerUiSession implements TreeObserver<UIElement>` held a concrete
  `ElementTreeSource`, so the mirror was pinned to this engine and §0's promise — *"the mirror is
  written once; the engine swap underneath it is a port of the seam's implementation, not of the
  mirror"* — was **false while every test passed**. `net/mirror/` now holds `ServerTreeMirror<N,T>`,
  `ClientTreeMirror<N,T>`, the `NodeMirror<N,T>` seam and `ElementNodeMirror` over today's tree; the
  sessions own one and keep their names, which really is the cosmetic half.
- **`shouldSuppress` stays.** The spec expected rate policy to replace it. It cannot yet: rate policy is
  declared per `Event` and is applied at M3, and `shouldSuppress` is doing a different job in the
  meantime — stopping a delta landing on a focused text field and resetting the caret. Removing it now
  would reintroduce that.

#### The original M2 specification

Appendix A of the network audit, built once against the seam: stable ids (D6), `insert/remove/move`
ops from the observer, attribute and inline-style deltas, state deltas from contracts, per-op
described-count integrity, live snapshot with ids for late viewers and hidden re-delivery, deltas
queued per viewer while hidden, `ui/treeOps` replacing `ui/treeDelta`, `EnvelopeCodec.VERSION`
bump. `ServerUiSession`/`ClientUiSession` are replaced by `ServerMirror`/`ClientMirror` (names
provisional) with the public method names kept where they were right.

**Ships:** the live defects fixed in game — disabling a button after open works; adding a row keeps
every sibling instance; hidden windows keep their instances; identity and inline style travel.
**Deletes:** `NetworkIds` (positional), `ui/treeDelta`, `dirtyIdentity`, `expectedElementCount` as a
global check, the `instanceof` switch in `wireReportedEvents`, `shouldSuppress`'s reason to exist
(rate policy replaces it).
**Accepts:** `assertSame` on the engine panel across a sibling insert; a server move keeps the
client instance; two viewers agree on every id after a reshape; a hidden-then-reshaped window comes
back with its instances; an idle window is silent; the mutation-checked count test.

### M3 — Events, validation, authoring surface · M · after: M2 · **SHIPPED 2026-08-30**

#### What has shipped

| | |
|---|---|
| Typed events | `io.on(el, Slider.VALUE_CHANGED, (ctx, v) -> …)` — `EventKind` deleted |
| §M3.P projections | the model→view direction, with its own review pass |
| **Viewer in every context** (S6) | `UiEventContext.viewer()`, plus `ctx.call(…)` and `ctx.setVisible(…)` |
| **Per-viewer visibility** (S7) | `setViewerVisible(peer, visible)`; structure to all, state to the watching |
| **Validation on dispatch** (S5) | sanitize what a gesture could produce, refuse and count what it could not |
| **Rate policy applied** | declared since M1, read by nothing until now |
| Per-connection refusal counter | per VIEWER, with a threshold that stops that viewer and not the window |

#### The merge D9 asked for, and what had to be fixed first

`bound()` and `client(io)` are now **one `client(io)`**, run on every bind. The first attempt declined
the merge as unsound in both directions, and that reading was half right and stopped too early:

- Run once and widget listeners are not re-attached after a re-describe.
- Run every time and wire methods re-register, which `MessageRouter` refuses.

Both true, and the conclusion — keep two hooks — was wrong, because **the status quo was already
broken**. `ClientWindows` said so in a comment: a re-describe builds *fresh panel instances*, so
handlers registered by the previous instance close over a panel that is now detached. They run, they
write widgets nothing draws, and nothing reports it. That was a recorded gap in `plan_ui_host.md`
worked around with a comment rather than fixed, and it is exactly what the merge exists to close.

The obstacle was the router refusal, and registrations do not have to *persist* — they have to be
*replaceable*. `ClientUiSession` now routes each wire method once and dispatches through a swappable
delegate, so re-running replaces the handler. **That is not a weakening of the duplicate rule**, which
exists to catch two different owners colliding on one name: within one session a repeated qualified
method is by construction the same panel rebinding itself, since nested panels are prefixed by ids
`ServerScope.attach` keeps unique.

#### Also deferred, with the reason

- **`call()` → `callViewer` only.** Not removed. It already refuses when there is more than one viewer
  and therefore no such thing as "the" client, so it is safe rather than ambiguous, and deleting it
  would make every single-viewer panel more verbose for no gain. What the viewer unlocked is the thing
  actually worth having: **`ctx.call(…)` answers the viewer that spoke**, which is what a handler
  almost always means.

#### The original M3 specification



#### M3.P — Projections: the model→view direction · **SHIPPED 2026-08-30, ahead of the rest of M3**

##### What shipped

| | |
|---|---|
| The engine | `ui/projection/` — `Projections` (declare, compare, write; `of`/`each`/`gatedBy`/`run`) and `AutoProjection` (the convention tier and its `Report`) |
| The surface | `ServerScope.project` / `projectEach` / `projectWhen` / `autoProject` / `bind` |
| When it runs | after the panel's `tick`, **before** `session.tick()` flushes, skipped while no viewer is watching — and **once before `open()` describes the tree**, which is what lets the seeding `mirror(model)` call be deleted rather than renamed |
| Contracts | `State.set`, `WidgetContract.primary()` + `Builder.primary(slot)`, declared on the eleven widgets that have one unambiguous meaning |
| Consumers | `MachinePanel` and `EnginePanel` migrated; both `mirror()` methods, both `tick()` overrides, the `dirty` flag and its model subscription all deleted |
| Acceptance | `ProjectionTest` (13), `ProjectionOverTheWireTest` (5) |

##### The review pass that followed, and what it changed

Shipped, then reviewed adversarially because it did not read as obvious — ten minutes to understand is
itself a finding. Six defects and one API mistake came out of that pass:

- **The API was not shaped like the thing it resembles.** `project(name, from, to)` had two invisible
  rules: name the projection so the automatic pass skips it, and declare it BEFORE that pass. Break
  either and a widget is written twice a tick by two projections that may disagree, the later winning.
  It is now `project(widget, State, from)` — **the same three-part shape as `io.on`**, so the pair reads
  as one idea pointing two ways, collisions are recognised by widget IDENTITY, and the ordering rule is
  gone. That symmetry is the thing that makes it explicable in a sentence.
- **`O(n^2)` in the list path** — `wanted.contains(...)` inside the removal loop, a quarter of a million
  comparisons per tick on a five-hundred-row list. A set.
- **Duplicate keys were silently destructive**: two items onto one element, so the list comes out
  shorter than the model and the ordering pass moves that element twice, the second undoing the first.
  A row vanishes, and it reads as a rendering bug. Refused loudly now.
- **Every row was re-applied every tick.** Now skipped when the model handed over a value-equal but
  DIFFERENT instance — and deliberately NOT when it is the same instance, because a mutable row object
  compares by identity and would answer "equal" for something whose fields just changed. Skipping there
  would freeze a row silently, which is the failure this whole thing exists to remove.
- **`bind` leaked the whole window.** It connected a listener to a model property and dropped the
  `Connection`; the model outlives the window, so the listener retained the widget and through it the
  tree, for good. Undone on close, with `Projections.close()` beside it.
- **`projectWhen` gated the wrong scope** — one set per window meant a nested panel's epoch silenced its
  parent's fields, last caller winning. One set per PANEL now.

##### Three things the build found before that



- **`autoProject` cannot guess which slot a widget means, and the plan had not said so.** `Slider`
  declares `MIN`, `MAX`, `STEP` and `VALUE` — all floats — so neither "the first slot" nor type
  matching disambiguates, and declaration order deliberately puts the range first because a value
  applied before its range is clamped against the old one. So a contract now **declares** its primary
  state and a widget without one is reported rather than assumed. Eleven have one; `Dialog`'s title,
  `SplitView`'s weights and `Popover`'s mode are not "what the widget is" and deliberately do not.
- **The convention was reading the ELEMENT's own fields.** The level walk ran to `Object`, so
  `UIElement.parent` and `UIElement.popoverInvoker` came back as unwired UI — the exact hazard
  `UiType.collect` stops at `Networked` levels to avoid, and the first draft's javadoc claimed walking
  every level was deliberate. `ServerScope` now passes the same boundary in; `ui.projection` cannot
  name `Networked` without inverting the dependency. **Found by reading the report**, which is what the
  report is for.
- **The report was a wall.** Seventeen lines on a real panel, fifteen of them buttons — honest, useless,
  and the kind of thing a reader learns to scroll past, taking the two lines that mattered with it. Now
  split by whether there is anything to act on: a field the model has **no accessor for** was never a
  candidate and is counted, not explained; a field whose accessor **exists** and still could not be
  wired is named with its reason. `MachinePanel` went from 17 lines to 1.

##### And one engine gap it exposed

`UIElement.addChildAt` **throws** on a same-parent move (`"Cannot add the same child twice"`), so the
tree could not express a reorder as a `move` — even though the wire has carried the op since M2 and
nothing had ever produced one. The guard stays, having caught three real double-parenting bugs
(`CrystalEditor`, `StatusBarView`, `DockGroup`), so a move is now spelled as one:
`UIElement.moveDescribedChildTo`, which reports `moved`. Without it a `projectEach` reorder would have
reached the client as destroy-and-rebuild of the row.

##### The original M3.P design

Every finding in the network audit runs **widget → wire**. Nothing in any of the three plans says how a
**model reaches a widget**, so it is hand-written: `MachinePanel.mirror(model)` writes every field into
every widget on every tick, and the panel author is responsible for remembering to. That works, and it
does not scale — not on cost, which is one `equals` per field, but on the three things below.

##### What actually goes wrong, and it is not performance

1. **A field you forget never updates, and the first value is right.** So it looks correct on open and
   freezes. This is not hypothetical: it is exactly how `ProgressBar` shipped, and the comment now
   sitting in `setFraction` is there because of it.
2. **Nested and composite models are walked by hand**, once per tick, per panel.
3. **Collections have no answer at all.** `ListView`/`TableView`/`TreeView` are local-only precisely
   because a collection's contract is its ROWS, and `mirror()` cannot express "these 40 items became
   41" — it can only re-set what it already has.
4. **A hidden window pays anyway.** `mirror()` runs from `tick()`, so a window nobody is watching still
   walks its whole model every tick.

##### The two questions, and only one of them is automatable

- *"Which model fields changed?"* — automatable, by reflection, dirty flags or snapshot diffing.
- *"Which widget shows which field?"* — **not automatable, by anyone.** React writes
  `<Slider value={m.throughput}/>`, LiveView writes `<%= @throughput %>`, Blazor writes
  `@bind-Value`, Unreal writes a `RepNotify` handler. Every production system requires one binding
  statement per displayed field, because that statement IS the UI design; there is nothing to deduce.

That is what makes this tractable rather than a research project: the mapping has to be stated, it is
stated ONCE, and it is the same line the author was already writing inside `mirror()`.

##### Three tiers, in the order a panel reaches for them

```java
// 1. EXPLICIT -- over any getter. The model is not touched: no Property fields, no annotations,
//    no interface, no rewrite. This is the tier a legacy model uses, and it is the default.
io.project(model::isRunning,  power::setChecked);
io.project(model::throughput, throughput::setValue);
io.project(() -> model.engine().coolant().level(), coolant::setFraction);   // nesting needs no feature

// 2. CONVENTION -- panel field name to model getter, over the reflection UiType.collect already does
//    (it derives every widget's CSS id from its field name; JavaFX's @FXML is the cited precedent).
io.autoProject(model);                       // wires what matches
io.project(model::label, status::setText);   // explicit always wins

// 3. OBSERVABLE -- no polling at all, for a model whose fields are the engine's own Property<T>.
io.bind(model.power, power, Checkbox.CHECKED);

// COLLECTIONS -- keyed, so add/remove/reorder become tree ops rather than a rebuild.
io.projectEach(model::items, list, Item::id, ItemRow::new, ItemRow::apply);
```

Evaluated once per tick by the engine, inside the flush the mirror already runs — and **skipped
entirely when no viewer is watching**, which is something `mirror()` in `tick()` structurally cannot be.

##### Two rules the tiers must obey

- **Tier 2 must SAY what it wired and what it skipped.** A convention that silently misses a field is
  the frozen-`ProgressBar` failure in a nicer hat, and the engine already has the rule for this: *live
  and inert look identical, so a capability that can be silently skipped must say it is on.* The report
  is at construction, once, naming every panel field with no match and every model getter with no
  widget.
- **A projection may not throw the frame.** A null anywhere in a chained getter is "skip", never an
  exception out of the tick — the same reasoning that made every delta apply per-entry and per-field.

##### For a genuinely large model

`io.projectWhen(model::version, …)` gates a whole projection set on a monotonically increasing epoch,
so an unchanged model costs ONE comparison rather than one per field. Unreal's `NetUpdateFrequency`
plus dirty tracking in spirit; free for any model that already has a revision counter, and unavailable
rather than wrong for one that does not.

##### Prior art, and why the other shapes were not chosen

| Shape | Who | Why not here |
|---|---|---|
| Re-render + diff | React, RN **Fabric**, **Blazor Server** | Cleanest authoring model there is, and it needs a tree allocation per tick plus a reconciler — and it fights the "panel IS an element with widget fields" design M1 and the host plan just settled |
| Compiled change tracking | **Phoenix LiveView** | The best answer of the five (statics sent once, dynamics keyed to the assigns they read) and it needs a template compiler; D7 ruled out codegen — no build step, Java 8 bytecode |
| Observable fields | **Unreal**, **Unity NGO**, **Godot 4**, JavaFX | Kept as tier 3, not as the default: it is invasive for a legacy model, which is the case that matters. `FFastArraySerializer`'s keyed per-item deltas are what `projectEach` is |
| Immutable snapshot diff | Elm, Redux+Immer | Needs the model to be persistent data structures. Available for free through tier 1 when it is |
| CRDT / OT | Yjs, Automerge, Figma | For MULTI-WRITER convergence. Not applicable: the server is the sole writer and clients send intents, so coherence is already structural |

##### Ships, deletes, accepts

**Ships:** a panel states its projection once and never writes a tick loop; a hidden window stops
walking its model; collections gain the row path `ListView` was held back for.
**Deletes:** `MachinePanel.mirror`, `EnginePanel`'s equivalent, and the per-tick model polling in both
`tick()` implementations.
**Accepts:**
- a projected field that changes reaches **every** viewer;
- **an unchanged tick sends nothing** — asserted on `InMemoryTransport.sent()`, never on state, because
  a traffic-free assertion is the only one that fails against the `setFraction`-notifies-unconditionally
  bug;
- a hidden window's projections are **not evaluated** (counted, not inferred);
- `autoProject`'s report names its misses — asserted directly, since silence is the failure mode;
- `projectEach`: an inserted row leaves every other row's instance untouched (`assertSame`, the M2
  assertion one layer up), and a reorder is a `move` rather than a rebuild;
- a null in a chained projection skips rather than throwing the frame.

#### The original M3 specification


Typed events on `ServerScope` (`io.on(el, Slider.VALUE, (viewer, v) -> …)`), rate policy in the
description, server-side validation by the widget's contract (disabled/inert/kind/payload), viewer
in every context, per-connection refusal counter with a threshold, per-viewer visibility, `call()`
→ `callViewer` only. The authoring rename (D9): `layout` → `build`, `bound()`+`client()` → one
`client(io)`, `closed(CloseReason)`. `MachinePanel`/`EnginePanel` migrated as the worked example.

**Ships:** forged events refused in game; two players on one machine attributed correctly.
**Deletes:** `UiEventKinds`, the string-kind `on(...)`, `UiEventContext`'s viewer-less shape,
`bound()`.
**Accepts:** NaN and out-of-range payloads clamped by the widget, disabled-element events refused
and counted, two-viewer attribution, the seven tear-out behaviours re-pinned as plain reparent
tests (they pass because M2 made reparenting free — no `Detached`).

### M4 — Windows, view commands, client-initiated open, sheets, limits · M · after: M2, M3 · **SHIPPED 2026-08-30**

#### What shipped

| | |
|---|---|
| View commands | `net/ViewCommand` (closed vocabulary), `ui/view`, `net/window/ViewCommands`, typed API on `ServerScope` |
| Close veto (D8) | `Networked.requestClose()`, `ui/requestClose` as a request, unanimity across viewers, wired to `WindowFrame.setDiscardGuard` |
| Sheets | `ScopedSheets` — selector rewrite scoped by type id, refcounted, released on close; `SheetSupply` pairs apply with release |
| Caps | `net/UiLimits` — windows, elements, description bytes, sheet size and count, inbound rate |
| Eviction | reports `RETENTION` rather than `CLIENT` |
| Visibility | reported when the whole compositor is suspended, which no window's own `onHidden` covers |
| **Client-initiated open (N10)** | `ServerWindows.openable(TYPE, resolver)` + `ClientWindows.requestOpen(TYPE, args, onGranted)`; the Machine example migrated off its hand-rolled notify |

All three N10 decisions implemented as taken: a **request** rather than a notify, openability declared
**server-at-registration** rather than on the type, and the reply carrying **success only** so the
window still arrives through the ordinary open path.

#### Two things deliberately not done, with the evidence

- **`WindowMount` keeps `contentReplaced`.** The spec listed it under *Deletes*; it is now **more**
  load-bearing than when that was written. Per-viewer visibility made a re-describe reachable — a
  returning viewer is re-served the live description — and without this the host keeps showing the tree
  that was replaced.
- **`openedBy` → `setOwnerWindow`**: `openedBy` exists nowhere in the codebase. Nothing to rename.

#### The original M4 specification



#### Client-initiated open (audit N10) — decided 2026-08-29

Folded in here rather than before M2, because refusing honestly needs the mirror's error plumbing and
doing it earlier would mean writing the refusal path twice. Three decisions, taken:

1. **A request, not a notify.** The client has to be able to learn it was refused. Today's hand-rolled
   idiom (`MachineExample`'s `machine/open`) is a notify whose comment says *"the window arriving IS
   the answer"* — fine while it always succeeds, and indistinguishable from a lost packet when it does
   not: the player presses the key and nothing happens, forever.
2. **Openability is declared server-at-registration**, not on the `UiType`. The same panel class may be
   openable in one context and not another, so putting it on the type makes a deployment decision into
   a property of a class.
3. **The reply carries success only.** The window itself arrives through the ordinary
   `ui/openWindow` path, so there is exactly one code path for "a window appeared" no matter who asked
   for it.

Shape:

```java
// Server, once. The resolver IS the authority: it gets the viewer, and null means refuse.
ServerWindows.openable(FurnacePanel.TYPE, (viewer, args) -> {
    BlockPos pos = readPos(args);                   // UNTRUSTED -- re-derive, never dereference
    if (!world.isBlockLoaded(pos)) return null;
    if (viewer.getDistanceSq(pos) > 64) return null;
    return furnaceAt(pos);
});

// Client
ClientWindows.of(connection).requestOpen(FurnacePanel.TYPE, args, result -> { ... });
```

Notes that have to survive into the implementation:

- **`args` is untrusted.** The resolver re-derives the model from it (a position, an id) and never
  accepts a reference. Same Rule-of-2 posture §4.8 takes for event payloads.
- **A refusal is an ordinary answer**, not an exception — `null` from the resolver.
- **Asking twice must not open twice.** `key(model)` already settles that once the open happens, so
  the resolver returning the same model is enough; no second mechanism.
- **The single-player trap belongs in the docs for this**, loudly: a client-initiated open almost
  always means opening a `GuiScreen`, and one that pauses the game stops the integrated server
  ticking, so the connection is never pumped and every call dies at its timeout. `doesGuiPauseGame()`
  must be `false`. It is invisible on a dedicated server, which is the configuration nobody tests the
  wire in.

#### The rest of M4

View commands (`focus`, `scrollIntoView`, `showDialog`/`hideDialog`, `openMenu`, `tooltip`,
`setTitle`, `setIcon`, `geometryHint`, `notify`, `requestClose`), the close veto wired to
`WindowFrame.discardGuard` (D8), eviction as `RETENTION`, visibility reported on `suspendDesktop`,
`openedBy` → `setOwnerWindow`, `WindowMount` shrunk. Sheets scoped by **selector rewrite** (the
interim until M5's native scopes) and refcounted by hash across windows. Caps: windows per
connection, elements and bytes per description and per sheet, cache sizes; rate limits per method;
capability negotiation at open.

**Ships:** a server can focus, title, dialog, notify and ask-before-close; **a client can ask for a
window and be told no**; sheets no longer leak or bleed; a hostile server or client cannot take the
other side down.
**Deletes:** `CgUiWindowMount.sheetSupply`'s global `addStylesheet`, `contentReplaced`, the version
refusal as the only skew handling.
**Accepts:** hostile-description fuzzing, skew fixtures (client missing a tag / a field), the sheet
refcount test (open twice, close twice, engine sheet list unchanged), eviction reason test, and for
N10: a refused request reaches the client AS a refusal (not a timeout), an un-declared type cannot be
opened by a client at all, and a resolver that re-derives from `args` is not fooled by a forged one.

### M5 — The three-tree engine core · XL · after: M0 (S1, S2), D2–D4 · **SHIPPED 2026-08-30**

> **SHIPPED 2026-08-30, in seven minor milestones — see `plan_m5.md` §4 for what each one found.**
> `ui.dom` (node tree, shadow roots, slots, retargeting, lifecycle, thread affinity per tree),
> the cascade SHARED behind `Styleable` with `@scope` proximity and an immutable `ComputedStyle`,
> `ui.box` (one Taffy tree per document, one-pass layout, hosts, mirrors, `Measurable`, world
> matrices composed never painted), `BoxPainter`, and `ui.service` (input with a mode stack and DOM
> propagation, one focus algorithm over navigation scopes, one motion service, freeze-not-detach).
> **`./gradlew :core:m5Acceptance`** is the run. Headless went 1622 → 1691.
>
> What it found, and none of it was visible from a design: the registry's initial for a margin, a
> padding and a border is `auto` (an auto margin CENTRES an absolutely positioned box, so every
> popup landed somewhere plausible and wrong); the document's box is a BLOCK container or a flex-row
> root stretches every child to the viewport; Taffy asks a measure function for MIN-content as well
> as max-content; a modal blocks the scope CONTAINING it, not its own; a skipped box is not a
> candidate but its children still are; and opening a modal changes what is hittable with no
> pointer movement and no frame. The last three were found by the focus and hit-test rows being
> written as tests, which is what that acceptance criterion was for.
>
> **Deliberately not ported, and named so it is not mistaken for done:** close watchers, light
> dismiss, `Dialog.pulse`, the drag ghost and `TransitionEngine` as the motion service's client —
> all widget-layer, all M6. The paragraph below is kept as the row was written.


> **Broken into seven minor milestones in `plan_m5.md` (2026-08-30) — read that, not this paragraph,
> before starting.** 5.0 the strangler line and skeleton · 5.1 the node tree · 5.2 the style pass
> re-hosted and scoped · 5.3 the box tree and one-pass layout · 5.4 paint and hit-test through boxes,
> in the harness · 5.5 the services · 5.6 acceptance and the porting guide. Ten decisions the row
> left open (package names, share-not-fork the cascade, names, attributes, slots, events, scopes, CSS
> defaults in the box tree, one Taffy tree per document, `TextNode` as the first measurable) are
> recorded there with recommendations. The paragraph below is kept as the row was written.

`ui.dom` node tree (identity, attributes, children, shadow roots, slots, composed iteration,
retargeting, lifecycle callbacks, thread assertion) implementing the M0 seam natively; the style pass
(cascade kept, `@scope` semantics, `font-size` inherits, rule-level invalidation on a bad selector,
immutable `ComputedStyle`); the `ui.render` box tree (Taffy under the box per D3, hosts, measure
protocol, one-pass layout, world matrices computed never painted, paint and hit-test over boxes);
services: input with a mode stack and DOM `stopPropagation`, one focus algorithm over navigation
scopes with `delegatesFocus`, one animation service, freeze-not-detach lifecycle. Built in new
packages beside the old engine (D2), driven by the seam's test suite and a headless `UIWindow`
equivalent, with the harness able to run either engine.

**Ships:** nothing user-visible until M6 — this is the one milestone that adds without shipping,
and it is why S1/S2 gate it.
**Deletes:** nothing yet (D2).
**Accepts:** the seam suite passes on the new tree unchanged — including
`MirrorIsEngineAgnosticTest`, which already proves the mirror runs over a non-`UIElement` tree, so the
networking half of this milestone is a `TreeSource` and a `NodeMirror` and nothing else; the 38 focus rows and the 20
hit-test rows become acceptance tests against the new services; layout runs in one pass on the
gallery's trees.

### M6 — The port · XL · after: M5 · **SHIPPED 2026-09-03** (6.0–6.10; see plan_m6.md §8 for the close-out)

> **Broken into minor milestones 6.0–6.9 in `plan_m6.md` (2026-08-31), after measuring the whole old
> engine.** Five corrections to the paragraph below are recorded there and should be read first: the
> sheet rewrite is a CLASSIFICATION (401 of 1,048 part selectors select a part under a part, which
> `::part()` cannot express — §1.1); the scope is ~96,500 lines, not `ui/elements` alone (§1.2); the
> networking sessions are on M6's critical path, not M7's (§1.3); 164 test files move with the widgets
> (§1.4); 32 tags match by the lowercase-class fallback and must register a `Name` (§1.5). The
> paragraph is kept as the row was written.


In dependency order, each step keeping the game runnable on the old engine until the last:
leaf widgets (Button, Checkbox, Switch, Slider, ProgressBar, UIText, TextField) as node + skin;
composites (ScrollerView, SplitView, TabView, Dialog, Popover/Menu/Dropdown, Tooltip,
ListView/TreeView/TableView, GraphView, CanvasView, the editor's host); the desktop (`WindowFrame` as
shadow + content slot, hosts for owned/pinned/top, freeze instead of detach, the animation service);
workbench, dock, chrome, editor widgets; every `ua/*.css` `__part__` selector → `::part()`; D4 applied
with its governance test; native `@scope` replaces M4's selector rewrite. Then the cutover: the
harness and `CgUiScreen` on the new engine, and the deletion.

**Ships:** the whole application on the new engine.
**Deletes:** old `ui/` core, `TopLayer`, `mirrored`, the internal-child flag and its 208 sites'
old form, `UIFrameTicker` as a widget interface, `importantPipeline` writes from the engine,
`WindowChrome`'s move-and-remember-the-flag, the four promotion special cases, the two coordinate
chains, `Disposer` as a second ownership tree, `UiThread` as a marker.
**Accepts:** every surviving behaviour test green; the invariants table pruned of every row that
no longer describes anything (the count is the metric).

### M7 — Networking completeness on the new engine · M · after: M4, M6 — **and interleaved with `plan_fs_rewrite.md` F0–F7 into one flow**

> **Fleshed out 2026-09-03**, after M6 closed and the filesystem audit (`plan_fs_rewrite.md`) produced
> its own milestones F0–F7. The two tracks cross at four points and every crossing puts the fs work
> first (§7.A below), so M7 is broken into 7.0–7.4 and slotted between the F milestones rather than
> run after them. **The table in §7.A is the order to implement in.** The paragraph M7 was first
> written as is kept at the end.

#### 7.A The one flow

| # | Step | Track | Why here |
|---|---|---|---|
| 1 | **F0** Foundations and the immediate fixes | fs | `Reply`/`Stream` in `core.async`, the three defect fixes, four cheap server fixes, the packages |
| 2 | **F1** The document model, headless | fs | Needs no wire; can run beside anything |
| 3 | **F2** The fs protocol: typed messages, paged answers, operation ids | fs | Settles the paged-answer shape (`fs/list` in pages over `Stream`) |
| 4 | **7.0** Row streams on the UI wire | M7 | Needs only M2's mirror and F0's `Stream`; written after F2 so `ui/rows` windows and `fs/list` pages are ONE paging idiom on the client, not two |
| 5 | **F3** The fs server: `fs.server`, `WatchHub`, `WorkspaceBinding` | fs | Per-server `WorkspaceService` on a connection attachment — what `ServerScope.workspace()` (7.1) hands out |
| 6 | **F4** The fs client: `Workspace` and its facades | fs | What `ClientScope.workspace()` (7.1) hands out |
| 7 | **F5** The workbench: `EditorService`, one key, session VERSION 7 | fs | The one open lane and the `Resource`-keyed session record that 7.1's networked tab lands on |
| 8 | **7.1** Workbench citizenship, and the workspace through the scope | M7 | A fourth `EditorInput` kind on F5's lane; restore by key through F5's record; `scope.workspace()` over F3/F4 |
| 9 | **7.2** Client-local children | M7 | Independent of fs; after 7.1 so its example is a local control on a served tab |
| 10 | **7.3** The remaining contracts; the provisional markers deleted | M7 | After 7.0 (the three collection widgets) and 7.2 (`ArrayControl`) |
| 11 | **F6** Documents that are not text; the notes example | fs | No bearing on M7 |
| 12 | **7.4** Inventories, logs, file lists; the two-process check | M7 | The proof: uses 7.0, 7.1 and F4 together |
| 13 | **F7 + M8** Probes and record, as one close-out | both | F7's docs step is M8's |

Built the other way round — 7.1 before F5 — the networked tab lands on the viewer lane and the
`CgPath`-keyed session record, both of which F5 deletes, and is ported a second time. 7.0 before F2
would mint a second paging shape. Those two constraints are the whole reason the tracks interleave.

#### 7.0 — Row streams on the UI wire · M · after: M2, F0, F2

**The mechanism the census has called "blocked on M7" since M1.** A list's contract is its rows, and
rows have to be a stream: the server holds the whole collection and describes only the **window** a
viewer is looking at; the client asks for `rows{from,to}` as it scrolls; rows arrive as ordinary
described subtrees keyed by a stable row key, so a row may hold a real `Button` that reports like any
other and an insert above the window shifts nothing (LiveView streams, RN `FlatList`, VS Code's tree
data provider — the audit's §4.4).

- **Server side.** `RowSource<T>`: `count()`, `rows(from, to)`, `keyOf(item)`, a change signal.
  `ServerScope.stream(list, source, create, apply)` beside `projectEach` — the row template is
  `create`, the per-run write is `apply`, as `Projections.each` already has them. The scope keeps
  **one window per viewer** and realises the UNION of viewer windows plus overscan as the list's
  described children; the mirror ships them as the inserts and removes they are. Rows are structure
  and structure goes to every viewer, so two viewers scrolled apart cost the union and nothing more —
  the one multi-viewer cost, bounded by viewers × window, and capped under `UiLimits`.
- **Wire.** `ui/rows{nid, from, to}` as a request on the interactive lane, debounced through
  `RatePolicy`. `COUNT` travels as state. A window whose `to` reaches `count` is **following**: the
  server slides it as rows append, which is what a log wants and what makes "scroll to the bottom and
  watch" cost nothing per line.
- **Client side.** `ListView` gains a `RemoteRows` model — an `ObservableList` whose size is the served
  count and whose `get(i)` answers the described row for `i` or a placeholder of `rowHeight` while the
  window is in flight; every row carries its index as an attribute, so a row received for another
  viewer's window is kept and not realised. `SELECTION` is by **key**, never index. Focus index stays
  local.
- **`TableView`**: columns (titles, widths, sort) as state, cells as row children, `SORT{column}` as an
  event the row source answers. **`TreeView`**: expansion is document state for a served tree, so the
  server holds it, `EXPAND{key}` is an event, and the row source is the flattened visible-row list
  carrying depth and `hasChildren` — the client draws it with the indent renderer it already has.

**Ships:** the Machine example's inventory as a streamed `TableView`, in game.
**Deletes:** the `ListView`, `TableView` and `TreeView` markers in `WidgetCensus` tier 4.
**Accepts:** `aTenThousandRowListShipsOnlyTheWindow` (count 10,000; described elements ≤ window +
overscan; bytes bounded); `scrollingSlidesTheWindowAndReleasesRowsBehindIt`;
`anInsertAboveTheWindowShiftsNoRow`; `selectionIsByKeyAndSurvivesAReorder`;
`twoViewersScrolledApartCostTheUnionAndNothingMore`; `aFollowedTailReceivesAppendsWithoutAsking`;
`aTreeExpandIsAServerEventAndTheRowsFollow`; `aRowButtonReportsLikeAnyOtherButton`.

#### 7.1 — Workbench citizenship, and the workspace through the scope · M · after: 7.0, F3, F4, F5

> **SHIPPED.** `Presentation` on `ui/openWindow`, `NetworkedPanels` as the workbench's `WindowMount`,
> `ClientScope.workspace()` / `ServerScope.workspace()`, and the Machine example declared
> `EDITOR_TAB`. Nine tests in `PresentationTest` (headless) and eight in `NetworkedPanelsTest`.
>
> **Three deviations, each recorded rather than quietly taken.**
>
> 1. **A networked tab is a `DockPanelRef`, not an `EditorInput`.** The plan said *"the fourth input
>    kind on F5's one lane"*, and the first three — a file, a library class, a generated source — are
>    all a `Resource` whose scheme decides where the bytes come from. `EditorService.open` reads
>    those bytes: `documents.open(input.resource())`. A networked panel has none, so making it an
>    `EditorInput` means a scheme whose provider answers a fake document, which is exactly the shape
>    that fails silently a milestone later. What "one lane" is protecting is that a tab is opened in
>    one place, and for a panel that is a `DockPanelRef` — which also buys the split, the drag, the
>    tear-out and the session record for nothing.
> 2. **A placement is declared with the resolver, never sent by the client.** The plan had
>    `requestOpen(type, {key})` and a `presentation` on the ref. Both are true, and the placement is
>    read from `openable(type, resolver, presentation)` instead: where a panel belongs is the mod's
>    statement about its own UI, and a restore holds no memory of how the window was presented the
>    first time — so a client that named one could ask for a tool window as a tab.
> 3. **The manifest is a session key of its own.** The plan expected the ref alone to carry enough.
>    It does not: the descriptor a ref decodes against is registered on first sight of a window of
>    that type, so on a fresh launch there is none and the ref is dropped before anything can ask.
>
> **Three defects found by building it**, none reachable before a server could open a workbench
> panel: `Workbench.othersEditing` NPE'd on a dock panel that is not a file, out of the active-panel
> signal inside the click; `ToolWindowManager.showPanel`'s docked branch dropped a show asked for
> before its region existed, while the windowed branch beside it remembered; and the replay hook for
> that was a one-shot covering only the frame the workbench joined a window.


**K5 closed.** A server can open a panel *as an editor tab* or *as a tool window*, and the workbench's
own dock, tear-out and session machinery applies to it — VS Code's `WebviewPanel`/`WebviewView`, the
port the audit named.

- **Presentation is a HINT on the open.** `ServerWindows.open(type, model, Presentation)` with
  `WINDOW | EDITOR_TAB | TOOL_WINDOW(region)`, carried on `ui/openWindow` and read through
  `ClientWindowContext.presentation()`. A host with no workbench mounts a window regardless; the
  server said what it would like, the client says what it has.
- **The mount routes.** The workbench installs a `WindowMount` that sends `EDITOR_TAB` to
  `EditorService.open(EditorInput.networked(context))` — the fourth input kind on F5's one lane, after
  file, library class and generated source — and `TOOL_WINDOW` to a `ViewContainer` view, and `WINDOW`
  to the desktop as today. `contentReplaced` swaps the tab's content; `closedByServer` closes the tab;
  the tab's close runs `mayClose` and reports `userClosed`; tearing the tab into a `DockWindow` moves
  the frame and touches the session not at all.
- **Restore by key.** A networked tab is a `DockPanelRef(typeId = the UiType id, {key, presentation})`
  in the VERSION 7 record. On the next connection the workbench re-asks the server through M4's
  `ClientWindows.requestOpen(type, {key})`; the tab shows F5's `LOADING` state until the window
  arrives, and a refusal drops the tab. A ref whose type has no manifest entry is dropped at read.
- **The workspace through the scope.** `ClientScope.workspace()` answers F4's `Workspace` and
  `ServerScope.workspace()` answers F3's `WorkspaceService`, both from the connection attachment, the
  viewer mapped to the actor. A panel that shows files reads them through the fs protocol and never
  re-ships a listing through the mirror. This is the crossing `plan_ui_host.md` deferred as "a
  client-side scope registry is additive later", and it is one method on each scope.

**Ships:** the Machine example openable as an editor tab and as a tool window, restored by key on
rejoin.
**Deletes:** nothing; closes K5.
**Accepts:** `aServerPanelOpensAsAnEditorTab`; `aServerPanelOpensAsAToolWindow`;
`aHostWithNoWorkbenchMountsAWindowRegardless`; `tearingOutANetworkedTabKeepsItsSession`;
`aNetworkedTabIsRestoredByKeyOnTheNextConnection`; `aRefusedRestoreDropsTheTab`;
`aPanelListsFilesThroughItsScopeNotTheMirror`.

#### 7.2 — Client-local children · S · after: 7.1

> **SHIPPED.** `UINode.markLocal()` plus `ClientScope.addLocal(parent, child)`; four tests in
> `LocalChildTest`.
>
> The plan's three properties are met by two mechanisms rather than one. Being undescribed is the
> filter in `describedChildren()`, which is what keeps a local child out of the encoding, the
> numbering and the integrity count. **Not shifting a described index is separate, and is
> structural**: `insertAt` keeps locals as the tail of the light list and refuses to put a described
> child past them, so index N means the same thing on both sides by construction. Maintaining the two
> lists in parallel instead is one subtraction that is right until somebody forgets it, and an insert
> landing one place out is a wrong picture rather than a failure.
>
> One thing found while writing it: `client(io)` re-running over the SAME tree — which a re-delivered
> `ui/openWindow` produces — would call `addLocal` again and double the viewer's controls, since the
> hook's contract is to be written as though nothing had been set up before. `ClientWindows` drops
> every local under the root before a re-bind.


`client(io)` runs over every build of the tree and its javadoc already says to write it as though
nothing had been set up before. What it cannot do is *add* anything: a child a panel appends locally is
in a described child list, so the next `insert` op lands one index off, and `Projections.each`'s
"nothing else may add children to `into`" is a rule with no legal way to obey it.

- `ClientScope.addLocal(parent, child)` is the one door: the child is owned by the panel instance, is
  never a **described** child (`describedChildren()` is what the mirror reads and indexes by), is
  re-added by `client(io)` on a re-describe like everything else there, and is invisible to the
  integrity count, which already sees described elements only.
- A local child follows served state through the served widget's own signal — a state delta runs the
  ordinary setter, which fires the ordinary signal — so no binding API is added.

**Ships:** a local copy-to-clipboard control on the log's served rows, needing no round trip.
**Accepts:** `aLocalChildSurvivesAReDescribe`; `aLocalChildNeverShiftsADescribedIndex`;
`aLocalChildIsNotCountedByTheIntegrityCheck`; `aLocalCopyButtonOnAServedRowNeedsNoRoundTrip`.

#### 7.3 — The remaining contracts; the provisional markers deleted · S · after: 7.0, 7.2

> **SHIPPED.** `ArrayControl.CONTRACT`, tier 4 deleted, and the three collection widgets rewritten
> into tier 2. Two tests in `WidgetContractRoundTripTest`.
>
> **The collections are DERIVED, not contracted** — which is what 7.0 settled rather than unblocked.
> A served collection is a `ServerScope.stream` on a CONTAINER: the rows arrive as ordinary described
> children, and the `ListView` a client builds over a `RemoteRows` around them is the client's own
> view of them. So the rows travel and the view does not, and that is the same reason `Configurator`
> is local. `ListView.describedChildren()` already said so from the other side. The two genuinely
> open halves — a table's columns, a tree's expansion — stay named in their reasons rather than
> pretended into a wire form nothing has asked for.
>
> **`ArrayControl` is the one control whose value type is not fixed by its class**, and that is what
> the old reason was really about rather than lists having no wire form (`stringListUnder` predates
> M7). An element descriptor says what one entry is and a `State` slot is declared once per kind, so
> entries cross as the text they read as and are coerced back by the element's own kind — inside the
> control, because only it knows what one of its entries is. Handing a number entry's text to
> `ConfigControls` yields `0`, silently, since it takes anything that is not a `Number` as zero: a
> list that arrives the right length and the wrong values.


- `ArrayControl` gets its contract: a list of values typed by its descriptor, which is a plain state
  slot now that a list has a wire form.
- Every tier 1–3 reason in `WidgetCensus` is re-read against the new engine. The verdicts expected:
  view state stays local (`Scroller`, `CanvasView`'s pan and zoom); derived stays local (`Configurator`
  family, `SymbolIcon`, the window pictures); the shell stays local; `ProjectFileTree` stays local and
  7.1's `scope.workspace()` is its honest wire form; `DockBannerBar` stays local because `notifyUser`
  is already the server's notice. A reason that no longer holds is rewritten, not deleted.
- Tier 4 — *blocked on a mechanism that has a name* — is deleted whole.

**Deletes:** `WidgetCensus` tier 4 and the four markers in it.
**Accepts:** the coverage test green with tier 4 gone; `anArrayControlRoundTrips`.

#### 7.4 — Inventories, logs and file lists; the two-process check · S · after: 7.1, 7.2, F4

> **SHIPPED, less the two-process run.** `StreamsPanel` in the Machine example — a streamed inventory
> with a Take button per row, a followed log, and a file list read through
> `io.workspace().files().list(...)` — attached over the whole model beside `EnginePanel`. Four tests
> (`aLogThatGrowsWhileFollowedShipsOnlyTheNewRows` with two viewers, plus three in
> `MachineExampleTest`), and `docs/CGUI_BUILDING_UIS.md` §7b says which shape is for what.
>
> **Built as containers rather than a `TableView` and a `ListView`.** The rows are what stream, and a
> `ListView` is the client's view of them — which is the same finding 7.3 recorded from the census
> side. A `TableView` here would have needed the columns half 7.0 deferred, and building it against a
> stream it does not own would have put the demonstration in the wrong place.
>
> **The two-process check with `runClient -PcgJoin` is NOT done** — it needs a person at two clients,
> which is the user's to run. The headless half measures what it can: bounded traffic, the follow, and
> a viewer above the tail keeping its elements.
>
> One test had to change: `MachineExampleTest.countElements` walked `children()`, which since 7.2
> includes the viewer's own controls — and the workspace column is exactly that. It counts
> `describedChildren()` now, which is the comparison the integrity check itself makes.


The three things the M7 row always promised, in the Machine example: the inventory (a streamed
`TableView` with a "take" button per row), a log (a streamed `ListView` following its tail), and a
file list read through `io.workspace().files().list(...)` into a local list — shown side by side so
the doc can say which shape is for what. Verified over a socket with `runClient -PcgJoin` and two
clients scrolled apart, and on the loopback probe for the numbers.

**Ships:** the three panels, in game, over a real socket.
**Accepts:** the M7 row's own two — a 10,000-row list over loopback with bounded traffic; a networked
panel docked, undocked and restored across a reconnect — plus
`aLogThatGrowsWhileFollowedShipsOnlyTheNewRows`, measured with two viewers.

#### What M7 does not do

Per-viewer **structure** (rows are structure, so a window is per viewer and its rows are shared);
a text filter on a row source (a `SORT` is one event the source answers; a filter is a search feature
and waits for one); `TextEditor` or `GraphView` on the wire (documents are the fs plan's, and the
two-formats objection in the census stands); the shell chrome; `mc1201` (D10); a display list.

#### The original M7 specification

Virtualised collections on the wire (`ListView`/`TreeView`/`TableView` rows as a stream with
`rows{from,to}`), workbench citizenship (a `DockPanelDescriptor` kind mounting a networked root into a
`ViewContainer`, session restore by key), contracts for the element classes M1 marked local-only
where a wire form now makes sense, client-local children in `client(io)`.

**Ships:** inventories, logs and file lists over the wire; a server panel as an editor tab or tool
window.
**Deletes:** the `LocalOnly` markers that were provisional.
**Accepts:** a 10,000-row list over loopback with bounded traffic; a networked panel docked, undocked,
restored across a reconnect.

### M8 — Hardening and docs · S · after: M7, and F7 of `plan_fs_rewrite.md`

One close-out with F7: its two probes and its docs step land here. Two-viewer fixtures for every server-side feature, `clientSmoke` (every contracted widget round-trips
over loopback), the networking primer and `CGUI_SERVER_AND_SERIALIZATION.md` rewritten to the new
protocol, `AGENTS.md`'s invariants table reduced to what still holds, `plan_ui_host.md` closed out.

---

## 3. Dependency view

```
D1–D7 ──► M0 (S1, S2, seam) ──► M1 (contracts) ──► M2 (mirror) ──► M3 (events, authoring) ──► M4 (windows, sheets, limits)
                  │                                                                                     │
                  └──────────────────────────► M5 (engine core) ──► M6 (port) ──────────────────────────┴──► M7 ──► M8
                                                                                                             ▲
   plan_fs_rewrite.md:  F0 ──► F1 ──► F2 ──► [7.0] ──► F3 ──► F4 ──► F5 ──► [7.1] ──► [7.2] ──► [7.3] ──► F6 ──► [7.4] ──► F7+M8
```

M1–M4 ran on the **old** engine through the seam and shipped live fixes early. M5 ran in parallel with
M2–M4 (different packages by D2). M6 was the single cutover. M7 needs both lines and is interleaved
with the filesystem track: the bracketed steps are M7's sub-milestones, in the one order §7.A gives.

---

## 4. Deletion ledger

The user's condition, checked per milestone. Nothing in this table survives the milestone that
names it.

| Milestone | Deleted |
|---|---|
| M0 | **Done, with corrections — see §2.0.1.** `UIElement.networkId` (deleted), the `UITreeObserver` interface (deleted), `NetworkIds.find`'s tree walk (now a map lookup). `reportedEvents` and `describedChildren*` were **surfaced through the seam rather than deleted**, because both are still per-instance and making them per-kind IS M1 |
| M1 | **Done.** hand-written `writeState`/`readState` ×12, the `instanceof` switch in `ClientUiSession.wireReportedEvents`, the per-instance reported-event set as the ANSWER to "what can this report". **`UiEventKinds` still exists and ten files still use it** — `EventKind` is a superset with the same string values, so the two agree, but two vocabularies for one thing is drift and M3 collapses them when it makes events typed. `addReportedEvent` survives as the per-instance *request* until M2 gives the mirror the description |
| M2 | positional `NetworkIds`, `ui/treeDelta`, `dirtyIdentity`, global count check, `wireReportedEvents`' switch, `ServerUiSession`/`ClientUiSession` internals |
| M3 | `UiEventKinds`, string-kind `on`, viewer-less `UiEventContext`, `bound()`, `layout(M)` |
| M4 | global sheet application, `contentReplaced`, VERSION-only skew handling |
| M6 | old `ui/` core, `TopLayer`, `mirrored`, the internal flag, `UIFrameTicker` interface, engine `importantPipeline` writes, `WindowChrome` flag juggling, promotion special cases, the second coordinate chain, `Disposer` as a tree, `UiThread` marker |
| M7 | `WidgetCensus` tier 4 whole — the `ListView`, `TableView`, `TreeView` and `ArrayControl` markers (7.0, 7.3); K5 closed (7.1). The fs track's own deletions are ledgered per milestone in `plan_fs_rewrite.md` §9 |
| M8 | invariants rows that describe nothing; `plan_ui_host.md` closed out; F7's docs step |

---

## 5. What "ready for implementation" means, concretely

Implementation can start the day D1–D7 are answered, with M0. M0 is deliberately small and
partly disposable: two spikes whose outcome can still change D3 and the shape of M5/M6, and one
interface that both lines depend on. Nothing after M0 should start before S1 and S2 have been run,
because the two things this plan cannot know from reading — whether the layout engine can be fixed
under us, and whether Shadow DOM in a Java 8-bytecode engine costs what the audit assumed — are
exactly what they measure.
